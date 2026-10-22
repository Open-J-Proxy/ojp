# ODBC 3.x API coverage

This document compares the functions exported by `ojp_odbc` with the ODBC 3.x
(3.8) driver API and lists the work that remains. It is the backlog for ODBC
conformance work in this module.

**Confidence: medium.**

- The lists of exported and missing functions were checked against
  `src/ojp_odbc_driver.cpp` (`SQL_API` definitions) and the generated
  `exports.def`.
- The partial behaviors were read in the source code.
- The conformance level of each function and the application impact (FireDAC,
  Windows Driver Manager) come from the Microsoft ODBC API reference and known
  client behavior. They were not confirmed by tracing those clients.

Legend:

- **Full**: exported and implemented.
- **Partial**: exported, but only a subset of the specification is implemented.
- **Missing**: not exported.

## Core

| Function | ANSI | W | Status | Notes |
|---|---|---|---|---|
| SQLAllocHandle / SQLFreeHandle | yes | n/a | Full | |
| SQLBindCol | yes | n/a | Full | |
| SQLBindParameter | yes | n/a | Partial | See below. |
| SQLCancel | yes | n/a | Full | |
| SQLCloseCursor | yes | n/a | Full | Delegates to `SQLFreeStmt(SQL_CLOSE)`. |
| SQLColAttribute | yes | yes | Partial | Unmapped fields return `HYC00`. |
| SQLColumns | yes | yes | Full | |
| SQLConnect | yes | yes | Full | |
| SQLCopyDesc | no | n/a | Missing | |
| SQLDescribeCol | yes | yes | Full | |
| SQLDisconnect | yes | n/a | Full | |
| SQLDriverConnect | yes | yes | Partial | `DriverCompletion` is ignored. The driver shows no prompt; the DSN dialog lives in the setup DLL. |
| SQLEndTran | yes | n/a | Partial | Environment handle returns `HYC00`. |
| SQLExecDirect | yes | yes | Full | |
| SQLExecute | yes | n/a | Full | |
| SQLFetch | yes | n/a | Full | |
| SQLFetchScroll | yes | n/a | Partial | Only `SQL_FETCH_NEXT`; any other orientation returns `HYC00`. |
| SQLFreeStmt | yes | n/a | Full | |
| SQLGetConnectAttr | yes | yes | Partial | Unmapped attributes return `HYC00`. |
| SQLGetCursorName | no | no | Missing | |
| SQLGetData | yes | n/a | Full | |
| SQLGetDescField | no | no | Missing | |
| SQLGetDescRec | no | no | Missing | |
| SQLGetDiagField | yes | yes | Full | |
| SQLGetDiagRec | yes | yes | Full | A non-standard `SQLGetDiagRecA` is also exported. |
| SQLGetEnvAttr | no | n/a | Missing | |
| SQLGetFunctions | yes | n/a | Full | Reports exactly the exported functions. |
| SQLGetInfo | yes | yes | Partial | Unmapped info types return `HYC00`. |
| SQLGetStmtAttr | yes | yes | Partial | Unmapped attributes return `HYC00`. |
| SQLGetTypeInfo | yes | yes | Full | |
| SQLNumParams | yes | n/a | Full | |
| SQLNumResultCols | yes | n/a | Full | |
| SQLParamData / SQLPutData | yes | n/a | Partial | Data-at-execution only for LOB parameters. |
| SQLPrepare | yes | yes | Full | |
| SQLRowCount | yes | n/a | Full | |
| SQLSetConnectAttr | yes | yes | Partial | See below. |
| SQLSetCursorName | no | no | Missing | |
| SQLSetDescField | yes | yes | Partial | Only `SQL_DESC_NAME` on the IPD; other fields return `HYC00`. |
| SQLSetDescRec | no | n/a | Missing | |
| SQLSetEnvAttr | yes | n/a | Partial | Only `SQL_ATTR_ODBC_VERSION` with `SQL_OV_ODBC3` or `SQL_OV_ODBC3_80`; anything else returns `HY024`. |
| SQLSetStmtAttr | yes | yes | Partial | Cursor attributes accept only forward-only, read-only, single-row values; other values return `HYC00`. |
| SQLSpecialColumns | no | no | Missing | |
| SQLStatistics | no | no | Missing | |
| SQLTables | yes | yes | Full | |

## Level 1

| Function | ANSI | W | Status | Notes |
|---|---|---|---|---|
| SQLBrowseConnect | no | no | Missing | |
| SQLForeignKeys | yes | yes | Full | |
| SQLMoreResults | yes | n/a | Partial | Advances results of procedure calls and multi-statement batches executed through a callable statement. Returns `SQL_NO_DATA` for other statements. |
| SQLNativeSql | no | no | Missing | |
| SQLPrimaryKeys | yes | yes | Full | |
| SQLProcedureColumns | yes | yes | Full | |
| SQLProcedures | yes | yes | Full | |
| SQLSetPos | no | n/a | Missing | |

## Level 2

| Function | ANSI | W | Status |
|---|---|---|---|
| SQLBulkOperations | no | n/a | Missing |
| SQLColumnPrivileges | no | no | Missing |
| SQLDescribeParam | no | n/a | Missing |
| SQLTablePrivileges | no | no | Missing |

## ODBC 2.x and asynchronous functions

`SQLAllocEnv`, `SQLAllocConnect`, `SQLAllocStmt`, `SQLFreeEnv`,
`SQLFreeConnect`, `SQLError`, `SQLColAttributes`, `SQLSetParam`,
`SQLExtendedFetch`, `SQLSetScrollOptions`, the `Get/Set*Option` functions,
`SQLParamOptions` and `SQLCompleteAsync` are not exported.

The Driver Manager maps the ODBC 2.x functions to their ODBC 3.x equivalents.
`SQLTransact` is exported and behaves like `SQLEndTran`.

## Partial functions in detail

### SQLBindParameter

- Only `SQL_PARAM_INPUT` is bound directly.
- Output and return-value parameters work only through procedure calls
  executed as callable statements.
- Parameter arrays (`SQL_ATTR_PARAMSET_SIZE` > 1) of DML without output
  parameters, LOB parameters, or multiple results run as one backend JDBC batch
  (`addBatch` for each row, then a single `executeBatch`). When the batch fails,
  the database reports no per-row result, so the rows get
  `SQL_PARAM_DIAG_UNAVAILABLE`. Other parameter arrays execute row by row.

### SQLSetConnectAttr

- Applied: `SQL_ATTR_AUTOCOMMIT`, `SQL_ATTR_TXN_ISOLATION`.
- Stored for `SQLGetConnectAttr` but without effect, because OJP controls
  timeouts and networking: `SQL_ATTR_LOGIN_TIMEOUT`,
  `SQL_ATTR_CONNECTION_TIMEOUT`, `SQL_ATTR_ACCESS_MODE`, `SQL_ATTR_QUIET_MODE`,
  `SQL_ATTR_PACKET_SIZE`, `SQL_ATTR_ODBC_CURSORS`.
- `SQL_ATTR_ASYNC_ENABLE` and `SQL_ATTR_METADATA_ID` accept only their default
  values (`SQL_ASYNC_ENABLE_OFF`, `SQL_FALSE`); other values return `HYC00`.
- `SQL_ATTR_CURRENT_CATALOG` returns `HYC00`. `SQLGetConnectAttr` returns the
  database taken from the JDBC URL.
- Any other attribute returns `HYC00`.

### SQLGetFunctions

The functions it reports as supported match the exported set. The partial
functions in this document are reported as supported, so clients only discover
their limits at call time.

## Remaining work

### High priority

1. **`SQLStatistics` and `SQLSpecialColumns`** (Core).
   - Used to discover indexes, unique keys and row identifiers when a client
     generates `UPDATE` and `DELETE` statements (for example, FireDAC with
     automatic update commands).
2. **`SQLDescribeParam`.**
   - Lets clients discover parameter types after `SQLPrepare`.
   - Without it, applications must declare parameter types explicitly.
3. **`SQLGetDescField`, `SQLGetDescRec`, and full `SQLSetDescField`.**
   - Needed by clients that read or set column and parameter metadata through
     descriptors instead of the statement API.
4. **`SQLGetEnvAttr`, and `SQL_ATTR_OUTPUT_NTS` in `SQLSetEnvAttr`.**
   - Required by the Core level.
   - Accept `SQL_ATTR_OUTPUT_NTS = SQL_TRUE` instead of returning `HY024`.

### Medium priority

5. **`SQLNativeSql`.** Returns the SQL after escape-sequence translation.
6. **`SQLGetCursorName` and `SQLSetCursorName`.** Core level.
7. **`SQLCopyDesc` and `SQLSetDescRec`.** Complete the descriptor API (Core
   level).
8. **`SQL_ATTR_CURRENT_CATALOG` in `SQLSetConnectAttr`.** Lets clients switch
   the current database.
9. **`SQLTablePrivileges` and `SQLColumnPrivileges`.** Level 2 catalog
   functions.
10. **Option values substituted with `01S02`.** Where the specification
    allows it, return `SQL_SUCCESS_WITH_INFO` with `01S02` (option value
    changed) for cursor attributes instead of `HYC00`.

### Low priority

11. **Scrollable cursors.** Support other `SQLFetchScroll` orientations,
    `SQLSetPos` and `SQLBulkOperations`.
    - The OJP cursors are forward-only, so this needs server-side support or
      client-side caching.
12. **`SQLBrowseConnect`.**
13. **Explicit export list.**
    - The driver exports symbols automatically (`WINDOWS_EXPORT_ALL_SYMBOLS`),
      which also exports gRPC and protobuf symbols.
    - Replace it with a `.def` file that exports only the ODBC API, and drop
      `SQLGetDiagRecA`.
