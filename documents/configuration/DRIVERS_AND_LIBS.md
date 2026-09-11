# OJP External Libraries Support

> **🚨 Important for Version 0.4.0-beta and Later:**  
> Starting from version **0.4.0-beta**, JDBC drivers are **NO LONGER included** in the OJP Server JAR or pre-built Docker images. You **MUST** download the JDBC driver(s) you want to use and place them in the `ojp-libs` folder before running ojp-server.
>
> - **Runnable JAR** deployments require you to download drivers using the `download-drivers.sh` script (for open-source databases) or manually (for proprietary databases)
> - **Docker** deployments require you to download drivers and mount the `ojp-libs` directory as a volume

OJP Server supports loading JDBC drivers from an external directory. You must provide the JDBC drivers you want to use by downloading them and placing them in the `ojp-libs` folder.

## Overview

OJP provides a flexible "drop-in" mechanism for JDBC drivers and related libraries:

- **Runnable JAR**: Download drivers separately using the provided script before running
- **Docker**: Download drivers and mount them as a volume when running the container
- **Custom Drivers**: Use specific driver versions or add proprietary drivers

### Supported Databases

**Open Source (download using provided script)**:
- **H2** - Embedded/file-based database
- **PostgreSQL** - Popular open-source relational database
- **MySQL** - Widely-used open-source database
- **MariaDB** - MySQL-compatible open-source database

**Proprietary (add via drop-in mechanism)**:
- **Oracle Database** - Requires `ojdbc*.jar`
  - **Optional**: Oracle UCP for advanced connection pooling (`ucp.jar`, `ons.jar`)
    - **Note**: To use Oracle UCP with OJP, you must provide an implementation of at least one OJP SPI (Service Provider Interface): `XAConnectionPoolProvider` for XA transactions or `DataSourceProvider` for regular connections. See [OJP SPI Documentation](../spi/) for details.
- **Microsoft SQL Server** - Requires `mssql-jdbc-*.jar`
- **IBM DB2** - Requires `db2jcc*.jar` (and optionally `db2jcc_license_*.jar`)

**Note:** You can place any additional JAR files needed by your database drivers (connection pools, monitoring libraries, etc.) in the same directory.

## How It Works

1. **External Libraries Directory**: OJP loads all JAR files from a configurable directory at startup
2. **Automatic Detection**: Drivers and libraries are automatically loaded into the classpath
3. **Helpful Messages**: OJP provides clear instructions when a driver is missing
4. **Required Setup**: You must download and provide drivers before running OJP Server (version 0.4.0-beta and later)

## Setup Instructions

### Option 1: Docker with Volume Mount (Recommended)

> **⚠️ Required for v0.4.0-beta and later:** Docker images no longer include drivers pre-installed. You must download drivers and mount them.

**Step 1: Download Open Source Drivers**

```bash
# Create external libraries directory
mkdir -p ./ojp-libs

# Download open source drivers (H2, PostgreSQL, MySQL, MariaDB)
cd ojp-server
bash download-drivers.sh ../ojp-libs
cd ..
```

**Step 2: Run Docker with Volume Mount**

```bash
# Run OJP with downloaded drivers mounted
docker run -d \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  --name ojp \
  rrobetti/ojp:1.0.0-RC1
```

**Adding Proprietary Drivers**:

```bash
# Create external libraries directory on host
mkdir -p ./ojp-libs

# Add proprietary drivers (e.g., Oracle, SQL Server, DB2)
cp ~/Downloads/ojdbc11.jar ./ojp-libs/

# Run with volume mount
docker run -d \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  --name ojp \
  rrobetti/ojp:1.0.0-RC1
```

### Option 2: Runnable JAR (Local/VM Deployment)

> **⚠️ Required for v0.4.0-beta and later:** You must download JDBC drivers before running the OJP Server JAR.

For complete instructions on building and running OJP as a runnable JAR, see the [OJP Server Runnable JAR Guide](../runnable-jar/README.md).

#### Step 1: Download Open Source Drivers

OJP provides a convenient script to download open source drivers:

```bash
cd ojp-server

# Download to default location (./ojp-libs)
bash download-drivers.sh

# Or specify custom directory
bash download-drivers.sh /opt/ojp/drivers
```

This downloads H2, PostgreSQL, MySQL, and MariaDB drivers from Maven Central.

#### Step 2: (Optional) Add Proprietary Drivers

Download proprietary JDBC drivers from vendors and add them to the same directory:

- **Oracle**: [Oracle JDBC Downloads](https://www.oracle.com/database/technologies/jdbc-downloads.html) (ojdbc*.jar)
  - Optional: Oracle UCP for advanced connection pooling (ucp.jar, ons.jar) - requires SPI implementation
- **SQL Server**: [Microsoft JDBC Driver Downloads](https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server)
- **DB2**: Contact IBM or check your DB2 installation directory

```bash
# Example: Adding Oracle driver
cp ~/Downloads/ojdbc11.jar ./ojp-libs/

# Example: Adding Oracle with UCP (requires SPI implementation)
cp ~/Downloads/ojdbc11.jar ./ojp-libs/
cp ~/Downloads/ucp.jar ./ojp-libs/
cp ~/Downloads/ons.jar ./ojp-libs/
```

#### Step 3: Run OJP Server

```bash
# Default location (./ojp-libs)
java -Duser.timezone=UTC -jar ojp-server-1.0.0-RC1-shaded.jar

# Custom location
java -Duser.timezone=UTC -Dojp.libs.path=/path/to/ojp-libs -jar ojp-server-1.0.0-RC1-shaded.jar
```

### Option 3: Docker with Custom Drivers (Volume Mount)

#### Step 1: Download and Prepare Libraries

```bash
# Create external libraries directory on host
mkdir -p ./ojp-libs

# Download open source drivers (required)
cd ojp-server
bash download-drivers.sh ../ojp-libs
cd ..

# Add proprietary drivers and libraries (if needed)
cp ~/Downloads/ojdbc11.jar ./ojp-libs/
# Optional: Add Oracle UCP for advanced connection pooling
cp ~/Downloads/ucp.jar ./ojp-libs/
```

#### Step 2: Run Container with Volume

```bash
docker run -d \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:1.0.0-RC1
```

The container is pre-configured to look for libraries in `/opt/ojp/ojp-libs`. All drivers placed in the mounted directory will be loaded automatically at startup.

### Option 4: Docker Compose

```yaml
version: '3.8'
services:
  ojp:
    image: rrobetti/ojp:1.0.0-RC1
    ports:
      - "1059:1059"
    volumes:
      - ./ojp-libs:/opt/ojp/ojp-libs  # Optional: for proprietary drivers
    environment:
      - OJP_LIBS_PATH=/opt/ojp/ojp-libs
```

**Note**: All drivers must be downloaded and placed in the mounted `ojp-libs` directory. Open-source drivers can be downloaded using the `download-drivers.sh` script.

## Configuration

### Environment Variables

You can configure the external libraries directory using environment variables:

```bash
# Using environment variable (underscores and uppercase)
export OJP_LIBS_PATH=/custom/path/to/ojp-libs

# Or using JVM system property (dots and lowercase)
java -Duser.timezone=UTC -Dojp.libs.path=/custom/path/to/ojp-libs -jar ojp-server.jar
```

### Default Paths

- **Runnable JAR**: `./ojp-libs` (relative to current directory)
- **Docker**: `/opt/ojp/ojp-libs`

## Verification

When OJP starts, check the logs for driver loading messages:

### Successful Loading

```
INFO  Loading external JDBC drivers...
INFO  Loading driver JAR: h2-2.3.232.jar
INFO  Loading driver JAR: postgresql-42.7.8.jar
INFO  Loading driver JAR: mysql-connector-j-9.5.0.jar
INFO  Loading driver JAR: mariadb-java-client-3.5.2.jar
INFO  Successfully loaded 4 driver JAR(s) from: /opt/ojp/ojp-libs
INFO  H2 JDBC driver loaded successfully
INFO  PostgreSQL JDBC driver loaded successfully
INFO  MySQL JDBC driver loaded successfully
INFO  MariaDB JDBC driver loaded successfully
```

With proprietary drivers:

```
INFO  Loading external JDBC drivers...
INFO  Loading driver JAR: h2-2.3.232.jar
INFO  Loading driver JAR: postgresql-42.7.8.jar
INFO  Loading driver JAR: ojdbc11.jar
INFO  Successfully loaded 5 driver JAR(s) from: /opt/ojp/ojp-libs
INFO  H2 JDBC driver loaded successfully
INFO  PostgreSQL JDBC driver loaded successfully
INFO  Oracle JDBC driver loaded successfully
```

### Driver Not Found

```
INFO  Oracle JDBC driver not found. To use Oracle databases:
INFO    1. Download ojdbc*.jar from Oracle (https://www.oracle.com/database/technologies/jdbc-downloads.html)
INFO    2. Place it in: /opt/ojp/ojp-libs
INFO    3. Restart OJP Server
```

## Kubernetes Deployment

### Using ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-drivers
data: {}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      containers:
      - name: ojp
        image: rrobetti/ojp:1.0.0-RC1
        volumeMounts:
        - name: drivers
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: drivers
        configMap:
          name: ojp-drivers
```

### Using Init Container (Download from Internal Repository)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      initContainers:
      - name: download-drivers
        image: curlimages/curl:latest
        command:
        - sh
        - -c
        - |
          curl -o /ojp-libs/ojdbc11.jar $ORACLE_DRIVER_URL
        env:
        - name: ORACLE_DRIVER_URL
          value: "https://your-internal-repo/ojp-libs/ojdbc11.jar"
        volumeMounts:
        - name: drivers
          mountPath: /ojp-libs
      containers:
      - name: ojp
        image: rrobetti/ojp:1.0.0-RC1
        volumeMounts:
        - name: drivers
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: drivers
        emptyDir: {}
```

## Troubleshooting

### Driver Not Loaded

**Problem**: Driver JAR is in the directory but not loaded.

**Solutions**:
1. Check file permissions (must be readable)
2. Verify file has `.jar` extension (case-insensitive)
3. Check logs for loading errors
4. Ensure driver JAR is not corrupted

### Wrong Driver Version

**Problem**: JDBC driver version incompatible with database.

**Solutions**:
1. Download correct driver version for your database
2. Refer to vendor compatibility matrix
3. Test driver independently before adding to OJP

### Multiple Driver Versions

**Problem**: Multiple versions of same driver in directory.

**Recommendation**: Keep only one version to avoid conflicts. Remove older versions.

### Permission Denied

**Problem**: OJP cannot read driver files.

**Solutions**:
```bash
# Fix permissions
chmod 644 ./ojp-libs/*.jar

# For Docker volume mounts
chown -R 1000:1000 ./ojp-libs/
```

## Best Practices

1. **Version Control**: Track which driver versions you're using
2. **Testing**: Test driver independently before production deployment
3. **Security**: Store drivers in a secure internal repository
4. **Updates**: Keep drivers updated for security patches
5. **Documentation**: Document which drivers your deployment requires

## CI/CD Integration

### GitLab CI Example

```yaml
deploy-ojp-with-drivers:
  stage: deploy
  script:
    - mkdir -p ojp-libs
    - curl -o ojp-libs/ojdbc11.jar $ORACLE_DRIVER_URL
    - docker run -d
        -p 1059:1059
        -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs
        rrobetti/ojp:latest
```

### GitHub Actions Example

```yaml
name: Deploy OJP with Drivers
on: [push]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Download drivers
        run: |
          mkdir -p ojp-libs
          curl -o ojp-libs/ojdbc11.jar ${{ secrets.ORACLE_DRIVER_URL }}
      - name: Run OJP with drivers
        run: |
          docker run -d \
            -p 1059:1059 \
            -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
            rrobetti/ojp:latest
```

## Security Considerations

1. **Licensing**: Ensure compliance with vendor license terms
2. **Distribution**: Never commit proprietary drivers to public repositories
3. **Access Control**: Restrict access to driver storage locations
4. **Scanning**: Scan driver JARs for vulnerabilities
5. **Compliance**: Follow your organization's software procurement policies

## FAQs

**Q: Can I use multiple proprietary drivers?**  
A: Yes, place all required JAR files in the external libraries directory.

**Q: What if I don't need proprietary drivers?**  
A: Use the `download-drivers.sh` script to get the open-source drivers (H2, PostgreSQL, MySQL, MariaDB) for both Docker and runnable JAR deployments.

**Q: Can I use specific versions of open source drivers?**  
A: Yes, simply download your preferred version and place it in the ojp-libs directory.

**Q: Can I hot-reload drivers?**  
A: No, OJP loads drivers and libraries at startup. Restart the server to load new JARs.

**Q: Does this work with JDBC driver dependencies?**  
A: Yes, place all required JARs (driver + dependencies + additional libraries) in the external libraries directory.

**Q: Can I use this with Oracle UCP or other connection pool libraries?**  
A: Yes, any JAR file placed in the directory will be loaded into the classpath. This includes connection pool libraries like Oracle UCP (ucp.jar), monitoring libraries, and other dependencies. **Note**: Oracle UCP requires implementing at least one OJP SPI (Service Provider Interface) for connection pooling.

## Support

For issues or questions about driver support:
- **GitHub Issues**: [Open an issue](https://github.com/Open-J-Proxy/ojp/issues)
- **Discord**: [Join our community](https://discord.gg/J5DdHpaUzu)
- **Documentation**: [OJP Documentation](https://github.com/Open-J-Proxy/ojp)
