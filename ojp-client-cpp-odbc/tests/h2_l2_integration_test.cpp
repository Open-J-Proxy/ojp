// Verifies H2 typed parameters, prepared queries, generated IDs, and column metadata through ODBC.
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
            " created_at TIMESTAMP NOT NULL, tiny_value TINYINT NOT NULL,"
            " small_value SMALLINT NOT NULL, integer_value INTEGER NOT NULL,"
            " long_value BIGINT NOT NULL, real_value REAL NOT NULL,"
            " double_value DOUBLE NOT NULL, boolean_value BOOLEAN NOT NULL,"
            " text_value VARCHAR(40) NOT NULL, binary_value VARBINARY(3) NOT NULL)");
        table_created = true;

        const std::string insert = "INSERT INTO " + table +
            " (amount, business_date, business_time, created_at, tiny_value, small_value,"
            " integer_value, long_value, real_value, double_value, boolean_value, text_value,"
            " binary_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
        SQLSCHAR tiny_value = 127;
        SQLLEN tiny_length = sizeof(tiny_value);
        require_success(SQLBindParameter(statement, 5, SQL_PARAM_INPUT, SQL_C_STINYINT,
                                         SQL_TINYINT, 0, 0, &tiny_value, sizeof(tiny_value),
                                         &tiny_length),
                        "SQLBindParameter(tiny integer)", SQL_HANDLE_STMT, statement);
        SQLSMALLINT small_value = 32767;
        SQLLEN small_length = sizeof(small_value);
        require_success(SQLBindParameter(statement, 6, SQL_PARAM_INPUT, SQL_C_SSHORT,
                                         SQL_SMALLINT, 0, 0, &small_value, sizeof(small_value),
                                         &small_length),
                        "SQLBindParameter(small integer)", SQL_HANDLE_STMT, statement);
        SQLINTEGER integer_value = 123456;
        SQLLEN integer_length = sizeof(integer_value);
        bind_integer(statement, 7, &integer_value, &integer_length);
        SQLBIGINT long_value = 33333333333333LL;
        SQLLEN long_length = sizeof(long_value);
        require_success(SQLBindParameter(statement, 8, SQL_PARAM_INPUT, SQL_C_SBIGINT,
                                         SQL_BIGINT, 0, 0, &long_value, sizeof(long_value),
                                         &long_length),
                        "SQLBindParameter(big integer)", SQL_HANDLE_STMT, statement);
        SQLREAL real_value = 20.25F;
        SQLLEN real_length = sizeof(real_value);
        require_success(SQLBindParameter(statement, 9, SQL_PARAM_INPUT, SQL_C_FLOAT,
                                         SQL_REAL, 0, 0, &real_value, sizeof(real_value),
                                         &real_length),
                        "SQLBindParameter(real)", SQL_HANDLE_STMT, statement);
        SQLDOUBLE double_value = 2.2222;
        SQLLEN double_length = sizeof(double_value);
        require_success(SQLBindParameter(statement, 10, SQL_PARAM_INPUT, SQL_C_DOUBLE,
                                         SQL_DOUBLE, 0, 0, &double_value, sizeof(double_value),
                                         &double_length),
                        "SQLBindParameter(double)", SQL_HANDLE_STMT, statement);
        SQLCHAR boolean_value = 1;
        SQLLEN boolean_length = sizeof(boolean_value);
        require_success(SQLBindParameter(statement, 11, SQL_PARAM_INPUT, SQL_C_BIT,
                                         SQL_BIT, 0, 0, &boolean_value, sizeof(boolean_value),
                                         &boolean_length),
                        "SQLBindParameter(boolean)", SQL_HANDLE_STMT, statement);
        char text_value[] = "ODBC H2 L2";
        SQLLEN text_length = SQL_NTS;
        require_success(SQLBindParameter(statement, 12, SQL_PARAM_INPUT, SQL_C_CHAR,
                                         SQL_VARCHAR, 40, 0, text_value, sizeof(text_value),
                                         &text_length),
                        "SQLBindParameter(text)", SQL_HANDLE_STMT, statement);
        SQLCHAR binary_value[] = {0x41, 0x00, 0x42};
        SQLLEN binary_length = sizeof(binary_value);
        require_success(SQLBindParameter(statement, 13, SQL_PARAM_INPUT, SQL_C_BINARY,
                                         SQL_VARBINARY, sizeof(binary_value), 0, binary_value,
                                         sizeof(binary_value), &binary_length),
                        "SQLBindParameter(binary)", SQL_HANDLE_STMT, statement);
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

        const std::string select = "SELECT id AS ID, amount,"
            " business_date, business_time, created_at, tiny_value, small_value, integer_value,"
            " long_value, real_value, double_value, boolean_value, text_value, binary_value FROM " +
            table + " WHERE id = ?";
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
        if (column_count != 14) {
            throw std::runtime_error("expected fourteen L2 result columns");
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
        SQLINTEGER selected_tiny = 0;
        SQLLEN selected_tiny_length = 0;
        require_success(SQLGetData(statement, 6, SQL_C_SLONG, &selected_tiny,
                                   sizeof(selected_tiny), &selected_tiny_length),
                        "SQLGetData(tiny integer)", SQL_HANDLE_STMT, statement);
        SQLINTEGER selected_small = 0;
        SQLLEN selected_small_length = 0;
        require_success(SQLGetData(statement, 7, SQL_C_SLONG, &selected_small,
                                   sizeof(selected_small), &selected_small_length),
                        "SQLGetData(small integer)", SQL_HANDLE_STMT, statement);
        SQLINTEGER selected_integer = 0;
        SQLLEN selected_integer_length = 0;
        require_success(SQLGetData(statement, 8, SQL_C_SLONG, &selected_integer,
                                   sizeof(selected_integer), &selected_integer_length),
                        "SQLGetData(integer)", SQL_HANDLE_STMT, statement);
        SQLBIGINT selected_long = 0;
        SQLLEN selected_long_length = 0;
        require_success(SQLGetData(statement, 9, SQL_C_SBIGINT, &selected_long,
                                   sizeof(selected_long), &selected_long_length),
                        "SQLGetData(big integer)", SQL_HANDLE_STMT, statement);
        SQLREAL selected_real = 0;
        SQLLEN selected_real_length = 0;
        require_success(SQLGetData(statement, 10, SQL_C_FLOAT, &selected_real,
                                   sizeof(selected_real), &selected_real_length),
                        "SQLGetData(real)", SQL_HANDLE_STMT, statement);
        SQLDOUBLE selected_double = 0;
        SQLLEN selected_double_length = 0;
        require_success(SQLGetData(statement, 11, SQL_C_DOUBLE, &selected_double,
                                   sizeof(selected_double), &selected_double_length),
                        "SQLGetData(double)", SQL_HANDLE_STMT, statement);
        SQLCHAR selected_boolean = 0;
        SQLLEN selected_boolean_length = 0;
        require_success(SQLGetData(statement, 12, SQL_C_BIT, &selected_boolean,
                                   sizeof(selected_boolean), &selected_boolean_length),
                        "SQLGetData(boolean)", SQL_HANDLE_STMT, statement);
        assert_text(statement, 13, "ODBC H2 L2");
        SQLCHAR selected_binary[3] = {};
        SQLLEN selected_binary_length = 0;
        require_success(SQLGetData(statement, 14, SQL_C_BINARY, selected_binary,
                                   sizeof(selected_binary), &selected_binary_length),
                        "SQLGetData(binary)", SQL_HANDLE_STMT, statement);
        if (selected_tiny != tiny_value || selected_small != small_value ||
            selected_integer != integer_value || selected_long != long_value ||
            selected_real != real_value || selected_double != double_value ||
            selected_boolean != boolean_value || selected_binary_length != sizeof(binary_value) ||
            !std::equal(std::begin(binary_value), std::end(binary_value),
                        std::begin(selected_binary))) {
            throw std::runtime_error("H2 did not round-trip the additional ODBC scalar types");
        }
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
