# OJP JDBC Driver Configuration Guide

This document covers configuration options for the OJP JDBC driver, including the new **Data Source-based Connection Pool Configuration** that provides improved security and flexibility.

## Data Source-based Connection Pool Configuration

The OJP JDBC driver supports configurable connection pool settings via an `ojp.properties` file using **named data sources**. This allows you to configure multiple connection pools with different settings for enhanced security and performance optimization.

### Key Benefits

- **Security**: Avoid exposing database names or usernames in configuration keys
- **Flexibility**: Configure multiple pools with different settings for the same database
- **Isolation**: Separate connection pools for different application components
- **Performance**: Optimize pool settings per use case

### How to Configure

1. Create an `ojp.properties` file in your application's classpath (either in the root or in the `resources` folder)
2. Define data sources using the format: `dataSourceName.ojp.connection.pool.*`
3. Optionally configure a default data source using: `ojp.connection.pool.*`
4. The driver will automatically load and initialize all configured data sources at startup

### Data Source Configuration Format

#### Named Data Sources
Use the prefix format: `dataSourceName.ojp.connection.pool.propertyName`

```properties
# Fast connection pool for real-time operations
fast.ojp.connection.pool.maximumPoolSize=50
fast.ojp.connection.pool.minimumIdle=10
fast.ojp.connection.pool.connectionTimeout=5000

# Slow connection pool for batch operations
batch.ojp.connection.pool.maximumPoolSize=10
batch.ojp.connection.pool.minimumIdle=2
batch.ojp.connection.pool.idleTimeout=600000

# Analytics pool for reporting
analytics.ojp.connection.pool.maximumPoolSize=20
analytics.ojp.connection.pool.minimumIdle=5
analytics.ojp.connection.pool.maxLifetime=1800000
```

#### Default Data Source
Use the standard format: `ojp.connection.pool.propertyName`

```properties
# Default data source (used when no specific dataSource is requested)
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
```

### Connection Pool Properties

| Property                              | Type | Default | Description                                              |
|---------------------------------------|------|---------|----------------------------------------------------------|
| `ojp.connection.pool.maximumPoolSize` | int  | 20      | Maximum number of connections in the pool                |
| `ojp.connection.pool.minimumIdle`     | int  | 5       | Minimum number of idle connections maintained            |
| `ojp.connection.pool.idleTimeout`     | long | 600000  | Maximum time (ms) a connection can sit idle (10 minutes) |
| `ojp.connection.pool.maxLifetime`     | long | 1800000 | Maximum lifetime (ms) of a connection (30 minutes)       |
| `ojp.connection.pool.connectionTimeout` | long | 10000   | Maximum time (ms) to wait for a connection (10 seconds)  |

### Using Named Data Sources in Your Application

Specify the data source name when creating connections:

```java
Properties props = new Properties();
props.setProperty("user", "myuser");
props.setProperty("password", "mypassword");
props.setProperty("dataSourceName", "fast");  // Use the 'fast' data source

Connection conn = DriverManager.getConnection("jdbc:ojp[localhost:1059]_h2:mem:testdb", props);
```

If no `dataSourceName` is specified, the default data source will be used.

### Example ojp.properties File

```properties
# Default data source configuration
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# High-performance data source for critical operations
critical.ojp.connection.pool.maximumPoolSize=100
critical.ojp.connection.pool.minimumIdle=20
critical.ojp.connection.pool.connectionTimeout=5000

# Background processing data source
background.ojp.connection.pool.maximumPoolSize=10
background.ojp.connection.pool.minimumIdle=2
background.ojp.connection.pool.idleTimeout=600000

# Reporting data source
reports.ojp.connection.pool.maximumPoolSize=15
reports.ojp.connection.pool.minimumIdle=3
reports.ojp.connection.pool.maxLifetime=1200000
```

### Connection Pool Fallback Behavior

- If no `ojp.properties` file is found, default values are used for the default data source
- If a data source is not configured, the connection will fail fast with a clear error message
- If a property is missing from a data source configuration, its default value is used
- If a property has an invalid value, the default is used and a warning is logged
- All validation and configuration logic is handled on the server side

### Security Considerations

This new configuration approach improves security by:

- **Avoiding Database Name Exposure**: No need to include database names in property keys
- **Username Independence**: Configuration is independent of database usernames
- **Flexible Mapping**: Multiple data sources can use the same database connection parameters
- **Clear Separation**: Different application components can use dedicated pools

## JDBC Driver Usage

The OJP JDBC driver follows standard JDBC patterns with the addition of data source name support:

```java
// Using the default data source
Connection conn1 = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059]_postgresql://localhost/mydb", "user", "pass");

// Using a named data source
Properties props = new Properties();
props.setProperty("user", "user");
props.setProperty("password", "pass");
props.setProperty("dataSourceName", "analytics");
Connection conn2 = DriverManager.getConnection(
    "jdbc:ojp[localhost:1059]_postgresql://localhost/mydb", props);
```

### Adding OJP Driver to Your Project

Add the OJP JDBC driver dependency to your project:

```xml
<dependency>
    <groupId>org.openjdbcproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.1.0-beta</version>
</dependency>
```

### JDBC URL Format

Replace your existing JDBC connection URL by prefixing with `ojp[host:port]_`:

```java
// Before (PostgreSQL example)
"jdbc:postgresql://user@localhost/mydb"

// After  
"jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb"

// Oracle example
"jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1"

// SQL Server example
"jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;databaseName=mydb"
```

Use the OJP driver class: `org.openjdbcproxy.jdbc.Driver`

### Important Notes

#### Disable Application-Level Connection Pooling

When using OJP, **disable any existing connection pooling** in your application (such as HikariCP, C3P0, or DBCP2) since OJP handles connection pooling at the proxy level. This prevents double-pooling and ensures optimal performance.

**Important**: OJP will not work properly if another connection pool is enabled on the application side. Make sure to disable all application-level connection pooling before using OJP.

## Related Documentation

- **[OJP Server Configuration](ojp-server-configuration.md)** - Server startup options and runtime configuration
- **[Example Configuration Properties](ojp-server-example.properties)** - Complete example configuration file with all settings