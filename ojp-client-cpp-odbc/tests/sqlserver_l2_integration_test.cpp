// Verifies SQL Server typed parameters, statement execution, generated IDs, and column metadata
// through ODBC. Type coverage follows the OJP JDBC SQLServerMultipleTypesIntegrationTest.
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

void prepare(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLPrepare(statement, sql_text(sql), SQL_NTS),
                    "SQLPrepare", SQL_HANDLE_STMT, statement);
}

void bind(SQLHSTMT statement, SQLUSMALLINT index, SQLSMALLINT c_type, SQLSMALLINT sql_type,
          SQLULEN column_size, SQLSMALLINT decimal_digits, SQLPOINTER value,
          SQLLEN buffer_length, SQLLEN* indicator, const std::string& label) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, c_type, sql_type,
                                     column_size, decimal_digits, value, buffer_length, indicator),
                    "SQLBindParameter(" + label + ")", SQL_HANDLE_STMT, statement);
}

void close_and_reset(SQLHSTMT statement) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
    require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(parameters)",
                    SQL_HANDLE_STMT, statement);
}

void expect_one_row_affected(SQLHSTMT statement, const std::string& operation) {
    SQLLEN affected_rows = -1;
    require_success(SQLRowCount(statement, &affected_rows), "SQLRowCount",
                    SQL_HANDLE_STMT, statement);
    expect(affected_rows == 1, operation + " should affect exactly one row");
}

std::string get_text(SQLHSTMT statement, SQLUSMALLINT column, std::size_t capacity = 256) {
    std::vector<char> buffer(capacity + 1, '\0');
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, SQL_C_CHAR, buffer.data(),
                               static_cast<SQLLEN>(buffer.size()), &length),
                    "SQLGetData(text column " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(length != SQL_NULL_DATA, "column " + std::to_string(column) + " was unexpectedly NULL");
    return std::string(buffer.data(), static_cast<std::size_t>(length));
}

void expect_text(SQLHSTMT statement, SQLUSMALLINT column, const std::string& expected) {
    const std::string actual = get_text(statement, column, expected.size() + 64);
    expect(actual == expected, "column " + std::to_string(column) + ": expected '" + expected +
                               "', got '" + actual + "'");
}

template <typename T>
T get_value(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type) {
    T value{};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, c_type, &value, sizeof(value), &length),
                    "SQLGetData(column " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(length != SQL_NULL_DATA, "column " + std::to_string(column) + " was unexpectedly NULL");
    return value;
}

std::vector<SQLCHAR> get_binary(SQLHSTMT statement, SQLUSMALLINT column, std::size_t capacity) {
    std::vector<SQLCHAR> buffer(capacity);
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, SQL_C_BINARY, buffer.data(),
                               static_cast<SQLLEN>(buffer.size()), &length),
                    "SQLGetData(binary column " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(length != SQL_NULL_DATA && static_cast<std::size_t>(length) <= capacity,
           "binary column " + std::to_string(column) + " has an unexpected length");
    buffer.resize(static_cast<std::size_t>(length));
    return buffer;
}

void expect_null(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type) {
    std::vector<SQLCHAR> buffer(64);
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, c_type, buffer.data(),
                               static_cast<SQLLEN>(buffer.size()), &length),
                    "SQLGetData(null column " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(length == SQL_NULL_DATA, "column " + std::to_string(column) +
                                    " should be returned as SQL_NULL_DATA");
}

void expect_column(SQLHSTMT statement, SQLUSMALLINT column, const std::string& name,
                   SQLSMALLINT expected_type) {
    SQLCHAR column_name[128] = {};
    SQLSMALLINT name_length = 0;
    SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
    SQLULEN column_size = 0;
    SQLSMALLINT decimal_digits = 0;
    SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
    require_success(SQLDescribeCol(statement, column, column_name, sizeof(column_name),
                                   &name_length, &data_type, &column_size, &decimal_digits,
                                   &nullable),
                    "SQLDescribeCol", SQL_HANDLE_STMT, statement);
    expect(std::string(reinterpret_cast<const char*>(column_name)) == name &&
           data_type == expected_type,
           "result metadata for column " + std::to_string(column) + " should be " + name);
}

SQLBIGINT current_identity(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "SELECT CAST(IDENT_CURRENT('" + table + "') AS BIGINT)");
    require_success(SQLFetch(statement), "SQLFetch(identity)", SQL_HANDLE_STMT, statement);
    const auto identity = get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT);
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(identity)",
                    SQL_HANDLE_STMT, statement);
    expect(identity >= 1, "SQL Server did not return the generated identity value");
    return identity;
}

// Mirrors typesCoverageTestSuccessful, typesPartialSupportTest, and testSqlServerNullValues.
void verify_multiple_types(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "CREATE TABLE " + table + " (id INT IDENTITY(1,1) PRIMARY KEY,"
        " val_int INT NOT NULL, val_varchar NVARCHAR(50), val_double_precision FLOAT,"
        " val_bigint BIGINT, val_tinyint TINYINT, val_smallint SMALLINT, val_boolean BIT,"
        " val_decimal DECIMAL(10, 2), val_float REAL, val_byte VARBINARY(1),"
        " val_binary VARBINARY(4), val_date DATE, val_time TIME, val_timestamp DATETIME2,"
        " val_instant DATETIME2, val_offsetdatetime DATETIMEOFFSET,"
        " val_offsettime DATETIMEOFFSET)");

    prepare(statement, "INSERT INTO " + table + " (val_int, val_varchar, val_double_precision,"
        " val_bigint, val_tinyint, val_smallint, val_boolean, val_decimal, val_float, val_byte,"
        " val_binary, val_date, val_time, val_timestamp, val_instant, val_offsetdatetime,"
        " val_offsettime) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    SQLINTEGER int_value = 1;
    SQLLEN int_length = sizeof(int_value);
    bind(statement, 1, SQL_C_SLONG, SQL_INTEGER, 0, 0, &int_value, sizeof(int_value),
         &int_length, "int");
    char varchar_value[] = "TITLE_1";
    SQLLEN varchar_length = SQL_NTS;
    bind(statement, 2, SQL_C_CHAR, SQL_WVARCHAR, 50, 0, varchar_value, sizeof(varchar_value),
         &varchar_length, "nvarchar");
    SQLDOUBLE double_value = 2.2222;
    SQLLEN double_length = sizeof(double_value);
    bind(statement, 3, SQL_C_DOUBLE, SQL_DOUBLE, 0, 0, &double_value, sizeof(double_value),
         &double_length, "float");
    SQLBIGINT bigint_value = 33333333333333LL;
    SQLLEN bigint_length = sizeof(bigint_value);
    bind(statement, 4, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &bigint_value, sizeof(bigint_value),
         &bigint_length, "bigint");
    // SQL Server TINYINT is unsigned (0-255), so 255 does not fit SQL_C_STINYINT; like the
    // JDBC test's setInt, it is sent as an integer parameter.
    SQLINTEGER tinyint_value = 255;
    SQLLEN tinyint_length = sizeof(tinyint_value);
    bind(statement, 5, SQL_C_SLONG, SQL_INTEGER, 0, 0, &tinyint_value, sizeof(tinyint_value),
         &tinyint_length, "tinyint");
    SQLSMALLINT smallint_value = 32767;
    SQLLEN smallint_length = sizeof(smallint_value);
    bind(statement, 6, SQL_C_SSHORT, SQL_SMALLINT, 0, 0, &smallint_value,
         sizeof(smallint_value), &smallint_length, "smallint");
    SQLCHAR boolean_value = 1;
    SQLLEN boolean_length = sizeof(boolean_value);
    bind(statement, 7, SQL_C_BIT, SQL_BIT, 0, 0, &boolean_value, sizeof(boolean_value),
         &boolean_length, "bit");
    SQL_NUMERIC_STRUCT decimal_value{};
    decimal_value.precision = 10;
    decimal_value.scale = 0;
    decimal_value.sign = 1;
    decimal_value.val[0] = 10;
    SQLLEN decimal_length = sizeof(decimal_value);
    bind(statement, 8, SQL_C_NUMERIC, SQL_DECIMAL, 10, 0, &decimal_value, sizeof(decimal_value),
         &decimal_length, "decimal");
    SQLREAL real_value = 20.20F;
    SQLLEN real_length = sizeof(real_value);
    bind(statement, 9, SQL_C_FLOAT, SQL_REAL, 0, 0, &real_value, sizeof(real_value),
         &real_length, "real");
    SQLCHAR byte_value[] = {0x01};
    SQLLEN byte_length = sizeof(byte_value);
    bind(statement, 10, SQL_C_BINARY, SQL_VARBINARY, sizeof(byte_value), 0, byte_value,
         sizeof(byte_value), &byte_length, "varbinary(1)");
    SQLCHAR binary_value[] = {'A', 'A', 'A', 'A'};
    SQLLEN binary_length = sizeof(binary_value);
    bind(statement, 11, SQL_C_BINARY, SQL_VARBINARY, sizeof(binary_value), 0, binary_value,
         sizeof(binary_value), &binary_length, "varbinary(4)");
    SQL_DATE_STRUCT date_value{2025, 3, 29};
    SQLLEN date_length = sizeof(date_value);
    bind(statement, 12, SQL_C_TYPE_DATE, SQL_TYPE_DATE, 0, 0, &date_value, sizeof(date_value),
         &date_length, "date");
    SQL_TIME_STRUCT time_value{11, 12, 13};
    SQLLEN time_length = sizeof(time_value);
    bind(statement, 13, SQL_C_TYPE_TIME, SQL_TYPE_TIME, 0, 0, &time_value, sizeof(time_value),
         &time_length, "time");
    SQL_TIMESTAMP_STRUCT timestamp_value{2025, 3, 30, 21, 22, 23, 0};
    SQLLEN timestamp_length = sizeof(timestamp_value);
    bind(statement, 14, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 27, 7, &timestamp_value,
         sizeof(timestamp_value), &timestamp_length, "datetime2");
    // The JDBC success test binds the timezone-aware columns as typed NULLs first.
    SQL_TIMESTAMP_STRUCT null_timestamp{};
    SQLLEN null_length = SQL_NULL_DATA;
    for (SQLUSMALLINT index = 15; index <= 17; ++index) {
        bind(statement, index, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 27, 7, &null_timestamp,
             sizeof(null_timestamp), &null_length, "null timestamp");
    }
    require_success(SQLExecute(statement), "SQLExecute(insert types)", SQL_HANDLE_STMT, statement);
    expect_one_row_affected(statement, "prepared INSERT");
    close_and_reset(statement);
    const SQLBIGINT generated_id = current_identity(statement, table);

    // Partial-support types: Instant as a UTC DATETIME2, and OffsetDateTime/OffsetTime as
    // DATETIMEOFFSET text because ODBC has no portable timezone-aware C type.
    prepare(statement, "UPDATE " + table + " SET val_instant = ?, val_offsetdatetime = ?,"
        " val_offsettime = ? WHERE id = ?");
    SQL_TIMESTAMP_STRUCT instant_value{2024, 12, 1, 10, 10, 10, 0};
    SQLLEN instant_length = sizeof(instant_value);
    bind(statement, 1, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 27, 7, &instant_value,
         sizeof(instant_value), &instant_length, "instant");
    char offset_datetime_value[] = "2024-12-01 10:10:10 +02:00";
    SQLLEN offset_datetime_length = SQL_NTS;
    bind(statement, 2, SQL_C_CHAR, SQL_VARCHAR, 40, 0, offset_datetime_value,
         sizeof(offset_datetime_value), &offset_datetime_length, "offset date-time");
    char offset_time_value[] = "16:20:30 -05:00";
    SQLLEN offset_time_length = SQL_NTS;
    bind(statement, 3, SQL_C_CHAR, SQL_VARCHAR, 40, 0, offset_time_value,
         sizeof(offset_time_value), &offset_time_length, "offset time");
    SQLBIGINT id_value = generated_id;
    SQLLEN id_length = sizeof(id_value);
    bind(statement, 4, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id_value, sizeof(id_value), &id_length,
         "id");
    require_success(SQLExecute(statement), "SQLExecute(update)", SQL_HANDLE_STMT, statement);
    expect_one_row_affected(statement, "prepared UPDATE");
    close_and_reset(statement);

    prepare(statement, "SELECT id, val_int, val_varchar, val_double_precision, val_bigint,"
        " val_tinyint, val_smallint, val_boolean, val_decimal,"
        " val_float, val_byte, val_binary, val_date, val_time, val_timestamp, val_instant,"
        " val_offsetdatetime, CAST(val_offsetdatetime AS VARCHAR(40)) AS offset_text,"
        " val_offsettime FROM " + table + " WHERE id = ?");
    bind(statement, 1, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id_value, sizeof(id_value), &id_length,
         "id");
    require_success(SQLExecute(statement), "SQLExecute(select types)", SQL_HANDLE_STMT, statement);
    SQLSMALLINT column_count = 0;
    require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols",
                    SQL_HANDLE_STMT, statement);
    expect(column_count == 19, "expected nineteen SQL Server L2 result columns");
    require_success(SQLFetch(statement), "SQLFetch(select types)", SQL_HANDLE_STMT, statement);
    expect_column(statement, 1, "id", SQL_INTEGER);
    expect_column(statement, 3, "val_varchar", SQL_VARCHAR);
    expect_column(statement, 4, "val_double_precision", SQL_DOUBLE);
    expect_column(statement, 5, "val_bigint", SQL_BIGINT);
    expect_column(statement, 8, "val_boolean", SQL_BIT);
    expect_column(statement, 11, "val_byte", SQL_VARBINARY);
    expect(get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT) == generated_id,
           "prepared SELECT returned the wrong identity");
    expect(get_value<SQLINTEGER>(statement, 2, SQL_C_SLONG) == int_value, "INT did not round-trip");
    expect_text(statement, 3, "TITLE_1");
    expect(get_value<SQLDOUBLE>(statement, 4, SQL_C_DOUBLE) == double_value,
           "FLOAT did not round-trip");
    expect(get_value<SQLBIGINT>(statement, 5, SQL_C_SBIGINT) == bigint_value,
           "BIGINT did not round-trip");
    expect(get_value<SQLINTEGER>(statement, 6, SQL_C_SLONG) == tinyint_value,
           "TINYINT did not round-trip");
    expect(get_value<SQLINTEGER>(statement, 7, SQL_C_SLONG) == smallint_value,
           "SMALLINT did not round-trip");
    expect(get_value<SQLCHAR>(statement, 8, SQL_C_BIT) == 1, "BIT did not round-trip");
    expect_text(statement, 9, "10.00");
    expect(get_value<SQLREAL>(statement, 10, SQL_C_FLOAT) == real_value,
           "REAL did not round-trip");
    expect(get_binary(statement, 11, 8) == std::vector<SQLCHAR>(std::begin(byte_value),
                                                                std::end(byte_value)),
           "VARBINARY(1) did not round-trip");
    expect(get_binary(statement, 12, 8) == std::vector<SQLCHAR>(std::begin(binary_value),
                                                                std::end(binary_value)),
           "VARBINARY(4) did not round-trip");
    expect_text(statement, 13, "2025-03-29");
    expect_text(statement, 14, "11:12:13");
    expect_text(statement, 15, "2025-03-30 21:22:23");
    expect_text(statement, 16, "2024-12-01 10:10:10");
    expect_text(statement, 17, "2024-12-01 08:10:10");
    expect_text(statement, 18, "2024-12-01 10:10:10.0000000 +02:00");
    expect_text(statement, 19, "1900-01-01 21:20:30");
    close_and_reset(statement);

    execute_direct(statement, "INSERT INTO " + table + " (val_int, val_varchar)"
        " VALUES (2, 'Test')");
    expect_one_row_affected(statement, "direct INSERT");
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(insert nulls)",
                    SQL_HANDLE_STMT, statement);
    prepare(statement, "SELECT val_int, val_varchar, val_double_precision, val_bigint, val_byte,"
        " val_date, val_time, val_timestamp, val_instant, val_offsetdatetime FROM " + table +
        " WHERE val_int = ?");
    SQLINTEGER null_row_value = 2;
    SQLLEN null_row_length = sizeof(null_row_value);
    bind(statement, 1, SQL_C_SLONG, SQL_INTEGER, 0, 0, &null_row_value, sizeof(null_row_value),
         &null_row_length, "null row id");
    require_success(SQLExecute(statement), "SQLExecute(select nulls)", SQL_HANDLE_STMT, statement);
    require_success(SQLFetch(statement), "SQLFetch(select nulls)", SQL_HANDLE_STMT, statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == 2, "NULL row returned wrong INT");
    expect_text(statement, 2, "Test");
    expect_null(statement, 3, SQL_C_DOUBLE);
    expect_null(statement, 4, SQL_C_SBIGINT);
    expect_null(statement, 5, SQL_C_BINARY);
    expect_null(statement, 6, SQL_C_CHAR);
    expect_null(statement, 7, SQL_C_CHAR);
    expect_null(statement, 8, SQL_C_CHAR);
    expect_null(statement, 9, SQL_C_CHAR);
    expect_null(statement, 10, SQL_C_CHAR);
    expect(SQLFetch(statement) == SQL_NO_DATA, "NULL row query returned too many rows");
    close_and_reset(statement);

    // VARBINARY columns make the server stream SQL Server rows one at a time.
    execute_direct(statement, "SELECT val_int, val_byte FROM " + table + " ORDER BY id");
    require_success(SQLFetch(statement), "SQLFetch(binary row 1)", SQL_HANDLE_STMT, statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == int_value,
           "first VARBINARY row returned the wrong INT");
    require_success(SQLFetch(statement), "SQLFetch(binary row 2)", SQL_HANDLE_STMT, statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == 2,
           "second VARBINARY row returned the wrong INT");
    expect_null(statement, 2, SQL_C_BINARY);
    expect(SQLFetch(statement) == SQL_NO_DATA, "VARBINARY query returned too many rows");
    close_and_reset(statement);
}

// Mirrors testSqlServerSpecificTypes and testSqlServerLargeTypes.
void verify_specific_and_large_types(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "CREATE TABLE " + table + " (id INT IDENTITY(1,1) PRIMARY KEY,"
        " ntext_col NTEXT, text_col TEXT, image_col IMAGE, xml_col XML, money_col MONEY,"
        " smallmoney_col SMALLMONEY, uniqueidentifier_col UNIQUEIDENTIFIER,"
        " geometry_col GEOMETRY, geography_col GEOGRAPHY, hierarchyid_col HIERARCHYID,"
        " sql_variant_col SQL_VARIANT, datetimeoffset_col DATETIMEOFFSET,"
        " datetime2_col DATETIME2, smalldatetime_col SMALLDATETIME,"
        " nvarchar_max NVARCHAR(MAX), varchar_max VARCHAR(MAX), varbinary_max VARBINARY(MAX))");

    std::string large_text;
    for (int index = 0; index < 1000; ++index) {
        large_text += "This is a large text string for testing purposes. ";
    }
    std::string large_unicode_text = large_text + " Unicode: \xE4\xB8\xAD\xE6\x96\x87 "
        "\xF0\x9F\x9A\x80";
    std::vector<SQLCHAR> large_binary(10000);
    for (std::size_t index = 0; index < large_binary.size(); ++index) {
        large_binary[index] = static_cast<SQLCHAR>(index % 256);
    }

    // NEWID() generates the UNIQUEIDENTIFIER, as in the JDBC test.
    const std::string insert = "INSERT INTO " + table + " (ntext_col, text_col, money_col,"
        " smallmoney_col, uniqueidentifier_col, datetimeoffset_col, datetime2_col,"
        " smalldatetime_col, nvarchar_max, varchar_max, varbinary_max)"
        " VALUES (?, ?, ?, ?, NEWID(), ?, ?, ?, ?, ?, ?)";
    prepare(statement, insert);
    std::string ntext_value = "NTEXT content with Unicode: \xE4\xB8\xAD\xE6\x96\x87 "
        "\xF0\x9F\x9A\x80";
    SQLLEN ntext_length = SQL_NTS;
    bind(statement, 1, SQL_C_CHAR, SQL_WLONGVARCHAR, ntext_value.size(), 0, &ntext_value[0],
         static_cast<SQLLEN>(ntext_value.size() + 1), &ntext_length, "ntext");
    char text_value[] = "TEXT content";
    SQLLEN text_length = SQL_NTS;
    bind(statement, 2, SQL_C_CHAR, SQL_LONGVARCHAR, sizeof(text_value), 0, text_value,
         sizeof(text_value), &text_length, "text");
    char money_value[] = "123.45";
    SQLLEN money_length = SQL_NTS;
    bind(statement, 3, SQL_C_CHAR, SQL_DECIMAL, 19, 4, money_value, sizeof(money_value),
         &money_length, "money");
    char smallmoney_value[] = "67.89";
    SQLLEN smallmoney_length = SQL_NTS;
    bind(statement, 4, SQL_C_CHAR, SQL_DECIMAL, 10, 4, smallmoney_value,
         sizeof(smallmoney_value), &smallmoney_length, "smallmoney");
    SQL_TIMESTAMP_STRUCT offset_value{2024, 12, 1, 10, 10, 10, 0};
    SQLLEN offset_length = sizeof(offset_value);
    bind(statement, 5, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 27, 7, &offset_value,
         sizeof(offset_value), &offset_length, "datetimeoffset");
    SQL_TIMESTAMP_STRUCT datetime2_value{2024, 12, 1, 14, 30, 45, 123000000};
    SQLLEN datetime2_length = sizeof(datetime2_value);
    bind(statement, 6, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 27, 7, &datetime2_value,
         sizeof(datetime2_value), &datetime2_length, "datetime2");
    SQL_TIMESTAMP_STRUCT smalldatetime_value{2024, 12, 1, 14, 30, 0, 0};
    SQLLEN smalldatetime_length = sizeof(smalldatetime_value);
    bind(statement, 7, SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 16, 0, &smalldatetime_value,
         sizeof(smalldatetime_value), &smalldatetime_length, "smalldatetime");
    SQLLEN nvarchar_max_length = SQL_NTS;
    bind(statement, 8, SQL_C_CHAR, SQL_WLONGVARCHAR, large_unicode_text.size(), 0,
         &large_unicode_text[0], static_cast<SQLLEN>(large_unicode_text.size() + 1),
         &nvarchar_max_length, "nvarchar(max)");
    SQLLEN varchar_max_length = SQL_NTS;
    bind(statement, 9, SQL_C_CHAR, SQL_LONGVARCHAR, large_text.size(), 0, &large_text[0],
         static_cast<SQLLEN>(large_text.size() + 1), &varchar_max_length, "varchar(max)");
    SQLLEN varbinary_max_length = static_cast<SQLLEN>(large_binary.size());
    bind(statement, 10, SQL_C_BINARY, SQL_LONGVARBINARY, large_binary.size(), 0,
         large_binary.data(), static_cast<SQLLEN>(large_binary.size()), &varbinary_max_length,
         "varbinary(max)");
    require_success(SQLExecute(statement), "SQLExecute(insert specific types)",
                    SQL_HANDLE_STMT, statement);
    expect_one_row_affected(statement, "prepared INSERT of SQL Server-specific types");
    close_and_reset(statement);
    SQLBIGINT generated_id = current_identity(statement, table);

    prepare(statement, "SELECT id, ntext_col, text_col,"
        " money_col, smallmoney_col, uniqueidentifier_col,"
        " datetimeoffset_col, datetime2_col, smalldatetime_col, nvarchar_max, varchar_max,"
        " varbinary_max, image_col, xml_col, CAST(geometry_col AS VARBINARY(MAX)) AS geometry_bytes,"
        " CAST(geography_col AS VARBINARY(MAX)) AS geography_bytes,"
        " CAST(hierarchyid_col AS VARBINARY(MAX)) AS hierarchyid_bytes, sql_variant_col"
        " FROM " + table + " WHERE id = ?");
    SQLLEN id_length = sizeof(generated_id);
    bind(statement, 1, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &generated_id, sizeof(generated_id),
         &id_length, "id");
    require_success(SQLExecute(statement), "SQLExecute(select specific types)",
                    SQL_HANDLE_STMT, statement);
    SQLSMALLINT column_count = 0;
    require_success(SQLNumResultCols(statement, &column_count), "SQLNumResultCols",
                    SQL_HANDLE_STMT, statement);
    expect(column_count == 18, "expected eighteen SQL Server specific result columns");
    require_success(SQLFetch(statement), "SQLFetch(select specific types)",
                    SQL_HANDLE_STMT, statement);
    expect_column(statement, 2, "ntext_col", SQL_VARCHAR);
    expect_column(statement, 12, "varbinary_max", SQL_VARBINARY);
    expect(get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT) == generated_id,
           "specific-types SELECT returned the wrong identity");
    expect_text(statement, 2, ntext_value);
    expect_text(statement, 3, "TEXT content");
    expect_text(statement, 4, "123.4500");
    expect_text(statement, 5, "67.8900");
    expect(get_text(statement, 6).size() == 36, "UNIQUEIDENTIFIER should use the GUID format");
    expect_text(statement, 7, "2024-12-01 10:10:10");
    expect_text(statement, 8, "2024-12-01 14:30:45.123");
    expect_text(statement, 9, "2024-12-01 14:30:00");
    expect_text(statement, 10, large_unicode_text);
    expect_text(statement, 11, large_text);
    expect(get_binary(statement, 12, large_binary.size() + 16) == large_binary,
           "VARBINARY(MAX) did not round-trip");
    for (SQLUSMALLINT column = 13; column <= 18; ++column) {
        expect_null(statement, column, column == 14 || column == 18 ? SQL_C_CHAR : SQL_C_BINARY);
    }
    close_and_reset(statement);
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
        [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the SQL Server L2 suite\n";
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
    const std::string suffix = random_suffix();
    const std::vector<std::string> tables = {
        "ojp_cpp_mssql_l2_types_" + suffix, "ojp_cpp_mssql_l2_specific_" + suffix};
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

        verify_multiple_types(statement, tables[0]);
        verify_specific_and_large_types(statement, tables[1]);

        for (const auto& table : tables) {
            execute_direct(statement, "DROP TABLE IF EXISTS " + table);
        }
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
            for (const auto& table : tables) {
                const std::string drop = "DROP TABLE IF EXISTS " + table;
                SQLExecDirect(statement, sql_text(drop), SQL_NTS);
            }
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
    std::cout << "C++ ODBC SQL Server L2 integration test passed\n";
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
