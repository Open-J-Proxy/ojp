# OJP Server Docker Deployment Guide

This guide covers deploying OJP Server using Docker, including configuration options, JVM parameter customization, and production best practices.

## Table of Contents

- [Quick Start](#quick-start)
- [Passing JVM Parameters](#passing-jvm-parameters)
- [Environment Variables](#environment-variables)
- [Production Deployment](#production-deployment)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)

---

## Quick Start

> **🚨 Important for Version 0.4.0-beta and Later:** JDBC drivers are **NO LONGER included** in the OJP Server Docker image. You **MUST** download drivers and mount them into the `ojp-libs` directory before running.

> **Note**: Run the following commands from the root of the OJP repository (or any working directory of your choice). The `ojp-server/download-drivers.sh` script is included in the OJP repository.

**Step 1: Download drivers**:

```bash
# From the OJP repository root (or your chosen working directory):
mkdir -p ojp-libs
bash ojp-server/download-drivers.sh ./ojp-libs
```

**Step 2: Run OJP Server with drivers mounted**:

```bash
# Run from the same directory where ojp-libs was created
docker run -d \
  --name ojp-server \
  -p 1059:1059 \
  -p 9159:9159 \
  -v "$(pwd)/ojp-libs":/opt/ojp/ojp-libs \
  rrobetti/ojp:1.0.0-RC1
```

This starts the OJP Server with:
- **Port 1059**: gRPC server for database connections
- **Port 9159**: Prometheus metrics endpoint
- **Drivers**: loaded from the mounted `ojp-libs` directory (H2, PostgreSQL, MySQL, MariaDB if downloaded via script)
- **UTC timezone**: The image is built with `-Duser.timezone=UTC` for consistent date/time handling
- **Non-root user**: the server process runs as a dedicated `ojp` system user — ensure the mounted `ojp-libs` directory is readable by other users (default `755`/`644` permissions are sufficient)

---

## Passing JVM Parameters

### Using JAVA_TOOL_OPTIONS

The recommended way to pass JVM parameters (including system properties, heap settings, and GC options) to the OJP Server Docker container is using the **JAVA_TOOL_OPTIONS** environment variable.

The JVM automatically recognizes and applies any parameters set in `JAVA_TOOL_OPTIONS` at startup.

#### Basic Example

```bash
docker run -d \
  --name ojp-server \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  rrobetti/ojp:1.0.0-RC1
```

#### Memory Configuration

Configure heap size and other memory settings:

```bash
docker run -d \
  --name ojp-server \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx2g -Xms1g -Duser.timezone=UTC" \
  rrobetti/ojp:1.0.0-RC1
```

#### Combined JVM Parameters

Combine multiple JVM parameters:

```bash
docker run -d \
  --name ojp-server \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC -XX:+UseG1GC" \
  rrobetti/ojp:1.0.0-RC1
```

### Common JVM Parameters

#### Memory Settings

| Parameter | Description | Example |
|-----------|-------------|---------|
| `-Xmx` | Maximum heap size | `-Xmx4g` (4 GB) |
| `-Xms` | Initial heap size | `-Xms2g` (2 GB) |
| `-XX:MaxMetaspaceSize` | Maximum metaspace size | `-XX:MaxMetaspaceSize=512m` |
| `-XX:MetaspaceSize` | Initial metaspace size | `-XX:MetaspaceSize=256m` |

#### Garbage Collection

| Parameter | Description | Use Case |
|-----------|-------------|----------|
| `-XX:+UseG1GC` | Use G1 garbage collector | Default for most workloads |
| `-XX:+UseZGC` | Use Z garbage collector | Low-latency requirements (Java 17+) |
| `-XX:MaxGCPauseMillis` | Target max GC pause time | `-XX:MaxGCPauseMillis=200` |
| `-XX:G1HeapRegionSize` | G1 heap region size | `-XX:G1HeapRegionSize=32m` |

#### System Properties

| Parameter | Description | Example |
|-----------|-------------|---------|
| `-Dfile.encoding` | Character encoding | `-Dfile.encoding=UTF-8` |
| `-Duser.timezone` | Default timezone | `-Duser.timezone=UTC` |
| `-Duser.language` | Default language | `-Duser.language=en` |
| `-Duser.country` | Default country | `-Duser.country=US` |

> **⚠️ Keep `-Duser.timezone=UTC`:**  
> The OJP Docker image is built with `-Duser.timezone=UTC` baked in. Always include this property when setting `JAVA_TOOL_OPTIONS` to ensure consistent date/time handling across all database types and client timezones.

#### Debugging and Diagnostics

| Parameter | Description | Example |
|-----------|-------------|---------|
| `-agentlib:jdwp` | Enable remote debugging | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` |
| `-XX:+HeapDumpOnOutOfMemoryError` | Create heap dump on OOM | `-XX:+HeapDumpOnOutOfMemoryError` |
| `-XX:HeapDumpPath` | Heap dump file path | `-XX:HeapDumpPath=/tmp/heapdump.hprof` |
| `-verbose:gc` | Verbose GC logging | `-verbose:gc` |

---

## Environment Variables

### OJP Configuration via Environment Variables

In addition to JVM parameters, OJP Server supports configuration through environment variables:

```bash
docker run -d \
  --name ojp-server \
  -p 8080:8080 \
  -p 9091:9091 \
  -e OJP_SERVER_PORT=8080 \
  -e OJP_PROMETHEUS_PORT=9091 \
  -e OJP_SERVER_LOGLEVEL=DEBUG \
  -e OJP_SERVER_VIRTUALTHREADS_ENABLED=true \
  -e OJP_SERVER_THREADPOOLSIZE=300 \
  rrobetti/ojp:1.0.0-RC1
```

See [ojp-server-configuration.md](ojp-server-configuration.md) for a complete list of available configuration options.

### Combining JAVA_TOOL_OPTIONS with OJP Environment Variables

You can use both approaches together:

```bash
docker run -d \
  --name ojp-server \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  -e OJP_SERVER_PORT=1059 \
  -e OJP_SERVER_LOGLEVEL=INFO \
  rrobetti/ojp:1.0.0-RC1
```

Ports can also be overridden via JVM system properties inside `JAVA_TOOL_OPTIONS`. The `-p` mapping must match whichever value the server actually binds to:

```bash
docker run -d \
  --name ojp-server \
  -p 3020:3020 \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Duser.timezone=UTC -Dojp.server.port=3020" \
  rrobetti/ojp:1.0.0-RC1
```

> **⚠️ Precedence:** JVM system properties (`-Dojp.server.port`) take precedence over environment variables (`OJP_SERVER_PORT`). If both are set, the JVM property wins and the server binds to that port — make sure the `-p` mapping reflects the effective value.

---

## Production Deployment

### Production-Ready Configuration

A production deployment example with appropriate JVM tuning:

```bash
docker run -d \
  --name ojp-server \
  --restart unless-stopped \
  -p 1059:1059 \
  -p 9159:9159 \
  -v /var/log/ojp:/var/log/ojp \
  -e JAVA_TOOL_OPTIONS="\
    -Xmx4g \
    -Xms2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/var/log/ojp/heapdump.hprof \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=UTC" \
  -e OJP_SERVER_PORT=1059 \
  -e OJP_PROMETHEUS_PORT=9159 \
  -e OJP_SERVER_VIRTUALTHREADS_ENABLED=true \
  -e OJP_SERVER_THREADPOOLSIZE=300 \
  -e OJP_SERVER_LOGLEVEL=INFO \
  -e OJP_SERVER_LOG_FILE=/var/log/ojp/server.log \
  -e OJP_SERVER_LOG_MAXHISTORY=90 \
  -e OJP_SERVER_ALLOWEDIPS="10.0.0.0/8" \
  rrobetti/ojp:1.0.0-RC1
```

### Docker Compose

Create a `docker-compose.yml` file:

```yaml
version: '3.8'

services:
  ojp-server:
    image: rrobetti/ojp:1.0.0-RC1
    container_name: ojp-server
    restart: unless-stopped
    ports:
      - "1059:1059"
      - "9159:9159"
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Xmx4g
        -Xms2g
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
        -XX:+HeapDumpOnOutOfMemoryError
        -XX:HeapDumpPath=/var/log/ojp/heapdump.hprof
        -Dfile.encoding=UTF-8
        -Duser.timezone=UTC
      OJP_SERVER_PORT: 1059
      OJP_PROMETHEUS_PORT: 9159
      OJP_SERVER_VIRTUALTHREADS_ENABLED: "true"
      OJP_SERVER_THREADPOOLSIZE: 300
      OJP_SERVER_LOGLEVEL: INFO
      OJP_SERVER_LOG_FILE: /var/log/ojp/server.log
      OJP_SERVER_LOG_MAXHISTORY: 90
      OJP_SERVER_ALLOWEDIPS: "10.0.0.0/8"
    volumes:
      - ojp-logs:/var/log/ojp
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9159/metrics"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

volumes:
  ojp-logs:
    driver: local
```

Start with:
```bash
docker-compose up -d
```

### Health Checks

Monitor container health:

```bash
# Check if container is running
docker ps | grep ojp-server

# View logs
docker logs ojp-server

# Check metrics endpoint
curl http://localhost:9159/metrics

# Follow logs in real-time
docker logs -f ojp-server
```

---

## Examples

### Example 1: Development Environment

```bash
docker run -d \
  --name ojp-dev \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx1g -Xms512m -Duser.timezone=UTC" \
  -e OJP_SERVER_LOGLEVEL=DEBUG \
  rrobetti/ojp:1.0.0-RC1
```

### Example 2: High-Memory Production Server

```bash
docker run -d \
  --name ojp-prod \
  --restart unless-stopped \
  -p 1059:1059 \
  -p 9159:9159 \
  -e JAVA_TOOL_OPTIONS="-Xmx16g -Xms8g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=America/New_York" \
  -e OJP_SERVER_VIRTUALTHREADS_ENABLED=true \
  -e OJP_SERVER_THREADPOOLSIZE=500 \
  -e OJP_SERVER_LOGLEVEL=INFO \
  -e OJP_SERVER_ALLOWEDIPS="10.0.0.0/8,172.16.0.0/12" \
  rrobetti/ojp:1.0.0-RC1
```

### Example 3: Remote Debugging

```bash
docker run -d \
  --name ojp-debug \
  -p 1059:1059 \
  -p 5005:5005 \
  -e JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" \
  -e OJP_SERVER_LOGLEVEL=DEBUG \
  rrobetti/ojp:1.0.0-RC1
```

Connect your IDE debugger to `localhost:5005`.

### Example 4: Low-Latency Configuration

For applications requiring low latency:

```bash
docker run -d \
  --name ojp-lowlatency \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx8g -Xms8g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  -e OJP_SERVER_VIRTUALTHREADS_ENABLED=true \
  -e OJP_SERVER_THREADPOOLSIZE=400 \
  rrobetti/ojp:1.0.0-RC1
```

### Example 5: Adding Proprietary Drivers

To add proprietary JDBC drivers (Oracle, SQL Server, DB2), use a volume mount:

```bash
# Create libs directory and download open source drivers
mkdir -p ojp-libs
cd ojp-server
bash download-drivers.sh ../ojp-libs
cd ..

# Add proprietary drivers
cp ~/Downloads/ojdbc11.jar ojp-libs/
cp ~/Downloads/mssql-jdbc-12.jar ojp-libs/

# Run with volume mount
docker run -d \
  --name ojp-custom \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  rrobetti/ojp:1.0.0-RC1
```

---

## Troubleshooting

### Verify JAVA_TOOL_OPTIONS is Applied

Check the container logs to confirm JVM parameters are picked up:

```bash
docker logs ojp-server 2>&1 | head -5
```

You should see:
```
Picked up JAVA_TOOL_OPTIONS: -Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC
```

### Check Memory Settings

```bash
# View memory usage
docker stats ojp-server

# Exec into container and check Java process
docker exec ojp-server ps aux | grep java
```

### Container Won't Start

1. Check logs for errors:
```bash
docker logs ojp-server
```

2. Verify ports are available:
```bash
netstat -tuln | grep -E '1059|9159'
```

3. Test with minimal configuration:
```bash
docker run --rm rrobetti/ojp:1.0.0-RC1
```

### Out of Memory Errors

If you see `OutOfMemoryError`:

1. Increase heap size:
```bash
-e JAVA_TOOL_OPTIONS="-Xmx8g -Xms4g"
```

2. Enable heap dumps for analysis:
```bash
-e JAVA_TOOL_OPTIONS="-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof"
```

3. Mount volume to preserve heap dump:
```bash
-v /host/path:/tmp
```

### Performance Issues

1. Enable GC logging:
```bash
-e JAVA_TOOL_OPTIONS="-Xmx4g -verbose:gc -Xlog:gc*:file=/var/log/ojp/gc.log"
```

2. If needed, disable virtual threads and increase platform thread pool:
```bash
-e OJP_SERVER_VIRTUALTHREADS_ENABLED=false
-e OJP_SERVER_THREADPOOLSIZE=500
```

3. Tune garbage collection:
```bash
-e JAVA_TOOL_OPTIONS="-Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
```

---

## Additional Resources

- **[OJP Server Configuration Guide](ojp-server-configuration.md)** - Complete configuration reference
- **[Kubernetes Deployment](../ebook/part1-chapter3a-kubernetes-helm.md)** - Deploy to Kubernetes with Helm
- **[Driver Configuration](DRIVERS_AND_LIBS.md)** - JDBC driver setup
- **[SSL/TLS Configuration](ssl-tls-certificate-placeholders.md)** - Certificate configuration

---

## Support

For issues or questions:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/tree/main/documents
