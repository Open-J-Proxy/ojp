# XA Pool Implementation Analysis

**Date:** 2025-12-20  
**Author:** GitHub Copilot Analysis  
**Status:** Updated based on @rrobetti feedback

## Executive Summary

This document analyzes the feasibility of implementing XA-aware backend session pooling using Apache Commons Pool 2 in the OJP proxy server, and how it integrates with the recently merged connection pool SPI.

### Key Findings (Updated)

1. **Current Architecture**: OJP recently introduced a pluggable ConnectionPoolProvider SPI that supports HikariCP and DBCP2
2. **XA vs Standard Pooling**: XA pooling has fundamentally different requirements that the current SPI doesn't address
3. **Apache Commons Pool 2**: Good fit for XA pooling but serves a different purpose than the DataSource-level SPI
4. **SPI Extension Required**: No - XA pooling and ConnectionPoolProvider SPI are orthogonal concerns
5. **Implementation Complexity**: Medium (reduced from High) - durability delegated to backend database
6. **Critical Simplification**: XA only supported on databases with native XA support (Oracle, PostgreSQL, SQL Server, DB2) - **no proxy-side durable store needed**

### Major Updates Based on Feedback

1. **Q1 - SQL Routing**: Extend StatementRequest proto to include optional Xid field (confirmed)
2. **Q2 - PREPARED Sessions**: Pin sessions, don't pool until commit/rollback (confirmed)
3. **Q4 - Multinode Recovery**: Use broadcast approach instead of shared durable store (new approach)
4. **C1 - User Need**: Confirmed that OJP users need distributed transactions
5. **C2 - Performance**: No durable writes needed - delegates to backend database (major simplification)
6. **Opinion 3 - Database XA**: XA ONLY supported on databases with native XA - no proxy durability layer needed (critical simplification)

---

## 1. Current Connection Pool SPI Architecture

### 1.1 Overview

The recently merged connection pool SPI (`ojp-datasource-api`) provides:

```java
public interface ConnectionPoolProvider {
    String id();
    DataSource createDataSource(PoolConfig config) throws SQLException;
    void closeDataSource(DataSource dataSource) throws Exception;
    Map<String, Object> getStatistics(DataSource dataSource);
    int getPriority();
    boolean isAvailable();
}
```

### 1.2 Current Implementations

- **HikariConnectionPoolProvider** (default, priority=100)
- **DbcpConnectionPoolProvider** (priority=10)

Both provide **DataSource** pooling (JDBC Connection objects).

### 1.3 Current XA Support

The server currently supports XA through:

1. **XADataSourceFactory**: Creates XADataSource instances per database type
2. **Session.isXA**: Flag indicating if session uses XA
3. **Direct XAResource delegation**: Operations forward to backend database's XAResource
4. **SessionManager**: Maps SessionInfo → Session → Connection/XAConnection
5. **StatementServiceImpl**: Implements XA RPC endpoints that delegate to Session.xaResource

**Key Limitation**: Current implementation delegates XA operations directly to the backend database's XAResource without proxy-side transaction management, state tracking, or connection pooling considerations.

---

## 2. XA vs Standard Connection Pooling

### 2.1 Fundamental Differences

| Aspect | Standard Pool (DataSource) | XA Pool (Proposed) |
|--------|---------------------------|-------------------|
| **Pool Unit** | Connection (java.sql.Connection) | BackendSession (wrapper around Connection) |
| **Lifecycle** | Borrow → Use → Return | Borrow on xaStart → Hold through prepare → Return on commit/rollback |
| **State Management** | Stateless per connection | Stateful per Xid (ACTIVE → ENDED → PREPARED → COMPLETED) |
| **Durability** | None required | Must persist PREPARED state |
| **Recovery** | Not applicable | Must support xaRecover() across restarts |
| **Binding** | Connection per request | 1:1 Xid ↔ BackendSession binding |
| **Release Timing** | After each request/transaction | Only after final commit/rollback |

### 2.2 Why Apache Commons Pool 2?

**Strengths for XA use case:**
- Generic pooling of arbitrary objects (not just JDBC)
- Built-in validation, eviction, and resource lifecycle management
- Well-tested, mature library
- Supports blocking borrow with timeout
- Factory pattern fits BackendSession abstraction

**Limitations:**
- Does NOT provide XA state machine or Xid binding
- Does NOT provide durability or recovery
- Does NOT enforce XA lifecycle invariants

**Verdict**: Commons Pool 2 is appropriate for pooling BackendSession objects, but **all XA-specific logic must be layered on top**.

---

## 3. Architecture Analysis: Two-Layer Design

### 3.1 Proposed Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ConnectionPoolProvider SPI                │
│  (DataSource pooling for non-XA connections)                │
└─────────────────────────────────────────────────────────────┘
                              │
                    Standard JDBC Operations
                              │
┌─────────────────────────────────────────────────────────────┐
│              XA Transaction Registry Layer                   │
│  - XidKey → TxContext mapping                               │
│  - XA state machine enforcement                             │
│  - Durable prepared store                                   │
│  - Xid ↔ BackendSession binding                            │
└─────────────────────────────────────────────────────────────┘
                              │
                       XA Operations
                              │
┌─────────────────────────────────────────────────────────────┐
│          BackendSessionPool (Commons Pool 2)                │
│  - Pool generic BackendSession objects                      │
│  - Validation, eviction, lifecycle                          │
│  - Does NOT understand XA semantics                         │
└─────────────────────────────────────────────────────────────┘
                              │
                       Borrow/Return
                              │
┌─────────────────────────────────────────────────────────────┐
│                    BackendSession                            │
│  - Wraps Connection or XAConnection                         │
│  - open(), close(), isHealthy(), reset()                    │
│  - Database-agnostic abstraction                            │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Key Insight: Two Different Concerns

1. **ConnectionPoolProvider SPI**: High-level DataSource pooling for standard JDBC
2. **XA Pool Layer**: Low-level session pooling with XA lifecycle awareness

These are **complementary, not conflicting**.

---

## 4. Do We Need to Extend the ConnectionPoolProvider SPI?

### 4.1 Analysis

**Question**: Should ConnectionPoolProvider support XA?

**Answer**: **No, but XA support should coexist alongside it.**

**Rationale**:

1. **Different Abstraction Levels**
   - ConnectionPoolProvider works at DataSource level (javax.sql.DataSource)
   - XA pooling works at BackendSession level (custom abstraction)

2. **Different Use Cases**
   - ConnectionPoolProvider: For standard JDBC operations (autocommit, local transactions)
   - XA Pool: For distributed transactions requiring 2PC

3. **Separation of Concerns**
   - DataSource pooling is well-solved by HikariCP/DBCP2
   - XA session management requires custom logic that doesn't fit DataSource model

### 4.2 Recommended Approach

**Create a parallel XA-specific abstraction**:

```java
// New interface in ojp-server module (not datasource-api)
public interface XASessionPool {
    BackendSession borrowForXid(XidKey xid) throws PoolExhaustedException;
    void returnSession(BackendSession session, XidKey xid);
    void invalidateSession(BackendSession session);
    Map<String, Object> getStatistics();
}

// Implementation using Commons Pool 2
public class CommonsPool2XASessionPool implements XASessionPool {
    private final GenericObjectPool<BackendSession> pool;
    // ...
}
```

**Do NOT extend ConnectionPoolProvider** because:
- XA sessions have different lifecycle than DataSources
- Mixing concerns would pollute the clean SPI
- XA is proxy-specific, SPI should remain generic

---

## 5. Implementation Design

### 5.1 Core Components

#### 5.1.1 XidKey (Immutable Identifier)

```java
public final class XidKey {
    private final int formatId;
    private final byte[] gtrid;
    private final byte[] bqual;
    
    // Stable equals/hashCode using Arrays
    // Compact toString() for logging
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/XidKey.java`

**Note**: Can wrap existing `XidImpl` or create new. Preference: new class for clarity.

#### 5.1.2 TxState Enum

```java
public enum TxState {
    NONEXISTENT,    // Before start
    ACTIVE,         // After start, work in progress
    ENDED,          // After end (success/fail/suspend)
    PREPARED,       // After prepare (XA_OK)
    COMMITTED,      // After commit
    ROLLEDBACK,     // After rollback
    HEURISTIC_MIXED // Optional
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/TxState.java`

#### 5.1.3 TxContext (State Holder)

```java
public class TxContext {
    private final XidKey xid;
    private TxState state;
    private BackendSession session; // null until allocated
    private long createdAtNanos;
    private long lastAccessNanos;
    private final Object lock = new Object();
    
    // Thread-safe state transitions
    public void transitionTo(TxState newState) throws XAException {
        synchronized (lock) {
            validateTransition(state, newState);
            this.state = newState;
        }
    }
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/TxContext.java`

#### 5.1.4 BackendSession (Pooled Object)

```java
public interface BackendSession {
    void open() throws SQLException;
    void close() throws SQLException;
    boolean isHealthy();
    void reset() throws SQLException; // Clear session state
    
    Connection getConnection();
    XAResource getXAResource(); // if XA
    
    String getSessionId();
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/BackendSession.java`

**Implementation Note**: 
- Wraps existing `Session` class or creates new abstraction
- `reset()` must rollback any open transaction, clear temp tables, reset variables

#### 5.1.5 BackendSessionFactory (Commons Pool 2)

```java
public class BackendSessionFactory implements PooledObjectFactory<BackendSession> {
    
    @Override
    public PooledObject<BackendSession> makeObject() throws Exception {
        BackendSession session = createNewSession();
        session.open();
        return new DefaultPooledObject<>(session);
    }
    
    @Override
    public void destroyObject(PooledObject<BackendSession> p) throws Exception {
        p.getObject().close();
    }
    
    @Override
    public boolean validateObject(PooledObject<BackendSession> p) {
        return p.getObject().isHealthy();
    }
    
    @Override
    public void activateObject(PooledObject<BackendSession> p) throws Exception {
        // No-op or refresh session if needed
    }
    
    @Override
    public void passivateObject(PooledObject<BackendSession> p) throws Exception {
        try {
            p.getObject().reset();
        } catch (Exception e) {
            // Mark for invalidation
            throw e;
        }
    }
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/BackendSessionFactory.java`

#### 5.1.6 XATransactionRegistry (Orchestrator)

```java
public class XATransactionRegistry {
    private final ConcurrentHashMap<XidKey, TxContext> contexts;
    private final BackendSessionPool pool;
    private final DurablePreparedStore preparedStore;
    
    public void xaStart(XidKey xid, int flags) throws XAException {
        // Allocate session on TMNOFLAGS
        // Validate TMJOIN/TMRESUME
        // Update state
    }
    
    public void xaEnd(XidKey xid, int flags) throws XAException {
        // Transition ACTIVE → ENDED
        // Do NOT return session
    }
    
    public int xaPrepare(XidKey xid) throws XAException {
        // Transition ENDED → PREPARED
        // Write to durable store BEFORE returning XA_OK
        // Handle XA_RDONLY optimization
    }
    
    public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
        // Idempotent: check if already COMMITTED
        // Perform commit on backend session
        // Clear durable store
        // Return session to pool
        // Remove TxContext
    }
    
    public void xaRollback(XidKey xid) throws XAException {
        // Similar to commit but rollback
        // Return session to pool
        // Remove TxContext
    }
    
    public List<XidKey> xaRecover(int flags) throws XAException {
        return preparedStore.listPrepared();
    }
}
```

**Location**: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/XATransactionRegistry.java`

#### 5.1.7 DurablePreparedStore (Interface)

```java
public interface DurablePreparedStore {
    void recordPrepared(XidKey xid, PreparedRecord record) throws IOException;
    void clearPrepared(XidKey xid) throws IOException;
    List<XidKey> listPrepared() throws IOException;
    Optional<PreparedRecord> load(XidKey xid) throws IOException;
}

public class PreparedRecord {
    private final XidKey xid;
    private final long timestamp;
    private final String backendSessionId; // Optional
    private final byte[] metadata; // Optional
}
```

**Location**: 
- Interface: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/DurablePreparedStore.java`
- Record: `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/PreparedRecord.java`

**Implementation Options**:
1. **File-based append-only log** (simplest, MVP)
2. **RocksDB embedded KV store** (production-ready)
3. **H2 embedded SQL database** (already a dependency)

**Recommended for MVP**: File-based with JSON or simple binary format.

### 5.2 State Machine Enforcement

#### 5.2.1 Valid Transitions

```
NONEXISTENT → ACTIVE (xaStart TMNOFLAGS)
ACTIVE → ENDED (xaEnd)
ACTIVE → ACTIVE (xaStart TMJOIN from same thread/session)
ENDED → ACTIVE (xaStart TMRESUME)
ENDED → PREPARED (xaPrepare → XA_OK)
ENDED → COMMITTED (xaCommit onePhase=true)
ENDED → ROLLEDBACK (xaRollback)
PREPARED → COMMITTED (xaCommit onePhase=false)
PREPARED → ROLLEDBACK (xaRollback)
ACTIVE → ROLLEDBACK (xaRollback direct)
```

#### 5.2.2 Error Codes

- **XAER_NOTA**: Unknown Xid (not in contexts map)
- **XAER_PROTO**: Invalid transition (e.g., prepare before end)
- **XAER_RMERR**: Internal error (pool exhausted, IO error)
- **XAER_RMFAIL**: Database connection failure

### 5.3 Integration with Existing Code

#### 5.3.1 StatementServiceImpl Modifications

**Current**: Directly delegates to `session.getXaResource()`

**Proposed**: Delegate to `XATransactionRegistry`

```java
@Override
public void xaStart(XaStartRequest request, StreamObserver<XaResponse> responseObserver) {
    try {
        XidKey xid = XidKey.from(request.getXid());
        xaRegistry.xaStart(xid, request.getFlags());
        
        responseObserver.onNext(XaResponse.newBuilder()
            .setSuccess(true)
            .build());
        responseObserver.onCompleted();
    } catch (XAException e) {
        // Handle error
    }
}
```

#### 5.3.2 SessionManager Interaction

**Question**: How does SQL routing work with XA?

**Current Approach**: 
- SessionInfo contains sessionUUID
- SessionManager maps sessionUUID → Session
- Session holds Connection

**Proposed Approach**:
- SessionInfo needs to carry XidKey (or Xid proto)
- SQL requests include Xid in request
- XATransactionRegistry.getSessionForXid(xid) → BackendSession
- Route SQL to that specific session

**Concern**: Protocol change required to add Xid to StatementRequest?

**Investigation Needed**: Check if StatementRequest already has transaction context field.

---

## 6. Questions and Concerns

### 6.1 Critical Questions

#### Q1: How does SQL routing associate with Xid?
**Current**: SessionInfo identifies the session  
**Issue**: XA requires SQL to execute on the session bound to the Xid  
**Proposal**: Extend StatementRequest to include optional Xid field  
**Alternative**: Use SessionInfo as proxy - map SessionInfo → active Xid  

**Decision**: Extend StatementRequest to include optional Xid field (confirmed by @rrobetti)

#### Q2: What happens on session health check failure during PREPARED?
**Issue**: If backend session dies while in PREPARED state, can't re-execute prepare on new session  
**Options**:
1. Fail-fast: Mark transaction for heuristic rollback
2. Re-prepare: Requires prepared transaction bridging to database XA (complex)
3. Pin session: Don't pool PREPARED sessions, keep until commit/rollback

**Decision**: Pin session - Don't pool PREPARED sessions, keep until commit/rollback (confirmed by @rrobetti)

#### Q3: Should BackendSession wrap Connection or be Connection?
**Current**: Session class already exists and is connection-centric  
**Issue**: BackendSession is a new abstraction  
**Options**:
1. Wrap existing Session class
2. Create new BackendSession interface, Session implements it
3. Rename/refactor Session → BackendSession

**Decision**: Option 1 (wrap) for minimal disruption, or Option 2 for cleaner design (confirmed by @rrobetti)

#### Q4: How to handle multinode XA?
**Current**: OJP supports multinode deployments  
**Issue**: XA PREPARED state must be visible to all nodes for recovery  

**Original Approach**: DurablePreparedStore must be shared (e.g., shared file system, database table, Redis)

**Alternative Approach (suggested by @rrobetti)**: Broadcast xaRecover to all OJP nodes; the node holding the session acts on it while others ignore it.

**Analysis of Broadcast Approach**:
- **Pros**: 
  - No shared durable store required
  - Simpler deployment (no shared file system/database dependency)
  - Each node maintains its own in-memory state
- **Cons**:
  - Node holding PREPARED session must stay alive (no failover)
  - If node crashes during PREPARED state, transaction is lost (heuristic outcome required)
  - Requires broadcast mechanism (gRPC broadcast or multicast)
- **Implications**:
  - Acceptable for MVP if we document that PREPARED transactions on crashed nodes require manual resolution
  - Recovery coordinator (TM) must broadcast xaRecover() to all known OJP nodes
  - Each node responds with its local PREPARED Xids
  - Requires node discovery/registry mechanism

**Recommended Approach for MVP**: 
1. Single-node deployment with in-memory state (Phase 1)
2. Add broadcast-based recovery for multinode (Phase 2)
3. Optional shared durable store for strict durability (Phase 3+)

**Decision**: Start with single-node, add broadcast recovery for multinode in Phase 2. This eliminates the need for a shared durable store for most use cases.

#### Q5: Does reset() break database-native XA?
**Issue**: If backend database supports XA, reset() might clear XA state  
**Concern**: Calling reset() on a PREPARED session could cause inconsistency  
**Mitigation**: Do NOT call reset() on sessions in PREPARED state; only on COMMIT/ROLLBACK  

**Decision**: Document invariant - reset() only allowed when returning to pool (post-completion). Confirmed by @rrobetti.

### 6.2 Design Concerns

#### C1: Complexity vs Benefit
**Concern**: Implementing full XA with durability is complex  
**Question**: Do OJP users actually need distributed transactions?  
**Answer**: Yes (confirmed by @rrobetti)
**Mitigation**: 
- Implement in phases (MVP without durability, then add recovery)
- Make feature opt-in (xa.enabled=false by default)
- Provide clear documentation on XA requirements and limitations

#### C2: Performance Impact
**Concern**: Durable writes on xaPrepare will add latency  
**Impact**: ~1-10ms per prepare depending on storage  

**Clarification from @rrobetti**: "What kind of durable are you talking about? Does the broadcast approach (Q4) help here?"

**Answer**: 
- **Original durability concern**: Writing PREPARED state to disk/database before returning XA_OK from xaPrepare()
- **With broadcast approach**: Durability not required for multinode recovery! Each node maintains in-memory PREPARED state. On xaRecover(), TM broadcasts to all nodes, each responds with its local PREPARED Xids.
- **Implication**: If we adopt the broadcast approach (Q4), we can eliminate durable storage entirely for MVP and even Phase 2
- **Trade-off**: PREPARED transactions are lost if node crashes (requires manual heuristic resolution)

**Revised Mitigation**:
- **MVP/Phase 2**: No durable storage, use broadcast recovery (as per Q4 solution)
- **Phase 3+ (optional)**: Add optional durable storage for strict durability guarantees
- This significantly reduces latency concern - no disk writes required

#### C3: Connection Pool Starvation
**Concern**: Long-lived XA transactions hold sessions longer than regular transactions  
**Impact**: May exhaust pool if many concurrent XA transactions  
**Mitigation**:
- Separate pool for XA sessions (recommended)
- Higher maxPoolSize for XA workloads
- Timeout enforcement (xaSetTransactionTimeout)
- Monitoring and alerting

#### C4: Testing Complexity
**Concern**: XA testing requires transaction managers (Narayana, Atomikos)  
**Challenge**: Integration test setup complexity  
**Mitigation**:
- Unit tests for state machine (no real DB)
- Manual harness for XA operations (no TM)
- Optional integration tests with Narayana (testcontainers)

### 6.3 Opinions and Recommendations

#### Opinion 1: Start Simple
**Recommendation**: Implement MVP without full durability first
- In-memory TxContext tracking
- No durable prepared store
- Single-node only
- Document limitations
- Add durability in Phase 2 (optional, not required for broadcast recovery)

**Rationale**: Proves architecture, unblocks testing, reduces initial complexity.

**Clarification from @rrobetti**: "Agreed, but I need more info on why durability is required."

**Answer**: 
- **Original rationale**: Durability ensures PREPARED transactions survive proxy crashes, as required by XA spec for strict correctness
- **With broadcast approach (Q4)**: Durability is NOT required for multinode deployments. xaRecover() broadcasts to all nodes, each responds with in-memory PREPARED state.
- **Trade-off**: If a node crashes while holding PREPARED transactions, those transactions are lost and require manual heuristic resolution by DBA/operator
- **Practical impact**: This is acceptable for most use cases. XA is typically used for short-lived transactions (seconds to minutes), not long-running transactions (hours/days)
- **When durability IS needed**: Mission-critical financial transactions or other scenarios where manual resolution is unacceptable
- **Conclusion**: Make durability optional (Phase 3+), not mandatory. Most users won't need it with broadcast recovery.

#### Opinion 2: Separate XA Pool from Standard Pool
**Recommendation**: Use separate Commons Pool 2 instance for XA sessions
- Different pool size configuration
- Different eviction policies
- Different validation (XA-aware health checks)
- Clearer metrics and monitoring

**Rationale**: XA sessions have different lifecycle needs.

**Question from @rrobetti**: "I need more information on this to have an opinion. Document what are the extra XA session needs and why it makes sense to have a separated pool for XA sessions."

**Answer - Extra XA Session Needs**:

1. **Longer Hold Times**: XA sessions are bound to Xid from xaStart() through xaPrepare() to xaCommit/Rollback(). This can span multiple client requests and potentially minutes. Standard connections are returned to pool after each request (seconds).

2. **Cannot Be Evicted While PREPARED**: Once in PREPARED state, XA session MUST NOT be evicted from pool or have its connection closed. Standard connections can be evicted when idle.

3. **Different Validation Logic**: XA sessions need to validate that:
   - Database XAResource is still functional
   - PREPARED transaction state is intact (if backend supports XA)
   - Standard connections only validate basic connectivity (e.g., SELECT 1)

4. **Pinning Requirement**: PREPARED sessions must be "pinned" (not returned to pool) until commit/rollback completes. Standard connections never pinned.

5. **Size Planning**: 
   - Standard pool: Sized for concurrent active requests (typically 10-50)
   - XA pool: Must accommodate concurrent *transactions* which may span many requests (potentially 100s if many concurrent distributed transactions)

6. **Different Leak Detection**: 
   - Standard: Leak = connection not returned after request completes
   - XA: Leak = Xid in ACTIVE/ENDED state for too long without prepare/commit

**Why Separate Pools Make Sense**:
- **Resource Isolation**: XA workload doesn't starve standard JDBC operations
- **Separate Monitoring**: Track XA-specific metrics (prepared count, transaction duration) without mixing with standard connection stats
- **Different Tuning**: Operators can tune XA pool (larger, longer timeouts) independently from standard pool
- **Clearer Alerting**: "XA pool exhausted" is different problem than "standard pool exhausted"

**Alternative (Single Pool)**:
- Could use single pool with careful management
- More complex: need to track which connections are XA-bound vs available for standard JDBC
- Risk of XA transactions exhausting pool, blocking standard JDBC

**Conclusion**: Separate pools recommended but not strictly required. Provides operational clarity and resource isolation at cost of slightly more configuration.

#### Opinion 3: Leverage Database XA When Available
**Recommendation**: When backend supports XA (Oracle, PostgreSQL, SQL Server), delegate prepare/commit to database
- Simpler than proxy-side transaction log
- Leverages database durability
- xaRecover maps directly to backend XA recovery

**Rationale**: Reduces proxy complexity, improves reliability.

**Question from @rrobetti**: "XA will be only used with databases that support XA, does it mean we do not need to manage durability?"

**Answer**: **YES! This is a critical simplification.**

**Implications**:
- Since XA will ONLY be used with databases that support XA (Oracle, PostgreSQL, SQL Server, DB2, etc.), we can **delegate all XA durability to the backend database**
- The proxy does NOT need its own durable prepared store
- xaPrepare() → delegates to backend's XAResource.prepare() → backend persists PREPARED state in its transaction log
- xaRecover() → delegates to backend's XAResource.recover() → backend returns PREPARED Xids from its transaction log
- Proxy only needs in-memory state tracking (XidKey → BackendSession mapping)

**What the Proxy Manages**:
- In-memory TxContext state machine (ACTIVE → ENDED → PREPARED → COMMITTED)
- Xid ↔ BackendSession binding
- Connection pooling and lifecycle
- Protocol translation (gRPC ↔ XA)

**What the Database Manages**:
- PREPARED transaction durability
- Recovery after crash
- Two-phase commit protocol completion

**This Resolves Multiple Concerns**:
1. **C2 (Performance)**: No proxy-side disk writes needed!
2. **Q4 (Multinode)**: No shared durable store needed! Each node can query backend XAResource.recover()
3. **Opinion 1 (Durability)**: Not required at proxy level!

**Revised Architecture**: 
- Proxy = stateless coordinator (except in-memory runtime state)
- Database = durable transaction log
- xaRecover(): Proxy asks backend DB for PREPARED Xids, not its own store

**Caveat from @rrobetti**: "Databases that don't support XA will not be permitted to use XA."

**Action**: Document this as a requirement. OJP XA feature requires backend database with native XA support. This eliminates complexity significantly.

#### Opinion 4: Make XA Opt-In
**Recommendation**: XA pooling should be explicitly enabled via configuration
```properties
ojp.xa.enabled=true
ojp.xa.pool.maxTotal=20
ojp.xa.pool.maxWait=5000
# No durable store needed - delegates to backend database
```

**Rationale**: 
- Most users don't need XA
- Adds complexity and resource overhead
- Clear opt-in documents requirements

**Confirmed by @rrobetti**: Agreed.

---

## 7. Proposed Implementation Plan

### Phase 1: Core Infrastructure (MVP)
**Goal**: Basic XA pooling with in-memory state, delegating durability to backend database

1. Create XidKey, TxState, TxContext classes
2. Create BackendSession interface wrapping existing Session
3. Implement BackendSessionFactory with Commons Pool 2
4. Create XATransactionRegistry with in-memory state tracking
5. Modify StatementServiceImpl to delegate to registry
6. Extend StatementRequest proto to include optional Xid field
7. Implement xaStart/xaEnd/xaPrepare/xaCommit/xaRollback (delegate to backend XAResource)
8. Implement xaRecover (delegate to backend XAResource.recover())
9. Unit tests for state machine
10. Manual integration test harness

**Deliverables**: 
- Basic XA operations work with single OJP node
- All durability delegated to backend database (no proxy-side durable store)
- PREPARED state tracked in-memory only

**Duration**: 2-3 weeks

### Phase 2: Multinode Support with Broadcast Recovery
**Goal**: XA recovery works across multiple OJP nodes

1. Implement broadcast mechanism for xaRecover() 
   - TM broadcasts xaRecover() to all known OJP nodes
   - Each node queries its backend XAResource.recover()
   - Each node responds with its local PREPARED Xids
2. Add node discovery/registry mechanism
3. Handle node failures gracefully
4. Multinode recovery integration tests

**Deliverables**: 
- XA recovery works in multinode deployments
- No shared durable store required
- Trade-off: PREPARED transactions lost if node crashes (documented limitation)

**Duration**: 1-2 weeks

### Phase 3: Production Hardening
**Goal**: Production-ready XA support

1. Add comprehensive metrics and monitoring
2. Add timeout enforcement and leak detection  
3. Connection pool starvation handling
4. Performance testing and tuning
5. Documentation and runbooks
6. (Optional) Add proxy-side durable store for mission-critical use cases

**Deliverables**: Production-ready XA feature

**Duration**: 2-3 weeks

### Phase 4: Integration Testing
**Goal**: Validate with real transaction managers

1. Narayana integration tests
2. Atomikos integration tests (if needed)
3. Multi-database XA tests
4. Failure injection and chaos testing
5. Performance benchmarking

**Deliverables**: Validated against real TM implementations

**Duration**: 1-2 weeks

**Total Duration**: 6-10 weeks (reduced from 8-10 weeks due to simplified architecture)

---

## 8. Alternatives Considered

### Alternative 1: Extend HikariCP to Support XA Lifecycle
**Approach**: Subclass HikariCP or create XA-aware wrapper

**Pros**:
- Reuse HikariCP infrastructure
- Less code to write

**Cons**:
- HikariCP not designed for XA lifecycle
- State machine logic awkward to layer on top
- Tight coupling to HikariCP implementation details
- Connection vs BackendSession impedance mismatch

**Verdict**: Not recommended. Mixing concerns leads to fragility.

### Alternative 2: No Pooling, One Session Per Xid
**Approach**: Create new session per xaStart, close on commit/rollback

**Pros**:
- Simplest to implement
- No pool management complexity

**Cons**:
- Poor performance (connection creation overhead)
- Doesn't scale with high XA throughput
- Doesn't leverage proxy pooling benefits

**Verdict**: Not viable for production use cases.

### Alternative 3: Use Existing DataSource Pool for XA
**Approach**: Get Connection from HikariCP, hold it for XA duration

**Pros**:
- Reuses existing infrastructure

**Cons**:
- DataSource pool not aware of XA lifecycle
- Connection held across multiple requests (violates pool assumptions)
- Eviction and validation happen at wrong times
- Difficult to implement PREPARED durability

**Verdict**: Doesn't align with HikariCP's design model.

---

## 9. Dependencies

### New Dependencies Required

```xml
<!-- Apache Commons Pool 2 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
    <version>1.0.0-RC1</version>
</dependency>

<!-- For testing: Narayana -->
<dependency>
    <groupId>org.jboss.narayana.jta</groupId>
    <artifactId>narayana-jta</artifactId>
    <version>7.0.2.Final</version>
    <scope>test</scope>
</dependency>
```

**Note**: RocksDB dependency removed - not needed as durability is delegated to backend database.

### Existing Dependencies to Leverage

- `jakarta.transaction-api` (already present for XA interfaces)
- `slf4j-api` (logging)
- Existing protobuf definitions (XaStartRequest, etc.)
- Backend database XA support (Oracle, PostgreSQL, SQL Server, DB2)

---

## 10. Risks and Mitigations

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| State machine bugs lead to resource leaks | High | Medium | Comprehensive unit tests, leak detection monitoring |
| Connection pool exhaustion under XA load | Medium | Medium | Separate XA pool, monitoring, timeout enforcement |
| Performance degradation | Low | Low | No durable writes needed (delegates to DB), benchmarking |
| Complex multinode recovery | Medium | Low | Broadcast-based recovery (simpler than shared store) |
| Database-specific XA quirks | Medium | High | Per-database testing, vendor documentation, fallback logic |
| Node crashes during PREPARED | Medium | Low | Document manual resolution procedure, make rare with proper monitoring |

---

## 11. Success Criteria

### Must Have (MVP)
- [ ] XA state machine correctly enforces valid transitions
- [ ] BackendSession pooling with Commons Pool 2 works
- [ ] xaStart/xaEnd/xaPrepare/xaCommit/xaRollback functional (delegate to backend XAResource)
- [ ] SQL routes to correct session for Xid (extend StatementRequest proto)
- [ ] Unit tests achieve >90% coverage of state machine
- [ ] Manual integration test with mock TM passes
- [ ] xaRecover delegates to backend database XAResource.recover()

### Should Have (Phase 2)
- [ ] Multinode broadcast-based recovery implemented
- [ ] Integration test with Narayana passes
- [ ] Metrics expose pool stats and XA operation counts
- [ ] Documentation covers configuration and limitations
- [ ] Document requirement: Backend database must support XA

### Nice to Have (Phase 3+)
- [ ] Optional proxy-side durable store for mission-critical use cases
- [ ] Performance benchmarks show <5% overhead vs non-XA
- [ ] Chaos engineering tests validate recovery scenarios
- [ ] Automatic heuristic handling

---

## 12. Conclusion

### Summary

Implementing XA-aware backend session pooling is **feasible and valuable** for OJP, with **significantly simplified architecture** due to feedback:

1. **New XA-specific layer** using Apache Commons Pool 2 for BackendSession pooling
2. **No extension** to the ConnectionPoolProvider SPI (orthogonal concerns)
3. **Careful state machine** enforcement with XATransactionRegistry
4. **No proxy-side durable store needed** - delegates all durability to backend database XA support
5. **Phased implementation** starting with MVP (6-10 weeks total)

### Key Architectural Decisions (Updated)

**Apache Commons Pool 2 is appropriate for pooling BackendSession objects**, but:
- It does NOT provide XA state management (we build that)
- It does NOT provide durability (delegates to backend database)
- It DOES provide solid pooling infrastructure (validation, eviction, lifecycle)

**The ConnectionPoolProvider SPI should NOT be extended for XA** because:
- XA operates at a different abstraction level (BackendSession vs DataSource)
- Mixing concerns would pollute the clean SPI design
- XA is proxy-specific, SPI is generic infrastructure

**Durability Delegated to Backend Database**:
- XA will ONLY be used with databases that support native XA (Oracle, PostgreSQL, SQL Server, DB2)
- Backend database persists PREPARED state in its transaction log
- Proxy only maintains in-memory state (XidKey → BackendSession mapping)
- xaRecover() delegates to backend's XAResource.recover()
- **No proxy-side durable store, no disk writes, no shared file system required**

**Multinode Recovery via Broadcast**:
- TM broadcasts xaRecover() to all OJP nodes
- Each node queries its backend XAResource.recover()
- Trade-off: If node crashes during PREPARED, manual resolution required
- Acceptable for most use cases (XA transactions are typically short-lived)
- Mixing concerns would pollute the clean SPI design
- XA is proxy-specific, SPI is generic infrastructure

### Recommended Path Forward

1. **Approve** this updated analysis and simplified architectural direction
2. **Implement** Phase 1 MVP (2-3 weeks) - in-memory state, delegate to backend DB
3. **Validate** with manual testing and unit tests
4. **Implement** Phase 2 (1-2 weeks) - multinode broadcast recovery
5. **Harden** to Phase 3 (2-3 weeks) - production-ready features
6. **Integrate** Phase 4 (1-2 weeks) - validate with Narayana/Atomikos

**Total Duration**: 6-10 weeks (reduced from original 8-10 weeks)

### Open Questions for Discussion

1. ~~Should we prioritize database-native XA delegation over proxy-side transaction log?~~ **RESOLVED: Yes, delegate to database (confirmed by @rrobetti)**
2. ~~What's the expected adoption rate for XA in OJP deployments?~~ **RESOLVED: Yes, users need XA (confirmed by @rrobetti)**
3. Should XA pooling be in a separate module (ojp-xa-pool) or in ojp-server? **TBD**
4. ~~Do we need multinode support in MVP or can it wait?~~ **RESOLVED: Phase 2 with broadcast recovery**
5. What monitoring/observability is most important for operators? **TBD**

---

## 11. Known Issues and Resolutions

### 11.1 Spring Boot + Narayana Integration Issue (December 2025)

**Issue**: XA transaction failures when integrating OJP with Spring Boot + Narayana Transaction Manager.

**Symptoms**:
- `XAException` with error code `XAER_INVAL` during `xaStart()` 
- PostgreSQL error: "tried to call end without corresponding start call. state=ENDED"
- "Connection has been closed" errors in subsequent transactions

**Root Causes Identified**:

1. **Missing XA Flag Routing**: The `handleXAStartWithPooling()` method unconditionally called `registerExistingSession()` which only accepts `TMNOFLAGS`. Transaction managers using `TMJOIN` or `TMRESUME` flags would fail.

2. **Missing Session Sanitization**: After commit/rollback, the XAConnection remained in ENDED state. Without sanitizing the connection between transactions, subsequent transactions would fail because the XA state was not reset to IDLE.

3. **Stale Connection References**: The OJP Session cached a connection reference that became stale after sanitization when the backend session obtained a fresh logical connection.

**Solutions Implemented**:

1. **Flag-Based Routing in `handleXAStartWithPooling()`**:
   ```java
   if (flags == XAResource.TMNOFLAGS) {
       // New transaction: use existing session from OJP Session
       registry.registerExistingSession(xidKey, backendSession, flags);
   } else if (flags == XAResource.TMJOIN || flags == XAResource.TMRESUME) {
       // Join or resume existing transaction
       registry.xaStart(xidKey, flags);
   }
   ```

2. **Session Sanitization via `sanitizeAfterTransaction()`**:
   - Added new method to `XABackendSession` interface
   - Calls `xaConnection.getConnection()` to obtain fresh logical connection
   - Per JDBC spec, this automatically closes previous logical connection and resets XA state to IDLE
   - Called after every `xaCommit()` and `xaRollback()` in `XATransactionRegistry`

3. **Dynamic Connection Retrieval in OJP Session**:
   - Modified `Session.getConnection()` to dynamically fetch from backend session for XA pooled sessions
   - Ensures fresh connection reference is always used after sanitization
   - Eliminates stale connection issues

**Technical Details**:

- **JDBC Spec Compliance**: Per JDBC 4.3 spec (section 12.4), calling `getConnection()` on an XAConnection must close any previous logical connection and return a new handle. This behavior is leveraged for session sanitization.

- **Session Lifecycle**: Sessions remain bound to OJP Session for multiple sequential transactions (correct behavior). They only return to pool when the client closes the connection. Sanitization happens BETWEEN transactions, not AT pool return.

- **XA State Reset**: The fresh logical connection from `xaConnection.getConnection()` has XA state reset to IDLE, allowing subsequent transactions to start cleanly.

**Integration Tests Added**:
- `testMultipleSequentialTransactionsWithSessionReuse()`: Validates 4 sequential transactions on same session
- `testTwoPhaseCommitWithSessionReuse()`: Validates 2PC followed by new transaction with session reuse

**Impact**: This fix enables OJP to work correctly with enterprise transaction managers like Narayana, Atomikos, and Bitronix that may use TMJOIN/TMRESUME flags for resource enlistment.

### 11.2 Future Considerations

**Connection Pool Behavior**: Different XA driver implementations may behave differently when `getConnection()` is called multiple times. Testing showed correct behavior with:
- PostgreSQL JDBC Driver
- Oracle JDBC Driver  
- SQL Server JDBC Driver (to be confirmed)

If issues arise with other drivers, consider:
- Driver-specific sanitization strategies
- Configuration flag to disable automatic sanitization
- Manual XA state validation before each transaction

---

## Appendix A: File Structure

```
ojp-server/src/main/java/org/openjproxy/grpc/server/
├── xa/
│   ├── XidKey.java
│   ├── TxState.java
│   ├── TxContext.java
│   ├── BackendSession.java
│   ├── BackendSessionImpl.java (wraps Session)
│   ├── BackendSessionFactory.java
│   ├── BackendSessionPool.java (Commons Pool 2 wrapper)
│   └── XATransactionRegistry.java
└── StatementServiceImpl.java (modified to use registry)

ojp-grpc-commons/src/main/proto/
└── StatementService.proto (extend StatementRequest with optional Xid field)

ojp-server/src/test/java/org/openjproxy/grpc/server/xa/
├── TxContextStateMachineTest.java
├── BackendSessionPoolTest.java
├── XATransactionRegistryTest.java
└── XAIntegrationTest.java (with Narayana)
```

**Note**: DurablePreparedStore and related files removed - not needed as durability delegated to backend database.

---

## Appendix B: Configuration Example

```properties
# OJP XA Configuration (ojp-server.properties)

# Enable XA support (default: false)
# REQUIREMENT: Backend database must support native XA (Oracle, PostgreSQL, SQL Server, DB2)
ojp.xa.enabled=true

# XA Backend Session Pool Settings
ojp.xa.pool.maxTotal=50
ojp.xa.pool.minIdle=5
ojp.xa.pool.maxWait=5000
ojp.xa.pool.testOnBorrow=true
ojp.xa.pool.testWhileIdle=true
ojp.xa.pool.timeBetweenEvictionRuns=30000

# XA Transaction Timeouts
ojp.xa.transaction.default-timeout=300  # seconds
ojp.xa.transaction.max-timeout=600

# Monitoring
ojp.xa.metrics.enabled=true
ojp.xa.leak-detection.threshold=60000  # milliseconds

# Multinode Recovery (Phase 2)
ojp.xa.multinode.broadcast.enabled=true
ojp.xa.multinode.node-discovery.method=static  # static, consul, etc.
```

**Note**: Durable prepared store configuration removed - not needed as durability delegated to backend database.

---

## Appendix C: Glossary

- **2PC**: Two-Phase Commit protocol
- **BackendSession**: Pooled object representing a database session
- **Commons Pool 2**: Apache library for generic object pooling
- **DataSource**: JDBC abstraction for connection factory
- **Durability**: Property that committed transactions survive crashes
- **Heuristic**: Decision made by RM when outcome unknown (e.g., timeout)
- **SPI**: Service Provider Interface - pluggable architecture pattern
- **TM**: Transaction Manager (e.g., Narayana, Atomikos)
- **XA**: X/Open XA standard for distributed transactions
- **Xid**: Transaction identifier in XA (formatId + gtrid + bqual)

---

**End of Analysis Document**
