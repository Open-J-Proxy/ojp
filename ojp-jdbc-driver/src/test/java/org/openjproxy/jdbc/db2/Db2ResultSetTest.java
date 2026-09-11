package org.openjproxy.jdbc.db2;

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
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * DB2-specific ResultSet tests.
 * Tests DB2-specific ResultSet behavior and data type handling.
 */
public class Db2ResultSetTest {

    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        // Create DB2 database connection
        connection = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = connection.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        // Create a scrollable and updatable Statement
        statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, // Scrollable ResultSet
                ResultSet.CONCUR_UPDATABLE         // Updatable ResultSet
        );

        // Create test table with DB2-compatible syntax
        try {
            statement.execute("DROP TABLE DB2INST1.db2_resultset_test");
        } catch (SQLException e) {
            // Table doesn't exist
        }

        statement.execute("CREATE TABLE DB2INST1.db2_resultset_test (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "name VARCHAR(100), " +
                "age INTEGER, " +
                "salary DECIMAL(10,2), " +
                "is_active SMALLINT)");

        // Insert test data
        statement.execute("INSERT INTO DB2INST1.db2_resultset_test VALUES (1, 'Alice', 25, 50000.00, true)");
        statement.execute("INSERT INTO DB2INST1.db2_resultset_test VALUES (2, 'Bob', 30, 60000.00, false)");
        statement.execute("INSERT INTO DB2INST1.db2_resultset_test VALUES (3, 'Charlie', 35, 70000.00, true)");
        statement.execute("INSERT INTO DB2INST1.db2_resultset_test VALUES (4, 'Diana', 28, 55000.00, false)");
        statement.execute("INSERT INTO DB2INST1.db2_resultset_test VALUES (5, 'Eve', 32, 65000.00, true)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (resultSet != null) {
            resultSet.close();
        }
        if (statement != null) {
            try {
                statement.execute("DROP TABLE DB2INST1.db2_resultset_test");
            } catch (SQLException e) {
                // Ignore
            }
            statement.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetNavigation(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        resultSet = statement.executeQuery("SELECT id, name, age FROM DB2INST1.db2_resultset_test ORDER BY id");

        // Test forward navigation
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("id"));
        assertEquals("Alice", resultSet.getString("name"));

        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("id"));
        assertEquals("Bob", resultSet.getString("name"));

        assertTrue(resultSet.next());
        assertEquals(3, resultSet.getInt("id"));
        assertEquals("Charlie", resultSet.getString("name"));

        assertTrue(resultSet.next());
        assertEquals(4, resultSet.getInt("id"));
        assertEquals("Diana", resultSet.getString("name"));

        assertTrue(resultSet.next());
        assertEquals(5, resultSet.getInt("id"));
        assertEquals("Eve", resultSet.getString("name"));

        // Test isFirst() and isLast()
        assertFalse(resultSet.isFirst());
        assertTrue(resultSet.isLast());

        // Navigate to last record
        assertTrue(resultSet.last());
        assertEquals(5, resultSet.getInt("id"));
        assertEquals("Eve", resultSet.getString("name"));
        assertTrue(resultSet.isLast());

        // Navigate to first record
        assertTrue(resultSet.first());
        assertEquals(1, resultSet.getInt("id"));
        assertEquals("Alice", resultSet.getString("name"));
        assertTrue(resultSet.isFirst());

        // Test absolute positioning
        assertTrue(resultSet.absolute(3));
        assertEquals(3, resultSet.getInt("id"));
        assertEquals("Charlie", resultSet.getString("name"));

        // Test relative positioning
        assertTrue(resultSet.relative(1));
        assertEquals(4, resultSet.getInt("id"));
        assertEquals("Diana", resultSet.getString("name"));

        assertTrue(resultSet.relative(-2));
        assertEquals(2, resultSet.getInt("id"));
        assertEquals("Bob", resultSet.getString("name"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        resultSet = statement.executeQuery("SELECT * FROM DB2INST1.db2_resultset_test WHERE id = 1");
        assertTrue(resultSet.next());

        // Test various data type retrievals
        assertEquals(1, resultSet.getInt("id"));
        assertEquals(1, resultSet.getInt(1));
        assertEquals("1", resultSet.getString("id"));

        assertEquals("Alice", resultSet.getString("name"));
        assertEquals("Alice", resultSet.getString(2));

        assertEquals(25, resultSet.getInt("age"));
        assertEquals(25, resultSet.getInt(3));

        assertEquals(50000.00, resultSet.getDouble("salary"), 0.01);
        assertEquals(50000.00, resultSet.getDouble(4), 0.01);

        assertEquals(1, resultSet.getInt("is_active"));
        assertEquals(1, resultSet.getInt(5));

        // Test wasNull()
        assertFalse(resultSet.wasNull());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetMetaData(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        resultSet = statement.executeQuery("SELECT * FROM DB2INST1.db2_resultset_test LIMIT 1");
        
        var metaData = resultSet.getMetaData();
        assertNotNull(metaData);

        // Test column count
        assertEquals(5, metaData.getColumnCount());

        // Test column names
        assertEquals("ID", metaData.getColumnName(1).toUpperCase());
        assertEquals("NAME", metaData.getColumnName(2).toUpperCase());
        assertEquals("AGE", metaData.getColumnName(3).toUpperCase());
        assertEquals("SALARY", metaData.getColumnName(4).toUpperCase());
        assertEquals("IS_ACTIVE", metaData.getColumnName(5).toUpperCase());

        // Test column types
        assertEquals(Types.INTEGER, metaData.getColumnType(1));
        assertEquals(Types.VARCHAR, metaData.getColumnType(2));
        assertEquals(Types.INTEGER, metaData.getColumnType(3));
        assertEquals(Types.DECIMAL, metaData.getColumnType(4));
        assertEquals(Types.SMALLINT, metaData.getColumnType(5));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetConcurrency(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        resultSet = statement.executeQuery("SELECT * FROM DB2INST1.db2_resultset_test WHERE id = 1");
        assertTrue(resultSet.next());

        // Test concurrency type
        assertEquals(ResultSet.CONCUR_READ_ONLY, resultSet.getConcurrency());

        // Test type
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, resultSet.getType());

    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetWarnings(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        resultSet = statement.executeQuery("SELECT * FROM DB2INST1.db2_resultset_test LIMIT 1");
        
        // Test warnings (initially should be null)
        SQLWarning warning = resultSet.getWarnings();
        // Don't assert null as some drivers may have warnings
        
        // Clear warnings
        resultSet.clearWarnings();
        
        // After clearing, warnings should be null
        warning = resultSet.getWarnings();
        assertNull(warning);
    }
}