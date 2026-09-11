package org.openjproxy.datasource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Immutable configuration class for connection pool settings.
 * Uses a builder pattern for construction and supports secure handling of credentials.
 *
 * <p>This class provides canonical fields for configuring JDBC connection pools
 * in a provider-agnostic way. It supports both password strings and secure
 * password suppliers for sensitive credential handling.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * PoolConfig config = PoolConfig.builder()
 *     .url("jdbc:postgresql://localhost:5432/mydb")
 *     .username("user")
 *     .password("secret".toCharArray())
 *     .driverClassName("org.postgresql.Driver")
 *     .maxPoolSize(20)
 *     .minIdle(5)
 *     .build();
 * }</pre>
 */
public final class PoolConfig {

    // Connection settings
    private final String url;
    private final String username;
    private final char[] password;
    private final Supplier<char[]> passwordSupplier;
    private final String driverClassName;

    // Pool sizing settings
    private final int maxPoolSize;
    private final int minIdle;

    // Timeout settings (in milliseconds)
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final long leakDetectionThresholdMs;

    // Validation and behavior settings
    private final String validationQuery;
    private final boolean autoCommit;
    private final Integer defaultTransactionIsolation;

    // Additional properties
    private final Map<String, String> properties;

    // Metrics
    private final String metricsPrefix;

    // Default values
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30000L;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 600000L;
    public static final long DEFAULT_MAX_LIFETIME_MS = 1800000L;
    public static final boolean DEFAULT_AUTO_COMMIT = true;
    // 0 disables HikariCP's leak detection. OJP intentionally holds one physical connection
    // per client session for the session's full lifetime, so a non-zero default would produce
    // false-positive "leak" warnings for long-lived client sessions (e.g. SQL IDEs).
    public static final long DEFAULT_LEAK_DETECTION_THRESHOLD_MS = 0L;

    private PoolConfig(Builder builder) {
        this.url = builder.url;
        this.username = builder.username;
        this.password = builder.password != null ? builder.password.clone() : null;
        this.passwordSupplier = builder.passwordSupplier;
        this.driverClassName = builder.driverClassName;
        this.maxPoolSize = builder.maxPoolSize;
        this.minIdle = builder.minIdle;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.maxLifetimeMs = builder.maxLifetimeMs;
        this.leakDetectionThresholdMs = builder.leakDetectionThresholdMs;
        this.validationQuery = builder.validationQuery;
        this.autoCommit = builder.autoCommit;
        this.defaultTransactionIsolation = builder.defaultTransactionIsolation;
        this.properties = builder.properties != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.properties))
            : Collections.emptyMap();
        this.metricsPrefix = builder.metricsPrefix;
    }

    /**
     * Creates a new builder for constructing PoolConfig instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the JDBC URL for database connections.
     *
     * @return the JDBC URL, may be null if not configured
     */
    public String getUrl() {
        return url;
    }

    /**
     * Gets the username for database authentication.
     *
     * @return the username, may be null if not configured
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the password as a char array for secure credential handling.
     * Returns a defensive copy to prevent external modification.
     *
     * <p>If a password supplier was configured, this method will invoke
     * the supplier to get the current password.</p>
     *
     * @return a copy of the password char array, or null if not configured
     */
    public char[] getPassword() {
        if (passwordSupplier != null) {
            char[] supplied = passwordSupplier.get();
            return supplied != null ? supplied.clone() : null;
        }
        return password != null ? password.clone() : null;
    }

    /**
     * Gets the password as a String.
     *
     * <p>Note: For security-sensitive applications, prefer using
     * {@link #getPassword()} with char arrays to minimize password
     * exposure in memory.</p>
     *
     * @return the password as a String, or null if not configured
     */
    public String getPasswordAsString() {
        char[] pwd = getPassword();
        return pwd != null ? new String(pwd) : null;
    }

    /**
     * Gets the JDBC driver class name.
     *
     * @return the driver class name, may be null if auto-detection is used
     */
    public String getDriverClassName() {
        return driverClassName;
    }

    /**
     * Gets the maximum pool size (maximum number of connections).
     *
     * @return the maximum pool size
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Gets the minimum number of idle connections to maintain.
     *
     * @return the minimum idle connections
     */
    public int getMinIdle() {
        return minIdle;
    }

    /**
     * Gets the connection timeout in milliseconds.
     * This is the maximum time to wait when acquiring a connection.
     *
     * @return the connection timeout in milliseconds
     */
    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    /**
     * Gets the idle timeout in milliseconds.
     * Connections idle longer than this will be eligible for removal.
     *
     * @return the idle timeout in milliseconds
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /**
     * Gets the maximum lifetime of a connection in milliseconds.
     * Connections older than this will be closed and removed.
     *
     * @return the maximum lifetime in milliseconds
     */
    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    /**
     * Gets the leak detection threshold in milliseconds.
     * When a connection is held (checked out from the pool) longer than this
     * threshold, HikariCP logs a warning with a stack trace of where it was acquired.
     *
     * <p>A value of {@code 0} disables leak detection. This is the default because
     * OJP intentionally holds one physical connection per client session for the
     * session's full lifetime, which can legitimately exceed typical leak-detection
     * windows for long-lived client tools (e.g. SQL IDEs). Enabling this is mainly
     * useful for diagnosing genuinely abandoned sessions.</p>
     *
     * @return the leak detection threshold in milliseconds, or 0 if disabled
     */
    public long getLeakDetectionThresholdMs() {
        return leakDetectionThresholdMs;
    }

    /**
     * Gets the SQL query used to validate connections.
     *
     * @return the validation query, may be null if not configured
     */
    public String getValidationQuery() {
        return validationQuery;
    }

    /**
     * Gets the default auto-commit mode for connections.
     *
     * @return true if auto-commit is enabled by default
     */
    public boolean isAutoCommit() {
        return autoCommit;
    }

    /**
     * Gets the default transaction isolation level.
     * This level will be restored when connections are returned to the pool.
     *
     * @return the default transaction isolation level (e.g., Connection.TRANSACTION_READ_COMMITTED),
     *         or null if not configured (pool will use database default)
     */
    public Integer getDefaultTransactionIsolation() {
        return defaultTransactionIsolation;
    }

    /**
     * Gets the additional properties map.
     *
     * @return an unmodifiable map of additional properties
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Gets the metrics prefix for monitoring.
     *
     * @return the metrics prefix, may be null
     */
    public String getMetricsPrefix() {
        return metricsPrefix;
    }

    /**
     * Clears sensitive data (password) from memory.
     * Call this method when the configuration is no longer needed.
     */
    public void clearSensitiveData() {
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
        }
    }

    @Override
    public String toString() {
        return "PoolConfig{" +
                "url='" + url + '\'' +
                ", username='" + username + '\'' +
                ", password='****'" +
                ", driverClassName='" + driverClassName + '\'' +
                ", maxPoolSize=" + maxPoolSize +
                ", minIdle=" + minIdle +
                ", connectionTimeoutMs=" + connectionTimeoutMs +
                ", idleTimeoutMs=" + idleTimeoutMs +
                ", maxLifetimeMs=" + maxLifetimeMs +
                ", leakDetectionThresholdMs=" + leakDetectionThresholdMs +
                ", validationQuery='" + validationQuery + '\'' +
                ", autoCommit=" + autoCommit +
                ", defaultTransactionIsolation=" + defaultTransactionIsolation +
                ", metricsPrefix='" + metricsPrefix + '\'' +
                ", properties=" + properties.keySet() +
                '}';
    }

    /**
     * Builder class for constructing PoolConfig instances.
     */
    public static final class Builder {
        private String url;
        private String username;
        private char[] password;
        private Supplier<char[]> passwordSupplier;
        private String driverClassName;
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private int minIdle = DEFAULT_MIN_IDLE;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
        private long maxLifetimeMs = DEFAULT_MAX_LIFETIME_MS;
        private long leakDetectionThresholdMs = DEFAULT_LEAK_DETECTION_THRESHOLD_MS;
        private String validationQuery;
        private boolean autoCommit = DEFAULT_AUTO_COMMIT;
        private Integer defaultTransactionIsolation;
        private Map<String, String> properties;
        private String metricsPrefix;

        private Builder() {
        }

        /**
         * Sets the JDBC URL.
         *
         * @param url the JDBC URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Sets the username for database authentication.
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the password as a char array for secure credential handling.
         * The array is copied to prevent external modification.
         *
         * @param password the password as a char array
         * @return this builder
         */
        public Builder password(char[] password) {
            this.password = password != null ? password.clone() : null;
            this.passwordSupplier = null;
            return this;
        }

        /**
         * Sets the password as a String.
         *
         * <p>Note: For security-sensitive applications, prefer using
         * {@link #password(char[])} to minimize password exposure.</p>
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password != null ? password.toCharArray() : null;
            this.passwordSupplier = null;
            return this;
        }

        /**
         * Sets a password supplier for dynamic credential retrieval.
         * This is useful for integrations with secret management systems.
         *
         * @param passwordSupplier a supplier that provides the password
         * @return this builder
         */
        public Builder passwordSupplier(Supplier<char[]> passwordSupplier) {
            this.passwordSupplier = passwordSupplier;
            this.password = null;
            return this;
        }

        /**
         * Sets the JDBC driver class name.
         *
         * @param driverClassName the driver class name
         * @return this builder
         */
        public Builder driverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
            return this;
        }

        /**
         * Sets the maximum pool size.
         *
         * @param maxPoolSize the maximum number of connections
         * @return this builder
         * @throws IllegalArgumentException if maxPoolSize is less than 1
         */
        public Builder maxPoolSize(int maxPoolSize) {
            if (maxPoolSize < 1) {
                throw new IllegalArgumentException("maxPoolSize must be at least 1");
            }
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        /**
         * Sets the minimum number of idle connections.
         *
         * @param minIdle the minimum idle connections
         * @return this builder
         * @throws IllegalArgumentException if minIdle is negative
         */
        public Builder minIdle(int minIdle) {
            if (minIdle < 0) {
                throw new IllegalArgumentException("minIdle cannot be negative");
            }
            this.minIdle = minIdle;
            return this;
        }

        /**
         * Sets the connection timeout in milliseconds.
         *
         * @param connectionTimeoutMs the timeout in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if timeout is negative
         */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            if (connectionTimeoutMs < 0) {
                throw new IllegalArgumentException("connectionTimeoutMs cannot be negative");
            }
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * Sets the idle timeout in milliseconds.
         *
         * @param idleTimeoutMs the timeout in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if timeout is negative
         */
        public Builder idleTimeoutMs(long idleTimeoutMs) {
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("idleTimeoutMs cannot be negative");
            }
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        /**
         * Sets the maximum lifetime of connections in milliseconds.
         *
         * @param maxLifetimeMs the maximum lifetime in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if maxLifetimeMs is negative
         */
        public Builder maxLifetimeMs(long maxLifetimeMs) {
            if (maxLifetimeMs < 0) {
                throw new IllegalArgumentException("maxLifetimeMs cannot be negative");
            }
            this.maxLifetimeMs = maxLifetimeMs;
            return this;
        }

        /**
         * Sets the leak detection threshold in milliseconds.
         *
         * @param leakDetectionThresholdMs the threshold in milliseconds, or 0 to disable leak detection
         * @return this builder
         * @throws IllegalArgumentException if leakDetectionThresholdMs is negative
         */
        public Builder leakDetectionThresholdMs(long leakDetectionThresholdMs) {
            if (leakDetectionThresholdMs < 0) {
                throw new IllegalArgumentException("leakDetectionThresholdMs cannot be negative");
            }
            this.leakDetectionThresholdMs = leakDetectionThresholdMs;
            return this;
        }

        /**
         * Sets the SQL query used for connection validation.
         *
         * @param validationQuery the validation query
         * @return this builder
         */
        public Builder validationQuery(String validationQuery) {
            this.validationQuery = validationQuery;
            return this;
        }

        /**
         * Sets the default auto-commit mode.
         *
         * @param autoCommit true to enable auto-commit by default
         * @return this builder
         */
        public Builder autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }

        /**
         * Sets the default transaction isolation level.
         * When set, the connection pool will reset connections to this isolation level
         * when they are returned to the pool, preventing transaction isolation state
         * pollution between clients.
         *
         * @param defaultTransactionIsolation the transaction isolation level
         *        (e.g., Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE),
         *        or null to use database default
         * @return this builder
         */
        public Builder defaultTransactionIsolation(Integer defaultTransactionIsolation) {
            this.defaultTransactionIsolation = defaultTransactionIsolation;
            return this;
        }

        /**
         * Sets additional properties.
         *
         * @param properties a map of additional properties
         * @return this builder
         */
        public Builder properties(Map<String, String> properties) {
            this.properties = properties != null ? new HashMap<>(properties) : null;
            return this;
        }

        /**
         * Adds a single property.
         *
         * @param key the property key
         * @param value the property value
         * @return this builder
         */
        public Builder property(String key, String value) {
            if (this.properties == null) {
                this.properties = new HashMap<>();
            }
            this.properties.put(key, value);
            return this;
        }

        /**
         * Sets the metrics prefix for monitoring.
         *
         * @param metricsPrefix the metrics prefix
         * @return this builder
         */
        public Builder metricsPrefix(String metricsPrefix) {
            this.metricsPrefix = metricsPrefix;
            return this;
        }

        /**
         * Builds a new PoolConfig instance.
         *
         * @return a new PoolConfig instance
         * @throws IllegalStateException if minIdle exceeds maxPoolSize
         */
        public PoolConfig build() {
            if (minIdle > maxPoolSize) {
                throw new IllegalStateException("minIdle (" + minIdle + ") cannot exceed maxPoolSize (" + maxPoolSize + ")");
            }
            return new PoolConfig(this);
        }
    }
}
