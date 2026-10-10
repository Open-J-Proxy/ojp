// PostgreSQL uses BYTEA/TEXT rather than JDBC BLOB/CLOB objects.
#include <sql.h>
#include <sqlext.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
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

SQLCHAR* sql_text(const std::string& sql) {
    return reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str()));
}

void expect(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
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

void require_state(SQLHSTMT statement, SQLRETURN result, SQLRETURN expected_result,
                   const std::string& expected_state) {
    expect(result == expected_result, "incorrect return code for SQLSTATE " + expected_state);
    SQLCHAR state[6] = {};
    SQLCHAR detail[1024] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT length = 0;
    require_success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, &native_error, detail,
                                  sizeof(detail), &length),
                    "SQLGetDiagRec", SQL_HANDLE_STMT, statement);
    expect(expected_state == reinterpret_cast<const char*>(state),
           "expected SQLSTATE " + expected_state + ", got " +
               reinterpret_cast<const char*>(state));
}

void execute_direct(SQLHSTMT statement, const std::string& sql) {
    require_success(SQLExecDirect(statement, sql_text(sql), SQL_NTS),
                    "SQLExecDirect", SQL_HANDLE_STMT, statement);
}

void reset_statement(SQLHSTMT statement) {
    require_success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt(close)",
                    SQL_HANDLE_STMT, statement);
    require_success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(reset parameters)",
                    SQL_HANDLE_STMT, statement);
}

void expect_one_row(SQLHSTMT statement) {
    SQLLEN count = 0;
    require_success(SQLRowCount(statement, &count), "SQLRowCount", SQL_HANDLE_STMT, statement);
    expect(count == 1, "LOB write must affect exactly one row");
}

void bind_lob(SQLHSTMT statement, SQLUSMALLINT index, SQLSMALLINT c_type,
              SQLSMALLINT sql_type, SQLPOINTER buffer, SQLLEN capacity, SQLLEN* indicator) {
    require_success(SQLBindParameter(statement, index, SQL_PARAM_INPUT, c_type, sql_type,
                                     0, 0, buffer, capacity, indicator),
                    "SQLBindParameter(LOB)", SQL_HANDLE_STMT, statement);
}

void send_parameter(SQLHSTMT statement, SQLPOINTER expected_token,
                    const std::string& data, std::size_t chunk_size) {
    SQLPOINTER token = nullptr;
    expect(SQLParamData(statement, &token) == SQL_NEED_DATA && token == expected_token,
           "SQLParamData must request the correct LOB token");
    if (data.empty()) {
        require_success(SQLPutData(statement, nullptr, 0), "SQLPutData(empty)",
                        SQL_HANDLE_STMT, statement);
    }
    for (std::size_t offset = 0; offset < data.size(); offset += chunk_size) {
        require_success(SQLPutData(statement, const_cast<char*>(data.data() + offset),
                                   static_cast<SQLLEN>(std::min(chunk_size, data.size() - offset))),
                        "SQLPutData", SQL_HANDLE_STMT, statement);
    }
}

void write_lobs(SQLHSTMT statement, const std::string& sql, SQLINTEGER id,
                const std::string& binary, const std::string& text,
                bool streamed, bool nulls = false) {
    reset_statement(statement);
    require_success(SQLPrepare(statement, sql_text(sql), SQL_NTS), "SQLPrepare(write)",
                    SQL_HANDLE_STMT, statement);
    SQLLEN id_length = sizeof(id);
    SQLLEN binary_length = nulls ? SQL_NULL_DATA : static_cast<SQLLEN>(binary.size());
    SQLLEN text_length = nulls ? SQL_NULL_DATA : static_cast<SQLLEN>(text.size());
    int binary_token = 2;
    int text_token = 3;
    if (streamed && !nulls) {
        binary_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(binary.size()));
        text_length = SQL_DATA_AT_EXEC;
    }
    require_success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER,
                                     0, 0, &id, sizeof(id), &id_length),
                    "SQLBindParameter(id)", SQL_HANDLE_STMT, statement);
    bind_lob(statement, 2, SQL_C_BINARY, SQL_LONGVARBINARY,
             streamed ? static_cast<SQLPOINTER>(&binary_token) : sql_text(binary),
             static_cast<SQLLEN>(binary.size()), &binary_length);
    bind_lob(statement, 3, SQL_C_CHAR, SQL_LONGVARCHAR,
             streamed ? static_cast<SQLPOINTER>(&text_token) : sql_text(text),
             static_cast<SQLLEN>(text.size()), &text_length);
    if (streamed && !nulls) {
        expect(SQLExecute(statement) == SQL_NEED_DATA, "LOB write must request streamed data");
        send_parameter(statement, &binary_token, binary, 48 * 1024);
        // Deliberately split UTF-8 characters across SQLPutData calls.
        send_parameter(statement, &text_token, text, 17003);
        SQLPOINTER token = nullptr;
        require_success(SQLParamData(statement, &token), "SQLParamData(complete)",
                        SQL_HANDLE_STMT, statement);
    } else {
        require_success(SQLExecute(statement), "SQLExecute(write)", SQL_HANDLE_STMT, statement);
    }
    expect_one_row(statement);
    reset_statement(statement);
}

void verify_column(SQLHSTMT statement, SQLUSMALLINT column, SQLSMALLINT c_type,
                   const std::string& expected, bool null_value) {
    constexpr std::size_t chunk_size = 17003;
    std::vector<char> buffer(chunk_size + (c_type == SQL_C_CHAR ? 1 : 0), '\x7f');
    SQLLEN indicator = -999;
    std::string actual;
    SQLRETURN result;
    if (!null_value && !expected.empty()) {
        require_state(statement, SQLGetData(statement, column, c_type, buffer.data(), 0, &indicator),
                      SQL_SUCCESS_WITH_INFO, "01004");
        expect(indicator == static_cast<SQLLEN>(expected.size()),
               "zero-length buffer must report the complete LOB byte length");
    }
    do {
        result = SQLGetData(statement, column, c_type, buffer.data(),
                            static_cast<SQLLEN>(buffer.size()), &indicator);
        require_success(result, "SQLGetData(LOB)", SQL_HANDLE_STMT, statement);
        if (null_value) {
            expect(result == SQL_SUCCESS && indicator == SQL_NULL_DATA,
                   "NULL must remain distinct from an empty LOB");
            return;
        }
        expect(indicator == static_cast<SQLLEN>(expected.size() - actual.size()),
               "incorrect remaining LOB byte length");
        const auto count = std::min(chunk_size, expected.size() - actual.size());
        actual.append(buffer.data(), count);
        if (c_type == SQL_C_CHAR) {
            expect(buffer[count] == '\0', "SQLGetData character chunks must be terminated");
        }
        const bool more = actual.size() < expected.size();
        expect(result == (more ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS),
               "incorrect LOB continuation status");
        if (more) {
            require_state(statement, result, SQL_SUCCESS_WITH_INFO, "01004");
        }
    } while (result == SQL_SUCCESS_WITH_INFO);
    expect(actual == expected, "LOB round trip changed payload bytes");
    expect(SQLGetData(statement, column, c_type, buffer.data(),
                      static_cast<SQLLEN>(buffer.size()), &indicator) == SQL_NO_DATA,
           "SQLGetData must stop after the final chunk");
}

void verify_row(SQLHSTMT statement, const std::string& table, SQLINTEGER id,
                const std::string& binary, const std::string& text, bool nulls = false) {
    reset_statement(statement);
    execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table +
                              " WHERE id = " + std::to_string(id));
    require_success(SQLFetch(statement), "SQLFetch(LOB)", SQL_HANDLE_STMT, statement);
    verify_column(statement, 1, SQL_C_BINARY, binary, nulls);
    verify_column(statement, 2, SQL_C_CHAR, text, nulls);
    expect(SQLFetch(statement) == SQL_NO_DATA, "unexpected extra LOB row");
    reset_statement(statement);
}

void verify_errors(SQLHSTMT statement, const std::string& table) {
    SQLPOINTER token = nullptr;
    require_state(statement, SQLCancel(statement), SQL_ERROR, "HYC00");
    require_state(statement, SQLParamData(statement, &token), SQL_ERROR, "HY010");
    require_state(statement, SQLPutData(statement, nullptr, 0), SQL_ERROR, "HY010");
    const std::string insert = "INSERT INTO " + table + " (id, binary_payload) VALUES (99, ?)";
    int token_value = 1;
    SQLLEN length = SQL_LEN_DATA_AT_EXEC(4);
    const auto start_stream = [&]() {
        reset_statement(statement);
        require_success(SQLPrepare(statement, sql_text(insert), SQL_NTS), "SQLPrepare(errors)",
                        SQL_HANDLE_STMT, statement);
        bind_lob(statement, 1, SQL_C_BINARY, SQL_LONGVARBINARY, &token_value, 0, &length);
        expect(SQLExecute(statement) == SQL_NEED_DATA, "error test must request data");
        expect(SQLParamData(statement, &token) == SQL_NEED_DATA && token == &token_value,
               "error test returned the wrong token");
    };
    // Driver managers end the data-at-execution sequence on SQLPutData errors.
    start_stream();
    require_state(statement, SQLPutData(statement, nullptr, 1), SQL_ERROR, "HY009");
    require_success(SQLCancel(statement), "SQLCancel(invalid buffer)", SQL_HANDLE_STMT, statement);
    char bytes[] = {'a', '\0', 'b', 'c'};
    start_stream();
    require_state(statement, SQLPutData(statement, bytes, -2), SQL_ERROR, "HY090");
    require_success(SQLCancel(statement), "SQLCancel(invalid length)", SQL_HANDLE_STMT, statement);
    start_stream();
    require_state(statement, SQLPutData(statement, bytes, SQL_NTS), SQL_ERROR, "HY090");
    require_success(SQLCancel(statement), "SQLCancel(binary SQL_NTS)", SQL_HANDLE_STMT, statement);
    start_stream();
    require_success(SQLPutData(statement, bytes, 3), "SQLPutData(short stream)",
                    SQL_HANDLE_STMT, statement);
    require_state(statement, SQLParamData(statement, &token), SQL_ERROR, "22001");
    // A failed stream must not leave partial data in PostgreSQL.
    reset_statement(statement);

    start_stream();
    require_success(SQLPutData(statement, bytes, sizeof(bytes)), "SQLPutData(cancelled stream)",
                    SQL_HANDLE_STMT, statement);
    require_success(SQLCancel(statement), "SQLCancel(LOB)", SQL_HANDLE_STMT, statement);
    require_state(statement, SQLParamData(statement, &token), SQL_ERROR, "HY010");
    reset_statement(statement);
    execute_direct(statement, "SELECT binary_payload FROM " + table + " WHERE id = 99");
    expect(SQLFetch(statement) == SQL_NO_DATA, "failed LOB stream inserted a partial row");
    reset_statement(statement);

    require_success(SQLPrepare(statement, sql_text(insert), SQL_NTS), "SQLPrepare(recovery)",
                    SQL_HANDLE_STMT, statement);
    bind_lob(statement, 1, SQL_C_BINARY, SQL_LONGVARBINARY, &token_value, 0, &length);
    expect(SQLExecute(statement) == SQL_NEED_DATA, "recovery test must request data");
    send_parameter(statement, &token_value, std::string(bytes, sizeof(bytes)), 2);
    require_success(SQLParamData(statement, &token), "SQLParamData(recovery)",
                    SQL_HANDLE_STMT, statement);
    expect_one_row(statement);
    expect(SQLExecute(statement) == SQL_NEED_DATA, "repeated execution must request fresh data");
    send_parameter(statement, &token_value, std::string(bytes, sizeof(bytes)), 2);
    require_state(statement, SQLParamData(statement, &token), SQL_ERROR, "23505");
    reset_statement(statement);
    execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table + " WHERE id = 99");
    require_success(SQLFetch(statement), "SQLFetch(recovered LOB)", SQL_HANDLE_STMT, statement);
    verify_column(statement, 1, SQL_C_BINARY, std::string(bytes, sizeof(bytes)), false);
    verify_column(statement, 2, SQL_C_CHAR, "", true);
    reset_statement(statement);
}

void verify_binary_pair(SQLHSTMT statement, const std::string& table,
                        const std::string& small, const std::string& large) {
    const std::string insert = "INSERT INTO " + table +
        " (id, binary_payload, binary_copy) VALUES (6, ?, ?)";
    require_success(SQLPrepare(statement, sql_text(insert), SQL_NTS), "SQLPrepare(two streams)",
                    SQL_HANDLE_STMT, statement);
    int first_token = 1;
    int second_token = 2;
    SQLLEN first_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(small.size()));
    SQLLEN second_length = SQL_LEN_DATA_AT_EXEC(static_cast<SQLLEN>(large.size()));
    bind_lob(statement, 1, SQL_C_BINARY, SQL_LONGVARBINARY, &first_token, 0, &first_length);
    bind_lob(statement, 2, SQL_C_BINARY, SQL_LONGVARBINARY, &second_token, 0, &second_length);
    expect(SQLExecute(statement) == SQL_NEED_DATA, "two-stream write must request data");
    send_parameter(statement, &first_token, small, 73);
    send_parameter(statement, &second_token, large, 48 * 1024);
    SQLPOINTER token = nullptr;
    require_success(SQLParamData(statement, &token), "SQLParamData(two streams)",
                    SQL_HANDLE_STMT, statement);
    expect_one_row(statement);
    reset_statement(statement);
    execute_direct(statement, "SELECT binary_payload, binary_copy FROM " + table + " WHERE id = 6");
    require_success(SQLFetch(statement), "SQLFetch(two streams)", SQL_HANDLE_STMT, statement);
    verify_column(statement, 1, SQL_C_BINARY, small, false);
    verify_column(statement, 2, SQL_C_BINARY, large, false);
    reset_statement(statement);
}

void verify_small_chunks(SQLHSTMT statement) {
    reset_statement(statement);
    execute_direct(statement, "SELECT CAST('abcdefghij' AS TEXT),"
                              " decode('00010203040506070809', 'hex')");
    require_state(statement, SQLCancel(statement), SQL_ERROR, "HYC00");
    require_success(SQLFetch(statement), "SQLFetch(small chunks)", SQL_HANDLE_STMT, statement);
    const std::vector<SQLLEN> expected_lengths = {10, 6, 2};
    char text_buffer[5] = {};
    unsigned char binary_buffer[4] = {};
    SQLLEN length = 0;
    std::string text;
    std::vector<unsigned char> binary;
    for (std::size_t chunk = 0; chunk < expected_lengths.size(); ++chunk) {
        const auto expected_result = chunk < 2 ? SQL_SUCCESS_WITH_INFO : SQL_SUCCESS;
        const auto count = std::min<SQLLEN>(4, expected_lengths[chunk]);
        expect(SQLGetData(statement, 1, SQL_C_CHAR, text_buffer, sizeof(text_buffer), &length) ==
                   expected_result,
               "small character chunk returned the wrong status");
        expect(length == expected_lengths[chunk] && text_buffer[count] == '\0',
               "character chunk indicator must report bytes remaining before this call");
        text.append(text_buffer, static_cast<std::size_t>(count));
        expect(SQLGetData(statement, 2, SQL_C_BINARY, binary_buffer, sizeof(binary_buffer), &length) ==
                   expected_result,
               "small binary chunk returned the wrong status");
        expect(length == expected_lengths[chunk],
               "binary chunk indicator must report bytes remaining before this call");
        binary.insert(binary.end(), binary_buffer, binary_buffer + count);
    }
    expect(text == "abcdefghij", "small character chunks changed the value");
    expect(binary == std::vector<unsigned char>({0, 1, 2, 3, 4, 5, 6, 7, 8, 9}),
           "small binary chunks changed the value");
    expect(SQLGetData(statement, 1, SQL_C_CHAR, text_buffer, sizeof(text_buffer), &length) == SQL_NO_DATA,
           "small character chunks did not reach end-of-data");
    expect(SQLGetData(statement, 2, SQL_C_BINARY, binary_buffer, sizeof(binary_buffer), &length) ==
               SQL_NO_DATA,
           "small binary chunks did not reach end-of-data");
    reset_statement(statement);
}

int run_integration_test(int argc, char** argv) {
    if (argc != 4) {
        throw std::runtime_error("expected PostgreSQL CSV path, enable variable, and endpoint variable");
    }
    const std::string enable_variable = argv[2];
    const std::string endpoint_variable = argv[3];
    const char* enabled_value = std::getenv(enable_variable.c_str());
    std::string enabled = enabled_value == nullptr ? "" : enabled_value;
    std::transform(enabled.begin(), enabled.end(), enabled.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    if (enabled.empty() || enabled == "false" || enabled == "0" || enabled == "no") {
        std::cout << "Skipped: set " << enable_variable << "=true to run the PostgreSQL L5 data-path suite\n";
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
    std::random_device random;
    const std::string table = "ojp_cpp_pg_l5_" + std::to_string(random()) +
                              std::to_string(random());
    const std::string insert = "INSERT INTO " + table +
                               " (id, binary_payload, text_payload) VALUES (?, ?, ?)";
    std::string small_binary(1000, '\0');
    std::string large_binary(1024 * 1024 + 137, '\0');
    for (std::size_t index = 0; index < large_binary.size(); ++index) {
        large_binary[index] = static_cast<char>((index * 37) % 256);
    }
    large_binary[large_binary.size() - 1] = '\0';
    large_binary[large_binary.size() - 2] = '\0';
    for (std::size_t index = 0; index < small_binary.size(); ++index) {
        small_binary[index] = static_cast<char>((index * 17) % 256);
    }
    const std::string small_text = "PostgreSQL-äöü-ñ-中文-東京-🚀-🙂";
    std::string large_text(65535, 'a');
    large_text += "🙂";
    for (int index = 0; index < 8000; ++index) {
        large_text += index % 2 == 0 ? small_text + ";" : "ASCII-é-🙂-東京;";
    }
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    bool table_created = false;
    try {
        require_success(SQLSetEnvAttr(SQL_NULL_HENV, SQL_ATTR_CONNECTION_POOLING,
                                      reinterpret_cast<SQLPOINTER>(SQL_CP_OFF), 0),
                        "SQLSetEnvAttr(pooling off)");
        require_success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment),
                        "SQLAllocHandle(environment)");
        require_success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                                      reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), SQL_IS_INTEGER),
                        "SQLSetEnvAttr", SQL_HANDLE_ENV, environment);
        require_success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection),
                        "SQLAllocHandle(connection)", SQL_HANDLE_ENV, environment);
        const std::string connection_string =
            "DRIVER={OJP};SERVER=" + brace_value(endpoint) +
            ";DATABASE=" + brace_value(config.url) + ";UID=" + brace_value(config.user) +
            ";P" "WD=" + brace_value(config.password) + ";";
        require_success(SQLDriverConnect(connection, nullptr, sql_text(connection_string),
                                          SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                        "SQLDriverConnect", SQL_HANDLE_DBC, connection);
        require_success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement),
                        "SQLAllocHandle(statement)", SQL_HANDLE_DBC, connection);
        execute_direct(statement, "CREATE TABLE " + table +
                                  " (id INT PRIMARY KEY, binary_payload BYTEA, binary_copy BYTEA,"
                                  " text_payload TEXT)");
        table_created = true;
        verify_small_chunks(statement);
        write_lobs(statement, insert, 1, small_binary, small_text, false);
        write_lobs(statement, insert, 2, large_binary, large_text, true);
        write_lobs(statement, insert, 3, "", "", false);
        write_lobs(statement, insert, 4, "", "", false, true);
        write_lobs(statement, insert, 5, "", "", true);
        for (int read = 0; read < 2; ++read) {
            verify_row(statement, table, 1, small_binary, small_text);
            verify_row(statement, table, 2, large_binary, large_text);
            verify_row(statement, table, 3, "", "");
            verify_row(statement, table, 4, "", "", true);
            verify_row(statement, table, 5, "", "");
        }
        execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table + " ORDER BY id");
        const std::vector<std::string> binary_rows = {small_binary, large_binary, "", "", ""};
        const std::vector<std::string> text_rows = {small_text, large_text, "", "", ""};
        for (std::size_t row = 0; row < binary_rows.size(); ++row) {
            require_success(SQLFetch(statement), "SQLFetch(LOB rows)", SQL_HANDLE_STMT, statement);
            verify_column(statement, 1, SQL_C_BINARY, binary_rows[row], row == 3);
            verify_column(statement, 2, SQL_C_CHAR, text_rows[row], row == 3);
        }
        expect(SQLFetch(statement) == SQL_NO_DATA, "unexpected extra LOB rows");
        reset_statement(statement);

        execute_direct(statement, "SELECT binary_payload, text_payload FROM " + table + " WHERE id = 1");
        std::vector<char> bound_binary(small_binary.size());
        std::vector<char> bound_text(small_text.size() + 1);
        SQLLEN binary_length = 0;
        SQLLEN text_length = 0;
        require_success(SQLBindCol(statement, 1, SQL_C_BINARY, bound_binary.data(),
                                   static_cast<SQLLEN>(bound_binary.size()), &binary_length),
                        "SQLBindCol(BYTEA)", SQL_HANDLE_STMT, statement);
        require_success(SQLBindCol(statement, 2, SQL_C_CHAR, bound_text.data(),
                                   static_cast<SQLLEN>(bound_text.size()), &text_length),
                        "SQLBindCol(TEXT)", SQL_HANDLE_STMT, statement);
        require_success(SQLFetch(statement), "SQLFetch(bound LOBs)", SQL_HANDLE_STMT, statement);
        expect(binary_length == static_cast<SQLLEN>(small_binary.size()) &&
                   std::string(bound_binary.begin(), bound_binary.end()) == small_binary,
               "bound BYTEA retrieval changed payload");
        expect(text_length == static_cast<SQLLEN>(small_text.size()) &&
                   std::string(bound_text.data()) == small_text,
               "bound TEXT retrieval changed payload");
        require_success(SQLFreeStmt(statement, SQL_UNBIND), "SQLFreeStmt(unbind)",
                        SQL_HANDLE_STMT, statement);
        reset_statement(statement);
        const std::string update = "UPDATE " + table +
            " SET id = ?, binary_payload = ?, text_payload = ? WHERE id = 1";
        write_lobs(statement, update, 1, large_binary, large_text, true);
        verify_row(statement, table, 1, large_binary, large_text);
        write_lobs(statement, update, 1, small_binary, small_text, false);
        verify_row(statement, table, 1, small_binary, small_text);
        write_lobs(statement, update, 1, "", "", false, true);
        verify_row(statement, table, 1, "", "", true);
        write_lobs(statement, update, 1, "", "", true);
        verify_row(statement, table, 1, "", "");
        verify_binary_pair(statement, table, small_binary, large_binary);
        verify_errors(statement, table);
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
        if (statement != SQL_NULL_HSTMT) {
            SQLCancel(statement);
            SQLFreeStmt(statement, SQL_CLOSE);
            SQLFreeStmt(statement, SQL_RESET_PARAMS);
            if (table_created) {
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
    std::cout << "C++ ODBC PostgreSQL L5 integration test passed\n";
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
