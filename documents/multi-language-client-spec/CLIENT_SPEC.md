# OJP Multi-Language Client Specification

> **Status:** Draft — April 2026
> **Last updated:** 2026-05-20
> **Scope:** Defines every aspect that a new OJP client library (in any language) must implement to be fully compatible with an OJP server. Written language-agnostically; Java-specific concepts are labelled as reference implementation only.
> **Reference implementation:** `ojp-jdbc-driver` module.
> **Protocol source of truth:** `ojp-grpc-commons/src/main/proto/StatementService.proto` and `echo.proto`.
> **Machine-oriented companion:** [`CLIENT_SPEC_AI.md`](CLIENT_SPEC_AI.md)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Core Concepts](#2-core-concepts)
   - 2.1 [Virtual Connections](#21-virtual-connections)
   - 2.2 [Deferred Session Assignment](#22-deferred-session-assignment)
   - 2.3 [Session Affinity](#23-session-affinity)
   - 2.4 [Client vs. Server Responsibilities](#24-client-vs-server-responsibilities)
3. [Architecture and Data Flow](#3-architecture-and-data-flow)
   - 3.1 [gRPC Interface and Channel Setup](#31-grpc-interface-and-channel-setup)
   - 3.2 [Connection Configuration (ConnectionDetails)](#32-connection-configuration-connectiondetails)
   - 3.3 [Client Identity (clientUUID)](#33-client-identity-clientuuid)
   - 3.4 [Load Balancing](#34-load-balancing)
   - 3.5 [Cluster Health Propagation](#35-cluster-health-propagation)
4. [Client Responsibilities](#4-client-responsibilities)
   - 4.1 [Connection Establishment and connHash Caching](#41-connection-establishment-and-connhash-caching)
   - 4.2 [Session Lifecycle](#42-session-lifecycle)
   - 4.3 [Failover](#43-failover)
   - 4.4 [Health Checking](#44-health-checking)
   - 4.5 [Connection Redistribution on Recovery](#45-connection-redistribution-on-recovery)
5. [Minimal End-to-End Example](#5-minimal-end-to-end-example)
6. [Error Handling](#6-error-handling)
   - 6.1 [Error Classification](#61-error-classification)
   - 6.2 [SQL Errors vs. Transport Errors](#62-sql-errors-vs-transport-errors)
7. [Implementation Guidance](#7-implementation-guidance)
   - 7.1 [Statement Execution](#71-statement-execution)
   - 7.2 [Parameter Type Mapping](#72-parameter-type-mapping)
   - 7.3 [Temporal Type Handling](#73-temporal-type-handling)
   - 7.4 [Result Set Streaming](#74-result-set-streaming)
   - 7.5 [LOB Handling](#75-lob-large-object-handling)
   - 7.6 [Transaction Management (non-XA)](#76-transaction-management-non-xa)
   - 7.7 [Savepoints](#77-savepoints)
   - 7.8 [XA / Distributed Transactions](#78-xa--distributed-transactions)
   - 7.9 [callResource Protocol](#79-callresource-protocol)
   - 7.10 [Configuration System](#710-configuration-system)
   - 7.11 [Query Result Caching](#711-query-result-caching)
   - 7.12 [Security / Transport](#712-security--transport)
   - 7.13 [DataSource / Integration API](#713-datasource--integration-api)
   - 7.14 [Client-Side Throttling](#714-client-side-throttling)
8. [Testing Coverage](#8-testing-coverage)

---

## 1. Overview

OJP (Open J Proxy) is a JDBC Type 3 proxy. Its central idea is that real database connections are owned exclusively by the OJP server, which manages them in pluggable connection pools (HikariCP by default, replaceable via SPI). Client applications communicate with the server via gRPC rather than opening direct database connections.

```
[Application] ──native API──> [OJP Client Library] ──gRPC/HTTP2──> [OJP Server] ──JDBC──> [Database]
```

This architecture lets many application instances scale independently without overwhelming the database, because the proxy enforces a global connection limit.

A non-Java OJP client replaces the `ojp-jdbc-driver` module. It must implement all 21 `StatementService` RPCs plus `EchoService.Echo`, handle the `SessionInfo` propagation contract on every call, and manage endpoint health, failover, and session stickiness on the client side. The server handles everything else: real connection management, transaction state, LOB storage, cursor state, and query caching.

### Language Equivalents

Each target language has its own standard database-access API. When implementing an OJP client, map OJP's connection/statement/result-set concepts to that language's native API:

| Language | JDBC Equivalent / Standard API | Description |
| :--- | :--- | :--- |
| **Go** | `database/sql` | The standard Go package providing a generic interface for SQL-like databases. |
| **Python** | DB-API 2.0 (`pep-249`) | A standard specification followed by almost all Python database drivers (e.g., `psycopg2`, `sqlite3`). |
| **C# / .NET** | `ADO.NET` | The foundational data access technology for .NET, using standard classes like `DbConnection`. |
| **C++** | `ODBC` | The "Open Database Connectivity" standard used for cross-platform database-agnostic apps. |
| **Node.js** | Common Driver Patterns / ORMs | Relies on community standards or ORMs like Sequelize or Prisma rather than a single built-in API. |
| **Ruby** | `DBI` / `Active Record` | Uses the Database Interface (DBI) module or the Active Record abstraction layer. |
| **PHP** | `PDO` (PHP Data Objects) | A lightweight, consistent interface for accessing various databases in PHP. |
| **Dart** | `sql_conn` / `drift` | Uses specific community packages or the Drift library for structured data access. |

> **Important operational rule:** Application-side connection pools **must be disabled** when using OJP. Double-pooling causes incorrect behavior and resource waste. This is the single most common misconfiguration.

---

## 2. Core Concepts

Before diving into implementation details, understand these four foundational ideas. Everything else in this specification follows from them.

### 2.1 Virtual Connections

An OJP "connection" is not a real database connection. The real JDBC connections are held exclusively in the server's connection pool. What the client holds is a `SessionInfo` — a lightweight proto message containing a `connHash` (a pool identifier), the `clientUUID`, and (once assigned) a `sessionUUID`.

Opening a connection is cheap. For non-XA connections after the first one, the client can satisfy the `connect()` call entirely from a local cache: it looks up the `connHash` for the given database credentials and builds the `SessionInfo` locally without making any gRPC call. This means connection acquisition for cached credentials costs only a hash-map lookup.

Multiple client connections sharing the same database credentials share the same server-side pool through the same `connHash`. The server distributes real JDBC connections across all these logical client connections.

Because the server owns the real connections, application-side connection pools are redundant and harmful — they create a second pool that fights with the server's pool for real database connections.

### 2.2 Deferred Session Assignment

The `sessionUUID` field in `SessionInfo` is not assigned at connection time. It is assigned by the server on the first operation that requires a persistent server-side session — for example, `startTransaction()`, creating a LOB, or opening a scrollable cursor. Until the server assigns a `sessionUUID`, requests are effectively stateless: any server can handle them using any real connection from the appropriate pool.

This means that for simple read-only queries (no transactions, no LOBs), the client never receives a `sessionUUID` at all, and all requests can be freely routed to any healthy server.

### 2.3 Session Affinity

Once a `sessionUUID` is assigned, **every subsequent request for that session must go to the same server**. The server encodes the binding in `SessionInfo.targetServer` (`host:port`). The client must record this mapping and enforce it on every outgoing request.

Session affinity covers all of: open transactions, open LOB handles, open server-side cursors, and XA transaction branches. Rerouting any of these to a different server is a protocol error — the session state exists only on the original server and cannot be migrated.

If the bound server becomes unhealthy while a sticky session is open, the client must raise an error to the caller immediately. It must not silently reroute the request to another server.

### 2.4 Client vs. Server Responsibilities

**The server owns:** real JDBC connections and connection pool management (pool implementation is pluggable via SPI); transaction state; LOB storage; server-side cursor state; query result caching; slow-query slot management; pool resizing in response to cluster health changes.

**The client owns:** `SessionInfo` propagation (attach current `SessionInfo` to every request; replace with response); `connHash` caching; endpoint health tracking; load balancing; failover; cluster health string building and pushing to surviving servers; session stickiness enforcement (`sessionUUID → targetServer` binding); background health-check task; connection redistribution after server recovery; client-side throttling (reads `maxAdmission`, `clientCount`, and `observedPeak` from `SessionInfo` responses and limits the number of concurrent requests this process sends to each OJP node — see §7.14).

---

## 3. Architecture and Data Flow

### 3.1 gRPC Interface and Channel Setup

The client must implement stubs for every RPC in `StatementService` and `EchoService`.

**`StatementService` RPCs:**

| RPC | Type | Purpose |
|---|---|---|
| `connect` | unary | Open a logical connection and receive `SessionInfo` |
| `executeUpdate` | unary | DML (INSERT / UPDATE / DELETE / DDL) |
| `executeQuery` | server-streaming | SELECT — returns a stream of `OpResult` blocks |
| `fetchNextRows` | unary | Pull the next page of rows for an open result set |
| `createLob` | client-streaming | Upload LOB data to the server in chunks |
| `readLob` | server-streaming | Download LOB data from the server |
| `terminateSession` | unary | Release server-side session state |
| `startTransaction` | unary | Begin an explicit transaction |
| `commitTransaction` | unary | Commit the active transaction |
| `rollbackTransaction` | unary | Roll back the active transaction |
| `callResource` | unary | Generic remote call for metadata, cursor navigation, savepoints |
| `xaStart` | unary | Begin an XA transaction branch |
| `xaEnd` | unary | End an XA transaction branch |
| `xaPrepare` | unary | Prepare an XA transaction branch |
| `xaCommit` | unary | Commit an XA transaction branch |
| `xaRollback` | unary | Roll back an XA transaction branch |
| `xaRecover` | unary | List XIDs of prepared transactions |
| `xaForget` | unary | Forget a heuristically completed transaction |
| `xaSetTransactionTimeout` | unary | Set XA timeout in seconds |
| `xaGetTransactionTimeout` | unary | Get current XA timeout |
| `xaIsSameRM` | unary | Check whether two sessions share a resource manager |

**`EchoService` RPC:**

| RPC | Type | Purpose |
|---|---|---|
| `Echo` | unary | Lightweight heartbeat / connectivity check |

**gRPC channel lifecycle:**

- One `ManagedChannel` (or equivalent) per server endpoint. Channels are long-lived and shared across all logical connections to that endpoint.
- Channels are created lazily on first connection, or eagerly during initialisation when endpoints are known upfront.
- Use DNS-prefixed targets (`dns:///host:port`) where the gRPC runtime supports it.
- Blocking stubs for synchronous operations; async stubs required for client-streaming (`createLob`) and server-streaming (`executeQuery`, `readLob`) RPCs.
- Channel shutdown must be graceful (allow in-flight calls to complete) and triggered on client shutdown.

### Pseudo-code

```python
# Create one long-lived channel per OJP server endpoint
channel = grpc.create_channel("localhost:10591", credentials=grpc.local_channel_credentials())
stub    = StatementServiceStub(channel)   # used for all SQL operations
echo    = EchoServiceStub(channel)        # used for health heartbeats

# On process shutdown — drain in-flight calls before closing
channel.shutdown(grace_period_seconds=5)
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`StatementService`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementService.java): the unified interface declaring all RPC methods.
> - `ojp-jdbc-driver` — [`StatementServiceGrpcClient`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java): the single-node gRPC implementation; contains the concrete gRPC stub calls and the `grpcChannelOpenAndStubsInitialized()` channel lifecycle method.
> - `ojp-jdbc-driver` — [`MultinodeStatementService`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeStatementService.java): the multinode facade that wraps `StatementServiceGrpcClient` per endpoint with routing, failover, and stickiness.
> - `ojp-grpc-commons` — [`GrpcChannelFactory`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/GrpcChannelFactory.java): `createChannel(host, port)` — builds `ManagedChannel` instances with plaintext or TLS; handles the `dns:///` prefix and max inbound message size.

---

### 3.2 Connection Configuration (ConnectionDetails)

A non-Java OJP client does not use a JDBC URL. Instead, it collects the following configuration items directly from the user or from a configuration file:

| Item | Required | Description |
|---|---|---|
| OJP server endpoints | Yes | One or more `host:port` pairs for the OJP server(s). In multinode mode this is a list. |
| Datasource name | No | A logical name for this datasource, default `"default"`. |
| Database URL | Yes | The connection URL for the **real database** (e.g., `jdbc:postgresql://db:5432/mydb`). Sent verbatim to the server. |
| User | Yes | Database username. |
| Password | Yes | Database password. |
| Properties | No | Additional key-value configuration pairs (pool sizing, cache rules, etc. — see §7.10, §7.11). |

Map the collected configuration to the `ConnectionDetails` proto fields as follows:

| Proto field | Type | Value |
|---|---|---|
| `url` | `string` | The **actual database URL**. The server uses this to create the real database connection pool. |
| `user` | `string` | Database username. |
| `password` | `string` | Database password. |
| `clientUUID` | `string` | Stable process UUID (see §3.3). |
| `properties` | `repeated PropertyEntry` | Configuration key-value pairs; include `ojp.datasource.name = <datasourceName>` when using a named datasource. |
| `serverEndpoints` | `repeated string` | All OJP server addresses as `"host:port"` strings (the full cluster list). |
| `clusterHealth` | `string` | Current cluster health string (see §3.5); empty string on the very first connect. |
| `isXA` | `bool` | `true` for XA connections, `false` otherwise. |

> **Important:** the `url` field must be consistent across all client processes that connect to the same logical datasource. The server computes `connHash` as SHA-256(`url + user + password + datasource_name`). Inconsistent `url` strings cause separate pools to be created.

**`connHash` cache key (client side):** `url + "|" + user + "|" + password + "|" + datasource_name`

> **Reference implementation:**
> - `ojp-grpc-commons` — [`ConnectionDetails` proto](../../ojp-grpc-commons/src/main/proto/StatementService.proto): field definitions.
> - `ojp-server` — [`ConnectionHashGenerator.hashConnectionDetails()`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/utils/ConnectionHashGenerator.java): SHA-256 of `url + user + password + datasource_name_from_properties`.
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.computeConnectionKey()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): client-side cache key computation.
> - `ojp-jdbc-driver` — [`MultinodeUrlParser`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeUrlParser.java): Java reference for JDBC URL parsing (Java-specific; not needed in non-Java clients).

---

### 3.3 Client Identity (clientUUID)

Generate one random UUID (version 4) when the client library is first loaded or when the process starts. This UUID must remain stable for the entire lifetime of the process. Attach `clientUUID` to every `ConnectionDetails` message sent to the server. Do not persist `clientUUID` across process restarts.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`ClientUUID`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientUUID.java): `getUUID()` returns the static, process-scoped UUID generated once at class-loading time via `UUID.randomUUID()`.

---

### 3.4 Load Balancing

Two strategies must be supported, selectable via configuration (see §7.10, property `ojp.loadaware.selection.enabled`):

**Least-connections (default, `true`):** Select the healthy server with the lowest number of active sessions. Track session counts in a thread-safe counter per server endpoint. Use round-robin as a tie-breaker when all servers have equal counts.

**Round-robin (`false`):** Cycle through healthy servers in order using an atomic counter modulo the number of healthy servers.

Server selection runs on every new connection attempt (non-XA, first `connect()`) and on every XA `connect()`. Once a session is assigned a server (via session stickiness), selection does not run again for that session. Only servers whose `isHealthy() == true` are eligible. If no healthy servers exist, raise a connection error.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.selectHealthyServer()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): dispatches to one of the two strategies based on config.
> - `MultinodeConnectionManager.selectByLeastConnections(healthyServers)`: picks the server with the lowest active-session count; falls back to round-robin on a tie.
> - `MultinodeConnectionManager.selectByRoundRobin(healthyServers)`: atomically increments `roundRobinCounter` and selects `servers[counter % size]`.
> - `ojp-jdbc-driver` — [`ServerEndpoint`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/ServerEndpoint.java): holds `isHealthy`, `lastFailureTime`, host, and port state for each endpoint.

---

### 3.5 Cluster Health Propagation

**Cluster health string format:**

```
host1:port1(UP);host2:port2(DOWN);host3:port3(UP)
```

Each semicolon-separated segment is `host:port(STATUS)` where status is `UP` or `DOWN`.

**Client responsibilities:**

- **Build** the cluster health string from local server endpoint health state before every `connect()` call and before every operation that carries a `SessionInfo`.
- **Consume** the cluster health string returned in `SessionInfo.clusterHealth` on every response. Update local endpoint health states accordingly.
- **Proactively push** the updated cluster health to all currently healthy servers whenever the topology changes. Two independent triggers — both are necessary:

  **Trigger 1 — health-check thread**: When `performHealthCheck()` detects a newly failed or recovered server, it calls `pushClusterHealthToAllHealthyServers()` inline on the health-check thread.

  **Trigger 2 — query thread**: When a SQL query thread detects server failure via `handleServerFailure()`, it submits `pushClusterHealthToAllHealthyServers()` to the background scheduler asynchronously (to avoid blocking the query thread).

  The push is done by calling `connect()` on each healthy server with a `ConnectionDetails` whose `clusterHealth` field contains the new topology string.

### Pseudo-code

```python
def build_cluster_health(endpoints):
    return ";".join(
        f"{ep.host}:{ep.port}({'UP' if ep.is_healthy else 'DOWN'})"
        for ep in endpoints
    )

def push_cluster_health(endpoints, stored_details):
    if not stored_details:
        return   # no connections yet
    health_str = build_cluster_health(endpoints)
    for conn_hash, details in stored_details.items():
        push_req = ConnectionDetails(**details, clusterHealth=health_str)
        for ep in endpoints:
            if ep.is_healthy:
                stubs[ep].connect(push_req)

def consume_cluster_health(session_info):
    for segment in session_info.clusterHealth.split(";"):
        host_port, status = segment.rsplit("(", 1)
        status = status.rstrip(")")
        endpoint = find_endpoint(host_port)
        if status == "DOWN" and endpoint.is_healthy:
            handle_server_failure(endpoint)
        # UP: do not mark healthy here — let the health-check thread confirm
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.generateClusterHealth()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): builds the semicolon-delimited health string.
> - `MultinodeConnectionManager.pushClusterHealthToAllHealthyServers()`: calls `connect()` on every healthy server; only runs when `!connectionDetailsByConnHash.isEmpty()`.
> - `MultinodeStatementService.withClusterHealth(session)`: enriches outgoing `SessionInfo` with the current cluster health string before each RPC.

---

## 4. Client Responsibilities

### 4.1 Connection Establishment and connHash Caching

**First connection (cache miss):**

1. Build a `ConnectionDetails` message (see §3.2 for field mapping).
2. Call `connect(ConnectionDetails)` on the chosen server. Receive `SessionInfo`.
3. Cache the returned `connHash`, keyed on `url + "|" + user + "|" + password + "|" + datasourceName`. Also store the full `ConnectionDetails` for NOT_FOUND recovery.
4. Return the received `SessionInfo` to the caller.

**Subsequent connections (cache hit, non-XA only):**

1. Look up `connHash` from the local cache by the connection key.
2. Build a `SessionInfo` locally without making any gRPC call: `{connHash, clientUUID, isXA: false}`.
3. Return this locally-built `SessionInfo`. No `sessionUUID` is set yet.

XA connections always call the server — caching is disabled for XA.

**Cache invalidation (NOT_FOUND recovery):**

When any gRPC call returns `Status.NOT_FOUND`:
1. Remove the cached `connHash` entry (but keep the stored `ConnectionDetails`).
2. Re-issue a real `connect()` RPC using the stored `ConnectionDetails`.
3. Cache the new `connHash` returned.
4. Retry the original failed operation once with the new `SessionInfo`.
5. This retry is only safe if the original request had no active `sessionUUID`. If a session was in progress, surface the error to the caller — the transaction state is permanently lost.

### Pseudo-code

```python
# First connection (cache miss)
req = ConnectionDetails(
    url             = "jdbc:postgresql://db:5432/mydb",
    user            = "alice",
    password        = "secret",
    clientUUID      = CLIENT_UUID,
    serverEndpoints = ["host1:10591", "host2:10591"],
    clusterHealth   = build_cluster_health(endpoints),   # "" on very first call
    isXA            = False,
    properties      = [PropertyEntry(key="ojp.datasource.name", value="default")]
)
session = stub.connect(req)
cache_key = f"{req.url}|{req.user}|{req.password}|default"
connhash_cache[cache_key] = session.connHash
stored_details[session.connHash] = req   # kept for NOT_FOUND recovery

# Subsequent connection (cache hit, non-XA)
session = SessionInfo(
    connHash   = connhash_cache[cache_key],
    clientUUID = CLIENT_UUID,
    isXA       = False
    # sessionUUID is absent; assigned lazily by server on startTransaction
)

# NOT_FOUND recovery
del connhash_cache[cache_key]
session = stub.connect(stored_details[old_conn_hash])
connhash_cache[cache_key] = session.connHash
# then retry the original failed RPC once
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.connect()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): orchestrates first-connect vs. cache-hit logic.
> - `MultinodeConnectionManager.computeConnectionKey()`: builds the `url|user|password|datasourceName` cache key.
> - `MultinodeConnectionManager.invalidateConnHash()`: removes the stale key from `connHashByConnectionKey` on `NOT_FOUND`.
> - `MultinodeConnectionManager.reconnectForConnHash()`: re-issues the real `connect()` RPC using stored `ConnectionDetails` and updates the cache.
> - `MultinodeConnectionManager.buildLocalSessionInfo()`: constructs the in-memory `SessionInfo` for cache-hit connections without an RPC call.

---

### 4.2 Session Lifecycle

**SessionInfo fields:**

| Field | Type | Meaning |
|---|---|---|
| `connHash` | string | Server-side key identifying which connection pool to use |
| `clientUUID` | string | Client process identity (see §3.3) |
| `sessionUUID` | string | Server-side session handle; set once a session is established |
| `transactionInfo` | `TransactionInfo` | Contains `transactionUUID` and `transactionStatus` (`TRX_ACTIVE`, `TRX_COMMITED`, `TRX_ROLLBACK`) |
| `sessionStatus` | `SessionStatus` | `SESSION_ACTIVE` or `SESSION_TERMINATED` |
| `isXA` | bool | Whether this is an XA session |
| `targetServer` | string | `host:port` of the server this session is pinned to |
| `clusterHealth` | string | Current cluster health snapshot from the server's perspective |
| `clientCount` | int32 | Number of distinct JVM/process clients connected to this `connHash` on this node |
| `maxAdmission` | int32 | Total connection pool slots on this node (the server's full capacity) |
| `observedPeak` | int32 | Peak concurrent load the server handled before its last admission timeout; `0` = no timeout observed yet (use `maxAdmission` instead) |

**Lifecycle rules:**

- Always propagate the **latest** `SessionInfo` on every outgoing request. The server updates and returns it in every response; the client must replace its local copy with the one returned.
- When the response contains a `sessionUUID` that was absent in the request, register it immediately: record the binding `sessionUUID → response.targetServer`.
- On connection close: call `terminateSession(SessionInfo)`. This is mandatory for releasing server-side resources.
- If `sessionStatus == SESSION_TERMINATED` is received, treat the connection as closed.

**Session affinity enforcement:**

- Maintain a thread-safe map of `sessionUUID → host:port`.
- On each request: if `sessionUUID` is set in the local `SessionInfo`, look up the bound server. Route the request to that server only.
- If the bound server is currently marked unhealthy: **raise an error to the caller** — do not silently reroute.
- When a session is closed (`terminateSession`), remove the binding from the map and decrement the session count for that server.

### Pseudo-code

```python
# Every gRPC call returns an updated SessionInfo — always replace the local copy
resp = stub.executeUpdate(StatementRequest(session=current_session, sql="..."))
current_session = resp.session   # update after every call

# When a new sessionUUID appears in the response, record the server binding
if resp.session.sessionUUID and resp.session.sessionUUID != current_session.sessionUUID:
    bind_session(resp.session.sessionUUID, resp.session.targetServer)

# Close a connection — release server-side state
stub.terminateSession(current_session)
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`Connection`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java): holds the mutable `session` field; `close()` calls `terminateSession(session)`; `checkValid()` guards every method against a closed connection.
> - `ojp-jdbc-driver` — [`MultinodeStatementService.withClusterHealth()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeStatementService.java): enriches outgoing `SessionInfo` with the current cluster health string before each RPC.
> - `MultinodeStatementService.checkAndBindSession()`: updates the stickiness map whenever the server returns a new or changed `sessionUUID`.
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.affinityServer(sessionKey)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): returns the bound server for a `sessionUUID`; throws `SQLException` if the bound server is unhealthy.
> - `MultinodeConnectionManager.bindSession(sessionUUID, targetServer)`: records the `sessionUUID → host:port` mapping in `sessionToServerMap`.
> - `ojp-jdbc-driver` — [`SessionTracker`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/SessionTracker.java): maintains per-server session counts used by the load-balancer and redistribution logic.

---

### 4.3 Failover

**What triggers failover:**

| Status code | Trigger failover? |
|---|---|
| `UNAVAILABLE` | Yes |
| `DEADLINE_EXCEEDED` | Yes |
| `UNKNOWN` (with "connection" in message) | Yes |
| `INTERNAL` with SQL metadata trailers | **No** — database-level error |
| `INTERNAL` without SQL metadata trailers | Yes — transport-level failure |
| `NOT_FOUND` | **No** — triggers reconnect (see §4.1) |
| `RESOURCE_EXHAUSTED` (pool exhaustion) | **No** — surface to caller |
| `CANCELLED` | **No** — client-initiated cancellation; must never mark a server unhealthy |
| Any `SQLException` from server | **No** |

**Failover procedure:**

1. Capture whether the server was previously healthy (`wasHealthy`).
2. Mark the server unhealthy (`isHealthy = false`), recording the failure timestamp.
3. Log the failure.
4. If `wasHealthy == true`, submit `pushClusterHealthToAllHealthyServers()` asynchronously to the background scheduler (to avoid blocking the query thread).
5. Shut down the gRPC channel for the failed server gracefully.
6. Select the next healthy server (using the configured strategy, excluding already-attempted servers).
7. Retry the operation on the new server.
8. If all servers have been attempted and all failed, raise a connection error to the caller.
9. Retry attempts and delay are configurable (see §7.10, `ojp.multinode.retry.attempts` and `ojp.multinode.retry.delay`).

**What must NOT trigger failover:** database errors, pool exhaustion, session-invalidation errors — surface all directly to caller.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`GrpcExceptionHandler.isConnectionLevelError()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/GrpcExceptionHandler.java): classifies a `StatusRuntimeException` as a connectivity failure vs. a SQL/business error. `CANCELLED` is explicitly **excluded**.
> - `GrpcExceptionHandler.isPoolNotFoundException()`: returns `true` for `NOT_FOUND`, triggering reconnect rather than failover.
> - `GrpcExceptionHandler.isSessionInvalidationError()`: returns `true` when the server indicates the session is gone.
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.handleServerFailure(endpoint, exception)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): marks the server unhealthy, timestamps the failure, shuts down the gRPC channel gracefully, and — only on a genuine healthy→unhealthy transition — submits `pushClusterHealthToAllHealthyServers()` to the background `healthCheckScheduler`.
> - `MultinodeStatementService.executeOpResultWithSessionStickinessAndBinding()`: the retry loop that drives the server-selection retry cycle and calls `handleServerFailure` on each failed attempt.

---

### 4.4 Health Checking

Run a periodic background task that checks server health at a configurable fixed interval (property `ojp.health.check.interval`, default 5 000 ms). The task must not block the main execution thread and must be a daemon task.

**Two-phase check:**

**Phase 1 — probe healthy servers (detect newly failed servers):**
Run when there are active XA sessions (`sessionToServerMap` is non-empty) **or** cached non-XA connection details (`connectionDetailsByConnHash` is non-empty). This dual guard ensures both XA and non-XA workloads trigger early failure detection. For each currently healthy server that passes the guard, send a probe call. If the call fails, mark the server unhealthy and call the server-failure handler.

**Phase 2 — probe unhealthy servers (detect recovery):**
For each currently unhealthy server, check if enough time has passed since the last failure (property `ojp.health.check.threshold`, default 5 000 ms). If so, probe the server. If the probe succeeds, run recovery (see §4.5).

**Health probe modes:**

| Mode | How to probe | When to use |
|---|---|---|
| Heartbeat (lightweight) | Send `connect()` with empty `url`, `user`, `password` — any response means transport is up | Default |
| Full validation | Send `connect()` with real credentials; on success, call `terminateSession` on the returned session | When heartbeat is insufficient |

**Configurable properties:**

| Property | Default | Meaning |
|---|---|---|
| `ojp.health.check.interval` | 5000 ms | How often the check runs |
| `ojp.health.check.threshold` | 5000 ms | How long to wait before re-probing an unhealthy server |
| `ojp.health.check.timeout` | 5000 ms | Maximum time for a single probe call |
| `ojp.redistribution.enabled` | `true` | Whether to run the periodic health checker at all |

### Pseudo-code

```python
def heartbeat_probe(stub):
    try:
        stub.connect(ConnectionDetails(url="", user="", password=""))
        return True
    except grpc.RpcError:
        return False

def run_health_check(endpoints, stubs, stored_details):
    for ep in endpoints:
        if ep.is_healthy:
            if stored_details or xa_sessions:   # guard: skip if no connections yet
                if not heartbeat_probe(stubs[ep]):
                    handle_server_failure(ep)
                    push_cluster_health_async(endpoints, stored_details)
        else:
            if time_since(ep.last_failure) >= HEALTH_CHECK_THRESHOLD:
                if heartbeat_probe(stubs[ep]):
                    reinitialize_pool_on_recovered_server(ep, stored_details)  # §4.5
                    ep.mark_healthy()
                    push_cluster_health_inline(endpoints, stored_details)
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.performHealthCheck()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): the scheduled task body; implements the two-phase check. Phase 1 fires when `!sessionToServerMap.isEmpty() || !connectionDetailsByConnHash.isEmpty()`. Phase 2 calls `reinitializePoolOnRecoveredServer()` before `markHealthy()`, then pushes cluster health.
> - `ojp-jdbc-driver` — [`HealthCheckValidator.validateServer(endpoint)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/HealthCheckValidator.java): performs a single lightweight probe; `validateServer(endpoint, connectionDetails)` performs the full-validation probe.
> - `ojp-jdbc-driver` — [`HealthCheckConfig`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/HealthCheckConfig.java): POJO holding `healthCheckIntervalMs`, `healthCheckThresholdMs`, `healthCheckTimeoutMs`, and `redistributionEnabled`.

---

### 4.5 Connection Redistribution on Recovery

When a failed server comes back online, rebalance client-side connections so that the recovered server receives its fair share of traffic again.

**Procedure on recovery:**

1. **Reinitialize pools on the recovered server first** (before marking healthy). For every cached `connHash`/`ConnectionDetails` pair, call `connect()` on the recovered server so it pre-warms the connection pool immediately. This closes the NOT_FOUND window between marking the server healthy and the first SQL call reaching it.
2. Mark the server healthy (`endpoint.markHealthy()`).
3. Push the updated cluster health string to all healthy servers (see §3.5).
4. If redistribution is enabled (`ojp.redistribution.enabled = true`), begin rebalancing:
   - Determine the ideal share: `totalConnections / numberOfHealthyServers`.
   - Identify over-loaded servers (connections > ideal share).
   - Close a fraction of idle connections on over-loaded servers; the client's load-balancing layer will route re-opens to the least-loaded server.
   - Honour `ojp.redistribution.idleRebalanceFraction` (default 1.0) and `ojp.redistribution.maxClosePerRecovery` (default 100).

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`MultinodeConnectionManager.reinitializePoolOnRecoveredServer(recoveredServer)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java): runs only when `!connectionDetailsByConnHash.isEmpty()`; always called **before** `endpoint.markHealthy()`.
> - `ojp-jdbc-driver` — [`ConnectionRedistributor.rebalance(recoveredServers, allHealthyServers)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/ConnectionRedistributor.java): closes a fraction of idle connections on over-loaded servers for non-XA mode.
> - `ojp-jdbc-driver` — [`XAConnectionRedistributor.rebalance(recoveredServers, allHealthyServers)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/XAConnectionRedistributor.java): equivalent redistribution for XA connections.
> - `ojp-jdbc-driver` — [`ConnectionTracker`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/ConnectionTracker.java): maintains the per-server `Connection` list consulted by `ConnectionRedistributor`.

---

## 5. Minimal End-to-End Example

The following self-contained pseudo-code covers the full lifecycle of a typical OJP client interaction: channel setup, connection (with caching), a SELECT query, a transaction with DML, and graceful shutdown.

```python
import grpc, uuid

CLIENT_UUID    = str(uuid.uuid4())
connhash_cache = {}   # url|user|password|dsname -> connHash
stored_details = {}   # connHash -> ConnectionDetails (for NOT_FOUND recovery)

channel = grpc.create_channel("ojp-server:10591",
                               credentials=grpc.local_channel_credentials())
stub = StatementServiceStub(channel)

# Open a connection (first call -> cache miss -> real RPC)
req = ConnectionDetails(
    url             = "jdbc:postgresql://db:5432/mydb",
    user            = "alice",
    password        = "secret",
    clientUUID      = CLIENT_UUID,
    serverEndpoints = ["ojp-server:10591"],
    clusterHealth   = "",   # empty on very first connect
    isXA            = False,
    properties      = [PropertyEntry(key="ojp.datasource.name", value="default")]
)
session = stub.connect(req)   # single RPC; subsequent connects use the cache
cache_key = "jdbc:postgresql://db:5432/mydb|alice|secret|default"
connhash_cache[cache_key] = session.connHash
stored_details[session.connHash] = req

# Execute a SELECT query
result_set_uuid = None
labels = []
rows   = []
for op_result in stub.executeQuery(StatementRequest(
        session       = session,
        sql           = "SELECT id, name FROM orders WHERE customer = ?",
        parameters    = [ParameterProto(index=1, type=PT_STRING,
                                        values=[ParameterValue(string_value="alice")])],
        statementUUID = str(uuid.uuid4()))):
    qr = op_result.query_result
    if result_set_uuid is None:
        result_set_uuid = qr.resultSetUUID
        labels = qr.labels
    rows.extend(qr.rows)
    session = op_result.session    # always update session after every RPC

stub.callResource(CallResourceRequest(
    session      = session,
    resourceType = RES_RESULT_SET,
    resourceUUID = result_set_uuid,
    target       = TargetCall(callType=CALL_CLOSE)
))

# Execute a transaction
session = stub.startTransaction(session)
# session.sessionUUID is now assigned; all subsequent calls go to session.targetServer

resp = stub.executeUpdate(StatementRequest(
    session       = session,
    sql           = "INSERT INTO orders(customer, amount) VALUES(?, ?)",
    parameters    = [
        ParameterProto(index=1, type=PT_STRING, values=[ParameterValue(string_value="alice")]),
        ParameterProto(index=2, type=PT_INT,    values=[ParameterValue(int_value=99)])
    ],
    statementUUID = str(uuid.uuid4())
))
session       = resp.session
rows_affected = resp.value.int_value   # e.g., 1

session = stub.commitTransaction(session)

# Close the connection and shut down
stub.terminateSession(session)
channel.shutdown(grace_period_seconds=5)
```

---

## 6. Error Handling

### 6.1 Error Classification

| Condition | gRPC status | Client action |
|---|---|---|
| SQL error (bad query, constraint, etc.) | `INTERNAL` + `SqlErrorResponse` trailer | Throw SQL exception; do not retry; do not mark server unhealthy |
| Pool not found (server restarted) | `NOT_FOUND` | Invalidate connHash cache; reconnect; retry once (§4.1) |
| Server unreachable | `UNAVAILABLE` | Failover to next server (§4.3) |
| Request timeout | `DEADLINE_EXCEEDED` | Failover to next server (§4.3) |
| Client-side cancellation | `CANCELLED` | Do **not** failover; do **not** mark server unhealthy; surface to caller |
| Pool exhausted | `RESOURCE_EXHAUSTED` | Throw pool-exhaustion error; do not retry; do not mark server unhealthy |
| Session invalidated (server failure) | Session-not-found message | Throw session-lost error; do not retry; let caller decide |
| Session stickiness violation (server down) | Local check before RPC | Throw connection error immediately; do not reroute |

### 6.2 SQL Errors vs. Transport Errors

When the server encounters a SQL error, it returns `Status.INTERNAL` with a `SqlErrorResponse` message attached to the trailing metadata. Extract it using the proto message key for `SqlErrorResponse`.

```
SqlErrorResponse {
    reason:       string
    sqlState:     string        // ANSI SQL state code
    vendorCode:   int32         // database-specific error code
    sqlErrorType: SqlErrorType  // SQL_EXCEPTION or SQL_DATA_EXCEPTION
}
```

Map to the host language's exception hierarchy: `SQL_EXCEPTION` -> standard SQL exception; `SQL_DATA_EXCEPTION` -> data-specific SQL exception (subtype).

> **Note:** Before April 2026, the server incorrectly used `Status.CANCELLED` for SQL errors. The correct status is `Status.INTERNAL` with a `SqlErrorResponse` trailer. Any implementation must use `INTERNAL` for SQL errors and must not treat `CANCELLED` as a server failure.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`GrpcExceptionHandler.handle(StatusRuntimeException)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/GrpcExceptionHandler.java): extracts `SqlErrorResponse` from gRPC trailing metadata on `Status.INTERNAL`.
> - `GrpcExceptionHandler.isPoolNotFoundException(exception)`: returns `true` for `NOT_FOUND`.
> - `GrpcExceptionHandler.isSessionInvalidationError(exception)`: returns `true` for session-invalidation error messages.
> - `GrpcExceptionHandler.isConnectionLevelError(exception)`: returns `true` for `UNAVAILABLE`, `DEADLINE_EXCEEDED`, and connection-related `UNKNOWN` errors.

---

## 7. Implementation Guidance

### 7.1 Statement Execution

All SQL is executed by populating a `StatementRequest` and calling either `executeUpdate` or `executeQuery` on the stub.

**Parameterless SQL:** Set `sql` to the full query string and leave `parameters` empty.

**Parameterized SQL:** Set `sql` with `?` positional placeholders and populate the `parameters` list with one `ParameterProto` per `?`. Assign a `statementUUID` (a random UUID per logical prepared-statement instance).

**Stored-procedure calls:** First call `callResource` with `CallType.CALL_PREPARE` to register the procedure on the server and receive a `resourceUUID`. Then call `callResource` with `CallType.CALL_EXECUTE` to run it, passing IN parameters and retrieving OUT/INOUT values from `CallResourceResponse.values`.

**StatementRequest structure:**

```
StatementRequest {
    session:       SessionInfo
    sql:           string
    parameters:    ParameterProto[]
    statementUUID: string
    properties:    PropertyEntry[]
}
```

**Execution routing:** Use `executeUpdate` for INSERT / UPDATE / DELETE / DDL (returns affected row count in `value.int_value`). Use `executeQuery` for SELECT. Always update the local `SessionInfo` from `OpResult.session`.

### Pseudo-code

```python
# DML
resp = stub.executeUpdate(StatementRequest(
    session       = session,
    sql           = "INSERT INTO orders(customer, amount) VALUES(?, ?)",
    parameters    = [
        ParameterProto(index=1, type=PT_STRING, values=[ParameterValue(string_value="Alice")]),
        ParameterProto(index=2, type=PT_INT,    values=[ParameterValue(int_value=42)])
    ],
    statementUUID = new_uuid()
))
session       = resp.session
rows_affected = resp.value.int_value

# Query
for op_result in stub.executeQuery(StatementRequest(
        session=session, sql="SELECT id, name FROM orders",
        statementUUID=new_uuid())):
    for row in op_result.query_result.rows:
        id_val   = row.values[0].int_value
        name_val = row.values[1].string_value
    session = op_result.session

# Stored procedure
prep_resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_CALLABLE_STATEMENT,
    target=TargetCall(callType=CALL_PREPARE, resourceName="{call my_proc(?,?)}",
                      params=[ParameterValue(int_value=1)])
))
exec_resp = stub.callResource(CallResourceRequest(
    session=prep_resp.session, resourceType=RES_CALLABLE_STATEMENT,
    resourceUUID=prep_resp.resourceUUID,
    target=TargetCall(callType=CALL_EXECUTE)
))
out_value = exec_resp.values[0]
session   = exec_resp.session
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`Statement`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java): `executeQuery(sql)` and `executeUpdate(sql)` delegate to `statementService`; holds `statementUUID` assigned lazily.
> - `ojp-jdbc-driver` — [`PreparedStatement`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/PreparedStatement.java): accumulates parameters in a `SortedMap<Integer, Parameter>`; all 28 `setXxx(index, value)` methods map to the corresponding `ParameterType` (see §7.2).
> - `ojp-jdbc-driver` — [`CallableStatement`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/CallableStatement.java): issues `callResource(CALL_PREPARE)` on construction; retrieves OUT/INOUT values via `callResource(CALL_EXECUTE)` after execution.

---

### 7.2 Parameter Type Mapping

Each parameter is represented as:

```
ParameterProto {
    index:  int32
    type:   ParameterTypeProto
    values: ParameterValue[]
}
```

**ParameterTypeProto values and their ParameterValue encoding:**

| Proto enum value | Wire field in `ParameterValue` | Notes |
|---|---|---|
| `PT_NULL` | `is_null = true` | Explicit null |
| `PT_BOOLEAN` | `bool_value` | |
| `PT_BYTE` | `int_value` | Clamp to byte range |
| `PT_SHORT` | `int_value` | Clamp to short range |
| `PT_INT` | `int_value` | |
| `PT_LONG` | `long_value` | |
| `PT_FLOAT` | `float_value` | |
| `PT_DOUBLE` | `double_value` | |
| `PT_BIG_DECIMAL` | `string_value` | Encode as `"<unscaledInteger> <scale>"` — see §7.2.1 |
| `PT_STRING` | `string_value` | |
| `PT_BYTES` | `bytes_value` | Raw bytes |
| `PT_DATE` | `date_value` | `google.type.Date` (year/month/day, no timezone) |
| `PT_TIME` | `time_value` | `google.type.TimeOfDay` (hours/minutes/seconds/nanos) |
| `PT_TIMESTAMP` | `timestamp_value` | `TimestampWithZone` — see §7.3 |
| `PT_ASCII_STREAM` | `bytes_value` | ASCII bytes |
| `PT_UNICODE_STREAM` | `bytes_value` | Unicode bytes |
| `PT_BINARY_STREAM` | `bytes_value` | Binary bytes |
| `PT_OBJECT` | varies | Best-effort mapping to one of the concrete value types |
| `PT_CHARACTER_READER` | `string_value` | Contents of the character stream |
| `PT_REF` | `string_value` | REF value as string |
| `PT_BLOB` | (LOB reference UUID) | Create LOB first (§7.5); then pass UUID as `string_value` |
| `PT_CLOB` | (LOB reference UUID) | Same as BLOB |
| `PT_ARRAY` | `int_array_value` / `long_array_value` / `string_array_value` | Use the typed array message matching element type |
| `PT_URL` | `url_value` (StringValue) | `URL.toExternalForm()` — presence-aware; unset = null |
| `PT_ROW_ID` | `rowid_value` (StringValue) | Base64-encoded bytes of the RowId — presence-aware |
| `PT_N_STRING` | `string_value` | Same wire format as PT_STRING |
| `PT_N_CHARACTER_STREAM` | `string_value` | Contents of the NCharacter stream |
| `PT_N_CLOB` | (LOB reference UUID) | Same as CLOB |
| `PT_SQL_XML` | `string_value` | XML content as string |

#### 7.2.1 BigDecimal encoding

BigDecimal is serialised as a space-separated string: `"<unscaledInteger> <scale>"`. Example: `BigDecimal("123.45")` yields `"12345 2"`.

> **Note:** A separate binary wire format is documented in `documents/protocol/BIGDECIMAL_WIRE_FORMAT.md` for contexts where binary efficiency is needed.

#### 7.2.2 Presence-aware fields

`url_value`, `rowid_value`, `uuid_value`, `biginteger_value`, `rowidlifetime_value` are all `google.protobuf.StringValue` (a wrapper message). An absent (unset) wrapper means SQL NULL. An empty string inside the wrapper is a valid non-null value.

> **Reference implementation:**
> - `ojp-grpc-commons` — [`ProtoConverter.toProto(Parameter)`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/ProtoConverter.java): converts a host-language `Parameter` object to `ParameterProto`; `fromProto(ParameterProto)` is the inverse.
> - `ProtoConverter.toParameterValue(Object value)`: the central dispatcher that routes each Java type to the correct `ParameterValue` oneof field.
> - `ProtoConverter.fromParameterValue(ParameterValue, ParameterType)`: decodes a wire value back to a Java object.
> - `ojp-grpc-commons` — [`ProtoTypeConverters`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/ProtoTypeConverters.java): `uuidToProto(UUID)` / `uuidFromProto(StringValue)`, `urlToProto(URL)` / `urlFromProto(StringValue)`, `rowIdToProto(RowId)` / `rowIdBytesFromProto(StringValue)`.
> - `ojp-grpc-commons` — [`BigDecimalWire`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/BigDecimalWire.java): `writeBigDecimal` / `readBigDecimal` — binary wire encoding for BigDecimal.

---

### 7.3 Temporal Type Handling

Timestamps are transmitted as:

```
TimestampWithZone {
    instant:       google.protobuf.Timestamp  // seconds + nanos since Unix epoch (UTC)
    timezone:      string                      // IANA zone ID or UTC offset (e.g., "Europe/Rome", "+02:00")
    original_type: TemporalType               // preserves the caller's original type
}
```

**TemporalType enum:**

| Value | Original type |
|---|---|
| `TEMPORAL_TYPE_UNSPECIFIED` | Default / unknown |
| `TEMPORAL_TYPE_TIMESTAMP` | `java.sql.Timestamp` |
| `TEMPORAL_TYPE_CALENDAR` | `java.util.Calendar` |
| `TEMPORAL_TYPE_OFFSET_DATE_TIME` | `java.time.OffsetDateTime` |
| `TEMPORAL_TYPE_LOCAL_DATE_TIME` | `java.time.LocalDateTime` |
| `TEMPORAL_TYPE_INSTANT` | `java.time.Instant` |
| `TEMPORAL_TYPE_LOCAL_DATE` | `java.time.LocalDate` |
| `TEMPORAL_TYPE_LOCAL_TIME` | `java.time.LocalTime` |
| `TEMPORAL_TYPE_OFFSET_TIME` | `java.time.OffsetTime` |

**Encoding rules:** (1) Convert the host-language datetime value to a UTC instant (seconds + nanoseconds since Unix epoch). (2) Record the IANA timezone or UTC offset string. (3) Set `original_type` to the closest matching `TemporalType` enum value.

**Decoding rules:** Use `original_type` to reconstruct the correct host-language type. `TEMPORAL_TYPE_LOCAL_DATE` uses `google.type.Date`. `TEMPORAL_TYPE_LOCAL_TIME` uses `google.type.TimeOfDay`.

The OJP server must always run with `user.timezone=UTC`. Client libraries should also normalise to UTC when encoding timestamps.

> **Reference implementation:**
> - `ojp-grpc-commons` — [`TemporalConverter`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/TemporalConverter.java): the definitive encoding/decoding reference for all temporal types including `toTimestampWithZone`, `fromTimestampWithZone`, `calendarToTimestampWithZone`, `offsetDateTimeToTimestampWithZone`, `localDateTimeToTimestampWithZone`, `instantToTimestampWithZone`, `localDateToProtoDate`, `localTimeToProtoTimeOfDay`, `offsetTimeToTimestampWithZone`, and `fromTimestampWithZoneToObject` (the unified decoder).

---

### 7.4 Result Set Streaming

`executeQuery` is a server-streaming RPC. The response stream contains one or more `OpResult` messages:

1. **First `OpResult`**: contains the initial data batch in `query_result`: `resultSetUUID`, `labels` (ordered column names), `rows` (first batch of `ResultRow` objects), and `flag` (`"ROW_BY_ROW"` for one-row-per-message mode).
2. **Subsequent `OpResult` messages** (only in non-row-by-row streaming mode): additional batches until the stream closes.
3. **`fetchNextRows`**: After the initial stream closes, call `fetchNextRows(ResultSetFetchRequest)` with `resultSetUUID` and a page size. Repeat until the response contains an empty `rows` list.

Map each `ParameterValue` oneof to the host language's equivalent type following the inverse of the encoding table in §7.2. Pay attention to `is_null = true` for SQL NULL values.

**Cursor navigation** (scrollable result sets) — through `callResource` with `ResourceType.RES_RESULT_SET`:

| Cursor operation | CallType |
|---|---|
| `next()` | `CALL_NEXT` |
| `first()` | `CALL_FIRST` |
| `last()` | `CALL_LAST` |
| `beforeFirst()` | `CALL_BEFORE` |
| `afterLast()` | `CALL_AFTER` |
| `absolute(row)` | `CALL_ABSOLUTE` |
| `relative(rows)` | `CALL_RELATIVE` |
| `previous()` | `CALL_PREVIOUS` |
| `close()` | `CALL_CLOSE` |

### Pseudo-code

```python
# Fetch additional pages
result_set_uuid = ...   # captured from the first op_result
all_rows = []
while True:
    resp = stub.fetchNextRows(ResultSetFetchRequest(
        session=session, resultSetUUID=result_set_uuid, size=500
    ))
    session = resp.session
    if not resp.query_result.rows:
        break
    all_rows.extend(resp.query_result.rows)

# Close the result set
stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_RESULT_SET,
    resourceUUID=result_set_uuid,
    target=TargetCall(callType=CALL_CLOSE)
))

# Absolute cursor navigation (scrollable result sets only)
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_RESULT_SET,
    resourceUUID=result_set_uuid,
    target=TargetCall(callType=CALL_ABSOLUTE, params=[ParameterValue(int_value=10)])
))
session     = resp.session
current_row = resp.values
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`ResultSet`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ResultSet.java): `next()` drives the multi-block iteration; all `getXxx(columnIndex)` methods call `ProtoConverter.fromParameterValue()` on the column's `ParameterValue`.
> - `ojp-jdbc-driver` — [`RemoteProxyResultSet`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/RemoteProxyResultSet.java): base class holding `resultSetUUID` and `statementService`; all scrollable-cursor operations issue `callResource(RES_RESULT_SET, ...)`.
> - `ojp-jdbc-driver` — [`StatementServiceGrpcClient.fetchNextRows(sessionInfo, resultSetUUID, size)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java): the RPC that fetches the next page.

---

### 7.5 LOB (Large Object) Handling

**LOB types:**

| LobType enum | Meaning |
|---|---|
| `LT_BLOB` | Binary large object |
| `LT_CLOB` | Character large object |
| `LT_BINARY_STREAM` | Binary stream (column-streaming variant) |
| `LT_ASCII_STREAM` | ASCII character stream |
| `LT_UNICODE_STREAM` | Unicode character stream |
| `LT_CHARACTER_STREAM` | Generic character stream |

**Writing a LOB (createLob):** Open a client-streaming call to `createLob`. Send one or more `LobDataBlock` messages (recommended chunk size: 32–64 KB) with `session`, `position` (byte offset), `data`, `lobType`, and optional `metadata`. Close the stream. The server responds with a `LobReference` containing `uuid` (the LOB handle), `bytesWritten`, and `lobType`. Store the `LobReference.uuid` and pass it as a parameter value (§7.2) when binding the LOB.

**Reading a LOB (readLob):** Call `readLob(ReadLobRequest)` and concatenate the `data` fields in order from the server-streaming response.

LOB handles are server-side objects. A connection with an open LOB must remain bound to the same server (§2.3). Do not reroute during failover.

### Pseudo-code

```python
CHUNK_SIZE = 64 * 1024

def write_lob(stub, session, data_bytes, lob_type=LT_BLOB):
    def generate_blocks():
        for offset in range(0, len(data_bytes), CHUNK_SIZE):
            yield LobDataBlock(session=session, position=offset,
                               data=data_bytes[offset:offset+CHUNK_SIZE], lobType=lob_type)
    return stub.createLob(generate_blocks()).uuid

lob_uuid = write_lob(stub, session, my_bytes)
stub.executeUpdate(StatementRequest(
    session=session, sql="INSERT INTO docs(content) VALUES(?)",
    parameters=[ParameterProto(index=1, type=PT_BLOB,
                               values=[ParameterValue(string_value=lob_uuid)])]
))

def read_lob(stub, session, lob_uuid, lob_type=LT_BLOB, max_bytes=10_000_000):
    req = ReadLobRequest(
        lobReference=LobReference(uuid=lob_uuid, session=session, lobType=lob_type),
        position=1, length=max_bytes
    )
    return b"".join(block.data for block in stub.readLob(req))
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`LobServiceImpl`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/LobServiceImpl.java): `sendBytes(lobType, pos, inputStream)` opens the client-streaming `createLob` call, chunks the data, and returns the `LobReference`. `parseReceivedBlocks(Iterator<LobDataBlock>)` reassembles chunks from a `readLob` stream.
> - `ojp-jdbc-driver` — [`StatementServiceGrpcClient.createLob(connection, iterator)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java): the client-streaming gRPC call.
> - `StatementServiceGrpcClient.readLob(lobReference, pos, length)`: the server-streaming gRPC call.
> - `ojp-jdbc-driver` — [`Blob`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Blob.java): `getBytes(pos, length)` and `getBinaryStream()` call `readLob`; `setBytes(pos, bytes)` calls `sendBytes`. [`Clob`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Clob.java) mirrors the same pattern for character data.

---

### 7.6 Transaction Management (non-XA)

Use the three transaction RPCs — `startTransaction`, `commitTransaction`, `rollbackTransaction` — together with `terminateSession` for connection close. All four update and return `SessionInfo`; always replace the local copy.

Transaction isolation is set or got by calling `callResource` with `RES_CONNECTION` and `CallType.CALL_SET` / `CALL_GET` and resource name `"TransactionIsolation"`.

### Pseudo-code

```python
# Begin an explicit transaction
session = stub.startTransaction(session)
# session.transactionInfo.transactionStatus == TRX_ACTIVE

resp    = stub.executeUpdate(StatementRequest(session=session, sql="INSERT INTO orders ..."))
session = resp.session

session = stub.commitTransaction(session)
# session.transactionInfo.transactionStatus == TRX_COMMITED
# — OR —
session = stub.rollbackTransaction(session)
# session.transactionInfo.transactionStatus == TRX_ROLLBACK

# Set transaction isolation (READ_COMMITTED = 2)
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_CONNECTION,
    target=TargetCall(callType=CALL_SET, resourceName="TransactionIsolation",
                      params=[ParameterValue(int_value=2)])
))
session = resp.session

# Get current isolation level
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_CONNECTION,
    target=TargetCall(callType=CALL_GET, resourceName="TransactionIsolation")
))
isolation_level = resp.values[0].int_value
session         = resp.session
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`Connection.setAutoCommit(boolean)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java): calls `commitTransaction` when switching on and `startTransaction` when switching off.
> - `Connection.commit()` / `Connection.rollback()`: delegate to `statementService.commitTransaction(session)` / `rollbackTransaction(session)` when `autoCommit == false`.
> - `Connection.close()`: calls `terminateSession(session)` unconditionally.
> - `Connection.setTransactionIsolation(level)` / `getTransactionIsolation()`: forwarded via `callProxy(CallType.CALL_SET/GET, "TransactionIsolation", ...)`.

---

### 7.7 Savepoints

Savepoints are implemented through the `callResource` protocol using `ResourceType.RES_SAVEPOINT`.

**Creating a savepoint:** Call `callResource` with `resourceType = RES_SAVEPOINT`, `target.callType = CALL_SET`, `target.resourceName = "Savepoint"`, `target.params = [savepointName]` if named. The response contains the savepoint UUID in `CallResourceResponse.resourceUUID`.

**Rolling back to a savepoint:** Call `callResource` with `resourceType = RES_SAVEPOINT`, `resourceUUID = <savepoint UUID>`, `target.callType = CALL_ROLLBACK`.

**Releasing a savepoint:** Call `callResource` with `resourceType = RES_SAVEPOINT`, `resourceUUID = <savepoint UUID>`, `target.callType = CALL_RELEASE`.

### Pseudo-code

```python
# Create a named savepoint
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_SAVEPOINT,
    target=TargetCall(callType=CALL_SET, resourceName="Savepoint",
                      params=[ParameterValue(string_value="my_savepoint")])
))
savepoint_uuid = resp.resourceUUID
session        = resp.session

# Roll back to the savepoint
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_SAVEPOINT, resourceUUID=savepoint_uuid,
    target=TargetCall(callType=CALL_ROLLBACK, resourceName="Savepoint")
))
session = resp.session

# Release the savepoint
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_SAVEPOINT, resourceUUID=savepoint_uuid,
    target=TargetCall(callType=CALL_RELEASE, resourceName="Savepoint")
))
session = resp.session
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`Connection.setSavepoint()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java) / `setSavepoint(name)`: calls `callProxy` with `CALL_SET`, `"Savepoint"`, and the optional name; wraps the returned resource UUID in a [`Savepoint`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Savepoint.java) object.
> - `Connection.rollback(Savepoint)`: calls `callProxy` with `CALL_ROLLBACK`.
> - `Connection.releaseSavepoint(Savepoint)`: calls `callProxy` with `CALL_RELEASE`.

---

### 7.8 XA / Distributed Transactions

XA support maps the standard XA resource manager protocol to gRPC RPCs. XA connections are always pinned to a single server (§2.3).

**XA transaction lifecycle:**

```
xaStart(XaStartRequest)       -- Begin branch; safe to retry on connection error
xaEnd(XaEndRequest)           -- End branch; NEVER retry after this point
xaPrepare(XaPrepareRequest)   -- Two-phase prepare; returns XA_OK or XA_RDONLY
xaCommit(XaCommitRequest)     -- Commit (onePhase=true for one-phase optimisation)
xaRollback(XaRollbackRequest) -- Roll back the branch
xaRecover(XaRecoverRequest)   -- List in-doubt XIDs (for recovery after crash)
xaForget(XaForgetRequest)     -- Forget a heuristically completed branch
```

**Xid encoding (XidProto):**

| Field | Type | Meaning |
|---|---|---|
| `formatId` | int32 | Transaction format ID |
| `globalTransactionId` | bytes | Global transaction ID (up to 64 bytes) |
| `branchQualifier` | bytes | Branch qualifier (up to 64 bytes) |

**Retry policy:** `xaStart` only — retry on connection-level errors (see §4.3). All other XA operations must not be retried automatically. Surface failures to the caller's transaction manager.

On the response to `xaStart`, record the `sessionUUID -> targetServer` binding. All subsequent XA operations for this branch must go to the same server. If that server is unavailable, raise `XAException(XAER_RMFAIL)`.

### Pseudo-code

```python
xid = XidProto(formatId=1, globalTransactionId=b"global-tx-001", branchQualifier=b"branch-1")

resp    = stub.xaStart(XaStartRequest(session=session, xid=xid, flags=0))
session = resp.session   # bind session.targetServer for all remaining calls

resp    = stub.executeUpdate(StatementRequest(session=session, sql="UPDATE accounts ..."))
session = resp.session

resp    = stub.xaEnd(XaEndRequest(session=session, xid=xid, flags=0))
session = resp.session

prep = stub.xaPrepare(XaPrepareRequest(session=session, xid=xid))
# prep.result = XA_OK or XA_RDONLY

stub.xaCommit(XaCommitRequest(session=session, xid=xid, onePhase=False))
# OR: stub.xaRollback(XaRollbackRequest(session=session, xid=xid))
# OR: stub.xaCommit(XaCommitRequest(session=session, xid=xid, onePhase=True))  # skip xaPrepare

# Recovery
resp = stub.xaRecover(XaRecoverRequest(session=session, flag=TMSTARTRSCAN))
# Forget
stub.xaForget(XaForgetRequest(session=session, xid=xid))
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`OjpXAResource`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/xa/OjpXAResource.java): implements `XAResource`; all 10 lifecycle methods; contains the `xaStart` retry loop and the `toXidProto` / `fromXidProto` conversion helpers.
> - `ojp-jdbc-driver` — [`OjpXAConnection`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/xa/OjpXAConnection.java): creates the XA-mode `StatementService` connection (always calling the server, never cache-hit) and vends `OjpXAResource`.
> - `ojp-jdbc-driver` — [`OjpXADataSource`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/xa/OjpXADataSource.java): entry point for XA; calls `MultinodeConnectionManager.connectXA()` to pin the session to a single server.

---

### 7.9 callResource Protocol

The `callResource` RPC is a generic mechanism for operations that do not fit a dedicated RPC — primarily `DatabaseMetaData` queries, `ResultSet` cursor/update operations, `Statement` cancellation, savepoint management, and resource lifecycle calls.

**Request:**

```
CallResourceRequest {
    session:      SessionInfo
    resourceType: ResourceType
    resourceUUID: string
    target:       TargetCall
    properties:   PropertyEntry[]
}

TargetCall {
    callType:     CallType
    resourceName: string
    params:       ParameterValue[]
    nextCall:     TargetCall   // optional chained call (recursive)
}
```

**ResourceType values:**

| Value | Meaning |
|---|---|
| `RES_RESULT_SET` | An open result set |
| `RES_STATEMENT` | A plain statement |
| `RES_PREPARED_STATEMENT` | A prepared statement |
| `RES_CALLABLE_STATEMENT` | A callable statement |
| `RES_LOB` | A LOB object |
| `RES_CONNECTION` | The connection itself (for metadata, catalog, etc.) |
| `RES_SAVEPOINT` | A savepoint |

**CallType reference (47 codes):**

`CALL_SET`, `CALL_GET`, `CALL_IS`, `CALL_ALL`, `CALL_NULLS`, `CALL_USES`, `CALL_SUPPORTS`, `CALL_STORES`, `CALL_NULL`, `CALL_DOES`, `CALL_DATA`, `CALL_NEXT`, `CALL_CLOSE`, `CALL_WAS`, `CALL_CLEAR`, `CALL_FIND`, `CALL_BEFORE`, `CALL_AFTER`, `CALL_FIRST`, `CALL_LAST`, `CALL_ABSOLUTE`, `CALL_RELATIVE`, `CALL_PREVIOUS`, `CALL_ROW`, `CALL_UPDATE`, `CALL_INSERT`, `CALL_DELETE`, `CALL_REFRESH`, `CALL_CANCEL`, `CALL_MOVE`, `CALL_OWN`, `CALL_OTHERS`, `CALL_UPDATES`, `CALL_DELETES`, `CALL_INSERTS`, `CALL_LOCATORS`, `CALL_AUTO`, `CALL_GENERATED`, `CALL_RELEASE`, `CALL_NATIVE`, `CALL_PREPARE`, `CALL_ROLLBACK`, `CALL_ABORT`, `CALL_EXECUTE`, `CALL_ADD`, `CALL_ENQUOTE`, `CALL_REGISTER`, `CALL_LENGTH`

Always update the local `SessionInfo` from `response.session`.

### Pseudo-code

```python
# Get database catalog
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_CONNECTION, resourceUUID="",
    target=TargetCall(callType=CALL_GET, resourceName="Catalog")
))
catalog_name = resp.values[0].string_value
session      = resp.session

# Cancel a running statement
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_STATEMENT, resourceUUID=statement_uuid,
    target=TargetCall(callType=CALL_CANCEL)
))
session = resp.session

# Chained call: get Schema and Catalog in one round-trip
resp = stub.callResource(CallResourceRequest(
    session=session, resourceType=RES_CONNECTION,
    target=TargetCall(callType=CALL_GET, resourceName="Schema",
                      nextCall=TargetCall(callType=CALL_GET, resourceName="Catalog"))
))
schema_name  = resp.values[0].string_value
catalog_name = resp.values[1].string_value
session      = resp.session
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`StatementServiceGrpcClient.callResource(CallResourceRequest)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java): the single-node gRPC call.
> - `ojp-jdbc-driver` — [`DatabaseMetaData`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/DatabaseMetaData.java): every `DatabaseMetaData` method (>200 in total) is implemented by calling `callResource` with `RES_CONNECTION` and the appropriate `CallType`.
> - `ojp-jdbc-driver` — `Connection.callProxy(callType, resourceName, returnType, params)`: the private convenience wrapper for issuing `callResource` calls.

---

### 7.10 Configuration System

**Configuration sources (in priority order):**

1. **System / environment properties** (highest priority) — e.g., `-Dojp.health.check.interval=10000`.
2. **`ojp.properties` file** — loaded from the classpath or a well-known filesystem path.
3. **Built-in defaults** (lowest priority).

**Property namespacing:**

```properties
# Global
ojp.health.check.interval=5000

# Per-datasource (datasource name: "analytics")
analytics.ojp.health.check.interval=10000
```

**Standard configuration properties:**

| Property | Default | Meaning |
|---|---|---|
| `ojp.health.check.interval` | `5000` (ms) | Periodic health check interval |
| `ojp.health.check.threshold` | `5000` (ms) | Minimum wait before re-probing an unhealthy server |
| `ojp.health.check.timeout` | `5000` (ms) | Probe call timeout |
| `ojp.redistribution.enabled` | `true` | Enable/disable the health checker and redistribution |
| `ojp.redistribution.idleRebalanceFraction` | `1.0` | Fraction of idle connections to close per rebalance cycle |
| `ojp.redistribution.maxClosePerRecovery` | `100` | Max connections closed per recovery event |
| `ojp.loadaware.selection.enabled` | `true` | Use least-connections; `false` = round-robin |
| `ojp.multinode.retry.attempts` | `3` | Max failover retry attempts |
| `ojp.multinode.retry.delay` | `100` (ms) | Delay between retry attempts |
| `ojp.datasource.name` | `"default"` | Active datasource name (sent to the server) |
| `ojp.grpc.tls.enabled` | `false` | Enable TLS on gRPC channels |
| `ojp.grpc.tls.cert.path` | — | Path to client certificate for mTLS |
| `ojp.jdbc.clientThrottle.mode` | `reactive` | Client throttle mode: `off`, `proactive`, `reactive`, or `combined` (see §7.14) |

**Duration format** — values support: no suffix = milliseconds; `ms` = milliseconds; `s` = seconds; `m` = minutes.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`DatasourcePropertiesLoader`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/DatasourcePropertiesLoader.java): `loadOjpPropertiesForDataSource(datasourceName)` merges file properties, system properties, and environment variables with per-datasource prefix resolution.
> - `ojp-jdbc-driver` — [`HealthCheckConfig`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/HealthCheckConfig.java): the strongly-typed POJO holding all health-check and redistribution settings.
> - `ojp-grpc-commons` — [`GrpcClientConfig.load()`](../../ojp-grpc-commons/src/main/java/org/openjproxy/config/GrpcClientConfig.java): loads gRPC-specific settings (max inbound message size, TLS config) from `ojp.properties`.

---

### 7.11 Query Result Caching

Cache configuration is entirely **client-side to server** — the client reads local cache rules and sends them to the server as `ConnectionDetails.properties` entries during `connect()`. The server applies them transparently; the client does not implement any caching logic itself.

**Properties sent to the server:**

| Property key | Meaning |
|---|---|
| `ojp.cache.enabled` | `"true"` to enable caching |
| `ojp.cache.queries.<N>.pattern` | Regex pattern matching SQL queries to cache |
| `ojp.cache.queries.<N>.ttl` | TTL in seconds for cached results |
| `ojp.cache.queries.<N>.invalidateOn` | Comma-separated table names that invalidate this rule |
| `ojp.cache.queries.<N>.enabled` | `"true"` / `"false"` to toggle individual rules |

`<N>` is a 1-based integer index. Rules are processed in index order.

**Example configuration:**

```properties
ojp.cache.enabled=true
ojp.cache.queries.1.pattern=SELECT .* FROM products.*
ojp.cache.queries.1.ttl=600
ojp.cache.queries.1.invalidateOn=products,product_prices
ojp.cache.queries.2.pattern=SELECT .* FROM users.*
ojp.cache.queries.2.ttl=300
ojp.cache.queries.2.invalidateOn=users
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`CacheConfigurationBuilder.addCachePropertiesToMap(propertiesMap, datasourceName)`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/CacheConfigurationBuilder.java): reads cache rules from the loaded `Properties` and appends them to the `ConnectionDetails.properties` map sent to the server on `connect()`.

---

### 7.12 Security / Transport

**Plaintext (default):** Create a plaintext gRPC channel targeting `dns:///host:port`. Suitable for internal networks or local development.

**TLS:** When `ojp.grpc.tls.enabled = true`, create a TLS-secured channel. Use the platform's default trust store or a custom CA certificate. Support mutual TLS (mTLS) when `ojp.grpc.tls.cert.path` is set. Certificate paths and key material must be loaded from configurable filesystem paths; do not hard-code them.

**Credential handling:** Passwords must never be logged or included in exception messages. Connection keys used for cache lookups (§4.1) may include the password as a cache key only — they must not be serialised or persisted.

> **Reference implementation:**
> - `ojp-grpc-commons` — [`GrpcChannelFactory.createChannel(host, port)`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/GrpcChannelFactory.java): creates a plaintext `ManagedChannel`; `createSecureChannel(host, port, size, tlsConfig)` builds the TLS-secured variant.
> - `ojp-grpc-commons` — [`GrpcClientConfig`](../../ojp-grpc-commons/src/main/java/org/openjproxy/config/GrpcClientConfig.java): loaded from `ojp.properties`; exposes `getTlsConfig()` and `getMaxInboundMessageSize()`.
> - `ojp-grpc-commons` — [`TlsConfig`](../../ojp-grpc-commons/src/main/java/org/openjproxy/config/TlsConfig.java): holds `enabled`, `certPath`, `keyPath`, `caPath`, and `clientAuth` flags.

---

### 7.13 DataSource / Integration API

Provide a higher-level `DataSource` (or equivalent) object that holds connection configuration and exposes a `getConnection()` method. Integrate cleanly with the host language's database access conventions (e.g., Python's DB-API 2.0, Go's `database/sql`, Node.js connection objects).

**Disable** the framework's own built-in connection pool when OJP is in use. For Spring Boot (Java), provide a `spring-boot-starter-ojp` auto-configuration module that excludes `DataSourceAutoConfiguration`. For other languages, document this clearly in the library README.

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`OjpDataSource`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/OjpDataSource.java): implements `javax.sql.DataSource`; `getConnection()` delegates to `DriverManager.getConnection(url, info)`.
> - `ojp-jdbc-driver` — [`OjpXADataSource`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/xa/OjpXADataSource.java): implements `javax.sql.XADataSource`; `getXAConnection()` creates an `OjpXAConnection` for JTA integration.
> - `spring-boot-starter-ojp` module: provides the Spring Boot auto-configuration class and the `OjpSystemPropertiesBridge` bean; excludes `DataSourceAutoConfiguration` to prevent double-pooling.

---

### 7.14 Client-Side Throttling

#### Why it exists

When the OJP server's connection pool is overwhelmed, it responds with admission timeouts. Without client-side throttling, all application threads keep retrying, making the overload worse — more requests arrive at the already-struggling server, consuming queue slots and prolonging the outage.

Client-side throttling breaks the cycle. As soon as the server reports an overload signal through `SessionInfo`, each client caps its own in-flight request count. Fewer requests reach the server, giving the pool space to recover.

**Example (without throttling):** 50 threads across 5 app servers hit one OJP node at once. The node's 10-slot pool is overwhelmed; 40 requests queue or time out. Those timed-out threads retry — sending even more requests. The situation gets worse.

**Example (with throttling):** The server reports `maxAdmission=10`, `clientCount=5`. Each client calculates `ceil(10/5) × 0.9 ≈ 1` concurrent request. At most 5–6 requests reach the server simultaneously — within its capacity. Admission timeouts stop and throughput recovers.

#### Three signals from the server

Every `SessionInfo` response (on `connect()`, `executeUpdate()`, and `startTransaction()`) carries three throttle signals:

| Field | Meaning |
|---|---|
| `maxAdmission` | The total pool slot count on this server node. The baseline capacity. |
| `clientCount` | How many distinct client processes (by `clientUUID`) are connected to this `connHash` on this node right now. |
| `observedPeak` | The server's observed peak concurrent load before its last admission timeout. Starts at `0` (no timeout seen yet). Updated downward immediately when a timeout occurs; recovers upward slowly (+1 per `totalSlots × 2` releases). |

#### Fair-share formula

Each client computes its per-node budget from these signals:

```
rawBudget = ceil(signal / clientCount) × numberOfUpNodes
budget    = floor(rawBudget × 0.9)   # 10% safety headroom
budget    = max(1, budget)            # always allow at least 1
```

Where `signal` is:
- **Proactive mode:** use `maxAdmission`.
- **Reactive mode (default):** use `observedPeak` (falls back to `maxAdmission` when `observedPeak == 0`).
- **Combined mode:** compute both, take `min(proactiveBudget, reactiveBudget)`.

The **10% headroom** exists because ceiling division can slightly over-allocate. Example: server has 20 slots, 3 clients.
- Without headroom: `ceil(20/3) = 7` per client → 3 clients × 7 = **21** requests, exceeding the 20-slot capacity by 1.
- With headroom: `floor(7 × 0.9) = floor(6.3) = 6` per client → 3 × 6 = **18**, safely within capacity.

The extra slack also absorbs one stale `clientCount` reading (the count is updated asynchronously).

**AIMD (Additive Increase, Multiplicative Decrease):** limits only change by `+1` per `SessionInfo` update when growing. They drop immediately when the signal decreases. This prevents multiple simultaneous client reconnections from all increasing their budgets at once (which would recreate the burst).

#### Four throttle modes

| Mode | Property value | What it uses |
|---|---|---|
| Off | `off` | No throttling; all requests pass through |
| Proactive | `proactive` | Uses `maxAdmission + clientCount` to set a static fair-share limit |
| **Reactive (default)** | `reactive` | Uses `observedPeak + clientCount` to track actual server capacity |
| Combined | `combined` | Uses both; the effective limit is `min(proactiveBudget, reactiveBudget)` |

Configure via `ojp.jdbc.clientThrottle.mode` (default: `reactive`). For most
workloads, `reactive` delivers the most adaptive performance; use `combined` or
`proactive` for workloads that cannot tolerate any bursts. Per-datasource override:

```properties
# Global default
ojp.jdbc.clientThrottle.mode=reactive

# Per-datasource override (datasource name: "analytics")
analytics.ojp.jdbc.clientThrottle.mode=off
```

#### How the counter works

Implement a fail-fast (non-blocking) per-`connHash` counter:

```python
class ClientThrottle:
    def __init__(self):
        self._in_flight = AtomicInt(0)   # thread-safe atomic integer
        self.proactive_limit = MAX_INT
        self.reactive_limit  = MAX_INT

    def try_acquire(self, mode, in_transaction) -> bool:
        if mode == "off" or in_transaction:
            return True               # bypass throttle while inside a transaction
        limit = self._effective_limit(mode)
        if limit == MAX_INT:
            return True               # uninitialised — let request through
        while True:
            cur = self._in_flight.get()
            if cur >= limit:
                return False          # reject: at limit
            if self._in_flight.compare_and_set(cur, cur + 1):
                return True           # accepted

    def release(self, mode, in_transaction):
        if mode == "off" or in_transaction:
            return
        self._in_flight.update(lambda v: max(0, v - 1))

    def update_from_session_info(self, session_info):
        # Called after each executeUpdate / startTransaction response
        client_count = max(1, session_info.clientCount)
        n_up = count_up_servers(session_info.clusterHealth)
        if session_info.maxAdmission > 0:
            raw = ceil(session_info.maxAdmission / client_count) * n_up
            new_limit = max(1, int(raw * 0.9))
            # AIMD: drop immediately, grow by at most +1
            if new_limit < self.proactive_limit:
                self.proactive_limit = new_limit
            elif new_limit > self.proactive_limit:
                self.proactive_limit = self.proactive_limit + 1
        # similar logic for reactive_limit using observedPeak
```

**Important:** the `try_acquire` / `release` pair must bracket every `executeQuery` and `executeUpdate` call. The `release` must happen in a `finally` block so it runs even if the call throws.

#### In-transaction bypass

Requests that arrive **inside an open transaction** bypass the throttle (see `in_transaction` flag above). Rejecting a mid-transaction request would cause an abandoned transaction on the server, holding real database connections and blocking other work. The server's admission-control semaphore provides the safety boundary for in-transaction work.

#### Cross-node note (v1 caveat)

In multinode deployments, each OJP node sees only its own `clientCount`. Two clients connecting to different nodes each compute their per-node budget independently. If each node has 10 slots and 1 client each, both clients compute `ceil(10/1) × 2 × 0.9 = 18`. Total cluster capacity is 20, and the server's own semaphore remains the final safety gate.

#### Pseudo-code — complete request lifecycle

```python
throttle = get_or_create_throttle(session.connHash)  # one per connHash

def execute_update(sql, params):
    in_txn = not auto_commit
    if not throttle.try_acquire(mode, in_txn):
        raise Exception("Client throttle limit reached; request rejected "
                        "to avoid overloading the database")
    try:
        resp = stub.executeUpdate(StatementRequest(session=session, sql=sql,
                                                   parameters=params,
                                                   statementUUID=new_uuid()))
        session = resp.session
        throttle.update_from_session_info(session)
        return resp.value.int_value  # affected row count
    finally:
        throttle.release(mode, in_txn)
```

> **Reference implementation:**
> - `ojp-jdbc-driver` — [`ClientThrottleManager`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientThrottleManager.java): the complete per-`connHash` throttle implementation with atomic CAS counter, AIMD update, and `countUpServers()`.
> - `ojp-jdbc-driver` — [`ClientThrottleMode`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientThrottleMode.java): `OFF | PROACTIVE | REACTIVE | COMBINED` enum; loaded from `ojp.jdbc.clientThrottle.mode`.
> - `ojp-jdbc-driver` — [`Connection`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java): `THROTTLE_MANAGERS` static map (keyed by `connHash`); `setSession()` calls `throttle.updateFromSessionInfo()` after each response.
> - `ojp-jdbc-driver` — [`Statement.acquireThrottle()`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java): the `try_acquire` / `release` guard used by `executeQuery` and `executeUpdate`.

---

## 8. Testing Coverage

A conformant client implementation must ship a test suite that exercises all the aspects above. Tests that require a live OJP server (and optionally a real database) should be **gated behind feature flags** so the suite can run incrementally in CI.

**Test infrastructure requirements:** A running OJP server (see `ojp-server` module and `download-drivers.sh`). At minimum, an embedded/in-process database (e.g., H2) for fast baseline tests. Optional: containerised databases gated by per-database flags.

### Required Test Scenarios

#### Basic CRUD
- SELECT, INSERT, UPDATE, DELETE via plain Statement and PreparedStatement.
- Verify affected row counts, returned ResultSet contents, and empty result sets.

#### Multiple data types
- Round-trip every `ParameterTypeProto` value through INSERT + SELECT.
- Cover: all integer widths, float, double, BigDecimal, string, boolean, byte array, date, time, timestamp (with and without timezone), LocalDate, LocalTime, LocalDateTime, OffsetDateTime, OffsetTime, Instant, URL, UUID, RowId, BLOB, CLOB, array, NULLs for each type.

#### Statement variants
- Plain `Statement`: `executeQuery`, `executeUpdate`, `execute`, `executeBatch`, `getResultSet`, `getUpdateCount`, `getGeneratedKeys`, `cancel`, `close`.
- `PreparedStatement`: all `setXxx` methods, `executeBatch`, multiple executions with the same prepared statement, `getParameterMetaData`.
- `CallableStatement`: IN, OUT, INOUT parameters; `registerOutParameter`; retrieval of OUT values after execution.

#### ResultSet navigation
- Forward-only cursors: `next()`, `wasNull()`, `close()`.
- Scrollable cursors: `first()`, `last()`, `beforeFirst()`, `afterLast()`, `absolute(n)`, `relative(n)`, `previous()`.
- Multi-block pagination: queries large enough to exceed one fetch page; verify all rows are retrieved.

#### ResultSet metadata
- `getColumnCount()`, `getColumnName()`, `getColumnType()`, `getColumnTypeName()`, `getPrecision()`, `getScale()`, `isNullable()`, `isAutoIncrement()`.

#### DatabaseMetaData
- `getTables()`, `getColumns()`, `getPrimaryKeys()`, `getIndexInfo()`, `getProcedures()`, `getTypeInfo()`, `supportsXxx()` methods. Verify results match the actual database schema.

#### Transactions
- Commit: insert rows in a transaction, commit, verify rows persist.
- Rollback: insert rows in a transaction, rollback, verify rows are absent.
- `autoCommit = false` then `setAutoCommit(true)` — verify implicit commit.
- Transaction isolation level: set, verify via `getTransactionIsolation()`, reset after connection return.

#### Savepoints
- Create a named and an anonymous savepoint. Rollback to each; verify partial rollback semantics. Release a savepoint.

#### XA transactions
- Full lifecycle: `xaStart`, `xaEnd`, `xaPrepare`, `xaCommit`.
- Rollback path: `xaStart`, `xaEnd`, `xaPrepare`, `xaRollback`.
- One-phase commit (`onePhase=true`).
- `xaRecover`: verify in-doubt XIDs are returned.
- `xaForget`: verify heuristically completed branch is removed.
- Transaction isolation reset after XA session.

#### LOBs
- BLOB: write a small blob (< 1 chunk), a large blob (multiple chunks), read back both; verify byte-for-byte equality.
- CLOB: same as BLOB but with character content.
- Binary stream, ASCII stream, Unicode stream: write via stream API, read back.
- Hydratable LOB: verify that a LOB reference can be passed as a parameter to a second statement.
- NULL LOB: verify that `setBlob(null)` / `setClob(null)` sends a SQL NULL.

#### Session affinity
- Verify that a connection with an open transaction always routes to the same server.
- Verify that a connection holding an open LOB always routes to the same server.
- Verify that when the bound server is down, an appropriate error is raised rather than silent rerouting.

#### Multi-block / large result sets
- Execute a query that returns more rows than one page. Verify all rows arrive and are in the correct order.

#### Multinode load balancing
- With two or more server endpoints, open `N` connections and verify they are distributed across servers (round-robin and least-connections modes separately).

#### Multinode failover
- Simulate one server going down mid-operation; verify the operation is retried on a surviving server (for stateless operations).
- Verify a server is marked unhealthy after failure.
- Verify subsequent connections avoid the unhealthy server.

#### Multinode recovery and redistribution
- Bring a server back; verify it is marked healthy after the health check interval.
- Verify new connections start routing to the recovered server.
- Verify connection redistribution closes a fraction of idle connections on over-loaded servers.

#### XA multinode
- Verify that each XA session binds to exactly one server.
- Verify that failover of an XA session to another server raises an error (not a silent reroute).
- Verify XA redistribution after server recovery.

#### connHash caching / connect-RPC skip
- Open two connections with the same credentials; verify only one `connect()` gRPC call is made.
- Simulate a `NOT_FOUND` response; verify the driver invalidates the cache and re-issues `connect()`.

#### Session stickiness error path
- Establish a session on server A. Mark server A unhealthy. Attempt a SQL operation. Verify an error is raised rather than the request being silently routed to server B.

#### Cluster health propagation
- Fail one server; verify the cluster health string sent in subsequent requests marks it `DOWN`.
- Recover the server; verify the health string marks it `UP`.

#### Concurrency / pool exhaustion
- Send more concurrent requests than the server-side pool size; verify pool-exhaustion errors are surfaced cleanly and do not mark servers unhealthy.

#### Slow query segregation
- Send queries that take longer than the slow-query threshold; verify they use the reserved slow-query slots and do not starve fast queries.

#### Client-side throttling
- With throttle mode `proactive`: send more concurrent requests than `ceil(maxAdmission / clientCount) × 0.9`; verify the excess requests are rejected immediately (no blocking) with a throttle error — not forwarded to the server.
- With throttle mode `reactive`: simulate an admission timeout (drive `observedPeak` down); verify the client reduces its budget and rejects excess requests.
- With throttle mode `combined`: verify the effective limit is `min(proactiveBudget, reactiveBudget)`.
- With throttle mode `off`: verify all requests pass through regardless of in-flight count.
- Verify that in-transaction requests bypass the throttle (a mid-transaction request is never rejected by the client).
- Verify AIMD recovery: after a reduced `observedPeak`, send requests at a modest rate; verify the budget increases by at most 1 per `SessionInfo` update.
- Verify per-datasource throttle mode override via `<name>.ojp.jdbc.clientThrottle.mode`.

#### Multi-datasource
- Configure two endpoints with different datasource names; verify each endpoint uses its own datasource configuration.

#### Configuration loading
- Verify properties are loaded from `ojp.properties`.
- Verify system properties override file properties.
- Verify per-datasource properties override global properties.

#### Performance / mini stress
- Open and close 100–1000 connections in parallel; verify no connection leaks, no deadlocks, and no degrading error rate.

### Database-specific test suites

Each database must have a dedicated test class gated by its own flag.

| Database | Feature flag |
|---|---|
| H2 | `enableH2Tests` |
| PostgreSQL | `enablePostgresTests` |
| MySQL | `enableMySQLTests` |
| MariaDB | `enableMariaDBTests` |
| Oracle | `enableOracleTests` |
| SQL Server | `enableSqlServerTests` |
| DB2 | `enableDb2Tests` |
| CockroachDB | `enableCockroachDBTests` |

H2 tests (in-process, no external dependency) must always be runnable in CI without any extra setup and should act as the first gate before any database-specific jobs run.

> **Reference implementation — test classes by area:**
>
> | Test area | Java test class(es) |
> |---|---|
> | Basic CRUD | [`BasicCrudIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/BasicCrudIntegrationTest.java) |
> | Multiple data types | `H2MultipleTypesIntegrationTest`, `PostgresMultipleTypesIntegrationTest`, `MySQLMultipleTypesIntegrationTest`, `OracleMultipleTypesIntegrationTest`, `SQLServerMultipleTypesIntegrationTest`, `Db2MultipleTypesIntegrationTest`, `CockroachDBMultipleTypesIntegrationTest`, `MariaDBMultipleTypesIntegrationTest` |
> | Statement variants | `H2StatementExtensiveTests`, `H2PreparedStatementExtensiveTests` (and per-DB equivalents) |
> | ResultSet navigation / metadata | `H2ResultSetTest` (and per-DB), `H2ResultSetMetaDataExtensiveTests`, `H2ReadMultipleBlocksOfDataIntegrationTest` |
> | DatabaseMetaData | `H2DatabaseMetaDataExtensiveTests`, `H2ConnectionExtensiveTests` (and per-DB) |
> | Transactions | `H2ConnectionExtensiveTests`, [`TransactionIsolationResetTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/TransactionIsolationResetTest.java) |
> | Savepoints | `H2SavepointTests` (and per-DB `*SavepointTests`) |
> | XA transactions | [`PostgresXAIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/PostgresXAIntegrationTest.java), `MySQLXAIntegrationTest`, `MariaDBXAIntegrationTest`, `OracleXAIntegrationTest`, `SqlServerXAIntegrationTest`, `Db2XAIntegrationTest`, [`XASessionInvalidationTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/XASessionInvalidationTest.java) |
> | LOBs | [`BlobIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/BlobIntegrationTest.java), [`BinaryStreamIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/BinaryStreamIntegrationTest.java), [`HydratedLobValidationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/HydratedLobValidationTest.java) (and per-DB variants) |
> | Session affinity | [`H2SessionAffinityIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/H2SessionAffinityIntegrationTest.java) (and per-DB `*SessionAffinity*`) |
> | Multi-block result sets | `H2ReadMultipleBlocksOfDataIntegrationTest` (and per-DB) |
> | Multinode load balancing | [`LoadAwareServerSelectionTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/LoadAwareServerSelectionTest.java), [`MultinodeIntegrationTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeIntegrationTest.java) |
> | Multinode failover | [`MultinodeFailoverTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeFailoverTest.java), [`MultinodeConnectionManagerErrorHandlingTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeConnectionManagerErrorHandlingTest.java) |
> | Multinode recovery / redistribution | [`MultinodeRecoveryTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeRecoveryTest.java) |
> | XA multinode | [`MultinodeXAIntegrationTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeXAIntegrationTest.java) |
> | connHash caching | [`ConnectRpcSkipOptimisationTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/ConnectRpcSkipOptimisationTest.java), [`UnifiedConnectionModeTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/UnifiedConnectionModeTest.java) |
> | Session stickiness error path | [`MultinodeTargetServerBindingTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeTargetServerBindingTest.java), `MultinodeStatementServiceTest` |
> | Cluster health propagation | [`MultinodeConnectionManagerClusterHealthTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeConnectionManagerClusterHealthTest.java) |
> | Concurrency / pool exhaustion | [`ConcurrencyTimeoutTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/ConcurrencyTimeoutTest.java) |
> | Multi-datasource | [`MultiDataSourceIntegrationTest`](../../ojp-jdbc-driver/src/test/java/openjproxy/jdbc/MultiDataSourceIntegrationTest.java), [`MultiDataSourceConfigurationTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/MultiDataSourceConfigurationTest.java) |
> | Configuration loading | [`DatasourcePropertiesLoaderSystemPropertyTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/DatasourcePropertiesLoaderSystemPropertyTest.java), [`DatasourcePropertiesLoaderEnvironmentTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/DatasourcePropertiesLoaderEnvironmentTest.java) |
> | URL parsing | [`MultinodeUrlParserTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeUrlParserTest.java), [`UrlParserTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/UrlParserTest.java), [`DriverMultinodeUrlTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/DriverMultinodeUrlTest.java) |
> | DataSource API | [`OjpDataSourceTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/OjpDataSourceTest.java), [`OjpXADataSourceTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/xa/OjpXADataSourceTest.java) |
> | Health check config | [`HealthCheckConfigTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/HealthCheckConfigTest.java), [`MultinodeRetryConfigTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/MultinodeRetryConfigTest.java) |
> | Session tracker unit | [`SessionTrackerTest`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/grpc/client/SessionTrackerTest.java) |

---

## Appendix A — Proto file locations

| File | Location |
|---|---|
| Main protocol | `ojp-grpc-commons/src/main/proto/StatementService.proto` |
| Generic value containers | `ojp-grpc-commons/src/main/proto/containers.proto` |
| Echo / heartbeat | `ojp-grpc-commons/src/main/proto/echo.proto` |

## Appendix B — Reference implementation classes

| Aspect | Java class |
|---|---|
| gRPC stubs | `StatementServiceGrpcClient` |
| Multinode routing | `MultinodeStatementService`, `MultinodeConnectionManager` |
| URL parsing | `MultinodeUrlParser`, `UrlParser` |
| Session tracking | `SessionTracker` |
| Health checking | `HealthCheckValidator`, `HealthCheckConfig` |
| Redistribution | `ConnectionRedistributor`, `XAConnectionRedistributor` |
| Error mapping | `GrpcExceptionHandler` |
| Connection lifecycle | `Connection` |
| Statement execution | `Statement`, `PreparedStatement`, `CallableStatement` |
| Result set | `ResultSet`, `RemoteProxyResultSet` |
| LOB handling | `Blob`, `Clob`, `NClob`, `Lob`, `LobServiceImpl` |
| XA | `OjpXAResource`, `OjpXAConnection`, `OjpXADataSource` |
| Driver entry point | `Driver` |
| DataSource wrapper | `OjpDataSource` |
| Client-side throttling | `ClientThrottleManager`, `ClientThrottleMode` |
