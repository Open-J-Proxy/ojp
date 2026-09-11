package org.openjproxy.grpc.server;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {
    private static final int THOUSAND = 1000;
    private static final int FAILURE_THRESHOLD = 3;
    private static final int THREE_HUNDRED = 300;
    private static final int FOUR_HUNDRED = 400;
    private static final int FIVE_HUNDRED = 500;
    private static final String DATA_SOURCE = "Test-DS";


    @Test
    void testAllowsWhenNoFailures() {
        CircuitBreaker breaker = new CircuitBreaker(THOUSAND, FAILURE_THRESHOLD, DATA_SOURCE);
        assertDoesNotThrow(() -> breaker.preCheck("SELECT 1"));
    }

    @Test
    void testBlocksAfterThreeFailures() {
        CircuitBreaker breaker = new CircuitBreaker(5000, FAILURE_THRESHOLD, DATA_SOURCE);
        String sql = "SELECT * FROM test";
        SQLException ex = new SQLException("fail");
        // Fail three times
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);

        SQLException thrown = assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        assertEquals("fail", thrown.getMessage());
    }

    @Test
    void testAllowsAgainAfterOpenTimeoutAndSuccessResets() {
        TestTicker ticker = new TestTicker();
        CircuitBreaker breaker = new CircuitBreaker(
                THREE_HUNDRED,
                FAILURE_THRESHOLD,
                DATA_SOURCE,
                CircuitBreakerMetrics.noop(),
                ticker::nanoTime
        );
        String sql = "UPDATE X SET Y=1";
        SQLException ex = new SQLException("fail");

        // Trip breaker
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        assertThrows(SQLException.class, () -> breaker.preCheck(sql));

        ticker.advanceMillis(FOUR_HUNDRED);
        // Should allow one through (half-open)
        assertDoesNotThrow(() -> breaker.preCheck(sql));
        // Success should reset
        breaker.onSuccess(sql);
        assertDoesNotThrow(() -> breaker.preCheck(sql));
    }

    @Test
    void testResetsOnSuccess() {
        CircuitBreaker breaker = new CircuitBreaker(THOUSAND, FAILURE_THRESHOLD, DATA_SOURCE);
        String sql = "INSERT X";
        SQLException ex = new SQLException("fail2");
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        breaker.onSuccess(sql);
        assertDoesNotThrow(() -> breaker.preCheck(sql));
    }

    @Test
    void testOnFailureIsNoOpWhenAlreadyOpen() {
        CircuitBreaker breaker = new CircuitBreaker(FIVE_HUNDRED, FAILURE_THRESHOLD, DATA_SOURCE);
        String sql = "SELECT fail";
        SQLException ex1 = new SQLException("fail1");
        SQLException ex2 = new SQLException("fail2");
        // Trip breaker
        breaker.onFailure(sql, ex1);
        breaker.onFailure(sql, ex1);
        breaker.onFailure(sql, ex1);

        // Now breaker is open, further failures should not change lastError
        breaker.onFailure(sql, ex2);

        SQLException thrown = assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        assertEquals("fail1", thrown.getMessage());
    }

    @Test
    void testRecordsCircuitBreakerMetricsLifecycle() {
        TestTicker ticker = new TestTicker();
        RecordingCircuitBreakerMetrics metrics = new RecordingCircuitBreakerMetrics();
        CircuitBreaker breaker = new CircuitBreaker(
                THREE_HUNDRED,
                FAILURE_THRESHOLD,
                DATA_SOURCE,
                metrics,
                ticker::nanoTime
        );
        String sql = "SELECT metrics";
        SQLException ex = new SQLException("fail");

        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);

        assertEquals(2, metrics.countState(CircuitBreakerMetrics.State.CLOSED));
        assertTrue(metrics.hasStateUpdate(
                DATA_SOURCE,
                sql,
                CircuitBreakerMetrics.State.OPEN,
                CircuitBreakerMetrics.TripReason.FAILURE_THRESHOLD
        ));

        assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        assertEquals(List.of(metricKey(DATA_SOURCE, sql)), metrics.blockedCalls);

        ticker.advanceMillis(FOUR_HUNDRED);
        assertDoesNotThrow(() -> breaker.preCheck(sql));
        assertTrue(metrics.hasStateUpdate(DATA_SOURCE, sql, CircuitBreakerMetrics.State.HALF_OPEN, null));

        breaker.onSuccess(sql);
        assertEquals(3, metrics.countState(CircuitBreakerMetrics.State.CLOSED));
    }

    private static String metricKey(String datasource, String queryHash) {
        return datasource + "|" + queryHash;
    }

    private static String stateMetricKey(
            String datasource,
            String queryHash,
            CircuitBreakerMetrics.State state,
            CircuitBreakerMetrics.TripReason reason
    ) {
        return String.join(
                "|",
                datasource,
                queryHash,
                state.name(),
                reason == null ? "NONE" : reason.name()
        );
    }

    private static final class RecordingCircuitBreakerMetrics extends CircuitBreakerMetrics {
        private final List<String> stateUpdates = new ArrayList<>();
        private final List<String> blockedCalls = new ArrayList<>();

        private RecordingCircuitBreakerMetrics() {
            super(OpenTelemetry.noop());
        }

        @Override
        public void updateState(String datasource, String queryHash, State newState) {
            stateUpdates.add(stateMetricKey(datasource, queryHash, newState, null));
        }

        @Override
        public void updateState(String datasource, String queryHash, State newState, TripReason tripReason) {
            stateUpdates.add(stateMetricKey(datasource, queryHash, newState, tripReason));
        }

        @Override
        public void recordBlockedCall(String datasource, String queryHash) {
            blockedCalls.add(metricKey(datasource, queryHash));
        }

        private long countState(CircuitBreakerMetrics.State state) {
            return stateUpdates.stream()
                    .filter(update -> update.contains("|" + state.name() + "|"))
                    .count();
        }

        private boolean hasStateUpdate(
                String datasource,
                String queryHash,
                CircuitBreakerMetrics.State state,
                CircuitBreakerMetrics.TripReason reason
        ) {
            return stateUpdates.contains(stateMetricKey(datasource, queryHash, state, reason));
        }
    }

    private static final class TestTicker {
        private long currentNanos;

        private long nanoTime() {
            return currentNanos;
        }

        private void advanceMillis(long millis) {
            currentNanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }
}
