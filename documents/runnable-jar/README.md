# OJP Server Runnable JAR Guide

This guide explains how to build and run the OJP Server as a standalone runnable JAR (executable JAR with all dependencies included) for environments where Docker or containers are not available.

## Prerequisites

- **Java 22 or higher** - Required for building and running OJP Server
- **Maven 3.9+** - Required for building the runnable JAR from source
- **Git** - Required for cloning the repository (if building from source)

### Java Version Check

Verify your Java version before proceeding:

```bash
java -version
```

Expected output (version should be 22 or higher):
```
openjdk version "22.0.1" 2024-04-16
OpenJDK Runtime Environment (build 22.0.1+8-16)
OpenJDK 64-Bit Server VM (build 22.0.1+8-16, mixed mode, sharing)
```

## Building the Runnable JAR from Source

### 1. Clone the Repository

```bash
git clone https://github.com/Open-J-Proxy/ojp.git
cd ojp
```

### 2. Build the Runnable JAR

Build the entire project including the runnable JAR:

```bash
mvn clean install -DskipTests
```

**Alternative**: Build only the server runnable JAR (after building dependencies once):

```bash
mvn clean package -pl ojp-server -DskipTests
```

### 3. Locate the Runnable JAR

After successful build, the runnable JAR will be located at:

```
ojp-server/target/ojp-server-<version>-shaded.jar
```

For example: `ojp-server/target/ojp-server-0.1.1-beta-shaded.jar`

The runnable JAR size is approximately **27MB** and contains all required dependencies.

## Running the OJP Server JAR

### Basic Execution

Run the OJP Server with default configuration:

```bash
java -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

### Expected Output

When the server starts successfully, you should see output similar to:

```
[main] INFO org.openjproxy.grpc.server.ServerConfiguration - OJP Server Configuration:
[main] INFO org.openjproxy.grpc.server.ServerConfiguration -   Server Port: 1059
[main] INFO org.openjproxy.grpc.server.ServerConfiguration -   Prometheus Port: 9159
[main] INFO org.openjproxy.grpc.server.ServerConfiguration -   OpenTelemetry Enabled: true
[main] INFO org.openjproxy.grpc.server.GrpcServer - Starting OJP gRPC Server on port 1059
[main] INFO org.openjproxy.grpc.server.GrpcServer - OJP gRPC Server started successfully and awaiting termination
```

### Running with Custom Configuration

You can customize the server configuration using system properties:

```bash
java -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
     -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

### Running as Background Process

To run the server in the background:

```bash
nohup java -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar > ojp-server.log 2>&1 &
```

To stop the background process:

```bash
# Find the process ID
ps aux | grep ojp-server

# Kill the process (replace <PID> with actual process ID)
kill <PID>
```

## Configuration Options

The OJP Server can be configured using system properties. Common options include:

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.server.port` | `1059` | gRPC server port |
| `ojp.prometheus.port` | `9159` | Prometheus metrics port |
| `ojp.thread.pool.size` | `200` | Server thread pool size |
| `ojp.max.request.size` | `4194304` | Maximum request size in bytes |
| `ojp.connection.idle.timeout` | `30000` | Connection idle timeout in milliseconds |
| `ojp.circuit.breaker.timeout` | `60000` | Circuit breaker timeout in milliseconds |
| `ojp.circuit.breaker.threshold` | `3` | Circuit breaker failure threshold |

### Example with Multiple Properties

```bash
java -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dojp.thread.pool.size=100 \
     -Dojp.max.request.size=8388608 \
     -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

## Verification

### 1. Check Server Status

Test if the server is running by checking if the port is listening:

```bash
# Check if port 1059 is listening (default)
netstat -tlnp | grep 1059

# Or using ss
ss -tlnp | grep 1059
```

### 2. Prometheus Metrics

Access Prometheus metrics (if enabled):

```bash
curl http://localhost:9159/metrics
```

### 3. Test with OJP JDBC Driver

Once the server is running, you can test it using the OJP JDBC Driver in your Java application with a connection URL like:

```
jdbc:ojp[localhost:1059]_h2:~/test
```

## Troubleshooting

### Java Version Issues

**Problem**: `error: invalid target release: 22`

**Solution**: Ensure you're using Java 22 or higher:
```bash
java -version
```

If using a different Java version, either:
- Upgrade to Java 22, or
- Temporarily modify `ojp-server/pom.xml` to use your Java version (change `maven.compiler.source` and `maven.compiler.target`)

### Build Issues

**Problem**: `Could not resolve dependencies`

**Solution**: Build from the project root to ensure all dependencies are available:
```bash
mvn clean install -DskipTests
```

**Problem**: Tests failing during build

**Solution**: Skip tests during build (tests require running databases):
```bash
mvn clean install -DskipTests
```

### Runtime Issues

**Problem**: `Address already in use`

**Solution**: Another process is using port 1059. Either:
- Stop the conflicting process, or
- Use a different port: `java -Dojp.server.port=8080 -jar ...`

**Problem**: `OutOfMemoryError`

**Solution**: Increase JVM heap size:
```bash
java -Xmx2g -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

**Problem**: Missing database drivers

**Solution**: The runnable JAR includes drivers for H2, PostgreSQL, MySQL, and MariaDB. For Oracle, DB2, SQL Server or other proprietary database, you need to add the respective JDBC drivers to the classpath:

```bash
java -cp "oracle-driver.jar:ojp-server-0.1.1-beta-shaded.jar" \
     org.openjproxy.grpc.server.GrpcServer
```

### Performance Tuning

For production environments, consider these JVM options:

```bash
java -Xmx4g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -Dojp.thread.pool.size=500 \
     -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

## Logging Configuration

The server uses SLF4J Simple Logger. Configure logging levels:

```bash
# Set log level to DEBUG
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
     -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar

# Disable most logging (ERROR only)
java -Dorg.slf4j.simpleLogger.defaultLogLevel=error \
     -jar ojp-server/target/ojp-server-0.1.1-beta-shaded.jar
```

## Next Steps

After successfully running the OJP Server:

1. **Configure your application** to use the [OJP JDBC Driver](../../README.md#2-add-ojp-jdbc-driver-to-your-project)
2. **Update connection URLs** to use the `ojp[host:port]_` prefix
3. **Disable application-level connection pooling** as OJP handles pooling
4. **Set up monitoring** (optional) using the Prometheus metrics endpoint
5. **Review** [OJP Server Configuration](../configuration/ojp-server-configuration.md) for advanced options

For framework-specific integration guides, see:
- [Spring Boot Integration](../java-frameworks/spring-boot/README.md)
- [Quarkus Integration](../java-frameworks/quarkus/README.md)
- [Micronaut Integration](../java-frameworks/micronaut/README.md)