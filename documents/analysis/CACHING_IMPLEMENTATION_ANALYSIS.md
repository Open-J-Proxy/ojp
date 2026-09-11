# OJP Query Result Caching Implementation Analysis

**Date:** February 11, 2026  
**Status:** ✅ **IMPLEMENTED** (v0.5.0-beta)  
**Scope:** SELECT statement result caching with local storage

---

## Executive Summary

This document provides the comprehensive analysis that guided the implementation of query result caching in Open J Proxy (OJP) for SELECT statements. The implementation follows the recommended approach outlined here and is fully operational in v0.5.0-beta.

**Implementation Status:**
- ✅ Client-side configuration via `ojp.properties`
- ✅ Local cache storage using Caffeine cache (per-server, independent)
- ✅ Automatic cache invalidation on DML operations (local server only)
- ✅ OpenTelemetry metrics integration
- ✅ Security validation and size limits
- ⏳ Distributed caching with cross-server synchronization (under discussion for future release)

> **⚠️ Multi-Server Limitation:** In multi-server deployments, each OJP instance maintains its own independent cache. Write operations invalidate only the local server's cache. Other servers' caches remain until TTL expiry. Use shorter TTLs (30-60s) in clustered environments.

**Key Achievements:**
- ✅ Zero application code changes required
- ✅ Works seamlessly with ORMs (Hibernate, Spring Data JPA)
- ✅ Production-ready with comprehensive testing (674 tests)
- ✅ Full observability through metrics and logging

---

## ⭐ IMPLEMENTED DESIGN

The implementation follows the recommended approach with local caching:

### 1. Query Marking: Client-Side Configuration in `ojp.properties` ✅ **IMPLEMENTED**

**Configuration is defined client-side** in the same `ojp.properties` file used for connection pools and datasource configuration:

```properties
# In ojp.properties (with connection pool config)
postgres_prod.ojp.cache.enabled=true
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = ?
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=users
```

**Why this approach:**
- ✅ **Follows existing OJP patterns** - All datasource config is already client-side
- ✅ **Simple** - No server-side config files or hot-reload mechanisms
- ✅ **Decoupled** - Each datasource controls its own cache independently
- ✅ **No OJP restart** - Only restart affected application, not OJP server
- ✅ **Works with ORMs** - Pattern matching works for Hibernate/Spring Data generated SQL

### 2. Cache Distribution: JDBC Driver as Active Relay (Optional) ✅

**Cache data distribution is OPTIONAL** and controlled per-datasource:

**When distribution is enabled**, cached data is distributed by the JDBC driver when returning query results:
- Data is already in driver memory (being returned to application)
- Driver streams cached results to other OJP servers via virtual threads (Java 25+)
- Smart distribution policy: Only distribute results < 200KB, TTL > 60s, > 1 row

**Why this approach:**
- ✅ **Optional** - Can use local-only caching for simpler deployments
- ✅ **Data already in memory** - No additional database queries needed when distributing
- ✅ **Saves N-1 database queries** - The whole point of distributed caching
- ✅ **Real-time propagation** - Immediate, no polling delays
- ✅ **Zero database overhead** - No notification tables or polling

### 3. Future Enhancement Options (Under Discussion)

**Note:** These are planned future enhancements for distributed cache synchronization, not implemented in v0.5.0-beta.

- **Legacy Java (<21)**: Use JDBC Notification Table (polling-based)
- **PostgreSQL-only**: Use LISTEN/NOTIFY for real-time propagation
- **Very large clusters (20+)**: Consider Redis + JDBC hybrid

---

### Implementation Flow

1. **Configuration**: Define cache rules in client's `ojp.properties` file (with optional distribution)
2. **Connection**: JDBC driver sends cache config to OJP server during connection
3. **Per-Session Storage**: Server stores cache rules for each session
4. **Query Execution**: Server matches queries against session's cache rules
5. **Cache Hit**: Return cached result immediately
6. **Cache Miss**: Execute query, cache result locally
7. **Distribution** (if enabled): Driver distributes cached result to other servers
8. **Invalidation**: DML operations invalidate affected cache entries

---

### Alternative Approaches Considered

The document explores other approaches for completeness, but they are **not recommended** for the following reasons:

- **SQL Comment Hints** (`/* @cache */`): Doesn't work with ORMs (Hibernate, Spring Data)
- **Server-Side Configuration**: Too complex (hot-reload, admin API, affects multiple apps)
- **JDBC Notification Table**: Adds database overhead, polling latency (good fallback though)
- **Redis**: Additional infrastructure complexity (only for very large clusters)

**See Section 13 for detailed comparison and rationale.**

---

## 1. Background: OJP Architecture

### 1.1 Current Query Flow

```mermaid
flowchart TD
    A[Application JDBC] --> B[OJP JDBC Driver Type 3]
    B -->|gRPC| C[OJP Server]
    C --> D[Connection Pool HikariCP]
    D --> E[Target Database<br/>PostgreSQL, MySQL, Oracle, etc.]
```

### 1.2 Existing Caching Mechanisms

OJP already implements caching in specific areas:

#### SqlEnhancerEngine Cache
- **Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SqlEnhancerEngine.java`
- **Purpose:** Caches SQL enhancement results (parsing, validation, optimization)
- **Implementation:** `ConcurrentHashMap<String, SqlEnhancementResult>`
- **Key:** Original SQL string
- **Thread-Safety:** Fully concurrent, lock-free reads

```java
private final ConcurrentHashMap<String, SqlEnhancementResult> cache;
```

#### SchemaCache
- **Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SchemaCache.java`
- **Purpose:** Caches database schema metadata for SQL enhancement
- **Implementation:** Volatile reference with atomic refresh locking
- **Thread-Safety:** Thread-safe via volatile semantics and AtomicBoolean

```java
private volatile SchemaMetadata currentSchema;
private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
```

### 1.3 Key Extension Points

OJP's architecture provides several excellent extension points for caching:

1. **Action Pattern** - Modular request handlers in StatementServiceImpl
2. **SQL Enhancement Pipeline** - Already intercepts and analyzes all SQL
3. **gRPC Protocol** - Metadata can be passed via request/response headers
4. **Multinode Architecture** - Existing infrastructure for server-to-server communication

---

## 2. Caching Strategy Options

### 2.1 Option 1: SQL String-Based Caching

**Approach:** Cache query results using the SQL string + parameter values as the cache key.

#### Implementation Overview

```java
// Cache structure
public class QueryResultCache {
    private final ConcurrentHashMap<QueryCacheKey, CachedResult> cache;
    
    static class QueryCacheKey {
        private final String sql;
        private final List<Object> parameters;
        private final int hashCode;
        
        @Override
        public boolean equals(Object o) {
            // Compare SQL and parameters
        }
        
        @Override
        public int hashCode() {
            return hashCode; // Pre-computed
        }
    }
    
    static class CachedResult {
        private final List<List<Object>> rows;      // Result data
        private final ResultSetMetaData metadata;    // Column info
        private final long timestamp;                // Cache time
        private final long ttl;                      // Time-to-live
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > ttl;
        }
    }
}
```

#### Integration Point: ExecuteQueryAction

```java
// ojp-server/src/main/java/org/openjproxy/grpc/server/action/statement/ExecuteQueryAction.java
public class ExecuteQueryAction implements Action {
    
    private final QueryResultCache resultCache;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager sessionManager) {
        String sql = request.getSql();
        List<Object> params = extractParameters(request);
        
        // Check if query is marked as cacheable
        if (isCacheable(request)) {
            QueryCacheKey key = new QueryCacheKey(sql, params);
            
            // Try to get from cache
            CachedResult cached = resultCache.get(key);
            if (cached != null && !cached.isExpired()) {
                log.debug("Cache HIT for query: {}", sql);
                return buildOpResultFromCache(cached);
            }
            
            // Cache MISS - execute query
            OpResult result = executeQueryOnDatabase(request, sessionManager);
            
            // Store in cache if successful
            if (result.getSuccess()) {
                resultCache.put(key, extractResultForCache(result));
            }
            
            return result;
        }
        
        // Non-cacheable query - execute normally
        return executeQueryOnDatabase(request, sessionManager);
    }
}
```

#### Advantages
- ✅ Simple and straightforward implementation
- ✅ Works with parameterized queries
- ✅ No application code changes needed
- ✅ Automatic cache key generation

#### Disadvantages
- ⚠️ Cannot handle semantically equivalent queries with different SQL text
  - Example: `SELECT * FROM users WHERE id=1` vs `SELECT * FROM users WHERE 1=id`
- ⚠️ Parameter order matters (could be normalized)
- ⚠️ No semantic understanding of query dependencies

#### Best For
- Read-heavy workloads with repeated identical queries
- Parameterized prepared statements
- Simple caching with minimal configuration

---

### 2.2 Option 2: Semantic Query Analysis with Apache Calcite

**Approach:** Use OJP's existing SqlEnhancerEngine to analyze queries semantically and create normalized cache keys.

#### Implementation Overview

```java
public class SemanticQueryCache {
    private final ConcurrentHashMap<NormalizedQuery, CachedResult> cache;
    private final SqlEnhancerEngine enhancer;
    
    static class NormalizedQuery {
        private final RelNode relationalAlgebra;  // Calcite's RelNode
        private final List<Object> parameters;
        
        @Override
        public boolean equals(Object o) {
            // Compare RelNode structure (normalized)
            return RelOptUtil.eq(this.relationalAlgebra, 
                                 ((NormalizedQuery)o).relationalAlgebra)
                && Objects.equals(this.parameters, 
                                 ((NormalizedQuery)o).parameters);
        }
    }
    
    public CachedResult lookup(String sql, List<Object> params) {
        // Parse SQL to RelNode using SqlEnhancerEngine
        RelNode normalized = enhancer.parseAndNormalize(sql);
        
        if (normalized == null) {
            return null; // Parsing failed
        }
        
        NormalizedQuery key = new NormalizedQuery(normalized, params);
        return cache.get(key);
    }
}
```

#### Integration with Existing SqlEnhancerEngine

The SqlEnhancerEngine already converts SQL to RelNode:

```java
// Existing code in SqlEnhancerEngine.java
private RelNode convertToRelNode(SqlNode validatedNode, SchemaMetadata schema) {
    // ... existing implementation ...
    // Returns normalized relational algebra representation
}
```

#### Advantages
- ✅ Handles semantically equivalent queries
  - `SELECT * FROM users WHERE id=1` ≡ `SELECT * FROM users WHERE 1=id`
- ✅ Leverages existing SqlEnhancerEngine infrastructure
- ✅ Can detect query dependencies (table access patterns)
- ✅ Enables intelligent cache invalidation

#### Disadvantages
- ⚠️ Requires SqlEnhancerEngine to be enabled
- ⚠️ Higher CPU overhead for cache key generation
- ⚠️ May encounter type system issues (as documented in INVESTIGATION_SQL_ENHANCER.md)
- ⚠️ Complex implementation

#### Best For
- Environments already using SQL enhancement
- Applications with varying query patterns
- Scenarios requiring sophisticated cache invalidation

---

### 2.3 Option 3: Hybrid Approach

**Approach:** Combine simple string-based caching with optional semantic analysis.

```java
public class HybridQueryCache {
    private final QueryResultCache stringCache;      // Fast path
    private final SemanticQueryCache semanticCache;  // Slow path
    private final boolean semanticEnabled;
    
    public CachedResult lookup(String sql, List<Object> params) {
        // Try fast string-based cache first
        CachedResult result = stringCache.get(sql, params);
        if (result != null) {
            return result;
        }
        
        // Fall back to semantic cache if enabled
        if (semanticEnabled) {
            return semanticCache.lookup(sql, params);
        }
        
        return null;
    }
}
```

---

## 3. Marking Queries as Cacheable

### 3.1 SQL Comment Hints

**Approach:** Use SQL comments to mark queries as cacheable.

#### Syntax Options

**Option A: Standard SQL Comments**
```sql
-- @cache ttl=300s
SELECT * FROM products WHERE category = 'electronics';

/* @cache ttl=5m invalidate_on=products */
SELECT id, name, price FROM products WHERE active = true;
```

**Option B: Hint-Style Comments**
```sql
SELECT /*+ CACHE(ttl=300) */ * FROM products;

SELECT /*+ CACHE(ttl=5m, key=product_list) */ 
  id, name, price 
FROM products;
```

#### Implementation

```java
public class CacheHintParser {
    private static final Pattern CACHE_HINT_PATTERN = 
        Pattern.compile("/\\*\\+\\s*CACHE\\(([^)]+)\\)\\s*\\*/");
    
    private static final Pattern COMMENT_HINT_PATTERN = 
        Pattern.compile("--\\s*@cache\\s+(.+)");
    
    public static CacheDirective parseCacheHint(String sql) {
        // Try hint-style comment
        Matcher m1 = CACHE_HINT_PATTERN.matcher(sql);
        if (m1.find()) {
            return parseCacheParams(m1.group(1));
        }
        
        // Try standard comment
        Matcher m2 = COMMENT_HINT_PATTERN.matcher(sql);
        if (m2.find()) {
            return parseCacheParams(m2.group(1));
        }
        
        return null; // Not cacheable
    }
    
    private static CacheDirective parseCacheParams(String params) {
        // Parse: ttl=300s, key=mykey, invalidate_on=table1,table2
        // Returns CacheDirective object
    }
}

public class CacheDirective {
    private final Duration ttl;
    private final String key;  // Optional explicit cache key
    private final Set<String> invalidateOnTables;
}
```

#### Integration in ExecuteQueryAction

```java
@Override
public OpResult execute(StatementRequest request, SessionManager sessionManager) {
    String sql = request.getSql();
    
    // Parse cache directive from SQL
    CacheDirective directive = CacheHintParser.parseCacheHint(sql);
    
    if (directive != null) {
        // Query is cacheable with specified settings
        return executeCachedQuery(request, directive, sessionManager);
    }
    
    // Not cacheable - execute normally
    return executeQueryOnDatabase(request, sessionManager);
}
```

#### Advantages
- ✅ Simple and explicit control
- ✅ No application code changes (just SQL modification)
- ✅ Works with any programming language/framework
- ✅ Human-readable and self-documenting
- ✅ Supports per-query TTL and invalidation rules

#### Disadvantages
- ⚠️ Requires SQL modification
- ⚠️ Can clutter SQL with comments
- ⚠️ Hint parsing adds slight overhead

#### Best For
- Applications with full control over SQL
- Scenarios requiring fine-grained cache control
- Teams comfortable with SQL hints (similar to Oracle hints)

---

### 3.2 JDBC Connection Properties

**Approach:** Configure caching behavior via JDBC connection URL or properties.

#### URL-Based Configuration

```java
// Enable caching for all SELECT statements
jdbc:ojp[localhost:1059]_postgresql://db:5432/mydb?cacheEnabled=true&cacheTtl=300

// Regex pattern for cacheable queries
jdbc:ojp[localhost:1059]_postgresql://db:5432/mydb
  ?cacheEnabled=true
  &cachePattern=SELECT.*FROM products.*
  &cacheTtl=300
```

#### Properties-Based Configuration

```java
Properties props = new Properties();
props.setProperty("user", "dbuser");
props.setProperty("password", "dbpass");

// Cache configuration
props.setProperty("ojp.cache.enabled", "true");
props.setProperty("ojp.cache.ttl.default", "300");
props.setProperty("ojp.cache.patterns", "SELECT.*FROM products.*,SELECT.*FROM users WHERE id=.*");
props.setProperty("ojp.cache.maxSize", "10000");

Connection conn = DriverManager.getConnection(url, props);
```

#### Implementation: Session-Level Cache Configuration

```java
// In ConnectionAction.java
public class ConnectionAction implements Action {
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager sessionManager) {
        Properties props = extractProperties(request);
        
        // Parse cache configuration
        CacheConfiguration config = CacheConfiguration.fromProperties(props);
        
        // Store in session
        Session session = sessionManager.createSession(request.getConnectionDetails());
        session.setCacheConfiguration(config);
        
        // Return session info with cache configuration
        return buildConnectionResult(session);
    }
}

public class Session {
    private final CacheConfiguration cacheConfig;
    
    public boolean isCacheable(String sql) {
        if (!cacheConfig.isEnabled()) {
            return false;
        }
        
        // Check against patterns
        for (Pattern pattern : cacheConfig.getPatterns()) {
            if (pattern.matcher(sql).matches()) {
                return true;
            }
        }
        
        return false;
    }
}
```

#### Advantages
- ✅ No SQL modification required
- ✅ Connection-level configuration
- ✅ Can be configured per application/environment
- ✅ Supports regex patterns for flexible matching

#### Disadvantages
- ⚠️ Less granular than per-query control
- ⚠️ Pattern matching can be expensive
- ⚠️ Harder to understand which queries are cached

#### Best For
- Applications without control over SQL
- Global caching policies
- Environment-specific cache configuration

---

### 3.3 Server-Side Configuration

**Approach:** Configure caching rules on the OJP server side.

#### Configuration File: ojp-cache-rules.yml

```yaml
cache:
  enabled: true
  defaultTtl: 300s
  maxSize: 10000
  
  rules:
    - name: product_catalog
      pattern: "SELECT .* FROM products WHERE .*"
      ttl: 600s
      invalidateOn:
        - products
        - product_categories
      
    - name: user_profile
      pattern: "SELECT .* FROM users WHERE id = ?"
      ttl: 300s
      invalidateOn:
        - users
      
    - name: static_reference_data
      pattern: "SELECT .* FROM (countries|currencies|timezones)"
      ttl: 3600s
      invalidateOn:
        - countries
        - currencies
        - timezones
```

#### Implementation: ServerConfiguration with Multi-Datasource Support

```java
// In ServerConfiguration.java
public class ServerConfiguration {
    
    private final CacheRuleEngine cacheRuleEngine;
    
    public void loadConfiguration() {
        // ... existing configuration loading ...
        
        // Load cache rules
        String cacheRulesFile = System.getProperty("ojp.cache.rules.file", 
                                                   "ojp-cache-rules.yml");
        if (new File(cacheRulesFile).exists()) {
            cacheRuleEngine = CacheRuleEngine.fromYaml(cacheRulesFile);
            log.info("Loaded cache rules from {}", cacheRulesFile);
        }
    }
}

public class CacheRuleEngine {
    private final Map<String, List<CacheRule>> datasourceRules;  // Per-datasource rules
    private final List<CacheRule> globalRules;  // Apply to all datasources
    
    public CacheRule matchRule(String sql, String datasourceName) {
        // 1. Try datasource-specific rules first
        List<CacheRule> dsRules = datasourceRules.get(datasourceName);
        if (dsRules != null) {
            for (CacheRule rule : dsRules) {
                if (rule.matches(sql)) {
                    return rule;
                }
            }
        }
        
        // 2. Fall back to global rules
        for (CacheRule rule : globalRules) {
            if (rule.matches(sql)) {
                return rule;
            }
        }
        
        return null;
    }
}
```

#### Hot-Reload and Dynamic Configuration Updates

**CRITICAL OPERATIONAL CONCERN**: Server-side configuration requiring restarts affects all applications.

**Problem**: 
- Single OJP server serves multiple applications
- One app needs cache config update → requires OJP restart
- Restart affects ALL applications using that OJP server
- Unacceptable in production environments

**Solution 1: File-Watch Based Hot-Reload**

```java
// In ServerConfiguration.java
public class ServerConfiguration {
    
    private volatile CacheRuleEngine cacheRuleEngine;
    private final ScheduledExecutorService configWatcher;
    private final Path configFilePath;
    private long lastModified;
    
    public void loadConfiguration() {
        // ... existing configuration loading ...
        
        // Load cache rules
        String cacheRulesFile = System.getProperty("ojp.cache.rules.file", 
                                                   "ojp-cache-rules.yml");
        configFilePath = Paths.get(cacheRulesFile);
        
        if (Files.exists(configFilePath)) {
            reloadCacheConfiguration();
            startConfigWatcher();
        }
    }
    
    private void startConfigWatcher() {
        configWatcher = Executors.newScheduledThreadPool(1);
        
        // Check for config file changes every 10 seconds
        configWatcher.scheduleAtFixedRate(() -> {
            try {
                long currentModified = Files.getLastModifiedTime(configFilePath).toMillis();
                
                if (currentModified > lastModified) {
                    log.info("Cache configuration file changed, reloading...");
                    reloadCacheConfiguration();
                    log.info("Cache configuration reloaded successfully");
                }
            } catch (Exception e) {
                log.error("Failed to check/reload configuration", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
    
    private void reloadCacheConfiguration() throws IOException {
        // Load new configuration
        CacheRuleEngine newEngine = CacheRuleEngine.fromYaml(configFilePath.toFile());
        
        // Atomic swap (volatile ensures visibility)
        this.cacheRuleEngine = newEngine;
        this.lastModified = Files.getLastModifiedTime(configFilePath).toMillis();
        
        log.info("Loaded {} cache rules from {}", 
                newEngine.getRuleCount(), configFilePath);
    }
    
    public CacheRuleEngine getCacheRuleEngine() {
        return cacheRuleEngine;  // Volatile read, always gets latest
    }
}
```

**Solution 2: HTTP/gRPC Admin API for Dynamic Updates**

```java
// Add admin endpoint to StatementServiceImpl
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    
    private final ServerConfiguration config;
    
    @Override
    public void updateCacheConfiguration(CacheConfigUpdateRequest request,
                                        StreamObserver<CacheConfigUpdateResponse> responseObserver) {
        try {
            // Authenticate admin request (API key, mTLS, etc.)
            if (!authenticateAdmin(request.getAdminToken())) {
                responseObserver.onError(new StatusException(Status.PERMISSION_DENIED));
                return;
            }
            
            // Parse new configuration
            CacheRuleEngine newEngine = CacheRuleEngine.fromYaml(request.getConfigYaml());
            
            // Validate configuration before applying
            if (!newEngine.validate()) {
                responseObserver.onError(new StatusException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid configuration")));
                return;
            }
            
            // Apply new configuration atomically
            config.updateCacheRuleEngine(newEngine);
            
            // Optional: Persist to disk
            if (request.getPersist()) {
                Files.write(config.getConfigFilePath(), 
                           request.getConfigYaml().getBytes(StandardCharsets.UTF_8));
            }
            
            responseObserver.onNext(CacheConfigUpdateResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Configuration updated successfully")
                .build());
            responseObserver.onCompleted();
            
            log.info("Cache configuration updated via admin API");
            
        } catch (Exception e) {
            log.error("Failed to update cache configuration", e);
            responseObserver.onError(e);
        }
    }
}

// Client utility for updating configuration
public class OjpAdminClient {
    
    public static void updateCacheConfig(String serverEndpoint, 
                                        String adminToken,
                                        String configYaml,
                                        boolean persist) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
            .forTarget(serverEndpoint)
            .build();
        
        try {
            StatementServiceBlockingStub stub = StatementServiceGrpc.newBlockingStub(channel);
            
            CacheConfigUpdateRequest request = CacheConfigUpdateRequest.newBuilder()
                .setAdminToken(adminToken)
                .setConfigYaml(configYaml)
                .setPersist(persist)
                .build();
            
            CacheConfigUpdateResponse response = stub.updateCacheConfiguration(request);
            
            if (response.getSuccess()) {
                System.out.println("Configuration updated: " + response.getMessage());
            } else {
                throw new Exception("Update failed: " + response.getMessage());
            }
            
        } finally {
            channel.shutdown();
        }
    }
}

// Usage from command line or CI/CD
// ojp-admin update-cache-config --server localhost:1059 
//                               --token $ADMIN_TOKEN 
//                               --config cache-rules.yml
//                               --persist
```

**Solution 3: Version-Based Configuration with Graceful Transition**

```java
public class CacheRuleEngine {
    private final int version;
    private final Map<String, List<CacheRule>> datasourceRules;
    private final List<CacheRule> globalRules;
    private final Instant loadedAt;
    
    public CacheRuleEngine(int version, /* ... */) {
        this.version = version;
        this.loadedAt = Instant.now();
        // ...
    }
}

public class ServerConfiguration {
    private volatile CacheRuleEngine currentEngine;
    private volatile CacheRuleEngine previousEngine;  // Keep one version back
    
    public void updateCacheRuleEngine(CacheRuleEngine newEngine) {
        if (newEngine.getVersion() <= currentEngine.getVersion()) {
            log.warn("Ignoring older configuration version: {} <= {}", 
                    newEngine.getVersion(), currentEngine.getVersion());
            return;
        }
        
        // Keep previous version for 5 minutes (allows in-flight requests to complete)
        this.previousEngine = this.currentEngine;
        this.currentEngine = newEngine;
        
        // Schedule cleanup of old version
        scheduler.schedule(() -> {
            log.info("Releasing old configuration version {}", 
                    previousEngine.getVersion());
            previousEngine = null;
        }, 5, TimeUnit.MINUTES);
        
        log.info("Updated cache configuration from version {} to {}", 
                previousEngine.getVersion(), currentEngine.getVersion());
    }
}
```

**Solution 4: Configuration as Code with Git Integration**

```yaml
# ojp-cache-rules.yml with metadata
version: 42
updatedAt: 2026-02-12T07:00:00Z
updatedBy: devops-team
git:
  commit: abc123def456
  branch: main
  repo: https://github.com/company/ojp-cache-config

cache:
  datasources:
    postgres_prod:
      rules:
        - name: product_catalog
          pattern: "SELECT .* FROM products WHERE .*"
          ttl: 600s
          # Audit: who, what, why
          comment: "Increased from 300s per JIRA-1234"
          lastUpdated: 2026-02-12T06:30:00Z
```

```java
// Git-backed configuration loader
public class GitConfigurationLoader {
    
    private final String gitRepoUrl;
    private final String configFilePath;
    private final Path localClonePath;
    
    public void startAutoSync() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Pull latest from git
                Git git = Git.open(localClonePath.toFile());
                git.pull().call();
                
                // Check if config file changed
                if (configFileChanged()) {
                    reloadConfiguration();
                }
                
            } catch (Exception e) {
                log.error("Failed to sync configuration from git", e);
            }
        }, 60, 60, TimeUnit.SECONDS);  // Check every minute
    }
}
```

**Configuration Update Workflow (Zero Downtime)**

```
1. Developer updates cache-rules.yml in git repo
2. Commits and pushes to main branch
3. CI/CD pipeline:
   a. Validates configuration (syntax, logic)
   b. Runs tests
   c. Calls OJP admin API to update config
   d. Verifies update successful
4. OJP servers:
   a. Receive new configuration via API or git-pull
   b. Validate configuration
   c. Atomically swap to new config (volatile)
   d. Keep old config for 5 minutes (in-flight requests)
5. Zero downtime for all applications
```

**Benefits of Hot-Reload Solutions:**

- ✅ **Zero downtime**: No restart required
- ✅ **Isolated impact**: Only affects cache behavior, not connections
- ✅ **Gradual rollout**: Update one server at a time
- ✅ **Quick rollback**: Keep previous version, easy to revert
- ✅ **Audit trail**: Git history shows who changed what and why
- ✅ **Validation**: Test config before applying
- ✅ **Multi-app safe**: One app's config change doesn't restart OJP

**Recommended Approach:**

**For Most Deployments:**
1. **File-watch hot-reload** (simple, automatic)
2. **Admin API** for manual updates (control, validation)

**For Large-Scale Production:**
1. **Git-backed configuration** (version control, audit)
2. **Admin API** for deployment automation
3. **Gradual rollout** across cluster

#### Addressing Multi-Datasource Reality

**CRITICAL CONSIDERATION**: A single OJP server can manage **dozens of different datasources**:

```
                         OJP Server
                             |
        +--------------------+--------------------+
        |                    |                    |
    DataSource 1        DataSource 2        DataSource 3
    (PostgreSQL Prod)   (MySQL Analytics)   (Oracle Legacy)
```

**Key Architectural Fact**: Datasource definitions are **client-side** - specified in the JDBC URL and connection properties:

```java
// Client 1: PostgreSQL production
jdbc:ojp[localhost:1059(postgres_prod)]_postgresql://prod-db:5432/sales

// Client 2: MySQL analytics
jdbc:ojp[localhost:1059(mysql_analytics)]_mysql://analytics-db:3306/reports

// Client 3: Oracle legacy
jdbc:ojp[localhost:1059(oracle_legacy)]_oracle:thin:@legacy-db:1521/LEGACY
```

**Solution: Datasource-Scoped Cache Configuration**

```yaml
# ojp-cache-rules.yml - Multi-datasource aware
cache:
  # Global rules (apply to ALL datasources if no specific rule)
  globalRules:
    - name: default_select_cache
      pattern: "SELECT .* FROM .*"
      ttl: 300s
      enabled: false  # Opt-in by default
  
  # Datasource-specific rules (override global)
  datasources:
    postgres_prod:
      rules:
        - name: product_catalog
          pattern: "SELECT .* FROM products WHERE .*"
          ttl: 600s
          invalidateOn: [products, product_categories]
        
        - name: user_profile
          pattern: "SELECT .* FROM users WHERE id = ?"
          ttl: 300s
          invalidateOn: [users]
    
    mysql_analytics:
      rules:
        - name: analytics_reports
          pattern: "SELECT .* FROM report_.*"
          ttl: 1800s  # 30 minutes for analytics
          invalidateOn: [report_tables]
    
    oracle_legacy:
      rules:
        - name: legacy_cache
          pattern: "SELECT .* FROM LEGACY_.*"
          ttl: 3600s  # 1 hour for legacy data
          invalidateOn: [LEGACY_TABLES]
```

**Alternative: Pattern-Based Datasource Matching**

If explicit datasource names are too rigid, use pattern matching:

```yaml
cache:
  datasourcePatterns:
    - pattern: "postgres_.*"  # Matches: postgres_prod, postgres_dev, etc.
      rules:
        - name: postgres_standard
          pattern: "SELECT .* FROM .*"
          ttl: 300s
    
    - pattern: "mysql_.*"
      rules:
        - name: mysql_standard
          pattern: "SELECT .* FROM .*"
          ttl: 600s
    
    - pattern: ".*_analytics"  # Matches: mysql_analytics, postgres_analytics
      rules:
        - name: analytics_long_cache
          pattern: "SELECT .* FROM .*"
          ttl: 1800s  # Analytics can cache longer
```

**Implementation in ExecuteQueryAction**

```java
public class ExecuteQueryAction implements Action {
    
    private final CacheRuleEngine cacheRuleEngine;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager sessionManager) {
        String sql = request.getSql();
        Session session = sessionManager.getSession(request.getSessionId());
        
        // Get datasource name from session (comes from client connection)
        String datasourceName = session.getDataSourceName();
        
        // Match rule based on BOTH SQL and datasource
        CacheRule rule = cacheRuleEngine.matchRule(sql, datasourceName);
        
        if (rule != null && rule.isCacheable()) {
            // Query is cacheable for this datasource
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,  // Include datasource in cache key
                sql,
                extractParameters(request)
            );
            
            // Try cache
            CachedResult cached = cache.get(key);
            if (cached != null && !cached.isExpired(rule.getTtl())) {
                return buildOpResultFromCache(cached);
            }
            
            // Execute and cache
            OpResult result = executeQueryOnDatabase(request, sessionManager);
            if (result.getSuccess()) {
                cache.put(key, extractResultForCache(result), rule);
            }
            
            return result;
        }
        
        // Not cacheable or no rule matched
        return executeQueryOnDatabase(request, sessionManager);
    }
}
```

**Cache Key Structure (Datasource-Aware)**

```java
public class QueryCacheKey {
    private final String datasourceName;  // CRITICAL: Isolate by datasource
    private final String sql;
    private final List<Object> parameters;
    
    @Override
    public boolean equals(Object o) {
        QueryCacheKey that = (QueryCacheKey) o;
        return Objects.equals(datasourceName, that.datasourceName)
            && Objects.equals(sql, that.sql)
            && Objects.equals(parameters, that.parameters);
    }
    
    // Different datasources with same SQL = different cache entries
}
```

**Why Datasource-Aware Caching Matters:**

1. **Isolation**: PostgreSQL production and MySQL analytics should have separate caches
2. **Different Policies**: Production might cache aggressively, analytics conservatively
3. **Security**: Prevent cache pollution across datasources
4. **Performance**: Different databases have different performance characteristics
5. **Multi-Tenancy**: Each tenant can have their own datasource with custom cache rules

**Real-World Example:**

```
OJP Server managing:
- postgres_prod (high-traffic e-commerce)
  → Cache product catalog aggressively (600s TTL)
  → Cache user data conservatively (300s TTL)

- mysql_analytics (reporting database)
  → Cache reports for 30 minutes (data changes infrequently)
  → Cache aggregations for 1 hour

- oracle_legacy (old ERP system)
  → Cache reference data for hours (rarely changes)
  → Don't cache transactional data
```

#### Advantages
- ✅ Centralized cache policy management
- ✅ No application or SQL changes needed
- ✅ **Hot-reload support**: Update configuration without restart
- ✅ **Zero downtime**: File-watch or admin API updates
- ✅ **Multi-app safe**: Config changes don't affect other applications
- ✅ Supports complex matching rules
- ✅ Declarative and version-controlled
- ✅ **Datasource-aware**: Different rules for different databases
- ✅ **Scales naturally**: Add new datasources without code changes
- ✅ **Isolation**: Each datasource has independent cache namespace
- ✅ **Gradual rollout**: Update servers one at a time
- ✅ **Quick rollback**: Keep previous config version for safety

#### Disadvantages
- ⚠️ Less visible to developers (unless using git-backed config)
- ⚠️ Can be out of sync with application expectations
- ⚠️ Configuration grows with number of datasources
- ⚠️ Requires monitoring to ensure hot-reload works correctly

#### Best For
- Production environments with DBAs/DevOps teams
- Multi-tenant scenarios with different cache policies
- Organizations with strict change control processes
- **Environments with multiple datasources per OJP server**
- **Heterogeneous database landscapes**
- **Multi-application deployments** (hot-reload prevents restart impact)

---

### 3.4 Client-Side Configuration (Connection-Time Distribution)

**Approach:** Define cacheable queries in client configuration files (`ojp.properties` or `ojp.yaml`) which are sent to all OJP servers in the cluster during connection establishment.

#### Configuration File: ojp-cache-client.yaml

```yaml
# Client-side cache configuration
ojp:
  cache:
    enabled: true
    queries:
      - sql: "SELECT * FROM products WHERE category = ?"
        ttl: 600s
        refreshInterval: 300s
        invalidateOn: [products, product_categories]
        
      - sql: "SELECT id, name, email FROM users WHERE id = ?"
        ttl: 300s
        refreshInterval: 150s
        invalidateOn: [users]
        
      - sql: "SELECT * FROM countries"
        ttl: 3600s
        refreshInterval: 1800s
        invalidateOn: [countries]
        preload: true  # Pre-populate cache on startup
```

#### Implementation: Connection-Time Propagation

```java
// In ojp-jdbc-driver: Connection establishment
public class OjpDriver implements Driver {
    
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        // Load client-side cache configuration
        CacheConfiguration clientConfig = loadCacheConfiguration();
        
        // Create connection request with cache config
        ConnectionRequest request = ConnectionRequest.newBuilder()
            .setConnectionDetails(details)
            .setCacheConfiguration(serializeCacheConfig(clientConfig))
            .build();
        
        // Send to OJP server (or all servers in multinode)
        SessionInfo session = statementService.connect(request);
        
        log.info("Connected with cache configuration: {} rules", 
                 clientConfig.getQueries().size());
        
        return new OjpConnection(session, statementService);
    }
    
    private CacheConfiguration loadCacheConfiguration() {
        // Try multiple sources in order:
        // 1. System property: -Dojp.cache.config=path/to/config.yaml
        // 2. Classpath: /ojp-cache-client.yaml
        // 3. Environment variable: OJP_CACHE_CONFIG
        // 4. Default location: ~/.ojp/cache-config.yaml
        
        String configPath = System.getProperty("ojp.cache.config");
        if (configPath != null) {
            return CacheConfiguration.fromFile(configPath);
        }
        
        InputStream stream = getClass().getResourceAsStream("/ojp-cache-client.yaml");
        if (stream != null) {
            return CacheConfiguration.fromYaml(stream);
        }
        
        return CacheConfiguration.empty();
    }
}

// In ojp-server: Connection handler
public class ConnectionAction implements Action {
    
    private final SessionManager sessionManager;
    private final QueryResultCache cache;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager manager) {
        // Extract cache configuration from connection request
        CacheConfiguration clientConfig = 
            deserializeCacheConfig(request.getCacheConfiguration());
        
        // Create session with client-provided cache rules
        Session session = sessionManager.createSession(request.getConnectionDetails());
        session.setCacheConfiguration(clientConfig);
        
        // Register cache rules for this session
        if (clientConfig.isEnabled()) {
            for (CacheQuery query : clientConfig.getQueries()) {
                cache.registerRule(session.getId(), query);
                
                // Preload cache if requested
                if (query.isPreload()) {
                    cache.preloadQuery(query);
                }
            }
            
            log.info("Registered {} cache rules for session {}", 
                    clientConfig.getQueries().size(), session.getId());
        }
        
        return buildConnectionResult(session);
    }
}

// Cache rule matching in ExecuteQueryAction
public class ExecuteQueryAction implements Action {
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager manager) {
        Session session = manager.getSession(request.getSessionId());
        String sql = request.getSql();
        
        // Check if this query matches session's cache rules
        CacheQuery matchedRule = session.getCacheConfiguration().matchQuery(sql);
        
        if (matchedRule != null) {
            QueryCacheKey key = new QueryCacheKey(
                session.getId(), 
                sql, 
                extractParameters(request)
            );
            
            // Try cache first
            CachedResult cached = cache.get(key);
            if (cached != null && !cached.isExpired(matchedRule.getTtl())) {
                return buildOpResultFromCache(cached);
            }
            
            // Execute and cache
            OpResult result = executeQueryOnDatabase(request, manager);
            if (result.getSuccess()) {
                cache.put(key, extractResultForCache(result), matchedRule);
            }
            
            return result;
        }
        
        // No cache rule - execute normally
        return executeQueryOnDatabase(request, manager);
    }
}
```

#### Multinode Distribution

In a multinode setup, the JDBC driver can send the cache configuration to ALL servers:

```java
public class MultinodeStatementService implements StatementService {
    
    private final MultinodeConnectionManager connectionManager;
    
    @Override
    public SessionInfo connect(ConnectionDetails details, CacheConfiguration cacheConfig) {
        // Get all server endpoints
        List<ServerEndpoint> allServers = connectionManager.getAllServers();
        
        // Send cache configuration to ALL servers
        for (ServerEndpoint server : allServers) {
            try {
                StatementServiceBlockingStub stub = 
                    connectionManager.getStub(server);
                
                ConnectionRequest request = ConnectionRequest.newBuilder()
                    .setConnectionDetails(details)
                    .setCacheConfiguration(serializeCacheConfig(cacheConfig))
                    .setDistributionMode(DistributionMode.CLUSTER_WIDE)
                    .build();
                
                stub.distributeCacheConfiguration(request);
                
                log.debug("Distributed cache config to server: {}", server);
                
            } catch (Exception e) {
                log.warn("Failed to distribute cache config to {}: {}", 
                        server, e.getMessage());
            }
        }
        
        // Establish connection on primary server
        ServerEndpoint primary = connectionManager.selectHealthyServer();
        return connectionManager.getStub(primary).connect(details);
    }
}
```

#### Advantages
- ✅ **Application control**: Developers define cache rules close to the code
- ✅ **Per-application policies**: Different apps can have different cache rules
- ✅ **Cluster-wide consistency**: All servers receive the same configuration
- ✅ **Version controlled**: Cache config travels with application code
- ✅ **Environment-specific**: Can use different configs for dev/staging/prod
- ✅ **Dynamic distribution**: No server restart needed
- ✅ **Explicit and visible**: Clear what's being cached

#### Disadvantages
- ⚠️ **Configuration duplication**: Each application must define cache rules
- ⚠️ **Connection overhead**: Configuration sent on every connection
- ⚠️ **Memory per session**: Each session stores its own cache rules
- ⚠️ **Potential inconsistency**: Different apps may define conflicting rules
- ⚠️ **Network overhead**: Sending config to all servers adds latency
- ⚠️ **No centralized governance**: Harder to enforce organization-wide policies
- ⚠️ **Scaling concerns**: Large number of connections × large config = significant overhead

#### Best For
- Applications with unique caching requirements
- Development/testing environments
- Microservices with isolated caching needs
- Scenarios where cache rules are tightly coupled to application logic
- Teams that want application-level control

#### Performance Considerations

**Connection-time overhead:**
```
Small config (10 rules, ~2KB):  +5-10ms connection time
Medium config (50 rules, ~10KB): +20-30ms connection time
Large config (200 rules, ~40KB): +50-100ms connection time
```

**Mitigation strategies:**
1. **Compression**: GZIP compress configuration before sending
2. **Caching**: Server caches configs by hash, send only hash on reconnect
3. **Lazy distribution**: Send to servers as connections are made to them
4. **Smart delta**: Send only changed rules on reconnection

```java
// Optimized with compression and caching
public class CacheConfigurationOptimizer {
    
    private static final ConcurrentHashMap<String, CacheConfiguration> configCache 
        = new ConcurrentHashMap<>();
    
    public static byte[] serializeOptimized(CacheConfiguration config) {
        // Calculate hash
        String hash = calculateHash(config);
        
        // Compress with GZIP
        byte[] compressed = compress(config.toBytes());
        
        // Return: [hash(32 bytes)][compressed_data]
        return concat(hash.getBytes(), compressed);
    }
    
    public static CacheConfiguration deserializeOptimized(byte[] data, 
                                                         String sessionId) {
        String hash = new String(data, 0, 32);
        
        // Check if we've seen this config before
        CacheConfiguration cached = configCache.get(hash);
        if (cached != null) {
            log.debug("Using cached configuration for session {}", sessionId);
            return cached;
        }
        
        // Decompress and parse
        byte[] compressed = Arrays.copyOfRange(data, 32, data.length);
        byte[] decompressed = decompress(compressed);
        CacheConfiguration config = CacheConfiguration.fromBytes(decompressed);
        
        // Cache for future connections
        configCache.put(hash, config);
        
        return config;
    }
}
```

---

### 3.5 Recommendation: Hybrid Multi-Level Approach

**Best Practice:** Support all four methods with a clear precedence order:

```
1. SQL Comment Hints (highest priority - per-query override)
   ↓
2. Client-Side Configuration (per-application cache policy)
   ↓
3. JDBC Connection Properties (connection-level defaults)
   ↓
4. Server-Side Configuration (lowest priority - organization defaults)
```

This provides maximum flexibility:
- **SQL hints**: Developers can override for specific critical queries
- **Client config**: Applications define their standard caching patterns
- **Connection properties**: Environment-specific tuning (dev vs prod)
- **Server config**: Ops teams set organization-wide defaults and policies

**Practical Usage Patterns:**

```yaml
# Server-side (ojp-cache-rules.yml) - Organization defaults
cache:
  defaultTtl: 300s
  rules:
    - pattern: "SELECT .* FROM audit_.*"
      cacheable: false  # Never cache audit queries

# Client-side (ojp-cache-client.yaml) - Application-specific
ojp:
  cache:
    queries:
      - sql: "SELECT * FROM products WHERE category = ?"
        ttl: 600s

# Connection properties - Environment override
jdbc:ojp[localhost:1059]_postgresql://db:5432/mydb
  ?cacheEnabled=true
  &cacheDefaultTtl=600  # Production uses longer TTL

# SQL hints - Critical query override
/* @cache ttl=60s */ SELECT * FROM inventory WHERE product_id = ?
```

---

## 4. Cache Invalidation Strategies

### 4.1 Time-Based Invalidation (TTL)

**Approach:** Simplest strategy - cache entries expire after a fixed duration.

```java
public class CachedResult {
    private final long timestamp;
    private final Duration ttl;
    
    public boolean isExpired() {
        return Duration.between(
            Instant.ofEpochMilli(timestamp),
            Instant.now()
        ).compareTo(ttl) > 0;
    }
}
```

**Advantages:**
- ✅ Simple and predictable
- ✅ No coordination needed in distributed setups
- ✅ Works for all query types

**Disadvantages:**
- ⚠️ May serve stale data
- ⚠️ Requires tuning TTL values
- ⚠️ Trade-off between freshness and cache hit rate

---

### 4.2 Write-Through Invalidation

**Approach:** Invalidate cache entries when related data is modified.

#### Implementation: Intercept DML Statements

```java
// In ExecuteUpdateAction.java
public class ExecuteUpdateAction implements Action {
    
    private final QueryResultCache cache;
    private final TableDependencyAnalyzer analyzer;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager sessionManager) {
        String sql = request.getSql();
        
        // Execute the UPDATE/INSERT/DELETE
        OpResult result = executeUpdateOnDatabase(request, sessionManager);
        
        if (result.getSuccess()) {
            // Analyze which tables were modified
            Set<String> affectedTables = analyzer.extractTables(sql);
            
            // Invalidate cache entries that depend on these tables
            cache.invalidateByTables(affectedTables);
            
            log.info("Invalidated cache entries for tables: {}", affectedTables);
        }
        
        return result;
    }
}
```

#### Table Dependency Tracking

```java
public class QueryResultCache {
    
    // Map from table name to cache keys that query that table
    private final ConcurrentHashMap<String, Set<QueryCacheKey>> tableDependencies;
    
    public void put(QueryCacheKey key, CachedResult result, Set<String> tables) {
        cache.put(key, result);
        
        // Track table dependencies
        for (String table : tables) {
            tableDependencies
                .computeIfAbsent(table, k -> ConcurrentHashMap.newKeySet())
                .add(key);
        }
    }
    
    public void invalidateByTables(Set<String> tables) {
        for (String table : tables) {
            Set<QueryCacheKey> keys = tableDependencies.get(table);
            if (keys != null) {
                for (QueryCacheKey key : keys) {
                    cache.remove(key);
                }
                tableDependencies.remove(table);
            }
        }
    }
}
```

#### Using SqlEnhancerEngine for Table Extraction

OJP's SqlEnhancerEngine with Calcite can extract table references:

```java
public class TableDependencyAnalyzer {
    
    private final SqlEnhancerEngine enhancer;
    
    public Set<String> extractTables(String sql) {
        try {
            // Parse SQL
            SqlNode parsed = enhancer.parse(sql);
            
            // Extract table references using Calcite visitor
            TableNameVisitor visitor = new TableNameVisitor();
            parsed.accept(visitor);
            
            return visitor.getTables();
            
        } catch (Exception e) {
            // Fallback: regex-based extraction
            return extractTablesUsingRegex(sql);
        }
    }
    
    // Fallback regex-based extraction
    private Set<String> extractTablesUsingRegex(String sql) {
        Set<String> tables = new HashSet<>();
        
        // Match: FROM/JOIN table_name or UPDATE table_name
        Pattern pattern = Pattern.compile(
            "(?:FROM|JOIN|UPDATE|INSERT INTO)\\s+([\\w.]+)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase());
        }
        
        return tables;
    }
}
```

**Advantages:**
- ✅ Ensures cache consistency
- ✅ Automatic invalidation on writes
- ✅ Works well for read-heavy workloads

**Disadvantages:**
- ⚠️ Requires accurate table dependency tracking
- ⚠️ Can invalidate more than necessary (over-invalidation)
- ⚠️ Doesn't handle external database modifications

---

### 4.3 Hybrid TTL + Write-Through

**Recommended Approach:** Combine both strategies for optimal results.

```java
public class CachedResult {
    private final long timestamp;
    private final Duration ttl;
    private final Set<String> tableDependencies;
    
    public boolean isExpired() {
        // Check TTL first (fast)
        if (Duration.between(
                Instant.ofEpochMilli(timestamp),
                Instant.now()
            ).compareTo(ttl) > 0) {
            return true;
        }
        
        // Still valid by TTL
        return false;
    }
}

// Cache invalidation on writes
cache.invalidateByTables(affectedTables);  // Immediate

// Plus background TTL expiration
scheduler.scheduleAtFixedRate(() -> {
    cache.evictExpired();
}, 60, 60, TimeUnit.SECONDS);
```

**This provides:**
- Immediate invalidation on writes through OJP
- Safety net for external modifications via TTL
- Best of both worlds

---

## 5. Distributed Cache Replication Using JDBC Drivers

### 5.1 Challenge: Cache Consistency Across OJP Servers

In a multinode OJP deployment, each server maintains its own cache. This creates consistency challenges:

```
Client 1 → OJP Server A (has cached results for query Q)
Client 2 → OJP Server B (no cache for query Q)
Client 3 → OJP Server C (no cache for query Q)

Client 4 writes to database through Server A
  → Server A invalidates cache
  → Servers B & C still have stale cache ❌
```

**Problem:** Cache invalidation doesn't propagate across servers.

---

### 5.2 Approach 1: JDBC-Based Notification Table

**Concept:** Use a database table to coordinate cache invalidation across OJP servers.

#### Schema

```sql
CREATE TABLE ojp_cache_notifications (
    notification_id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(255) NOT NULL,       -- Which server sent notification
    operation VARCHAR(50) NOT NULL,         -- 'INVALIDATE', 'CLEAR_ALL'
    affected_tables TEXT[],                 -- Tables modified
    cache_keys TEXT[],                      -- Optional: specific keys
    timestamp TIMESTAMP DEFAULT NOW(),
    processed_by TEXT[]                     -- List of servers that processed this
);

CREATE INDEX idx_cache_notif_timestamp ON ojp_cache_notifications(timestamp);
CREATE INDEX idx_cache_notif_processed ON ojp_cache_notifications(processed_by);
```

#### Implementation

```java
public class JdbcCacheNotificationService {
    
    private final DataSource notificationDataSource;
    private final String serverId;
    private final QueryResultCache localCache;
    private final ScheduledExecutorService scheduler;
    
    public JdbcCacheNotificationService(
            DataSource dataSource,
            String serverId,
            QueryResultCache cache) {
        this.notificationDataSource = dataSource;
        this.serverId = serverId;
        this.localCache = cache;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    public void start() {
        // Poll for notifications every 1 second
        scheduler.scheduleAtFixedRate(() -> {
            processNotifications();
        }, 0, 1, TimeUnit.SECONDS);
        
        log.info("Started JDBC cache notification service for server: {}", serverId);
    }
    
    /**
     * Send notification when cache should be invalidated
     */
    public void notifyInvalidation(Set<String> affectedTables) {
        try (Connection conn = notificationDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO ojp_cache_notifications " +
                 "(server_id, operation, affected_tables, timestamp) " +
                 "VALUES (?, ?, ?, NOW())")) {
            
            stmt.setString(1, serverId);
            stmt.setString(2, "INVALIDATE");
            
            // Convert Set to JDBC array
            Array tablesArray = conn.createArrayOf("VARCHAR", 
                                                    affectedTables.toArray());
            stmt.setArray(3, tablesArray);
            
            stmt.executeUpdate();
            
            log.debug("Sent cache invalidation notification for tables: {}", 
                     affectedTables);
            
        } catch (SQLException e) {
            log.error("Failed to send cache invalidation notification", e);
        }
    }
    
    /**
     * Process notifications from other servers
     */
    private void processNotifications() {
        try (Connection conn = notificationDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT notification_id, server_id, operation, affected_tables " +
                 "FROM ojp_cache_notifications " +
                 "WHERE timestamp > NOW() - INTERVAL '5 minutes' " +
                 "  AND (processed_by IS NULL OR NOT (? = ANY(processed_by))) " +
                 "ORDER BY notification_id")) {
            
            stmt.setString(1, serverId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long notificationId = rs.getLong("notification_id");
                    String sourceServer = rs.getString("server_id");
                    String operation = rs.getString("operation");
                    Array tablesArray = rs.getArray("affected_tables");
                    
                    // Skip our own notifications
                    if (serverId.equals(sourceServer)) {
                        markAsProcessed(notificationId);
                        continue;
                    }
                    
                    // Process notification
                    if ("INVALIDATE".equals(operation)) {
                        String[] tables = (String[]) tablesArray.getArray();
                        Set<String> tableSet = new HashSet<>(Arrays.asList(tables));
                        
                        localCache.invalidateByTables(tableSet);
                        
                        log.info("Processed invalidation from server {} for tables: {}", 
                                sourceServer, tableSet);
                    }
                    
                    // Mark as processed
                    markAsProcessed(notificationId);
                }
            }
            
        } catch (SQLException e) {
            log.error("Failed to process cache notifications", e);
        }
    }
    
    private void markAsProcessed(long notificationId) {
        try (Connection conn = notificationDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "UPDATE ojp_cache_notifications " +
                 "SET processed_by = array_append(processed_by, ?) " +
                 "WHERE notification_id = ?")) {
            
            stmt.setString(1, serverId);
            stmt.setLong(2, notificationId);
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            log.error("Failed to mark notification as processed", e);
        }
    }
    
    /**
     * Cleanup old notifications (called periodically)
     */
    public void cleanupOldNotifications() {
        try (Connection conn = notificationDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            int deleted = stmt.executeUpdate(
                "DELETE FROM ojp_cache_notifications " +
                "WHERE timestamp < NOW() - INTERVAL '1 hour'"
            );
            
            log.debug("Cleaned up {} old cache notifications", deleted);
            
        } catch (SQLException e) {
            log.error("Failed to cleanup old notifications", e);
        }
    }
}
```

#### Integration with ExecuteUpdateAction

```java
public class ExecuteUpdateAction implements Action {
    
    private final JdbcCacheNotificationService notificationService;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager sessionManager) {
        String sql = request.getSql();
        
        // Execute the update
        OpResult result = executeUpdateOnDatabase(request, sessionManager);
        
        if (result.getSuccess()) {
            // Extract affected tables
            Set<String> affectedTables = extractTables(sql);
            
            // Invalidate local cache
            cache.invalidateByTables(affectedTables);
            
            // Notify other OJP servers
            notificationService.notifyInvalidation(affectedTables);
        }
        
        return result;
    }
}
```

#### Configuration

```yaml
# ojp-server.yml
cache:
  replication:
    enabled: true
    mode: jdbc
    jdbc:
      url: jdbc:postgresql://cache-db:5432/ojp_cache
      username: ojp_cache_user
      password: ${OJP_CACHE_PASSWORD}
      pollIntervalSeconds: 1
      cleanupIntervalMinutes: 60
```

#### Advantages
- ✅ No additional infrastructure required (uses existing JDBC)
- ✅ Reliable and persistent
- ✅ Simple implementation
- ✅ Works across network partitions (eventually consistent)
- ✅ Audit trail of cache operations

#### Disadvantages
- ⚠️ Polling introduces latency (1-2 seconds typical)
- ⚠️ Additional database load (though minimal)
- ⚠️ Requires dedicated database or schema
- ⚠️ Not real-time (eventual consistency)

#### Best For
- Environments that prefer database-based coordination
- Scenarios where 1-2 second invalidation latency is acceptable
- Teams familiar with JDBC and SQL
- Deployments that want to avoid additional services

---

### 5.3 Approach 2: JDBC with LISTEN/NOTIFY (PostgreSQL)

**Concept:** Use PostgreSQL's LISTEN/NOTIFY for real-time cache invalidation.

#### Implementation

```java
public class PostgresListenNotifyCache {
    
    private final DataSource dataSource;
    private final String serverId;
    private final QueryResultCache localCache;
    private volatile Connection listenerConnection;
    private final ExecutorService listenerExecutor;
    
    public void start() throws SQLException {
        // Create dedicated connection for LISTEN
        listenerConnection = dataSource.getConnection();
        
        // Create notification function and trigger (one-time setup)
        setupNotificationTrigger();
        
        // Start listening
        try (Statement stmt = listenerConnection.createStatement()) {
            stmt.execute("LISTEN ojp_cache_invalidation");
        }
        
        // Start listener thread
        listenerExecutor.submit(this::listenForNotifications);
        
        log.info("Started PostgreSQL LISTEN/NOTIFY cache synchronization");
    }
    
    private void setupNotificationTrigger() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Create notification function
            stmt.execute(
                "CREATE OR REPLACE FUNCTION notify_cache_invalidation() " +
                "RETURNS TRIGGER AS $$ " +
                "BEGIN " +
                "  PERFORM pg_notify('ojp_cache_invalidation', " +
                "    json_build_object(" +
                "      'table', TG_TABLE_NAME, " +
                "      'operation', TG_OP" +
                "    )::text" +
                "  ); " +
                "  RETURN NEW; " +
                "END; " +
                "$$ LANGUAGE plpgsql;"
            );
            
            // Create triggers on monitored tables
            for (String table : getMonitoredTables()) {
                stmt.execute(
                    "DROP TRIGGER IF EXISTS cache_invalidation_trigger ON " + table + ";" +
                    "CREATE TRIGGER cache_invalidation_trigger " +
                    "AFTER INSERT OR UPDATE OR DELETE ON " + table + " " +
                    "FOR EACH STATEMENT " +
                    "EXECUTE FUNCTION notify_cache_invalidation();"
                );
            }
            
            log.info("Setup cache invalidation triggers for tables: {}", 
                    getMonitoredTables());
        }
    }
    
    private void listenForNotifications() {
        PGConnection pgConn = listenerConnection.unwrap(PGConnection.class);
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Get notifications (blocks until available)
                PGNotification[] notifications = pgConn.getNotifications(1000);
                
                if (notifications != null) {
                    for (PGNotification notification : notifications) {
                        processNotification(notification);
                    }
                }
                
            } catch (SQLException e) {
                log.error("Error receiving notifications", e);
                try {
                    Thread.sleep(5000);  // Back off before retry
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }
    
    private void processNotification(PGNotification notification) {
        String channel = notification.getName();
        String payload = notification.getParameter();
        
        if ("ojp_cache_invalidation".equals(channel)) {
            try {
                // Parse JSON payload
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                String table = json.get("table").getAsString();
                String operation = json.get("operation").getAsString();
                
                // Invalidate cache
                localCache.invalidateByTables(Collections.singleton(table));
                
                log.debug("Invalidated cache for table {} due to {}", table, operation);
                
            } catch (Exception e) {
                log.error("Failed to process notification: {}", payload, e);
            }
        }
    }
}
```

#### Advantages
- ✅ Real-time invalidation (sub-second latency)
- ✅ No polling overhead
- ✅ Minimal database impact
- ✅ Simple and elegant for PostgreSQL

#### Disadvantages
- ⚠️ PostgreSQL-specific (not portable)
- ⚠️ Requires database-level trigger setup
- ⚠️ Doesn't work for external database modifications through other apps
- ⚠️ Requires maintaining a persistent connection

#### Best For
- PostgreSQL-only deployments
- Real-time cache consistency requirements
- Low-latency applications

---

### 5.4 Approach 4: JDBC Driver as Active Relay (Push-Based)

**Concept:** Use the OJP JDBC driver as an active relay to stream cached data and broadcast invalidation signals directly to all OJP servers, eliminating polling overhead.

#### Architecture

```mermaid
sequenceDiagram
    participant App as Application
    participant Driver as OJP JDBC Driver
    participant ServerA as OJP Server A
    participant ServerB as OJP Server B
    participant ServerC as OJP Server C
    participant DB as Database

    App->>Driver: executeQuery(SELECT ...)
    Driver->>ServerA: gRPC: executeQuery()
    
    alt Cache MISS
        ServerA->>DB: Execute query
        DB-->>ServerA: Result set
        ServerA-->>Driver: Result + Cache Signal
        
        Note over Driver: Spawn virtual thread<br/>to propagate cache
        
        par Parallel Distribution
            Driver->>ServerB: gRPC: distributeCachedResult()
            Driver->>ServerC: gRPC: distributeCachedResult()
        end
        
        ServerB->>ServerB: Store in local cache
        ServerC->>ServerC: Store in local cache
    end
    
    Driver-->>App: Result set
    
    Note over App: Later: UPDATE occurs
    
    App->>Driver: executeUpdate(UPDATE ...)
    Driver->>ServerA: gRPC: executeUpdate()
    ServerA->>DB: Execute update
    DB-->>ServerA: Success
    ServerA->>ServerA: Invalidate local cache
    ServerA-->>Driver: Success + Invalidation Signal
    
    Note over Driver: Broadcast invalidation<br/>(virtual thread)
    
    par Broadcast Invalidation
        Driver->>ServerB: gRPC: invalidateCache()
        Driver->>ServerC: gRPC: invalidateCache()
    end
    
    Driver-->>App: Success
```

#### Implementation

##### 1. Enhanced gRPC Protocol

```protobuf
// Add to statement.proto
service StatementService {
    // Existing methods...
    rpc ExecuteQuery(StatementRequest) returns (OpResult);
    rpc ExecuteUpdate(StatementRequest) returns (OpResult);
    
    // New cache coordination methods
    rpc DistributeCachedResult(CacheDistributionRequest) returns (CacheDistributionResponse);
    rpc InvalidateCache(CacheInvalidationRequest) returns (CacheInvalidationResponse);
}

message OpResult {
    bool success = 1;
    // ... existing fields ...
    
    // New cache coordination fields
    CacheSignal cacheSignal = 20;
}

message CacheSignal {
    enum SignalType {
        NONE = 0;
        CACHE_AND_DISTRIBUTE = 1;  // Server wants driver to distribute
        INVALIDATE_AND_BROADCAST = 2;  // Server wants driver to broadcast invalidation
    }
    
    SignalType type = 1;
    string cacheKey = 2;
    bytes cachedData = 3;  // Serialized result set
    repeated string affectedTables = 4;
    int64 ttl = 5;
}

message CacheDistributionRequest {
    string cacheKey = 1;
    bytes cachedData = 2;
    int64 ttl = 3;
    string sourceServerId = 4;  // Exclude from distribution
}

message CacheInvalidationRequest {
    repeated string affectedTables = 1;
    repeated string cacheKeys = 2;  // Optional: specific keys
    string sourceServerId = 3;  // Exclude from broadcast
}
```

##### 2. JDBC Driver Implementation

```java
// In ojp-jdbc-driver
public class OjpStatement implements Statement {
    
    private final StatementService statementService;
    private final ExecutorService cacheDistributionExecutor;
    
    public OjpStatement(StatementService service) {
        this.statementService = service;
        
        // Use virtual threads if Java 25+, otherwise use thread pool
        if (Runtime.version().feature() >= 21) {
            this.cacheDistributionExecutor = 
                Executors.newVirtualThreadPerTaskExecutor();
        } else {
            this.cacheDistributionExecutor = 
                Executors.newFixedThreadPool(10);
        }
    }
    
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        StatementRequest request = StatementRequest.newBuilder()
            .setSessionId(sessionId)
            .setSql(sql)
            .build();
        
        OpResult result = statementService.executeQuery(request);
        
        // Check for cache distribution signal
        if (result.hasCacheSignal() && 
            result.getCacheSignal().getType() == SignalType.CACHE_AND_DISTRIBUTE) {
            
            // Spawn virtual thread to distribute cache asynchronously
            cacheDistributionExecutor.submit(() -> {
                distributeCacheToCluster(result.getCacheSignal());
            });
        }
        
        return new RemoteProxyResultSet(result);
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        StatementRequest request = StatementRequest.newBuilder()
            .setSessionId(sessionId)
            .setSql(sql)
            .build();
        
        OpResult result = statementService.executeUpdate(request);
        
        // Check for invalidation broadcast signal
        if (result.hasCacheSignal() && 
            result.getCacheSignal().getType() == SignalType.INVALIDATE_AND_BROADCAST) {
            
            // Spawn virtual thread to broadcast invalidation
            cacheDistributionExecutor.submit(() -> {
                broadcastInvalidationToCluster(result.getCacheSignal());
            });
        }
        
        return result.getUpdateCount();
    }
    
    private void distributeCacheToCluster(CacheSignal signal) {
        try {
            if (!(statementService instanceof MultinodeStatementService)) {
                return;  // Single server, no distribution needed
            }
            
            MultinodeStatementService multinode = 
                (MultinodeStatementService) statementService;
            
            // Get all servers except the source
            List<ServerEndpoint> targetServers = 
                multinode.getAllServersExcept(signal.getSourceServerId());
            
            CacheDistributionRequest request = CacheDistributionRequest.newBuilder()
                .setCacheKey(signal.getCacheKey())
                .setCachedData(signal.getCachedData())
                .setTtl(signal.getTtl())
                .setSourceServerId(getCurrentServerId())
                .build();
            
            // Parallel distribution using virtual threads
            List<CompletableFuture<Void>> futures = targetServers.stream()
                .map(server -> CompletableFuture.runAsync(() -> {
                    try {
                        StatementServiceBlockingStub stub = 
                            multinode.getStub(server);
                        stub.distributeCachedResult(request);
                        
                        log.debug("Distributed cache to server: {}", server);
                        
                    } catch (Exception e) {
                        log.warn("Failed to distribute cache to {}: {}", 
                                server, e.getMessage());
                    }
                }, cacheDistributionExecutor))
                .collect(Collectors.toList());
            
            // Wait for all distributions (with timeout)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);
            
            log.info("Cache distributed to {} servers", targetServers.size());
            
        } catch (Exception e) {
            log.error("Cache distribution failed", e);
        }
    }
    
    private void broadcastInvalidationToCluster(CacheSignal signal) {
        try {
            if (!(statementService instanceof MultinodeStatementService)) {
                return;
            }
            
            MultinodeStatementService multinode = 
                (MultinodeStatementService) statementService;
            
            List<ServerEndpoint> targetServers = 
                multinode.getAllServersExcept(signal.getSourceServerId());
            
            CacheInvalidationRequest request = CacheInvalidationRequest.newBuilder()
                .addAllAffectedTables(signal.getAffectedTablesList())
                .setSourceServerId(getCurrentServerId())
                .build();
            
            // Parallel broadcast
            List<CompletableFuture<Void>> futures = targetServers.stream()
                .map(server -> CompletableFuture.runAsync(() -> {
                    try {
                        StatementServiceBlockingStub stub = 
                            multinode.getStub(server);
                        stub.invalidateCache(request);
                        
                        log.debug("Broadcasted invalidation to: {}", server);
                        
                    } catch (Exception e) {
                        log.warn("Failed to invalidate cache on {}: {}", 
                                server, e.getMessage());
                    }
                }, cacheDistributionExecutor))
                .collect(Collectors.toList());
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(2, TimeUnit.SECONDS);
            
            log.info("Cache invalidation broadcasted to {} servers", 
                    targetServers.size());
            
        } catch (Exception e) {
            log.error("Invalidation broadcast failed", e);
        }
    }
}
```

##### 3. Server-Side Implementation

```java
// In ojp-server
public class ExecuteQueryAction implements Action {
    
    private final QueryResultCache cache;
    private final CacheConfiguration config;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager manager) {
        String sql = request.getSql();
        
        // Check if cacheable
        if (!isCacheable(sql)) {
            return executeQueryOnDatabase(request, manager);
        }
        
        QueryCacheKey key = new QueryCacheKey(sql, extractParameters(request));
        
        // Try cache first
        CachedResult cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return buildOpResultFromCache(cached);
        }
        
        // Cache MISS - execute query
        OpResult result = executeQueryOnDatabase(request, manager);
        
        if (result.getSuccess() && config.isDistributionEnabled()) {
            // Store in local cache
            CachedResult cachedResult = extractResultForCache(result);
            cache.put(key, cachedResult);
            
            // Signal driver to distribute to other servers
            CacheSignal signal = CacheSignal.newBuilder()
                .setType(SignalType.CACHE_AND_DISTRIBUTE)
                .setCacheKey(key.toString())
                .setCachedData(serializeCachedResult(cachedResult))
                .setTtl(cachedResult.getTtl())
                .build();
            
            result = result.toBuilder()
                .setCacheSignal(signal)
                .build();
        }
        
        return result;
    }
}

public class ExecuteUpdateAction implements Action {
    
    private final QueryResultCache cache;
    private final TableDependencyAnalyzer analyzer;
    
    @Override
    public OpResult execute(StatementRequest request, SessionManager manager) {
        String sql = request.getSql();
        
        // Execute update
        OpResult result = executeUpdateOnDatabase(request, manager);
        
        if (result.getSuccess()) {
            // Extract affected tables
            Set<String> affectedTables = analyzer.extractTables(sql);
            
            // Invalidate local cache
            cache.invalidateByTables(affectedTables);
            
            // Signal driver to broadcast invalidation
            CacheSignal signal = CacheSignal.newBuilder()
                .setType(SignalType.INVALIDATE_AND_BROADCAST)
                .addAllAffectedTables(affectedTables)
                .build();
            
            result = result.toBuilder()
                .setCacheSignal(signal)
                .build();
        }
        
        return result;
    }
}

// New gRPC service methods
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    
    private final QueryResultCache cache;
    
    @Override
    public void distributeCachedResult(CacheDistributionRequest request,
                                      StreamObserver<CacheDistributionResponse> responseObserver) {
        try {
            // Deserialize and store in local cache
            CachedResult result = deserializeCachedResult(request.getCachedData());
            QueryCacheKey key = QueryCacheKey.fromString(request.getCacheKey());
            
            cache.put(key, result);
            
            log.info("Received and stored cache from relay: {}", key);
            
            responseObserver.onNext(CacheDistributionResponse.newBuilder()
                .setSuccess(true)
                .build());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to store distributed cache", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void invalidateCache(CacheInvalidationRequest request,
                               StreamObserver<CacheInvalidationResponse> responseObserver) {
        try {
            // Invalidate local cache by tables
            Set<String> tables = new HashSet<>(request.getAffectedTablesList());
            cache.invalidateByTables(tables);
            
            log.info("Invalidated cache for tables: {}", tables);
            
            responseObserver.onNext(CacheInvalidationResponse.newBuilder()
                .setSuccess(true)
                .setInvalidatedCount(cache.getInvalidatedCount())
                .build());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to invalidate cache", e);
            responseObserver.onError(e);
        }
    }
}
```

#### Advantages
- ✅ **Real-time propagation**: No polling delays, immediate cache distribution
- ✅ **Zero database overhead**: No notification table or queries needed
- ✅ **Efficient with virtual threads**: Scales to thousands of connections (Java 25+)
- ✅ **Direct server-to-server**: Leverages existing gRPC connections
- ✅ **Async and non-blocking**: Doesn't slow down query execution
- ✅ **Push-based**: More efficient than polling
- ✅ **Simple failure handling**: Failed distributions don't affect primary query
- ✅ **No additional infrastructure**: Uses existing OJP components

#### Disadvantages
- ⚠️ **Driver complexity**: Adds significant logic to the JDBC driver
- ⚠️ **Memory pressure**: Driver must serialize result sets for distribution
- ⚠️ **Network amplification**: One query generates N-1 distribution calls
- ⚠️ **Potential data duplication**: Large result sets sent to multiple servers
- ⚠️ **Driver stability risk**: Bugs in distribution affect all queries
- ⚠️ **Harder to debug**: Distribution happens asynchronously in driver
- ⚠️ **Connection dependency**: Requires driver to know all server endpoints
- ⚠️ **No persistence**: If driver crashes during distribution, caches inconsistent
- ⚠️ **Testing complexity**: Harder to test multinode coordination

#### Performance Characteristics

**Cache Distribution Overhead:**
```
Small result (10 rows, ~2KB):     +10-20ms per server
Medium result (100 rows, ~20KB):  +30-50ms per server  
Large result (1000 rows, ~200KB): +100-200ms per server

3-server cluster, medium result:  ~100ms total distribution time
10-server cluster, medium result: ~300ms total distribution time
```

**Optimization: Selective Distribution**

Only distribute if result set meets criteria:

```java
public class CacheDistributionPolicy {
    
    private final int maxSizeBytes = 100_000;  // 100KB
    private final int maxRows = 500;
    
    public CacheDistributionPolicy(boolean distributionEnabled) {
        this.distributionEnabled = distributionEnabled;
    }
    
    public boolean shouldDistribute(CachedResult result) {
        // First check: is distribution enabled for this datasource?
        if (!distributionEnabled) {
            return false;  // Local-only caching
        }
        
        // Don't distribute large results
        if (result.getSizeBytes() > maxSizeBytes) {
            return false;
        }
        
        if (result.getRowCount() > maxRows) {
            return false;
        }
        
        // Don't distribute if TTL is very short
        if (result.getTtl() < Duration.ofSeconds(60)) {
            return false;
        }
        
        return true;
    }
}
```

#### Best For
- Small to medium-sized result sets (< 100KB)
- High cache hit rate scenarios
- Clusters with < 10 servers
- Applications using Java 25+ (virtual threads)
- Real-time cache consistency requirements
- Environments where database load is a bottleneck

#### Not Recommended For
- Large result sets (> 1MB)
- Very large clusters (> 20 servers)
- High write rate scenarios (constant invalidations)
- Legacy Java environments without virtual thread support
- Unstable network conditions

---

### 5.5 Approach 5: Hybrid JDBC + External Cache

**Concept:** Use JDBC for coordination + external distributed cache (Redis, Hazelcast).

```mermaid
graph TB
    subgraph OJP_Cluster["OJP Cluster"]
        ServerA["OJP Server A"]
        ServerB["OJP Server B"]
        ServerC["OJP Server C"]
        
        ServerA --> Redis
        ServerB --> Redis
        ServerC --> Redis
        
        Redis["Redis Cluster<br/>(Cache Storage + Pub/Sub)"]
        Redis --> NotifDB["Notification DB<br/>(JDBC - Backup)"]
    end
    
    style OJP_Cluster fill:#f0f0f0,stroke:#333,stroke-width:2px
```

#### Implementation

```java
public class HybridCacheService {
    
    private final RedisClient redisClient;
    private final JdbcCacheNotificationService jdbcNotification;
    private final String serverId;
    
    public void start() {
        // Start Redis pub/sub
        redisClient.subscribe("ojp:cache:invalidate", this::handleRedisNotification);
        
        // Start JDBC polling as backup
        jdbcNotification.start();
        
        log.info("Started hybrid cache service (Redis + JDBC)");
    }
    
    public void invalidateCache(Set<String> tables) {
        try {
            // Try Redis first (fast path)
            redisClient.publish("ojp:cache:invalidate", 
                              serializeInvalidation(tables));
            
        } catch (Exception e) {
            log.warn("Redis publish failed, falling back to JDBC", e);
            
            // Fallback to JDBC (reliable path)
            jdbcNotification.notifyInvalidation(tables);
        }
    }
    
    private void handleRedisNotification(String message) {
        Set<String> tables = deserializeInvalidation(message);
        localCache.invalidateByTables(tables);
        
        log.debug("Processed Redis invalidation for tables: {}", tables);
    }
}
```

#### Advantages
- ✅ Fast invalidation via Redis pub/sub
- ✅ Reliable fallback via JDBC
- ✅ Scalable with dedicated cache infrastructure
- ✅ Best of both worlds

#### Disadvantages
- ⚠️ Requires additional infrastructure (Redis)
- ⚠️ More complex setup and operation
- ⚠️ Additional dependencies

#### Best For
- Large-scale production deployments
- High-throughput applications
- Organizations already using Redis/Hazelcast

---

### 5.6 Comprehensive Recommendation Matrix (REVISED)

| Scenario | Recommended Approach | Reason |
|----------|---------------------|--------|
| **Most deployments (90%)** | **JDBC Driver Relay** | Data already in memory, saves database load, real-time |
| Small-medium result sets (<200KB) | JDBC Driver Relay | Efficient distribution, data already in driver |
| Large result sets (>200KB) | JDBC Polling | Avoids network amplification |
| PostgreSQL-only | LISTEN/NOTIFY | Real-time, PostgreSQL-native |
| Very large clusters (20+ servers) | Redis + JDBC backup | Reduces network amplification |
| Multi-database support | JDBC Driver Relay or Polling | Works with any database |
| Real-time requirements (<100ms) | JDBC Driver Relay (Java 25+) or LISTEN/NOTIFY | Immediate propagation |
| High availability critical | Hybrid (Redis + JDBC) | Redundant notification paths |
| Java 25+ environment | JDBC Driver Relay | Leverages virtual threads for efficiency |
| Legacy Java (<21) | JDBC Polling or LISTEN/NOTIFY | Virtual threads make driver relay more efficient |
| ORM-based applications (Hibernate, Spring Data) | Server-side config + Driver Relay | Works regardless of framework |
| Development/testing | Client-side config + Driver Relay | Easy per-app customization |
| Production | Server-side config + Driver Relay | Centralized governance, efficient distribution |

**Key Change**: JDBC Driver Relay is now recommended as default for most scenarios, with fallback to polling only for large results or very large clusters.

---

## 6. Implementation Roadmap

### Phase 1: Local Caching (Single Server)
**Goal:** Implement basic query result caching on a single OJP server.

**Tasks:**
1. ✅ Design QueryResultCache with TTL support
2. ✅ Implement CacheHintParser for SQL comment hints
3. ✅ Modify ExecuteQueryAction to check cache
4. ✅ Add cache metrics and monitoring
5. ✅ Create configuration options
6. ✅ Write unit tests

**Deliverables:**
- Working cache on single OJP server
- Documentation for SQL hint syntax
- Performance benchmarks

---

### Phase 2: Write-Through Invalidation
**Goal:** Implement automatic cache invalidation on DML operations.

**Tasks:**
1. ✅ Implement TableDependencyAnalyzer
2. ✅ Track table dependencies in cache
3. ✅ Modify ExecuteUpdateAction to invalidate cache
4. ✅ Add integration tests for invalidation
5. ✅ Document invalidation behavior

**Deliverables:**
- Automatic invalidation on writes
- Documentation of invalidation semantics
- Test suite for invalidation scenarios

---

### Phase 3: Distributed Cache (JDBC-Based)
**Goal:** Enable cache coordination across multiple OJP servers.

**Tasks:**
1. ✅ Design notification table schema
2. ✅ Implement JdbcCacheNotificationService
3. ✅ Add configuration for notification DataSource
4. ✅ Implement notification polling and processing
5. ✅ Add cleanup job for old notifications
6. ✅ Create multinode integration tests
7. ✅ Document multinode setup

**Deliverables:**
- Working distributed cache coordination
- Multinode deployment guide
- Performance characteristics documentation

---

### Phase 4: Advanced Features (Optional)
**Goal:** Add sophisticated caching features.

**Tasks:**
1. Semantic query analysis with Calcite
2. PostgreSQL LISTEN/NOTIFY support
3. Redis integration option
4. Cache warming/preloading
5. Query result compression
6. Cache statistics dashboard

---

## 7. Configuration Examples

### 7.1 Enable Basic Caching

```yaml
# ojp-server.yml
cache:
  enabled: true
  defaultTtl: 300s
  maxSize: 10000
  evictionPolicy: LRU  # Least Recently Used
```

### 7.2 Enable Distributed Caching (JDBC)

```yaml
# ojp-server.yml
cache:
  enabled: true
  defaultTtl: 300s
  maxSize: 10000
  
  replication:
    enabled: true
    mode: jdbc
    serverId: ojp-server-1  # Unique per server
    
    jdbc:
      url: jdbc:postgresql://cache-db:5432/ojp_cache
      username: ojp_cache_user
      password: ${OJP_CACHE_PASSWORD}
      pollIntervalSeconds: 1
      cleanupIntervalMinutes: 60
```

### 7.3 Enable PostgreSQL LISTEN/NOTIFY

```yaml
# ojp-server.yml (PostgreSQL only)
cache:
  enabled: true
  
  replication:
    enabled: true
    mode: postgres-notify
    
    monitoredTables:
      - products
      - users
      - orders
```

---

## 8. Code Integration Points

### 8.1 Files to Modify

1. **ojp-server/src/main/java/org/openjproxy/grpc/server/action/statement/ExecuteQueryAction.java**
   - Add cache lookup before query execution
   - Store results in cache after execution

2. **ojp-server/src/main/java/org/openjproxy/grpc/server/action/statement/ExecuteUpdateAction.java**
   - Add cache invalidation after successful DML

3. **ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java**
   - Add cache configuration loading
   - Initialize cache services

4. **ojp-server/src/main/java/org/openjproxy/grpc/server/StatementServiceImpl.java**
   - Wire up cache services to actions

### 8.2 New Files to Create

1. **ojp-server/src/main/java/org/openjproxy/grpc/server/cache/QueryResultCache.java**
   - Main cache implementation

2. **ojp-server/src/main/java/org/openjproxy/grpc/server/cache/CacheHintParser.java**
   - Parse SQL comments for cache directives

3. **ojp-server/src/main/java/org/openjproxy/grpc/server/cache/JdbcCacheNotificationService.java**
   - JDBC-based distributed cache coordination

4. **ojp-server/src/main/java/org/openjproxy/grpc/server/cache/TableDependencyAnalyzer.java**
   - Extract table dependencies from SQL

5. **ojp-server/src/main/java/org/openjproxy/grpc/server/cache/CacheConfiguration.java**
   - Cache configuration POJO

---

## 9. Performance Considerations

### 9.1 Memory Management

**Issue:** Cached query results consume heap memory.

**Solutions:**
1. **Size-based eviction** - Limit total cache size (e.g., 1GB max)
2. **Entry count limit** - Maximum number of cached queries (e.g., 10,000)
3. **LRU eviction** - Evict least recently used entries
4. **Off-heap storage** - Use direct ByteBuffers for large result sets
5. **Result set compression** - Compress cached data (GZIP, LZ4)

```java
public class QueryResultCache {
    private final long maxSizeBytes;
    private final AtomicLong currentSizeBytes = new AtomicLong(0);
    
    public void put(QueryCacheKey key, CachedResult result) {
        long resultSize = result.estimateSize();
        
        // Check if adding this result would exceed max size
        while (currentSizeBytes.get() + resultSize > maxSizeBytes) {
            evictLRU();  // Evict until there's space
        }
        
        cache.put(key, result);
        currentSizeBytes.addAndGet(resultSize);
    }
}
```

### 9.2 Cache Key Computation

**Issue:** Computing hash codes for cache keys can be expensive.

**Optimization:** Pre-compute and store hash codes.

```java
public class QueryCacheKey {
    private final String sql;
    private final List<Object> parameters;
    private final int hashCode;  // Pre-computed
    
    public QueryCacheKey(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = parameters;
        this.hashCode = computeHashCode();  // Compute once
    }
    
    private int computeHashCode() {
        int result = sql.hashCode();
        result = 31 * result + Objects.hashCode(parameters);
        return result;
    }
    
    @Override
    public int hashCode() {
        return hashCode;  // O(1) lookup
    }
}
```

### 9.3 Monitoring and Metrics

**Key Metrics to Track:**

```java
public class CacheMetrics {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong invalidations = new AtomicLong(0);
    
    public double getHitRate() {
        long h = hits.get();
        long m = misses.get();
        return (h + m == 0) ? 0.0 : (double) h / (h + m);
    }
    
    // Expose as Prometheus metrics
    public void registerPrometheusMetrics(CollectorRegistry registry) {
        Gauge.build()
            .name("ojp_cache_hit_rate")
            .help("Query cache hit rate")
            .register(registry)
            .set(this::getHitRate);
        
        Counter.build()
            .name("ojp_cache_operations_total")
            .labelNames("type")  // hit, miss, eviction, invalidation
            .help("Total cache operations by type")
            .register(registry);
    }
}
```

---

## 10. Security Considerations

### 10.1 Cache Isolation

**Issue:** Multi-tenant environments need isolated caches.

**Solution:** Namespace cache keys by connection properties.

```java
public class QueryCacheKey {
    private final String tenant;        // Add tenant identifier
    private final String databaseUrl;   // Isolate by database
    private final String username;      // Isolate by user
    private final String sql;
    private final List<Object> parameters;
    
    // Prevents cross-tenant cache pollution
}
```

### 10.2 Sensitive Data in Cache

**Issue:** Cached results may contain PII or sensitive data.

**Mitigation:**
1. **Encryption at rest** - Encrypt cached data
2. **Selective caching** - Don't cache queries with sensitive columns
3. **Cache access control** - Verify user permissions on cache hits
4. **Audit logging** - Log cache access for sensitive data

```java
public class SensitiveDataFilter {
    
    private final Set<String> sensitiveColumns = Set.of(
        "password", "ssn", "credit_card", "api_key"
    );
    
    public boolean isCacheable(String sql) {
        // Don't cache queries that select sensitive columns
        for (String col : sensitiveColumns) {
            if (sql.toLowerCase().contains(col)) {
                return false;
            }
        }
        return true;
    }
}
```

### 10.3 Cache Poisoning Prevention

**Issue:** Malicious users could flood cache with junk queries.

**Mitigation:**
1. **Rate limiting** - Limit cache insertions per connection
2. **Query complexity limits** - Don't cache excessively complex queries
3. **Size limits** - Reject results larger than threshold
4. **Permission checks** - Verify user has SELECT permission before caching

---

## 11. Testing Strategy

### 11.1 Unit Tests

```java
@Test
public void testCacheHitReturnsExactResults() {
    // Given: A cached query result
    QueryCacheKey key = new QueryCacheKey("SELECT * FROM users WHERE id = ?", 
                                          List.of(123));
    CachedResult cached = createTestResult();
    cache.put(key, cached);
    
    // When: Same query is executed
    CachedResult result = cache.get(key);
    
    // Then: Returns cached result
    assertNotNull(result);
    assertEquals(cached, result);
}

@Test
public void testCacheInvalidationRemovesEntries() {
    // Given: Cached queries for multiple tables
    cache.put(keyForUsersTable, resultA);
    cache.put(keyForOrdersTable, resultB);
    
    // When: Invalidate users table
    cache.invalidateByTables(Set.of("users"));
    
    // Then: Only users queries are removed
    assertNull(cache.get(keyForUsersTable));
    assertNotNull(cache.get(keyForOrdersTable));
}
```

### 11.2 Integration Tests

```java
@Test
public void testDistributedCacheInvalidation() throws Exception {
    // Given: Two OJP servers with distributed cache
    OjpServer server1 = startServer(1059, "server-1");
    OjpServer server2 = startServer(1060, "server-2");
    
    Connection conn1 = connectTo(server1);
    Connection conn2 = connectTo(server2);
    
    // When: Execute cacheable query on server 1
    ResultSet rs1 = conn1.createStatement()
        .executeQuery("/* @cache ttl=300s */ SELECT * FROM users WHERE id = 1");
    rs1.next();
    int value1 = rs1.getInt("value");
    
    // And: Update data through server 1
    conn1.createStatement()
        .executeUpdate("UPDATE users SET value = 999 WHERE id = 1");
    
    // Then: Server 2's cache should be invalidated
    Thread.sleep(2000);  // Wait for notification propagation
    
    ResultSet rs2 = conn2.createStatement()
        .executeQuery("/* @cache ttl=300s */ SELECT * FROM users WHERE id = 1");
    rs2.next();
    int value2 = rs2.getInt("value");
    
    assertEquals(999, value2);  // Should see updated value
}
```

### 11.3 Performance Tests

```java
@Test
public void testCachePerformanceImprovement() {
    // Measure baseline (no cache)
    long baselineTime = measureQueryTime(() -> {
        for (int i = 0; i < 1000; i++) {
            executeQuery("SELECT * FROM large_table WHERE category = 'test'");
        }
    });
    
    // Enable cache and warm up
    enableCache();
    executeQuery("/* @cache */ SELECT * FROM large_table WHERE category = 'test'");
    
    // Measure with cache
    long cachedTime = measureQueryTime(() -> {
        for (int i = 0; i < 1000; i++) {
            executeQuery("/* @cache */ SELECT * FROM large_table WHERE category = 'test'");
        }
    });
    
    // Verify improvement
    double improvement = (double) (baselineTime - cachedTime) / baselineTime;
    assertTrue(improvement > 0.80,  // Expect >80% improvement
               "Cache should improve performance significantly");
}
```

---

## 12. Comparison with Alternative Solutions

### 12.1 Application-Level Caching (e.g., Spring Cache)

**Pros:**
- More control in application code
- Easier debugging
- Type-safe

**Cons:**
- Must implement in every application
- Not transparent to existing code
- Doesn't work across different applications

**OJP Advantage:** Transparent caching at JDBC layer works for ALL applications.

---

### 12.2 Database Query Result Cache (e.g., PostgreSQL Shared Buffers)

**Pros:**
- Automatic and transparent
- Managed by database

**Cons:**
- Caches pages, not query results
- Shared across all queries (no selective caching)
- Limited by database server resources

**OJP Advantage:** Application-aware caching with fine-grained control and offloaded from database.

---

### 12.3 Redis/Memcached as External Cache

**Pros:**
- Dedicated cache infrastructure
- Scalable
- Rich feature set

**Cons:**
- Requires application code changes
- Network latency for cache lookups
- Additional infrastructure to manage

**OJP Advantage:** Transparent caching without application changes, can optionally use Redis for distribution.

---

## 13. Summary and Recommendations

### 13.1 Simplified Approach: Client-Side Configuration in ojp.properties

**DESIGN PRINCIPLE**: Cache configuration should follow existing OJP patterns - **datasource configuration is client-side in `ojp.properties`**.

This aligns with how OJP already handles:
- Connection pool configuration (`ojp.connection.pool.*`)
- XA transaction configuration (`ojp.xa.*`)
- Datasource-specific properties (`{datasourceName}.ojp.*`)

**Benefits of Client-Side Approach**:
- ✅ **Simple**: No server-side configuration, no hot-reload complexity
- ✅ **Aligned**: Follows existing OJP configuration patterns
- ✅ **Decoupled**: Each datasource controls its own cache policy
- ✅ **Independent**: Update one datasource's cache without affecting others
- ✅ **No restart**: Only affected application needs restart (not OJP server)
- ✅ **Familiar**: Developers already know where to configure datasources

---

#### Query Marking: Client-Side Configuration in ojp.properties (⭐ RECOMMENDED)

**Configuration Location**: `ojp.properties` (same file as connection pool config)

```properties
# Default datasource cache configuration
ojp.cache.enabled=true
ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
ojp.cache.queries.1.ttl=600s
ojp.cache.queries.1.invalidateOn=products,product_categories

ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = ?
ojp.cache.queries.2.ttl=300s
ojp.cache.queries.2.invalidateOn=users

# Multinode datasource cache configuration (separate from default)
multinode.ojp.cache.enabled=true
multinode.ojp.cache.queries.1.pattern=SELECT .* FROM analytics_.*
multinode.ojp.cache.queries.1.ttl=1800s
multinode.ojp.cache.queries.1.invalidateOn=analytics_tables

# PostgreSQL production datasource
postgres_prod.ojp.cache.enabled=true
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM orders WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=300s

# MySQL analytics datasource (longer TTL)
mysql_analytics.ojp.cache.enabled=true
mysql_analytics.ojp.cache.queries.1.pattern=SELECT .* FROM report_.*
mysql_analytics.ojp.cache.queries.1.ttl=3600s
```

**Implementation in JDBC Driver**:

```java
// In DatasourcePropertiesLoader.java (existing class)
public static Properties loadOjpPropertiesForDataSource(String dataSourceName) {
    Properties allProperties = loadOjpProperties();
    Properties dataSourceProperties = new Properties();
    
    // Load cache configuration for this datasource
    String cachePrefix = dataSourceName + ".ojp.cache.";
    
    for (String key : allProperties.stringPropertyNames()) {
        if (key.startsWith(cachePrefix)) {
            // Remove the dataSource prefix
            String standardKey = key.substring(dataSourceName.length() + 1);
            dataSourceProperties.setProperty(standardKey, allProperties.getProperty(key));
        }
    }
    
    // For "default" datasource, also check unprefixed properties
    if ("default".equals(dataSourceName)) {
        for (String key : allProperties.stringPropertyNames()) {
            if (key.startsWith("ojp.cache.")) {
                dataSourceProperties.setProperty(key, allProperties.getProperty(key));
            }
        }
    }
    
    return dataSourceProperties;
}

// Parse cache configuration from properties
public class CacheConfiguration {
    private final boolean enabled;
    private final boolean distribute;  // Enable driver relay distribution
    private final List<CacheQuery> queries;
    
    public static CacheConfiguration fromProperties(Properties props) {
        boolean enabled = Boolean.parseBoolean(
            props.getProperty("ojp.cache.enabled", "false"));
        
        if (!enabled) {
            return CacheConfiguration.disabled();
        }
        
        // Check if distribution is enabled (default: false for local-only caching)
        boolean distribute = Boolean.parseBoolean(
        
        List<CacheQuery> queries = new ArrayList<>();
        
        // Parse numbered query configurations
        for (int i = 1; i <= 100; i++) {  // Support up to 100 queries
            String pattern = props.getProperty("ojp.cache.queries." + i + ".pattern");
            if (pattern == null) break;  // No more queries
            
            String ttl = props.getProperty("ojp.cache.queries." + i + ".ttl", "300s");
            String invalidateOn = props.getProperty("ojp.cache.queries." + i + ".invalidateOn", "");
            
            queries.add(new CacheQuery(pattern, parseTtl(ttl), parseTableList(invalidateOn)));
        }
        
        return new CacheConfiguration(enabled, distribute, queries);
    }
}
```

**Send to OJP Server During Connection**:

```java
// In Driver.java
@Override
public Connection connect(String url, Properties info) throws SQLException {
    // Load datasource-specific properties (including cache config)
    String dataSourceName = extractDataSourceName(url);
    Properties dsProperties = DatasourcePropertiesLoader
        .loadOjpPropertiesForDataSource(dataSourceName);
    
    // Parse cache configuration
    CacheConfiguration cacheConfig = CacheConfiguration.fromProperties(dsProperties);
    
    // Create connection request with cache config
    ConnectionRequest request = ConnectionRequest.newBuilder()
        .setConnectionDetails(details)
        .setCacheConfiguration(serializeCacheConfig(cacheConfig))
        .build();
    
    // Send to OJP server
    SessionInfo session = statementService.connect(request);
    
    return new OjpConnection(session, statementService);
}
```

**OJP Server Stores Per-Session Configuration**:

```java
// In ojp-server: Session.java
public class Session {
    private final String sessionId;
    private final String dataSourceName;
    private final CacheConfiguration cacheConfig;  // Per-session cache rules
    
    // Each session knows its own cache rules
}

// In ExecuteQueryAction.java
public class ExecuteQueryAction implements Action {
    @Override
    public OpResult execute(StatementRequest request, SessionManager manager) {
        String sql = request.getSql();
        Session session = manager.getSession(request.getSessionId());
        
        // Get cache config from session (came from client properties)
        CacheConfiguration cacheConfig = session.getCacheConfiguration();
        
        // Match query against this session's cache rules
        CacheQuery matchedRule = cacheConfig.matchQuery(sql);
        
        if (matchedRule != null) {
            // Query is cacheable for this session/datasource
            // ... proceed with caching logic
        }
        
        return executeQueryOnDatabase(request, manager);
    }
}
```

**Advantages**:
- ✅ **Follows existing OJP patterns**: Same as connection pool config
- ✅ **Simple**: No server-side config files, no hot-reload mechanism
- ✅ **Decoupled**: Each datasource has independent cache policy
- ✅ **Per-session**: Different sessions can have different cache rules
- ✅ **No server restart**: Only restart affected application
- ✅ **Works with ORMs**: Pattern matching works for any generated SQL
- ✅ **Version controlled**: `ojp.properties` in application repo
- ✅ **Environment-specific**: Different properties for dev/staging/prod

**Disadvantages**:
- ⚠️ Configuration per connection (memory overhead)
- ⚠️ Must restart application to change cache rules (acceptable)
- ⚠️ Different applications may have conflicting rules (isolated by session)

**When to Update Cache Configuration**:
1. Edit `ojp.properties` in application repository
2. Commit and deploy application
3. Restart only that application
4. OJP server and other applications unaffected ✅

---

#### Alternative: JDBC Connection Properties (Simpler)

For basic caching without per-query control:

```java
// JDBC URL with cache parameters
jdbc:ojp[localhost:1059(postgres_prod)]_postgresql://db:5432/mydb
  ?ojpCacheEnabled=true
  &ojpCacheDefaultTtl=300
  &ojpCachePatterns=SELECT.*FROM products.*,SELECT.*FROM users.*
```

**Even Simpler**: Just enable caching, cache everything:
```
?ojpCacheEnabled=true&ojpCacheDefaultTtl=600
```

---


**Data Already in Memory**: The data is already in the JDBC driver being returned to the application. Just add a side-effect to stream it to other servers.

- `distribute=true`: Enable driver relay to other servers
- `distribute=false`: Cache only maintained locally (default)

**Smart Distribution Policy** (when distribution is enabled):
```java
public boolean shouldDistribute(CachedResult result) {
    // First check: is distribution enabled for this datasource?
    if (!distributionEnabled) return false;  // Local-only caching
    
    // Don't distribute very large results
    if (result.getSizeBytes() > 200_000) return false;  // 200KB limit
    
    // Don't distribute if TTL is very short (not worth it)
    if (result.getTtl() < Duration.ofSeconds(60)) return false;
    
    // Don't distribute single-row results (cheap to re-query)
    if (result.getRowCount() < 2) return false;
    
    return true;
}
```

**Fallback Options**:
- **JDBC Notification Table**: For large results or legacy Java environments
- **PostgreSQL LISTEN/NOTIFY**: For PostgreSQL-only deployments
- **Redis + JDBC**: For very large clusters (20+ servers)

---

### 13.2 Final Recommendation (SIMPLIFIED)

**⭐ RECOMMENDED FOR MOST DEPLOYMENTS (90% of use cases):**

**1. Query Marking: Client-Side Configuration in `ojp.properties`**

```properties
# In ojp.properties (same file as connection pool config)
postgres_prod.ojp.cache.enabled=true
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = ?
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=users
```

**Why this approach?**
- ✅ **Follows existing OJP patterns** (same as connection pool config)
- ✅ **Simple**: No server-side config, no hot-reload complexity
- ✅ **Decoupled**: Each datasource controls its own cache
- ✅ **Independent updates**: Change one app without affecting others
- ✅ **Works with ORMs**: Pattern matching for generated SQL
- ✅ **No OJP restart**: Only restart affected application
- ✅ **Familiar**: Developers already know this location
- ✅ **Optional distribution**: Can start with local-only caching

**2. Local Caching:** TTL-based with write-through invalidation

**3. Distributed Caching (Optional):** JDBC Driver as Active Relay
- Data already in driver memory
- Smart distribution policy (< 200KB, TTL > 60s, > 1 row)
- Saves N-1 database queries

**Alternative (Even Simpler)**: JDBC URL Parameters
```
jdbc:ojp[localhost:1059]_postgresql://db:5432/mydb?ojpCacheEnabled=true&ojpCacheDefaultTtl=600
```

**Fallback Options:**
- **Legacy Java (<21)**: Use JDBC Notification Table instead of driver relay
- **PostgreSQL-Only**: Use LISTEN/NOTIFY for real-time propagation
- **Very Large Clusters (20+)**: Consider Redis + JDBC hybrid

---

### 13.3 Why Client-Side Configuration is the Right Approach

**Key Insight from @rrobetti**: "The configuration of cached queries shall be in the client within the ojp.properties under the datasource configuration. This way every datasource controls its own set of cached queries and can update them independently. This is aligned with other OJP configurations related to datasources and pooling which are already done in the client side."

**Comparison: Client-Side vs. Server-Side**

| Aspect | Client-Side (✅ Recommended) | Server-Side (❌ Too Complex) |
|--------|------------------------------|------------------------------|
| **Simplicity** | ✅ Simple, no hot-reload needed | ❌ Requires hot-reload, admin API, git-backed config |
| **Alignment** | ✅ Follows existing OJP patterns | ❌ New pattern, different from pool config |
| **Updates** | ✅ Edit ojp.properties, restart app | ❌ Edit server config, hot-reload mechanism |
| **Impact** | ✅ Only affected app restarted | ❌ Potential impact on all apps |
| **Decoupling** | ✅ Each datasource independent | ❌ Centralized, potential conflicts |
| **Familiarity** | ✅ Developers know where to look | ❌ New location, less discoverable |
| **Version Control** | ✅ With application code | ⚠️ Separate repo or server files |
| **Testing** | ✅ Test with app deployment | ⚠️ Separate deployment/testing |
| **Complexity** | ✅ Minimal | ❌ File-watch, admin API, validation, rollback |

**This is the right approach because:**
1. **Follows existing patterns**: Connection pools, XA config - all datasource settings are client-side
2. **Simpler**: No complex server-side hot-reload mechanisms needed
3. **Decoupled**: Each application/datasource is independent
4. **Natural fit**: Cache rules are datasource-specific, belong with datasource config
5. **No over-engineering**: Don't add complexity when simpler solution exists

---

### 13.4 Evolution of Recommendations

**Iteration 1**: SQL hints + JDBC notification table
- ❌ SQL hints don't work with ORMs (Hibernate, Spring Data)

**Iteration 2**: Server-side config with hot-reload + driver relay
- ❌ Too complex (hot-reload, admin API, git-backed config, version management)
- ❌ Doesn't follow existing OJP patterns
- ❌ Server configuration for what should be client configuration

**Iteration 3 (Final)**: Client-side `ojp.properties` + driver relay
- ✅ **Simple**: Follows existing OJP configuration patterns
- ✅ **Aligned**: Same location as all other datasource config
- ✅ **Decoupled**: Each datasource independent
- ✅ **Practical**: No unnecessary complexity

**Learning**: Sometimes the simplest solution aligned with existing patterns is the best solution. Don't over-engineer when a straightforward approach exists.

---

## 14. Phased Implementation Plan (Local Caching Only)

This section provides a **detailed, actionable implementation plan** for implementing query result caching in OJP, focusing on **local caching at individual nodes only** (excluding distributed cache coordination for now).

---

### 14.1 Overview

**Scope:** Local query result caching on each OJP server node independently
- ✅ Client-side configuration in `ojp.properties`
- ✅ Pattern-based query matching
- ✅ TTL-based cache expiration
- ✅ Write-through cache invalidation
- ❌ **NOT included**: Distributed cache coordination (JDBC driver relay)

**Goal:** Implement a production-ready local query cache that:
1. Works with any ORM/framework (Hibernate, Spring Data, MyBatis, jOOQ)
2. Requires no changes to application code
3. Provides immediate performance benefits for repeated queries
4. Integrates seamlessly with existing OJP architecture

---

### 14.2 Phase 1: Foundation (Weeks 1-2)

#### 14.2.1 Core Data Structures

**Task 1.1: Create Cache Key Class**

Location: `ojp-server/src/main/java/org/openjproxy/cache/QueryCacheKey.java`

```java
package org.openjproxy.cache;

import java.util.List;
import java.util.Objects;

/**
 * Immutable cache key for query results.
 * Includes datasource name for isolation between datasources.
 */
public final class QueryCacheKey {
    private final String datasourceName;
    private final String normalizedSql;
    private final List<Object> parameters;
    private final int hashCode;
    
    public QueryCacheKey(String datasourceName, String sql, List<Object> params) {
        this.datasourceName = Objects.requireNonNull(datasourceName);
        this.normalizedSql = normalizeSql(sql);
        this.parameters = params != null ? List.copyOf(params) : List.of();
        this.hashCode = Objects.hash(datasourceName, normalizedSql, parameters);
    }
    
    private String normalizeSql(String sql) {
        // Remove extra whitespace, normalize to uppercase
        return sql.trim().replaceAll("\\s+", " ").toUpperCase();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueryCacheKey)) return false;
        QueryCacheKey that = (QueryCacheKey) o;
        return datasourceName.equals(that.datasourceName) &&
               normalizedSql.equals(that.normalizedSql) &&
               parameters.equals(that.parameters);
    }
    
    @Override
    public int hashCode() {
        return hashCode;
    }
    
    public String getDatasourceName() { return datasourceName; }
    public String getNormalizedSql() { return normalizedSql; }
    public List<Object> getParameters() { return parameters; }
}
```

**Deliverable:** QueryCacheKey class with tests

---

**Task 1.2: Create Cache Entry Class**

Location: `ojp-server/src/main/java/org/openjproxy/cache/CachedQueryResult.java`

```java
package org.openjproxy.cache;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Cached query result with metadata.
 */
public final class CachedQueryResult {
    private final List<List<Object>> rows;
    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final Instant cachedAt;
    private final Instant expiresAt;
    private final Set<String> dependentTables;
    private final long sizeBytes;
    
    public CachedQueryResult(
            List<List<Object>> rows,
            List<String> columnNames,
            List<String> columnTypes,
            Instant cachedAt,
            Instant expiresAt,
            Set<String> dependentTables) {
        this.rows = List.copyOf(rows);
        this.columnNames = List.copyOf(columnNames);
        this.columnTypes = List.copyOf(columnTypes);
        this.cachedAt = cachedAt;
        this.expiresAt = expiresAt;
        this.dependentTables = Set.copyOf(dependentTables);
        this.sizeBytes = estimateSize();
    }
    
    private long estimateSize() {
        // Rough estimate: rows * columns * avg 50 bytes per cell
        return (long) rows.size() * columnNames.size() * 50;
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    public boolean isValid() {
        return !isExpired();
    }
    
    // Getters
    public List<List<Object>> getRows() { return rows; }
    public List<String> getColumnNames() { return columnNames; }
    public List<String> getColumnTypes() { return columnTypes; }
    public Instant getCachedAt() { return cachedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Set<String> getDependentTables() { return dependentTables; }
    public long getSizeBytes() { return sizeBytes; }
}
```

**Deliverable:** CachedQueryResult class with tests

---

**Task 1.3: Create Cache Configuration Classes**

Location: `ojp-server/src/main/java/org/openjproxy/cache/CacheConfiguration.java`

```java
package org.openjproxy.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cache configuration for a single datasource.
 * Created from ojp.properties on client-side and sent to server during connection.
 */
public class CacheConfiguration {
    private final boolean enabled;
    private final boolean distribute;  // For future use (Phase 3+)
    private final List<CacheRule> rules;
    
    public CacheConfiguration(boolean enabled, boolean distribute, List<CacheRule> rules) {
        this.enabled = enabled;
        this.distribute = distribute;
        this.rules = new ArrayList<>(rules);
    }
    
    public boolean isEnabled() { return enabled; }
    public boolean isDistribute() { return distribute; }
    public List<CacheRule> getRules() { return rules; }
    
    /**
     * Find matching cache rule for a SQL query.
     * Returns null if no rule matches or caching is disabled.
     */
    public CacheRule findMatchingRule(String sql) {
        if (!enabled) {
            return null;
        }
        
        String normalizedSql = sql.trim().replaceAll("\\s+", " ");
        
        for (CacheRule rule : rules) {
            if (rule.matches(normalizedSql)) {
                return rule;
            }
        }
        
        return null;
    }
    
    public static class CacheRule {
        private final String patternString;
        private final Pattern pattern;
        private final Duration ttl;
        private final List<String> invalidateOn;
        
        public CacheRule(String patternString, Duration ttl, List<String> invalidateOn) {
            this.patternString = patternString;
            this.pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
            this.ttl = ttl;
            this.invalidateOn = invalidateOn != null ? List.copyOf(invalidateOn) : List.of();
        }
        
        public boolean matches(String sql) {
            return pattern.matcher(sql).matches();
        }
        
        public String getPatternString() { return patternString; }
        public Duration getTtl() { return ttl; }
        public List<String> getInvalidateOn() { return invalidateOn; }
    }
}
```

**Deliverable:** Configuration classes with tests

---

**Task 1.4: Create Cache Storage**

Location: `ojp-server/src/main/java/org/openjproxy/cache/QueryResultCache.java`

```java
package org.openjproxy.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local query result cache for a single OJP server.
 * Thread-safe, TTL-based expiration, size-bounded.
 */
public class QueryResultCache {
    private static final Logger logger = LoggerFactory.getLogger(QueryResultCache.class);
    
    private final Cache<QueryCacheKey, CachedQueryResult> cache;
    private final ConcurrentHashMap<String, Set<QueryCacheKey>> tableIndex;
    
    // Metrics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    
    public QueryResultCache(int maxSize, Duration maxAge) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(maxAge)
                .recordStats()
                .evictionListener((key, value, cause) -> {
                    evictions.incrementAndGet();
                    logger.debug("Cache eviction: key={}, cause={}", key, cause);
                })
                .build();
        
        this.tableIndex = new ConcurrentHashMap<>();
    }
    
    /**
     * Get cached result if available and valid.
     */
    public CachedQueryResult get(QueryCacheKey key) {
        CachedQueryResult result = cache.getIfPresent(key);
        
        if (result != null) {
            if (result.isValid()) {
                hits.incrementAndGet();
                logger.debug("Cache HIT: datasource={}, sql={}", 
                    key.getDatasourceName(), 
                    key.getNormalizedSql());
                return result;
            } else {
                // Expired - remove it
                cache.invalidate(key);
                misses.incrementAndGet();
                logger.debug("Cache MISS (expired): datasource={}", key.getDatasourceName());
                return null;
            }
        }
        
        misses.incrementAndGet();
        logger.debug("Cache MISS: datasource={}, sql={}", 
            key.getDatasourceName(), 
            key.getNormalizedSql());
        return null;
    }
    
    /**
     * Put result in cache and index by tables.
     */
    public void put(QueryCacheKey key, CachedQueryResult result) {
        cache.put(key, result);
        
        // Index by tables for invalidation
        for (String table : result.getDependentTables()) {
            tableIndex.computeIfAbsent(table.toUpperCase(), k -> ConcurrentHashMap.newKeySet())
                      .add(key);
        }
        
        logger.debug("Cache PUT: datasource={}, sql={}, ttl={}s, tables={}", 
            key.getDatasourceName(),
            key.getNormalizedSql(),
            Duration.between(result.getCachedAt(), result.getExpiresAt()).getSeconds(),
            result.getDependentTables());
    }
    
    /**
     * Invalidate all cache entries that depend on specified table.
     */
    public void invalidateByTable(String datasourceName, String tableName) {
        String tableKey = tableName.toUpperCase();
        Set<QueryCacheKey> keysToInvalidate = tableIndex.get(tableKey);
        
        if (keysToInvalidate != null) {
            int invalidated = 0;
            for (QueryCacheKey key : keysToInvalidate) {
                // Only invalidate if datasource matches
                if (key.getDatasourceName().equals(datasourceName)) {
                    cache.invalidate(key);
                    invalidated++;
                }
            }
            
            // Clean up the index
            keysToInvalidate.removeIf(key -> key.getDatasourceName().equals(datasourceName));
            
            logger.info("Cache invalidation: datasource={}, table={}, entries={}", 
                datasourceName, tableName, invalidated);
        }
    }
    
    /**
     * Clear all cache entries for a datasource.
     */
    public void clearDatasource(String datasourceName) {
        cache.asMap().keySet().removeIf(key -> 
            key.getDatasourceName().equals(datasourceName));
        
        logger.info("Cache cleared: datasource={}", datasourceName);
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        return new CacheStats(
            hits.get(),
            misses.get(),
            evictions.get(),
            cache.estimatedSize(),
            stats.hitRate()
        );
    }
    
    public static class CacheStats {
        public final long hits;
        public final long misses;
        public final long evictions;
        public final long size;
        public final double hitRate;
        
        public CacheStats(long hits, long misses, long evictions, long size, double hitRate) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.size = size;
            this.hitRate = hitRate;
        }
    }
}
```

**Deliverable:** QueryResultCache class with comprehensive tests

---

#### 14.2.2 Configuration Parsing

**Task 1.5: Parse ojp.properties Cache Configuration**

Location: `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/CacheConfigurationParser.java`

```java
package org.openjproxy.jdbc;

import org.openjproxy.cache.CacheConfiguration;
import org.openjproxy.cache.CacheConfiguration.CacheRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Parses cache configuration from ojp.properties.
 * Configuration format:
 *   {datasource}.ojp.cache.enabled=true
 *   {datasource}.ojp.cache.queries.{N}.pattern=SELECT .* FROM products .*
 *   {datasource}.ojp.cache.queries.{N}.ttl=600s
 *   {datasource}.ojp.cache.queries.{N}.invalidateOn=products,categories
 */
public class CacheConfigurationParser {
    
    public static CacheConfiguration parse(Properties props, String datasourceName) {
        String prefix = datasourceName + ".ojp.cache.";
        
        // Check if cache is enabled
        boolean enabled = Boolean.parseBoolean(
            props.getProperty(prefix + "enabled", "false"));
        
        if (!enabled) {
            return new CacheConfiguration(false, false, List.of());
        }
        
        // Check if distribution is enabled (for future use)
        boolean distribute = Boolean.parseBoolean(
            props.getProperty(prefix + "distribute", "false"));
        
        // Parse cache rules
        List<CacheRule> rules = new ArrayList<>();
        
        for (int i = 1; i <= 100; i++) {  // Support up to 100 rules
            String rulePrefix = prefix + "queries." + i + ".";
            String pattern = props.getProperty(rulePrefix + "pattern");
            
            if (pattern == null) {
                break;  // No more rules
            }
            
            String ttlStr = props.getProperty(rulePrefix + "ttl", "300s");
            Duration ttl = parseDuration(ttlStr);
            
            String invalidateOnStr = props.getProperty(rulePrefix + "invalidateOn", "");
            List<String> invalidateOn = invalidateOnStr.isEmpty() 
                ? List.of() 
                : Arrays.asList(invalidateOnStr.split(","));
            
            rules.add(new CacheRule(pattern, ttl, invalidateOn));
        }
        
        return new CacheConfiguration(enabled, distribute, rules);
    }
    
    private static Duration parseDuration(String str) {
        // Parse formats like "300s", "10m", "1h"
        String value = str.substring(0, str.length() - 1);
        char unit = str.charAt(str.length() - 1);
        
        long amount = Long.parseLong(value);
        
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Invalid duration: " + str);
        };
    }
}
```

**Deliverable:** Parser with unit tests for various configuration formats

---

**Task 1.6: Update Connection Protocol**

Location: `ojp-grpc-contract/src/main/proto/ojp.proto`

Add cache configuration to connection request:

```protobuf
message ConnectRequest {
    string datasource_name = 1;
    map<string, string> properties = 2;
    
    // New: cache configuration for this datasource
    CacheConfigurationProto cache_config = 3;
}

message CacheConfigurationProto {
    bool enabled = 1;
    bool distribute = 2;  // For future use
    repeated CacheRuleProto rules = 3;
}

message CacheRuleProto {
    string pattern = 1;
    int64 ttl_seconds = 2;
    repeated string invalidate_on = 3;
}
```

**Deliverable:** Updated proto file and regenerated gRPC code

---

#### 14.2.3 Session Storage

**Task 1.7: Store Cache Config in Session**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/ServerSession.java`

Update `ServerSession` to store cache configuration:

```java
public class ServerSession {
    private final String sessionId;
    private final String datasourceName;
    private final Connection dbConnection;
    private final CacheConfiguration cacheConfig;  // NEW
    
    public ServerSession(
            String sessionId, 
            String datasourceName,
            Connection dbConnection,
            CacheConfiguration cacheConfig) {
        this.sessionId = sessionId;
        this.datasourceName = datasourceName;
        this.dbConnection = dbConnection;
        this.cacheConfig = cacheConfig;  // NEW
    }
    
    public CacheConfiguration getCacheConfig() {
        return cacheConfig;
    }
    
    // ... existing methods
}
```

**Deliverable:** Updated ServerSession with cache config storage

---

**Task 1.8: Update Connection Handler**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/OjpServiceImpl.java`

Update `connect()` method to handle cache configuration:

```java
@Override
public void connect(ConnectRequest request, StreamObserver<ConnectResponse> responseObserver) {
    try {
        String datasourceName = request.getDatasourceName();
        
        // Parse cache configuration from request
        CacheConfiguration cacheConfig = parseCacheConfig(request.getCacheConfig());
        
        // Create database connection
        Connection dbConnection = createDatabaseConnection(datasourceName, request.getProperties());
        
        // Create session with cache config
        String sessionId = UUID.randomUUID().toString();
        ServerSession session = new ServerSession(
            sessionId, 
            datasourceName, 
            dbConnection,
            cacheConfig);
        
        sessionManager.registerSession(session);
        
        logger.info("Session created: id={}, datasource={}, cacheEnabled={}", 
            sessionId, datasourceName, cacheConfig.isEnabled());
        
        responseObserver.onNext(ConnectResponse.newBuilder()
            .setSessionId(sessionId)
            .build());
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        logger.error("Connection failed", e);
        responseObserver.onError(e);
    }
}

private CacheConfiguration parseCacheConfig(CacheConfigurationProto proto) {
    if (proto == null || !proto.getEnabled()) {
        return new CacheConfiguration(false, false, List.of());
    }
    
    List<CacheConfiguration.CacheRule> rules = new ArrayList<>();
    for (CacheRuleProto ruleProto : proto.getRulesList()) {
        rules.add(new CacheConfiguration.CacheRule(
            ruleProto.getPattern(),
            Duration.ofSeconds(ruleProto.getTtlSeconds()),
            ruleProto.getInvalidateOnList()
        ));
    }
    
    return new CacheConfiguration(
        proto.getEnabled(),
        proto.getDistribute(),
        rules
    );
}
```

**Deliverable:** Updated connection handling with cache config support

---

**Task 1.9: Update JDBC Driver Connection**

Location: `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/OjpConnection.java`

Update driver to send cache configuration during connection:

```java
public Connection connect(String url, Properties info) throws SQLException {
    // Parse connection URL to get datasource name
    String datasourceName = parseDataSourceName(url);
    
    // Parse cache configuration for this datasource
    CacheConfiguration cacheConfig = CacheConfigurationParser.parse(info, datasourceName);
    
    // Convert to proto
    CacheConfigurationProto.Builder configProtoBuilder = CacheConfigurationProto.newBuilder()
        .setEnabled(cacheConfig.isEnabled())
        .setDistribute(cacheConfig.isDistribute());
    
    for (CacheConfiguration.CacheRule rule : cacheConfig.getRules()) {
        configProtoBuilder.addRules(CacheRuleProto.newBuilder()
            .setPattern(rule.getPatternString())
            .setTtlSeconds(rule.getTtl().getSeconds())
            .addAllInvalidateOn(rule.getInvalidateOn())
            .build());
    }
    
    // Connect to OJP server with cache config
    ConnectRequest request = ConnectRequest.newBuilder()
        .setDatasourceName(datasourceName)
        .putAllProperties(convertProperties(info))
        .setCacheConfig(configProtoBuilder.build())  // NEW
        .build();
    
    ConnectResponse response = stub.connect(request);
    
    logger.info("Connected: sessionId={}, cacheEnabled={}", 
        response.getSessionId(), cacheConfig.isEnabled());
    
    return new OjpConnectionImpl(response.getSessionId(), stub, cacheConfig);
}
```

**Deliverable:** Updated JDBC driver with cache config transmission

---

#### 14.2.4 Testing Infrastructure

**Task 1.10: Create Test Fixtures**

Location: `ojp-server/src/test/java/org/openjproxy/cache/CacheTestFixtures.java`

```java
package org.openjproxy.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class CacheTestFixtures {
    
    public static CacheConfiguration createTestConfig() {
        return new CacheConfiguration(
            true,  // enabled
            false, // distribute (not used in Phase 1-2)
            List.of(
                new CacheConfiguration.CacheRule(
                    "SELECT .* FROM products .*",
                    Duration.ofMinutes(10),
                    List.of("products")
                ),
                new CacheConfiguration.CacheRule(
                    "SELECT .* FROM users WHERE id = .*",
                    Duration.ofMinutes(5),
                    List.of("users")
                )
            )
        );
    }
    
    public static QueryCacheKey createTestKey(String datasource, String sql) {
        return new QueryCacheKey(datasource, sql, List.of());
    }
    
    public static CachedQueryResult createTestResult(Set<String> tables, Duration ttl) {
        Instant now = Instant.now();
        return new CachedQueryResult(
            List.of(List.of("value1", "value2")),
            List.of("col1", "col2"),
            List.of("VARCHAR", "VARCHAR"),
            now,
            now.plus(ttl),
            tables
        );
    }
}
```

**Deliverable:** Test fixtures and utilities

---

**Task 1.11: Unit Tests**

Create comprehensive unit tests:

```java
// Test files to create:
// - QueryCacheKeyTest.java - Test key equality, hashing, normalization
// - CachedQueryResultTest.java - Test expiration, size estimation
// - CacheConfigurationTest.java - Test pattern matching, rule ordering
// - CacheConfigurationParserTest.java - Test parsing from properties
// - QueryResultCacheTest.java - Test cache operations, eviction, invalidation
```

**Deliverable:** 5 test classes with >90% code coverage

---

**Estimated Time for Phase 1:** 2 weeks  
**Deliverables:**
- ✅ Core cache data structures (key, entry, config)
- ✅ Cache storage with TTL and eviction
- ✅ Configuration parsing from ojp.properties
- ✅ Updated connection protocol (proto)
- ✅ Session-based cache config storage
- ✅ Comprehensive unit tests (>90% coverage)

**Risk Mitigation:**
- Use Caffeine library (proven, high-performance)
- Extensive unit testing before integration
- Follow existing OJP patterns (session management, proto)

---

### 14.3 Phase 2: Query Execution Integration (Weeks 3-4)

#### 14.3.1 Cache Lookup in Query Execution

**Task 2.1: Integrate Cache into ExecuteQueryAction**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/action/ExecuteQueryAction.java`

Update query execution to check cache first:

```java
public class ExecuteQueryAction implements Action<ExecuteQueryRequest, ExecuteQueryResponse> {
    
    private final QueryResultCache cache;
    
    @Override
    public ExecuteQueryResponse execute(ExecuteQueryRequest request, ServerSession session) {
        String sql = request.getSql();
        List<Object> parameters = request.getParametersList();
        
        // Get cache configuration for this session
        CacheConfiguration cacheConfig = session.getCacheConfig();
        
        // Check if query should be cached
        CacheConfiguration.CacheRule rule = cacheConfig.findMatchingRule(sql);
        
        if (rule != null) {
            // Try to get from cache
            QueryCacheKey cacheKey = new QueryCacheKey(
                session.getDatasourceName(),
                sql,
                parameters
            );
            
            CachedQueryResult cachedResult = cache.get(cacheKey);
            
            if (cachedResult != null) {
                // Cache HIT - return cached result
                logger.debug("Returning cached result: datasource={}, sql={}", 
                    session.getDatasourceName(), sql);
                
                return buildResponseFromCache(cachedResult);
            }
            
            // Cache MISS - execute query and cache result
            logger.debug("Cache miss, executing query: datasource={}, sql={}", 
                session.getDatasourceName(), sql);
            
            ExecuteQueryResponse response = executeQueryOnDatabase(request, session);
            
            // Cache the result
            CachedQueryResult resultToCache = buildCachedResult(
                response,
                rule.getTtl(),
                rule.getInvalidateOn()
            );
            
            cache.put(cacheKey, resultToCache);
            
            return response;
        }
        
        // No cache rule matched - execute normally
        return executeQueryOnDatabase(request, session);
    }
    
    private ExecuteQueryResponse executeQueryOnDatabase(
            ExecuteQueryRequest request, 
            ServerSession session) {
        // Existing query execution logic
        // ...
    }
    
    private ExecuteQueryResponse buildResponseFromCache(CachedQueryResult cached) {
        ExecuteQueryResponse.Builder responseBuilder = ExecuteQueryResponse.newBuilder();
        
        // Add column metadata
        for (int i = 0; i < cached.getColumnNames().size(); i++) {
            responseBuilder.addColumns(ColumnMetadata.newBuilder()
                .setName(cached.getColumnNames().get(i))
                .setType(cached.getColumnTypes().get(i))
                .build());
        }
        
        // Add rows
        for (List<Object> row : cached.getRows()) {
            RowData.Builder rowBuilder = RowData.newBuilder();
            for (Object value : row) {
                rowBuilder.addValues(convertToProtoValue(value));
            }
            responseBuilder.addRows(rowBuilder.build());
        }
        
        return responseBuilder.build();
    }
    
    private CachedQueryResult buildCachedResult(
            ExecuteQueryResponse response,
            Duration ttl,
            List<String> invalidateOn) {
        
        // Extract column names and types
        List<String> columnNames = response.getColumnsList().stream()
            .map(ColumnMetadata::getName)
            .toList();
        
        List<String> columnTypes = response.getColumnsList().stream()
            .map(ColumnMetadata::getType)
            .toList();
        
        // Extract rows
        List<List<Object>> rows = response.getRowsList().stream()
            .map(row -> row.getValuesList().stream()
                .map(this::convertFromProtoValue)
                .toList())
            .toList();
        
        Instant now = Instant.now();
        
        return new CachedQueryResult(
            rows,
            columnNames,
            columnTypes,
            now,
            now.plus(ttl),
            Set.copyOf(invalidateOn)
        );
    }
}
```

**Deliverable:** Updated ExecuteQueryAction with cache integration

---

**Task 2.2: Add Cache Manager to Server**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/OjpServer.java`

Initialize global cache on server startup:

```java
public class OjpServer {
    private final QueryResultCache queryCache;
    
    public OjpServer(ServerConfiguration config) {
        // Initialize cache with configured limits
        int maxCacheSize = config.getInt("ojp.cache.maxSize", 10000);
        Duration maxAge = Duration.ofHours(config.getInt("ojp.cache.maxAgeHours", 24));
        
        this.queryCache = new QueryResultCache(maxCacheSize, maxAge);
        
        logger.info("Query cache initialized: maxSize={}, maxAge={}", 
            maxCacheSize, maxAge);
    }
    
    public QueryResultCache getQueryCache() {
        return queryCache;
    }
}
```

**Deliverable:** Global cache initialization

---

#### 14.3.2 Integration Testing

**Task 2.3: Create Integration Tests**

Location: `ojp-server/src/test/java/org/openjproxy/cache/QueryCacheIntegrationTest.java`

```java
@Test
public void testCacheHitOnRepeatedQuery() {
    // Given: Cache configuration with rule for products table
    CacheConfiguration config = createConfigWithProductsRule();
    
    // When: Execute same query twice
    ExecuteQueryResponse response1 = executeQuery("SELECT * FROM products WHERE id = 1", config);
    ExecuteQueryResponse response2 = executeQuery("SELECT * FROM products WHERE id = 1", config);
    
    // Then: Second query should be faster (cache hit)
    assertThat(response1.getRows()).isEqualTo(response2.getRows());
    assertThat(cacheStats.getHits()).isEqualTo(1);
}

@Test
public void testCacheMissOnDifferentParameters() {
    // Given: Cache configuration
    CacheConfiguration config = createConfigWithProductsRule();
    
    // When: Execute queries with different parameters
    executeQuery("SELECT * FROM products WHERE id = 1", config);
    executeQuery("SELECT * FROM products WHERE id = 2", config);
    
    // Then: Both are cache misses (different parameters)
    assertThat(cacheStats.getMisses()).isEqualTo(2);
}

@Test
public void testCacheExpiration() throws InterruptedException {
    // Given: Cache configuration with short TTL (2 seconds)
    CacheConfiguration config = createConfigWithShortTtl(Duration.ofSeconds(2));
    
    // When: Execute query, wait for expiration, execute again
    executeQuery("SELECT * FROM products WHERE id = 1", config);
    Thread.sleep(3000);  // Wait for expiration
    executeQuery("SELECT * FROM products WHERE id = 1", config);
    
    // Then: Second query is cache miss (expired)
    assertThat(cacheStats.getHits()).isEqualTo(0);
    assertThat(cacheStats.getMisses()).isEqualTo(2);
}

@Test
public void testNoCachingWhenDisabled() {
    // Given: Cache configuration disabled
    CacheConfiguration config = new CacheConfiguration(false, false, List.of());
    
    // When: Execute same query twice
    executeQuery("SELECT * FROM products WHERE id = 1", config);
    executeQuery("SELECT * FROM products WHERE id = 1", config);
    
    // Then: Both are executed (no caching)
    verify(dbConnection, times(2)).prepareStatement(any());
}
```

**Deliverable:** Integration test suite covering cache behavior

---

**Task 2.4: Performance Benchmarks**

Create JMH benchmarks to measure cache performance:

```java
@Benchmark
public void benchmarkCacheHit(Blackhole blackhole) {
    // Measure cache hit performance
    ExecuteQueryResponse response = executeQueryWithCache();
    blackhole.consume(response);
}

@Benchmark
public void benchmarkCacheMiss(Blackhole blackhole) {
    // Measure cache miss + database query performance
    cache.invalidateAll();
    ExecuteQueryResponse response = executeQueryWithCache();
    blackhole.consume(response);
}

@Benchmark
public void benchmarkNoCaching(Blackhole blackhole) {
    // Baseline: no caching
    ExecuteQueryResponse response = executeQueryWithoutCache();
    blackhole.consume(response);
}
```

**Deliverable:** Performance benchmarks showing cache effectiveness

---

**Estimated Time for Phase 2:** 2 weeks  
**Deliverables:**
- ✅ Cache lookup in ExecuteQueryAction
- ✅ Cache miss handling and storage
- ✅ Global cache manager
- ✅ Integration tests
- ✅ Performance benchmarks
- ✅ Documentation

**Success Criteria:**
- Cache hit rate >60% for repeated queries
- Cache hit latency <5ms
- Cache miss overhead <10% vs no caching

---

### 14.4 Phase 3: Write-Through Invalidation (Weeks 5-6)

#### 14.4.1 Table Dependency Analysis

**Task 3.1: Create Table Extractor**

Location: `ojp-server/src/main/java/org/openjproxy/cache/SqlTableExtractor.java`

```java
package org.openjproxy.cache;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts table names from SQL statements using JSqlParser.
 */
public class SqlTableExtractor {
    
    /**
     * Extract tables affected by a DML statement (INSERT, UPDATE, DELETE).
     */
    public Set<String> extractAffectedTables(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            Set<String> tables = new HashSet<>();
            
            if (statement instanceof Insert) {
                Insert insert = (Insert) statement;
                tables.add(insert.getTable().getName());
            } else if (statement instanceof Update) {
                Update update = (Update) statement;
                tables.add(update.getTable().getName());
            } else if (statement instanceof Delete) {
                Delete delete = (Delete) statement;
                tables.add(delete.getTable().getName());
            }
            
            // Normalize to uppercase
            return tables.stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
            
        } catch (Exception e) {
            logger.warn("Failed to parse SQL for table extraction: {}", sql, e);
            return Set.of();
        }
    }
    
    /**
     * Extract tables referenced by a SELECT statement.
     */
    public Set<String> extractReferencedTables(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            List<String> tableList = tablesNamesFinder.getTableList(statement);
            
            // Normalize to uppercase
            return tableList.stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
            
        } catch (Exception e) {
            logger.warn("Failed to parse SQL for table extraction: {}", sql, e);
            return Set.of();
        }
    }
}
```

**Deliverable:** Table extraction utility with tests

---

**Task 3.2: Integrate Invalidation into ExecuteUpdateAction**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/action/ExecuteUpdateAction.java`

```java
public class ExecuteUpdateAction implements Action<ExecuteUpdateRequest, ExecuteUpdateResponse> {
    
    private final QueryResultCache cache;
    private final SqlTableExtractor tableExtractor;
    
    @Override
    public ExecuteUpdateResponse execute(ExecuteUpdateRequest request, ServerSession session) {
        String sql = request.getSql();
        
        // Execute the update
        ExecuteUpdateResponse response = executeUpdateOnDatabase(request, session);
        
        // Extract affected tables
        Set<String> affectedTables = tableExtractor.extractAffectedTables(sql);
        
        // Invalidate cache entries that depend on these tables
        for (String table : affectedTables) {
            cache.invalidateByTable(session.getDatasourceName(), table);
            logger.debug("Cache invalidated: datasource={}, table={}", 
                session.getDatasourceName(), table);
        }
        
        return response;
    }
}
```

**Deliverable:** Automatic cache invalidation on writes

---

**Task 3.3: Add Automatic Table Detection**

Enhance cache rule configuration to support automatic table detection:

```properties
# Manual table specification
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

# Automatic table detection
postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users .*
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=AUTO  # Parse SQL to extract tables
```

Update `CacheConfiguration.CacheRule` to support AUTO:

```java
public class CacheRule {
    private final boolean autoDetectTables;
    
    public CacheRule(String pattern, Duration ttl, List<String> invalidateOn) {
        this.patternString = pattern;
        this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        this.ttl = ttl;
        
        // Check if AUTO mode
        if (invalidateOn.size() == 1 && "AUTO".equals(invalidateOn.get(0))) {
            this.autoDetectTables = true;
            this.invalidateOn = List.of();
        } else {
            this.autoDetectTables = false;
            this.invalidateOn = List.copyOf(invalidateOn);
        }
    }
    
    public boolean isAutoDetectTables() {
        return autoDetectTables;
    }
}
```

Update caching logic to auto-detect tables:

```java
// In ExecuteQueryAction
if (rule.isAutoDetectTables()) {
    // Auto-detect tables from SQL
    Set<String> tables = tableExtractor.extractReferencedTables(sql);
    resultToCache = new CachedQueryResult(..., tables);
} else {
    // Use explicit table list
    resultToCache = new CachedQueryResult(..., Set.copyOf(rule.getInvalidateOn()));
}
```

**Deliverable:** Automatic table detection with fallback to manual specification

---

#### 14.3.2 Testing Invalidation

**Task 3.4: Create Invalidation Tests**

```java
@Test
public void testCacheInvalidationOnInsert() {
    // Given: Cached query result for products table
    executeQuery("SELECT * FROM products WHERE category = 'electronics'", config);
    assertThat(cacheStats.getHits()).isEqualTo(0);
    
    executeQuery("SELECT * FROM products WHERE category = 'electronics'", config);
    assertThat(cacheStats.getHits()).isEqualTo(1);  // Cache hit
    
    // When: Insert new product
    executeUpdate("INSERT INTO products (id, category) VALUES (100, 'electronics')");
    
    // Then: Cache should be invalidated
    executeQuery("SELECT * FROM products WHERE category = 'electronics'", config);
    assertThat(cacheStats.getHits()).isEqualTo(1);  // Still 1 (cache miss after invalidation)
}

@Test
public void testCacheInvalidationOnUpdate() {
    // Test UPDATE invalidates cache
}

@Test
public void testCacheInvalidationOnDelete() {
    // Test DELETE invalidates cache
}

@Test
public void testNoInvalidationOnDifferentTable() {
    // Given: Cached query for products table
    executeQuery("SELECT * FROM products", config);
    executeQuery("SELECT * FROM products", config);
    assertThat(cacheStats.getHits()).isEqualTo(1);
    
    // When: Update different table (users)
    executeUpdate("UPDATE users SET name = 'test' WHERE id = 1");
    
    // Then: Products cache should NOT be invalidated
    executeQuery("SELECT * FROM products", config);
    assertThat(cacheStats.getHits()).isEqualTo(2);  // Still cache hit
}

@Test
public void testDatasourceIsolation() {
    // Given: Two datasources with same table name
    executeQuery("SELECT * FROM products", configForDatasource1);
    executeQuery("SELECT * FROM products", configForDatasource2);
    
    // When: Update table in datasource1
    executeUpdate("UPDATE products SET price = 100", datasource1);
    
    // Then: Only datasource1 cache should be invalidated
    executeQuery("SELECT * FROM products", configForDatasource1);  // Cache miss
    executeQuery("SELECT * FROM products", configForDatasource2);  // Cache hit
}
```

**Deliverable:** Comprehensive invalidation test suite

---

**Estimated Time for Phase 3:** 2 weeks  
**Deliverables:**
- ✅ SQL table extraction utility
- ✅ Cache invalidation on DML operations
- ✅ Automatic table detection (AUTO mode)
- ✅ Datasource isolation in invalidation
- ✅ Comprehensive invalidation tests

**Success Criteria:**
- 100% correct invalidation (no stale data)
- Datasource isolation working properly
- Invalidation overhead <5ms per DML operation

---

### 14.5 Phase 4: Monitoring and Observability (Week 7)

#### 14.5.1 Metrics Integration

**Task 4.1: Add Cache Metrics**

Location: `ojp-server/src/main/java/org/openjproxy/cache/CacheMetrics.java`

```java
package org.openjproxy.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for query result cache.
 */
public class CacheMetrics {
    private final MeterRegistry registry;
    
    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }
    
    public void recordCacheHit(String datasourceName) {
        registry.counter("ojp.cache.hits", 
            Tags.of("datasource", datasourceName)).increment();
    }
    
    public void recordCacheMiss(String datasourceName) {
        registry.counter("ojp.cache.misses", 
            Tags.of("datasource", datasourceName)).increment();
    }
    
    public void recordCacheEviction(String datasourceName) {
        registry.counter("ojp.cache.evictions", 
            Tags.of("datasource", datasourceName)).increment();
    }
    
    public void recordCacheInvalidation(String datasourceName, String tableName, int entriesInvalidated) {
        registry.counter("ojp.cache.invalidations", 
            Tags.of("datasource", datasourceName, "table", tableName))
            .increment(entriesInvalidated);
    }
    
    public void recordCacheSize(String datasourceName, long size) {
        registry.gauge("ojp.cache.size", 
            Tags.of("datasource", datasourceName), size);
    }
    
    public void recordCacheSizeBytes(String datasourceName, long bytes) {
        registry.gauge("ojp.cache.size.bytes", 
            Tags.of("datasource", datasourceName), bytes);
    }
}
```

Integrate into QueryResultCache:

```java
public class QueryResultCache {
    private final CacheMetrics metrics;
    
    public CachedQueryResult get(QueryCacheKey key) {
        CachedQueryResult result = cache.getIfPresent(key);
        
        if (result != null && result.isValid()) {
            metrics.recordCacheHit(key.getDatasourceName());
            return result;
        }
        
        metrics.recordCacheMiss(key.getDatasourceName());
        return null;
    }
    
    public void invalidateByTable(String datasourceName, String tableName) {
        // ... invalidation logic
        metrics.recordCacheInvalidation(datasourceName, tableName, invalidated);
    }
}
```

**Deliverable:** Comprehensive cache metrics

---

**Task 4.2: Add Cache Statistics Endpoint**

Location: `ojp-server/src/main/java/org/openjproxy/grpc/server/action/GetCacheStatsAction.java`

```java
/**
 * gRPC action to retrieve cache statistics.
 * Useful for monitoring and debugging.
 */
public class GetCacheStatsAction implements Action<GetCacheStatsRequest, GetCacheStatsResponse> {
    
    private final QueryResultCache cache;
    
    @Override
    public GetCacheStatsResponse execute(GetCacheStatsRequest request, ServerSession session) {
        QueryResultCache.CacheStats stats = cache.getStats();
        
        return GetCacheStatsResponse.newBuilder()
            .setHits(stats.hits)
            .setMisses(stats.misses)
            .setEvictions(stats.evictions)
            .setSize(stats.size)
            .setHitRate(stats.hitRate)
            .build();
    }
}
```

Add to proto:

```protobuf
message GetCacheStatsRequest {
    string session_id = 1;
}

message GetCacheStatsResponse {
    int64 hits = 1;
    int64 misses = 2;
    int64 evictions = 3;
    int64 size = 4;
    double hit_rate = 5;
}
```

**Deliverable:** Cache statistics API

---

**Task 4.3: Add Logging**

Add structured logging throughout cache operations:

```java
// Cache hits
logger.debug("Cache HIT: datasource={}, sql={}, age={}ms", 
    datasource, sql, ageMillis);

// Cache misses
logger.debug("Cache MISS: datasource={}, sql={}, reason={}", 
    datasource, sql, reason);

// Cache invalidations
logger.info("Cache invalidation: datasource={}, table={}, entries={}, duration={}ms", 
    datasource, table, entriesInvalidated, durationMillis);

// Cache evictions
logger.debug("Cache eviction: datasource={}, sql={}, reason={}", 
    datasource, sql, evictionReason);
```

**Deliverable:** Comprehensive logging for debugging

---

**Estimated Time for Phase 4:** 1 week  
**Deliverables:**
- ✅ Micrometer metrics integration
- ✅ Cache statistics API
- ✅ Structured logging
- ✅ Monitoring dashboard examples

**Success Criteria:**
- Metrics exported to Prometheus/Grafana
- Real-time visibility into cache performance
- Debugging capabilities for cache issues

---

### 14.6 Phase 5: Production Hardening (Weeks 8-9)

#### 14.6.1 Configuration Validation

**Task 5.1: Add Configuration Validation**

Location: `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/CacheConfigurationValidator.java`

```java
public class CacheConfigurationValidator {
    
    public static void validate(CacheConfiguration config) throws IllegalArgumentException {
        if (!config.isEnabled()) {
            return;  // Nothing to validate if disabled
        }
        
        for (CacheConfiguration.CacheRule rule : config.getRules()) {
            validateRule(rule);
        }
    }
    
    private static void validateRule(CacheConfiguration.CacheRule rule) {
        // Validate pattern is valid regex
        try {
            Pattern.compile(rule.getPatternString());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid cache rule pattern: " + rule.getPatternString(), e);
        }
        
        // Validate TTL is reasonable
        long ttlSeconds = rule.getTtl().getSeconds();
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException(
                "Cache TTL must be at least 1 second: " + ttlSeconds);
        }
        if (ttlSeconds > 86400) {  // 24 hours
            logger.warn("Cache TTL very long (>24h): {}s", ttlSeconds);
        }
        
        // Validate table names
        for (String table : rule.getInvalidateOn()) {
            if (!isValidTableName(table) && !"AUTO".equals(table)) {
                throw new IllegalArgumentException(
                    "Invalid table name: " + table);
            }
        }
    }
    
    private static boolean isValidTableName(String name) {
        // Simple validation: alphanumeric, underscore, hyphen
        return name.matches("[a-zA-Z0-9_-]+");
    }
}
```

Integrate validation into driver:

```java
// In OjpConnection.connect()
CacheConfiguration cacheConfig = CacheConfigurationParser.parse(info, datasourceName);

// Validate before sending to server
try {
    CacheConfigurationValidator.validate(cacheConfig);
} catch (IllegalArgumentException e) {
    throw new SQLException("Invalid cache configuration: " + e.getMessage(), e);
}

// Send to server
// ...
```

**Deliverable:** Configuration validation with clear error messages

---

**Task 5.2: Add Connection Property Documentation**

Location: `ojp-jdbc-driver/README.md`

Add documentation for cache properties:

```markdown
### Query Result Caching

OJP supports local query result caching for SELECT statements to improve performance.

#### Configuration

Cache configuration is defined in `ojp.properties` under your datasource:

```properties
# Enable caching for datasource
postgres_prod.ojp.cache.enabled=true

# Optional: Enable distributed caching (default: false)
# Not implemented yet - will be available in future release

# Define cache rules
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE category = .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = .*
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=users,user_profiles
```

#### Pattern Syntax

Patterns use Java regex syntax:
- `.` matches any character
- `.*` matches any sequence of characters
- `\d+` matches one or more digits
- Case-insensitive matching

#### TTL Format

Time-to-live values support:
- `s` - seconds (e.g., `300s`)
- `m` - minutes (e.g., `10m`)
- `h` - hours (e.g., `2h`)
- `d` - days (e.g., `1d`)

#### Table Invalidation

Specify tables that should trigger cache invalidation:
- Explicit list: `invalidateOn=products,categories`
- Auto-detect: `invalidateOn=AUTO` (parses SQL to find tables)

#### Works with ORMs

Cache configuration works seamlessly with:
- Hibernate
- Spring Data JPA
- MyBatis
- jOOQ
- Any framework that uses JDBC

Pattern matching automatically handles ORM-generated SQL.
```

**Deliverable:** User-facing documentation

---

**Task 5.3: Add Error Handling**

Ensure graceful degradation when cache fails:

```java
public CachedQueryResult get(QueryCacheKey key) {
    try {
        CachedQueryResult result = cache.getIfPresent(key);
        // ... normal logic
        return result;
    } catch (Exception e) {
        logger.error("Cache lookup failed, falling back to database: {}", 
            key, e);
        metrics.recordCacheError(key.getDatasourceName());
        return null;  // Fall back to database query
    }
}

public void put(QueryCacheKey key, CachedQueryResult result) {
    try {
        cache.put(key, result);
        // ... normal logic
    } catch (Exception e) {
        logger.error("Cache store failed, continuing without caching: {}", 
            key, e);
        metrics.recordCacheError(key.getDatasourceName());
        // Don't throw - caching is optional
    }
}
```

**Deliverable:** Robust error handling with fallback

---

**Task 5.4: Add Security Considerations**

Implement cache isolation and security:

```java
/**
 * Ensures cache isolation between sessions with different permissions.
 */
public class SecureCacheKey extends QueryCacheKey {
    private final String userId;  // Or session security context
    
    public SecureCacheKey(String datasource, String sql, List<Object> params, String userId) {
        super(datasource, sql, params);
        this.userId = userId;
    }
    
    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) return false;
        SecureCacheKey that = (SecureCacheKey) o;
        return userId.equals(that.userId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), userId);
    }
}
```

Configuration option:

```properties
# Enable user-isolation in cache (default: false for performance)
postgres_prod.ojp.cache.userIsolation=true
```

**Deliverable:** Security-aware caching with isolation options

---

**Estimated Time for Phase 5:** 2 weeks  
**Deliverables:**
- ✅ Configuration validation
- ✅ Error handling and fallback
- ✅ User documentation
- ✅ Security considerations
- ✅ Production-ready code

**Success Criteria:**
- Graceful degradation on cache failures
- Clear error messages for misconfigurations
- Security review passed
- Documentation complete

---

### 14.7 Phase 6: Testing and Deployment (Weeks 10-11)

#### 14.7.1 End-to-End Testing

**Task 6.1: Create E2E Test Suite**

```java
/**
 * End-to-end tests using real OJP server and JDBC driver.
 */
@SpringBootTest
public class CacheE2ETest {
    
    @Test
    public void testCachingWithHibernate() {
        // Test: Hibernate-generated queries are cached
        // Given: Entity with @Cacheable annotation
        // When: Query entity twice
        // Then: Second query uses cache
    }
    
    @Test
    public void testCachingWithSpringDataJPA() {
        // Test: Spring Data JPA queries are cached
        // Given: Repository with cache configuration
        // When: findById() twice
        // Then: Second call uses cache
    }
    
    @Test
    public void testCachingWithMyBatis() {
        // Test: MyBatis queries are cached
    }
    
    @Test
    public void testMultipleDatasources() {
        // Test: Multiple datasources with different cache configs
        // Given: Two datasources (postgres_prod, mysql_analytics)
        // When: Query both
        // Then: Each uses its own cache configuration
    }
    
    @Test
    public void testCacheUnderLoad() {
        // Test: Cache performs well under concurrent load
        // Given: 100 concurrent threads
        // When: Each executes 1000 queries
        // Then: Cache maintains >80% hit rate
    }
}
```

**Deliverable:** Comprehensive E2E test suite

---

**Task 6.2: Performance Testing**

Create performance tests:

```java
@Test
public void testCachePerformanceImprovement() {
    // Baseline: without cache
    disableCache();
    long baselineDuration = measureQueryPerformance(1000);
    
    // With cache
    enableCache();
    long cachedDuration = measureQueryPerformance(1000);
    
    // Verify: At least 5x improvement
    assertThat(cachedDuration).isLessThan(baselineDuration / 5);
}

@Test
public void testCacheMemoryUsage() {
    // Measure memory usage with cache
    enableCache();
    executeQueries(10000);
    long memoryUsed = measureMemory();
    
    // Verify: Memory usage is bounded
    assertThat(memoryUsed).isLessThan(100_000_000);  // 100MB
}
```

**Deliverable:** Performance test suite

---

#### 14.7.2 Documentation

**Task 6.3: Create User Guide**

Location: `documents/QUERY_RESULT_CACHING.md`

```markdown
# Query Result Caching in OJP

## Overview

OJP supports local query result caching to improve performance for frequently executed SELECT queries.

## Quick Start

### 1. Enable Caching

Add to your `ojp.properties`:

```properties
# Enable cache for your datasource
postgres_prod.ojp.cache.enabled=true

# Define cache rules
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products
```

### 2. Restart Your Application

Cache configuration is loaded during application startup.

### 3. Verify Caching

Check logs for cache activity:
```
INFO  Cache HIT: datasource=postgres_prod, sql=SELECT * FROM products WHERE id = 1
INFO  Cache MISS: datasource=postgres_prod, sql=SELECT * FROM products WHERE id = 2
```

Monitor metrics:
```
ojp.cache.hits{datasource="postgres_prod"} = 1523
ojp.cache.misses{datasource="postgres_prod"} = 234
ojp.cache.hit_rate{datasource="postgres_prod"} = 0.867
```

## Configuration Reference

[Detailed configuration options...]

## Best Practices

[Recommendations for cache rules, TTL selection, etc...]

## Troubleshooting

[Common issues and solutions...]
```

**Deliverable:** Complete user guide

---

**Task 6.4: Create Operations Guide**

Location: `documents/CACHE_OPERATIONS.md`

```markdown
# Operating Query Result Cache in Production

## Monitoring

### Key Metrics to Watch

- `ojp.cache.hit_rate` - Should be >60% for effective caching
- `ojp.cache.size` - Should stay below max configured size
- `ojp.cache.evictions` - High eviction rate indicates cache too small
- `ojp.cache.invalidations` - High rate indicates frequent writes

### Alerts

Recommended alerts:
- Cache hit rate drops below 40%
- Cache size exceeds 80% of maximum
- Eviction rate exceeds 10/second

## Tuning

### Adjusting Cache Size

If you see high eviction rates, increase cache size:

```properties
# In ojp-server.yml (applies to all datasources on server)
ojp.cache.maxSize=20000  # Default: 10000
```

### Adjusting TTL

Balance between freshness and cache hit rate:
- Short TTL (60s): Fresh data, lower hit rate
- Medium TTL (600s): Good balance for most cases
- Long TTL (3600s): Maximum performance, use for rarely-changing data

### Pattern Optimization

Make patterns as specific as possible:
- ✅ Good: `SELECT .* FROM products WHERE category = .*`
- ❌ Too broad: `SELECT .*` (caches everything)

## Troubleshooting

[Common issues and solutions...]
```

**Deliverable:** Operations guide for production

---

**Estimated Time for Phase 6:** 2 weeks  
**Deliverables:**
- ✅ E2E test suite with ORMs
- ✅ Performance testing
- ✅ User guide
- ✅ Operations guide
- ✅ Production readiness review

**Success Criteria:**
- All E2E tests passing
- Performance targets met
- Documentation complete
- Production deployment checklist ready

---

### 14.8 Phase 7: Production Deployment (Week 12)

#### 14.8.1 Gradual Rollout

**Task 7.1: Pilot Deployment**

Deploy to a single non-critical application:

```
Week 12, Day 1-2: Pilot
1. Select pilot application (low-risk, high-benefit)
2. Add cache configuration to ojp.properties
3. Deploy pilot application with caching enabled
4. Monitor for 48 hours
5. Verify:
   - No errors or exceptions
   - Cache hit rate >60%
   - Query latency improved
   - No stale data issues
```

**Deliverable:** Pilot deployment with monitoring

---

**Task 7.2: Production Rollout**

Gradual rollout to production:

```
Week 12, Day 3-5: Production Rollout
1. Day 3: Deploy to 10% of applications
2. Day 4: Deploy to 50% of applications
3. Day 5: Deploy to 100% of applications

At each stage:
- Monitor cache metrics
- Check for errors
- Verify performance improvement
- Roll back if issues detected
```

**Deliverable:** Production deployment across all applications

---

**Task 7.3: Post-Deployment Review**

After 1 week in production:

```
Review Checklist:
□ Cache hit rate meets targets (>60%)
□ No stale data incidents
□ Performance improvement measurable
□ Error rate unchanged
□ Memory usage acceptable
□ Team trained on cache operations
```

**Deliverable:** Post-deployment review and lessons learned

---

**Estimated Time for Phase 7:** 1 week  
**Deliverables:**
- ✅ Pilot deployment successful
- ✅ Production rollout complete
- ✅ Post-deployment review
- ✅ Team training

**Success Criteria:**
- Cache running in production without issues
- Measurable performance improvement
- Team confident operating cache

---

### 14.9 Summary Timeline

| Phase | Duration | Focus | Key Deliverables |
|-------|----------|-------|------------------|
| **Phase 1** | Weeks 1-2 | Foundation | Core data structures, config parsing, proto updates |
| **Phase 2** | Weeks 3-4 | Integration | Cache lookup in query execution, cache storage |
| **Phase 3** | Weeks 5-6 | Invalidation | Write-through invalidation, table detection |
| **Phase 4** | Week 7 | Observability | Metrics, statistics API, logging |
| **Phase 5** | Weeks 8-9 | Hardening | Validation, error handling, documentation |
| **Phase 6** | Weeks 10-11 | Testing | E2E tests, performance tests, production prep |
| **Phase 7** | Week 12 | Deployment | Pilot, rollout, review |

**Total Timeline:** 12 weeks (3 months) to production

---

### 14.10 Resource Requirements

#### 14.10.1 Team

**Minimum Team:**
- 1 Senior Java Developer (full-time) - Core implementation
- 1 DevOps Engineer (part-time) - Deployment and monitoring
- 1 QA Engineer (part-time) - Testing
- 1 Tech Lead (oversight) - Code review and architecture decisions

**Recommended Team:**
- 2 Senior Java Developers - Parallel development (driver + server)
- 1 DevOps Engineer (full-time) - Monitoring, deployment
- 1 QA Engineer (full-time) - Comprehensive testing
- 1 Tech Lead - Architecture and oversight

#### 14.10.2 Skills Required

- Strong Java development (collections, concurrency, patterns)
- gRPC and Protocol Buffers
- JDBC driver internals
- SQL parsing (JSqlParser)
- Regex pattern matching
- Micrometer metrics
- Unit and integration testing
- ORM knowledge (Hibernate, Spring Data)

#### 14.10.3 Dependencies

**Required Libraries:**
- `com.github.ben-manes.caffeine:caffeine:3.1.8` - High-performance cache
- `com.github.jsqlparser:jsqlparser:4.7` - SQL parsing for table extraction
- `io.micrometer:micrometer-core:1.16.3` - Metrics (already in OJP)
- JUnit 5 - Testing (already in OJP)
- Mockito - Mocking (already in OJP)

**No new infrastructure required** - uses existing OJP components.

---

### 14.11 Risk Mitigation

#### 14.11.1 Technical Risks

| Risk | Mitigation |
|------|------------|
| **Cache causes stale data** | Write-through invalidation + comprehensive tests |
| **Memory exhaustion** | Bounded cache size, monitoring alerts |
| **Performance regression** | Benchmarks, performance tests, gradual rollout |
| **Pattern matching errors** | Configuration validation, clear error messages |
| **ORM compatibility issues** | E2E tests with all major ORMs |

#### 14.11.2 Operational Risks

| Risk | Mitigation |
|------|------------|
| **Production incidents** | Graceful degradation, rollback plan, pilot deployment |
| **Configuration errors** | Validation, clear documentation, examples |
| **Monitoring blind spots** | Comprehensive metrics, logging, statistics API |
| **Team knowledge gaps** | Documentation, training, runbooks |

---

### 14.12 Success Metrics

**Performance Metrics:**
- ✅ Cache hit rate >60% for repeated queries
- ✅ Cache hit latency <5ms
- ✅ Overall query latency reduced by >30% for cacheable queries
- ✅ Database load reduced by >40% for cacheable queries

**Operational Metrics:**
- ✅ Zero incidents related to stale data
- ✅ Cache memory usage <100MB per OJP server
- ✅ Configuration errors detected before runtime
- ✅ Deployment completed without downtime

**Business Metrics:**
- ✅ Database costs reduced by 20-40%
- ✅ Application response time improved by >30%
- ✅ User satisfaction improved
- ✅ ROI positive within 3 months

---

### 14.13 Future Enhancements (Post Phase 7)

After successful local caching deployment, consider these enhancements:

#### 14.13.1 Distributed Cache Coordination (Phase 8)

**When to implement:**
- Multiple OJP servers deployed
- Shared query patterns across servers
- Database load still high despite local caching

**Approach:** JDBC Driver as Active Relay
- Leverage data already in driver memory
- Distribute cache to other OJP servers
- Smart distribution policy (small results, long TTL)

**Timeline:** 4-6 weeks after Phase 7

#### 14.13.2 Advanced Features (Phase 9+)

Optional enhancements for specific use cases:
- Semantic query analysis with Calcite (query equivalence)
- Cache warming/preloading (predictive caching)
- Query result compression (reduce memory usage)
- Per-user cache isolation (security)
- Cache statistics dashboard (UI)

**Timeline:** As needed based on requirements

---

### 14.14 Decision Points

#### 14.14.1 When to Proceed to Next Phase

Before moving to each phase, verify:

**Before Phase 2:**
- ✅ All Phase 1 tests passing
- ✅ Code review completed
- ✅ Performance benchmarks baseline established

**Before Phase 3:**
- ✅ Phase 2 integration tests passing
- ✅ Cache hit/miss working correctly
- ✅ No performance regressions

**Before Phase 4:**
- ✅ Invalidation tests all passing (100% correctness)
- ✅ No stale data in any test scenario
- ✅ Datasource isolation verified

**Before Phase 5:**
- ✅ Metrics integrated and working
- ✅ Monitoring dashboard created
- ✅ Observability sufficient for debugging

**Before Phase 6:**
- ✅ All hardening tasks complete
- ✅ Security review passed
- ✅ Configuration validation robust

**Before Phase 7:**
- ✅ All E2E tests passing
- ✅ Performance targets met
- ✅ Documentation complete
- ✅ Team trained

#### 14.14.2 When to Implement Distributed Caching

Implement distributed cache (JDBC Driver Relay) if:
- ✅ Local caching successfully deployed
- ✅ Multiple OJP servers in production
- ✅ Cache hit rate on each server is good but overall database load still high
- ✅ Query patterns are shared across servers (same queries on different servers)
- ✅ Team has capacity for additional complexity

**Don't implement distributed caching if:**
- ❌ Local caching not yet stable
- ❌ Single OJP server deployment
- ❌ Queries are unique per server (no benefit from distribution)
- ❌ Database load is acceptable with local caching

---

### 14.15 Checklist for Implementation

#### Phase 1: Foundation
- [ ] Create QueryCacheKey class
- [ ] Create CachedQueryResult class
- [ ] Create CacheConfiguration classes
- [ ] Create QueryResultCache class
- [ ] Create CacheConfigurationParser
- [ ] Update proto file (ConnectRequest)
- [ ] Update ServerSession to store cache config
- [ ] Update connection handler (OjpServiceImpl)
- [ ] Update JDBC driver to send cache config
- [ ] Create test fixtures
- [ ] Write unit tests (>90% coverage)

#### Phase 2: Query Execution Integration
- [ ] Integrate cache into ExecuteQueryAction
- [ ] Implement cache lookup logic
- [ ] Implement cache storage logic
- [ ] Add cache manager to OjpServer
- [ ] Create integration tests
- [ ] Create performance benchmarks

#### Phase 3: Write-Through Invalidation
- [ ] Create SqlTableExtractor utility
- [ ] Integrate invalidation into ExecuteUpdateAction
- [ ] Implement automatic table detection (AUTO mode)
- [ ] Add datasource isolation in invalidation
- [ ] Create invalidation test suite

#### Phase 4: Monitoring and Observability
- [ ] Add Micrometer metrics integration
- [ ] Create cache statistics API
- [ ] Add structured logging
- [ ] Create monitoring dashboard examples

#### Phase 5: Production Hardening
- [ ] Add configuration validation
- [ ] Implement error handling and fallback
- [ ] Create user documentation
- [ ] Add security considerations (user isolation option)
- [ ] Production readiness review

#### Phase 6: Testing and Deployment
- [ ] Create E2E test suite (Hibernate, Spring Data, MyBatis)
- [ ] Create performance test suite
- [ ] Create user guide
- [ ] Create operations guide
- [ ] Production deployment checklist

#### Phase 7: Production Deployment
- [ ] Pilot deployment (single application)
- [ ] Monitor pilot for 48 hours
- [ ] Gradual production rollout (10% → 50% → 100%)
- [ ] Post-deployment review
- [ ] Team training

---

### 14.16 Example Configuration for Common Scenarios

#### 14.16.1 E-Commerce Application

```properties
# ojp.properties for e-commerce app
postgres_prod.ojp.cache.enabled=true

# Product catalog (changes infrequently)
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE category = .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

# Product details (changes infrequently)
postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM products WHERE id = .*
postgres_prod.ojp.cache.queries.2.ttl=600s
postgres_prod.ojp.cache.queries.2.invalidateOn=products

# User profiles (moderate changes)
postgres_prod.ojp.cache.queries.3.pattern=SELECT .* FROM users WHERE id = .*
postgres_prod.ojp.cache.queries.3.ttl=300s
postgres_prod.ojp.cache.queries.3.invalidateOn=users

# Shopping cart items (frequent changes)
# DON'T CACHE - use database directly
```

#### 14.16.2 Analytics Application

```properties
# ojp.properties for analytics app
mysql_analytics.ojp.cache.enabled=true

# Report data (expensive queries, infrequent changes)
mysql_analytics.ojp.cache.queries.1.pattern=SELECT .* FROM report_.*
mysql_analytics.ojp.cache.queries.1.ttl=1800s
mysql_analytics.ojp.cache.queries.1.invalidateOn=AUTO

# Aggregated metrics (very expensive, changes daily)
mysql_analytics.ojp.cache.queries.2.pattern=SELECT .* FROM daily_metrics .*
mysql_analytics.ojp.cache.queries.2.ttl=3600s
mysql_analytics.ojp.cache.queries.2.invalidateOn=daily_metrics

# Dashboard queries (real-time not required)
mysql_analytics.ojp.cache.queries.3.pattern=SELECT COUNT.* FROM .*
mysql_analytics.ojp.cache.queries.3.ttl=600s
mysql_analytics.ojp.cache.queries.3.invalidateOn=AUTO
```

#### 14.16.3 Multi-Tenant SaaS Application

```properties
# ojp.properties for multi-tenant app
postgres_prod.ojp.cache.enabled=true

# Note: Cache keys include parameters, so tenant_id in WHERE clause
# ensures cache isolation between tenants automatically

# Tenant configuration (rarely changes)
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM tenant_config WHERE tenant_id = .*
postgres_prod.ojp.cache.queries.1.ttl=1800s
postgres_prod.ojp.cache.queries.1.invalidateOn=tenant_config

# Tenant users (moderate changes)
postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE tenant_id = .* AND .*
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=users

# Optional: Enable user-level isolation for security
postgres_prod.ojp.cache.userIsolation=true
```

---

### 14.17 Cost-Benefit Analysis

#### 14.17.1 Development Cost

**Total effort:** 12 weeks (3 months)
- 1 Senior Java Developer × 12 weeks = 480 hours
- 1 QA Engineer × 6 weeks = 240 hours  
- 1 DevOps Engineer × 4 weeks = 160 hours
- 1 Tech Lead × 3 weeks (oversight) = 120 hours

**Total:** ~1000 hours (~6 person-months)

#### 14.17.2 Benefits

**Performance Benefits:**
- Query latency reduced by 30-80% for cacheable queries
- Database load reduced by 40-70%
- Application response time improved
- Better user experience

**Cost Benefits:**
- Database costs reduced (fewer queries = lower compute)
- Potential to defer database scaling
- Reduced database licensing costs (for commercial DBs)
- Lower cloud infrastructure costs

**Example ROI:**
```
Scenario: E-commerce application with 1M queries/day
- 60% cache hit rate
- Average query time reduced from 50ms to 5ms
- Database load reduced by 60%

Savings:
- Database instance costs: $500/month → $200/month ($300/month saved)
- Response time improvement: 45ms average (better UX)
- Database scaling deferred: $2000 one-time cost avoided

ROI: Positive within 3-6 months
```

---

### 14.18 Conclusion

This phased implementation plan provides a **clear, actionable roadmap** for implementing local query result caching in OJP. 

**Key Points:**
1. **Start simple**: Local caching only (no distributed coordination yet)
2. **Follow existing patterns**: Client-side configuration in `ojp.properties`
3. **Iterative approach**: Each phase builds on the previous
4. **Production-ready**: Comprehensive testing, monitoring, documentation
5. **Low risk**: Gradual rollout with rollback capability

**Next Steps:**
1. Review and approve this implementation plan
2. Assemble team and allocate resources
3. Begin Phase 1 implementation
4. **After successful local caching**: Consider distributed cache coordination (JDBC Driver Relay)

**Distributed caching (JDBC Driver Relay) is intentionally excluded** from this plan and can be implemented as Phase 8+ after local caching is proven successful in production.

---

### 13.5 Implementation Priority

**Phase 1: Local Caching (Immediate Value)**
- Query result cache with TTL
- Parse cache config from `ojp.properties`
- Send to server during connection
- Store per-session in server

**Phase 2: Write-Through Invalidation (Consistency)**
- Invalidate cache on DML operations
- Table dependency tracking
- Per-session cache isolation

**Phase 3: Distributed Coordination (Multinode)**
- JDBC Driver as Active Relay
- Smart distribution policy
- Cache hit/miss metrics

**Phase 4: Advanced (Optional)**
- Redis integration for very large clusters
- Query result compression
- Cache warming
- Advanced invalidation strategies

---

### 13.6 Key Takeaways

1. **Keep it Simple**: Client-side configuration in `ojp.properties` is simpler than server-side with hot-reload

2. **Follow Existing Patterns**: OJP already does datasource config client-side - cache config should too

3. **Data Already in Memory**: JDBC driver relay makes sense because data is already there

4. **Decoupling is Good**: Each datasource controlling its own cache rules prevents conflicts

5. **Avoid Over-Engineering**: Don't build hot-reload, admin APIs, git-backed config when not needed

6. **Listen to Feedback**: @rrobetti's insight about aligning with existing patterns was the key

---

## 14. References and Further Reading

### OJP Documentation
- [OJP Architecture](documents/ebook/part1-chapter2-architecture.md)
- [Multinode Configuration](documents/multinode/README.md)
- [Per-Endpoint Datasources](documents/multinode/per-endpoint-datasources.md)
- [SQL Enhancer](INVESTIGATION_SQL_ENHANCER.md)

### Apache Calcite
- [Calcite Documentation](https://calcite.apache.org/)
- [SQL Parser](https://calcite.apache.org/docs/reference.html)

### Distributed Caching
- [Cache Consistency Patterns](https://martinfowler.com/bliki/TwoHardThings.html)
- [PostgreSQL LISTEN/NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)

### JDBC Specifications
- [JDBC 4.3 Specification](https://download.oracle.com/otn-pub/jcp/jdbc-4_3-mrel3-spec/jdbc4.3-fr-spec.pdf)

---

## Appendix: Complete Example Configuration

```properties
# ojp.properties - Complete cache configuration example

# Default datasource cache configuration
ojp.cache.enabled=true
ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
ojp.cache.queries.1.ttl=600s
ojp.cache.queries.1.invalidateOn=products,product_categories

ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = ?
ojp.cache.queries.2.ttl=300s
ojp.cache.queries.2.invalidateOn=users

# PostgreSQL production datasource
postgres_prod.ojp.cache.enabled=true
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM orders WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=300s
postgres_prod.ojp.cache.queries.1.invalidateOn=orders

postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM inventory WHERE .*
postgres_prod.ojp.cache.queries.2.ttl=120s
postgres_prod.ojp.cache.queries.2.invalidateOn=inventory

# MySQL analytics datasource (longer TTL for analytics)
mysql_analytics.ojp.cache.enabled=true
mysql_analytics.ojp.cache.queries.1.pattern=SELECT .* FROM report_.*
mysql_analytics.ojp.cache.queries.1.ttl=1800s
mysql_analytics.ojp.cache.queries.1.invalidateOn=report_tables

mysql_analytics.ojp.cache.queries.2.pattern=SELECT .* FROM metrics_.*
mysql_analytics.ojp.cache.queries.2.ttl=3600s
mysql_analytics.ojp.cache.queries.2.invalidateOn=metrics_tables

# Oracle legacy datasource (very long TTL for rarely changing data)
oracle_legacy.ojp.cache.enabled=true
oracle_legacy.ojp.cache.queries.1.pattern=SELECT .* FROM LEGACY_REFERENCE_.*
oracle_legacy.ojp.cache.queries.1.ttl=7200s
oracle_legacy.ojp.cache.queries.1.invalidateOn=LEGACY_TABLES
```

---

**END OF ANALYSIS**
