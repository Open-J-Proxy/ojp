package org.openjproxy.jdbc.h2;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class H2XAIntegrationTest {

    private static boolean isH2TestEnabled;
    private XAConnection xaConnection;
    private XAConnection secondXaConnection;
    private Connection connection;
    private Connection verificationConnection;
    private String tableName;

    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (verificationConnection != null && tableName != null) {
            try (Statement statement = verificationConnection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + tableName);
            }
        }
        if (connection != null) {
            connection.close();
        }
        if (xaConnection != null) {
            xaConnection.close();
        }
        if (secondXaConnection != null) {
            secondXaConnection.close();
        }
        if (verificationConnection != null) {
            verificationConnection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldSupportXaLifecycleAndRecovery(
            String driverClass, String url, String user, String password) throws Exception {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        tableName = "h2_xa_" + System.currentTimeMillis();

        verificationConnection = DriverManager.getConnection(url, user, password);
        try (Statement statement = verificationConnection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName +
                    " (id INT PRIMARY KEY, value VARCHAR(100))");
        }

        OjpXADataSource dataSource = new OjpXADataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        xaConnection = dataSource.getXAConnection(user, password);
        secondXaConnection = dataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
        XAResource resource = xaConnection.getXAResource();

        assertNotNull(resource);
        assertFalse(connection.isClosed());
        assertFalse(connection.getAutoCommit());
        assertTrue(resource.isSameRM(secondXaConnection.getXAResource()));

        Xid twoPhaseXid = new TestXid(1, "h2-two-phase".getBytes(), "branch-1".getBytes());
        resource.start(twoPhaseXid, XAResource.TMNOFLAGS);
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "INSERT INTO " + tableName + " VALUES (1, 'two-phase')"));
        }
        resource.end(twoPhaseXid, XAResource.TMSUCCESS);
        int prepareResult = resource.prepare(twoPhaseXid);
        assertEquals(XAResource.XA_OK, prepareResult);
        Xid[] recovered = resource.recover(XAResource.TMSTARTRSCAN);
        assertTrue(Arrays.stream(recovered).anyMatch(xid -> sameXid(xid, twoPhaseXid)),
                "Recovery scan should return the prepared XID");
        resource.recover(XAResource.TMENDRSCAN);
        resource.commit(twoPhaseXid, false);
        assertEquals(1, rowCount(verificationConnection, tableName, 1));

        Xid rollbackXid = new TestXid(2, "h2-rollback".getBytes(), "branch-2".getBytes());
        resource.start(rollbackXid, XAResource.TMNOFLAGS);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + tableName + " VALUES (2, 'rollback')");
        }
        resource.end(rollbackXid, XAResource.TMSUCCESS);
        resource.rollback(rollbackXid);
        assertEquals(0, rowCount(verificationConnection, tableName, 2));

        Xid onePhaseXid = new TestXid(3, "h2-one-phase".getBytes(), "branch-3".getBytes());
        resource.start(onePhaseXid, XAResource.TMNOFLAGS);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + tableName + " VALUES (3, 'one-phase')");
        }
        resource.end(onePhaseXid, XAResource.TMSUCCESS);
        resource.commit(onePhaseXid, true);
        assertEquals(1, rowCount(verificationConnection, tableName, 3));

        boolean timeoutSet = resource.setTransactionTimeout(30);
        assertEquals(timeoutSet ? 30 : 0, resource.getTransactionTimeout());
        resource.setTransactionTimeout(0);

        try {
            resource.forget(onePhaseXid);
        } catch (javax.transaction.xa.XAException ignored) {
            // H2 may reject forget for an already completed branch.
        }
    }

    private static int rowCount(Connection connection, String table, int id) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE id = " + id)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static boolean sameXid(Xid left, Xid right) {
        return left.getFormatId() == right.getFormatId() &&
                Arrays.equals(left.getGlobalTransactionId(), right.getGlobalTransactionId()) &&
                Arrays.equals(left.getBranchQualifier(), right.getBranchQualifier());
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
