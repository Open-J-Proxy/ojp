package org.openjproxy.jdbc.db2;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * DB2-specific DatabaseMetaData tests.
 * Tests DB2-specific metadata functionality and behavior.
 */
 class Db2DatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private Connection connection;
    private DatabaseMetaData metaData;

    @BeforeAll
     static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @SneakyThrows
     void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        connection = DriverManager.getConnection(url, user, pwd);
        metaData = connection.getMetaData();
        
        // Create test table for metadata tests
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE db2_metadata_test");
        } catch (SQLException e) {
            // Table doesn't exist
        }
        
        stmt.execute("CREATE TABLE db2_metadata_test (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "name VARCHAR(100) NOT NULL, " +
                "age INTEGER, " +
                "salary DECIMAL(10,2), " +
                "is_active SMALLINT, " +
                "created_date DATE, " +
                "notes CLOB(1M))");
        
        // Create index for testing
        stmt.execute("CREATE INDEX idx_db2_metadata_name ON db2_metadata_test (name)");
        stmt.close();
    }

    @AfterEach
     void tearDown() throws SQLException {
        if (connection != null) {
            try {
                Statement stmt = connection.createStatement();
                stmt.execute("DROP INDEX idx_db2_metadata_name");
                stmt.execute("DROP TABLE db2_metadata_test");
                stmt.close();
            } catch (SQLException e) {
                // Ignore
            }
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2DatabaseInfo(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test basic database information
        String productName = metaData.getDatabaseProductName();
        assertNotNull(productName);
        assertTrue(productName.toLowerCase().contains("db2"));

        String productVersion = metaData.getDatabaseProductVersion();
        assertNotNull(productVersion);

        String driverName = metaData.getDriverName();
        assertNotNull(driverName);

        String driverVersion = metaData.getDriverVersion();
        assertNotNull(driverVersion);

        // Test JDBC compliance
        int majorVersion = metaData.getJDBCMajorVersion();
        int minorVersion = metaData.getJDBCMinorVersion();
        assertTrue(majorVersion >= 4); // Should support at least JDBC 4.0

        System.out.println("DB2 Database Product: " + productName + " " + productVersion);
        System.out.println("DB2 Driver: " + driverName + " " + driverVersion);
        System.out.println("JDBC Version: " + majorVersion + "." + minorVersion);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2TableMetaData(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test table information
        ResultSet tables = metaData.getTables(null, null, "DB2_METADATA_TEST", new String[]{"TABLE"});
        assertTrue(tables.next());
        
        String tableName = tables.getString("TABLE_NAME");
        assertEquals("DB2_METADATA_TEST", tableName.toUpperCase());
        
        String tableType = tables.getString("TABLE_TYPE");
        assertEquals("TABLE", tableType);
        
        assertFalse(tables.next()); // Should only have one result
        tables.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2ColumnMetaData(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test column information
        ResultSet columns = metaData.getColumns(null, null, "DB2_METADATA_TEST", null);
        
        boolean foundId = false, foundName = false, foundAge = false, foundSalary = false;
        boolean foundIsActive = false, foundCreatedDate = false, foundNotes = false;
        
        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            int dataType = columns.getInt("DATA_TYPE");
            String typeName = columns.getString("TYPE_NAME");
            int columnSize = columns.getInt("COLUMN_SIZE");
            int nullable = columns.getInt("NULLABLE");
            
            switch (columnName.toUpperCase()) {
                case "ID":
                    foundId = true;
                    assertEquals(Types.INTEGER, dataType);
                    assertEquals(DatabaseMetaData.columnNoNulls, nullable);
                    break;
                case "NAME":
                    foundName = true;
                    assertEquals(Types.VARCHAR, dataType);
                    assertEquals(100, columnSize);
                    assertEquals(DatabaseMetaData.columnNoNulls, nullable);
                    break;
                case "AGE":
                    foundAge = true;
                    assertEquals(Types.INTEGER, dataType);
                    assertEquals(DatabaseMetaData.columnNullable, nullable);
                    break;
                case "SALARY":
                    foundSalary = true;
                    assertEquals(Types.DECIMAL, dataType);
                    assertEquals(DatabaseMetaData.columnNullable, nullable);
                    break;
                case "IS_ACTIVE":
                    foundIsActive = true;
                    assertEquals(Types.SMALLINT, dataType);
                    break;
                case "CREATED_DATE":
                    foundCreatedDate = true;
                    assertEquals(Types.DATE, dataType);
                    break;
                case "NOTES":
                    foundNotes = true;
                    assertEquals(Types.CLOB, dataType);
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + columnName.toUpperCase());
            }
        }
        
        assertTrue(foundId);
        assertTrue(foundName);
        assertTrue(foundAge);
        assertTrue(foundSalary);
        assertTrue(foundIsActive);
        assertTrue(foundCreatedDate);
        assertTrue(foundNotes);
        
        columns.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2PrimaryKeyMetaData(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test primary key information
        ResultSet primaryKeys = metaData.getPrimaryKeys(null, null, "DB2_METADATA_TEST");
        assertTrue(primaryKeys.next());
        
        String columnName = primaryKeys.getString("COLUMN_NAME");
        assertEquals("ID", columnName.toUpperCase());
        
        String pkName = primaryKeys.getString("PK_NAME");
        assertNotNull(pkName);
        
        assertFalse(primaryKeys.next()); // Should only have one primary key column
        primaryKeys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2IndexMetaData(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test index information
        ResultSet indexes = metaData.getIndexInfo(null, null, "DB2_METADATA_TEST", false, false);
        
        boolean foundNameIndex = false;
        boolean foundPrimaryKeyIndex = false;
        
        while (indexes.next()) {
            String indexName = indexes.getString("INDEX_NAME");
            String columnName = indexes.getString("COLUMN_NAME");
            boolean nonUnique = indexes.getBoolean("NON_UNIQUE");
            
            if (indexName != null) {
                if (indexName.toUpperCase().contains("IDX_DB2_METADATA_NAME")) {
                    foundNameIndex = true;
                    assertEquals("NAME", columnName.toUpperCase());
                    assertTrue(nonUnique); // Our index is not unique
                } else if (columnName != null && columnName.equalsIgnoreCase("ID")) {
                    foundPrimaryKeyIndex = true;
                    assertFalse(nonUnique); // Primary key index is unique
                }
            }
        }
        
        assertTrue(foundNameIndex);
        assertTrue(foundPrimaryKeyIndex);
        
        indexes.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2DatabaseCapabilities(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test various database capabilities
        assertTrue(metaData.supportsTransactions());
        assertTrue(metaData.supportsSelectForUpdate());
        assertTrue(metaData.supportsStoredProcedures());
        assertTrue(metaData.supportsSubqueriesInExists());
        assertTrue(metaData.supportsSubqueriesInIns());
        assertTrue(metaData.supportsUnion());
        assertTrue(metaData.supportsUnionAll());
        
        // Test transaction isolation levels
        assertTrue(metaData.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(metaData.supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ));
        
        // Test result set capabilities
        assertTrue(metaData.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(metaData.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        
        System.out.println("DB2 supports transactions: " + metaData.supportsTransactions());
        System.out.println("DB2 supports stored procedures: " + metaData.supportsStoredProcedures());
        System.out.println("DB2 max connections: " + metaData.getMaxConnections());
        System.out.println("DB2 max table name length: " + metaData.getMaxTableNameLength());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2SqlKeywords(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        // Test SQL keywords
        String keywords = metaData.getSQLKeywords();
        assertNotNull(keywords);
        assertFalse(keywords.isEmpty());
        
        // Test identifier quote string
        String quoteString = metaData.getIdentifierQuoteString();
        assertNotNull(quoteString);
        
        // Test search string escape
        String searchStringEscape = metaData.getSearchStringEscape();
        assertNotNull(searchStringEscape);
        
        System.out.println("DB2 identifier quote: " + quoteString);
        System.out.println("DB2 search escape: " + searchStringEscape);
    }
}