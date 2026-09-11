package org.openjproxy.jdbc.oracle;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Oracle-specific ResultSet tests.
 * Tests Oracle-specific ResultSet behavior and data type handling.
 */
public class OracleResultSetTest {

    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");

        // Create Oracle database connection
        connection = DriverManager.getConnection(url, user, pwd);

        // Create a scrollable and updatable Statement
        statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, // Scrollable ResultSet
                ResultSet.CONCUR_UPDATABLE         // Updatable ResultSet
        );

        // Create a test table and insert data
        try {
            statement.execute("DROP TABLE oracle_resultset_test_table");
        } catch (Exception e) {
            //Expected if table does not exist.
        }
        
        // Create table with Oracle-specific data types
        statement.execute("CREATE TABLE oracle_resultset_test_table (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "name VARCHAR2(255), " +
                "age NUMBER(10), " +
                "salary NUMBER(10,2), " +
                "active NUMBER(1), " +
                "created_at TIMESTAMP)");
        
        statement.execute("INSERT INTO oracle_resultset_test_table (id, name, age, salary, active, created_at) " +
                "VALUES (1, 'Alice', 30, 50000.00, 1, CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO oracle_resultset_test_table (id, name, age, salary, active, created_at) " +
                "VALUES (2, 'Bob', 25, 45000.00, 0, CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO oracle_resultset_test_table (id, name, age, salary, active, created_at) " +
                "VALUES (3, 'Charlie', 35, 55000.00, 1, CURRENT_TIMESTAMP)");

        // Query the data with a scrollable ResultSet
        resultSet = statement.executeQuery("SELECT * FROM oracle_resultset_test_table ORDER BY id");
    }

    @AfterEach
    void tearDown() throws SQLException {
        // Clean up resources
        if (resultSet != null) resultSet.close();
        if (statement != null) statement.close();
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleNavigationMethods(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        assertTrue(resultSet.next()); // Row 1
        assertTrue(resultSet.next()); // Row 2
        assertTrue(resultSet.previous()); // Back to Row 1
        assertTrue(resultSet.last()); // Move to the last row
        assertTrue(resultSet.isLast());
        assertTrue(resultSet.first()); // Back to the first row
        assertTrue(resultSet.isFirst());
        assertFalse(resultSet.isAfterLast());
        assertFalse(resultSet.isBeforeFirst());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleDataRetrievalMethods(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.next();
        assertEquals(1, resultSet.getInt("id"));
        assertEquals("Alice", resultSet.getString("name"));
        assertEquals(30, resultSet.getInt("age"));
        assertEquals(50000.00, resultSet.getDouble("salary"));
        assertEquals(1, resultSet.getInt("active")); // Oracle uses NUMBER(1) for boolean
        assertNotNull(resultSet.getDate("created_at"));
        assertNotNull(resultSet.getTimestamp("created_at"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleGetMethodsByColumnIndex(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.next();
        assertEquals(1, resultSet.getInt(1)); // id
        assertEquals("Alice", resultSet.getString(2)); // name
        assertEquals(30, resultSet.getInt(3)); // age
        assertEquals(50000.00, resultSet.getDouble(4)); // salary
        assertEquals(1, resultSet.getInt(5)); // active (Oracle NUMBER(1))
        assertNotNull(resultSet.getDate(6)); // created_at
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleNullHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        statement.execute("INSERT INTO oracle_resultset_test_table (id, name, age, salary, active, created_at) " +
                "VALUES (5, NULL, NULL, NULL, NULL, NULL)");
        resultSet = statement.executeQuery("SELECT * FROM oracle_resultset_test_table WHERE id = 5");
        assertTrue(resultSet.next());
        assertNull(resultSet.getString("name"));
        assertTrue(resultSet.wasNull());
        assertEquals(0, resultSet.getInt("age")); // Oracle returns 0 for NULL numbers
        assertTrue(resultSet.wasNull());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleCursorPositionMethods(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        assertTrue(resultSet.first());
        assertFalse(resultSet.isBeforeFirst());
        assertFalse(resultSet.isAfterLast());
        assertTrue(resultSet.last());
        resultSet.afterLast();
        assertFalse(resultSet.next());
        resultSet.beforeFirst();
        assertTrue(resultSet.next());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleWarnings(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        SQLWarning warning = resultSet.getWarnings();
        // Oracle may or may not have warnings, just check it doesn't throw
        assertNotNull(resultSet); // Basic validation
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleAdvancedNavigation(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.absolute(2); // Move to the second row
        assertEquals("Bob", resultSet.getString("name"));

        resultSet.relative(1); // Move to the third row
        assertEquals("Charlie", resultSet.getString("name"));

        resultSet.relative(-2); // Move back to the first row
        assertEquals("Alice", resultSet.getString("name"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleSpecificDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        // Create table with Oracle-specific data types
        try {
            statement.execute("DROP TABLE oracle_specific_types_test");
        } catch (Exception e) {
            // Ignore
        }
        
        statement.execute("CREATE TABLE oracle_specific_types_test (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "varchar2_col VARCHAR2(100), " +
                "char_col CHAR(10), " +
                "number_col NUMBER(19,4), " +
                "binary_double_col BINARY_DOUBLE, " +
                "binary_float_col BINARY_FLOAT, " +
                "raw_col RAW(100), " +
                "clob_col CLOB)");
        
        statement.execute("INSERT INTO oracle_specific_types_test " +
                "(id, varchar2_col, char_col, number_col, binary_double_col, binary_float_col, raw_col, clob_col) " +
                "VALUES (1, 'Oracle VARCHAR2', 'CHAR10    ', 12345.6789, 123.456, 78.9, " +
                "HEXTORAW('48656C6C6F'), 'CLOB content')");
        
        ResultSet rs = statement.executeQuery("SELECT * FROM oracle_specific_types_test WHERE id = 1");
        assertTrue(rs.next());
        
        assertEquals("Oracle VARCHAR2", rs.getString("varchar2_col"));
        assertEquals("CHAR10    ", rs.getString("char_col")); // CHAR is padded
        assertEquals(12345.6789, rs.getDouble("number_col"), 0.0001);
        assertEquals(123.456, rs.getDouble("binary_double_col"), 0.001);
        assertEquals(78.9, rs.getFloat("binary_float_col"), 0.1);
        assertNotNull(rs.getBytes("raw_col"));
        assertEquals("CLOB content", rs.getString("clob_col"));
        
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleResultSetMetadata(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        java.sql.ResultSetMetaData metadata = resultSet.getMetaData();
        
        assertNotNull(metadata);
        assertTrue(metadata.getColumnCount() >= 6);
        
        // Verify Oracle-specific type mappings
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String columnName = metadata.getColumnName(i);
            String typeName = metadata.getColumnTypeName(i);
            
            if ("id".equalsIgnoreCase(columnName) || "age".equalsIgnoreCase(columnName)) {
                assertEquals("NUMBER", typeName);
            } else if ("name".equalsIgnoreCase(columnName)) {
                assertEquals("VARCHAR2", typeName);
            } else if ("salary".equalsIgnoreCase(columnName)) {
                assertEquals("NUMBER", typeName);
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleRowCounting(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        // Count rows by iterating
        int count = 0;
        resultSet.beforeFirst();
        while (resultSet.next()) {
            count++;
        }
        assertEquals(3, count);
        
        // Test getRow() method
        resultSet.absolute(2);
        assertEquals(2, resultSet.getRow());
        
        resultSet.last();
        assertTrue(resultSet.getRow() >= 3);
    }
}