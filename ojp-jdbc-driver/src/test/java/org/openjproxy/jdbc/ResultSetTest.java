package org.openjproxy.jdbc;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

public class ResultSetTest {

    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    private static boolean isH2TestEnabled;
    private static boolean isPostgresTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        // Skip H2 tests if not enabled
        if (url.toLowerCase().contains("_h2:") && !isH2TestEnabled) {
            Assumptions.assumeFalse(true, "Skipping H2 tests");
        }
        // Skip PostgreSQL tests if not enabled
        if (url.contains("postgresql") && !isPostgresTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping Postgres tests");
        }

        // Create an in-memory H2 database connection
        connection = DriverManager.getConnection(url, user, pwd);

        // Create a scrollable and read-only Statement
        statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, // Scrollable ResultSet
                ResultSet.CONCUR_UPDATABLE         // Read-only ResultSet
        );

        // Create a test table and insert data
        try {
            statement.execute("DROP TABLE resultset_test_table");
        } catch (Exception e) {
            //Expected if table does not exist.
        }
        
        // Create table for H2/PostgreSQL
        statement.execute("CREATE TABLE resultset_test_table (id INT PRIMARY KEY, name VARCHAR(255), age INT, salary DECIMAL(10,2), active BOOLEAN, created_at TIMESTAMP)");
        statement.execute("INSERT INTO resultset_test_table (id, name, age, salary, active, created_at) VALUES (1, 'Alice', 30, 50000.00, TRUE, CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO resultset_test_table (id, name, age, salary, active, created_at) VALUES (2, 'Bob', 25, 45000.00, FALSE, CURRENT_TIMESTAMP)");
        statement.execute("INSERT INTO resultset_test_table (id, name, age, salary, active, created_at) VALUES (3, 'Charlie', 35, 55000.00, TRUE, CURRENT_TIMESTAMP)");

        // Query the data with a scrollable ResultSet
        resultSet = statement.executeQuery("SELECT * FROM resultset_test_table");
    }

    @AfterEach
    void tearDown() throws SQLException {
        // Clean up resources
        if (resultSet != null) resultSet.close();
        if (statement != null) statement.close();
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testNavigationMethods(String driverClass, String url, String user, String pwd) throws SQLException {
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
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testDataRetrievalMethods(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.next();
        assertEquals(1, resultSet.getInt("id"));
        assertEquals("Alice", resultSet.getString("name"));
        assertEquals(30, resultSet.getInt("age"));
        assertEquals(50000.00, resultSet.getDouble("salary"));
        assertTrue(resultSet.getBoolean("active"));
        assertNotNull(resultSet.getDate("created_at"));
        assertNotNull(resultSet.getTimestamp("created_at"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testGetMethodsByColumnIndex(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.next();
        assertEquals(1, resultSet.getInt(1)); // id
        assertEquals("Alice", resultSet.getString(2)); // name
        assertEquals(30, resultSet.getInt(3)); // age
        assertEquals(50000.00, resultSet.getDouble(4)); // salary
        assertTrue(resultSet.getBoolean(5)); // active
        assertNotNull(resultSet.getDate(6)); // created_at
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testNullHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        statement.execute("INSERT INTO resultset_test_table (id, name, age, salary, active, created_at) VALUES (5, NULL, NULL, NULL, NULL, NULL)");
        resultSet = statement.executeQuery("SELECT * FROM resultset_test_table WHERE id = 5");
        assertTrue(resultSet.next());
        assertNull(resultSet.getString("name"));
        assertTrue(resultSet.wasNull());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testUpdateMethods(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.moveToInsertRow();
        resultSet.updateInt("id", 4);
        resultSet.updateString("name", "David");
        resultSet.updateInt("age", 40);
        resultSet.updateDouble("salary", 60000.00);
        resultSet.updateBoolean("active", false);
        resultSet.insertRow();

        resultSet = statement.executeQuery("SELECT * FROM resultset_test_table WHERE id = 4");
        assertTrue(resultSet.next());
        assertEquals("David", resultSet.getString("name"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testCursorPositionMethods(String driverClass, String url, String user, String pwd) throws SQLException {
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
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testWarnings(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        SQLWarning warning = resultSet.getWarnings();
        assertNull(warning); // No warnings for this ResultSet
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void testAdvancedNavigation(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        resultSet.absolute(2); // Move to the second row
        assertEquals("Bob", resultSet.getString("name"));

        resultSet.relative(1); // Move to the third row
        assertEquals("Charlie", resultSet.getString("name"));

        resultSet.relative(-2); // Move back to the first row
        assertEquals("Alice", resultSet.getString("name"));
    }
}