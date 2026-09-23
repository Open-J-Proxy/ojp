# Database-Specific Connection Count Queries

## Overview

This document outlines database-specific SQL queries to retrieve the number of active connections for a specific database user. These queries are essential for the connection count validation mechanism in OJP's multinode pool resizing logic.

## Purpose

Before resizing connection pools due to perceived node failures, OJP can query the database to verify the actual number of connections from the current user. This helps distinguish between:
- **True node failure**: Node is down, connections are closed, database shows fewer connections
- **Network partition**: Node is up but unreachable to some clients, connections still active in database

## Database-Specific Queries

### PostgreSQL

```sql
-- Count active connections for current user
SELECT COUNT(*) as connection_count
FROM pg_stat_activity
WHERE usename = CURRENT_USER
  AND state != 'idle'
  AND pid != pg_backend_pid();  -- Exclude the query connection itself
```

**Alternative (all connections including idle):**
```sql
SELECT COUNT(*) as connection_count
FROM pg_stat_activity
WHERE usename = CURRENT_USER
  AND pid != pg_backend_pid();
```

**Permissions Required**: None for current user's connections. Access to `pg_stat_activity` is granted by default.

**Notes**:
- `state != 'idle'` filters out idle connections
- `pg_backend_pid()` excludes the current query connection
- Works with PostgreSQL 9.2+

### MySQL / MariaDB

```sql
-- Count active connections for current user
SELECT COUNT(*) as connection_count
FROM information_schema.PROCESSLIST
WHERE USER = SUBSTRING_INDEX(USER(), '@', 1)
  AND ID != CONNECTION_ID();  -- Exclude the query connection itself
```

**Alternative (with host filtering):**
```sql
SELECT COUNT(*) as connection_count
FROM information_schema.PROCESSLIST
WHERE USER = SUBSTRING_INDEX(USER(), '@', 1)
  AND HOST LIKE CONCAT(SUBSTRING_INDEX(USER(), '@', -1), '%')
  AND ID != CONNECTION_ID();
```

**Permissions Required**: `PROCESS` privilege to see all processes, or user sees their own connections by default.

**Notes**:
- `USER()` returns 'username@hostname'
- `SUBSTRING_INDEX(USER(), '@', 1)` extracts just the username
- `CONNECTION_ID()` excludes the current query connection
- Works with MySQL 5.1+ and MariaDB 10.0+

### Oracle

```sql
-- Count active sessions for current user
SELECT COUNT(*) as connection_count
FROM v$session
WHERE username = SYS_CONTEXT('USERENV', 'SESSION_USER')
  AND sid != SYS_CONTEXT('USERENV', 'SID')  -- Exclude current session
  AND status = 'ACTIVE';
```

**Alternative (all sessions including inactive):**
```sql
SELECT COUNT(*) as connection_count
FROM v$session
WHERE username = SYS_CONTEXT('USERENV', 'SESSION_USER')
  AND sid != SYS_CONTEXT('USERENV', 'SID')
  AND type = 'USER';  -- Exclude background processes
```

**Permissions Required**: 
- `SELECT` privilege on `v$session` (typically granted via `SELECT_CATALOG_ROLE` or similar)
- For non-DBA users, Oracle may require explicit grants

**Notes**:
- `v$session` is a performance view
- `SYS_CONTEXT('USERENV', 'SESSION_USER')` gets the current user
- `SYS_CONTEXT('USERENV', 'SID')` gets the current session ID
- Works with Oracle 10g+

### SQL Server

```sql
-- Count active connections for current user
SELECT COUNT(*) as connection_count
FROM sys.dm_exec_sessions
WHERE login_name = SUSER_SNAME()
  AND session_id != @@SPID  -- Exclude current session
  AND is_user_process = 1;
```

**Alternative (with database context):**
```sql
SELECT COUNT(*) as connection_count
FROM sys.dm_exec_sessions s
WHERE s.login_name = SUSER_SNAME()
  AND s.session_id != @@SPID
  AND s.is_user_process = 1
  AND s.database_id = DB_ID();  -- Only current database
```

**Permissions Required**: 
- `VIEW SERVER STATE` permission (server-level)
- Without this permission, users can only see their own sessions

**Notes**:
- `sys.dm_exec_sessions` is a dynamic management view
- `SUSER_SNAME()` returns the current login name
- `@@SPID` is the current session ID
- `is_user_process = 1` excludes system processes
- Works with SQL Server 2005+

### DB2

```sql
-- Count active connections for current user (DB2 LUW)
SELECT COUNT(*) as connection_count
FROM TABLE(MON_GET_CONNECTION(NULL, -1))
WHERE APPLICATION_HANDLE != MON_GET_APPLICATION_HANDLE()
  AND SESSION_AUTH_ID = SESSION_USER;
```

**Alternative (application-level):**
```sql
SELECT COUNT(*) as connection_count
FROM SYSIBMADM.APPLICATIONS
WHERE AUTHID = USER
  AND APPL_ID != (SELECT CURRENT SERVER FROM SYSIBM.SYSDUMMY1);
```

**Permissions Required**: 
- `EXECUTE` privilege on monitoring functions
- `SELECT` privilege on `SYSIBMADM.APPLICATIONS`
- `DATAACCESS` or `DBADM` authority may be required

**Notes**:
- `MON_GET_CONNECTION()` is a table function for connection monitoring
- `SESSION_USER` and `USER` return the current user
- Works with DB2 10.1+ for LUW (Linux, Unix, Windows)
- For DB2 z/OS, different system tables may be required

### H2

```sql
-- Count active connections (H2 in-memory/embedded)
SELECT COUNT(*) as connection_count
FROM INFORMATION_SCHEMA.SESSIONS
WHERE USER_NAME = USER()
  AND ID != SESSION_ID();
```

**Permissions Required**: None (accessible by all users for their own sessions)

**Notes**:
- H2 provides `INFORMATION_SCHEMA.SESSIONS`
- `USER()` returns the current user
- `SESSION_ID()` returns the current session ID
- Works with H2 1.4+
- Limited utility in embedded mode (single JVM)

### CockroachDB

```sql
-- Count active sessions for current user
SELECT COUNT(*) as connection_count
FROM crdb_internal.cluster_sessions
WHERE user_name = current_user()
  AND session_id != crdb_internal.cluster_session_id();
```

**Alternative (using pg_stat_activity for PostgreSQL compatibility):**
```sql
SELECT COUNT(*) as connection_count
FROM pg_stat_activity
WHERE usename = CURRENT_USER
  AND pid != pg_backend_pid();
```

**Permissions Required**: None for current user's sessions

**Notes**:
- CockroachDB supports PostgreSQL-compatible queries
- `crdb_internal.cluster_sessions` is CockroachDB-specific
- `pg_stat_activity` provides PostgreSQL compatibility
- Works with CockroachDB 19.1+

## Implementation Considerations

### 1. Query Execution Context

**When to Execute:**
- Before expanding pool (server failure detected)
- Before contracting pool (server recovery detected)
- On-demand via admin API (optional)

**Frequency:**
- Should be rate-limited to avoid overhead
- Only execute when cluster health changes
- Cache results for a short period (e.g., 5-10 seconds)

### 2. Connection Overhead

**Query Cost:**
- All queries are lightweight (system views/tables)
- Execution time: typically < 100ms
- Uses one additional database connection briefly

**Mitigation:**
- Use connection pool's existing connections
- Set query timeout (e.g., 5 seconds)
- Handle failures gracefully (proceed with resize on error)

### 3. Expected Connection Counts

**Baseline Calculation:**
```
Expected Connections Per Server = Total Max Pool Size / Number of Healthy Servers
```

**Example (3-server cluster):**
- Total max pool size: 30
- Normal operation: Each server has ~10 connections
- One server appears down: Expected ~15 connections on remaining servers
- If database shows ~30 connections total: **Network partition** (server still serving)
- If database shows ~20 connections total: **True failure** (server is down)

### 4. Decision Logic

```
Current DB Connections = Query Result
Expected Connections After Resize = (Total Max Pool Size / New Healthy Server Count)
Connection Threshold = Total Max Pool Size * 0.9  // 90% threshold

IF Current DB Connections >= Connection Threshold THEN
    // Network partition likely - do NOT resize
    Log: "Detected potential network partition - database shows {count} connections, expected resize would be premature"
    Skip pool resize
ELSE
    // True failure - proceed with resize
    Log: "Confirmed node failure - database shows {count} connections (below threshold), proceeding with pool resize"
    Resize pools
END IF
```

### 5. Error Handling

**Query Failures:**
- Network timeout: Proceed with resize (conservative)
- Permission denied: Log warning, proceed with resize
- Unknown database: Skip validation, proceed with resize
- Query syntax error: Log error, proceed with resize

**Fallback Strategy:**
If validation fails, the system should default to the current behavior (resize based on cluster health) to maintain availability.

## Security Considerations

### 1. Least Privilege

- Queries only access the current user's connection information
- No need for elevated privileges in most databases
- PostgreSQL and H2: No special permissions required
- MySQL: May need `PROCESS` privilege (or sees own connections)
- Oracle, SQL Server, DB2: May require monitoring permissions

### 2. Information Leakage

- Queries reveal only connection counts, not sensitive data
- No access to other users' session details
- No query content or result data exposed

### 3. SQL Injection Prevention

- All queries use database functions (CURRENT_USER, USER(), etc.)
- No user-supplied parameters in queries
- Prepared statements can still be used for safety

## Performance Impact

### Resource Usage

**Database Side:**
- Minimal CPU impact (system view queries are optimized)
- No disk I/O (data is in-memory)
- Negligible memory overhead

**OJP Server Side:**
- One additional query per health change event
- Query executes in ~10-100ms typically
- Minimal memory for result set (single integer)

### Scalability

**Small Deployments (2-3 servers):**
- Health changes are rare (server failures/recoveries)
- Validation query overhead negligible
- Total overhead: ~1-2 queries per hour in stable environment

**Large Deployments (10+ servers):**
- Health changes more frequent
- Consider rate limiting (max 1 query per 5 seconds per datasource)
- Total overhead: ~10-20 queries per hour per datasource

## Testing Strategy

### Unit Tests

```java
@Test
void testPostgreSQLConnectionCountQuery() {
    int count = connectionValidator.getConnectionCount(
        dataSource, 
        DbName.POSTGRES
    );
    assertTrue(count >= 0);
}
```

### Integration Tests

1. **Baseline Test**: Query connection count in normal operation
2. **Server Failure Test**: Verify count decreases when server actually fails
3. **Network Partition Simulation**: Mock partition, verify count remains high
4. **Threshold Test**: Verify resize is skipped when count above threshold

### Performance Tests

1. **Query Latency**: Measure query execution time across databases
2. **Concurrent Queries**: Test multiple simultaneous validation queries
3. **Load Test**: Verify validation doesn't impact normal operations

## Monitoring and Observability

### Metrics

- `ojp.pool.resize.validation.query.duration_ms` - Query execution time
- `ojp.pool.resize.validation.skipped` - Count of skipped resizes
- `ojp.pool.resize.validation.performed` - Count of performed resizes
- `ojp.pool.resize.validation.errors` - Count of validation errors

### Logs

```
INFO: Validating connection count before pool resize: connHash={}, currentHealthy={}
DEBUG: Database connection count query result: {} connections (threshold: {})
INFO: Skipping pool resize - network partition detected ({} connections above threshold)
INFO: Proceeding with pool resize - confirmed node failure ({} connections below threshold)
WARN: Connection count validation failed: {} - proceeding with resize (conservative)
```

## Alternatives Considered

### 1. Client-Side Connection Tracking Only

**Pros:**
- No database queries required
- Lower latency decision
- No database permissions needed

**Cons:**
- Cannot detect network partitions
- No ground truth from database
- Client state may be stale

### 2. Heartbeat-Based Detection

**Pros:**
- Active monitoring of server health
- Can detect various failure modes

**Cons:**
- Doesn't solve network partition problem
- Additional network overhead
- Complex implementation

### 3. Distributed Consensus (e.g., Raft, Paxos)

**Pros:**
- Definitive cluster membership decisions
- Handles split-brain scenarios

**Cons:**
- Significant complexity
- Requires coordination infrastructure
- Overkill for connection pooling

**Recommendation**: Database connection count validation provides a simple, effective solution with minimal overhead.

## Conclusion

Querying the database for connection counts before pool resizing is a pragmatic approach to distinguish between true node failures and network partitions. The solution:

1. **Works across major databases** with standard system views
2. **Minimal overhead** (~10-100ms per validation)
3. **Simple to implement** with existing infrastructure
4. **Safe fallback** (proceeds with resize on validation failure)
5. **Effective detection** of network partition scenarios

The main trade-off is the additional database query, but this is acceptable given the infrequency of health changes and the benefit of avoiding unnecessary pool resizing.
