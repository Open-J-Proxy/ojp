# Connection Pool Timeouts and Pre-Warming: Analysis

## Status

This is the initial source-based investigation, created before implementation. The
implementation decisions below are proposals and will be updated with verified
behavior and remaining limitations as work proceeds.

## Executive summary

OJP currently exposes one connection timeout property per pool type:
`ojp.connection.pool.connectionTimeout` and its XA counterpart. The default is
10,000 ms. In the regular pooled path this value is used as the semaphore
admission timeout, while OJP separately supplies a 1 ms backend pool timeout.
HikariCP enforces a 250 ms minimum, so a connection request can fail during pool
growth even though admission allows it to wait much longer. Commons Pool 2 uses
the 1 ms value directly for XA borrowing.

Pool creation is on demand, when a client first connects. Regular provider pools
are published after creation without verifying the configured minimum idle
connections. Hikari starts its pool on construction and creates connections
asynchronously; DBCP2 defers opening its pool until use. XA is different:
OJP calls `resizeBackendPool(maxPoolSize, minIdle)` before publishing its
registry, which synchronously prepares idle sessions.

## Current behavior

### Timeout properties and applied values

There are two user-facing timeout property *names*, one regular and one XA:

| Property | Default | Applied to |
|---|---:|---|
| `ojp.connection.pool.connectionTimeout` | 10,000 ms | Regular admission semaphore; also reused as the admission fallback for configured slow-query segregation |
| `ojp.xa.connection.pool.connectionTimeout` | 10,000 ms | XA admission semaphore; also reused as the admission fallback for configured slow-query segregation |

`CommonConstants.DEFAULT_CONNECTION_TIMEOUT` is 10,000 ms. Both
`DataSourceConfigurationManager` configuration classes read their respective
property with that default. The regular pool is built with
`FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS = 1`, independently of the property.
`HikariConnectionPoolProvider` clamps that to HikariCP's documented minimum of
250 ms; this is the source of the approximately 250 ms backend wait. DBCP2 maps
the 1 ms value to `BasicDataSource.setMaxWait`. XA maps it to
`GenericObjectPoolConfig.setMaxWait`.

Admission and acquisition can therefore wait independently and sequentially.
For a regular new session, `SessionConnectionHelper` waits for the admission
permit first, then calls `ConnectionAcquisitionManager.acquireConnection`,
which calls `DataSource.getConnection()`. The regular default admission timeout
is 10 seconds and the pool timeout is at most 250 ms for Hikari (or about 1 ms
for DBCP2); time spent in the two stages can add. For XA, the configured
admission timeout is also distinct from Commons Pool's borrow timeout, currently
set to 1 ms by the server.

An exception to the one-property model is slow-query segregation: explicit
server-side `slowQuery.fastSlotTimeout` and `slowQuery.slowSlotTimeout` settings
take precedence for those admission lanes. Separately, XA transaction-start
slot acquisition has a 60-second default (`DEFAULT_XA_START_TIMEOUT_MILLIS`);
that is not the datasource admission or pool-borrow timeout.

The regular Hikari acquisition manager also performs an exhaustion pre-check
when the pool is full and has no idle connections. It returns an error instead
of attempting a pool borrow in that state. It does not guard the pool's
under-capacity cold-start case, where creation is still in progress.

### Pool implementations

* **HikariCP:** `HikariConnectionPoolProvider.createDataSource` builds a
  `HikariDataSource`; construction starts Hikari's pool. OJP sets
  `initializationFailTimeout` to 10 seconds, but does not wait until all
  `minimumIdle` connections are ready before publishing the datasource. Hikari
  fills idle connections asynchronously. Its 250 ms connection-timeout minimum
  applies even though OJP requested 1 ms.
* **DBCP2:** `DbcpConnectionPoolProvider` sets `maxTotal`, `minIdle`, and
  `maxWait` on `BasicDataSource`. DBCP's pool initialization is lazy, so setting
  `minIdle` does not prove that physical connections exist before first use.
  Its `maxWait` is the configured provider value (currently 1 ms); unlike
  Hikari, the provider has no 250 ms timeout clamp.
* **Commons Pool 2 / XA:** `CommonsPool2XADataSource` configures blocking
  borrowing and a `maxWait` duration, and `borrowSession` calls
  `pool.borrowObject()`. OJP's XA registration path explicitly invokes
  `resizeBackendPool(maxPoolSize, minIdle)` before inserting the registry; the
  implementation calls `preparePool()` and has a manual `addObject()` fallback.
  This is synchronous pre-initialization, not a configurable feature, and a
  physical-connection failure prevents the registry from being registered.
  Connection construction and validation behavior is supplied through
  `BackendSessionFactory`.

### Initialization, admission, and other paths

Pools and per-datasource configurations are created on demand, not globally at
server startup. `ConnectAction` uses a per-connection-hash lock to serialize
regular datasource creation; the XA action similarly serializes registry
creation. The first client request discovers a datasource, creates its pool,
and then publishes it into server context. Regular connection acquisition and
session allocation remain lazy until the session helper borrows a connection.
Configured read replicas are another on-demand provider path and currently use
their own connection timeout property and sizing defaults.

The regular admission manager is configured from the regular connection timeout
and is sized against the actual (possibly multinode- and database-budget-limited)
pool size. XA admission is configured similarly. The admission permit is acquired
before the physical pooled connection. Pool initialization itself occurs before
the datasource is published, but regular provider initialization currently
doesn't establish the configured minimum connections. XA initializes the
minimum before publishing.

Multinode configurations divide requested `maximumPoolSize` and `minimumIdle`
by the healthy server count before creating each server's pool. Database
connection-budget management can further lower those values. Any pre-warming
must use those final local values to avoid exceeding per-database or multinode
budgets.

## Problems identified

1. The admission timeout is documented in places as a general connection wait,
   while its actual main role is semaphore waiting.
2. The regular underlying provider receives a 1 ms timeout, not the configured
   admission timeout. Hikari's 250 ms minimum is therefore the premature
   acquisition failure mechanism during cold pool growth.
3. Creating a pool object is not proof that its configured minimum connections
   are ready. Regular Hikari and DBCP pools may be published while empty or
   partially filled.
4. The XA path already has a distinct synchronous min-idle initialization step,
   but its acquisition timeout is still fail-fast and the prefill operation is
   not bounded by the new proposed acquisition-timeout setting.
5. Admission and pool waits are separately bounded, but slow-query lane
   timeouts, XA start-slot timeout, pool startup failure timeout, and direct
   replica configuration make it important to define which timeout bounds each
   stage. No stage should silently become unbounded.

## Proposed implementation

1. Add regular and XA `admissionTimeout` and `acquisitionTimeout` properties,
   each defaulting to 5,000 ms. Preserve the legacy `connectionTimeout` value:
   when it is the only timeout setting, split it evenly between admission and
   acquisition (floor/remainder behavior for odd values must be tested). When
   both new values are set, they take precedence over the legacy property; if
   only one new value is set, retain 5,000 ms for the other. Apply the same
   precedence rules to XA.
2. Use the admission value for semaphore admission and acquisition value for
   regular providers and XA borrow. Keep provider-specific constraints explicit:
   Hikari clamps values below 250 ms; DBCP2 and Commons Pool 2 have different
   edge behavior for zero/negative durations. Invalid values should be rejected
   or handled consistently rather than silently creating unbounded waits.
3. Add an opt-in pool pre-warming setting. After the final local pool sizes are
   determined and the provider creates the pool, synchronously acquire enough
   physical connections to verify the configured minimum, return them to the
   pool, then publish the datasource and create normal-workload admission
   state. Failure must close the partially initialized pool and return a clear
   connection initialization error. Dynamic first-use registration remains
   supported.
4. Extend the provider API with a warm-up contract or use a bounded,
   provider-agnostic checkout/return loop. Verify provider semantics first:
   Hikari's internal fill may be asynchronous, DBCP may block on its first
   checkout, and XA already offers `preparePool()`. Warm-up must not claim to
   bypass admission limits; it runs during registration before application
   connections are admitted, and uses the already budgeted pool size.
5. For parallel physical connection creation, ensure the provider can safely
   scale within its configured maximum and maintain cleanup on partial failure.
   Avoid an unbounded warm-up loop or multiplying warm-up threads across
   datasources/nodes.

## Compatibility considerations

* Preserve legacy regular and XA properties and their established per-datasource
  scoping. Document the precedence rule for mixed legacy/new values.
* Existing users who configure a legacy timeout will see that total timeout
  split between the two serial stages, preserving the approximate overall
  budget. For 10,000 ms, that is 5,000 ms at each stage.
* An enabled pre-warm mode intentionally delays first registration until the
  configured minimum is available. It can expose unavailable databases sooner
  and adds connection startup latency and baseline database usage. It should be
  opt-in initially.
* The recommended `minimumIdle = maximumPoolSize` is workload- and database
  capacity-dependent, not a universal default. Multinode allocation and
  `DatabaseConnectionBudgetManager` reductions must be respected before warming.
* XA prefill currently happens regardless of an opt-in pre-warm property;
  changing this behavior needs care to avoid regressing XA's existing
  initialization guarantee.

## Test strategy

* Add configuration tests for defaults, legacy-only split (including odd
  values), each single-new-property case, both-new-property precedence, mixed
  legacy/new settings, invalid and boundary values, and regular/XA parity.
* Add provider tests verifying timeout propagation and documented Hikari's
  250 ms minimum, DBCP `maxWait`, and Commons Pool 2 `maxWait`.
* Add pre-warm tests for min-idle population, `minimumIdle == maximumPoolSize`,
  concurrent first registration (exactly one pool), slow creation, unavailable
  database / partial failure cleanup, and ready-before-admission ordering.
* Add a cold-start connection storm test against an initially empty pool and
  verify requests wait within the configured acquisition budget rather than
  failing at the Hikari minimum. Confirm saturated pools still reject excess
  workload through bounded admission.
* Exercise XA and multinode pool-size division/budget caps. Run focused module
  unit tests first, then relevant integration tests; report measured startup
  duration, physical connection count, latency, and request outcomes where the
  existing test harness permits reliable measurements.

## Relevant source files

* `ojp-grpc-commons/src/main/java/org/openjproxy/constants/CommonConstants.java`
  — property names, 10-second default, and 1 ms fail-fast constant.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/pool/DataSourceConfigurationManager.java`
  — regular and XA property parsing and defaults.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java`
  — regular lazy registration and pool construction.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/SessionConnectionHelper.java`
  and `AdmissionControlManager.java` — admission-before-acquisition ordering.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/ConnectionAcquisitionManager.java`
  — regular pool borrowing and Hikari exhaustion pre-check.
* `ojp-datasource-api/src/main/java/org/openjproxy/datasource/PoolConfig.java`
  and `ConnectionPoolProvider.java` — provider-neutral pool configuration and
  SPI contract.
* `ojp-datasource-hikari/src/main/java/org/openjproxy/datasource/hikari/HikariConnectionPoolProvider.java`
  — Hikari connection timeout clamp and datasource construction.
* `ojp-datasource-dbcp/src/main/java/org/openjproxy/datasource/dbcp/DbcpConnectionPoolProvider.java`
  — DBCP `maxWait` mapping and lazy datasource setup.
* `ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java`
  and `ojp-server/.../HandleXAConnectionWithPoolingAction.java` — Commons Pool
  borrow, timeout mapping, and existing XA min-idle initialization.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/MultinodePoolCoordinator.java`
  and `DatabaseConnectionBudgetManager.java` — final local pool sizes.
* `ojp-server/src/main/java/org/openjproxy/grpc/server/readwrite/ReadWriteDataSourceManager.java`
  — separately configured replica pool path.
