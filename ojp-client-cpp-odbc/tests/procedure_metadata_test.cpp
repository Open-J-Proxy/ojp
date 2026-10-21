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

std::string column_name(SQLHSTMT statement, SQLUSMALLINT column) {
    SQLCHAR name[64] = {};
    SQLSMALLINT length = 0;
    success(SQLDescribeCol(statement, column, name, sizeof(name), &length, nullptr, nullptr,
                           nullptr, nullptr), "SQLDescribeCol");
    return reinterpret_cast<const char*>(name);
}

std::string string_data(SQLHSTMT statement, SQLUSMALLINT column) {
    SQLCHAR value[64] = {};
    SQLLEN indicator = 0;
    success(SQLGetData(statement, column, SQL_C_CHAR, value, sizeof(value), &indicator),
            "SQLGetData");
    return indicator == SQL_NULL_DATA ? "<null>" : reinterpret_cast<const char*>(value);
}

SQLSMALLINT short_data(SQLHSTMT statement, SQLUSMALLINT column) {
    SQLSMALLINT value = 0;
    success(SQLGetData(statement, column, SQL_C_SSHORT, &value, 0, nullptr), "SQLGetData");
    return value;
}

void check_procedures(SQLHSTMT statement) {
    success(SQLProcedures(statement, text(""), 0, text("dbo"), SQL_NTS, text("add_values"),
                          SQL_NTS), "SQLProcedures");
    SQLSMALLINT columns = 0;
    success(SQLNumResultCols(statement, &columns), "SQLNumResultCols");
    require(columns == 8, "SQLProcedures must return the 8 ODBC columns");
    require(column_name(statement, 4) == "NUM_INPUT_PARAMS" &&
            column_name(statement, 8) == "PROCEDURE_TYPE",
            "SQLProcedures must use the ODBC column names");
    success(SQLFetch(statement), "SQLFetch procedure");
    require(string_data(statement, 3) == "add_values;1", "Unexpected procedure name");
    require(short_data(statement, 8) == SQL_PT_FUNCTION, "Unexpected procedure type");
    require(SQLFetch(statement) == SQL_NO_DATA, "SQLProcedures must return one row");
    SQLFreeStmt(statement, SQL_CLOSE);
}

void check_procedure_columns(SQLHSTMT statement) {
    success(SQLProcedureColumns(statement, nullptr, 0, text("dbo"), SQL_NTS, text("add_values"),
                                SQL_NTS, nullptr, 0), "SQLProcedureColumns");
    SQLSMALLINT columns = 0;
    success(SQLNumResultCols(statement, &columns), "SQLNumResultCols");
    require(columns == 19, "SQLProcedureColumns must return the 19 ODBC columns");
    require(column_name(statement, 8) == "COLUMN_SIZE" &&
            column_name(statement, 10) == "DECIMAL_DIGITS" &&
            column_name(statement, 19) == "IS_NULLABLE",
            "SQLProcedureColumns must use the ODBC column names");
    const struct {
        const char* name;
        SQLSMALLINT type;
    } expected[] = {{"@RETURN_VALUE", SQL_RETURN_VALUE}, {"@a", SQL_PARAM_INPUT},
                    {"@r", SQL_PARAM_INPUT_OUTPUT}};
    for (const auto& parameter : expected) {
        success(SQLFetch(statement), "SQLFetch procedure column");
        require(string_data(statement, 4) == parameter.name, "Unexpected parameter name");
        require(short_data(statement, 5) == parameter.type, "Unexpected parameter type");
        require(short_data(statement, 6) == SQL_INTEGER, "Unexpected parameter data type");
    }
    require(SQLFetch(statement) == SQL_NO_DATA, "SQLProcedureColumns must return three rows");
    SQLFreeStmt(statement, SQL_CLOSE);
}

void run(const std::string& connection_string) {
    SQLHENV environment = SQL_NULL_HENV;
    SQLHDBC connection = SQL_NULL_HDBC;
    SQLHSTMT statement = SQL_NULL_HSTMT;
    success(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &environment), "Allocate environment");
    success(SQLSetEnvAttr(environment, SQL_ATTR_ODBC_VERSION,
                          reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0), "SQL_ATTR_ODBC_VERSION");
    success(SQLAllocHandle(SQL_HANDLE_DBC, environment, &connection), "Allocate connection");
    std::string text_value = connection_string;
    success(SQLDriverConnect(connection, nullptr, reinterpret_cast<SQLCHAR*>(text_value.data()),
                             SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT), "SQLDriverConnect");
    SQLUSMALLINT supported = SQL_FALSE;
    success(SQLGetFunctions(connection, SQL_API_SQLPROCEDURECOLUMNS, &supported), "SQLGetFunctions");
    require(supported == SQL_TRUE, "SQLProcedureColumns must be reported as supported");
    success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");
    check_procedures(statement);
    check_procedure_columns(statement);
    SQLFreeHandle(SQL_HANDLE_STMT, statement);
    SQLDisconnect(connection);
    SQLFreeHandle(SQL_HANDLE_DBC, connection);
    SQLFreeHandle(SQL_HANDLE_ENV, environment);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: ojp_odbc_procedure_metadata_test <fake server executable>");
        FakeServerProcess server(argv[1]);
        run(server.connection_string("jdbc:h2:mem:procedure_metadata"));
        const auto calls = server.finish();
        // An empty catalog is sent as NULL (the current catalog).
        require(contains_line(calls, "metadata Procedures <null>|dbo|add_values"),
                "SQLProcedures must call DatabaseMetaData.getProcedures");
        require(contains_line(calls, "metadata ProcedureColumns <null>|dbo|add_values|<null>"),
                "SQLProcedureColumns must call DatabaseMetaData.getProcedureColumns");
        std::cout << "Procedure metadata tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
