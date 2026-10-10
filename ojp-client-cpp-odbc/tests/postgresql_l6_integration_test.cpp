// Verifies PostgreSQL session affinity for temporary tables and session state
// through ODBC.
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
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS), "SQLExecDirect",
                    SQL_HANDLE_STMT, statement);
}

void close_statement(SQLHSTMT statement, const std::string& operation) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), operation, SQL_HANDLE_STMT, statement);
}

std::string read_text(SQLHSTMT statement, const std::string& sql) {
    execute_direct(statement, sql);
    require_success(SQLFetch(statement), "SQLFetch(text)", SQL_HANDLE_STMT, statement);
    SQLCHAR value[128] = {};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, value, sizeof(value), &length),
                    "SQLGetData(text)", SQL_HANDLE_STMT, statement);
    expect(length >= 0 && length < static_cast<SQLLEN>(sizeof(value)),
           "text was NULL or truncated");
    const std::string result(reinterpret_cast<const char*>(value));
    expect(SQLFetch(statement) == SQL_NO_DATA, "text query returned more than one row");
    close_statement(statement, "SQLFreeStmt(text)");
    return result;
}

void verify_label(SQLHSTMT statement, const std::string& table, int id,
                  const std::string& expected) {
    expect(read_text(statement, "SELECT label FROM " + table +
                                    " WHERE id = " + std::to_string(id)) == expected,
           "PostgreSQL temporary-table value was not preserved across requests");
}

SQLINTEGER row_count(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "SELECT COUNT(*) FROM " + table);
    require_success(SQLFetch(statement), "SQLFetch(count)", SQL_HANDLE_STMT, statement);
    SQLINTEGER count = 0;
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &count, sizeof(count), &length),
                    "SQLGetData(count)", SQL_HANDLE_STMT, statement);
    expect(length == sizeof(count), "row count was NULL or had an incorrect size");
    close_statement(statement, "SQLFreeStmt(count)");
    return count;
}

void set_autocommit(SQLHDBC connection, SQLULEN value) {
    require_success(
        SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                          reinterpret_cast<SQLPOINTER>(static_cast<std::uintptr_t>(value)),
                          SQL_IS_UINTEGER),
        "SQLSetConnectAttr(autocommit)", SQL_HANDLE_DBC, connection);
}

void end_transaction(SQLHDBC connection, SQLSMALLINT completion) {
    require_success(SQLEndTran(SQL_HANDLE_DBC, connection, completion), "SQLEndTran",
                    SQL_HANDLE_DBC, connection);
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error(
            "expected PostgreSQL CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the PostgreSQL L6 session-affinity suite\n";
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
    const std::string table = "ojp_cpp_pg_l6_" + random_suffix();
    const std::string session_marker = "ojp_l6_" + random_suffix();
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
        const std::string connection_string = "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
                                              ";DATABASE=" + brace_value(config.url) +
                                              ";UID=" + brace_value(config.user) +
                                              ";P"
                                              "WD=" +
                                              brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string), SQL_NTS,
                                         nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "CREATE TEMPORARY TABLE " + table +
                                      " (id INT PRIMARY KEY, label VARCHAR(100)) "
                                      "ON COMMIT PRESERVE ROWS");
        table_created = true;
        execute_direct(statement, "INSERT INTO " + table +
                                      " VALUES (1, 'initial'), (2, 'second'), (3, 'third')");
        execute_direct(statement, "UPDATE " + table + " SET label = 'updated' WHERE id = 2");
        expect(row_count(statement, table) == 3,
               "PostgreSQL temporary table rows were not visible across requests");
        verify_label(statement, table, 1, "initial");
        verify_label(statement, table, 2, "updated");
        verify_label(statement, table, 3, "third");

        set_autocommit(connection, SQL_AUTOCOMMIT_OFF);
        execute_direct(statement, "SET LOCAL application_name = '" + session_marker + "'");
        expect(read_text(statement, "SELECT current_setting('application_name')") == session_marker,
               "PostgreSQL transaction-local session state was not preserved "
               "across requests");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (4, 'committed')");
        end_transaction(connection, SQL_COMMIT);
        expect(read_text(statement, "SELECT current_setting('application_name')") != session_marker,
               "PostgreSQL SET LOCAL state leaked beyond its transaction");
        verify_label(statement, table, 4, "committed");

        execute_direct(statement, "UPDATE " + table + " SET label = 'rolled back' WHERE id = 4");
        end_transaction(connection, SQL_ROLLBACK);
        verify_label(statement, table, 4, "committed");
        expect(row_count(statement, table) == 4,
               "PostgreSQL temporary table did not persist correctly across "
               "transactions");
        end_transaction(connection, SQL_COMMIT);

        set_autocommit(connection, SQL_AUTOCOMMIT_ON);
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
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeStmt(statement, SQL_CLOSE);
            if (connection != SQL_NULL_HDBC) {
                SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK);
                SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                  reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_ON), SQL_IS_UINTEGER);
            }
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
    std::cout << "C++ ODBC PostgreSQL L6 integration test passed\n";
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
