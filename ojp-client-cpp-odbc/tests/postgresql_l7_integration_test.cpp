// Verifies PostgreSQL operations across a multinode OJP cluster through ODBC.
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
#include <utility>
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
                     SQLSMALLINT handle_type = SQL_HANDLE_ENV,
                     SQLHANDLE handle = SQL_NULL_HANDLE) {
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

std::string environment_value(const std::string& name) {
    const char* value = std::getenv(name.c_str());
    return value == nullptr ? std::string{} : std::string(value);
}

bool is_enabled(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return value == "true" || value == "1" || value == "yes";
}

std::vector<std::string> split_endpoints(const std::string& value) {
    std::vector<std::string> endpoints;
    std::size_t start = 0;
    while (start <= value.size()) {
        const auto separator = value.find(',', start);
        std::string endpoint = value.substr(
            start, separator == std::string::npos ? separator : separator - start);
        const auto first = endpoint.find_first_not_of(" \t\r\n");
        const auto last = endpoint.find_last_not_of(" \t\r\n");
        endpoint = first == std::string::npos ? "" : endpoint.substr(first, last - first + 1);
        if (endpoint.empty()) {
            throw std::runtime_error(
                "OJP_TEST_POSTGRESQL_L7_ADDRS contains an empty endpoint");
        }
        if (std::find(endpoints.begin(), endpoints.end(), endpoint) == endpoints.end()) {
            endpoints.push_back(std::move(endpoint));
        }
        if (separator == std::string::npos) {
            break;
        }
        start = separator + 1;
    }
    if (endpoints.size() < 2) {
        throw std::runtime_error(
            "OJP_TEST_POSTGRESQL_L7_ADDRS must contain at least two OJP servers");
    }
    return endpoints;
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

void expect(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

SQLHDBC open_connection(SQLHENV environment, const DatabaseConfig& config,
                        const std::string& endpoints, bool load_aware) {
    SQLHDBC connection = SQL_NULL_HDBC;
    require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                   reinterpret_cast<SQLHANDLE*>(&connection)),
                    "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
    const std::string connection_string =
        "DRIVER={OJP};SERVER=" + brace_value(endpoints) +
        ";DATABASE=" + brace_value(config.url) +
        ";UID=" + brace_value(config.user) +
        ";P"
        "WD=" + brace_value(config.password) + ";OJP.LOADAWARE.SELECTION.ENABLED=" +
        std::string(load_aware ? "true" : "false") + ";";
    require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string), SQL_NTS,
                                     nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                    "SQLDriverConnect", SQL_HANDLE_DBC, connection);
    return connection;
}

void verify_value(SQLHSTMT statement, const std::string& table, int id,
                  const std::string& expected) {
    execute_direct(statement, "SELECT label FROM " + table + " WHERE id = " +
                                  std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch", SQL_HANDLE_STMT, statement);
    SQLCHAR label[128] = {};
    SQLLEN label_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, label, sizeof(label), &label_length),
                    "SQLGetData(label)", SQL_HANDLE_STMT, statement);
    expect(std::string(reinterpret_cast<const char*>(label)) == expected,
           "PostgreSQL cluster returned an unexpected value");
    expect(SQLFetch(statement) == SQL_NO_DATA,
           "PostgreSQL L7 lookup returned more than one row");
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error(
            "expected PostgreSQL L7 CSV path, enable variable, and endpoints variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoints_variable = argv[3];
    if (!is_enabled(environment_value(enable_variable))) {
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the PostgreSQL L7 suite\n";
        return 77;
    }
    const std::string configured_endpoints = environment_value(endpoints_variable);
    const auto endpoints = split_endpoints(configured_endpoints);
    const std::string unavailable_endpoint =
        environment_value("OJP_TEST_POSTGRESQL_L7_UNAVAILABLE_ADDR").empty()
            ? "127.0.0.1:1"
            : environment_value("OJP_TEST_POSTGRESQL_L7_UNAVAILABLE_ADDR");
    if (std::find(endpoints.begin(), endpoints.end(), unavailable_endpoint) != endpoints.end()) {
        throw std::runtime_error(
            "the unavailable test endpoint must not be a healthy endpoint");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    const std::string server_list = unavailable_endpoint + "," + configured_endpoints;
    const std::string table = "ojp_cpp_pg_l7_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC first_connection = SQL_NULL_HDBC;
    SQLHDBC second_connection = SQL_NULL_HDBC;
    SQLHSTMT first_statement = SQL_NULL_HSTMT;
    SQLHSTMT second_statement = SQL_NULL_HSTMT;
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
        first_connection = open_connection(environment, config, server_list, false);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, first_connection, &first_statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, first_connection);
        execute_direct(first_statement, "CREATE TABLE " + table +
                                       " (id INT PRIMARY KEY, label VARCHAR(100) NOT NULL)");
        table_created = true;
        execute_direct(first_statement, "INSERT INTO " + table +
                                       " VALUES (1, 'first connection')");
        verify_value(first_statement, table, 1, "first connection");

        second_connection = open_connection(environment, config, server_list, true);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, second_connection, &second_statement),
                        "SQLAllocHandle(second statement)", SQL_HANDLE_DBC, second_connection);
        verify_value(second_statement, table, 1, "first connection");
        execute_direct(second_statement, "UPDATE " + table +
                                         " SET label = 'cached pool reuse' WHERE id = 1");
        verify_value(first_statement, table, 1, "cached pool reuse");
        execute_direct(first_statement, "INSERT INTO " + table +
                                       " VALUES (2, 'cluster write')");
        verify_value(second_statement, table, 2, "cluster write");
        execute_direct(second_statement, "DELETE FROM " + table + " WHERE id = 2");
        execute_direct(first_statement, "SELECT id FROM " + table + " WHERE id = 2");
        expect(SQLFetch(first_statement) == SQL_NO_DATA,
               "PostgreSQL cluster delete was not visible to the other connection");
        require_success(SQLFreeStmt(first_statement, SQL_CLOSE), "SQLFreeStmt(empty result)",
                        SQL_HANDLE_STMT, first_statement);

        execute_direct(first_statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, second_statement),
                        "SQLFreeHandle(second statement)", SQL_HANDLE_DBC, second_connection);
        second_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, first_statement),
                        "SQLFreeHandle(statement)", SQL_HANDLE_DBC, first_connection);
        first_statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(second_connection), "SQLDisconnect(second)",
                        SQL_HANDLE_DBC, second_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, second_connection),
                        "SQLFreeHandle(second connection)", SQL_HANDLE_ENV, environment);
        second_connection = SQL_NULL_HDBC;
        require_success(SQLDisconnect(first_connection), "SQLDisconnect(first)",
                        SQL_HANDLE_DBC, first_connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, first_connection),
                        "SQLFreeHandle(first connection)", SQL_HANDLE_ENV, environment);
        first_connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment),
                        "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (table_created && first_statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(first_statement, sql_text(drop), SQL_NTS);
        }
        if (second_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, second_statement);
        }
        if (first_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, first_statement);
        }
        if (second_connection != SQL_NULL_HDBC) {
            SQLDisconnect(second_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, second_connection);
        }
        if (first_connection != SQL_NULL_HDBC) {
            SQLDisconnect(first_connection);
            SQLFreeHandle(SQL_HANDLE_DBC, first_connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC PostgreSQL L7 multinode integration test passed\n";
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
