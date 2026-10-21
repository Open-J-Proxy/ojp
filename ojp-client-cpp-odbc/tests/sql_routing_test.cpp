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

// Each SQL text is executed once; the fake server reports which execute request it received.
const std::vector<std::string> kResultSetSql = {
    "IF 1 = 1 SELECT 7 AS r ELSE SELECT 8 AS r",
    "begin select 9 as r end",
    "/* batch */ WHILE 1 = 0 SELECT 1",
    "EXEC dbo.add_values 1, 2",
    "INSERT INTO items OUTPUT inserted.id VALUES (1)",
    // A trailing ';' or one inside a literal does not separate statements.
    "INSERT INTO items OUTPUT inserted.id VALUES (2); -- trailing comment",
    "INSERT INTO items OUTPUT inserted.id VALUES (';')",
};

// Batches with several statements run through a callable statement so that SQLMoreResults works.
const std::vector<std::string> kMultiResultSql = {
    "DECLARE @r INT; EXEC dbo.add_values @a = 2, @b = 3, @r = @r OUTPUT; SELECT @r AS r",
    "SET NOCOUNT ON; SELECT 42 AS r",
};

const std::vector<std::string> kUpdateSql = {
    "SET NOCOUNT ON",
    "DECLARE @x INT = 1; UPDATE items SET name = 'select' WHERE id = @x",
    "BEGIN TRANSACTION",
    "UPDATE items SET name = (SELECT 'x') WHERE id = 1",
};

void execute_all(const std::string& connection_string) {
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment), "Allocate environment");
    success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                          reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0), "SQL_ATTR_ODBC_VERSION");
    success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection), "Allocate connection");
    std::string text = connection_string;
    success(SQLDriverConnect(connection, nullptr, reinterpret_cast<SQLCHAR*>(text.data()), SQL_NTS,
                             nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT), "SQLDriverConnect");
    success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");
    for (const auto* list : {&kResultSetSql, &kMultiResultSql, &kUpdateSql}) {
        for (const auto& sql : *list) {
            success(SQLExecDirect(statement, reinterpret_cast<SQLCHAR*>(const_cast<char*>(sql.c_str())),
                                  SQL_NTS), "SQLExecDirect " + sql);
            SQLFreeStmt(statement, SQL_CLOSE);
        }
    }
    SQLFreeHandle(SQL_HANDLE_STMT, statement);
    SQLDisconnect(connection);
    SQLFreeHandle(SQL_HANDLE_DBC, connection);
    SQLFreeHandle(SQL_HANDLE_ENV, environment);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_sql_routing_test <fake server executable>");
        FakeServerProcess server(argv[1]);
        execute_all(server.connection_string("jdbc:h2:mem:sql_routing"));
        const auto calls = server.finish();
        for (const auto& sql : kResultSetSql) {
            require(contains_line(calls, "executeUpdateResultSet " + sql),
                    "SQL that may return a result set must use the update-result-set request: " + sql);
        }
        for (const auto& sql : kMultiResultSql) {
            require(contains_line(calls, "prepareCall " + sql),
                    "A multi-statement batch must run through a callable statement: " + sql);
            require(!contains_line(calls, "executeUpdateResultSet " + sql),
                    "A multi-statement batch must not use the single-result request: " + sql);
        }
        for (const auto& sql : kUpdateSql) {
            require(contains_line(calls, "executeUpdate " + sql),
                    "SQL without a result set must use the plain update request: " + sql);
        }
        std::cout << "SQL routing tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
