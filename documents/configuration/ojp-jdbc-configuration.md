# OJP JDBC Driver Configuration Guide

This document covers configuration options for the OJP JDBC driver, including client-side connection pool settings and multiple data sources configuration.

## Client-Side Connection Pool Configuration

The OJP JDBC driver supports configurable connection pool settings via an `ojp.properties` file. This allows customization of HikariCP connection pool behavior on a per-client basis, with support for multiple data sources configuration.

### How to Configure

1. Create an `ojp.properties` file in your application's classpath (either in the root or in the `resources` folder)
2. Add any of the supported properties (all are optional)
3. The driver will automatically load and send these properties to the server when establishing a connection

### Connection Pool Properties

| Property                              | Type | Default | Description                                              |
|---------------------------------------|------|---------|----------------------------------------------------------|
| `ojp.connection.pool.maximumPoolSize` | int  | 20      | Maximum number of connections in the pool                |
| `ojp.connection.pool.minimumIdle`     | int  | 5       | Minimum number of idle connections maintained            |
| `ojp.connection.pool.idleTimeout`     | long | 600000  | Maximum time (ms) a connection can sit idle (10 minutes) |
| `ojp.connection.pool.maxLifetime`     | long | 1800000 | Maximum lifetime (ms) of a connection (30 minutes)       |
| `ojp.connection.pool.connectionTimeout` | long | 10000   | Maximum time (ms) to wait for a connection (10 seconds)  |

### Multiple Data Sources Configuration

OJP supports multiple data source configurations, scoped by both database name and username. This allows different connection pool settings for different databases and users within the same application.

#### Configuration Hierarchy

The configuration supports three levels of specificity, with more specific configurations taking precedence:

1. **Database + User Specific**: `actualDbName.actualUserName.ojp.connection.pool.*`
2. **Database Specific**: `actualDbName.ojp.connection.pool.*` 
3. **Global Default**: `ojp.connection.pool.*`

#### Configuration Resolution

When establishing a connection, OJP resolves configuration properties using the following priority order:

1. **Most Specific**: Look for `databaseName.userName.ojp.connection.pool.propertyName`
2. **Database Specific**: If not found, look for `databaseName.ojp.connection.pool.propertyName`
3. **Global Default**: If still not found, use `ojp.connection.pool.propertyName`
4. **System Default**: If no configuration is found, use the built-in default value

#### Database Name Extraction

The actual database name is automatically extracted from the JDBC URL:

- **PostgreSQL**: `jdbc:postgresql://host:port/databaseName` → `databaseName`
- **MySQL**: `jdbc:mysql://host:port/databaseName` → `databaseName`
- **MariaDB**: `jdbc:mariadb://host:port/databaseName` → `databaseName`
- **Oracle**: `jdbc:oracle:thin:@host:port/serviceName` → `serviceName` (or SID for SID format)
- **SQL Server**: `jdbc:sqlserver://host:port;databaseName=dbName` → `dbName`
- **DB2**: `jdbc:db2://host:port/databaseName` → `databaseName`
- **H2**: `jdbc:h2:mem:databaseName` → `databaseName`

#### Example Configurations

**Basic Global Configuration:**
```properties
# Global defaults for all connections
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=10000
```

**Database-Specific Configuration:**
```properties
# Global defaults
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5

# Specific settings for 'myapp' database
myapp.ojp.connection.pool.maximumPoolSize=50
myapp.ojp.connection.pool.minimumIdle=10
```

**Database + User Specific Configuration:**
```properties
# Global defaults
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5

# Settings for 'myapp' database
myapp.ojp.connection.pool.maximumPoolSize=50
myapp.ojp.connection.pool.minimumIdle=10

# Specific settings for 'admin' user on 'myapp' database
myapp.admin.ojp.connection.pool.maximumPoolSize=100
myapp.admin.ojp.connection.pool.minimumIdle=20
myapp.admin.ojp.connection.pool.connectionTimeout=30000
```

**Complete Multi-Database Example:**
```properties
# Global defaults
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
ojp.connection.pool.connectionTimeout=10000

# Production database settings
production.ojp.connection.pool.maximumPoolSize=100
production.ojp.connection.pool.minimumIdle=20

# Reporting database settings (read-heavy workload)
reporting.ojp.connection.pool.maximumPoolSize=50
reporting.ojp.connection.pool.minimumIdle=10
reporting.ojp.connection.pool.connectionTimeout=30000

# Admin user gets larger pools
production.admin.ojp.connection.pool.maximumPoolSize=150
reporting.admin.ojp.connection.pool.maximumPoolSize=75

# Service account gets smaller pools
production.service.ojp.connection.pool.maximumPoolSize=30
production.service.ojp.connection.pool.minimumIdle=5
```

#### Configuration Validation and Error Handling

OJP validates configuration prefixes at startup to ensure they match the actual database and user combinations:

**Valid Prefixes:**
- `actualDatabaseName.ojp.connection.pool.*` (database-specific)
- `actualDatabaseName.actualUserName.ojp.connection.pool.*` (database+user specific)
- `ojp.connection.pool.*` (global default)

**Invalid Prefixes (Will Cause Startup Failure):**
- `wrongDatabaseName.ojp.connection.pool.*` (database name doesn't match)
- `userName.ojp.connection.pool.*` (username-only prefixes are not supported)
- `wrongDb.wrongUser.ojp.connection.pool.*` (neither database nor user matches)

**Error Examples:**
```properties
# This will FAIL if connecting to 'myapp' database
wrongdb.ojp.connection.pool.maximumPoolSize=30

# This will FAIL - username-only prefixes are not supported
admin.ojp.connection.pool.maximumPoolSize=50

# This will SUCCEED when connecting to 'myapp' database as 'admin' user
myapp.admin.ojp.connection.pool.maximumPoolSize=50
```

When invalid prefixes are detected, OJP will fail fast with a clear error message indicating:
- Which prefixes are invalid
- What the expected prefixes should be for the current connection
- The current database name and username

#### Partial Configuration Support

You can configure only some properties at each level:

```properties
# Global timeout settings
ojp.connection.pool.connectionTimeout=10000
ojp.connection.pool.idleTimeout=600000

# Database-specific pool sizes
myapp.ojp.connection.pool.maximumPoolSize=50

# User-specific minimum idle (inherits other settings from above)
myapp.admin.ojp.connection.pool.minimumIdle=15
```

### Connection Pool Fallback Behavior

- If no `ojp.properties` file is found, all default values are used
- If a property is missing from the file, the hierarchical lookup is performed
- If a property has an invalid value, it falls back to the next level in the hierarchy
- If no valid value is found at any level, the built-in default is used
- All validation and configuration logic is handled on the server side

## JDBC Driver Usage

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