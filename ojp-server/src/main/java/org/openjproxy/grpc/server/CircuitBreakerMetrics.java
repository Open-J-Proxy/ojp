package org.openjproxy.grpc.server;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class CircuitBreakerMetrics {

    private static final CircuitBreakerMetrics NOOP = new CircuitBreakerMetrics(OpenTelemetry.noop(), false);

    private static final AttributeKey<String> QUERY_HASH = AttributeKey.stringKey("query_hash");
    private static final AttributeKey<String> DATA_SOURCE = AttributeKey.stringKey("datasource");
    private static final AttributeKey<String> FROM_STATE = AttributeKey.stringKey("from_state");
    private static final AttributeKey<String> TO_STATE = AttributeKey.stringKey("to_state");
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");

    private final boolean enabled;
    private final ObservableLongGauge stateGauge;
    private final LongCounter transitionsCounter;
    private final LongCounter tripsCounter;
    private final LongCounter blockedCallsCounter;
    private final DoubleHistogram openDurationHistogram;
    private final ConcurrentHashMap<CircuitBreakerKey, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CircuitBreakerKey, Long> openTimestamps = new ConcurrentHashMap<>();

    public CircuitBreakerMetrics(OpenTelemetry openTelemetry) {
        this(openTelemetry, true);
    }

    private CircuitBreakerMetrics(OpenTelemetry openTelemetry, boolean enabled) {
        this.enabled = enabled;
        Meter meter = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .getMeter("ojp.server.circuit.breaker");

        this.stateGauge = meter
                .gaugeBuilder("ojp.circuit_breaker.state")
                .setDescription("Current circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
                .ofLongs()
                .buildWithCallback(this::observeState);

        this.transitionsCounter = meter.counterBuilder("ojp.circuit_breaker.transitions.total")
                .setDescription("Counts circuit breaker state transitions")
                .build();

        this.tripsCounter = meter.counterBuilder("ojp.circuit_breaker.trips.total")
                .setDescription("Counts when a circuit opens due to failure conditions")
                .build();

        this.blockedCallsCounter = meter.counterBuilder("ojp.circuit_breaker.blocked_calls.total")
                .setDescription("Counts requests blocked because a circuit is open")
                .build();

        this.openDurationHistogram = meter
                .histogramBuilder("ojp.circuit_breaker.open_duration.seconds")
                .setDescription("Time circuit remains OPEN before recovery attempt")
                .setUnit("s")
                .build();
    }

    public static CircuitBreakerMetrics noop() {
        return NOOP;
    }

    public void clearState(String datasource, String queryHash) {
        CircuitBreakerKey key = CircuitBreakerKey.of(datasource, queryHash);

        this.states.remove(key);
        this.openTimestamps.remove(key);
    }

    public void updateState(String datasource, String queryHash, State newState) {
        updateState(datasource, queryHash, newState, null);
    }

    public void updateState(String datasource, String queryHash, State newState, TripReason tripReason) {
        if (!enabled) {
            return;
        }

        Objects.requireNonNull(newState, "newState");
        CircuitBreakerKey key = CircuitBreakerKey.of(datasource, queryHash);

        states.compute(key, (k, previousState) -> {
            if (previousState == newState) {
                return previousState;
            }

            State fromState = previousState == null ? State.CLOSED : previousState;
            if (previousState == null && newState == State.CLOSED) {
                return newState;
            }

            handleTransition(key, datasource, queryHash, fromState, newState, tripReason);
            return newState;
        });
    }

    public void recordBlockedCall(String datasource, String queryHash) {
        if (!enabled) {
            return;
        }

        blockedCallsCounter.add(
                1,
                Attributes.of(
                        DATA_SOURCE, datasource,
                        QUERY_HASH, queryHash
                )
        );
    }

    private void observeState(ObservableLongMeasurement observer) {
        if (!enabled) {
            return;
        }

        for (Map.Entry<CircuitBreakerKey, State> entry : states.entrySet()) {
            CircuitBreakerKey key = entry.getKey();
            observer.record(
                    entry.getValue().value,
                    Attributes.of(
                            DATA_SOURCE, key.datasource,
                            QUERY_HASH, key.queryHash
                    )
            );
        }
    }

    private void handleTransition(
            CircuitBreakerKey key,
            String datasource,
            String queryHash,
            State previousState,
            State newState,
            TripReason tripReason
    ) {
        boolean enteringOpen = newState == State.OPEN;
        boolean leavingOpen = previousState == State.OPEN && newState != State.OPEN;

        transitionsCounter.add(
                1,
                Attributes.of(
                        DATA_SOURCE, datasource,
                        QUERY_HASH, queryHash,
                        FROM_STATE, previousState.name(),
                        TO_STATE, newState.name()
                )
        );

        if (enteringOpen) {
            validateTripReason(tripReason);
            openTimestamps.put(key, System.nanoTime());
            tripsCounter.add(
                    1,
                    Attributes.of(
                            DATA_SOURCE, datasource,
                            QUERY_HASH, queryHash,
                            REASON, tripReason.name()
                    )
            );
        }

        if (leavingOpen) {
            recordOpenDuration(key, datasource, queryHash);
        }
    }

    private void recordOpenDuration(
            CircuitBreakerKey key,
            String datasource,
            String queryHash
    ) {
        Long start = openTimestamps.remove(key);
        if (start == null) {
            return;
        }

        double durationSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        openDurationHistogram.record(
                durationSeconds,
                Attributes.of(
                        DATA_SOURCE, datasource,
                        QUERY_HASH, queryHash
                )
        );
    }

    private void validateTripReason(TripReason tripReason) {
        if (tripReason == null) {
            throw new IllegalArgumentException(
                    "TripReason must be provided when transitioning to OPEN"
            );
        }
    }

    private static final class CircuitBreakerKey {
        private final String datasource;
        private final String queryHash;

        private CircuitBreakerKey(String datasource, String queryHash) {
            this.datasource = Objects.requireNonNull(datasource, "datasource");
            this.queryHash = Objects.requireNonNull(queryHash, "queryHash");
        }

        static CircuitBreakerKey of(String datasource, String queryHash) {
            return new CircuitBreakerKey(datasource, queryHash);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CircuitBreakerKey that)) {
                return false;
            }
            return datasource.equals(that.datasource) && queryHash.equals(that.queryHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(datasource, queryHash);
        }
    }

    public enum TripReason {
        FAILURE_THRESHOLD
    }

    enum State {
        CLOSED(0),
        OPEN(1),
        HALF_OPEN(2);

        final long value;

        State(long value) {
            this.value = value;
        }
    }
}
