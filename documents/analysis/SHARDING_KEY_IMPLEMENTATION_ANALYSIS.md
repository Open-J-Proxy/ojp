# JDBC ShardingKey Implementation Analysis

**Date:** 2026-11-07
**Status:** Analysis only — no implementation is proposed by this document

## Executive summary

OJP currently does not implement JDBC `ShardingKey` support. The JDBC driver's `Connection` class implements `java.sql.Connection` but does not override the four JDBC 4.3 sharding methods. The inherited interface defaults therefore report the feature as unsupported, and H2 and PostgreSQL tests explicitly assert `SQLFeatureNotSupportedException`. The server-side interface converter knows the `ShardingKey` and `ShardingKeyBuilder` interfaces, but there is no key transport, shard registry, or key-based connection selection.

The most promising direction is **OJP-managed routing among explicitly configured shard datasources**, initially pinning one backend shard to a logical JDBC connection. OJP already creates datasource pools per connection configuration, supports named datasources, and defers regular physical connection allocation until the first operation. This can support a key being set before a backend connection is acquired. A first version should not attempt to split SQL, combine results, or coordinate transactions across shards.

## What JDBC ShardingKey means

JDBC 4.3 defines `ShardingKey` as an opaque key used by a JDBC driver or datasource to identify a database shard. Applications can build keys through `ShardingKeyBuilder`, then pass them through `Connection.setShardingKey(...)` or `ConnectionBuilder.shardingKey(...)`. The `setShardingKeyIfValid(...)` variants allow a caller to test whether a key can be applied without requiring an exception for an invalid/unavailable key.

The API is a routing hint and contract between the application and its JDBC stack. By itself, it does **not** define:

- The shard topology, key ranges, or hashing algorithm.
- How a shard is provisioned or migrated.
- How SQL is rewritten or a query is split across shards.
- How uniqueness, foreign keys, joins, or transactions spanning shards work.

OJP would need an explicit routing policy and topology to turn the API into meaningful multi-shard behavior.

## Current implementation in OJP

### Client behavior

- `org.openjproxy.jdbc.Connection` implements `java.sql.Connection`, but has no overrides for `setShardingKey`, `setShardingKeyIfValid`, or `createShardingKeyBuilder`. JDBC interface defaults are what currently produce the unsupported behavior.
- `PostgresConnectionExtensiveTests` and `H2ConnectionExtensiveTests` assert that all four `setShardingKey*` overloads throw `SQLFeatureNotSupportedException`.
- `Driver.connect()` performs the gRPC `connect` call and returns an OJP `Connection`. It sends URL, credentials, endpoints, and datasource properties, but no sharding key.

### Server behavior and routing foundations

- `ConnectAction` uses a hash of URL, username, password, and datasource name to identify and create/reuse a datasource pool. For regular connections, it returns session information without creating a session or borrowing a physical JDBC connection.
- `SessionConnectionHelper` obtains the actual backend connection when an operation needs one. Sessions can also lazily acquire primary or replica connections for read/write splitting.
- Pools are created from generic `DataSource` instances through the connection-pool provider SPI. The existing read/write registry selects replica datasources; it is not a shard registry and is not keyed by a JDBC `ShardingKey`.
- gRPC `ConnectionDetails` and `SessionInfo` have no shard-key or shard-identity fields. The generic resource-call path can reflectively call methods on backend JDBC objects, but an OJP client `ShardingKey` cannot currently be transported or reconstructed as a backend driver's opaque implementation.
- `JavaSqlInterfacesConverter` recognizes `ShardingKey` and `ShardingKeyBuilder`; that is interface classification support, not an implementation of either API.

**Implication:** The normal `connect()` path is a suitable place to establish the datasource topology, while setting the key before the first SQL operation can select a shard before a physical connection is borrowed. The API and protocol still need to define what happens if a key is changed after a connection has been pinned or used.

## Implementation possibilities

### 1. OJP-managed routing over configured shard datasources — recommended first

Configure a set of named shard datasources under one logical datasource. The OJP client returns an OJP-owned `ShardingKeyBuilder` whose resulting key retains typed subkey components. The client sends those components in a dedicated gRPC operation or request field. The server validates and maps the key to one configured shard, then pins the session to that shard's pool.

Routing policies could start with an explicit range map or a stable hash-to-shard map. Later, the registry could support rendezvous hashing, a versioned routing table, or application-provided routing rules.

**Pros**

- Reuses OJP's datasource and pool abstractions and server-side connection lifecycle.
- Gives applications portable JDBC-facing behavior without requiring each backend driver's proprietary shard API.
- Keeps credentials and shard topology on the server; clients send a key, not a shard URL.
- Allows server-side admission control, metrics, failover, and pool budgets to remain per shard.
- Can be introduced incrementally for one-shard-per-session behavior.

**Cons**

- Requires new key serialization and a stable supported set of Java/JDBC subkey types. `ShardingKey` itself is opaque, and arbitrary keys built by external driver implementations cannot be assumed serializable.
- Requires shard-map configuration, validation, reload behavior, and clear unknown-key behavior.
- Requires connection/session changes so all operations (including metadata, LOBs, read/write splitting, and XA work) stay on the selected shard.
- Resharding changes hash/range ownership and may need migration tooling; a naïve modulo hash remaps many tenants when shard count changes.
- JDBC does not make cross-shard joins, global sequences, or referential integrity work automatically.

**Best fit:** OJP-operated deployments with known shard pools and a stable application key, such as tenant ID.

### 2. Delegate to a shard-aware backend JDBC driver or datasource

Forward a supported key to the backend driver's `Connection.setShardingKey(...)`, or use the JDBC 4.3 datasource acquisition overload that accepts a key when the provider and driver support it. This would let database-vendor routing determine the destination.

**Pros**

- Leverages database-native topology, routing, and failover where available.
- Avoids duplicating vendor-specific shard discovery or key-to-shard algorithms in OJP.
- Can be a smaller feature for a deployment with one known shard-aware driver and datasource.

**Cons**

- Support and semantics vary by database vendor, driver, and datasource provider; it is not a backend-neutral OJP capability.
- The key must be reconstructed in a form accepted by the backend driver. OJP cannot simply forward an arbitrary client-side `ShardingKey` object across gRPC.
- A pooled physical connection may already have been acquired or associated with state before the key arrives. OJP must prove the driver/provider safely routes or reconfigures that connection.
- Does not provide a uniform server-owned pool per shard or OJP-level visibility into the chosen destination.

**Best fit:** A targeted integration for a database whose supported JDBC driver already provides shard-aware datasource routing. It is not a general OJP sharding strategy.

### 3. Treat each shard as an explicitly named datasource

Applications select a datasource name (or JDBC URL alias) before connecting, and OJP routes to that datasource using its existing connection-hash and pool setup. This resembles explicit routing in application code rather than implementing the JDBC ShardingKey contract.

**Pros**

- Closely matches current per-datasource pool creation and configuration.
- Makes the chosen shard explicit and easy to debug.
- Requires little or no opaque key transport.

**Cons**

- The application must choose the shard before opening a connection; it does not honor `Connection.setShardingKey(...)`.
- Every application duplicates the key-to-datasource mapping, creating drift and configuration mistakes.
- Updating mapping or credentials across many clients is operationally expensive.
- Does not provide a transparent path from existing JDBC sharding APIs to OJP behavior.

**Best fit:** A low-risk transitional solution or a deliberate application-managed routing mode.

### 4. Route schemas or tenant namespaces within a shared shard

Use the key to select a schema, catalog, tenant namespace, or database role on an already selected backend. OJP could set the backend connection's schema or call a configured vendor-specific session command.

**Pros**

- Can provide tenant isolation when the database hosts many tenants in one physical database.
- May use fewer connection pools and fewer database instances than one database per tenant.
- OJP already proxies operations such as `setSchema`, so there is a starting point for session-scoped state.

**Cons**

- Schema/catalog identifiers and session commands are vendor-specific and require strict allowlisting.
- Schema selection is not the same as physical shard selection and does not provide shard-level capacity isolation.
- Pooled connection state must be reset before reuse to prevent cross-tenant leakage.
- Applications can still issue unqualified SQL or otherwise escape a schema boundary unless the database enforces isolation.

**Best fit:** A separate tenant/schema-routing mode, not a substitute for shard routing.

### 5. Scatter/gather SQL and cross-shard transactions — later, separate capabilities

Use the ShardingKey to select a shard for ordinary operations, then add SQL parsing and fan-out for queries that span shards. Potential future extensions include aggregate merging, distributed pagination, global ordering, and coordinated writes.

**Pros**

- Could support analytics and administrative queries spanning many shards.
- Offers a path toward higher-level database virtualization beyond connection routing.

**Cons**

- Requires SQL dialect-aware parsing, rewriting, merge semantics, and failure handling.
- Correct aggregation, ordering, pagination, generated keys, and locking are nontrivial and may differ by backend.
- Cross-shard writes require distributed transaction or compensating-action semantics; they are not made atomic by `setShardingKey`.
- Fan-out can increase load and tail latency substantially.

**Best fit:** A separately scoped product capability after single-shard routing is stable. It should not be included in an initial ShardingKey implementation.

## Key design decisions and edge cases

1. **Key representation:** Define OJP's supported subkey values and SQL types. Preserve component order and type, validate unsupported values, and avoid Java serialization. Add explicit protocol fields rather than sending `toString()` or opaque object bytes.
2. **Mapping ownership:** Keep shard URLs, credentials, and routing configuration server-side. The client should normally send the logical key, not an arbitrary target shard address.
3. **Routing algorithm:** Begin with an explicit, versioned mapping or a stable hash. Include an algorithm/version identifier so configuration changes do not silently reinterpret existing sessions.
4. **Pinning and mutation:** Pin the logical session to one shard before its first backend operation. Define deterministic behavior for later `setShardingKey` calls. Changing shards after statements, transaction start, or creation of session-bound resources can invalidate connection state; rejecting such a change is safer than silently moving it.
5. **`setShardingKeyIfValid`:** Return `false` without changing the existing route when the key is malformed, unmapped, or cannot be applied under the session's current state. The throwing variants should report a clear `SQLException`.
6. **Transactions and XA:** Keep one transaction on one shard for an initial version. XA may use the selected shard's XA datasource, but cross-shard XA coordination is a separate design with recovery and resource-manager implications.
7. **Read/write splitting:** Select the shard first, then apply replica routing within that shard. Replica configuration must be associated with the shard, not merely the logical datasource.
8. **Availability and concurrency:** A missing/unavailable shard should produce explicit error behavior; silently routing to another shard risks returning or writing the wrong tenant's data. Repeated or concurrent key-setting calls need defined ordering.
9. **Observability:** Emit shard identity as a bounded metric/log attribute and track selected shard, route-table version, rejection, and failover. Avoid using raw tenant keys as metric labels or logging sensitive identifiers.
10. **Compatibility:** Preserve current behavior for connections that never use sharding. Existing tests that assert unsupported behavior should be replaced with targeted routing tests only when a shard configuration is present; unsupported/unconfigured behavior should remain explicit.

## Recommended staged approach

### Phase 0 — agree on product semantics

Choose the initial target use case (physical database shards vs. tenant schemas), supported key types, server configuration model, unknown-key policy, and whether key changes after first use are rejected. Avoid promising generic JDBC routing until these are defined.

### Phase 1 — minimal OJP-managed single-shard routing

- Add an OJP-owned key builder and typed key representation.
- Add server-side shard datasource configuration and a deterministic key-to-shard resolver.
- Carry key-setting through a dedicated protocol path; keep the logical datasource/pool identity separate from the selected shard identity.
- Resolve and pin a shard before the first backend connection is borrowed.
- Route all operations in the session to the pinned shard and reject unsafe rebinding.
- Support only local (non-XA) transactions on one shard initially, or explicitly support XA only when its chosen shard has an XA pool.

### Phase 2 — operational hardening

Add route-table versioning, shard health and admission handling, routing metrics, configuration reload tests, read/write replica association, XA behavior tests, and multi-server consistency tests. Document shard migration as an operational workflow before enabling automated remapping.

### Phase 3 — optional advanced routing

Consider vendor-native delegation, tenant-schema routing, resharding assistance, and only then any cross-shard query or transaction features. Keep these separate from the initial JDBC method support.

## Recommendation

Proceed with a design for **OJP-managed, server-configured shard datasources** and a minimal contract of **one key → one shard → one pinned JDBC session**. It aligns with OJP's existing server-side pools and lazy regular-connection lifecycle, while providing consistent behavior across backend drivers. Implement the standard JDBC methods only after defining the key wire format, mapping configuration, and rebinding semantics.

Do not treat passing a `ShardingKey` through the existing generic reflection path as a complete solution, and do not imply that ShardingKey support alone provides SQL fan-out or cross-shard transactions. Keep backend-native delegation as an optional adapter for specific vendors, and retain explicit datasource selection as a transitional escape hatch.

## Repository references

- `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java` — OJP JDBC connection implementation and resource-call proxy.
- `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Driver.java` — JDBC connect flow and properties sent to the server.
- `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/postgres/PostgresConnectionExtensiveTests.java` and `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/h2/H2ConnectionExtensiveTests.java` — current unsupported-method assertions.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java` — server datasource setup and lazy regular-session behavior.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/SessionConnectionHelper.java` and `ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java` — physical backend connection acquisition and session pinning.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/readwrite/ReadWriteDataSourceRegistry.java` — existing read/write datasource routing registry.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/JavaSqlInterfacesConverter.java` — JDBC interface classification, including sharding interfaces.
- `ojp-grpc-commons/src/main/proto/StatementService.proto` — current connection/session protocol, which has no ShardingKey representation.
