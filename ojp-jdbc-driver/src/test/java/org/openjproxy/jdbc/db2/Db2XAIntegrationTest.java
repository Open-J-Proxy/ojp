package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class Db2XAIntegrationTest {

    private static boolean isTestDisabled;
    private XAConnection xaConnection;
    private Connection connection;
    private String url;
    private String user;
    private String password;
    private String tableName;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    private void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        Class.forName(driverClass);
        this.url = url;
        this.user = user;
        this.password = password;
        this.tableName = "DB2INST1.OJP_DB2_XA_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();

        try (Connection setupConnection = DriverManager.getConnection(url, user, password);
             Statement statement = setupConnection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName
                    + " (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR(100) NOT NULL)");
        }

        OjpXADataSource dataSource = new OjpXADataSource(url, user, password);
        xaConnection = dataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        if (xaConnection != null) {
            xaConnection.close();
        }
        if (tableName != null) {
            try (Connection cleanupConnection = DriverManager.getConnection(url, user, password);
                 Statement statement = cleanupConnection.createStatement()) {
                statement.execute("DROP TABLE " + tableName);
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testXAConnectionBasics(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        assertNotNull(xaConnection, "XA connection should be created");
        assertNotNull(connection, "Logical connection should be created");
        assertFalse(connection.isClosed(), "Logical connection should remain open");
        assertFalse(connection.getAutoCommit(), "XA connection should not be in auto-commit mode");
        assertNotNull(xaConnection.getXAResource(), "XA resource should be available");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testTwoPhaseCommitAndRecovery(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        XAResource resource = xaConnection.getXAResource();
        Xid xid = xid(1, "db2-two-phase-commit");

        resource.start(xid, XAResource.TMNOFLAGS);
        insert(1, "committed");
        resource.end(xid, XAResource.TMSUCCESS);
        assertEquals(XAResource.XA_OK, resource.prepare(xid), "A write transaction should prepare");

        Xid[] recovered = resource.recover(XAResource.TMSTARTRSCAN);
        assertTrue(Arrays.stream(recovered).anyMatch(candidate -> sameXid(candidate, xid)),
                "The prepared XID should be visible in the recovery scan");
        resource.recover(XAResource.TMENDRSCAN);
        resource.commit(xid, false);

        assertEquals(1, countRows(1), "Two-phase commit should persist its row");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testRollbackRemovesWrites(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        XAResource resource = xaConnection.getXAResource();
        Xid xid = xid(2, "db2-rollback");

        resource.start(xid, XAResource.TMNOFLAGS);
        insert(2, "rolled-back");
        resource.end(xid, XAResource.TMSUCCESS);
        resource.rollback(xid);

        assertEquals(0, countRows(2), "Rolled-back XA work must not be visible");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testOnePhaseCommit(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        XAResource resource = xaConnection.getXAResource();
        Xid xid = xid(3, "db2-one-phase-commit");

        resource.start(xid, XAResource.TMNOFLAGS);
        insert(3, "one-phase");
        resource.end(xid, XAResource.TMSUCCESS);
        resource.commit(xid, true);

        assertEquals(1, countRows(3), "One-phase commit should persist its row");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testResourceManagerIdentity(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        OjpXADataSource otherDataSource = new OjpXADataSource(url, user, password);
        XAConnection otherConnection = otherDataSource.getXAConnection(user, password);
        try {
            assertTrue(xaConnection.getXAResource().isSameRM(otherConnection.getXAResource()),
                    "XA connections to the same DB2 resource should share resource-manager identity");
        } finally {
            otherConnection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testTransactionTimeoutContract(String driverClass, String url, String user, String password)
            throws Exception {
        setUp(driverClass, url, user, password);
        XAResource resource = xaConnection.getXAResource();

        boolean accepted = resource.setTransactionTimeout(120);
        int timeout = resource.getTransactionTimeout();
        assertTrue(timeout >= 0, "Transaction timeout must be non-negative");
        if (accepted) {
            assertEquals(120, timeout, "Accepted timeout should be reported by the resource");
        }
        assertTrue(resource.setTransactionTimeout(0), "Resetting the XA timeout should be supported");
        assertEquals(0, resource.getTransactionTimeout(), "Reset timeout should be zero");
    }

    private void insert(int id, String label) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tableName + " (id, label) VALUES (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, label);
            assertEquals(1, statement.executeUpdate(), "XA insert should affect one row");
        }
    }

    private int countRows(int id) throws SQLException {
        try (Connection verifyConnection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = verifyConnection.prepareStatement(
                     "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Count query should return one row");
                return resultSet.getInt(1);
            }
        }
    }

    private static Xid xid(int formatId, String globalId) {
        return new TestXid(formatId, globalId.getBytes(), ("branch-" + formatId).getBytes());
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
