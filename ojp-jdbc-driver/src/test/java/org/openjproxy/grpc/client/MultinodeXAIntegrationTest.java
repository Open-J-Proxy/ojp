package org.openjproxy.grpc.client;

import io.grpc.StatusRuntimeException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.plexus.util.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Multinode XA Integration Test
 * 
 * Configuration:
 * - Non-XA pooling: DISABLED (multinode.ojp.connection.pool.enabled=false)
 * - XA pooling: ENABLED with max pool size of 22 connections (min idle: 20)
 * - Expected PostgreSQL connections: 20-25 total (XA pool only, no non-XA pool overhead)
 * 
 * This test validates XA transaction behavior across multiple OJP server nodes
 * with isolation from non-XA connection pooling.
 */
@Slf4j
public class MultinodeXAIntegrationTest {
    private static final int THREADS = 5; // Number of worker threads
    private static final int RAMPUP_MS = 50 * 1000; // 50 seconds Ramp-up window in milliseconds
    
    // Retry configuration for connection-level failures
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100; // Initial delay between retries
    
    // Test failure thresholds
    // Total failures can be higher due to session invalidation when servers restart
    // Session invalidation is a consequence of server failure and is expected during testing
    private static final int MAX_TOTAL_FAILURES = 50;
    // Non-connectivity failures should be zero - all failures should be connectivity-related
    // (including session invalidation, which is caused by server unavailability)
    private static final int MAX_NON_CONNECTIVITY_FAILURES = 0;

    // After the SQL query phase the JVM stays alive for this long so the daemon health-check
    // thread keeps running during the CI kill/restart verification cycle.
    // The CI can cut this short by creating /tmp/multinode-xa-ci-done.
    private static final long KEEPALIVE_MAX_MS = TimeUnit.MINUTES.toMillis(12);
    private static final long KEEPALIVE_POLL_INTERVAL_MS = 5_000L;

    protected static boolean isTestDisabled;
    private static Queue<Long> queryDurations = new ConcurrentLinkedQueue<>();
    private static AtomicInteger totalQueries = new AtomicInteger(0);
    private static AtomicInteger totalFailedQueries = new AtomicInteger(0);
    private static final AtomicInteger nonConnectivityFailedQueries = new AtomicInteger(0);
    private static final ExecutorService queryExecutor = new RoundRobinExecutorService(100);
    private static MultinodeTestHelper testHelper;

    private static OjpXADataSource xaDataSource;

    @BeforeAll
    static void checkTestConfiguration() {
        // Check both system property and environment variable
        boolean sysPropEnabled = Boolean.parseBoolean(System.getProperty("multinodeXATestsEnabled", "false"));
        boolean envVarEnabled = Boolean.parseBoolean(System.getenv("MULTINODE_XA_TESTS_ENABLED"));
        isTestDisabled = !(sysPropEnabled || envVarEnabled);
    }

    @SneakyThrows
    public void setUp() throws SQLException {
        queryDurations = new ConcurrentLinkedQueue<>();
        totalQueries = new AtomicInteger(0);
        totalFailedQueries = new AtomicInteger(0);
        testHelper = new MultinodeTestHelper(totalFailedQueries, nonConnectivityFailedQueries);
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_connection.csv")
    void runTests(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Multinode tests are disabled");

        this.setUp();
        // 1. Schema and seeding (using non-XA connection for setup - no pooling due to test configuration)
        Class.forName(driverClass);
        try (Connection conn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = conn.createStatement();
            stmt.execute(
                    "DROP TABLE IF EXISTS order_items CASCADE;" +
                            "DROP TABLE IF EXISTS reviews CASCADE;" +
                            "DROP TABLE IF EXISTS orders CASCADE;" +
                            "DROP TABLE IF EXISTS products CASCADE;" +
                            "DROP TABLE IF EXISTS users CASCADE;" +

                            "CREATE TABLE users (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  username VARCHAR(50)," +
                            "  email VARCHAR(100)" +
                            ");" +
                            "CREATE TABLE products (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  name VARCHAR(100)," +
                            "  price DECIMAL" +
                            ");" +
                            "CREATE TABLE orders (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  user_id INT REFERENCES users(id)," +
                            "  order_date TIMESTAMP DEFAULT now()" +
                            ");" +
                            "CREATE TABLE order_items (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  order_id INT REFERENCES orders(id)," +
                            "  product_id INT REFERENCES products(id)," +
                            "  quantity INT" +
                            ");" +
                            "CREATE TABLE reviews (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  user_id INT REFERENCES users(id)," +
                            "  product_id INT REFERENCES products(id)," +
                            "  rating INT," +
                            "  comment TEXT" +
                            ");"
            );
            stmt.close();

            // Seed data
            System.out.println("Seeding users...");
            conn.createStatement().execute(
                    "INSERT INTO users (username, email) " +
                            "SELECT 'user' || g, 'user' || g || '@example.com' FROM generate_series(1,10000) g"
            );
            System.out.println("Seeding products...");
            conn.createStatement().execute(
                    "INSERT INTO products (name, price) " +
                            "SELECT 'Product ' || g, (random()*1000)::int + 1 FROM generate_series(1,1000) g"
            );
            System.out.println("Seeding orders...");
            conn.createStatement().execute(
                    "INSERT INTO orders (user_id, order_date) " +
                            "SELECT (random()*9999 + 1)::int, NOW() - INTERVAL '1 day' * (random()*365)::int FROM generate_series(1,50000) g"
            );
            System.out.println("Seeding order_items...");
            conn.createStatement().execute(
                    "INSERT INTO order_items (order_id, product_id, quantity) " +
                            "SELECT (random()*49999+1)::int, (random()*999+1)::int, (random()*10+1)::int FROM generate_series(1,100000) g"
            );
            System.out.println("Seeding reviews...");
            conn.createStatement().execute(
                    "INSERT INTO reviews (user_id, product_id, rating, comment) " +
                            "SELECT (random()*9999+1)::int, (random()*999+1)::int, (random()*5+1)::int, 'review ' || g FROM generate_series(1,30000) g"
            );
        }

        // 2. Test timing with ramp-up
        long globalStart = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        int rampupPerThread = (THREADS > 1) ? RAMPUP_MS / (THREADS - 1) : 0;
        for (int t = 0; t < THREADS; t++) {

            final int threadNum = t;
            executor.submit(() -> {
                try {
                    // Ramp-up delay for this thread
                    if (threadNum > 0) Thread.sleep(threadNum * rampupPerThread);
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
                try {
                    runExactQuerySequence(threadNum, driverClass, url, user, password);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(1000);
        }
        long globalEnd = System.nanoTime();

        // 3. Reporting
        int numQueries = totalQueries.get();
        int numTotalFailures = totalFailedQueries.get();
        int numNonConnectivityFailures = nonConnectivityFailedQueries.get();
        long totalTimeMs = (globalEnd - globalStart) / 1_000_000;
        double avgQueryMs = numQueries > 0
                ? queryDurations.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0
                : 0;

        Thread.sleep(5000); //Wait for any pending queries and logs to flush
        System.out.println("\n=== TEST REPORT ===");
        System.out.println("Total queries executed: " + numQueries);
        System.out.println("Total test duration: " + totalTimeMs + " ms");
        System.out.printf("Average query duration: %.3f ms\n", avgQueryMs);
        System.out.println("Total query failures: " + numTotalFailures);
        System.out.println("Total non-connectivity-related failures: " + numNonConnectivityFailures);
        //Assertions.assertEquals(2160, numQueries);
        assertTrue(numTotalFailures < MAX_TOTAL_FAILURES,
            "Expected fewer than " + MAX_TOTAL_FAILURES + " total failures, but got: " + numTotalFailures);
        assertEquals(MAX_NON_CONNECTIVITY_FAILURES, numNonConnectivityFailures,
            "Expected " + MAX_NON_CONNECTIVITY_FAILURES + " non-connectivity failures (session invalidation is connectivity-related), but got: " + numNonConnectivityFailures);
        assertTrue(totalTimeMs < 180000, "Total test time too high: " + totalTimeMs + " ms");
        assertTrue(avgQueryMs < 1000.0, "Average query time too high: " + avgQueryMs + " ms");

        // 4. Keepalive phase
        // After the SQL phase the test assertions are done, but the CI kill/restart verification
        // cycle is still running.  Without this phase the Maven JVM would exit immediately,
        // killing the daemon health-check thread.
        //
        // Each iteration creates a fresh XAConnection so that the current cluster-health
        // (number of healthy servers) is included in the connect() RPC sent to the surviving
        // server.  The server's MultinodePoolCoordinator uses this to recalculate minIdle:
        //   - 2 healthy servers → minIdle = ceil(20/2) = 10 per server (total 20)
        //   - 1 healthy server  → minIdle = ceil(20/1) = 20 on survivor (total 20)
        // HikariCP then proactively creates connections to reach the new minIdle, keeping
        // pg_stat_activity at or above the CI threshold of 20 throughout kill/restart cycles.
        //
        // Using DriverManager (non-XA) here would NOT work: the cluster-health push from
        // pushClusterHealthToAllHealthyServers() only covers non-XA pools; XA pools are
        // updated only when a new XA connect() RPC is sent with the updated health value.
        //
        // The CI signals us to stop by creating /tmp/multinode-xa-ci-done.
        // We also enforce a hard cap (KEEPALIVE_MAX_MS) so we never block indefinitely.
        System.out.println("\n=== KEEPALIVE PHASE: holding JVM open for CI kill/restart checks ===");
        try {
            new File("/tmp/multinode-xa-queries-done").createNewFile();
        } catch (Exception ignored) {
        }
        File ciDoneFile = new File("/tmp/multinode-xa-ci-done");
        long keepaliveEnd = System.currentTimeMillis() + KEEPALIVE_MAX_MS;
        while (!ciDoneFile.exists() && System.currentTimeMillis() < keepaliveEnd) {
            try {
                XAConnection xaConn = xaDataSource.getXAConnection();
                try {
                    xaConn.getConnection(); // sends connect() RPC with current cluster health
                } finally {
                    xaConn.close();
                }
            } catch (Exception ignored) {
                // Expected during server kills/restarts
            }
            Thread.sleep(KEEPALIVE_POLL_INTERVAL_MS);
        }
        System.out.println("=== KEEPALIVE PHASE complete ===");
    }

    @SneakyThrows
    private static void timeAndRun(Callable<Void> query) {
        // Run each query in its own thread to better test multinode balancing
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            Exception lastException = null;
            boolean succeeded = false;
            
            // Retry logic for connection-level errors
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    query.call();
                    succeeded = true;
                    break; // Success - exit retry loop
                } catch (Exception e) {
                    lastException = e;
                    
                    // Check if this is a connection-level error that should be retried
                    if (GrpcExceptionHandler.isConnectionLevelError(e) && attempt < MAX_RETRIES) {
                        // Connection-level error - retry after delay with exponential backoff
                        long delayMs = (long) (RETRY_DELAY_MS * Math.pow(2, attempt));
                        log.debug("Connection-level error on attempt {}, retrying after {}ms: {}", 
                            attempt + 1, delayMs, e.getMessage());
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break; // Exit retry loop if interrupted
                        }
                    } else {
                        // Either not a connection-level error, or we've exhausted retries
                        break;
                    }
                }
            }
            
            // If we didn't succeed after retries, count it as a failure
            if (!succeeded && lastException != null) {
                incrementFailures(lastException);
                System.err.println("Query failed after " + (MAX_RETRIES + 1) + " attempts: " + 
                    lastException.getMessage() + " \n " + ExceptionUtils.getStackTrace(lastException));
            }
            
            long end = System.nanoTime();
            queryDurations.add(end - start);
            totalQueries.incrementAndGet();

        }, queryExecutor);
        future.get();
    }

    @FunctionalInterface
    private interface SqlWork {
        void accept(Connection conn) throws Exception;
    }

    /**
     * Direct XA transaction wrapper: creates a new XAConnection, executes work inside an XA transaction,
     * and properly cleans up resources. 
     * 
     * NOTE: This test uses UNPOOLED non-XA mode (ojp.connection.pool.enabled=false) to isolate
     * XA connection behavior. Only XA connections will use pooling, with max pool size of 20-22 connections.
     * This ensures PostgreSQL connection count stays within expected limits (20-25 total connections).
     */
    private static void withXATx(
            String driverClass,
            String url,
            String user,
            String password,
            SqlWork work
    ) throws Exception {
        // Load driver if needed
        Class.forName(driverClass);
        
        // Initialize XADataSource if needed (client-side, no pooling)
        if (xaDataSource == null) {
            synchronized (MultinodeXAIntegrationTest.class) {
                if (xaDataSource == null) {
                    xaDataSource = new OjpXADataSource();
                    xaDataSource.setUrl(url);
                    xaDataSource.setUser(user);
                    xaDataSource.setPassword(password);
                    log.info("✓ OjpXADataSource initialized (client-side has no pooling; server-side XA pool max=20-22)");
                }
            }
        }
        
        // Create new XAConnection for this transaction
        XAConnection xaConn = xaDataSource.getXAConnection();
        Connection conn = null;
        XAResource xaRes = null;
        Xid xid = null;
        
        try {
            conn = xaConn.getConnection();
            xaRes = xaConn.getXAResource();
            
            // Generate unique Xid for this transaction
            xid = new SimpleXid(
                100, 
                ("gtrid_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId()).getBytes(),
                ("bqual_" + System.nanoTime()).getBytes()
            );
            
            // Start XA transaction
            xaRes.start(xid, XAResource.TMNOFLAGS);
            
            // Execute work
            work.accept(conn);
            
            // End and prepare XA transaction
            xaRes.end(xid, XAResource.TMSUCCESS);
            int prepareResult = xaRes.prepare(xid);
            
            // Commit if prepared successfully
            if (prepareResult == XAResource.XA_OK) {
                xaRes.commit(xid, false);
            }
            
        } catch (Exception e) {
            // Rollback on error
            if (xaRes != null && xid != null) {
                try {
                    xaRes.end(xid, XAResource.TMFAIL);
                    xaRes.rollback(xid);
                } catch (Exception rollbackEx) {
                    log.warn("XA rollback failed", rollbackEx);
                }
            }
            throw e;
        } finally {
            // Always close connection and XAConnection
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
            if (xaConn != null) {
                try {
                    xaConn.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Simple Xid implementation for XA transactions
     */
    private static class SimpleXid implements Xid {
        private final int formatId;
        private final byte[] gtrid;
        private final byte[] bqual;

        public SimpleXid(int formatId, byte[] gtrid, byte[] bqual) {
            this.formatId = formatId;
            this.gtrid = gtrid;
            this.bqual = bqual;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return gtrid;
        }

        @Override
        public byte[] getBranchQualifier() {
            return bqual;
        }
    }

    private static void runExactQuerySequence(int threadNum, String driverClass, String url, String user, String password) throws SQLException {

        // Transaction Block 1: create user, create order for that user, add order items
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstUser = conn.prepareStatement(
                            "INSERT INTO users (username, email) VALUES (?, ?) RETURNING id")) {
                        pstUser.setString(1, "txuser_" + threadNum);
                        pstUser.setString(2, "txuser_" + threadNum + "@example.com");
                        ResultSet rs = pstUser.executeQuery();
                        int userId = 0;
                        if (rs.next()) userId = rs.getInt(1);

                        int orderId = 0;
                        try (PreparedStatement pstOrder = conn.prepareStatement(
                                "INSERT INTO orders (user_id, order_date) VALUES (?, NOW()) RETURNING id")) {
                            pstOrder.setInt(1, userId);
                            ResultSet rsOrder = pstOrder.executeQuery();
                            if (rsOrder.next()) orderId = rsOrder.getInt(1);

                            // Add 3 items to this order
                            for (int i = 1; i <= 3; i++) {
                                try (PreparedStatement pstItem = conn.prepareStatement(
                                        "INSERT INTO order_items (order_id, product_id, quantity) VALUES (?, ?, ?)")) {
                                    pstItem.setInt(1, orderId);
                                    pstItem.setInt(2, i);
                                    pstItem.setInt(3, i);
                                    pstItem.execute();
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 2: update product price and log a review for the product
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstUpdate = conn.prepareStatement(
                            "UPDATE products SET price = price + 1 WHERE id = ?")) {
                        pstUpdate.setInt(1, 1);
                        pstUpdate.execute();
                        try (PreparedStatement pstReview = conn.prepareStatement(
                                "INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?, ?, ?, ?)")) {
                            pstReview.setInt(1, 1);
                            pstReview.setInt(2, 1);
                            pstReview.setInt(3, 4);
                            pstReview.setString(4, "Auto tx review");
                            pstReview.execute();
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 3: delete and recreate a review
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstDel = conn.prepareStatement(
                            "DELETE FROM reviews WHERE user_id = ? AND product_id = ?")) {
                        pstDel.setInt(1, 1);
                        pstDel.setInt(2, 1);
                        pstDel.execute();
                    }
                    try (PreparedStatement pstIns = conn.prepareStatement(
                            "INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?, ?, ?, ?)")) {
                        pstIns.setInt(1, 1);
                        pstIns.setInt(2, 1);
                        pstIns.setInt(3, 5);
                        pstIns.setString(4, "Recreated review");
                        pstIns.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Queries 4-100: Each query uses direct XA transaction management (new XAConnection per transaction)

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userA");
                        pst.setString(2, "userA@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userB");
                        pst.setString(2, "userB@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userC");
                        pst.setString(2, "userC@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed1@example.com");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed2@example.com");
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET username=? WHERE id=?")) {
                        pst.setString(1, "userA_updated");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userD");
                        pst.setString(2, "userD@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userE");
                        pst.setString(2, "userE@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed3@example.com");
                        pst.setInt(2, 3);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userF");
                        pst.setString(2, "userF@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // -- Product insert/update
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductA");
                        pst.setBigDecimal(2, new BigDecimal("123.45"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductB");
                        pst.setBigDecimal(2, new BigDecimal("67.89"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductC");
                        pst.setBigDecimal(2, new BigDecimal("250.00"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                        pst.setBigDecimal(1, new BigDecimal("199.99"));
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                        pst.setBigDecimal(1, new BigDecimal("299.99"));
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductD");
                        pst.setBigDecimal(2, new BigDecimal("111.11"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductE");
                        pst.setBigDecimal(2, new BigDecimal("77.77"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                        pst.setString(1, "ProductA-Updated");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                        pst.setString(1, "ProductB-Updated");
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductF");
                        pst.setBigDecimal(2, new BigDecimal("333.33"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // Various SELECT queries

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE user_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE user_id=?")) {
                        pst.setInt(1, 2);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders ORDER BY order_date DESC LIMIT 10")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT count(*) FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        log.info("select count of quantities from order_items for order_id = " + 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                Long sum = rs.getLong(1);
                                log.info("count returned = " + sum);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT SUM(quantity) FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        log.info("select sum of quantities from order_items for order_id = " + 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getObject(1);
                                if (rs.wasNull()) {
                                    log.info("Result was null");
                                } else {
                                    Long sum = rs.getLong(1);
                                    log.info("sum returned = " + sum);
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE product_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(rating) FROM reviews WHERE product_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE user_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT u.username, o.id FROM users u JOIN orders o ON u.id = o.user_id WHERE u.id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getString("username");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT oi.id, p.name FROM order_items oi JOIN products p ON oi.product_id=p.id WHERE oi.order_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT user_id, COUNT(*) FROM orders GROUP BY user_id")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("user_id");
                                rs.getInt(2);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // Loop of 7 * 3 queries
        for (int i = 0; i < 7; i++) {
            final int idx = i + 2;

            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM users WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM products WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
        }

        // Final simple counts
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM users")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // 5 heavy queries, all with direct XA transactions
        for (int i = 0; i < 5; i++) {
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement(
                                "SELECT" +
                                        "  u.id," +
                                        "  COUNT(o.id) AS num_orders," +
                                        "  SUM(oi.quantity) AS total_quantity" +
                                        " FROM users u" +
                                        " JOIN orders o ON u.id = o.user_id" +
                                        " JOIN order_items oi ON o.id = oi.order_id" +
                                        " GROUP BY u.id"
                        )) {
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt(1);
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
        }
    }

    private static void incrementFailures(Exception e) {
        testHelper.incrementFailures(e);
    }
}
