// Verifies SQL Server column metadata, C type conversions, typed parameter binding, large LOBs,
// timestamps across the whole DATETIME2 range, procedure result sets and SQLGetTypeInfoW.
#include "connection_suite.h"

#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
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
    for (const char character : value) {
        escaped.push_back(character);
        if (character == '}') {
            escaped.push_back('}');
        }
    }
    return "{" + escaped + "}";
}

std::string diagnostic(SQLSMALLINT handle_type, SQLHANDLE handle, std::string* state) {
    SQLCHAR sql_state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT length = 0;
    if (handle == SQL_NULL_HANDLE ||
        SQLGetDiagRec(handle_type, handle, 1, sql_state, &native_error, detail, sizeof(detail),
                      &length) != SQL_SUCCESS) {
        return "";
    }
    if (state != nullptr) {
        *state = reinterpret_cast<const char*>(sql_state);
    }
    return std::string("[") + reinterpret_cast<const char*>(sql_state) + "] " +
           reinterpret_cast<const char*>(detail);
}

void require_success(SQLRETURN result, const std::string& operation,
                     SQLSMALLINT handle_type = SQL_HANDLE_ENV,
                     SQLHANDLE handle = SQL_NULL_HANDLE) {
    if (!SQL_SUCCEEDED(result)) {
        throw std::runtime_error(operation + " failed " + diagnostic(handle_type, handle, nullptr));
    }
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

class Statement {
public:
    explicit Statement(SQLHSTMT handle) : handle_(handle) {}

    SQLHSTMT handle() const { return handle_; }

    void execute(const std::string& sql) {
        require_success(SQLExecDirect(handle_, sql_text(sql), SQL_NTS), "SQLExecDirect " + sql,
                        SQL_HANDLE_STMT, handle_);
    }

    void prepare(const std::string& sql) {
        require_success(SQLPrepare(handle_, sql_text(sql), SQL_NTS), "SQLPrepare " + sql,
                        SQL_HANDLE_STMT, handle_);
    }

    void run_prepared(const std::string& operation) {
        require_success(SQLExecute(handle_), operation, SQL_HANDLE_STMT, handle_);
    }

    void fetch(const std::string& operation) {
        require_success(SQLFetch(handle_), "SQLFetch " + operation, SQL_HANDLE_STMT, handle_);
    }

    void close() {
        SQLFreeStmt(handle_, SQL_CLOSE);
        SQLFreeStmt(handle_, SQL_RESET_PARAMS);
    }

    std::string text(SQLUSMALLINT column) {
        char value[256] = {};
        SQLLEN length = 0;
        require_success(SQLGetData(handle_, column, SQL_C_CHAR, value, sizeof(value), &length),
                        "SQLGetData(SQL_C_CHAR)", SQL_HANDLE_STMT, handle_);
        return length == SQL_NULL_DATA ? "<null>" : value;
    }

    template <typename T>
    T get(SQLUSMALLINT column, SQLSMALLINT c_type, const std::string& operation) {
        T value{};
        SQLLEN length = 0;
        require_success(SQLGetData(handle_, column, c_type, &value, sizeof(value), &length),
                        "SQLGetData " + operation, SQL_HANDLE_STMT, handle_);
        expect(length != SQL_NULL_DATA, operation + " returned NULL");
        return value;
    }

    // Returns the SQLSTATE of the first diagnostic after a call that did not return SQL_SUCCESS.
    std::string state_of(SQLRETURN result, SQLRETURN expected, const std::string& operation) {
        expect(result == expected, operation + " returned " + std::to_string(result));
        std::string state;
        diagnostic(SQL_HANDLE_STMT, handle_, &state);
        return state;
    }

private:
    SQLHSTMT handle_;
};

struct ColumnShape {
    const char* name;
    SQLSMALLINT type;
    SQLULEN size;
    SQLSMALLINT digits;
    SQLSMALLINT nullable;
};

// Column metadata comes from java.sql.ResultSetMetaData, not from the values.
void check_metadata(Statement& statement, const std::string& table) {
    statement.execute("SELECT id, nv, nv_max, dec_value, vb_max, date_value, dto_value FROM " +
                      table + " WHERE 1 = 0");
    const ColumnShape expected[] = {
        {"id", SQL_INTEGER, 10, 0, SQL_NO_NULLS},
        {"nv", SQL_WVARCHAR, 10, 0, SQL_NULLABLE},
        {"nv_max", SQL_WLONGVARCHAR, 1073741823, 0, SQL_NULLABLE},
        {"dec_value", SQL_DECIMAL, 10, 2, SQL_NULLABLE},
        {"vb_max", SQL_LONGVARBINARY, 2147483647, 0, SQL_NULLABLE},
        {"date_value", SQL_TYPE_DATE, 10, 0, SQL_NULLABLE},
        {"dto_value", SQL_WVARCHAR, 34, 0, SQL_NULLABLE},
    };
    for (SQLUSMALLINT column = 1; column <= 7; ++column) {
        const auto& shape = expected[column - 1];
        SQLCHAR name[64] = {};
        SQLSMALLINT name_length = 0;
        SQLSMALLINT type = 0;
        SQLULEN size = 0;
        SQLSMALLINT digits = 0;
        SQLSMALLINT nullable = 0;
        require_success(SQLDescribeCol(statement.handle(), column, name, sizeof(name),
                                       &name_length, &type, &size, &digits, &nullable),
                        "SQLDescribeCol", SQL_HANDLE_STMT, statement.handle());
        std::ostringstream actual;
        actual << reinterpret_cast<const char*>(name) << " type=" << type << " size=" << size
               << " digits=" << digits << " nullable=" << nullable;
        expect(std::string(reinterpret_cast<const char*>(name)) == shape.name &&
                   type == shape.type && size == shape.size && digits == shape.digits &&
                   nullable == shape.nullable,
               "Unexpected column metadata: " + actual.str());
    }
    SQLLEN display_size = 0;
    require_success(SQLColAttribute(statement.handle(), 1, SQL_DESC_DISPLAY_SIZE, nullptr, 0,
                                    nullptr, &display_size),
                    "SQLColAttribute(SQL_DESC_DISPLAY_SIZE)", SQL_HANDLE_STMT, statement.handle());
    expect(display_size == 11, "INT must have a display size of 11");
    SQLLEN is_unsigned = SQL_TRUE;
    require_success(SQLColAttribute(statement.handle(), 1, SQL_DESC_UNSIGNED, nullptr, 0,
                                    nullptr, &is_unsigned),
                    "SQLColAttribute(SQL_DESC_UNSIGNED)", SQL_HANDLE_STMT, statement.handle());
    expect(is_unsigned == SQL_FALSE, "INT must be reported as signed");
    statement.close();
}

void check_parameter_binding(Statement& statement, const std::string& table) {
    statement.prepare("INSERT INTO " + table + " (id, guid_value, unsigned_value, dec_value,"
                      " real_value, date_value) VALUES (?, ?, ?, ?, ?, ?)");
    SQLINTEGER id = 1;
    SQLGUID guid = {0x6F9619FF, 0x8B86, 0xD011, {0xB4, 0x2D, 0x00, 0xC0, 0x4F, 0xC9, 0x64, 0xFF}};
    SQLUINTEGER unsigned_value = 4000000000u;
    char decimal_text[] = "12345.67";
    SQLDOUBLE real_value = 1.5;
    // ODBC 2.x date type.
    DATE_STRUCT date_value = {2024, 2, 29};
    SQLLEN lengths[6] = {0, 0, 0, SQL_NTS, 0, 0};
    const SQLHSTMT handle = statement.handle();
    require_success(SQLBindParameter(handle, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                                     &id, 0, &lengths[0]), "bind id", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 2, SQL_PARAM_INPUT, SQL_C_GUID, SQL_GUID, 36, 0,
                                     &guid, 0, &lengths[1]), "bind guid", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 3, SQL_PARAM_INPUT, SQL_C_ULONG, SQL_BIGINT, 0, 0,
                                     &unsigned_value, 0, &lengths[2]),
                    "bind unsigned", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 4, SQL_PARAM_INPUT, SQL_C_CHAR, SQL_DECIMAL, 10, 2,
                                     decimal_text, sizeof(decimal_text), &lengths[3]),
                    "bind decimal", SQL_HANDLE_STMT, handle);
    // The value is aligned with the declared SQL type: the server casts strictly.
    require_success(SQLBindParameter(handle, 5, SQL_PARAM_INPUT, SQL_C_DOUBLE, SQL_REAL, 0, 0,
                                     &real_value, 0, &lengths[4]),
                    "bind real", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 6, SQL_PARAM_INPUT, SQL_C_DATE, SQL_DATE, 10, 0,
                                     &date_value, 0, &lengths[5]),
                    "bind date", SQL_HANDLE_STMT, handle);
    statement.run_prepared("SQLExecute(insert typed parameters)");
    statement.close();

    statement.execute("SELECT CONVERT(VARCHAR(36), guid_value), unsigned_value,"
                      " CONVERT(VARCHAR(20), dec_value), real_value,"
                      " CONVERT(VARCHAR(10), date_value, 23), guid_value FROM " + table +
                      " WHERE id = 1");
    statement.fetch("typed parameters");
    expect(statement.text(1) == "6F9619FF-8B86-D011-B42D-00C04FC964FF",
           "GUID parameter did not round-trip");
    expect(statement.get<SQLBIGINT>(2, SQL_C_SBIGINT, "unsigned") == 4000000000LL,
           "Unsigned parameter did not round-trip");
    expect(statement.text(3) == "12345.67", "Decimal parameter did not round-trip");
    expect(statement.get<SQLREAL>(4, SQL_C_FLOAT, "real") == 1.5f,
           "Double bound as SQL_REAL did not round-trip");
    expect(statement.text(5) == "2024-02-29", "ODBC 2.x date parameter did not round-trip");
    const auto read_guid = statement.get<SQLGUID>(6, SQL_C_GUID, "guid");
    expect(std::memcmp(&read_guid, &guid, sizeof(guid)) == 0, "SQL_C_GUID conversion failed");
    statement.close();
}

void check_conversions(Statement& statement, const std::string& table) {
    statement.execute("UPDATE " + table + " SET nv = N'abcdefgh', small_value = 300,"
                      " dto_value = '2024-12-01 10:10:10 +02:00' WHERE id = 1");
    statement.close();
    statement.execute("SELECT dec_value, small_value, nv, dec_value, date_value, dto_value FROM " +
                      table + " WHERE id = 1");
    statement.fetch("conversions");
    const auto numeric = statement.get<SQL_NUMERIC_STRUCT>(1, SQL_C_NUMERIC, "numeric");
    expect(numeric.sign == 1 && numeric.val[0] == (1234567 & 0xff) &&
               numeric.val[1] == ((1234567 >> 8) & 0xff) &&
               numeric.val[2] == ((1234567 >> 16) & 0xff),
           "DECIMAL to SQL_C_NUMERIC conversion failed");

    SQLCHAR tiny = 0;
    SQLLEN length = 0;
    expect(statement.state_of(SQLGetData(statement.handle(), 2, SQL_C_UTINYINT, &tiny,
                                         sizeof(tiny), &length),
                              SQL_ERROR, "SQLGetData(300 as SQL_C_UTINYINT)") == "22003",
           "An out-of-range conversion must report 22003");

    char short_text[5] = {};
    expect(statement.state_of(SQLGetData(statement.handle(), 3, SQL_C_CHAR, short_text,
                                         sizeof(short_text), &length),
                              SQL_SUCCESS_WITH_INFO, "SQLGetData(truncated text)") == "01004",
           "A truncated string must report 01004");
    expect(std::string(short_text) == "abcd", "The truncated string has the wrong prefix");

    SQLINTEGER whole = 0;
    expect(statement.state_of(SQLGetData(statement.handle(), 4, SQL_C_SLONG, &whole,
                                         sizeof(whole), &length),
                              SQL_SUCCESS_WITH_INFO, "SQLGetData(12345.67 as SQL_C_SLONG)") ==
               "01S07",
           "A fractional truncation must report 01S07");
    expect(whole == 12345, "DECIMAL to SQL_C_SLONG conversion failed");

    const auto date = statement.get<SQL_DATE_STRUCT>(5, SQL_C_TYPE_DATE, "date");
    expect(date.year == 2024 && date.month == 2 && date.day == 29,
           "DATE to SQL_C_TYPE_DATE conversion failed");
    expect(statement.text(6) == "2024-12-01 10:10:10 +02:00",
           "DATETIMEOFFSET must keep its offset");
    statement.close();
}

// Text above 2 MiB is sent as a CLOB; the result is larger than the default gRPC message limit.
void check_large_values(Statement& statement, const std::string& table) {
    std::string long_text(3 * 1024 * 1024, 'x');
    long_text.front() = 'a';
    long_text.back() = 'z';
    std::vector<unsigned char> blob(9 * 1024 * 1024);
    for (std::size_t index = 0; index < blob.size(); ++index) {
        blob[index] = static_cast<unsigned char>((index * 31) & 0xff);
    }
    statement.prepare("UPDATE " + table + " SET nv_max = ?, vb_max = ? WHERE id = 1");
    SQLLEN text_length = static_cast<SQLLEN>(long_text.size());
    SQLLEN blob_length = static_cast<SQLLEN>(blob.size());
    const SQLHSTMT handle = statement.handle();
    require_success(SQLBindParameter(handle, 1, SQL_PARAM_INPUT, SQL_C_CHAR, SQL_WLONGVARCHAR,
                                     long_text.size(), 0, &long_text[0], text_length,
                                     &text_length), "bind long text", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 2, SQL_PARAM_INPUT, SQL_C_BINARY, SQL_LONGVARBINARY,
                                     blob.size(), 0, blob.data(), blob_length, &blob_length),
                    "bind blob", SQL_HANDLE_STMT, handle);
    statement.run_prepared("SQLExecute(update large values)");
    statement.close();

    statement.execute("SELECT LEN(nv_max), LEFT(nv_max, 1) + RIGHT(nv_max, 1), vb_max FROM " +
                      table + " WHERE id = 1");
    statement.fetch("large values");
    expect(statement.get<SQLINTEGER>(1, SQL_C_SLONG, "text length") ==
               static_cast<SQLINTEGER>(long_text.size()),
           "Long text was not stored completely");
    expect(statement.text(2) == "az", "Long text was stored with the wrong content");
    std::vector<unsigned char> read;
    std::vector<unsigned char> chunk(1024 * 1024);
    for (;;) {
        SQLLEN length = 0;
        const SQLRETURN result = SQLGetData(handle, 3, SQL_C_BINARY, chunk.data(),
                                            static_cast<SQLLEN>(chunk.size()), &length);
        if (result == SQL_NO_DATA) {
            break;
        }
        require_success(result, "SQLGetData(blob chunk)", SQL_HANDLE_STMT, handle);
        const auto received = length == SQL_NO_TOTAL || length > static_cast<SQLLEN>(chunk.size())
            ? chunk.size() : static_cast<std::size_t>(length);
        read.insert(read.end(), chunk.begin(), chunk.begin() + received);
        if (result == SQL_SUCCESS) {
            break;
        }
    }
    expect(read == blob, "The 9 MiB VARBINARY(MAX) value did not round-trip");
    statement.close();
}

// Timestamps outside 1970-3000 and before the Gregorian cutover keep their civil value.
void check_timestamps(Statement& statement, const std::string& table) {
    const struct {
        SQL_TIMESTAMP_STRUCT value;
        const char* text;
    } cases[] = {
        {{1, 1, 1, 0, 0, 0, 0}, "0001-01-01 00:00:00.0000000"},
        {{1500, 6, 15, 12, 30, 0, 0}, "1500-06-15 12:30:00.0000000"},
        {{1582, 10, 15, 0, 0, 0, 0}, "1582-10-15 00:00:00.0000000"},
        {{1969, 12, 31, 23, 59, 59, 0}, "1969-12-31 23:59:59.0000000"},
        {{9999, 12, 31, 23, 59, 59, 123456700}, "9999-12-31 23:59:59.1234567"},
    };
    SQLINTEGER id = 0;
    SQL_TIMESTAMP_STRUCT value{};
    SQLLEN lengths[2] = {0, 0};
    const SQLHSTMT handle = statement.handle();
    statement.prepare("INSERT INTO " + table + " (id, ts_value) VALUES (?, ?)");
    require_success(SQLBindParameter(handle, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                                     &id, 0, &lengths[0]), "bind id", SQL_HANDLE_STMT, handle);
    require_success(SQLBindParameter(handle, 2, SQL_PARAM_INPUT, SQL_C_TYPE_TIMESTAMP,
                                     SQL_TYPE_TIMESTAMP, 27, 7, &value, 0, &lengths[1]),
                    "bind timestamp", SQL_HANDLE_STMT, handle);
    for (std::size_t index = 0; index < sizeof(cases) / sizeof(cases[0]); ++index) {
        id = static_cast<SQLINTEGER>(100 + index);
        value = cases[index].value;
        statement.run_prepared(std::string("SQLExecute(timestamp ") + cases[index].text + ")");
    }
    statement.close();

    statement.execute("SELECT CONVERT(VARCHAR(27), ts_value, 121), ts_value FROM " + table +
                      " WHERE id >= 100 ORDER BY id");
    for (const auto& item : cases) {
        statement.fetch(item.text);
        expect(statement.text(1) == item.text,
               std::string("Timestamp parameter was stored with another value: ") + item.text);
        const auto read = statement.get<SQL_TIMESTAMP_STRUCT>(2, SQL_C_TYPE_TIMESTAMP,
                                                              item.text);
        expect(std::memcmp(&read, &item.value, sizeof(read)) == 0,
               std::string("Timestamp was read with another value: ") + item.text);
    }
    statement.close();
}

void check_procedure_result_set(Statement& statement, const std::string& procedure) {
    statement.execute("CREATE PROCEDURE " + procedure +
                      " @value INT AS BEGIN SET NOCOUNT ON; SELECT @value * 2 AS doubled; END");
    statement.close();
    for (const std::string sql : {"EXEC " + procedure + " 21", "{CALL " + procedure + "(21)}"}) {
        statement.execute(sql);
        statement.fetch(sql);
        expect(statement.get<SQLINTEGER>(1, SQL_C_SLONG, sql) == 42,
               "The procedure result set was not returned for " + sql);
        expect(SQLFetch(statement.handle()) == SQL_NO_DATA, "Unexpected procedure rows: " + sql);
        statement.close();
    }
}

void check_type_info(Statement& statement) {
    require_success(SQLGetTypeInfoW(statement.handle(), SQL_INTEGER), "SQLGetTypeInfoW",
                    SQL_HANDLE_STMT, statement.handle());
    statement.fetch("SQLGetTypeInfoW");
    expect(statement.get<SQLSMALLINT>(2, SQL_C_SSHORT, "DATA_TYPE") == SQL_INTEGER,
           "SQLGetTypeInfoW must return the requested type");
    expect(statement.text(1) == "int", "SQLGetTypeInfoW returned an unexpected TYPE_NAME");
    statement.close();
}

int run_connection(const DatabaseConfig& config, const std::string& endpoint) {
    const std::string suffix = random_suffix();
    const std::string table = "ojp_cpp_mssql_types_" + suffix;
    const std::string procedure = "ojp_cpp_mssql_proc_" + suffix;
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT handle = SQL_NULL_HSTMT;
    try {
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection),
                        "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
        // A 1 MiB LOB chunk splits the 9 MiB upload into several requests.
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) +
            ";UID=" + brace_value(config.user) +
            ";PWD=" + brace_value(config.password) + ";OJP.LOB.CHUNK.SIZE=1048576;";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string),
                                         SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &handle),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);
        Statement statement(handle);
        statement.execute("CREATE TABLE " + table + " (id INT NOT NULL PRIMARY KEY,"
                          " nv NVARCHAR(10), nv_max NVARCHAR(MAX), dec_value DECIMAL(10,2),"
                          " vb_max VARBINARY(MAX), date_value DATE, dto_value DATETIMEOFFSET,"
                          " guid_value UNIQUEIDENTIFIER, unsigned_value BIGINT, real_value REAL,"
                          " small_value INT, ts_value DATETIME2)");
        statement.close();

        check_metadata(statement, table);
        check_parameter_binding(statement, table);
        check_conversions(statement, table);
        check_large_values(statement, table);
        check_timestamps(statement, table);
        check_procedure_result_set(statement, procedure);
        check_type_info(statement);

        statement.execute("DROP PROCEDURE " + procedure);
        statement.close();
        statement.execute("DROP TABLE " + table);
        statement.close();
        require_success(SQLFreeHandle(SQL_HANDLE_STMT, handle), "SQLFreeHandle(statement)",
                        SQL_HANDLE_DBC, connection);
        handle = SQL_NULL_HSTMT;
        require_success(SQLDisconnect(connection), "SQLDisconnect", SQL_HANDLE_DBC, connection);
        require_success(SQLFreeHandle(SQL_HANDLE_DBC, connection), "SQLFreeHandle(connection)",
                        SQL_HANDLE_ENV, environment);
        connection = SQL_NULL_HDBC;
        require_success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "SQLFreeHandle(environment)");
        environment = SQL_NULL_HENV;
    } catch (const std::exception& error) {
        // The connection suite hides exception messages because connection diagnostics may
        // contain credentials; failures after the connection is open are printed here.
        if (handle != SQL_NULL_HSTMT) {
            std::cerr << error.what() << '\n';
        }
        if (handle != SQL_NULL_HSTMT) {
            SQLFreeStmt(handle, SQL_CLOSE);
            SQLFreeStmt(handle, SQL_RESET_PARAMS);
            const std::string cleanup = "IF OBJECT_ID('" + procedure + "') IS NOT NULL DROP "
                "PROCEDURE " + procedure + "; IF OBJECT_ID('" + table + "') IS NOT NULL DROP "
                "TABLE " + table;
            SQLExecDirect(handle, sql_text(cleanup), SQL_NTS);
            SQLFreeHandle(SQL_HANDLE_STMT, handle);
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
    std::cout << "C++ ODBC SQL Server data types integration test passed\n";
    return 0;
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
                  << "=true to run the SQL Server data types suite\n";
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
    return ojp::test::run_connections(csv_path, [&](const DatabaseConfig& config) {
        return run_connection(config, endpoint);
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
