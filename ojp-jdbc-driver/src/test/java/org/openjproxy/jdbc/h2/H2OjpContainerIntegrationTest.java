package org.openjproxy.jdbc.h2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjproxy.testcontainers.OjpContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple H2 integration test using OjpContainer to verify that the testcontainers
 * module works correctly with OJP Server and H2 database.
 */
class H2OjpContainerIntegrationTest {

    private static OjpContainer ojpContainer;
    private static String ojpConnectionString;

    @BeforeAll
    static void setupClass() {
        // Start OJP container
        ojpContainer = new OjpContainer();
        ojpContainer.start();

        // Get the connection string for OJP
        ojpConnectionString = ojpContainer.getOjpConnectionString();
        System.out.println("OJP Container started at: " + ojpConnectionString);
    }

    @AfterAll
    static void teardownClass() {
        if (ojpContainer != null) {
            ojpContainer.stop();
        }
    }

    @Test
    void testSimpleH2QueryThroughOjpContainer() throws SQLException {
        // Build JDBC URL with OJP connection string: jdbc:ojp[host:port]_h2:~/test
        // JDBC driver auto-registers when on classpath (JDBC 4.0+)
        String jdbcUrl = "jdbc:ojp[" + ojpConnectionString + "]_h2:~/testojp";

        // Connect through OJP to H2
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            assertNotNull(conn, "Connection should not be null");
            assertFalse(conn.isClosed(), "Connection should be open");

            // Create a simple test table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, name VARCHAR(100))");
            }

            // Insert test data
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO test_table (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "TestValue1");
                int rowsInserted = ps.executeUpdate();
                assertEquals(1, rowsInserted, "Should insert 1 row");

                ps.setInt(1, 2);
                ps.setString(2, "TestValue2");
                rowsInserted = ps.executeUpdate();
                assertEquals(1, rowsInserted, "Should insert 1 row");
            }

            // Query the data
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM test_table WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "ResultSet should have at least one row");
                    assertEquals(1, rs.getInt("id"), "ID should be 1");
                    assertEquals("TestValue1", rs.getString("name"), "Name should be TestValue1");
                    assertFalse(rs.next(), "ResultSet should have only one row");
                }
            }

            // Query all data
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM test_table")) {
                assertTrue(rs.next(), "ResultSet should have a count");
                assertEquals(2, rs.getInt("cnt"), "Should have 2 rows");
            }

            // Clean up - drop table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE test_table");
            }

            System.out.println("H2 OjpContainer integration test passed successfully!");
        }
    }

    @Test
    void testOjpContainerConnectionInfo() {
        assertNotNull(ojpContainer, "OJP container should not be null");
        assertTrue(ojpContainer.isRunning(), "OJP container should be running");

        String host = ojpContainer.getOjpHost();
        Integer port = ojpContainer.getOjpPort();
        String connectionString = ojpContainer.getOjpConnectionString();

        assertNotNull(host, "Host should not be null");
        assertNotNull(port, "Port should not be null");
        assertNotNull(connectionString, "Connection string should not be null");
        assertTrue(port > 0, "Port should be positive");
        assertTrue(connectionString.contains(":"), "Connection string should contain :");
        assertEquals(host + ":" + port, connectionString, "Connection string format should be host:port");

        System.out.println("OJP Container info - Host: " + host + ", Port: " + port);
    }
}
