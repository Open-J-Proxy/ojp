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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Db2TransactionCommitRollbackIntegrationTest {

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testCommitPersistsAndRollbackDiscardsChanges(
            String driverClass, String url, String user, String password) throws Exception {
        assumeTrue(isTestEnabled, "DB2 tests are disabled");
        Class.forName(driverClass);
        String tableName = tableName();
        try {
            try (Connection setupConnection = DriverManager.getConnection(url, user, password);
                 Statement statement = setupConnection.createStatement()) {
                statement.execute("CREATE TABLE " + tableName
                        + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)");
            }

            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                connection.setAutoCommit(false);
                insert(connection, tableName, 1, "committed");
                connection.commit();
                insert(connection, tableName, 2, "rolled-back");
                connection.rollback();
            }

            try (Connection verificationConnection = DriverManager.getConnection(url, user, password);
                 Statement statement = verificationConnection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, label FROM " + tableName + " ORDER BY id")) {
                assertTrue(resultSet.next(), "Committed change should be visible");
                assertEquals(1, resultSet.getInt("id"));
                assertEquals("committed", resultSet.getString("label"));
                assertFalse(resultSet.next(), "Rolled-back change must not be visible");
            }
        } finally {
            dropTableIfExists(url, user, password, tableName);
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testTransactionIsolationCanBeChangedAndReadBack(
            String driverClass, String url, String user, String password) throws Exception {
        assumeTrue(isTestEnabled, "DB2 tests are disabled");
        Class.forName(driverClass);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            int originalIsolation = connection.getTransactionIsolation();
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation(),
                        "DB2 should report the configured repeatable-read isolation level");
            } finally {
                connection.setTransactionIsolation(originalIsolation);
            }
            assertEquals(originalIsolation, connection.getTransactionIsolation(),
                    "The original isolation level should be restored");
        }
    }

    private static void insert(Connection connection, String tableName, int id, String label)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, label);
            assertEquals(1, statement.executeUpdate(), "Transaction insert should affect one row");
        }
    }

    private static void dropTableIfExists(String url, String user, String password, String tableName)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + tableName);
        } catch (SQLException exception) {
            if (!"42704".equals(exception.getSQLState())) {
                throw exception;
            }
        }
    }

    private static String tableName() {
        return "DB2INST1.OJP_TX_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();
    }
}
