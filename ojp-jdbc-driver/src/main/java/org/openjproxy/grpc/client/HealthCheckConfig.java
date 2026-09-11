package org.openjproxy.grpc.client;

import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * Configuration for health check and connection redistribution in multinode deployments.
 * Loads settings from ojp.properties.
 */
@Slf4j
public class HealthCheckConfig {

    // Default values
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 5000L; // 5 seconds
    private static final long DEFAULT_HEALTH_CHECK_THRESHOLD_MS = 5000L; // 5 seconds
    private static final int DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5000; // 5 seconds
    private static final boolean DEFAULT_REDISTRIBUTION_ENABLED = true;
    private static final double DEFAULT_IDLE_REBALANCE_FRACTION = 1.0;
    private static final int DEFAULT_MAX_CLOSE_PER_RECOVERY = 100;
    private static final boolean DEFAULT_LOAD_AWARE_SELECTION_ENABLED = true;

    // Property keys
    private static final String PROP_HEALTH_CHECK_INTERVAL = "ojp.health.check.interval";
    private static final String PROP_HEALTH_CHECK_THRESHOLD = "ojp.health.check.threshold";
    private static final String PROP_HEALTH_CHECK_TIMEOUT = "ojp.health.check.timeout";
    private static final String PROP_REDISTRIBUTION_ENABLED = "ojp.redistribution.enabled";
    private static final String PROP_REDISTRIBUTION_IDLE_FRACTION = "ojp.redistribution.idleRebalanceFraction";
    private static final String PROP_REDISTRIBUTION_MAX_CLOSE = "ojp.redistribution.maxClosePerRecovery";
    private static final String PROP_LOAD_AWARE_SELECTION = "ojp.loadaware.selection.enabled";

    private final long healthCheckIntervalMs;
    private final long healthCheckThresholdMs;
    private final int healthCheckTimeoutMs;
    private final boolean redistributionEnabled;
    private final double idleRebalanceFraction;
    private final int maxClosePerRecovery;
    private final boolean loadAwareSelectionEnabled;

    private HealthCheckConfig(long healthCheckIntervalMs, long healthCheckThresholdMs,
                            int healthCheckTimeoutMs,
                            boolean redistributionEnabled, double idleRebalanceFraction,
                            int maxClosePerRecovery, boolean loadAwareSelectionEnabled) {
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.healthCheckThresholdMs = healthCheckThresholdMs;
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
        this.redistributionEnabled = redistributionEnabled;
        this.idleRebalanceFraction = idleRebalanceFraction;
        this.maxClosePerRecovery = maxClosePerRecovery;
        this.loadAwareSelectionEnabled = loadAwareSelectionEnabled;
    }

    /**
     * Loads health check configuration from properties.
     *
     * @param props Properties to load from (typically from ojp.properties)
     * @return HealthCheckConfig instance with loaded or default values
     */
    public static HealthCheckConfig loadFromProperties(Properties props) {
        long interval = getLongProperty(props, PROP_HEALTH_CHECK_INTERVAL, DEFAULT_HEALTH_CHECK_INTERVAL_MS);
        long threshold = getLongProperty(props, PROP_HEALTH_CHECK_THRESHOLD, DEFAULT_HEALTH_CHECK_THRESHOLD_MS);
        int timeout = getIntProperty(props, PROP_HEALTH_CHECK_TIMEOUT, DEFAULT_HEALTH_CHECK_TIMEOUT_MS);
        boolean enabled = getBooleanProperty(props, PROP_REDISTRIBUTION_ENABLED, DEFAULT_REDISTRIBUTION_ENABLED);
        double idleFraction = getDoubleProperty(props, PROP_REDISTRIBUTION_IDLE_FRACTION, DEFAULT_IDLE_REBALANCE_FRACTION);
        int maxClose = getIntProperty(props, PROP_REDISTRIBUTION_MAX_CLOSE, DEFAULT_MAX_CLOSE_PER_RECOVERY);
        boolean loadAware = getBooleanProperty(props, PROP_LOAD_AWARE_SELECTION, DEFAULT_LOAD_AWARE_SELECTION_ENABLED);

        log.info("Health check configuration loaded: interval={}ms, threshold={}ms, timeout={}ms, enabled={}, idleFraction={}, maxClose={}, loadAwareSelection={}",
                interval, threshold, timeout, enabled, idleFraction, maxClose, loadAware);

        return new HealthCheckConfig(interval, threshold, timeout, enabled, idleFraction, maxClose, loadAware);
    }

    /**
     * Creates a configuration with default values.
     *
     * @return HealthCheckConfig instance with default values
     */
    public static HealthCheckConfig createDefault() {
        return new HealthCheckConfig(
            DEFAULT_HEALTH_CHECK_INTERVAL_MS,
            DEFAULT_HEALTH_CHECK_THRESHOLD_MS,
            DEFAULT_HEALTH_CHECK_TIMEOUT_MS,
            DEFAULT_REDISTRIBUTION_ENABLED,
            DEFAULT_IDLE_REBALANCE_FRACTION,
            DEFAULT_MAX_CLOSE_PER_RECOVERY,
            DEFAULT_LOAD_AWARE_SELECTION_ENABLED
        );
    }

    /**
     * Resolves a property value using system properties as the highest-priority source,
     * then falling back to the supplied {@link Properties} object (typically from
     * {@code ojp.properties}), and finally returning {@code null} if neither has a value.
     *
     * <p>This mirrors the precedence order used by {@code GrpcClientConfig} and
     * {@code DatasourcePropertiesLoader}, ensuring that properties set in Spring Boot's
     * {@code application.yml} (forwarded to system properties by
     * {@code OjpSystemPropertiesBridge}) or via {@code -D} JVM flags always take effect.</p>
     */
    private static String resolveValue(Properties props, String key) {
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }
        if (props == null) {
            return null;
        }
        String fileProp = props.getProperty(key);
        return (fileProp != null && !fileProp.trim().isEmpty()) ? fileProp.trim() : null;
    }

    private static long getLongProperty(Properties props, String key, long defaultValue) {
        String value = resolveValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            long longValue = parseDurationMs(value);
            if (longValue < 0) {
                log.warn("Invalid negative value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return longValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = resolveValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            long longValue = parseDurationMs(value);
            if (longValue < 0 || longValue > Integer.MAX_VALUE) {
                log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return (int) longValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Parses a duration string into milliseconds.
     * Supported formats:
     * <ul>
     *   <li>Plain integer — treated as milliseconds (e.g. {@code 5000})</li>
     *   <li>{@code <n>ms} — milliseconds (e.g. {@code 500ms})</li>
     *   <li>{@code <n>s}  — seconds (e.g. {@code 10s})</li>
     *   <li>{@code <n>m}  — minutes (e.g. {@code 2m})</li>
     * </ul>
     *
     * @param value trimmed property value string
     * @return duration in milliseconds
     * @throws NumberFormatException if the value cannot be parsed
     */
    static long parseDurationMs(String value) {
        if (value.endsWith("ms")) {
            return Long.parseLong(value.substring(0, value.length() - 2).trim());
        } else if (value.endsWith("m")) {
            return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 60_000L;
        } else if (value.endsWith("s")) {
            return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 1_000L;
        } else {
            return Long.parseLong(value);
        }
    }

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = resolveValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static double getDoubleProperty(Properties props, String key, double defaultValue) {
        String value = resolveValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            double doubleValue = Double.parseDouble(value);
            if (doubleValue < 0.0 || doubleValue > 1.0) {
                log.warn("Invalid value for {}: {} (must be 0.0-1.0), using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return doubleValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public long getHealthCheckIntervalMs() {
        return healthCheckIntervalMs;
    }

    public long getHealthCheckThresholdMs() {
        return healthCheckThresholdMs;
    }

    public int getHealthCheckTimeoutMs() {
        return healthCheckTimeoutMs;
    }

    public boolean isRedistributionEnabled() {
        return redistributionEnabled;
    }

    public double getIdleRebalanceFraction() {
        return idleRebalanceFraction;
    }

    public int getMaxClosePerRecovery() {
        return maxClosePerRecovery;
    }

    public boolean isLoadAwareSelectionEnabled() {
        return loadAwareSelectionEnabled;
    }

    @Override
    public String toString() {
        return "HealthCheckConfig{" +
                "intervalMs=" + healthCheckIntervalMs +
                ", thresholdMs=" + healthCheckThresholdMs +
                ", timeoutMs=" + healthCheckTimeoutMs +
                ", enabled=" + redistributionEnabled +
                ", idleFraction=" + idleRebalanceFraction +
                ", maxClose=" + maxClosePerRecovery +
                ", loadAwareSelection=" + loadAwareSelectionEnabled +
                '}';
    }
}
