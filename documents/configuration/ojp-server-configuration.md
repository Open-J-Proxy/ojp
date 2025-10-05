# OJP Server Complete Configuration Guide

The OJP Server supports comprehensive configuration through both JVM system properties and environment variables. This document covers all available configuration options including server settings, connection pools, slow query segregation, and client-side configuration.

## Server Configuration

The server supports configuration through both JVM system properties and environment variables. JVM system properties take precedence over environment variables when both are specified.

### Core Server Settings

| Property                             | Environment Variable                 | Type    | Default   | Description                                            |
|--------------------------------------|--------------------------------------|---------|-----------|--------------------------------------------------------|
| `ojp.server.port`                    | `OJP_SERVER_PORT`                    | int     | 1059      | gRPC server port                                       |
| `ojp.prometheus.port`                | `OJP_PROMETHEUS_PORT`                | int     | 9159      | Prometheus metrics HTTP server port                    |
| `ojp.server.threadPoolSize`          | `OJP_SERVER_THREADPOOLSIZE`          | int     | 200       | gRPC server thread pool size                           |
| `ojp.server.maxRequestSize`          | `OJP_SERVER_MAXREQUESTSIZE`          | int     | 4194304   | Maximum request size in bytes (4MB)                    |
| `ojp.server.connectionIdleTimeout`   | `OJP_SERVER_CONNECTIONIDLETIMEOUT`   | long    | 30000     | Connection idle timeout in milliseconds                |

### Logging Settings

| Property                      | Environment Variable          | Type    | Default | Description                               |
|-------------------------------|-------------------------------|---------|---------|-------------------------------------------|
| `ojp.server.logLevel`         | `OJP_SERVER_LOGLEVEL`         | string  | INFO    | Log verbosity level (TRACE, DEBUG, INFO, WARN, ERROR) |

### Security Settings

| Property                      | Environment Variable          | Type    | Default   | Description                                              |
|-------------------------------|-------------------------------|---------|-----------|----------------------------------------------------------|
| `ojp.server.allowedIps`       | `OJP_SERVER_ALLOWEDIPS`       | string  | 0.0.0.0/0 | IP whitelist for gRPC server (comma-separated)          |
| `ojp.prometheus.allowedIps`   | `OJP_PROMETHEUS_ALLOWEDIPS`   | string  | 0.0.0.0/0 | IP whitelist for Prometheus endpoint (comma-separated)  |

### OpenTelemetry Settings

| Property                      | Environment Variable          | Type    | Default | Description                                    |
|-------------------------------|-------------------------------|---------|---------|------------------------------------------------|
| `ojp.opentelemetry.enabled`   | `OJP_OPENTELEMETRY_ENABLED`   | boolean | true    | Enable/disable OpenTelemetry instrumentation  |
| `ojp.opentelemetry.endpoint`  | `OJP_OPENTELEMETRY_ENDPOINT`  | string  | ""      | OpenTelemetry exporter endpoint (empty = default) |

### Circuit Breaker Settings

| Property                             | Environment Variable                 | Type | Default | Description                                       |
|--------------------------------------|--------------------------------------|------|---------|---------------------------------------------------|
| `ojp.server.circuitBreakerTimeout`   | `OJP_SERVER_CIRCUITBREAKERTIMEOUT`   | long | 60000   | Circuit breaker timeout once open in milliseconds |
| `ojp.server.circuitBreakerThreshold` | `OJP_SERVER_CIRCUITBREAKERTHRESHOLD` | int  | 3       | Circuit breaker failure threshold                 |

### Slow Query Segregation Settings

| Property                                           | Environment Variable                               | Type    | Default  | Description                                      |
|----------------------------------------------------|----------------------------------------------------|---------|----------|--------------------------------------------------|
| `ojp.server.slowQuerySegregation.enabled`         | `OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED`         | boolean | true     | Enable/disable slow query segregation feature   |
| `ojp.server.slowQuerySegregation.slowSlotPercentage` | `OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE` | int     | 20       | Percentage of slots for slow operations (0-100) |
| `ojp.server.slowQuerySegregation.idleTimeout`     | `OJP_SERVER_SLOWQUERYSEGREGATION_IDLETIMEOUT`     | long    | 10000    | Idle timeout for slot borrowing (milliseconds)  |
| `ojp.server.slowQuerySegregation.slowSlotTimeout` | `OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTTIMEOUT` | long    | 120000   | Timeout for acquiring slow operation slots (ms) |
| `ojp.server.slowQuerySegregation.fastSlotTimeout` | `OJP_SERVER_SLOWQUERYSEGREGATION_FASTSLOTTIMEOUT` | long    | 60000    | Timeout for acquiring fast operation slots (ms) |

## Client-Side Configuration

For JDBC driver and client-side connection pool configuration, see:

- **[OJP JDBC Configuration](ojp-jdbc-configuration.md)** - JDBC driver setup and client connection pool settings

## Configuration Methods

### 1. JVM System Properties

Set configuration using JVM system properties when starting the server:

```bash
java -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dojp.opentelemetry.enabled=false \
     -Dojp.server.threadPoolSize=100 \
     -Dojp.server.circuitBreakerTimeout=120000 \
     -Dojp.server.circuitBreakerThreshold=3 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=25 \
     -Dojp.server.allowedIps="192.168.1.0/24,10.0.0.1" \
     -jar ojp-server.jar
```

### 2. Environment Variables

Set configuration using environment variables:

```bash
export OJP_SERVER_PORT=8080
export OJP_PROMETHEUS_PORT=9091
export OJP_OPENTELEMETRY_ENABLED=false
export OJP_SERVER_THREADPOOLSIZE=100
export OJP_SERVER_CIRCUITBREAKERTIMEOUT=120000
export OJP_SERVER_CIRCUITBREAKERTHRESHOLD=3
export OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED=true
export OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE=25
export OJP_SERVER_ALLOWEDIPS="192.168.1.0/24,10.0.0.1"
java -jar ojp-server.jar
```

### 3. Docker Environment Variables

```bash
docker run -e OJP_SERVER_PORT=8080 \
           -e OJP_PROMETHEUS_PORT=9091 \
           -e OJP_OPENTELEMETRY_ENABLED=false \
           -e OJP_SERVER_CIRCUITBREAKERTIMEOUT=120000 \
           -e OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED=true \
           -e OJP_SERVER_ALLOWEDIPS="192.168.1.0/24,10.0.0.1" \
           -p 8080:8080 \
           -p 9091:9091 \
           rrobetti/ojp:latest
```

## IP Whitelist Configuration

The server supports IP-based access control for both the gRPC server and Prometheus endpoints.

### Supported Formats

- **Individual IP addresses**: `192.168.1.1`
- **CIDR ranges**: `192.168.1.0/24`, `10.0.0.0/8`
- **Wildcard (allow all)**: `0.0.0.0/0` or `*`
- **Multiple rules**: `192.168.1.1,10.0.0.0/8,172.16.0.1`

### Examples

```bash
# Allow only specific IPs
-Dojp.server.allowedIps="192.168.1.100,192.168.1.101"

# Allow a subnet range
-Dojp.server.allowedIps="192.168.1.0/24"

# Allow multiple subnets and specific IPs
-Dojp.server.allowedIps="192.168.1.0/24,10.0.0.0/8,127.0.0.1"

# Allow all (default)
-Dojp.server.allowedIps="0.0.0.0/0"
```

### Separate Prometheus Whitelist

You can configure different IP restrictions for the Prometheus metrics endpoint:

```bash
# Allow gRPC from internal network, Prometheus from monitoring subnet only
-Dojp.server.allowedIps="10.0.0.0/8" \
-Dojp.prometheus.allowedIps="192.168.100.0/24"
```

## Slow Query Segregation Feature

The Slow Query Segregation feature monitors all database operations and classifies them as "slow" or "fast" based on their execution time, then manages the number of concurrently executing operations to prevent slow operations from blocking the system.

### How It Works

1. **Operation Monitoring**: Every SQL operation is tracked using a hash of the SQL statement
2. **Execution Time Tracking**: Execution times are recorded and averaged using a weighted formula: `new_average = ((stored_average * 4) + new_measurement) / 5`
3. **Classification**: An operation is classified as "slow" if its average execution time is **2x or greater** than the overall average execution time
4. **Slot Management**: The total number of concurrent operations is limited by the HikariCP connection pool maximum size
5. **Slot Borrowing**: If one pool (slow/fast) is idle for a configurable time, the other pool can borrow its slots

### Configuration

```properties
# Enable/disable the feature
ojp.server.slowQuerySegregation.enabled=true

# Percentage of slots for slow operations (0-100)
ojp.server.slowQuerySegregation.slowSlotPercentage=20

# Idle timeout for slot borrowing (milliseconds)
ojp.server.slowQuerySegregation.idleTimeout=10000

# Timeout for acquiring slow operation slots (milliseconds)
ojp.server.slowQuerySegregation.slowSlotTimeout=120000

# Timeout for acquiring fast operation slots (milliseconds)
ojp.server.slowQuerySegregation.fastSlotTimeout=60000
```

### Benefits

- **Per-datasource isolation**: Each datasource maintains independent slow/fast lanes based on actual pool sizes
- **Enhanced resource protection**: Smart borrowing preserves at least one slot per pool and requires prior activity
- **Prevents resource starvation**: Fast operations aren't blocked by slow ones within each datasource
- **Adaptive learning**: Automatically discovers and adapts to slow operations per datasource
- **Efficient resource utilization**: Smart slot borrowing maximizes connection pool usage while maintaining safety

## Configuration Examples

### Development Environment

```bash
java -Dojp.server.port=1059 \
     -Dojp.prometheus.port=9159 \
     -Dojp.server.logLevel=DEBUG \
     -Dojp.server.accessLogging=true \
     -Dojp.server.allowedIps="0.0.0.0/0" \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -jar ojp-server.jar
```

### Production Environment

```bash
java -Dojp.server.port=1059 \
     -Dojp.prometheus.port=9159 \
     -Dojp.server.logLevel=INFO \
     -Dojp.server.accessLogging=false \
     -Dojp.server.threadPoolSize=300 \
     -Dojp.server.circuitBreakerTimeout=60000 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=25 \
     -Dojp.server.slowQuerySegregation.slowSlotTimeout=180000 \
     -Dojp.server.allowedIps="10.0.0.0/8,172.16.0.0/12" \
     -Dojp.prometheus.allowedIps="192.168.100.0/24" \
     -jar ojp-server.jar
```

### High-Throughput Environment

```bash
java -Dojp.server.port=1059 \
     -Dojp.server.threadPoolSize=500 \
     -Dojp.server.maxRequestSize=16777216 \
     -Dojp.server.connectionIdleTimeout=60000 \
     -Dojp.server.circuitBreakerTimeout=90000 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=30 \
     -Dojp.server.slowQuerySegregation.idleTimeout=5000 \
     -jar ojp-server.jar
```

### Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-server-config
data:
  OJP_SERVER_PORT: "1059"
  OJP_PROMETHEUS_PORT: "9159"
  OJP_SERVER_THREADPOOLSIZE: "200"
  OJP_SERVER_CIRCUITBREAKERTIMEOUT: "60000"
  OJP_SERVER_CIRCUITBREAKERTHRESHOLD: "3"
  OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED: "true"
  OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE: "20"
  OJP_SERVER_SLOWQUERYSEGREGATION_IDLETIMEOUT: "10000"
  OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTTIMEOUT: "120000"
  OJP_SERVER_SLOWQUERYSEGREGATION_FASTSLOTTIMEOUT: "60000"
  OJP_SERVER_ALLOWEDIPS: "10.244.0.0/16"
  OJP_PROMETHEUS_ALLOWEDIPS: "10.244.0.0/16"
  OJP_OPENTELEMETRY_ENABLED: "true"
  OJP_SERVER_LOGLEVEL: "INFO"
```

## Configuration Validation

- **Invalid values**: Fall back to defaults with warning logs
- **Invalid IP rules**: Server startup fails with error
- **Port conflicts**: Standard socket binding errors apply
- **Type mismatches**: Automatic fallback to defaults

## Troubleshooting

### Common Issues

1. **Server won't start**: Check IP whitelist configuration and port availability
2. **Can't connect**: Verify client IP is in the allowed list
3. **Metrics unavailable**: Check Prometheus port and IP whitelist
4. **Performance issues**: Adjust thread pool size, connection timeouts, and slow query segregation settings
5. **Slow queries blocking fast ones**: Enable slow query segregation and tune slot percentages

### Debugging Configuration

Enable debug logging to see configuration loading:

```bash
-Dojp.server.logLevel=DEBUG
```

Configuration summary is logged at startup:

```
INFO org.openjproxy.grpc.server.ServerConfiguration - OJP Server Configuration:
INFO org.openjproxy.grpc.server.ServerConfiguration -   Server Port: 1059
INFO org.openjproxy.grpc.server.ServerConfiguration -   Prometheus Port: 9159
INFO org.openjproxy.grpc.server.ServerConfiguration -   OpenTelemetry Enabled: true
INFO org.openjproxy.grpc.server.ServerConfiguration -   Slow Query Segregation Enabled: true
INFO org.openjproxy.grpc.server.ServerConfiguration -   Slow Query Slot Percentage: 20%
...
```

### Performance Tuning Tips

1. **Thread Pool**: Start with 200 threads, increase for high-concurrency environments
2. **Circuit Breaker**: Adjust timeout based on your slowest acceptable query
3. **Slow Query Segregation**: 
   - Increase slow slot percentage if you have many legitimate slow queries
   - Decrease idle timeout for more aggressive slot borrowing
   - Increase timeouts in environments with occasional very slow queries
4. **Connection Pools**: Configure client-side pool sizes based on application requirements
5. **Request Size**: Increase for applications that handle large result sets

## Related Documentation

- **[Slow Query Segregation Documentation](../designs/SLOW_QUERY_SEGREGATION.md)** - Detailed guide to the slow query segregation feature
- **[Example Configuration Properties](ojp-server-example.properties)** - Complete example configuration file with all settings