package org.openjproxy.jdbc.testutil;

import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared utility class for database test setup, teardown, and validation operations.
 * Used by both H2 and Postgres integration tests to provide common functionality
 * while allowing database-specific customizations.
 */
public class TestDBUtils {

    /**
     * Result holder for connection creation that includes both Connection and XAConnection.
     * When isXA is false, only connection is populated and xaConnection is null.
     */
    public static class ConnectionResult {
        private final Connection connection;
        private final XAConnection xaConnection;
        private final boolean isXA;
        private XAResource xaResource;
        private Xid currentXid;
        private boolean xaTransactionStarted = false;

        public ConnectionResult(Connection connection, XAConnection xaConnection) {
            this.connection = connection;
            this.xaConnection = xaConnection;
            this.isXA = (xaConnection != null);
            if (isXA) {
                try {
                    this.xaResource = xaConnection.getXAResource();
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to get XAResource", e);
                }
            }
        }

        public Connection getConnection() {
            return connection;
        }

        public XAConnection getXaConnection() {
            return xaConnection;
        }

        public boolean isXA() {
            return isXA;
        }

        /**
         * Starts XA transaction if this is an XA connection and transaction not already started.
         * Should be called after setAutoCommit(false) for XA connections.
         */
        public void startXATransactionIfNeeded() throws SQLException {
            if (isXA && !xaTransactionStarted) {
                try {
                    if (!connection.getAutoCommit()) {
                        currentXid = new SimpleXid();
                        xaResource.start(currentXid, XAResource.TMNOFLAGS);
                        xaTransactionStarted = true;
                    }
                } catch (Exception e) {
                    throw new SQLException("Failed to start XA transaction", e);
                }
            }
        }

        /**
         * Commits the transaction using the appropriate method based on connection type.
         * For XA connections, uses XAResource.commit(). For regular connections, uses Connection.commit().
         */
        public void commit() throws SQLException {
            if (isXA) {
                // For XA connections, don't call connection.commit() - it's not allowed
                // Instead, manage via XAResource if autocommit is off
                try {
                    if (!connection.getAutoCommit() && xaTransactionStarted) {
                        // End and commit the XA transaction
                        xaResource.end(currentXid, XAResource.TMSUCCESS);
                        xaResource.commit(currentXid, true);
                        currentXid = null;
                        xaTransactionStarted = false;
                    }
                } catch (Exception e) {
                    xaTransactionStarted = false;
                    currentXid = null;
                    throw new SQLException("Failed to commit XA transaction", e);
                }
            } else {
                connection.commit();
            }
        }

        /**
         * Rolls back the transaction.
         */
        public void rollback() throws SQLException {
            if (isXA) {
                try {
                    if (!connection.getAutoCommit() && xaTransactionStarted) {
                        xaResource.end(currentXid, XAResource.TMFAIL);
                        xaResource.rollback(currentXid);
                        currentXid = null;
                        xaTransactionStarted = false;
                    }
                } catch (Exception e) {
                    xaTransactionStarted = false;
                    currentXid = null;
                    throw new SQLException("Failed to rollback XA transaction", e);
                }
            } else {
                connection.rollback();
            }
        }

        /**
         * Closes both connection and xaConnection if they exist.
         * For XA connections, properly ends any active transaction before closing.
         */
        public void close() {
            // For XA connections, end any active transaction before closing
            if (isXA && xaTransactionStarted) {
                try {
                    xaResource.end(currentXid, XAResource.TMFAIL);
                    xaResource.rollback(currentXid);
                    xaTransactionStarted = false;
                    currentXid = null;
                } catch (Exception e) {
                    // Log but don't throw - we're closing anyway
                    System.err.println("Warning: Failed to rollback XA transaction during close: " + e.getMessage());
                }
            }

            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception e) {
                // Ignore
            }
            try {
                if (xaConnection != null) {
                    xaConnection.close();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /**
     * Simple Xid implementation for XA transactions in tests.
     */
    private static class SimpleXid implements Xid {
        // AtomicInteger replaces synchronized(SimpleXid.class) for thread-safe ID generation
        // and avoids virtual-thread pinning
        private static final AtomicInteger counter = new AtomicInteger(0);
        private final int id;

        public SimpleXid() {
            this.id = counter.getAndIncrement();
        }

        @Override
        public int getFormatId() {
            return 1;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return ("gtx" + id).getBytes();
        }

        @Override
        public byte[] getBranchQualifier() {
            return ("btx" + id).getBytes();
        }
    }

    /**
     * Creates a database connection based on the isXA flag.
     * If isXA is true, creates an XA connection using OjpXADataSource.
     * If isXA is false, creates a standard JDBC connection using DriverManager.
     *
     * @param url      The JDBC URL
     * @param user     The database user
     * @param password The database password
     * @param isXA     Whether to create an XA connection
     * @return ConnectionResult containing the Connection and optionally XAConnection
     * @throws SQLException if connection creation fails
     */
    public static ConnectionResult createConnection(String url, String user, String password, boolean isXA) throws SQLException {
        if (isXA) {
            OjpXADataSource xaDataSource = new OjpXADataSource();
            xaDataSource.setUrl(url);
            xaDataSource.setUser(user);
            xaDataSource.setPassword(password);
            XAConnection xaConnection = xaDataSource.getXAConnection(user, password);
            Connection connection = xaConnection.getConnection();
            ConnectionResult result = new ConnectionResult(connection, xaConnection);
            // For XA connections, set autocommit to false and start transaction immediately
            connection.setAutoCommit(false);
            result.startXATransactionIfNeeded();
            return result;
        } else {
            Connection connection = DriverManager.getConnection(url, user, password);
            return new ConnectionResult(connection, null);
        }
    }

    /**
     * Enum representing different SQL syntax variations for database-specific operations.
     */
    public enum SqlSyntax {
        H2,
        POSTGRES,
        MYSQL,
        ORACLE,
        SQLSERVER,
        DB2,
        COCKROACHDB
    }

    /**
     * Creates a basic test table for integration tests.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @param sqlSyntax  The SQL syntax to use (H2, POSTGRES, MYSQL, ORACLE, or SQLSERVER)
     * @throws SQLException if table creation fails
     */
    public static void createBasicTestTable(Connection connection, String tableName, SqlSyntax sqlSyntax, boolean createDefalutData) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists with database-specific syntax
            if (sqlSyntax == SqlSyntax.ORACLE) {
                // Oracle needs special handling for DROP TABLE IF EXISTS
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                // SQL Server uses IF EXISTS syntax
                statement.execute("IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName);
            } else if (sqlSyntax == SqlSyntax.DB2) {
                // DB2 might not support IF EXISTS in older versions, so use try/catch
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else {
                String dropTableSql = "DROP TABLE IF EXISTS " + tableName;
                statement.execute(dropTableSql);
            }

            // Create table with appropriate syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.ORACLE) {
                createTableSql = "CREATE TABLE " + tableName + " (id NUMBER(10) PRIMARY KEY, name VARCHAR2(255))";
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                createTableSql = "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name NVARCHAR(255))";
            } else if (sqlSyntax == SqlSyntax.DB2) {
                createTableSql = "CREATE TABLE " + tableName + " (id INTEGER PRIMARY KEY NOT NULL, name VARCHAR(255))";
            } else {
                createTableSql = "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(255))";
            }
            statement.execute(createTableSql);

            // Insert initial test data
            if (createDefalutData) {
                statement.execute("INSERT INTO " + tableName + " (id, name) VALUES (1, 'Alice')");
                statement.execute("INSERT INTO " + tableName + " (id, name) VALUES (2, 'Bob')");
            }
        }
    }

    /**
     * Creates a test table with auto-increment capabilities.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @param sqlSyntax  The SQL syntax to use (H2, POSTGRES, MYSQL, ORACLE, or SQLSERVER)
     * @throws SQLException if table creation fails
     */
    public static void createAutoIncrementTestTable(Connection connection, String tableName, SqlSyntax sqlSyntax) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists with database-specific syntax
            if (sqlSyntax == SqlSyntax.ORACLE) {
                // Oracle needs special handling for DROP TABLE IF EXISTS
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                // SQL Server uses IF EXISTS syntax
                statement.execute("IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName);
            } else if (sqlSyntax == SqlSyntax.DB2) {
                // DB2 might not support IF EXISTS in older versions, so use try/catch
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else {
                String dropTableSql = "DROP TABLE IF EXISTS " + tableName;
                statement.execute(dropTableSql);
            }

            // Create table with appropriate auto-increment syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                createTableSql = "CREATE TABLE " + tableName + " (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))";
            } else if (sqlSyntax == SqlSyntax.POSTGRES || sqlSyntax == SqlSyntax.COCKROACHDB) {
                createTableSql = "CREATE TABLE " + tableName + " (id SERIAL PRIMARY KEY, name VARCHAR(255))";
            } else if (sqlSyntax == SqlSyntax.ORACLE) {
                // Oracle uses sequences and triggers or IDENTITY columns (12c+)
                createTableSql = "CREATE TABLE " + tableName + " (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR2(255))";
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                // SQL Server uses IDENTITY
                createTableSql = "CREATE TABLE " + tableName + " (id INT IDENTITY(1,1) PRIMARY KEY, name NVARCHAR(255))";
            } else if (sqlSyntax == SqlSyntax.DB2) {
                // DB2 uses GENERATED BY DEFAULT AS IDENTITY
                createTableSql = "CREATE TABLE " + tableName + " (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(255))";
            } else { // MYSQL
                createTableSql = "CREATE TABLE " + tableName + " (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))";
            }
            statement.execute(createTableSql);
        }
    }

    /**
     * Creates a comprehensive test table with multiple data types.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @param sqlSyntax  The SQL syntax to use (H2, POSTGRES, MYSQL, ORACLE, or SQLSERVER)
     * @throws SQLException if table creation fails
     */
    public static void createMultiTypeTestTable(Connection connection, String tableName, SqlSyntax sqlSyntax) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists with database-specific syntax
            if (sqlSyntax == SqlSyntax.ORACLE) {
                // Oracle needs special handling for DROP TABLE IF EXISTS
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                // SQL Server uses IF EXISTS syntax
                statement.execute("IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName);
            } else if (sqlSyntax == SqlSyntax.DB2) {
                // DB2 might not support IF EXISTS in older versions, so use try/catch
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
            } else {
                String dropTableSql = "DROP TABLE IF EXISTS " + tableName;
                statement.execute(dropTableSql);
            }

            // Create table with appropriate data type syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int INT NOT NULL," +
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE PRECISION," +
                        " val_bigint BIGINT," +
                        " val_tinyint TINYINT," +
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +
                        " val_decimal DECIMAL," +
                        " val_float FLOAT(2)," +
                        " val_byte BINARY," +
                        " val_binary BINARY(4)," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP," +
                        " val_localdatetime TIMESTAMP," +  // LocalDateTime
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIME," +  // LocalTime
                        " val_instant TIMESTAMP," +  // Instant
                        " val_offsetdatetime TIMESTAMP WITH TIME ZONE," +  // OffsetDateTime
                        " val_offsettime TIME WITH TIME ZONE)";  // OffsetTime
            } else if (sqlSyntax == SqlSyntax.POSTGRES || sqlSyntax == SqlSyntax.COCKROACHDB) {
                // PostgreSQL/CockroachDB syntax - adjust types for PostgreSQL/CockroachDB compatibility
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int INT NOT NULL," +
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE PRECISION," +
                        " val_bigint BIGINT," +
                        " val_tinyint SMALLINT," +  // PostgreSQL/CockroachDB doesn't have TINYINT, use SMALLINT
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +
                        " val_decimal DECIMAL," +
                        " val_float REAL," +  // PostgreSQL/CockroachDB uses REAL instead of FLOAT(2)
                        " val_byte BYTEA," +  // PostgreSQL/CockroachDB uses BYTEA instead of BINARY
                        " val_binary BYTEA," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP," +
                        " val_localdatetime TIMESTAMP," +  // LocalDateTime
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIME," +  // LocalTime
                        " val_instant TIMESTAMP," +  // Instant
                        " val_offsetdatetime TIMESTAMP WITH TIME ZONE," +  // OffsetDateTime
                        " val_offsettime TIME WITH TIME ZONE)";  // OffsetTime
            } else if (sqlSyntax == SqlSyntax.ORACLE) {
                // Oracle syntax - Oracle-specific types and adjustments
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int NUMBER(10) NOT NULL," +  // Oracle uses NUMBER instead of INT
                        " val_varchar VARCHAR2(50) NOT NULL," +  // Oracle uses VARCHAR2
                        " val_double_precision BINARY_DOUBLE," +  // Oracle's equivalent
                        " val_bigint NUMBER(19)," +  // Oracle uses NUMBER for BIGINT
                        " val_tinyint NUMBER(3)," +  // Oracle uses NUMBER for TINYINT
                        " val_smallint NUMBER(5)," +  // Oracle uses NUMBER for SMALLINT
                        " val_boolean NUMBER(1)," +  // Oracle uses NUMBER(1) for boolean (before 23c)
                        " val_decimal NUMBER," +
                        " val_float BINARY_FLOAT," +  // Oracle's floating point type
                        " val_byte RAW(1)," +  // Oracle uses RAW for binary data
                        " val_binary RAW(4)," +
                        " val_date DATE," +
                        " val_time TIMESTAMP," +  // Oracle DATE includes time, use TIMESTAMP for time-only
                        " val_timestamp TIMESTAMP," +
                        " val_localdatetime TIMESTAMP," +  // LocalDateTime
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIMESTAMP," +  // LocalTime (Oracle doesn't have TIME type)
                        " val_instant TIMESTAMP," +  // Instant
                        " val_offsetdatetime TIMESTAMP WITH TIME ZONE," +  // OffsetDateTime
                        " val_offsettime TIMESTAMP WITH TIME ZONE)";  // OffsetTime
            } else if (sqlSyntax == SqlSyntax.SQLSERVER) {
                // SQL Server syntax - SQL Server-specific types and adjustments
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int INT NOT NULL," +
                        " val_varchar NVARCHAR(50)," +  // SQL Server uses NVARCHAR for Unicode
                        " val_double_precision FLOAT," +  // SQL Server uses FLOAT for double precision
                        " val_bigint BIGINT," +
                        " val_tinyint TINYINT," +
                        " val_smallint SMALLINT," +
                        " val_boolean BIT," +  // SQL Server uses BIT for boolean
                        " val_decimal DECIMAL(10, 2)," +
                        " val_float REAL," +  // SQL Server uses REAL for single precision
                        " val_byte VARBINARY(1)," +  // SQL Server uses VARBINARY for binary data
                        " val_binary VARBINARY(4)," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp DATETIME2," +  // SQL Server uses DATETIME2 for timestamp
                        " val_localdatetime DATETIME2," +  // LocalDateTime
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIME," +  // LocalTime
                        " val_instant DATETIME2," +  // Instant
                        " val_offsetdatetime DATETIMEOFFSET," +  // OffsetDateTime
                        " val_offsettime DATETIMEOFFSET)";  // OffsetTime (SQL Server doesn't have TIME WITH TIME ZONE)
            } else if (sqlSyntax == SqlSyntax.DB2) {
                // DB2 syntax - DB2-specific types and adjustments
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int INTEGER NOT NULL," +  // DB2 uses INTEGER instead of INT
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE," +  // DB2 uses DOUBLE for double precision
                        " val_bigint BIGINT," +
                        " val_tinyint SMALLINT," +  // DB2 uses SMALLINT for TINYINT (no TINYINT in DB2)
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +  // DB2 has native BOOLEAN support
                        " val_decimal DECIMAL(10, 2)," +
                        " val_float REAL," +  // DB2 uses REAL for single precision float
                        " val_byte VARBINARY(1)," +  // DB2 uses VARBINARY for binary data
                        " val_binary VARBINARY(4)," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP," +
                        " val_localdatetime TIMESTAMP," +  // LocalDateTime
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIME," +  // LocalTime
                        " val_instant TIMESTAMP," +  // Instant
                        " val_offsetdatetime TIMESTAMP," +  // OffsetDateTime (DB2 doesn't have TIMESTAMP WITH TIME ZONE in all versions)
                        " val_offsettime TIMESTAMP)";  // OffsetTime
            } else { // MYSQL
                // MySQL syntax - MySQL specific types and adjustments
                createTableSql = "CREATE TABLE " + tableName + "(" +
                        " val_int INT NOT NULL," +
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE PRECISION," +
                        " val_bigint BIGINT," +
                        " val_tinyint TINYINT," +
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +
                        " val_decimal DECIMAL," +
                        " val_float FLOAT," +
                        " val_byte BINARY," +
                        " val_binary BINARY(4)," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP," +
                        " val_localdatetime DATETIME," +  // LocalDateTime - MySQL uses DATETIME
                        " val_localdate DATE," +  // LocalDate
                        " val_localtime TIME," +  // LocalTime
                        " val_instant TIMESTAMP," +  // Instant
                        " val_offsetdatetime TIMESTAMP," +  // OffsetDateTime (MySQL doesn't have timezone support in older versions)
                        " val_offsettime TIME)";  // OffsetTime
            }
            statement.execute(createTableSql);
        }
    }

    /**
     * Cleans up test tables by dropping them.
     *
     * @param connection The database connection
     * @param tableNames The names of the tables to drop
     */
    public static void cleanupTestTables(Connection connection, String... tableNames) {
        try (Statement statement = connection.createStatement()) {
            for (String tableName : tableNames) {
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore - table might not exist
                }
            }
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Validates that all rows in a ResultSet can be read without errors.
     * This is useful for testing metadata operations that return ResultSets.
     *
     * @param rs The ResultSet to validate
     * @throws SQLException if validation fails
     */
    public static void validateAllRows(ResultSet rs) throws SQLException {
        assertNotNull(rs, "ResultSet should not be null");
        ResultSetMetaData rsmd = rs.getMetaData();
        assertNotNull(rsmd, "ResultSetMetaData should not be null");
        int cols = rsmd.getColumnCount();
        assertTrue(cols >= 0, "Column count should be >= 0");

        int rowCount = 0;
        while (rs.next()) {
            for (int i = 1; i <= cols; i++) {
                // Always validate column value can be read (nulls are acceptable)
                try {
                    Object value = rs.getObject(i);
                    // If not null, assert string conversion doesn't throw
                    if (value != null) {
                        value.toString();
                    }
                } catch (Exception e) {
                    fail("Reading column " + i + " failed: " + e.getMessage());
                }
            }
            rowCount++;
            if (rowCount > 100) break; // Limit for test runtime
        }
        assertTrue(rowCount >= 0, "Row count should be >= 0");
    }

    /**
     * Executes an update statement and returns the affected row count.
     *
     * @param connection The database connection
     * @param sql        The SQL update statement
     * @return The number of affected rows
     * @throws SQLException if the update fails
     */
    public static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Creates a MySQL-specific test table with MySQL unique data types.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @throws SQLException if table creation fails
     */
    public static void createMySQLSpecificTestTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists
            String dropTableSql = "DROP TABLE IF EXISTS " + tableName;
            statement.execute(dropTableSql);

            // Create table with MySQL-specific data types
            String createTableSql = "CREATE TABLE " + tableName + "(" +
                    " id INT AUTO_INCREMENT PRIMARY KEY," +
                    " enum_col ENUM('small', 'medium', 'large')," +
                    " json_col JSON," +
                    " text_col TEXT," +
                    " mediumtext_col MEDIUMTEXT," +
                    " longtext_col LONGTEXT," +
                    " blob_col BLOB," +
                    " mediumblob_col MEDIUMBLOB," +
                    " longblob_col LONGBLOB," +
                    " set_col SET('option1', 'option2', 'option3')," +
                    " year_col YEAR," +
                    " bit_col BIT(8)" +
                    ")";
            statement.execute(createTableSql);
        }
    }

    /**
     * Creates a PostgreSQL-specific JSON/JSONB test table.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @throws SQLException if table creation fails
     */
    public static void createPostgresJsonTestTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tableName);
            statement.execute(
                    "CREATE TABLE " + tableName + "(" +
                    " id SERIAL PRIMARY KEY," +
                    " json_col JSON," +
                    " jsonb_col JSONB," +
                    " json_null_col JSON" +
                    ")"
            );
        }
    }

    /**
     * Creates a SQL Server-specific test table with SQL Server unique data types.
     *
     * @param connection The database connection
     * @param tableName  The name of the table to create
     * @throws SQLException if table creation fails
     */
    public static void createSqlServerSpecificTestTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists
            String dropTableSql = "IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName;
            statement.execute(dropTableSql);

            // Create table with SQL Server-specific data types
            String createTableSql = "CREATE TABLE " + tableName + "(" +
                    " id INT IDENTITY(1,1) PRIMARY KEY," +
                    " ntext_col NTEXT," +
                    " text_col TEXT," +
                    " image_col IMAGE," +
                    " xml_col XML," +
                    " money_col MONEY," +
                    " smallmoney_col SMALLMONEY," +
                    " uniqueidentifier_col UNIQUEIDENTIFIER," +
                    " geometry_col GEOMETRY," +
                    " geography_col GEOGRAPHY," +
                    " hierarchyid_col HIERARCHYID," +
                    " sql_variant_col SQL_VARIANT," +
                    " datetimeoffset_col DATETIMEOFFSET," +
                    " datetime2_col DATETIME2," +
                    " smalldatetime_col SMALLDATETIME" +
                    ")";
            statement.execute(createTableSql);
        }
    }

    /**
     * Safely closes database resources without throwing exceptions.
     *
     * @param autoCloseables The resources to close
     */
    public static void closeQuietly(AutoCloseable... autoCloseables) {
        for (AutoCloseable resource : autoCloseables) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }
}