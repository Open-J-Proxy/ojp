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

void verify_result_rows(SQLHSTMT statement, SQLINTEGER expected_rows) {
    SQLSMALLINT column_count = 0;
    require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols", SQL_HANDLE_STMT,
                    statement);
    if (column_count != 2) {
        throw std::runtime_error("expected two columns in the streamed result set");
    }

    SQLCHAR column_name[32] = {};
    SQLSMALLINT name_length = 0;
    SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
    SQLULEN column_size = 0;
    SQLSMALLINT decimal_digits = 0;
    SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
    require_success(SQLDescribeCol(statement, 1, column_name, sizeof(column_name), &name_length,
                                   &data_type, &column_size, &decimal_digits, &nullable),
                    "SQLDescribeCol", SQL_HANDLE_STMT, statement);
    if (std::string(reinterpret_cast<const char*>(column_name)) != "ID") {
        throw std::runtime_error("streamed result metadata returned the wrong first column");
    }

    SQLINTEGER count = 0;
    while (true) {
        const auto fetch_result = SQLFetch(statement);
        if (fetch_result == SQL_NO_DATA) {
            break;
        }
        require_success(fetch_result, "SQLFetch", SQL_HANDLE_STMT, statement);

        SQLINTEGER id = 0;
        SQLLEN id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &id, sizeof(id), &id_length),
                        "SQLGetData(id)", SQL_HANDLE_STMT, statement);
        SQLCHAR label[64] = {};
        SQLLEN label_length = 0;
        require_success(SQLGetData(statement, 2, SQL_C_CHAR, label, sizeof(label), &label_length),
                        "SQLGetData(label)", SQL_HANDLE_STMT, statement);
        ++count;
        if (id != count || std::string(reinterpret_cast<const char*>(label)) !=
                                   "H2_ROW_" + std::to_string(count)) {
            throw std::runtime_error(
                    "streamed result rows were missing, duplicated, or out of order");
        }
    }
    if (count != expected_rows) {
        throw std::runtime_error("expected " + std::to_string(expected_rows) +
                                 " streamed rows, received " + std::to_string(count));
    }
    if (SQLFetch(statement) != SQL_NO_DATA) {
        throw std::runtime_error(
                "fetching past the end of the result set did not return SQL_NO_DATA");
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
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L3 suite\n";
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
    constexpr SQLINTEGER total_rows = 10001;
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    const std::string table = "ojp_cpp_l3_" + random_suffix();
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
                                              ";P"
                                              "WD=" +
                                              brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr,
                                         reinterpret_cast<SQLCHAR*>(
                                                 const_cast<char*>(connection_string.c_str())),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "CREATE TABLE " + table +
                                          " (id INT PRIMARY KEY, label VARCHAR(32) NOT NULL)");
        table_created = true;
        execute_direct(statement,
                       "INSERT INTO " + table +
                               " (id, label) SELECT CAST(X AS INT), CONCAT('H2_ROW_', X) "
                               "FROM SYSTEM_RANGE(1, " +
                               std::to_string(total_rows) + ")");
        SQLLEN affected_rows = -1;
        require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount", SQL_HANDLE_STMT,
                        statement);
        if (affected_rows != total_rows) {
            throw std::runtime_error("expected all H2 L3 rows to be inserted");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert)", SQL_HANDLE_STMT,
                        statement);

        execute_direct(statement, "SELECT id AS ID, label FROM " + table + " ORDER BY id");
        require_success(SQLFetch(statement), "SQLFetch(first row)", SQL_HANDLE_STMT, statement);
        SQLINTEGER first_id = 0;
        SQLLEN first_id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &first_id, sizeof(first_id),
                                   &first_id_length),
                        "SQLGetData(first id)", SQL_HANDLE_STMT, statement);
        if (first_id != 1) {
            throw std::runtime_error("the first streamed row had the wrong ID");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(partial result)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT id AS ID, label FROM " + table + " ORDER BY id");
        verify_result_rows(statement, total_rows);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(streamed result)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT id AS ID, label FROM " + table + " WHERE id < 0");
        verify_result_rows(statement, 0);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(empty result)",
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
    std::cout << "C++ ODBC H2 L3 integration test passed\n";
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
