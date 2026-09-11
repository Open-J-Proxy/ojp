package org.openjproxy.jdbc;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connHash, per-node client-side throttle.
 * Prevents this JVM from sending more concurrent requests to one OJP node than its fair share.
 *
 * <p>Mechanism: fail-fast AtomicInteger counter (no blocking, no semaphore).
 * Limit updates are a single volatile write; AIMD increase capped at +1 per SessionInfo update.</p>
 *
 * <h2>Reactive throttle hardening (resilience under burst overload)</h2>
 * <ul>
 *   <li><b>Cooldown:</b> {@link #notifyServerOverload()} ignores signals within
 *       {@code overloadCooldownMs} of the previous halving, so a single burst cannot
 *       multiplicatively crash the limit.</li>
 *   <li><b>Soft floor:</b> {@code reactiveLimit} never drops below
 *       {@code max(1, proactiveLimit / reactiveFloorDivisor)}.</li>
 *   <li><b>Configurable decrease factor:</b> reactive halving can be tuned to a gentler
 *       value (e.g. 0.75 instead of 0.5) via {@code reactiveDecreaseFactor}.</li>
 *   <li><b>Autonomous additive recovery:</b> each successful {@link #release} contributes
 *       to a counter; after enough successes without overload the reactive limit grows by
 *       one, up to {@code proactiveLimit}. This makes recovery independent of
 *       {@link #updateFromSessionInfo} arriving (which only happens on connect responses).</li>
 * </ul>
 *
 * <p>All hardening parameters are configurable via system properties:
 * <ul>
 *   <li>{@code ojp.jdbc.clientThrottle.overloadCooldownMs} (default 200)</li>
 *   <li>{@code ojp.jdbc.clientThrottle.reactiveFloorDivisor} (default 4 → floor = proactive/4)</li>
 *   <li>{@code ojp.jdbc.clientThrottle.reactiveDecreaseFactor} (default 0.75)</li>
 *   <li>{@code ojp.jdbc.clientThrottle.recoverySuccessThreshold} (default 0 → auto: max(8, reactiveLimit))</li>
 * </ul>
 */
@Slf4j
public class ClientThrottleManager {

    // 10% safety headroom applied after ceiling division.
    // Ceiling division can slightly over-allocate: e.g. 20 server slots / 3 clients
    // → ceil(20/3) = 7 per client; 3 × 7 = 21 would exceed real capacity by 1.
    // Multiplying by 0.9 brings the per-client budget down by one slot (floor: 6),
    // absorbing one stale clientCount value before all clients burst at the same moment.
    private static final double THROTTLE_SAFETY_MARGIN = 0.9;

    // System property keys for reactive hardening
    static final String PROP_OVERLOAD_COOLDOWN_MS = "ojp.jdbc.clientThrottle.overloadCooldownMs";
    static final String PROP_REACTIVE_FLOOR_DIVISOR = "ojp.jdbc.clientThrottle.reactiveFloorDivisor";
    static final String PROP_REACTIVE_DECREASE_FACTOR = "ojp.jdbc.clientThrottle.reactiveDecreaseFactor";
    static final String PROP_RECOVERY_SUCCESS_THRESHOLD = "ojp.jdbc.clientThrottle.recoverySuccessThreshold";

    // Hardening defaults
    private static final long DEFAULT_OVERLOAD_COOLDOWN_MS = 200L;
    private static final int DEFAULT_REACTIVE_FLOOR_DIVISOR = 4;
    private static final double DEFAULT_REACTIVE_DECREASE_FACTOR = 0.75d;
    private static final int DEFAULT_RECOVERY_SUCCESS_THRESHOLD = 0; // 0 ⇒ auto: max(8, reactiveLimit)

    private final long overloadCooldownMs;
    private final int reactiveFloorDivisor;
    private final double reactiveDecreaseFactor;
    private final int recoverySuccessThreshold;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile int proactiveLimit = Integer.MAX_VALUE;
    private volatile int reactiveLimit = Integer.MAX_VALUE;
    private volatile int lastProactiveLimit = Integer.MAX_VALUE;
    private volatile int lastReactiveLimit = Integer.MAX_VALUE;

    // Cooldown + autonomous recovery state
    private final AtomicLong lastOverloadNanos = new AtomicLong(0L);
    private final AtomicInteger successesSinceOverload = new AtomicInteger(0);

    public ClientThrottleManager() {
        this(
            longProp(PROP_OVERLOAD_COOLDOWN_MS, DEFAULT_OVERLOAD_COOLDOWN_MS),
            intProp(PROP_REACTIVE_FLOOR_DIVISOR, DEFAULT_REACTIVE_FLOOR_DIVISOR),
            doubleProp(PROP_REACTIVE_DECREASE_FACTOR, DEFAULT_REACTIVE_DECREASE_FACTOR),
            intProp(PROP_RECOVERY_SUCCESS_THRESHOLD, DEFAULT_RECOVERY_SUCCESS_THRESHOLD)
        );
    }

    // Visible for testing.
    ClientThrottleManager(long overloadCooldownMs, int reactiveFloorDivisor,
                          double reactiveDecreaseFactor, int recoverySuccessThreshold) {
        this.overloadCooldownMs = Math.max(0L, overloadCooldownMs);
        this.reactiveFloorDivisor = Math.max(1, reactiveFloorDivisor);
        // Clamp factor to (0, 1]; values <=0 or >=1 are nonsensical for AIMD.
        double rf = reactiveDecreaseFactor;
        if (!(rf > 0d && rf < 1d)) {
            rf = DEFAULT_REACTIVE_DECREASE_FACTOR;
        }
        this.reactiveDecreaseFactor = rf;
        this.recoverySuccessThreshold = Math.max(0, recoverySuccessThreshold);
    }

    private static long longProp(String key, long def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleProp(String key, double def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Update limits from a fresh SessionInfo.
     * AIMD: decrease immediately; increase capped at currentLimit + 1.
     *
     * <p>Only connect responses carry throttle data (maxAdmission &gt; 0).
     * executeUpdate/executeQuery responses use a minimal SessionInfo without throttle fields
     * (maxAdmission = 0). When maxAdmission is zero, there is nothing to update, so this
     * method returns immediately, preserving any reactive limit already set by
     * {@link #notifyServerOverload()}.</p>
     */
    public void updateFromSessionInfo(SessionInfo sessionInfo) {
        if (sessionInfo.getMaxAdmission() <= 0) {
            // No throttle data in this SessionInfo (e.g., executeUpdate/executeQuery response).
            // Skip to preserve any reactive limit set by notifyServerOverload().
            return;
        }
        int clientCount = Math.max(1, sessionInfo.getClientCount());
        int maxAdmission = sessionInfo.getMaxAdmission();
        int observedPeak = sessionInfo.getObservedPeak();

        int numOjpServers = countUpServers(sessionInfo.getClusterHealth());

        if (maxAdmission > 0) {
            int rawProactive = (int) Math.min(Integer.MAX_VALUE,
                    (long) Math.ceil((double) maxAdmission / clientCount) * numOjpServers);
            int newProactive = (int) (rawProactive * THROTTLE_SAFETY_MARGIN);
            if (newProactive < 1) {
                newProactive = 1;
            }
            if (newProactive < lastProactiveLimit) {
                proactiveLimit = newProactive;
            } else if (newProactive > lastProactiveLimit) {
                proactiveLimit = lastProactiveLimit + 1;
            }
            lastProactiveLimit = proactiveLimit;
        }

        if (observedPeak > 0 && maxAdmission > 0) {
            int rawReactive = (int) Math.min(Integer.MAX_VALUE,
                    (long) Math.ceil((double) observedPeak / clientCount) * numOjpServers);
            int newReactive = (int) (rawReactive * THROTTLE_SAFETY_MARGIN);
            // Apply floor relative to proactive limit so SessionInfo updates can't drop
            // reactive below the configured fairness threshold.
            int floor = proactiveLimit == Integer.MAX_VALUE
                    ? 1
                    : Math.max(1, proactiveLimit / reactiveFloorDivisor);
            if (newReactive < floor) {
                newReactive = floor;
            }
            // Cap at proactive limit — reactive should never exceed deterministic capacity.
            int cap = proactiveLimit;
            if (cap != Integer.MAX_VALUE && newReactive > cap) {
                newReactive = cap;
            }
            if (newReactive < lastReactiveLimit) {
                reactiveLimit = newReactive;
            } else if (newReactive > lastReactiveLimit) {
                int bumped = lastReactiveLimit + 1;
                if (cap != Integer.MAX_VALUE && bumped > cap) {
                    bumped = cap;
                }
                reactiveLimit = bumped;
            }
            lastReactiveLimit = reactiveLimit;
        } else {
            reactiveLimit = Integer.MAX_VALUE;
            lastReactiveLimit = Integer.MAX_VALUE;
        }

        log.debug("ClientThrottleManager updated: proactiveLimit={}, reactiveLimit={}, clientCount={}, maxAdmission={}, observedPeak={}, numServers={}",
                proactiveLimit, reactiveLimit, clientCount, maxAdmission, observedPeak, numOjpServers);
    }

    /**
     * Count UP servers from clusterHealth string "host1:port1(UP);host2:port2(DOWN);..."
     * Returns 1 if clusterHealth is empty/null.
     */
    private int countUpServers(String clusterHealth) {
        if (clusterHealth == null || clusterHealth.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (String entry : clusterHealth.split(";")) {
            if (entry.contains("(UP)")) {
                count++;
            }
        }
        return count > 0 ? count : 1;
    }

    /**
     * Attempt to acquire a throttle slot.
     *
     * @param mode the configured throttle mode
     * @param inTransaction whether this connection is currently in a transaction (autoCommit=false)
     * @return true if the request should proceed, false if it should be rejected
     */
    public boolean tryAcquire(ClientThrottleMode mode, boolean inTransaction) {
        if (mode == ClientThrottleMode.OFF) {
            return true;
        }
        if (inTransaction) {
            return true;
        }

        int effectiveLimit = getEffectiveLimit(mode);
        if (effectiveLimit == Integer.MAX_VALUE) {
            return true;
        }

        int current = inFlight.get();
        if (current >= effectiveLimit) {
            log.debug("Client throttle rejected: inFlight={}, effectiveLimit={}, mode={}", current, effectiveLimit, mode);
            return false;
        }
        // CAS loop: atomically check-and-increment to avoid exceeding the limit due to races
        while (true) {
            int cur = inFlight.get();
            if (cur >= effectiveLimit) {
                log.debug("Client throttle rejected (CAS): inFlight={}, effectiveLimit={}, mode={}", cur, effectiveLimit, mode);
                return false;
            }
            if (inFlight.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /**
     * Release a previously acquired slot. Must be called after tryAcquire returned true.
     *
     * <p>Each successful release also drives the autonomous reactive recovery clock: after
     * enough consecutive releases without an overload signal, {@code reactiveLimit} is
     * bumped by +1 (bounded by {@code proactiveLimit}). This breaks the prior dependency
     * on {@link #updateFromSessionInfo} arriving for recovery — under sustained execute
     * traffic with no new connections, the reactive limit can still recover.</p>
     */
    public void release(ClientThrottleMode mode, boolean inTransaction) {
        if (mode == ClientThrottleMode.OFF || inTransaction) {
            return;
        }
        // Atomically clamp to 0 to handle any race in concurrent releases
        inFlight.updateAndGet(v -> Math.max(0, v - 1));
        tickReactiveRecovery();
    }

    /**
     * Autonomous additive-increase tick for the reactive limit.
     *
     * <p>Counts successes since the last overload event. After
     * {@code recoverySuccessThreshold} successes (or, when 0, after
     * {@code max(8, reactiveLimit)} successes), increase {@code reactiveLimit} by +1 up
     * to {@code proactiveLimit}. The counter is reset on every overload signal.</p>
     */
    private void tickReactiveRecovery() {
        int rl = reactiveLimit;
        if (rl == Integer.MAX_VALUE) {
            // Reactive throttle not engaged yet; nothing to recover.
            return;
        }
        int threshold = recoverySuccessThreshold > 0
                ? recoverySuccessThreshold
                : Math.max(8, rl);
        int successes = successesSinceOverload.incrementAndGet();
        if (successes >= threshold) {
            // Reset counter and bump reactive limit by +1 (bounded by proactive limit).
            successesSinceOverload.set(0);
            int pl = proactiveLimit;
            int cap = pl == Integer.MAX_VALUE ? Integer.MAX_VALUE : pl;
            if (rl < cap) {
                int next = rl + 1;
                reactiveLimit = next;
                lastReactiveLimit = next;
                log.debug("ClientThrottleManager autonomous recovery: reactiveLimit {} -> {} (proactive cap={})",
                        rl, next, pl);
            }
        }
    }

    private int getEffectiveLimit(ClientThrottleMode mode) {
        switch (mode) {
            case PROACTIVE: return proactiveLimit;
            case REACTIVE: return reactiveLimit;
            case COMBINED:
                int pl = proactiveLimit;
                int rl = reactiveLimit;
                return Math.min(pl, rl);
            default: return Integer.MAX_VALUE;
        }
    }

    public int getInFlight() {
        return inFlight.get();
    }

    /**
     * Origin of a server overload signal. Propagated via gRPC trailer
     * {@code ojp-overload-lane}.
     *
     * <p>The driver applies lane-aware back-off:</p>
     * <ul>
     *   <li>{@code FAST} — primary concurrency budget saturated; apply normal AIMD
     *       multiplicative decrease.</li>
     *   <li>{@code SLOW} — slow-lane saturation; in a fast-dominated workload (e.g. 90 %
     *       OLTP) the slow lane is decoupled from fast-query budget by design, so the
     *       driver does <em>not</em> halve the reactive limit. This is the primary fix
     *       for cross-lane contamination.</li>
     *   <li>{@code QUEUE} — admission-queue depth limit, a transient burst signal rather
     *       than a saturation signal. Skipped to avoid penalizing the steady-state limit
     *       for brief open-loop spikes.</li>
     *   <li>{@code UNKNOWN} — legacy / unspecified; treat as {@code FAST} for safety.</li>
     * </ul>
     */
    /** gRPC trailer key name used to convey the overloaded lane (matches server-side constant). */
    public static final String OVERLOAD_LANE_HEADER = "ojp-overload-lane";

    public enum OverloadLane {
        FAST, SLOW, QUEUE, UNKNOWN;

        public static OverloadLane parse(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            switch (value.trim().toLowerCase()) {
                case "fast": return FAST;
                case "slow": return SLOW;
                case "queue": return QUEUE;
                default: return UNKNOWN;
            }
        }
    }

    /**
     * Lane-aware overload notification. Backwards-compatible counterpart of
     * {@link #notifyServerOverload()} (which defaults to {@code FAST}). Implements
     * lane-aware suppression as described on {@link OverloadLane}.
     */
    public void notifyServerOverload(OverloadLane lane) {
        OverloadLane effective = lane == null ? OverloadLane.UNKNOWN : lane;
        if (effective == OverloadLane.SLOW || effective == OverloadLane.QUEUE) {
            // Cross-lane contamination fix: a slow-lane or queue-depth overload must not
            // depress the (predominantly fast) client-side reactive limit. We just log
            // and return without resetting the recovery counter.
            log.debug("ClientThrottleManager notifyServerOverload({}): suppressed (lane decoupled)", effective);
            return;
        }
        notifyServerOverload();
    }

    /**
     * Called when the server rejects a request with RESOURCE_EXHAUSTED (slot admission timeout).
     * Applies a (configurable) multiplicative decrease to the reactive limit (AIMD).
     *
     * <p><b>Cooldown:</b> consecutive calls within {@code overloadCooldownMs} of the last
     * effective halving are coalesced into a single event. This prevents an open-loop
     * burst of N simultaneous rejections from triggering N halvings (e.g. 32 → 1 in
     * milliseconds) and is the primary protection against the reactive limit collapsing
     * to 1 from a single overload episode.</p>
     *
     * <p><b>Soft floor:</b> the reactive limit is never decreased below
     * {@code max(1, proactiveLimit / reactiveFloorDivisor)}. Without a known proactive
     * limit the floor is 1.</p>
     *
     * <p><b>Decrease factor:</b> defaults to {@code 0.75} (gentler than halving) but can
     * be reduced (e.g. {@code 0.5} or lower) for more aggressive back-off via the
     * {@code reactiveDecreaseFactor} property.</p>
     *
     * <p>If the reactive limit was uninitialised (MAX_VALUE), it is seeded from
     * {@code proactiveLimit * factor} so the client immediately backs off to a sensible
     * level instead of jumping straight to the floor.</p>
     */
    public void notifyServerOverload() {
        // Reset autonomous recovery counter — successes accrued so far no longer count
        // toward additive-increase.
        successesSinceOverload.set(0);

        long now = System.nanoTime();
        long last = lastOverloadNanos.get();
        long cooldownNanos = overloadCooldownMs * 1_000_000L;
        if (cooldownNanos > 0 && last != 0L && (now - last) < cooldownNanos) {
            // Within cooldown window — treat as the same overload event.
            log.debug("ClientThrottleManager notifyServerOverload: within cooldown ({} ms), skip halving", overloadCooldownMs);
            return;
        }
        // CAS to ensure only one thread within a cooldown window actually decreases.
        if (!lastOverloadNanos.compareAndSet(last, now)) {
            // Another thread updated; defer to it.
            log.debug("ClientThrottleManager notifyServerOverload: another thread won the cooldown race, skip");
            return;
        }

        int current = reactiveLimit;
        int pl = proactiveLimit;
        int floor = pl == Integer.MAX_VALUE ? 1 : Math.max(1, pl / reactiveFloorDivisor);
        int newLimit;
        if (current == Integer.MAX_VALUE) {
            // Reactive limit was uninitialised — seed from proactive limit using the
            // configured decrease factor (default 0.75).
            if (pl == Integer.MAX_VALUE) {
                newLimit = 1;
            } else {
                newLimit = Math.max(floor, (int) Math.floor(pl * reactiveDecreaseFactor));
            }
        } else {
            newLimit = Math.max(floor, (int) Math.floor(current * reactiveDecreaseFactor));
            // Ensure we make at least 1 unit of progress when current > floor, to avoid
            // rounding-induced stalls (e.g. current=2, factor=0.75 → 1.5 → 1).
            if (newLimit == current && current > floor) {
                newLimit = current - 1;
            }
        }
        reactiveLimit = newLimit;
        lastReactiveLimit = newLimit;
        log.debug("ClientThrottleManager notifyServerOverload: reactiveLimit {} -> {} (floor={}, factor={}, proactive={})",
                current, newLimit, floor, reactiveDecreaseFactor, pl);
    }

    public int getProactiveLimit() {
        return proactiveLimit;
    }

    public int getReactiveLimit() {
        return reactiveLimit;
    }
}
