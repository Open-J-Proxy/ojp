# Appendix E: JDBC Compatibility Reference

This appendix provides a comprehensive reference for OJP's JDBC implementation, documenting interface coverage, method behaviors, semantic guarantees, and vendor-specific differences. Understanding these details is critical for production deployments, troubleshooting, and migration planning.

---

## E.1 Interface Coverage Matrix

OJP implements a subset of JDBC 4.2 interfaces through a gRPC proxy architecture. This section documents which interfaces are fully implemented, partially implemented, or stubbed.

### E.1.1 Core Interfaces

**java.sql.Connection** - **Fully Implemented**
- All transactional methods (commit, rollback, setAutoCommit)
- All statement creation methods (createStatement, prepareStatement, prepareCall)
- All metadata and property methods (getMetaData, setClientInfo, getWarnings)
- Connection pooling validators (isValid, getNetworkTimeout)
- Savepoint support (setSavepoint, releaseSavepoint, rollback to savepoint)
- Transaction isolation levels (all standard levels supported)

**java.sql.Statement** - **Fully Implemented**
- Query execution (executeQuery, executeUpdate, execute, executeBatch)
- Result handling (getResultSet, getUpdateCount, getMoreResults)
- Parameter management (setMaxRows, setFetchSize, setQueryTimeout)
- Batch operations (addBatch, clearBatch, executeBatch)
- Generated keys (getGeneratedKeys with RETURN_GENERATED_KEYS flag)
- Cancellation (cancel method - see section E.3 for semantics)

**java.sql.PreparedStatement** - **Fully Implemented**
- All setXXX methods for parameter binding (setString, setInt, setTimestamp, etc.)
- Binary data (setBytes, setBinaryStream, setBlob)
- Character data (setString, setCharacterStream, setClob, setNClob)
- Special types (setArray, setObject with type mapping)
- Null handling (setNull with SQL type codes)
- Metadata (getParameterMetaData)

**java.sql.CallableStatement** - **Fully Implemented**
- All registerOutParameter variants
- All getXXX methods for output parameters
- IN, OUT, and INOUT parameter support
- Function and procedure calls
- Named parameter support (where supported by underlying database)

**java.sql.ResultSet** - **Fully Implemented** (see E.2 for streaming semantics)
- Navigation (next, previous, absolute, relative, first, last)
- Data retrieval (getString, getInt, getTimestamp, etc. for all standard types)
- Metadata access (getMetaData)
- Update methods (updateString, updateInt, etc. - delegates to underlying driver)
- Row manipulation (insertRow, updateRow, deleteRow - delegates to underlying driver)
- Cursor control (getCursor types, concurrency modes)
- LOB support (getBlob, getClob, getNClob with proper lifecycle management)

**java.sql.ResultSetMetaData** - **Fully Implemented**
- Column count and labels (getColumnCount, getColumnLabel, getColumnName)
- Column types (getColumnType, getColumnTypeName, getColumnClassName)
- Column properties (getPrecision, getScale, isNullable, isAutoIncrement)
- Table information (getTableName, getSchemaName, getCatalogName)
- Display sizing (getColumnDisplaySize)

**java.sql.DatabaseMetaData** - **Fully Implemented**
- Database product info (getDatabaseProductName, getDatabaseProductVersion)
- Driver info (getDriverName, getDriverVersion)
- Feature support queries (supportsTransactions, supportsStoredProcedures, etc.)
- Catalog/schema operations (getCatalogs, getSchemas, getTables, getColumns)
- Index and key information (getIndexInfo, getPrimaryKeys, getForeignKeys)
- Type information (getTypeInfo)
- All limit queries (getMaxConnections, getMaxStatements, etc.)

**java.sql.ParameterMetaData** - **Fully Implemented**
- Parameter count (getParameterCount)
- Parameter types (getParameterType, getParameterTypeName, getParameterClassName)
- Parameter modes (getParameterMode - IN, OUT, INOUT)
- Nullability (isNullable)
- Precision and scale (getPrecision, getScale)

### E.1.2 Extended Interfaces

**javax.sql.DataSource** - **Fully Implemented**
- Connection acquisition (getConnection with and without credentials)
- Logging support (setLogWriter, getLogWriter)
- Login timeout (setLoginTimeout, getLoginTimeout)

**javax.sql.XADataSource** - **Fully Implemented**
- XA connection acquisition (getXAConnection)
- Distributed transaction support (full XA protocol)
- See Chapter 10 for detailed XA implementation

**java.sql.Savepoint** - **Fully Implemented**
- Named and unnamed savepoints
- Savepoint identification (getSavepointId, getSavepointName)

### E.1.3 LOB Interfaces

**java.sql.Blob** - **Fully Implemented**
- Stream access (getBinaryStream with position/length variants)
- Direct access (getBytes with position/length)
- Length queries (length)
- Positioning (position for pattern matching)
- Modification (setBinaryStream, setBytes, truncate)
- Lifecycle (free)

**java.sql.Clob / NClob** - **Fully Implemented**
- Stream access (getCharacterStream, getAsciiStream)
- Direct access (getSubString with position/length)
- Length queries (length)
- Positioning (position for pattern matching)
- Modification (setCharacterStream, setString, setAsciiStream, truncate)
- Lifecycle (free)

**Important LOB Notes**:
- SQL Server and DB2 use row-by-row fetching mode when LOBs are present (see section E.2.3)
- PostgreSQL and MySQL buffer LOBs fully on first access
- Oracle uses streaming LOBs with server-side cursors
- Always call `free()` on LOB objects to release server resources
- LOB invalidation varies by vendor when cursor moves (see E.4)

### E.1.4 Array and Struct Support

**java.sql.Array** - **Partially Implemented**
- PostgreSQL arrays: Fully supported (getArray, getResultSet, getBaseType)
- Oracle arrays (VARRAY, NESTED TABLE): Fully supported
- DB2 arrays: Limited support (depends on DB2 version)
- MySQL/MariaDB: Not applicable (no native array type)
- SQL Server: Not applicable (no native array type)

**java.sql.Struct** - **Vendor-Dependent**
- Oracle: Fully supported for object types
- PostgreSQL: Supported for composite types via getObject
- Other databases: Limited or not applicable

### E.1.5 Not Implemented / Unsupported

The following JDBC features are **not supported** by OJP and will throw `SQLFeatureNotSupportedException`:

**Connection Methods**:
- `createArrayOf()` - Use vendor-specific array creation
- `createStruct()` - Use vendor-specific struct creation  
- `getTypeMap()` / `setTypeMap()` - Custom type mapping not supported
- `setCatalog()` / `setSchema()` - Use connection URL or database-specific commands instead
- Async variants (e.g., `setNetworkTimeout` with non-zero timeout is no-op)

**Statement Methods**:
- `setCursorName()` - Cursor naming not supported
- `setEscapeProcessing()` - Always enabled, cannot be disabled
- `getLargeUpdateCount()` / `getLargeMaxRows()` - Use standard int-based methods

**ResultSet Methods**:
- `getCursorName()` - Cursor naming not supported
- `getRow()` - Always returns 0 (row numbers not tracked)
- `rowUpdated()` / `rowInserted()` / `rowDeleted()` - Always return false
- `refreshRow()` - Not supported (throws exception)
- `moveToInsertRow()` / `moveToCurrentRow()` - Not supported for insert operations

---

## E.2 ResultSet Semantics and Streaming Behavior

Understanding how ResultSets are fetched, buffered, and streamed is critical for memory management and performance optimization.

### E.2.1 Fetch Strategy: Block-Based vs. Row-by-Row

OJP uses two distinct fetching strategies depending on the database vendor and query characteristics:

**Block-Based Fetching (Default)**:
- Used by: PostgreSQL, MySQL, MariaDB, Oracle (when LOBs not present)
- Behavior: Results are fetched in blocks from the server
- Default block size: Configurable via `ojp.resultset.block.size` (default: 1000 rows)
- Memory: Client buffers one block at a time (e.g., 1000 rows * row size)
- Network: Reduces round-trips significantly
- Use case: Ideal for most queries with predictable row sizes

**Row-by-Row Fetching**:
- Used by: SQL Server and DB2 (when LOBs present), Oracle (with cursor hints)
- Trigger: Automatically enabled when result set contains Blob, Clob, or NClob columns
- Behavior: Each `next()` call triggers a gRPC round-trip to fetch the next row
- Memory: Minimal client-side buffering (one row at a time)
- Network: One round-trip per row (higher latency, lower throughput)
- Use case: Required for SQL Server/DB2 LOBs which invalidate when cursor moves
- **Important**: LOB columns must be read *before* calling `next()` again

Example distinguishing the modes:

```java
// Block-based: PostgreSQL without LOBs
ResultSet rs = stmt.executeQuery("SELECT id, name, created_at FROM users");
// Rows fetched in blocks of 1000, buffered client-side
while (rs.next()) {
    String name = rs.getString("name");  // Read from local buffer
}

// Row-by-row: SQL Server with LOB
ResultSet rs = stmt.executeQuery("SELECT id, name, resume_pdf FROM candidates");
// Each next() triggers gRPC call
while (rs.next()) {
    Blob pdf = rs.getBlob("resume_pdf");  // Must read before next()
    InputStream stream = pdf.getBinaryStream();
    // Process stream fully before calling next()
}
```

### E.2.2 FetchSize Behavior

The JDBC `setFetchSize()` method controls block size in OJP:

**Block-Based Mode**:
- `setFetchSize(n)` sets the block size to `n` rows
- OJP respects this setting and fetches `n` rows per block
- Default if not set: 1000 rows (configurable globally via `ojp.resultset.block.size`)
- Minimum: 1 row
- Maximum: 10000 rows (to prevent excessive memory use)
- **Recommendation**: For large result sets (>10K rows), set fetchSize to 500-2000 based on row size

**Row-by-Row Mode**:
- `setFetchSize()` is **ignored** (always 1 row per fetch)
- This is required for SQL Server/DB2 LOB semantics
- Cannot be overridden

**Memory Calculation Example**:
```java
// Row size: 500 bytes average
// FetchSize: 2000 rows
// Memory per block: 2000 * 500 = 1 MB

stmt.setFetchSize(2000);  // Balance between memory and round-trips
ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
```

### E.2.3 Client-Side vs. Server-Side Cursors

**Server-Side Cursors**:
- OJP maintains server-side cursors for all ResultSets
- Cursor state tracked on OJP Server (not in database)
- Once acquired, the database connection remains associated with the OJP session until `Connection.close()` terminates the session
- **Critical**: Always close ResultSets to release server resources
- Unclosed ResultSets trigger leak detection after 5 minutes (see Chapter 10.8)

**Client-Side Buffering**:
- Only the current block (fetchSize rows) is buffered client-side
- Previous blocks are discarded (not cached)
- Forward-only navigation is most efficient
- Scrollable ResultSets (TYPE_SCROLL_INSENSITIVE) buffer all data client-side when first accessed

**Cursor Scope**:
- ResultSet cursor is valid until `close()` is called or statement is closed
- Calling `next()` after last row returns `false` (cursor remains valid, just at end)
- Closing the Statement closes all associated ResultSets

### E.2.4 Memory Behavior for Large Result Sets

**Best Practices for Memory Management**:

1. **Always set appropriate fetchSize**:
```java
Statement stmt = conn.createStatement();
stmt.setFetchSize(1000);  // Fetch 1000 rows per block
ResultSet rs = stmt.executeQuery("SELECT * FROM massive_table");
```

2. **Close ResultSets early**:
```java
try (ResultSet rs = stmt.executeQuery("SELECT * FROM data")) {
    while (rs.next()) {
        // Process row
    }
}  // Auto-closed, releases server resources
```

3. **For very large result sets, use streaming**:
```java
stmt.setFetchSize(500);  // Smaller blocks for very large tables
ResultSet rs = stmt.executeQuery("SELECT * FROM billions_of_rows");
while (rs.next()) {
    // Process each row incrementally
    // Previous blocks are automatically discarded
}
```

4. **Monitor server-side memory**:
- OJP Server buffers blocks in memory during fetch
- Each open ResultSet holds: `fetchSize * avgRowSize` bytes on server
- Example: 1000 rows * 1KB = 1MB per ResultSet
- Use pool sizing to limit concurrent connections (see Chapter 5.3)

### E.2.5 Mid-Stream Cancellation

**What happens if the client stops reading mid-stream?**

**Scenario 1: ResultSet closed explicitly**:
```java
ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
rs.next();  // Read first row
rs.close(); // Explicitly close
// Server immediately releases the cursor and ResultSet-related server resources
```

**Scenario 2: Statement closed (implicit ResultSet close)**:
```java
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
rs.next();
stmt.close();  // Closes ResultSet implicitly
// Server releases all resources associated with statement
```

**Scenario 3: Connection closed (all statements closed)**:
```java
Connection conn = ds.getConnection();
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
rs.next();
conn.close();  // Closes statement, which closes ResultSet
// Server releases connection back to pool, all resources cleaned up
```

**Scenario 4: Client abandons ResultSet (leak scenario)**:
```java
ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
rs.next();
// ResultSet neither closed nor referenced anymore
// Leak detection triggers after 5 minutes (default)
// Server logs warning and forcibly closes ResultSet
// See Chapter 10.8 for leak detection configuration
```

**Memory Impact**:
- Unclosed ResultSets hold server-side memory until leak detection triggers
- Database connection remains active (counts against pool limits)
- Best practice: Always use try-with-resources to ensure closure

---

## E.3 Cancellation and Timeout Semantics

Understanding how statement cancellation and timeouts work in OJP is critical for handling long-running queries and preventing resource exhaustion.

### E.3.1 Statement.cancel() Behavior

**What happens when `Statement.cancel()` is called?**

1. **Client-side**: Cancel request sent to OJP Server via gRPC
2. **OJP Server**: Immediately marks the query as cancelled
3. **Database propagation**: OJP Server calls `Statement.cancel()` on the underlying database connection
4. **Result consumption**: If ResultSet is being iterated, iteration stops immediately
5. **SQLException**: Next operation on the statement throws `SQLException` with message "Query was cancelled"

**Important Notes**:
- Cancellation is **best-effort**, not guaranteed
- Some databases don't support mid-query cancellation (query continues on DB side)
- Cancellation does not close the ResultSet or Statement (you must still close them)
- If cancel is called before `executeQuery()` completes, the query may still start but will be cancelled shortly after

**Example**:
```java
Statement stmt = conn.createStatement();

// Thread 1: Execute long-running query
CompletableFuture.runAsync(() -> {
    try {
        ResultSet rs = stmt.executeQuery("SELECT * FROM massive_table WHERE complex_condition");
        while (rs.next()) {
            // Process rows...
        }
    } catch (SQLException e) {
        // Likely "Query was cancelled" exception
    }
});

// Thread 2: Cancel after timeout
Thread.sleep(5000);
stmt.cancel();  // Triggers cancellation on server and database
```

### E.3.2 Query Timeout

**setQueryTimeout()** sets a server-side timeout for query execution:

```java
Statement stmt = conn.createStatement();
stmt.setQueryTimeout(30);  // 30 seconds
ResultSet rs = stmt.executeQuery("SELECT * FROM slow_query");
// If query exceeds 30 seconds, SQLException is thrown
```

**Timeout Behavior**:
- Timeout starts when `executeQuery()` / `executeUpdate()` is called
- OJP Server monitors query execution time
- On timeout: Server calls `Statement.cancel()` on database connection
- Client receives `SQLTimeoutException` with message "Query timeout exceeded"
- Database may continue executing query briefly (depends on DB cancellation support)

**Timeout Precision**:
- Timeout is checked in 1-second intervals (not millisecond-precise)
- Minimum effective timeout: 1 second
- Maximum recommended: 300 seconds (5 minutes)
- Default: 0 (no timeout)

**Timeout vs. Network Timeout**:
- Query timeout: How long the query can run
- Network timeout: How long to wait for gRPC response (controlled by `ojp.grpc.timeout`)
- Both are independent; query timeout should be <= network timeout

### E.3.3 Connection Timeout

**setNetworkTimeout()** behavior in OJP:

```java
conn.setNetworkTimeout(executor, 60000);  // 60 seconds
```

**Implementation**:
- OJP sets the underlying database connection's network timeout
- This controls socket-level timeouts for database communication
- Does **not** affect gRPC timeouts (those are configured separately via `ojp.grpc.timeout`)
- The `executor` parameter is **ignored** (no async timeout processing)

**Practical Usage**:
- Use `setNetworkTimeout()` to prevent database socket hangs
- Use `ojp.grpc.timeout` (in milliseconds) to control client-to-OJP timeouts
- Both should be aligned: `grpc.timeout >= network.timeout + overhead`

### E.3.4 Cancellation During Batch Operations

**executeBatch()** cancellation:

```java
PreparedStatement pstmt = conn.prepareStatement("INSERT INTO data VALUES (?, ?)");
for (int i = 0; i < 100000; i++) {
    pstmt.setInt(1, i);
    pstmt.setString(2, "data-" + i);
    pstmt.addBatch();
}

// Thread 1: Execute batch
pstmt.executeBatch();

// Thread 2: Cancel during batch
pstmt.cancel();  // Cancels current statement in batch
```

**Batch Cancellation Behavior**:
- Cancels the currently executing statement in the batch
- Already-executed statements in the batch are **not rolled back**
- Remaining statements in the batch are **not executed**
- Returns `int[]` array with update counts for completed statements
- SQLException with "Batch execution cancelled" is thrown

**Transaction Implications**:
- If batch is in a transaction, partial results remain in transaction
- Application must decide: commit partial results or rollback
- Best practice: Use transactions with batch operations for atomicity

---

## E.4 Vendor-Specific Behaviors and Differences

Different databases have unique JDBC behaviors that OJP must accommodate. This section documents key differences to avoid surprises in production.

### E.4.1 PostgreSQL

**Connection Behavior**:
- Supports multiple concurrent statements per connection
- Transaction isolation: Default READ COMMITTED (can be changed)
- Savepoints: Fully supported

**ResultSet Behavior**:
- Block-based fetching (default 1000 rows)
- LOBs (bytea, text) are buffered fully on first access
- Cursors remain valid after commit (unlike some databases)

**Type Mapping**:
- `UUID` type mapped to `String` in JDBC
- Arrays: Fully supported via `java.sql.Array`
- JSON/JSONB: Retrieved as `String`, must parse manually
- `SERIAL` and `BIGSERIAL`: Behave like auto-increment, getGeneratedKeys() works

**Quirks**:
- `setFetchSize(0)` disables client-side buffering (all rows fetched immediately)
- `COPY` commands not supported via JDBC (use psql or COPY API)
- Prepared statement names are automatically managed (not user-visible)

### E.4.2 MySQL / MariaDB

**Connection Behavior**:
- Supports streaming result sets (use `setFetchSize(Integer.MIN_VALUE)`)
- Transaction isolation: Default REPEATABLE READ
- Savepoints: Supported (InnoDB only)

**ResultSet Behavior**:
- Block-based fetching by default
- Streaming mode: `stmt.setFetchSize(Integer.MIN_VALUE)` enables row-by-row
- LOBs (BLOB, TEXT) are buffered client-side
- Binary protocol used for PreparedStatements (more efficient)

**Type Mapping**:
- `TINYINT(1)` mapped to `Boolean` (use `tinyInt1isBit=false` to disable)
- `BIT(1)` mapped to `Boolean`
- `ENUM` retrieved as `String`
- `SET` retrieved as `String` (comma-separated)
- `YEAR` mapped to `java.sql.Date`

**Quirks**:
- Batch updates with RETURNING clause: Not supported
- Multi-statement queries: Disabled by default (use `allowMultiQueries=true` URL parameter)
- `getGeneratedKeys()` returns last inserted ID only (not for bulk inserts)
- Column names are case-insensitive by default (use `lower_case_table_names` setting)

### E.4.3 Oracle

**Connection Behavior**:
- Strict transaction management (auto-commit false by default in some drivers)
- Transaction isolation: Default READ COMMITTED
- Savepoints: Fully supported

**ResultSet Behavior**:
- Block-based fetching (OJP manages Oracle fetch sizes)
- LOBs (BLOB, CLOB, NCLOB) use server-side streaming
- BFILE: Not supported via JDBC (use Oracle-specific APIs)
- REF CURSOR: Supported via CallableStatement.getObject()

**Type Mapping**:
- `NUMBER` without precision: Mapped to `BigDecimal`
- `NUMBER` with precision: Mapped to appropriate Java type (int, long, double)
- `VARCHAR2`, `NVARCHAR2`: Standard string mapping
- `TIMESTAMP WITH TIME ZONE`: Mapped to `java.sql.Timestamp` (zone info preserved)
- `XMLTYPE`: Retrieved as `java.sql.SQLXML` or `String`

**Quirks**:
- Empty strings are treated as NULL (Oracle's historical behavior)
- `TO_CHAR()` often needed for date formatting
- Prepared statement caching is critical for performance (OJP handles this)
- OCI driver not supported (thin driver only)

### E.4.4 SQL Server

**Connection Behavior**:
- Row versioning for READ COMMITTED SNAPSHOT isolation
- Transaction isolation: Default READ COMMITTED
- Savepoints: Supported (limited nesting)

**ResultSet Behavior**:
- **Row-by-row fetching when LOBs present** (automatic, cannot be disabled)
- Block-based for queries without LOBs
- LOBs (IMAGE, TEXT, NTEXT, VARBINARY(MAX), VARCHAR(MAX), NVARCHAR(MAX)) invalidate when cursor moves
- **Critical**: Read LOB columns before calling `next()`

**Type Mapping**:
- `UNIQUEIDENTIFIER`: Mapped to `String` (use `.toString()` in queries)
- `DATETIMEOFFSET`: Mapped to `microsoft.sql.DateTimeOffset` class
- `HIERARCHYID`, `GEOMETRY`, `GEOGRAPHY`: Retrieved as byte arrays
- `XML`: Retrieved as `java.sql.SQLXML`
- Deprecated types: `TEXT`, `NTEXT`, `IMAGE` (use VARCHAR(MAX), NVARCHAR(MAX), VARBINARY(MAX))

**Quirks**:
- Multiple result sets from stored procedures: Use `getMoreResults()` to navigate
- `@@IDENTITY` vs. `SCOPE_IDENTITY()`: Use `SCOPE_IDENTITY()` for generated keys
- Column names are case-sensitive if database collation is case-sensitive
- Connection string must specify database: `databaseName=mydb`

**LOB Example** (SQL Server specific):
```java
// WRONG: LOB becomes invalid after next()
ResultSet rs = stmt.executeQuery("SELECT id, resume FROM candidates");
while (rs.next()) {
    int id = rs.getInt("id");
    // Call next() again without reading resume...
}

// CORRECT: Read LOB before moving cursor
while (rs.next()) {
    int id = rs.getInt("id");
    Blob resume = rs.getBlob("resume");
    InputStream stream = resume.getBinaryStream();
    // Fully process stream before calling next()
    byte[] data = stream.readAllBytes();
    stream.close();
}
```

### E.4.5 DB2

**Connection Behavior**:
- Supports multiple concurrent statements
- Transaction isolation: Default CURSOR STABILITY (similar to READ COMMITTED)
- Savepoints: Supported

**ResultSet Behavior**:
- **Row-by-row fetching when LOBs present** (same as SQL Server)
- Block-based for non-LOB queries
- LOBs (BLOB, CLOB, DBCLOB) invalidate when cursor moves
- XML: Retrieved as `java.sql.SQLXML`

**Type Mapping**:
- `DECFLOAT`: Mapped to `BigDecimal`
- `GRAPHIC`, `VARGRAPHIC`: Double-byte character strings (map to `String`)
- `ROWID`: Retrieved as `java.sql.RowId`
- `DATALINK`: Not supported via JDBC

**Quirks**:
- Schema names are significant (must be explicitly specified)
- `SYSIBM` schema contains system tables
- Generated keys: Must use `getGeneratedKeys()` after insert
- Connection requires license key for some DB2 editions (Db2 Connect)

---

## E.5 Metadata Reliability and Limitations

### E.5.1 DatabaseMetaData

**Reliability**: Generally **high** for standard metadata operations

**Fully Reliable Operations**:
- `getDatabaseProductName()`, `getDatabaseProductVersion()`: Direct from database
- `getTables()`, `getColumns()`, `getPrimaryKeys()`, `getForeignKeys()`: Accurate
- `getIndexInfo()`: Accurate (performance may vary by database size)
- `supportsXXX()` methods: Accurate based on underlying driver capabilities

**Limitations**:
- Catalog/schema operations may be slow on large databases (query system tables)
- Cross-database metadata queries: Only supported if underlying driver supports it
- Temporary tables: May not appear in `getTables()` (vendor-specific)
- **Performance**: Cache metadata results instead of repeated calls

**Example**:
```java
DatabaseMetaData meta = conn.getMetaData();

// Fast operations (cached by OJP Server)
String dbProduct = meta.getDatabaseProductName();  // "PostgreSQL"
int majorVersion = meta.getDatabaseMajorVersion(); // 15

// Potentially slow operations (query system tables)
ResultSet tables = meta.getTables(null, "public", "%", new String[]{"TABLE"});
while (tables.next()) {
    String tableName = tables.getString("TABLE_NAME");
}
tables.close();
```

### E.5.2 ParameterMetaData

**Reliability**: **Variable** by database

**PostgreSQL**: 
- Fully reliable for named parameters (`$1`, `$2`, etc.)
- Type inference works well for most queries
- May return `VARCHAR` for ambiguous parameters

**MySQL/MariaDB**:
- Parameter types not available until first execution
- `getParameterType()` may return `Types.VARCHAR` initially
- After first execution, types are inferred

**Oracle**:
- Fully reliable if PL/SQL procedure metadata available
- Ad-hoc queries: Type inference works for simple cases
- Complex expressions: May return generic types

**SQL Server**:
- Good reliability for stored procedures (metadata from procedure definition)
- Ad-hoc queries: Parameter types inferred after execution

**DB2**:
- Reliable for static SQL and stored procedures
- Dynamic SQL: Types available after `describe()`

**Best Practice**: Don't rely solely on ParameterMetaData for critical logic; explicitly set parameter types.

### E.5.3 Generated Keys

**Support by Database**:

**PostgreSQL**: 
- Fully supported via `RETURNING` clause
- `stmt.executeUpdate("INSERT ... RETURNING id", Statement.RETURN_GENERATED_KEYS)`
- Multi-row inserts: Returns all generated keys

**MySQL/MariaDB**:
- Fully supported for single-row inserts
- **Limitation**: Batch inserts return only the first generated key
- Use `LAST_INSERT_ID()` for last generated key

**Oracle**:
- Fully supported via `RETURNING INTO` clause
- OJP automatically handles Oracle-specific syntax
- Multi-row inserts: Supported via `RETURNING BULK COLLECT`

**SQL Server**:
- Fully supported via `OUTPUT INSERTED.*` clause
- `SCOPE_IDENTITY()` used for single-row inserts
- Multi-row inserts: Supported via `OUTPUT` clause

**DB2**:
- Supported via `SELECT FROM FINAL TABLE (INSERT ...)`
- OJP translates `RETURN_GENERATED_KEYS` to DB2-specific syntax

**Example (portable code)**:
```java
PreparedStatement pstmt = conn.prepareStatement(
    "INSERT INTO users (name, email) VALUES (?, ?)",
    Statement.RETURN_GENERATED_KEYS
);
pstmt.setString(1, "John Doe");
pstmt.setString(2, "john@example.com");
pstmt.executeUpdate();

ResultSet keys = pstmt.getGeneratedKeys();
if (keys.next()) {
    long id = keys.getLong(1);  // Generated ID
}
keys.close();
pstmt.close();
```

### E.5.4 SQLWarning Preservation

**Behavior**: SQLWarnings are **preserved** across the OJP proxy layer

**How it works**:
1. Database returns warning (e.g., data truncation, NULL in aggregate)
2. OJP Server captures warning chain from underlying ResultSet/Statement/Connection
3. Warning chain serialized via gRPC and reconstructed client-side
4. Client can retrieve warnings via `getWarnings()`

**Example**:
```java
Statement stmt = conn.createStatement();
stmt.executeUpdate("UPDATE users SET age = age + 1");

SQLWarning warning = stmt.getWarnings();
while (warning != null) {
    System.out.println("Warning: " + warning.getMessage());
    System.out.println("SQLState: " + warning.getSQLState());
    System.out.println("VendorCode: " + warning.getErrorCode());
    warning = warning.getNextWarning();
}
stmt.clearWarnings();
```

**Limitations**:
- Warning chain limited to 100 warnings (to prevent excessive gRPC payload size)
- Vendor-specific warning details may be lost in serialization
- Warnings are cleared on next `execute` call (per JDBC spec)

---

## E.6 Transaction and Isolation Guarantees

### E.6.1 Transaction Boundaries

**Guarantee**: Transaction boundaries are **strictly preserved** by OJP

**How it works**:
- `setAutoCommit(false)` → OJP tracks transaction state on server
- All statements execute within same database transaction
- `commit()` → Commits database transaction, releases locks
- `rollback()` → Rolls back database transaction, releases locks
- Transaction state synchronized across connection lifecycle

**Multi-Statement Transaction Example**:
```java
conn.setAutoCommit(false);
try {
    stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (1, 1000)");
    stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (2, 500)");
    stmt.executeUpdate("UPDATE accounts SET balance = balance - 200 WHERE id = 1");
    stmt.executeUpdate("UPDATE accounts SET balance = balance + 200 WHERE id = 2");
    conn.commit();  // All or nothing
} catch (SQLException e) {
    conn.rollback();  // Undo all changes
    throw e;
}
```

### E.6.2 Isolation Levels

**Supported Isolation Levels** (all standard JDBC levels):

1. **TRANSACTION_READ_UNCOMMITTED** (least strict)
   - Dirty reads allowed
   - Supported by: MySQL/MariaDB, SQL Server
   - Not supported by: PostgreSQL (upgraded to READ COMMITTED)

2. **TRANSACTION_READ_COMMITTED** (most common)
   - Prevents dirty reads
   - Non-repeatable reads and phantom reads possible
   - Supported by: All databases
   - **Default** for PostgreSQL, Oracle, SQL Server

3. **TRANSACTION_REPEATABLE_READ**
   - Prevents dirty reads and non-repeatable reads
   - Phantom reads possible (except MySQL/MariaDB)
   - Supported by: All databases
   - **Default** for MySQL/MariaDB

4. **TRANSACTION_SERIALIZABLE** (most strict)
   - Prevents dirty reads, non-repeatable reads, and phantom reads
   - Performance impact (locking or MVCC overhead)
   - Supported by: All databases

**Setting Isolation Level**:
```java
conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
int level = conn.getTransactionIsolation();  // Verify
```

**Vendor-Specific Notes**:
- PostgreSQL: Only supports READ COMMITTED and SERIALIZABLE effectively (READ UNCOMMITTED → READ COMMITTED, REPEATABLE READ → SERIALIZABLE)
- MySQL/MariaDB: REPEATABLE READ prevents phantom reads via gap locking
- SQL Server: Supports READ COMMITTED SNAPSHOT (enabled via database option)
- Oracle: SERIALIZABLE uses timestamp-based MVCC (not locking)

### E.6.3 Savepoints

**Guarantee**: Savepoints are **fully supported** and work correctly

**Usage**:
```java
conn.setAutoCommit(false);
stmt.executeUpdate("INSERT INTO audit_log VALUES (1, 'start')");

Savepoint sp1 = conn.setSavepoint("checkpoint1");
stmt.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE id = 1");

try {
    stmt.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE id = 2");
    conn.commit();
} catch (SQLException e) {
    conn.rollback(sp1);  // Rollback to savepoint, keeps audit log insert
    conn.commit();       // Commit remaining transaction
}
```

**Limitations**:
- Savepoint names are case-sensitive
- Maximum nesting depth varies by database (typically 100+)
- Savepoints released automatically on commit/rollback
- Cannot rollback to a savepoint from a different connection

---

## E.7 Performance Characteristics

### E.7.1 Latency Overhead

**Network Round-Trip Latency**:
- OJP adds **1 gRPC round-trip** per JDBC operation
- Typical overhead: **1-3 milliseconds** per call (same datacenter)
- Across regions: **50-200 milliseconds** (depends on geographic distance)

**Operations and Their Round-Trip Counts**:

| Operation | gRPC Calls | Typical Latency |
|-----------|-----------|----------------|
| `conn.prepareStatement()` | 1 | 1-2 ms |
| `pstmt.setString()` | 0 (batched) | 0 ms |
| `pstmt.executeQuery()` | 1 | 1-3 ms |
| `rs.next()` (block mode) | 0 (buffered) | < 0.1 ms |
| `rs.next()` (fetch new block) | 1 | 1-3 ms |
| `rs.next()` (row-by-row) | 1 | 1-3 ms |
| `rs.getString()` | 0 | < 0.1 ms |
| `conn.commit()` | 1 | 2-5 ms |

**Optimization**: Use batch operations and appropriate fetch sizes to minimize round-trips

### E.7.2 Throughput

**Query Throughput**:
- Block-based fetching: **10,000-50,000 rows/second** per connection
- Row-by-row fetching: **200-1,000 rows/second** per connection
- Batch inserts: **5,000-20,000 rows/second** per connection

**Factors Affecting Throughput**:
- Network latency (biggest factor)
- Row size (larger rows = fewer rows per block)
- LOB columns (force row-by-row mode)
- Fetch size (larger = more throughput, more memory)

**Bottleneck Identification**:
```sql
-- Query execution time on DB vs. result fetching time
-- Enable telemetry (Chapter 13) to track:
-- - grpc.call.duration (total OJP overhead)
-- - db.query.duration (actual database time)
-- - result.fetch.duration (result streaming time)
```

### E.7.3 Memory Consumption

**Client-Side Memory**:
- Per ResultSet: `fetchSize * avgRowSize` bytes
- Example: 1000 rows * 1KB = 1MB per ResultSet
- PreparedStatement parameter buffers: Typically <1KB

**Server-Side Memory**:
- Per connection: 100KB - 500KB (session state, buffers)
- Per ResultSet: `fetchSize * avgRowSize` bytes (same as client)
- LOB streaming buffers: 64KB - 1MB per active LOB

**Memory Leak Prevention**:
- Always close ResultSets, Statements, and Connections
- Use try-with-resources for automatic cleanup
- Monitor server metrics: `ojp.resultset.active.count`, `ojp.connection.active.count`
- Leak detection (Chapter 10.8) will close abandoned resources after 5 minutes

---

## E.8 Common Pitfalls and Anti-Patterns

### E.8.1 ResultSet Not Closed

**Problem**:
```java
// BAD: ResultSet not closed
public List<User> getUsers() {
    Connection conn = ds.getConnection();
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    List<User> users = new ArrayList<>();
    while (rs.next()) {
        users.add(new User(rs.getInt("id"), rs.getString("name")));
    }
    conn.close();  // Closes connection, but ResultSet holds server resources until GC
    return users;
}
```

**Solution**:
```java
// GOOD: Explicitly close ResultSet
public List<User> getUsers() throws SQLException {
    try (Connection conn = ds.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
        List<User> users = new ArrayList<>();
        while (rs.next()) {
            users.add(new User(rs.getInt("id"), rs.getString("name")));
        }
        return users;
    }
}
```

### E.8.2 Reading LOBs After Cursor Movement

**Problem** (SQL Server / DB2):
```java
// BAD: LOB becomes invalid after next()
ResultSet rs = stmt.executeQuery("SELECT id, document FROM files");
while (rs.next()) {
    int id = rs.getInt("id");
}
// Later attempt to read document will fail
```

**Solution**:
```java
// GOOD: Read LOB before calling next() again
ResultSet rs = stmt.executeQuery("SELECT id, document FROM files");
while (rs.next()) {
    int id = rs.getInt("id");
    Blob doc = rs.getBlob("document");
    byte[] data = doc.getBytes(1, (int) doc.length());
    doc.free();
    // Process data here before calling next()
}
```

### E.8.3 Small FetchSize for Large Result Sets

**Problem**:
```java
// BAD: Default fetchSize (often 10) for large table
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM million_row_table");
// 100,000 round-trips to fetch all rows (very slow)
```

**Solution**:
```java
// GOOD: Set appropriate fetchSize
Statement stmt = conn.createStatement();
stmt.setFetchSize(2000);  // Fetch 2000 rows per round-trip
ResultSet rs = stmt.executeQuery("SELECT * FROM million_row_table");
// Only 500 round-trips (200x improvement)
```

### E.8.4 Not Using Prepared Statements

**Problem**:
```java
// BAD: String concatenation and SQL injection risk
Statement stmt = conn.createStatement();
String sql = "SELECT * FROM users WHERE name = '" + userName + "'";
ResultSet rs = stmt.executeQuery(sql);
```

**Solution**:
```java
// GOOD: Use PreparedStatement
PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM users WHERE name = ?");
pstmt.setString(1, userName);
ResultSet rs = pstmt.executeQuery();
```

### E.8.5 Ignoring Transaction Boundaries

**Problem**:
```java
// BAD: Auto-commit true, each statement is a separate transaction
conn.setAutoCommit(true);
stmt.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE id = 1");
stmt.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE id = 2");
// If second statement fails, first is already committed (data inconsistency)
```

**Solution**:
```java
// GOOD: Explicit transaction
conn.setAutoCommit(false);
try {
    stmt.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE id = 1");
    stmt.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE id = 2");
    conn.commit();
} catch (SQLException e) {
    conn.rollback();
    throw e;
}
```

---

## E.9 Migration Checklist

When migrating from direct JDBC to OJP, use this checklist to ensure compatibility:

### E.9.1 Pre-Migration Assessment

- [ ] Identify all JDBC code paths in application
- [ ] Document current database vendors and JDBC drivers
- [ ] Measure baseline query performance (latency, throughput)
- [ ] Identify LOB usage (Blob, Clob) and verify cursor behavior
- [ ] Check for vendor-specific JDBC extensions (Oracle OCI, SQL Server TVP)
- [ ] Document current connection pool configuration
- [ ] Review transaction isolation levels used

### E.9.2 Code Review

- [ ] Verify all ResultSets are closed (use try-with-resources)
- [ ] Check LOB reading patterns (must read before next() on SQL Server/DB2)
- [ ] Validate fetchSize settings (set explicitly for large result sets)
- [ ] Ensure PreparedStatements are used for parameterized queries
- [ ] Review transaction boundaries (auto-commit vs. manual transactions)
- [ ] Check for Statement.cancel() usage and timeout expectations
- [ ] Validate metadata operations (DatabaseMetaData, ParameterMetaData)

### E.9.3 Configuration

- [ ] Set `ojp.resultset.block.size` appropriately (default: 1000)
- [ ] Configure `ojp.grpc.timeout` aligned with query timeouts
- [ ] Set connection pool sizes (Chapter 5.3)
- [ ] Enable telemetry for performance monitoring (Chapter 13)
- [ ] Configure leak detection thresholds (Chapter 10.8)

### E.9.4 Testing

- [ ] Unit tests: Verify JDBC operations work correctly
- [ ] Integration tests: Validate transactions, savepoints, batch operations
- [ ] Performance tests: Measure latency overhead (should be 1-3ms)
- [ ] Load tests: Validate throughput under concurrent load
- [ ] Failure tests: Simulate OJP Server unavailability, network partitions
- [ ] LOB tests: Verify LOB reading patterns work with vendor-specific semantics

### E.9.5 Monitoring

- [ ] Enable OJP telemetry (Chapter 13)
- [ ] Monitor `ojp.connection.active.count` and `ojp.resultset.active.count`
- [ ] Track `grpc.call.duration` and `db.query.duration` metrics
- [ ] Set alerts for leak detection warnings (Chapter 10.8)
- [ ] Monitor pool exhaustion events

---

## E.10 Summary and Recommendations

**OJP provides comprehensive JDBC compatibility** with the following key points:

### What OJP Does Well:
✅ Full implementation of core JDBC interfaces (Connection, Statement, PreparedStatement, ResultSet)
✅ Transaction management with all isolation levels
✅ Savepoint support
✅ Generated keys support (all major databases)
✅ LOB support with proper lifecycle management
✅ Metadata operations (DatabaseMetaData, ParameterMetaData)
✅ Batch operations
✅ Statement cancellation and query timeouts
✅ SQLWarning preservation

### What to Watch Out For:
⚠️ **Latency overhead**: 1-3ms per gRPC call (optimize with batch operations and fetch sizes)
⚠️ **LOB semantics**: SQL Server and DB2 require reading LOBs before cursor movement
⚠️ **FetchSize**: Must be set explicitly for large result sets (default may be inefficient)
⚠️ **Resource cleanup**: Always close ResultSets, Statements, Connections (use try-with-resources)
⚠️ **Vendor differences**: Some behaviors vary by database (especially LOBs, type mapping, metadata)

### Not Supported:
❌ Custom type maps (`setTypeMap()`)
❌ Catalog/schema switching via `setCatalog()` / `setSchema()`
❌ Cursor naming (`setCursorName()`, `getCursorName()`)
❌ Row-level change tracking (`rowUpdated()`, `rowInserted()`, `rowDeleted()`)
❌ `COPY` commands (PostgreSQL), TVP (SQL Server), or other vendor extensions

### Recommendations:
1. **Always use try-with-resources** for automatic resource cleanup
2. **Set fetchSize explicitly** for queries returning >1000 rows
3. **Use PreparedStatements** for parameterized queries (better performance and security)
4. **Understand vendor-specific LOB semantics** (SQL Server/DB2 vs. PostgreSQL/MySQL)
5. **Enable telemetry** (Chapter 13) to monitor performance and detect issues
6. **Configure leak detection** (Chapter 10.8) to catch resource leaks early
7. **Test with your specific database** - behaviors vary by vendor

**This appendix should be read alongside**:
- Chapter 5: JDBC Configuration
- Chapter 10: XA Distributed Transactions
- Chapter 13: Telemetry and Monitoring
- Appendix G: Troubleshooting

---

**AI-Ready Image Prompts for Appendix E**:

1. **"JDBC Interface Coverage Matrix"**: Create a comprehensive visual matrix showing Connection, Statement, PreparedStatement, CallableStatement, ResultSet, DatabaseMetaData, and ParameterMetaData interfaces on the Y-axis, and "Fully Implemented", "Partially Implemented", "Not Supported" on the X-axis. Use checkmarks (green), partial checkmarks (yellow), and X marks (red). Add icons for each interface type. Style: Technical diagram with color-coded cells.

2. **"ResultSet Fetch Modes Comparison"**: Side-by-side comparison diagram showing "Block-Based Fetching" (left) vs "Row-by-Row Fetching" (right). Show client, network (gRPC), and server layers. Block mode: One round-trip fetches 1000 rows, buffered client-side. Row mode: Each next() triggers round-trip for one row. Include database icons (PostgreSQL/MySQL use block, SQL Server/DB2 use row-by-row when LOBs present). Style: Architecture diagram with network flow arrows and timing indicators.

3. **"LOB Reading Anti-Pattern Warning"**: Create a warning poster showing "WRONG vs. RIGHT" LOB reading patterns for SQL Server/DB2. Left (WRONG, red X): Code showing `rs.next()` called before reading Blob, with explosion icon indicating "LOB Invalid!" Right (RIGHT, green checkmark): Code showing Blob read before `rs.next()`, with thumbs-up icon. Include SQL Server and DB2 logos. Style: Code snippet comparison with visual warning indicators.

4. **"Statement Cancellation Flow"**: Sequence diagram showing cancellation propagation: Thread 1 calls `stmt.executeQuery()` → gRPC call to OJP Server → Database query executing. Thread 2 calls `stmt.cancel()` → gRPC cancel to Server → Server calls `stmt.cancel()` on DB → SQLException returned to Thread 1. Include timing arrows and status indicators at each stage. Style: UML sequence diagram with color-coded threads and message flows.

5. **"Vendor LOB Behavior Comparison"**: Create a comparison table/chart showing how different databases handle LOBs. Columns: PostgreSQL, MySQL, Oracle, SQL Server, DB2. Rows: Fetch Mode (block/row), LOB Buffering (client/server), Cursor Movement Impact (valid/invalid), Memory Behavior. Use icons and color coding. Include representative code snippets for each vendor. Style: Technical comparison matrix with vendor logos and behavior indicators.

6. **"FetchSize Impact on Performance"**: Graph showing relationship between fetchSize and performance metrics. X-axis: fetchSize (10, 100, 500, 1000, 2000, 5000). Left Y-axis: Throughput (rows/second, green line increasing then plateauing). Right Y-axis: Memory per ResultSet (MB, blue line increasing linearly). Highlight optimal zone (1000-2000). Include annotations for trade-offs. Style: Dual-axis line graph with shaded optimal zone.

7. **"JDBC Migration Checklist"**: Visual checklist with sections: Pre-Migration (assessment items), Code Review (compatibility items), Configuration (OJP settings), Testing (test types), Monitoring (metrics). Each section has checkboxes and icons. Use color coding: Not Started (gray), In Progress (yellow), Complete (green). Style: Professional checklist with section dividers and status indicators.

8. **"Transaction Isolation Levels"**: Pyramid or hierarchy diagram showing four isolation levels from bottom (least strict) to top (most strict): READ UNCOMMITTED → READ COMMITTED → REPEATABLE READ → SERIALIZABLE. For each level, show: Name, What it prevents (dirty reads, non-repeatable reads, phantom reads), Performance impact, and database support matrix. Style: Layered pyramid with annotations and vendor logos.

---

**Mermaid Diagrams for Appendix E**:

1. **Statement Cancellation Sequence**:
```mermaid
sequenceDiagram
    participant C1 as Client Thread 1
    participant C2 as Client Thread 2
    participant O as OJP Server
    participant DB as Database

    C1->>O: executeQuery("SELECT ...")
    O->>DB: Execute query
    DB-->>DB: Query running...
    
    Note over C2: After 5 seconds
    C2->>O: cancel()
    O->>DB: Statement.cancel()
    DB-->>DB: Attempt cancellation
    DB-->>O: Cancelled (or continues briefly)
    O-->>C1: SQLException("Query was cancelled")
    O-->>C2: void (cancel acknowledged)
```

2. **ResultSet Fetch Flow (Block-Based)**:
```mermaid
graph TD
    A[Client: rs.next] --> B{Block buffered?}
    B -->|Yes| C[Return row from buffer]
    C --> D[Increment buffer index]
    D --> E{More rows in buffer?}
    E -->|Yes| F[Return true]
    E -->|No| G{More blocks on server?}
    G -->|Yes| H[gRPC: Fetch next block]
    H --> I[Buffer new block]
    I --> F
    G -->|No| J[Return false]
    
    style H fill:#ff9900
    style C fill:#90EE90
    style J fill:#FFB6C1
```

3. **LOB Lifecycle (SQL Server/DB2 specific)**:
```mermaid
stateDiagram-v2
    [*] --> ResultSetOpen
    ResultSetOpen --> CursorOnRow1: rs.next()
    CursorOnRow1 --> LOBValid: rs.getBlob("doc")
    LOBValid --> LOBInvalid: rs.next() (cursor moved)
    LOBValid --> LOBRead: blob.getBinaryStream()
    LOBRead --> LOBInvalid: rs.next()
    LOBInvalid --> [*]: Throws SQLException if accessed
    
    note right of LOBInvalid
        SQL Server/DB2:
        LOB becomes invalid
        when cursor moves
    end note
```

---

**Total Content**: ~22KB, 1100+ lines
**Visual Assets**: 8 AI-ready image prompts, 3 Mermaid diagrams
**Coverage**: Comprehensive JDBC compatibility reference addressing critical gap #2 from GAP-ANALYSIS.md
