# Code Review Question Template

Use this template for questions that present code or configuration and ask to identify issues or improvements.

---

## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Medium/Hard - Code reviews are typically not Easy]  
**Type**: Code Review  
**Category**: [Foundation/Configuration/Advanced Features/Operations/Development]  
**Topic**: [Specific topic]  
**Reference**: [eBook Chapter X: Title, Section X.X]

**Scenario (optional):**
[Brief context if needed - 1-2 sentences]

**Question:**
Review the following [code/configuration/command]. Identify the issue/what's wrong/what needs to be improved.

**Code/Configuration:**
```[language]
[Your code or configuration here]
[Should be realistic and contain 1-3 identifiable issues]
[Keep it concise - 5-20 lines typically]
```

**Options:**
A) [First potential issue or fix]
B) [Second potential issue or fix]
C) [Third potential issue or fix]
D) [Fourth potential issue or fix]

**Correct Answer:** [Letter]

**Explanation:**
[Explain:
- What the issue is
- Why it's a problem
- How to fix it correctly
- What the corrected version looks like]

**Corrected Version:**
```[language]
[Show the corrected code/configuration]
```

**Distractor Analysis:**
- A) [Why this is not the main issue or would not fix the problem]
- B) [Explain why this is not correct]
- C) [Explain why this is not the issue]
- D) [Explain why this is not the problem]

**Tags**: #category #difficulty #code-review #topic #[language]

---

## Example: Configuration Code Review

## Question 92: OJP JDBC URL Configuration Error

**Difficulty**: Medium  
**Type**: Code Review  
**Category**: Configuration  
**Topic**: JDBC URL Format  
**Reference**: Chapter 5: JDBC Configuration, Section 5.1

**Scenario:**
A developer is trying to connect to an Oracle database using OJP but keeps getting connection errors.

**Question:**
Review the following JDBC URL configuration. What is wrong with this URL?

**Code:**
```java
String jdbcUrl = "jdbc:ojp_oracle:thin:@localhost:1521/XEPDB1[localhost:1059]";
```

**Options:**
A) The Oracle connection string format is incorrect
B) The OJP server location should come before the database type, not after
C) The port 1059 should be 1058
D) The oracle thin driver prefix is missing "jdbc:"

**Correct Answer:** B

**Explanation:**
In OJP JDBC URLs, the server location must appear immediately after the `ojp` prefix and before the underlying database driver type. The correct format is: `jdbc:ojp[host:port]_drivertype`. The OJP server location in square brackets should come right after `ojp` and before the underscore, not at the end.

**Corrected Version:**
```java
String jdbcUrl = "jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1";
```

**Distractor Analysis:**
- A) The Oracle connection string format (`thin:@localhost:1521/XEPDB1`) is actually correct
- C) Port 1059 is the correct default OJP server port
- D) The `jdbc:` prefix is present at the beginning, so this is not the issue

**Tags**: #configuration #medium #code-review #jdbc-url #oracle #syntax-error

---

## Example: Java Code Review

## Question 145: Connection Pool Configuration in Spring Boot

**Difficulty**: Medium  
**Type**: Code Review  
**Category**: Configuration  
**Topic**: Framework Integration  
**Reference**: Chapter 7: Framework Integration, Section 7.1

**Scenario:**
A Spring Boot application using OJP is experiencing performance issues with excessive connections being created.

**Question:**
Review the following Spring Boot configuration. Identify the configuration issue that would cause double connection pooling.

**Code:**
```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariConfig hikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(50);
        config.setMinimumIdle(10);
        return config;
    }
    
    @Bean
    public DataSource dataSource() {
        HikariConfig config = hikariConfig();
        config.setJdbcUrl("jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb");
        config.setUsername("user");
        config.setPassword("password");
        return new HikariDataSource(config);
    }
}
```

**Options:**
A) The JDBC URL is malformed
B) HikariCP is being explicitly configured when OJP already provides connection pooling
C) The username and password should not be set in the DataSource configuration
D) The maximum pool size of 50 is too high for OJP

**Correct Answer:** B

**Explanation:**
When using OJP, connection pooling is handled by the OJP server, not by the client application. This configuration explicitly creates a HikariCP connection pool on the client side, which results in double pooling - both HikariCP on the client and the OJP server maintain separate connection pools. This defeats the purpose of OJP and can lead to connection exhaustion and poor resource utilization.

**Corrected Version:**
```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    public DataSource dataSource() {
        // Use simple DataSource, not HikariCP
        // OJP handles pooling on the server side
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.openjproxy.jdbc.Driver");
        dataSource.setUrl("jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb");
        dataSource.setUsername("user");
        dataSource.setPassword("password");
        return dataSource;
    }
}
```

Alternatively, in application.properties:
```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
# Disable Spring Boot's connection pooling
spring.datasource.hikari.maximum-pool-size=0
```

**Distractor Analysis:**
- A) The JDBC URL format is correct for OJP
- C) Setting username and password in DataSource configuration is standard practice and not an issue
- D) The pool size value itself is not the problem; the issue is having client-side pooling at all

**Tags**: #configuration #medium #code-review #spring-boot #hikaricp #double-pooling #java

---

## Example: Advanced Code Review

## Question 201: Custom Pool Provider Implementation

**Difficulty**: Hard  
**Type**: Code Review  
**Category**: Development  
**Topic**: Pool Provider SPI  
**Reference**: Chapter 12: Connection Pool Provider SPI, Section 12.3

**Question:**
Review this custom connection pool provider implementation. Identify the critical bug that would cause connection leaks.

**Code:**
```java
public class CustomPoolProvider implements PoolProvider {
    
    @Override
    public Pool createPool(PoolConfiguration config) {
        return new CustomPool(config);
    }
    
    private static class CustomPool implements Pool {
        private final Queue<Connection> availableConnections;
        private final PoolConfiguration config;
        
        public CustomPool(PoolConfiguration config) {
            this.config = config;
            this.availableConnections = new ConcurrentLinkedQueue<>();
            initializePool();
        }
        
        private void initializePool() {
            for (int i = 0; i < config.getMinimumIdle(); i++) {
                availableConnections.offer(createConnection());
            }
        }
        
        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = availableConnections.poll();
            if (conn == null || conn.isClosed()) {
                conn = createConnection();
            }
            return conn;
        }
        
        @Override
        public void close() {
            for (Connection conn : availableConnections) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // log error
                }
            }
        }
        
        private Connection createConnection() {
            // create and return new connection
        }
    }
}
```

**Options:**
A) The initializePool() method should be synchronized
B) Connections are never returned to the pool after use
C) The ConcurrentLinkedQueue is not thread-safe
D) The close() method should throw SQLException

**Correct Answer:** B

**Explanation:**
This implementation has a critical connection leak: connections retrieved via `getConnection()` are never returned to the pool. The pool dispenses connections but has no mechanism to receive them back. Users would need to somehow return connections to `availableConnections`, but there's no method to do so. A proper implementation needs a `returnConnection(Connection conn)` method or should wrap connections in a proxy that automatically returns them when closed.

**Corrected Approach:**
```java
@Override
public Connection getConnection() throws SQLException {
    Connection conn = availableConnections.poll();
    if (conn == null || conn.isClosed()) {
        conn = createConnection();
    }
    // Wrap connection to intercept close() and return to pool
    return new ConnectionWrapper(conn, this::returnConnection);
}

private void returnConnection(Connection conn) {
    if (availableConnections.size() < config.getMaximumPoolSize()) {
        availableConnections.offer(conn);
    } else {
        try {
            conn.close();  // Truly close if pool is full
        } catch (SQLException e) {
            // log error
        }
    }
}
```

**Distractor Analysis:**
- A) initializePool() runs only during construction (single-threaded), so synchronization isn't needed there
- C) ConcurrentLinkedQueue is indeed thread-safe; this is not the issue
- D) The close() method signature is correct; Pool.close() does not throw SQLException in the SPI

**Tags**: #development #hard #code-review #pool-provider-spi #connection-leak #java #bugs

---

## Tips for Writing Code Review Questions

### Code Selection:
- ✅ Use realistic code snippets
- ✅ Include 1-3 identifiable issues
- ✅ Keep code concise (under 30 lines)
- ✅ Ensure syntax is correct (except for the issue being tested)
- ✅ Use proper formatting and indentation

### Issue Types to Test:
- Configuration errors
- Common bugs or antipatterns
- Resource leaks
- Security vulnerabilities
- Performance issues
- Missing error handling
- Incorrect API usage

### What Makes Good Code Review Questions:
- ✅ Tests ability to spot real problems
- ✅ Issues are things developers actually make
- ✅ Requires understanding of concepts, not just syntax
- ✅ Can be fixed with knowledge from the eBook
- ✅ Teaches something valuable

### What to Avoid:
- ❌ Purely stylistic issues (indentation, naming)
- ❌ Syntax errors (unless testing syntax knowledge)
- ❌ Multiple unrelated issues (confuses focus)
- ❌ Issues requiring external knowledge
- ❌ Overly complex code

---

**Template Version**: 1.0  
**Last Updated**: 2026-02-09
