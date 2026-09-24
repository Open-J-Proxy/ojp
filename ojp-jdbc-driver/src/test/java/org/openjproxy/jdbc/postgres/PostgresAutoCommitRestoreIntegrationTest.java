package org.openjproxy.jdbc.postgres;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL variant of the end-to-end regression test for a bug where
 * {@link Connection#setAutoCommit(boolean) setAutoCommit(true)} failed to restore the physical
 * connection's autocommit mode on the server after leaving manual-commit mode. Mirrors
 * {@code H2AutoCommitRestoreIntegrationTest} to validate the fix against a real PostgreSQL
 * driver/server, not just the embedded H2 engine.
 */
class PostgresAutoCommitRestoreIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PostgresAutoCommitRestoreIntegrationTest.class);

    private static final int ROW_ID = 1;
    private static final String INITIAL_VALUE = "INITIAL_VALUE";
    private static final String COMMITTED_VALUE = "COMMITTED_VALUE";
    private static final String AUTOCOMMIT_VALUE = "AUTOCOMMIT_VALUE";

    private static boolean isPostgresTestEnabled;
    private Connection connection;

    @BeforeAll
    static void setupClass() {
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(connection);
    }

    private void createTable(String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("INSERT INTO " + tableName + " (id, name) VALUES (" + ROW_ID + ", '" + INITIAL_VALUE + "')");
        }
    }

    private String readValue(String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM " + tableName + " WHERE id = " + ROW_ID)) {
            assertTrue(rs.next(), "The row should still exist");
            return rs.getString("name");
        }
    }

    /**
     * Scenario exercised here — the original bug report:
     * <ol>
     *   <li>Disable autoCommit and update a row.</li>
     *   <li>Re-enable autoCommit ({@code setAutoCommit(true)}) <b>without ever calling
     *       {@code commit()}</b> — this is exactly the call sequence that triggered the bug,
     *       relying on {@code setAutoCommit(true)} itself to both commit and restore autocommit.</li>
     *   <li>Update the row again, again without ever calling {@code commit()}, relying purely
     *       on autocommit having been restored.</li>
     *   <li>Terminate the session (close the connection) and reconnect.</li>
     *   <li>Assert the second update is durably visible — proving it was auto-committed rather
     *       than silently rolled back on session termination.</li>
     * </ol>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void setAutoCommitTrueShouldDurablyPersistStatementsExecutedAfterRestoringAutoCommit(
            String driverClass, String url, String user, String password) throws SQLException {

        assumeTrue(isPostgresTestEnabled, "Skipping Postgres tests - not enabled");
        logger.info("Testing setAutoCommit(true) restore (no explicit commit) with driver: {}", driverClass);

        String tableName = "pg_autocommit_restore_test";
        connection = DriverManager.getConnection(url, user, password);
        createTable(tableName);
        logger.info("Setup complete: table created and row inserted with value '{}'", INITIAL_VALUE);

        // --- Step 1: start a transaction and update the row, never calling commit() explicitly ---
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + tableName + " SET name = '" + COMMITTED_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }

        // --- Step 2: setAutoCommit(true) is the ONLY commit mechanism used here — the exact
        // call sequence that used to leave the physical connection in manual-commit mode. ---
        connection.setAutoCommit(true);
        assertTrue(connection.getAutoCommit(), "Client-side autoCommit flag must read true after setAutoCommit(true)");
        logger.info("setAutoCommit(true) called: transaction should be committed and autoCommit restored");

        // --- Step 3: update the row again WITHOUT ever calling commit(). If autoCommit was not
        // actually restored on the physical connection, this update is silently discarded when
        // the session is terminated below. ---
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + tableName + " SET name = '" + AUTOCOMMIT_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }
        logger.info("Update executed in (supposedly restored) autoCommit mode, no explicit commit() called");

        // --- Step 4: terminate the session and reconnect, to force any uncommitted phantom
        // transaction to be resolved (committed if autoCommit was truly restored, rolled back
        // otherwise). ---
        connection.close();
        connection = DriverManager.getConnection(url, user, password);

        // --- Step 5: verify the update survived session termination. ---
        String actualValue = readValue(tableName);
        logger.info("Actual value after reconnecting: '{}'", actualValue);
        assertEquals(AUTOCOMMIT_VALUE, actualValue,
                "setAutoCommit(true) must restore autoCommit on the physical connection so "
                        + "subsequent statements are durably persisted without an explicit "
                        + "commit(). Expected '" + AUTOCOMMIT_VALUE + "' but got '" + actualValue
                        + "' — the update was silently discarded, meaning the physical "
                        + "connection was still left in manual-commit mode.");
        logger.info("Test passed: update executed after setAutoCommit(true) was durably persisted");
    }

    /**
     * Scenario exercised here — a closely related edge case: an explicit {@code commit()} is
     * called first (as many frameworks do), and only afterwards is {@code setAutoCommit(true)}
     * called with no statement executed in between. Per JDBC semantics, {@code commit()} on a
     * manual-commit connection does NOT switch it back to autocommit mode — it stays in
     * manual-commit mode until {@code setAutoCommit(true)} is called. Gating the restore purely
     * on the last-known transaction status (active vs. committed) would incorrectly skip the
     * restore in this case, leaving the physical connection permanently stuck in manual-commit
     * mode.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void setAutoCommitTrueAfterAnExplicitCommitShouldStillRestoreAutoCommit(
            String driverClass, String url, String user, String password) throws SQLException {

        assumeTrue(isPostgresTestEnabled, "Skipping Postgres tests - not enabled");
        logger.info("Testing setAutoCommit(true) restore after an explicit commit() with driver: {}", driverClass);

        String tableName = "pg_autocommit_restore_after_commit_test";
        connection = DriverManager.getConnection(url, user, password);
        createTable(tableName);
        logger.info("Setup complete: table created and row inserted with value '{}'", INITIAL_VALUE);

        // --- Step 1: start a transaction, update the row, and commit it explicitly ---
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + tableName + " SET name = '" + COMMITTED_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }
        connection.commit();
        logger.info("Transaction committed explicitly: row value is now '{}'", COMMITTED_VALUE);

        // --- Step 2: re-enable autoCommit with NO statement executed since the explicit commit.
        // The physical connection is still in manual-commit mode at this point. ---
        connection.setAutoCommit(true);
        assertTrue(connection.getAutoCommit(), "Client-side autoCommit flag must read true after setAutoCommit(true)");

        // --- Step 3: update the row again WITHOUT ever calling commit(). ---
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + tableName + " SET name = '" + AUTOCOMMIT_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }
        logger.info("Update executed in (supposedly restored) autoCommit mode, no explicit commit() called");

        // --- Step 4: terminate the session and reconnect. ---
        connection.close();
        connection = DriverManager.getConnection(url, user, password);

        // --- Step 5: verify the update survived session termination. ---
        String actualValue = readValue(tableName);
        logger.info("Actual value after reconnecting: '{}'", actualValue);
        assertEquals(AUTOCOMMIT_VALUE, actualValue,
                "setAutoCommit(true) must restore autoCommit on the physical connection even "
                        + "when called right after an explicit commit() with no statement in "
                        + "between. Expected '" + AUTOCOMMIT_VALUE + "' but got '" + actualValue
                        + "' — the physical connection was left stuck in manual-commit mode.");
        logger.info("Test passed: update executed after setAutoCommit(true) was durably persisted");
    }
}
