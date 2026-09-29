# Slow Query Segregation Per Datasource/Pool — Analysis

## Executive Summary

Slow Query Segregation (SQS) is currently **enabled and configured globally on an OJP server**, but its runtime state is already **isolated per connection pool**. The server creates one `AdmissionControlManager` for each connection hash. Each manager owns its own slot manager and query-performance monitor, so query classification and learned baselines do not combine across pools.

The limitation is configuration scope: one server-level `ojp.server.slowQuerySegregation.*` configuration is applied to every pool. There is no current setting to enable SQS for one datasource and leave it disabled for another, or to tune its slot allocation and thresholds separately per datasource.

Supporting datasource-specific behavior should therefore be a configuration-resolution change, not a redesign of the segregation algorithm. A server-owned policy should resolve global defaults plus datasource overrides before each pool's manager is created. No gRPC protocol change appears necessary: datasource name and pool configuration are available in the server's connection setup path.

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

Use a **datasource name** as the operator-facing override key, and resolve that policy for every connection hash belonging to that datasource name. A connection hash is an implementation identity that changes when URL or credentials change; it is not a practical configuration key. If two pools use the same datasource name, they should receive the same named policy unless a later requirement explicitly calls for pool-identity-level overrides.

Use server-owned configuration for these settings. Per-client controls would let an application influence shared server resource allocation and could make pool behavior depend on which client connects first.

A clear precedence model is:

1. Datasource-specific property, when set.
2. Existing server-wide SQS property, when no datasource override is set.
3. Existing default, when neither is set.

This preserves existing deployments while allowing selected datasources to opt in or out and override selected tuning values. The semantics of the existing global `enabled` flag should be explicitly documented as the default, rather than as an unconditional kill switch, if a datasource override is allowed to enable SQS while the global value is false. Alternatively, if a hard server-wide kill switch is required, it must take precedence over every datasource override.

### 2. Resolve effective configuration at pool creation

Introduce a small immutable effective-policy representation or equivalent resolver. It should:

- Accept the datasource name and global `ServerConfiguration`.
- Apply per-datasource overrides independently for enablement and each supported SQS tuning property.
- Validate and normalize values using the same rules as global settings.
- Supply backward-compatible global/default values when an override is absent.
- Be resolved before the manager is created, then passed into `CreateSlowQuerySegregationManagerAction`.

The current connection setup already knows the datasource name and pool sizing when it invokes manager creation. Threading the resolved policy through this existing path should avoid changes to the gRPC contract. The XA path should use the same policy resolution while retaining its existing XA-specific slot-capacity and timeout behavior.

### 3. Keep admission control distinct from SQS

Admission control is always active for pooled connections. A datasource override of `slowQuerySegregation.enabled=false` must continue to create an admission-control-only manager, not disable the manager or remove the semaphore. When SQS is enabled for a datasource, only that manager should use the slow/fast classification and slot split.

The configuration model and logs should describe these as distinct modes so operators do not mistake “SQS disabled” for “admission control disabled.”

### 4. Preserve per-pool isolation and lifecycle

Continue storing and retrieving managers by `connHash`, and continue giving each manager its own performance monitor and slot accounting. Do not share a query baseline across datasources merely because they use the same server-level defaults.

Resolve policy when a pool is created, not per statement. If pool configuration can be reloaded dynamically in the future, define how the corresponding manager is updated or replaced, and how permits held by active sessions are handled. Runtime reload is not required for an initial implementation; server restart/reconnection semantics can remain unchanged.

### 5. Make the effective policy observable

At pool initialization, log the datasource name, effective enabled state, and effective tuning values (without logging credentials or other sensitive connection details). Where SQS status/metrics are exposed, associate them with datasource identity so operators can verify that overrides took effect. Avoid requiring operators to interpret opaque connection hashes.

## Recommended Configuration Shape

Retain the existing `ojp.server.slowQuerySegregation.*` properties as global defaults. Add a documented namespace for server-side datasource overrides, with a consistent form for enablement and every tunable value, for example:

```properties
ojp.server.datasource.<datasource-name>.slowQuerySegregation.enabled=true
ojp.server.datasource.<datasource-name>.slowQuerySegregation.slowSlotPercentage=30
```

This is an illustrative shape, not an existing supported configuration. The final syntax should account for datasource names containing dots or other property delimiters; an explicit configuration file structure or escaped/encoded datasource key may be safer than direct property-name concatenation. Per-field fallback is preferable to requiring every datasource override to repeat all global settings.

Do not introduce pool-hash keyed properties in the initial design. Hashes are opaque and include connection-specific inputs, so they are brittle for operations. If separately configurable pools with the same datasource name become a demonstrated requirement, add an explicit stable pool identifier and define its precedence over datasource-level policy.

## Risks and Design Decisions

| Area | Consideration |
|---|---|
| Global enablement precedence | Decide whether global `enabled=false` is a default that datasource settings can override or a hard kill switch. |
| Datasource-name syntax | Names may contain delimiters that collide with property-key syntax; the resolver must parse names unambiguously. |
| Pool vs datasource identity | Multiple connection hashes can map to one datasource name; define that they share the named policy while keeping runtime monitors independent. |
| Partial overrides | Missing individual fields should fall back to global values, not silently to unrelated hard-coded defaults. |
| Capacity and isolation | Each pool's slots remain bounded by its own pool capacity; per-pool settings do not create a server-wide database concurrency cap across pools. |
| XA parity | Ensure datasource-specific enablement and tuning do not accidentally change XA's existing slot-count or admission-timeout semantics. |
| Observability | Logs and status should expose the effective datasource-level policy without exposing credentials or making connHash the only useful label. |
| Compatibility | Existing global properties and their defaults must produce the same behavior when no datasource overrides are configured. |

## Suggested Validation

An implementation should cover at least:

1. No datasource overrides: behavior matches the current server-wide configuration.
2. SQS enabled for one datasource and disabled for another: each resolves the expected mode while both retain admission control.
3. A per-datasource tuning override changes only that datasource's manager.
4. Two connection hashes with the same datasource name resolve the same policy but retain independent slots and performance histories.
5. Two datasource names targeting the same database resolve independently.
6. Partial and invalid overrides fall back or fail according to a documented, consistent validation rule.
7. XA and non-XA pool creation use the same policy resolution without changing their existing capacity/timeout handling.
8. Execution continues to select the manager using the session's connection hash.

## Recommendation

Implement datasource-scoped **server-side policy overrides**, while retaining global properties as defaults and retaining one manager per connection hash. This is the smallest architectural extension because the runtime segregation and isolation boundaries already exist; the missing piece is resolving different effective configuration for each datasource before manager creation.

Before implementation, settle the global-enable precedence and datasource-key syntax. Keep all datasource overrides server-controlled, preserve always-on admission control when SQS is off, and treat dynamic reload and connection-hash-level overrides as out of scope unless separately required.

**Confidence: High (95%)** that enablement and tuning are currently global while runtime admission/SQS state is per connection hash. This is directly supported by the server configuration fields, manager creation branches, manager map key, and statement execution lookup. The proposed configuration shape is a recommendation rather than an existing project convention; its final syntax and global-enable precedence still need an explicit product decision.

## Implementation References

- [Server configuration](../../ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java)
- [Manager creation and policy application](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/CreateSlowQuerySegregationManagerAction.java)
- [Per-connection-hash manager storage](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/ActionContext.java)
- [Connection hash generation](../../ojp-server/src/main/java/org/openjproxy/grpc/server/utils/ConnectionHashGenerator.java)
- [Statement execution through admission/SQS manager](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/CommandExecutionHelper.java)
- [Always-on admission control rationale](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md)
- [Slow Query Segregation feature overview](../designs/SLOW_QUERY_SEGREGATION.md)
