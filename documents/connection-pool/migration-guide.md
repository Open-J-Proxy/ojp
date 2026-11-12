# Migration Guide: Connection Pool Abstraction

This guide explains the OJP connection pool abstraction layer and how to use different pool providers.

## Overview

The OJP connection pool abstraction provides:
- Provider-agnostic configuration
- Easy switching between pool implementations (HikariCP, DBCP, etc.)
- Secure credential handling
- ServiceLoader-based provider discovery

## How It Works

OJP now uses a Service Provider Interface (SPI) for connection pool implementations. By default, HikariCP is used as the provider, but you can switch to other providers like Apache Commons DBCP.

The configuration properties have always been `ojp.` prefixed and continue to work unchanged.

## Using Different Providers

### Default (HikariCP)

HikariCP is the default provider. No changes are needed if you're already using OJP's connection pool configuration:

```properties
# ojp.properties - existing configuration continues to work
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=10000
```

For predictable, low latency, consider setting `minimumIdle` equal to
`maximumPoolSize` when database capacity allows. For example:

```properties
ojp.connection.pool.maximumPoolSize=50
ojp.connection.pool.minimumIdle=50
```

OJP waits for admission before accessing the pool, then borrows fail-fast.
HikariCP's internal acquisition timeout is effectively 250 ms in OJP's current
implementation. A smaller `minimumIdle` can require new physical connections
during a traffic spike, and slow connection creation can cause failures before
the maximum is reached. Equal sizes reduce this risk after pool population but
do not guarantee immediate readiness. They also use more idle database
connections and can increase baseline resource use and initialization time.
Check the total connection budget across OJP nodes before using this setting.
The 250 ms acquisition floor is specific to HikariCP; DBCP2's internal
acquisition behavior differs.

### Switching to DBCP

To use Apache Commons DBCP2 instead of HikariCP:

1. Add the DBCP provider dependency:
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-datasource-dbcp</artifactId>
    <version>${ojp.version}</version>
</dependency>
```

2. Configure the provider:
```properties
# ojp.properties
ojp.datasource.provider=dbcp
```

## Configuration Reference

### OJP Connection Pool Properties

| Property | Description |
|----------|-------------|
| `ojp.connection.pool.maximumPoolSize` | Maximum pool size |
| `ojp.connection.pool.minimumIdle` | Minimum idle connections |
| `ojp.connection.pool.connectionTimeout` | Admission wait budget (ms); backend pool borrow is fail-fast |
| `ojp.connection.pool.idleTimeout` | Idle timeout (ms) |
| `ojp.connection.pool.maxLifetime` | Max connection lifetime (ms) |
| `ojp.datasource.provider` | Provider ID (hikari, dbcp) |

### Per-Datasource Configuration

For named datasources, prefix properties with the datasource name. The name is inferred from the prefix — no separate name property is needed. The prefix must match the name used in the JDBC URL (e.g., `jdbc:ojp[localhost:1059(mainApp)]_...`):

```properties
# Default settings (no prefix, used when URL has no datasource name)
ojp.connection.pool.maximumPoolSize=20

# Named datasource "mainApp" — name inferred from prefix
mainApp.ojp.connection.pool.maximumPoolSize=30

# Named datasource "batchJob" — name inferred from prefix
batchJob.ojp.connection.pool.maximumPoolSize=5
```

## Environment-Specific Configuration

**Recommended Approach (Since PR #298):** OJP automatically loads environment-specific properties files without requiring any custom code.

Use environment-specific properties files for different environments:
- `ojp-dev.properties` - Development environment
- `ojp-staging.properties` - Staging environment
- `ojp-prod.properties` - Production environment

Set the environment using:
```bash
# System property
-Dojp.environment=dev

# Or environment variable
export OJP_ENVIRONMENT=dev
```

**For more details, see:**
- **[OJP JDBC Configuration](../configuration/ojp-jdbc-configuration.md)** - Complete guide to environment-specific configuration
- **[Example Configuration Files](../configuration/)** - ojp-dev.properties, ojp-staging.properties, ojp-prod.properties

## Programmatic Usage (Advanced)

**Note:** For most use cases, including environment-specific configuration, you should use the properties file approach described above. The programmatic API shown below is intended for advanced scenarios where you need direct control over DataSource creation.

### Using the SPI Directly

```java
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;

// Build configuration
PoolConfig config = PoolConfig.builder()
    .url("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("secret")
    .maxPoolSize(20)
    .minIdle(5)
    .connectionTimeoutMs(10000)
    .build();

// Create DataSource using default provider (HikariCP)
DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);

// Or specify a provider
DataSource ds = ConnectionPoolProviderRegistry.createDataSource("dbcp", config);

// Close when done
ConnectionPoolProviderRegistry.closeDataSource("hikari", ds);
```

## Provider Priority

When no provider is specified, the registry selects the provider with the highest priority:

| Provider | Priority |
|----------|----------|
| HikariCP | 100 (default) |
| DBCP | 10 |
| Custom | 0 (default) |

## Environment Variable Override

Override the provider via environment variable:

```bash
export OJP_DATASOURCE_PROVIDER=dbcp
```

## Troubleshooting

### "Unknown provider" error

Ensure the provider module is on the classpath:
- For DBCP: Add `ojp-datasource-dbcp` dependency
- For HikariCP: Already included by default

### Pool Statistics

Get pool statistics for monitoring:

```java
Map<String, Object> stats = ConnectionPoolProviderRegistry.getStatistics("hikari", dataSource);
System.out.println("Active connections: " + stats.get("activeConnections"));
System.out.println("Idle connections: " + stats.get("idleConnections"));
```

## Support

For questions or issues, please open an issue on the [OJP GitHub repository](https://github.com/Open-J-Proxy/ojp).
