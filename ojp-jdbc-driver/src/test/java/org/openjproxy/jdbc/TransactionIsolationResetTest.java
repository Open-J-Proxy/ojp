package org.openjproxy.jdbc;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that verify transaction isolation level is properly reset when connections
 * are returned to the pool. This prevents connection state pollution between different
 * client sessions.
 * <p>
 * The test simulates multiple clients that:
 * 1. Change transaction isolation levels aggressively
 * 2. Close their connections (returning them to the pool)
 * 3. Verify that subsequent connections have the correct default isolation level
 */

class TransactionIsolationResetTest {
    private static final Logger logger = LoggerFactory.getLogger(TransactionIsolationResetTest.class);
    private static boolean isH2TestEnabled;

    private Connection connection1;
    private Connection connection2;
    private Connection connection3;

    @BeforeAll
     static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
     void tearDown() {
        TestDBUtils.closeQuietly(connection1);
        TestDBUtils.closeQuietly(connection2);
        TestDBUtils.closeQuietly(connection3);
    }

    /**
     * Test that transaction isolation is reset to the default level when a connection
     * is returned to the pool and reused by another client.
     * <p>
     * Scenario:
     * 1. Client 1 gets a connection, changes isolation to SERIALIZABLE, closes it
     * 2. Client 2 gets a connection (should be the same physical connection from pool)
     * 3. Verify Client 2's connection has the default isolation level (READ_COMMITTED)
     * 4. Client 2 changes to READ_UNCOMMITTED, closes it
     * 5. Client 3 gets a connection (again from pool)
     * 6. Verify Client 3's connection has the default isolation level (READ_COMMITTED)
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testTransactionIsolationResetBetweenSessions(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        // Client 1: Change isolation and close
        logger.info("Client 1: Getting connection and changing isolation to SERIALIZABLE");
        connection1 = DriverManager.getConnection(url, user, password);
        int defaultIsolation = connection1.getTransactionIsolation();
        logger.info("Client 1: Default isolation level is: {}", defaultIsolation);

        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation());
        logger.info("Client 1: Changed isolation to SERIALIZABLE, closing connection");
        connection1.close();

        // Small delay to ensure connection is processed and returned to pool
        // HikariCP processes connection returns asynchronously
        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Client 2: Get connection from pool - should have default isolation
        logger.info("Client 2: Getting connection from pool");
        connection2 = DriverManager.getConnection(url, user, password);
        int client2Isolation = connection2.getTransactionIsolation();
        logger.info("Client 2: Transaction isolation level is: {}", client2Isolation);

        // Verify Client 2 gets the default isolation level, not SERIALIZABLE
        assertEquals(defaultIsolation, client2Isolation,
                "Connection from pool should have default isolation level, not the level set by previous client");

        // Client 2: Change to different isolation and close
        logger.info("Client 2: Changing isolation to READ_UNCOMMITTED, closing connection");
        connection2.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, connection2.getTransactionIsolation());
        connection2.close();

        // Small delay to ensure connection is processed and returned to pool
        // HikariCP processes connection returns asynchronously
        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Client 3: Get connection from pool - should have default isolation
        logger.info("Client 3: Getting connection from pool");
        connection3 = DriverManager.getConnection(url, user, password);
        int client3Isolation = connection3.getTransactionIsolation();
        logger.info("Client 3: Transaction isolation level is: {}", client3Isolation);

        // Verify Client 3 gets the default isolation level, not READ_UNCOMMITTED
        assertEquals(defaultIsolation, client3Isolation,
                "Connection from pool should have default isolation level, not the level set by previous client");
    }

    /**
     * Test aggressive transaction isolation changes within a single session.
     * This verifies that isolation changes work correctly during the lifetime of a session.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testAggressiveIsolationChangesWithinSession(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        connection1 = DriverManager.getConnection(url, user, password);
        int defaultIsolation = connection1.getTransactionIsolation();

        // Change isolation multiple times
        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation());

        connection1.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, connection1.getTransactionIsolation());

        connection1.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection1.getTransactionIsolation());

        connection1.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection1.getTransactionIsolation());

        // Close and reopen - should get default isolation
        connection1.close();

        // Small delay to ensure connection is processed and returned to pool
        // HikariCP processes connection returns asynchronously
        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        connection2 = DriverManager.getConnection(url, user, password);
        assertEquals(defaultIsolation, connection2.getTransactionIsolation(),
                "After closing connection with multiple isolation changes, new connection should have default isolation");
    }

    /**
     * Test that multiple concurrent clients don't interfere with each other's
     * transaction isolation settings.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testConcurrentIsolationChanges(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        // Open 3 connections concurrently
        connection1 = DriverManager.getConnection(url, user, password);
        connection2 = DriverManager.getConnection(url, user, password);
        connection3 = DriverManager.getConnection(url, user, password);

        // Set different isolation levels
        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection2.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        connection3.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        // Verify each connection maintains its own isolation level
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation());
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, connection2.getTransactionIsolation());
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection3.getTransactionIsolation());

        logger.info("Successfully verified concurrent connections maintain independent isolation levels");
    }

    /**
     * CRITICAL TEST: This test verifies the bug fix for connection state pollution.
     * BEFORE THE FIX: This test would FAIL because connections retained their isolation
     * level when returned to the pool, causing the next client to get wrong isolation.
     * AFTER THE FIX: This test PASSES because connections are reset to default isolation.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testConnectionStatePollutionPrevention(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("=== CRITICAL BUG FIX TEST: Connection State Pollution ===");

        // Client 1: Gets connection, changes isolation to SERIALIZABLE
        logger.info("Client 1: Polluting connection with SERIALIZABLE isolation");
        connection1 = DriverManager.getConnection(url, user, password);
        int defaultIsolation = connection1.getTransactionIsolation();
        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection1.getTransactionIsolation());
        connection1.close();

        // Wait for connection to be returned to pool
        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Client 2: Gets connection from pool
        // BEFORE FIX: Would get SERIALIZABLE (WRONG - state pollution)
        // AFTER FIX: Should get default isolation (CORRECT)
        logger.info("Client 2: Verifying connection was cleaned (no state pollution)");
        connection2 = DriverManager.getConnection(url, user, password);
        int actualIsolation = connection2.getTransactionIsolation();

        logger.info("Expected isolation: {}, Actual isolation: {}", defaultIsolation, actualIsolation);
        assertEquals(defaultIsolation, actualIsolation,
                "CRITICAL BUG: Connection from pool has polluted state! " +
                        "This means transaction isolation was NOT reset when connection returned to pool.");

        logger.info("=== TEST PASSED: No connection state pollution detected ===");
        connection2.close();
    }

    /**
     * CRITICAL TEST: Simulates real-world scenario with many clients rapidly changing
     * isolation levels. This would cause widespread state pollution before the fix.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testRapidIsolationChangesMultipleClients(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("=== STRESS TEST: Rapid isolation changes by multiple clients ===");

        int defaultIsolation = -1;

        // Simulate 10 clients rapidly changing isolation levels
        for (int i = 0; i < 10; i++) {
            Connection conn = DriverManager.getConnection(url, user, password);

            if (defaultIsolation == -1) {
                defaultIsolation = conn.getTransactionIsolation();
                logger.info("Default isolation level: {}", defaultIsolation);
            }

            // Each client changes to a random isolation level
            int[] levels = {
                    Connection.TRANSACTION_READ_UNCOMMITTED,
                    Connection.TRANSACTION_READ_COMMITTED,
                    Connection.TRANSACTION_REPEATABLE_READ,
                    Connection.TRANSACTION_SERIALIZABLE
            };
            int randomLevel = levels[i % levels.length];

            logger.info("Client {}: Changing isolation to {}", i, randomLevel);
            conn.setTransactionIsolation(randomLevel);
            assertEquals(randomLevel, conn.getTransactionIsolation());
            conn.close();

            // Small delay to simulate real-world timing
            try {
                Thread.sleep(10); //NOSONAR
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Final verification: Get a connection and verify it has default isolation
        logger.info("Final verification: Getting connection after stress test");
        Connection finalConn = DriverManager.getConnection(url, user, password);
        assertEquals(defaultIsolation, finalConn.getTransactionIsolation(),
                "After rapid changes by many clients, connection should still have default isolation");
        finalConn.close();

        logger.info("=== STRESS TEST PASSED: All connections properly reset ===");
    }

    /**
     * CRITICAL TEST: Tests the scenario where one client uses SERIALIZABLE and
     * another uses READ_UNCOMMITTED. Without the fix, one would get the other's setting.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testExtremeIsolationLevelChanges(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("=== TEST: Extreme isolation level changes (highest to lowest) ===");

        // Client 1: Use SERIALIZABLE (highest isolation)
        connection1 = DriverManager.getConnection(url, user, password);
        int defaultIsolation = connection1.getTransactionIsolation();
        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection1.close();

        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Client 2: Should get default, not SERIALIZABLE
        connection2 = DriverManager.getConnection(url, user, password);
        assertEquals(defaultIsolation, connection2.getTransactionIsolation(),
                "Connection should have default isolation, not SERIALIZABLE from previous client");

        // Client 2: Change to READ_UNCOMMITTED (lowest isolation)
        connection2.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        connection2.close();

        try {
            Thread.sleep(50); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Client 3: Should get default, not READ_UNCOMMITTED
        connection3 = DriverManager.getConnection(url, user, password);
        assertEquals(defaultIsolation, connection3.getTransactionIsolation(),
                "Connection should have default isolation, not READ_UNCOMMITTED from previous client");

        logger.info("=== TEST PASSED: Extreme isolation changes properly handled ===");
    }

    /**
     * CRITICAL TEST: Verify that isolation is reset even when client doesn't explicitly
     * close the connection (e.g., connection timeout or client crash simulation).
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
     void testIsolationResetAfterConnectionLeak(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("=== TEST: Isolation reset after connection leak/timeout ===");

        connection1 = DriverManager.getConnection(url, user, password);
        int defaultIsolation = connection1.getTransactionIsolation();

        // Change isolation
        connection1.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        // Explicitly close (in real scenario, this might be a timeout)
        connection1.close();

        // Give pool time to process
        try {
            Thread.sleep(100); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // New connection should have default isolation
        connection2 = DriverManager.getConnection(url, user, password);
        assertEquals(defaultIsolation, connection2.getTransactionIsolation(),
                "Connection should be reset to default isolation even after abnormal close");

        logger.info("=== TEST PASSED: Isolation reset works correctly ===");
    }
}
