# OJP JDBC Driver Configuration Guide

This document covers configuration options for the OJP JDBC driver, including client-side connection pool settings with multi-datasource support.

## Overview

The OJP JDBC driver supports configurable connection pool settings via an `ojp.properties` file with advanced multi-datasource capabilities. This allows customization of HikariCP connection pool behavior on a per-client basis with support for multiple named datasources for enhanced flexibility.

## Connection Close Behavior

When application code calls `Connection.close()`, the OJP JDBC driver performs session termination **synchronously by default**.

That means the close call blocks until the server-side session has been terminated.

If you want asynchronous behavior (close returns immediately; termination runs in the background), set:

```properties
ojp.jdbc.connection.close.synchronous=false
```

This property also supports datasource prefixes:

```properties
myApp.ojp.jdbc.connection.close.synchronous=false
```

### Retry Rules

The driver retries session termination **up to 3 times** only when the failure is clearly connection-related, for example:

- gRPC `UNAVAILABLE`
- gRPC `DEADLINE_EXCEEDED`
- equivalent transport/connectivity failures

The driver does **not** retry when the failure is not transient connectivity, for example:

- pool or session not found (`NOT_FOUND`)
- server-side `SQLException` / database error
- other non-connection failures

### Why This Matters

This keeps close-path latency low by default while still allowing strict blocking behavior when required.

With synchronous close (default), failures are surfaced to the caller.
With asynchronous close (`ojp.jdbc.connection.close.synchronous=false`), termination failures are logged.

## Multi-DataSource Configuration

### Flexibility and Operational Benefits

The multi-datasource configuration approach provides several operational benefits:

- **Configuration Isolation**: Different applications or components can have their own pool configurations without interfering with each other
- **Operational Flexibility**: Different datasources can point to the same database with different pool settings optimized for their use case
- **Resource Management**: Fine-grained control over connection pool resources per application component

### Configuration Format

#### Named DataSource Configuration

Configure pool settings for specific datasources using the format `{dataSourceName}.ojp.connection.pool.*`:

```properties
# Main application datasource - high concurrency
mainApp.ojp.connection.pool.maximumPoolSize=50
mainApp.ojp.connection.pool.minimumIdle=10
mainApp.ojp.connection.pool.connectionTimeout=15000

# Read-only reporting datasource - smaller pool
reporting.ojp.connection.pool.maximumPoolSize=8
reporting.ojp.connection.pool.minimumIdle=2
reporting.ojp.connection.pool.idleTimeout=300000

# Batch processing datasource - medium pool with longer timeouts
batchJob.ojp.connection.pool.maximumPoolSize=15
batchJob.ojp.connection.pool.minimumIdle=3
batchJob.ojp.connection.pool.maxLifetime=1800000
```

#### Default DataSource Configuration (Backward Compatibility)

For backward compatibility, properties without a datasource prefix are treated as the "default" datasource:

```properties
# Default datasource configuration (backward compatible)
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=600000
```
#### Default Maximum Inbound Message Size Configuration 

```properties
# Default Maximum Inbound Message Size Configuration 
ojp.grpc.maxInboundMessageSize=16777216  
```

### How to Use DataSources

#### Specifying DataSource in JDBC URL

Include the dataSource name in parentheses within the OJP connection section to specify which datasource configuration to use:

```java
// Use the mainApp datasource configuration
String url = "jdbc:ojp[localhost:1059(mainApp)]_postgresql://user@localhost/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");

// Use the reporting datasource configuration  
String reportingUrl = "jdbc:ojp[localhost:1059(reporting)]_postgresql://user@localhost/mydb";
Connection reportingConn = DriverManager.getConnection(reportingUrl, "user", "password");

// Use default configuration (no dataSource parameter)
String defaultUrl = "jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb";
Connection defaultConn = DriverManager.getConnection(defaultUrl, "user", "password");
```

#### Multiple DataSources for Same Database

Multiple datasources can point to the same database connection parameters while using different pool configurations:

```properties
# Primary application pool - high capacity
primary.ojp.connection.pool.maximumPoolSize=40
primary.ojp.connection.pool.minimumIdle=8

# Background tasks pool - smaller capacity
background.ojp.connection.pool.maximumPoolSize=10
background.ojp.connection.pool.minimumIdle=2
```

Both can connect to the same database:
```java
// Same database, different pool configurations
Connection primaryConn = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059(primary)]_postgres:mydb", "user", "pass");
Connection backgroundConn = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059(background)]_postgres:mydb", "user", "pass");
```

## Client-Side Connection Pool Configuration

### How to Configure

1. Create an `ojp.properties` file in your application's classpath (either in the root or in the `resources` folder)
2. Add any of the supported properties (all are optional)
3. Use either named datasource configuration or default configuration format
4. The driver will automatically load and send these properties to the server when establishing a connection

### Environment-Specific Configuration

OJP supports environment-specific properties files, allowing you to maintain separate configurations for different environments (development, staging, production, etc.) **without requiring any custom code**.

#### Naming Convention

Use the following naming pattern for environment-specific properties files:

- `ojp-dev.properties` - Development environment
- `ojp-staging.properties` - Staging environment
- `ojp-prod.properties` - Production environment
- `ojp-test.properties` - Testing environment
- `ojp-{environment}.properties` - Any custom environment name

#### Environment Selection

The environment is automatically determined by (in order of precedence):

1. **System property**: `-Dojp.environment=dev`
2. **Environment variable**: `OJP_ENVIRONMENT=dev`

If no environment is specified, OJP loads the default `ojp.properties` file.

#### Fallback Behavior

- If an environment is specified but the environment-specific file doesn't exist, OJP automatically falls back to `ojp.properties`
- This ensures backward compatibility and provides a safe default configuration

#### Configuration Examples

**Development Environment (`ojp-dev.properties`):**
```properties
# Development configuration - smaller pools, more logging
ojp.connection.pool.maximumPoolSize=10
ojp.connection.pool.minimumIdle=2
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=300000

# XA settings for development
ojp.xa.connection.pool.maxTotal=5
ojp.xa.connection.pool.minIdle=1
```

**Staging Environment (`ojp-staging.properties`):**
```properties
# Staging configuration - moderate pools, production-like
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=20000
ojp.connection.pool.idleTimeout=600000

# XA settings for staging
ojp.xa.connection.pool.maxTotal=15
ojp.xa.connection.pool.minIdle=3
```

**Production Environment (`ojp-prod.properties`):**
```properties
# Production configuration - optimized for high load
ojp.connection.pool.maximumPoolSize=50
ojp.connection.pool.minimumIdle=10
ojp.connection.pool.connectionTimeout=15000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000

# XA settings for production
ojp.xa.connection.pool.maxTotal=40
ojp.xa.connection.pool.minIdle=8
ojp.xa.connection.pool.connectionTimeout=25000

# Production-specific datasources
webapp.ojp.connection.pool.maximumPoolSize=60
webapp.ojp.connection.pool.minimumIdle=15

api.ojp.connection.pool.maximumPoolSize=40
api.ojp.connection.pool.minimumIdle=10
```

#### Running with Environment-Specific Configuration

**Using System Property:**
```bash
# Development
java -Dojp.environment=dev -jar myapp.jar

# Staging
java -Dojp.environment=staging -jar myapp.jar

# Production
java -Dojp.environment=prod -jar myapp.jar
```

**Using Environment Variable:**
```bash
# Development
export OJP_ENVIRONMENT=dev
java -jar myapp.jar

# Staging
export OJP_ENVIRONMENT=staging
java -jar myapp.jar

# Production
export OJP_ENVIRONMENT=prod
java -jar myapp.jar
```

**Docker Deployment:**
```yaml
# docker-compose.yml
services:
  app-dev:
    image: myapp:latest
    environment:
      - OJP_ENVIRONMENT=dev
  
  app-prod:
    image: myapp:latest
    environment:
      - OJP_ENVIRONMENT=prod
```

**Kubernetes Deployment:**
```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp
spec:
  template:
    spec:
      containers:
      - name: myapp
        image: myapp:latest
        env:
        - name: OJP_ENVIRONMENT
          value: "prod"
```

#### Benefits

- **No Custom Code Required**: Simply specify the environment and OJP automatically loads the correct configuration
- **Single Application Package**: Deploy the same JAR/WAR to all environments with all configuration files included
- **Clear Separation**: Each environment has its own clearly named configuration file
- **Easy Testing**: Test different environment configurations locally by changing the environment variable
- **Safe Defaults**: Falls back to `ojp.properties` if environment-specific file is missing
- **Override Support**: Environment variables and system properties can still override file properties

#### Example Configuration Files

Complete example configuration files for different environments are available:

- **[ojp-dev.properties](ojp-dev.properties)** - Development environment configuration
- **[ojp-staging.properties](ojp-staging.properties)** - Staging environment configuration
- **[ojp-prod.properties](ojp-prod.properties)** - Production environment configuration

These examples demonstrate recommended settings for each environment and can be used as starting templates.

### Connection Pool Properties

| Property                              | Type | Default | Description                                              | Since |
|---------------------------------------|------|---------|----------------------------------------------------------|-------|
| `ojp.connection.pool.enabled`         | boolean | true | Enable/disable connection pooling | 0.2.0-beta |
| `ojp.connection.pool.maximumPoolSize` | int  | 20      | Maximum number of connections in the pool                | 0.2.0-beta |
| `ojp.connection.pool.minimumIdle`     | int  | 5       | Minimum number of idle connections maintained            | 0.2.0-beta |
| `ojp.connection.pool.idleTimeout`     | long | 600000  | Maximum time (ms) a connection can sit idle (10 minutes) | 0.2.0-beta |
| `ojp.connection.pool.maxLifetime`     | long | 1800000 | Maximum lifetime (ms) of a connection (30 minutes)       | 0.2.0-beta |
| `ojp.connection.pool.connectionTimeout` | long | 10000   | Semaphore admission wait budget (ms) for non-XA requests; pool acquisition is fail-fast  | 0.2.0-beta |
| `ojp.connection.pool.defaultTransactionIsolation` | string/int | READ_COMMITTED | Default transaction isolation level (see below) | 0.2.0-beta |

**Note**: These properties can be used with or without a datasource name prefix. For example:
- `ojp.connection.pool.maximumPoolSize=20` (default datasource)
- `myApp.ojp.connection.pool.maximumPoolSize=50` (myApp datasource)

### JDBC Client Properties

| Property | Type | Default | Description | Since |
|----------|------|---------|-------------|-------|
| `ojp.jdbc.connection.close.synchronous` | boolean | true | Controls `Connection.close()` behavior. `true` = close waits for terminate-session RPC (default), `false` = async close. | 0.4.2-beta |
| `ojp.jdbc.clientThrottle.mode` | string | `reactive` | Client-side throttling mode. Controls how the driver limits concurrent in-flight requests per application instance. `off` = disabled, `proactive` = static fair-share only, `reactive` = adaptive `observedPeak` only (default — delivers the most adaptive performance for most workloads), `combined` = min(proactive, reactive). For workloads that cannot tolerate any bursts, use `combined` or `proactive`. See [Chapter 8a: Client-Side Throttling](../ebook/part3-chapter8a-client-throttling.md). | 0.5.0-beta |
| `ojp.jdbc.clientThrottle.reactiveDecreaseFactor` | double | `0.75` | Reactive-mode multiplicative back-off factor after overload (`RESOURCE_EXHAUSTED`). Lower values back off more aggressively. Use `0.5` or lower when you need stronger overload protection and can tolerate stricter throughput reduction. Valid range: `0 < factor < 1`. | 0.5.1-SNAPSHOT |

### Programmatic Configuration via `DriverManager.getConnection()`

In addition to `ojp.properties` files and system properties, you can supply pool configuration
**directly in the `Properties` object** passed to `DriverManager.getConnection(url, info)`.
Any `ojp.connection.pool.*` or `ojp.xa.*` key present in `info` is merged on top of the
file-based configuration and takes the **highest precedence**.

```java
Properties info = new Properties();
info.setProperty("user", "alice");
info.setProperty("password", "secret");

// These override any matching value in ojp.properties / system properties / env vars
info.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(allocatedMaxConnections));
info.setProperty("ojp.connection.pool.minimumIdle", "3");

Connection conn = DriverManager.getConnection(
    "jdbc:ojp[ojp-server:1059]_postgresql://db:5432/mydb", info);
```

**Property precedence** (highest to lowest):

| Priority | Source | Example |
|----------|--------|---------|
| 1 | `info` argument to `getConnection()` | `info.setProperty("ojp.connection.pool.maximumPoolSize", "50")` |
| 2 | Environment variables | `OJP_CONNECTION_POOL_MAXIMUMPOOLSIZE=50` |
| 3 | System properties | `-Dojp.connection.pool.maximumPoolSize=50` |
| 4 | Properties file (`ojp.properties`) | `ojp.connection.pool.maximumPoolSize=50` |

Only `ojp.connection.pool.*`, `ojp.xa.*`, and `ojp.jdbc.*` keys are read from `info`.
JDBC-standard keys such as `user` and `password` are extracted separately and are never
treated as pool configuration.

### Transaction Isolation Configuration

OJP automatically resets transaction isolation levels when connections are returned to the pool, preventing state pollution between different client sessions.

#### Behavior

By default, OJP uses **READ_COMMITTED** as the default transaction isolation level. You can override this by explicitly configuring a different isolation level.

#### Configuration Property

| Property                              | Type | Default | Description                                              | Since |
|---------------------------------------|------|---------|----------------------------------------------------------|-------|
| `ojp.connection.pool.defaultTransactionIsolation` | string/int | READ_COMMITTED | Transaction isolation level to reset connections to | 0.2.0-beta |
| `ojp.xa.connection.pool.defaultTransactionIsolation` | string/int | READ_COMMITTED | Transaction isolation level for XA connections | 0.2.0-beta |

#### Valid Values

The property accepts multiple formats (case-insensitive):

| String Name | Constant Name | Description |
|-------------|---------------|-------------|
| `NONE` | `TRANSACTION_NONE` | Transactions not supported |
| `READ_UNCOMMITTED` | `TRANSACTION_READ_UNCOMMITTED` | Lowest isolation - dirty reads allowed |
| `READ_COMMITTED` | `TRANSACTION_READ_COMMITTED` | Most common - prevents dirty reads |
| `REPEATABLE_READ` | `TRANSACTION_REPEATABLE_READ` | Prevents non-repeatable reads |
| `SERIALIZABLE` | `TRANSACTION_SERIALIZABLE` | Highest isolation - fully isolated |

#### Configuration Examples

```properties
# Using string name (recommended - most readable)
ojp.connection.pool.defaultTransactionIsolation=READ_COMMITTED

# Using constant name
ojp.connection.pool.defaultTransactionIsolation=TRANSACTION_SERIALIZABLE

# For XA connections
ojp.xa.connection.pool.defaultTransactionIsolation=SERIALIZABLE

# Per-datasource configuration
mainApp.ojp.connection.pool.defaultTransactionIsolation=SERIALIZABLE
reporting.ojp.connection.pool.defaultTransactionIsolation=READ_COMMITTED
```

#### When to Configure

Configure a custom isolation level when:
- You want all connections to use a specific isolation level regardless of database default
- Different applications sharing the same database need different isolation guarantees
- You want to enforce a stricter or more relaxed isolation policy

#### How It Works

1. **Default (READ_COMMITTED)**: OJP uses READ_COMMITTED isolation level by default for all connections
2. **Configured**: When set, OJP uses the specified isolation level instead of the default
3. **Connection Reset**: When connections return to the pool, they are automatically reset to the configured isolation level (or READ_COMMITTED if not configured)
4. **Optimization**: The connection pool only resets isolation if it was actually changed during the session

**Note**: See `documents/analysis/TRANSACTION_ISOLATION_HANDLING.md` for complete technical documentation.

### Disabling Connection Pooling

Both Non-XA and XA connection pooling can be disabled independently using configuration properties. This is useful for:
- **Development and Testing**: Simplify debugging by removing pool complexity
- **Low-Frequency Applications**: When connection reuse overhead exceeds benefits
- **Diagnostic Mode**: Isolate issues related to connection pooling
- **Single-Threaded Applications**: Where connection pooling provides no benefit

#### Non-XA Pool Disable

Disable Non-XA connection pooling using:

```properties
# Disable pooling for default datasource
ojp.connection.pool.enabled=false

# Disable pooling for specific datasource
myApp.ojp.connection.pool.enabled=false
```

**Configuration Precedence:**

Properties can be specified in three ways, with the following precedence (highest to lowest):

1. **Environment Variables** (highest priority)
   ```bash
   # Disable pooling via environment variable
   export MYAPP_OJP_CONNECTION_POOL_ENABLED=false
   export OJP_CONNECTION_POOL_ENABLED=false  # for default datasource
   ```

2. **System Properties** (via `-D` flags)
   ```bash
   # Disable pooling via system property
   java -Dmyapp.ojp.connection.pool.enabled=false -jar app.jar
   mvn test -Dmultinode.ojp.connection.pool.enabled=false
   ```

3. **Properties File** (`ojp.properties` - lowest priority)
   ```properties
   myapp.ojp.connection.pool.enabled=false
   ```

**Behavior when disabled:**
- Connections created directly via `DriverManager.getConnection()`
- No connection reuse - new connection per request
- Lower memory overhead, higher connection acquisition latency
- Pool size properties (maximumPoolSize, minimumIdle) are ignored

**Example Configuration:**
```properties
# Debugging datasource with pool disabled
debug.ojp.connection.pool.enabled=false
debug.ojp.connection.pool.connectionTimeout=5000

# Production datasource with pool enabled
prod.ojp.connection.pool.enabled=true
prod.ojp.connection.pool.maximumPoolSize=50
prod.ojp.connection.pool.minimumIdle=10
```

#### XA Pool Disable

Disable XA connection pooling using:

```properties
# Disable XA pooling for default datasource
ojp.xa.connection.pool.enabled=false

# Disable XA pooling for specific datasource
myApp.ojp.xa.connection.pool.enabled=false
```

**Configuration Precedence:**

Properties can be specified in three ways, with the following precedence (highest to lowest):

1. **Environment Variables** (highest priority)
   ```bash
   # Disable XA pooling via environment variable
   export MYAPP_OJP_XA_CONNECTION_POOL_ENABLED=false
   export OJP_XA_CONNECTION_POOL_ENABLED=false  # for default datasource
   ```

2. **System Properties** (via `-D` flags)
   ```bash
   # Disable XA pooling via system property
   java -Dmyapp.ojp.xa.connection.pool.enabled=false -jar app.jar
   mvn test -Dmultinode.ojp.xa.connection.pool.enabled=false
   ```

3. **Properties File** (`ojp.properties` - lowest priority)
   ```properties
   myapp.ojp.xa.connection.pool.enabled=false
   ```

**Behavior when disabled:**
- XADataSource created directly without pooling
- XAConnections created on demand per session
- No backend session pooling - direct XA operations
- Lower overhead for infrequent XA transactions
- Pool size properties (maxTotal, minIdle) are ignored

**Example Configuration:**
```properties
# Testing XA datasource with pool disabled
test.ojp.xa.connection.pool.enabled=false

# Production XA datasource with pool enabled
prod.ojp.xa.connection.pool.enabled=true
prod.ojp.xa.connection.pool.maxTotal=50
prod.ojp.xa.connection.pool.minIdle=10
```

**Important Notes:**
- Non-XA and XA pool settings are **independent** - you can disable one without affecting the other
- When pooling is disabled, connection acquisition is slower but simpler
- Pool disable is applied per datasource name, allowing mixed configurations
- For production workloads with high concurrency, keeping pooling enabled is recommended

### XA Backend Session Pool Configuration

When using XA (distributed transaction) connections via `OjpXADataSource` **with pooling enabled**, OJP uses **server-side backend session pooling** with Apache Commons Pool 2. This is separate from the HikariCP pooling used for non-XA connections.

| Property | Type | Default | Description | Since |
|----------|------|---------|-------------|-------|
| `ojp.xa.connection.pool.enabled` | boolean | true | Enable/disable XA connection pooling | 0.2.0-beta |
| `ojp.xa.connection.pool.maxTotal` | int | 20 | Maximum XA backend sessions per server | 0.2.0-beta |
| `ojp.xa.connection.pool.minIdle` | int | 5 | Minimum idle XA sessions (pre-warmed) | 0.2.0-beta |
| `ojp.xa.connection.pool.connectionTimeout` | long | 20000 | Semaphore admission wait budget (ms) for XA requests; backend pool borrow is fail-fast | 0.2.0-beta |
| `ojp.xa.connection.pool.idleTimeout` | long | 600000 | Max idle time (ms) before eviction (10 minutes) | 0.2.0-beta |
| `ojp.xa.connection.pool.maxLifetime` | long | 1800000 | Max lifetime (ms) of XA session (30 minutes) | 0.2.0-beta |
| `ojp.xa.connection.pool.timeBetweenEvictionRuns` | long | 30000 | How often evictor runs (ms) to clean up excess idle connections (30 seconds) | 0.2.0-beta |
| `ojp.xa.connection.pool.numTestsPerEvictionRun` | int | 10 | Number of idle connections checked per eviction run | 0.2.0-beta |
| `ojp.xa.connection.pool.softMinEvictableIdleTime` | long | 60000 | Min idle time (ms) before soft eviction respecting minIdle (60 seconds) | 0.2.0-beta |

#### XA Pool Architecture

- **Client Side**: No connection pooling - connections created and closed after use (when XA pooling enabled)
- **Client Side (pooling disabled)**: XAConnections created on demand without any pooling
- **Server Side**: Apache Commons Pool 2 pools PostgreSQL XA backend sessions
- **Connection Reuse**: Physical XAConnection stays open across multiple transactions
- **Dual-Condition Lifecycle**: Sessions returned to pool only when BOTH transaction complete AND client XAConnection closed

#### XA Configuration Examples

> **Note:** These are configuration examples only, not recommendations. Adjust pool sizes based on your application's actual workload characteristics and resource constraints.

```properties
# Default XA backend session pool
ojp.xa.connection.pool.maxTotal=20
ojp.xa.connection.pool.minIdle=5
ojp.xa.connection.pool.connectionTimeout=20000

# High-volume application with more XA concurrency
mainApp.ojp.xa.connection.pool.maxTotal=100
mainApp.ojp.xa.connection.pool.minIdle=10
mainApp.ojp.xa.connection.pool.connectionTimeout=30000

# Analytics with lower XA concurrency
analytics.ojp.xa.connection.pool.maxTotal=25
analytics.ojp.xa.connection.pool.minIdle=3
analytics.ojp.xa.connection.pool.connectionTimeout=15000
```

#### Multinode XA Pool Division

For N servers with maxTotal=M:
- Each server gets: M / N sessions
- Example: 2 servers, maxTotal=22 → 11 per server
- Automatic rebalancing on server failures/recoveries

#### Monitoring XA Pools

Enable DEBUG logging for pool diagnostics:
```properties
-Dorg.slf4j.simpleLogger.log.org.openjproxy=DEBUG
```

Log output includes:
```
XA pool initialized: server=localhost:5432, maxTotal=11, minIdle=10
Session borrowed successfully: poolState=[active=2, idle=8, maxTotal=11]
Session returned to pool: poolState=[active=1, idle=9, maxTotal=11]
Pool resized due to cluster health change: maxTotal=11→22, minIdle=10→20
```

For comprehensive XA management documentation, see **[XA Management Guide](../multinode/XA_MANAGEMENT.md)**

### Example ojp.properties File

```properties
# Multi-datasource configuration example

# Default datasource for backward compatibility
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# XA backend session pool (server-side)
ojp.xa.connection.pool.maxTotal=20
ojp.xa.connection.pool.minIdle=5
ojp.xa.connection.pool.connectionTimeout=20000
ojp.xa.connection.pool.idleTimeout=600000
ojp.xa.connection.pool.maxLifetime=1800000
ojp.xa.connection.pool.timeBetweenEvictionRuns=30000
ojp.xa.connection.pool.numTestsPerEvictionRun=10
ojp.xa.connection.pool.softMinEvictableIdleTime=60000

# High-performance application datasource
webapp.ojp.connection.pool.maximumPoolSize=50
webapp.ojp.connection.pool.minimumIdle=10
webapp.ojp.connection.pool.connectionTimeout=5000
webapp.ojp.xa.connection.pool.maxTotal=100
webapp.ojp.xa.connection.pool.minIdle=10
webapp.ojp.xa.connection.pool.connectionTimeout=30000

# Batch processing datasource
batch.ojp.connection.pool.maximumPoolSize=20
batch.ojp.connection.pool.minimumIdle=2
batch.ojp.connection.pool.maxLifetime=3600000
batch.ojp.xa.connection.pool.maxTotal=22
batch.ojp.xa.connection.pool.minIdle=15
batch.ojp.xa.connection.pool.connectionTimeout=25000

# Read-only analytics datasource
analytics.ojp.connection.pool.maximumPoolSize=8
analytics.ojp.connection.pool.minimumIdle=1
analytics.ojp.connection.pool.idleTimeout=900000
analytics.ojp.xa.connection.pool.maxTotal=11
analytics.ojp.xa.connection.pool.minIdle=5
analytics.ojp.xa.connection.pool.connectionTimeout=15000
```

### Connection Pool Fallback Behavior

- If no `ojp.properties` file is found, all default values are used
- If a property is missing from the file, its default value is used
- If a property has an invalid value, the default is used and a warning is logged
- If a datasource name is not configured, the connection will use server defaults
- All validation and configuration logic is handled on the server side

### Best Practices

1. **Use descriptive datasource names** that reflect their purpose (e.g., `webApp`, `batchJob`, `analytics`)
2. **Size pools appropriately** for each use case - high-traffic web applications need larger pools than batch jobs
3. **Configure timeouts** based on expected connection usage patterns
4. **Monitor pool metrics** using JMX to optimize settings over time
5. **Use smaller pools for background tasks** to prevent resource exhaustion

## JDBC Driver Usage

### Adding OJP Driver to Your Project

Add the OJP JDBC driver dependency to your project:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

### JDBC URL Format

Replace your existing JDBC connection URL by prefixing with `ojp[host:port]_` and optionally specify a datasource:

```java
// Before (PostgreSQL example)
"jdbc:postgresql://user@localhost/mydb"

// After with default datasource
"jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb"

// After with named datasource
"jdbc:ojp[localhost:1059(mainApp)]_postgresql://user@localhost/mydb"

// Oracle example with datasource
"jdbc:ojp[localhost:1059(analytics)]_oracle:thin:@localhost:1521/XEPDB1"

// SQL Server example with datasource
"jdbc:ojp[localhost:1059(reporting)]_sqlserver://localhost:1433;databaseName=mydb"
```

Use the OJP driver class: `org.openjproxy.jdbc.Driver`

### SSL/TLS Certificate Configuration with Placeholders

OJP supports server-side SSL/TLS certificate configuration using property placeholders in JDBC URLs. This allows certificate paths to be configured on the OJP server rather than hardcoded in the client connection URL.

#### Why Use Placeholders?

- **Centralized certificate management**: Certificates reside on the OJP server
- **Security**: Certificate paths are not exposed in application configuration
- **Environment flexibility**: Different certificate paths for dev/staging/production
- **Simplified client configuration**: Clients don't need access to certificate files

#### How to Configure

**Step 1: Configure the JDBC URL with placeholders in `ojp.properties`:**

```properties
# PostgreSQL with SSL
mainApp.ojp.datasource.url=jdbc:ojp[localhost:1059(mainApp)]_postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}

# MySQL with SSL
reporting.ojp.datasource.url=jdbc:ojp[localhost:1059(reporting)]_mysql://dbhost:3306/mydb?useSSL=true&trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}

# Oracle with wallet
analytics.ojp.datasource.url=jdbc:ojp[localhost:1059(analytics)]_oracle:thin:@dbhost:2484/myservice?oracle.net.wallet_location=${ojp.server.oracle.wallet.location}
```

**Step 2: Configure the certificate paths on the OJP server** (see [OJP Server Configuration](ojp-server-configuration.md)):

```bash
# Using JVM properties
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/ca-cert.pem \
  -Dojp.server.mysql.truststore=file:///etc/ojp/certs/truststore.jks \
  -Dojp.server.oracle.wallet.location=/etc/ojp/wallet

# Or using environment variables
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/ca-cert.pem
export OJP_SERVER_MYSQL_TRUSTSTORE=file:///etc/ojp/certs/truststore.jks
export OJP_SERVER_ORACLE_WALLET_LOCATION=/etc/ojp/wallet
```

#### Placeholder Format

Placeholders use the format: `${property.name}`

**Security Note**: Property names are validated on the server to prevent attacks if a client is compromised. Only property names starting with `ojp.server.` or `ojp.client.` are allowed, and they must contain only alphanumeric characters, dots, hyphens, and underscores.

**Naming convention:**
- **Always use the ojp.server prefix**: `${ojp.server.sslrootcert}` (required for validation)
- Use descriptive names: `${ojp.server.postgresql.sslrootcert}` is better than `${cert1}`
- Include database type: `${ojp.server.mysql.truststore}`, `${ojp.server.db2.keystore}`
- Include environment if needed: `${ojp.server.prod.sslrootcert}`
- Use only allowed characters: alphanumeric, dots (`.`), hyphens (`-`), underscores (`_`)
- Keep suffix under 200 characters

**Why these rules matter**: If a client application is compromised, attackers could inject malicious property names. The validation rules prevent:
- Access to system properties like `${java.home}`
- Command injection through special characters like `;`, `|`, `&`
- Path traversal attacks like `../../../etc/passwd`
- SQL injection attempts
- Denial of service through extremely long names

For complete security details, see the [SSL/TLS Certificate Configuration Guide](ssl-tls-certificate-placeholders.md).

#### Database-Specific Examples

**PostgreSQL:**
```properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://host:5432/db?\
ssl=true&sslmode=verify-full&\
sslrootcert=${ojp.server.sslrootcert}&\
sslcert=${ojp.server.sslcert}&\
sslkey=${ojp.server.sslkey}
```

**MySQL/MariaDB:**
```properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://host:3306/db?\
useSSL=true&requireSSL=true&\
trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}&\
trustCertificateKeyStorePassword=${ojp.server.mysql.truststorePassword}
```

**Oracle:**
```properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@host:2484/service?\
oracle.net.wallet_location=${ojp.server.oracle.wallet.location}&\
oracle.net.ssl_server_dn_match=true
```

**SQL Server:**
```properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://host:1433;\
databaseName=mydb;encrypt=true;\
trustStore=${ojp.server.sqlserver.truststore};\
trustStorePassword=${ojp.server.sqlserver.truststorePassword}
```

**DB2:**
```properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_db2://host:50001/mydb:\
sslConnection=true;\
sslTrustStoreLocation=${ojp.server.db2.truststore};\
sslTrustStorePassword=${ojp.server.db2.truststorePassword};
```

For comprehensive SSL/TLS configuration examples and best practices, see the [SSL/TLS Certificate Configuration Guide](ssl-tls-certificate-placeholders.md).

### DataSource Parameter Usage

The dataSource specification in parentheses within the OJP connection section specifies which configuration to use:
- **No parameter**: Uses "default" datasource configuration
- **`(myApp)`**: Uses configuration prefixed with "myApp."
- **`(analytics)`**: Uses configuration prefixed with "analytics."

```java
// Examples of different datasource usage
Connection mainConn = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059(mainApp)]_postgres:mydb", "user", "pass");
    
Connection analyticsConn = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059(analytics)]_postgres:mydb", "user", "pass");
    
Connection defaultConn = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059]_postgres:mydb", "user", "pass"); // Uses default config
```

### Important Notes

#### Disable Application-Level Connection Pooling

When using OJP with regular (non-XA) connections, **disable any existing connection pooling** in your application (such as HikariCP, C3P0, or DBCP2) since OJP handles connection pooling at the proxy level. This prevents double-pooling and ensures optimal performance.

**Important**: OJP will not work properly if another connection pool is enabled on the application side for regular connections. Make sure to disable all application-level connection pooling before using OJP.

**XA Connections**: When using `OjpXADataSource` for distributed transactions (XA mode), by default OJP uses server-side backend session pooling with Apache Commons Pool 2 if no other OJP XA SPI implementation is provided. The server manages PostgreSQL XAConnection pooling to ensure:
- Physical XAConnection reuse across multiple transactions
- Dual-condition session lifecycle (returned only when transaction complete AND client XAConnection closed)
- Dynamic pool rebalancing during server failures/recoveries
- Proper XA spec compliance (connection properties persist across transactions)

See **[XA Management Guide](../multinode/XA_MANAGEMENT.md)** for comprehensive XA pooling documentation.

#### DataSource Isolation

Each datasource name creates a separate connection pool on the server side, even when connecting to the same database. This provides:
- Configuration isolation between different application components
- Independent monitoring and metrics per datasource  
- Fine-grained resource control
- Better troubleshooting capabilities

#### Backward Compatibility

The multi-datasource feature is fully backward compatible:
- Existing applications without `dataSource` parameter continue to work unchanged
- Existing `ojp.properties` files without datasource prefixes continue to work as "default" datasource
- No changes required for existing deployments unless you want to use multi-datasource features

## Read/Write Splitting Configuration

OJP supports automatic read/write traffic splitting. Write operations (INSERT, UPDATE, DELETE, DDL) are always routed to the primary datasource. Stateless (auto-commit) read operations (SELECT, WITH, EXPLAIN, SHOW, DESCRIBE) are routed to a replica datasource when read/write splitting is enabled. All operations inside an explicit transaction are always routed to the primary.

Read/write splitting is configured entirely through `ojp.properties`. No server-side configuration changes are needed.

> **Note:** Sticky sessions are **opt-in** (default: `0` — disabled). Do not set a non-zero `stickySessionSeconds` unless your application requires read-your-writes guarantees after writes outside of a transaction.

### Primary Datasource Properties

Use the format `{primaryName}.ojp.readwrite.*` to configure the primary:

| Property | Default | Description |
|---|---|---|
| `{primary}.ojp.readwrite.role` | — | Must be `primary` to enable read/write splitting for this datasource |
| `{primary}.ojp.readwrite.enabled` | `false` | Enable (`true`) or disable read/write splitting for this primary |
| `{primary}.ojp.readwrite.replicaSelectionStrategy` | `ROUND_ROBIN` | Replica selection strategy: `ROUND_ROBIN` or `RANDOM`. `LEAST_CONNECTIONS` is accepted but currently falls back to `ROUND_ROBIN`; metrics-based selection is planned for a future phase. |
| `{primary}.ojp.readwrite.stickySessionSeconds` | `0` | Read-your-writes window in seconds. `0` = disabled (opt-in). After a write, reads continue going to the primary for this many seconds before reverting to replica routing |
| `{primary}.ojp.readwrite.replicaFailoverToPrimary` | `true` | Fall back to the primary when no healthy replica is available |

### Replica Datasource Properties

Use the format `{replicaName}.ojp.*` to configure each replica:

| Property | Default | Description |
|---|---|---|
| `{replica}.ojp.readwrite.role` | — | Must be `replica` |
| `{replica}.ojp.readwrite.primary` | — | Name of the primary datasource this replica belongs to |
| `{replica}.ojp.connection.url` | — | **Required.** JDBC URL of the replica database |
| `{replica}.ojp.connection.user` | `""` | Replica database user |
| `{replica}.ojp.connection.password` | `""` | Replica database password |
| `{replica}.ojp.pool.maxPoolSize` | `10` | Maximum connections in the replica pool |
| `{replica}.ojp.pool.minIdle` | `2` | Minimum idle connections |
| `{replica}.ojp.pool.connectionTimeout` | `30000` | Connection acquire timeout (ms) |
| `{replica}.ojp.pool.idleTimeout` | `600000` | Idle connection timeout (ms) |
| `{replica}.ojp.pool.maxLifetime` | `1800000` | Maximum connection lifetime (ms) |

### Example Configuration

```properties
# Primary datasource (read/write splitting enabled, no sticky session)
mydb.ojp.readwrite.role=primary
mydb.ojp.readwrite.enabled=true
mydb.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
# stickySessionSeconds defaults to 0 (disabled) — opt-in only when needed

# Replica 1
replica1.ojp.readwrite.role=replica
replica1.ojp.readwrite.primary=mydb
replica1.ojp.connection.url=jdbc:postgresql://replica1-host:5432/mydb
replica1.ojp.connection.user=app_ro
replica1.ojp.connection.password=secret
replica1.ojp.pool.maxPoolSize=15
replica1.ojp.pool.minIdle=3

# Replica 2
replica2.ojp.readwrite.role=replica
replica2.ojp.readwrite.primary=mydb
replica2.ojp.connection.url=jdbc:postgresql://replica2-host:5432/mydb
replica2.ojp.connection.user=app_ro
replica2.ojp.connection.password=secret
replica2.ojp.pool.maxPoolSize=15
replica2.ojp.pool.minIdle=3
```

### Sticky Sessions (Read-Your-Writes)

Sticky sessions guarantee that a client which just executed a write will continue reading from the primary for a configurable window, giving replicas time to catch up. This is an opt-in behaviour because it introduces latency on the read path and is only necessary when your application requires seeing its own writes immediately outside of a transaction.

```properties
# Enable 3-second sticky window (reads stay on primary for 3 s after every write)
mydb.ojp.readwrite.stickySessionSeconds=3
```

**When to use sticky sessions:**
- Application executes a write and then immediately queries without a transaction, and must see the write
- Replication lag is measurable and the application is not latency-sensitive

**When to leave sticky sessions disabled (`0`, the default):**
- All writes and their subsequent reads are wrapped in the same transaction (the transaction itself guarantees read-your-writes via the primary)
- The application tolerates eventual consistency for reads outside transactions

### Routing Rules Summary

| Operation | Inside Transaction | Sticky Window Active | Routes To |
|---|---|---|---|
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | — | — | Replica |
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | ✓ | — | Primary |
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | — | ✓ | Primary |
| INSERT / UPDATE / DELETE / DDL | — | — | Primary |
| INSERT / UPDATE / DELETE / DDL | ✓ | — | Primary |

## Related Documentation

- **[SSL/TLS Certificate Configuration Guide](ssl-tls-certificate-placeholders.md)** - Complete guide for configuring SSL/TLS certificates with property placeholders
- **[OJP Server Configuration](ojp-server-configuration.md)** - Server startup options and runtime configuration
- **[Example Configuration Properties](ojp-server-example.properties)** - Complete example configuration file with all settings
