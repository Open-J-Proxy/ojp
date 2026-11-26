# Implementation Guide: Connection Count Validation for Pool Resizing

## Overview

This document provides a detailed implementation guide for adding connection count validation to OJP's multinode pool resizing logic. This feature queries the database before resizing connection pools to distinguish between true node failures and network partitions.

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    ProcessClusterHealthAction                    │
│  (Existing class - trigger point for pool resizing)              │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                │ calls if validation enabled
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                       PoolResizeValidator                        │
│  - Orchestrates validation process                               │
│  - Implements rate limiting                                      │
│  - Handles time-based overrides                                  │
│  - Makes resize decision                                         │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                │ gets query for database type
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ConnectionCountQueryFactory                     │
│  - Maps DbName to appropriate query implementation               │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                                │ returns
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│              ConnectionCountQuery (Interface)                    │
│  + getQuery(): String                                            │
│  + executeQuery(Connection): int                                 │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                    ┌───────────┴───────────┬───────────────┐
                    ▼                       ▼               ▼
        ┌────────────────────┐  ┌──────────────────┐  ┌─────────────┐
        │ PostgreSQLQuery    │  │   MySQLQuery     │  │ OracleQuery │
        │ MySQLQuery         │  │   SQLServerQuery │  │     ...     │
        │ OracleQuery        │  │   DB2Query       │  │             │
        │ SQLServerQuery     │  │   H2Query        │  │             │
        │ DB2Query           │  │   CockroachQuery │  │             │
        │ H2Query            │  │                  │  │             │
        │ CockroachDBQuery   │  │                  │  │             │
        └────────────────────┘  └──────────────────┘  └─────────────┘
```

### Data Flow

```
1. Client reports cluster health change
   ↓
2. ProcessClusterHealthAction detects health change
   ↓
3. Check if validation is enabled (config)
   ↓
4. PoolResizeValidator.validate() called
   ↓
5. Check rate limit (last validation time)
   ↓
6. Get appropriate ConnectionCountQuery from factory
   ↓
7. Borrow connection from pool
   ↓
8. Execute query with timeout
   ↓
9. Compare result to threshold
   ↓
10. Return ValidationResult (PROCEED or SKIP)
    ↓
11. If SKIP: Log and return (no resize)
    If PROCEED: Continue with pool resize
```

## Implementation Details

### 1. Configuration Properties

Add to `ServerConfiguration.java`:

```java
public class ServerConfiguration {
    // ... existing properties ...
    
    // Connection count validation properties
    private boolean poolResizeValidationEnabled = false;  // Opt-in
    private double poolResizeValidationThreshold = 0.85;  // 85% threshold
    private String poolResizeValidationFailureMode = "PROCEED";  // PROCEED or SKIP
    private int poolResizeValidationQueryTimeout = 5000;  // 5 seconds
    private int poolResizeValidationRateLimitMs = 5000;  // 5 seconds
    private long poolResizeValidationForceResizeAfterMs = 300000;  // 5 minutes
    
    // Getters and setters
    // ...
}
```

Load from properties file:

```properties
# ojp-server.properties
ojp.pool.resize.validation.enabled=false
ojp.pool.resize.validation.connectionThreshold=0.85
ojp.pool.resize.validation.failureMode=PROCEED
ojp.pool.resize.validation.queryTimeout=5000
ojp.pool.resize.validation.rateLimitMs=5000
ojp.pool.resize.validation.forceResizeAfterMs=300000
```

### 2. ConnectionCountQuery Interface

Create new interface:

```java
package org.openjproxy.grpc.server.pool.validation;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Interface for database-specific connection count queries.
 * Implementations provide the SQL query and execution logic to count
 * active connections for the current database user.
 */
public interface ConnectionCountQuery {
    
    /**
     * Returns the SQL query to count connections.
     * The query should:
     * - Count connections for the current user only
     * - Exclude the connection used to execute the query
     * - Optionally filter by connection state (active vs idle)
     * 
     * @return SQL query string
     */
    String getQuery();
    
    /**
     * Executes the connection count query.
     * 
     * @param conn Database connection (from pool)
     * @return Number of active connections for current user
     * @throws SQLException if query execution fails
     */
    int executeQuery(Connection conn) throws SQLException;
    
    /**
     * Returns a human-readable description of what the query does.
     * Used for logging and documentation.
     * 
     * @return Description string
     */
    default String getDescription() {
        return "Counts connections for current user";
    }
}
```

### 3. Database-Specific Implementations

#### PostgreSQL

```java
package org.openjproxy.grpc.server.pool.validation.queries;

import org.openjproxy.grpc.server.pool.validation.ConnectionCountQuery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PostgreSQLConnectionCountQuery implements ConnectionCountQuery {
    
    private static final String QUERY = 
        "SELECT COUNT(*) as connection_count " +
        "FROM pg_stat_activity " +
        "WHERE usename = CURRENT_USER " +
        "  AND pid != pg_backend_pid()";
    
    @Override
    public String getQuery() {
        return QUERY;
    }
    
    @Override
    public int executeQuery(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(QUERY);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("connection_count");
            }
            throw new SQLException("Query returned no results");
        }
    }
    
    @Override
    public String getDescription() {
        return "PostgreSQL: Counts active connections from pg_stat_activity";
    }
}
```

#### MySQL/MariaDB

```java
public class MySQLConnectionCountQuery implements ConnectionCountQuery {
    
    private static final String QUERY =
        "SELECT COUNT(*) as connection_count " +
        "FROM information_schema.PROCESSLIST " +
        "WHERE USER = SUBSTRING_INDEX(USER(), '@', 1) " +
        "  AND ID != CONNECTION_ID()";
    
    @Override
    public String getQuery() {
        return QUERY;
    }
    
    @Override
    public int executeQuery(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(QUERY);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("connection_count");
            }
            throw new SQLException("Query returned no results");
        }
    }
    
    @Override
    public String getDescription() {
        return "MySQL/MariaDB: Counts processes from INFORMATION_SCHEMA";
    }
}
```

#### Oracle

```java
public class OracleConnectionCountQuery implements ConnectionCountQuery {
    
    private static final String QUERY =
        "SELECT COUNT(*) as connection_count " +
        "FROM v$session " +
        "WHERE username = SYS_CONTEXT('USERENV', 'SESSION_USER') " +
        "  AND sid != SYS_CONTEXT('USERENV', 'SID') " +
        "  AND type = 'USER'";
    
    @Override
    public String getQuery() {
        return QUERY;
    }
    
    @Override
    public int executeQuery(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(QUERY);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("connection_count");
            }
            throw new SQLException("Query returned no results");
        }
    }
    
    @Override
    public String getDescription() {
        return "Oracle: Counts sessions from v$session";
    }
}
```

*(Similar implementations for SQL Server, DB2, H2, CockroachDB - see DATABASE_CONNECTION_COUNT_QUERIES.md)*

### 4. ConnectionCountQueryFactory

```java
package org.openjproxy.grpc.server.pool.validation;

import com.openjproxy.grpc.DbName;
import org.openjproxy.grpc.server.pool.validation.queries.*;

/**
 * Factory for creating database-specific connection count queries.
 */
public class ConnectionCountQueryFactory {
    
    /**
     * Returns the appropriate ConnectionCountQuery for the given database type.
     * 
     * @param dbName Database type
     * @return ConnectionCountQuery implementation
     * @throws IllegalArgumentException if database type is not supported
     */
    public static ConnectionCountQuery getQuery(DbName dbName) {
        switch (dbName) {
            case POSTGRES:
                return new PostgreSQLConnectionCountQuery();
            case MYSQL:
            case MARIADB:
                return new MySQLConnectionCountQuery();
            case ORACLE:
                return new OracleConnectionCountQuery();
            case SQLSERVER:
                return new SQLServerConnectionCountQuery();
            case DB2:
                return new DB2ConnectionCountQuery();
            case H2:
                return new H2ConnectionCountQuery();
            case COCKROACHDB:
                return new CockroachDBConnectionCountQuery();
            default:
                throw new IllegalArgumentException(
                    "Connection count query not supported for database: " + dbName);
        }
    }
    
    /**
     * Checks if connection count validation is supported for the given database.
     * 
     * @param dbName Database type
     * @return true if supported, false otherwise
     */
    public static boolean isSupported(DbName dbName) {
        try {
            getQuery(dbName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

### 5. ValidationResult Class

```java
package org.openjproxy.grpc.server.pool.validation;

/**
 * Result of connection count validation.
 */
public class ValidationResult {
    
    public enum Decision {
        /** Proceed with pool resize (node failure confirmed or validation failed) */
        PROCEED_WITH_RESIZE,
        
        /** Skip pool resize (network partition detected) */
        SKIP_RESIZE
    }
    
    private final Decision decision;
    private final int actualConnectionCount;
    private final int threshold;
    private final String reason;
    private final boolean validationPerformed;
    
    private ValidationResult(Decision decision, int actualConnectionCount, 
                            int threshold, String reason, boolean validationPerformed) {
        this.decision = decision;
        this.actualConnectionCount = actualConnectionCount;
        this.threshold = threshold;
        this.reason = reason;
        this.validationPerformed = validationPerformed;
    }
    
    public static ValidationResult proceed(String reason) {
        return new ValidationResult(Decision.PROCEED_WITH_RESIZE, -1, -1, reason, false);
    }
    
    public static ValidationResult proceed(int actualCount, int threshold, String reason) {
        return new ValidationResult(Decision.PROCEED_WITH_RESIZE, actualCount, threshold, reason, true);
    }
    
    public static ValidationResult skip(int actualCount, int threshold, String reason) {
        return new ValidationResult(Decision.SKIP_RESIZE, actualCount, threshold, reason, true);
    }
    
    // Getters
    public Decision getDecision() { return decision; }
    public int getActualConnectionCount() { return actualConnectionCount; }
    public int getThreshold() { return threshold; }
    public String getReason() { return reason; }
    public boolean isValidationPerformed() { return validationPerformed; }
    
    @Override
    public String toString() {
        if (!validationPerformed) {
            return String.format("ValidationResult{decision=%s, reason='%s'}", 
                decision, reason);
        }
        return String.format(
            "ValidationResult{decision=%s, actualCount=%d, threshold=%d, reason='%s'}", 
            decision, actualConnectionCount, threshold, reason);
    }
}
```

### 6. PoolResizeValidator

```java
package org.openjproxy.grpc.server.pool.validation;

import com.openjproxy.grpc.DbName;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates whether pool resizing should proceed by querying the database
 * for the current connection count.
 */
@Slf4j
public class PoolResizeValidator {
    
    private final ServerConfiguration config;
    private final Map<String, Long> lastValidationTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHealthChangeTime = new ConcurrentHashMap<>();
    
    public PoolResizeValidator(ServerConfiguration config) {
        this.config = config;
    }
    
    /**
     * Validates whether pool resize should proceed based on database connection count.
     * 
     * @param connHash Connection hash identifying the datasource
     * @param dataSource DataSource to query
     * @param dbName Database type
     * @param allocation Current pool allocation
     * @param newHealthyServerCount New number of healthy servers
     * @return ValidationResult indicating whether to proceed or skip resize
     */
    public ValidationResult validate(String connHash, 
                                     DataSource dataSource,
                                     DbName dbName,
                                     MultinodePoolCoordinator.PoolAllocation allocation,
                                     int newHealthyServerCount) {
        
        log.debug("Starting pool resize validation: connHash={}, dbName={}, newHealthyServers={}", 
            connHash, dbName, newHealthyServerCount);
        
        // Check rate limiting
        if (!checkRateLimit(connHash)) {
            log.debug("Validation skipped due to rate limiting: connHash={}", connHash);
            return ValidationResult.proceed("Rate limit not reached");
        }
        
        // Check time-based override
        if (shouldForceResize(connHash)) {
            log.info("Forcing pool resize due to time-based override: connHash={}", connHash);
            return ValidationResult.proceed("Time-based override triggered");
        }
        
        // Check if validation is supported for this database
        if (!ConnectionCountQueryFactory.isSupported(dbName)) {
            log.debug("Connection count validation not supported for {}: connHash={}", 
                dbName, connHash);
            return ValidationResult.proceed("Database type not supported: " + dbName);
        }
        
        // Execute validation query
        try {
            int actualCount = queryConnectionCount(dataSource, dbName);
            int threshold = calculateThreshold(allocation);
            
            log.info("Connection count validation: connHash={}, actualCount={}, threshold={}", 
                connHash, actualCount, threshold);
            
            if (actualCount >= threshold) {
                // Likely network partition - high connection count suggests "failed" server is still serving
                String reason = String.format(
                    "Network partition detected: %d connections >= threshold %d", 
                    actualCount, threshold);
                log.warn("Skipping pool resize for {}: {}", connHash, reason);
                return ValidationResult.skip(actualCount, threshold, reason);
            } else {
                // True failure - connection count dropped as expected
                String reason = String.format(
                    "Node failure confirmed: %d connections < threshold %d", 
                    actualCount, threshold);
                log.info("Proceeding with pool resize for {}: {}", connHash, reason);
                return ValidationResult.proceed(actualCount, threshold, reason);
            }
            
        } catch (Exception e) {
            log.warn("Connection count validation failed for {}: {} - {}",
                connHash, e.getClass().getSimpleName(), e.getMessage());
            
            // Fail-open: proceed with resize on validation failure
            if ("PROCEED".equalsIgnoreCase(config.getPoolResizeValidationFailureMode())) {
                return ValidationResult.proceed("Validation failed: " + e.getMessage());
            } else {
                return ValidationResult.skip(-1, -1, "Validation failed (fail-closed): " + e.getMessage());
            }
        }
    }
    
    private boolean checkRateLimit(String connHash) {
        long now = System.currentTimeMillis();
        Long lastTime = lastValidationTime.get(connHash);
        
        if (lastTime == null || (now - lastTime) >= config.getPoolResizeValidationRateLimitMs()) {
            lastValidationTime.put(connHash, now);
            
            // Track first health change time for time-based override
            lastHealthChangeTime.putIfAbsent(connHash, now);
            
            return true;
        }
        
        return false;
    }
    
    private boolean shouldForceResize(String connHash) {
        if (config.getPoolResizeValidationForceResizeAfterMs() <= 0) {
            return false;  // Feature disabled
        }
        
        Long firstChangeTime = lastHealthChangeTime.get(connHash);
        if (firstChangeTime == null) {
            return false;
        }
        
        long elapsed = System.currentTimeMillis() - firstChangeTime;
        if (elapsed >= config.getPoolResizeValidationForceResizeAfterMs()) {
            // Clear tracking to reset timer for next cycle
            lastHealthChangeTime.remove(connHash);
            return true;
        }
        
        return false;
    }
    
    private int queryConnectionCount(DataSource dataSource, DbName dbName) throws SQLException {
        ConnectionCountQuery query = ConnectionCountQueryFactory.getQuery(dbName);
        
        log.debug("Executing connection count query: {}", query.getDescription());
        
        try (Connection conn = dataSource.getConnection()) {
            // Set query timeout
            conn.setNetworkTimeout(null, config.getPoolResizeValidationQueryTimeout());
            
            return query.executeQuery(conn);
        }
    }
    
    private int calculateThreshold(MultinodePoolCoordinator.PoolAllocation allocation) {
        int originalMaxPoolSize = allocation.getOriginalMaxPoolSize();
        double thresholdFraction = config.getPoolResizeValidationThreshold();
        
        return (int) Math.ceil(originalMaxPoolSize * thresholdFraction);
    }
    
    /**
     * Resets validation tracking for a connection hash.
     * Called when connection is closed or datasource is removed.
     * 
     * @param connHash Connection hash to reset
     */
    public void reset(String connHash) {
        lastValidationTime.remove(connHash);
        lastHealthChangeTime.remove(connHash);
        log.debug("Reset validation tracking for connHash={}", connHash);
    }
}
```

### 7. Integration with ProcessClusterHealthAction

Modify `ProcessClusterHealthAction.execute()`:

```java
public class ProcessClusterHealthAction {
    
    private final PoolResizeValidator resizeValidator;  // Add this
    
    public ProcessClusterHealthAction(ServerConfiguration config) {
        this.resizeValidator = new PoolResizeValidator(config);
    }
    
    public void execute(ActionContext context, SessionInfo sessionInfo) {
        // ... existing health check logic ...
        
        if (healthChanged) {
            int healthyServerCount = context.getClusterHealthTracker()
                .countHealthyServers(clusterHealth);
            
            log.info("[POOL-RESIZE] Cluster health changed for {}, healthy servers: {}", 
                    connHash, healthyServerCount);
            
            // NEW: Validate before resizing if enabled
            if (context.getServerConfiguration().isPoolResizeValidationEnabled()) {
                ValidationResult validation = validateResize(
                    context, connHash, healthyServerCount);
                
                if (validation.getDecision() == ValidationResult.Decision.SKIP_RESIZE) {
                    log.info("[POOL-RESIZE] Skipping resize for {}: {}", 
                        connHash, validation.getReason());
                    return;  // Skip resize
                }
                
                log.info("[POOL-RESIZE] Proceeding with resize for {}: {}", 
                    connHash, validation.getReason());
            }
            
            // Update the pool coordinator with new healthy server count
            ConnectionPoolConfigurer.getPoolCoordinator()
                .updateHealthyServers(connHash, healthyServerCount);
            
            // Apply pool size changes
            // ... existing resize logic ...
        }
    }
    
    private ValidationResult validateResize(ActionContext context, 
                                           String connHash, 
                                           int newHealthyServerCount) {
        DataSource ds = context.getDatasourceMap().get(connHash);
        DbName dbName = context.getDbNameMap().get(connHash);
        MultinodePoolCoordinator.PoolAllocation allocation = 
            ConnectionPoolConfigurer.getPoolCoordinator().getPoolAllocation(connHash);
        
        if (ds == null || dbName == null || allocation == null) {
            log.warn("Cannot validate resize: missing datasource, dbName, or allocation");
            return ValidationResult.proceed("Required components not available");
        }
        
        return resizeValidator.validate(connHash, ds, dbName, allocation, newHealthyServerCount);
    }
}
```

## Testing

### Unit Tests

Create `PoolResizeValidatorTest.java`:

```java
class PoolResizeValidatorTest {
    
    @Test
    void testValidationProceedsWhenBelowThreshold() {
        // Setup with 30 max pool size, 85% threshold = 26
        // Simulate 20 actual connections (below threshold)
        // Expect: PROCEED
    }
    
    @Test
    void testValidationSkipsWhenAboveThreshold() {
        // Setup with 30 max pool size, 85% threshold = 26
        // Simulate 28 actual connections (above threshold)
        // Expect: SKIP
    }
    
    @Test
    void testRateLimitingPreventsFrequentQueries() {
        // Call validate() twice within rate limit window
        // Expect: Second call returns PROCEED without querying
    }
    
    @Test
    void testTimeBasedOverrideForcesResize() {
        // Simulate time passing beyond force resize threshold
        // Expect: PROCEED regardless of connection count
    }
    
    @Test
    void testFailOpenOnQueryError() {
        // Configure fail-open mode
        // Simulate query failure
        // Expect: PROCEED
    }
    
    @Test
    void testFailClosedOnQueryError() {
        // Configure fail-closed mode
        // Simulate query failure
        // Expect: SKIP
    }
}
```

### Integration Tests

Create `ConnectionCountValidationIntegrationTest.java`:

```java
class ConnectionCountValidationIntegrationTest {
    
    @Test
    void testPostgreSQLConnectionCountQuery() {
        // Use testcontainers to start PostgreSQL
        // Create 10 connections
        // Query connection count
        // Verify count is approximately 10
    }
    
    @Test
    void testValidationWithRealDatabase() {
        // 3-server setup with HikariCP
        // Create connections
        // Simulate server failure
        // Verify validation correctly detects scenario
    }
}
```

## Deployment

### Phase 1: Development (Weeks 1-2)
- Implement all query classes
- Implement PoolResizeValidator
- Unit tests for all components

### Phase 2: Integration (Week 3)
- Integrate with ProcessClusterHealthAction
- Add configuration properties
- Integration tests with real databases

### Phase 3: Testing (Week 4)
- Load testing
- Network partition simulation
- Performance verification

### Phase 4: Documentation (Week 5)
- Operator guide
- Configuration examples
- Troubleshooting guide

### Phase 5: Release (Week 6)
- Beta testing with select users
- Bug fixes
- GA release

## Rollback Plan

If issues arise:

1. **Immediate**: Disable via config (`ojp.pool.resize.validation.enabled=false`)
2. **Code-level**: Validation is opt-in, so disabling reverts to previous behavior
3. **Emergency**: Remove validation check from ProcessClusterHealthAction (simple if statement)

No database schema changes, so rollback is clean.

## Monitoring

Add metrics:

```java
// In PoolResizeValidator
meter("ojp.pool.resize.validation.attempts", tags("connHash", connHash));
timer("ojp.pool.resize.validation.duration", tags("database", dbName.name()));
counter("ojp.pool.resize.validation.decision", tags("decision", decision.name(), "connHash", connHash));
counter("ojp.pool.resize.validation.errors", tags("error", errorType, "database", dbName.name()));
```

Add logs:

```
INFO: Pool resize validation enabled for connHash={}
DEBUG: Executing connection count query: {} (timeout: {}ms)
INFO: Connection count: {}, threshold: {}, decision: {}
WARN: Connection count validation failed: {} - proceeding with resize
INFO: Skipping pool resize - network partition detected
```

## Conclusion

This implementation provides:

- ✅ Clear separation of concerns (query, validation, integration)
- ✅ Database-agnostic design with easy extensibility
- ✅ Comprehensive error handling
- ✅ Configurable behavior
- ✅ Testable architecture
- ✅ Production-ready monitoring

Estimated implementation: **3-4 weeks** for complete feature with testing and documentation.
