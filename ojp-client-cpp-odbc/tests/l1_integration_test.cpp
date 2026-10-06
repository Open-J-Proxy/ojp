#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iomanip>
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

std::vector<std::string> parse_csv_record(const std::string& line) {
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
        } else if (character == ',' && !quoted) {
            fields.push_back(field);
            field.clear();
        } else if (character == '"' && field.empty()) {
            quoted = true;
        } else {
            field.push_back(character);
        }
    }
    if (quoted) {
        throw std::runtime_error("database connection CSV contains an unterminated quoted field");
    }
    fields.push_back(field);
    return fields;
}

DatabaseConfig read_connection_config(const std::string& path) {
    std::ifstream input(path);
    if (!input) {
        throw std::runtime_error("cannot open the database connection CSV");
    }
    std::string line;
    if (!std::getline(input, line)) {
        throw std::runtime_error("database connection CSV is empty");
    }
    if (!line.empty() && line.back() == '\r') {
        line.pop_back();
    }
    const auto fields = parse_csv_record(line);
    if (fields.size() != 3 || fields[0].empty()) {
        throw std::runtime_error("expected one CSV record with JDBC URL, username, and password");
    }
    std::string extra;
    if (std::getline(input, extra)) {
        throw std::runtime_error("expected exactly one database connection CSV record");
    }
    return {fields[0], fields[1], fields[2]};
}

std::string brace_value(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size());
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

std::string random_suffix() {
    std::random_device random;
    std::ostringstream suffix;
    suffix << std::hex << std::setfill('0');
    for (int index = 0; index < 6; ++index) {
        suffix << std::setw(2) << (random() & 0xff);
    }
    return suffix.str();
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement,
                                  reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())),
                                  SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void assert_affected_rows(SQLHSTMT statement, SQLLEN expected) {
    SQLLEN actual = -1;
    require_success(SQLRowCount(statement, &actual), "SQLRowCount", SQL_HANDLE_STMT, statement);
    if (actual != expected) {
        throw std::runtime_error("expected " + std::to_string(expected) +
                                 " affected rows, got " + std::to_string(actual));
    }
}

void prepare_integer_parameter(SQLHSTMT statement, SQLINTEGER* value, SQLLEN* indicator) {
    require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG,
                                     SQL_INTEGER, 0, 0, value, sizeof(*value), indicator),
                    "SQLBindParameter(integer)", SQL_HANDLE_STMT, statement);
}

void assert_sql_error(SQLHSTMT statement, const std::string& sql, const char* sql_state) {
    const auto result = SQLExecDirect(statement,
        reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())), SQL_NTS);
    if (result != SQL_ERROR) {
        throw std::runtime_error("expected invalid SQL to return SQL_ERROR");
    }
    SQLCHAR actual_state[6] = {};
    SQLCHAR message[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT message_length = 0;
    require_success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, actual_state,
                                  &native_error, message, sizeof(message), &message_length),
                    "SQLGetDiagRec", SQL_HANDLE_STMT, statement);
    if (std::string(reinterpret_cast<const char*>(actual_state)) != sql_state ||
        message_length == 0) {
        throw std::runtime_error("expected SQLSTATE " + std::string(sql_state) +
                                 " with a diagnostic message, got " +
                                 std::string(reinterpret_cast<const char*>(actual_state)) +
                                 " and vendor code " + std::to_string(native_error) +
                                 ": " + reinterpret_cast<const char*>(message));
    }
}

int run_integration_test(int argc, char** argv) {
    if (argc != 6) {
        throw std::runtime_error(
            "expected database name, CSV path, enable variable, endpoint variable, and SQLSTATE");
    }
    const std::string database = argv[1];
    const std::string csv_path = argv[2];
    const std::string enable_variable = argv[3];
    const std::string endpoint_variable = argv[4];
    const std::string expected_sql_state = argv[5];
    const char* enabled = std::getenv(enable_variable.c_str());
    std::string enabled_value = enabled == nullptr ? "" : enabled;
    std::transform(enabled_value.begin(), enabled_value.end(), enabled_value.begin(),
        [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
    const auto first = enabled_value.find_first_not_of(" \t\r\n");
    const auto last = enabled_value.find_last_not_of(" \t\r\n");
    enabled_value = first == std::string::npos
        ? "" : enabled_value.substr(first, last - first + 1);
    if (enabled_value.empty() || enabled_value == "false" || enabled_value == "0" ||
        enabled_value == "no") {
        std::cout << "Skipped: set " << enable_variable
                  << "=true to run the real-server " << database << " L1 suite\n";
        return 77;
    }
    if (enabled_value != "true" && enabled_value != "1" && enabled_value != "yes") {
        throw std::runtime_error(enable_variable + " must be true or false");
    }
    const char* endpoint_value = std::getenv(endpoint_variable.c_str());
    std::string endpoint = endpoint_value == nullptr ? "" : endpoint_value;
    const auto endpoint_first = endpoint.find_first_not_of(" \t\r\n");
    const auto endpoint_last = endpoint.find_last_not_of(" \t\r\n");
    endpoint = endpoint_first == std::string::npos
        ? "" : endpoint.substr(endpoint_first, endpoint_last - endpoint_first + 1);
    if (endpoint.empty()) {
        throw std::runtime_error(endpoint_variable + " is required when " +
                                 enable_variable + "=true");
    }

    const DatabaseConfig config = read_connection_config(csv_path);
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    std::string table = "ojp_cpp_l1_" + random_suffix();
    bool table_created = false;
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
        require_success(SQLDriverConnect(connection, nullptr,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(connection_string.c_str())),
            SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
            "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        execute_direct(statement, "SELECT 1");
        require_success(SQLFetch(statement), "SQLFetch(readiness)", SQL_HANDLE_STMT, statement);
        SQLINTEGER readiness = 0;
        SQLLEN readiness_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &readiness,
                                   sizeof(readiness), &readiness_length),
                        "SQLGetData(readiness)", SQL_HANDLE_STMT, statement);
        if (readiness != 1) {
            throw std::runtime_error(database + " readiness query did not return 1");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(readiness)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        table_created = true;

        SQLINTEGER row_id = 1;
        SQLLEN id_length = sizeof(row_id);
        char name[] = "before";
        SQLLEN name_length = SQL_NTS;
        const std::string insert = "INSERT INTO " + table + " (id, name) VALUES (?, ?)";
        require_success(SQLPrepare(statement,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(insert.c_str())), SQL_NTS),
            "SQLPrepare(insert)", SQL_HANDLE_STMT, statement);
        prepare_integer_parameter(statement, &row_id, &id_length);
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_CHAR,
                                         SQL_VARCHAR, 100, 0, name, sizeof(name), &name_length),
                        "SQLBindParameter(name)", SQL_HANDLE_STMT, statement);
        require_success(SQLExecute(statement), "SQLExecute(insert)", SQL_HANDLE_STMT, statement);
        assert_affected_rows(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert)",
                        SQL_HANDLE_STMT, statement);

        const std::string select = "SELECT id, name FROM " + table + " WHERE id = ?";
        require_success(SQLPrepare(statement,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(select.c_str())), SQL_NTS),
            "SQLPrepare(select)", SQL_HANDLE_STMT, statement);
        prepare_integer_parameter(statement, &row_id, &id_length);
        SQLINTEGER bound_id = 0;
        SQLLEN bound_id_length = 0;
        SQLCHAR bound_name[64] = {};
        SQLLEN bound_name_length = 0;
        require_success(SQLBindCol(statement, 1, SQL_C_SLONG, &bound_id,
                                   sizeof(bound_id), &bound_id_length),
                        "SQLBindCol(id)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindCol(statement, 2, SQL_C_CHAR, bound_name,
                                   sizeof(bound_name), &bound_name_length),
                        "SQLBindCol(name)", SQL_HANDLE_STMT, statement);
        require_success(SQLExecute(statement), "SQLExecute(select)", SQL_HANDLE_STMT, statement);
        SQLSMALLINT column_count = 0;
        require_success(SQLNumResultCols(statement, &column_count),
                        "SQLNumResultCols", SQL_HANDLE_STMT, statement);
        if (column_count != 2) {
            throw std::runtime_error("expected two result columns");
        }
        require_success(SQLFetch(statement), "SQLFetch(select)", SQL_HANDLE_STMT, statement);
        SQLINTEGER actual_id = 0;
        SQLLEN actual_id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SLONG, &actual_id,
                                   sizeof(actual_id), &actual_id_length),
                        "SQLGetData(id)", SQL_HANDLE_STMT, statement);
        SQLCHAR actual_name[64] = {};
        SQLLEN actual_name_length = 0;
        require_success(SQLGetData(statement, 2, SQL_C_CHAR, actual_name,
                                   sizeof(actual_name), &actual_name_length),
                        "SQLGetData(name)", SQL_HANDLE_STMT, statement);
        if (actual_id != row_id || bound_id != row_id ||
            std::string(reinterpret_cast<const char*>(bound_name)) != "before" ||
            std::string(reinterpret_cast<const char*>(actual_name)) != "before") {
            throw std::runtime_error("SELECT returned unexpected L1 row values");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(select)",
                        SQL_HANDLE_STMT, statement);
        require_success(SQLFreeStmt(statement, SQL_UNBIND), "SQLFreeStmt(unbind)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT CAST(NULL AS VARCHAR(10))");
        require_success(SQLFetch(statement), "SQLFetch(NULL)", SQL_HANDLE_STMT, statement);
        SQLCHAR null_value[16] = {};
        SQLLEN null_indicator = 0;
        require_success(SQLGetData(statement, 1, SQL_C_CHAR, null_value,
                                   sizeof(null_value), &null_indicator),
                        "SQLGetData(NULL)", SQL_HANDLE_STMT, statement);
        if (null_indicator != SQL_NULL_DATA) {
            throw std::runtime_error("SQL NULL was not returned as SQL_NULL_DATA");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(NULL)",
                        SQL_HANDLE_STMT, statement);

        char updated_name[] = "after";
        name_length = SQL_NTS;
        const std::string update = "UPDATE " + table + " SET name = ? WHERE id = ?";
        require_success(SQLPrepare(statement,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(update.c_str())), SQL_NTS),
            "SQLPrepare(update)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_CHAR,
                                         SQL_VARCHAR, 100, 0, updated_name,
                                         sizeof(updated_name), &name_length),
                        "SQLBindParameter(updated name)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_SLONG,
                                         SQL_INTEGER, 0, 0, &row_id, sizeof(row_id), &id_length),
                        "SQLBindParameter(update id)", SQL_HANDLE_STMT, statement);
        require_success(SQLExecute(statement), "SQLExecute(update)", SQL_HANDLE_STMT, statement);
        assert_affected_rows(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(update)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "DELETE FROM " + table + " WHERE id = 1");
        assert_affected_rows(statement, 1);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(delete)",
                        SQL_HANDLE_STMT, statement);
        execute_direct(statement, "SELECT id, name FROM " + table + " WHERE id = 1");
        require_success(SQLNumResultCols(statement, &column_count),
                        "SQLNumResultCols(empty)", SQL_HANDLE_STMT, statement);
        if (column_count != 2 || SQLFetch(statement) != SQL_NO_DATA) {
            throw std::runtime_error("expected an empty two-column result after DELETE");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(empty select)",
                        SQL_HANDLE_STMT, statement);

        assert_sql_error(statement, "THIS IS NOT VALID SQL", expected_sql_state.c_str());
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(invalid SQL)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "DROP TABLE IF EXISTS " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, statement),
                        "SQLFreeHandle(statement)", SQL_HANDLE_DBC, connection);
        statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(connection), "SQLDisconnect", SQL_HANDLE_DBC, connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, connection),
                        "SQLFreeHandle(connection)", SQL_HANDLE_ENV, environment);
        connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment),
                        "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (...) {
        if (table_created && statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(
                const_cast<char*>(drop.c_str())), SQL_NTS);
        }
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (connection != SQL_NULL_HDBC) {
            if (SQLDisconnect(connection) != SQL_SUCCESS) {
                // SQLFreeHandle also attempts session termination.
            }
            SQLFreeHandle(SQL_HANDLE_DBC, connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC L1 " << database << " integration test passed\n";
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
