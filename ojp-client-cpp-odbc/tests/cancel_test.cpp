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
#include <atomic>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result, const std::string& operation) {
    require(SQL_SUCCEEDED(result), operation + " failed");
}

std::string sql_state(SQLHSTMT statement) {
    SQLCHAR state[6] = {};
    SQLCHAR message[256] = {};
    success(SQLGetDiagRec(SQL_HANDLE_STMT, statement, 1, state, nullptr,
                          message, sizeof(message), nullptr), "SQLGetDiagRec");
    return reinterpret_cast<char*>(state);
}

SQLRETURN exec_direct(SQLHSTMT statement, const char* sql) {
    return SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql)), SQL_NTS);
}

long long elapsed_ms(Clock::time_point started) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
}

long count_lines(const std::vector<std::string>& lines, const std::string& expected) {
    return static_cast<long>(std::count(lines.begin(), lines.end(), expected));
}

void test_idle_cancel(SQLHSTMT statement) {
    success(SQLCancel(statement), "SQLCancel without a running execution");
}

void test_cancel_from_other_thread(SQLHSTMT statement) {
    std::atomic<SQLRETURN> cancel_result{SQL_ERROR};
    std::thread canceller([&]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        cancel_result = SQLCancel(statement);
    });
    const auto started = Clock::now();
    const SQLRETURN result = exec_direct(statement, "UPDATE slow SET x = 1");
    const auto elapsed = elapsed_ms(started);
    canceller.join();
    require(cancel_result == SQL_SUCCESS, "SQLCancel during execution must succeed");
    require(result == SQL_ERROR, "Cancelled execution must fail");
    require(sql_state(statement) == "HY008", "Cancelled execution must report HY008");
    require(elapsed < 5000, "SQLCancel did not interrupt the execution (" +
                                std::to_string(elapsed) + " ms)");

    success(exec_direct(statement, "UPDATE fast SET x = 1"), "Execution after cancel");
}

void test_query_timeout(SQLHSTMT statement) {
    success(SQLSetStmtAttr(statement, SQL_ATTR_QUERY_TIMEOUT,
                           reinterpret_cast<SQLPOINTER>(static_cast<SQLULEN>(1)), 0),
            "SQL_ATTR_QUERY_TIMEOUT");
    const auto started = Clock::now();
    const SQLRETURN result = exec_direct(statement, "UPDATE slow SET x = 2");
    const auto elapsed = elapsed_ms(started);
    require(result == SQL_ERROR, "Timed out execution must fail");
    require(sql_state(statement) == "HYT00", "Timed out execution must report HYT00");
    require(elapsed >= 900 && elapsed < 5000, "Unexpected timeout duration (" +
                                                  std::to_string(elapsed) + " ms)");

    success(exec_direct(statement, "UPDATE fast SET x = 2"),
            "Execution after timeout (endpoint must stay healthy)");
    success(SQLSetStmtAttr(statement, SQL_ATTR_QUERY_TIMEOUT,
                           reinterpret_cast<SQLPOINTER>(static_cast<SQLULEN>(0)), 0),
            "Reset SQL_ATTR_QUERY_TIMEOUT");
}

// Multi-statement SQL runs through a server-side CallableStatement, whose execution must follow
// SQLCancel too.
void test_cancel_callable(SQLHSTMT statement) {
    std::atomic<SQLRETURN> cancel_result{SQL_ERROR};
    std::thread canceller([&]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        cancel_result = SQLCancel(statement);
    });
    const auto started = Clock::now();
    const SQLRETURN result = exec_direct(statement, "UPDATE slow SET x = 3; SELECT 1");
    const auto elapsed = elapsed_ms(started);
    canceller.join();
    require(cancel_result == SQL_SUCCESS, "SQLCancel during the callable execution must succeed");
    require(result == SQL_ERROR, "Cancelled callable execution must fail");
    require(sql_state(statement) == "HY008", "Cancelled callable execution must report HY008");
    require(elapsed < 5000, "SQLCancel did not interrupt the callable execution (" +
                                std::to_string(elapsed) + " ms)");
    success(SQLFreeStmt(statement, SQL_CLOSE), "Close cursor after cancelled callable");
    success(exec_direct(statement, "UPDATE fast SET x = 3"), "Execution after callable cancel");
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_cancel_test <fake server executable>");
        FakeServerProcess server(argv[1]);

        SQLHENV environment = SQL_NULL_HENV;
        SQLHDBC connection = SQL_NULL_HDBC;
        SQLHSTMT statement = SQL_NULL_HSTMT;
        success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment), "Allocate environment");
        success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection), "Allocate connection");
        std::string connection_string = server.connection_string("jdbc:h2:mem:cancel");
        success(SQLDriverConnect(connection, nullptr,
                                 reinterpret_cast<SQLCHAR*>(connection_string.data()), SQL_NTS,
                                 nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT), "SQLDriverConnect");
        success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");

        test_idle_cancel(statement);
        test_cancel_from_other_thread(statement);
        test_query_timeout(statement);
        test_cancel_callable(statement);

        success(SQLFreeHandle(SQL_HANDLE_STMT, statement), "Free statement");
        success(SQLDisconnect(connection), "SQLDisconnect");
        success(SQLFreeHandle(SQL_HANDLE_DBC, connection), "Free connection");
        success(SQLFreeHandle(SQL_HANDLE_ENV, environment), "Free environment");

        const auto calls = server.finish();
        // A cancelled or timed out execution must reach the server once (no retry/failover).
        require(count_lines(calls, "executeUpdate UPDATE slow SET x = 1") == 1,
                "Cancelled execution was retried");
        require(count_lines(calls, "executeUpdate UPDATE slow SET x = 2") == 1,
                "Timed out execution was retried");
        require(count_lines(calls, "cancelled UPDATE slow SET x = 1") == 1,
                "The server did not observe the SQLCancel");
        require(count_lines(calls, "cancelled UPDATE slow SET x = 2") == 1,
                "The server did not observe the query timeout");
        require(count_lines(calls, "cancelled callable execute") == 1,
                "The server did not observe the callable SQLCancel");
        require(count_lines(calls, "cancelCallable") == 1,
                "The callable statement was not cancelled through Statement.cancel()");
        require(count_lines(calls, "closeCallable") == 1,
                "The cancelled callable statement was not closed");
        std::cout << "Cancellation and query timeout tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
