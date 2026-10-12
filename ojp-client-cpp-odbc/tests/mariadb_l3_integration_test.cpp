// Verifies MariaDB result streaming and cursor lifecycle through ODBC.
#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <random>
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
        throw std::runtime_error("cannot read the MariaDB connection CSV");
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
        throw std::runtime_error("expected JDBC URL, username, and password in MariaDB CSV");
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

std::string random_suffix() {
    std::random_device random;
    std::ostringstream suffix;
    suffix << std::hex;
    for (int index = 0; index < 8; ++index) {
        suffix << static_cast<unsigned int>(random() & 0xff);
    }
    return suffix.str();
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

void verify_metadata(SQLHSTMT statement) {
    SQLSMALLINT column_count = 0;
    require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols",
                    SQL_HANDLE_STMT, statement);
    expect(column_count == 2, "expected two columns in the MariaDB streamed result set");

    for (SQLUSMALLINT column = 1; column <= 2; ++column) {
        SQLCHAR name[32] = {};
        SQLSMALLINT name_length = 0;
        SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
        SQLULEN column_size = 0;
        SQLSMALLINT decimal_digits = 0;
        SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
        require_success(SQLDescribeCol(statement, column, name, sizeof(name), &name_length,
                                       &data_type, &column_size, &decimal_digits, &nullable),
                        "SQLDescribeCol", SQL_HANDLE_STMT, statement);
        const std::string expected = column == 1 ? "id" : "label";
        expect(name_length == static_cast<SQLSMALLINT>(expected.size()) &&
                   std::string(reinterpret_cast<const char*>(name)) == expected,
               "unexpected MariaDB result column label: expected " + expected);
    }
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
               "repeated fetch past the MariaDB result end did not return SQL_NO_DATA");
    }
}

void verify_rows(SQLHSTMT statement, SQLINTEGER expected_rows) {
    verify_metadata(statement);
    SQLINTEGER count = 0;
    while (true) {
        const SQLRETURN result = SQLFetch(statement);
        if (result == SQL_NO_DATA) {
            break;
        }
        require_success(result, "SQLFetch", SQL_HANDLE_STMT, statement);
        ++count;
        expect(count <= expected_rows && read_id(statement) == count,
               "MariaDB streamed IDs were missing, duplicated, or out of order");
        SQLCHAR label[64] = {};
        SQLLEN length = 0;
        const SQLRETURN data_result =
            SQLGetData(statement, 2, SQL_C_CHAR, label, sizeof(label), &length);
        require_success(data_result, "SQLGetData(label)", SQL_HANDLE_STMT, statement);
        const std::string expected = "MARIADB_ROW_" + std::to_string(count);
        expect(data_result == SQL_SUCCESS && length == static_cast<SQLLEN>(expected.size()) &&
                   std::string(reinterpret_cast<const char*>(label)) == expected,
               "MariaDB streamed text was truncated or incorrect");
    }
    expect(count == expected_rows, "expected " + std::to_string(expected_rows) +
                                       " MariaDB rows, received " + std::to_string(count));
    verify_exhausted(statement);
}

std::string digits_table() {
    return "(SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 "
           "UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 "
           "UNION ALL SELECT 8 UNION ALL SELECT 9)";
}

std::string insert_rows_query(const std::string& table, SQLINTEGER row_count) {
    const std::string digits = digits_table();
    return "INSERT INTO " + table +
           " (id, label) SELECT n, CONCAT('MARIADB_ROW_', n) FROM "
           "(SELECT 1 + d0.digit + d1.digit * 10 + d2.digit * 100 + d3.digit * 1000 + "
           "d4.digit * 10000 AS n FROM " +
           digits + " AS d0 CROSS JOIN " + digits + " AS d1 CROSS JOIN " + digits +
           " AS d2 CROSS JOIN " + digits + " AS d3 CROSS JOIN " + digits +
           " AS d4) AS generated WHERE n <= " + std::to_string(row_count);
}

std::string select_rows_query(const std::string& table) {
    return "SELECT id, label FROM " + table + " ORDER BY id";
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error(
            "expected MariaDB CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the MariaDB L3 suite\n";
        return 77;
    }
    if (enabled != "true" && enabled != "1" && enabled != "yes") {
        throw std::runtime_error(enable_variable + " must be true or false");
    }
    const char* endpoint_value = std::getenv(endpoint_variable.c_str());
    const std::string endpoint = endpoint_value == nullptr ? "" : endpoint_value;
    if (endpoint.empty()) {
        throw std::runtime_error(endpoint_variable + " is required when " + enable_variable +
                                  "=true");
    }

    const DatabaseConfig config = read_connection_config(argv[1]);
    constexpr SQLINTEGER total_rows = 10001;
    const std::string table = "ojp_cpp_mariadb_l3_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    bool table_created = false;
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

        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, label VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        table_created = true;
        execute_direct(statement, insert_rows_query(table, total_rows));
        SQLLEN affected_rows = -1;
        require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount",
                        SQL_HANDLE_STMT, statement);
        expect(affected_rows == total_rows, "expected all MariaDB L3 rows to be inserted");
        close_statement(statement, "SQLFreeStmt(insert)");

        const std::string streamed_query = select_rows_query(table);
        execute_direct(statement, streamed_query);
        verify_metadata(statement);
        require_success(SQLFetch(statement), "SQLFetch(first streamed row)",
                        SQL_HANDLE_STMT, statement);
        expect(read_id(statement) == 1, "the first MariaDB streamed row had the wrong ID");
        close_statement(statement, "SQLFreeStmt(partial result)");

        execute_direct(statement, streamed_query);
        verify_rows(statement, total_rows);
        close_statement(statement, "SQLFreeStmt(streamed result)");

        execute_direct(statement, streamed_query);
        require_success(SQLFetch(statement), "SQLFetch(partial cursor)",
                        SQL_HANDLE_STMT, statement);
        expect(read_id(statement) == 1, "the partial MariaDB cursor returned the wrong first ID");
        require_success(SQLCloseCursor(statement), "SQLCloseCursor(partial result)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT id, label FROM " + table +
                                 " WHERE id < 0 ORDER BY id");
        verify_rows(statement, 0);
        require_success(SQLCloseCursor(statement), "SQLCloseCursor(empty result)",
                        SQL_HANDLE_STMT, statement);

        const std::string prepared_query =
            "SELECT id, label FROM " + table + " WHERE id <= ? ORDER BY id";
        require_success(SQLPrepare(statement, sql_text(prepared_query), SQL_NTS),
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
            verify_rows(statement, rows);
            close_statement(statement, "SQLFreeStmt(prepared result)");
        }
        require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(parameters)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, streamed_query);
        require_success(SQLFetch(statement), "SQLFetch(active statement)",
                        SQL_HANDLE_STMT, statement);
        expect(read_id(statement) == 1, "the active MariaDB statement returned the wrong ID");
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement),
                        "SQLFreeHandle(active statement)", SQL_HANDLE_STMT, statement);
        statement = SQL_NULL_HSTMT;
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(replacement statement)", SQL_HANDLE_DBC, connection);
        execute_direct(statement, "DROP TABLE " + table);
        table_created = false;
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
        if (table_created && statement == SQL_NULL_HSTMT &&
            connection != SQL_NULL_HDBC) {
            SQLAllocHandle(SQL_HANDLE_STMT, connection,
                           reinterpret_cast<SQLHANDLE*>(&statement));
        }
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeStmt(statement, SQL_CLOSE);
            SQLFreeStmt(statement, SQL_RESET_PARAMS);
            if (table_created) {
                const std::string drop = "DROP TABLE IF EXISTS " + table;
                SQLExecDirect(statement, sql_text(drop), SQL_NTS);
            }
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
    std::cout << "C++ ODBC MariaDB/MySQL L3 integration test passed\n";
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
