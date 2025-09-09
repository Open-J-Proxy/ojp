# Data Source-based Connection Pool Configuration Examples

This document provides practical examples of the new data source-based connection pool configuration feature.

## Basic Example

### Step 1: Configure ojp.properties

Create an `ojp.properties` file in your application's classpath:

```properties
# Default data source (used when no dataSourceName is specified)
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# Fast data source for real-time operations
fast.ojp.connection.pool.maximumPoolSize=50
fast.ojp.connection.pool.minimumIdle=10
fast.ojp.connection.pool.connectionTimeout=5000

# Batch data source for background processing
batch.ojp.connection.pool.maximumPoolSize=10
batch.ojp.connection.pool.minimumIdle=2
batch.ojp.connection.pool.idleTimeout=600000

# Analytics data source for reporting
analytics.ojp.connection.pool.maximumPoolSize=20
analytics.ojp.connection.pool.minimumIdle=5
analytics.ojp.connection.pool.maxLifetime=1800000
```

### Step 2: Use Named Data Sources in Your Application

```java
import java.sql.*;
import java.util.Properties;

public class DataSourceExample {
    public static void main(String[] args) throws SQLException {
        // Using the default data source
        Connection defaultConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059]_h2:mem:myapp", "sa", "");
        
        // Using the fast data source for real-time operations
        Properties fastProps = new Properties();
        fastProps.setProperty("user", "sa");
        fastProps.setProperty("password", "");
        fastProps.setProperty("dataSourceName", "fast");
        Connection fastConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059]_h2:mem:myapp", fastProps);
        
        // Using the batch data source for background processing
        Properties batchProps = new Properties();
        batchProps.setProperty("user", "sa");
        batchProps.setProperty("password", "");
        batchProps.setProperty("dataSourceName", "batch");
        Connection batchConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059]_h2:mem:myapp", batchProps);
        
        // Use connections for different purposes...
        performFastOperation(fastConn);
        performBatchOperation(batchConn);
        performDefaultOperation(defaultConn);
        
        // Close connections
        fastConn.close();
        batchConn.close();
        defaultConn.close();
    }
    
    private static void performFastOperation(Connection conn) throws SQLException {
        // Real-time operations with optimized pool settings
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, 123);
            ResultSet rs = ps.executeQuery();
            // Process results...
        }
    }
    
    private static void performBatchOperation(Connection conn) throws SQLException {
        // Batch operations with different pool characteristics
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE inventory SET status = 'processed' WHERE batch_id = 456");
        }
    }
    
    private static void performDefaultOperation(Connection conn) throws SQLException {
        // Standard operations using default pool settings
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT COUNT(*) FROM orders");
        }
    }
}
```

## Advanced Examples

### Multi-Database Configuration

```properties
# Primary database pools
primary-read.ojp.connection.pool.maximumPoolSize=40
primary-read.ojp.connection.pool.minimumIdle=8

primary-write.ojp.connection.pool.maximumPoolSize=20
primary-write.ojp.connection.pool.minimumIdle=5

# Analytics database pools
analytics-etl.ojp.connection.pool.maximumPoolSize=15
analytics-etl.ojp.connection.pool.minimumIdle=3
analytics-etl.ojp.connection.pool.idleTimeout=900000

analytics-query.ojp.connection.pool.maximumPoolSize=25
analytics-query.ojp.connection.pool.minimumIdle=5

# Reporting database pool
reports.ojp.connection.pool.maximumPoolSize=10
reports.ojp.connection.pool.minimumIdle=2
reports.ojp.connection.pool.maxLifetime=1200000
```

### Application Component Separation

```java
public class UserService {
    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "app_user");
        props.setProperty("password", "app_password");
        props.setProperty("dataSourceName", "user-service");  // Dedicated pool
        return DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost/myapp", props);
    }
}

public class ReportService {
    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "report_user");
        props.setProperty("password", "report_password");
        props.setProperty("dataSourceName", "reports");  // Optimized for long-running queries
        return DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost/analytics", props);
    }
}

public class BatchProcessor {
    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "batch_user");
        props.setProperty("password", "batch_password");
        props.setProperty("dataSourceName", "batch-processing");  // Smaller pool, longer timeouts
        return DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost/myapp", props);
    }
}
```

## Configuration Reference

### Data Source Naming Rules

- Use descriptive names that reflect the purpose: `user-api`, `batch-processing`, `analytics-etl`
- Avoid database-specific names for security: use `primary-read` instead of `userdb-read`
- Use consistent naming conventions: `service-operation` or `service_operation`

### Pool Sizing Guidelines

| Use Case | Max Pool Size | Min Idle | Connection Timeout | Idle Timeout |
|----------|---------------|----------|-------------------|--------------|
| Real-time API | 50-100 | 10-20 | 3-5 seconds | 5-10 minutes |
| Background Jobs | 5-15 | 2-5 | 10-30 seconds | 10-30 minutes |
| Reporting | 10-25 | 3-8 | 10-15 seconds | 15-60 minutes |
| ETL/Batch | 5-20 | 2-5 | 30-60 seconds | 30-120 minutes |

### Error Handling

If you request a non-existent data source, OJP will fail fast with a clear error:

```
Data source 'unknown' not found. Available data sources: [default, fast, batch, analytics]. 
Please ensure the data source is configured in your ojp.properties file using the format: 
unknown.ojp.connection.pool.* or ojp.connection.pool.* for the default data source.
```

## Migration from Old Configuration

### Before (Simple Configuration)
```properties
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
```

### After (Data Source-based Configuration)
```properties
# Default remains the same
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5

# Add specific data sources for optimization
fast-api.ojp.connection.pool.maximumPoolSize=50
fast-api.ojp.connection.pool.minimumIdle=10

batch-jobs.ojp.connection.pool.maximumPoolSize=10
batch-jobs.ojp.connection.pool.minimumIdle=2
```

Your existing code will continue to work with the default data source, and you can gradually migrate to use specific data sources where beneficial.