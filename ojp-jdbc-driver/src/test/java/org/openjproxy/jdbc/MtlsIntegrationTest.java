package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MtlsIntegrationTest {

    private static final String JDBC_URL_PROPERTY = "ojp.mtls.jdbc.url";
    private static final String USER_PROPERTY = "ojp.mtls.user";
    private static final String PASSWORD_PROPERTY = "ojp.mtls.password";
    private static final String ENDPOINTS_PROPERTY = "ojp.mtls.endpoints";

    @Test
    void shouldRunJdbcAndXaOperationsAgainstEveryMutuallyAuthenticatedServer() throws Exception {
        assumeMtlsTestsEnabled();
        String jdbcUrl = requiredProperty(JDBC_URL_PROPERTY);
        String user = requiredProperty(USER_PROPERTY);
        String password = requiredProperty(PASSWORD_PROPERTY);
        String tableName = "ojp_mtls_" + UUID.randomUUID().toString().replace("-", "");

        for (String endpoint : System.getProperty(ENDPOINTS_PROPERTY, "localhost:10591,localhost:10592")
                .split(",")) {
            try (Connection connection = DriverManager.getConnection(urlForEndpoint(jdbcUrl, endpoint.trim()),
                    user, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName + " (id INTEGER PRIMARY KEY, label VARCHAR(40))");
        }
        try {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TEMPORARY TABLE ojp_mtls_session (value INTEGER)");
                    statement.execute("INSERT INTO ojp_mtls_session VALUES (7)");
                    try (ResultSet resultSet = statement.executeQuery("SELECT value FROM ojp_mtls_session")) {
                        assertTrue(resultSet.next());
                        assertEquals(7, resultSet.getInt(1));
                    }
                    SQLException sqlException = assertThrows(SQLException.class,
                            () -> statement.executeQuery("SELECT * FROM ojp_mtls_missing_table"));
                    assertTrue(sqlException.getSQLState().startsWith("42"));
                }
                connection.setAutoCommit(false);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "jdbc-commit");
                    assertEquals(1, insert.executeUpdate());
                    connection.commit();

                    insert.setInt(1, 2);
                    insert.setString(2, "jdbc-rollback");
                    assertEquals(1, insert.executeUpdate());
                    connection.rollback();
                }
            }

            OjpXADataSource dataSource = new OjpXADataSource(jdbcUrl, user, password);
            XAConnection xaConnection = dataSource.getXAConnection();
            try {
                try (Connection connection = xaConnection.getConnection()) {
                    XAResource xaResource = xaConnection.getXAResource();
                    Xid committedXid = new TestXid(1, "mtls-commit".getBytes(), "branch-1".getBytes());
                    xaResource.start(committedXid, XAResource.TMNOFLAGS);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                        insert.setInt(1, 3);
                        insert.setString(2, "xa-commit");
                        assertEquals(1, insert.executeUpdate());
                    }
                    xaResource.end(committedXid, XAResource.TMSUCCESS);
                    assertEquals(XAResource.XA_OK, xaResource.prepare(committedXid));
                    assertTrue(Arrays.stream(xaResource.recover(XAResource.TMSTARTRSCAN))
                            .anyMatch(xid -> sameXid(xid, committedXid)));
                    xaResource.recover(XAResource.TMENDRSCAN);
                    xaResource.commit(committedXid, false);

                    Xid rollbackXid = new TestXid(2, "mtls-rollback".getBytes(), "branch-2".getBytes());
                    xaResource.start(rollbackXid, XAResource.TMNOFLAGS);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
                        insert.setInt(1, 4);
                        insert.setString(2, "xa-rollback");
                        assertEquals(1, insert.executeUpdate());
                    }
                    xaResource.end(rollbackXid, XAResource.TMSUCCESS);
                    xaResource.rollback(rollbackXid);
                }
            } finally {
                xaConnection.close();
            }

            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT id, label FROM " + tableName + " ORDER BY id");
                 ResultSet resultSet = query.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("id"));
                assertEquals("jdbc-commit", resultSet.getString("label"));
                assertTrue(resultSet.next());
                assertEquals(3, resultSet.getInt("id"));
                assertEquals("xa-commit", resultSet.getString("label"));
                assertFalse(resultSet.next());
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + tableName);
            }
        }
    }

    @Test
    void shouldRejectConnectionWithoutClientCertificate() {
        assumeMtlsTestsEnabled();
        assertTrue(System.getProperty("ojp.client.tls.keystore.path") == null,
                "Do not configure a client keystore for the missing-certificate case");
        assertThrows(SQLException.class, this::openConnection,
                "A server requiring client certificates must reject clients without one");
    }

    @Test
    void shouldRejectConnectionWithUntrustedClientCertificate() {
        assumeMtlsTestsEnabled();
        assertTrue(System.getProperty("ojp.client.tls.keystore.path") != null,
                "Configure the untrusted client keystore for this case");
        assertThrows(SQLException.class, this::openConnection,
                "A server must reject client certificates it does not trust");
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(requiredProperty(JDBC_URL_PROPERTY),
                requiredProperty(USER_PROPERTY), requiredProperty(PASSWORD_PROPERTY));
    }

    private static String urlForEndpoint(String jdbcUrl, String endpoint) {
        int suffixIndex = jdbcUrl.indexOf("]_");
        if (!jdbcUrl.startsWith("jdbc:ojp[") || suffixIndex < 0) {
            throw new IllegalArgumentException("Expected a multinode OJP JDBC URL");
        }
        return "jdbc:ojp[" + endpoint + "]" + jdbcUrl.substring(suffixIndex + 1);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }

    private static void assumeMtlsTestsEnabled() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("ojp.mtls.tests.enabled", "false")),
                "Run this integration suite with -Dojp.mtls.tests.enabled=true");
    }

    private static boolean sameXid(Xid left, Xid right) {
        return left.getFormatId() == right.getFormatId()
                && Arrays.equals(left.getGlobalTransactionId(), right.getGlobalTransactionId())
                && Arrays.equals(left.getBranchQualifier(), right.getBranchQualifier());
    }

    private static final class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        private TestXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
