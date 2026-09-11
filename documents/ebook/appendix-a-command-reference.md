# Appendix A: Command and Configuration Quick Reference

This appendix provides a comprehensive quick reference for all commands, configuration properties, and common patterns used throughout OJP. Use this as a desk reference when working with OJP in development or production environments.

> **🚨 Important for Version 0.4.0-beta and Later:**  
> JDBC drivers must be downloaded before running ojp-server (both JAR and Docker). See [Chapter 4: Database Drivers](part2-chapter4-database-drivers.md) for complete setup instructions.

## Server Commands

### Starting the Server

The most common way to start the OJP server is using the standalone JAR downloaded from Maven Central:

> **⚠️ For v0.4.0-beta and later:** Download JDBC drivers to `ojp-libs/` before starting the server. See [Chapter 4: Database Drivers](part2-chapter4-database-drivers.md) for complete setup instructions.

**Download the JAR from Maven Central** (recommended):

```bash
wget https://repo1.maven.org/maven2/org/openjproxy/ojp-server/1.0.0-RC1/ojp-server-1.0.0-RC1-shaded.jar
chmod +x ojp-server-1.0.0-RC1-shaded.jar
java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1.jar
```

For development with custom configuration, you can specify properties:

```bash
java -Dojp.server.port=9059 \
     -Dojp.telemetry.enabled=true \
     -Dojp.telemetry.prometheus.enabled=true \
     -jar ojp-server-1.0.0-RC1-shaded.jar
```

When running from source during development (see [Building from Source](../runnable-jar/BUILDING_FROM_SOURCE.md)):

```bash
cd ojp-server
mvn exec:java
```

### Docker Deployment

Pull and run the official Docker image:

```bash
docker pull ghcr.io/open-j-proxy/ojp-server:latest
docker run -p 9059:9059 ghcr.io/open-j-proxy/ojp-server:latest
```

With custom configuration:

```bash
docker run -p 9059:9059 \
  -e OJP_SERVER_PORT=9059 \
  -e OJP_TELEMETRY_ENABLED=true \
  -e OJP_TELEMETRY_PROMETHEUS_ENABLED=true \
  ghcr.io/open-j-proxy/ojp-server:latest
```

### Kubernetes/Helm Deployment

Add the Helm repository and install:

```bash
helm repo add ojp https://open-j-proxy.github.io/ojp-helm
helm repo update
helm install my-ojp ojp/ojp-server
```

Custom values installation:

```bash
helm install my-ojp ojp/ojp-server -f custom-values.yaml
```

Upgrade existing deployment:

```bash
helm upgrade my-ojp ojp/ojp-server --reuse-values
```

## Server Configuration Properties

### Core Server Settings

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.server.port` | `9059` | gRPC server listening port |
| `ojp.server.max-inbound-message-size` | `4194304` (4MB) | Maximum request message size |
| `ojp.server.max-connection-idle` | `PT30M` | Maximum connection idle time |
| `ojp.server.max-connection-age` | `PT24H` | Maximum connection age |
| `ojp.server.max-concurrent-calls-per-connection` | `100` | Max concurrent calls per connection |
| `ojp.server.keepalive-time` | `PT2H` | Keepalive ping interval |
| `ojp.server.keepalive-timeout` | `PT20S` | Keepalive ping timeout |
| `ojp.server.permit-keepalive-without-calls` | `false` | Allow keepalive without active calls |
| `ojp.server.permit-keepalive-time` | `PT5M` | Minimum keepalive interval allowed |

### Security Settings

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.server.ip-whitelist` | None | Comma-separated IP/CIDR whitelist |

Example:
```properties
ojp.server.ip-whitelist=10.0.0.0/8,192.168.1.0/24,172.16.0.1
```

### Connection Pool Settings

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.pool.maximum-pool-size` | `10` | Maximum pool size per database |
| `ojp.pool.minimum-idle` | `5` | Minimum idle connections |
| `ojp.pool.connection-timeout` | `30000` (30s) | Connection acquisition timeout |
| `ojp.pool.idle-timeout` | `600000` (10m) | Idle connection timeout |
| `ojp.pool.max-lifetime` | `1800000` (30m) | Maximum connection lifetime |
| `ojp.pool.keepalive-time` | `0` | Connection keepalive time (0=disabled) |
| `ojp.pool.leak-detection-threshold` | `0` | Connection leak detection (0=disabled) |

### Telemetry Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.telemetry.enabled` | `false` | Enable telemetry |
| `ojp.telemetry.prometheus.enabled` | `false` | Enable Prometheus metrics |
| `ojp.telemetry.prometheus.port` | `9090` | Prometheus metrics port |
| `ojp.telemetry.prometheus.path` | `/metrics` | Metrics endpoint path |

### Slow Query Segregation

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.slow-query.enabled` | `false` | Enable slow query segregation |
| `ojp.slow-query.threshold-multiplier` | `2.0` | Threshold multiplier for classification |
| `ojp.slow-query.slow-pool-percentage` | `20` | Percentage of pool for slow queries |
| `ojp.slow-query.monitoring-window` | `PT1M` | Monitoring window duration |

### XA Transaction Support

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.xa.backend-session-pool.max-total` | `100` | Maximum XA backend sessions |
| `ojp.xa.backend-session-pool.max-idle` | `10` | Maximum idle XA sessions |
| `ojp.xa.backend-session-pool.min-idle` | `5` | Minimum idle XA sessions |

## JDBC Driver Configuration

### JDBC URL Format

The standard OJP JDBC URL format:

```
jdbc:ojp://host:port/database?property=value
```

For multinode deployments:

```
jdbc:ojp://host1:port1,host2:port2,host3:port3/database
```

### Connection String Examples

**PostgreSQL:**
```
jdbc:ojp://localhost:9059/postgres?user=dbuser&password=dbpass
```

**MySQL:**
```
jdbc:ojp://localhost:9059/mydb?user=root&password=secret
```

**Oracle:**
```
jdbc:ojp://localhost:9059/ORCL?user=system&password=oracle
```

**SQL Server:**
```
jdbc:ojp://localhost:9059/master?user=sa&password=YourStrong!Passw0rd
```

**H2 (In-Memory):**
```
jdbc:ojp://localhost:9059/mem:testdb?user=sa&password=
```

### Client-Side Pool Configuration (ojp.properties)

Place `ojp.properties` in your classpath with pool settings:

```properties
# HikariCP pool configuration
maximumPoolSize=20
minimumIdle=5
connectionTimeout=30000
idleTimeout=600000
maxLifetime=1800000
leakDetectionThreshold=60000

# Connection validation
validationTimeout=5000
connectionTestQuery=SELECT 1
```

## Maven Dependencies

### Standard JDBC Driver

```xml
<dependency>
    <groupId>com.github.open-j-proxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

### XA DataSource Support

```xml
<dependency>
    <groupId>com.github.open-j-proxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
<!-- Include transaction manager like Atomikos -->
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jdbc</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

## Framework Integration Snippets

### Spring Boot Configuration

**Recommended — Spring Boot Starter (Spring Boot 3.x/4.x, Java 17+):**

Maven dependency:
```xml
<!-- OJP Spring Boot Starter — replaces spring-boot-starter-jdbc -->
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>spring-boot-starter-ojp</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

Complete `application.properties` (starter handles driver and datasource type automatically; all `ojp.*` settings go in the same file):
```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
spring.datasource.username=dbuser
spring.datasource.password=dbpass

# Optional: OJP pool settings (forwarded to the OJP server)
ojp.connection.pool.maximum-pool-size=20
ojp.connection.pool.minimum-idle=5
ojp.connection.pool.connection-timeout=30000
ojp.grpc.max-inbound-message-size=16777216

# To use a named datasource pool on the OJP server, embed the name in the URL:
# spring.datasource.url=jdbc:ojp[localhost:1059(myApp)]_postgresql://localhost:5432/mydb
```

**Manual — Spring Boot 3.x / Java 11 (without starter):**

`application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
    username: dbuser
    password: dbpass
    driver-class-name: org.openjproxy.jdbc.Driver
    type: org.springframework.jdbc.datasource.SimpleDriverDataSource
```

Maven dependency (no starter):
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### Quarkus Configuration

**application.properties:**
```properties
quarkus.datasource.jdbc.url=jdbc:ojp://localhost:9059/mydb
quarkus.datasource.username=dbuser
quarkus.datasource.password=dbpass
quarkus.datasource.driver=io.openjproxy.jdbc.Driver

# Disable Agroal pooling
quarkus.datasource.jdbc.max-size=1
quarkus.datasource.jdbc.min-size=1
```

### Micronaut Configuration

**application.yml:**
```yaml
datasources:
  default:
    url: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
    username: dbuser
    password: dbpass
```

**DataSource Factory:**
```java
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.openjproxy.jdbc.OjpDataSource;

import javax.sql.DataSource;

@Factory
public class DataSourceFactory {

    @Singleton
    @Named("default")
    public DataSource dataSource(
        @Value("${datasources.default.url}") String url,
        @Value("${datasources.default.username}") String user,
        @Value("${datasources.default.password}") String password
    ) {
        return new OjpDataSource(url, user, password);
    }
}
```

## Testing and Development

### Building from Source

```bash
git clone https://github.com/Open-J-Proxy/ojp.git
cd ojp
./download-drivers.sh
mvn clean install
```

### Running Tests

Run all tests:
```bash
mvn test
```

Run PostgreSQL tests only:
```bash
mvn test -Dtest.postgresql.enabled=true
```

Run multiple database tests:
```bash
mvn test -Dtest.postgresql.enabled=true -Dtest.mysql.enabled=true
```

### Docker Compose for Local Development

**docker-compose.yml:**
```yaml
version: '3.8'
services:
  ojp-server:
    image: ghcr.io/open-j-proxy/ojp-server:latest
    ports:
      - "9059:9059"
      - "9090:9090"
    environment:
      - OJP_TELEMETRY_ENABLED=true
      - OJP_TELEMETRY_PROMETHEUS_ENABLED=true
  
  postgres:
    image: postgres:15
    environment:
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
```

Start services:
```bash
docker-compose up -d
```

## Monitoring and Observability

### Prometheus Metrics Endpoint

Access metrics:
```bash
curl http://localhost:9090/metrics
```

### Common PromQL Queries

**Active connections:**
```promql
hikaricp_connections_active{pool="mydb"}
```

**Pool usage percentage:**
```promql
(hikaricp_connections_active{pool="mydb"} / hikaricp_connections_max{pool="mydb"}) * 100
```

**Connection acquisition rate:**
```promql
rate(hikaricp_connections_acquire_seconds_count{pool="mydb"}[5m])
```

**Slow query count:**
```promql
ojp_slow_queries_total{pool="mydb"}
```

### Grafana Dashboard Import

Import the OJP dashboard (ID: coming soon) or use this query for a simple panel:

```json
{
  "targets": [
    {
      "expr": "hikaricp_connections_active",
      "legendFormat": "{{pool}} - Active"
    },
    {
      "expr": "hikaricp_connections_idle",
      "legendFormat": "{{pool}} - Idle"
    }
  ]
}
```

## Troubleshooting Quick Checks

### Connection Issues

**Check server is running:**
```bash
nc -zv localhost 9059
```

**Check server logs:**
```bash
docker logs <container-id>
# or
journalctl -u ojp-server -f
```

**Verify JDBC driver is loaded:**
```java
Class.forName("io.openjproxy.jdbc.Driver");
```

### Performance Issues

**Check pool exhaustion:**
```bash
curl -s http://localhost:9090/metrics | grep hikaricp_connections
```

**Enable DEBUG logging:**
```properties
logging.level.io.openjproxy=DEBUG
```

**Check for connection leaks:**
```properties
ojp.pool.leak-detection-threshold=30000
```

### Multinode Issues

**Verify all servers are reachable:**
```bash
for host in host1 host2 host3; do
  nc -zv $host 9059
done
```

**Check load distribution:**
```bash
curl http://host1:9090/metrics | grep grpc_server_handled_total
curl http://host2:9090/metrics | grep grpc_server_handled_total
curl http://host3:9090/metrics | grep grpc_server_handled_total
```

## Environment Variables

All configuration properties can be set via environment variables using this pattern:

```bash
OJP_SERVER_PORT=9059
OJP_POOL_MAXIMUM_POOL_SIZE=20
OJP_TELEMETRY_ENABLED=true
OJP_SLOW_QUERY_ENABLED=true
```

The pattern is: `OJP_` + property path in UPPER_SNAKE_CASE.

---

**IMAGE_PROMPT_A1:** Create a layered command reference infographic showing three tiers: "Server Commands" (top - Docker, Helm, JAR), "Configuration" (middle - properties organized by category), and "Monitoring" (bottom - metrics and health checks). Use color coding: blue for server operations, green for configuration, orange for monitoring. Include icons for each command type and show relationships with connecting lines.

This quick reference appendix provides the essential commands and configurations you'll need for day-to-day work with OJP. Keep it handy when setting up new environments, troubleshooting issues, or optimizing performance.
