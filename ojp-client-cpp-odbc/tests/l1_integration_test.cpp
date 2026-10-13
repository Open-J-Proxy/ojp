#include "connection_suite.h"

#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using ojp::test::DatabaseConfig;

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

std::vector<SQLWCHAR> to_sql_wide(const std::u16string& input) {
    std::vector<SQLWCHAR> output;
    output.reserve(input.size() + 1);
    for (std::size_t index = 0; index < input.size(); ++index) {
        std::uint32_t code_point = input[index];
        if (sizeof(SQLWCHAR) == 2) {
            output.push_back(static_cast<SQLWCHAR>(code_point));
        } else {
            if (code_point >= 0xd800 && code_point <= 0xdbff && index + 1 < input.size()) {
                const auto low = static_cast<std::uint32_t>(input[++index]);
                code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
            }
            output.push_back(static_cast<SQLWCHAR>(code_point));
        }
    }
    output.push_back(0);
    return output;
}

std::vector<SQLWCHAR> to_sql_wide(const std::string& ascii) {
    std::u16string wide;
    wide.reserve(ascii.size());
    for (const auto character : ascii) {
        wide.push_back(static_cast<char16_t>(static_cast<unsigned char>(character)));
    }
    return to_sql_wide(wide);
}

bool equal_ascii_case_insensitive(std::string first, std::string second) {
    const auto uppercase = [](unsigned char character) {
        return static_cast<char>(std::toupper(character));
    };
    std::transform(first.begin(), first.end(), first.begin(), uppercase);
    std::transform(second.begin(), second.end(), second.begin(), uppercase);
    return first == second;
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

int run_connection(const DatabaseConfig& config,
                   const std::string& database,
                   const std::string& expected_sql_state,
                   const std::string& endpoint) {
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    SQLHDBC wide_connection = SQL_NULL_HDBC;
    SQLHSTMT wide_statement = SQL_NULL_HSTMT;
    std::string table = "ojp_cpp_l1_" + random_suffix();
    const std::string child_table = table + "_child";
    std::string metadata_table = table;
    std::string metadata_child_table = child_table;
    if (config.url.rfind("jdbc:h2:", 0) == 0) {
        const auto uppercase = [](unsigned char character) {
            return static_cast<char>(std::toupper(character));
        };
        std::transform(metadata_table.begin(), metadata_table.end(), metadata_table.begin(),
                       uppercase);
        std::transform(metadata_child_table.begin(), metadata_child_table.end(),
                       metadata_child_table.begin(), uppercase);
    }
    bool table_created = false;
    bool child_table_created = false;
    bool wide_connected = false;
    const char* test_stage = "allocate environment";
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE,
                                       reinterpret_cast<SQLHANDLE*>(&environment)),
                        "SQLAllocHandle(environment)");
        test_stage = "set ODBC version";
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3),
                                      SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        test_stage = "allocate ANSI connection";
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                       reinterpret_cast<SQLHANDLE*>(&connection)),
                        "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);

        test_stage = "ANSI SQLDriverConnect";
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";PWD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr,
            reinterpret_cast<SQLCHAR*>(const_cast<char*>(connection_string.c_str())),
            SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
            "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        test_stage = "allocate wide connection";
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment,
                                       reinterpret_cast<SQLHANDLE*>(&wide_connection)),
                        "SQLAllocHandle(wide connection)", SQL_HANDLE_ENV, environment);
        test_stage = "wide SQLDriverConnectW";
        const std::string wide_connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";******;";
        auto wide_connection_buffer = to_sql_wide(wide_connection_string);
        require_success(SQLDriverConnectW(wide_connection, nullptr, wide_connection_buffer.data(),
                                          SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnectW", SQL_HANDLE_DBC, wide_connection);
        wide_connected = true;
        test_stage = "disconnect after SQLDriverConnectW";
        require_success(SQLDisconnect(wide_connection), "SQLDisconnect(wide)",
                        SQL_HANDLE_DBC, wide_connection);
        wide_connected = false;
        test_stage = "wide SQLConnectW";
        const std::string connect_options =
            "SERVER=" + brace_value(endpoint) + ";DATABASE=" + brace_value(config.url);
        auto wide_connect_options = to_sql_wide(connect_options);
        auto wide_user = to_sql_wide(config.user);
        auto wide_password = to_sql_wide(config.password);
        require_success(SQLConnectW(wide_connection, wide_connect_options.data(), SQL_NTS,
                                    wide_user.data(), SQL_NTS, wide_password.data(), SQL_NTS),
                        "SQLConnectW", SQL_HANDLE_DBC, wide_connection);
        wide_connected = true;
        test_stage = "allocate wide statement";
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, wide_connection,
                                       reinterpret_cast<SQLHANDLE*>(&wide_statement)),
                        "SQLAllocHandle(wide statement)", SQL_HANDLE_DBC, wide_connection);
        test_stage = "allocate ANSI statement";
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        test_stage = "readiness query";
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

        test_stage = "Unicode parameter and result round trip";
        const std::u16string unicode_text = u"A\U0001f642B";
        const auto unicode_parameter = to_sql_wide(unicode_text);
        SQLLEN unicode_length = static_cast<SQLLEN>(
            (unicode_parameter.size() - 1) * sizeof(SQLWCHAR));
        const std::u16string unicode_query = u"SELECT ? /* Unicode SQL: 東京 */";
        const auto wide_query = to_sql_wide(unicode_query);
        require_success(SQLPrepareW(wide_statement,
                                    const_cast<SQLWCHAR*>(wide_query.data()), SQL_NTS),
                        "SQLPrepareW", SQL_HANDLE_STMT, wide_statement);
        require_success(SQLBindParameter(wide_statement, 1, SQL_PARAM_INPUT, SQL_C_WCHAR,
                                         SQL_WVARCHAR, unicode_text.size(), 0,
                                         const_cast<SQLWCHAR*>(unicode_parameter.data()),
                                         unicode_length, &unicode_length),
                        "SQLBindParameter(SQL_C_WCHAR)", SQL_HANDLE_STMT, wide_statement);
        std::vector<SQLWCHAR> bound_unicode(unicode_parameter.size(), 0);
        SQLLEN bound_unicode_length = 0;
        require_success(SQLBindCol(wide_statement, 1, SQL_C_WCHAR, bound_unicode.data(),
                                   static_cast<SQLLEN>(bound_unicode.size() * sizeof(SQLWCHAR)),
                                   &bound_unicode_length),
                        "SQLBindCol(SQL_C_WCHAR)", SQL_HANDLE_STMT, wide_statement);
        require_success(SQLExecute(wide_statement), "SQLExecute(Unicode)", SQL_HANDLE_STMT,
                        wide_statement);
        require_success(SQLFetch(wide_statement), "SQLFetch(Unicode bound)",
                        SQL_HANDLE_STMT, wide_statement);
        if (bound_unicode_length != unicode_length ||
            !std::equal(unicode_parameter.begin(), unicode_parameter.end(), bound_unicode.begin())) {
            throw std::runtime_error("SQL_C_WCHAR parameter/bound-column round trip was corrupted");
        }
        require_success(SQLFreeStmt(wide_statement, SQL_CLOSE), "SQLFreeStmt(Unicode bound)",
                        SQL_HANDLE_STMT, wide_statement);
        require_success(SQLFreeStmt(wide_statement, SQL_UNBIND), "SQLFreeStmt(Unicode unbind)",
                        SQL_HANDLE_STMT, wide_statement);
        require_success(SQLExecute(wide_statement), "SQLExecute(Unicode get data)",
                        SQL_HANDLE_STMT, wide_statement);
        require_success(SQLFetch(wide_statement), "SQLFetch(Unicode get data)",
                        SQL_HANDLE_STMT, wide_statement);
        std::vector<SQLWCHAR> retrieved_unicode;
        SQLRETURN unicode_result = SQL_SUCCESS_WITH_INFO;
        while (unicode_result == SQL_SUCCESS_WITH_INFO) {
            SQLWCHAR chunk[3] = {};
            SQLLEN chunk_length = 0;
            unicode_result = SQLGetData(wide_statement, 1, SQL_C_WCHAR, chunk,
                                        static_cast<SQLLEN>(sizeof(chunk)), &chunk_length);
            require_success(unicode_result, "SQLGetData(SQL_C_WCHAR)", SQL_HANDLE_STMT,
                            wide_statement);
            retrieved_unicode.insert(retrieved_unicode.end(), chunk, chunk +
                static_cast<std::size_t>(chunk[0] != 0) +
                static_cast<std::size_t>(chunk[1] != 0) +
                static_cast<std::size_t>(chunk[2] != 0));
        }
        if (retrieved_unicode.size() != unicode_parameter.size() - 1 ||
            !std::equal(retrieved_unicode.begin(), retrieved_unicode.end(),
                        unicode_parameter.begin())) {
            throw std::runtime_error("SQLGetData(SQL_C_WCHAR) round trip was corrupted");
        }
        require_success(SQLFreeStmt(wide_statement, SQL_CLOSE), "SQLFreeStmt(Unicode get data)",
                        SQL_HANDLE_STMT, wide_statement);

        test_stage = "wide direct SQL execution";
        const auto direct_wide_query = to_sql_wide(u"SELECT 2 /* Unicode direct */");
        require_success(SQLExecDirectW(wide_statement,
                                       const_cast<SQLWCHAR*>(direct_wide_query.data()), SQL_NTS),
                        "SQLExecDirectW", SQL_HANDLE_STMT, wide_statement);
        require_success(SQLFetch(wide_statement), "SQLFetch(SQLExecDirectW)",
                        SQL_HANDLE_STMT, wide_statement);
        SQLINTEGER direct_value = 0;
        SQLLEN direct_indicator = 0;
        require_success(SQLGetData(wide_statement, 1, SQL_C_SLONG, &direct_value,
                                   sizeof(direct_value), &direct_indicator),
                        "SQLGetData(SQLExecDirectW)", SQL_HANDLE_STMT, wide_statement);
        if (direct_value != 2) {
            throw std::runtime_error("SQLExecDirectW returned an unexpected value");
        }
        require_success(SQLFreeStmt(wide_statement, SQL_CLOSE), "SQLFreeStmt(SQLExecDirectW)",
                        SQL_HANDLE_STMT, wide_statement);

        test_stage = "Unicode diagnostics and wide connection info";
        SQLWCHAR invalid_sql[] = {static_cast<SQLWCHAR>(0xd800), 0};
        if (SQLExecDirectW(wide_statement, invalid_sql, SQL_NTS) != SQL_ERROR) {
            throw std::runtime_error("invalid SQLWCHAR surrogate did not return SQL_ERROR");
        }
        SQLWCHAR diagnostic_state[6] = {};
        SQLWCHAR diagnostic_message[128] = {};
        SQLINTEGER diagnostic_native_error = 0;
        SQLSMALLINT diagnostic_length = 0;
        require_success(SQLGetDiagRecW(SQL_HANDLE_STMT, wide_statement, 1, diagnostic_state,
                                       &diagnostic_native_error, diagnostic_message,
                                       128, &diagnostic_length),
                        "SQLGetDiagRecW", SQL_HANDLE_STMT, wide_statement);
        if (diagnostic_state[0] != static_cast<SQLWCHAR>('2') ||
            diagnostic_state[1] != static_cast<SQLWCHAR>('2') ||
            diagnostic_state[2] != static_cast<SQLWCHAR>('0') ||
            diagnostic_state[3] != static_cast<SQLWCHAR>('1') ||
            diagnostic_state[4] != static_cast<SQLWCHAR>('8')) {
            throw std::runtime_error("invalid Unicode did not produce SQLSTATE 22018");
        }
        require_success(SQLFreeStmt(wide_statement, SQL_CLOSE), "SQLFreeStmt(invalid Unicode)",
                        SQL_HANDLE_STMT, wide_statement);
        SQLWCHAR dbms_name[64] = {};
        SQLSMALLINT dbms_name_length = 0;
        require_success(SQLGetInfoW(wide_connection, SQL_DBMS_NAME, dbms_name,
                                    64, &dbms_name_length),
                        "SQLGetInfoW", SQL_HANDLE_DBC, wide_connection);
        if (dbms_name_length == 0) {
            throw std::runtime_error("SQLGetInfoW returned an empty DBMS name");
        }

        test_stage = "create metadata test tables";
        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        table_created = true;
        execute_direct(statement, "CREATE TABLE " + child_table +
                                  " (id INT PRIMARY KEY, parent_id INT, "
                                  "CONSTRAINT ojp_l1_fk FOREIGN KEY (parent_id) REFERENCES " +
                                  table + "(id))");
        child_table_created = true;

        test_stage = "SQLTables";
        SQLCHAR table_type[] = "TABLE";
        require_success(SQLTables(statement, nullptr, 0, nullptr, 0,
                                  reinterpret_cast<SQLCHAR*>(metadata_table.data()), SQL_NTS,
                                  table_type, SQL_NTS),
                        "SQLTables", SQL_HANDLE_STMT, statement);
        SQLSMALLINT metadata_column_count = 0;
        require_success(SQLNumResultCols(statement, &metadata_column_count),
                        "SQLNumResultCols(SQLTables)", SQL_HANDLE_STMT, statement);
        if (metadata_column_count != 5 ||
            SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLTables did not return the created table");
        }
        SQLCHAR metadata_text[128] = {};
        SQLLEN metadata_text_length = 0;
        require_success(SQLGetData(statement, 3, SQL_C_CHAR, metadata_text,
                                   sizeof(metadata_text), &metadata_text_length),
                        "SQLGetData(SQLTables table name)", SQL_HANDLE_STMT, statement);
        if (!equal_ascii_case_insensitive(
                std::string(reinterpret_cast<const char*>(metadata_text)), table)) {
            throw std::runtime_error("SQLTables returned an unexpected table name");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLTables)",
                        SQL_HANDLE_STMT, statement);

        test_stage = "SQLTablesW";
        auto wide_table_name = to_sql_wide(metadata_table);
        auto wide_table_type = to_sql_wide(u"TABLE");
        require_success(SQLTablesW(wide_statement, nullptr, 0, nullptr, 0,
                                   wide_table_name.data(), SQL_NTS,
                                   wide_table_type.data(), SQL_NTS),
                        "SQLTablesW", SQL_HANDLE_STMT, wide_statement);
        if (SQLFetch(wide_statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLTablesW did not return the created table");
        }
        std::vector<SQLWCHAR> wide_catalog_name(metadata_table.size() + 1, 0);
        SQLLEN wide_catalog_name_length = 0;
        require_success(SQLGetData(wide_statement, 3, SQL_C_WCHAR, wide_catalog_name.data(),
                                   static_cast<SQLLEN>(
                                       wide_catalog_name.size() * sizeof(SQLWCHAR)),
                                   &wide_catalog_name_length),
                        "SQLGetData(SQLTablesW)", SQL_HANDLE_STMT, wide_statement);
        if (wide_catalog_name_length !=
                static_cast<SQLLEN>(metadata_table.size() * sizeof(SQLWCHAR)) ||
            !std::equal(wide_table_name.begin(), wide_table_name.end() - 1,
                        wide_catalog_name.begin())) {
            throw std::runtime_error("SQLTablesW returned an unexpected table name");
        }
        require_success(SQLFreeStmt(wide_statement, SQL_CLOSE), "SQLFreeStmt(SQLTablesW)",
                        SQL_HANDLE_STMT, wide_statement);

        test_stage = "SQLColumns";
        require_success(SQLColumns(statement, nullptr, 0, nullptr, 0,
                                   reinterpret_cast<SQLCHAR*>(metadata_table.data()), SQL_NTS,
                                   nullptr, 0),
                        "SQLColumns", SQL_HANDLE_STMT, statement);
        require_success(SQLNumResultCols(statement, &metadata_column_count),
                        "SQLNumResultCols(SQLColumns)", SQL_HANDLE_STMT, statement);
        if (metadata_column_count != 24 || SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLColumns did not return JDBC column metadata");
        }
        std::memset(metadata_text, 0, sizeof(metadata_text));
        require_success(SQLGetData(statement, 4, SQL_C_CHAR, metadata_text,
                                   sizeof(metadata_text), &metadata_text_length),
                        "SQLGetData(SQLColumns column name)", SQL_HANDLE_STMT, statement);
        if (!equal_ascii_case_insensitive(
                std::string(reinterpret_cast<const char*>(metadata_text)), "id")) {
            throw std::runtime_error("SQLColumns did not identify the primary key column");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLColumns)",
                        SQL_HANDLE_STMT, statement);

        test_stage = "SQLPrimaryKeys";
        require_success(SQLPrimaryKeys(statement, nullptr, 0, nullptr, 0,
                                       reinterpret_cast<SQLCHAR*>(metadata_table.data()), SQL_NTS),
                        "SQLPrimaryKeys", SQL_HANDLE_STMT, statement);
        if (SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLPrimaryKeys did not return the primary key");
        }
        std::memset(metadata_text, 0, sizeof(metadata_text));
        require_success(SQLGetData(statement, 4, SQL_C_CHAR, metadata_text,
                                   sizeof(metadata_text), &metadata_text_length),
                        "SQLGetData(SQLPrimaryKeys column name)", SQL_HANDLE_STMT, statement);
        if (!equal_ascii_case_insensitive(
                std::string(reinterpret_cast<const char*>(metadata_text)), "id")) {
            throw std::runtime_error("SQLPrimaryKeys returned an unexpected key column");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLPrimaryKeys)",
                        SQL_HANDLE_STMT, statement);

        test_stage = "SQLForeignKeys";
        require_success(SQLForeignKeys(statement, nullptr, 0, nullptr, 0, nullptr, 0,
                                       nullptr, 0, nullptr, 0,
                                       reinterpret_cast<SQLCHAR*>(
                                           metadata_child_table.data()), SQL_NTS),
                        "SQLForeignKeys", SQL_HANDLE_STMT, statement);
        if (SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLForeignKeys did not return the declared foreign key");
        }
        std::memset(metadata_text, 0, sizeof(metadata_text));
        require_success(SQLGetData(statement, 8, SQL_C_CHAR, metadata_text,
                                   sizeof(metadata_text), &metadata_text_length),
                        "SQLGetData(SQLForeignKeys column name)", SQL_HANDLE_STMT, statement);
        if (!equal_ascii_case_insensitive(
                std::string(reinterpret_cast<const char*>(metadata_text)), "parent_id")) {
            throw std::runtime_error("SQLForeignKeys returned an unexpected foreign key column");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLForeignKeys)",
                        SQL_HANDLE_STMT, statement);

        test_stage = "SQLGetTypeInfo all types";
        require_success(SQLGetTypeInfo(statement, SQL_ALL_TYPES), "SQLGetTypeInfo",
                        SQL_HANDLE_STMT, statement);
        if (SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLGetTypeInfo returned no supported database types");
        }
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLGetTypeInfo)",
                        SQL_HANDLE_STMT, statement);
        test_stage = "SQLGetTypeInfo SQL_INTEGER filter";
        require_success(SQLGetTypeInfo(statement, SQL_INTEGER), "SQLGetTypeInfo(SQL_INTEGER)",
                        SQL_HANDLE_STMT, statement);
        if (SQLFetch(statement) != SQL_SUCCESS) {
            throw std::runtime_error("SQLGetTypeInfo(SQL_INTEGER) returned no integer type");
        }
        do {
            SQLINTEGER reported_type = 0;
            SQLLEN reported_type_length = 0;
            require_success(SQLGetData(statement, 2, SQL_C_SLONG, &reported_type,
                                       sizeof(reported_type), &reported_type_length),
                            "SQLGetData(SQLGetTypeInfo DATA_TYPE)", SQL_HANDLE_STMT, statement);
            if (reported_type != SQL_INTEGER) {
                throw std::runtime_error("SQLGetTypeInfo returned a different type than requested");
            }
        } while (SQLFetch(statement) == SQL_SUCCESS);
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(SQLGetTypeInfo filtered)",
                        SQL_HANDLE_STMT, statement);

        test_stage = "prepared CRUD";
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
        SQLCHAR described_name[32] = {};
        SQLSMALLINT described_name_length = 0;
        require_success(SQLColAttribute(statement, 2, SQL_DESC_NAME, described_name,
                                        sizeof(described_name), &described_name_length,
                                        nullptr),
                        "SQLColAttribute", SQL_HANDLE_STMT, statement);
        SQLLEN described_type = SQL_UNKNOWN_TYPE;
        require_success(SQLColAttribute(statement, 2, SQL_DESC_TYPE, nullptr, 0, nullptr,
                                        &described_type),
                        "SQLColAttribute(type)", SQL_HANDLE_STMT, statement);
        if (std::string(reinterpret_cast<const char*>(described_name)) != "name" ||
            described_type != SQL_VARCHAR) {
            throw std::runtime_error("SQLColAttribute returned unexpected result metadata");
        }
        SQLWCHAR wide_described_name[32] = {};
        SQLSMALLINT wide_described_name_length = 0;
        require_success(SQLColAttributeW(statement, 2, SQL_DESC_NAME, wide_described_name,
                                         32, &wide_described_name_length, nullptr),
                        "SQLColAttributeW", SQL_HANDLE_STMT, statement);
        if (wide_described_name_length != 4 ||
            wide_described_name[0] != static_cast<SQLWCHAR>('n')) {
            throw std::runtime_error("SQLColAttributeW returned unexpected column metadata");
        }
        SQLWCHAR wide_described_column[32] = {};
        SQLSMALLINT wide_described_length = 0;
        SQLSMALLINT wide_described_type = SQL_UNKNOWN_TYPE;
        require_success(SQLDescribeColW(statement, 2, wide_described_column, 32,
                                        &wide_described_length, &wide_described_type,
                                        nullptr, nullptr, nullptr),
                        "SQLDescribeColW", SQL_HANDLE_STMT, statement);
        if (wide_described_length != 4 || wide_described_type != SQL_VARCHAR) {
            throw std::runtime_error("SQLDescribeColW returned unexpected result metadata");
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

        test_stage = "SQLSTATE error diagnostics";
        assert_sql_error(statement, "THIS IS NOT VALID SQL", expected_sql_state.c_str());
        require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(invalid SQL)",
                        SQL_HANDLE_STMT, statement);

        execute_direct(statement, "DROP TABLE IF EXISTS " + child_table);
        child_table_created = false;
        execute_direct(statement, "DROP TABLE IF EXISTS " + table);
        table_created = false;
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, wide_statement),
                        "SQLFreeHandle(wide statement)", SQL_HANDLE_DBC, wide_connection);
        wide_statement = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(wide_connection), "SQLDisconnectW", SQL_HANDLE_DBC,
                        wide_connection);
        wide_connected = false;
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, wide_connection),
                        "SQLFreeHandle(wide connection)", SQL_HANDLE_ENV, environment);
        wide_connection = SQL_NULL_HDBC;
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
        std::cerr << database << " L1 failed during stage: " << test_stage << '\n'
                  << std::flush;
        if (child_table_created && statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + child_table;
            SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(
                const_cast<char*>(drop.c_str())), SQL_NTS);
        }
        if (table_created && statement != SQL_NULL_HSTMT) {
            const std::string drop = "DROP TABLE IF EXISTS " + table;
            SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(
                const_cast<char*>(drop.c_str())), SQL_NTS);
        }
        if (statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, statement);
        }
        if (wide_statement != SQL_NULL_HSTMT) {
            SQLFreeHandle(SQL_HANDLE_STMT, wide_statement);
        }
        if (wide_connection != SQL_NULL_HDBC) {
            if (wide_connected) {
                SQLDisconnect(wide_connection);
            }
            SQLFreeHandle(SQL_HANDLE_DBC, wide_connection);
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

    return ojp::test::run_connections(csv_path, [&](const DatabaseConfig& config) {
        return run_connection(config, database, expected_sql_state, endpoint);
    });
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
