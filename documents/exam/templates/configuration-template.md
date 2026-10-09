# Configuration Question Template

Use this template for questions focused on configuration files, properties, and setup.

---

## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Easy/Medium/Hard]  
**Type**: Configuration  
**Category**: [Foundation/Configuration/Advanced Features/Operations/Development]  
**Topic**: [Specific topic]  
**Reference**: [eBook Chapter X: Title, Section X.X]

**Scenario (if applicable):**
[Brief context - 1-2 sentences]

**Question:**
[Ask about configuration property, value, file location, or approach]

**Configuration Example (if applicable):**
```[properties/yaml/xml/java]
[Show configuration that's being asked about]
```

**Options:**
A) [First option]
B) [Second option]
C) [Third option]
D) [Fourth option]

**Correct Answer:** [Letter]

**Explanation:**
[Explain the correct configuration, why it works, and what it accomplishes]

**Configuration Details:**
[Additional information about the configuration property or approach]

**Distractor Analysis:**
- A) [Why this configuration is incorrect]
- B) [Why this won't work]
- C) [Why this is not the right approach]
- D) [Why this is incorrect]

**Tags**: #category #difficulty #configuration #topic

---

## Example: Server Configuration

## Question 67: Configuring Custom Server Port

**Difficulty**: Easy  
**Type**: Configuration  
**Category**: Configuration  
**Topic**: Server Configuration  
**Reference**: Chapter 6: Server Configuration, Section 6.1

**Question:**
You need to run multiple OJP servers on the same machine for testing. Which system property should you use to configure a custom gRPC port for the second server instance?

**Options:**
A) `-Dojp.port=10593`
B) `-Dojp.server.port=10593`
C) `-Dgrpc.server.port=10593`
D) `-Dserver.port=10593`

**Correct Answer:** B

**Explanation:**
The correct system property for configuring the OJP server's gRPC port is `ojp.server.port`. This allows you to override the default port (1059) when starting the server. For example: `java -Dojp.server.port=10593 -jar ojp-server.jar`

**Configuration Details:**
You can also use the environment variable `OJP_SERVER_PORT=10593` to achieve the same result. This is useful in containerized environments or when you cannot easily pass system properties.

**Distractor Analysis:**
- A) `ojp.port` is not the correct property name; missing the `server` component
- C) `grpc.server.port` is not used by OJP; the naming convention is `ojp.server.port`
- D) `server.port` is too generic and is actually used by Spring Boot, not OJP

**Tags**: #configuration #easy #server #ports #system-properties

---

## Example: JDBC Configuration

## Question 98: Connection Pool Configuration

**Difficulty**: Medium  
**Type**: Configuration  
**Category**: Configuration  
**Topic**: JDBC Configuration  
**Reference**: Chapter 5: JDBC Configuration, Section 5.2

**Scenario:**
You're deploying an OJP-based application to production and need to optimize connection pool settings for a high-traffic API that handles 1000 requests per second.

**Question:**
Which configuration approach provides the best connection pool settings for high-throughput scenarios?

**Configuration Examples:**

**Options:**
A) 
```properties
ojp.datasource.maximumPoolSize=10
ojp.datasource.minimumIdle=5
ojp.datasource.connectionTimeout=30000
```

B) 
```properties
ojp.datasource.maximumPoolSize=100
ojp.datasource.minimumIdle=50
ojp.datasource.connectionTimeout=5000
```

C) 
```properties
ojp.datasource.maximumPoolSize=50
ojp.datasource.minimumIdle=10
ojp.datasource.connectionTimeout=10000
```

D) 
```properties
# Let OJP use default values
# No configuration needed
```

**Correct Answer:** C

**Explanation:**
Option C provides a balanced configuration for high-throughput scenarios. A maximum pool size of 50 provides sufficient concurrency without overwhelming the database. A minimum idle of 10 ensures connections are ready without maintaining too many idle connections. A 10-second timeout is reasonable for most queries while preventing indefinite waiting. These settings should be tuned based on actual load testing, but provide a good starting point.

**Configuration Details:**
- `maximumPoolSize`: Total connections in the pool (should be tuned to database capacity)
- `minimumIdle`: Connections kept ready for immediate use
- `connectionTimeout`: Maximum milliseconds to wait for a connection

Best practice is to start with conservative values and increase based on monitoring and performance testing.

**Distractor Analysis:**
- A) Pool size of 10 is too small for 1000 req/sec; will cause connection wait times
- B) Pool size of 100 might be excessive and could strain the database; 5-second timeout might be too aggressive
- D) Default values are conservative and may not be optimal for high-throughput scenarios

**Tags**: #configuration #medium #jdbc #connection-pool #performance #tuning

---

## Example: Framework Integration Configuration

## Question 112: Spring Boot Application Properties

**Difficulty**: Medium  
**Type**: Configuration  
**Category**: Configuration  
**Topic**: Framework Integration  
**Reference**: Chapter 7: Framework Integration, Section 7.1

**Scenario:**
You're integrating OJP with a Spring Boot 3.x application that previously used native PostgreSQL with HikariCP.

**Question:**
Which application.properties configuration correctly integrates OJP while disabling Spring Boot's built-in connection pooling?

**Options:**

A)
```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
spring.datasource.username=user
spring.datasource.password=password
```

B)
```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
spring.datasource.username=user
spring.datasource.password=password
spring.datasource.hikari.maximum-pool-size=0
```

C)
```properties
spring.datasource.url=jdbc:postgresql://db.example.com:5432/mydb
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
spring.datasource.username=user
spring.datasource.password=password
ojp.server.address=localhost:1059
```

D)
```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://db.example.com:5432/mydb
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.username=user
spring.datasource.password=password
spring.datasource.type=com.zaxxer.hikari.HikariDataSource
```

**Correct Answer:** B

**Explanation:**
Option B is the most complete and correct configuration. It:
1. Uses the correct OJP JDBC URL format with the server location
2. Specifies the OJP JDBC driver class
3. Includes credentials
4. Explicitly disables Spring Boot's HikariCP by setting maximum-pool-size to 0

Setting `spring.datasource.hikari.maximum-pool-size=0` is crucial to prevent double connection pooling, as OJP handles pooling on the server side.

**Configuration Details:**
Alternative approaches:
- Set `spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource`
- Configure `spring.datasource.hikari.maximum-pool-size=1` (minimal pool)

**Distractor Analysis:**
- A) Missing the crucial step to disable HikariCP pooling; will result in double pooling
- C) Incorrect URL format (missing OJP prefix); `ojp.server.address` is not a valid Spring Boot property
- D) Wrong driver class (PostgreSQL driver instead of OJP driver); explicitly enables HikariCP

**Tags**: #configuration #medium #spring-boot #framework-integration #hikaricp #double-pooling

---

## Tips for Writing Configuration Questions

### Focus Areas:
- ✅ Property names and syntax
- ✅ Configuration file locations
- ✅ Value ranges and formats
- ✅ Environment-specific settings
- ✅ Configuration precedence

### Good Configuration Questions:
- Test understanding of why configuration is needed
- Include realistic scenarios
- Show actual configuration syntax
- Teach best practices
- Address common misconfigurations

### Question Types:
- Which property to use
- Correct property value
- Complete configuration example
- Troubleshooting misconfigurations
- Best practice configurations

### What to Avoid:
- ❌ Testing memory of exact default values (unless critical)
- ❌ Obscure configuration properties
- ❌ Configuration options that don't exist
- ❌ Overly complex configuration files

---

**Template Version**: 1.0  
**Last Updated**: 2026-02-09
