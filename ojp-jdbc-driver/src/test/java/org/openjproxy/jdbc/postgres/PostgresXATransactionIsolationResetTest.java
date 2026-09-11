package org.openjproxy.jdbc.postgres;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for XA transaction isolation reset behavior with PostgreSQL.
 * These tests verify that transaction isolation levels are properly reset when
 * XA connections return to the pool, preventing state pollution between sessions.
 * <p>
 * These tests require:
 * 1. A running OJP server (localhost:1059)
 * 2. A PostgreSQL database with XA support
 * 3. Enable with -DenablePostgresTests=true
 */

class PostgresXATransactionIsolationResetTest {
    private static final Logger logger = LoggerFactory.getLogger(PostgresXATransactionIsolationResetTest.class);
    private static boolean isTestEnabled;
    private OjpXADataSource xaDataSource;  // Reuse same datasource to share XA pool
    private XAConnection xaConnection1;
    private XAConnection xaConnection2;
    private XAConnection xaConnection3;
    private Connection connection1;
    private Connection connection2;
    private Connection connection3;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) {
        assumeFalse(!isTestEnabled, "Postgres XA isolation tests are disabled. Enable with -DenablePostgresTests=true");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        // Create shared XA DataSource - this ensures all connections share the same XA session pool
        xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);

        // Note: Default transaction isolation is READ_COMMITTED (hardcoded in server)
        // Tests verify that isolation resets back to READ_COMMITTED after connection use
    }

    private XAConnection createXAConnection(String url, String user, String password) throws SQLException {
        logger.info("Creating XAConnection for Postgres XA with the url: {}", url);
        // Reuse the shared datasource instance so connections share the XA pool
        return xaDataSource.getXAConnection(user, password);
    }

    @AfterEach
     void tearDown() {
        TestDBUtils.closeQuietly(connection1);
        TestDBUtils.closeQuietly(connection2);
        TestDBUtils.closeQuietly(connection3);

        if (xaConnection1 != null) {
            try {
                xaConnection1.close();
            } catch (Exception e) {
                logger.warn("Error closing XA connection 1: {}", e.getMessage());
            }
        }
        if (xaConnection2 != null) {
            try {
                xaConnection2.close();
            } catch (Exception e) {
                logger.warn("Error closing XA connection 2: {}", e.getMessage());
            }
        }
        if (xaConnection3 != null) {
            try {
                xaConnection3.close();
            } catch (Exception e) {
                logger.warn("Error closing XA connection 3: {}", e.getMessage());
            }
        }
    }

    /**
     * CRITICAL TEST: Verifies that transaction isolation level is properly reset when
     * XA connections are returned to the pool. This prevents connection state pollution.
     * <p>
     * Scenario that would FAIL before the fix:
     * 1. Client A gets XA connection with default isolation (READ_COMMITTED)
     * 2. Client A changes isolation to SERIALIZABLE
     * 3. Client A closes connection (returns to pool)
     * 4. Client B gets XA connection from pool
     * 5. WITHOUT FIX: Client B would get connection with SERIALIZABLE isolation
     * 6. WITH FIX: Client B gets connection with READ_COMMITTED isolation (properly reset)
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXAConnectionStatePollutionPrevention(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Client A: Get XA connection and verify default isolation
        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();

        int defaultIsolation = connection1.getTransactionIsolation();
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, defaultIsolation,
                "Default isolation should be READ_COMMITTED");
        logger.info("Client A: Default isolation level is READ_COMMITTED");

        // Client A: Change isolation to SERIALIZABLE within XA transaction
        XAResource xaResource1 = xaConnection1.getXAResource();
        Xid xid1 = new TestXid(1, "gtrid-1".getBytes(), "bqual-1".getBytes());
        xaResource1.start(xid1, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation(),
                "Isolation should be changed to SERIALIZABLE");
        logger.info("Client A: Changed isolation to SERIALIZABLE");

        xaResource1.end(xid1, XAResource.TMSUCCESS);
        xaResource1.commit(xid1, true);

        // Client A: Close connection (return to pool)
        connection1.close();
        xaConnection1.close();
        logger.info("Client A: Closed XA connection, returned to pool");

        // Delay to ensure connection is processed and backend session returned to pool
        // XA pool processing may take longer than regular connection pools
        Thread.sleep(500); //NOSONAR

        // Client B: Get XA connection from pool
        xaConnection2 = createXAConnection(url, user, password);
        connection2 = xaConnection2.getConnection();
        logger.info("Client B: Obtained XA connection from pool");

        // CRITICAL ASSERTION: Verify isolation is reset to READ_COMMITTED
        int isolationAfterReset = connection2.getTransactionIsolation();
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolationAfterReset,
                "Isolation should be reset to READ_COMMITTED (pool should reset state)");
        logger.info("Client B: Isolation correctly reset to READ_COMMITTED");

        // Close Client B's connection
        connection2.close();
        xaConnection2.close();
    }

    /**
     * Tests that rapidly changing isolation levels across multiple XA clients
     * doesn't cause pool pollution. This stress test simulates aggressive
     * isolation level changes.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    void testXARapidIsolationChangesMultipleClients(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        int[] isolationLevels = {
                Connection.TRANSACTION_READ_UNCOMMITTED,
                Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_SERIALIZABLE
        };

        // Simulate 10 clients rapidly changing isolation levels
        for (int i = 0; i < 10; i++) {
            XAConnection xaConn = createXAConnection(url, user, password);

            try (Connection conn = xaConn.getConnection()) {
                XAResource xaResource = xaConn.getXAResource();
                // Start XA transaction
                Xid xid = new TestXid(i + 1, ("gtrid-" + i).getBytes(), ("bqual-" + i).getBytes());
                xaResource.start(xid, XAResource.TMNOFLAGS);

                // Change to random isolation level
                int targetIsolation = isolationLevels[i % isolationLevels.length];
                conn.setTransactionIsolation(targetIsolation);
                logger.info("Client {}: Set isolation to {}", i, targetIsolation);

                // End and commit transaction
                xaResource.end(xid, XAResource.TMSUCCESS);
                xaResource.commit(xid, true);

            } finally {
                xaConn.close();
            }

            // Small delay
            Thread.sleep(10); //NOSONAR
        }

        // After all the churn, verify next connection has default isolation
        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection1.getTransactionIsolation(),
                "After multiple clients with different isolation levels, new connection should have READ_COMMITTED");
        logger.info("Final verification: Isolation correctly maintained at READ_COMMITTED");
    }

    /**
     * Tests extreme isolation level transitions (lowest to highest and vice versa)
     * to ensure reset works correctly for all isolation levels.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXAExtremeIsolationLevelChanges(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Client 1: Change to highest isolation (SERIALIZABLE)
        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();
        XAResource xaResource1 = xaConnection1.getXAResource();

        Xid xid1 = new TestXid(1, "gtrid-high".getBytes(), "bqual-high".getBytes());
        xaResource1.start(xid1, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation());
        logger.info("Client 1: Set to highest isolation (SERIALIZABLE)");

        xaResource1.end(xid1, XAResource.TMSUCCESS);
        xaResource1.commit(xid1, true);

        connection1.close();
        xaConnection1.close();
        Thread.sleep(500); //NOSONAR

        // Client 2: Get connection, verify reset, change to lowest isolation
        xaConnection2 = createXAConnection(url, user, password);
        connection2 = xaConnection2.getConnection();
        XAResource xaResource2 = xaConnection2.getXAResource();

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection2.getTransactionIsolation(),
                "Should be reset to READ_COMMITTED after SERIALIZABLE");

        Xid xid2 = new TestXid(2, "gtrid-low".getBytes(), "bqual-low".getBytes());
        xaResource2.start(xid2, XAResource.TMNOFLAGS);

        connection2.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, connection2.getTransactionIsolation());
        logger.info("Client 2: Set to lowest isolation (READ_UNCOMMITTED)");

        xaResource2.end(xid2, XAResource.TMSUCCESS);
        xaResource2.commit(xid2, true);

        connection2.close();
        xaConnection2.close();
        Thread.sleep(500);//NOSONAR

        // Client 3: Verify reset to default again
        xaConnection3 = createXAConnection(url, user, password);
        connection3 = xaConnection3.getConnection();

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection3.getTransactionIsolation(),
                "Should be reset to READ_COMMITTED after READ_UNCOMMITTED");
        logger.info("Client 3: Correctly reset to READ_COMMITTED");
    }

    /**
     * Tests that basic isolation reset works within a simple XA transaction workflow.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXABasicIsolationReset(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Get XA connection
        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();
        XAResource xaResource = xaConnection1.getXAResource();

        // Verify default
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection1.getTransactionIsolation());

        // Start XA transaction and change isolation
        Xid xid = new TestXid(1, "basic-test".getBytes(), "branch-1".getBytes());
        xaResource.start(xid, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection1.getTransactionIsolation());

        xaResource.end(xid, XAResource.TMSUCCESS);
        xaResource.commit(xid, true);

        // Close and reopen
        connection1.close();
        xaConnection1.close();
        Thread.sleep(500); //NOSONAR

        xaConnection2 = createXAConnection(url, user, password);
        connection2 = xaConnection2.getConnection();

        // Verify reset
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection2.getTransactionIsolation(),
                "Isolation should be reset after connection returned to pool");
    }

    /**
     * Tests multiple isolation changes within the same XA session.
     * Verifies that only the final state needs to be reset when connection is closed.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXAMultipleIsolationChangesInSession(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();
        XAResource xaResource = xaConnection1.getXAResource();

        // Transaction 1: Change isolation multiple times
        Xid xid1 = new TestXid(1, "multi-change-1".getBytes(), "branch-1".getBytes());
        xaResource.start(xid1, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection1.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection1.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);

        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, connection1.getTransactionIsolation());

        xaResource.end(xid1, XAResource.TMSUCCESS);
        xaResource.commit(xid1, true);

        // Transaction 2: Change again
        Xid xid2 = new TestXid(2, "multi-change-2".getBytes(), "branch-2".getBytes());
        xaResource.start(xid2, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        xaResource.end(xid2, XAResource.TMSUCCESS);
        xaResource.commit(xid2, true);

        // Close connection
        connection1.close();
        xaConnection1.close();
        Thread.sleep(500);//NOSONAR

        // New connection should have default isolation
        xaConnection2 = createXAConnection(url, user, password);
        connection2 = xaConnection2.getConnection();

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection2.getTransactionIsolation(),
                "After multiple changes in same session, isolation should reset to READ_COMMITTED");
    }

    /**
     * Verifies that the default isolation level for new XA connections is READ_COMMITTED.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXADefaultIsolationLevel(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();

        int defaultIsolation = connection1.getTransactionIsolation();
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, defaultIsolation,
                "Default isolation level should be READ_COMMITTED");

        logger.info("Verified: Default XA isolation level is READ_COMMITTED");
    }

    /**
     * Tests custom configured isolation level.
     * <p>
     * DISABLED: This test relies on being able to configure custom default transaction isolation
     * at the server level. Since the OJP server is shared across all tests, changing this
     * configuration would interfere with other tests. This scenario should be covered by
     * unit tests in the ojp-server module instead.
     * <p>
     * TODO: Create unit tests in ojp-server module to cover custom isolation configuration
     */
    // @ParameterizedTest
    // @CsvFileSource(resources = "/postgres_xa_connection.csv")
    // @Disabled("Test requires changing server-wide configuration which interferes with other tests")
     void testXAConfiguredCustomIsolation(String driverClass, String url, String user, String password) throws Exception {
        // DISABLED - See comment above
    }

    /**
     * Tests isolation reset after abnormal connection closure.
     * Simulates a connection leak scenario where connection is not properly closed.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
     void testXAIsolationResetAfterConnectionLeak(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Create connection and change isolation
        xaConnection1 = createXAConnection(url, user, password);
        connection1 = xaConnection1.getConnection();
        XAResource xaResource1 = xaConnection1.getXAResource();

        Xid xid1 = new TestXid(1, "leak-test".getBytes(), "branch-1".getBytes());
        xaResource1.start(xid1, XAResource.TMNOFLAGS);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        xaResource1.end(xid1, XAResource.TMSUCCESS);
        xaResource1.commit(xid1, true);

        // Simulate leak: Just close logical connection, not XA connection
        connection1.close();
        // Don't close xaConnection1 immediately - simulate leak

        Thread.sleep(100);//NOSONAR

        // Eventually close the leaked XA connection
        xaConnection1.close();
        Thread.sleep(500);//NOSONAR

        // Get new connection - should still have proper default isolation
        xaConnection2 = createXAConnection(url, user, password);
        connection2 = xaConnection2.getConnection();

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection2.getTransactionIsolation(),
                "Even after connection leak, new connection should have correct default isolation");
        logger.info("Verified: Isolation reset works even after simulated connection leak");
    }

    /**
     * Simple Xid implementation for testing.
     */
    private static class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        public TestXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
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
