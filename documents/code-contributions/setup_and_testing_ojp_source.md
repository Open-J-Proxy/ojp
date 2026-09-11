
### Prerequisites
- **For OJP JDBC Driver**: Java 11 or higher
- **For OJP Server**: Java 25 or higher
- **For Development/Testing**: Java 25 or higher (recommended)
- Maven 3.9+
- Docker (for running databases and OJP server)

### Getting Started

1. **Clone the repository**
   ```bash
   git clone https://github.com/Open-J-Proxy/ojp.git
   cd ojp
   ```

2. **Build the project**
   ```bash
   mvn clean install -DskipTests
   ```

3. **Download JDBC drivers** (required before starting the OJP server)
   
   > **⚠️ Required for v0.4.0-beta and later:** Starting from version 0.4.0-beta, JDBC drivers are no longer included in the OJP Server JAR and must be downloaded separately.
   
   OJP Server requires JDBC drivers to connect to databases. The open source drivers (H2, PostgreSQL, MySQL, MariaDB) are not packaged with OJP by default and must be downloaded separately.
   
   Run the download script from the project root:
   ```bash
   bash ojp-server/download-drivers.sh
   ```
   
   This script downloads the following drivers from Maven Central:
   - H2 Database
   - PostgreSQL
   - MySQL
   - MariaDB
   
   The drivers will be placed in the `ojp-server/ojp-libs` directory. You can optionally specify a custom directory:
   ```bash
   bash ojp-server/download-drivers.sh /path/to/custom/directory
   ```
   
   **Note:** For proprietary databases (Oracle, SQL Server, DB2), you'll need to manually download and place their JDBC drivers in the ojp-libs directory. 
   See the [Oracle Testing Guide](../../documents/environment-setup/oracle-testing-guide.md), 
   [SQL Server Testing Guide](../../documents/environment-setup/sqlserver-testing-guide.md), 
   and [DB2 Testing Guide](../../documents/environment-setup/db2-testing-guide.md) for specific instructions.

4. **Start OJP server** (required for running tests)
   ```bash
   mvn verify -pl ojp-server -Prun-ojp-server
   ```

5. **Run integration tests**
   Navigate to the ojp-jdbc-driver folder first:
   ```bash
   cd ojp-jdbc-driver
   mvn test -DenableH2Tests=true
   ```
**Note:** By default, all database tests (including H2) are disabled. To run specific database tests locally, use the appropriate enable flags (e.g., `-DenableH2Tests=true`, `-DenablePostgresTests=true`). To run the full set of integration tests, you have to run all the databases locally. Follow the instructions at [Run Local Databases](../../documents/environment-setup/run-local-databases.md)

### Running Integration Tests from IDE

When running integration tests via IDEs (IntelliJ IDEA, Eclipse, VSCode, etc.) against Docker containers of ojp-server, you must configure the following JVM parameters:

```
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
```

**Why these parameters are required:**
- **`-Dfile.encoding=UTF-8`**: Ensures consistent character encoding across different environments and prevents encoding-related test failures when handling database data with special characters.
- **`-Duser.timezone=UTC`**: Standardizes timezone handling to prevent time-related test failures due to local timezone differences.

**How to configure in your IDE:**

**IntelliJ IDEA:**
1. Go to Run → Edit Configurations
2. Select your test configuration (or create a new JUnit configuration)
3. In the "VM options" field, add: `-Dfile.encoding=UTF-8 -Duser.timezone=UTC`
4. Apply and run your tests

**Eclipse:**
1. Right-click on your test → Run As → Run Configurations
2. Select your JUnit configuration
3. Go to the "Arguments" tab
4. In the "VM arguments" section, add: `-Dfile.encoding=UTF-8 -Duser.timezone=UTC`
5. Apply and run your tests

**VSCode:**
1. Open `.vscode/settings.json` in your project
2. Add or modify the Java test configuration:
   ```json
   {
     "java.test.config": {
       "vmArgs": ["-Dfile.encoding=UTF-8", "-Duser.timezone=UTC"]
     }
   }
   ```
3. Run your tests

**Note:** These parameters are automatically set in Maven Surefire plugin configuration for command-line test execution, but IDE test runners require manual configuration.

### Databases with integration tests
We have comprehensive JDBC integration tests with OJP for the following databases:
- Postgres
- MariaDB
- MySQL
- CockroachDB
- Oracle
- SQL Server
- DB2
- H2

The free and open source databases (H2, Postgres, MySQL, MariaDB and CockroachDB) JDBC drivers must be downloaded separately using the `download-drivers.sh` script (see step 3 above) for both runnable JAR and Docker deployments.

> **📌 Version 0.4.0-beta and Later:** Starting from v0.4.0-beta, JDBC drivers are not included in either the runnable JAR or Docker images.

All database tests are disabled by default and must be explicitly enabled using their respective flags (e.g., `-DenableH2Tests=true`). In CI pipelines, only H2 tests run in the Main CI workflow as a fast fail-fast mechanism. For proprietary databases like Oracle and SQL Server, see specific sections below.

### Oracle Database Setup (Optional)
Oracle integration tests require the Oracle JDBC driver and due to licensing restrictions we do not pack it with OJP.
For detailed Oracle setup instructions, see [Oracle Testing Guide](../../documents/environment-setup/oracle-testing-guide.md).

### SQL Server Database Setup (Optional)
SQL Server integration tests use the Microsoft SQL Server JDBC driver which is not included in OJP dependencies.
For detailed SQL Server setup instructions, see [SQL Server Testing Guide](../../documents/environment-setup/sqlserver-testing-guide.md).

### DB2 Database Setup (Optional)
DB2 integration tests use the IBM JDBC driver which is not included in OJP dependencies.
For detailed DB2 instructions, see [DB2 Testing Guide](../../documents/environment-setup/db2-testing-guide.md).

### CockroachDB Database Setup (Optional)
CockroachDB integration tests use the PostgreSQL JDBC driver. Starting from version 0.4.0-beta, you must download drivers and place them in the `ojp-libs` folder.
For detailed CockroachDB setup instructions, see [CockroachDB Testing Guide](../../documents/environment-setup/cockroachdb-testing-guide.md).


### Testing Configuration
- Test connection configurations are stored in CSV files under `test/resources`
- File naming indicates supported databases (e.g., `h2_postgres_connections.csv` supports H2 and PostgreSQL)
- Integration tests run against each configured connection
- See [run-local-databases.md](documents/environment-setup/run-local-databases.md) for local database setup

### Test Options
- `-DenableH2Tests` - Enable H2 integration tests (disabled by default)
- `-DenablePostgresTests` - Enable PostgreSQL integration tests (disabled by default)
- `-DenableMySQLTests` - Enable MySQL integration tests (disabled by default)
- `-DenableMariaDBTests` - Enable MariaDB integration tests (disabled by default)
- `-DenableCockroachDBTests` - Enable CockroachDB integration tests (disabled by default)
- `-DenableOracleTests` - Enable Oracle integration tests (disabled by default, requires manual Oracle JDBC driver setup)
- `-DenableSqlServerTests` - Enable SQL Server integration tests (disabled by default)

### Workflow Ordering and Fail-Fast Strategy
The CI workflows are organized in a hierarchical order to save CI cycles:

1. **Main CI** - Runs first and only executes H2 database tests
   - H2 is an embedded database that requires no external services
   - Runs the fastest and serves as a fail-fast mechanism
   - If basic functionality is broken, this workflow fails immediately without running expensive database setups
   - Only enables H2 tests with `-DenableH2Tests=true`

2. **Specialized Test Jobs** - Run only after Main CI succeeds (using `needs: [build-test]`)
   - PostgreSQL Integration Tests (JDK 11, 17, 21, 25)
     - **Note**: All PostgreSQL integration tests run twice per matrix configuration:
       1. Against a standard OJP server without SQL enhancer
       2. Against an OJP server with SQL enhancer enabled (`-Dojp.sql.enhancer.enabled=true`)
     - This ensures compatibility and correctness with both configurations
   - MySQL Integration Tests (JDK 11, 17, 21, 25)
   - MariaDB Integration Tests (JDK 11, 17, 21, 25)
   - CockroachDB Integration Tests (JDK 11, 17, 21, 25)
   - DB2 Integration Tests (JDK 11, 17, 21, 25)
   - Multinode Integration Tests (PostgreSQL-based failover testing)
   - Oracle Database Testing (JDK 11, 17, 21, 25)
   - SQL Server Integration Tests (JDK 11, 17, 21, 25)

**Implementation**: All jobs are consolidated into a single workflow file (`.github/workflows/main.yml`) with job dependencies using the `needs` keyword. This ensures sequential execution works on **all branches** (PRs, main, feature branches) without relying on GitHub Actions `workflow_run` triggers.
   
This approach ensures that:
- Quick feedback on broken code (H2 tests run in seconds)
- Resource efficiency (expensive database setups only run if basic tests pass)
- Reduced CI costs and execution time
- Early detection of major issues before running full test suite
- Sequential execution works consistently on all branches including PRs

### Contributing code
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Ensure all tests pass
5. Submit a pull request

For questions or support, please open an issue on GitHub.
