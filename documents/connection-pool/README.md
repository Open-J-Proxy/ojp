# Connection Pool Abstraction

This document describes the OJP Connection Pool Abstraction - a pluggable SPI for connection pool providers.

## Overview

OJP provides a connection pool abstraction layer that allows users to choose between different connection pool implementations. The abstraction consists of:

- **ojp-datasource-api**: Core API module containing the SPI interfaces and configuration classes
- **ojp-datasource-hikari**: HikariCP provider (default, highest priority)
- **ojp-datasource-dbcp**: Apache Commons DBCP2 provider (alternative)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    OJP Server (StatementServiceImpl)            │
├─────────────────────────────────────────────────────────────────┤
│                ConnectionPoolProviderRegistry                    │
│           (ServiceLoader discovery & factory)                    │
├──────────────────────┬──────────────────────────────────────────┤
│  ConnectionPoolProvider (SPI Interface)                         │
├──────────────────────┼──────────────────────┬───────────────────┤
│   HikariCP Provider  │     DBCP Provider    │  Custom Provider  │
│(ojp-datasource-hikari)│(ojp-datasource-dbcp) │    (user jar)     │
│    Priority: 100     │     Priority: 10      │    Priority: 0    │
└──────────────────────┴──────────────────────┴───────────────────┘
```

## Integration

The OJP server (`StatementServiceImpl`) uses this abstraction layer to create connection pools. By default, HikariCP is used, but you can switch to other providers by:

1. Adding the provider module to the classpath
2. Optionally setting `ojp.datasource.provider` to the provider ID

## Modules

### ojp-datasource-api

The core API module containing:

| Class | Description |
|-------|-------------|
| `PoolConfig` | Immutable configuration class with builder pattern |
| `ConnectionPoolProvider` | SPI interface for provider implementations |
| `ConnectionPoolProviderRegistry` | ServiceLoader-based discovery and factory |

**Note:** This module has no dependencies on vendor-specific libraries.

### ojp-datasource-hikari

HikariCP provider (default):

| Class | Description |
|-------|-------------|
| `HikariConnectionPoolProvider` | HikariCP implementation of `ConnectionPoolProvider` |

### ojp-datasource-dbcp

Apache Commons DBCP2 provider:

| Class | Description |
|-------|-------------|
| `DbcpConnectionPoolProvider` | DBCP2 implementation of `ConnectionPoolProvider` |

## Usage

### Basic Usage

```java
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import javax.sql.DataSource;

// Configure pool
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("secret")
    .maxPoolSize(20)
    .minIdle(5)
    .connectionTimeoutMs(10000)
    .validationQuery("SELECT 1")
    .build();

// Create DataSource using default provider (highest priority)
DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);

// Or specify a provider
DataSource ds = ConnectionPoolProviderRegistry.createDataSource("dbcp", config);

// Get pool statistics
Map<String, Object> stats = ConnectionPoolProviderRegistry.getStatistics("dbcp", ds);

// Close when done
ConnectionPoolProviderRegistry.closeDataSource("dbcp", ds);
```

### Secure Password Handling

For integration with secret management systems:

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .passwordSupplier(() -> secretManager.getPassword()) // Dynamic password retrieval
    .build();
```

Or using char arrays for better memory hygiene:

```java
char[] password = getPasswordFromVault();
try {
    PoolConfig config = PoolConfig.builder()
        .url("jdbc:postgresql://localhost:5432/mydb")
        .username("user")
        .password(password)
        .build();
    // use config...
} finally {
    Arrays.fill(password, '\0'); // Clear from memory
}
```

## Configuration Reference

### PoolConfig Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | String | null | JDBC URL |
| `username` | String | null | Database username |
| `password` | char[] | null | Database password (or use `passwordSupplier`) |
| `passwordSupplier` | Supplier<char[]> | null | Dynamic password provider |
| `driverClassName` | String | null | JDBC driver class (auto-detected if not set) |
| `maxPoolSize` | int | 10 | Maximum pool size |
| `minIdle` | int | 2 | Minimum idle connections |
| `connectionTimeoutMs` | long | 30000 | Connection acquisition timeout (ms) |
| `idleTimeoutMs` | long | 600000 | Idle connection timeout (ms) |
| `maxLifetimeMs` | long | 1800000 | Maximum connection lifetime (ms) |
| `validationQuery` | String | null | SQL query to validate connections |
| `autoCommit` | boolean | true | Default auto-commit mode |
| `properties` | Map<String,String> | empty | Additional driver/pool properties |
| `metricsPrefix` | String | null | Prefix for metrics naming |

When OJP's server creates a pool, it sets the backend borrow timeout to 1 ms so
the admission semaphore owns the wait budget. HikariCP enforces a 250 ms
minimum, so its effective internal acquisition timeout is 250 ms. This server
behavior is distinct from the `PoolConfig` default shown above, which applies
when creating a provider DataSource directly.

For predictable latency in an OJP server deployment, consider setting
`ojp.connection.pool.minimumIdle` equal to
`ojp.connection.pool.maximumPoolSize` when database capacity allows. This
reduces connection creation during traffic spikes after the pool has
populated, but does not make connections ready immediately. It also uses more
idle connections and can slow initialization. The 250 ms acquisition floor
describes HikariCP; alternative providers such as DBCP2 have their own
acquisition behavior.

### Provider-Specific Properties

Pass provider-specific properties using the `properties` map:

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    // ... other settings
    .property("cachePrepStmts", "true")
    .property("prepStmtCacheSize", "250")
    .build();
```

## Adding Custom Providers

### Step 1: Implement the SPI

```java
package com.example.mypool;

import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

public class MyPoolProvider implements ConnectionPoolProvider {

    @Override
    public String id() {
        return "mypool";
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        // Create and configure your DataSource
        MyDataSource ds = new MyDataSource();
        ds.setUrl(config.getUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPasswordAsString());
        ds.setMaxPoolSize(config.getMaxPoolSize());
        // ... other configuration
        return ds;
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof MyDataSource) {
            ((MyDataSource) dataSource).close();
        }
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        if (dataSource instanceof MyDataSource) {
            MyDataSource ds = (MyDataSource) dataSource;
            return Map.of(
                "activeConnections", ds.getActiveCount(),
                "idleConnections", ds.getIdleCount()
            );
        }
        return Map.of();
    }

    @Override
    public int getPriority() {
        return 0; // Higher = preferred for auto-selection
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.example.mypool.MyDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

### Step 2: Register via ServiceLoader

Create file: `META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider`

```
com.example.mypool.MyPoolProvider
```

### Step 3: Package and Deploy

Package your provider as a JAR and add it to the classpath. The `ConnectionPoolProviderRegistry` will automatically discover it.

## Migration from Direct HikariCP Usage

If you're currently using HikariCP directly, here's how to migrate:

### Before (HikariCP directly)

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
config.setUsername("user");
config.setPassword("secret");
config.setMaximumPoolSize(20);
config.setMinimumIdle(5);
HikariDataSource ds = new HikariDataSource(config);
```

### After (Using Abstraction)

```java
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("secret")
    .maxPoolSize(20)
    .minIdle(5)
    .build();
DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);
```

### Property Mapping (HikariCP → PoolConfig)

| HikariCP Property | PoolConfig Field |
|-------------------|------------------|
| `jdbcUrl` | `url` |
| `username` | `username` |
| `password` | `password` |
| `maximumPoolSize` | `maxPoolSize` |
| `minimumIdle` | `minIdle` |
| `connectionTimeout` | `connectionTimeoutMs` |
| `idleTimeout` | `idleTimeoutMs` |
| `maxLifetime` | `maxLifetimeMs` |
| `connectionTestQuery` | `validationQuery` |
| `autoCommit` | `autoCommit` |

## Available Providers

| Provider ID | Module | Description | Priority |
|-------------|--------|-------------|----------|
| `hikari` | ojp-datasource-hikari | HikariCP (Default) | 100 |
| `dbcp` | ojp-datasource-dbcp | Apache Commons DBCP2 | 10 |

## Statistics

Each provider can return pool statistics. Common statistics include:

| Statistic | Description |
|-----------|-------------|
| `activeConnections` | Number of active connections |
| `idleConnections` | Number of idle connections |
| `totalConnections` | Total connections in pool |
| `maxPoolSize` | Configured maximum size |
| `minIdle` | Configured minimum idle |

## Security Considerations

1. **Passwords are never logged** - The `PoolConfig.toString()` masks passwords
2. **Use char[] or Supplier** - Prefer `password(char[])` or `passwordSupplier()` over string passwords
3. **Clear sensitive data** - Call `config.clearSensitiveData()` when configuration is no longer needed

## Thread Safety

- `PoolConfig` is immutable and thread-safe
- `ConnectionPoolProviderRegistry` is thread-safe
- Provider implementations should be thread-safe

## Troubleshooting

### No providers found

Ensure the provider JAR is on the classpath and contains the ServiceLoader registration file:
```
META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider
```

### Provider not available

Check `provider.isAvailable()` - it may return false if required dependencies are missing.

### Connection timeout

Increase `connectionTimeoutMs` or check network connectivity to the database.
