# Implement Autonomous IBM DB2 Database Testing Workflow

## Summary
Create a dedicated GitHub Actions workflow to run IBM DB2 Database tests autonomously in CI without requiring IBM DB2 JDBC driver licenses in the main repository.

## Background
Currently, DB2 tests are disabled in the Main CI workflow (see lines 83-108 in `.github/workflows/main.yml`) because we cannot keep IBM DB2 JDBC drivers in the ojp-server module due to licensing requirements. This issue implements a separate workflow that will:
1. Add the IBM DB2 JDBC driver dynamically during CI execution
2. Set up the DB2 database with adequate startup time and resources
3. Compile and test with DB2 Database
4. Run autonomously without affecting the main workflow

## Implementation Details

### 1. Create New Workflow File
Create `.github/workflows/db2-testing.yml` based on the existing `main.yml` workflow structure with the following modifications:

**Workflow Configuration:**
- **Name:** `IBM DB2 Database Testing`
- **Triggers:** 
  - Push to `main` branch
  - Pull requests to `main` branch
  - Manual workflow dispatch (for on-demand testing)
  - **Optional:** Scheduled runs (e.g., nightly) to conserve CI resources
- **Java Version Matrix:** Test with JDK 11, 17, 21, and 22 (same as main workflow)

### 2. Database Service Configuration
Add IBM DB2 Database as a service in the workflow:

```yaml
services:
  db2:
    image: ibmcom/db2:11.5.8.0
    env:
      LICENSE: accept
      DB2INSTANCE: db2inst1
      DB2INST1_PASSWORD: testpassword
      DBNAME: testdb
      BLU: false
      ENABLE_ORACLE_COMPATIBILITY: false
      UPDATEAVAIL: NO
      TO_CREATE_SAMPLEDB: false
      REPODB: false
      IS_OSXFS: false
      PERSISTENT_HOME: false
      HADR_ENABLED: false
    options: >-
      --name ojp-db2
      --privileged
      --memory 6g
      --health-cmd "su - db2inst1 -c 'db2 connect to testdb && db2 select 1 from sysibm.sysdummy1'"
      --health-interval 60s
      --health-timeout 30s
      --health-retries 20
    ports:
      - 50000:50000
```

**Critical Notes:** 
- **DB2 requires privileged mode** (`--privileged`) to run properly
- **Memory requirement:** Minimum 6GB RAM allocation (`--memory 6g`)
- **Startup time:** DB2 can take **15+ minutes** to start the first time, especially in CI environments
- **Health checks:** Very generous intervals (60s) and retries (20) are needed

### 3. Extended Database Startup Wait

Add an extended wait step after services are initialized to ensure DB2 is fully ready:

```yaml
- name: Wait for DB2 to be fully ready
  run: |
    echo "Waiting for DB2 to fully initialize (this may take 15+ minutes)..."
    
    # Wait for initial startup
    sleep 180
    
    # Poll DB2 until it's ready (max 20 minutes)
    COUNTER=0
    MAX_ATTEMPTS=60
    
    until docker exec ojp-db2 su - db2inst1 -c "db2 connect to testdb" > /dev/null 2>&1; do
      COUNTER=$((COUNTER+1))
      if [ $COUNTER -eq $MAX_ATTEMPTS ]; then
        echo "DB2 failed to start after $MAX_ATTEMPTS attempts"
        docker logs ojp-db2
        exit 1
      fi
      echo "Waiting for DB2... attempt $COUNTER/$MAX_ATTEMPTS"
      sleep 20
    done
    
    echo "=== DB2 is ready ==="
    
    # Verify connection
    docker exec ojp-db2 su - db2inst1 -c "db2 connect to testdb && db2 'select 1 from sysibm.sysdummy1'"
```

**Important:** This step is critical because:
- DB2 health checks may report healthy before the database is fully operational
- The first-time initialization involves extensive setup
- Premature test execution will result in connection failures

### 4. Modify ojp-server pom.xml

Use the same placeholder comment added for Oracle and SQL Server (if not already present). Add this comment after the existing database dependencies (around line 70, after the PostgreSQL dependency):

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

### 5. Inject DB2 JDBC Driver in Workflow

Add a step after checkout to inject the IBM DB2 JDBC driver into `ojp-server/pom.xml`:

```yaml
- name: Add IBM DB2 JDBC Driver to ojp-server
  run: |
    # Define the DB2 JDBC dependency
    DB2_DEPENDENCY='        <!-- IBM DB2 JDBC Driver (added by CI) -->\n        <dependency>\n            <groupId>com.ibm.db2</groupId>\n            <artifactId>jcc</artifactId>\n            <version>11.5.9.0</version>\n        </dependency>'
    
    # Insert after the placeholder comment
    sed -i "/<!-- PROPRIETARY DATABASE DRIVERS PLACEHOLDER -->/a\\$DB2_DEPENDENCY" ojp-server/pom.xml
    
    # Verify the insertion
    echo "=== Modified ojp-server/pom.xml dependencies section ==="
    sed -n '/PROPRIETARY DATABASE DRIVERS PLACEHOLDER/,/HikariCP/p' ojp-server/pom.xml
```

### 6. Build and Test Steps

Follow the same build pattern as `main.yml`:

1. **Build ojp-grpc-commons** with JDK 22
2. **Build ojp-server** with JDK 22 (now includes DB2 driver)
3. **Run ojp-server** as background process
4. **Switch to matrix JDK version** (11, 17, 21, or 22)
5. **Build ojp-jdbc-driver** with matrix JDK
6. **Run DB2 tests** with the flag `-DenableDb2Tests`

```yaml
- name: Test (ojp-jdbc-driver) with DB2 enabled
  run: mvn test -pl ojp-jdbc-driver -Dgpg.skip=true -DenableDb2Tests
```

### 7. Test Configuration

The workflow will use existing DB2 test connection configurations:
- `db2_connections.csv` - DB2-only tests
- `h2_postgres_mysql_mariadb_oracle_sqlserver_db2_connections.csv` - Multi-database tests including DB2

**Connection String Format:**
```
jdbc:ojp[localhost:1059]_db2://localhost:50000/testdb
```

**Connection Details:**
- URL: `jdbc:ojp[localhost:1059]_db2://localhost:50000/testdb`
- User: `db2inst1`
- Password: `testpassword`
- Database: `testdb`

Reference: See [DB2 Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/db2-testing-guide.md)

### 8. Special Considerations

**LOB and ResultSetMetadata Handling:** DB2 JDBC driver has special requirements:
- Both LOBs and ResultSetMetaData become invalid once the cursor advances or ResultSet is accessed from another thread
- OJP reads rows one at a time when LOBs are present (no batching)
- LOB data and metadata are eagerly cached immediately upon row access
- This ensures consistency and prevents driver exceptions

This behavior is already implemented in OJP and the tests validate this functionality.

**Resource Requirements:**
- DB2 requires privileged Docker mode
- Minimum 6GB RAM allocation
- Significantly longer startup time than other databases
- Consider running this workflow less frequently (e.g., nightly builds only)

## Acceptance Criteria

- [ ] New workflow file `.github/workflows/db2-testing.yml` is created
- [ ] Placeholder comment exists in `ojp-server/pom.xml` for CI driver injection (shared with Oracle/SQL Server)
- [ ] Workflow successfully injects DB2 JDBC driver into pom.xml during CI execution
- [ ] DB2 Database service starts successfully in the workflow with adequate wait time
- [ ] Workflow handles DB2's extended startup time (15+ minutes) gracefully
- [ ] ojp-server builds successfully with DB2 JDBC driver included
- [ ] All DB2-specific tests pass (test classes matching `Db2*Test.java`)
- [ ] Workflow runs in parallel with main CI without conflicts (though resource-intensive)
- [ ] Workflow badge can be added to README.md showing DB2 test status
- [ ] No DB2 JDBC driver is committed to the repository (remains license-clean)
- [ ] Workflow includes proper error handling for DB2 startup failures with log output

## Test Classes to Execute

The following DB2 test classes should execute successfully:
- `Db2ReadMultipleBlocksOfDataIntegrationTest`
- `Db2PreparedStatementExtensiveTests`
- `Db2BlobIntegrationTest`
- `Db2DatabaseMetaDataExtensiveTests`
- `Db2ResultSetTest`
- `Db2ResultSetMetaDataExtensiveTests`
- `Db2ConnectionExtensiveTests`
- `Db2SavepointTests`
- `Db2BinaryStreamIntegrationTest`
- `Db2MultipleTypesIntegrationTest`
- `Db2StatementExtensiveTests`
- `Db2XAIntegrationTest`

## Additional Notes

- DB2 tests are disabled by default (controlled by `-DenableDb2Tests` system property)
- The official IBM DB2 11.5.8.0 image is used for testing
- **This is the most resource-intensive workflow** of the three proprietary databases
- **Consider these optimization strategies:**
  - Run only on scheduled intervals (nightly) rather than every PR
  - Use workflow dispatch for manual on-demand testing
  - Possibly reduce Java version matrix (e.g., test only JDK 11 and 22)
  - Add a workflow condition to skip if `[skip db2]` is in commit message
- DB2 requires significantly more startup time than Oracle or SQL Server
- GitHub Actions runners may have memory constraints - monitor runner capacity
- The workflow should include comprehensive logging for debugging DB2 startup issues

## Performance Optimization Suggestions

Given DB2's resource requirements, consider:

```yaml
# Example: Run on schedule instead of every push
on:
  schedule:
    - cron: '0 2 * * *'  # Run at 2 AM UTC daily
  workflow_dispatch:  # Allow manual triggers
  pull_request:
    branches: [ main ]
    paths:
      - 'ojp-jdbc-driver/src/test/java/**/Db2*.java'  # Only when DB2 tests change
```

## Related Documentation
- [DB2 Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/db2-testing-guide.md)
- [Main CI Workflow](https://github.com/Open-J-Proxy/ojp/blob/main/.github/workflows/main.yml)
- [Setup and Testing OJP Source](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md)

## Dependencies
None - this is a standalone workflow implementation. If Oracle and SQL Server workflows are implemented first, they will share the same placeholder comment in `ojp-server/pom.xml`.

## Estimated Effort
**High** - Requires workflow creation, pom.xml modification, extended startup time handling, resource allocation considerations, and thorough testing of the CI pipeline. Most complex of the three due to DB2's resource requirements and long startup time. Additional effort needed for performance optimization strategies.
