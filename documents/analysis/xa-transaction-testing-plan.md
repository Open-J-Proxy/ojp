# XA Transaction Testing Plan for OJP

## Executive Summary

This document outlines a comprehensive plan to test XA (eXtended Architecture) transaction capabilities across multiple database systems (Oracle, SQL Server, DB2) and message queues. The testing strategy adopts a **baseline-first approach** where tests initially use native JDBC drivers to establish known-good behavior before migrating to OJP.

## Table of Contents

1. [Background and Objectives](#background-and-objectives)
2. [Testing Approach and Strategy](#testing-approach-and-strategy)
3. [Test Scenarios](#test-scenarios)
4. [Database-Specific Considerations](#database-specific-considerations)
5. [Queue Integration Testing](#queue-integration-testing)
6. [Implementation Plan](#implementation-plan)
7. [Questions and Concerns](#questions-and-concerns)

## Background and Objectives

### What is XA?

XA (eXtended Architecture) is a standard for distributed transaction processing that enables atomic operations across multiple heterogeneous resource managers (databases, message queues, etc.). The XA protocol implements the two-phase commit (2PC) protocol:

- **Phase 1 (Prepare)**: All participants prepare to commit and vote on whether they can commit
- **Phase 2 (Commit/Rollback)**: Based on votes, coordinator instructs all participants to commit or rollback

### Why Test XA in OJP?

OJP acts as a JDBC Type 3 driver proxy. It's critical to verify that XA transaction semantics are preserved through the proxy layer, ensuring:
- ACID properties are maintained
- Two-phase commit protocol works correctly
- Recovery and failure scenarios are handled properly
- Performance characteristics are acceptable

### Testing Objectives

1. **Baseline Establishment**: Create tests using native JDBC drivers to understand expected behavior
2. **OJP Validation**: Migrate tests to OJP and verify identical behavior
3. **Coverage**: Test all XA operations including edge cases and failure scenarios
4. **Multi-Resource**: Test distributed transactions across multiple databases and queues
5. **Recovery**: Validate crash recovery and prepared transaction handling

## Testing Approach and Strategy

### Phase 1: Native Driver Baseline Testing (Weeks 1-2)

Create comprehensive test suites that use native JDBC drivers directly, bypassing OJP entirely. This establishes the "ground truth" for expected XA behavior.

**Key Benefits:**
- Validates test infrastructure (TestContainers, JMS setup, etc.)
- Documents expected behavior for each database
- Identifies database-specific quirks and limitations
- Creates regression baseline for OJP comparison

**Test Structure:**
```
native-xa-tests/
├── oracle/
│   ├── OracleNativeXABasicTest.java
│   ├── OracleNativeXARecoveryTest.java
│   └── OracleNativeXAFailureTest.java
├── sqlserver/
│   ├── SqlServerNativeXABasicTest.java
│   ├── SqlServerNativeXARecoveryTest.java
│   └── SqlServerNativeXAFailureTest.java
├── db2/
│   ├── Db2NativeXABasicTest.java
│   ├── Db2NativeXARecoveryTest.java
│   └── Db2NativeXAFailureTest.java
└── common/
    ├── XATestBase.java
    ├── XidGenerator.java
    └── XATransactionUtils.java
```

### Phase 2: OJP XA Testing (Weeks 3-4)

Migrate baseline tests to use OJP, comparing results against Phase 1 baseline.

**Approach:**
- Run identical test scenarios through OJP proxy
- Compare behavior, timing, and error handling
- Document any differences or issues
- Verify connection pooling doesn't interfere with XA semantics

### Phase 3: Advanced Scenarios (Weeks 5-6)

Test complex multi-resource scenarios and edge cases:
- Distributed transactions across multiple databases
- Database + Queue transactions
- Concurrent XA transactions
- High-volume XA operations
- Network failure simulations
- OJP server restart during transactions

## Test Scenarios

### 1. Basic XA Operations

#### 1.1 XADataSource Creation and Connection
**Objective**: Verify basic XA infrastructure setup

```java
@Test
public void testXADataSourceCreation() {
    // Create XADataSource
    // Get XAConnection
    // Get XAResource
    // Verify connection properties
    // Verify auto-commit is false
}
```

**Expected Behavior:**
- XADataSource creates successfully
- XAConnection and XAResource are non-null
- Auto-commit is disabled on XA connections
- Connection is usable for SQL operations

#### 1.2 Simple XA Transaction (Two-Phase Commit)
**Objective**: Verify basic 2PC flow

**Steps:**
1. Start XA transaction: `xaResource.start(xid, TMNOFLAGS)`
2. Execute SQL operations (INSERT/UPDATE)
3. End XA transaction: `xaResource.end(xid, TMSUCCESS)`
4. Prepare: `int result = xaResource.prepare(xid)`
5. Commit: `xaResource.commit(xid, false)` if result is XA_OK
6. Verify data persistence in new transaction

**Success Criteria:**
- All XA operations complete without exception
- Data is committed and visible in subsequent transactions
- prepare() returns XA_OK or XA_RDONLY

#### 1.3 XA Transaction with Rollback
**Objective**: Verify rollback functionality

**Steps:**
1. Start XA transaction
2. Execute SQL operations (INSERT/UPDATE)
3. End XA transaction: `xaResource.end(xid, TMSUCCESS)`
4. Rollback: `xaResource.rollback(xid)`
5. Verify data is NOT persisted

**Success Criteria:**
- Rollback completes without exception
- Data is not visible in subsequent transactions
- No side effects remain

#### 1.4 One-Phase Commit Optimization
**Objective**: Verify single-resource optimization

**Steps:**
1. Start XA transaction
2. Execute SQL operations
3. End XA transaction
4. One-phase commit: `xaResource.commit(xid, true)` (skip prepare)
5. Verify data persistence

**Success Criteria:**
- One-phase commit succeeds
- Data is committed correctly
- Performance is better than two-phase commit

### 2. Transaction Isolation and Concurrency

#### 2.1 Multiple Concurrent XA Transactions
**Objective**: Verify XA handles concurrent transactions

**Approach:**
- Start multiple XA transactions simultaneously on different connections
- Each transaction operates on different data
- Prepare and commit all transactions
- Verify all data committed correctly with proper isolation

#### 2.2 XA Transaction Isolation Levels
**Objective**: Verify isolation levels work with XA

**Test Cases:**
- READ_UNCOMMITTED
- READ_COMMITTED
- REPEATABLE_READ
- SERIALIZABLE

**Verify:**
- Isolation semantics are preserved
- Dirty reads, phantom reads behave as expected

### 3. Failure and Error Scenarios

#### 3.1 Transaction Timeout
**Objective**: Verify timeout handling

**Steps:**
1. Set transaction timeout: `xaResource.setTransactionTimeout(5)`
2. Start XA transaction
3. Wait longer than timeout
4. Attempt to commit
5. Verify timeout exception

**Success Criteria:**
- Timeout is enforced
- Appropriate exception is thrown
- Transaction is rolled back automatically

#### 3.2 Failed Prepare Phase
**Objective**: Verify handling when prepare fails

**Steps:**
1. Start XA transaction
2. Execute operations that will cause prepare to fail (e.g., constraint violation)
3. End transaction
4. Attempt prepare - should fail
5. Rollback transaction
6. Verify consistent state

**Success Criteria:**
- Prepare failure is detected
- Transaction can be rolled back cleanly
- Database remains consistent

#### 3.3 Network Failure During Transaction
**Objective**: Verify resilience to network issues

**Approach:**
- Start XA transaction
- Simulate network interruption (for OJP: stop server temporarily)
- Attempt to complete transaction
- Verify appropriate error handling and recovery

#### 3.4 Heuristic Outcomes
**Objective**: Handle heuristic decisions

**Test heuristic scenarios:**
- Heuristic commit (some resources committed, others didn't)
- Heuristic rollback (some resources rolled back, others didn't)
- Heuristic mixed (inconsistent outcomes)

**Verify:**
- Heuristic exceptions are thrown
- Application can detect and handle inconsistencies

### 4. Recovery and Prepared Transaction Management

#### 4.1 XA Recovery - List Prepared Transactions
**Objective**: Verify ability to query in-doubt transactions

**Steps:**
1. Start multiple XA transactions
2. Prepare all transactions (but don't commit)
3. Call `xaResource.recover(TMSTARTRSCAN | TMENDRSCAN)`
4. Verify all prepared Xids are returned
5. Commit or rollback each recovered Xid

**Success Criteria:**
- recover() returns all prepared transactions
- Each returned Xid can be committed or rolled back
- After resolution, recover() returns empty list

#### 4.2 Crash Recovery - Commit Prepared Transaction
**Objective**: Verify recovery after crash during commit

**Scenario:**
1. Transaction Manager prepares transaction
2. TM crashes before sending commit
3. New TM recovers and completes commit

**Implementation:**
1. Prepare XA transaction
2. Simulate crash (disconnect, don't commit)
3. Recover prepared transactions using recover()
4. Commit recovered transaction
5. Verify data is committed

**Success Criteria:**
- Prepared transaction survives "crash"
- Transaction can be committed after recovery
- Data integrity is maintained

#### 4.3 Crash Recovery - Rollback Prepared Transaction
**Objective**: Verify recovery rollback path

**Similar to 4.2 but:**
- After recovery, rollback instead of commit
- Verify data is NOT persisted
- Verify clean state

#### 4.4 Forget Operation
**Objective**: Verify forget() for heuristic transactions

**Steps:**
1. Create a heuristic outcome scenario
2. Call `xaResource.forget(xid)` to clear heuristic
3. Verify transaction is removed from recovery list
4. Verify subsequent operations succeed

**Success Criteria:**
- forget() completes without error
- Xid is removed from prepared transaction list
- Database consistency is maintained

### 5. Multi-Resource Distributed Transactions

#### 5.1 Two-Database Transaction
**Objective**: Verify XA across two databases

**Steps:**
1. Create XA connections to two different databases (e.g., Oracle + SQL Server)
2. Start XA transaction with same global transaction ID, different branch qualifiers
3. Execute operations on both databases
4. Prepare both resources
5. Commit both resources
6. Verify data in both databases

**Success Criteria:**
- Both databases participate in same transaction
- Either both commit or both rollback (atomicity)
- Data consistency across databases

#### 5.2 Database + Queue Transaction
**Objective**: Verify XA with database and JMS queue

**Steps:**
1. Create XA connection to database
2. Create XA connection to JMS queue
3. Start distributed transaction
4. Insert data to database
5. Send message to queue
6. Commit transaction
7. Verify both database record and queue message exist

**Failure Test:**
- Rollback transaction
- Verify neither database record nor queue message exist

**Success Criteria:**
- Atomic behavior: both succeed or both fail
- No orphaned records or messages

#### 5.3 Three-Resource Transaction
**Objective**: Verify XA with multiple resources

**Resources:**
- Oracle database
- SQL Server database  
- DB2 database

**Steps:**
1. Create XA connections to all three
2. Execute distributed transaction
3. Verify atomic commit/rollback

### 6. Database-Specific XA Features

#### 6.1 Oracle-Specific Tests
- Test Oracle XA with RAC (if available)
- Test tight vs loose coupling
- Test Oracle-specific XA extensions

#### 6.2 SQL Server-Specific Tests
- Verify XA stored procedures are installed
- Test DTC integration (if applicable)
- Test SQL Server XA permissions

#### 6.3 DB2-Specific Tests
- Test DB2 XA configuration
- Test DB2 transaction logging
- Test DB2-specific XA features

## Database-Specific Considerations

### Oracle XA Setup

**Requirements:**
- Oracle XA library (`$ORACLE_HOME/javavm/lib/aurora_xa.jar`)
- XA permissions: `GRANT SELECT ON pending_trans$ TO <user>`
- XA permissions: `GRANT SELECT ON dba_2pc_pending TO <user>`
- XA permissions: `GRANT SELECT ON dba_pending_transactions TO <user>`
- XA permissions: `GRANT EXECUTE ON DBMS_XA TO <user>`

**TestContainer Setup:**
```java
OracleContainer oracle = new OracleContainer("gvenzl/oracle-xe:21-slim")
    .withDatabaseName("testdb")
    .withUsername("testuser")
    .withPassword("testpass")
    .withInitScript("oracle-xa-setup.sql");
```

**Init Script (oracle-xa-setup.sql):**
```sql
-- Grant XA permissions
GRANT SELECT ON pending_trans$ TO testuser;
GRANT SELECT ON dba_2pc_pending TO testuser;
GRANT SELECT ON dba_pending_transactions TO testuser;
GRANT EXECUTE ON DBMS_XA TO testuser;
GRANT FORCE ANY TRANSACTION TO testuser;
```

**Known Issues:**
- Oracle XE may have limitations on XA functionality
- Some Oracle versions require specific JDBC driver versions
- Oracle XA requires FORCE ANY TRANSACTION privilege for recovery

### SQL Server XA Setup

**Requirements:**
- XA stored procedures must be installed: `sp_sqljdbc_xa_install`
- User must be member of SqlJDBCXAUser role
- DTC (Distributed Transaction Coordinator) must be enabled (for native testing)

**TestContainer Setup:**
```java
MSSQLServerContainer sqlServer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
    .acceptLicense()
    .withInitScript("sqlserver-xa-setup.sql");
```

**Init Script (sqlserver-xa-setup.sql):**
```sql
-- Install XA stored procedures (requires SA privileges)
EXEC sp_sqljdbc_xa_install;

-- Create XA user role if not exists
IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser')
    CREATE ROLE SqlJDBCXAUser;

-- Grant XA permissions
GRANT EXECUTE ON xp_sqljdbc_xa_init TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_start TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_end TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_commit TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_recover TO SqlJDBCXAUser;
GRANT EXECUTE ON xp_sqljdbc_xa_forget TO SqlJDBCXAUser;

-- Add test user to XA role
ALTER ROLE SqlJDBCXAUser ADD MEMBER testuser;
```

**Known Issues:**
- SQL Server requires SA privileges to install XA procedures
- Container must run as privileged to enable XA
- Some versions have DTC compatibility issues

### DB2 XA Setup

**Requirements:**
- DB2 XA library (`db2java.zip` or `db2jcc4.jar`)
- Database must be configured for XA: `UPDATE DBM CFG USING TM_DATABASE <dbname>`
- User needs DBADM or SQLADM authority for XA operations

**TestContainer Setup:**
```java
Db2Container db2 = new Db2Container("icr.io/db2_community/db2:latest")
    .acceptLicense()
    .withDatabaseName("testdb")
    .withUsername("db2inst1")
    .withPassword("testpass")
    .withEnv("ARCHIVE_LOGS", "false")
    .withEnv("AUTOCONFIG", "false")
    .withInitScript("db2-xa-setup.sql");
```

**Init Script (db2-xa-setup.sql):**
```sql
-- Enable XA transactions
UPDATE DATABASE CONFIGURATION FOR testdb USING TM_DATABASE testdb;

-- Grant XA permissions
GRANT DBADM ON DATABASE TO USER testuser;
```

**Known Issues:**
- DB2 container startup is slower than other databases
- DB2 requires specific configuration for XA
- License acceptance required for DB2 container
- DB2 XA implementation has historically had performance concerns

## Queue Integration Testing

### JMS Queue Setup

**TestContainer for ActiveMQ:**
```java
ActiveMQContainer activeMQ = new ActiveMQContainer("apache/activemq-classic:latest")
    .withExposedPorts(61616, 8161);
```

**Or Apache Artemis:**
```java
ArtemisContainer artemis = new ArtemisContainer("apache/activemq-artemis:latest");
```

### XA Queue Test Scenarios

#### Test 1: Database Write + Queue Send (Commit)
1. Start XA transaction
2. Insert record to database
3. Send message to queue
4. Commit both resources
5. Verify both operations succeeded

#### Test 2: Database Write + Queue Send (Rollback)
1. Start XA transaction
2. Insert record to database
3. Send message to queue
4. Rollback transaction
5. Verify neither operation persisted

#### Test 3: Queue Receive + Database Write
1. Pre-populate queue with message
2. Start XA transaction
3. Receive message from queue
4. Write to database based on message content
5. Commit transaction
6. Verify message consumed and database updated

#### Test 4: Queue Failure Scenarios
1. Test queue unavailable during prepare
2. Test queue timeout during commit
3. Test recovery after queue failure

## Implementation Plan

### Week 1: Infrastructure Setup

**Deliverables:**
- [ ] Create `native-xa-tests` module in project
- [ ] Set up TestContainers for Oracle, SQL Server, DB2
- [ ] Set up TestContainers for ActiveMQ/Artemis
- [ ] Create base test classes and utilities
- [ ] Create XidGenerator utility
- [ ] Document setup procedures

**Test Infrastructure Components:**
```
native-xa-tests/
├── pom.xml (with all required dependencies)
├── src/
│   ├── main/java/
│   │   └── org/openjproxy/xa/test/
│   │       ├── util/
│   │       │   ├── XidGenerator.java
│   │       │   ├── XATransactionUtils.java
│   │       │   └── TestContainerManager.java
│   │       └── base/
│   │           ├── NativeXATestBase.java
│   │           └── XARecoveryTestBase.java
│   └── test/java/
│       └── org/openjproxy/xa/test/
│           ├── native/
│           │   ├── oracle/
│           │   ├── sqlserver/
│           │   └── db2/
│           ├── multiresource/
│           └── queue/
└── src/test/resources/
    ├── oracle-xa-setup.sql
    ├── sqlserver-xa-setup.sql
    └── db2-xa-setup.sql
```

### Week 2: Native Driver Basic Tests

**Deliverables:**
- [ ] Implement basic XA tests for Oracle (native)
- [ ] Implement basic XA tests for SQL Server (native)
- [ ] Implement basic XA tests for DB2 (native)
- [ ] Document baseline behavior for each database
- [ ] Create test report comparing databases

**Tests to Implement:**
- XADataSource creation
- Simple two-phase commit
- Rollback scenarios
- One-phase commit optimization
- Transaction timeout

### Week 3: Native Driver Recovery Tests

**Deliverables:**
- [ ] Implement XA recovery tests for all databases
- [ ] Test prepared transaction listing (recover)
- [ ] Test commit of recovered transactions
- [ ] Test rollback of recovered transactions
- [ ] Test forget operation
- [ ] Document recovery behavior differences

**Tests to Implement:**
- Basic recovery (recover() API)
- Commit after recovery
- Rollback after recovery
- Forget heuristic transactions
- Multiple prepared transactions recovery

### Week 4: Native Driver Advanced Tests

**Deliverables:**
- [ ] Implement failure scenario tests
- [ ] Implement concurrency tests
- [ ] Implement multi-resource tests (2 databases)
- [ ] Document performance characteristics
- [ ] Create comprehensive test report

**Tests to Implement:**
- Failed prepare scenarios
- Heuristic outcomes
- Concurrent XA transactions
- Two-database transactions
- Isolation level verification

### Week 5: OJP XA Testing

**Deliverables:**
- [ ] Port all native tests to use OJP
- [ ] Compare OJP behavior vs native baseline
- [ ] Document any differences or issues
- [ ] Fix OJP issues discovered
- [ ] Verify connection pooling compatibility

**Approach:**
- Create parallel test suite using OJP URLs
- Run identical scenarios
- Compare results, timing, error handling
- Investigate and fix any discrepancies

### Week 6: Queue Integration and Final Testing

**Deliverables:**
- [ ] Implement database + queue XA tests (native)
- [ ] Implement database + queue XA tests (OJP)
- [ ] Implement three-resource transaction tests
- [ ] Perform comprehensive regression testing
- [ ] Create final test report and recommendations

**Tests to Implement:**
- Database + JMS queue transactions
- Multiple queues + database
- Three-way distributed transactions
- Queue failure scenarios
- End-to-end recovery scenarios

### Week 7: Documentation and Knowledge Transfer

**Deliverables:**
- [ ] Final test report with findings
- [ ] XA best practices guide for OJP users
- [ ] Known limitations and workarounds
- [ ] Performance tuning recommendations
- [ ] CI/CD integration documentation

## Questions and Concerns

### Technical Questions

#### 1. XA Transaction Manager Selection
**Question**: Which Transaction Manager should we use for testing?

**Options:**
- **Atomikos** (already in dependencies)
  - Pros: Lightweight, easy to use, supports multiple resources
  - Cons: Commercial license for production use
- **Narayana** (JBoss Transaction Manager)
  - Pros: Full JTA implementation, open source
  - Cons: More complex setup
- **Bitronix**
  - Pros: Simple, open source
  - Cons: Project appears less active

**Recommendation**: Start with Atomikos since it's already in the project dependencies. Consider Narayana for a fully open-source option.

#### 2. DB2 Container Availability and Licensing
**Question**: IBM DB2 containers have licensing requirements. How should we handle this?

**Concerns:**
- DB2 Community Edition has restrictions
- Container image requires license acceptance
- May not be suitable for public CI/CD

**Options:**
1. Use DB2 Developer Edition (free but licensed)
2. Document DB2 setup but make tests optional
3. Use DB2 Community Edition with proper license acceptance
4. Consider IBM Cloud free tier for testing

**Recommendation**: Make DB2 tests optional (system property flag), document license requirements clearly, use DB2 Developer Edition container with explicit license acceptance.

#### 3. Queue Selection
**Question**: Which JMS provider should we use for testing?

**Options:**
- **ActiveMQ Classic**: Well-established, good XA support
- **ActiveMQ Artemis**: Newer, better performance
- **RabbitMQ**: Popular but JMS support via plugin
- **IBM MQ**: Enterprise-grade but complex setup

**Recommendation**: Use ActiveMQ Artemis as primary queue. It has excellent XA support, good TestContainer support, and represents modern JMS implementations.

#### 4. TestContainer Resource Management
**Question**: Should we use singleton containers or per-test containers?

**Trade-offs:**
- **Singleton** (Shared across all tests):
  - Pros: Faster test execution, less resource usage
  - Cons: Tests may interfere with each other, cleanup complexity
- **Per-Test** (New container for each test):
  - Pros: Complete isolation, no interference
  - Cons: Slower execution, higher resource usage

**Recommendation**: Use singleton pattern with careful database cleanup between tests. Existing SQL Server implementation already uses this pattern successfully.

### Architectural Concerns

#### 5. OJP Connection Pooling and XA
**Question**: How does OJP's connection pooling interact with XA transactions?

**Concerns:**
- XA connections have special lifecycle requirements
- Pooled connections must maintain XA state
- Connection reuse must not interfere with transaction boundaries
- Prepared transactions must survive connection pool recycling

**Testing Requirements:**
- Verify XA connection pooling works correctly
- Test connection reuse after XA transactions
- Test prepared transaction with connection pool churn
- Verify no XA state leakage between pooled connections

#### 6. OJP Server Restart During XA Transaction
**Question**: What happens if OJP server restarts during a prepared transaction?

**Concerns:**
- Client connection is lost
- Transaction is in prepared state (in-doubt)
- How does recovery work?

**Testing Requirements:**
- Prepare transaction through OJP
- Restart OJP server
- Reconnect and attempt recovery
- Verify transaction can be committed or rolled back

**Expected Challenge**: This is a critical scenario that may expose architectural limitations.

#### 7. Distributed XA Across Multiple OJP Instances
**Question**: Can a distributed XA transaction span multiple OJP server instances?

**Scenario**: Transaction coordinator wants to include resources from multiple OJP servers (each connected to different databases).

**Concerns:**
- Each OJP instance manages its own connection pool
- Global transaction ID must be coordinated across OJP instances
- Recovery becomes more complex

**Testing Requirements:**
- Test XA across 2+ OJP instances
- Test recovery in multi-OJP scenario
- Document behavior and limitations

### Operational Concerns

#### 8. CI/CD Resource Requirements
**Question**: Can CI/CD environments handle multiple database containers?

**Concerns:**
- Running Oracle + SQL Server + DB2 + Queue simultaneously
- Memory and CPU requirements may be substantial
- Build time may increase significantly

**Recommendations:**
- Make advanced tests optional for PR builds
- Run full test suite on scheduled/nightly builds
- Consider parallel test execution strategies
- Monitor resource usage and optimize

#### 9. Test Data Management
**Question**: How to manage test data and cleanup?

**Concerns:**
- Each test creates tables and data
- Prepared transactions may leave in-doubt transactions
- Cleanup failures can cause subsequent test failures

**Recommendations:**
- Use unique table names (timestamp-based)
- Implement robust cleanup in @AfterEach
- Add test to verify clean state before starting
- Document manual cleanup procedures

#### 10. Performance Baseline
**Question**: What performance characteristics should we expect?

**Metrics to Measure:**
- XA transaction overhead vs regular transaction
- Two-phase commit latency
- Recovery operation time
- Connection establishment time
- OJP overhead vs native driver

**Recommendation**: Create performance test suite in addition to functional tests. Document baseline performance for each database.

### Testing Strategy Concerns

#### 11. Test Execution Time
**Question**: How long will the full test suite take to run?

**Estimates:**
- Container startup: 2-5 minutes per database
- Basic tests: ~30 minutes
- Recovery tests: ~20 minutes (require delays)
- Multi-resource tests: ~30 minutes
- **Total: ~1.5-2 hours for full suite**

**Mitigation:**
- Parallel test execution where possible
- Shared containers to reduce startup time
- Separate test profiles (quick vs comprehensive)

#### 12. Test Reliability
**Question**: How to ensure tests are reliable and not flaky?

**Concerns:**
- Timing-dependent tests (especially recovery)
- Container startup race conditions
- Network timing issues
- Database-specific quirks

**Recommendations:**
- Use proper wait strategies for containers
- Add retry logic for timing-sensitive operations
- Implement health checks before tests
- Document known flaky tests and mitigation strategies

#### 13. Native vs OJP Test Parity
**Question**: How to ensure native and OJP tests are truly equivalent?

**Approach:**
- Share test logic via abstract base classes
- Use same test data and scenarios
- Parameterize connection creation (native vs OJP)
- Compare results systematically

**Example Structure:**
```java
// Base test with all test logic
abstract class XABasicTestBase {
    protected abstract XADataSource createXADataSource();
    
    @Test
    void testSimpleCommit() { /* test logic */ }
}

// Native implementation
class OracleNativeXABasicTest extends XABasicTestBase {
    protected XADataSource createXADataSource() {
        return new oracle.jdbc.xa.OracleXADataSource();
    }
}

// OJP implementation
class OracleOjpXABasicTest extends XABasicTestBase {
    protected XADataSource createXADataSource() {
        return new OjpXADataSource(); // using OJP
    }
}
```

### Documentation Concerns

#### 14. User-Facing Documentation
**Question**: What documentation should we provide to OJP users about XA?

**Required Documentation:**
- XA setup guide for each database
- Known limitations and workarounds
- Performance considerations
- Recovery procedures
- Example code and best practices
- Troubleshooting guide

**Recommendation**: Create comprehensive XA user guide after testing is complete, based on findings and best practices discovered during testing.

#### 15. Test Maintenance
**Question**: How to maintain tests as databases and OJP evolve?

**Concerns:**
- Database version updates may change XA behavior
- OJP changes may affect XA functionality
- TestContainer image updates
- JDBC driver updates

**Recommendations:**
- Document expected behavior explicitly
- Version-pin container images initially
- Regular test review and updates
- Automated CI/CD to catch regressions early

## Success Criteria

### Functional Success Criteria

1. ✅ All basic XA operations work correctly (start, end, prepare, commit, rollback)
2. ✅ Recovery operations function properly (recover, commit, rollback, forget)
3. ✅ Multi-resource transactions maintain atomicity
4. ✅ Native and OJP behavior is equivalent (or differences documented)
5. ✅ All three databases (Oracle, SQL Server, DB2) pass test suite
6. ✅ Queue integration works correctly
7. ✅ Failure scenarios are handled gracefully

### Quality Success Criteria

1. ✅ Test coverage: Minimum 90% of XA code paths
2. ✅ No flaky tests: All tests pass consistently (>99% pass rate)
3. ✅ Performance: OJP XA overhead < 20% vs native
4. ✅ Documentation: Complete setup and usage guides
5. ✅ CI/CD: Automated test execution on PR and nightly builds

### Deliverable Success Criteria

1. ✅ Comprehensive test suite implemented and passing
2. ✅ Native baseline tests documented
3. ✅ OJP compatibility validated
4. ✅ Known limitations documented
5. ✅ Performance benchmarks established
6. ✅ User guide published

## Risks and Mitigation

### Risk 1: Database Container Limitations
**Risk**: TestContainer databases may not fully support XA features
**Impact**: High - Core functionality may not be testable
**Mitigation**: 
- Test with real database instances in addition to containers
- Document container limitations
- Provide alternative test approaches

### Risk 2: OJP Architectural Limitations
**Risk**: OJP design may not fully support XA semantics
**Impact**: Critical - May require architectural changes
**Mitigation**:
- Identify issues early in testing
- Document limitations clearly
- Propose architectural improvements if needed

### Risk 3: Test Execution Time
**Risk**: Full test suite takes too long for CI/CD
**Impact**: Medium - Slows development velocity
**Mitigation**:
- Implement test parallelization
- Create quick vs comprehensive test profiles
- Optimize container reuse

### Risk 4: Multi-Resource Complexity
**Risk**: Distributed transaction testing is complex and may be unreliable
**Impact**: Medium - May not achieve full coverage
**Mitigation**:
- Start with simple two-resource scenarios
- Build complexity gradually
- Accept some limitations in test coverage

### Risk 5: Database-Specific Issues
**Risk**: Each database has unique XA quirks that complicate testing
**Impact**: Medium - Increases development time
**Mitigation**:
- Research database-specific XA documentation thoroughly
- Leverage community knowledge
- Document workarounds clearly

## Appendix

### Appendix A: XA Xid Format

```java
/**
 * Test Xid implementation for XA testing
 */
public class TestXid implements Xid {
    private final int formatId;
    private final byte[] globalTransactionId;
    private final byte[] branchQualifier;
    
    public TestXid(int formatId, byte[] globalTxId, byte[] branchQual) {
        this.formatId = formatId;
        this.globalTransactionId = globalTxId;
        this.branchQualifier = branchQual;
        
        // Validate constraints
        if (globalTxId.length > 64) 
            throw new IllegalArgumentException("Global TX ID > 64 bytes");
        if (branchQual.length > 64) 
            throw new IllegalArgumentException("Branch Qualifier > 64 bytes");
    }
    
    @Override
    public int getFormatId() { return formatId; }
    
    @Override
    public byte[] getGlobalTransactionId() { return globalTransactionId; }
    
    @Override
    public byte[] getBranchQualifier() { return branchQualifier; }
}
```

### Appendix B: Useful XA Resources

**Specifications:**
- [X/Open XA Specification](https://pubs.opengroup.org/onlinepubs/009680699/toc.pdf)
- [JTA Specification (JSR 907)](https://jcp.org/en/jsr/detail?id=907)

**Database XA Documentation:**
- [Oracle XA](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/distributed-transactions.html)
- [SQL Server XA](https://learn.microsoft.com/en-us/sql/connect/jdbc/understanding-xa-transactions)
- [DB2 XA](https://www.ibm.com/docs/en/db2/11.5?topic=transactions-xa-distributed-transaction-management)

**TestContainers:**
- [TestContainers Documentation](https://www.testcontainers.org/)
- [Oracle Container](https://www.testcontainers.org/modules/databases/oraclexe/)
- [SQL Server Container](https://www.testcontainers.org/modules/databases/mssqlserver/)
- [DB2 Container](https://www.testcontainers.org/modules/databases/db2/)

**Transaction Managers:**
- [Atomikos](https://www.atomikos.com/Documentation/)
- [Narayana](https://narayana.io/)

### Appendix C: Example Test Execution Commands

```bash
# Run all native XA tests
mvn test -pl native-xa-tests

# Run Oracle native tests only
mvn test -pl native-xa-tests -Dtest="Oracle*NativeXA*"

# Run SQL Server native tests only
mvn test -pl native-xa-tests -Dtest="SqlServer*NativeXA*" -DenableSqlServerTests=true

# Run DB2 native tests only
mvn test -pl native-xa-tests -Dtest="Db2*NativeXA*" -DenableDb2Tests=true

# Run all OJP XA tests
mvn test -pl native-xa-tests -Dtest="*OjpXA*"

# Run quick test suite (basic tests only)
mvn test -pl native-xa-tests -Dtest="*Basic*"

# Run full test suite (all tests)
mvn test -pl native-xa-tests -DenableAllXATests=true
```

### Appendix D: Sample CI/CD Workflow

```yaml
name: XA Transaction Tests

on: [push, pull_request]

jobs:
  quick-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Run basic XA tests
        run: mvn test -pl native-xa-tests -Dtest="*Basic*"

  comprehensive-tests:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Run all XA tests
        run: mvn test -pl native-xa-tests -DenableAllXATests=true
```

## Conclusion

This comprehensive testing plan provides a structured approach to validating XA transaction capabilities in OJP. By starting with native driver baseline tests and progressively adding complexity, we ensure robust coverage while maintaining clear understanding of expected behavior.

The plan addresses critical XA operations including prepare, commit, rollback, recovery, and forget across multiple databases and message queues using TestContainers for consistency and reproducibility.

Key success factors:
1. **Baseline-first approach** ensures we understand correct behavior before testing OJP
2. **Comprehensive scenario coverage** including edge cases and failure modes
3. **Multi-resource testing** validates true distributed transaction capabilities
4. **Clear documentation** of findings, limitations, and best practices

The identified questions and concerns highlight areas requiring decisions and careful attention during implementation. Addressing these systematically will lead to a robust, maintainable test suite that provides confidence in OJP's XA transaction handling.

**Next Steps:**
1. Review and approve this plan with stakeholders
2. Set up development environment and TestContainers
3. Begin Phase 1: Native driver baseline testing
4. Iterate based on findings and feedback

## Detailed Test Implementation Structure

Based on comprehensive XA testing analysis, the following detailed structure should be implemented:

### Project Structure

```
ojp-jdbc-driver/src/test/java/
└── org/openjproxy/xa/baseline/
    ├── common/
    │   ├── XATestBase.java              # Base class for all XA tests
    │   ├── XidGenerator.java            # Utility for creating XIDs
    │   ├── TransactionCoordinator.java  # Manual 2PC coordinator
    │   └── TestContainerManager.java    # Container lifecycle management
    │
    ├── containers/
    │   ├── OracleXAContainer.java       # Oracle TestContainer wrapper
    │   ├── SQLServerXAContainer.java    # SQL Server TestContainer wrapper
    │   ├── DB2XAContainer.java          # DB2 TestContainer wrapper
    │   ├── ArtemisXAContainer.java      # ActiveMQ Artemis TestContainer wrapper
    │   └── IBMMQXAContainer.java        # IBM MQ TestContainer wrapper (optional)
    │
    ├── single/
    │   ├── OracleXABasicTest.java       # Oracle basic XA tests
    │   ├── SQLServerXABasicTest.java    # SQL Server basic XA tests
    │   ├── DB2XABasicTest.java          # DB2 basic XA tests
    │   ├── OracleXARecoveryTest.java    # Oracle recovery tests
    │   ├── SQLServerXARecoveryTest.java # SQL Server recovery tests
    │   ├── DB2XARecoveryTest.java       # DB2 recovery tests
    │   ├── OracleXAEdgeCasesTest.java   # Oracle edge cases
    │   ├── SQLServerXAEdgeCasesTest.java # SQL Server edge cases
    │   └── DB2XAEdgeCasesTest.java      # DB2 edge cases
    │
    ├── distributed/
    │   ├── TwoPhaseCommitTest.java      # 2PC across databases
    │   ├── MixedDatabaseXATest.java     # Different database vendors
    │   ├── DistributedRollbackTest.java # Rollback scenarios
    │   └── PartialFailureTest.java      # Failure during prepare
    │
    ├── queue/
    │   ├── DatabaseQueueXATest.java     # DB + Queue transactions
    │   ├── QueueProducerConsumerTest.java # Producer/Consumer pattern
    │   └── QueueFailureTest.java        # Queue failure scenarios
    │
    ├── atomikos/
    │   ├── AtomikosIntegrationTest.java # Atomikos coordination
    │   ├── AtomikosRecoveryTest.java    # Atomikos recovery
    │   └── AtomikosTimeoutTest.java     # Timeout handling
    │
    ├── performance/
    │   ├── ConcurrentXATest.java        # Concurrent transactions
    │   ├── LongRunningXATest.java       # Long-duration transactions
    │   └── LargeDataXATest.java         # Large data volume
    │
    └── dbspecific/
        ├── OracleSpecificTest.java      # Oracle-specific features
        ├── SQLServerSpecificTest.java   # SQL Server-specific features
        └── DB2SpecificTest.java         # DB2-specific features
```

### Test Resources Structure

```
ojp-jdbc-driver/src/test/resources/
└── xa-baseline/
    ├── sql/
    │   ├── oracle-xa-setup.sql
    │   ├── sqlserver-xa-setup.sql
    │   └── db2-xa-setup.sql
    ├── properties/
    │   ├── atomikos.properties
    │   └── jta.properties
    └── testdata/
        ├── large-dataset.csv
        └── test-messages.json
```

## Edge Cases and Protocol Violations

The reference document identifies **59 edge case tests** across these categories:

### Protocol Violations (15 tests - HIGH priority)

1. **Start Before Previous Transaction Ended**: Call `start()` with new XID while previous transaction still active
   - Expected: `XAException(XAER_PROTO)`

2. **End Before Start**: Call `end()` without calling `start()` first
   - Expected: `XAException(XAER_PROTO or XAER_NOTA)`

3. **Prepare Before End**: Call `prepare()` without calling `end()` first
   - Expected: `XAException(XAER_PROTO)`

4. **Commit Without Prepare (Two-Phase Mode)**: Call `commit(xid, false)` without calling `prepare()` first
   - Expected: `XAException(XAER_PROTO)` OR auto-prepare (document database-specific behavior)

5. **Double Prepare**: Call `prepare()` twice on same XID
   - Expected: `XAException(XAER_PROTO or XAER_NOTA)`

6. **Double Commit**: Call `commit()` twice on same XID
   - Expected: `XAException(XAER_NOTA)` - XID not found after first commit

7. **Reuse XID After Commit**: Try to start new transaction with previously committed XID
   - Expected: `XAException(XAER_DUPID or XAER_NOTA)`

8. **Double Rollback**: Call `rollback()` twice on same XID
   - Expected: `XAException(XAER_NOTA)`

9. **Rollback After Commit**: Try to rollback a committed transaction
   - Expected: `XAException(XAER_NOTA)`

10. **Commit After Rollback**: Try to commit a rolled-back transaction
    - Expected: `XAException(XAER_NOTA)`

11. **Start with TMJOIN Without Existing Transaction**: Use TMJOIN flag without an existing transaction to join
    - Expected: `XAException(XAER_NOTA or XAER_PROTO)`

12. **Resume Without Suspend**: Use TMRESUME flag without previous TMSUSPEND
    - Expected: `XAException(XAER_PROTO)`

13. **Multiple End Calls**: Call `end()` multiple times on same transaction
    - Expected: `XAException(XAER_PROTO)`

14. **SQL Operations After End But Before Start**: Execute SQL when no XA transaction is active
    - Expected: May succeed (auto-commit) or fail - document behavior

15. **Prepare on Read-Only Transaction Then Commit**: Call `commit()` after receiving XA_RDONLY from `prepare()`
    - Expected: `XAException(XAER_NOTA)` - transaction already completed

### Resource Lifecycle Violations (8 tests - HIGH priority)

1. **Manual Commit on XA Connection**: Call `connection.commit()` while XA transaction is active
   - Expected: SQLException

2. **SetAutoCommit(true) During XA Transaction**: Try to enable auto-commit while XA transaction is active
   - Expected: SQLException or silently ignored

3. **Use Connection After Close**: Execute SQL after closing connection
   - Expected: SQLException

4. **XA Operations After Logical Connection Close**: Close logical connection but try to continue using XAResource
   - Expected: May work or fail - document behavior per database

5. **Close Connection With Active Transaction**: Close connection without ending XA transaction
   - Expected: Auto-rollback expected

6. **Close XAConnection With Prepared Transaction**: Close XAConnection while transaction is in prepared state
   - Expected: Prepared transaction persists, can be recovered

7. **Use XAResource After XAConnection Close**: Try to use XAResource after closing XAConnection
   - Expected: XAException or SQLException

8. **Resource Leak - Many Unclosed Connections**: Create many connections without closing them
   - Expected: Eventually hit connection pool limit

### Common Developer Mistakes (10 tests - HIGH priority)

1. **Not Checking Prepare Result**: Always call `commit()` after `prepare()` without checking for XA_RDONLY
2. **Mixing One-Phase and Two-Phase Commit**: Call `prepare()` then `commit()` with onePhase=true
3. **Non-Unique Global Transaction IDs**: Reuse global transaction ID across different transactions
4. **XID Component Too Long**: Create XID with globalTransactionId > 64 bytes
5. **Using TMSUCCESS Flag on Failed Transaction**: Use TMSUCCESS even though transaction encountered errors
6. **Forgetting to End Transaction Before Timeout**: Let transaction timeout without calling `end()`
7. **Not Handling Heuristic Outcomes**: Ignore XA_HEUR* exceptions, don't call `forget()`
8. **Assuming isSameRM() Returns True**: Not checking `isSameRM()` result before optimization
9. **Concurrent Access to Single XAResource**: Use XAResource from multiple threads without synchronization
10. **Not Cleaning Up After Exception**: Forget to rollback after exception

### Null and Invalid Parameters (6 tests - MEDIUM priority)

1. Null XID to all methods
2. XID with null components
3. Invalid flag values
4. Negative transaction timeout
5. Zero format ID
6. Null parameter to `isSameRM()`

### Concurrency and Threading Issues (5 tests - MEDIUM priority)

1. Concurrent `start()` calls
2. Concurrent SQL execution
3. Start on one thread, end on another
4. Concurrent `recover()` calls
5. Race between prepare and timeout

### Timeout Edge Cases (4 tests - MEDIUM priority)

1. Zero timeout
2. Very short timeout (1 second)
3. Timeout during long-running SQL
4. Change timeout mid-transaction

### Recovery Edge Cases (5 tests - MEDIUM priority)

1. Recover with no prepared transactions
2. Multiple recover without ENDRSCAN
3. Forget non-existent transaction
4. Commit/rollback recovered transaction twice
5. Recover during active transactions

### Database-Specific Edge Cases (6 tests - LOW priority)

1. Oracle RAC node failure
2. SQL Server MSDTC not running
3. DB2 log full
4. Tablespace/Disk full during prepare
5. Database restart during prepared transaction
6. Deadlock during XA transaction

## Maven Dependencies

Complete Maven dependencies required for XA baseline testing:

```xml
<dependencies>
    <!-- Oracle XA Driver -->
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc11</artifactId>
        <version>23.3.0.23.09</version>
        <scope>test</scope>
    </dependency>

    <!-- SQL Server XA Driver -->
    <dependency>
        <groupId>com.microsoft.sqlserver</groupId>
        <artifactId>mssql-jdbc</artifactId>
        <version>12.8.2.jre11</version>
        <scope>test</scope>
    </dependency>

    <!-- DB2 XA Driver -->
    <dependency>
        <groupId>com.ibm.db2</groupId>
        <artifactId>jcc</artifactId>
        <version>11.5.9.0</version>
        <scope>test</scope>
    </dependency>

    <!-- Atomikos Transaction Manager -->
    <dependency>
        <groupId>com.atomikos</groupId>
        <artifactId>transactions-jta</artifactId>
        <version>6.0.0</version>
        <classifier>jakarta</classifier>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.atomikos</groupId>
        <artifactId>transactions-jdbc</artifactId>
        <version>6.0.0</version>
        <classifier>jakarta</classifier>
        <scope>test</scope>
    </dependency>

    <!-- ActiveMQ Artemis XA -->
    <dependency>
        <groupId>org.apache.activemq</groupId>
        <artifactId>artemis-jakarta-client</artifactId>
        <version>2.35.0</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.apache.activemq</groupId>
        <artifactId>artemis-jms-client</artifactId>
        <version>2.35.0</version>
        <scope>test</scope>
    </dependency>

    <!-- JTA API -->
    <dependency>
        <groupId>jakarta.transaction</groupId>
        <artifactId>jakarta.transaction-api</artifactId>
        <version>2.0.1</version>
        <scope>test</scope>
    </dependency>

    <!-- JMS API -->
    <dependency>
        <groupId>jakarta.jms</groupId>
        <artifactId>jakarta.jms-api</artifactId>
        <version>3.1.0</version>
        <scope>test</scope>
    </dependency>

    <!-- TestContainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>oracle-xe</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mssqlserver</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>db2</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.17</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.16</version>
        <scope>test</scope>
    </dependency>

    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.12.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Test Implementation Template

Each test should follow this structure for consistency:

```java
/**
 * Test Case: [Brief Description]
 * Objective: [What this test validates]
 * Expected: [Expected behavior/exception]
 * Database Variations: [Known differences if any]
 */
@Test
public void test[Scenario]_[Database]() throws Exception {
    // Setup
    XADataSource xaDataSource = createXADataSource();
    XAConnection xaConnection = xaDataSource.getXAConnection();
    XAResource xaResource = xaConnection.getXAResource();
    Connection connection = xaConnection.getConnection();
    
    try {
        // Create test table
        String tableName = "xa_test_" + System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + tableName + " (id INT, data VARCHAR(100))");
        }
        
        // Execute test scenario
        Xid xid = XidGenerator.createXid();
        xaResource.start(xid, XAResource.TMNOFLAGS);
        
        // ... test-specific operations ...
        
        xaResource.end(xid, XAResource.TMSUCCESS);
        int prepareResult = xaResource.prepare(xid);
        
        if (prepareResult == XAResource.XA_OK) {
            xaResource.commit(xid, false);
        }
        
        // Assertions
        // ... verify expected behavior ...
        
        // Cleanup
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DROP TABLE " + tableName);
        }
    } finally {
        if (connection != null) connection.close();
        if (xaConnection != null) xaConnection.close();
    }
}
```

## Test Execution Commands

```bash
# Run all baseline XA tests
mvn test -Dtest="org.openjproxy.xa.baseline.**"

# Run Oracle-specific tests
mvn test -Dtest="org.openjproxy.xa.baseline.single.Oracle*"

# Run SQL Server-specific tests
mvn test -Dtest="org.openjproxy.xa.baseline.single.SQLServer*"

# Run DB2-specific tests
mvn test -Dtest="org.openjproxy.xa.baseline.single.DB2*"

# Run distributed transaction tests
mvn test -Dtest="org.openjproxy.xa.baseline.distributed.*"

# Run queue integration tests
mvn test -Dtest="org.openjproxy.xa.baseline.queue.*"

# Run Atomikos tests
mvn test -Dtest="org.openjproxy.xa.baseline.atomikos.*"

# Run performance tests
mvn test -Dtest="org.openjproxy.xa.baseline.performance.*"

# Run edge case tests
mvn test -Dtest="org.openjproxy.xa.baseline.single.*EdgeCases*"
```

## XA Error Codes Reference

| Error Code | Value | Meaning | Common Causes |
|-----------|-------|---------|---------------|
| XA_OK | 0 | Normal execution | Prepare succeeded, commit needed |
| XA_RDONLY | 3 | Read-only transaction | Prepare succeeded, no commit needed |
| XA_HEURCOM | 7 | Heuristic commit | Transaction committed outside XA |
| XA_HEURMIX | 5 | Heuristic mixed | Some branches committed, some rolled back |
| XA_HEURHAZ | 8 | Heuristic hazard | State unknown due to heuristic decision |
| XA_HEURRB | 6 | Heuristic rollback | Transaction rolled back outside XA |
| XAER_RMERR | -3 | Resource manager error | Database internal error |
| XAER_NOTA | -4 | XID not valid | Unknown or invalid XID |
| XAER_INVAL | -5 | Invalid arguments | Invalid parameters passed |
| XAER_PROTO | -6 | Protocol error | Operation called in wrong sequence |
| XAER_RMFAIL | -7 | Resource manager failed | Database unavailable |
| XAER_DUPID | -8 | Duplicate XID | XID already in use |
| XAER_OUTSIDE | -9 | Outside valid state | Operation not valid in current state |

## Two-Phase Commit Protocol Flow

```
Transaction Manager         Resource Manager 1         Resource Manager 2
      |                            |                         |
      |--- xaStart(xid1) --------->|                         |
      |--- xaStart(xid2) -------------------------------->|  |
      |                            |                         |
      |   (Application executes SQL operations)              |
      |                            |                         |
      |--- xaEnd(xid1) ----------->|                         |
      |--- xaEnd(xid2) ----------------------------------->|  |
      |                            |                         |
      |            PREPARE PHASE                             |
      |--- xaPrepare(xid1) ------->|                         |
      |<-- XA_OK ------------------|                         |
      |--- xaPrepare(xid2) ---------------------------->|   |
      |<-- XA_OK -----------------------------------------|   |
      |                            |                         |
      |     (All prepares successful - decide to commit)     |
      |                            |                         |
      |            COMMIT PHASE                              |
      |--- xaCommit(xid1, false) ->|                         |
      |<-- Success ----------------|                         |
      |--- xaCommit(xid2, false) ------------------------>|  |
      |<-- Success ----------------------------------------|  |
      |                            |                         |
      |        TRANSACTION COMPLETE                          |
```

## Summary of Enhanced Test Coverage

This enhanced plan now includes:

1. **Detailed Project Structure**: Complete package and class organization
2. **59 Edge Case Tests**: Comprehensive coverage of protocol violations, lifecycle issues, and developer mistakes
3. **Complete Maven Dependencies**: All required libraries for XA testing
4. **Test Templates**: Standardized test implementation patterns
5. **Error Code Reference**: Quick reference for XA error codes
6. **2PC Protocol Diagram**: Visual representation of two-phase commit
7. **Test Execution Commands**: Ready-to-use Maven commands

The plan maintains the original baseline-first strategy while adding significant depth in test scenario detail, edge case coverage, and implementation guidance.

