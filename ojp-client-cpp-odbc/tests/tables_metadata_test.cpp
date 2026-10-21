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

// Reads one row of the five SQLTables columns, with NULL reported as "<null>".
std::vector<std::string> fetch_tables_row(SQLHSTMT statement) {
    success(SQLFetch(statement), "SQLFetch");
    std::vector<std::string> row;
    for (SQLUSMALLINT column = 1; column <= 5; ++column) {
        char value[64] = {};
        SQLLEN length = 0;
        success(SQLGetData(statement, column, SQL_C_CHAR, value, sizeof(value), &length),
                "SQLGetData");
        row.push_back(length == SQL_NULL_DATA ? "<null>" : value);
    }
    require(SQLFetch(statement) == SQL_NO_DATA, "SQLTables returned more rows than expected");
    success(SQLFreeStmt(statement, SQL_CLOSE), "SQLFreeStmt");
    return row;
}

void expect_row(const std::vector<std::string>& row, const std::vector<std::string>& expected,
                const std::string& call) {
    require(row == expected, call + " returned an unexpected SQLTables row");
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

    success(SQLTables(statement, text(SQL_ALL_CATALOGS), SQL_NTS, text(""), SQL_NTS,
                      text(""), SQL_NTS, nullptr, 0), "SQLTables(SQL_ALL_CATALOGS)");
    expect_row(fetch_tables_row(statement), {"db", "<null>", "<null>", "<null>", "<null>"},
               "SQL_ALL_CATALOGS");

    success(SQLTables(statement, text(""), SQL_NTS, text(SQL_ALL_SCHEMAS), SQL_NTS,
                      text(""), SQL_NTS, nullptr, 0), "SQLTables(SQL_ALL_SCHEMAS)");
    expect_row(fetch_tables_row(statement), {"<null>", "dbo", "<null>", "<null>", "<null>"},
               "SQL_ALL_SCHEMAS");

    success(SQLTables(statement, text(""), SQL_NTS, text(""), SQL_NTS, text(""), SQL_NTS,
                      text(SQL_ALL_TABLE_TYPES), SQL_NTS), "SQLTables(SQL_ALL_TABLE_TYPES)");
    expect_row(fetch_tables_row(statement), {"<null>", "<null>", "<null>", "TABLE", "<null>"},
               "SQL_ALL_TABLE_TYPES");

    // Quoted table types and an empty catalog, as sent by FireDAC.
    success(SQLTables(statement, text(""), SQL_NTS, text("dbo"), SQL_NTS, text("%"), SQL_NTS,
                      text("'TABLE','VIEW'"), SQL_NTS), "SQLTables(quoted types)");
    expect_row(fetch_tables_row(statement), {"db", "dbo", "items", "TABLE", "<null>"},
               "SQLTables with quoted types");

    // A "%" table type combined with other filters means every type.
    success(SQLTables(statement, nullptr, 0, nullptr, 0, text("items"), SQL_NTS,
                      text(SQL_ALL_TABLE_TYPES), SQL_NTS), "SQLTables(all types for a table)");
    expect_row(fetch_tables_row(statement), {"db", "dbo", "items", "TABLE", "<null>"},
               "SQLTables with all types for a table");

    SQLFreeHandle(SQL_HANDLE_STMT, statement);
    SQLDisconnect(connection);
    SQLFreeHandle(SQL_HANDLE_DBC, connection);
    SQLFreeHandle(SQL_HANDLE_ENV, environment);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_tables_metadata_test <fake server executable>");
        FakeServerProcess server(argv[1]);
        run(server.connection_string("jdbc:h2:mem:tables_metadata"));
        const auto calls = server.finish();
        const std::vector<std::string> expected = {
            "metadata Catalogs ",
            "metadata Schemas ",
            "metadata TableTypes ",
            "metadata Tables <null>|dbo|%|[TABLE,VIEW]",
            "metadata Tables <null>|<null>|items|<null>",
        };
        for (const auto& line : expected) {
            require(contains_line(calls, line), "Missing metadata call: " + line);
        }
        std::cout << "SQLTables metadata tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
