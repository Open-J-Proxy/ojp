package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific ResultSetMetaData integration tests.
 * Tests SQL Server-specific metadata functionality for result sets.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
class SQLServerResultSetMetaDataExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerResultSetMetaDataBasics(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server ResultSetMetaData basics for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_rsmd_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rsmd_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        // Test basic metadata properties
        int columnCount = rsmd.getColumnCount();
        assertTrue(columnCount > 0, "Column count should be > 0");

        // Test each column's metadata
        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);
            assertNotNull("Column name should not be null", columnName);
            assertTrue(columnName.length() > 0, "Column name should not be empty");

            String columnTypeName = rsmd.getColumnTypeName(i);
            assertNotNull("Column type name should not be null", columnTypeName);

            int columnType = rsmd.getColumnType(i);
            assertTrue(columnType != 0, "Column type should be valid SQL type");

            String columnClassName = rsmd.getColumnClassName(i);
            assertNotNull("Column class name should not be null", columnClassName);

            int precision = rsmd.getPrecision(i);
            assertTrue(precision >= 0, "Precision should be >= 0");

            int scale = rsmd.getScale(i);
            assertTrue(scale >= 0, "Scale should be >= 0");
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rsmd_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server specific data types metadata for url -> " + url);

        // Create table with SQL Server-specific types
        TestDBUtils.createSqlServerSpecificTestTable(conn, "sqlserver_specific_rsmd_test");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_specific_rsmd_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();

        // Check specific SQL Server types
        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);
            String columnTypeName = rsmd.getColumnTypeName(i);
            int columnType = rsmd.getColumnType(i);

            switch (columnName.toLowerCase()) {
                case "id":
                    assertEquals("int", columnTypeName.toLowerCase());
                    assertEquals(Types.INTEGER, columnType);
                    break;
                case "ntext_col":
                    assertTrue(columnTypeName.toLowerCase().contains("ntext") ||
                            columnTypeName.toLowerCase().contains("nvarchar"), "NTEXT type should be recognized");
                    break;
                case "money_col":
                    assertTrue(columnTypeName.toLowerCase().contains("money"), "MONEY type should be recognized");
                    break;
                case "uniqueidentifier_col":
                    assertTrue(columnTypeName.toLowerCase().contains("uniqueidentifier"), "UNIQUEIDENTIFIER type should be recognized");
                    break;
                case "datetimeoffset_col":
                    assertTrue(columnTypeName.toLowerCase().contains("datetimeoffset"), "DATETIMEOFFSET type should be recognized");
                    break;
                case "datetime2_col":
                    assertTrue(
                            columnTypeName.toLowerCase().contains("datetime2"), "DATETIME2 type should be recognized");
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_specific_rsmd_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerColumnProperties(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server column properties for url -> " + url);

        // Create a table with specific column properties
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_column_props_test', 'U') IS NOT NULL DROP TABLE sqlserver_column_props_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_column_props_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "required_col NVARCHAR(50) NOT NULL, " +
                "optional_col NVARCHAR(100) NULL, " +
                "readonly_col AS (id * 2), " +  // Computed column
                "decimal_col DECIMAL(10,2) NOT NULL, " +
                "max_col NVARCHAR(MAX))");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_column_props_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);

            switch (columnName.toLowerCase()) {
                case "id":
                    assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    assertTrue(rsmd.isAutoIncrement(i), "ID should be auto increment");
                    break;
                case "required_col":
                    assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    assertFalse(rsmd.isAutoIncrement(i), "Required column should not be auto increment");
                    break;
                case "optional_col":
                    assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(i));
                    break;
                case "readonly_col":
                    assertTrue(rsmd.isReadOnly(i), "Computed column should be read-only");
                    break;
                case "decimal_col":
                    assertEquals(10, rsmd.getPrecision(i));
                    assertEquals(2, rsmd.getScale(i));
                    break;
                case "max_col":
                    // SQL Server MAX columns have very large precision
                    assertTrue(rsmd.getPrecision(i) > 1000, "MAX column should have large precision");
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_column_props_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerTableAndSchemaInfo(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server table and schema info for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_table_info_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_table_info_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            // Test catalog and schema information
            String catalogName = rsmd.getCatalogName(i);
            String schemaName = rsmd.getSchemaName(i);
            String tableName = rsmd.getTableName(i);

            // SQL Server should provide table information
            assertNotNull("Table name should not be null", tableName);
            if (tableName.length() > 0) {
                assertEquals("sqlserver_table_info_test", tableName.toLowerCase());
            }

            // Schema name should be available (might be 'dbo' or user schema)
            if (schemaName != null && schemaName.length() > 0) {
                assertTrue(schemaName.length() > 0, "Schema name should be valid");
            }

            // Test display size
            int displaySize = rsmd.getColumnDisplaySize(i);
            assertTrue(displaySize > 0, "Display size should be > 0");

            // Test searchable property
            boolean isSearchable = rsmd.isSearchable(i);
            // Most columns should be searchable
            assertTrue(isSearchable, "Column should be searchable");
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_table_info_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerAliasedColumns(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server aliased columns for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_alias_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        PreparedStatement ps = conn.prepareStatement(
                "SELECT id AS identifier, name AS full_name, " +
                        "UPPER(name) AS upper_name, " +
                        "LEN(name) AS name_length " +
                        "FROM sqlserver_alias_test"
        );
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        assertEquals(4, rsmd.getColumnCount());

        // Test aliased column names
        assertEquals("identifier", rsmd.getColumnLabel(1));
        assertEquals("full_name", rsmd.getColumnLabel(2));
        assertEquals("upper_name", rsmd.getColumnLabel(3));
        assertEquals("name_length", rsmd.getColumnLabel(4));

        // Test original column names (where applicable)
        assertEquals("identifier", rsmd.getColumnName(1));
        assertEquals("full_name", rsmd.getColumnName(2));
        // Computed columns might not have original names

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_alias_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNullabilityAndUpdatability(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server nullability and updatability for url -> " + url);

        // Create table with various constraints
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_constraints_test', 'U') IS NOT NULL DROP TABLE sqlserver_constraints_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_constraints_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "not_null_col NVARCHAR(50) NOT NULL, " +
                "nullable_col NVARCHAR(50) NULL, " +
                "computed_col AS (id + 100), " +
                "default_col NVARCHAR(50) DEFAULT 'default_value')");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_constraints_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
            String columnName = rsmd.getColumnName(i);

            switch (columnName.toLowerCase()) {
                case "id":
                    assertEquals(
                            ResultSetMetaData.columnNoNulls, rsmd.isNullable(i), "Primary key should not allow nulls");
                    assertTrue(rsmd.isAutoIncrement(i), "Identity column should be auto-increment");
                    break;
                case "not_null_col":
                    assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(i), "NOT NULL column should not allow nulls");
                    break;
                case "nullable_col":
                    assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(i), "Nullable column should allow nulls");
                    break;
                case "computed_col":
                    assertTrue(rsmd.isReadOnly(i), "Computed column should be read-only");
                    break;
                case "default_col":
                    assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(i), "Column with default should allow nulls");
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_constraints_test");
        conn.close();
    }
}