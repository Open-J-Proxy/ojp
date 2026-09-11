package org.openjproxy.grpc.server;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class CircuitBreakerRegistry {

    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakerStore = new ConcurrentHashMap<>();
    private final long openMs;
    private final int failureThreshold;
    private final CircuitBreakerMetrics metrics;

    public CircuitBreakerRegistry(long openMs, int failureThreshold) {
        this(openMs, failureThreshold, CircuitBreakerMetrics.noop());
    }

    public CircuitBreakerRegistry(long openMs, int failureThreshold, CircuitBreakerMetrics metrics) {
        this.openMs = openMs;
        this.failureThreshold = failureThreshold;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }


    public CircuitBreaker get(String key) {
        return circuitBreakerStore.computeIfAbsent(key, k -> new CircuitBreaker(openMs, failureThreshold, key, metrics));
    }
}
