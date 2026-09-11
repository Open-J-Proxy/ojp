# OJP Server Runnable JAR Guide

> **🚨 Important for Version 0.4.0-beta and Later:**  
> Starting from version **0.4.0-beta**, JDBC drivers are **NO LONGER included** in the OJP Server JAR. You **MUST** download the JDBC driver(s) you want to use and place them in the `ojp-libs` folder before running ojp-server. Use the `download-drivers.sh` script for open-source drivers (H2, PostgreSQL, MySQL, MariaDB), or manually download proprietary drivers from vendors (Oracle, SQL Server, DB2).

This guide explains how to run the OJP Server as a standalone runnable JAR (executable JAR with all dependencies included) for environments where Docker or containers are not available.

> **💡 No build required!** From version **0.4.0-beta** onwards, the OJP Server JAR is published to Maven Central. You can download and run it directly without cloning the repository or installing Maven.  
> If you need to build from source (e.g., for contributing), see [Building from Source](BUILDING_FROM_SOURCE.md).

## Prerequisites

- **Java 25 or higher** - Required for running OJP Server

### Java Version Check

Verify your Java version before proceeding:

```bash
java -version
```

Expected output (version should be 21 or higher):
```
openjdk version "21.0.9" 2024-10-21
OpenJDK Runtime Environment (build 21.0.9+10)
OpenJDK 64-Bit Server VM (build 21.0.9+10, mixed mode, sharing)
```

## Downloading the Runnable JAR from Maven Central

The easiest way to get the OJP Server JAR is to download it directly from Maven Central — no source code or build tools required.

### 1. Download the JAR

```bash
wget https://repo1.maven.org/maven2/org/openjproxy/ojp-server/1.0.0-RC1/ojp-server-1.0.0-RC1-shaded.jar
```

Or using `curl`:

```bash
curl -LO https://repo1.maven.org/maven2/org/openjproxy/ojp-server/1.0.0-RC1/ojp-server-1.0.0-RC1-shaded.jar
```

### 2. Make the JAR Executable (Optional)

```bash
chmod +x ojp-server-1.0.0-RC1-shaded.jar
```

The JAR size is approximately **20MB** (without drivers). Open-source JDBC drivers must be downloaded separately (see below).

> **📌 Version 0.4.0-beta and Later:** Starting from v0.4.0-beta, JDBC drivers are **not included** in the runnable JAR. You must download them separately using the provided script or manually for proprietary databases.

## Downloading Open Source JDBC Drivers

The OJP Server requires JDBC drivers to connect to databases. For convenience, a script is provided to download open-source drivers.

### 1. Download Drivers Using the Script

```bash
# Download the driver download script from the OJP repository
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
bash download-drivers.sh
```

This script downloads the following drivers from Maven Central:
- **H2** (v2.3.232) - Embedded/file-based database
- **PostgreSQL** (v42.7.8) - PostgreSQL database
- **MySQL** (v9.5.0) - MySQL database
- **MariaDB** (v3.5.2) - MariaDB database

The drivers will be placed in the `./ojp-libs` directory (approximately 7MB total).

### 2. Verify Downloaded Drivers

```bash
ls -lh ojp-libs/
```

Expected output (exact versions depend on the script):
```
-rw-rw-r-- 1 user user 2.6M h2-2.3.232.jar
-rw-rw-r-- 1 user user 726K mariadb-java-client-3.5.2.jar
-rw-rw-r-- 1 user user 2.5M mysql-connector-j-9.5.0.jar
-rw-rw-r-- 1 user user 1.1M postgresql-42.7.8.jar
```

## Adding Proprietary Database Drivers (Optional)

The runnable JAR includes infrastructure for loading JDBC drivers but does not include the drivers themselves. To use **proprietary databases** (Oracle, SQL Server, DB2) or custom driver versions, you need to add their JDBC drivers to the `ojp-libs` directory.

### 1. Ensure Open Source Drivers Are Downloaded

If you haven't already, download the open source drivers:

```bash
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
bash download-drivers.sh
```

### 2. Add Proprietary Driver JARs

Download the required JDBC driver JAR(s) from the vendor and place them in the `ojp-libs` directory:

**Oracle Example:**
```bash
# Download ojdbc11.jar from Oracle
cp ~/Downloads/ojdbc11.jar ojp-libs/

# Optional: Add Oracle UCP for advanced connection pooling
cp ~/Downloads/ucp.jar ojp-libs/
cp ~/Downloads/ons.jar ojp-libs/
```

**SQL Server Example:**
```bash
# Download mssql-jdbc from Microsoft
cp ~/Downloads/mssql-jdbc-12.6.1.jre11.jar ojp-libs/
```

**DB2 Example:**
```bash
# Download db2jcc from IBM
cp ~/Downloads/db2jcc4.jar ojp-libs/
```

### 3. Run with External Libraries

The server automatically loads drivers from the `./ojp-libs` directory:

```bash
# Default location (./ojp-libs)
java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1-shaded.jar

# Or specify custom path
java -Duser.timezone=UTC -Dojp.libs.path=./ojp-libs -jar ojp-server-1.0.0-RC1-shaded.jar
```

The server will automatically:
- Load all JARs from the `ojp-libs` directory
- Discover and register JDBC drivers using Java's ServiceLoader mechanism
- Make additional libraries (like Oracle UCP) available on the classpath

**Note**: The `ojp-libs` directory must contain the open source drivers (downloaded via `download-drivers.sh`) for the server to work with H2, PostgreSQL, MySQL, or MariaDB databases.

For detailed information, see the [Drop-In External Libraries Documentation](../configuration/DRIVERS_AND_LIBS.md).

## Running the OJP Server JAR

### Basic Execution

Run the OJP Server with default configuration (ensure drivers are downloaded first):

```bash
# First, download open source drivers
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
bash download-drivers.sh

# Then run the server
java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1-shaded.jar
```

### Basic Execution with Custom Driver Location

Run the OJP Server with external libraries in a custom location:

```bash
java -Duser.timezone=UTC -Dojp.libs.path=/opt/drivers -jar ojp-server-1.0.0-RC1-shaded.jar
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
java -Duser.timezone=UTC \
     -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
     -jar ojp-server-1.0.0-RC1-shaded.jar
```

### Running as Background Process

To run the server in the background:

```bash
nohup java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1-shaded.jar > ojp-server.log 2>&1 &
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
| `ojp.libs.path` | `./ojp-libs` | Path to external libraries directory (for proprietary drivers) |
| `ojp.server.virtualThreads.enabled` | `false` | Enable Java virtual threads for gRPC request handling |
| `ojp.server.threadPoolSize` | `200` | Fixed thread pool size when virtual threads are disabled |
| `ojp.max.request.size` | `4194304` | Maximum request size in bytes |
| `ojp.connection.idle.timeout` | `30000` | Connection idle timeout in milliseconds |
| `ojp.circuit.breaker.timeout` | `60000` | Circuit breaker timeout in milliseconds |
| `ojp.circuit.breaker.threshold` | `3` | Circuit breaker failure threshold |

> **⚠️ Important JVM Property:**  
> Always start the server with `-Duser.timezone=UTC`. The OJP Server handles date/time values from multiple databases and client timezones. Running the JVM in UTC ensures consistent and predictable behavior for all temporal operations. Omitting this setting can cause incorrect date/time conversions, especially when clients and databases are in different timezones.

### Example with Multiple Properties

```bash
java -Duser.timezone=UTC \
     -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dojp.libs.path=./ojp-libs \
     -Dojp.server.virtualThreads.enabled=true \
     -Dojp.server.threadPoolSize=100 \
     -Dojp.max.request.size=8388608 \
     -jar ojp-server-1.0.0-RC1-shaded.jar
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

**Problem**: Server fails to start with unsupported class version error

**Solution**: Ensure you're using Java 25 or higher:
```bash
java -version
```

If using an older Java version, upgrade to Java 25 or higher. You can download it from [Eclipse Temurin](https://adoptium.net), [Oracle JDK](https://www.oracle.com/java/), or [Amazon Corretto](https://aws.amazon.com/corretto/).

### Runtime Issues

**Problem**: `Address already in use`

**Solution**: Another process is using port 1059. Either:
- Stop the conflicting process, or
- Use a different port: `java -Dojp.server.port=8080 -jar ...`

**Problem**: `OutOfMemoryError`

**Solution**: Increase JVM heap size:
```bash
java -Duser.timezone=UTC -Xmx2g -jar ojp-server-1.0.0-RC1-shaded.jar
```

**Problem**: Missing database drivers

**Solution**: Download the open source drivers using the provided script:

```bash
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
bash download-drivers.sh
```

This will download H2, PostgreSQL, MySQL, and MariaDB drivers to the `ojp-libs` directory.

For Oracle, DB2, SQL Server or other proprietary databases:

1. Ensure open source drivers are downloaded (see above)
2. Place proprietary driver JAR(s) in the same `ojp-libs` directory
3. Start the server (drivers are automatically detected)

Example:
```bash
# Download open source drivers first
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
bash download-drivers.sh

# Add proprietary driver
cp ~/Downloads/ojdbc11.jar ojp-libs/

# Run server
java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1-shaded.jar
```

See the [Downloading Open Source JDBC Drivers](#downloading-open-source-jdbc-drivers) and [Adding Proprietary Database Drivers](#adding-proprietary-database-drivers-optional) sections above for detailed instructions.

### Performance Tuning

For production environments, consider these JVM options:

```bash
java -Duser.timezone=UTC \
     -Xmx4g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -Dojp.server.virtualThreads.enabled=true \
     -Dojp.server.threadPoolSize=500 \
     -jar ojp-server-1.0.0-RC1-shaded.jar
```

## Logging Configuration

The server uses SLF4J Simple Logger. Configure logging levels:

```bash
# Set log level to DEBUG
java -Duser.timezone=UTC \
     -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
     -jar ojp-server-1.0.0-RC1-shaded.jar

# Disable most logging (ERROR only)
java -Duser.timezone=UTC \
     -Dorg.slf4j.simpleLogger.defaultLogLevel=error \
     -jar ojp-server-1.0.0-RC1-shaded.jar
```

## Building from Source

If you need to modify OJP Server or contribute to the project, you can build the JAR from source. See [Building OJP Server from Source](BUILDING_FROM_SOURCE.md) for complete instructions.

## Next Steps

After successfully running the OJP Server:

1. **Download open source drivers** using the [instructions above](#downloading-open-source-jdbc-drivers)
2. **Add proprietary drivers** (if needed) using the [external libraries directory](#adding-proprietary-database-drivers-optional)
3. **Configure your application** to use the [OJP JDBC Driver](../../README.md#2-add-ojp-jdbc-driver-to-your-project)
4. **Update connection URLs** to use the `ojp[host:port]_` prefix
5. **Disable application-level connection pooling** as OJP handles pooling
6. **Set up monitoring** (optional) using the Prometheus metrics endpoint
7. **Review** [OJP Server Configuration](../configuration/ojp-server-configuration.md) for advanced options

### Additional Documentation

- [Building from Source](BUILDING_FROM_SOURCE.md) - Instructions for building OJP Server from the source repository
- [Drop-In External Libraries Support](../configuration/DRIVERS_AND_LIBS.md) - Comprehensive guide for adding proprietary drivers
- [Spring Boot Integration](../java-frameworks/spring-boot/README.md)
- [Quarkus Integration](../java-frameworks/quarkus/README.md)
- [Micronaut Integration](../java-frameworks/micronaut/README.md)
