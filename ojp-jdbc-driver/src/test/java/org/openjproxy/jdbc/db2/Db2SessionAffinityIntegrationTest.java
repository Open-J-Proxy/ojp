package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
