# Building OJP Server from Source

> **📦 Prefer the pre-built JAR?**  
> You do not need to build OJP Server from source for regular use. The recommended approach is to download the pre-built shaded JAR from Maven Central. See the [Runnable JAR Guide](README.md) for instructions.

This guide is intended for contributors and developers who need to modify, extend, or contribute to OJP Server.

## Prerequisites

- **Java 25 or higher**
- **Maven 3.9+**
- **Git**

### Verify Prerequisites

```bash
java -version
# Expected: openjdk version "21.x.x" or higher

mvn -version
# Expected: Apache Maven 3.9.x or higher
```

## Building the Runnable JAR from Source

### 1. Clone the Repository

```bash
git clone https://github.com/Open-J-Proxy/ojp.git
cd ojp
```

### 2. Build the Entire Project

```bash
mvn clean install -DskipTests
```

**Alternative**: Build only the server runnable JAR (after building dependencies once):

```bash
mvn clean package -pl ojp-server -DskipTests
```

### 3. Locate the Runnable JAR

After a successful build, the runnable JAR will be at:

```
ojp-server/target/ojp-server-<version>-shaded.jar
```

For example: `ojp-server/target/ojp-server-1.0.0-RC1-shaded.jar`

### 4. Download JDBC Drivers

Before running the server, download the open-source JDBC drivers:

```bash
cd ojp-server
bash download-drivers.sh
```

This downloads H2, PostgreSQL, MySQL, and MariaDB drivers to `./ojp-libs`.

### 5. Run the Server

```bash
java -jar ojp-server/target/ojp-server-1.0.0-RC1-shaded.jar
```

## Troubleshooting

### Java Version Issues

**Problem**: `error: invalid target release: 21`

**Solution**: Ensure you are using Java 25 or higher:
```bash
java -version
```

### Build Issues

**Problem**: `Could not resolve dependencies`

**Solution**: Build from the project root to ensure all dependencies are resolved:
```bash
mvn clean install -DskipTests
```

**Problem**: Tests failing during build

**Solution**: Skip tests (tests require running databases):
```bash
mvn clean install -DskipTests
```

## Next Steps

After building and running the server, refer to the [Runnable JAR Guide](README.md) for configuration options, driver setup, and verification steps.

For contributing code changes, see [Source Code Developer Setup](../code-contributions/setup_and_testing_ojp_source.md).
