package org.openjproxy.jdbc.db2;

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

class Db2MtlsIntegrationTest {

    private static final String JDBC_URL_PROPERTY = "ojp.db2.mtls.jdbc.url";
    private static final String USER_PROPERTY = "ojp.db2.mtls.user";
    private static final String PASSWORD_PROPERTY = "ojp.db2.mtls.password";
    private static final String ENDPOINTS_PROPERTY = "ojp.db2.mtls.endpoints";

    @Test
    void shouldRunJdbcAndXaOperationsAgainstEveryMutuallyAuthenticatedServer() throws Exception {
        assumeMtlsTestsEnabled();
        String jdbcUrl = requiredProperty(JDBC_URL_PROPERTY);
        String user = requiredProperty(USER_PROPERTY);
        String password = requiredProperty(PASSWORD_PROPERTY);
        String tableName = tableName();

        for (String endpoint : System.getProperty(ENDPOINTS_PROPERTY, "localhost:10591,localhost:10592")
                .split(",")) {
            try (Connection connection = DriverManager.getConnection(urlForEndpoint(jdbcUrl, endpoint), user, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("VALUES 1")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertFalse(resultSet.next());
            }
        }

        boolean tableCreated = false;
        try {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + tableName
                        + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(40) NOT NULL)");
                tableCreated = true;

                connection.setAutoCommit(false);
                insert(connection, tableName, 1, "jdbc-commit");
                connection.commit();
                insert(connection, tableName, 2, "jdbc-rollback");
                connection.rollback();

                SQLException sqlError = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT * FROM DB2INST1.OJP_MTLS_MISSING"));
                assertTrue(sqlError.getSQLState().startsWith("42"),
                        "DB2 SQL errors should reach the JDBC caller with their SQLSTATE");
            }

            OjpXADataSource dataSource = new OjpXADataSource(jdbcUrl, user, password);
            XAConnection xaConnection = dataSource.getXAConnection();
            try {
                Connection connection = xaConnection.getConnection();
                XAResource resource = xaConnection.getXAResource();
                Xid committedXid = xid(1, "db2-mtls-commit");
                resource.start(committedXid, XAResource.TMNOFLAGS);
                insert(connection, tableName, 3, "xa-commit");
                resource.end(committedXid, XAResource.TMSUCCESS);
                assertEquals(XAResource.XA_OK, resource.prepare(committedXid));
                Xid[] recovered = resource.recover(XAResource.TMSTARTRSCAN);
                assertTrue(Arrays.stream(recovered).anyMatch(candidate -> sameXid(candidate, committedXid)),
                        "A prepared DB2 transaction should be available to recovery");
                resource.recover(XAResource.TMENDRSCAN);
                resource.commit(committedXid, false);

                Xid rolledBackXid = xid(2, "db2-mtls-rollback");
                resource.start(rolledBackXid, XAResource.TMNOFLAGS);
                insert(connection, tableName, 4, "xa-rollback");
                resource.end(rolledBackXid, XAResource.TMSUCCESS);
                resource.rollback(rolledBackXid);
            } finally {
                xaConnection.close();
            }

            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, label FROM " + tableName + " ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("id"));
                assertEquals("jdbc-commit", resultSet.getString("label"));
                assertTrue(resultSet.next());
                assertEquals(3, resultSet.getInt("id"));
                assertEquals("xa-commit", resultSet.getString("label"));
                assertFalse(resultSet.next(), "Rolled-back JDBC and XA rows must not be persisted");
            }
        } finally {
            if (tableCreated) {
                try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                     Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE " + tableName);
                }
            }
        }
    }

    @Test
    void shouldRejectConnectionWithoutClientCertificate() {
        assumeMtlsTestsEnabled();
        assertTrue(System.getProperty("ojp.client.tls.keystore.path") == null,
                "The missing-certificate case must not configure a client keystore");
        assertThrows(SQLException.class, this::openConnection,
                "A DB2 endpoint requiring client certificates must reject unauthenticated clients");
    }

    @Test
    void shouldRejectConnectionWithUntrustedClientCertificate() {
        assumeMtlsTestsEnabled();
        assertTrue(System.getProperty("ojp.client.tls.keystore.path") != null,
                "The untrusted-certificate case must configure the untrusted client keystore");
        assertThrows(SQLException.class, this::openConnection,
                "A DB2 endpoint must reject untrusted client certificates");
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(requiredProperty(JDBC_URL_PROPERTY),
                requiredProperty(USER_PROPERTY), requiredProperty(PASSWORD_PROPERTY));
    }

    private static void insert(Connection connection, String tableName, int id, String label) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, label);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String tableName() {
        return "DB2INST1.OJP_MTLS_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();
    }

    private static String urlForEndpoint(String jdbcUrl, String endpoint) {
        int suffixIndex = jdbcUrl.indexOf("]_");
        if (!jdbcUrl.startsWith("jdbc:ojp[") || suffixIndex < 0) {
            throw new IllegalArgumentException("Expected a multinode OJP JDBC URL");
        }
        return "jdbc:ojp[" + endpoint.trim() + "]" + jdbcUrl.substring(suffixIndex + 1);
    }

    private static Xid xid(int formatId, String globalId) {
        return new TestXid(formatId, globalId.getBytes(), ("branch-" + formatId).getBytes());
    }

    private static boolean sameXid(Xid left, Xid right) {
        return left.getFormatId() == right.getFormatId()
                && Arrays.equals(left.getGlobalTransactionId(), right.getGlobalTransactionId())
                && Arrays.equals(left.getBranchQualifier(), right.getBranchQualifier());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }

    private static void assumeMtlsTestsEnabled() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("ojp.db2.mtls.tests.enabled", "false")),
                "Run this suite with -Dojp.db2.mtls.tests.enabled=true");
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
