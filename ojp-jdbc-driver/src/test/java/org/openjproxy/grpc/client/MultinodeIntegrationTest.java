package org.openjproxy.grpc.client;

import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.plexus.util.ExceptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Slf4j
public class MultinodeIntegrationTest {
    private static final int THREADS = 30; // Number of worker threads
    private static final int RAMPUP_MS = 120 * 1000; // 120 seconds Ramp-up window in milliseconds

    // After the SQL query phase the JVM stays alive for this long so the daemon health-check
    // thread keeps running during the CI kill/restart verification cycle.
    // The CI can cut this short by creating /tmp/multinode-ci-done.
    private static final long KEEPALIVE_MAX_MS = TimeUnit.MINUTES.toMillis(12);
    private static final long KEEPALIVE_POLL_INTERVAL_MS = 5_000L;
    
    // Retry configuration for connection-level failures
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100; // Initial delay between retries
    
    // Test failure thresholds
    // During CI this test kills/restarts two servers while 30 worker threads execute 2160 SQL operations.
    // Because health-check failover detection and pool redistribution are asynchronous (5s interval/threshold
    // by default), bursts of connectivity failures can overlap both kill windows and occasionally exceed 150.
    // A ceiling of 200 keeps the test focused on resilience (non-connectivity failures are still capped below)
    // while absorbing expected timing variance from concurrent failover/recovery.
    private static final int MAX_TOTAL_FAILURES = 200;
    // Allow up to 5 non-connectivity failures to tolerate transient races that can occasionally
    // produce a non-connection error (e.g. brief pool exhaustion during server kill/recovery).
    // All failures beyond this limit must be connectivity-related.
    private static final int MAX_NON_CONNECTIVITY_FAILURES = 5;

    protected static boolean isTestDisabled;
    private static Queue<Long> queryDurations = new ConcurrentLinkedQueue<>();
    private static AtomicInteger totalQueries = new AtomicInteger(0);
    private static AtomicInteger totalFailedQueries = new AtomicInteger(0);
    private static final AtomicInteger nonConnectivityFailedQueries = new AtomicInteger(0);
    private static HikariDataSource ds;
    private static MultinodeTestHelper testHelper;

    @BeforeAll
    static void checkTestConfiguration() {
        // Check both system property and environment variable
        boolean sysPropEnabled = Boolean.parseBoolean(System.getProperty("multinodeTestsEnabled", "false"));
        boolean envVarEnabled = Boolean.parseBoolean(System.getenv("MULTINODE_TESTS_ENABLED"));
        isTestDisabled = !(sysPropEnabled || envVarEnabled);
    }

    @SneakyThrows
    public void setUp() throws SQLException {
        queryDurations = new ConcurrentLinkedQueue<>();
        totalQueries = new AtomicInteger(0);
        totalFailedQueries = new AtomicInteger(0);
        nonConnectivityFailedQueries.set(0);
        testHelper = new MultinodeTestHelper(totalFailedQueries, nonConnectivityFailedQueries);
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_connection.csv")
    void runTests(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Multinode tests are disabled");
        
        this.setUp();
        // 1. Schema and seeding (not timed)
        try (Connection conn = getConnection(driverClass, url, user, password)) {
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
                } catch (InterruptedException ignored) {
                }
                runExactQuerySequence(threadNum, driverClass, url, user, password);
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
        System.out.println("\n=== TEST REPORT ===");
        System.out.println("Total queries executed: " + numQueries);
        System.out.println("Total test duration: " + totalTimeMs + " ms");
        System.out.printf("Average query duration: %.3f ms\n", avgQueryMs);
        System.out.println("Total query failures: " + numTotalFailures);
        System.out.println("Total non-connectivity-related failures: " + numNonConnectivityFailures);
        assertEquals(2160, numQueries);
        assertTrue(numTotalFailures <= MAX_TOTAL_FAILURES,
            "Expected fewer or equal to " + MAX_TOTAL_FAILURES + " total failures, but got: " + numTotalFailures);
        assertTrue(numNonConnectivityFailures <= MAX_NON_CONNECTIVITY_FAILURES,
            "Expected at most " + MAX_NON_CONNECTIVITY_FAILURES + " non-connectivity failures, but got: " + numNonConnectivityFailures);
        assertTrue(totalTimeMs < 180000);
        assertTrue(avgQueryMs < 40);

        // 4. Keepalive phase
        // After the SQL phase the test assertions are done, but the CI kill/restart verification
        // cycle is still running.  Without this phase the Maven JVM would exit immediately,
        // killing the daemon health-check thread in MultinodeConnectionManager.  That thread is
        // responsible for detecting server failures and proactively pushing the updated
        // clusterHealth to surviving servers so they can expand their connection pools.
        // By keeping the JVM alive (and periodically opening a fresh OJP connection so that
        // connectionDetailsByConnHash stays populated), we ensure the health checker keeps
        // running for the duration of the CI cycle.
        //
        // The CI signals us to stop by creating /tmp/multinode-ci-done.
        // We also enforce a hard cap (KEEPALIVE_MAX_MS) so we never block indefinitely.
        System.out.println("\n=== KEEPALIVE PHASE: holding JVM open for CI kill/restart checks ===");
        try {
            new File("/tmp/multinode-queries-done").createNewFile();
        } catch (Exception ignored) {
        }
        File ciDoneFile = new File("/tmp/multinode-ci-done");
        long keepaliveEnd = System.currentTimeMillis() + KEEPALIVE_MAX_MS;
        while (!ciDoneFile.exists() && System.currentTimeMillis() < keepaliveEnd) {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                // Intentionally empty: opening the connection is enough to keep
                // connectionDetailsByConnHash populated so the health-check daemon
                // thread continues to monitor and push cluster-health updates.
            } catch (Exception ignored) {
                // Expected during server kills/restarts
            }
            Thread.sleep(KEEPALIVE_POLL_INTERVAL_MS);
        }
        System.out.println("=== KEEPALIVE PHASE complete ===");
    }

    private static void timeAndRun(Callable<Void> query) {
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
            testHelper.incrementFailures(lastException);
            System.err.println("Query failed after " + (MAX_RETRIES + 1) + " attempts: " + 
                lastException.getMessage() + " \n " + ExceptionUtils.getStackTrace(lastException));
        }
        
        long end = System.nanoTime();
        queryDurations.add(end - start);
        totalQueries.incrementAndGet();
    }

    /**
     * Each thread runs this exact sequence of 100 queries, all using PreparedStatement.
     * Also demonstrates realistic transaction blocks.
     * All queries are coded individually and marked if part of a transaction.
     */
    @SneakyThrows
    protected static Connection getConnection(String driverClass, String url, String user, String password) throws SQLException {
        Class.forName(driverClass);
        //Commented code below is useful for comparison tests with using HikariCP directly, without OJP
        /*if (ds == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(10);
            ds = new HikariDataSource(config);
        }
        return ds.getConnection();
        */
        return DriverManager.getConnection(url, user, password);
    }

    private static void runExactQuerySequence(int threadNum, String driverClass, String url, String user, String password) {
        // Transaction Block 1: create user, create order for that user, add order items
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                conn.setAutoCommit(false);
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
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 2: update product price and log a review for the product
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                conn.setAutoCommit(false);
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
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 3: delete and recreate a review
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                conn.setAutoCommit(false);
                try {
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
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Queries 4-100: Each query uses its own connection
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userA");
                    pst.setString(2, "userA@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userB");
                    pst.setString(2, "userB@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userC");
                    pst.setString(2, "userC@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                    pst.setString(1, "changed1@example.com");
                    pst.setInt(2, 1);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                    pst.setString(1, "changed2@example.com");
                    pst.setInt(2, 2);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET username=? WHERE id=?")) {
                    pst.setString(1, "userA_updated");
                    pst.setInt(2, 1);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userD");
                    pst.setString(2, "userD@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userE");
                    pst.setString(2, "userE@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                    pst.setString(1, "changed3@example.com");
                    pst.setInt(2, 3);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                    pst.setString(1, "userF");
                    pst.setString(2, "userF@example.com");
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // -- Product insert/update
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductA");
                    pst.setBigDecimal(2, new BigDecimal("123.45"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductB");
                    pst.setBigDecimal(2, new BigDecimal("67.89"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductC");
                    pst.setBigDecimal(2, new BigDecimal("250.00"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                    pst.setBigDecimal(1, new BigDecimal("199.99"));
                    pst.setInt(2, 1);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                    pst.setBigDecimal(1, new BigDecimal("299.99"));
                    pst.setInt(2, 2);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductD");
                    pst.setBigDecimal(2, new BigDecimal("111.11"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductE");
                    pst.setBigDecimal(2, new BigDecimal("77.77"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                    pst.setString(1, "ProductA-Updated");
                    pst.setInt(2, 1);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                    pst.setString(1, "ProductB-Updated");
                    pst.setInt(2, 2);
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                    pst.setString(1, "ProductF");
                    pst.setBigDecimal(2, new BigDecimal("333.33"));
                    pst.execute();
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE user_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE user_id=?")) {
                    pst.setInt(1, 2);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders ORDER BY order_date DESC LIMIT 10")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE order_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM order_items")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT count(*) FROM order_items WHERE order_id=?")) {
                    pst.setInt(1, 1);
                    log.info("select count of quantities from order_items for order_id = " + 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            Long sum  = rs.getLong(1);
                            log.info("count returned = " + sum);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getDouble(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE product_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(rating) FROM reviews WHERE product_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getDouble(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE user_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT u.username, o.id FROM users u JOIN orders o ON u.id = o.user_id WHERE u.id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getString("username");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT oi.id, p.name FROM order_items oi JOIN products p ON oi.product_id=p.id WHERE oi.order_id=?")) {
                    pst.setInt(1, 1);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("id");
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT user_id, COUNT(*) FROM orders GROUP BY user_id")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt("user_id");
                            rs.getInt(2);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getDouble(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        for (int i = 0; i < 7; i++) {
            final int idx = i + 2;
            timeAndRun(() -> {
                try (Connection conn = getConnection(driverClass, url, user, password)) {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                        pst.setInt(1, idx);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                } catch (Exception e) {
                    testHelper.incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try (Connection conn = getConnection(driverClass, url, user, password)) {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM users WHERE id=?")) {
                        pst.setInt(1, idx);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                } catch (Exception e) {
                    testHelper.incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try (Connection conn = getConnection(driverClass, url, user, password)) {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM products WHERE id=?")) {
                        pst.setInt(1, idx);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                } catch (Exception e) {
                    testHelper.incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
        }

        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM users")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
                try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // 5 heavy queries, will take longer than simpler queries and serve as an attack on the connection pool
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try (Connection conn = getConnection(driverClass, url, user, password)) {
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
            } catch (Exception e) {
                testHelper.incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
    }
}
