// Verifies SQL Server session affinity with local temporary tables through ODBC.
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

void close_statement(SQLHSTMT statement, const std::string& operation) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), operation, SQL_HANDLE_STMT, statement);
}

void verify_rows(SQLHSTMT statement,
                 const std::vector<std::pair<SQLINTEGER, std::string>>& expected_rows) {
    for (const auto& expected : expected_rows) {
        require_success(SQLFetch(statement), "SQLFetch", SQL_HANDLE_STMT, statement);
        SQLINTEGER id = 0;
        SQLLEN id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &id, sizeof(id), &id_length),
                        "SQLGetData(id)", SQL_HANDLE_STMT, statement);
        SQLCHAR label[64] = {};
        SQLLEN label_length = 0;
        require_success(SQLGetData(statement, 2, SQL_C_CHAR, label, sizeof(label), &label_length),
                        "SQLGetData(label)", SQL_HANDLE_STMT, statement);
        expect(id == expected.first,
               "SQL Server temporary-table rows were out of order");
        expect(std::string(reinterpret_cast<const char*>(label)) == expected.second,
               "SQL Server temporary-table data did not persist across statements");
    }
    expect(SQLFetch(statement) == SQL_NO_DATA,
           "SQL Server returned unexpected temporary-table rows");
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
                  << "=true to run the SQL Server L6 suite\n";
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
    const std::string table = "#ojp_cpp_mssql_l6_" + random_suffix();
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
                                  " (id INT PRIMARY KEY, label NVARCHAR(64) NOT NULL)");
        execute_direct(statement, "INSERT INTO " + table +
                                  " VALUES (1, N'Alice'), (2, N'Bob'), (3, N'Charlie')");
        execute_direct(statement, "UPDATE " + table +
                                  " SET label = N'Updated Bob' WHERE id = 2");
        execute_direct(statement, "SELECT id, label FROM " + table + " ORDER BY id");
        verify_rows(statement, {{1, "Alice"}, {2, "Updated Bob"}, {3, "Charlie"}});
        close_statement(statement, "SQLFreeStmt(select)");

        require_success(SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                          reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_OFF),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(autocommit off)", SQL_HANDLE_DBC, connection);
        execute_direct(statement, "INSERT INTO " + table + " VALUES (4, N'Committed')");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT),
                        "SQLEndTran(commit)", SQL_HANDLE_DBC, connection);
        execute_direct(statement, "SELECT id, label FROM " + table + " WHERE id = 4");
        verify_rows(statement, {{4, "Committed"}});
        close_statement(statement, "SQLFreeStmt(transaction select)");
        require_success(SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT),
                        "SQLEndTran(read transaction commit)", SQL_HANDLE_DBC, connection);

        require_success(SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                                          reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_ON),
                                          SQL_IS_UINTEGER),
                        "SQLSetConnectAttr(autocommit on)", SQL_HANDLE_DBC, connection);
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
            const std::string drop = "IF OBJECT_ID('tempdb.." + table +
                "') IS NOT NULL DROP TABLE " + table;
            SQLExecDirect(statement, sql_text(drop), SQL_NTS);
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (connection != SQL_NULL_HDBC) {
            SQLEndTran(SQL_HANDLE_DBC, connection, SQL_ROLLBACK);
            SQLDisconnect(connection);
            SQLFreeHandle(SQL_HANDLE_DBC, connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC SQL Server L6 integration test passed\n";
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
