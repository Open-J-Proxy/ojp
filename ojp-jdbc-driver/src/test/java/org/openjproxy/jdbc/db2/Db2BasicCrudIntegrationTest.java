package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Db2BasicCrudIntegrationTest {

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testConnectionLifecycleAndCrud(String driverClass, String url, String user, String password)
            throws Exception {
        assumeTrue(isTestEnabled, "DB2 tests are disabled");
        Class.forName(driverClass);
        String tableName = "DB2INST1.OJP_CRUD_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();

        try {
            try (Connection setupConnection = DriverManager.getConnection(url, user, password);
                 Statement statement = setupConnection.createStatement()) {
                statement.execute("CREATE TABLE " + tableName
                        + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)");
            }

            Connection firstConnection = DriverManager.getConnection(url, user, password);
            try (Connection secondConnection = DriverManager.getConnection(url, user, password)) {
                assertNotSame(firstConnection, secondConnection,
                        "DriverManager must return distinct virtual JDBC connections");
                try (PreparedStatement insert = firstConnection.prepareStatement(
                        "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "initial");
                    assertEquals(1, insert.executeUpdate());
                    insert.setInt(1, 2);
                    insert.setString(2, "to-delete");
                    assertEquals(1, insert.executeUpdate());
                }

                try (PreparedStatement update = secondConnection.prepareStatement(
                        "UPDATE " + tableName + " SET label = ? WHERE id = ?")) {
                    update.setString(1, "updated");
                    update.setInt(2, 1);
                    assertEquals(1, update.executeUpdate());
                }

                try (Statement statement = secondConnection.createStatement();
                     ResultSet resultSet = statement.executeQuery(
                             "SELECT id, label FROM " + tableName + " ORDER BY id")) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt("id"));
                    assertEquals("updated", resultSet.getString("label"));
                    assertTrue(resultSet.next());
                    assertEquals(2, resultSet.getInt("id"));
                    assertEquals("to-delete", resultSet.getString("label"));
                    assertFalse(resultSet.next());
                }

                try (PreparedStatement delete = secondConnection.prepareStatement(
                        "DELETE FROM " + tableName + " WHERE id = ?")) {
                    delete.setInt(1, 2);
                    assertEquals(1, delete.executeUpdate());
                }
                try (Statement statement = secondConnection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1), "DELETE should remove only the selected row");
                }
                try (Statement statement = secondConnection.createStatement()) {
                    SQLException sqlException = assertThrows(SQLException.class,
                            () -> statement.executeQuery("SELECT * FROM DB2INST1.OJP_CRUD_MISSING"),
                            "An invalid DB2 table should surface as a JDBC SQL error");
                    assertTrue(sqlException.getSQLState().startsWith("42"),
                            "Missing DB2 objects should preserve their SQLSTATE");
                }
            } finally {
                firstConnection.close();
            }

            assertTrue(firstConnection.isClosed(), "Closing a JDBC connection should close its virtual connection");
            assertThrows(SQLException.class, firstConnection::createStatement,
                    "A closed virtual connection must reject further operations");
        } finally {
            try (Connection cleanupConnection = DriverManager.getConnection(url, user, password);
                 Statement statement = cleanupConnection.createStatement()) {
                statement.execute("DROP TABLE " + tableName);
            } catch (SQLException exception) {
                if (!"42704".equals(exception.getSQLState())) {
                    throw exception;
                }
            }
        }
    }
}
