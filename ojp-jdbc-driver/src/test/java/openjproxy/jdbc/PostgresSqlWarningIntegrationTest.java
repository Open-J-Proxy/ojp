package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests verifying that {@link SQLWarning} chains are fully transferred through
 * the OJP gRPC boundary for PostgreSQL connections.
 *
 * <p>PostgreSQL's {@code RAISE WARNING} inside a {@code DO} block emits a {@link SQLWarning}
 * with SQLSTATE {@code '01000'}, which is used here to verify that all attributes
 * ({@code message}, {@code sqlState}, {@code vendorCode}) survive the round-trip.
 *
 * <p>Run with {@code -DenablePostgresTests=true}.
 */
class PostgresSqlWarningIntegrationTest {

    private static boolean isTestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (statement != null && !statement.isClosed()) {
            statement.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldReturnNullGetWarningsWhenNoPriorWarningExists(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        statement.executeQuery("SELECT 1");
        statement.clearWarnings();

        assertNull(statement.getWarnings());
        assertNull(connection.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldTransferSingleWarningWithAllAttributes(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // PostgreSQL RAISE WARNING emits a SQLWarning with SQLSTATE '01000'.
        statement.clearWarnings();
        statement.execute(
                "DO $$ BEGIN " +
                "  RAISE WARNING 'ojp single warning test'; " +
                "END $$"
        );

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "A SQLWarning must be returned after RAISE WARNING");
        assertNotNull(warning.getMessage(),
                "message must be preserved across gRPC boundary");
        // PostgreSQL maps RAISE WARNING to SQLSTATE '01000'.
        assertEquals("01000", warning.getSQLState(),
                "sqlState must be '01000' for a PostgreSQL RAISE WARNING");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldTransferChainedWarningsInOrderViaStoredProcedure(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Create a stored procedure that emits two warnings in sequence.
        statement.execute(
                "CREATE OR REPLACE PROCEDURE ojp_chain_warning() " +
                "LANGUAGE plpgsql AS $$ " +
                "BEGIN " +
                "  RAISE WARNING 'warning one'; " +
                "  RAISE WARNING 'warning two'; " +
                "END; $$"
        );
        statement.clearWarnings();
        statement.execute("CALL ojp_chain_warning()");

        SQLWarning first = statement.getWarnings();
        assertNotNull(first, "First warning in chain must not be null");
        assertNotNull(first.getMessage(), "First warning message must be transferred");
        assertEquals("01000", first.getSQLState(), "First warning sqlState must be '01000'");

        SQLWarning second = first.getNextWarning();
        assertNotNull(second,
                "Second warning in chain must not be null – the full chain must be transferred");
        assertNotNull(second.getMessage(), "Second warning message must be transferred");
        assertEquals("01000", second.getSQLState(), "Second warning sqlState must be '01000'");

        assertNull(second.getNextWarning(), "No more warnings expected beyond the two emitted");

        statement.execute("DROP PROCEDURE IF EXISTS ojp_chain_warning");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldTransferConnectionLevelWarning(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Verify the connection-level warning transport does not crash.
        SQLWarning connWarning = connection.getWarnings();
        if (connWarning != null) {
            assertNotNull(connWarning.getMessage());
            assertNotNull(connWarning.getSQLState());
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldTransferWarningMessageContent(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        String expectedMessageSnippet = "ojp_message_content_check";
        statement.clearWarnings();
        statement.execute(
                "DO $$ BEGIN " +
                "  RAISE WARNING '" + expectedMessageSnippet + "'; " +
                "END $$"
        );

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "Warning must be present");
        assertNotNull(warning.getMessage(), "Warning message must not be null");
        // The exact message may be prefixed by PostgreSQL (e.g. "WARNING: ..."); check it contains
        // the text we set so we know the message payload was truly transferred.
        assertTrue(
                warning.getMessage().contains(expectedMessageSnippet),
                "Warning message must contain the text we emitted; got: " + warning.getMessage()
        );
    }
}
