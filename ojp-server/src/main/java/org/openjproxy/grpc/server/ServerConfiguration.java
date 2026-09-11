package org.openjproxy.grpc.server;

import org.openjproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration class for the OJP Server that loads settings from JVM arguments and environment variables.
 * JVM arguments take precedence over environment variables.
 */
public class ServerConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ServerConfiguration.class);

    // Configuration keys
    private static final String SERVER_PORT_KEY = "ojp.server.port";
    private static final String PROMETHEUS_PORT_KEY = "ojp.prometheus.port";
    private static final String OPENTELEMETRY_ENABLED_KEY = "ojp.telemetry.enabled";
    private static final String OPENTELEMETRY_ENDPOINT_KEY = "ojp.opentelemetry.endpoint";
    private static final String THREAD_POOL_SIZE_KEY = "ojp.server.threadPoolSize";
    private static final String VIRTUAL_THREADS_ENABLED_KEY = "ojp.server.virtualThreads.enabled";
    private static final String MAX_REQUEST_SIZE_KEY = "ojp.server.maxRequestSize";
    private static final String LOG_LEVEL_KEY = "ojp.server.logLevel";
    private static final String ALLOWED_IPS_KEY = "ojp.server.allowedIps";
    private static final String CONNECTION_IDLE_TIMEOUT_KEY = "ojp.server.connectionIdleTimeout";
    private static final String PROMETHEUS_ALLOWED_IPS_KEY = "ojp.prometheus.allowedIps";
    private static final String CIRCUIT_BREAKER_TIMEOUT_KEY = "ojp.server.circuitBreakerTimeout";
    private static final String CIRCUIT_BREAKER_THRESHOLD_KEY = "ojp.server.circuitBreakerThreshold";
    private static final String SLOW_QUERY_SEGREGATION_ENABLED_KEY = "ojp.server.slowQuerySegregation.enabled";
    private static final String SLOW_QUERY_SLOT_PERCENTAGE_KEY = "ojp.server.slowQuerySegregation.slowSlotPercentage";
    private static final String SLOW_QUERY_IDLE_TIMEOUT_KEY = "ojp.server.slowQuerySegregation.idleTimeout";
    private static final String SLOW_QUERY_SLOW_SLOT_TIMEOUT_KEY = "ojp.server.slowQuerySegregation.slowSlotTimeout";
    private static final String SLOW_QUERY_FAST_SLOT_TIMEOUT_KEY = "ojp.server.slowQuerySegregation.fastSlotTimeout";
    private static final String SLOW_QUERY_UPDATE_GLOBAL_AVG_INTERVAL_KEY = "ojp.server.slowQuerySegregation.updateGlobalAvgInterval";
    private static final String SLOW_QUERY_CLASSIFICATION_MODE_KEY = "ojp.server.slowQuerySegregation.classificationMode";
    private static final String SLOW_QUERY_THRESHOLD_MS_KEY = "ojp.server.slowQuerySegregation.slowQueryThresholdMs";
    private static final String SLOW_QUERY_MINIMUM_SLOW_QUERY_MS_KEY = "ojp.server.slowQuerySegregation.minimumSlowQueryMs";
    private static final String SLOW_QUERY_SLOW_MULTIPLIER_KEY = "ojp.server.slowQuerySegregation.slowMultiplier";
    private static final String SLOW_QUERY_RECOVERY_MULTIPLIER_KEY = "ojp.server.slowQuerySegregation.recoveryMultiplier";
    private static final String SLOW_QUERY_MIN_SAMPLES_KEY = "ojp.server.slowQuerySegregation.minSamples";
    private static final String SLOW_QUERY_BASELINE_PERCENTILE_KEY = "ojp.server.slowQuerySegregation.baselinePercentile";
    private static final String SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS_KEY = "ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds";
    private static final String ADMISSION_CONTROL_MAX_QUEUE_DEPTH_KEY = "ojp.server.admissionControl.maxQueueDepth";
    private static final String LEGACY_SLOW_QUERY_MAX_QUEUE_DEPTH_KEY = "ojp.server.slowQuerySegregation.maxQueueDepth";
    private static final String MAX_CONCURRENT_REQUESTS_KEY = "ojp.server.maxConcurrentRequests";
    private static final String DRIVERS_PATH_KEY = "ojp.libs.path";
    private static final String SQL_ENHANCER_ENABLED_KEY = "ojp.sql.enhancer.enabled";
    private static final String SQL_ENHANCER_MODE_KEY = "ojp.sql.enhancer.mode";
    private static final String SQL_ENHANCER_DIALECT_KEY = "ojp.sql.enhancer.dialect";
    private static final String SQL_ENHANCER_TARGET_DIALECT_KEY = "ojp.sql.enhancer.targetDialect";
    private static final String SQL_ENHANCER_LOG_OPTIMIZATIONS_KEY = "ojp.sql.enhancer.logOptimizations";
    private static final String SQL_ENHANCER_RULES_KEY = "ojp.sql.enhancer.rules";
    private static final String SQL_ENHANCER_OPTIMIZATION_TIMEOUT_KEY = "ojp.sql.enhancer.optimizationTimeout";
    private static final String SQL_ENHANCER_CACHE_ENABLED_KEY = "ojp.sql.enhancer.cacheEnabled";
    private static final String SQL_ENHANCER_CACHE_SIZE_KEY = "ojp.sql.enhancer.cacheSize";
    private static final String SQL_ENHANCER_FAIL_ON_VALIDATION_ERROR_KEY = "ojp.sql.enhancer.failOnValidationError";
    private static final String STATEMENT_CACHE_ENABLED_KEY = CommonConstants.STATEMENT_CACHE_ENABLED_PROPERTY;
    private static final String STATEMENT_CACHE_MAX_SIZE_KEY = CommonConstants.STATEMENT_CACHE_MAX_SIZE_PROPERTY;
    private static final String STATEMENT_CACHE_SQL_LIMIT_KEY = CommonConstants.STATEMENT_CACHE_SQL_LIMIT_PROPERTY;
    private static final String STATEMENT_CACHE_SERVER_PREPARE_KEY = CommonConstants.STATEMENT_CACHE_SERVER_PREPARE_PROPERTY;
    private static final String STATEMENT_CACHE_PREPARE_THRESHOLD_KEY = CommonConstants.STATEMENT_CACHE_PREPARE_THRESHOLD_PROPERTY;
    private static final String XA_STATEMENT_CACHE_ENABLED_KEY = CommonConstants.XA_STATEMENT_CACHE_ENABLED_PROPERTY;
    private static final String XA_STATEMENT_CACHE_MAX_SIZE_KEY = CommonConstants.XA_STATEMENT_CACHE_MAX_SIZE_PROPERTY;
    private static final String XA_STATEMENT_CACHE_SQL_LIMIT_KEY = CommonConstants.XA_STATEMENT_CACHE_SQL_LIMIT_PROPERTY;
    private static final String XA_STATEMENT_CACHE_SERVER_PREPARE_KEY = CommonConstants.XA_STATEMENT_CACHE_SERVER_PREPARE_PROPERTY;
    private static final String XA_STATEMENT_CACHE_PREPARE_THRESHOLD_KEY = CommonConstants.XA_STATEMENT_CACHE_PREPARE_THRESHOLD_PROPERTY;
    private static final String RESULTSET_ROWS_PER_BLOCK_KEY = CommonConstants.RESULTSET_ROWS_PER_BLOCK_PROPERTY;

    // Schema loader configuration keys
    private static final String SCHEMA_REFRESH_ENABLED_KEY = "ojp.sql.enhancer.schema.refresh.enabled";
    private static final String SCHEMA_REFRESH_INTERVAL_HOURS_KEY = "ojp.sql.enhancer.schema.refresh.interval.hours";
    private static final String SCHEMA_LOAD_TIMEOUT_SECONDS_KEY = "ojp.sql.enhancer.schema.load.timeout.seconds";
    private static final String SCHEMA_FALLBACK_ENABLED_KEY = "ojp.sql.enhancer.schema.fallback.enabled";

    // Session cleanup configuration keys
    private static final String SESSION_CLEANUP_ENABLED_KEY = "ojp.server.sessionCleanup.enabled";
    private static final String SESSION_TIMEOUT_MINUTES_KEY = "ojp.server.sessionCleanup.timeoutMinutes";
    private static final String SESSION_CLEANUP_INTERVAL_MINUTES_KEY = "ojp.server.sessionCleanup.intervalMinutes";

    // Tracing configuration keys
    private static final String TRACING_ENABLED_KEY = "ojp.tracing.enabled";
    private static final String TRACING_ENDPOINT_KEY = "ojp.tracing.endpoint";
    private static final String TRACING_EXPORTER_KEY = "ojp.tracing.exporter";
    private static final String TRACING_SERVICE_NAME_KEY = "ojp.tracing.serviceName";
    private static final String TRACING_SAMPLE_RATE_KEY = "ojp.tracing.sampleRate";

    // Telemetry metrics configuration keys
    private static final String TELEMETRY_GRPC_METRICS_ENABLED_KEY = "ojp.telemetry.grpc.metrics.enabled";
    private static final String TELEMETRY_POOL_METRICS_ENABLED_KEY = "ojp.telemetry.pool.metrics.enabled";
    private static final String TELEMETRY_CACHE_METRICS_ENABLED_KEY = "ojp.telemetry.cache.metrics.enabled";
    private static final String TELEMETRY_CIRCUIT_BREAKER_ENABLED_KEY = "ojp.telemetry.circuitbreaker.enabled";

    // Eager Close ResultSet mode configuration key
    private static final String EAGER_CLOSE_ENABLED_KEY = "ojp.resultset.eagerClose.enabled";

    // TLS configuration keys
    private static final String TLS_ENABLED_KEY = "ojp.server.tls.enabled";
    private static final String TLS_KEYSTORE_PATH_KEY = "ojp.server.tls.keystore.path";
    private static final String TLS_KEYSTORE_PASSWORD_KEY = "ojp.server.tls.keystore.password";
    private static final String TLS_TRUSTSTORE_PATH_KEY = "ojp.server.tls.truststore.path";
    private static final String TLS_TRUSTSTORE_PASSWORD_KEY = "ojp.server.tls.truststore.password";
    private static final String TLS_KEYSTORE_TYPE_KEY = "ojp.server.tls.keystore.type";
    private static final String TLS_TRUSTSTORE_TYPE_KEY = "ojp.server.tls.truststore.type";
    private static final String TLS_CLIENT_AUTH_REQUIRED_KEY = "ojp.server.tls.clientAuthRequired";


    // Default values
    public static final int DEFAULT_SERVER_PORT = CommonConstants.DEFAULT_PORT_NUMBER;
    public static final int DEFAULT_PROMETHEUS_PORT = 9159;
    public static final boolean DEFAULT_OPENTELEMETRY_ENABLED = true;
    public static final String DEFAULT_OPENTELEMETRY_ENDPOINT = "";
    public static final int DEFAULT_THREAD_POOL_SIZE = 200;
    public static final boolean DEFAULT_VIRTUAL_THREADS_ENABLED = false;
    public static final int DEFAULT_MAX_REQUEST_SIZE = 4 * 1024 * 1024; // 4MB
    public static final String DEFAULT_LOG_LEVEL = "INFO";
    public static final boolean DEFAULT_ACCESS_LOGGING = false;
    public static final List<String> DEFAULT_ALLOWED_IPS = List.of(IpWhitelistValidator.ALLOW_ALL_IPS); // Allow all by default
    public static final long DEFAULT_CONNECTION_IDLE_TIMEOUT = 30000; // 30 seconds
    public static final List<String> DEFAULT_PROMETHEUS_ALLOWED_IPS = List.of(IpWhitelistValidator.ALLOW_ALL_IPS); // Allow all by default
    public static final long DEFAULT_CIRCUIT_BREAKER_TIMEOUT = 60000; // 60 seconds
    public static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 3; // 3 failures before opening the circuit breaker.
    public static final boolean DEFAULT_SLOW_QUERY_SEGREGATION_ENABLED = false; // Disabled by default, opt-in
    public static final int DEFAULT_SLOW_QUERY_SLOT_PERCENTAGE = 20; // 20% of slots for slow queries
    public static final long DEFAULT_SLOW_QUERY_IDLE_TIMEOUT = 10000; // 10 seconds idle timeout
    public static final long DEFAULT_SLOW_QUERY_SLOW_SLOT_TIMEOUT = 120000; // 120 seconds slow slot timeout
    public static final long DEFAULT_SLOW_QUERY_FAST_SLOT_TIMEOUT = 60000; // 60 seconds fast slot timeout
    public static final long DEFAULT_SLOW_QUERY_UPDATE_GLOBAL_AVG_INTERVAL = 300; // 300 seconds (5 minutes) global average update interval
    public static final SlowQueryClassificationMode DEFAULT_SLOW_QUERY_CLASSIFICATION_MODE = SlowQueryClassificationMode.RELATIVE_FAST_BASELINE;
    public static final long DEFAULT_SLOW_QUERY_THRESHOLD_MS = QueryPerformanceMonitor.DEFAULT_SLOW_QUERY_THRESHOLD_MS;
    public static final long DEFAULT_SLOW_QUERY_MINIMUM_SLOW_QUERY_MS = QueryPerformanceMonitor.DEFAULT_MINIMUM_SLOW_QUERY_MS;
    public static final double DEFAULT_SLOW_QUERY_SLOW_MULTIPLIER = QueryPerformanceMonitor.DEFAULT_SLOW_MULTIPLIER;
    public static final double DEFAULT_SLOW_QUERY_RECOVERY_MULTIPLIER = QueryPerformanceMonitor.DEFAULT_RECOVERY_MULTIPLIER;
    public static final int DEFAULT_SLOW_QUERY_MIN_SAMPLES = QueryPerformanceMonitor.DEFAULT_MIN_SAMPLES;
    public static final int DEFAULT_SLOW_QUERY_BASELINE_PERCENTILE = QueryPerformanceMonitor.DEFAULT_BASELINE_PERCENTILE;
    public static final long DEFAULT_SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS =
            QueryPerformanceMonitor.DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS;
    public static final int DEFAULT_ADMISSION_CONTROL_MAX_QUEUE_DEPTH = 0; // 0 means auto-calculate from total slots
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 200;
    public static final String DEFAULT_DRIVERS_PATH = "./ojp-libs"; // Default external libraries directory path

    // SQL Enhancer default values
    public static final boolean DEFAULT_SQL_ENHANCER_ENABLED = false; // Disabled by default, opt-in
    public static final String DEFAULT_SQL_ENHANCER_MODE = "VALIDATE"; // VALIDATE, OPTIMIZE, TRANSLATE, ANALYZE
    public static final String DEFAULT_SQL_ENHANCER_DIALECT = "GENERIC"; // GENERIC, POSTGRESQL, MYSQL, ORACLE, SQL_SERVER, H2
    public static final String DEFAULT_SQL_ENHANCER_TARGET_DIALECT = ""; // Empty = no translation
    public static final boolean DEFAULT_SQL_ENHANCER_LOG_OPTIMIZATIONS = true;
    public static final String DEFAULT_SQL_ENHANCER_RULES = ""; // Empty = use safe defaults
    public static final int DEFAULT_SQL_ENHANCER_OPTIMIZATION_TIMEOUT = 100; // milliseconds
    public static final boolean DEFAULT_SQL_ENHANCER_CACHE_ENABLED = true;
    public static final int DEFAULT_SQL_ENHANCER_CACHE_SIZE = 1000;
    public static final boolean DEFAULT_SQL_ENHANCER_FAIL_ON_VALIDATION_ERROR = true;
    public static final boolean DEFAULT_STATEMENT_CACHE_ENABLED = CommonConstants.DEFAULT_STATEMENT_CACHE_ENABLED;
    public static final int DEFAULT_STATEMENT_CACHE_MAX_SIZE = CommonConstants.DEFAULT_STATEMENT_CACHE_MAX_SIZE;
    public static final int DEFAULT_STATEMENT_CACHE_SQL_LIMIT = CommonConstants.DEFAULT_STATEMENT_CACHE_SQL_LIMIT;
    public static final boolean DEFAULT_STATEMENT_CACHE_SERVER_PREPARE = CommonConstants.DEFAULT_STATEMENT_CACHE_SERVER_PREPARE;
    public static final int DEFAULT_STATEMENT_CACHE_PREPARE_THRESHOLD = CommonConstants.DEFAULT_STATEMENT_CACHE_PREPARE_THRESHOLD;

    // Schema loader default values
    public static final boolean DEFAULT_SCHEMA_REFRESH_ENABLED = true;
    public static final long DEFAULT_SCHEMA_REFRESH_INTERVAL_HOURS = 24;
    public static final long DEFAULT_SCHEMA_LOAD_TIMEOUT_SECONDS = 30;
    public static final boolean DEFAULT_SCHEMA_FALLBACK_ENABLED = true;

    // Session cleanup default values
    public static final boolean DEFAULT_SESSION_CLEANUP_ENABLED = true; // Enable session cleanup by default
    public static final long DEFAULT_SESSION_TIMEOUT_MINUTES = 30; // 30 minutes session timeout
    public static final long DEFAULT_SESSION_CLEANUP_INTERVAL_MINUTES = 5; // Run cleanup every 5 minutes

    // Tracing default values
    public static final boolean DEFAULT_TRACING_ENABLED = false; // Disabled by default, opt-in
    public static final String DEFAULT_TRACING_ENDPOINT = "http://localhost:9411/api/v2/spans"; // Zipkin default
    public static final String DEFAULT_TRACING_EXPORTER = "zipkin"; // "zipkin" or "otlp"
    public static final String DEFAULT_TRACING_SERVICE_NAME = "ojp-server";
    public static final double DEFAULT_TRACING_SAMPLE_RATE = 1.0; // 100% sampling when enabled

    // Telemetry metrics default values
    public static final boolean DEFAULT_TELEMETRY_GRPC_METRICS_ENABLED = true; // Enabled by default when OpenTelemetry is enabled
    public static final boolean DEFAULT_TELEMETRY_POOL_METRICS_ENABLED = true; // Enabled by default when OpenTelemetry is enabled
    public static final boolean DEFAULT_TELEMETRY_CACHE_METRICS_ENABLED = true; // Enabled by default when OpenTelemetry is enabled
    public static final boolean DEFAULT_TELEMETRY_CIRCUIT_BREAKER_ENABLED = true; // Enabled by default when OpenTelemetry is enabled

    // Eager Close ResultSet mode default values
    public static final boolean DEFAULT_EAGER_CLOSE_ENABLED = true; // Enabled by default

    // TLS default values
    public static final boolean DEFAULT_TLS_ENABLED = false; // Disabled by default for backwards compatibility
    public static final boolean DEFAULT_TLS_CLIENT_AUTH_REQUIRED = false; // mTLS disabled by default

    // XA pooling default values
    public static final boolean DEFAULT_XA_POOLING_ENABLED = true; // Enable XA pooling by default
    public static final int DEFAULT_XA_MAX_POOL_SIZE = 10;
    public static final int DEFAULT_XA_MIN_IDLE = 2;
    public static final long DEFAULT_XA_MAX_WAIT_MILLIS = 30000; // 30 seconds
    public static final long DEFAULT_XA_IDLE_TIMEOUT_MINUTES = 10;
    public static final long DEFAULT_XA_MAX_LIFETIME_MINUTES = 30;

    // ResultSet streaming default values
    public static final int DEFAULT_RESULTSET_ROWS_PER_BLOCK = CommonConstants.DEFAULT_RESULTSET_ROWS_PER_BLOCK; // 100 rows per streaming block
    public static final int MIN_RESULTSET_ROWS_PER_BLOCK = 1;
    public static final int MAX_RESULTSET_ROWS_PER_BLOCK = 10000;

    // Configuration values
    private final int serverPort;
    private final int prometheusPort;
    private final boolean openTelemetryEnabled;
    private final String openTelemetryEndpoint;
    private final int threadPoolSize;
    private final boolean virtualThreadsEnabled;
    private final int maxRequestSize;
    private final String logLevel;
    private final List<String> allowedIps;
    private final long connectionIdleTimeout;
    private final List<String> prometheusAllowedIps;
    private final long circuitBreakerTimeout;
    private final int circuitBreakerThreshold;
    private final boolean slowQuerySegregationEnabled;
    private final int slowQuerySlotPercentage;
    private final long slowQueryIdleTimeout;
    private final long slowQuerySlowSlotTimeout;
    private final long slowQueryFastSlotTimeout;
    private final long slowQueryUpdateGlobalAvgInterval;
    private final SlowQueryClassificationMode slowQueryClassificationMode;
    private final long slowQueryThresholdMs;
    private final long slowQueryMinimumSlowQueryMs;
    private final double slowQuerySlowMultiplier;
    private final double slowQueryRecoveryMultiplier;
    private final int slowQueryMinSamples;
    private final int slowQueryBaselinePercentile;
    private final long slowQueryBaselineRefreshIntervalSeconds;
    private final int admissionControlMaxQueueDepth;
    private final int maxConcurrentRequests;
    private final String driversPath;
    private final boolean sqlEnhancerEnabled;
    private final String sqlEnhancerMode;
    private final String sqlEnhancerDialect;
    private final String sqlEnhancerTargetDialect;
    private final boolean sqlEnhancerLogOptimizations;
    private final String sqlEnhancerRules;
    private final int sqlEnhancerOptimizationTimeout;
    private final boolean sqlEnhancerCacheEnabled;
    private final int sqlEnhancerCacheSize;
    private final boolean sqlEnhancerFailOnValidationError;
    private final boolean statementCacheEnabled;
    private final int statementCacheMaxSize;
    private final int statementCacheSqlLimit;
    private final boolean statementCacheServerPrepare;
    private final int statementCachePrepareThreshold;
    private final boolean xaStatementCacheEnabled;
    private final int xaStatementCacheMaxSize;
    private final int xaStatementCacheSqlLimit;
    private final boolean xaStatementCacheServerPrepare;
    private final int xaStatementCachePrepareThreshold;

    // Schema loader configuration
    private final boolean schemaRefreshEnabled;
    private final long schemaRefreshIntervalHours;
    private final long schemaLoadTimeoutSeconds;
    private final boolean schemaFallbackEnabled;

    // Session cleanup configuration
    private final boolean sessionCleanupEnabled;
    private final long sessionTimeoutMinutes;
    private final long sessionCleanupIntervalMinutes;

    // Tracing configuration
    private final boolean tracingEnabled;
    private final String tracingEndpoint;
    private final String tracingExporter;
    private final String tracingServiceName;
    private final double tracingSampleRate;

    // Telemetry metrics configuration
    private final boolean telemetryGrpcMetricsEnabled;
    private final boolean telemetryPoolMetricsEnabled;
    private final boolean telemetryCacheMetricsEnabled;
    private final boolean telemetryCircuitBreakerMetricsEnabled;

    // TLS configuration
    private final boolean tlsEnabled;
    private final String tlsKeystorePath;
    private final String tlsKeystorePassword;
    private final String tlsTruststorePath;
    private final String tlsTruststorePassword;
    private final String tlsKeystoreType;
    private final String tlsTruststoreType;
    private final boolean tlsClientAuthRequired;

    // Eager Close ResultSet mode configuration
    private final boolean eagerCloseEnabled;

    // ResultSet streaming configuration
    private final int resultsetRowsPerBlock;


    public ServerConfiguration() {
        this.serverPort = getIntProperty(SERVER_PORT_KEY, DEFAULT_SERVER_PORT);
        this.prometheusPort = getIntProperty(PROMETHEUS_PORT_KEY, DEFAULT_PROMETHEUS_PORT);
        this.openTelemetryEnabled = getBooleanProperty(OPENTELEMETRY_ENABLED_KEY, DEFAULT_OPENTELEMETRY_ENABLED);
        this.openTelemetryEndpoint = getStringProperty(OPENTELEMETRY_ENDPOINT_KEY, DEFAULT_OPENTELEMETRY_ENDPOINT);
        this.threadPoolSize = getIntProperty(THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
        this.virtualThreadsEnabled = getBooleanProperty(VIRTUAL_THREADS_ENABLED_KEY, DEFAULT_VIRTUAL_THREADS_ENABLED);
        this.maxRequestSize = getIntProperty(MAX_REQUEST_SIZE_KEY, DEFAULT_MAX_REQUEST_SIZE);
        this.logLevel = getStringProperty(LOG_LEVEL_KEY, DEFAULT_LOG_LEVEL);
        this.allowedIps = getListProperty(ALLOWED_IPS_KEY, DEFAULT_ALLOWED_IPS);
        this.connectionIdleTimeout = getLongProperty(CONNECTION_IDLE_TIMEOUT_KEY, DEFAULT_CONNECTION_IDLE_TIMEOUT);
        this.prometheusAllowedIps = getListProperty(PROMETHEUS_ALLOWED_IPS_KEY, DEFAULT_PROMETHEUS_ALLOWED_IPS);
        this.circuitBreakerTimeout = getLongProperty(CIRCUIT_BREAKER_TIMEOUT_KEY, DEFAULT_CIRCUIT_BREAKER_TIMEOUT);
        this.circuitBreakerThreshold = getIntProperty(CIRCUIT_BREAKER_THRESHOLD_KEY, DEFAULT_CIRCUIT_BREAKER_THRESHOLD);
        this.slowQuerySegregationEnabled = getBooleanProperty(SLOW_QUERY_SEGREGATION_ENABLED_KEY, DEFAULT_SLOW_QUERY_SEGREGATION_ENABLED);
        this.slowQuerySlotPercentage = getIntProperty(SLOW_QUERY_SLOT_PERCENTAGE_KEY, DEFAULT_SLOW_QUERY_SLOT_PERCENTAGE);
        this.slowQueryIdleTimeout = getLongProperty(SLOW_QUERY_IDLE_TIMEOUT_KEY, DEFAULT_SLOW_QUERY_IDLE_TIMEOUT);
        this.slowQuerySlowSlotTimeout = getLongProperty(SLOW_QUERY_SLOW_SLOT_TIMEOUT_KEY, DEFAULT_SLOW_QUERY_SLOW_SLOT_TIMEOUT);
        this.slowQueryFastSlotTimeout = getLongProperty(SLOW_QUERY_FAST_SLOT_TIMEOUT_KEY, DEFAULT_SLOW_QUERY_FAST_SLOT_TIMEOUT);
        this.slowQueryUpdateGlobalAvgInterval = getLongProperty(SLOW_QUERY_UPDATE_GLOBAL_AVG_INTERVAL_KEY, DEFAULT_SLOW_QUERY_UPDATE_GLOBAL_AVG_INTERVAL);
        this.slowQueryClassificationMode = getSlowQueryClassificationModeProperty(SLOW_QUERY_CLASSIFICATION_MODE_KEY,
                DEFAULT_SLOW_QUERY_CLASSIFICATION_MODE);
        this.slowQueryThresholdMs = getNonNegativeLongProperty(SLOW_QUERY_THRESHOLD_MS_KEY, DEFAULT_SLOW_QUERY_THRESHOLD_MS);
        this.slowQueryMinimumSlowQueryMs = getNonNegativeLongProperty(SLOW_QUERY_MINIMUM_SLOW_QUERY_MS_KEY,
                DEFAULT_SLOW_QUERY_MINIMUM_SLOW_QUERY_MS);
        this.slowQuerySlowMultiplier = getGreaterThanDoubleProperty(SLOW_QUERY_SLOW_MULTIPLIER_KEY,
                DEFAULT_SLOW_QUERY_SLOW_MULTIPLIER, 1.0);
        this.slowQueryRecoveryMultiplier = getRecoveryMultiplierProperty(SLOW_QUERY_RECOVERY_MULTIPLIER_KEY,
                DEFAULT_SLOW_QUERY_RECOVERY_MULTIPLIER, slowQuerySlowMultiplier);
        this.slowQueryMinSamples = getMinimumIntProperty(SLOW_QUERY_MIN_SAMPLES_KEY, DEFAULT_SLOW_QUERY_MIN_SAMPLES, 1);
        this.slowQueryBaselinePercentile = getBoundedIntProperty(SLOW_QUERY_BASELINE_PERCENTILE_KEY,
                DEFAULT_SLOW_QUERY_BASELINE_PERCENTILE, 1, 99);
        this.slowQueryBaselineRefreshIntervalSeconds = getNonNegativeLongProperty(
                SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS_KEY, DEFAULT_SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS);
        this.admissionControlMaxQueueDepth = getNonNegativeIntProperty(ADMISSION_CONTROL_MAX_QUEUE_DEPTH_KEY,
                getNonNegativeIntProperty(LEGACY_SLOW_QUERY_MAX_QUEUE_DEPTH_KEY, DEFAULT_ADMISSION_CONTROL_MAX_QUEUE_DEPTH));
        this.maxConcurrentRequests = getNonNegativeIntProperty(MAX_CONCURRENT_REQUESTS_KEY, DEFAULT_MAX_CONCURRENT_REQUESTS);
        this.driversPath = getStringProperty(DRIVERS_PATH_KEY, DEFAULT_DRIVERS_PATH);
        this.sqlEnhancerEnabled = getBooleanProperty(SQL_ENHANCER_ENABLED_KEY, DEFAULT_SQL_ENHANCER_ENABLED);
        this.sqlEnhancerMode = getStringProperty(SQL_ENHANCER_MODE_KEY, DEFAULT_SQL_ENHANCER_MODE);
        this.sqlEnhancerDialect = getStringProperty(SQL_ENHANCER_DIALECT_KEY, DEFAULT_SQL_ENHANCER_DIALECT);
        this.sqlEnhancerTargetDialect = getStringProperty(SQL_ENHANCER_TARGET_DIALECT_KEY, DEFAULT_SQL_ENHANCER_TARGET_DIALECT);
        this.sqlEnhancerLogOptimizations = getBooleanProperty(SQL_ENHANCER_LOG_OPTIMIZATIONS_KEY, DEFAULT_SQL_ENHANCER_LOG_OPTIMIZATIONS);
        this.sqlEnhancerRules = getStringProperty(SQL_ENHANCER_RULES_KEY, DEFAULT_SQL_ENHANCER_RULES);
        this.sqlEnhancerOptimizationTimeout = getIntProperty(SQL_ENHANCER_OPTIMIZATION_TIMEOUT_KEY, DEFAULT_SQL_ENHANCER_OPTIMIZATION_TIMEOUT);
        this.sqlEnhancerCacheEnabled = getBooleanProperty(SQL_ENHANCER_CACHE_ENABLED_KEY, DEFAULT_SQL_ENHANCER_CACHE_ENABLED);
        this.sqlEnhancerCacheSize = getIntProperty(SQL_ENHANCER_CACHE_SIZE_KEY, DEFAULT_SQL_ENHANCER_CACHE_SIZE);
        this.sqlEnhancerFailOnValidationError = getBooleanProperty(SQL_ENHANCER_FAIL_ON_VALIDATION_ERROR_KEY, DEFAULT_SQL_ENHANCER_FAIL_ON_VALIDATION_ERROR);
        this.statementCacheEnabled = getBooleanProperty(STATEMENT_CACHE_ENABLED_KEY, DEFAULT_STATEMENT_CACHE_ENABLED);
        this.statementCacheMaxSize = getNonNegativeIntProperty(STATEMENT_CACHE_MAX_SIZE_KEY, DEFAULT_STATEMENT_CACHE_MAX_SIZE);
        this.statementCacheSqlLimit = getNonNegativeIntProperty(STATEMENT_CACHE_SQL_LIMIT_KEY, DEFAULT_STATEMENT_CACHE_SQL_LIMIT);
        this.statementCacheServerPrepare = getBooleanProperty(STATEMENT_CACHE_SERVER_PREPARE_KEY, DEFAULT_STATEMENT_CACHE_SERVER_PREPARE);
        this.statementCachePrepareThreshold = getNonNegativeIntProperty(STATEMENT_CACHE_PREPARE_THRESHOLD_KEY,
                DEFAULT_STATEMENT_CACHE_PREPARE_THRESHOLD);
        this.xaStatementCacheEnabled = getBooleanProperty(XA_STATEMENT_CACHE_ENABLED_KEY, DEFAULT_STATEMENT_CACHE_ENABLED);
        this.xaStatementCacheMaxSize = getNonNegativeIntProperty(XA_STATEMENT_CACHE_MAX_SIZE_KEY, DEFAULT_STATEMENT_CACHE_MAX_SIZE);
        this.xaStatementCacheSqlLimit = getNonNegativeIntProperty(XA_STATEMENT_CACHE_SQL_LIMIT_KEY, DEFAULT_STATEMENT_CACHE_SQL_LIMIT);
        this.xaStatementCacheServerPrepare = getBooleanProperty(XA_STATEMENT_CACHE_SERVER_PREPARE_KEY, DEFAULT_STATEMENT_CACHE_SERVER_PREPARE);
        this.xaStatementCachePrepareThreshold = getNonNegativeIntProperty(XA_STATEMENT_CACHE_PREPARE_THRESHOLD_KEY,
                DEFAULT_STATEMENT_CACHE_PREPARE_THRESHOLD);

        // Schema loader configuration
        this.schemaRefreshEnabled = getBooleanProperty(SCHEMA_REFRESH_ENABLED_KEY, DEFAULT_SCHEMA_REFRESH_ENABLED);
        this.schemaRefreshIntervalHours = getLongProperty(SCHEMA_REFRESH_INTERVAL_HOURS_KEY, DEFAULT_SCHEMA_REFRESH_INTERVAL_HOURS);
        this.schemaLoadTimeoutSeconds = getLongProperty(SCHEMA_LOAD_TIMEOUT_SECONDS_KEY, DEFAULT_SCHEMA_LOAD_TIMEOUT_SECONDS);
        this.schemaFallbackEnabled = getBooleanProperty(SCHEMA_FALLBACK_ENABLED_KEY, DEFAULT_SCHEMA_FALLBACK_ENABLED);

        // Session cleanup configuration
        this.sessionCleanupEnabled = getBooleanProperty(SESSION_CLEANUP_ENABLED_KEY, DEFAULT_SESSION_CLEANUP_ENABLED);
        this.sessionTimeoutMinutes = getLongProperty(SESSION_TIMEOUT_MINUTES_KEY, DEFAULT_SESSION_TIMEOUT_MINUTES);
        this.sessionCleanupIntervalMinutes = getLongProperty(SESSION_CLEANUP_INTERVAL_MINUTES_KEY, DEFAULT_SESSION_CLEANUP_INTERVAL_MINUTES);

        // TLS configuration
        this.tlsEnabled = getBooleanProperty(TLS_ENABLED_KEY, DEFAULT_TLS_ENABLED);
        this.tlsKeystorePath = getStringProperty(TLS_KEYSTORE_PATH_KEY, null);
        this.tlsKeystorePassword = getStringProperty(TLS_KEYSTORE_PASSWORD_KEY, null);
        this.tlsTruststorePath = getStringProperty(TLS_TRUSTSTORE_PATH_KEY, null);
        this.tlsTruststorePassword = getStringProperty(TLS_TRUSTSTORE_PASSWORD_KEY, null);
        this.tlsKeystoreType = getStringProperty(TLS_KEYSTORE_TYPE_KEY, "JKS");
        this.tlsTruststoreType = getStringProperty(TLS_TRUSTSTORE_TYPE_KEY, "JKS");
        this.tlsClientAuthRequired = getBooleanProperty(TLS_CLIENT_AUTH_REQUIRED_KEY, DEFAULT_TLS_CLIENT_AUTH_REQUIRED);

        // Eager Close ResultSet mode configuration
        this.eagerCloseEnabled = getBooleanProperty(EAGER_CLOSE_ENABLED_KEY, DEFAULT_EAGER_CLOSE_ENABLED);

        // Tracing configuration
        this.tracingEnabled = getBooleanProperty(TRACING_ENABLED_KEY, DEFAULT_TRACING_ENABLED);
        this.tracingEndpoint = getStringProperty(TRACING_ENDPOINT_KEY, DEFAULT_TRACING_ENDPOINT);
        this.tracingExporter = getStringProperty(TRACING_EXPORTER_KEY, DEFAULT_TRACING_EXPORTER);
        this.tracingServiceName = getStringProperty(TRACING_SERVICE_NAME_KEY, DEFAULT_TRACING_SERVICE_NAME);
        this.tracingSampleRate = getDoubleProperty(TRACING_SAMPLE_RATE_KEY, DEFAULT_TRACING_SAMPLE_RATE);

        // Telemetry metrics configuration
        this.telemetryGrpcMetricsEnabled = getBooleanProperty(TELEMETRY_GRPC_METRICS_ENABLED_KEY, DEFAULT_TELEMETRY_GRPC_METRICS_ENABLED);
        this.telemetryPoolMetricsEnabled = getBooleanProperty(TELEMETRY_POOL_METRICS_ENABLED_KEY, DEFAULT_TELEMETRY_POOL_METRICS_ENABLED);
        this.telemetryCacheMetricsEnabled = getBooleanProperty(TELEMETRY_CACHE_METRICS_ENABLED_KEY, DEFAULT_TELEMETRY_CACHE_METRICS_ENABLED);
        this.telemetryCircuitBreakerMetricsEnabled = getBooleanProperty(TELEMETRY_CIRCUIT_BREAKER_ENABLED_KEY, DEFAULT_TELEMETRY_CIRCUIT_BREAKER_ENABLED);

        // ResultSet streaming configuration
        this.resultsetRowsPerBlock = getBoundedIntProperty(RESULTSET_ROWS_PER_BLOCK_KEY, DEFAULT_RESULTSET_ROWS_PER_BLOCK,
                MIN_RESULTSET_ROWS_PER_BLOCK, MAX_RESULTSET_ROWS_PER_BLOCK);

        logConfigurationSummary();
    }

    /**
     * Gets a string property value. JVM system properties take precedence over environment variables.
     */
    private String getStringProperty(String key, String defaultValue) {
        // First check JVM system properties
        String value = System.getProperty(key);
        if (value != null) {
            logger.debug("Using JVM property {}={}", key, value);
            return value;
        }

        // Then check environment variables (convert dots to underscores and uppercase)
        String envKey = key.replace('.', '_').toUpperCase();
        value = System.getenv(envKey);
        if (value != null) {
            logger.debug("Using environment variable {}={}", envKey, value);
            return value;
        }

        logger.debug("Using default value for {}: {}", key, defaultValue);
        return defaultValue;
    }

    /**
     * Gets an integer property value with validation.
     */
    private int getIntProperty(String key, int defaultValue) {
        String value = getStringProperty(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private int getNonNegativeIntProperty(String key, int defaultValue) {
        int value = getIntProperty(key, defaultValue);
        if (value < 0) {
            logger.warn("Invalid negative value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private int getBoundedIntProperty(String key, int defaultValue, int minValue, int maxValue) {
        int value = getIntProperty(key, defaultValue);
        if (value < minValue || value > maxValue) {
            logger.warn("Value {} for property '{}' is outside the allowed range [{}, {}], using default: {}",
                    value, key, minValue, maxValue, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private long getNonNegativeLongProperty(String key, long defaultValue) {
        long value = getLongProperty(key, defaultValue);
        if (value < 0) {
            logger.warn("Invalid negative value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * Gets a long property value with validation.
     */
    private long getLongProperty(String key, long defaultValue) {
        String value = getStringProperty(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid long value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Gets a boolean property value.
     */
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getStringProperty(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    /**
     * Gets a double property value with validation.
     */
    private double getDoubleProperty(String key, double defaultValue) {
        String value = getStringProperty(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid double value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private double getGreaterThanDoubleProperty(String key, double defaultValue, double minExclusive) {
        double value = getDoubleProperty(key, defaultValue);
        if (value <= minExclusive) {
            logger.warn("Invalid value for property '{}': {}, must be > {}, using default: {}",
                    key, value, minExclusive, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private int getMinimumIntProperty(String key, int defaultValue, int minInclusive) {
        int value = getIntProperty(key, defaultValue);
        if (value < minInclusive) {
            logger.warn("Invalid value for property '{}': {}, must be >= {}, using default: {}",
                    key, value, minInclusive, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private double getRecoveryMultiplierProperty(String key, double defaultValue, double slowMultiplier) {
        double value = getDoubleProperty(key, defaultValue);
        if (value <= 1.0 || value >= slowMultiplier) {
            double fallback = defaultValue;
            if (fallback <= 1.0 || fallback >= slowMultiplier) {
                fallback = (slowMultiplier + 1.0) / 2.0;
            }
            logger.warn("Invalid value for property '{}': {}, must be > 1.0 and < slowMultiplier={}, using fallback: {}",
                    key, value, slowMultiplier, fallback);
            return fallback;
        }
        return value;
    }

    private SlowQueryClassificationMode getSlowQueryClassificationModeProperty(String key,
                                                                               SlowQueryClassificationMode defaultValue) {
        String rawValue = getStringProperty(key, defaultValue.name());
        try {
            return SlowQueryClassificationMode.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid slow query classification mode for property '{}': {}, using default: {}",
                    key, rawValue, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Gets a list property value (comma-separated).
     */
    private List<String> getListProperty(String key, List<String> defaultValue) {
        String value = getStringProperty(key, String.join(",", defaultValue));
        if (value.trim().isEmpty()) {
            return new ArrayList<>(defaultValue);
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Logs a summary of the current configuration.
     */
    private void logConfigurationSummary() {
        logger.info("OJP Server Configuration:");
        logger.info("  Server Port: {}", serverPort);
        logger.info("  Prometheus Port: {}", prometheusPort);
        logger.info("  OpenTelemetry Enabled: {}", openTelemetryEnabled);
        logger.info("  OpenTelemetry Endpoint: {}", openTelemetryEndpoint.isEmpty() ? "default" : openTelemetryEndpoint);
        logger.info("  Virtual Threads Enabled: {}", virtualThreadsEnabled);
        if (virtualThreadsEnabled) {
            logger.info("  Thread Pool Size: {} (used only when virtual threads are disabled)", threadPoolSize);
        } else {
            logger.info("  Thread Pool Size: {}", threadPoolSize);
        }
        logger.info("  Max Request Size: {} bytes", maxRequestSize);
        logger.info("  Log Level: {}", logLevel);
        logger.info("  Allowed IPs: {}", allowedIps);
        logger.info("  Connection Idle Timeout: {} ms", connectionIdleTimeout);
        logger.info("  Prometheus Allowed IPs: {}", prometheusAllowedIps);
        logger.info("  Circuit Breaker Timeout: {} ms", circuitBreakerTimeout);
        logger.info("  Circuit Breaker Threshold: {} ", circuitBreakerThreshold);
        logger.info("  Slow Query Segregation Enabled: {}", slowQuerySegregationEnabled);
        logger.info("  Slow Query Slot Percentage: {}%", slowQuerySlotPercentage);
        logger.info("  Slow Query Idle Timeout: {} ms", slowQueryIdleTimeout);
        logger.info("  Slow Query Slow Slot Timeout: {} ms", slowQuerySlowSlotTimeout);
        logger.info("  Slow Query Fast Slot Timeout: {} ms", slowQueryFastSlotTimeout);
        logger.info("  Slow Query Update Global Avg Interval: {} seconds", slowQueryUpdateGlobalAvgInterval);
        if (slowQuerySegregationEnabled) {
            logger.info("  SQS Classification Mode: {}", slowQueryClassificationMode);
            logger.info("  SQS Minimum Slow Query: {} ms", slowQueryMinimumSlowQueryMs);
            logger.info("  SQS Slow Multiplier: {}", slowQuerySlowMultiplier);
            logger.info("  SQS Recovery Multiplier: {}", slowQueryRecoveryMultiplier);
            logger.info("  SQS Min Samples: {}", slowQueryMinSamples);
            logger.info("  SQS Baseline Percentile: {}", slowQueryBaselinePercentile);
            logger.info("  SQS Baseline Refresh Interval: {} seconds", slowQueryBaselineRefreshIntervalSeconds);
            logger.info("  SQS Slow Query Threshold: {} ms", slowQueryThresholdMs);
        }
        logger.info("  Admission Control Max Queue Depth: {} (0 means auto)", admissionControlMaxQueueDepth);
        logger.info("  Max Concurrent Requests: {} (0 means unlimited)", maxConcurrentRequests);
        logger.info("  External Libraries Path: {}", driversPath);
        logger.info("  SQL Enhancer Enabled: {}", sqlEnhancerEnabled);
        logger.info("  SQL Enhancer Mode: {}", sqlEnhancerMode);
        logger.info("  SQL Enhancer Dialect: {}", sqlEnhancerDialect);
        logger.info("  SQL Enhancer Target Dialect: {}", sqlEnhancerTargetDialect.isEmpty() ? "none (no translation)" : sqlEnhancerTargetDialect);
        logger.info("  SQL Enhancer Log Optimizations: {}", sqlEnhancerLogOptimizations);
        logger.info("  SQL Enhancer Rules: {}", sqlEnhancerRules.isEmpty() ? "default (safe rules)" : sqlEnhancerRules);
        logger.info("  SQL Enhancer Optimization Timeout: {} ms", sqlEnhancerOptimizationTimeout);
        logger.info("  SQL Enhancer Cache Enabled: {}", sqlEnhancerCacheEnabled);
        logger.info("  SQL Enhancer Cache Size: {}", sqlEnhancerCacheSize);
        logger.info("  SQL Enhancer Fail On Validation Error: {}", sqlEnhancerFailOnValidationError);
        logger.info("Statement Cache Configuration:");
        logger.info("  Statement Cache (non-XA): enabled={}, maxSize={}, sqlLimit={}, serverPrepare={}, prepareThreshold={}",
                statementCacheEnabled, statementCacheMaxSize, statementCacheSqlLimit,
                statementCacheServerPrepare, statementCachePrepareThreshold);
        logger.info("  Statement Cache (XA): enabled={}, maxSize={}, sqlLimit={}, serverPrepare={}, prepareThreshold={}",
                xaStatementCacheEnabled, xaStatementCacheMaxSize, xaStatementCacheSqlLimit,
                xaStatementCacheServerPrepare, xaStatementCachePrepareThreshold);
        logger.info("Session Cleanup Configuration:");
        logger.info("  Session Cleanup Enabled: {}", sessionCleanupEnabled);
        logger.info("  Session Timeout: {} minutes", sessionTimeoutMinutes);
        logger.info("  Cleanup Interval: {} minutes", sessionCleanupIntervalMinutes);
        logger.info("TLS Configuration:");
        logger.info("  TLS Enabled: {}", tlsEnabled);
        if (tlsEnabled) {
            if (logger.isInfoEnabled()) {
                logger.info("  TLS Keystore Path: {}", maskPath(tlsKeystorePath));
                logger.info("  TLS Truststore Path: {}", maskPath(tlsTruststorePath));
            }
            logger.info("  TLS Client Auth Required (mTLS): {}", tlsClientAuthRequired);
            logger.info("  TLS Keystore Type: {}", tlsKeystoreType);
            logger.info("  TLS Truststore Type: {}", tlsTruststoreType);
        }
        logger.info("Eager Close ResultSet Mode:");
        logger.info("  Eager Close Enabled: {}", eagerCloseEnabled);
        logger.info("Tracing Configuration:");
        logger.info("  Tracing Enabled: {}", tracingEnabled);
        if (tracingEnabled) {
            logger.info("  Tracing Exporter: {}", tracingExporter);
            logger.info("  Tracing Endpoint: {}", tracingEndpoint);
            logger.info("  Tracing Service Name: {}", tracingServiceName);
            logger.info("  Tracing Sample Rate: {}", tracingSampleRate);
        }
        logger.info("ResultSet Streaming Configuration:");
        logger.info("  ResultSet Rows Per Block: {}", resultsetRowsPerBlock);
    }

    /**
     * Masks sensitive path information for logging.
     * Shows whether path is configured without revealing full path.
     *
     * @param path The file path to mask
     * @return Masked path representation
     */
    private String maskPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "not configured (using JVM default)";
        }
        // Show only the filename, not the full path
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSeparator >= 0 && lastSeparator < path.length() - 1) {
            return "***/" + path.substring(lastSeparator + 1);
        }
        return "configured";
    }

    // Getters
    public int getServerPort() {
        return serverPort;
    }

    public int getPrometheusPort() {
        return prometheusPort;
    }

    public boolean isOpenTelemetryEnabled() {
        return openTelemetryEnabled;
    }

    public String getOpenTelemetryEndpoint() {
        return openTelemetryEndpoint;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public boolean isVirtualThreadsEnabled() {
        return virtualThreadsEnabled;
    }

    public int getMaxRequestSize() {
        return maxRequestSize;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public List<String> getAllowedIps() {
        return new ArrayList<>(allowedIps);
    }

    public long getConnectionIdleTimeout() {
        return connectionIdleTimeout;
    }

    public List<String> getPrometheusAllowedIps() {
        return new ArrayList<>(prometheusAllowedIps);
    }

    public long getCircuitBreakerTimeout() {
        return circuitBreakerTimeout;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public boolean isSlowQuerySegregationEnabled() {
        return slowQuerySegregationEnabled;
    }

    public int getSlowQuerySlotPercentage() {
        return slowQuerySlotPercentage;
    }

    public long getSlowQueryIdleTimeout() {
        return slowQueryIdleTimeout;
    }

    public long getSlowQuerySlowSlotTimeout() {
        return slowQuerySlowSlotTimeout;
    }

    public long getSlowQueryFastSlotTimeout() {
        return slowQueryFastSlotTimeout;
    }

    public long getSlowQueryUpdateGlobalAvgInterval() {
        return slowQueryUpdateGlobalAvgInterval;
    }

    public SlowQueryClassificationMode getSlowQueryClassificationMode() {
        return slowQueryClassificationMode;
    }

    public long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    public long getSlowQueryMinimumSlowQueryMs() {
        return slowQueryMinimumSlowQueryMs;
    }

    public double getSlowQuerySlowMultiplier() {
        return slowQuerySlowMultiplier;
    }

    public double getSlowQueryRecoveryMultiplier() {
        return slowQueryRecoveryMultiplier;
    }

    public int getSlowQueryMinSamples() {
        return slowQueryMinSamples;
    }

    public int getSlowQueryBaselinePercentile() {
        return slowQueryBaselinePercentile;
    }

    public long getSlowQueryBaselineRefreshIntervalSeconds() {
        return slowQueryBaselineRefreshIntervalSeconds;
    }

    public int getAdmissionControlMaxQueueDepth() {
        return admissionControlMaxQueueDepth;
    }

    /**
     * @deprecated Use {@link #getAdmissionControlMaxQueueDepth()} instead.
     */
    @Deprecated(since = "0.4.16", forRemoval = false)
    public int getSlowQueryMaxQueueDepth() {
        return getAdmissionControlMaxQueueDepth();
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public String getDriversPath() {
        return driversPath;
    }

    public boolean isSqlEnhancerEnabled() {
        return sqlEnhancerEnabled;
    }

    public String getSqlEnhancerMode() {
        return sqlEnhancerMode;
    }

    public String getSqlEnhancerDialect() {
        return sqlEnhancerDialect;
    }

    public String getSqlEnhancerTargetDialect() {
        return sqlEnhancerTargetDialect;
    }

    public boolean isSqlEnhancerLogOptimizations() {
        return sqlEnhancerLogOptimizations;
    }

    public String getSqlEnhancerRules() {
        return sqlEnhancerRules;
    }

    public int getSqlEnhancerOptimizationTimeout() {
        return sqlEnhancerOptimizationTimeout;
    }

    public boolean isSqlEnhancerCacheEnabled() {
        return sqlEnhancerCacheEnabled;
    }

    public int getSqlEnhancerCacheSize() {
        return sqlEnhancerCacheSize;
    }

    public boolean isSqlEnhancerFailOnValidationError() {
        return sqlEnhancerFailOnValidationError;
    }

    public boolean isStatementCacheEnabled() {
        return statementCacheEnabled;
    }

    public int getStatementCacheMaxSize() {
        return statementCacheMaxSize;
    }

    public int getStatementCacheSqlLimit() {
        return statementCacheSqlLimit;
    }

    public boolean isStatementCacheServerPrepare() {
        return statementCacheServerPrepare;
    }

    public int getStatementCachePrepareThreshold() {
        return statementCachePrepareThreshold;
    }

    public boolean isXaStatementCacheEnabled() {
        return xaStatementCacheEnabled;
    }

    public int getXaStatementCacheMaxSize() {
        return xaStatementCacheMaxSize;
    }

    public int getXaStatementCacheSqlLimit() {
        return xaStatementCacheSqlLimit;
    }

    public boolean isXaStatementCacheServerPrepare() {
        return xaStatementCacheServerPrepare;
    }

    public int getXaStatementCachePrepareThreshold() {
        return xaStatementCachePrepareThreshold;
    }

    public boolean isSchemaRefreshEnabled() {
        return schemaRefreshEnabled;
    }

    public long getSchemaRefreshIntervalHours() {
        return schemaRefreshIntervalHours;
    }

    public long getSchemaLoadTimeoutSeconds() {
        return schemaLoadTimeoutSeconds;
    }

    public boolean isSchemaFallbackEnabled() {
        return schemaFallbackEnabled;
    }

    public boolean isSessionCleanupEnabled() {
        return sessionCleanupEnabled;
    }

    public long getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public long getSessionCleanupIntervalMinutes() {
        return sessionCleanupIntervalMinutes;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public String getTlsKeystorePath() {
        return tlsKeystorePath;
    }

    public String getTlsKeystorePassword() {
        return tlsKeystorePassword;
    }

    public String getTlsTruststorePath() {
        return tlsTruststorePath;
    }

    public String getTlsTruststorePassword() {
        return tlsTruststorePassword;
    }

    public String getTlsKeystoreType() {
        return tlsKeystoreType;
    }

    public String getTlsTruststoreType() {
        return tlsTruststoreType;
    }

    public boolean isTlsClientAuthRequired() {
        return tlsClientAuthRequired;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public String getTracingEndpoint() {
        return tracingEndpoint;
    }

    public String getTracingExporter() {
        return tracingExporter;
    }

    public String getTracingServiceName() {
        return tracingServiceName;
    }

    public double getTracingSampleRate() {
        return tracingSampleRate;
    }

    public boolean isTelemetryGrpcMetricsEnabled() {
        return telemetryGrpcMetricsEnabled;
    }

    public boolean isTelemetryPoolMetricsEnabled() {
        return telemetryPoolMetricsEnabled;
    }

    public boolean isTelemetryCircuitBreakerMetricsEnabled() {
        return telemetryCircuitBreakerMetricsEnabled;
    }

    public boolean isTelemetryCacheMetricsEnabled() {
        return telemetryCacheMetricsEnabled;
    }

    public boolean isEagerCloseEnabled() {
        return eagerCloseEnabled;
    }

    public int getResultsetRowsPerBlock() {
        return resultsetRowsPerBlock;
    }

}
