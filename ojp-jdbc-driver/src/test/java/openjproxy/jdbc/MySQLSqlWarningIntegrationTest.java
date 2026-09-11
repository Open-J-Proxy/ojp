package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests verifying that {@link SQLWarning} chains are fully transferred through
 * the OJP gRPC boundary for MySQL and MariaDB connections.
 *
 * <p>MySQL/MariaDB SIGNAL with SQLSTATE {@code '01000'} (general warning) is used to produce
 * deterministic single and chained warnings, so that all three attributes ({@code message},
 * {@code sqlState}, {@code vendorCode}) can be asserted precisely.
 *
 * <p>Run with {@code -DenableMySQLTests=true} and/or {@code -DenableMariaDBTests=true}.
 */
class MySQLSqlWarningIntegrationTest {

    // OJP test URLs use the form jdbc:ojp[host:port]_mysql://... for MySQL.
    private static final String MYSQL_URL_MARKER = "_mysql://";

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        if (url.toLowerCase().contains("mysql")) {
            assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        } else {
            assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
        }
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
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldReturnNullGetWarningsWhenNoPriorWarningExists(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Run a plain query that produces no warning.
        statement.executeQuery("SELECT 1");
        statement.clearWarnings();

        assertNull(statement.getWarnings());
        assertNull(connection.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferSingleWarningWithAllAttributes(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Create a stored procedure that emits exactly one SQLSTATE '01000' warning.
        statement.execute("DROP PROCEDURE IF EXISTS ojp_single_warning");
        statement.execute(
                "CREATE PROCEDURE ojp_single_warning() " +
                "BEGIN " +
                "  SIGNAL SQLSTATE '01000' " +
                "    SET MESSAGE_TEXT = 'ojp single warning test', MYSQL_ERRNO = 1234; " +
                "END"
        );
        statement.clearWarnings();
        statement.execute("CALL ojp_single_warning()");

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "A SQLWarning must be returned after SIGNAL SQLSTATE '01000'");
        assertEquals("ojp single warning test", warning.getMessage(),
                "message must be preserved across gRPC boundary");
        assertEquals(expectedSingleWarningSqlState(url), warning.getSQLState(),
                "sqlState must match the backend driver's JDBC warning behaviour");
        // MySQL maps MYSQL_ERRNO to vendorCode.
        assertEquals(1234, warning.getErrorCode(),
                "vendorCode must be preserved across gRPC boundary");

        statement.execute("DROP PROCEDURE IF EXISTS ojp_single_warning");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldMirrorBackendWarningVisibilityForSequentialSignalWarnings(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // MySQL/MariaDB JDBC only surfaces the last warning emitted by sequential SIGNAL statements.
        // OJP should mirror that backend-driver behaviour exactly, rather than inventing a chain.
        statement.execute("DROP PROCEDURE IF EXISTS ojp_chain_warning");
        statement.execute(
                "CREATE PROCEDURE ojp_chain_warning() " +
                "BEGIN " +
                "  SIGNAL SQLSTATE '01000' " +
                "    SET MESSAGE_TEXT = 'warning one', MYSQL_ERRNO = 1001; " +
                "  SIGNAL SQLSTATE '01001' " +
                "    SET MESSAGE_TEXT = 'warning two', MYSQL_ERRNO = 1002; " +
                "END"
        );
        statement.clearWarnings();
        statement.execute("CALL ojp_chain_warning()");

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "The backend-visible warning must be transferred");
        assertEquals("warning two", warning.getMessage(), "The last warning must be preserved");
        assertEquals(expectedSequentialWarningSqlState(url), warning.getSQLState(),
                "sqlState must match the backend driver's JDBC warning behaviour");
        assertEquals(1002, warning.getErrorCode(), "vendorCode must match the backend-visible warning");
        assertNull(warning.getNextWarning(), "No warning chain is exposed by the backend driver here");

        statement.execute("DROP PROCEDURE IF EXISTS ojp_chain_warning");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferDataTruncationWarningFromInsert(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // A VARCHAR(5) column with strict mode off generates a truncation warning for long values.
        statement.execute("DROP TABLE IF EXISTS ojp_truncation_test");
        statement.execute("CREATE TABLE ojp_truncation_test (col VARCHAR(5))");
        // Disable strict mode so truncation becomes a warning rather than an error.
        statement.execute("SET SESSION sql_mode = ''");
        statement.clearWarnings();
        statement.execute("INSERT INTO ojp_truncation_test VALUES ('toolongvalue')");

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning,
                "Data truncation must produce a SQLWarning; if this fails the mode change may not have worked");
        assertNotNull(warning.getMessage(), "Truncation warning message must be transferred");
        assertTrue(warning.getMessage().toLowerCase(Locale.ROOT).contains("truncat"),
                "Truncation warning message must contain the backend warning text");
        assertEquals(expectedTruncationWarningSqlState(url), warning.getSQLState(),
                "Truncation warning sqlState must match the backend driver's JDBC warning behaviour");
        assertEquals(1265, warning.getErrorCode(),
                "Truncation warning vendorCode must be transferred");

        statement.execute("DROP TABLE IF EXISTS ojp_truncation_test");
    }

    private static String expectedSingleWarningSqlState(String url) {
        // Verified against the direct JDBC drivers used in CI:
        // MySQL Connector/J remaps this SIGNAL warning to SQLSTATE 42000,
        // while MariaDB (the only non-MySQL URL in this CSV) returns null.
        return isMySqlUrl(url) ? "42000" : null;
    }

    private static String expectedSequentialWarningSqlState(String url) {
        // Verified against the direct JDBC drivers used in CI:
        // MySQL surfaces only the last SIGNAL warning with SQLSTATE HY000,
        // while MariaDB (the only non-MySQL URL in this CSV) surfaces only the
        // last warning and leaves SQLSTATE null.
        return isMySqlUrl(url) ? "HY000" : null;
    }

    private static String expectedTruncationWarningSqlState(String url) {
        // Verified against the direct JDBC drivers used in CI:
        // MySQL reports SQLSTATE 01000 for truncation warnings, while MariaDB
        // (the only non-MySQL URL in this CSV) reports null.
        return isMySqlUrl(url) ? "01000" : null;
    }

    private static boolean isMySqlUrl(String url) {
        // OJP test URLs use the form jdbc:ojp[host:port]_mysql://... for MySQL.
        return url.toLowerCase(Locale.ROOT).contains(MYSQL_URL_MARKER);
    }
}
