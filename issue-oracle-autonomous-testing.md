# Implement Autonomous Oracle Database Testing Workflow

## Summary
Create a dedicated GitHub Actions workflow to run Oracle Database tests autonomously in CI without requiring Oracle JDBC driver licenses in the main repository.

## Background
Currently, Oracle tests are disabled in the Main CI workflow (see lines 55-68 in `.github/workflows/main.yml`) because we cannot keep Oracle JDBC drivers in the ojp-server module due to licensing requirements. This issue implements a separate workflow that will:
1. Add the Oracle JDBC driver dynamically during CI execution
2. Compile and test with Oracle Database
3. Run autonomously without affecting the main workflow

## Implementation Details

### 1. Create New Workflow File
Create `.github/workflows/oracle-testing.yml` based on the existing `main.yml` workflow structure with the following modifications:

**Workflow Configuration:**
- **Name:** `Oracle Database Testing`
- **Triggers:** 
  - Push to `main` branch
  - Pull requests to `main` branch
  - Manual workflow dispatch (for on-demand testing)
- **Java Version Matrix:** Test with JDK 11, 17, 21, and 22 (same as main workflow)

### 2. Database Service Configuration
Add Oracle Database as a service in the workflow:

```yaml
services:
  oracle:
    image: gvenzl/oracle-xe:21-slim
    env:
      ORACLE_PASSWORD: testpassword
      APP_USER: testuser
      APP_USER_PASSWORD: testpassword
    options: >-
      --name ojp-oracle
      --health-cmd "echo 'SELECT 1 FROM DUAL;' | sqlplus -s system/testpassword@localhost/XEPDB1"
      --health-interval 20s
      --health-timeout 10s
      --health-retries 10
    ports:
      - 1521:1521
```

**Note:** Oracle XE can take 2-5 minutes to fully start up. Ensure adequate health check retries.

### 3. Modify ojp-server pom.xml

Add a placeholder comment in `ojp-server/pom.xml` to indicate where CI workflows should inject database drivers. Add this comment after the existing database dependencies (around line 70, after the PostgreSQL dependency):

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

### 4. Inject Oracle JDBC Driver in Workflow

Add a step after checkout to inject the Oracle JDBC driver into `ojp-server/pom.xml`:

```yaml
- name: Add Oracle JDBC Driver to ojp-server
  run: |
    # Define the Oracle JDBC dependency
    ORACLE_DEPENDENCY='        <!-- Oracle JDBC Driver (added by CI) -->\n        <dependency>\n            <groupId>com.oracle.database.jdbc</groupId>\n            <artifactId>ojdbc11</artifactId>\n            <version>23.8.0.25.04</version>\n        </dependency>'
    
    # Insert after the placeholder comment
    sed -i "/<!-- PROPRIETARY DATABASE DRIVERS PLACEHOLDER -->/a\\$ORACLE_DEPENDENCY" ojp-server/pom.xml
    
    # Verify the insertion
    echo "=== Modified ojp-server/pom.xml dependencies section ==="
    sed -n '/PROPRIETARY DATABASE DRIVERS PLACEHOLDER/,/HikariCP/p' ojp-server/pom.xml
```

### 5. Build and Test Steps

Follow the same build pattern as `main.yml`:

1. **Build ojp-grpc-commons** with JDK 22
2. **Build ojp-server** with JDK 22 (now includes Oracle driver)
3. **Run ojp-server** as background process
4. **Switch to matrix JDK version** (11, 17, 21, or 22)
5. **Build ojp-jdbc-driver** with matrix JDK
6. **Run Oracle tests** with the flag `-DenableOracleTests`

```yaml
- name: Test (ojp-jdbc-driver) with Oracle enabled
  run: mvn test -pl ojp-jdbc-driver -Dgpg.skip=true -DenableOracleTests
```

### 6. Test Configuration

The workflow will use existing Oracle test connection configurations:
- `oracle_connections.csv` - Oracle-only tests
- `h2_oracle_connections.csv` - Combined H2 and Oracle tests
- `h2_postgres_mysql_mariadb_oracle_connections.csv` - Multi-database tests including Oracle

Reference: See [Oracle Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/oracle-testing-guide.md)

## Acceptance Criteria

- [ ] New workflow file `.github/workflows/oracle-testing.yml` is created
- [ ] Placeholder comment added to `ojp-server/pom.xml` for CI driver injection
- [ ] Workflow successfully injects Oracle JDBC driver into pom.xml during CI execution
- [ ] Oracle Database service starts successfully in the workflow
- [ ] ojp-server builds successfully with Oracle JDBC driver included
- [ ] All Oracle-specific tests pass (test classes matching `Oracle*Test.java`)
- [ ] Workflow runs in parallel with main CI without conflicts
- [ ] Workflow badge can be added to README.md showing Oracle test status
- [ ] No Oracle JDBC driver is committed to the repository (remains license-clean)

## Test Classes to Execute

The following Oracle test classes should execute successfully:
- `OracleBinaryStreamIntegrationTest`
- `OracleBlobIntegrationTest`
- `OracleXAIntegrationTest`
- `OracleResultSetTest`
- `OracleStatementExtensiveTests`
- `OracleSavepointTests`
- `OracleResultSetMetaDataExtensiveTests`
- `OracleReadMultipleBlocksOfDataIntegrationTest`
- `OraclePreparedStatementExtensiveTests`
- `OracleDatabaseMetaDataExtensiveTests`
- `OracleConnectionExtensiveTests`

## Additional Notes

- Oracle tests are disabled by default (controlled by `-DenableOracleTests` system property)
- The Oracle XE Docker image (`gvenzl/oracle-xe:21-slim`) is used for testing
- Connection URL format: `jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1`
- Test user: `testuser` with password: `testpassword`
- The workflow should not block or delay the main CI workflow
- Consider running this workflow on a schedule (e.g., nightly) in addition to PR triggers to conserve CI resources

## Related Documentation
- [Oracle Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/oracle-testing-guide.md)
- [Main CI Workflow](https://github.com/Open-J-Proxy/ojp/blob/main/.github/workflows/main.yml)
- [Setup and Testing OJP Source](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md)

## Dependencies
None - this is a standalone workflow implementation.

## Estimated Effort
**Medium** - Requires workflow creation, pom.xml modification, and thorough testing of the CI pipeline.
