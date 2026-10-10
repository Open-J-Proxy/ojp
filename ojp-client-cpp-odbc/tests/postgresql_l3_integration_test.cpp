// Verifies PostgreSQL multi-block results and ODBC cursor lifecycle; BYTEA uses normal streaming.
#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct DatabaseConfig {
    std::string url;
    std::string user;
    std::string password;
};

DatabaseConfig read_connection_config(const std::string& path) {
    std::ifstream input(path);
    std::string line;
    if (!input || !std::getline(input, line)) {
        throw std::runtime_error("cannot read the PostgreSQL connection CSV");
    }
    std::vector<std::string> fields;
    std::string field;
    bool quoted = false;
    for (std::size_t index = 0; index < line.size(); ++index) {
        const char character = line[index];
        if (quoted) {
            if (character == '"' && index + 1 < line.size() && line[index + 1] == '"') {
                field.push_back('"');
                ++index;
            } else if (character == '"') {
                quoted = false;
            } else {
                field.push_back(character);
            }
        } else if (character == ',') {
            fields.push_back(field);
            field.clear();
        } else if (character == '"' && field.empty()) {
            quoted = true;
        } else if (character != '\r') {
            field.push_back(character);
        }
    }
    fields.push_back(field);
    if (quoted || fields.size() != 3 || fields[0].empty()) {
        throw std::runtime_error("expected JDBC URL, username, and password in PostgreSQL CSV");
    }
    return {fields[0], fields[1], fields[2]};
}

std::string brace_value(const std::string& value) {
    std::string escaped;
    for (const char character : value) {
        escaped.push_back(character);
        if (character == '}') {
            escaped.push_back('}');
        }
    }
    return "{" + escaped + "}";
}

void require_success(SQLRETURN result, const std::string& operation,
                     SQLSMALLINT handle_type = SQL_HANDLE_ENV, SQLHANDLE handle = SQL_NULL_HANDLE) {
    if (SQL_SUCCEEDED(result)) {
        return;
    }
    std::ostringstream message;
    message << operation << " failed";
    if (handle != SQL_NULL_HANDLE) {
        SQLCHAR state[6] = {};
        SQLCHAR detail[1024] = {};
        SQLINTEGER native_error = 0;
        SQLSMALLINT detail_length = 0;
        if (SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail,
                          sizeof(detail), &detail_length) == SQL_SUCCESS) {
            message << " [" << state << ", " << native_error << "] "
                    << reinterpret_cast<const char*>(detail);
        }
    }
    throw std::runtime_error(message.str());
}

void expect(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

SQLCHAR* sql_text(const std::string& sql) {
    return reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str()));
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void close_statement(SQLHSTMT statement, const std::string& operation) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), operation, SQL_HANDLE_STMT, statement);
}

void verify_column(SQLHSTMT statement, SQLUSMALLINT column, const std::string& expected) {
    SQLCHAR name[64] = {};
    SQLSMALLINT name_length = 0;
    SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
    SQLULEN column_size = 0;
    SQLSMALLINT decimal_digits = 0;
    SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
    require_success(SQLDescribeCol(statement, column, name, sizeof(name), &name_length,
                                   &data_type, &column_size, &decimal_digits, &nullable),
                    "SQLDescribeCol", SQL_HANDLE_STMT, statement);
    expect(name_length == static_cast<SQLSMALLINT>(expected.size()) &&
               std::string(reinterpret_cast<const char*>(name)) == expected,
           "unexpected PostgreSQL column label: expected " + expected);
}

void verify_metadata(SQLHSTMT statement, const std::string& second_label) {
    SQLSMALLINT column_count = 0;
    require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols",
                    SQL_HANDLE_STMT, statement);
    expect(column_count == 2, "expected two columns in the PostgreSQL result set");
    verify_column(statement, 1, "stream_id");
    verify_column(statement, 2, second_label);
}

SQLINTEGER read_id(SQLHSTMT statement) {
    SQLINTEGER id = 0;
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &id, sizeof(id), &length),
                    "SQLGetData(id)", SQL_HANDLE_STMT, statement);
    expect(length == static_cast<SQLLEN>(sizeof(id)), "unexpected ID length or NULL ID");
    return id;
}

void verify_exhausted(SQLHSTMT statement) {
    for (int attempt = 0; attempt < 3; ++attempt) {
        expect(SQLFetch(statement) == SQL_NO_DATA,
               "repeated fetch past the PostgreSQL result end did not return SQL_NO_DATA");
    }
}

void verify_text_rows(SQLHSTMT statement, SQLINTEGER expected_rows) {
    verify_metadata(statement, "RowLabel");
    SQLINTEGER count = 0;
    while (true) {
        const SQLRETURN result = SQLFetch(statement);
        if (result == SQL_NO_DATA) {
            break;
        }
        require_success(result, "SQLFetch(text)", SQL_HANDLE_STMT, statement);
        ++count;
        expect(count <= expected_rows && read_id(statement) == count,
               "PostgreSQL streamed IDs were missing, duplicated, or out of order");
        SQLCHAR label[64] = {};
        SQLLEN length = 0;
        const SQLRETURN data_result = SQLGetData(statement, 2, SQL_C_CHAR, label, sizeof(label),
                                                &length);
        require_success(data_result, "SQLGetData(label)", SQL_HANDLE_STMT, statement);
        const std::string expected = "POSTGRESQL_ROW_" + std::to_string(count);
        expect(data_result == SQL_SUCCESS && length == static_cast<SQLLEN>(expected.size()) &&
                   std::string(reinterpret_cast<const char*>(label)) == expected,
               "PostgreSQL streamed text was NULL, truncated, or incorrect");
    }
    expect(count == expected_rows, "expected " + std::to_string(expected_rows) +
                                      " PostgreSQL rows, received " + std::to_string(count));
    verify_exhausted(statement);
}

void verify_binary_rows(SQLHSTMT statement, SQLINTEGER expected_rows) {
    verify_metadata(statement, "BytePayload");
    const std::vector<std::vector<SQLCHAR>> payloads = {
        {}, {0x00, 0x01, 0x7f, 0x80, 0xff}, {0xfe, 0xdc, 0xba, 0x98}, {}};
    SQLINTEGER count = 0;
    while (true) {
        const SQLRETURN result = SQLFetch(statement);
        if (result == SQL_NO_DATA) {
            break;
        }
        require_success(result, "SQLFetch(BYTEA)", SQL_HANDLE_STMT, statement);
        ++count;
        expect(count <= expected_rows && read_id(statement) == count,
               "PostgreSQL BYTEA rows were missing, duplicated, or out of order");
        SQLCHAR payload[16] = {};
        SQLLEN length = 0;
        const SQLRETURN data_result = SQLGetData(statement, 2, SQL_C_BINARY, payload,
                                                sizeof(payload), &length);
        require_success(data_result, "SQLGetData(BYTEA)", SQL_HANDLE_STMT, statement);
        if (count % 4 == 0) {
            expect(length == SQL_NULL_DATA, "expected SQL_NULL_DATA for NULL BYTEA");
        } else {
            const auto& expected = payloads[count % 4];
            expect(data_result == SQL_SUCCESS && length == static_cast<SQLLEN>(expected.size()) &&
                       std::equal(expected.begin(), expected.end(), payload),
                   "PostgreSQL BYTEA length or contents mismatch (including empty BYTEA)");
        }
    }
    expect(count == expected_rows, "PostgreSQL did not return all BYTEA rows");
    verify_exhausted(statement);
}

std::string text_query(const std::string& upper_bound) {
    return "SELECT n AS STREAM_ID, 'POSTGRESQL_ROW_' || n::text AS \"RowLabel\" "
           "FROM generate_series(1, " + upper_bound + ") AS numbers(n) ORDER BY n";
}

void verify_first_row(SQLHSTMT statement) {
    verify_metadata(statement, "RowLabel");
    require_success(SQLFetch(statement), "SQLFetch(partial result)", SQL_HANDLE_STMT, statement);
    expect(read_id(statement) == 1, "the first PostgreSQL streamed row had the wrong ID");
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error("expected PostgreSQL CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the PostgreSQL L3 suite\n";
        return 77;
    }
    if (enabled != "true" && enabled != "1" && enabled != "yes") {
        throw std::runtime_error(enable_variable + " must be true or false");
    }
    const char* endpoint_value = std::getenv(endpoint_variable.c_str());
    const std::string endpoint = endpoint_value == nullptr ? "" : endpoint_value;
    if (endpoint.empty()) {
        throw std::runtime_error(endpoint_variable + " is required when " + enable_variable + "=true");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    constexpr SQLINTEGER total_rows = 10001;
    const std::string streamed_query = text_query(std::to_string(total_rows));
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                       reinterpret_cast<SQLHANDLE*>(&connection)),
                        "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";P" "WD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, streamed_query);
        verify_first_row(statement);
        close_statement(statement, "SQLFreeStmt(partial result)");
        execute_direct(statement, streamed_query);
        verify_text_rows(statement, total_rows);
        close_statement(statement, "SQLFreeStmt(streamed result)");

        execute_direct(statement, streamed_query);
        verify_first_row(statement);
        require_success(SQLCloseCursor(statement), "SQLCloseCursor(partial result)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement, text_query("0"));
        verify_text_rows(statement, 0);
        require_success(SQLCloseCursor(statement), "SQLCloseCursor(empty result)",
                        SQL_HANDLE_STMT, statement);

        require_success(SQLPrepare(statement, sql_text(text_query("CAST(? AS INTEGER)")), SQL_NTS),
                        "SQLPrepare(streamed query)", SQL_HANDLE_STMT, statement);
        SQLINTEGER upper_bound = 3;
        SQLLEN bound_length = sizeof(upper_bound);
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER,
                                         0, 0, &upper_bound, sizeof(upper_bound), &bound_length),
                        "SQLBindParameter(upper bound)", SQL_HANDLE_STMT, statement);
        for (const SQLINTEGER rows : {SQLINTEGER{3}, total_rows}) {
            upper_bound = rows;
            require_success(SQLExecute(statement), "SQLExecute(reused query)",
                            SQL_HANDLE_STMT, statement);
            verify_text_rows(statement, rows);
            close_statement(statement, "SQLFreeStmt(prepared result)");
        }
        require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(parameters)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement,
                       "SELECT n AS STREAM_ID, CASE n % 4 "
                       "WHEN 0 THEN NULL::bytea WHEN 1 THEN decode('00017f80ff', 'hex') "
                       "WHEN 2 THEN decode('fedcba98', 'hex') ELSE decode('', 'hex') END "
                       "AS \"BytePayload\" FROM generate_series(1, " +
                           std::to_string(total_rows) + ") AS numbers(n) ORDER BY n");
        verify_binary_rows(statement, total_rows);
        close_statement(statement, "SQLFreeStmt(BYTEA result)");

        execute_direct(statement, streamed_query);
        verify_first_row(statement);
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement),
                        "SQLFreeHandle(active statement)", SQL_HANDLE_STMT, statement);
        statement = SQL_NULL_HSTMT;
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(replacement statement)", SQL_HANDLE_DBC, connection);
        execute_direct(statement, text_query("3"));
        verify_text_rows(statement, 3);
        close_statement(statement, "SQLFreeStmt(replacement result)");
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement),
                        "SQLFreeHandle(statement)", SQL_HANDLE_STMT, statement);
        statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(connection), "SQLDisconnect", SQL_HANDLE_DBC, connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, connection),
                        "SQLFreeHandle(connection)", SQL_HANDLE_ENV, environment);
        connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (connection != SQL_NULL_HDBC) {
            SQLDisconnect(connection);
            SQLFreeHandle(SQL_HANDLE_DBC, connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC PostgreSQL L3 integration test passed\n";
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        return run_integration_test(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
