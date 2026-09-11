# SQL Server Integration Tests with TestContainers

## Overview

All SQL Server integration tests have been migrated to use TestContainers. This provides:
- Automatic SQL Server container management
- Consistent test environment across all environments
- No need for external SQL Server instances

## How It Works

### TestContainer Setup

1. **SQLServerTestContainer** - Singleton class that manages a shared SQL Server container
   - Located: `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/testutil/SQLServerTestContainer.java`
   - Uses `mcr.microsoft.com/mssql/server:2022-latest` Docker image
   - Automatically starts on first test execution
   - Shared across all SQL Server tests for efficiency
   - Automatically stops when tests complete

2. **SQLServerConnectionProvider** - Custom JUnit ArgumentsProvider
   - Located: `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/testutil/SQLServerConnectionProvider.java`
   - Provides dynamic connection details from the TestContainer
   - Replaces CSV-based connection configuration

### Test Changes

All SQL Server test classes have been updated:
- Changed from `@CsvFileSource` to `@ArgumentsSource(SQLServerConnectionProvider.class)`
- Tests automatically use TestContainer when `enableSqlServerTests=true`

### Running SQL Server Tests

#### Prerequisites
- Docker must be installed and running
- OJP server must be started (tests connect through OJP proxy)

#### Local Execution
```bash
# Start OJP server first (requires Java 25+)
java -Duser.timezone=UTC -jar ojp-server/target/ojp-server-1.0.0-RC1-shaded.jar &

# Run SQL Server tests
mvn test -pl ojp-jdbc-driver -DenableSqlServerTests=true -Dtest="SQLServer*"
```

#### CI/CD Workflow
The `.github/workflows/sqlserver-testing.yml` workflow:
- Automatically builds the OJP server
- Starts the server in background
- Runs all SQL Server integration tests
- Uses TestContainers for SQL Server instance
- Matrix tests against Java 11, 17, 21, 25

### Dependencies

> **⚠️ For Version 0.4.0-beta and Later:**  
> Runtime JDBC drivers are no longer added to ojp-server/pom.xml. Instead, they must be placed in the `ojp-libs` folder. The test dependencies below are still required in the test modules.

#### ojp-libs (Runtime - Server)
For running the OJP server, SQL Server JDBC driver must be placed in the `ojp-libs` directory:
```bash
# Download SQL Server JDBC driver
mkdir -p ojp-libs
cp ~/Downloads/mssql-jdbc-12.8.1.jre11.jar ./ojp-libs/
```

See [Database Drivers Configuration Guide](configuration/DRIVERS_AND_LIBS.md) for more details.

#### ojp-jdbc-driver/pom.xml
```xml
<!-- SQL Server JDBC Driver - Required by TestContainers SQL Server -->
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.8.1.jre11</version>
    <scope>test</scope>
</dependency>

<!-- TestContainers SQL Server -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mssqlserver</artifactId>
    <version>1.0.0-RC1</version>
    <scope>test</scope>
</dependency>
```

## Test Files Updated

All SQL Server integration test files:
- SQLServerBinaryStreamIntegrationTest.java
- SQLServerBlobIntegrationTest.java
- SQLServerConnectionExtensiveTests.java
- SQLServerDatabaseMetaDataExtensiveTests.java
- SQLServerMultipleTypesIntegrationTest.java
- SQLServerPreparedStatementExtensiveTests.java
- SQLServerReadMultipleBlocksOfDataIntegrationTest.java
- SQLServerResultSetMetaDataExtensiveTests.java
- SQLServerResultSetTest.java
- SQLServerSavepointTests.java
- SQLServerStatementExtensiveTests.java

## Benefits

1. **No External Dependencies** - No need to set up external SQL Server
2. **Consistency** - Same environment for all developers and CI
3. **Isolation** - Each test run uses fresh containers
4. **Speed** - Shared container across tests improves performance
5. **Simplicity** - Automatic container lifecycle management
