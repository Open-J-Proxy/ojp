package org.openjproxy.jdbc.h2;

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
 * Integration tests for JDBC transaction commit and rollback semantics using H2.
 *
 * <p>According to the JDBC specification (java.sql.Connection javadoc and the JDBC 4.3 spec,
 * section 10):
 * <ul>
 *   <li>When {@code autoCommit} is {@code false}, a transaction begins implicitly with the
 *       first SQL statement and ends when the application calls {@link Connection#commit()} or
 *       {@link Connection#rollback()}.</li>
 *   <li>{@link Connection#commit()} makes all changes made since the previous commit/rollback
 *       permanent and releases any database locks currently held by the connection.</li>
 *   <li>{@link Connection#rollback()} undoes all changes made in the current transaction and
 *       releases any database locks. The data is restored to the state it was in at the last
 *       commit point.</li>
 * </ul>
 *
 * <p>Therefore, the expected outcome of the scenario tested here is:
 * <ol>
 *   <li>After the first update and commit, the row's value is durably persisted as "COMMITTED_VALUE".</li>
 *   <li>After the second update and rollback, the row's value is restored to "COMMITTED_VALUE"
 *       (the rolled-back change to "ROLLED_BACK_VALUE" is discarded).</li>
 * </ol>
 */
class H2TransactionCommitRollbackIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(H2TransactionCommitRollbackIntegrationTest.class);

    private static final String TABLE_NAME = "h2_tx_commit_rollback_test";
    private static final int ROW_ID = 1;
    private static final String INITIAL_VALUE = "INITIAL_VALUE";
    private static final String COMMITTED_VALUE = "COMMITTED_VALUE";
    private static final String ROLLED_BACK_VALUE = "ROLLED_BACK_VALUE";

    private static boolean isH2TestEnabled;
    private Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(connection);
    }

    /**
     * Verifies that after committing a transaction the data is durable, and that a subsequent
     * rollback restores the row to the last committed state.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Insert a row with {@code INITIAL_VALUE} and commit (setup, autoCommit on).</li>
     *   <li>Disable autoCommit to enable explicit transaction control.</li>
     *   <li>Update the row to {@code COMMITTED_VALUE} — commit the transaction.</li>
     *   <li>Update the same row to {@code ROLLED_BACK_VALUE} — rollback the transaction.</li>
     *   <li>Read the row and assert the value is {@code COMMITTED_VALUE} (the rollback
     *       discarded the second update per JDBC spec).</li>
     * </ol>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldReturnCommittedValueWhenRollbackDiscardsSubsequentUpdate(
            String driverClass, String url, String user, String password) throws SQLException {

        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing transaction commit/rollback with driver: {}", driverClass);

        connection = DriverManager.getConnection(url, user, password);

        // --- Setup: create table and insert initial row with autoCommit=true ---
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            stmt.execute("CREATE TABLE " + TABLE_NAME + " (id INT PRIMARY KEY, name VARCHAR(255))");
            stmt.execute("INSERT INTO " + TABLE_NAME + " (id, name) VALUES (" + ROW_ID + ", '" + INITIAL_VALUE + "')");
        }
        logger.info("Setup complete: table created and row inserted with value '{}'", INITIAL_VALUE);

        // --- Step 1: Disable autoCommit to start explicit transaction management ---
        connection.setAutoCommit(false);

        // --- Step 2: Update to COMMITTED_VALUE and commit ---
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + TABLE_NAME + " SET name = '" + COMMITTED_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }
        connection.commit();
        logger.info("First update committed: row value is now '{}'", COMMITTED_VALUE);

        // --- Step 3: Update to ROLLED_BACK_VALUE and rollback ---
        try (Statement stmt = connection.createStatement()) {
            int rowsUpdated = stmt.executeUpdate(
                    "UPDATE " + TABLE_NAME + " SET name = '" + ROLLED_BACK_VALUE + "' WHERE id = " + ROW_ID);
            assertEquals(1, rowsUpdated, "Exactly one row should be updated");
        }
        connection.rollback();
        logger.info("Second update rolled back: row value should have reverted to '{}'", COMMITTED_VALUE);

        // --- Step 4: Verify the row holds the committed value ---
        // Per JDBC spec section 10: rollback() undoes all changes made in the current
        // transaction and restores data to the state at the last commit point.
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM " + TABLE_NAME + " WHERE id = " + ROW_ID)) {

            assertTrue(rs.next(), "The row should still exist after rollback");
            String actualValue = rs.getString("name");
            logger.info("Actual value after rollback: '{}'", actualValue);

            assertEquals(COMMITTED_VALUE, actualValue,
                    "Per JDBC spec, rollback() must restore the row to its last committed state. "
                            + "Expected '" + COMMITTED_VALUE + "' but got '" + actualValue + "'.");
        }
        logger.info("Test passed: rollback correctly reverted to committed value '{}'", COMMITTED_VALUE);
    }
}
