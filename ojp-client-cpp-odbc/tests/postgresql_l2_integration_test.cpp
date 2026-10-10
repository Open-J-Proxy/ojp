// PostgreSQL equivalents of the JDBC multiple-types and statement L2 tests.
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
        throw std::runtime_error("cannot read the PostgreSQL connection CSV");
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
        throw std::runtime_error("expected JDBC URL, username, and password in PostgreSQL CSV");
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

void prepare(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLPrepare(statement, sql_text(sql), SQL_NTS),
                    "SQLPrepare", SQL_HANDLE_STMT, statement);
}

void execute_prepared(SQLHSTMT statement, const std::string& label) {
    require_success(SQLExecute(statement), "SQLExecute(" + label + ")",
                    SQL_HANDLE_STMT, statement);
}

void close_cursor(SQLHSTMT statement) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
}

void close_and_reset(SQLHSTMT statement) {
    close_cursor(statement);
    require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(parameters)",
                    SQL_HANDLE_STMT, statement);
}

void expect_rows_affected(SQLHSTMT statement, SQLLEN expected) {
    SQLLEN actual = -1;
    require_success(SQLRowCount(statement, &actual), "SQLRowCount", SQL_HANDLE_STMT, statement);
    expect(actual == expected, "expected " + std::to_string(expected) +
           " affected rows, got " + std::to_string(actual));
}

void fetch(SQLHSTMT statement) {
    require_success(SQLFetch(statement), "SQLFetch", SQL_HANDLE_STMT, statement);
}

void expect_end(SQLHSTMT statement) {
    expect(SQLFetch(statement) == SQL_NO_DATA, "query returned too many rows");
}

void bind(SQLHSTMT statement, SQLUSMALLINT index, SQLSMALLINT c_type, SQLSMALLINT sql_type,
          SQLULEN size, SQLSMALLINT scale, SQLPOINTER value, SQLLEN capacity, SQLLEN* length) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, c_type, sql_type,
                                     size, scale, value, capacity, length),
                    "SQLBindParameter(" + std::to_string(index) + ")", SQL_HANDLE_STMT, statement);
}

template <typename T>
T get_value(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type) {
    T value{};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, c_type, &value, sizeof(value), &length),
                    "SQLGetData(" + std::to_string(column) + ")", SQL_HANDLE_STMT, statement);
    expect(length == static_cast<SQLLEN>(sizeof(value)),
           "unexpected scalar length in column " + std::to_string(column));
    return value;
}

std::string get_text(SQLHSTMT statement, SQLUSMALLINT column, std::size_t capacity = 256) {
    std::vector<char> buffer(capacity + 1, '\0');
    SQLLEN length = 0;
    const SQLRETURN result = SQLGetData(statement, column, SQL_C_CHAR, buffer.data(),
                                       static_cast<SQLLEN>(buffer.size()), &length);
    require_success(result, "SQLGetData(text " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(result == SQL_SUCCESS && length >= 0 &&
           static_cast<std::size_t>(length) <= capacity,
           "NULL or truncated text in column " + std::to_string(column));
    return std::string(buffer.data(), static_cast<std::size_t>(length));
}

void expect_text(SQLHSTMT statement, SQLUSMALLINT column, const std::string& expected) {
    const std::string actual = get_text(statement, column, expected.size() + 64);
    expect(actual == expected, "column " + std::to_string(column) +
           ": expected '" + expected + "', got '" + actual + "'");
}

void expect_binary(SQLHSTMT statement, SQLUSMALLINT column,
                   const std::vector<SQLCHAR>& expected) {
    std::vector<SQLCHAR> buffer(expected.size() + 16);
    SQLLEN length = 0;
    const SQLRETURN result = SQLGetData(statement, column, SQL_C_BINARY, buffer.data(),
                                       static_cast<SQLLEN>(buffer.size()), &length);
    require_success(result, "SQLGetData(binary " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(result == SQL_SUCCESS && length == static_cast<SQLLEN>(expected.size()),
           "BYTEA length mismatch in column " + std::to_string(column));
    buffer.resize(expected.size());
    expect(buffer == expected, "BYTEA contents mismatch in column " + std::to_string(column));
}

void expect_null(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type) {
    SQLCHAR buffer[64] = {};
    SQLLEN length = 0;
    require_success(SQLGetData(statement, column, c_type, buffer, sizeof(buffer), &length),
                    "SQLGetData(NULL " + std::to_string(column) + ")",
                    SQL_HANDLE_STMT, statement);
    expect(length == SQL_NULL_DATA, "expected SQL_NULL_DATA in column " + std::to_string(column));
}

void expect_column(SQLHSTMT statement, SQLUSMALLINT column, const std::string& name,
                   SQLSMALLINT expected_type) {
    SQLCHAR actual_name[128] = {};
    SQLSMALLINT name_length = 0;
    SQLSMALLINT data_type = SQL_UNKNOWN_TYPE;
    SQLULEN size = 0;
    SQLSMALLINT digits = 0;
    SQLSMALLINT nullable = SQL_NULLABLE_UNKNOWN;
    require_success(SQLDescribeCol(statement, column, actual_name, sizeof(actual_name),
                                   &name_length, &data_type, &size, &digits, &nullable),
                    "SQLDescribeCol", SQL_HANDLE_STMT, statement);
    expect(std::string(reinterpret_cast<char*>(actual_name)) == name &&
           data_type == expected_type, "unexpected result metadata for " + name);
}

struct Parameter {
    SQLSMALLINT c_type;
    SQLSMALLINT sql_type;
    SQLULEN size;
    SQLSMALLINT scale;
    SQLPOINTER value;
    SQLLEN capacity;
    SQLLEN length;
};

void bind_parameters(SQLHSTMT statement, std::vector<Parameter>& parameters, bool nulls = false) {
    for (std::size_t index = 0; index < parameters.size(); ++index) {
        auto& parameter = parameters[index];
        if (nulls) {
            parameter.length = SQL_NULL_DATA;
        }
        bind(statement, static_cast<SQLUSMALLINT>(index + 1), parameter.c_type, parameter.sql_type,
             parameter.size, parameter.scale, parameter.value, parameter.capacity, &parameter.length);
    }
}

const std::string columns =
    "val_int, val_varchar, val_double, val_bigint, val_tinyint, val_smallint, val_boolean,"
    " val_numeric, val_decimal, val_float, val_byte, val_binary, val_date, val_time,"
    " val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant,"
    " val_offsetdatetime, val_offsettime, val_uuid";

void verify_multiple_types(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "CREATE TABLE " + table +
        " (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
        " val_int INTEGER UNIQUE, val_varchar VARCHAR(100), val_double DOUBLE PRECISION,"
        " val_bigint BIGINT, val_tinyint SMALLINT, val_smallint SMALLINT, val_boolean BOOLEAN,"
        " val_numeric NUMERIC(20,3), val_decimal DECIMAL(30,6), val_float REAL,"
        " val_byte BYTEA, val_binary BYTEA, val_date DATE, val_time TIME,"
        " val_timestamp TIMESTAMP, val_localdatetime TIMESTAMP, val_localdate DATE,"
        " val_localtime TIME, val_instant TIMESTAMP, val_offsetdatetime TIMESTAMPTZ,"
        " val_offsettime TIMETZ, val_uuid UUID)");
    close_and_reset(statement);
    // Explicit casts also make string-bound timezone values and UUIDs valid PostgreSQL parameters.
    prepare(statement, "INSERT INTO " + table + " (" + columns + ") VALUES"
        " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS DATE), CAST(? AS TIME),"
        " CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), CAST(? AS DATE), CAST(? AS TIME),"
        " CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMETZ), CAST(? AS UUID))");

    SQLINTEGER int_value = 1;
    std::string text = "TITLE_1 UTF-8: caf\xC3\xA9 \xE4\xB8\xAD\xE6\x96\x87 \xF0\x9F\x9A\x80";
    SQLDOUBLE double_value = 2.2222;
    SQLBIGINT bigint_value = 33333333333333LL;
    SQLSCHAR tiny_value = 127;
    SQLSMALLINT small_value = 32767;
    SQLCHAR boolean_value = 1;
    SQL_NUMERIC_STRUCT numeric{};
    numeric.precision = 20;
    numeric.scale = 3;
    numeric.sign = 1;
    // Unscaled 1234567, little-endian base 256: 1234.567.
    numeric.val[0] = 0x87;
    numeric.val[1] = 0xd6;
    numeric.val[2] = 0x12;
    std::string decimal = "12345678901234567890.123456";
    SQLREAL real_value = 20.20F;
    std::vector<SQLCHAR> byte = {0x01};
    std::vector<SQLCHAR> binary = {0x41, 0x00, 0xff, 0x42};
    SQL_DATE_STRUCT date{2025, 3, 29};
    SQL_TIME_STRUCT time{11, 12, 13};
    SQL_TIMESTAMP_STRUCT timestamp{2025, 3, 30, 21, 22, 23, 123456000};
    SQL_TIMESTAMP_STRUCT local_datetime{2024, 12, 1, 14, 30, 45, 0};
    SQL_DATE_STRUCT local_date{2024, 12, 15};
    SQL_TIME_STRUCT local_time{15, 45, 30};
    SQL_TIMESTAMP_STRUCT instant{2024, 12, 1, 10, 10, 10, 0};
    std::string offset_datetime = "2024-12-01 10:10:10.123456+02:00";
    std::string offset_time = "16:20:30-05:00";
    std::string uuid = "123e4567-e89b-12d3-a456-426614174000";
    std::vector<Parameter> parameters = {
        {SQL_C_SLONG, SQL_INTEGER, 0, 0, &int_value, sizeof(int_value), sizeof(int_value)},
        {SQL_C_CHAR, SQL_VARCHAR, 100, 0, text.data(), static_cast<SQLLEN>(text.size() + 1), SQL_NTS},
        {SQL_C_DOUBLE, SQL_DOUBLE, 0, 0, &double_value, sizeof(double_value), sizeof(double_value)},
        {SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &bigint_value, sizeof(bigint_value), sizeof(bigint_value)},
        {SQL_C_STINYINT, SQL_TINYINT, 0, 0, &tiny_value, sizeof(tiny_value), sizeof(tiny_value)},
        {SQL_C_SSHORT, SQL_SMALLINT, 0, 0, &small_value, sizeof(small_value), sizeof(small_value)},
        {SQL_C_BIT, SQL_BIT, 0, 0, &boolean_value, sizeof(boolean_value), sizeof(boolean_value)},
        {SQL_C_NUMERIC, SQL_NUMERIC, 20, 3, &numeric, sizeof(numeric), sizeof(numeric)},
        {SQL_C_CHAR, SQL_DECIMAL, 30, 6, decimal.data(),
            static_cast<SQLLEN>(decimal.size() + 1), SQL_NTS},
        {SQL_C_FLOAT, SQL_REAL, 0, 0, &real_value, sizeof(real_value), sizeof(real_value)},
        {SQL_C_BINARY, SQL_VARBINARY, byte.size(), 0, byte.data(), 1, 1},
        {SQL_C_BINARY, SQL_VARBINARY, binary.size(), 0, binary.data(), 4, 4},
        {SQL_C_TYPE_DATE, SQL_TYPE_DATE, 0, 0, &date, sizeof(date), sizeof(date)},
        {SQL_C_TYPE_TIME, SQL_TYPE_TIME, 0, 0, &time, sizeof(time), sizeof(time)},
        {SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 26, 6, &timestamp,
            sizeof(timestamp), sizeof(timestamp)},
        {SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 26, 6, &local_datetime,
            sizeof(local_datetime), sizeof(local_datetime)},
        {SQL_C_TYPE_DATE, SQL_TYPE_DATE, 0, 0, &local_date, sizeof(local_date), sizeof(local_date)},
        {SQL_C_TYPE_TIME, SQL_TYPE_TIME, 0, 0, &local_time, sizeof(local_time), sizeof(local_time)},
        {SQL_C_TYPE_TIMESTAMP, SQL_TYPE_TIMESTAMP, 26, 6, &instant, sizeof(instant), sizeof(instant)},
        {SQL_C_CHAR, SQL_VARCHAR, 40, 0, offset_datetime.data(),
            static_cast<SQLLEN>(offset_datetime.size() + 1), SQL_NTS},
        {SQL_C_CHAR, SQL_VARCHAR, 40, 0, offset_time.data(),
            static_cast<SQLLEN>(offset_time.size() + 1), SQL_NTS},
        {SQL_C_CHAR, SQL_VARCHAR, 36, 0, uuid.data(), static_cast<SQLLEN>(uuid.size() + 1), SQL_NTS}
    };
    bind_parameters(statement, parameters);
    execute_prepared(statement, "insert positive types");
    expect_rows_affected(statement, 1);
    close_cursor(statement);

    // Reuse the prepared INSERT, including an explicit rebind to a different text buffer.
    int_value = 2;
    bigint_value = -33333333333333LL;
    tiny_value = -128;
    small_value = -32768;
    boolean_value = 0;
    numeric.sign = 0;
    decimal = "-12345678901234567890.123456";
    parameters[8].value = decimal.data();
    parameters[8].capacity = static_cast<SQLLEN>(decimal.size() + 1);
    bind_parameters(statement, parameters);
    execute_prepared(statement, "insert negative types");
    expect_rows_affected(statement, 1);
    close_cursor(statement);

    // Typed NULL input for every supported parameter family, not merely omitted columns.
    bind_parameters(statement, parameters, true);
    execute_prepared(statement, "insert typed NULLs");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);

    // Table-isolated lookup avoids currval()/lastval() assumptions across pooled JDBC sessions.
    execute_direct(statement, "SELECT id FROM " + table + " ORDER BY id");
    SQLBIGINT ids[3] = {};
    for (auto& id : ids) {
        fetch(statement);
        id = get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT);
    }
    expect_end(statement);
    expect(ids[0] > 0 && ids[1] > ids[0] && ids[2] > ids[1],
           "generated PostgreSQL identities should be positive and distinct");
    close_and_reset(statement);

    const std::string select =
        "SELECT id, val_int, val_varchar, val_double, val_bigint, val_tinyint, val_smallint,"
        " val_boolean, val_numeric, val_decimal, val_float, val_byte, val_binary, val_date,"
        " val_time, val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant,"
        " CAST(val_offsetdatetime AT TIME ZONE 'UTC' AS TEXT) AS utc_offsetdatetime,"
        " CAST(val_offsettime AS TEXT) AS offsettime_text, CAST(val_uuid AS TEXT) AS uuid_text"
        " FROM " + table + " WHERE id = ?";
    prepare(statement, select);
    SQLBIGINT id = ids[0];
    SQLLEN id_length = sizeof(id);
    bind(statement, 1, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id, sizeof(id), &id_length);
    for (int row = 0; row < 3; ++row) {
        id = ids[row];
        bind(statement, 1, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id, sizeof(id), &id_length);
        execute_prepared(statement, "select types with rebound id");
        SQLSMALLINT count = 0;
        require_success(SQLNumResultCols(statement, &count), "SQLNumResultCols",
                        SQL_HANDLE_STMT, statement);
        expect(count == 23, "expected twenty-three PostgreSQL result columns");
        expect_column(statement, 1, "id", SQL_BIGINT);
        expect_column(statement, 2, "val_int", SQL_INTEGER);
        expect_column(statement, 3, "val_varchar", SQL_VARCHAR);
        expect_column(statement, 4, "val_double", SQL_DOUBLE);
        expect_column(statement, 8, "val_boolean", SQL_BIT);
        expect_column(statement, 12, "val_byte", SQL_VARBINARY);
        expect_column(statement, 14, "val_date", SQL_TYPE_DATE);
        fetch(statement);
        expect(get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT) == id, "wrong generated id");
        if (row == 2) {
            for (SQLUSMALLINT column = 2; column <= 23; ++column) {
                const SQLSMALLINT type = column == 12 || column == 13 ? SQL_C_BINARY :
                    column == 8 ? SQL_C_BIT : column == 4 ? SQL_C_DOUBLE :
                    column == 5 ? SQL_C_SBIGINT : column == 11 ? SQL_C_FLOAT :
                    column == 2 || column == 6 || column == 7 ? SQL_C_SLONG : SQL_C_CHAR;
                expect_null(statement, column, type);
            }
        } else {
            expect(get_value<SQLINTEGER>(statement, 2, SQL_C_SLONG) == row + 1, "wrong INTEGER");
            expect_text(statement, 3, text);
            expect(get_value<SQLDOUBLE>(statement, 4, SQL_C_DOUBLE) == double_value, "wrong DOUBLE");
            expect(get_value<SQLBIGINT>(statement, 5, SQL_C_SBIGINT) ==
                   (row == 0 ? 33333333333333LL : -33333333333333LL), "wrong BIGINT");
            expect(get_value<SQLINTEGER>(statement, 6, SQL_C_SLONG) == (row == 0 ? 127 : -128),
                   "wrong PostgreSQL SMALLINT representation of a tiny integer");
            expect(get_value<SQLINTEGER>(statement, 7, SQL_C_SLONG) ==
                   (row == 0 ? 32767 : -32768), "wrong SMALLINT");
            expect(get_value<SQLCHAR>(statement, 8, SQL_C_BIT) == (row == 0 ? 1 : 0),
                   "BOOLEAN true/false did not round-trip");
            expect_text(statement, 9, row == 0 ? "1234.567" : "-1234.567");
            expect_text(statement, 10, row == 0 ?
                        "12345678901234567890.123456" : "-12345678901234567890.123456");
            expect(get_value<SQLREAL>(statement, 11, SQL_C_FLOAT) == real_value, "wrong REAL");
            expect_binary(statement, 12, byte);
            expect_binary(statement, 13, binary);
            expect_text(statement, 14, "2025-03-29");
            expect_text(statement, 15, "11:12:13");
            expect_text(statement, 16, "2025-03-30 21:22:23.123456");
            expect_text(statement, 17, "2024-12-01 14:30:45");
            expect_text(statement, 18, "2024-12-15");
            expect_text(statement, 19, "15:45:30");
            expect_text(statement, 20, "2024-12-01 10:10:10");
            expect_text(statement, 21, "2024-12-01 08:10:10.123456");
            expect_text(statement, 22, "16:20:30-05");
            expect_text(statement, 23, uuid);
        }
        expect_end(statement);
        close_cursor(statement);
    }
    close_and_reset(statement);

    prepare(statement, "UPDATE " + table + " SET val_boolean = ?, val_varchar = ? WHERE id = ?");
    SQLCHAR updated_boolean = 0;
    SQLLEN boolean_length = sizeof(updated_boolean);
    std::string updated_text = "prepared update";
    SQLLEN text_length = SQL_NTS;
    id = ids[0];
    bind(statement, 1, SQL_C_BIT, SQL_BIT, 0, 0, &updated_boolean,
         sizeof(updated_boolean), &boolean_length);
    bind(statement, 2, SQL_C_CHAR, SQL_VARCHAR, 100, 0, updated_text.data(),
         static_cast<SQLLEN>(updated_text.size() + 1), &text_length);
    bind(statement, 3, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id, sizeof(id), &id_length);
    execute_prepared(statement, "update");
    expect_rows_affected(statement, 1);
    close_cursor(statement);
    updated_boolean = 1;
    updated_text = "rebound update";
    bind(statement, 2, SQL_C_CHAR, SQL_VARCHAR, 100, 0, updated_text.data(),
         static_cast<SQLLEN>(updated_text.size() + 1), &text_length);
    execute_prepared(statement, "repeated update");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);
    execute_direct(statement, "SELECT val_boolean, val_varchar FROM " + table +
                   " WHERE id = " + std::to_string(ids[0]));
    fetch(statement);
    expect(get_value<SQLCHAR>(statement, 1, SQL_C_BIT) == 1, "repeated UPDATE lost boolean");
    expect_text(statement, 2, updated_text);
    expect_end(statement);
    close_and_reset(statement);

    execute_direct(statement, "INSERT INTO " + table +
                   " (val_int, val_boolean) VALUES (4, false)");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);
    execute_direct(statement, "UPDATE " + table + " SET val_varchar = 'direct update' WHERE val_int = 4");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);
    execute_direct(statement, "SELECT id, val_boolean, val_varchar FROM " + table + " WHERE val_int = 4");
    fetch(statement);
    expect(get_value<SQLBIGINT>(statement, 1, SQL_C_SBIGINT) > ids[2], "direct INSERT lost identity");
    expect(get_value<SQLCHAR>(statement, 2, SQL_C_BIT) == 0, "direct false value was lost");
    expect_text(statement, 3, "direct update");
    expect_end(statement);
    close_and_reset(statement);
    execute_direct(statement, "DELETE FROM " + table + " WHERE val_int = 4");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);
    prepare(statement, "SELECT id FROM " + table + " WHERE id = ?");
    id = -1;
    bind(statement, 1, SQL_C_SBIGINT, SQL_BIGINT, 0, 0, &id, sizeof(id), &id_length);
    execute_prepared(statement, "empty query");
    expect_end(statement);
    close_and_reset(statement);
}

void verify_large_types(SQLHSTMT statement, const std::string& table) {
    execute_direct(statement, "CREATE TABLE " + table +
                   " (id INTEGER PRIMARY KEY, text_value TEXT, binary_value BYTEA)");
    close_and_reset(statement);
    std::string text;
    for (int index = 0; index < 4000; ++index) {
        text += "UTF-8 caf\xC3\xA9 \xE4\xB8\xAD\xE6\x96\x87 \xF0\x9F\x9A\x80; ";
    }
    std::vector<SQLCHAR> binary(100000);
    for (std::size_t index = 0; index < binary.size(); ++index) {
        binary[index] = static_cast<SQLCHAR>(index % 256);
    }
    prepare(statement, "INSERT INTO " + table + " VALUES (?, ?, ?)");
    SQLINTEGER id = 1;
    SQLLEN id_length = sizeof(id);
    SQLLEN text_length = static_cast<SQLLEN>(text.size());
    SQLLEN binary_length = static_cast<SQLLEN>(binary.size());
    bind(statement, 1, SQL_C_SLONG, SQL_INTEGER, 0, 0, &id, sizeof(id), &id_length);
    bind(statement, 2, SQL_C_CHAR, SQL_LONGVARCHAR, text.size(), 0, text.data(),
         text_length + 1, &text_length);
    bind(statement, 3, SQL_C_BINARY, SQL_LONGVARBINARY, binary.size(), 0, binary.data(),
         binary_length, &binary_length);
    execute_prepared(statement, "large TEXT/BYTEA insert");
    expect_rows_affected(statement, 1);
    close_cursor(statement);
    id = 2;
    text_length = 0;
    binary_length = 0;
    execute_prepared(statement, "empty TEXT/BYTEA insert");
    expect_rows_affected(statement, 1);
    close_cursor(statement);
    id = 3;
    text_length = SQL_NULL_DATA;
    binary_length = SQL_NULL_DATA;
    execute_prepared(statement, "NULL TEXT/BYTEA insert");
    expect_rows_affected(statement, 1);
    close_and_reset(statement);

    execute_direct(statement, "SELECT id, text_value, binary_value FROM " + table + " ORDER BY id");
    fetch(statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == 1, "wrong large row id");
    expect_text(statement, 2, text);
    expect_binary(statement, 3, binary);
    fetch(statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == 2, "wrong empty row id");
    expect_text(statement, 2, "");
    expect_binary(statement, 3, {});
    fetch(statement);
    expect(get_value<SQLINTEGER>(statement, 1, SQL_C_SLONG) == 3, "wrong NULL row id");
    expect_null(statement, 2, SQL_C_CHAR);
    expect_null(statement, 3, SQL_C_BINARY);
    expect_end(statement);
    close_and_reset(statement);
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error("expected PostgreSQL CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(),
        [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the PostgreSQL L2 suite\n";
        return 77;
    }
    if (enabled != "true" && enabled != "1" && enabled != "yes") {
        throw std::runtime_error(enable_variable + " must be true or false");
    }
    const char* endpoint_value = std::getenv(endpoint_variable.c_str());
    const std::string endpoint = endpoint_value == nullptr ? "" : endpoint_value;
    if (endpoint.empty()) {
        throw std::runtime_error(endpoint_variable + " is required when " + enable_variable + "=true");
    }
    const DatabaseConfig config = read_connection_config(argv[1]);
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    const std::string suffix = random_suffix();
    const std::vector<std::string> tables = {
        "ojp_cpp_pg_l2_types_" + suffix, "ojp_cpp_pg_l2_large_" + suffix};
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
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) + ";UID=" + brace_value(config.user) +
            ";P" "WD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string), SQL_NTS,
                                         nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection,
                                       reinterpret_cast<SQLHANDLE*>(&statement)),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);

        verify_multiple_types(statement, tables[0]);
        verify_large_types(statement, tables[1]);
        for (const auto& table : tables) {
            execute_direct(statement, "DROP TABLE IF EXISTS " + table);
            close_and_reset(statement);
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
                if (!SQL_SUCCEEDED(SQLExecDirect(statement, sql_text(drop), SQL_NTS))) {
                    std::cerr << "Cleanup failed for " << table << '\n';
                }
                SQLFreeStmt(statement, SQL_CLOSE);
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
    std::cout << "C++ ODBC PostgreSQL L2 integration test passed\n"
              << "Limitations: temporal/numeric results use text; timezone and UUID parameters use "
                 "explicit SQL casts. Java objects, PGobject/JSON, arrays, callable/batch APIs, "
                 "and L5 LOB/stream semantics are not covered.\n";
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
