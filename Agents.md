# Agents.md — AI Agent Guide for Open J Proxy (OJP)

This file provides guidance for AI coding agents (GitHub Copilot, etc.) working inside this repository. Read it before making any changes.

---

## Agent Behavior Guidelines

- Use simple language and simple examples to explain things.
- Be honest, even when the honest answer is "I don't know" or "this approach has problems."
- Look for the best technical solution, not just the most convenient one.
- Don't default to agreement — push back when something seems wrong or suboptimal.
- Proactively offer questions, opinions, suggestions, and concerns rather than waiting to be asked.
- When root-causing an issue or suggesting a solution, always report an honest confidence level — either as a percentage (0–100%) or as a label (Low / Medium / High) — and briefly explain what is driving that confidence or uncertainty.

---

## What OJP Is

OJP is the **world's first open-source JDBC Type 3 driver**. It consists of two main deployable artefacts:

1. **ojp-server** – a standalone gRPC server that owns and controls the real database connection pools (HikariCP). Applications never connect directly to the database.
2. **ojp-jdbc-driver** – a JDBC 4.2-compliant driver that clients drop in. Instead of opening real connections, it makes gRPC calls to ojp-server.

The value proposition is back-pressure / connection-storm prevention: many application instances can scale elastically without ever overwhelming the database, because the proxy enforces a global connection limit.

```
[Java App] --JDBC--> [ojp-jdbc-driver] --gRPC/HTTP2--> [ojp-server] --JDBC--> [Database]
```

Supported databases (tested): PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, DB2, H2.  
In principle any database that ships a JDBC driver should work.

---

## Repository Layout

This is a **multi-module Maven project**. All modules share the parent `pom.xml` at the root.

| Module | Purpose |
|---|---|
| `ojp-grpc-commons` | Shared Protobuf/gRPC contracts (`.proto` files). Both driver and server depend on this. |
| `ojp-jdbc-driver` | JDBC driver implementation (`OjpDriver`, `OjpConnection`, `OjpStatement`, `OjpResultSet`, …) |
| `ojp-server` | gRPC server, HikariCP pool management, session/transaction tracking, slow-query segregation |
| `ojp-datasource-api` | SPI interface: `ConnectionPoolProvider` |
| `ojp-datasource-hikari` | Built-in HikariCP implementation of `ConnectionPoolProvider` (priority 100) |
| `ojp-datasource-dbcp` | Built-in DBCP2 implementation of `ConnectionPoolProvider` (priority 10) |
| `ojp-xa-pool-commons` | XA-capable pool provider (`CommonsPool2XAProvider`) and `XAConnectionPoolProvider` SPI |
| `ojp-testcontainers` | OJP-specific Testcontainers support for reproducible integration tests |
| `spring-boot-starter-ojp` | Spring Boot auto-configuration / starter |

Documentation lives under `documents/`. ADRs are in `documents/ADRs/`. The `ROADMAP.md` at the root describes the release plan (1.0.0 targets September/October 2026).

---

## Java Version Requirements

| Context | Minimum Java |
|---|---|
| ojp-jdbc-driver (runtime) | Java 11 |
| ojp-server (runtime) | Java 25 |
| Development / CI build | Java 25 (recommended) |

The root `pom.xml` compiles with `source/target = 11` but the server module overrides this to 25. **Do not lower these targets.** CI tests against Java 11, 17, 21, and 25 for the driver.

---

## Build Commands

```bash
# Build everything, skip tests (required before running any tests)
mvn clean install -DskipTests -Dgpg.skip=true

# Build a single module and its dependencies
mvn clean install -pl ojp-server -am -DskipTests -Dgpg.skip=true

# Verify compilation only (quick sanity check before committing)
mvn clean compile
```

**Never commit code that fails `mvn clean compile`.**

---

## Testing

### Architecture of tests

Most tests in `ojp-jdbc-driver` are **integration tests**: they require a running OJP server and, for non-H2 databases, a running database container. This design choice means the tests verify the real end-to-end flow, which is appropriate for a proxy that sits between a driver and a database.

### Running tests locally

**Step 1 – Download JDBC drivers** (needed since 0.4.0-beta; drivers are no longer bundled):

```bash
cd ojp-server
bash download-drivers.sh        # downloads H2, PostgreSQL, MySQL, MariaDB to ojp-server/ojp-libs/
cd ..
```

**Step 2 – Start the OJP server** (leave this terminal open):

```bash
mvn verify -pl ojp-server -Prun-ojp-server
```

**Step 3 – Run tests** (in another terminal):

```bash
cd ojp-jdbc-driver
mvn test -DenableH2Tests=true   # H2 is embedded; no external DB needed
```

All database test flags are disabled by default. Enable only what you need:

| Flag | Database |
|---|---|
| `-DenableH2Tests=true` | H2 (embedded, fast) |
| `-DenablePostgresTests=true` | PostgreSQL |
| `-DenableMySQLTests=true` | MySQL |
| `-DenableMariaDBTests=true` | MariaDB |
| `-DenableCockroachDBTests=true` | CockroachDB |
| `-DenableOracleTests=true` | Oracle |
| `-DenableSqlServerTests=true` | SQL Server |

For IDE runs, always add `-Dfile.encoding=UTF-8 -Duser.timezone=UTC` as JVM arguments.

### CI strategy

The GitHub Actions workflow (`main.yml`) implements a **fail-fast gate**: H2 tests run first; expensive database-specific jobs only run if H2 passes. PostgreSQL tests run twice – once with a standard OJP server and once with the SQL enhancer enabled.

### Test types

- **Integration tests** (preferred for anything touching SQL execution, transactions, or connection management) – live under `ojp-jdbc-driver/src/test/`.
- **Unit tests** (for pure logic: load balancing, circuit breaker, parsing) – test classes in isolation.
- JUnit 5 is the test framework. Follow the `shouldReturnXxxWhenYyy` naming convention.

---

## Key Architectural Decisions

See `documents/ADRs/` for full context. Brief summary:

| ADR | Decision | Rationale |
|---|---|---|
| ADR-001 | Java | Wide ecosystem, strong JDBC history |
| ADR-002 | gRPC over REST | HTTP/2 multiplexing, streaming, low latency |
| ADR-003 | HikariCP | Best-in-class connection pool performance |
| ADR-004 | Implement JDBC interfaces | Drop-in replacement without app changes |
| ADR-005 | OpenTelemetry | Vendor-neutral observability |
| ADR-006 | SPI pattern | Extensibility without forking |
| ADR-007 | Commons Pool2 for XA | Universal XA pool that works with any XADataSource |
| ADR-008 | Caffeine for caching | High-performance, off-heap friendly cache |

---

## Extension Points (SPIs)

OJP uses Java's `ServiceLoader` to discover implementations at runtime. There are two SPIs:

### `ConnectionPoolProvider` (`ojp-datasource-api`)
Manages regular (non-XA) connection pools. Built-in implementations: HikariCP (default, priority 100), DBCP2 (priority 10). To replace the pool, place a JAR implementing this interface in `ojp-libs/` — no recompile needed.

### `XAConnectionPoolProvider` (`ojp-xa-pool-commons`)
Manages XA-capable pools for distributed transactions. Built-in: Commons Pool2 (priority 100). Useful for adding Oracle UCP or Atomikos-backed pools.

When implementing either SPI, register the implementation via `META-INF/services/` as required by `ServiceLoader`.

---

## Critical Operational Rules (Do Not Break)

1. **Application-level connection pools MUST be disabled** when using OJP. Double-pooling (e.g., HikariCP on the app side + HikariCP on the server side) will cause incorrect behavior and resource waste. This is the single most common misconfiguration.

2. **Always start ojp-server with `-Duser.timezone=UTC`**. The server receives timestamps from databases in different timezones; UTC on the JVM prevents incorrect conversions.

3. **JDBC drivers are not bundled** (since 0.4.0-beta). They must be placed in `ojp-libs/` (or the path configured by `ojp.drivers.path` / `OJP_DRIVERS_PATH`). The `download-drivers.sh` script handles open-source drivers. Proprietary ones (Oracle, SQL Server, DB2) require manual download.

4. **The SQL enhancer (Apache Calcite integration) is EXPERIMENTAL and disabled by default.** It has known type-system incompatibilities with PostgreSQL, MySQL, Oracle, and SQL Server. Do **not** enable it in production and do not spend effort fixing Calcite type mapping without understanding the root cause documented in `INVESTIGATION_SQL_ENHANCER.md`.

---

## Frequently Changed Areas and Tips

### Adding support for a new database

- No server-side code changes are usually needed — just supply the JDBC driver JAR in `ojp-libs/`.
- For XA support, follow `documents/guides/ADDING_DATABASE_XA_SUPPORT.md`.
- Add integration tests guarded by a new `-DenableXxxTests=true` flag, following the pattern in existing test classes.

### Modifying the gRPC protocol

- Edit `.proto` files in `ojp-grpc-commons/src/main/proto/`.
- Run `mvn clean install -pl ojp-grpc-commons` to regenerate Java stubs.
- Both driver and server must be updated together; the stubs are shared.
- Be careful with backward compatibility — the driver and server may be deployed independently.

### Modifying connection pool behavior

- The server's pool management lives in `ojp-server`. The SPI interfaces live in `ojp-datasource-api`.
- The default HikariCP implementation is in `ojp-datasource-hikari`. Changes here affect all deployments that don't supply a custom SPI.

### Multinode changes

- Multinode logic (load-aware routing, failover, session stickiness) lives in the driver (`ojp-jdbc-driver`).
- The URL format is `jdbc:ojp[host1:port1,host2:port2]_actual_jdbc_url`.
- Session stickiness must be preserved across failover; test any changes against multinode integration tests.

### Spring Boot starter

- Auto-configuration is in `spring-boot-starter-ojp`. It wires `OjpDataSource` automatically when the driver is on the classpath.
- Disable Spring Boot's own HikariCP auto-configuration when using OJP (see `documents/java-frameworks/spring-boot/`).

---

## Code Style

- **Java conventions**: camelCase for variables/methods, PascalCase for classes.
- **Lombok**: Used throughout for boilerplate reduction (`@Getter`, `@Setter`, `@Builder`, `@Slf4j`, etc.). Do not write getters/setters by hand.
- **Indentation**: 4 spaces.
- **Comments**: Only when necessary to explain non-obvious logic. Code should be self-documenting.
- **Minimize new dependencies**: Check license compatibility (Apache 2.0 or compatible required). Prefer existing libraries (HikariCP, gRPC, OpenTelemetry).
- **No secrets or credentials** should ever be committed, even in tests (use environment variables or test containers).

---

## Opinions / Things Worth Knowing

- The project is pre-1.0 (current: 0.4.x-beta). The public APIs — especially the SPIs — are still evolving. Avoid making SPI interfaces more complex than needed; simplicity helps third-party implementers.
- `SUMMARY.md` at the root is not a documentation index — it's the investigation summary for the SQL Enhancer feature. This is confusing; the actual doc index is `documents/README.md`. When the SQL Enhancer matures or is removed, `SUMMARY.md` should be renamed.
- The documentation under `documents/ebook/` is extensive and well-written, but some `[IMAGE PROMPT N]` placeholders remain in the architecture chapter. These are stubs for future diagrams and should not be treated as broken documentation.
- The test flag pattern (`-DenableXxxTests=true`) is the right approach for a project that supports 7+ databases — don't collapse it into a single flag or automatic discovery, as that would make local runs impractical.
- `ojp-testcontainers` is a newer module that enables deterministic, self-contained integration testing. Prefer it over manually managed Docker databases for new tests.
- The slow query segregation feature is clever (20% slots for slow queries, adaptive learning, slot borrowing). Be careful when touching the slot management logic; it's a concurrency-sensitive area.
- Circuit breaker timeout (`ojp.server.circuitBreakerTimeout`) defaults to 60 seconds. When writing tests that simulate failures, account for this delay.

---

## Documentation Map

| Topic | Location |
|---|---|
| Quick start | `README.md` |
| Architecture deep dive | `documents/ebook/part1-chapter2-architecture.md` |
| Server configuration reference | `documents/configuration/ojp-server-configuration.md` |
| JDBC driver configuration | `documents/configuration/ojp-jdbc-configuration.md` |
| SPI guide | `documents/Understanding-OJP-SPIs.md` |
| Multinode setup | `documents/multinode/README.md` |
| XA transactions | `documents/multinode/XA_MANAGEMENT.md` |
| Slow query segregation | `documents/designs/SLOW_QUERY_SEGREGATION.md` |
| Telemetry / OpenTelemetry | `documents/telemetry/README.md` |
| Database setup for tests | `documents/environment-setup/` |
| Release process | `documents/guides/RELEASE_PROCESS.md` |
| Contributor recognition | `documents/contributor-badges/contributor-recognition-program.md` |
| All ADRs | `documents/ADRs/` |
| SQL Enhancer investigation | `INVESTIGATION_SQL_ENHANCER.md` |
| Roadmap | `ROADMAP.md` |

---

## Communication and Community

- **GitHub Issues**: Bug reports, feature requests.
- **GitHub Discussions**: Architecture questions, open-ended proposals.
- **Discord**: Real-time chat — [discord.gg/J5DdHpaUzu](https://discord.gg/J5DdHpaUzu).
- When opening a PR, link it to an issue with `Fixes #NNN` in the description.
