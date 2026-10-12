package org.openjproxy.jdbc.testutil;

import org.junit.jupiter.params.provider.Arguments;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MySQL-compatible databases that share integration test logic but must be tested
 * separately, each through its own vendor JDBC driver on the OJP server.
 */
public enum MySQLCompatibleDatabase {

    MYSQL("MySQL", "enableMySQLTests", "/mysql_connection.csv", "MySQL Connector/J"),
    MARIADB("MariaDB", "enableMariaDBTests", "/mariadb_connection.csv", "MariaDB Connector/J");

    private final String displayName;
    private final String enableProperty;
    private final String connectionCsv;
    private final String expectedServerDriverName;
    private volatile boolean serverDriverVerified;

    MySQLCompatibleDatabase(String displayName, String enableProperty, String connectionCsv,
                            String expectedServerDriverName) {
        this.displayName = displayName;
        this.enableProperty = enableProperty;
        this.connectionCsv = connectionCsv;
        this.expectedServerDriverName = expectedServerDriverName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExpectedServerDriverName() {
        return expectedServerDriverName;
    }

    public boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(enableProperty, "false"));
    }

    /**
     * Reads the database specific connection CSV as {@code driverClass, url, user, password} arguments.
     */
    public Stream<Arguments> connectionArguments() {
        List<Arguments> arguments = new ArrayList<>();
        try (InputStream in = MySQLCompatibleDatabase.class.getResourceAsStream(connectionCsv)) {
            if (in == null) {
                throw new IllegalStateException("Connection CSV not found: " + connectionCsv);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                arguments.add(Arguments.of(columns[0].trim(), columns[1].trim(), columns[2].trim(), columns[3].trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return arguments.stream();
    }

    /**
     * Skips the test unless this database is enabled and, once per JVM, asserts that the OJP server
     * talks to it through the vendor's own JDBC driver (e.g. MariaDB Connector/J for MariaDB).
     */
    public void assumeEnabledAndVerifyServerDriver(String url, String user, String password) throws SQLException {
        assumeTrue(isEnabled(), displayName + " tests are not enabled (-D" + enableProperty + "=true)");
        if (serverDriverVerified) {
            return;
        }
        synchronized (this) {
            if (!serverDriverVerified) {
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    assertEquals(expectedServerDriverName, connection.getMetaData().getDriverName(),
                            "OJP server must use the " + displayName + " JDBC driver for " + url);
                }
                serverDriverVerified = true;
            }
        }
    }
}
