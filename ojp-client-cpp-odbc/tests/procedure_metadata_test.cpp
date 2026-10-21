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
    // FireDAC queries SQL_DESC_ROWVER for every column and logs an error when it fails.
    SQLLEN row_version = -1;
    success(SQLColAttribute(statement, 1, SQL_DESC_ROWVER, nullptr, 0, nullptr, &row_version),
            "SQLColAttribute SQL_DESC_ROWVER");
    require(row_version == SQL_FALSE, "SQL_DESC_ROWVER must be SQL_FALSE");
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

void check_output_parameters(SQLHSTMT statement) {
    SQLINTEGER return_value = 0;
    SQLINTEGER a = 5;
    SQLINTEGER r = 7;
    SQLLEN return_indicator = 0;
    SQLLEN r_indicator = 0;
    success(SQLBindParameter(statement, 1, SQL_PARAM_OUTPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                             &return_value, 0, &return_indicator), "SQLBindParameter return value");
    success(SQLBindParameter(statement, 2, SQL_PARAM_INPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0, &a, 0,
                             nullptr), "SQLBindParameter input");
    success(SQLBindParameter(statement, 3, SQL_PARAM_INPUT_OUTPUT, SQL_C_SLONG, SQL_INTEGER, 0, 0,
                             &r, 0, &r_indicator), "SQLBindParameter input/output");
    SQLHDESC ipd = SQL_NULL_HDESC;
    success(SQLGetStmtAttr(statement, SQL_ATTR_IMP_PARAM_DESC, &ipd, 0, nullptr),
            "SQLGetStmtAttr(SQL_ATTR_IMP_PARAM_DESC)");
    success(SQLSetDescField(ipd, 1, SQL_DESC_NAME, text("@RETURN_VALUE"), SQL_NTS),
            "SQLSetDescField(SQL_DESC_NAME) on the IPD");
    SQLHDESC apd = SQL_NULL_HDESC;
    success(SQLGetStmtAttr(statement, SQL_ATTR_APP_PARAM_DESC, &apd, 0, nullptr),
            "SQLGetStmtAttr(SQL_ATTR_APP_PARAM_DESC)");
    require(SQLSetDescField(apd, 1, SQL_DESC_NAME, text("@a"), SQL_NTS) == SQL_ERROR,
            "SQL_DESC_NAME must only be accepted on the IPD");
    SQLCHAR state[6] = {};
    SQLINTEGER native = 0;
    SQLSMALLINT length = 0;
    require(SQLGetDiagRec(SQL_HANDLE_DESC, apd, 1, state, &native, nullptr, 0, &length) !=
                SQL_ERROR && std::string(reinterpret_cast<char*>(state)) == "HYC00",
            "An unsupported descriptor field must report HYC00");
    success(SQLExecDirect(statement, text("{? = CALL dbo.add_values(?, ?)}"), SQL_NTS),
            "SQLExecDirect with output parameters");
    require(return_value == 42 && return_indicator == sizeof(SQLINTEGER),
            "The return value must be written to the bound buffer");
    require(r == 12 && r_indicator == sizeof(SQLINTEGER),
            "The INPUT_OUTPUT parameter must be written back");
    SQLSMALLINT columns = -1;
    success(SQLNumResultCols(statement, &columns), "SQLNumResultCols");
    require(columns == 0, "A call without a result set must not expose columns");

    r = 7;
    success(SQLExecDirect(statement, text("{? = CALL dbo.add_values_rows(?, ?)}"), SQL_NTS),
            "SQLExecDirect with output parameters and a result set");
    require(r == 12, "The INPUT_OUTPUT parameter must be written back after a result set");
    success(SQLFetch(statement), "SQLFetch callable result set");
    require(string_data(statement, 1) == "12", "Unexpected callable result-set value");
    require(SQLFetch(statement) == SQL_NO_DATA, "The callable result set must have one row");
    SQLFreeStmt(statement, SQL_CLOSE);

    success(SQLSetStmtAttr(statement, SQL_ATTR_PARAMSET_SIZE, reinterpret_cast<SQLPOINTER>(2), 0),
            "SQL_ATTR_PARAMSET_SIZE");
    require(SQLExecDirect(statement, text("{? = CALL dbo.add_values(?, ?)}"), SQL_NTS) == SQL_ERROR,
            "Output parameters must be rejected with parameter arrays");
    success(SQLSetStmtAttr(statement, SQL_ATTR_PARAMSET_SIZE, reinterpret_cast<SQLPOINTER>(1), 0),
            "SQL_ATTR_PARAMSET_SIZE");
    SQLFreeStmt(statement, SQL_RESET_PARAMS);
}

// The fake server answers a batch with: update count 3, rows {1}, update count 5, rows {2}.
void check_more_results(SQLHSTMT statement) {
    SQLCHAR* batch = text("UPDATE items SET name = 'x'; SELECT 1 AS value; UPDATE items SET "
                          "name = 'y'; SELECT 2 AS value");
    success(SQLExecDirect(statement, batch, SQL_NTS), "SQLExecDirect batch");
    SQLLEN rows = 0;
    success(SQLRowCount(statement, &rows), "SQLRowCount");
    require(rows == 1, "The row count must describe the first result set");
    success(SQLFetch(statement), "SQLFetch first result set");
    require(string_data(statement, 1) == "1", "Unexpected first result set");
    require(SQLFetch(statement) == SQL_NO_DATA, "The first result set must have one row");
    success(SQLMoreResults(statement), "SQLMoreResults second result set");
    SQLSMALLINT columns = 0;
    success(SQLNumResultCols(statement, &columns), "SQLNumResultCols");
    require(columns == 1 && column_name(statement, 1) == "value", "Unexpected second result set");
    success(SQLFetch(statement), "SQLFetch second result set");
    require(string_data(statement, 1) == "2", "Unexpected second result set value");
    require(SQLMoreResults(statement) == SQL_NO_DATA, "SQLMoreResults must end after two results");
    require(SQLMoreResults(statement) == SQL_NO_DATA, "SQLMoreResults must stay at SQL_NO_DATA");

    // Closing the cursor, re-executing and freeing the handle close the open statement.
    success(SQLExecDirect(statement, batch, SQL_NTS), "SQLExecDirect batch again");
    success(SQLCloseCursor(statement), "SQLCloseCursor");
    require(SQLMoreResults(statement) == SQL_NO_DATA, "A closed cursor has no more results");
    success(SQLExecDirect(statement, batch, SQL_NTS), "SQLExecDirect batch");
    success(SQLExecDirect(statement, batch, SQL_NTS), "SQLExecDirect batch over an open one");
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
    // FireDAC discards catalog rows whose TABLE_CAT/PROCEDURE_CAT differ from SQL_DATABASE_NAME.
    SQLCHAR database_name[64] = {};
    SQLSMALLINT name_length = 0;
    success(SQLGetInfo(connection, SQL_DATABASE_NAME, database_name, sizeof(database_name),
                       &name_length), "SQLGetInfo SQL_DATABASE_NAME");
    require(std::string(reinterpret_cast<const char*>(database_name)) == "AdventureWorks",
            "SQL_DATABASE_NAME must return the catalog of the JDBC URL");
    SQLCHAR current_catalog[64] = {};
    SQLINTEGER catalog_length = 0;
    success(SQLGetConnectAttr(connection, SQL_ATTR_CURRENT_CATALOG, current_catalog,
                              sizeof(current_catalog), &catalog_length),
            "SQLGetConnectAttr SQL_ATTR_CURRENT_CATALOG");
    require(std::string(reinterpret_cast<const char*>(current_catalog)) == "AdventureWorks",
            "SQL_ATTR_CURRENT_CATALOG must agree with SQL_DATABASE_NAME");
    success(SQLAllocHandle(SQL_HANDLE_STMT, connection, &statement), "Allocate statement");
    check_procedures(statement);
    check_procedure_columns(statement);
    check_output_parameters(statement);
    check_more_results(statement);
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
        run(server.connection_string(
            "{jdbc:sqlserver://fake:1433;databaseName=AdventureWorks;encrypt=false}"));
        const auto calls = server.finish();
        // An empty catalog is sent as NULL (the current catalog).
        require(contains_line(calls, "metadata Procedures <null>|dbo|add_values"),
                "SQLProcedures must call DatabaseMetaData.getProcedures");
        require(contains_line(calls, "metadata ProcedureColumns <null>|dbo|add_values|<null>"),
                "SQLProcedureColumns must call DatabaseMetaData.getProcedureColumns");
        for (const char* expected : {"prepareCall {? = CALL dbo.add_values(?, ?)}",
                                     "callable Object 2 5", "callable Object 3 7",
                                     "callable OutParameter 1 4", "callable OutParameter 3 4",
                                     "callable ", "callable UpdateCount", "callable Object 1",
                                     "callable Object 3", "callable ResultSet",
                                     "closeResultSet rs-callable", "closeCallable"}) {
            require(contains_line(calls, expected),
                    std::string("Missing callable statement call: ") + expected);
        }
        // Two procedure calls close their statement at once; the four batches are closed by
        // SQLMoreResults, SQLCloseCursor, the next execution and SQLFreeHandle.
        require(std::count(calls.begin(), calls.end(), "closeCallable") == 6,
                "Every callable statement must be closed exactly once");
        for (const char* expected : {"callable MoreResults", "closeResultSet rs-batch-1",
                                     "closeResultSet rs-batch-2"}) {
            require(contains_line(calls, expected),
                    std::string("Missing multi-result call: ") + expected);
        }
        std::cout << "Procedure metadata tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
