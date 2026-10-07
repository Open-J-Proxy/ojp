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
    suffix << std::hex;
    for (int index = 0; index < 6; ++index) {
        suffix << static_cast<unsigned int>(random() & 0xff);
    }
    return suffix.str();
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement,
        reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())), SQL_NTS),
        "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void bind_integer(SQLHSTMT statement, SQLUSMALLINT index, SQLINTEGER* value,
                  SQLLEN* indicator) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, SQL_C_SLONG,
                                     SQL_INTEGER, 0, 0, value, sizeof(*value), indicator),
                    "SQLBindParameter(integer)", SQL_HANDLE_STMT, statement);
}

void assert_text(SQLHSTMT statement, SQLUSMALLINT column, const std::string& expected) {
    SQLCHAR actual[128] = {};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, SQL_C_CHAR, actual, sizeof(actual), &length),
                    "SQLGetData(text)", SQL_HANDLE_STMT, statement);
    if (std::string(reinterpret_cast<const char*>(actual)) != expected) {
        throw std::runtime_error("expected '" + expected + "', got '" +
                                 reinterpret_cast<const char*>(actual) + "'");
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
    std::transform(enabled.begin(), enabled.end(), enabled.begin(),
        [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the H2 L2 suite\n";
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
    const std::string table = "ojp_cpp_l2_" + random_suffix();
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

        execute_direct(statement, "CREATE TABLE " + table +
            " (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
            " amount DECIMAL(12, 3) NOT NULL,"
            " business_date DATE NOT NULL, business_time TIME NOT NULL,"
            " created_at TIMESTAMP NOT NULL)");
        table_created = true;

        const std::string insert = "INSERT INTO " + table +
            " (amount, business_date, business_time, created_at) VALUES (?, ?, ?, ?)";
        require_success(SQLPrepare(statement,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(insert.c_str())), SQL_NTS),
            "SQLPrepare(insert)", SQL_HANDLE_STMT, statement);
        SQL_NUMERIC_STRUCT amount{};
        amount.precision = 12;
        amount.scale = 3;
        amount.sign = 1;
        amount.val[0] = 0x87;
        amount.val[1] = 0xd6;
        amount.val[2] = 0x12;
        SQLLEN amount_length = sizeof(amount);
        require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_NUMERIC,
                                         SQL_NUMERIC, 12, 3, &amount, sizeof(amount),
                                         &amount_length),
                        "SQLBindParameter(decimal)", SQL_HANDLE_STMT, statement);

        SQL_DATE_STRUCT date{2026, 10, 7};
        SQLLEN date_length = sizeof(date);
        require_success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_TYPE_DATE,
                                         SQL_TYPE_DATE, 0, 0, &date, sizeof(date), &date_length),
                        "SQLBindParameter(date)", SQL_HANDLE_STMT, statement);
        SQL_TIME_STRUCT time{12, 34, 56};
        SQLLEN time_length = sizeof(time);
        require_success(SQLBindParameter(statement, 3, SQL_PARAM_INPUT, SQL_C_TYPE_TIME,
                                         SQL_TYPE_TIME, 0, 0, &time, sizeof(time), &time_length),
                        "SQLBindParameter(time)", SQL_HANDLE_STMT, statement);
        SQL_TIMESTAMP_STRUCT timestamp{2026, 10, 7, 12, 34, 56, 123000000};
        SQLLEN timestamp_length = sizeof(timestamp);
        require_success(SQLBindParameter(statement, 4, SQL_PARAM_INPUT, SQL_C_TYPE_TIMESTAMP,
                                         SQL_TYPE_TIMESTAMP, 0, 0, &timestamp,
                                         sizeof(timestamp), &timestamp_length),
                        "SQLBindParameter(timestamp)", SQL_HANDLE_STMT, statement);
        require_success(SQLExecute(statement), "SQLExecute(insert)", SQL_HANDLE_STMT, statement);
        SQLLEN affected_rows = -1;
        require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount",
                        SQL_HANDLE_STMT, statement);
        if (affected_rows != 1) {
            throw std::runtime_error("expected one inserted L2 row");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert)",
                        SQL_HANDLE_STMT, statement);
        require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(parameters)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "SELECT MAX(id) FROM " + table);
        require_success(SQLFetch(statement), "SQLFetch(generated key)", SQL_HANDLE_STMT, statement);
        SQLBIGINT generated_id = 0;
        SQLLEN generated_id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SBIGINT, &generated_id,
                                   sizeof(generated_id), &generated_id_length),
                        "SQLGetData(generated key)", SQL_HANDLE_STMT, statement);
        if (generated_id < 1) {
            throw std::runtime_error("H2 did not return the generated identity value");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(identity)",
                        SQL_HANDLE_STMT, statement);

        const std::string select = "SELECT id AS ID, CAST(amount AS VARCHAR) AS AMOUNT_TEXT,"
            " business_date, business_time, created_at FROM " + table + " WHERE id = ?";
        require_success(SQLPrepare(statement,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(select.c_str())), SQL_NTS),
            "SQLPrepare(select)", SQL_HANDLE_STMT, statement);
        SQLINTEGER id_parameter = static_cast<SQLINTEGER>(generated_id);
        SQLLEN id_parameter_length = sizeof(id_parameter);
        bind_integer(statement, 1, &id_parameter, &id_parameter_length);
        require_success(SQLExecute(statement), "SQLExecute(select)", SQL_HANDLE_STMT, statement);
        SQLSMALLINT column_count = 0;
        require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols",
                        SQL_HANDLE_STMT, statement);
        if (column_count != 5) {
            throw std::runtime_error("expected five L2 result columns");
        }
        SQLCHAR column_name[64] = {};
        SQLSMALLINT name_length = 0;
        SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
        SQLULEN column_size = 0;
        SQLSMALLINT decimal_digits = 0;
        SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
        require_success(SQLDescribeCol(statement, 1, column_name, sizeof(column_name),
                                       &name_length, &data_type, &column_size,
                                       &decimal_digits, &nullable),
                        "SQLDescribeCol", SQL_HANDLE_STMT, statement);
        if (std::string(reinterpret_cast<const char*>(column_name)) != "ID" ||
            data_type != SQL_BIGINT) {
            throw std::runtime_error("basic result metadata returned the wrong ID column");
        }
        require_success(SQLFetch(statement), "SQLFetch(select)", SQL_HANDLE_STMT, statement);
        SQLBIGINT selected_id = 0;
        SQLLEN selected_id_length = 0;
        require_success(SQLGetData(statement, 1, SQL_C_SBIGINT, &selected_id,
                                   sizeof(selected_id), &selected_id_length),
                        "SQLGetData(id)", SQL_HANDLE_STMT, statement);
        if (selected_id != generated_id) {
            throw std::runtime_error("prepared SELECT returned the wrong identity");
        }
        assert_text(statement, 2, "1234.567");
        assert_text(statement, 3, "2026-10-07");
        assert_text(statement, 4, "12:34:56");
        assert_text(statement, 5, "2026-10-07 12:34:56.123");
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(select)",
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
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
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
            SQLDisconnect(connection);
            SQLFreeHandle(SQL_HANDLE_DBC, connection);
        }
        if (environment != SQL_NULL_HENV) {
            SQLFreeHandle(SQL_HANDLE_ENV, environment);
        }
        throw;
    }
    std::cout << "C++ ODBC H2 L2 integration test passed\n";
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
