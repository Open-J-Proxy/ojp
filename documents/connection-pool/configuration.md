# Connection Pool Configuration Reference

This document provides a complete reference for configuring connection pools in OJP.

## Overview

OJP uses a connection pool abstraction layer that allows switching between different pool implementations. The configuration properties have always been `ojp.` prefixed, following the OJP naming convention.

## Server Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OJP_DATASOURCE_PROVIDER` | Default provider ID | hikari |

### System Properties

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.datasource.provider` | Default provider ID | hikari |

## Client Configuration (ojp.properties)

Add these properties to your `ojp.properties` file (or environment-specific files like `ojp-dev.properties`, `ojp-staging.properties`, `ojp-prod.properties`):

```properties
# Select Apache Commons DBCP2 instead of the default HikariCP provider
ojp.datasource.provider=dbcp

# Pool sizing
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5

# Timeouts (milliseconds)
ojp.connection.pool.connectionTimeout=10000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

**For environment-specific configuration**, see:
- **[OJP JDBC Configuration](../configuration/ojp-jdbc-configuration.md)** - Complete guide to environment-specific properties files (introduced in PR #298)

### Per-Datasource Configuration

For named datasources, prefix the properties with the datasource name. The name is inferred from the prefix itself — no separate `name` property is needed:

```properties
# Default datasource settings (no prefix)
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5

# Named datasource "mainApp" — name is inferred from the prefix
mainApp.ojp.connection.pool.maximumPoolSize=30
mainApp.ojp.connection.pool.minimumIdle=10

# Named datasource "batchJob" — name is inferred from the prefix
batchJob.ojp.connection.pool.maximumPoolSize=5
batchJob.ojp.connection.pool.minimumIdle=1
```

The datasource name used in the JDBC URL must match the prefix used in the properties file:
```
jdbc:ojp[localhost:1059(mainApp)]_postgresql://db:5432/mydb
```

## Property Reference

### OJP Connection Pool Properties

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.connection.pool.maximumPoolSize` | Maximum pool size | 20 |
| `ojp.connection.pool.minimumIdle` | Minimum idle connections | 5 |
| `ojp.connection.pool.connectionTimeout` | Admission wait budget (ms); backend pool acquisition is fail-fast | 10000 |
| `ojp.connection.pool.idleTimeout` | Idle timeout (ms) | 600000 |
| `ojp.connection.pool.maxLifetime` | Max connection lifetime (ms) | 1800000 |
| `ojp.connection.pool.defaultTransactionIsolation` | Default transaction isolation level | READ_COMMITTED |

### Predictable-Latency Sizing

For latency-sensitive workloads, set `minimumIdle` equal to `maximumPoolSize`
when the database connection budget permits:

```properties
ojp.connection.pool.maximumPoolSize=50
ojp.connection.pool.minimumIdle=50
```

OJP applies admission control before borrowing a database connection. After
admission, pool acquisition is fail-fast. With HikariCP, OJP's internal
acquisition timeout is effectively 250 ms: OJP sets 1 ms, and HikariCP enforces
a 250 ms minimum. If `minimumIdle` is lower than `maximumPoolSize`, HikariCP
may need to create physical connections as demand grows; slow connection
creation can cause acquisition failures before the configured maximum is
reached, especially at cold start or during a sudden spike.

Equal sizes encourage the pool to maintain its full capacity. Once connections
are available, this makes acquisition latency more predictable and reduces
connection creation during spikes. It does not make all connections ready
immediately. The trade-off is more idle database connections, higher baseline
resource use, and potentially slower initialization. In multinode
deployments, include the per-node pool capacity in the database connection
budget. This is a workload-specific recommendation, not a universal setting.

### Transaction Isolation Configuration

The `defaultTransactionIsolation` property configures how connections are reset when returned to the pool:

```properties
# Default is READ_COMMITTED
# No configuration needed unless you want a different isolation level

# Explicit configuration (string names - recommended)
ojp.connection.pool.defaultTransactionIsolation=READ_COMMITTED
ojp.connection.pool.defaultTransactionIsolation=SERIALIZABLE

# Using JDBC constant names
ojp.connection.pool.defaultTransactionIsolation=TRANSACTION_READ_COMMITTED

# For XA connections
ojp.xa.connection.pool.defaultTransactionIsolation=SERIALIZABLE
```

**Supported Values:**
- String names: `READ_COMMITTED`, `SERIALIZABLE`, `READ_UNCOMMITTED`, `REPEATABLE_READ`, `NONE`
- JDBC constant names: `TRANSACTION_READ_COMMITTED`, `TRANSACTION_SERIALIZABLE`, etc.

**Behavior:**
- When configured: All connections reset to this isolation level when returned to pool
- When not configured: Defaults to READ_COMMITTED for all connections
- Optimization: Only resets if isolation level was actually changed during session

### Provider-Specific Properties

Pass provider-specific properties using the prefix `ojp.datasource.properties.*`:

```properties
# DBCP-specific properties
ojp.datasource.properties.testOnBorrow=true
ojp.datasource.properties.testWhileIdle=true
ojp.datasource.properties.numTestsPerEvictionRun=3
```

## DBCP Provider Configuration

The DBCP provider (`ojp-datasource-dbcp`) maps `PoolConfig` to DBCP `BasicDataSource`:

| PoolConfig Field | DBCP Property |
|------------------|---------------|
| `url` | `url` |
| `username` | `username` |
| `password` | `password` |
| `driverClassName` | `driverClassName` |
| `maxPoolSize` | `maxTotal` |
| `minIdle` | `minIdle` |
| `connectionTimeoutMs` | internal fail-fast timeout (`maxWait` in DBCP / `connectionTimeout` in Hikari); OJP's server sets this to 1 ms |
| `idleTimeoutMs` | `minEvictableIdleTimeMillis` |
| `maxLifetimeMs` | `maxConnLifetimeMillis` |
| `validationQuery` | `validationQuery` |
| `autoCommit` | `defaultAutoCommit` |

### DBCP-Specific Settings

The DBCP provider automatically configures:

- `maxIdle` = `maxPoolSize` (connections won't be evicted below this)
- `testOnBorrow` = true (when validationQuery is set)
- `testWhileIdle` = true (when validationQuery is set)
- `timeBetweenEvictionRunsMillis` = 30000 (30 seconds)
- `numTestsPerEvictionRun` = 3

## Default Values

These are the default values defined in `CommonConstants`:

| Setting | Default |
|---------|---------|
| `maximumPoolSize` | 20 |
| `minimumIdle` | 5 |
| `connectionTimeout` | 10000 (10 seconds) |
| `idleTimeout` | 600000 (10 minutes) |
| `maxLifetime` | 1800000 (30 minutes) |

## Examples

### High-Performance Configuration

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("secret")
    .maxPoolSize(50)
    .minIdle(10)
    .connectionTimeoutMs(5000)  // Fail fast
    .validationQuery("SELECT 1")
    .property("cachePrepStmts", "true")
    .property("prepStmtCacheSize", "500")
    .build();
```

### Read-Replica Configuration

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://replica.example.com:5432/mydb")
    .username("readonly")
    .password("secret")
    .maxPoolSize(20)
    .minIdle(2)
    .idleTimeoutMs(60000)  // Shorter idle timeout for read replicas
    .autoCommit(true)      // Read-only queries
    .build();
```

### Connection Pool with Secret Manager

```java
// AWS Secrets Manager example
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://prod.example.com:5432/mydb")
    .username("app_user")
    .passwordSupplier(() -> {
        // Retrieve password from AWS Secrets Manager
        return awsSecretsManager.getSecretString("prod/db/password").toCharArray();
    })
    .maxPoolSize(30)
    .minIdle(5)
    .build();
```
