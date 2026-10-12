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

void expect_rows_affected(SQLHSTMT statement, SQLLEN expected) {
    SQLLEN actual = -1;
    require_success(SQLRowCount(statement, &actual), "SQLRowCount", SQL_HANDLE_STMT, statement);
    expect(actual == expected, "expected " + std::to_string(expected) +
           " affected rows, got " + std::to_string(actual));
}

void expect_sqlstate(SQLHSTMT statement, const std::string& sql, const std::string& expected) {
    expect(SQLExecDirect(statement, sql_text(sql), SQL_NTS) == SQL_ERROR,
           "invalid SQL should return SQL_ERROR");
    SQLCHAR state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT detail_length = 0;
    require_success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, &native_error, detail,
                                  sizeof(detail), &detail_length),
                    "SQLGetDiagRec", SQL_HANDLE_STMT, statement);
    expect(expected == reinterpret_cast<const char*>(state),
           "expected SQLSTATE " + expected + ", got " +
               reinterpret_cast<const char*>(state));
    expect(detail_length > 0, "SQL error did not include a diagnostic message");
}

std::string get_text(SQLHSTMT statement, SQLUSMALLINT column) {
    SQLCHAR value[128] = {};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, SQL_C_CHAR, value, sizeof(value), &length),
                    "SQLGetData(text)", SQL_HANDLE_STMT, statement);
    expect(length >= 0 && length < static_cast<SQLLEN>(sizeof(value)),
           "text was NULL or truncated");
    return reinterpret_cast<const char*>(value);
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
                  << "=true to run the MariaDB L1 suite\n";
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
    const std::string table = "ojp_cpp_mariadb_l1_" + random_suffix();
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    bool table_created = false;
    try {
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
            ";P" "WD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "SELECT 1");
        require_success(SQLFetch(statement), "SQLFetch(readiness)", SQL_HANDLE_STMT, statement);
        SQLINTEGER readiness = 0;
        SQLLEN readiness_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &readiness,
                                   sizeof(readiness), &readiness_length),
                        "SQLGetData(readiness)", SQL_HANDLE_STMT, statement);
        expect(readiness == 1, "MariaDB readiness query did not return 1");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(readiness)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "CREATE TABLE " + table +
                                 " (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL) ENGINE=InnoDB");
        table_created = true;
        execute_direct(statement, "INSERT INTO " + table +
                                 " (id, name) VALUES (1, 'before')");
        expect_rows_affected(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT id, name FROM " + table + " WHERE id = 1");
        SQLSMALLINT column_count = 0;
        require_success(SQLNumResultCols(statement, &column_count),
                        "SQLNumResultCols", SQL_HANDLE_STMT, statement);
        expect(column_count == 2, "expected two result columns");
        require_success(SQLFetch(statement), "SQLFetch(select)", SQL_HANDLE_STMT, statement);
        SQLINTEGER actual_id = 0;
        SQLLEN id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &actual_id,
                                   sizeof(actual_id), &id_length),
                        "SQLGetData(id)", SQL_HANDLE_STMT, statement);
        expect(actual_id == 1 && get_text(statement, 2) == "before",
               "SELECT returned unexpected row values");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(select)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT CAST(NULL AS CHAR(10))");
        require_success(SQLFetch(statement), "SQLFetch(NULL)", SQL_HANDLE_STMT, statement);
        SQLCHAR null_value[16] = {};
        SQLLEN null_indicator = 0;
        require_success(SQLGetData(statement, 1, SQL_C_CHAR, null_value,
                                   sizeof(null_value), &null_indicator),
                        "SQLGetData(NULL)", SQL_HANDLE_STMT, statement);
        expect(null_indicator == SQL_NULL_DATA, "SQL NULL was not returned as SQL_NULL_DATA");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(NULL)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "UPDATE " + table + " SET name = 'after' WHERE id = 1");
        expect_rows_affected(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(update)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement, "SELECT name FROM " + table + " WHERE id = 1");
        require_success(SQLFetch(statement), "SQLFetch(updated row)", SQL_HANDLE_STMT, statement);
        expect(get_text(statement, 1) == "after", "UPDATE did not persist the new value");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(updated row)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "DELETE FROM " + table + " WHERE id = 1");
        expect_rows_affected(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(delete)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement, "SELECT id, name FROM " + table + " WHERE id = 1");
        require_success(SQLNumResultCols(statement, &column_count),
                        "SQLNumResultCols(empty)", SQL_HANDLE_STMT, statement);
        expect(column_count == 2 && SQLFetch(statement) == SQL_NO_DATA,
               "expected an empty two-column result after DELETE");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(empty result)",
                        SQL_HANDLE_STMT, statement);

        expect_sqlstate(statement, "THIS IS NOT VALID SQL", "42000");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(invalid SQL)",
                        SQL_HANDLE_STMT, statement);

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
        if (table_created && statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(statement, sql_text(drop), SQL_NTS);
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
    std::cout << "C++ ODBC MariaDB/MySQL L1 integration test passed\n";
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
