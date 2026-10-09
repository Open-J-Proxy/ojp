// Verifies H2 BLOB/CLOB stream round trips through ODBC.
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
        throw std::runtime_error("cannot read the H2 connection CSV");
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
        throw std::runtime_error("expected JDBC URL, username, and password in H2 CSV");
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
        if (SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail, sizeof(detail),
                          &detail_length) == SQL_SUCCESS) {
            message << " [" << state << ", " << native_error << "] "
                    << reinterpret_cast<const char*>(detail);
        }
    }
    throw std::runtime_error(message.str());
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

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement,
                                  reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())),
                                  SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void bind_lob_parameter(SQLHSTMT statement, SQLUSMALLINT index, SQLSMALLINT c_type,
                        SQLSMALLINT sql_type, SQLPOINTER token, SQLLEN* indicator) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, c_type, sql_type, 0, 0,
                                     token, 0, indicator),
                    "SQLBindParameter(LOB)", SQL_HANDLE_STMT, statement);
}

void send_parameter(SQLHSTMT statement, SQLPOINTER expected_token, const std::string& data,
                    std::size_t chunk_size) {
    SQLPOINTER token = nullptr;
    require_success(SQLParamData(statement, &token), "SQLParamData", SQL_HANDLE_STMT, statement);
    if (token != expected_token) {
        throw std::runtime_error("SQLParamData returned the wrong LOB parameter token");
    }
    for (std::size_t offset = 0; offset < data.size(); offset += chunk_size) {
        const auto length = std::min(chunk_size, data.size() - offset);
        require_success(SQLPutData(statement, const_cast<char*>(data.data() + offset),
                                   static_cast<SQLLEN>(length)),
                        "SQLPutData", SQL_HANDLE_STMT, statement);
    }
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error("expected H2 CSV path, enable variable, and endpoint variable");
    }
    const std::string csv_path = argv[1];
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L5 suite\n";
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
    std::string binary_data;
    binary_data.reserve(180000);
    for (std::size_t index = 0; index < 180000; ++index) {
        binary_data.push_back(static_cast<char>((index * 31) & 0xff));
    }
    std::string clob_data;
    const std::string utf8_text = "H2-L5-LOB-é-東京-🙂;";
    for (int index = 0; index < 6000; ++index) {
        clob_data += utf8_text;
    }

    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    const std::string table = "ojp_cpp_l5_" + random_suffix();
    bool table_created = false;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        require_success(
            SQLAllocHandle(SQL_HANDLE_DBC, environment, reinterpret_cast<SQLHANDLE*>(&connection)),
            "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
        const std::string connection_string = "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
                                              ";DATABASE=" + brace_value(config.url) +
                                              ";UID=" + brace_value(config.user) +
                                              ";P"
                                              "WD=" +
                                              brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr,
                                         reinterpret_cast<SQLCHAR*>(
                                             const_cast<char*>(connection_string.c_str())),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(
            SQLAllocHandle(SQL_HANDLE_STMT, connection, reinterpret_cast<SQLHANDLE*>(&statement)),
            "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement,
                       "CREATE TABLE " + table +
                           " (id INT PRIMARY KEY, binary_payload BLOB, text_payload CLOB)");
        table_created = true;
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(create table)",
                        SQL_HANDLE_STMT, statement);
        require_success(SQLPrepare(statement,
                                   reinterpret_cast<SQLCHAR*>(const_cast<char*>(
                                       ("INSERT INTO " + table +
                                        " (id, binary_payload, text_payload) VALUES (?, ?, ?)")
                                           .c_str())),
                                   SQL_NTS),
                        "SQLPrepare(insert)", SQL_HANDLE_STMT, statement);

        SQLINTEGER id = 1;
        SQLLEN id_length = sizeof(id);
        int binary_token_value = 2;
        int clob_token_value = 3;
        SQLPOINTER binary_token = &binary_token_value;
        SQLPOINTER clob_token = &clob_token_value;
        SQLLEN binary_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(binary_data.size()));
        SQLLEN clob_length = SQL_DATA_AT_EXEC;
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0,
                                         0, &id, sizeof(id), &id_length),
                        "SQLBindParameter(id)", SQL_HANDLE_STMT, statement);
        bind_lob_parameter(statement, 2, SQL_C_BINARY, SQL_LONGVARBINARY, binary_token,
                           &binary_length);
        bind_lob_parameter(statement, 3, SQL_C_CHAR, SQL_LONGVARCHAR, clob_token, &clob_length);
        const auto execute_result = SQLExecute(statement);
        if (execute_result != SQL_NEED_DATA) {
            require_success(execute_result, "SQLExecute(data-at-execution)", SQL_HANDLE_STMT,
                            statement);
            throw std::runtime_error("SQLExecute did not request the bound LOB streams");
        }
        send_parameter(statement, binary_token, binary_data, 24000);
        send_parameter(statement, clob_token, clob_data, 17000);
        SQLPOINTER token = nullptr;
        require_success(SQLParamData(statement, &token), "SQLParamData(execute)", SQL_HANDLE_STMT,
                        statement);
        SQLLEN affected_rows = 0;
        require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount", SQL_HANDLE_STMT,
                        statement);
        if (affected_rows != 1) {
            throw std::runtime_error("expected one row after inserting the LOB parameters");
        }

        id = 2;
        id_length = sizeof(id);
        char empty_lob[1] = {};
        SQLLEN empty_length = 0;
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0,
                                         0, &id, sizeof(id), &id_length),
                        "SQLBindParameter(empty row id)", SQL_HANDLE_STMT, statement);
        bind_lob_parameter(statement, 2, SQL_C_BINARY, SQL_LONGVARBINARY, empty_lob, &empty_length);
        bind_lob_parameter(statement, 3, SQL_C_CHAR, SQL_LONGVARCHAR, empty_lob, &empty_length);
        require_success(SQLExecute(statement), "SQLExecute(empty LOBs)", SQL_HANDLE_STMT,
                        statement);

        id = 3;
        id_length = sizeof(id);
        SQLLEN null_binary = SQL_NULL_DATA;
        SQLLEN null_clob = SQL_NULL_DATA;
        SQLPOINTER null_value = nullptr;
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0,
                                         0, &id, sizeof(id), &id_length),
                        "SQLBindParameter(NULL row id)", SQL_HANDLE_STMT, statement);
        bind_lob_parameter(statement, 2, SQL_C_BINARY, SQL_LONGVARBINARY, null_value, &null_binary);
        bind_lob_parameter(statement, 3, SQL_C_CHAR, SQL_LONGVARCHAR, null_value, &null_clob);
        require_success(SQLExecute(statement), "SQLExecute(NULL LOBs)", SQL_HANDLE_STMT, statement);

        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert)", SQL_HANDLE_STMT,
                        statement);
        execute_direct(statement,
                       "SELECT binary_payload, text_payload FROM " + table + " WHERE id = 1");
        require_success(SQLFetch(statement), "SQLFetch(LOB row)", SQL_HANDLE_STMT, statement);
        std::vector<SQLCHAR> received_binary(binary_data.size());
        SQLLEN received_binary_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_BINARY, received_binary.data(),
                                   static_cast<SQLLEN>(received_binary.size()),
                                   &received_binary_length),
                        "SQLGetData(BLOB)", SQL_HANDLE_STMT, statement);
        if (received_binary_length != static_cast<SQLLEN>(binary_data.size()) ||
            std::string(received_binary.begin(), received_binary.end()) != binary_data) {
            throw std::runtime_error("BLOB stream round trip changed the binary payload");
        }
        std::vector<SQLCHAR> received_clob(clob_data.size() + 1, 0);
        SQLLEN received_clob_length = 0;
        require_success(SQLGetData(statement, 2, SQL_C_CHAR, received_clob.data(),
                                   static_cast<SQLLEN>(received_clob.size()),
                                   &received_clob_length),
                        "SQLGetData(CLOB)", SQL_HANDLE_STMT, statement);
        if (received_clob_length != static_cast<SQLLEN>(clob_data.size()) ||
            std::string(reinterpret_cast<const char*>(received_clob.data()),
                        static_cast<std::size_t>(received_clob_length)) != clob_data) {
            throw std::runtime_error("CLOB stream round trip changed the UTF-8 text payload");
        }

        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(first result)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement,
                       "SELECT binary_payload, text_payload FROM " + table + " WHERE id = 2");
        require_success(SQLFetch(statement), "SQLFetch(empty LOB row)", SQL_HANDLE_STMT, statement);
        SQLCHAR empty_output[1] = {};
        SQLLEN empty_indicator = -1;
        require_success(SQLGetData(statement, 1, SQL_C_BINARY, empty_output, sizeof(empty_output),
                                   &empty_indicator),
                        "SQLGetData(empty BLOB)", SQL_HANDLE_STMT, statement);
        if (empty_indicator != 0) {
            throw std::runtime_error("empty BLOB was not returned as an empty value");
        }
        require_success(SQLGetData(statement, 2, SQL_C_CHAR, empty_output, sizeof(empty_output),
                                   &empty_indicator),
                        "SQLGetData(empty CLOB)", SQL_HANDLE_STMT, statement);
        if (empty_indicator != 0) {
            throw std::runtime_error("empty CLOB was not returned as an empty value");
        }

        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(empty result)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement,
                       "SELECT binary_payload, text_payload FROM " + table + " WHERE id = 3");
        require_success(SQLFetch(statement), "SQLFetch(NULL LOB row)", SQL_HANDLE_STMT, statement);
        SQLCHAR null_output[1] = {};
        SQLLEN null_indicator = 0;
        require_success(SQLGetData(statement, 1, SQL_C_BINARY, null_output, sizeof(null_output),
                                   &null_indicator),
                        "SQLGetData(NULL BLOB)", SQL_HANDLE_STMT, statement);
        if (null_indicator != SQL_NULL_DATA) {
            throw std::runtime_error("NULL BLOB parameter was not returned as SQL NULL");
        }
        require_success(
            SQLGetData(statement, 2, SQL_C_CHAR, null_output, sizeof(null_output), &null_indicator),
            "SQLGetData(NULL CLOB)", SQL_HANDLE_STMT, statement);
        if (null_indicator != SQL_NULL_DATA) {
            throw std::runtime_error("NULL CLOB parameter was not returned as SQL NULL");
        }

        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(second result)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement), "SQLFreeHandle(statement)",
                        SQL_HANDLE_DBC, connection);
        statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(connection), "SQLDisconnect", SQL_HANDLE_DBC, connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, connection), "SQLFreeHandle(connection)",
                        SQL_HANDLE_ENV, environment);
        connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (table_created && statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(const_cast<char*>(drop.c_str())),
                          SQL_NTS);
        }
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
    std::cout << "C++ ODBC H2 L5 integration test passed\n";
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    try {
        return run_integration_test(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
