package org.openjproxy.jdbc;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;


class ConcurrencyTimeoutTest {
    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyTimeoutTest.class);

    private static final int THREADS = 50; // Smaller number for quick testing
    private static final int OPERATIONS_PER_THREAD = 5;

    private static boolean isH2TestEnabled;
    private static final AtomicInteger successfulOperations = new AtomicInteger(0);
    private static final AtomicInteger failedOperations = new AtomicInteger(0);

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testConcurrencyWithTimeout(String driverClass, String url, String user, String password) {
        assumeFalse(!isH2TestEnabled, "H2 tests are disabled");

        successfulOperations.set(0);
        failedOperations.set(0);

        // Setup a simple table
        try (Connection conn = getConnection(driverClass, url, user, password)) {
            conn.createStatement().execute("DROP TABLE IF EXISTS concurrency_test");
            conn.createStatement().execute("CREATE TABLE concurrency_test (id INT PRIMARY KEY, test_value VARCHAR(50))");
        }

        // Run concurrent operations
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    try (Connection conn = getConnection(driverClass, url, user, password)) {
                        try (PreparedStatement pst = conn.prepareStatement("INSERT INTO concurrency_test (id, test_value) VALUES (?, ?)")) {
                            pst.setInt(1, threadNum * OPERATIONS_PER_THREAD + i);
                            pst.setString(2, "thread_" + threadNum + "_op_" + i);
                            pst.execute();
                            successfulOperations.incrementAndGet();
                        }
                    } catch (Exception e) {
                        logger.warn("Operation failed for thread {}, operation {}: {}", threadNum, i, e.getMessage());
                        failedOperations.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();

        // The key test: this should complete in reasonable time instead of hanging indefinitely
        boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);

        int successful = successfulOperations.get();
        int failed = failedOperations.get();
        int total = successful + failed;
        int expected = THREADS * OPERATIONS_PER_THREAD;

        logger.info("Test completed - Finished: {}, Successful: {}, Failed: {}, Total: {}, Expected: {}",
                finished, successful, failed, total, expected);

        // Assertions
        assertTrue(finished, "Test should complete without hanging indefinitely");
        assertTrue(successful > 0, "Some operations should succeed");
        assertTrue(total >= expected * 0.8, "Most operations should complete (success or controlled failure)");
    }

    private static Connection getConnection(String driverClass, String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}