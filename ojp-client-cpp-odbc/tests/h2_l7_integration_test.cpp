// Verifies H2 operations across a configured OJP cluster, including endpoint recovery and reuse.
#include "connection_suite.h"

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

std::string environment_value(const char* name) {
    const char* value = std::getenv(name);
    return value == nullptr ? std::string{} : std::string(value);
}

bool enabled(const std::string& value) {
    std::string normalized = value;
    std::transform(normalized.begin(), normalized.end(), normalized.begin(),
                   [](unsigned char character) {
                       return static_cast<char>(std::tolower(character));
                   });
    return normalized == "true" || normalized == "1" || normalized == "yes";
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
            throw std::runtime_error("OJP_TEST_H2_L7_ADDRS must contain non-empty endpoints");
        }
        endpoints.push_back(std::move(endpoint));
        if (separator == std::string::npos) {
            break;
        }
        start = separator + 1;
    }
    if (endpoints.size() < 2) {
        throw std::runtime_error("OJP_TEST_H2_L7_ADDRS must contain at least two OJP servers");
    }
    return endpoints;
}

std::string random_suffix() {
    static unsigned int sequence = 0;
    std::ostringstream suffix;
    suffix << ++sequence << '_' << std::rand();
    return suffix.str();
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(
                                      const_cast<char*>(sql.c_str())), SQL_NTS),
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
        ";PWD=;OJP.LOADAWARE.SELECTION.ENABLED=" +
        std::string(load_aware ? "true" : "false") + ";";
    require_success(SQLDriverConnect(connection, nullptr,
                                     reinterpret_cast<SQLCHAR*>(
                                         const_cast<char*>(connection_string.c_str())),
                                     SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                    "SQLDriverConnect", SQL_HANDLE_DBC, connection);
    return connection;
}

void verify_label(SQLHSTMT statement, const std::string& table, int id,
                  const std::string& expected) {
    execute_direct(statement, "SELECT label FROM " + table + " WHERE id = " +
                                  std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch", SQL_HANDLE_STMT, statement);
    SQLCHAR label[128] = {};
    SQLLEN label_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, label, sizeof(label), &label_length),
                    "SQLGetData(label)", SQL_HANDLE_STMT, statement);
    if (reinterpret_cast<const char*>(label) != expected) {
        throw std::runtime_error("cluster connection returned an unexpected H2 value");
    }
    if (SQLFetch(statement) != SQL_NO_DATA) {
        throw std::runtime_error("H2 L7 lookup returned more than one row");
    }
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
}

int run_connection(const DatabaseConfig& config,
                   const std::string& unavailable_endpoint) {
    const std::string server_list = unavailable_endpoint + "," + environment_value(
        "OJP_TEST_H2_L7_ADDRS");
    const std::string table = "ojp_cpp_l7_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC first_connection = SQL_NULL_HDBC;
    SQLHDBC second_connection = SQL_NULL_HDBC;
    SQLHSTMT first_statement = SQL_NULL_HSTMT;
    SQLHSTMT second_statement = SQL_NULL_HSTMT;
    bool table_created = false;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        first_connection = open_connection(environment, config, server_list, true);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, first_connection,
                                       reinterpret_cast<SQLHANDLE*>(&first_statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, first_connection);
        execute_direct(first_statement, "CREATE TABLE " + table +
                                           " (id INT PRIMARY KEY, label VARCHAR(100))");
        table_created = true;
        execute_direct(first_statement, "INSERT INTO " + table + " VALUES (1, 'first-node')");
        verify_label(first_statement, table, 1, "first-node");

        second_connection = open_connection(environment, config, server_list, false);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, second_connection,
                                       reinterpret_cast<SQLHANDLE*>(&second_statement)),
                        "SQLAllocHandle(second statement)", SQL_HANDLE_DBC, second_connection);
        verify_label(second_statement, table, 1, "first-node");
        execute_direct(second_statement, "INSERT INTO " + table + " VALUES (2, 'cached-pool')");
        verify_label(first_statement, table, 2, "cached-pool");

        execute_direct(first_statement, "UPDATE " + table +
                                            " SET label = 'cluster-update' WHERE id = 1");
        verify_label(second_statement, table, 1, "cluster-update");
        execute_direct(second_statement, "DELETE FROM " + table + " WHERE id = 2");
        execute_direct(first_statement, "SELECT id FROM " + table + " WHERE id = 2");
        if (SQLFetch(first_statement) != SQL_NO_DATA) {
            throw std::runtime_error("cluster delete was not visible to the other connection");
        }
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
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (table_created && first_statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(first_statement, reinterpret_cast<SQLCHAR*>(
                                              const_cast<char*>(drop.c_str())), SQL_NTS);
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
    std::cout << "C++ ODBC H2 L7 multinode integration test passed\n";
    return 0;
}

int run_integration_test(int argc, char** argv) {
    if (argc != 3) {
        throw std::runtime_error("expected H2 L7 CSV path and enable variable");
    }
    const std::string enable_variable = argv[2];
    if (!enabled(environment_value(enable_variable.c_str()))) {
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L7 suite\n";
        return 77;
    }
    const auto endpoints = split_endpoints(environment_value("OJP_TEST_H2_L7_ADDRS"));
    const std::string unavailable_endpoint =
        environment_value("OJP_TEST_H2_L7_UNAVAILABLE_ADDR").empty()
            ? "127.0.0.1:1" : environment_value("OJP_TEST_H2_L7_UNAVAILABLE_ADDR");
    if (std::find(endpoints.begin(), endpoints.end(), unavailable_endpoint) != endpoints.end()) {
        throw std::runtime_error("unavailable test endpoint must not be in OJP_TEST_H2_L7_ADDRS");
    }
    return ojp::test::run_connections(argv[1], [&](const DatabaseConfig& config) {
        return run_connection(config, unavailable_endpoint);
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
