# OJP Server SQL Injection Protection - Implementation Analysis

**Date:** November 11, 2026

**Status:** Completed implementation analysis; proposed design, not implemented

**Scope:** Server-side defense in depth, not a runtime implementation

**Source baseline:** `a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71` (`main`).

**Confidence:** High for the cited execution flow and the limits of proxy-side
prevention; medium for the proposed design and effort until parser, coverage,
and performance prototypes are completed. No exploitable vulnerability is
claimed from this design review alone.

## 1. Executive Recommendation

SQL injection prevention starts with application-side parameter binding.
A JDBC proxy receives SQL after application code has constructed it; it cannot
reliably reconstruct which characters came from trusted code and which came
from user input. Server-side inspection must therefore be described as a
configurable SQL policy boundary, not a guarantee that arbitrary application
SQL has been made safe.

**Recommended direction:** an optional, server-owned SQL firewall with an
initial observation mode and explicit enforcement for selected workloads.
Prefer approved parameterized query templates for stable applications and
dialect-aware structural restrictions where a parser has been validated.
Do not silently rewrite SQL, escape or interpolate parameters, or market
keyword matching as complete protection.

Application parameterization, safe stored procedures, database least privilege,
and protected access to OJP remain necessary. This document proposes work;
it does not introduce protection or certify the current server against attacks.

## 2. Threat Model and Limits

The target threat is untrusted application input changing the SQL structure
submitted through OJP. Also consider a caller submitting arbitrary SQL directly
to the proxy: client-side checks alone do not form a server security boundary.
The deployment must prevent direct database access around OJP if it relies on
proxy policy enforcement.

| Situation | What OJP could enforce | What it cannot infer |
|---|---|---|
| Correctly parameterized statement | Approve the template and preserve typed parameter binding | Whether a bound account ID belongs to the end user |
| Input concatenated before `prepareStatement` | Reject an unapproved template or disallowed structure | Which SQL characters came from input; preparing already-unsafe SQL does not repair it |
| Plain statement with embedded literals | Restrict explicitly approved SQL and statement capabilities | Whether a legal-looking literal or condition expresses application intent |
| Stored procedure or function | Restrict callable targets and visible call syntax | Dynamic SQL, triggers, or side effects inside the database |
| Dynamic identifiers or sort direction | Require approved structural choices | Make table names safe by binding them as ordinary values |
| Reused, previously stored input | Apply the same boundary to the later SQL submission | Track the original provenance of second-order input |

This is not row-level authorization, a replacement for database permissions,
a generic database activity monitor, or protection from every expensive query.
A read-looking query can call side-effecting functions, access sensitive system
objects, or exhaust resources. A "SELECT only" rule is not a read-only guarantee.

## 3. Current Execution Surface

The inventory below describes current behavior, not an implemented firewall.
Source links in section 11 pin the reviewed revision.

| Surface | Verified behavior | Required change |
|---|---|---|
| Protocol and clients ([S1], [S2]) | SQL, typed values, statement UUID, and properties are transported separately; no trustworthy SQL provenance or explicit plain/prepared indicator is supplied | Evaluate on the server; do not infer safety from client API choice or parameter count |
| Query execution ([S3]) | Query-cache lookup happens before enhancement/execution; nonempty parameters select JDBC preparation, otherwise plain statement execution | Check policy before returning cached data as well as before JDBC; include zero-parameter prepared SQL |
| Binding ([S4]) | `StatementFactory` prepares SQL and `ParameterHandler` uses typed JDBC setters | Preserve binding; do not scan ordinary bound values as SQL |
| Update and statement reuse ([S5]) | Update execution can reuse a statement UUID, bind parameters, add a batch, or execute | Evaluate the immutable SQL actually associated with the resolved statement, not only request SQL |
| Batches ([S6]) | Plain `addBatch(sql)`/`executeBatch()` use `callResource`; prepared `addBatch()` uses the update RPC followed by resource execution | Cover both transports and retain SQL/policy metadata for every queued entry |
| Callable and reflection ([S7]) | Callable preparation/execution and chained JDBC resource methods use generic reflected dispatch | Classify supported methods and overloads before invocation; track callable resources |
| Streaming LOB creation ([S8]) | `CreateLobAction` can prepare SQL supplied in stream metadata | Guard this preparation path too; deny before consuming or leaking unnecessary streaming resources |
| Existing parsers ([S9]) | Server already depends on Calcite 1.42.0 and JSqlParser 4.9 | Evaluate reuse before adding a dependency; neither is a proven cross-dialect security validator |
| Enhancement and extraction ([S10]) | Calcite enhancement uses query parsing and passes original SQL through on failure; table extraction returns an empty set on parser failure | Do not reuse fallback/empty results as approval; independently validate effective SQL |
| Cache security heuristics ([S11]) | Cache-specific checks examine comments and parameter substrings | Keep cache defenses separate from SQL authorization; heuristic hits do not establish injection |
| Identity and transport ([S12]) | TLS/client-certificate authentication are configurable and default off; clients supply credentials and a client UUID | Establish a verified principal-to-policy/session mapping; routing hashes and caller metadata are not authentication |
| Errors and visibility ([S13]) | SQL exception metadata includes reason, SQLState and vendor code; logs/metrics can include raw SQL, and some parameter logs/errors include values | Reuse the transport error mechanism with non-retryable, redacted policy errors; review existing telemetry as part of the feature |

These are architectural requirements and existing behavior observations, not
proof that a malicious remote caller can exploit any particular deployment.
The reviewed server configuration and connection flow do not establish a
ready-made authenticated per-workload policy identity. That must be verified
and designed, particularly for multi-tenant deployments.

## 4. Compare the Available Approaches

| Approach | Benefits | Limits and recommendation |
|---|---|---|
| Application parameterization | Separates data values from SQL structure using existing JDBC semantics | Primary prevention; OJP cannot retrofit it after concatenation |
| Strict approved-template allowlist | Small, reviewable permitted SQL set; no need to guess malicious intent | Operational cost for migrations and dynamic SQL; preferred first enforceable workload profile |
| Dialect-aware AST policy | Can restrict statement classes, referenced objects, calls, and multiple statements | Parser coverage and backend grammar must agree; prototype before selecting a library |
| Regex/keyword or risk-score detection | Cheap signals for observation and investigation | Bypasses and false positives; not a standalone enforcement guarantee |
| Existing SQL optimizer/enhancer | Potential reuse of parsing infrastructure | Optimization is not security validation; must not inherit permissive fallback or semantic rewriting |
| External database firewall | May provide mature dialect support and central operations | Additional product, licensing, topology, latency, and deployment responsibilities; evaluate if broad coverage is urgent |

There are two different kinds of "template allowlist." A strict registry can
compare exact, approved parameterized SQL text, scoped to a trusted workload.
This rejects formatting changes but avoids needing a generic SQL normalizer.
An AST-derived structural allowlist is more flexible but inherits parser risk.
Never replace every literal with a placeholder indiscriminately: removing
constants or predicates can hide security-relevant structure. Likewise, a
template must preserve operators, identifiers, clauses, and parameter positions.
Neither approach validates end-user authorization.

Do not learn and immediately approve SQL from production traffic. Observed
templates are candidates for offline review, not trusted evidence of safety.
Protect approved-template artifacts and changes like application code.

## 5. Proposed Enforcement Architecture

### One decision service, complete coverage

Use one server-local policy evaluator, made available through the existing
`ActionContext` wiring and called from every path that can prepare or execute
SQL, rather than a check implemented only in the Java client or only in one
gRPC handler. Its inputs should include the SQL template, operation
kind, trusted policy scope, dialect context, and policy revision. Parameter
metadata may be useful; ordinary bound values must not be parsed as SQL.

An evaluation result should explicitly distinguish `ALLOW`, `DENY`, and
`UNSUPPORTED`/evaluation failure with stable internal reason codes. Enforcement
must map unsupported syntax and evaluation failures to a documented rejection;
it must not silently execute unchecked SQL. Observation records the outcome but
preserves ordinary execution, except when an independent hard request limit
rejects the request. Disabled mode preserves existing behavior.

Order the decision before query-cache lookup/return, database preparation or
execution, and expensive resources where possible. A query-cache hit can expose
data without executing SQL, so a JDBC-only guard is incomplete. If dialect
resolution requires an established
connection, obtain it safely and release it on denial; do not make a blanket
claim that every rejected request can avoid pool acquisition. Never hold a
database connection while waiting for an unbounded parser queue.

If SQL is transformed, policy approval must cover the actual SQL submitted to
JDBC as well as any relevant original-template restrictions. A cached or
prepared statement needs an immutable link to its approved SQL and scope;
execution must honor the active policy revision. A policy reload must not
leave already-prepared resources as a permanent bypass.

When a request contains both a statement UUID and SQL text, resolve the resource
under its owning session, then use its stored SQL identity for the decision.
Reject inconsistent metadata rather than approving request SQL and executing
a different previously prepared statement. Do not equate "no parameters" with
"plain statement": zero-parameter prepared statements share that wire shape.

### Batch and transaction semantics

For a batch received as one request, preflight every visible element before
executing any of it. A denied element should not leave earlier elements
executed merely because policy evaluation was interleaved with JDBC calls.
This is a policy guarantee, not a promise of database-level atomic execution:
ordinary database failures and driver batch semantics still apply.

Where entries arrive through separate resource calls, validate at admission
and revalidate the complete retained batch against the applicable revision
before execution. Define the existing client's exception/update-count behavior
for rejected batches without fabricating successful counts.

A denied request must not execute a hidden commit, rollback, retry, or XA
transition. Preserve transaction state and require the caller to choose
recovery. Resource cleanup must release only resources allocated for the denied
operation, not unrelated resources belonging to its session.

### Resource dispatch boundary

Inventory SQL-bearing method overloads, not just methods named `executeQuery`.
A generic resource invocation API can reach connection preparation, statement
execution, batch assembly, callable execution, and future JDBC extensions.
SQL inspection must not happen after reflective invocation has already reached
the database. Conversely, do not disable harmless cursor/metadata/lifecycle
operations indiscriminately. A supported-method policy is needed where a
resource method cannot be classified safely. Include chained calls and SQL
preparation through LOB stream metadata, not only ordinary unary RPCs.

## 6. Parser, Dialect, and Performance Requirements

Evaluate the already included Calcite and JSqlParser facilities first; do not
select a library merely because it accepts a simple query.
Record license compatibility, release maintenance, dependencies, supported
syntax, SQL-list parsing, resource limits, and AST features needed by policy.
This analysis does not claim that any candidate covers every OJP database.

The evaluation corpus must cover PostgreSQL, MySQL, MariaDB, Oracle, SQL Server,
DB2, and H2, including JDBC escapes and dialect-specific quoting/comments.
Account for CTEs with modifying statements, procedure blocks, vendor hints,
JSON operators, dollar-quoted strings, national/alternative quoted strings,
quoted identifiers, and semicolons inside strings. A semicolon split or first
keyword check is insufficient. Support for one dialect is not evidence for
another, and syntactically valid SQL need not be allowed SQL.

Derive dialect from server-controlled datasource configuration and verified
database context, not a caller's assertion. Schema/search path, SQL modes,
identifier case, and temporary objects can affect interpretation. If policies
authorize objects, fully qualified names and database grants are safer than
assuming an unqualified identifier always refers to the same object.

Bound input size, batch length, parse complexity/depth, concurrent evaluations,
and cache memory. A timeout alone is not a CPU bound if the parser ignores
interruption. Do not run uncontrolled parsing work on gRPC event-loop threads;
first establish the actual executor model and a bounded execution strategy.

Cache only decisions that are safe to reuse. Include policy revision, dialect,
trusted scope, SQL identity, and any relevant session context in the cache key.
Do not use an existing statistics hash or a literal-stripped fingerprint as an
authorization identity without proving equivalence. Keep cached syntax and
policy decisions separate when their lifetimes differ.

Benchmark cold parses, cache hits, policy reloads, high-cardinality SQL, large
batches, and adversarial input. Measure p50/p95/p99 added latency, throughput,
CPU, allocations, cache memory, and impact on connection acquisition and slow
query segregation. Set numerical release budgets using OJP's baseline and
target deployment requirements, not invented performance claims.

## 7. Configuration, Identity, and Operations

The following is a proposed configuration model, not existing property names:

| Setting | Required behavior |
|---|---|
| Mode | `OFF` by default for compatibility; explicit `OBSERVE` and `ENFORCE` |
| Policy scope | Server-owned datasource/workload mapping bound to a verified principal or database account |
| Approved SQL and capabilities | Versioned templates; explicit statement/object/call and multi-statement rules |
| Dialect | Verified per datasource; enforcement rejects unknown/unvalidated syntax |
| Limits | Input/batch/parser/cache bounds, with explicit limit-exceeded errors |
| Policy artifact | Admin-controlled location and revision; no caller-provided override |
| Audit | Redacted, bounded, rate-limited output and defined retention/access |

Do not trust a JDBC client name, a requested database username alone, a session
identifier, or arbitrary request properties as proof of identity. Establish how
server-side authentication and resource ownership bind a session to a policy.
If that binding is insufficient, it is a prerequisite for tenant-specific
enforcement, not a detail to postpone until after rollout.

Validate policy files at startup and atomically activate a complete revision.
An invalid initial enforcement policy must stop the protected configuration
from serving unchecked requests. A rejected reload should retain the last
valid revision and produce an explicit operator-visible error. Do not let one
node fall back to `OFF` when others enforce. Distribute policy revisions using
deployment/configuration management first; a new distributed policy protocol
is not required for an initial implementation.

Return a stable SQL exception through the existing transport error path; agree
on SQLState/vendor code and batch semantics before implementation. Do not retry
policy denials as transient backend failures. Keep policy details out of client
error text and do not echo complete SQL or parameter values. Denials are not
backend outages: they must not inflate database failure metrics or trip circuit
breakers.

Audit reason, mode, policy revision, bounded scope identifier, operation kind,
and a non-reversible query identifier where needed. Raw SQL may contain secrets
even without parameters; low-entropy query hashes may be guessable, so consider
a keyed fingerprint. Do not use SQL, session IDs, or arbitrary query hashes as
unbounded metric labels. Review existing logs and traces as well as new events.

## 8. Required Verification

Tests for a future implementation must show the exact denied SQL never reaches
JDBC, not merely assert that an error was returned.

| Area | Required cases |
|---|---|
| Positive compatibility | ORM-generated and plain/prepared SQL, safe bound quotes/comments/keywords, nulls, binary/stream/LOB values, generated keys, callable parameters |
| Coverage | Dedicated RPCs, generic/chained resource execution, SQL in LOB streams, statement/prepared batches, callable statements, XA and non-XA, zero-parameter and reused prepared resources |
| Policy decisions | Approved template accepted; altered structure denied; unsupported dialect/syntax and parser failure reject in enforcement; observation records without blocking |
| Parser boundaries | Nested queries, vendor quoting/comments, multi-statement syntax, semicolons in strings, CTE writes, functions/procedures with side effects |
| No partial policy execution | A later denied batch member causes zero members of that request to execute; ordinary backend errors retain documented JDBC semantics |
| Lifecycle | No connection/statement/stream/slot leaks after denial; transaction and XA state unchanged; no inappropriate retries or circuit-breaker failures |
| Identity and revision | Scope isolation, client metadata cannot change policy, cross-session resources and UUID/SQL mismatches rejected, reload applies to old prepared resources, cached decisions, and query-cache hits |
| Operational safety | Invalid startup/reload, policy-version drift, bounded audit without SQL/values, redacted errors, cache eviction and parser exhaustion |
| Clients and databases | Real-server tests across supported database profiles and Java/non-Java client entry points; unchanged clients when enforcement is off |

Use focused policy/parser unit tests and real-server integration tests through
public client APIs. Reuse OJP's database fixture and enable-flag conventions.
Do not claim multi-database readiness based only on H2 or a mocked JDBC call.

## 9. Work Packages and Rollout

| Phase | Deliverable and exit criterion | Relative effort |
|---|---|---|
| 1. Scope and prototype | Execution-path inventory, trust binding, real SQL corpus, parser/strict-template experiment; choose an initial supported dialect/profile | Medium |
| 2. Core boundary | Single evaluator, complete execution/resource coverage, limits, typed denial errors, cleanup and focused tests | Large |
| 3. Observation | Redacted metrics/audit, baseline overhead measurements, offline template review and workload replay | Medium |
| 4. Narrow enforcement | Explicitly configured workload, immutable approved templates, failure policy, batch/reload/cache tests, operator runbook | Large |
| 5. Broader support | Validated AST restrictions and more dialects, callable policies, cross-client/database regression and adversarial corpus | Large and ongoing |

These are relative sizes, not calendar estimates. Coverage of generic resource
dispatch, identity binding, parser compatibility, and performance are the main
unknowns. Estimate engineer-weeks only after phase 1; runtime code alone is not
the full cost. Database fixtures, security review, operational documentation,
and long-term parser/rule maintenance must be included.

Keep the initial implementation internal to `ojp-server` unless the inventory
proves that existing protocol metadata is insufficient. Server-only enforcement
should not require clients to opt in or claim that SQL is safe. If new protocol
fields become necessary, use the canonical contract module and preserve mixed
client/server version behavior. A pluggable public SPI is not a prerequisite.

Release gates: prove all in-scope execution routes are covered, document every
unsupported feature, demonstrate absence of execution on policy denial, meet
agreed performance budgets, and complete dialect and lifecycle regression
coverage. Promote observation to enforcement per workload after reviewing
false positives; never automatically promote learned queries. A rollback is an
explicit operator decision because disabling enforcement removes a security
control, not merely a performance optimization.

## 10. Decisions Needed Before Implementation

1. Is the first deliverable strict approved-template enforcement for a bounded
   workload, broad suspicious-SQL detection, or general AST capability policy?
   Recommend strict templates plus observation; the other goals have different
   assurance and compatibility costs.
2. Which dialect and workload will be validated first, and may unsupported
   syntax be rejected there?
3. What authenticated server-owned identity selects policy, and is session and
   resource ownership sufficient for that scope?
4. Which plain statements, procedure calls, migrations, dynamic identifiers,
   and administrative operations must remain available, and under which
   separately governed profile?
5. What latency/memory budgets, SQL exception contract, policy reload behavior,
   audit retention, and administrator approval process are acceptable?

## 11. References

- [OWASP SQL Injection Prevention Cheat Sheet](https://github.com/OWASP/CheatSheetSeries/blob/master/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.md):
  parameterized values, fixed mappings for structural choices, stored procedure
  limits, and database least privilege.

The following links are pinned to the reviewed source baseline. Line ranges
refer to that revision, not future changes on `main`.

- **[S1] Contract:** [StatementService.proto](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-grpc-contract/proto/StatementService.proto#L126-L131),
  lines 126-131, 187-196, 220-225, 340-353, 473-484: query/update/resource RPCs
  and SQL/parameter/resource request fields. Lines 159-168 enumerate database
  types, including an unmapped-driver case.
- **[S2] Client serialization:** [Statement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java#L143-L159),
  lines 143-159; [PreparedStatement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/PreparedStatement.java#L90-L151),
  lines 90-97, 112-128, 140-151;
  [StatementServiceGrpcClient.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java#L100-L147),
  lines 100-118, 130-147.
- **[S3] Query/cache/enhancement ordering:** [ExecuteQueryAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/ExecuteQueryAction.java#L105-L201),
  lines 105-124, 136-175, 188-201.
- **[S4] Preparation and binding:** [StatementFactory.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/statement/StatementFactory.java#L103-L122),
  lines 103-122; [ParameterHandler.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/statement/ParameterHandler.java#L77-L82),
  lines 77-82, 180-205, 232-248.
- **[S5] Update/reuse:** [ExecuteUpdateAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/ExecuteUpdateAction.java#L123-L155),
  lines 123-155, 257-263.
- **[S6] Batch transports:** [Statement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java#L433-L451),
  lines 433-451; [PreparedStatement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/PreparedStatement.java#L166-L184),
  lines 166-184.
- **[S7] Callable/resource paths:** [Connection.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java#L165-L170),
  lines 165-170; [CallableStatement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/CallableStatement.java#L736-L746),
  lines 736-746, 869-878;
  [CallResourceAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/resource/CallResourceAction.java#L122-L247),
  lines 122-169, 182-215, 233-247.
- **[S8] LOB preparation:** [CreateLobAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/CreateLobAction.java#L218-L226),
  lines 218-226.
- **[S9] Existing dependencies:** [ojp-server/pom.xml](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/pom.xml#L218-L239),
  lines 218-223, 234-239.
- **[S10] Non-enforcing parsers:** [SqlEnhancerEngine.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SqlEnhancerEngine.java#L381-L410),
  lines 381-410, 477-499, 547-559;
  [SqlTableExtractor.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/SqlTableExtractor.java#L47-L110),
  lines 47-70, 83-110;
  [ServerConfiguration.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java#L141-L151),
  lines 141-151 (enhancer opt-in).
- **[S11] Cache heuristics:** [CacheSecurityValidator.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/CacheSecurityValidator.java#L24-L43),
  lines 24-43, 88-126.
- **[S12] Identity/transport configuration:** [ServerConfiguration.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java#L182-L184),
  lines 182-184; [GrpcServer.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcServer.java#L118-L140),
  lines 118-140, 277-290; [StatementService.proto](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-grpc-contract/proto/StatementService.proto#L10-L18),
  lines 10-18; [ConnectAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java#L78-L86),
  lines 78-86, 384-402.
- **[S13] Errors/logs/metrics:** [GrpcExceptionHandler.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcExceptionHandler.java#L52-L69),
  lines 52-69; [PreparedStatement.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/PreparedStatement.java#L295-L303),
  lines 121, 144, 170, 295-303;
  [ExecuteQueryAction.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/ExecuteQueryAction.java#L55-L56),
  lines 55-56; [OpenTelemetrySqlStatementMetrics.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/metrics/OpenTelemetrySqlStatementMetrics.java#L68-L79),
  lines 68-79; [ParameterHandler.java](https://github.com/Open-J-Proxy/ojp/blob/a1b3ef07172db5dbdf4f5bf2373419b0cbe3ec71/ojp-server/src/main/java/org/openjproxy/grpc/server/statement/ParameterHandler.java#L149-L160),
  lines 149-160.
