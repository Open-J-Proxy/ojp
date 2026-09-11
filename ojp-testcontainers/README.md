# OJP TestContainers

TestContainers support for OJP (Open JDBC Proxy) Server integration testing.

## Overview

This module provides a ready-to-use TestContainer implementation for OJP Server, making it easy to write integration tests that require an OJP Server instance.

## Usage

### Maven Dependency

Add the following dependency to your project's `pom.xml`:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-testcontainers</artifactId>
    <version>1.0.0-RC1</version>
    <scope>test</scope>
</dependency>
```

### Basic Usage with JUnit 5

```java
import org.junit.jupiter.api.Test;
import org.openjproxy.testcontainers.OjpContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;

@Testcontainers
public class MyIntegrationTest {

    @Container
    public static OjpContainer ojpContainer = new OjpContainer();

    @Test
    public void testDatabaseConnection() throws Exception {
        // Get OJP connection string
        String ojpConnectionString = ojpContainer.getOjpConnectionString();
        
        // Use it in your JDBC URL (PostgreSQL example)
        String jdbcUrl = "jdbc:ojp[" + ojpConnectionString + "]_postgresql://postgres-host:5432/mydb";
        
        // Connect through OJP
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "user", "password")) {
            // Your test code here
        }
    }
}
```

### Using a Specific OJP Version

```java
@Container
public static OjpContainer ojpContainer = new OjpContainer("0.4.2-beta");
```

Or with full image name:

```java
@Container
public static OjpContainer ojpContainer = new OjpContainer(
    DockerImageName.parse("rrobetti/ojp:1.0.0-RC1")
);
```

### Manual Container Management

```java
@Test
public void testWithManualContainer() {
    try (OjpContainer ojpContainer = new OjpContainer()) {
        ojpContainer.start();
        
        String host = ojpContainer.getOjpHost();
        Integer port = ojpContainer.getOjpPort();
        
        // Your test code here
    }
}
```

### Using a Custom Docker Image

If you want to use a custom Docker image (e.g., from a private registry or with a different name):

```java
DockerImageName customImage = DockerImageName.parse("myregistry.com/my-ojp:1.0.0")
        .asCompatibleSubstituteFor("rrobetti/ojp:1.0.0-RC1");
        
OjpContainer ojpContainer = new OjpContainer(customImage);
```

### Using a Specific Version

```java
OjpContainer ojpContainer = new OjpContainer("rrobetti/ojp:1.0.0-RC1");
```

## API Methods

The `OjpContainer` class provides the following methods:

- `getOjpConnectionString()` - Returns "host:port" for use in JDBC URLs
- `getOjpHost()` - Returns the container host
- `getOjpPort()` - Returns the mapped OJP port (default: 1059)

## Requirements

- Docker must be installed and running
- Java 11 or higher
- The OJP JDBC driver must be in your project's classpath

## See Also

- [OJP Main Repository](https://github.com/Open-J-Proxy/ojp)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [OJP JDBC Driver](../ojp-jdbc-driver/README.md)
