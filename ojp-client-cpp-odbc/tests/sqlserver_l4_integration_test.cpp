// Verifies SQL Server transaction, savepoint, and isolation behavior through ODBC.
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

void expect_execution_error(SQLHSTMT statement, const std::string& sql) {
    const auto result = SQLExecDirect(statement, sql_text(sql), SQL_NTS);
    expect(result == SQL_ERROR, "SQLExecDirect should have rejected the duplicate key");
}

void close_statement(SQLHSTMT statement, const std::string& operation) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), operation, SQL_HANDLE_STMT, statement);
}

SQLINTEGER row_count(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "SELECT COUNT(*) FROM " + table);
    require_success(SQLFetch(statement), "SQLFetch(count)", SQL_HANDLE_STMT, statement);
    SQLINTEGER count = 0;
    SQLLEN length = 0;
    require_success(SQLGetData(statement, 1, SQL_C_SLONG, &count, sizeof(count), &length),
                    "SQLGetData(count)", SQL_HANDLE_STMT, statement);
    close_statement(statement, "SQLFreeStmt(count)");
    return count;
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
                  << "=true to run the SQL Server L4 suite\n";
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
    const std::string table = "ojp_cpp_mssql_l4_tx_" + random_suffix();
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

        SQLULEN attribute = 0;
        require_success(SQLGetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT, &attribute,
                                          sizeof(attribute), nullptr),
                        "SQLGetConnectAttr(autocommit)", SQL_HANDLE_DBC, connection);
        expect(attribute == SQL_AUTOCOMMIT_ON, "new connections must start in autocommit mode");
        SQLUSMALLINT transaction_capability = 0;
        require_success(SQLGetInfo(connection, SQL_TXN_CAPABLE, &transaction_capability,
                                   sizeof(transaction_capability), nullptr),
                        "SQLGetInfo(transaction capability)", SQL_HANDLE_DBC, connection);
        expect(transaction_capability == SQL_TC_ALL,
               "the ODBC client must advertise transaction support");
        SQLUSMALLINT supports_end_transaction = SQL_FALSE;
        require_success(SQLGetFunctions(connection, SQL_API_SQLENDTRAN,
                                        &supports_end_transaction),
                        "SQLGetFunctions(SQLEndTran)", SQL_HANDLE_DBC, connection);
        expect(supports_end_transaction == SQL_TRUE,
               "the ODBC client must advertise SQLEndTran support");
        SQLUINTEGER isolation_options = 0;
        require_success(SQLGetInfo(connection, SQL_TXN_ISOLATION_OPTION, &isolation_options,
                                   sizeof(isolation_options), nullptr),
                        "SQLGetInfo(isolation options)", SQL_HANDLE_DBC, connection);
        expect((isolation_options & SQL_TXN_SERIALIZABLE) != 0,
               "the ODBC client must advertise serializable isolation");

        require_success(SQLSetConnectAttr(connection, SQL_ATTR_TXN_ISOLATION,
                                          reinterpret_cast<SQLPOINTER>(SQL_TXN_SERIALIZABLE),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(isolation)", SQL_HANDLE_DBC, connection);
        require_success(SQLGetConnectAttr(connection, SQL_ATTR_TXN_ISOLATION, &attribute,
                                          sizeof(attribute), nullptr),
                        "SQLGetConnectAttr(isolation)", SQL_HANDLE_DBC, connection);
        expect(attribute == SQL_TXN_SERIALIZABLE,
               "transaction isolation level did not round-trip");
        require_success(SQLSetConnectAttr(connection, SQL_ATTR_TXN_ISOLATION,
                                          reinterpret_cast<SQLPOINTER>(SQL_TXN_READ_COMMITTED),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(reset isolation)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "CREATE TABLE " + table +
                                 " (id INT PRIMARY KEY, label VARCHAR(32) NOT NULL)");
        require_success(SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                          reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_OFF),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(autocommit off)", SQL_HANDLE_DBC, connection);
        require_success(SQLGetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT, &attribute,
                                          sizeof(attribute), nullptr),
                        "SQLGetConnectAttr(autocommit off)", SQL_HANDLE_DBC, connection);
        expect(attribute == SQL_AUTOCOMMIT_OFF, "autocommit did not turn off");

        execute_direct(statement, "INSERT INTO " + table + " VALUES (1, 'rolled back')");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK),
                        "SQLEndTran(rollback)", SQL_HANDLE_DBC, connection);
        expect(row_count(statement, table) == 0, "rollback retained an uncommitted row");

        execute_direct(statement, "INSERT INTO " + table + " VALUES (7, 'preserved')");
        execute_direct(statement, "SAVEPOINT before_duplicate");
        expect_execution_error(statement, "INSERT INTO " + table + " VALUES (7, 'duplicate')");
        execute_direct(statement, "ROLLBACK TO SAVEPOINT before_duplicate");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (8, 'recovered')");
        expect(row_count(statement, table) == 2,
               "the transaction could not recover from a statement error");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT),
                        "SQLEndTran(recovery commit)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "INSERT INTO " + table + " VALUES (1, 'kept')");
        execute_direct(statement, "SAVEPOINT before_second");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (2, 'removed')");
        execute_direct(statement, "SAVE TRANSACTION before_third");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (3, 'removed')");
        execute_direct(statement, "ROLLBACK TRANSACTION before_third");
        expect(row_count(statement, table) == 4, "rollback to the nested savepoint failed");
        execute_direct(statement, "ROLLBACK TO SAVEPOINT before_second");
        expect(row_count(statement, table) == 3, "rollback to the earlier savepoint failed");

        execute_direct(statement, "SAVEPOINT released_point");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (4, 'kept')");
        const auto release_result =
            SQLExecDirect(statement, sql_text("RELEASE SAVEPOINT released_point"), SQL_NTS);
        expect(release_result == SQL_SUCCESS || release_result == SQL_SUCCESS_WITH_INFO ||
                   release_result == SQL_ERROR,
               "savepoint release returned an invalid ODBC status");
        execute_direct(statement, "INSERT INTO " + table + " VALUES (5, 'kept')");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT),
                        "SQLEndTran(commit)", SQL_HANDLE_DBC, connection);
        expect(row_count(statement, table) == 5, "commit did not persist the expected rows");

        execute_direct(statement, "INSERT INTO " + table + " VALUES (6, 'implicit commit')");
        require_success(SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                          reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_ON),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(autocommit on)", SQL_HANDLE_DBC, connection);
        expect(row_count(statement, table) == 6,
               "enabling autocommit did not commit the active transaction");

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
            if (connection != SQL_NULL_HDBC) {
                SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK);
                SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                  reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_ON),
                                  SQL_IS_UINTEGER);
            }
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
    std::cout << "C++ ODBC SQL Server L4 integration test passed\n";
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
