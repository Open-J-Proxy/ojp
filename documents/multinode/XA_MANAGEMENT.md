# XA Transaction Management in OJP

Start with the [simplified XA flows](../designs/MAIN_FLOWS.md#main-xa-flows): setup → branch work → completion → closure, with recovery as a separate scenario. This page explains the mechanisms behind those operations. Use the [JDBC configuration reference](../configuration/ojp-jdbc-configuration.md) for exact client-supplied pool settings and the [documentation hub](../README.md) for other tasks.

**Last Updated**: 2025-12-28  
**Status**: Production Ready  
**Version**: Unified Connection Model with Backend Session Pooling

## Table of Contents
1. [Overview](#overview)
2. [XA Architecture](#xa-architecture)
3. [Backend Session Pooling](#backend-session-pooling)
4. [Configuration](#configuration)
5. [Dual-Condition Session Lifecycle](#dual-condition-session-lifecycle)
6. [Dynamic Pool Rebalancing](#dynamic-pool-rebalancing)
7. [Multinode XA Support](#multinode-xa-support)
8. [Performance and Monitoring](#performance-and-monitoring)
9. [Troubleshooting](#troubleshooting)

---

## Overview

OJP provides comprehensive XA (distributed transaction) support with backend session pooling for PostgreSQL and other XA-capable databases. The implementation uses Apache Commons Pool 2 for managing XA backend sessions on the server side, while maintaining JDBC XA spec compliance on the client side.

### Key Features

- **Backend Session Pooling**: Server-side pooling of XAConnection and backend sessions using Apache Commons Pool 2
- **Dual-Condition Lifecycle**: Sessions returned to pool only when BOTH transaction complete AND OJP XAConnection closed
- **Dynamic Pool Rebalancing**: Automatic pool resizing based on cluster health and server failures
- **Single-Server XA Connect**: Each `getXAConnection()` binds to exactly one least-loaded server — no unnecessary pool creation on other nodes
- **Non-XA connect() RPC Skip**: After the first successful non-XA `connect()`, the `connHash` is cached client-side; subsequent JDBC connections are built locally with zero gRPC overhead
- **Pool-Lost Recovery**: If the server returns `NOT_FOUND` (pool lost after restart), the driver invalidates the cache, re-issues `connect()`, and retries transparently
- **Connection Reuse**: Physical PostgreSQL connections stay open and reused across multiple XA transactions

### Architecture Principles

1. **Separation of Concerns**:
   - **Application Side**: Disable application-level connection pools, including HikariCP. The transaction manager coordinates XA through OJP's XA datasource and resource; backend pooling belongs on the server. See [framework integration](../java-frameworks/README.md).
   - **Server Side**: Apache Commons Pool 2 (XA) or HikariCP (non-XA) pools PostgreSQL backend sessions via SPIs

2. **Connection Strategy**:
   - **XA**: Each `getXAConnection()` sends one `connect()` RPC to the single least-loaded server; the session binds to that server for its lifetime
   - **Non-XA first call**: Fans out `connect()` to all servers so every node learns the datasource; caches the returned `connHash`
   - **Non-XA subsequent calls**: Built locally from the `connHash` cache — no gRPC round-trip

3. **Pooling Contract**:
   - Pool's `makeObject()` → `open()` creates physical PostgreSQL XAConnection
   - Pool's `passivateObject()` → `reset()` cleans state, keeps connection open
   - Pool's `destroyObject()` → `close()` destroys physical connection (eviction only)

4. **XA Spec Compliance**:
   - Sessions support multiple sequential XA transactions
   - Connection properties persist across transaction boundaries
   - No connection recreation between transactions (JDBC XA requirement)

---

## XA Architecture

### Connection Flow

```
Client Application
    ↓
OjpXADataSource.getXAConnection()
    ↓ (select single least-loaded server via SessionTracker)
[gRPC Call] StatementService.connect(isXA=true) → ONE server
    ↓
OJP Server - CommonsPool2XADataSource
    ↓ borrowSession()
Apache Commons Pool 2
    ↓ makeObject() / borrowObject()
PostgreSQL XAConnection (backend session)
    ↓
PostgreSQL Database
```

### Component Responsibilities

#### Client Side (OJP JDBC Driver)
- `OjpXADataSource`: Implements `javax.sql.XADataSource`
- `OjpXAConnection`: Wraps server XA session, provides `XAResource` and `Connection`
- `OjpXALogicalConnection`: Logical connection from XAConnection, blocks direct commit/rollback
- `OjpXAResource`: Delegates XA operations to server via gRPC

#### Server Side (OJP Server)
- `CommonsPool2XADataSource`: Manages backend session pool using Apache Commons Pool 2
- `XATransactionRegistry`: Tracks active XA transactions and session lifecycle
- `XABackendSession`: Wraps PostgreSQL XAConnection with pooling lifecycle methods
- `StatementServiceImpl`: Routes XA operations to appropriate backend sessions

#### Backend (PostgreSQL)
- `PGXADataSource`: Creates XA-capable connections
- `PGXAConnection`: PostgreSQL's XA connection implementation
- `PGXAResource`: PostgreSQL's XA resource for two-phase commit

### Data Flow

1. **Connection Establishment**:
   ```
   Client: OjpXADataSource.getXAConnection()
   → Server: borrowSession() from Commons Pool 2
   → Pool: makeObject() if needed, or reuse idle session
   → Backend: Open or reuse PostgreSQL XAConnection
   ```

2. **XA Transaction Execution**:
   ```
   Client: xaResource.start(xid)
   → Server: Delegate to PostgreSQL XAResource.start(xid)
   → Execute SQL operations
   → Client: xaResource.end(xid)
   → Client: xaResource.prepare(xid)
   → Client: xaResource.commit(xid)
   ```

3. **Connection Release**:
   ```
   Client: xaConnection.close()
   → Server: Mark transaction complete, keep in registry
   → When BOTH conditions met (transaction complete AND XAConnection closed): Return session to pool
   → Pool: passivateObject() → reset() (clean state, keep connection open)
   ```

---

## Backend Session Pooling

### Why Server-Side Pooling?

**Problem**: XA connections cannot use HikariCP (non-XA only)
**Solution**: Apache Commons Pool 2 manages PostgreSQL XA backend sessions on server side

### Pool Architecture

#### XABackendSession Wrapper

```java
public class XABackendSession implements Poolable {
    private XAConnection xaConnection;      // PostgreSQL XAConnection
    private Connection connection;           // Regular connection from XAConnection
    private XAResource xaResource;          // PostgreSQL XAResource
    
    // Pooling lifecycle methods
    public void open() { /* Create XAConnection */ }
    public void reset() { /* Clean state, keep connection */ }
    public void close() { /* Destroy connection (eviction only) */ }
}
```

#### CommonsPool2XADataSource

Manages the pool with dynamic resizing:

```java
public class CommonsPool2XADataSource {
    private GenericObjectPool<XABackendSession> pool;
    
    public XABackendSession borrowSession() { return pool.borrowObject(); }
    public void returnSession(XABackendSession session) { pool.returnObject(session); }
    
    // Dynamic resizing for failover
    public void setMaxTotal(int maxTotal) { /* Resize pool */ }
    public void setMinIdle(int minIdle) { /* Pre-warm connections */ }
}
```

### Pool Configuration

Default values (per server):
- `maxTotal`: 11 (maximum active sessions)
- `minIdle`: 10 (pre-warmed idle sessions)
- `maxWait`: 20 seconds (borrow timeout)

Multinode division:
- 2 servers: 22 total → 11 per server
- Pool sizes automatically rebalance on server failures

### Lifecycle States

1. **Idle in Pool**:
   - Physical PostgreSQL XAConnection **open**
   - Session wrapper in pool's idle queue
   - Ready for immediate reuse

2. **Active (Borrowed)**:
   - Session in use by OJP XA session
   - Executing XA transactions
   - NOT yet returned to pool

3. **Transaction Complete, XAConnection Open**:
   - Transaction committed/rolled back
   - OJP XAConnection still open (client hasn't closed)
   - Session stays in XA registry (not returned to pool)

4. **Ready for Return**:
   - BOTH conditions met:
     - Transaction complete (commit/rollback called)
     - OJP XAConnection closed (client called close())
   - Session returned to pool via `returnSession()`
   - Pool calls `passivateObject()` → `reset()`

5. **Evicted from Pool**:
   - Pool evicts idle session (max lifetime reached)
   - Pool calls `destroyObject()` → `close()`
   - Physical PostgreSQL XAConnection destroyed

---

## Configuration

**Note**: All pool configuration properties are documented in detail at `documents/configuration/ojp-jdbc-configuration.md`. This section provides a high-level overview of XA-specific configuration.

### Server-Side Pool Configuration

XA connections use **separate pool configuration** from non-XA connections. Configuration is provided by the client and applied on the server side when creating XA datasources.

#### Multinode Pool Division

For N servers with maxTotal=M:
- Each server gets: M / N sessions
- Example: 2 servers, maxTotal=22 → 11 per server

Pool sizes automatically rebalance when:
- Server fails → remaining servers expand to compensate for lost sessions on Server 1
- Server recovers → Both servers rebalance, dividing the total sessions among all servers in the cluster

---

## Dual-Condition Session Lifecycle

### Why Dual-Condition?

**Problem**: Sessions were immediately returned to pool after `commit()`, but OJP XAConnection was still open. Next transaction on same OJP connection failed because backend session was already back in the pool.

**Solution**: Sessions only returned when **BOTH** conditions met:
1. XA transaction complete (`commit()` or `rollback()` called)
2. OJP XAConnection closed (client called `xaConnection.close()`)

### Lifecycle Flow

#### Normal Transaction Flow

```
1. Client: xaConnection = xaDataSource.getXAConnection()
   → Server: Borrow session from pool (idle → active)

2. Client: xaResource.start(xid)
   → Server: Register in XATransactionRegistry
   → Registry: Mark transactionComplete = false

3. Client: Execute SQL operations

4. Client: xaResource.end(xid), prepare(xid), commit(xid)
   → Server: Mark transactionComplete = true
   → Session STAYS in registry (OJP XAConnection still open)

5. Client: xaConnection.close()
   → Server: Call registry.returnCompletedSessions()
   → Registry: Find sessions where transactionComplete = true
   → Return those sessions to pool
   → Pool: passivateObject() → reset()
```

#### Multiple Sequential Transactions (JDBC XA Requirement)

```
1. Client: xaConnection = xaDataSource.getXAConnection()
   → Server: Borrow session from pool

2. Client: Transaction 1
   xaResource.start(xid1) → end() → commit()
   → Server: Mark transactionComplete = true
   → Session stays in registry (XAConnection still open)

3. Client: Transaction 2 (same XAConnection)
   xaResource.start(xid2) → end() → commit()
   → Server: Mark transactionComplete = true again
   → Session stays in registry

4. Client: xaConnection.close()
   → Server: Return session to pool once
   → Connection reused across both transactions ✓
```

### Implementation Details

#### TxContext Tracking

```java
public class TxContext {
    private String xid;
    private String ojpSessionId;          // OJP XAConnection UUID
    private XABackendSession backendSession;  // Pooled backend session
    private boolean transactionComplete;   // Set on commit/rollback
}
```

#### XATransactionRegistry

```java
public class XATransactionRegistry {
    private ConcurrentHashMap<String, TxContext> transactions;
    
    public void markTransactionComplete(String xid) {
        TxContext ctx = transactions.get(xid);
        ctx.setTransactionComplete(true);
        // Session stays in registry
    }
    
    public void returnCompletedSessions(String ojpSessionId) {
        // Called when OJP XAConnection closes
        List<TxContext> completed = transactions.values().stream()
            .filter(ctx -> ctx.getOjpSessionId().equals(ojpSessionId))
            .filter(TxContext::isTransactionComplete)
            .collect(Collectors.toList());
        
        for (TxContext ctx : completed) {
            poolProvider.returnSession(ctx.getBackendSession());
            transactions.remove(ctx.getXid());
        }
    }
}
```

### Session Lifecycle Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ Pool (Idle Sessions)                                            │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐                         │
│ │ Session │  │ Session │  │ Session │  ...                     │
│ └─────────┘  └─────────┘  └─────────┘                         │
└──────┬──────────────────────────────────────────────────────────┘
       │ borrowSession()
       ↓
┌─────────────────────────────────────────────────────────────────┐
│ XA Transaction Registry (Active Transactions)                   │
│ ┌────────────────────────────────────────────────────────────┐ │
│ │ TxContext: xid=tx1, ojpSessionId=uuid1,                   │ │
│ │           transactionComplete=false, backendSession=s1    │ │
│ └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│        xaResource.commit() → transactionComplete = true          │
│        Session STAYS in registry (XAConnection open)             │
│                                                                  │
│        xaConnection.close() → returnCompletedSessions(uuid1)     │
│        ↓                                                         │
└────────┼─────────────────────────────────────────────────────────┘
         │ returnSession(s1)
         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Pool (Idle Sessions) - Session s1 returned                      │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐           │
│ │ Session │  │ Session │  │ Session │  │    s1   │  ...      │
│ └─────────┘  └─────────┘  └─────────┘  └─────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Dynamic Pool Rebalancing

### Why Dynamic Rebalancing?

In multinode deployments, server failures require immediate pool resizing:
- Server 1 fails → Server 2 must expand to compensate for lost sessions on Server 1
- Server 1 recovers → Both servers rebalance, dividing the total sessions among all servers in the cluster

### Rebalancing Triggers

1. **Cluster Health Changes**:
   - Health check detects server failure/recovery
   - `processClusterHealth()` called with updated health status
   - Pool resizing triggered for affected servers

2. **Connection Establishment**:
   - `connect()` called with cluster health information
   - Pools resized BEFORE borrowing session
   - Prevents pool exhaustion on first connection attempt

3. **XA Operations**:
   - `xaStart()`, `xaPrepare()`, `xaCommit()` propagate cluster health
   - Ensures pools stay balanced during transaction execution

### Resizing Algorithm

Pool resizing is handled by `StatementServiceImpl.processClusterHealth()` and `CommonsPool2XADataSource` classes. The algorithm divides the configured total pool size among healthy servers:

- Count healthy servers in the cluster
- Divide total maxTotal and minIdle by healthy server count
- Call `setMaxTotal()` and `setMinIdle()` on each healthy server's pool
- Pre-warm idle connections to the new minIdle target

When `setMinIdle()` is called, the pool ensures maxIdle is updated and attempts to create idle connections immediately using `preparePool()`. If under load, a manual creation loop ensures connections are created even if the pool is actively being used.

### Rebalancing Timing

**Critical**: `processClusterHealth()` called **BEFORE** `borrowSession()`
- Ensures pool resized before first connection attempt
- Prevents pool exhaustion cycle where:
  1. All sessions borrowed
  2. Pool exhausted
  3. Health check can't run (needs session)
  4. Pool never resizes

**Implementation**:
```java
public XABackendSession connect(ConnectionDetails details) {
    // 1. Process cluster health FIRST
    processClusterHealth(details.getClusterHealth());
    
    // 2. THEN borrow session (pool already resized)
    return borrowSession();
}
```

### Failover Example

**Scenario**: 2 servers, maxTotal=22, minIdle=20

**Initial State**:
- Server 1: maxTotal=11, minIdle=10, idle=10
- Server 2: maxTotal=11, minIdle=10, idle=10

**Server 1 Fails**:
1. Health check detects failure
2. `processClusterHealth()` called with Server 1=DOWN, Server 2=UP
3. Server 2 pool resized: maxTotal=22, minIdle=20
4. `setMinIdle(20)` pre-warms 10 additional connections
5. Server 2 now handles all traffic with 20 idle + capacity for 2 more active

**Server 1 Recovers**:
1. Health check detects recovery (periodic 5-second heartbeat)
2. `processClusterHealth()` called with both servers UP
3. Both pools resized: maxTotal=11, minIdle=10
4. Excess connections naturally evicted over time (idleTimeout)

---

## Multinode XA Support

### XA Connection Model: Single Least-Loaded Server

Each XA `getXAConnection()` call connects to exactly **one** server, chosen by the
least-connections strategy (round-robin when loads are equal):

- Avoids creating unnecessary backend pools on every node for a single-server transaction
- XA sessions are bound to their server for the lifetime of the `XAConnection`
- Successive `getXAConnection()` calls are automatically spread across the cluster
  as the `SessionTracker` counts grow on the chosen servers

**Contrast with non-XA (regular JDBC `getConnection()`):**  
The first non-XA `connect()` call fans out to **all** servers so every node learns
about the datasource configuration.  Subsequent calls are served from a local
`connHash` cache — no gRPC round-trip at all (see *Non-XA connect() RPC Skip*
below).

### Load-Aware Selection

Uses `SessionTracker` for accurate load distribution:

```java
public ServerEndpoint selectByLeastConnections() {
    ServerEndpoint leastLoaded = null;
    int minSessions = Integer.MAX_VALUE;
    
    for (ServerEndpoint server : healthyServers) {
        int sessionCount = sessionTracker.getSessionCount(server);
        if (sessionCount < minSessions) {
            minSessions = sessionCount;
            leastLoaded = server;
        }
    }
    return leastLoaded;
}
```

### Health Check Integration

**Periodic Health Checks** (every 5 seconds):
- Detect server failures/recoveries automatically
- No dependency on new connection attempts
- Trigger pool rebalancing on state changes

**Health Check Implementation**:
```java
// Scheduled executor runs performHealthCheck() every 5 seconds
public boolean performHealthCheck(ServerEndpoint server) {
    try {
        // Lightweight heartbeat query
        SessionInfo session = connect(server, heartbeatConnectionDetails);
        terminateSession(session);
        return true;
    } catch (Exception e) {
        return false;  // Server unhealthy
    }
}
```

### XA Transaction Across Servers

**Distributed Transaction Example** (2 databases, 2 OJP servers):

```
Transaction Manager (e.g., Narayana, Bitronix)
    │
    ├─→ OJP Server 1 (XAResource 1)
    │       ↓
    │   PostgreSQL DB 1 (XA backend)
    │
    └─→ OJP Server 2 (XAResource 2)
            ↓
        PostgreSQL DB 2 (XA backend)

1. TM: xaResource1.start(xid, branch1)
2. TM: xaResource2.start(xid, branch2)
3. Execute SQL on both branches
4. TM: xaResource1.end() → prepare() → commit()
5. TM: xaResource2.end() → prepare() → commit()
```

**Supported Transaction Managers**: Narayana, Bitronix, and other JTA-compliant transaction managers.

**Note on Atomikos**: Atomikos is currently **not supported** due to its architecture requiring pooled XAConnections at the application side. OJP manages all pooling on the server side, so XA connections obtained by the application must be unpooled (open/close directly without intermediate pooling). Atomikos does not support unpooled XA connections in its free version, making it incompatible with OJP's server-side pooling model.

Each OJP server:
- Manages its own backend session pool
- Delegates XA operations to PostgreSQL XAResource
- Returns sessions to pool after transaction completes

---

## Performance and Monitoring

### Pool Metrics

Enable DEBUG logging for pool diagnostics:
```properties
-Dorg.slf4j.simpleLogger.log.org.openjproxy=DEBUG
```

**Log Output**:
```
XA pool initialized: server=localhost:5432, maxTotal=11, minIdle=10
Session borrowed successfully: poolState=[active=2, idle=8, maxTotal=11]
Session returned to pool: poolState=[active=1, idle=9, maxTotal=11]
Pool resized due to cluster health change: maxTotal=11→22, minIdle=10→20
Pre-warmed 10 idle connections successfully
```

### Performance Characteristics

**Connection Reuse**:
- ✅ Physical PostgreSQL XAConnection stays open across transactions
- ✅ No connection recreation overhead between transactions
- ✅ Session reset (10-50ms) vs. connection creation (100-500ms)

**Pool Pre-Warming**:
- ✅ Idle connections pre-created at startup (minIdle)
- ✅ Zero latency on first borrow (connection already open)
- ✅ Automatic expansion during failover (setMinIdle pre-warms immediately)

**Load Distribution**:
- ✅ SessionTracker provides accurate session counts
- ✅ Load-aware selection distributes evenly (15-20% better than round-robin)
- ✅ All connection types use same load algorithm

### JMX Monitoring

Apache Commons Pool 2 exposes JMX metrics:

```
org.openjproxy:type=CommonsPool2XADataSource,name=localhost:5432
- NumActive: Current active (borrowed) sessions
- NumIdle: Current idle sessions in pool
- MaxTotal: Maximum pool size
- MinIdle: Minimum idle sessions maintained
- MaxWaitMillis: Borrow timeout
```

---

## Non-XA connect() RPC Skip and Pool-Lost Recovery

### connect() RPC Skip Optimisation

For regular (non-XA) connections, the `connect()` gRPC RPC is only sent **once**
per unique `(url, user, password, dataSourceName)` combination.  After the first
successful call the driver caches the returned `connHash` client-side.  All
subsequent `DriverManager.getConnection()` / `DataSource.getConnection()` calls
are fulfilled locally from that cache — no gRPC round-trip.

```
1st getConnection()  →  connect() RPC → ALL servers seeded → connHash cached
2nd getConnection()  →  local SessionInfo built from cache  (no RPC)
3rd getConnection()  →  local SessionInfo built from cache  (no RPC)
…
```

The first call still fans out to every server so all nodes receive the datasource
configuration and can serve subsequent SQL operations without extra setup.

### Pool-Lost Recovery (NOT_FOUND)

When an OJP Server restarts it loses its in-memory datasource map.  The driver
detects this through the `Status.NOT_FOUND` gRPC status code:

1. Server throws `PoolNotFoundException` (no pool for the received `connHash`).
2. `CommandExecutionHelper` maps it to `Status.NOT_FOUND`.
3. The driver receives `NOT_FOUND`, invalidates the cached `connHash`, and
   calls a fresh `connect()` RPC to recreate the pool on the server.
4. The failed SQL call is retried once on the same server — transparent to
   the application.

This recovery is only applied to stateless (no active session UUID) calls.  If an
active transaction was in progress on the restarted server, the appropriate
`SQLException` is surfaced to the application so it can retry the business logic.

**Log messages to expect** (at `WARN` level):
```
NOT_FOUND from server proxy1.example.com:1059 – pool lost, reconnecting and retrying
Invalidated cached connHash <hash> (pool lost on server)
Re-cached connHash <hash> after reconnect
```

---

## Troubleshooting

### Common Issues

#### 1. Pool Exhaustion

**Symptom**:
```
SQLException: [XA-POOL-BORROW] POOL EXHAUSTED: maxTotal=11, active=11, idle=0, timeout=20000ms
```

**Causes**:
- Too many concurrent XA transactions
- Sessions not being returned (connection leak)
- maxTotal too low for workload

**Solutions**:
- Increase `ojp.xa.connection.pool.maxTotal`
- Check for connection leaks (sessions not closed in finally blocks)
- Review connection timeout settings

#### 2. "Session not opened" Errors

**Symptom**:
```
SQLException: Session not opened
```

**Cause**: This was a bug in OJP versions before dual-condition lifecycle fix (commit c5492f5)

**Fixed In**: Version with dual-condition session lifecycle
- Sessions no longer closed prematurely
- Pool only contains open, healthy sessions

#### 3. Connection Drop to Zero After Server Failure

**Symptom**: After server restarts, connection count drops to zero

**Causes**:
- Health check not detecting server recovery
- Pool not being re-initialized after recovery

**Fixed In**: Periodic health checker (every 5 seconds)
- Automatic recovery detection
- Pool rebalancing triggered on recovery

#### 4. Slow Failover

**Symptom**: Long delay before connections work after server failure

**Causes**:
- Pool rebalancing after borrowSession() (too late)
- Health check not propagating cluster state

**Fixed In**: Cluster health propagation during connect()
- Pool resized BEFORE borrowing session
- Immediate failover response

### Debug Logging

Enable comprehensive XA debugging:

```properties
# Core OJP logging
-Dorg.slf4j.simpleLogger.log.org.openjproxy=DEBUG

# Reduce ResultSet verbosity
-Dorg.slf4j.simpleLogger.log.org.openjproxy.jdbc.ResultSet=INFO
```

**Log Analysis**:
```
# Pool operations
grep "Session borrowed" server.log
grep "Session returned" server.log
grep "Pool resized" server.log

# Transaction lifecycle
grep "XA transaction" server.log
grep "transactionComplete" server.log

# Health checks
grep "Health check" server.log
grep "Server.*UP" server.log
```

### Health Checks

**Verify Pool State**:
```sql
-- PostgreSQL: Check active XA connections
SELECT * FROM pg_stat_activity WHERE backend_type = 'client backend';

-- Check prepared transactions (should be 0 after commit)
SELECT * FROM pg_prepared_xacts;
```

**Verify Session Counts**:
```java
// Enable SessionTracker logging
sessionTracker.getSessionCount(server)  // Should match pool.getNumActive()
```

---

## Migration Guide

### From Pass-Through XA (Old)

**Old Configuration** (deprecated, no longer supported):
```properties
ojp.xa.maxTransactions=50
ojp.xa.startTimeoutMillis=60000
```

**New Configuration**:
```properties
# Backend session pool configuration
ojp.xa.connection.pool.maxTotal=22
ojp.xa.connection.pool.minIdle=20
ojp.xa.connection.pool.connectionTimeout=20000
```

**Changes**:
- ❌ Removed: `ojp.xa.maxTransactions` (replaced by pool-based concurrency control)
- ❌ Removed: `ojp.xa.startTimeoutMillis` (no longer needed)
- ✅ Added: Full pool configuration for XA backend sessions
- ✅ Added: Dual-condition session lifecycle
- ✅ Added: Dynamic pool rebalancing

### Code Changes

**No client code changes required!**

XA application code remains identical:
```java
// Same code works with both old and new implementation
XADataSource xaDataSource = new OjpXADataSource(url, user, password);
XAConnection xaConnection = xaDataSource.getXAConnection();
XAResource xaResource = xaConnection.getXAResource();
Connection connection = xaConnection.getConnection();

// Execute XA transaction
xaResource.start(xid, XAResource.TMNOFLAGS);
connection.createStatement().execute("INSERT INTO...");
xaResource.end(xid, XAResource.TMSUCCESS);
xaResource.prepare(xid);
xaResource.commit(xid, false);

xaConnection.close();  // Now properly returns session to pool
```

### Verification

**Test Checklist**:
- [ ] XA transactions complete successfully
- [ ] Multiple sequential transactions on same XAConnection work
- [ ] Connections properly released (check pool metrics)
- [ ] Failover works (kill server, verify pool resizing)
- [ ] No connection leaks (monitor for 1+ hours)

---

## Related Documentation

- **[XA Transaction Flow](XA_TRANSACTION_FLOW.md)** - Detailed XA operation flow diagrams
- **[Multinode Architecture](multinode-architecture.md)** - Overall multinode design
- **[JDBC Configuration](../configuration/ojp-jdbc-configuration.md)** - Client-side configuration
- **[Adding XA Support](../guides/ADDING_DATABASE_XA_SUPPORT.md)** - Add XA for new databases

---

**Last Updated**: 2025-12-28  
**Implemented In**: Commit c5492f5 and later  
**Test Coverage**: MultinodeXAIntegrationTest (300+ queries, server failure/recovery cycles)
