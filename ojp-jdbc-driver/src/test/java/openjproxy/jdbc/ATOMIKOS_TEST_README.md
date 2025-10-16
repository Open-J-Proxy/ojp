# Atomikos Distributed Transaction Integration Test

## Overview

The `AtomikosTransactionIntegrationTest` verifies that OJP (Open J Proxy) correctly supports Atomikos transaction management for distributed, two-phase commit (2PC) transactions. This integration test demonstrates OJP's compatibility with JTA-compliant transaction managers and proves that it can participate in distributed transactions across multiple database resources.

**Important: This test uses OJP's JDBC driver to connect to databases through the OJP server, validating that OJP properly handles distributed transactions in a real proxy scenario.**

## Purpose

This test validates:
1. **OJP's compatibility** with Atomikos as a JTA transaction manager
2. **Transaction coordination through OJP proxy** - All database operations go through OJP
3. **Distributed transaction commits** across multiple database resources via OJP
4. **Distributed transaction rollbacks** when failures occur in any resource via OJP
5. **Data consistency** is maintained across commit and rollback scenarios with OJP
6. **Transaction state management** through the complete transaction lifecycle via OJP

## Test Architecture

### Resources
The test uses **two separate H2 databases** running in embedded mode, accessed through OJP:
- **Database 1 (Orders)**: Stores order records - accessed via `jdbc:ojp[localhost:1059]_h2:mem:orders_db`
- **Database 2 (Inventory)**: Stores product inventory - accessed via `jdbc:ojp[localhost:1059]_h2:mem:inventory_db`

### Transaction Manager
- **Atomikos TransactionsEssentials** (version 5.0.9) manages the distributed transactions
- Uses JTA 1.3 API (`javax.transaction`)
- Configured with **AtomikosNonXADataSourceBean** to wrap OJP connections
- Uses "last resource commit optimization" for non-XA resources

### OJP Integration
- **OJP JDBC Driver** (`org.openjproxy.jdbc.Driver`) is used for all database connections
- All SQL operations and transaction commands flow through the **OJP server on localhost:1059**
- OJP handles connection pooling and forwards transaction operations to underlying databases
- Demonstrates real-world usage of OJP in distributed transaction scenarios

### Scenarios Tested

#### 1. Successful Distributed Transaction Commit through OJP
**Test Method**: `testSuccessfulDistributedTransactionCommit()`

**Scenario**: Simulates placing an order that requires:
- Creating an order record in the orders database (via OJP)
- Reducing inventory in the inventory database (via OJP)

**Expected Result**: Both operations commit successfully, demonstrating proper distributed transaction coordination through OJP proxy.

**Verification**:
- Order record exists in database 1 (verified via OJP connection)
- Inventory is reduced by the correct amount in database 2 (verified via OJP connection)

#### 2. Distributed Transaction Rollback on Failure through OJP
**Test Method**: `testDistributedTransactionRollbackOnFailure()`

**Scenario**: Simulates an order placement that fails due to:
- Successfully creating an order record (via OJP)
- Attempting to reduce inventory beyond available quantity (via OJP, business logic failure)

**Expected Result**: The entire transaction rolls back through OJP, and no changes persist in either database.

**Verification**:
- No order record exists in database 1 (verified via OJP connection, rolled back)
- Inventory remains unchanged in database 2 (verified via OJP connection, rolled back)

#### 3. Data Consistency Across Multiple Transactions through OJP
**Test Method**: `testDataConsistencyAcrossMultipleTransactions()`

**Scenario**: Executes a sequence of transactions through OJP:
1. Successful transaction (commits via OJP)
2. Failed transaction (rolls back via OJP)
3. Another successful transaction (commits via OJP)

**Expected Result**: Only successful transactions persist their changes through OJP.

**Verification**:
- Final database state reflects only committed transactions (verified via OJP)
- Failed transaction changes are not present
- Inventory counts match expected values after all transactions

#### 4. Transaction Manager Configuration
**Test Method**: `testAtomikosTransactionManagerConfiguration()`

**Scenario**: Validates Atomikos transaction manager state transitions:
- No active transaction initially
- Active state during transaction
- Proper state after commit/rollback

**Expected Result**: Transaction status correctly reflects each lifecycle stage.

## Running the Test

### Prerequisites
- Java 11 or higher
- Maven 3.6 or higher
- **OJP server running on localhost:1059** (Required!)

### Start OJP Server

Before running the test, start the OJP server:

```bash
# From the project root
mvn verify -pl ojp-server -Prun-ojp-server
```

The server must be running on `localhost:1059` for the test to succeed.

### Execute the Test

From the project root:
```bash
cd ojp-jdbc-driver
mvn test -Dtest=AtomikosTransactionIntegrationTest
```

Or as part of the full test suite:
```bash
mvn test
```

### Expected Output
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Note**: If the OJP server is not running, the tests will fail with connection errors.

## Dependencies

The test requires the following dependencies (automatically included in test scope):

```xml
<!-- Atomikos Transaction Manager -->
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jdbc</artifactId>
    <version>5.0.9</version>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jta</artifactId>
    <version>5.0.9</version>
    <scope>test</scope>
</dependency>

<!-- JTA API -->
<dependency>
    <groupId>javax.transaction</groupId>
    <artifactId>javax.transaction-api</artifactId>
    <version>1.3</version>
    <scope>test</scope>
</dependency>

<!-- H2 Database -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.3.232</version>
    <scope>test</scope>
</dependency>
```

## Technical Details

### OJP JDBC URL Configuration
Each database is accessed through OJP using its JDBC driver:
```java
// Orders database through OJP
dataSource1.setDriverClassName("org.openjproxy.jdbc.Driver");
dataSource1.setUrl("jdbc:ojp[localhost:1059]_h2:mem:orders_db;DB_CLOSE_DELAY=-1");

// Inventory database through OJP
dataSource2.setDriverClassName("org.openjproxy.jdbc.Driver");
dataSource2.setUrl("jdbc:ojp[localhost:1059]_h2:mem:inventory_db;DB_CLOSE_DELAY=-1");
```

### Atomikos Non-XA DataSource Configuration
Since OJP doesn't directly support XA, we use AtomikosNonXADataSourceBean:
```java
AtomikosNonXADataSourceBean dataSource = new AtomikosNonXADataSourceBean();
dataSource.setUniqueResourceName("ojp_db1_orders");
dataSource.setDriverClassName("org.openjproxy.jdbc.Driver");
dataSource.setUrl("jdbc:ojp[localhost:1059]_h2:mem:orders_db;DB_CLOSE_DELAY=-1");
dataSource.setUser("sa");
dataSource.setPassword("");
```

### Transaction Lifecycle through OJP
1. **Begin**: `userTransaction.begin()` - Atomikos coordinates transaction start
2. **Execute**: SQL operations through OJP connections - OJP forwards to underlying databases
3. **Commit/Rollback**: `userTransaction.commit()` or `userTransaction.rollback()` - Atomikos coordinates, OJP executes

### Transaction Coordination
- Atomikos manages the overall transaction lifecycle using JTA
- OJP JDBC driver forwards transaction commands (commit, rollback, setAutoCommit) to the OJP server
- OJP server manages actual database connections through HikariCP pools
- Each database operation flows: Client → Atomikos → OJP Driver → OJP Server → Database

## Integration with OJP

This test validates real-world OJP usage:
1. **OJP JDBC driver** is used for all database connections (not direct JDBC)
2. **All operations go through OJP server** running on localhost:1059
3. **Connection pooling** is managed by OJP server (using HikariCP)
4. **Transaction semantics** are properly maintained through the proxy layer
5. OJP correctly handles:
   - Transaction begin/commit/rollback commands
   - Distributed transaction coordination
   - Connection state management across transactions
   - Error propagation and rollback scenarios

This demonstrates that OJP can be used in enterprise environments requiring:
- JTA transaction management (Atomikos, Bitronix, etc.)
- Distributed transactions across multiple databases
- Transaction coordinator integration (Spring @Transactional, Jakarta EE, etc.)

## Troubleshooting

### Connection Refused Errors
If tests fail with "Connection refused" or "UNAVAILABLE: io exception":
- **Cause**: OJP server is not running
- **Solution**: Start the OJP server on localhost:1059 before running tests
  ```bash
  mvn verify -pl ojp-server -Prun-ojp-server
  ```

### Transaction Log Files
Atomikos creates transaction log files (tmlog*.log) in the working directory. These are automatically ignored via .gitignore.

### Port Conflicts
- This test requires OJP server on **port 1059**
- Ensure no other service is using this port
- Check OJP server logs if connection issues occur

### Database State
- H2 databases are in-memory and isolated per test
- Each test method runs with fresh database state
- No cleanup needed between test runs

## References

- [Atomikos Documentation](https://www.atomikos.com/Documentation/)
- [JTA Specification](https://jakarta.ee/specifications/transactions/)
- [H2 Database XA Support](http://www.h2database.com/html/advanced.html#xa_transactions)
- [Two-Phase Commit Protocol](https://en.wikipedia.org/wiki/Two-phase_commit_protocol)
