package org.openjproxy.grpc.server;

/**
 * Indicates request rejection due to server-side overload and admission-control limits.
 *
 * <p>Carries the {@link Lane} that observed the saturation so the JDBC driver can apply
 * lane-aware back-off (e.g. a slow-lane saturation should not depress the fast-lane
 * client-side throttle when fast-lane queries dominate the workload).</p>
 */
public class ServerOverloadException extends RuntimeException {

    /**
     * Origin of the overload signal. Propagated to the JDBC driver via gRPC trailer
     * metadata key {@code ojp-overload-lane}.
     */
    public enum Lane {
        /** Overload while waiting on the fast-lane admission semaphore. */
        FAST,
        /** Overload while waiting on the slow-lane admission semaphore. */
        SLOW,
        /** Fail-fast rejection at queue-depth limit (transient burst, not saturation). */
        QUEUE,
        /** Lane unknown / not applicable (e.g. admission-control-only mode). */
        UNKNOWN
    }

    private final Lane lane;

    public ServerOverloadException(String message) {
        this(message, Lane.UNKNOWN);
    }

    public ServerOverloadException(String message, Lane lane) {
        super(message);
        this.lane = lane == null ? Lane.UNKNOWN : lane;
    }

    public ServerOverloadException(String message, Throwable cause) {
        super(message, cause);
        this.lane = Lane.UNKNOWN;
    }

    public ServerOverloadException(String message, Lane lane, Throwable cause) {
        super(message, cause);
        this.lane = lane == null ? Lane.UNKNOWN : lane;
    }

    public Lane getLane() {
        return lane;
    }
}
