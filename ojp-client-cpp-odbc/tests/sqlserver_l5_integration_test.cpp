// Verifies SQL Server LOB upload and retrieval through the ODBC client.
#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
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
        throw std::runtime_error("cannot read the SQL Server connection CSV");
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
        } else {
            field.push_back(character);
        }
    }
    fields.push_back(field);
    if (quoted || fields.size() != 3 || fields[0].empty()) {
        throw std::runtime_error("expected JDBC URL, username, and password in SQL Server CSV");
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
    for (int index = 0; index < 6; ++index) {
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

void send_lob_data(SQLHSTMT statement, SQLPOINTER lob_token,
                   const std::vector<std::uint8_t>& bytes) {
    SQLPOINTER token = nullptr;
    const auto parameter_result = SQLParamData(statement, &token);
    expect(parameter_result == SQL_NEED_DATA && token == lob_token,
           "SQLParamData did not request the bound SQL Server LOB");
    constexpr std::size_t chunk_size = 48 * 1024;
    if (bytes.empty()) {
        require_success(SQLPutData(statement, nullptr, 0), "SQLPutData(empty LOB)",
                        SQL_HANDLE_STMT, statement);
    }
    for (std::size_t offset = 0; offset < bytes.size(); offset += chunk_size) {
        const auto length = std::min(chunk_size, bytes.size() - offset);
        require_success(SQLPutData(statement,
                                   const_cast<std::uint8_t*>(bytes.data() + offset),
                                   static_cast<SQLLEN>(length)),
                        "SQLPutData(LOB)", SQL_HANDLE_STMT, statement);
    }
    require_success(SQLParamData(statement, &token), "SQLParamData(complete LOB)",
                    SQL_HANDLE_STMT, statement);
}

void insert_lob(SQLHSTMT statement, SQLINTEGER id, SQLLEN* id_length,
                SQLLEN* lob_length, SQLPOINTER lob_token,
                const std::vector<std::uint8_t>& bytes) {
    *id_length = 0;
    *lob_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(bytes.size()));
    expect(SQLExecute(statement) == SQL_NEED_DATA,
           "SQLExecute did not request the SQL Server LOB data");
    send_lob_data(statement, lob_token, bytes);
}

void verify_lob_rows(SQLHSTMT statement,
                     const std::vector<std::vector<std::uint8_t>>& expected_payloads,
                     const std::vector<bool>& expected_nulls) {
    for (std::size_t row = 0; row < expected_payloads.size(); ++row) {
        require_success(SQLFetch(statement), "SQLFetch(LOB)", SQL_HANDLE_STMT, statement);
        SQLINTEGER id = 0;
        SQLLEN id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &id, sizeof(id), &id_length),
                        "SQLGetData(LOB id)", SQL_HANDLE_STMT, statement);
        expect(id == static_cast<SQLINTEGER>(row + 1),
               "SQL Server LOB rows were missing or out of order");

        constexpr std::size_t chunk_size = 48 * 1024;
        std::vector<SQLCHAR> buffer(chunk_size);
        std::vector<std::uint8_t> actual_payload;
        SQLLEN data_length = 0;
        SQLRETURN read_result = SQL_SUCCESS;
        do {
            read_result = SQLGetData(statement, 2, SQL_C_BINARY, buffer.data(),
                                     static_cast<SQLLEN>(buffer.size()), &data_length);
            require_success(read_result, "SQLGetData(LOB payload)", SQL_HANDLE_STMT, statement);
            if (expected_nulls[row]) {
                expect(data_length == SQL_NULL_DATA, "SQL Server did not preserve a NULL LOB");
                break;
            }
            const auto remaining = expected_payloads[row].size() - actual_payload.size();
            expect(data_length == static_cast<SQLLEN>(remaining),
                   "SQL Server returned an incorrect remaining LOB length");
            const auto bytes_read = std::min(chunk_size, remaining);
            actual_payload.insert(actual_payload.end(), buffer.begin(),
                                  buffer.begin() + static_cast<std::ptrdiff_t>(bytes_read));
            const bool more_data = actual_payload.size() < expected_payloads[row].size();
            expect((read_result == SQL_SUCCESS_WITH_INFO) == more_data,
                   "SQLGetData returned an incorrect LOB continuation status");
        } while (read_result == SQL_SUCCESS_WITH_INFO);
        expect(actual_payload == expected_payloads[row],
               "SQL Server returned incorrect LOB bytes");
    }
    expect(SQLFetch(statement) == SQL_NO_DATA, "SQL Server returned unexpected LOB rows");
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error(
            "expected SQL Server CSV path, enable variable, and endpoint variable");
    }
    const std::string csv_path = argv[1];
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
                  << "=true to run the SQL Server L5 suite\n";
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

    const DatabaseConfig config = read_connection_config(csv_path);
    const std::string table = "ojp_cpp_mssql_l5_lob_" + random_suffix();
    const std::string insert_sql = "INSERT INTO " + table + " (id, payload) VALUES (?, ?)";
    std::vector<std::uint8_t> large_payload(1024 * 1024 + 137);
    std::vector<std::uint8_t> small_payload(1000);
    const std::string unicode_text =
        "SQL Server BLOB test data - special characters: äöü ñ 中文 🚀";
    const std::vector<std::uint8_t> unicode_payload(unicode_text.begin(), unicode_text.end());
    const std::string updated_text = "Updated SQL Server BLOB data with more content";
    const std::vector<std::uint8_t> updated_payload(updated_text.begin(), updated_text.end());
    for (std::size_t index = 0; index < large_payload.size(); ++index) {
        large_payload[index] = static_cast<std::uint8_t>((index * 37) % 256);
    }
    for (std::size_t index = 0; index < small_payload.size(); ++index) {
        small_payload[index] = static_cast<std::uint8_t>((index * 17) % 256);
    }

    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3),
                                      SQL_IS_INTEGER),
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
                                  " (id INT PRIMARY KEY, payload VARBINARY(MAX) NULL)");
        require_success(SQLPrepare(statement, sql_text(insert_sql), SQL_NTS),
                        "SQLPrepare(LOB insert)", SQL_HANDLE_STMT, statement);

        SQLINTEGER id = 0;
        SQLLEN id_length = 0;
        SQLLEN lob_length = 0;
        const auto lob_token = reinterpret_cast<SQLPOINTER>(static_cast<std::uintptr_t>(1));
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG,
                                         SQL_INTEGER, 0, 0, &id, sizeof(id), &id_length),
                        "SQLBindParameter(LOB id)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_BINARY,
                                         SQL_LONGVARBINARY, large_payload.size(), 0, lob_token, 0,
                                         &lob_length),
                        "SQLBindParameter(LOB payload)", SQL_HANDLE_STMT, statement);

        id = 1;
        insert_lob(statement, id, &id_length, &lob_length, lob_token, large_payload);
        id = 2;
        insert_lob(statement, id, &id_length, &lob_length, lob_token, small_payload);
        id = 3;
        insert_lob(statement, id, &id_length, &lob_length, lob_token, unicode_payload);
        id = 4;
        insert_lob(statement, id, &id_length, &lob_length, lob_token, {});

        id = 5;
        lob_length = SQL_NULL_DATA;
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_BINARY,
                                         SQL_LONGVARBINARY, 0, 0, nullptr, 0, &lob_length),
                        "SQLBindParameter(NULL LOB)", SQL_HANDLE_STMT, statement);
        require_success(SQLExecute(statement), "SQLExecute(NULL LOB)", SQL_HANDLE_STMT, statement);

        require_success(SQLFreeStmt(statement, SQL_CLOSE),
                        "SQLFreeStmt(insert)", SQL_HANDLE_STMT, statement);
        const std::string update_sql = "UPDATE " + table + " SET payload = ? WHERE id = ?";
        require_success(SQLPrepare(statement, sql_text(update_sql), SQL_NTS),
                        "SQLPrepare(LOB update)", SQL_HANDLE_STMT, statement);
        id = 2;
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_BINARY,
                                         SQL_LONGVARBINARY, updated_payload.size(), 0,
                                         lob_token, 0, &lob_length),
                        "SQLBindParameter(updated LOB)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_SLONG,
                                         SQL_INTEGER, 0, 0, &id, sizeof(id), &id_length),
                        "SQLBindParameter(update id)", SQL_HANDLE_STMT, statement);
        id_length = 0;
        lob_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(updated_payload.size()));
        expect(SQLExecute(statement) == SQL_NEED_DATA,
               "SQLExecute did not request the updated SQL Server LOB data");
        send_lob_data(statement, lob_token, updated_payload);

        execute_direct(statement, "SELECT id, payload FROM " + table + " ORDER BY id");
        verify_lob_rows(statement,
                        {large_payload, updated_payload, unicode_payload, {}, {}},
                        {false, false, false, false, true});
        execute_direct(statement, "DROP TABLE " + table);

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
            SQLFreeStmt(statement, SQL_CLOSE);
            SQLFreeStmt(statement, SQL_RESET_PARAMS);
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(statement, sql_text(drop), SQL_NTS);
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
    std::cout << "C++ ODBC SQL Server L5 integration test passed\n";
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
