package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Db2MultinodeIntegrationTest {

    private static String jdbcUrl;
    private static String user;
    private static String password;
    private static String[] endpoints;

    @BeforeAll
    static void configure() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("ojp.db2.multinode.tests.enabled", "false")),
                "DB2 multinode tests are disabled");
        jdbcUrl = requiredProperty("ojp.db2.multinode.url");
        user = requiredProperty("ojp.db2.user");
        password = requiredProperty("ojp.db2.password");
        endpoints = requiredProperty("ojp.db2.multinode.endpoints").split(",");
    }

    @Test
    void shouldCompleteCrudThroughEveryHealthyEndpoint() throws SQLException {
        String tableName = tableName();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName
                    + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)");

            for (int i = 0; i < endpoints.length; i++) {
                try (Connection endpointConnection = DriverManager.getConnection(
                        urlForEndpoint(jdbcUrl, endpoints[i]), user, password);
                     PreparedStatement insert = endpointConnection.prepareStatement(
                             "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                    insert.setInt(1, i + 1);
                    insert.setString(2, "endpoint-" + (i + 1));
                    assertEquals(1, insert.executeUpdate(), "Each endpoint should accept a DB2 write");
                }
            }

            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT id, label FROM " + tableName + " ORDER BY id")) {
                for (int i = 0; i < endpoints.length; i++) {
                    assertTrue(resultSet.next(), "A write from each endpoint should be visible");
                    assertEquals(i + 1, resultSet.getInt("id"));
                    assertEquals("endpoint-" + (i + 1), resultSet.getString("label"));
                }
                assertFalse(resultSet.next(), "No unexpected rows should be present");
            }

            assertEquals(1, statement.executeUpdate(
                    "UPDATE " + tableName + " SET label = 'updated' WHERE id = 1"));
            assertEquals(1, statement.executeUpdate("DELETE FROM " + tableName + " WHERE id = 2"));
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT id, label FROM " + tableName + " ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("id"));
                assertEquals("updated", resultSet.getString("label"));
                assertFalse(resultSet.next());
            }
        } finally {
            dropTableIfExists(tableName);
        }
    }

    @Test
    void shouldCompleteStatelessRequestsWhileOneServerIsUnavailable() throws SQLException {
        for (int i = 0; i < 10; i++) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("VALUES 1")) {
                assertTrue(resultSet.next(), "A healthy node should serve each new stateless session");
                assertEquals(1, resultSet.getInt(1));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void shouldResumeOperationsAfterARecoveredServerReturns() throws SQLException {
        for (String endpoint : endpoints) {
            try (Connection connection = DriverManager.getConnection(urlForEndpoint(jdbcUrl, endpoint), user, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("VALUES 1")) {
                assertTrue(resultSet.next(), "The recovered endpoint should accept a new connection");
                assertEquals(1, resultSet.getInt(1));
                assertFalse(resultSet.next());
            }
        }

        String tableName = tableName();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName
                    + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)");
            for (int id = 1; id <= 5; id++) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                    insert.setInt(1, id);
                    insert.setString(2, "recovered-" + id);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                assertTrue(resultSet.next());
                assertEquals(5, resultSet.getInt(1), "Writes should remain available after node recovery");
            }
        } finally {
            dropTableIfExists(tableName);
        }
    }

    @Test
    void shouldSurfaceDatabaseErrorsWithoutBreakingSubsequentRequests() throws SQLException {
        String missingTable = "DB2INST1.OJP_MISSING_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            SQLException databaseError = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM " + missingTable));
            assertTrue(databaseError.getSQLState().startsWith("42"),
                    "A missing DB2 object should be classified as a database error");

            try (ResultSet resultSet = statement.executeQuery("VALUES 1")) {
                assertTrue(resultSet.next(), "A SQL error must not poison later healthy requests");
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    private static String tableName() {
        return "DB2INST1.OJP_MULTI_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();
    }

    private static void dropTableIfExists(String tableName) throws SQLException {
        if (tableName == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + tableName);
        } catch (SQLException exception) {
            if (!"42704".equals(exception.getSQLState())) {
                throw exception;
            }
        }
    }

    private static String urlForEndpoint(String baseUrl, String endpoint) {
        int suffixIndex = baseUrl.indexOf("]_");
        if (!baseUrl.startsWith("jdbc:ojp[") || suffixIndex < 0) {
            throw new IllegalArgumentException("Expected a multinode OJP JDBC URL");
        }
        return "jdbc:ojp[" + endpoint.trim() + "]" + baseUrl.substring(suffixIndex + 1);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }
}
