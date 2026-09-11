# Chapter 5: OJP JDBC Driver Configuration

> **Chapter Overview**: Configure the OJP JDBC driver on the client side with connection pool settings, multi-datasource support, and framework integration. This chapter covers advanced configuration patterns that optimize performance for different application workloads.

---

## 5.1 JDBC URL Format

The OJP JDBC URL follows a specific format that wraps your existing database JDBC URL.

### URL Structure

**[IMAGE PROMPT 1]**: Create a detailed URL anatomy diagram:
Break down the OJP URL into components with labels and examples:
- Protocol prefix (jdbc:ojp)
- Server location [host:port]
- Optional datasource name (datasourceName)
- Separator (_)
- Original JDBC URL (database-specific)
Use color coding for each component
Professional URL specification guide style

```mermaid
graph LR
    A["jdbc:ojp"] --> B["[localhost:1059]"]
    B --> C["(mainApp)"]
    C --> D["_postgresql://"]
    D --> E["localhost:5432/mydb"]
    
    A1[Protocol<br/>Prefix] -.-> A
    B1[OJP Server<br/>Location] -.-> B
    C1[DataSource Name<br/>Optional] -.-> C
    D1[Database<br/>Type] -.-> D
    E1[Database<br/>Connection] -.-> E
    
    style A fill:#4caf50
    style B fill:#ffd54f
    style C fill:#90caf9
    style D fill:#ff8a65
    style E fill:#ba68c8
```

**Basic Format**:
```
jdbc:ojp[server-host:server-port]_original-jdbc-url
```

**With DataSource Name**:
```
jdbc:ojp[server-host:server-port(datasource-name)]_original-jdbc-url
```

### URL Components Explained

| Component | Description | Example | Required |
|-----------|-------------|---------|----------|
| `jdbc:ojp` | OJP protocol prefix | `jdbc:ojp` | Yes |
| `[host:port]` | OJP Server location | `[localhost:1059]` | Yes |
| `(datasource)` | Named datasource | `(mainApp)` | No |
| `_` | Separator | `_` | Yes |
| Original URL | Database JDBC URL | `postgresql://...` | Yes |

### Multi-Database Examples

**[IMAGE PROMPT 2]**: Create a reference card showing URL examples for all major databases:
PostgreSQL, MySQL, MariaDB, H2, Oracle, SQL Server, DB2
Show both before and after OJP URL format
Use database vendor colors and logos
Professional quick reference card style

**PostgreSQL**:
```java
// Before OJP
String before = "jdbc:postgresql://localhost:5432/mydb";

// After OJP
String after = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";

// With datasource name
String named = "jdbc:ojp[localhost:1059(mainApp)]_postgresql://localhost:5432/mydb";

// With connection parameters (including SSL)
String params = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb?" +
                "ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";
// For SSL configuration details between OJP and database, see Chapter 11: Security & Network Architecture
```

**MySQL**:
```java
// Standard MySQL
String mysql = "jdbc:ojp[localhost:1059]_mysql://localhost:3306/mydb";

// With timezone
String tz = "jdbc:ojp[localhost:1059]_mysql://localhost:3306/mydb?" +
            "serverTimezone=UTC";

// With charset
String charset = "jdbc:ojp[localhost:1059]_mysql://localhost:3306/mydb?" +
                 "useUnicode=true&characterEncoding=utf8";
```

**Oracle**:
```java
// Thin connection with service name
String service = "jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1";

// Thin connection with SID
String sid = "jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521:XE";

// TNS Names
String tns = "jdbc:ojp[localhost:1059]_oracle:thin:@PROD_DB";

// Oracle RAC
String rac = "jdbc:ojp[localhost:1059]_oracle:thin:@" +
             "(DESCRIPTION=" +
             "(ADDRESS_LIST=" +
             "(ADDRESS=(PROTOCOL=TCP)(HOST=rac1)(PORT=1521))" +
             "(ADDRESS=(PROTOCOL=TCP)(HOST=rac2)(PORT=1521)))" +
             "(CONNECT_DATA=(SERVICE_NAME=racdb)))";
```

**SQL Server**:
```java
// With database name
String db = "jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;" +
            "databaseName=mydb;encrypt=false";

// With instance
String instance = "jdbc:ojp[localhost:1059]_sqlserver://localhost;" +
                  "instanceName=SQLEXPRESS;databaseName=mydb";

// Windows authentication
String winAuth = "jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;" +
                 "databaseName=mydb;integratedSecurity=true";
```

**H2**:
```java
// File-based
String file = "jdbc:ojp[localhost:1059]_h2:~/testdb";

// In-memory
String mem = "jdbc:ojp[localhost:1059]_h2:mem:testdb";

// With options
String opts = "jdbc:ojp[localhost:1059]_h2:~/testdb;AUTO_SERVER=TRUE";
```

### Connection Parameters

**[IMAGE PROMPT 3]**: Create an infographic showing how parameters are passed through:
Application → OJP URL with params → OJP Server → Database with params
Highlight that database-specific parameters are preserved
Use data flow diagram style
Professional parameter handling guide

Database-specific parameters are passed through to the underlying database:

```java
// PostgreSQL with SSL
String pgSsl = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb?" +
               "ssl=true&sslmode=require&sslcert=/path/to/cert.pem";

// MySQL with connection options
String myOpts = "jdbc:ojp[localhost:1059]_mysql://localhost:3306/mydb?" +
                "useSSL=true&requireSSL=true&" +
                "serverTimezone=UTC&" +
                "maxAllowedPacket=1048576";

// Oracle with connection properties
String orOpts = "jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1?" +
                "oracle.net.encryption_client=REQUIRED&" +
                "oracle.net.encryption_types_client=(AES256)";
```

**Key Point**: OJP passes all database-specific parameters directly to the underlying driver. Configure SSL, timeouts, and other database settings as you normally would.

### Connection Close Semantics

When your application calls `Connection.close()`, the OJP JDBC driver terminates the server-side session **synchronously by default**. In practical terms, `close()` blocks until the server-side session has been terminated.

This default behavior is correctness-oriented because it guarantees that the session is fully released before the application proceeds.

If you need non-blocking behavior, set:

```properties
ojp.jdbc.connection.close.synchronous=false
```

#### Retry Behavior During Close

If the termination call fails because of a **connection-level** problem, the driver retries it up to **3 times** before giving up. This retry applies to failures such as:

- gRPC `UNAVAILABLE`
- gRPC `DEADLINE_EXCEEDED`
- comparable transport/connectivity failures

The driver does **not** retry when the failure indicates that retrying will not help, such as:

- `NOT_FOUND` / pool or session not found
- server-side `SQLException`
- other non-connectivity failures

So the operational rule is simple:

- default (`ojp.jdbc.connection.close.synchronous=true`): synchronous close, failures are surfaced to the caller
- configured asynchronous (`false`): async close, failures are logged

---

## 5.2 Connection Pool Settings

OJP Server supports multiple connection pool implementations:
- **HikariCP** (default for non-XA connections) - High-performance JDBC connection pool
- **Apache DBCP** (alternative for non-XA connections) - Traditional pooling implementation  
- **Custom XA Implementation** (for XA transactions) - Uses Apache Commons Pool 2 for backend session pooling (see Chapter 10 for details)

These pools run on the OJP Server side, but you configure them from the client side via the `ojp.properties` file in your application. This allows you to tune the server-side connection pool behavior without modifying server configuration files. For XA-specific pooling architecture, refer to Chapter 10 which explains the dual-condition lifecycle and backend session management.

### Configuration File Location

**[IMAGE PROMPT 4]**: Create a file location diagram showing:
- Classpath root (src/main/resources)
- ojp.properties file
- Application loading configuration
- Multiple datasource configs in one file
Use file system and classpath visualization
Professional configuration management guide

The `ojp.properties` file should be placed in your application's classpath root:

```
src/
└── main/
    └── resources/
        └── ojp.properties   ← Place here
```

**Alternative locations**:
- JAR root: `/ojp.properties` (inside the JAR)
- Working directory: `./ojp.properties` (for quick testing)
- Custom location: Use system property `-Dojp.config.file=/path/to/ojp.properties`

### Basic Configuration

**[IMAGE PROMPT 5]**: Create a configuration example showing:
Default datasource configuration with common settings
Side annotations explaining each property
Use code editor style with syntax highlighting
Professional configuration guide

**Example `ojp.properties`**:

```properties
# Default DataSource Configuration (backward compatible)
# These settings apply when no datasource name is specified in the URL

# Maximum pool size - max concurrent connections
ojp.connection.pool.maximumPoolSize=20

# Minimum idle connections - kept ready for use
ojp.connection.pool.minimumIdle=5

# Connection timeout - max wait for connection (milliseconds)
ojp.connection.pool.connectionTimeout=30000

# Idle timeout - close unused connections after this time (milliseconds)
ojp.connection.pool.idleTimeout=600000

# Max lifetime - maximum time a connection can exist (milliseconds)
ojp.connection.pool.maxLifetime=1800000

# Connection test query (optional, auto-detected if not set)
ojp.connection.pool.connectionTestQuery=SELECT 1

# Leak detection threshold - warn if connection held too long (milliseconds)
ojp.connection.pool.leakDetectionThreshold=60000
```

### Configuration Reference

**[IMAGE PROMPT 6]**: Create a comprehensive configuration reference table:
Show all HikariCP properties with:
- Property name
- Default value
- Recommended range
- Use case
Use table/matrix format
Professional reference documentation style

```mermaid
graph TB
    subgraph "Connection Pool Lifecycle"
    CREATE[Connection<br/>Created]
    IDLE[Idle Pool<br/>minimumIdle]
    ACTIVE[Active<br/>In Use]
    TIMEOUT[Idle Timeout<br/>Close]
    LIFETIME[Max Lifetime<br/>Close]
    end
    
    CREATE --> IDLE
    IDLE --> ACTIVE
    ACTIVE --> IDLE
    IDLE --> TIMEOUT
    ACTIVE --> LIFETIME
    
    style IDLE fill:#4caf50
    style ACTIVE fill:#ffd54f
    style TIMEOUT fill:#ff5252
    style LIFETIME fill:#ff5252
```

**Complete Property Reference**:

| Property | Type | Default | Description | Since |
|----------|------|---------|-------------|-------|
| `ojp.connection.pool.enabled` | boolean | true | Enable/disable connection pooling | 0.2.0-beta |
| `ojp.connection.pool.maximumPoolSize` | int | 20 | Maximum number of connections in pool | 0.2.0-beta |
| `ojp.connection.pool.minimumIdle` | int | 5 | Minimum number of idle connections | 0.2.0-beta |
| `ojp.connection.pool.connectionTimeout` | long | 10000 | Max wait for connection (ms) | 0.2.0-beta |
| `ojp.connection.pool.idleTimeout` | long | 600000 | Max idle time before close (ms) | 0.2.0-beta |
| `ojp.connection.pool.maxLifetime` | long | 1800000 | Max connection lifetime (ms) | 0.2.0-beta |
| `ojp.connection.pool.leakDetectionThreshold` | long | 0 | Connection leak detection (ms, 0=off) | 0.2.0-beta |
| `ojp.connection.pool.defaultTransactionIsolation` | string/int | READ_COMMITTED | Default transaction isolation level | 0.2.0-beta |

### Pool Sizing Guidelines

**[IMAGE PROMPT 7]**: Create an infographic showing pool sizing recommendations:
Show relationship between: # of CPU cores, concurrent requests, optimal pool size
Include formulas and examples for different workloads
Use charts and calculations
Professional capacity planning guide

**Sizing Formula** (General Guidance):

The most well-known pool sizing guidance comes from Brett Wooldridge (creator of HikariCP) and the PostgreSQL wiki:
```
Pool Size = (number of CPU cores × 2) + effective spindle count
```

For application-centric sizing based on concurrent thread load, an alternative formula is:
```
Pool Size = (Tn × (Cm - 1)) + 1

Where:
Tn = Number of threads making requests
Cm = Connection multiplier (1.5-2.0 for most workloads)
```

**Important**: These formulas are provided as general guidance only. Real-world pool sizing depends heavily on your specific workload characteristics, query execution times, transaction patterns, database capabilities, and hardware resources. Start with these recommendations, then adjust based on monitoring data, connection wait times, and actual usage patterns. Always load test with your specific workload before deploying to production.

**Recommendations by Workload**:

| Application Type | Concurrent Requests | Suggested Pool Size |
|------------------|---------------------|---------------------|
| Microservice (lightweight) | 10-20 | 5-10 |
| Web Application | 50-100 | 20-30 |
| API Server | 100-500 | 30-50 |
| Batch Processing | Variable | 10-20 |
| Real-time Analytics | High | 50-100 |

**Example Configurations**:

```properties
# Microservice - Light load
ojp.connection.pool.maximumPoolSize=10
ojp.connection.pool.minimumIdle=3
ojp.connection.pool.connectionTimeout=20000

# Web Application - Medium load  
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=10
ojp.connection.pool.connectionTimeout=30000

# High-traffic API - Heavy load
ojp.connection.pool.maximumPoolSize=50
ojp.connection.pool.minimumIdle=20
ojp.connection.pool.connectionTimeout=40000
ojp.connection.pool.idleTimeout=300000
```

---

## 5.3 Client-Side Configuration

Configure OJP client behavior for specific application needs.

### Properties File Setup

**Full configuration example**:

```properties
# ============================================
# OJP Client Configuration
# ============================================

# Default DataSource Pool Configuration
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
ojp.connection.pool.leakDetectionThreshold=60000

# gRPC Configuration
ojp.grpc.maxInboundMessageSize=16777216  # 16 MB

# Connection retry settings
ojp.connection.retry.maxAttempts=3
ojp.connection.retry.backoffMs=1000
```

### Programmatic Configuration

**[IMAGE PROMPT 8]**: Create a code example showing:
Programmatic configuration via Properties object
Setting properties before getting connection
Use IDE/code style with syntax highlighting
Professional Java configuration guide

For applications that need dynamic configuration (for example, where pool size is computed at
runtime), you can pass `ojp.connection.pool.*` and `ojp.xa.*` properties directly in the
`Properties` object supplied to `DriverManager.getConnection()`. These programmatic values have
the **highest precedence** and override any matching setting in `ojp.properties`, system
properties, or environment variables.

```java
import java.sql.*;
import java.util.Properties;

public class OjpProgrammaticConfig {
    
    public static Connection getConfiguredConnection(int allocatedMaxConnections) throws SQLException {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
        
        // Create properties
        Properties props = new Properties();
        props.setProperty("user", "myuser");
        props.setProperty("password", "mypassword");
        
        // OJP pool properties - override ojp.properties / env vars / system properties
        props.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(allocatedMaxConnections));
        props.setProperty("ojp.connection.pool.minimumIdle", "5");
        props.setProperty("ojp.connection.pool.connectionTimeout", "25000");
        
        // Database-specific properties are passed through to the underlying driver
        props.setProperty("ssl", "true");
        props.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        
        return DriverManager.getConnection(url, props);
    }
    
    public static void main(String[] args) throws SQLException {
        try (Connection conn = getConfiguredConnection(20)) {
            DatabaseMetaData meta = conn.getMetaData();
            System.out.println("Connected to: " + meta.getDatabaseProductName());
        }
    }
}
```

**Property precedence** (highest to lowest):

| Priority | Source | Example |
|----------|--------|---------|
| 1 | `Properties` argument to `getConnection()` | `props.setProperty("ojp.connection.pool.maximumPoolSize", "50")` |
| 2 | Environment variables | `OJP_CONNECTION_POOL_MAXIMUMPOOLSIZE=50` |
| 3 | System properties | `-Dojp.connection.pool.maximumPoolSize=50` |
| 4 | Properties file (`ojp.properties`) | `ojp.connection.pool.maximumPoolSize=50` |

> **Note**: Only `ojp.connection.pool.*` and `ojp.xa.*` keys are read from the `Properties`
> argument. JDBC-standard keys (`user`, `password`) and database-specific keys (`ssl`, etc.) are
> handled separately and are never treated as pool configuration.

### Environment-Specific Configuration

OJP supports native environment-specific configuration files using the naming convention `ojp-{environment}.properties`. This eliminates the need for custom application code to manage different configurations per environment.

**[IMAGE PROMPT 9]**: Create a multi-environment configuration diagram:
Show Development, Testing, Production environments
Each with different pool sizes and timeouts
Use environment-specific color coding
Include file structure showing ojp.properties and ojp-{env}.properties files
Professional multi-environment guide

#### File Structure

Place environment-specific property files in your application's classpath (e.g., `src/main/resources/`):

```
resources/
  ├── ojp.properties           # Default fallback (backward compatible)
  ├── ojp-dev.properties       # Development configuration
  ├── ojp-staging.properties   # Staging configuration
  └── ojp-prod.properties      # Production configuration
```

#### Configuration Files

**Development Environment** (`ojp-dev.properties`):
```properties
# Development - Small pools, verbose logging
ojp.connection.pool.maximumPoolSize=5
ojp.connection.pool.minimumIdle=2
ojp.connection.pool.connectionTimeout=10000
ojp.connection.pool.idleTimeout=300000
```

**Staging Environment** (`ojp-staging.properties`):
```properties
# Staging - Medium pools, production-like settings
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=10
ojp.connection.pool.connectionTimeout=20000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

**Production Environment** (`ojp-prod.properties`):
```properties
# Production - Large pools, optimized settings
ojp.connection.pool.maximumPoolSize=50
ojp.connection.pool.minimumIdle=20
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
ojp.connection.pool.keepaliveTime=300000
```

**Default Fallback** (`ojp.properties`):
```properties
# Fallback configuration - used when no environment specified
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=15000
```

#### Specifying the Environment

OJP automatically loads the appropriate configuration file based on the environment name. The environment can be specified in two ways (with the following precedence):

**1. System Property** (highest priority):
```bash
# Command line
java -Dojp.environment=prod -jar app.jar

# Maven
mvn spring-boot:run -Dojp.environment=staging

# Gradle
./gradlew bootRun -Dojp.environment=dev
```

**2. Environment Variable**:
```bash
# Unix/Linux/macOS
export OJP_ENVIRONMENT=prod
java -jar app.jar

# Windows
set OJP_ENVIRONMENT=prod
java -jar app.jar

# Docker
docker run -e OJP_ENVIRONMENT=prod myapp:latest
```

#### Docker and Kubernetes Deployment

**Docker Compose**:
```yaml
version: '3.8'
services:
  app:
    image: myapp:latest
    environment:
      - OJP_ENVIRONMENT=prod
    # Application will load ojp-prod.properties automatically
```

**Kubernetes Deployment**:
```yaml
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
        # Application will load ojp-prod.properties automatically
```

**Kubernetes ConfigMap** (for dynamic environment setting):
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-config
data:
  environment: "prod"
---
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
          valueFrom:
            configMapKeyRef:
              name: ojp-config
              key: environment
```

#### Loading Behavior

1. **Environment Specified**: OJP loads `ojp-{environment}.properties` first
2. **Fallback**: If environment-specific file not found, falls back to `ojp.properties`
3. **No Configuration**: If no properties files found, uses OJP server defaults
4. **Backward Compatible**: Existing `ojp.properties` files continue to work without any changes

#### Benefits

- ✅ **No Custom Code**: Environment selection happens automatically
- ✅ **Single Deployment Artifact**: All configurations bundled in one JAR/WAR
- ✅ **CI/CD Friendly**: Same artifact deployed across all environments
- ✅ **Docker/Kubernetes Native**: Standard environment variable support
- ✅ **Backward Compatible**: Existing applications continue working unchanged

#### Example: Multi-Environment Setup

```java
// No code changes needed - just specify the environment
// Application code remains the same across all environments
public class MyApp {
    public static void main(String[] args) {
        // OJP automatically loads ojp-{environment}.properties
        // based on OJP_ENVIRONMENT or -Dojp.environment
        
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
        Connection conn = DriverManager.getConnection(url, "user", "pass");
        
        // Connection pool configured based on environment
        // dev: 5 max connections
        // staging: 25 max connections  
        // prod: 50 max connections
    }
}
```

---

## 5.4 Framework Integration Best Practices

Integrate OJP with popular Java frameworks while avoiding common pitfalls.

### Disabling Application-Level Pooling

**[IMAGE PROMPT 10]**: Create a "do's and don'ts" infographic:
DON'T: Application pool + OJP pool (double pooling)
DO: Direct connection + OJP pool
Show resource waste in double pooling scenario
Use warning icons and clear visual contrast
Professional best practices guide

**Critical Rule**: ⚠️ **Disable all application-level connection pooling when using OJP**

```mermaid
graph TB
    subgraph "❌ WRONG: Double Pooling"
    APP1[Application]
    POOL1[HikariCP<br/>20 connections]
    OJP1[OJP Server<br/>20 connections]
    DB1[(Database<br/>400 connections!)]
    
    APP1 --> POOL1
    POOL1 --> OJP1
    OJP1 --> DB1
    end
    
    subgraph "✅ CORRECT: OJP Only"
    APP2[Application]
    OJP2[OJP Server<br/>20 connections]
    DB2[(Database<br/>20 connections)]
    
    APP2 --> OJP2
    OJP2 --> DB2
    end
    
    style POOL1 fill:#ff5252,color:#fff
    style DB1 fill:#ff5252,color:#fff
    style OJP2 fill:#4caf50
    style DB2 fill:#4caf50
```

Double pooling creates a serious problem: application-side connection pools don't call close() on connections, preventing OJP from knowing when a connection has been released by a thread or transaction. This defeats OJP's core purpose of providing centralized connection control and breaks the intelligent connection management that OJP provides.

### Spring Boot Integration

Spring Boot integration with OJP requires disabling the framework's built-in connection pooling to avoid double-pooling issues. The key concepts and patterns are covered briefly below, but for detailed examples, complete configuration options, and troubleshooting guidance, see **Chapter 7: Framework Integration**.

**Quick Reference**:
```yaml
spring:
  datasource:
    url: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
    username: myuser
    password: mypassword
    driver-class-name: org.openjproxy.jdbc.Driver
    # Disable HikariCP - see Chapter 7 for details
```

**See Chapter 7** for complete Spring Boot integration including:
- Full configuration examples for Spring Boot 2.x and 3.x
- SLF4J logging conflict resolution
- Transaction management patterns
- Testing strategies

**Better approach - Custom DataSource**:

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DataSourceConfig {
    
    @Bean
    public DataSource dataSource() {
        // Use simple DataSource without pooling
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.openjproxy.jdbc.Driver");
        dataSource.setUrl("jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb");
        dataSource.setUsername("myuser");
        dataSource.setPassword("mypassword");
        
        return dataSource;
    }
}
```


### Quarkus and Micronaut Integration

Both Quarkus and Micronaut also require special configuration to avoid double-pooling. Each framework has specific patterns for disabling built-in connection pooling and integrating with OJP.

**See Chapter 7** for complete framework integration coverage:
- **Quarkus**: Unpooled mode configuration, native compilation considerations, dev mode setup
- **Micronaut**: Custom DataSource factory patterns, AOP considerations
- **Comparison**: Framework-specific trade-offs and best practices

---

### Transaction Management

**[IMAGE PROMPT 12]**: Create a transaction flow diagram:
Show: Application → Transaction Manager → OJP Connection → Database
Highlight that standard JTA/JDBC transactions work normally
Use transaction sequence diagram style
Professional transaction management guide

OJP fully supports standard JDBC transactions:

```java
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    
    private final JdbcTemplate jdbcTemplate;
    
    // Spring's @Transactional works normally with OJP
    @Transactional
    public void transferFunds(int fromAccount, int toAccount, double amount) {
        // Both operations in same transaction
        jdbcTemplate.update(
            "UPDATE accounts SET balance = balance - ? WHERE id = ?",
            amount, fromAccount
        );
        
        jdbcTemplate.update(
            "UPDATE accounts SET balance = balance + ? WHERE id = ?",
            amount, toAccount
        );
        
        // Transaction commits automatically (or rolls back on exception)
    }
    
    // Manual transaction management also works
    public void manualTransaction() throws SQLException {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
        
        try (Connection conn = DriverManager.getConnection(url, "user", "pass")) {
            conn.setAutoCommit(false);  // Start transaction
            
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE id = 1");
                stmt.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE id = 2");
                
                conn.commit();  // Commit transaction
                System.out.println("Transaction committed");
            } catch (SQLException e) {
                conn.rollback();  // Rollback on error
                System.out.println("Transaction rolled back");
                throw e;
            }
        }
    }
}
```

**Transaction Isolation Levels**:

```java
// Set isolation level (works with OJP)
connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
```

---

## 5.5 Client-Side Throttling

When multiple application instances share the same OJP server, each instance should limit
its own concurrent request count to its fair share of the server's connection pool. Without
this, a burst of requests from one instance can overwhelm the server's admission queue and
cause timeouts for all instances.

OJP's **Client-Side Throttling** handles this automatically. The server sends three numbers
to every connecting client (via `SessionInfo`):

- **`maxAdmission`** — the configured pool size on this node.
- **`clientCount`** — how many distinct application instances are currently connected for
  this database and credential pair.
- **`observedPeak`** — the real in-flight count just before the last admission timeout
  (0 if no timeout has occurred).

The driver uses these to compute a per-instance limit and enforces it with a fail-fast
counter (no blocking). Requests that exceed the limit receive an immediate `SQLException`
rather than queuing and making the server's situation worse.

### Configuration

The throttle mode is controlled by a single driver property:

```properties
# Default: reactive (most adaptive performance for most workloads)
ojp.jdbc.clientThrottle.mode=reactive
```

| Value | Description |
|---|---|
| `reactive` | Adaptive limit only, based on `observedPeak`. **Default.** Delivers the most adaptive performance for most workloads; no fairness guarantee between clients. |
| `combined` | Uses both `maxAdmission` (static fairness) and `observedPeak` (adaptive capacity). Takes the stricter of the two limits. Use for workloads that cannot tolerate any bursts. |
| `proactive` | Static fair-share limit only, based on `maxAdmission` and `clientCount`. Use for workloads that cannot tolerate any bursts and where `observedPeak` cannot be trusted. |
| `off` | Disable throttling entirely (legacy compatibility only). |

For most workloads, `reactive` is correct and no change is needed. Switch to
`combined` or `proactive` only when bursts must be strictly avoided.

### Reactive back-off tuning

Reactive mode uses an AIMD multiplicative decrease factor:

```properties
# Default: gentler back-off
ojp.jdbc.clientThrottle.reactiveDecreaseFactor=0.75
```

- `0.75` (default) reduces concurrency more gradually after overload.
- Use `0.5` (half) or lower when you need more aggressive back-off.
- Valid range is `0 < factor < 1`.

### Disabling per datasource

```properties
# Default datasource: reactive throttling (no change needed)
ojp.jdbc.clientThrottle.mode=reactive

# Disable for a specific datasource (e.g., batch analytics)
analytics.ojp.jdbc.clientThrottle.mode=off
```

> **For a full explanation** of how client throttling works, the formula used, AIMD
> adaptation, in-transaction bypass, and multinode behaviour, see
> **[Chapter 8a: Client-Side Throttling](../ebook/part3-chapter8a-client-throttling.md)**.

---

## Summary

You now have comprehensive knowledge of OJP JDBC driver configuration:

✅ **URL Format**: Understand OJP URL structure and multi-database examples  
✅ **Pool Settings**: Configure HikariCP properties via `ojp.properties`  
✅ **Client Configuration**: Set up environment-specific and programmatic configs  
✅ **Framework Integration**: Properly integrate with Spring Boot, Quarkus, Micronaut  
✅ **Client Throttling**: Limit per-instance concurrent requests with `ojp.jdbc.clientThrottle.mode`  

**Key Takeaways**:
- OJP URL wraps your existing database JDBC URL
- Configure connection pools via `ojp.properties` in classpath
- **Always disable application-level connection pooling**
- Standard JDBC transactions work seamlessly with OJP
- Framework-specific configuration differs but principle remains: no double pooling
- Client throttling is on by default — each instance self-limits to its fair share of the server's pool

In the next chapter, we'll explore OJP Server configuration, covering advanced settings for security, performance, and resilience.

---

**Previous Chapter**: [← Chapter 4: Database Driver Configuration](part2-chapter4-database-drivers.md)  
**Next Chapter**: [Chapter 6: OJP Server Configuration →](part2-chapter6-server-configuration.md)
