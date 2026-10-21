#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif
#include <sql.h>
#include <sqlext.h>

#include "fake_server_process.h"

#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr SQLULEN kRows = 5;

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result, const std::string& operation) {
    require(SQL_SUCCEEDED(result), operation + " failed");
}

long count_lines(const std::vector<std::string>& lines, const std::string& expected) {
    return static_cast<long>(std::count(lines.begin(), lines.end(), expected));
}

std::string first_diagnostic(SQLHSTMT statement, std::string* state) {
    SQLCHAR sql_state[6] = {};
    SQLCHAR message[512] = {};
    success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, sql_state, nullptr,
                          message, sizeof(message), nullptr), "SQLGetDiagRec");
    *state = reinterpret_cast<char*>(sql_state);
    return reinterpret_cast<char*>(message);
}

class Session {
public:
    explicit Session(const std::string& connection_string) {
        success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment_), "Allocate environment");
        success(SQLSetEnvAttr(environment_, SQL_ATTR_ODBC_VERSION,
                              reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0), "SQL_ATTR_ODBC_VERSION");
        success(SQLAllocHandle(SQL_HANDLE_DBC, environment_, &connection_), "Allocate connection");
        std::string text = connection_string;
        success(SQLDriverConnect(connection_, nullptr, reinterpret_cast<SQLCHAR*>(text.data()),
                                 SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
                "SQLDriverConnect");
        success(SQLAllocHandle(SQL_HANDLE_STMT, connection_, &statement), "Allocate statement");
    }

    ~Session() {
        SQLFreeHandle(SQL_HANDLE_STMT, statement);
        SQLDisconnect(connection_);
        SQLFreeHandle(SQL_HANDLE_DBC, connection_);
        SQLFreeHandle(SQL_HANDLE_ENV, environment_);
    }

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    SQLHSTMT statement = SQL_NULL_HSTMT;

private:
    SQLHENV environment_ = SQL_NULL_HENV;
    SQLHDBC connection_ = SQL_NULL_HDBC;
};

// Executes a two-marker INSERT over a column-wise array of kRows rows; ids[row] == 99 makes the
// fake server fail that row.
struct ArrayExecution {
    SQLRETURN result = SQL_ERROR;
    SQLUSMALLINT status[kRows] = {};
    SQLULEN processed = 0;
    SQLLEN row_count = -1;
};

ArrayExecution execute_array(SQLHSTMT statement, const std::string& sql,
                             const std::vector<SQLINTEGER>& ids) {
    std::vector<SQLINTEGER> ids_buffer = ids;
    std::vector<SQLINTEGER> values(kRows, 7);
    ArrayExecution execution;
    std::fill(std::begin(execution.status), std::end(execution.status),
              static_cast<SQLUSMALLINT>(0xFFFF));
    success(SQLSetStmtAttr(statement, SQL_ATTR_PARAMSET_SIZE,
                           reinterpret_cast<SQLPOINTER>(kRows), 0), "SQL_ATTR_PARAMSET_SIZE");
    success(SQLSetStmtAttr(statement, SQL_ATTR_PARAM_STATUS_PTR, execution.status, 0),
            "SQL_ATTR_PARAM_STATUS_PTR");
    success(SQLSetStmtAttr(statement, SQL_ATTR_PARAMS_PROCESSED_PTR, &execution.processed, 0),
            "SQL_ATTR_PARAMS_PROCESSED_PTR");
    success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_LONG, SQL_INTEGER, 0, 0,
                             ids_buffer.data(), 0, nullptr), "Bind parameter 1");
    success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_LONG, SQL_INTEGER, 0, 0,
                             values.data(), 0, nullptr), "Bind parameter 2");
    execution.result = SQLExecDirect(statement,
        reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())), SQL_NTS);
    SQLRowCount(statement, &execution.row_count);
    return execution;
}

void require_status(const ArrayExecution& execution, const std::vector<SQLUSMALLINT>& expected,
                    const std::string& scenario) {
    for (SQLULEN row = 0; row < kRows; ++row) {
        require(execution.status[row] == expected[row], scenario + ": unexpected status of row " +
                    std::to_string(row + 1) + " (" + std::to_string(execution.status[row]) + ")");
    }
}

void test_single_request(SQLHSTMT statement) {
    const auto execution = execute_array(statement, "INSERT INTO ok VALUES (?, ?)", {1, 2, 3, 4, 5});
    require(execution.result == SQL_SUCCESS, "All rows must succeed");
    require(execution.processed == kRows, "All rows must be processed");
    require(execution.row_count == 5, "Row count must add the update counts");
    require_status(execution, std::vector<SQLUSMALLINT>(kRows, SQL_PARAM_SUCCESS), "single request");
}

void test_failure_stops_batch(SQLHSTMT statement) {
    const auto execution = execute_array(statement, "INSERT INTO partial VALUES (?, ?)",
                                         {1, 2, 99, 4, 5});
    require(execution.result == SQL_SUCCESS_WITH_INFO,
            "A failed row with a status array must return SQL_SUCCESS_WITH_INFO");
    require(execution.processed == 3, "Rows after the failure were not executed");
    require(execution.row_count == 2, "Only the executed rows must be counted");
    require_status(execution, {SQL_PARAM_SUCCESS, SQL_PARAM_SUCCESS, SQL_PARAM_ERROR,
                               SQL_PARAM_UNUSED, SQL_PARAM_UNUSED}, "partial counts");
    std::string state;
    const std::string message = first_diagnostic(statement, &state);
    require(state == "23000", "The SQLSTATE of the server must be kept");
    require(message.find("[parameter row 3]") != std::string::npos,
            "The diagnostic must identify the failed row: " + message);
}

void test_failure_continues_batch(SQLHSTMT statement) {
    const auto execution = execute_array(statement, "INSERT INTO every VALUES (?, ?)",
                                         {1, 2, 99, 4, 5});
    require(execution.result == SQL_SUCCESS_WITH_INFO,
            "A failed row with a status array must return SQL_SUCCESS_WITH_INFO");
    require(execution.processed == kRows, "All rows must be processed");
    require(execution.row_count == 4, "The successful rows must be counted");
    require_status(execution, {SQL_PARAM_SUCCESS, SQL_PARAM_SUCCESS, SQL_PARAM_ERROR,
                               SQL_PARAM_SUCCESS, SQL_PARAM_SUCCESS}, "every count");
}

void test_legacy_server(const std::string& fake_server) {
    FakeServerProcess server(fake_server);
    {
        Session session(server.connection_string("jdbc:h2:mem:legacy"));
        const auto execution = execute_array(session.statement, "INSERT INTO ok VALUES (?, ?)",
                                             {1, 2, 3, 4, 5});
        require(execution.result == SQL_SUCCESS, "Row by row execution must succeed");
        require(execution.processed == kRows, "Row by row execution must process all rows");
    }
    const auto calls = server.finish();
    require(count_lines(calls, "executeUpdate INSERT INTO ok VALUES (?, ?)") == 5,
            "Without the capability each row must be one request");
    require(count_lines(calls, "executeParameterSets INSERT INTO ok VALUES (?, ?) 2 5") == 0,
            "Parameter sets must not be sent to a server without the capability");
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_parameter_sets_test <fake server executable>");
        {
            FakeServerProcess server(argv[1]);
            {
                Session session(server.connection_string("jdbc:h2:mem:parameter_sets"));
                test_single_request(session.statement);
                test_failure_stops_batch(session.statement);
                test_failure_continues_batch(session.statement);
            }
            const auto calls = server.finish();
            require(count_lines(calls, "executeParameterSets INSERT INTO ok VALUES (?, ?) 2 5") == 1,
                    "The array must be sent in one request");
            require(count_lines(calls, "executeUpdate INSERT INTO ok VALUES (?, ?)") == 0,
                    "No row may be sent on its own");
        }
        test_legacy_server(argv[1]);
        std::cout << "Parameter set tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
