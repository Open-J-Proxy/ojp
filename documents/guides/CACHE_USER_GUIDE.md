# Query Result Caching - User Guide

First follow the [normal query flow](../designs/EXECUTE_QUERY_FLOW.md); its optional-feature notes explain where a cache hit changes the journey. This guide covers that specialised scenario. Use the [JDBC configuration reference](../configuration/ojp-jdbc-configuration.md) for driver and datasource settings and the [documentation hub](../README.md) for other tasks.

## Overview

OJP query result caching provides automatic caching of SELECT query results to reduce database load and improve query performance. Cache configuration is specified by the client application in the JDBC connection string. This guide covers configuration, usage, monitoring, and troubleshooting.

## Quick Start

Add cache configuration properties to your JDBC connection string:

```java
String url = "jdbc:ojp://localhost:5053/mydb?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*&" +
             "ojp.cache.queries.1.ttl=600s&" +
             "ojp.cache.queries.1.invalidateOn=products";

Connection conn = DriverManager.getConnection(url, user, password);
```

That's it! Queries matching your pattern will now be cached automatically.

## Configuration

### Basic Configuration

Cache configuration is specified via JDBC connection properties:

```properties
# Enable or disable caching
ojp.cache.enabled=true

# Define cache rules (multiple rules supported)
ojp.cache.queries.<N>.pattern=<regex>
ojp.cache.queries.<N>.ttl=<duration>
ojp.cache.queries.<N>.invalidateOn=<table1>,<table2>
ojp.cache.queries.<N>.enabled=true
```

### Pattern Syntax

Patterns use Java regular expressions to match SQL queries:

**Basic patterns:**
```properties
# Match exact query
ojp.cache.queries.1.pattern=SELECT * FROM users WHERE id = \?

# Match any query on products table
ojp.cache.queries.2.pattern=SELECT .* FROM products.*

# Match queries with specific WHERE clause
ojp.cache.queries.3.pattern=SELECT .* FROM orders WHERE status = 'PENDING'

# Match category filter queries
ojp.cache.queries.4.pattern=SELECT .* FROM products WHERE category IN \(.*\)
```

**Advanced patterns:**
```properties
# Match multiple tables
ojp.cache.queries.5.pattern=SELECT .* FROM (products|categories).*

# Match complex queries
ojp.cache.queries.6.pattern=SELECT p\..*,c\..* FROM products p JOIN categories c.*
```

**Tips:**
- Use `.*` to match any characters
- Use `\?` to match parameter placeholders
- Use `\s+` to match whitespace
- Test patterns before deployment
- Keep patterns as specific as possible

### TTL Format

Time-To-Live specifies how long results stay cached:

```properties
# Seconds
ojp.cache.queries.1.ttl=300s    # 5 minutes

# Minutes  
ojp.cache.queries.2.ttl=10m     # 10 minutes

# Hours
ojp.cache.queries.3.ttl=2h      # 2 hours
```

**Guidelines:**
- **Minimum:** 10s (shorter TTLs may cause high database load)
- **Maximum:** 24h (longer TTLs risk serving stale data)
- **Recommended:** 5-30 minutes for most use cases
- **⚠️ Multi-server:** Use 30-60s in clustered deployments (see [Limitations](#limitations))

### Invalidation

> **⚠️ Important:** Invalidation is **optional** and should be used carefully. Constant invalidation defeats the purpose of caching. 
> 
> **Best for:** Reference data that changes infrequently (e.g., countries, currencies, product categories, configuration settings)  
> **Avoid for:** Frequently changing data (e.g., real-time inventory, active user sessions, constantly updated records)

Cache entries are automatically invalidated when tables are modified:

```properties
# Invalidate when products table is updated
ojp.cache.queries.1.invalidateOn=products

# Invalidate when any of these tables are updated
ojp.cache.queries.2.invalidateOn=products,product_prices,product_inventory

# No automatic invalidation (rely only on TTL)
ojp.cache.queries.3.invalidateOn=
```

**How it works:**
- INSERT/UPDATE/DELETE statements are analyzed
- Affected tables are extracted automatically
- Cache entries for those tables are invalidated
- Next query will fetch fresh data from database

## Configuration Examples

### E-commerce Product Catalog

```java
// Product listing (frequently queried, infrequently updated)
String url = "jdbc:ojp://localhost:5053/ecommerce?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE category = \\?&" +
             "ojp.cache.queries.1.ttl=600s&" +
             "ojp.cache.queries.1.invalidateOn=products&" +
             // Product details (very frequently queried)
             "ojp.cache.queries.2.pattern=SELECT .* FROM products WHERE id = \\?&" +
             "ojp.cache.queries.2.ttl=300s&" +
             "ojp.cache.queries.2.invalidateOn=products,product_prices";

Connection conn = DriverManager.getConnection(url, user, password);
```

### User Profile Cache

```java
// User profile lookups
String url = "jdbc:ojp://localhost:5053/userdb?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT .* FROM users WHERE id = \\?&" +
             "ojp.cache.queries.1.ttl=600s&" +
             "ojp.cache.queries.1.invalidateOn=users&" +
             // User permissions (changes less frequently)
             "ojp.cache.queries.2.pattern=SELECT .* FROM user_permissions WHERE user_id = \\?&" +
             "ojp.cache.queries.2.ttl=1800s&" +
             "ojp.cache.queries.2.invalidateOn=user_permissions,users";

Connection conn = DriverManager.getConnection(url, user, password);
```

### Analytics/Reporting

```java
// Dashboard queries (can tolerate slight staleness)
String url = "jdbc:ojp://localhost:5053/analytics?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT COUNT\\(\\*\\) FROM daily_stats WHERE.*&" +
             "ojp.cache.queries.1.ttl=300s&" +
             "ojp.cache.queries.1.invalidateOn=daily_stats&" +
             // Report aggregations (expensive queries, reference data)
             "ojp.cache.queries.2.pattern=SELECT .* FROM country_codes WHERE.*&" +
             "ojp.cache.queries.2.ttl=3600s&" +
             "ojp.cache.queries.2.invalidateOn=country_codes";

Connection conn = DriverManager.getConnection(url, user, password);
```

## Monitoring

### OpenTelemetry Metrics

OJP exports cache metrics via OpenTelemetry:

```properties
# Enable cache metrics (default: true when OpenTelemetry enabled)
ojp.telemetry.cache.metrics.enabled=true
```

**Available metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `ojp.cache.hits` | Counter | Cache hits per datasource |
| `ojp.cache.misses` | Counter | Cache misses per datasource |
| `ojp.cache.evictions` | Counter | Cache evictions (size/TTL) |
| `ojp.cache.invalidations` | Counter | Cache invalidations (writes) |
| `ojp.cache.rejections` | Counter | Rejected entries (too large) |
| `ojp.cache.size.entries` | Gauge | Current cache entry count |
| `ojp.cache.size.bytes` | Gauge | Current cache size in bytes |
| `ojp.query.execution.time` | Histogram | Query execution time |
| `ojp.cache.rejection.size` | Histogram | Size of rejected entries |

### Prometheus Queries

```promql
# Cache hit rate
sum(rate(ojp_cache_hits_total[5m])) / 
  (sum(rate(ojp_cache_hits_total[5m])) + sum(rate(ojp_cache_misses_total[5m])))

# Cache size per datasource
ojp_cache_size_bytes{datasource="postgres_prod"}

# Top cached queries
topk(10, sum by(sql_statement) (ojp_cache_hits_total))
```

### Logging

Enable debug logging to see cache operations:

```properties
# In logback.xml or application.properties
logging.level.org.openjproxy.grpc.server.action.transaction.ExecuteQueryAction=DEBUG
```

**Log messages:**
```
[DEBUG] Cache HIT: datasource=postgres_prod, sql=SELECT * FROM products WHERE id = ?
[DEBUG] Cache MISS: datasource=postgres_prod, sql=SELECT * FROM users WHERE id = ?
[DEBUG] Stored in cache: datasource=postgres_prod, rows=10, size=2KB
[WARN] Cache configuration warning: Rule 1: TTL very short (< 10s)
```

## Troubleshooting

### Cache Not Working

**Symptoms:** All queries show "Cache MISS" in logs

**Possible causes:**

1. **Caching not enabled**
   ```properties
   # Check this is set to true in your JDBC connection string
   ojp.cache.enabled=true
   ```

2. **Pattern doesn't match**
   - Test pattern with your actual SQL
   - SQL is normalized (whitespace collapsed)
   - Pattern is case-sensitive

3. **Rule is disabled**
   ```properties
   # Ensure enabled (or omit - defaults to true) in your JDBC connection string
   ojp.cache.queries.1.enabled=true
   ```

4. **Check validation errors in logs**
   ```
   [ERROR] Cache configuration rejected: Invalid regex pattern
   ```

### Low Cache Hit Rate

**Symptoms:** High ratio of misses to hits

**Possible causes:**

1. **Patterns too specific**
   - Use `.*` instead of exact values
   - Match structure, not data

2. **TTL too short**
   - Increase TTL (e.g., from 60s to 600s)
   - Balance freshness vs performance

3. **Parameter variations**
   - Queries with different parameters are cached separately
   - Consider if all parameter combinations should be cached

4. **High write rate**
   - Frequent UPDATEs invalidate cache
   - May not benefit from caching

### High Memory Usage

**Symptoms:** OJP server using excessive memory

**Solutions:**

1. **Lower TTL values** (entries expire sooner)
2. **More selective patterns** (cache fewer queries)
3. **Exclude large result sets** (queries returning many rows)

### Stale Data

**Symptoms:** Application sees outdated data

**Possible causes:**

1. **TTL too long**
   - Reduce TTL for frequently changing data
   - Consider table update patterns

2. **Missing invalidation tables**
   ```properties
   # Add all tables that affect this query in your JDBC connection string
   ojp.cache.queries.1.invalidateOn=products,product_prices,product_inventory
   ```

3. **External updates**
   - Updates from other applications don't invalidate
   - Local-only caching limitation

### Security Warnings

**Symptoms:** Warnings about suspicious SQL in logs

```
[WARN] Suspicious SQL pattern detected in cache key - potential SQL injection
```

**Action:**
- Review the SQL being executed
- Could indicate SQL injection attempt
- Cache key validation prevents caching potentially malicious queries

## Working with ORMs (Hibernate, Spring Data JPA)

OJP caching works seamlessly with ORMs that generate SQL dynamically. Patterns can be flexible enough to match various column selections and ordering.

### Pattern Flexibility

**What patterns CAN match:**
- ✅ **Column selection** - Different columns or column order: `SELECT id, name FROM users` vs `SELECT name, id FROM users`
- ✅ **WHERE clause variations** - Different predicates: `WHERE id = ?` vs `WHERE status = ?`  
- ✅ **JOIN variations** - Simple to complex joins
- ✅ **Whitespace differences** - Extra spaces, newlines, tabs

**Pattern examples for ORMs:**

```java
// Matches any Hibernate query on users table, regardless of selected columns
String url = "jdbc:ojp://localhost:5053/orm_db?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT\\\\s+.*\\\\s+FROM\\\\s+users\\\\s+WHERE.*&" +
             "ojp.cache.queries.1.ttl=300s&" +
             "ojp.cache.queries.1.invalidateOn=users&" +
             // Match Spring Data JPA repository queries
             "ojp.cache.queries.2.pattern=SELECT\\\\s+.*\\\\s+FROM\\\\s+products\\\\s+WHERE\\\\s+category_id\\\\s*=.*&" +
             "ojp.cache.queries.2.ttl=600s&" +
             "ojp.cache.queries.2.invalidateOn=products";

Connection conn = DriverManager.getConnection(url, user, password);
```

### Important Considerations

1. **Cache key includes full SQL** - Even if pattern is broad, cache key is the exact SQL + parameters
   - `SELECT id FROM users WHERE id=1` ≠ `SELECT name FROM users WHERE id=1` (different cache entries)
   
2. **Parameter handling** - Parameter values are part of cache key
   - `SELECT * FROM products WHERE id=?` with `id=5` ≠ same query with `id=10`

3. **Testing ORM patterns** - Enable SQL logging in your ORM to see generated SQL:
   ```properties
   # Hibernate SQL logging
   spring.jpa.show-sql=true
   spring.jpa.properties.hibernate.format_sql=true
   ```

4. **Pattern complexity** - Balance between matching ORM variations and being specific enough:
   - ❌ Too broad: `SELECT .* FROM .*` (caches everything)
   - ✅ Just right: `SELECT .* FROM products WHERE.*` (specific table, flexible query)

### Example: Spring Data JPA Repository

```java
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCategoryId(Long categoryId);  // Generates: SELECT * FROM products WHERE category_id = ?
    Product findBySkuCode(String skuCode);             // Generates: SELECT * FROM products WHERE sku_code = ?
}
```

**Cache configuration:**
```java
// Single pattern matches both repository methods
String url = "jdbc:ojp://localhost:5053/myapp?" +
             "ojp.cache.enabled=true&" +
             "ojp.cache.queries.1.pattern=SELECT\\\\s+.*\\\\s+FROM\\\\s+products\\\\s+WHERE.*&" +
             "ojp.cache.queries.1.ttl=300s&" +
             "ojp.cache.queries.1.invalidateOn=products";

Connection conn = DriverManager.getConnection(url, user, password);
```

## Best Practices

### Pattern Design

1. **Start broad, narrow down** - Begin with `.*` patterns, refine based on metrics
2. **Match query structure** - Pattern should match SQL structure, not data values
3. **Test before deploying** - Use regex tester to validate patterns
4. **Document your patterns** - Add comments explaining what each pattern matches

### TTL Selection

1. **Consider data freshness requirements** - How stale can data be?
2. **Consider update frequency** - How often does data change?
3. **Start conservative** - Begin with shorter TTLs, increase if safe
4. **Monitor invalidation rate** - High invalidation = shorter effective TTL
5. **⚠️ Multi-server deployments** - Use shorter TTLs (30-60s) in clustered environments since invalidation only affects the local server. See [Limitations](#limitations) section for details.

### Invalidation Configuration

1. **List all dependent tables** - Include all tables that affect query results
2. **Consider join relationships** - Include joined tables
3. **Test invalidation** - Verify cache is cleared on UPDATE

### Monitoring

1. **Track hit rate** - Target >70% for cached queries
2. **Monitor cache size** - Ensure memory usage is acceptable
3. **Watch for warnings** - Address configuration warnings
4. **Set up alerts** - Alert on low hit rate or high memory

## Limitations

### Multi-Server Deployments ⚠️

> **⚠️ IMPORTANT:** In the current v0.5.0-beta implementation, **each OJP server maintains its own independent local cache**. This has important implications for multi-server deployments:

**Current Behavior:**
- When an UPDATE/INSERT/DELETE statement is executed on **Server A**, only **Server A's cache** is invalidated
- **Servers B, C, D** retain their cached entries until TTL expires naturally
- This can result in different servers serving different data temporarily

**Example Scenario:**
1. Client updates a product name via Server A → Server A invalidates its cache for that product ✅
2. Another client queries the same product via Server B → Server B returns **stale cached data** until TTL expires ⚠️

**Recommendations for Multi-Server Environments:**
- Use **shorter TTLs** (30-60 seconds) to limit the staleness window
- Apply caching only to **reference data** that changes very infrequently (countries, currencies, categories)
- Consider the acceptable staleness for your use case when choosing TTL values
- Monitor cache hit rates and adjust TTL accordingly

**Future Enhancement:**
Distributed cache synchronization across OJP server nodes is currently **under discussion** for a future release. This would enable real-time invalidation propagation across all servers in a cluster.

### Current Implementation (Local-Only)

- **Per-server caching** - Each OJP server has independent cache
- **Write invalidation** - Only local server cache is invalidated  
- **No cluster coordination** - Caches not shared between servers
- **Local memory only** - No external cache infrastructure required

### Future Enhancements (Under Discussion)

- **Distributed caching** - Share cache across OJP servers
- **Write-through propagation** - Invalidate caches on all servers in real-time
- **Advanced analytics** - Per-query hit rates and performance metrics
- **Cache warming** - Pre-populate cache on startup

## Advanced Topics

### Cache Warming

Pre-populate cache on server start:

```sql
-- Execute common queries after deployment
SELECT * FROM products WHERE category = 'electronics';
SELECT * FROM products WHERE category = 'clothing';
```

### Multi-Tenant Configuration

Separate cache configuration per tenant datasource:

```java
// Tenant 1 connection
String tenant1Url = "jdbc:ojp://localhost:5053/tenant1?" +
                    "ojp.cache.enabled=true&" +
                    "ojp.cache.queries.1.pattern=SELECT .* FROM products.*&" +
                    "ojp.cache.queries.1.ttl=600s";

Connection tenant1Conn = DriverManager.getConnection(tenant1Url, user, password);

// Tenant 2 connection with different TTL
String tenant2Url = "jdbc:ojp://localhost:5053/tenant2?" +
                    "ojp.cache.enabled=true&" +
                    "ojp.cache.queries.1.pattern=SELECT .* FROM products.*&" +
                    "ojp.cache.queries.1.ttl=300s";

Connection tenant2Conn = DriverManager.getConnection(tenant2Url, user, password);
```

### Performance Tuning

1. **Optimize pattern matching** - Simpler patterns match faster
2. **Limit result set size** - Large results take longer to cache
3. **Balance cache size** - More cache = more memory but fewer misses
4. **Monitor overhead** - Caching adds ~5-20ms per query

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- eBook Documentation: [documents/ebook/README.md](../ebook/README.md)

## Version

This guide is for OJP v0.5.0-beta with complete query result caching implementation.
