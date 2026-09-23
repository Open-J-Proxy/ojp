# Implement Autonomous SQL Server Database Testing Workflow

## Summary
Create a dedicated GitHub Actions workflow to run SQL Server Database tests autonomously in CI without requiring Microsoft SQL Server JDBC driver licenses in the main repository.

## Background
Currently, SQL Server tests are disabled in the Main CI workflow (see lines 70-81 in `.github/workflows/main.yml`) because we cannot keep SQL Server JDBC drivers in the ojp-server module due to licensing requirements. This issue implements a separate workflow that will:
1. Add the Microsoft SQL Server JDBC driver dynamically during CI execution
2. Set up the SQL Server database with required test databases and users
3. Compile and test with SQL Server Database
4. Run autonomously without affecting the main workflow

## Implementation Details

### 1. Create New Workflow File
Create `.github/workflows/sqlserver-testing.yml` based on the existing `main.yml` workflow structure with the following modifications:

**Workflow Configuration:**
- **Name:** `SQL Server Database Testing`
- **Triggers:** 
  - Push to `main` branch
  - Pull requests to `main` branch
  - Manual workflow dispatch (for on-demand testing)
- **Java Version Matrix:** Test with JDK 11, 17, 21, and 22 (same as main workflow)

### 2. Database Service Configuration
Add SQL Server Database as a service in the workflow:

```yaml
services:
  sqlserver:
    image: mcr.microsoft.com/mssql/server:2022-latest
    env:
      ACCEPT_EULA: Y
      SA_PASSWORD: TestPassword123!
    options: >-
      --name ojp-sqlserver
      --health-cmd "/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P TestPassword123! -Q 'SELECT 1'"
      --health-interval 20s
      --health-timeout 10s
      --health-retries 10
    ports:
      - 1433:1433
```

**Note:** SQL Server can take 3-5 minutes to fully start up. Ensure adequate health check retries and startup wait time.

### 3. Database Initialization Step

Add a step to create the test database and user after SQL Server starts:

```yaml
- name: Initialize SQL Server Database
  run: |
    # Wait for SQL Server to be fully ready
    sleep 30
    
    # Create test database and user
    docker exec ojp-sqlserver /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P TestPassword123! -Q "
    CREATE DATABASE defaultdb;
    CREATE LOGIN testuser WITH PASSWORD = 'TestPassword123!';
    USE defaultdb;
    CREATE USER testuser FOR LOGIN testuser;
    ALTER ROLE db_datareader ADD MEMBER testuser;
    ALTER ROLE db_datawriter ADD MEMBER testuser;
    ALTER ROLE db_ddladmin ADD MEMBER testuser;
    ALTER SERVER ROLE sysadmin ADD MEMBER testuser;
    " || echo "Database setup completed with warnings"
    
    echo "=== SQL Server database initialized ==="
```

**Important:** SQL Server requires additional setup steps compared to other databases:
- Creating the `defaultdb` database
- Creating the `testuser` login and user
- Granting appropriate permissions (datareader, datawriter, ddladmin, sysadmin)

### 4. Modify ojp-server pom.xml

Use the same placeholder comment added for Oracle (if not already present). Add this comment after the existing database dependencies (around line 70, after the PostgreSQL dependency):

```xml
        <!-- https://mvnrepository.com/artifact/org.postgresql/postgresql -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.8</version>
        </dependency>

        <!-- PROPRIETARY DATABASE DRIVERS PLACEHOLDER -->
        <!-- CI workflows dynamically add Oracle, SQL Server, and DB2 drivers here -->
        <!-- Do not commit proprietary drivers to this pom.xml -->

        <!-- https://mvnrepository.com/artifact/com.zaxxer/HikariCP -->
```

### 5. Inject SQL Server JDBC Driver in Workflow

Add a step after checkout to inject the Microsoft SQL Server JDBC driver into `ojp-server/pom.xml`:

```yaml
- name: Add SQL Server JDBC Driver to ojp-server
  run: |
    # Define the SQL Server JDBC dependency
    SQLSERVER_DEPENDENCY='        <!-- Microsoft SQL Server JDBC Driver (added by CI) -->\n        <dependency>\n            <groupId>com.microsoft.sqlserver</groupId>\n            <artifactId>mssql-jdbc</artifactId>\n            <version>12.8.1.jre11</version>\n        </dependency>'
    
    # Insert after the placeholder comment
    sed -i "/<!-- PROPRIETARY DATABASE DRIVERS PLACEHOLDER -->/a\\$SQLSERVER_DEPENDENCY" ojp-server/pom.xml
    
    # Verify the insertion
    echo "=== Modified ojp-server/pom.xml dependencies section ==="
    sed -n '/PROPRIETARY DATABASE DRIVERS PLACEHOLDER/,/HikariCP/p' ojp-server/pom.xml
```

### 6. Build and Test Steps

Follow the same build pattern as `main.yml`:

1. **Build ojp-grpc-commons** with JDK 22
2. **Build ojp-server** with JDK 22 (now includes SQL Server driver)
3. **Run ojp-server** as background process
4. **Switch to matrix JDK version** (11, 17, 21, or 22)
5. **Build ojp-jdbc-driver** with matrix JDK
6. **Run SQL Server tests** with the flag `-DenableSqlServerTests`

```yaml
- name: Test (ojp-jdbc-driver) with SQL Server enabled
  run: mvn test -pl ojp-jdbc-driver -Dgpg.skip=true -DenableSqlServerTests
```

### 7. Test Configuration

The workflow will use existing SQL Server test connection configurations:
- `sqlserver_connections.csv` - SQL Server-only tests
- `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` - Multi-database tests including SQL Server

**Connection String Format:**
```
jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;databaseName=defaultdb;encrypt=false;trustServerCertificate=true
```

Reference: See [SQL Server Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/sqlserver-testing-guide.md)

### 8. Special Considerations

**LOB Handling:** SQL Server JDBC driver has special LOB (Large Object) handling requirements:
- Advancing a ResultSet invalidates any associated LOBs (Blob, Clob, binary streams)
- OJP reads LOB-containing rows one at a time instead of batching
- LOBs are fully read into memory upfront

This behavior is already implemented in OJP and the tests validate this functionality.

## Acceptance Criteria

- [ ] New workflow file `.github/workflows/sqlserver-testing.yml` is created
- [ ] Placeholder comment exists in `ojp-server/pom.xml` for CI driver injection (shared with Oracle/DB2)
- [ ] Workflow successfully injects SQL Server JDBC driver into pom.xml during CI execution
- [ ] SQL Server Database service starts successfully in the workflow
- [ ] Test database (`defaultdb`) and user (`testuser`) are created successfully
- [ ] ojp-server builds successfully with SQL Server JDBC driver included
- [ ] All SQL Server-specific tests pass (test classes matching `SQLServer*Test.java`)
- [ ] Workflow runs in parallel with main CI without conflicts
- [ ] Workflow badge can be added to README.md showing SQL Server test status
- [ ] No SQL Server JDBC driver is committed to the repository (remains license-clean)

## Test Classes to Execute

The following SQL Server test classes should execute successfully:
- `SQLServerBinaryStreamIntegrationTest`
- `SQLServerBlobIntegrationTest`
- `SQLServerPreparedStatementExtensiveTests`
- `SQLServerSavepointTests`
- `SQLServerConnectionExtensiveTests`
- `SQLServerDatabaseMetaDataExtensiveTests`
- `SQLServerResultSetMetaDataExtensiveTests`
- `SQLServerResultSetTest`
- `SQLServerStatementExtensiveTests`
- `SQLServerXAIntegrationTest`
- `SQLServerReadMultipleBlocksOfDataIntegrationTest`
- `SQLServerMultipleTypesIntegrationTest`

## Additional Notes

- SQL Server tests are disabled by default (controlled by `-DenableSqlServerTests` system property)
- The official Microsoft SQL Server 2022 image is used for testing
- Database initialization is **required** - unlike Oracle, SQL Server doesn't come pre-configured with test databases
- Connection uses `encrypt=false;trustServerCertificate=true` for testing purposes only
- The workflow should not block or delay the main CI workflow
- Consider running this workflow on a schedule (e.g., nightly) in addition to PR triggers to conserve CI resources
- SQL Server startup time is longer than PostgreSQL/MySQL but shorter than DB2

## Related Documentation
- [SQL Server Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/sqlserver-testing-guide.md)
- [Main CI Workflow](https://github.com/Open-J-Proxy/ojp/blob/main/.github/workflows/main.yml)
- [Setup and Testing OJP Source](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md)

## Dependencies
None - this is a standalone workflow implementation. If the Oracle workflow is implemented first, they will share the same placeholder comment in `ojp-server/pom.xml`.

## Estimated Effort
**Medium to High** - Requires workflow creation, pom.xml modification, database initialization scripting, and thorough testing of the CI pipeline. Slightly more complex than Oracle due to required database setup steps.
