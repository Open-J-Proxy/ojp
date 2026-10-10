// Verifies MariaDB transaction, isolation, savepoint, and autocommit behavior through ODBC.
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

void require_sqlstate(SQLHSTMT statement, const std::string& sql,
                      const std::string& expected) {
    expect(SQLExecDirect(statement, sql_text(sql), SQL_NTS) == SQL_ERROR,
           "expected SQL_ERROR with SQLSTATE " + expected);
    SQLCHAR state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT detail_length = 0;
    require_success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, &native_error, detail,
                                  sizeof(detail), &detail_length),
                    "SQLGetDiagRec", SQL_HANDLE_STMT, statement);
    expect(expected == reinterpret_cast<const char*>(state),
           "expected SQLSTATE " + expected + ", got " + reinterpret_cast<const char*>(state));
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
    expect(length == sizeof(value), "SQLGetConnectAttr returned an incorrect value size");
    return value;
}

SQLINTEGER row_count(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "SELECT COUNT(*) FROM " + table);
    require_success(SQLFetch(statement), "SQLFetch(count)", SQL_HANDLE_STMT, statement);
    SQLINTEGER count = 0;
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &count, sizeof(count), &length),
                    "SQLGetData(count)", SQL_HANDLE_STMT, statement);
    expect(length == sizeof(count), "row count was NULL or had an incorrect size");
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(count)",
                    SQL_HANDLE_STMT, statement);
    return count;
}

std::string read_text(SQLHSTMT statement, const std::string& sql) {
    execute_direct(statement, sql);
    require_success(SQLFetch(statement), "SQLFetch(text)", SQL_HANDLE_STMT, statement);
    SQLCHAR value[64] = {};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_CHAR, value, sizeof(value), &length),
                    "SQLGetData(text)", SQL_HANDLE_STMT, statement);
    expect(length >= 0 && length < static_cast<SQLLEN>(sizeof(value)),
           "text was NULL or truncated");
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(text)",
                    SQL_HANDLE_STMT, statement);
    return reinterpret_cast<const char*>(value);
}

void end_transaction(SQLHDBC connection, SQLSMALLINT completion) {
    require_success(SQLEndTran(SQL_HANDLE_DBC, connection, completion), "SQLEndTran",
                    SQL_HANDLE_DBC, connection);
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error("expected MariaDB CSV path, enable variable, and endpoint variable");
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
                  << "=true to run the MariaDB L4 suite\n";
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
    const std::string table = "ojp_cpp_mariadb_l4_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHDBC observer = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    SQLHSTMT observer_statement = SQL_NULL_HSTMT;
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
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";P" "WD=" + brace_value(config.password) + ";";
        for (SQLHDBC* handle : {&connection, &observer}) {
            require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment, handle),
                            "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
            require_success(SQLDriverConnect(*handle, nullptr, sql_text(connection_string),
                                             SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                            "SQLDriverConnect", SQL_HANDLE_DBC, *handle);
        }
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, observer, &observer_statement),
                        "SQLAllocHandle(observer statement)", SQL_HANDLE_DBC, observer);

        expect(get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) == SQL_AUTOCOMMIT_ON,
               "new connections must start in autocommit mode");
        SQLUSMALLINT capability = SQL_TC_NONE;
        require_success(SQLGetInfo(connection, SQL_TXN_CAPABLE, &capability, sizeof(capability),
                                   nullptr),
                        "SQLGetInfo(transaction capability)", SQL_HANDLE_DBC, connection);
        expect(capability == SQL_TC_DML, "MariaDB should report transactional DML support");
        SQLUSMALLINT supports_end_transaction = SQL_FALSE;
        require_success(SQLGetFunctions(connection, SQL_API_SQLENDTRAN, &supports_end_transaction),
                        "SQLGetFunctions(SQLEndTran)", SQL_HANDLE_DBC, connection);
        expect(supports_end_transaction == SQL_TRUE, "SQLEndTran support must be advertised");

        SQLUINTEGER isolation_options = 0;
        require_success(SQLGetInfo(connection, SQL_TXN_ISOLATION_OPTION, &isolation_options,
                                   sizeof(isolation_options), nullptr),
                        "SQLGetInfo(isolation options)", SQL_HANDLE_DBC, connection);
        const SQLULEN original_isolation =
            get_connect_option(connection, SQL_ATTR_TXN_ISOLATION);
        for (const SQLULEN level : {SQL_TXN_READ_UNCOMMITTED, SQL_TXN_READ_COMMITTED,
                                    SQL_TXN_REPEATABLE_READ, SQL_TXN_SERIALIZABLE}) {
            expect((isolation_options & level) != 0, "required isolation is not advertised");
            set_connect_option(connection, SQL_ATTR_TXN_ISOLATION, level);
            expect(get_connect_option(connection, SQL_ATTR_TXN_ISOLATION) == level,
                   "transaction isolation did not round-trip");
        }
        set_connect_option(connection, SQL_ATTR_TXN_ISOLATION, original_isolation);

        execute_direct(statement, "CREATE TABLE " + table +
                                 " (id INT PRIMARY KEY, label VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        table_created = true;
        execute_direct(statement, "INSERT INTO " + table + " VALUES (1, 'initial')");
        set_connect_option(connection, SQL_ATTR_AUTOCOMMIT, SQL_AUTOCOMMIT_OFF);
        expect(get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) == SQL_AUTOCOMMIT_OFF,
               "autocommit did not turn off");

        execute_direct(statement, "UPDATE " + table + " SET label = 'committed' WHERE id = 1");
        expect(read_text(observer_statement, "SELECT label FROM " + table + " WHERE id = 1") ==
                   "initial",
               "uncommitted data was visible to another connection");
        end_transaction(connection, SQL_COMMIT);
        expect(read_text(observer_statement, "SELECT label FROM " + table + " WHERE id = 1") ==
                   "committed",
               "commit was not visible to another connection");

        execute_direct(statement, "UPDATE " + table + " SET label = 'rolled back' WHERE id = 1");
        end_transaction(connection, SQL_ROLLBACK);
        expect(read_text(statement, "SELECT label FROM " + table + " WHERE id = 1") == "committed",
               "rollback did not restore the committed value");
        end_transaction(connection, SQL_COMMIT);

        execute_direct(statement, "INSERT INTO " + table + " VALUES (2, 'kept')");
        execute_direct(statement, "SAVEPOINT before_discard");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (3, 'discarded')");
        execute_direct(statement, "ROLLBACK TO SAVEPOINT before_discard");
        expect(row_count(statement, table) == 2, "savepoint rollback retained a discarded row");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (4, 'released')");
        execute_direct(statement, "RELEASE SAVEPOINT before_discard");
        require_sqlstate(statement, "ROLLBACK TO SAVEPOINT before_discard", "3B001");
        end_transaction(connection, SQL_COMMIT);
        expect(row_count(observer_statement, table) == 3,
               "savepoint rollback or release persisted incorrect rows");

        execute_direct(statement, "INSERT INTO " + table + " VALUES (5, 'rolled back')");
        execute_direct(statement, "SAVEPOINT before_full_rollback");
        end_transaction(connection, SQL_ROLLBACK);
        require_sqlstate(statement, "ROLLBACK TO SAVEPOINT before_full_rollback", "3B001");
        expect(row_count(observer_statement, table) == 3,
               "full rollback retained an inserted row");

        execute_direct(statement, "INSERT INTO " + table + " VALUES (6, 'implicit commit')");
        set_connect_option(connection, SQL_ATTR_AUTOCOMMIT, SQL_AUTOCOMMIT_ON);
        expect(get_connect_option(connection, SQL_ATTR_AUTOCOMMIT) == SQL_AUTOCOMMIT_ON,
               "autocommit did not turn back on");
        expect(row_count(observer_statement, table) == 4,
               "enabling autocommit did not commit the active transaction");

        execute_direct(statement, "DROP TABLE " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, observer_statement),
                        "SQLFreeHandle(observer statement)", SQL_HANDLE_DBC, observer);
        observer_statement = SQL_NULL_HSTMT;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement), "SQLFreeHandle(statement)",
                        SQL_HANDLE_DBC, connection);
        statement = SQL_NULL_HSTMT;
        for (SQLHDBC* handle : {&observer, &connection}) {
            require_success(SQLDisconnect(*handle), "SQLDisconnect", SQL_HANDLE_DBC, *handle);
            require_success(SQLFreeHandle(SQL_HANDLE_DBC, *handle), "SQLFreeHandle(connection)",
                            SQL_HANDLE_ENV, environment);
            *handle = SQL_NULL_HDBC;
        }
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeStmt(statement, SQL_CLOSE);
            if (connection != SQL_NULL_HDBC) {
                SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK);
                SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                  reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_ON),
                                  SQL_IS_UINTEGER);
            }
            if (table_created) {
                const std::string drop = "DROP TABLE IF EXISTS " + table;
                SQLExecDirect(statement, sql_text(drop), SQL_NTS);
            }
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (observer_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, observer_statement);
        }
        for (SQLHDBC handle : {observer, connection}) {
            if (handle != SQL_NULL_HDBC) {
                SQLDisconnect(handle);
                SQLFreeHandle(SQL_HANDLE_DBC, handle);
            }
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC MariaDB L4 integration test passed\n";
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
