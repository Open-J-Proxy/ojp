# OJP JDBC Driver Configuration Guide

This document covers configuration options for the OJP JDBC driver, including client-side connection pool settings with multi-datasource support.

## Overview

The OJP JDBC driver supports configurable connection pool settings via an `ojp.properties` file with advanced multi-datasource capabilities. This allows customization of HikariCP connection pool behavior on a per-client basis with support for multiple named datasources for enhanced flexibility.

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

| Property                              | Type | Default | Description                                              |
|---------------------------------------|------|---------|----------------------------------------------------------|
| `ojp.connection.pool.maximumPoolSize` | int  | 20      | Maximum number of connections in the pool                |
| `ojp.connection.pool.minimumIdle`     | int  | 5       | Minimum number of idle connections maintained            |
| `ojp.connection.pool.idleTimeout`     | long | 600000  | Maximum time (ms) a connection can sit idle (10 minutes) |
| `ojp.connection.pool.maxLifetime`     | long | 1800000 | Maximum lifetime (ms) of a connection (30 minutes)       |
| `ojp.connection.pool.connectionTimeout` | long | 10000   | Maximum time (ms) to wait for a connection (10 seconds)  |

### Connection Pool Properties

| Property                              | Type | Default | Description                                              |
|---------------------------------------|------|---------|----------------------------------------------------------|
| `ojp.connection.pool.maximumPoolSize` | int  | 20      | Maximum number of connections in the pool                |
| `ojp.connection.pool.minimumIdle`     | int  | 5       | Minimum number of idle connections maintained            |
| `ojp.connection.pool.idleTimeout`     | long | 600000  | Maximum time (ms) a connection can sit idle (10 minutes) |
| `ojp.connection.pool.maxLifetime`     | long | 1800000 | Maximum lifetime (ms) of a connection (30 minutes)       |
| `ojp.connection.pool.connectionTimeout` | long | 10000   | Maximum time (ms) to wait for a connection (10 seconds)  |

**Note**: These properties can be used with or without a datasource name prefix. For example:
- `ojp.connection.pool.maximumPoolSize=20` (default datasource)
- `myApp.ojp.connection.pool.maximumPoolSize=50` (myApp datasource)

### Example ojp.properties File

```properties
# Multi-datasource configuration example

# Default datasource for backward compatibility
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# High-performance application datasource
webapp.ojp.connection.pool.maximumPoolSize=50
webapp.ojp.connection.pool.minimumIdle=10
webapp.ojp.connection.pool.connectionTimeout=5000

# Batch processing datasource
batch.ojp.connection.pool.maximumPoolSize=20
batch.ojp.connection.pool.minimumIdle=2
batch.ojp.connection.pool.maxLifetime=3600000

# Read-only analytics datasource
analytics.ojp.connection.pool.maximumPoolSize=8
analytics.ojp.connection.pool.minimumIdle=1
analytics.ojp.connection.pool.idleTimeout=900000
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
    <version>0.1.1-beta</version>
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

When using OJP, **disable any existing connection pooling** in your application (such as HikariCP, C3P0, or DBCP2) since OJP handles connection pooling at the proxy level. This prevents double-pooling and ensures optimal performance.

**Important**: OJP will not work properly if another connection pool is enabled on the application side. Make sure to disable all application-level connection pooling before using OJP.

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

## Related Documentation

- **[OJP Server Configuration](ojp-server-configuration.md)** - Server startup options and runtime configuration
- **[Example Configuration Properties](ojp-server-example.properties)** - Complete example configuration file with all settings