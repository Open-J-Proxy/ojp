// Verifies MariaDB BLOB/TEXT stream round trips through ODBC.
#include "connection_suite.h"

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

using ojp::test::DatabaseConfig;

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

void expect(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
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

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void reset_statement(SQLHSTMT statement) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
    require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(reset parameters)",
                    SQL_HANDLE_STMT, statement);
}

void bind_lob(SQLHSTMT statement, SQLUSMALLINT index, SQLSMALLINT c_type,
              SQLSMALLINT sql_type, SQLPOINTER value, SQLLEN buffer_length, SQLLEN* indicator) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, c_type, sql_type, 0, 0,
                                     value, buffer_length, indicator),
                    "SQLBindParameter(LOB)", SQL_HANDLE_STMT, statement);
}

void send_parameter(SQLHSTMT statement, SQLPOINTER expected_token, const std::string& data,
                    std::size_t chunk_size) {
    SQLPOINTER token = nullptr;
    expect(SQLParamData(statement, &token) == SQL_NEED_DATA && token == expected_token,
           "SQLParamData must request the correct LOB token");
    if (data.empty()) {
        require_success(SQLPutData(statement, nullptr, 0), "SQLPutData(empty)",
                        SQL_HANDLE_STMT, statement);
    }
    for (std::size_t offset = 0; offset < data.size(); offset += chunk_size) {
        const auto length = std::min(chunk_size, data.size() - offset);
        require_success(SQLPutData(statement,
                                   const_cast<char*>(data.data() + offset),
                                   static_cast<SQLLEN>(length)),
                        "SQLPutData", SQL_HANDLE_STMT, statement);
    }
}

void write_lobs(SQLHSTMT statement, const std::string& sql, SQLINTEGER id,
                const std::string& binary, const std::string& text, bool streamed,
                bool null_values = false, bool updating = false) {
    reset_statement(statement);
    require_success(SQLPrepare(statement, sql_text(sql), SQL_NTS), "SQLPrepare(LOB write)",
                    SQL_HANDLE_STMT, statement);

    SQLLEN id_length = sizeof(id);
    SQLLEN binary_length = null_values ? SQL_NULL_DATA : static_cast<SQLLEN>(binary.size());
    SQLLEN text_length = null_values ? SQL_NULL_DATA : static_cast<SQLLEN>(text.size());
    const SQLUSMALLINT id_index = updating ? 3 : 1;
    const SQLUSMALLINT binary_index = updating ? 1 : 2;
    const SQLUSMALLINT text_index = updating ? 2 : 3;
    int binary_token = 2;
    int text_token = 3;
    char empty_value = '\0';
    SQLPOINTER binary_value = binary.empty() ? &empty_value : const_cast<char*>(binary.data());
    SQLPOINTER text_value = text.empty() ? &empty_value : const_cast<char*>(text.data());
    if (streamed && !null_values) {
        binary_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(binary.size()));
        text_length = SQL_DATA_AT_EXEC;
        binary_value = &binary_token;
        text_value = &text_token;
    } else if (null_values) {
        binary_value = nullptr;
        text_value = nullptr;
    }

    require_success(SQLBindParameter(statement, id_index, SQL_PARAM_INPUT, SQL_C_SLONG,
                                     SQL_INTEGER, 0,
                                     0, &id, sizeof(id), &id_length),
                    "SQLBindParameter(id)", SQL_HANDLE_STMT, statement);
    bind_lob(statement, binary_index, SQL_C_BINARY, SQL_LONGVARBINARY, binary_value,
             static_cast<SQLLEN>(binary.size()), &binary_length);
    bind_lob(statement, text_index, SQL_C_CHAR, SQL_LONGVARCHAR, text_value,
             static_cast<SQLLEN>(text.size()), &text_length);

    if (streamed && !null_values) {
        expect(SQLExecute(statement) == SQL_NEED_DATA, "LOB write must request streamed data");
        send_parameter(statement, &binary_token, binary, 48 * 1024);
        // Deliberately split UTF-8 characters across SQLPutData calls.
        send_parameter(statement, &text_token, text, 17003);
        SQLPOINTER token = nullptr;
        require_success(SQLParamData(statement, &token), "SQLParamData(complete)",
                        SQL_HANDLE_STMT, statement);
    } else {
        require_success(SQLExecute(statement), "SQLExecute(LOB write)",
                        SQL_HANDLE_STMT, statement);
    }

    SQLLEN affected_rows = 0;
    require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount",
                    SQL_HANDLE_STMT, statement);
    expect(affected_rows == 1, "LOB write must affect exactly one row");
    reset_statement(statement);
}

void verify_column(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type,
                   const std::string& context, const std::string& expected, bool null_value) {
    constexpr std::size_t chunk_size = 17003;
    std::vector<char> buffer(chunk_size + (c_type == SQL_C_CHAR ? 1 : 0), '\x7f');
    SQLLEN indicator = -1;
    std::string actual;
    SQLRETURN result = SQL_SUCCESS;
    do {
        result = SQLGetData(statement, column, c_type, buffer.data(),
                            static_cast<SQLLEN>(buffer.size()), &indicator);
        require_success(result, "SQLGetData(LOB)", SQL_HANDLE_STMT, statement);
        if (null_value) {
            expect(result == SQL_SUCCESS && indicator == SQL_NULL_DATA,
                   "NULL must remain distinct from an empty LOB");
            return;
        }
        expect(indicator >= 0, "LOB read returned an invalid length");
        const auto count = std::min(chunk_size, static_cast<std::size_t>(indicator));
        expect(result != SQL_SUCCESS_WITH_INFO || count > 0,
               "LOB read made no progress");
        actual.append(buffer.data(), count);
        if (c_type == SQL_C_CHAR) {
            expect(buffer[count] == '\0', "SQLGetData character chunks must be terminated");
        }
    } while (result == SQL_SUCCESS_WITH_INFO);
    if (actual != expected) {
        std::size_t mismatch = 0;
        while (mismatch < actual.size() && mismatch < expected.size() &&
               actual[mismatch] == expected[mismatch]) {
            ++mismatch;
        }
        std::ostringstream message;
        message << context << " payload mismatch: expected " << expected.size()
                << " bytes, received " << actual.size() << " bytes; first difference at byte "
                << mismatch;
        if (mismatch < expected.size()) {
            message << ", expected value "
                    << static_cast<unsigned int>(static_cast<unsigned char>(expected[mismatch]));
        } else {
            message << ", expected end of payload";
        }
        if (mismatch < actual.size()) {
            message << ", received value "
                    << static_cast<unsigned int>(static_cast<unsigned char>(actual[mismatch]));
        } else {
            message << ", received end of payload";
        }
        throw std::runtime_error(message.str());
    }
    expect(SQLGetData(statement, column, c_type, buffer.data(),
                      static_cast<SQLLEN>(buffer.size()), &indicator) == SQL_NO_DATA,
           "SQLGetData must stop after the final chunk");
}

void verify_row(SQLHSTMT statement, const std::string& table, SQLINTEGER id,
                const std::string& binary, const std::string& text, bool null_values = false) {
    reset_statement(statement);
    execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table +
                              " WHERE id = " + std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch(LOB)", SQL_HANDLE_STMT, statement);
    verify_column(statement, 1, SQL_C_BINARY, "row " + std::to_string(id) + " binary",
                  binary, null_values);
    verify_column(statement, 2, SQL_C_CHAR, "row " + std::to_string(id) + " text",
                  text, null_values);
    expect(SQLFetch(statement) == SQL_NO_DATA, "unexpected extra LOB row");
    reset_statement(statement);
}

std::string make_binary(std::size_t size, unsigned int multiplier) {
    std::string value(size, '\0');
    for (std::size_t index = 0; index < size; ++index) {
        value[index] = static_cast<char>((index * multiplier) & 0xff);
    }
    return value;
}

int run_connection(const DatabaseConfig& config,
                   const std::string& endpoint) {
    const std::string table = "ojp_cpp_mariadb_l5_" + random_suffix();
    const std::string insert = "INSERT INTO " + table +
                               " (id, binary_payload, text_payload) VALUES (?, ?, ?)";
    const std::string update = "UPDATE " + table +
                               " SET binary_payload = ?, text_payload = ? WHERE id = ?";
    const std::string small_binary = make_binary(1000, 17);
    const std::string binary_chunk = "medium-blob-chunk-";
    std::string repeated_binary;
    repeated_binary.reserve(binary_chunk.size() * 10000);
    for (int index = 0; index < 10000; ++index) {
        repeated_binary += binary_chunk;
    }
    const std::string text_chunk = "medium-text-chunk-";
    std::string large_text;
    large_text.reserve(text_chunk.size() * 10000);
    for (int index = 0; index < 10000; ++index) {
        large_text += text_chunk;
    }
    const std::string unicode_text = "MariaDB-L5-äöü-ñ-中文-東京-🚀-🙂;";
    const std::string updated_text = "Updated MariaDB LOB data: Καλημέρα 東京";
    const std::string updated_binary = make_binary(8193, 29);

    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    bool table_created = false;
    try {
        require_success(SQLSetEnvAttr(SQL_NULL_HENV, SQL_ATTR_CONNECTION_POOLING,
                                      reinterpret_cast<SQLPOINTER>(SQL_CP_OFF), 0),
                        "SQLSetEnvAttr(pooling off)");
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection),
                        "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);

        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";PWD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, binary_payload MEDIUMBLOB NULL,"
                                  " text_payload MEDIUMTEXT NULL) ENGINE=InnoDB");
        table_created = true;

        write_lobs(statement, insert, 1, small_binary, unicode_text, false);
        write_lobs(statement, insert, 2, repeated_binary, large_text, true);
        write_lobs(statement, insert, 3, "", "", true);
        write_lobs(statement, insert, 4, "", "", false, true);
        write_lobs(statement, insert, 5, small_binary, unicode_text, true);
        for (int read = 0; read < 2; ++read) {
            verify_row(statement, table, 1, small_binary, unicode_text);
            verify_row(statement, table, 2, repeated_binary, large_text);
            verify_row(statement, table, 3, "", "");
            verify_row(statement, table, 4, "", "", true);
            verify_row(statement, table, 5, small_binary, unicode_text);
        }

        reset_statement(statement);
        execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table +
                                  " ORDER BY id");
        const std::vector<std::string> binary_rows = {
            small_binary, repeated_binary, "", "", small_binary};
        const std::vector<std::string> text_rows = {
            unicode_text, large_text, "", "", unicode_text};
        for (std::size_t row = 0; row < binary_rows.size(); ++row) {
            require_success(SQLFetch(statement), "SQLFetch(LOB rows)",
                            SQL_HANDLE_STMT, statement);
            const bool null_values = row == 3;
            const std::string row_context = "row " + std::to_string(row + 1);
            verify_column(statement, 1, SQL_C_BINARY, row_context + " binary",
                          binary_rows[row], null_values);
            verify_column(statement, 2, SQL_C_CHAR, row_context + " text",
                          text_rows[row], null_values);
        }
        expect(SQLFetch(statement) == SQL_NO_DATA, "unexpected extra LOB rows");
        reset_statement(statement);

        write_lobs(statement, update, 1, updated_binary, updated_text, true, false, true);
        verify_row(statement, table, 1, updated_binary, updated_text);
        write_lobs(statement, update, 1, "", "", true, false, true);
        verify_row(statement, table, 1, "", "");
        write_lobs(statement, update, 1, "", "", false, true, true);
        verify_row(statement, table, 1, "", "", true);

        execute_direct(statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement),
                        "SQLFreeHandle(statement)", SQL_HANDLE_DBC, connection);
        statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(connection), "SQLDisconnect", SQL_HANDLE_DBC, connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, connection),
                        "SQLFreeHandle(connection)", SQL_HANDLE_ENV, environment);
        connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (statement != SQL_NULL_HSTMT) {
            SQLCancel(statement);
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
    std::cout << "C++ ODBC MariaDB/MySQL L5 integration test passed\n";
    return 0;
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
    std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the MariaDB L5 suite\n";
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

    return ojp::test::run_connections(argv[1], [&](const DatabaseConfig& config) {
        return run_connection(config, endpoint);
    });
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
