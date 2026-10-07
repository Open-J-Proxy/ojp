// Verifies H2 transaction, isolation, and savepoint semantics through ODBC.
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

void set_connect_option(SQLHDBC connection, SQLINTEGER attribute, SQLULEN value) {
    require_success(SQLSetConnectAttr(connection, attribute,
                                      reinterpret_cast<SQLPOINTER>(
                                          static_cast<std::uintptr_t>(value)),
                                      SQL_IS_UINTEGER),
                    "SQLSetConnectAttr", SQL_HANDLE_DBC, connection);
}

SQLULEN get_connect_option(SQLHDBC connection, SQLINTEGER attribute) {
    SQLULEN value = 0;
    SQLINTEGER length = 0;
    require_success(SQLGetConnectAttr(connection, attribute, &value, sizeof(value), &length),
                    "SQLGetConnectAttr", SQL_HANDLE_DBC, connection);
    if (length != sizeof(value)) {
        throw std::runtime_error("SQLGetConnectAttr returned an incorrect value size");
    }
    return value;
}

std::string read_label(SQLHSTMT statement, const std::string& table, SQLINTEGER id) {
    execute_direct(statement, "SELECT label FROM " + table + " WHERE id = " + std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch(label)", SQL_HANDLE_STMT, statement);
    SQLCHAR label[64] = {};
    SQLLEN label_length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, label, sizeof(label), &label_length),
                    "SQLGetData(label)", SQL_HANDLE_STMT, statement);
    const std::string result(reinterpret_cast<const char*>(label));
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(label)",
                    SQL_HANDLE_STMT, statement);
    return result;
}

void require_missing_row(SQLHSTMT statement, const std::string& table, SQLINTEGER id) {
    execute_direct(statement, "SELECT id FROM " + table + " WHERE id = " + std::to_string(id));
    if (SQLFetch(statement) != SQL_NO_DATA) {
        throw std::runtime_error("a row that should have been rolled back is visible");
    }
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(missing row)",
                    SQL_HANDLE_STMT, statement);
}

void require_sqlstate(SQLRETURN result, SQLHSTMT statement, const std::string& expected_state) {
    if (SQL_SUCCEEDED(result)) {
        throw std::runtime_error("expected SQL_ERROR with state " + expected_state);
    }
    SQLCHAR state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT detail_length = 0;
    require_success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, &native_error, detail,
                                  sizeof(detail), &detail_length),
                    "SQLGetDiagRec", SQL_HANDLE_STMT, statement);
    if (expected_state != reinterpret_cast<const char*>(state)) {
        throw std::runtime_error("expected SQLSTATE " + expected_state + ", got " +
                                 reinterpret_cast<const char*>(state));
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
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L4 suite\n";
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
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    const std::string table = "ojp_cpp_l4_" + random_suffix();
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
        const std::string connection_string = "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
                                              ";DATABASE=" + brace_value(config.url) +
                                              ";UID=" + brace_value(config.user) +
                                              ";P" "WD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr,
                                         reinterpret_cast<SQLCHAR*>(
                                             const_cast<char*>(connection_string.c_str())),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        if (get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) != SQL_AUTOCOMMIT_ON) {
            throw std::runtime_error("ODBC connections must start in autocommit mode");
        }
        if (get_connect_option(connection, SQL_ATTR_TXN_ISOLATION) != SQL_TXN_READ_COMMITTED) {
            throw std::runtime_error("H2 default transaction isolation should be READ_COMMITTED");
        }
        SQLUSMALLINT transaction_capability = SQL_TC_NONE;
        SQLSMALLINT information_length = 0;
        require_success(SQLGetInfo(connection, SQL_TXN_CAPABLE, &transaction_capability,
                                   sizeof(transaction_capability), &information_length),
                        "SQLGetInfo(transaction capability)", SQL_HANDLE_DBC, connection);
        if (transaction_capability != SQL_TC_DML) {
            throw std::runtime_error("H2 transaction capability should report DML transactions");
        }
        set_connect_option(connection, SQL_ATTR_TXN_ISOLATION, SQL_TXN_SERIALIZABLE);
        if (get_connect_option(connection, SQL_ATTR_TXN_ISOLATION) != SQL_TXN_SERIALIZABLE) {
            throw std::runtime_error("transaction isolation did not change to SERIALIZABLE");
        }
        set_connect_option(connection, SQL_ATTR_TXN_ISOLATION, SQL_TXN_READ_COMMITTED);

        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, label VARCHAR(32) NOT NULL)");
        table_created = true;
        execute_direct(statement, "INSERT INTO " + table + " (id, label) VALUES (1, 'INITIAL')");

        set_connect_option(connection, SQL_ATTR_AUTOCOMMIT, SQL_AUTOCOMMIT_OFF);
        if (get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) != SQL_AUTOCOMMIT_OFF) {
            throw std::runtime_error("autocommit did not turn off");
        }
        execute_direct(statement, "UPDATE " + table + " SET label = 'COMMITTED' WHERE id = 1");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT), "SQLEndTran(commit)",
                        SQL_HANDLE_DBC, connection);
        execute_direct(statement, "UPDATE " + table + " SET label = 'ROLLED_BACK' WHERE id = 1");
        require_success(SQLTransact(environment, connection, SQL_ROLLBACK), "SQLTransact(rollback)",
                        SQL_HANDLE_DBC, connection);
        if (read_label(statement, table, 1) != "COMMITTED") {
            throw std::runtime_error("rollback did not restore the last committed row value");
        }

        execute_direct(statement, "INSERT INTO " + table + " (id, label) VALUES (2, 'KEEP')");
        execute_direct(statement, "SAVEPOINT ojp_l4_point");
        execute_direct(statement, "INSERT INTO " + table + " (id, label) VALUES (3, 'DISCARD')");
        execute_direct(statement, "ROLLBACK TO SAVEPOINT ojp_l4_point");
        require_missing_row(statement, table, 3);
        execute_direct(statement, "INSERT INTO " + table + " (id, label) VALUES (4, 'RELEASED')");
        execute_direct(statement, "RELEASE SAVEPOINT ojp_l4_point");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT), "SQLEndTran(savepoint commit)",
                        SQL_HANDLE_DBC, connection);
        if (read_label(statement, table, 2) != "KEEP" ||
            read_label(statement, table, 4) != "RELEASED") {
            throw std::runtime_error("savepoint rollback or release changed committed rows");
        }
        SQLRETURN stale_savepoint_result =
            SQLExecDirect(statement,
                          reinterpret_cast<SQLCHAR*>(const_cast<char*>(
                              "ROLLBACK TO SAVEPOINT ojp_l4_point")),
                          SQL_NTS);
        require_sqlstate(stale_savepoint_result, statement, "3B001");

        execute_direct(statement, "UPDATE " + table +
                                  " SET label = 'AUTOCOMMIT_COMMITTED' WHERE id = 1");
        set_connect_option(connection, SQL_ATTR_AUTOCOMMIT, SQL_AUTOCOMMIT_ON);
        if (get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) != SQL_AUTOCOMMIT_ON) {
            throw std::runtime_error("autocommit did not turn back on");
        }
        if (read_label(statement, table, 1) != "AUTOCOMMIT_COMMITTED") {
            throw std::runtime_error("enabling autocommit did not commit the active transaction");
        }
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
    std::cout << "C++ ODBC H2 L4 integration test passed\n";
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
