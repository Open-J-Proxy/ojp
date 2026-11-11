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
#include <cstdio>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr const char* kPassword = "fake-secret-41";

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void success(SQLRETURN result, const std::string& operation) {
    require(SQL_SUCCEEDED(result), operation + " failed");
}

bool contains_line(const std::vector<std::string>& lines, const std::string& expected) {
    return std::find(lines.begin(), lines.end(), expected) != lines.end();
}

SQLCHAR* text(const char* value) {
    return reinterpret_cast<SQLCHAR*>(const_cast<char*>(value));
}

std::string diagnostic_state(SQLSMALLINT handle_type, SQLHANDLE handle, std::string* message) {
    SQLCHAR state[6] = {};
    SQLCHAR detail[512] = {};
    SQLINTEGER native_error = 0;
    SQLSMALLINT length = 0;
    if (!SQL_SUCCEEDED(SQLGetDiagRec(handle_type, handle, 1, state, &native_error, detail,
                                     sizeof(detail), &length))) {
        return "";
    }
    *message = reinterpret_cast<const char*>(detail);
    return reinterpret_cast<const char*>(state);
}

void check_statement_attributes(SQLHSTMT statement) {
    success(SQLSetStmtAttr(statement, SQL_ATTR_CURSOR_TYPE,
                           reinterpret_cast<SQLPOINTER>(SQL_CURSOR_FORWARD_ONLY), 0),
            "SQLSetStmtAttr(SQL_CURSOR_FORWARD_ONLY)");
    SQLULEN cursor_type = 99;
    success(SQLGetStmtAttr(statement, SQL_ATTR_CURSOR_TYPE, &cursor_type, 0, nullptr),
            "SQLGetStmtAttr(SQL_ATTR_CURSOR_TYPE)");
    require(cursor_type == SQL_CURSOR_FORWARD_ONLY, "The cursor type was not stored");

    // An unsupported value fails with a diagnostic naming the attribute and the value.
    require(SQLSetStmtAttr(statement, SQL_ATTR_CURSOR_TYPE,
                           reinterpret_cast<SQLPOINTER>(SQL_CURSOR_KEYSET_DRIVEN), 0) == SQL_ERROR,
            "A keyset-driven cursor must be rejected");
    std::string message;
    require(diagnostic_state(SQL_HANDLE_STMT, statement, &message) == "HYC00",
            "A rejected statement attribute must report HYC00");
    require(message.find("SQL_ATTR_CURSOR_TYPE = " + std::to_string(SQL_CURSOR_KEYSET_DRIVEN)) !=
                std::string::npos,
            "The diagnostic must name the attribute and value: " + message);

    SQLHANDLE descriptors[4] = {};
    const SQLINTEGER kinds[4] = {SQL_ATTR_APP_ROW_DESC, SQL_ATTR_APP_PARAM_DESC,
                                 SQL_ATTR_IMP_ROW_DESC, SQL_ATTR_IMP_PARAM_DESC};
    for (int index = 0; index < 4; ++index) {
        success(SQLGetStmtAttrW(statement, kinds[index], &descriptors[index], 0, nullptr),
                "SQLGetStmtAttrW(descriptor)");
        require(descriptors[index] != nullptr, "The implicit descriptors must not be NULL");
    }
    require(descriptors[0] != descriptors[1] && descriptors[2] != descriptors[3],
            "Each implicit descriptor must be a distinct handle");
}

void check_connection_attributes(SQLHDBC connection) {
    success(SQLSetConnectAttrW(connection, SQL_ATTR_CONNECTION_TIMEOUT,
                               reinterpret_cast<SQLPOINTER>(static_cast<SQLULEN>(7)), 0),
            "SQLSetConnectAttrW(SQL_ATTR_CONNECTION_TIMEOUT)");
    SQLUINTEGER timeout = 0;
    success(SQLGetConnectAttrW(connection, SQL_ATTR_CONNECTION_TIMEOUT, &timeout, 0, nullptr),
            "SQLGetConnectAttrW(SQL_ATTR_CONNECTION_TIMEOUT)");
    require(timeout == 7, "SQL_ATTR_CONNECTION_TIMEOUT was not stored");
    SQLUINTEGER dead = SQL_CD_TRUE;
    success(SQLGetConnectAttr(connection, SQL_ATTR_CONNECTION_DEAD, &dead, 0, nullptr),
            "SQLGetConnectAttr(SQL_ATTR_CONNECTION_DEAD)");
    require(dead == SQL_CD_FALSE, "An open connection must not be reported as dead");
}

void check_wide_info_and_functions(SQLHDBC connection) {
    SQLWCHAR name[64] = {};
    SQLSMALLINT length = 0;
    success(SQLGetInfoW(connection, SQL_DRIVER_NAME, name, sizeof(name), &length),
            "SQLGetInfoW(SQL_DRIVER_NAME)");
    std::string driver_name;
    for (SQLWCHAR* character = name; *character != 0; ++character) {
        driver_name.push_back(static_cast<char>(*character));
    }
    require(!driver_name.empty(), "SQLGetInfoW returned an empty driver name");
    require(length == static_cast<SQLSMALLINT>(driver_name.size() * sizeof(SQLWCHAR)),
            "SQLGetInfoW must report the string length in bytes");

    for (const SQLUSMALLINT function : {SQL_API_SQLGETSTMTATTR, SQL_API_SQLCLOSECURSOR,
                                        SQL_API_SQLFETCHSCROLL, SQL_API_SQLMORERESULTS}) {
        SQLUSMALLINT supported = SQL_FALSE;
        success(SQLGetFunctions(connection, function, &supported), "SQLGetFunctions");
        require(supported == SQL_TRUE,
                "SQLGetFunctions must advertise function " + std::to_string(function));
    }
}

// Bindings survive executions until SQL_RESET_PARAMS, and only bindings with a matching
// parameter marker are sent.
void check_parameter_bindings(SQLHSTMT statement) {
    success(SQLPrepare(statement, text("UPDATE items SET name = 'x' WHERE id = ?"), SQL_NTS),
            "SQLPrepare");
    SQLINTEGER first = 5;
    SQLINTEGER extra = 6;
    SQLLEN length = 0;
    success(SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                             &first, 0, &length), "SQLBindParameter(1)");
    success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                             &extra, 0, &length), "SQLBindParameter(2)");
    success(SQLExecute(statement), "SQLExecute(first)");
    first = 8;
    success(SQLExecute(statement), "SQLExecute(second)");
    success(SQLFreeStmt(statement, SQL_RESET_PARAMS), "SQLFreeStmt(SQL_RESET_PARAMS)");
}

void check_fetch_pointers(SQLHSTMT statement) {
    SQLULEN fetched = 99;
    SQLUSMALLINT status = 99;
    success(SQLSetStmtAttr(statement, SQL_ATTR_ROWS_FETCHED_PTR, &fetched, 0),
            "SQLSetStmtAttr(SQL_ATTR_ROWS_FETCHED_PTR)");
    success(SQLSetStmtAttr(statement, SQL_ATTR_ROW_STATUS_PTR, &status, 0),
            "SQLSetStmtAttr(SQL_ATTR_ROW_STATUS_PTR)");
    success(SQLTables(statement, text(SQL_ALL_CATALOGS), SQL_NTS, text(""), SQL_NTS,
                      text(""), SQL_NTS, nullptr, 0), "SQLTables(SQL_ALL_CATALOGS)");
    success(SQLFetch(statement), "SQLFetch");
    require(fetched == 1 && status == SQL_ROW_SUCCESS,
            "SQLFetch must fill SQL_ATTR_ROWS_FETCHED_PTR and SQL_ATTR_ROW_STATUS_PTR");
    require(SQLFetch(statement) == SQL_NO_DATA, "Expected a single catalog row");
    require(fetched == 0, "SQLFetch must report zero rows at the end of the result set");
    success(SQLCloseCursor(statement), "SQLCloseCursor");
}

void run(const std::string& connection_string) {
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment), "Allocate environment");
    success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                          reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0), "SQL_ATTR_ODBC_VERSION");
    success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection), "Allocate connection");
    std::string connection_text = connection_string;
    success(SQLDriverConnect(connection, nullptr,
                             reinterpret_cast<SQLCHAR*>(connection_text.data()), SQL_NTS,
                             nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT), "SQLDriverConnect");
    success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");

    check_statement_attributes(statement);
    check_connection_attributes(connection);
    check_wide_info_and_functions(connection);
    check_parameter_bindings(statement);
    check_fetch_pointers(statement);

    SQLFreeHandle(SQL_HANDLE_STMT, statement);
    SQLDisconnect(connection);
    SQLFreeHandle(SQL_HANDLE_DBC, connection);
    SQLFreeHandle(SQL_HANDLE_ENV, environment);
}

}  // namespace

// The test runs with OJP_ODBC_LOG_FILE=<log path> and OJP_ODBC_LOG_LEVEL=debug (set by CTest).
int main(int argc, char** argv) {
    try {
        require(argc == 3, "Usage: ojp_odbc_driver_api_test <fake server executable> <log path>");
        const std::string log_path = argv[2];
        std::remove(log_path.c_str());
        FakeServerProcess server(argv[1]);
        run(server.connection_string(std::string("jdbc:h2:mem:driver_api;password=") + kPassword));
        const auto calls = server.finish();

        const std::string sql = "UPDATE items SET name = 'x' WHERE id = ?";
        require(contains_line(calls, "parameters " + sql + ": 1=5"),
                "The first execution must send only the parameter with a marker");
        require(contains_line(calls, "parameters " + sql + ": 1=8"),
                "The binding must persist and be read again on the second execution");

        std::ifstream input(log_path, std::ios::binary);
        require(static_cast<bool>(input), "OJP_ODBC_LOG_FILE was not written");
        const std::string log((std::istreambuf_iterator<char>(input)),
                              std::istreambuf_iterator<char>());
        require(log.find("SQLDriverConnect: connecting to 127.0.0.1:") != std::string::npos,
                "The log must name the API and the endpoint");
        require(log.find("HYC00") != std::string::npos,
                "The log must record the rejected statement attribute");
        require(log.find(kPassword) == std::string::npos,
                "The log must not contain the password of the JDBC URL");
        std::cout << "Driver API tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
