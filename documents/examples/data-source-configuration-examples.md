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

public class DataSourceExample {
    public static void main(String[] args) throws SQLException {
        // Using the default data source
        Connection defaultConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059]_h2:mem:myapp", "sa", "");
        
        // Using the fast data source for real-time operations
        Connection fastConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059>fast]_h2:mem:myapp", "sa", "");
        
        // Using the batch data source for background processing
        Connection batchConn = DriverManager.getConnection(
            "jdbc:ojp[localhost:1059>batch]_h2:mem:myapp", "sa", "");
        
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
        return DriverManager.getConnection(
            "jdbc:ojp[localhost:1059>user-service]_postgresql://localhost/myapp", 
            "app_user", "app_password");
    }
}

public class ReportService {
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
            "jdbc:ojp[localhost:1059>reports]_postgresql://localhost/analytics", 
            "report_user", "report_password");
    }
}

public class BatchProcessor {
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
            "jdbc:ojp[localhost:1059>batch-processing]_postgresql://localhost/myapp", 
            "batch_user", "batch_password");
    }
}
```

## Configuration Reference

### Data Source Naming Rules

- Use descriptive names that reflect the purpose: `user-api`, `batch-processing`, `analytics-etl`
- Avoid database-specific names for security: use `primary-read` instead of `userdb-read`
- Use consistent naming conventions: `service-operation` or `service_operation`

### Error Handling

If you request a non-existent data source, OJP will fail fast with a clear error:

```
Data source 'unknown' not found. Available data sources: [default, fast, batch, analytics]. 
Please ensure the data source is configured in your ojp.properties file using the format: 
unknown.ojp.connection.pool.* or ojp.connection.pool.* for the default data source.
```