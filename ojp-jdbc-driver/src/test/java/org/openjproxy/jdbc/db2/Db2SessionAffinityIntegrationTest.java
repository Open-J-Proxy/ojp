package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class Db2SessionAffinityIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDeclaredTemporaryTableRemainsAvailableOnBoundSession(
            String driverClass, String configuredUrl, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        Class.forName(driverClass);
        String url = System.getProperty("ojp.db2.multinode.url", configuredUrl);
        String tableName = "OJP_AFFINITY_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("DECLARE GLOBAL TEMPORARY TABLE SESSION." + tableName
                    + " (id INTEGER NOT NULL, label VARCHAR(100)) ON COMMIT PRESERVE ROWS NOT LOGGED");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO SESSION." + tableName + " (id, label) VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "first");
                assertEquals(1, insert.executeUpdate());
                insert.setInt(1, 2);
                insert.setString(2, "second");
                assertEquals(1, insert.executeUpdate());
            }

            assertEquals(1, statement.executeUpdate(
                    "UPDATE SESSION." + tableName + " SET label = 'updated' WHERE id = 2"));
            connection.commit();

            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT id, label FROM SESSION." + tableName + " ORDER BY id")) {
                assertTrue(resultSet.next(), "First temporary-table row should be available on the bound session");
                assertEquals(1, resultSet.getInt("id"));
                assertEquals("first", resultSet.getString("label"));
                assertTrue(resultSet.next(), "Second temporary-table row should be available on the bound session");
                assertEquals(2, resultSet.getInt("id"));
                assertEquals("updated", resultSet.getString("label"));
                assertFalse(resultSet.next(), "The temporary table should contain exactly two rows");
            }
        }
    }

    @Test
    void testStickyDb2SessionFailsInsteadOfReroutingWhenItsServerStops() throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        String url = requiredProperty("ojp.db2.affinity.url");
        String user = requiredProperty("ojp.db2.user");
        String password = requiredProperty("ojp.db2.password");
        Path readyFile = Path.of(requiredProperty("ojp.db2.affinity.ready.file"));
        Path continueFile = Path.of(requiredProperty("ojp.db2.affinity.continue.file"));
        String tableName = "OJP_AFFINITY_FAILOVER_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();

        Class.forName("org.openjproxy.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("DECLARE GLOBAL TEMPORARY TABLE SESSION." + tableName
                    + " (id INTEGER NOT NULL, label VARCHAR(100)) ON COMMIT PRESERVE ROWS NOT LOGGED");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO SESSION." + tableName + " (id, label) VALUES (1, 'sticky')")) {
                assertEquals(1, insert.executeUpdate());
            }
            connection.commit();
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT label FROM SESSION." + tableName + " WHERE id = 1")) {
                assertTrue(resultSet.next());
                assertEquals("sticky", resultSet.getString(1));
                assertFalse(resultSet.next());
            }

            Files.write(readyFile, new byte[]{1});
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (!Files.exists(continueFile) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(Files.exists(continueFile), "The CI test must stop the bound OJP server");

            assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT label FROM SESSION." + tableName + " WHERE id = 1"),
                    "The sticky DB2 session must fail instead of rerouting to another healthy OJP server");
        }

        try (Connection fallbackConnection = DriverManager.getConnection(
                urlForEndpoint(url, "localhost:10592"), user, password);
             Statement statement = fallbackConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("VALUES 1")) {
            assertTrue(resultSet.next(), "The other OJP server should remain healthy");
            assertEquals(1, resultSet.getInt(1));
            assertFalse(resultSet.next());
        }
    }

    private static String urlForEndpoint(String baseUrl, String endpoint) {
        int suffixIndex = baseUrl.indexOf("]_");
        if (!baseUrl.startsWith("jdbc:ojp[") || suffixIndex < 0) {
            throw new IllegalArgumentException("Expected a multinode OJP JDBC URL");
        }
        return "jdbc:ojp[" + endpoint + "]" + baseUrl.substring(suffixIndex + 1);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }
}
