# Slow Query Segregation Per Datasource/Pool — Analysis

## Executive Summary

Slow Query Segregation (SQS) is currently **enabled and configured globally on an OJP server**, but its runtime state is already **isolated per connection pool**. The server creates one `AdmissionControlManager` for each connection hash. Each manager owns its own slot manager and query-performance monitor, so query classification and learned baselines do not combine across pools.

The limitation is configuration scope: one server-level `ojp.server.slowQuerySegregation.*` configuration is applied to every pool. There is no current client connection property to enable SQS for one datasource and leave it disabled for another, or to tune its slot allocation and thresholds separately per datasource.

Supporting datasource-specific behavior should therefore be a configuration-resolution change, not a redesign of the segregation algorithm. The application client should supply SQS settings as connection properties when it connects, alongside its other OJP connection configuration. The server must read those properties before creating the pool's manager. The JDBC driver already forwards OJP connection properties in `ConnectionDetails`, so this should not require a gRPC protocol change.

## Current Behavior

### Configuration scope

`ServerConfiguration` reads one set of server-wide properties, including:

- `ojp.server.slowQuerySegregation.enabled`
- `ojp.server.slowQuerySegregation.slowSlotPercentage`
- Slot idle and acquisition timeouts
- Classification mode, thresholds, multipliers, sample count, and baseline settings

`CreateSlowQuerySegregationManagerAction` reads those same `ServerConfiguration` values whenever it creates a manager. Thus, when SQS is enabled, every newly configured non-XA pool and XA datasource receives the same SQS policy. When it is disabled, each still receives always-on admission control in admission-control-only mode; that mode is not SQS.

### Runtime isolation

The server stores managers in a map keyed by `connHash`. The hash includes the JDBC URL, username, password, and datasource name, so different connection configurations (including different pools for the same database) can have separate managers. Each manager constructs its own `SlotManager` and `QueryPerformanceMonitor`; the monitor's observed query performance and classifications are therefore per connection hash.

Statement execution looks up the manager using the session's connection hash and runs through it. This means SQS capacity and query learning are already separate per pool, even though configuration is shared.

### Answer to the question

The belief is **correct for the control plane, but not for runtime state**:

| Concern | Current scope |
|---|---|
| Enable/disable SQS | Server-wide |
| SQS tuning properties | Server-wide |
| Admission/SQS manager | Per connection hash (pool identity) |
| Slot accounting | Per manager/pool |
| Query-performance tracking and classification | Per manager/pool |

In short, the server currently cannot opt in or tune SQS per datasource, but SQS does not share one global queue or learned query history across all pools.

## What Per-Datasource Support Requires

### 1. Define the policy's identity and precedence

Have the **application client** provide the SQS policy as OJP connection properties when opening its datasource connection. Resolve those values for the pool associated with that connection. A connection hash is an implementation identity that changes when URL or credentials change; it is not a practical configuration key. If the same connection hash is used by clients with different policies, the implementation must not silently let whichever client connects first determine behavior for all of them. Prefer requiring consistent SQS properties for clients sharing a pool, and detect/reject conflicts, rather than changing pool identity and accidentally creating duplicate database pools.

This is client-supplied configuration, but it controls server-side admission and resource allocation. The server must validate and bound all received values; it should not trust client input merely because it arrived as a connection property.

A clear precedence model is:

1. Client-provided SQS connection property, when set.
2. Existing server-wide SQS property, as a backward-compatible fallback where the client omitted that property.
3. Existing default, when neither is set.

This preserves existing deployments while allowing application clients to opt in or out and override selected tuning values. Decide whether the existing global `enabled` flag is only a fallback or remains a hard server-side kill switch. If it is a hard kill switch, it must take precedence over a client request to enable SQS; document that exception clearly.

### 2. Resolve effective configuration at pool creation

Introduce a small immutable effective-policy representation or equivalent resolver. It should:

- Accept the extracted client connection properties and global `ServerConfiguration`.
- Resolve client-supplied values independently for enablement and each supported SQS tuning property, using the documented fallback precedence.
- Validate and normalize values using the same rules as global settings.
- Retain backward-compatible global/default values when the client omits a property.
- Be resolved before the manager is created, then passed into `CreateSlowQuerySegregationManagerAction`.

The JDBC driver's `Driver` currently forwards OJP properties in the connection request, and the server's connection setup extracts client properties before configuring the pool. Threading the resolved policy from that connection setup into manager creation should avoid changes to the gRPC contract. The XA path should use the same policy resolution while retaining its existing XA-specific slot-capacity and timeout behavior.

### 3. Keep admission control distinct from SQS

Admission control is always active for pooled connections. A client connection property of `slowQuerySegregation.enabled=false` must continue to create an admission-control-only manager, not disable the manager or remove the semaphore. When SQS is enabled for a connection's pool, only that manager should use the slow/fast classification and slot split.

The configuration model and logs should describe these as distinct modes so operators do not mistake “SQS disabled” for “admission control disabled.”

### 4. Preserve per-pool isolation and lifecycle

Continue storing and retrieving managers by `connHash`, and continue giving each manager its own performance monitor and slot accounting. Do not share a query baseline across datasources merely because they use the same server-level defaults.

Resolve policy when a pool is created, not per statement. If pool configuration can be reloaded dynamically in the future, define how the corresponding manager is updated or replaced, and how permits held by active sessions are handled. Runtime reload is not required for an initial implementation; server restart/reconnection semantics can remain unchanged.

### 5. Make the effective policy observable

At pool initialization, log the datasource name, effective enabled state, and effective tuning values (without logging credentials or other sensitive connection details). Where SQS status/metrics are exposed, associate them with datasource identity so operators can verify that client settings took effect. Avoid requiring operators to interpret opaque connection hashes.

## Recommended Configuration Shape

The application should supply SQS settings through its OJP connection properties, for example via the `Properties` passed to `DriverManager.getConnection` or the datasource's OJP properties configuration:

```properties
ojp.slowQuerySegregation.enabled=true
ojp.slowQuerySegregation.slowSlotPercentage=30
```

These names are illustrative and are not currently supported properties. The application supplies values on the connection for which they should apply; they are not a server configuration namespace or a datasource-name-encoded server setting. The existing server-wide `ojp.server.slowQuerySegregation.*` properties can remain as compatibility fallbacks for clients that do not provide the corresponding connection property. Per-field fallback is preferable to requiring each client to repeat all settings.

Do not require clients to configure SQS by pool hash. Hashes are opaque and include connection-specific inputs, so they are brittle for application configuration. Client connection properties naturally travel with the connection request; define consistent settings for clients that reuse the same pool.

## Risks and Design Decisions

| Area | Consideration |
|---|---|
| Client/global precedence | Decide whether global `enabled=false` is a fallback clients can override or a hard server-side kill switch. |
| Shared-pool consistency | Clients that reuse the same connection hash/pool must not silently request conflicting SQS policies; define validation and conflict behavior. |
| Client property delivery | Ensure all supported client configuration paths (inline connection properties and OJP properties files) forward the settings at connection time. |
| Partial overrides | Missing individual fields should fall back to global values, not silently to unrelated hard-coded defaults. |
| Capacity and isolation | Each pool's slots remain bounded by its own pool capacity; per-pool settings do not create a server-wide database concurrency cap across pools. |
| XA parity | Ensure datasource-specific enablement and tuning do not accidentally change XA's existing slot-count or admission-timeout semantics. |
| Observability | Logs and status should expose the effective datasource-level policy without exposing credentials or making connHash the only useful label. |
| Compatibility | Existing global properties and their defaults must produce the same behavior when clients omit SQS connection properties. |

## Suggested Validation

An implementation should cover at least:

1. No client SQS properties: behavior matches the current server-wide configuration.
2. SQS enabled on one datasource connection and disabled on another: each resolves the expected mode while both retain admission control.
3. A client-provided tuning property changes only the associated pool's manager.
4. Two clients sharing a connection hash/pool with matching settings share its policy and manager; conflicting settings are detected rather than silently applied first-client-wins.
5. Two datasource connections can provide different policies even when they target the same database, while retaining separate connection identities and managers.
6. Partial and invalid overrides fall back or fail according to a documented, consistent validation rule.
7. XA and non-XA pool creation use the same policy resolution without changing their existing capacity/timeout handling.
8. Execution continues to select the manager using the session's connection hash.

## Recommendation

Implement **client-supplied SQS connection properties**, resolved by the server before manager creation, while retaining one manager per connection hash. Keep existing server properties only as documented compatibility fallbacks (unless product requirements designate the global enablement as a hard kill switch). This is the smallest architectural extension because the runtime segregation and isolation boundaries already exist; the missing piece is reading the client's connection-time policy and applying it consistently to the associated pool.

Before implementation, settle global-enable precedence, client property names, and behavior when clients sharing a pool specify conflicting settings. Keep all received values validated server-side, preserve always-on admission control when SQS is off, and treat dynamic reload as out of scope unless separately required.

**Confidence: High (95%)** that enablement and tuning are currently global while runtime admission/SQS state is per connection hash. This is directly supported by the server configuration fields, manager creation branches, manager map key, and statement execution lookup. The driver already forwards OJP connection properties to the server. The proposed SQS property names and conflict behavior are recommendations, not existing supported behavior, and require an explicit product decision.

## Implementation References

- [Server configuration](../../ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java)
- [Manager creation and policy application](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/CreateSlowQuerySegregationManagerAction.java)
- [Per-connection-hash manager storage](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/ActionContext.java)
- [Connection hash generation](../../ojp-server/src/main/java/org/openjproxy/grpc/server/utils/ConnectionHashGenerator.java)
- [Statement execution through admission/SQS manager](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/CommandExecutionHelper.java)
- [Driver connection-property forwarding](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Driver.java)
- [Always-on admission control rationale](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md)
- [Slow Query Segregation feature overview](../designs/SLOW_QUERY_SEGREGATION.md)
