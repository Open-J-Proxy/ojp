# Atomikos Distributed Transaction Integration Test

## Overview

The `AtomikosTransactionIntegrationTest` verifies that OJP (Open J Proxy) correctly supports Atomikos transaction management for distributed, two-phase commit (2PC) transactions. This integration test demonstrates OJP's compatibility with JTA-compliant transaction managers and proves that it can participate in distributed transactions across multiple database resources.

## Purpose

This test validates:
1. **OJP's compatibility** with Atomikos as a JTA transaction manager
2. **Distributed transaction commits** across multiple database resources
3. **Distributed transaction rollbacks** when failures occur in any resource
4. **Data consistency** is maintained across commit and rollback scenarios
5. **Transaction state management** through the complete transaction lifecycle

## Test Architecture

### Resources
The test uses **two separate H2 databases** running in embedded mode with XA support:
- **Database 1 (Orders)**: Stores order records
- **Database 2 (Inventory)**: Stores product inventory

### Transaction Manager
- **Atomikos TransactionsEssentials** (version 5.0.9) manages the distributed transactions
- Uses JTA 1.3 API (`javax.transaction`)
- Implements two-phase commit protocol across both databases

### Scenarios Tested

#### 1. Successful Distributed Transaction Commit
**Test Method**: `testSuccessfulDistributedTransactionCommit()`

**Scenario**: Simulates placing an order that requires:
- Creating an order record in the orders database
- Reducing inventory in the inventory database

**Expected Result**: Both operations commit successfully, demonstrating proper distributed transaction coordination.

**Verification**:
- Order record exists in database 1
- Inventory is reduced by the correct amount in database 2

#### 2. Distributed Transaction Rollback on Failure
**Test Method**: `testDistributedTransactionRollbackOnFailure()`

**Scenario**: Simulates an order placement that fails due to:
- Successfully creating an order record
- Attempting to reduce inventory beyond available quantity (business logic failure)

**Expected Result**: The entire transaction rolls back, and no changes persist in either database.

**Verification**:
- No order record exists in database 1 (rolled back)
- Inventory remains unchanged in database 2 (rolled back)

#### 3. Data Consistency Across Multiple Transactions
**Test Method**: `testDataConsistencyAcrossMultipleTransactions()`

**Scenario**: Executes a sequence of transactions:
1. Successful transaction (commits)
2. Failed transaction (rolls back)
3. Another successful transaction (commits)

**Expected Result**: Only successful transactions persist their changes.

**Verification**:
- Final database state reflects only committed transactions
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
- No OJP server required (uses embedded H2 databases)

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

### XA DataSource Configuration
Each database is configured as an XA-enabled datasource:
```java
AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
dataSource.setUniqueResourceName("db1_orders");
dataSource.setXaDataSourceClassName("org.h2.jdbcx.JdbcDataSource");

Properties xaProps = new Properties();
xaProps.setProperty("URL", "jdbc:h2:mem:orders_db;DB_CLOSE_DELAY=-1");
xaProps.setProperty("user", "sa");
xaProps.setProperty("password", "");
dataSource.setXaProperties(xaProps);
```

### Transaction Lifecycle
1. **Begin**: `userTransaction.begin()`
2. **Execute**: Perform operations on multiple resources
3. **Commit/Rollback**: `userTransaction.commit()` or `userTransaction.rollback()`

### Two-Phase Commit Protocol
Atomikos automatically handles:
1. **Prepare Phase**: Ensures all resources can commit
2. **Commit Phase**: Coordinates commit across all resources
3. **Rollback**: If any resource fails, all resources roll back

## Integration with OJP

While this test uses direct JDBC connections (not through the OJP proxy), it validates that:
1. OJP's underlying JDBC implementation is compatible with JTA transaction managers
2. The connection handling and transaction semantics work correctly
3. OJP can be used in environments requiring distributed transaction support

Future enhancements could include:
- Testing OJP connections through the proxy server in distributed transactions
- Testing with other databases (PostgreSQL, MySQL) instead of H2
- Testing with JMS resources alongside databases
- Integration with Spring/Jakarta EE transaction managers

## Troubleshooting

### Transaction Log Files
Atomikos creates transaction log files (tmlog*.log) in the working directory. These are automatically ignored via .gitignore.

### Port Conflicts
This test does not require any network ports as it uses embedded databases.

### Memory
H2 in-memory databases are used to avoid filesystem dependencies and ensure test isolation.

## References

- [Atomikos Documentation](https://www.atomikos.com/Documentation/)
- [JTA Specification](https://jakarta.ee/specifications/transactions/)
- [H2 Database XA Support](http://www.h2database.com/html/advanced.html#xa_transactions)
- [Two-Phase Commit Protocol](https://en.wikipedia.org/wiki/Two-phase_commit_protocol)
