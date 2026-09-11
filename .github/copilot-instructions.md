# Copilot Instructions for Open J Proxy (OJP)

This file provides guidance for GitHub Copilot working inside this repository. Read it before making any changes.

---

## Agent Behavior Guidelines

- Use simple language and simple examples to explain things.
- Be honest, even when the honest answer is "I don't know" or "this approach has problems."
- Look for the best technical solution, not just the most convenient one.
- Don't default to agreement — push back when something seems wrong or suboptimal.
- Proactively offer questions, opinions, suggestions, and concerns rather than waiting to be asked.
- When root-causing an issue or suggesting a solution, always report an honest confidence level — either as a percentage (0–100%) or as a label (Low / Medium / High) — and briefly explain what is driving that confidence or uncertainty.

---

## Java Runtime Requirement

**This project uses Java 25. Use the Java 25 runtime for all build and test tasks.**

| Context | Minimum Java |
|---|---|
| ojp-jdbc-driver (runtime) | Java 11 |
| ojp-server (runtime) | Java 25 |
| Development / CI build | Java 25 (required) |

The root `pom.xml` compiles with `source/target = 11` but the server module overrides this to 25. **Do not lower these targets.** Never suggest Java 8 or Java 17 as the build/test runtime; always use Java 25.

---

## What OJP Is

OJP is the **world's first open-source JDBC Type 3 driver**. It consists of two main deployable artefacts:

1. **ojp-server** – a standalone gRPC server that owns and controls the real database connection pools (HikariCP). Applications never connect directly to the database.
2. **ojp-jdbc-driver** – a JDBC 4.2-compliant driver that clients drop in. Instead of opening real connections, it makes gRPC calls to ojp-server.

```
[Java App] --JDBC--> [ojp-jdbc-driver] --gRPC/HTTP2--> [ojp-server] --JDBC--> [Database]
```

Supported databases: PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, DB2, H2.

---

## Repository Layout

This is a **multi-module Maven project**. All modules share the parent `pom.xml` at the root.

| Module | Purpose |
|---|---|
| `ojp-grpc-commons` | Shared Protobuf/gRPC contracts (`.proto` files) |
| `ojp-jdbc-driver` | JDBC driver implementation |
| `ojp-server` | gRPC server, HikariCP pool management, session/transaction tracking |
| `ojp-datasource-api` | SPI interface: `ConnectionPoolProvider` |
| `ojp-datasource-hikari` | Built-in HikariCP implementation (priority 100) |
| `ojp-datasource-dbcp` | Built-in DBCP2 implementation (priority 10) |
| `ojp-xa-pool-commons` | XA-capable pool provider and `XAConnectionPoolProvider` SPI |
| `ojp-testcontainers` | OJP-specific Testcontainers support for integration tests |
| `spring-boot-starter-ojp` | Spring Boot auto-configuration / starter |

---

## Build Commands

Always use Java 25 when running these commands.

```bash
# Run lint only (fast, no compilation needed)
mvn checkstyle:check

# Build everything, skip tests
mvn clean install -DskipTests -Dgpg.skip=true

# Build a single module and its dependencies
mvn clean install -pl ojp-server -am -DskipTests -Dgpg.skip=true

# Verify compilation only (quick sanity check before committing)
mvn clean compile
```

---

## Pre-commit Requirements

- All code must compile successfully before committing.
- All Checkstyle (SonarLint) rules must pass — **never commit code that fails `mvn checkstyle:check`**.
- Run `mvn clean compile` to verify both lint and compilation — **never commit code that fails**.
- Ensure you are using Java 25 as the active runtime before building or testing.

---

## Testing

Most tests in `ojp-jdbc-driver` are **integration tests** that require a running OJP server. H2 tests are the fast, embedded option.

### Running tests locally

**Step 1 – Download JDBC drivers:**
```bash
cd ojp-server
bash download-drivers.sh
cd ..
```

**Step 2 – Start the OJP server (leave running):**
```bash
mvn verify -pl ojp-server -Prun-ojp-server
```

**Step 3 – Run tests:**
```bash
cd ojp-jdbc-driver
mvn test -DenableH2Tests=true
```

All database test flags are disabled by default:

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

- Use JUnit 5. Follow the `shouldReturnXxxWhenYyy` naming convention.
- Prefer `ojp-testcontainers` for new tests over manually managed Docker databases.

---

## Code Style

- **Java conventions**: camelCase for variables/methods, PascalCase for classes.
- **Lombok**: Used throughout (`@Getter`, `@Setter`, `@Builder`, `@Slf4j`, etc.). Do not write getters/setters by hand.
- **Indentation**: 4 spaces.
- **Comments**: Only when necessary to explain non-obvious logic. Code should be self-documenting.
- **New dependencies**: Check license compatibility (Apache 2.0 or compatible required). Minimize additions.
- **No secrets or credentials** in committed code — use environment variables or Testcontainers.

---

## SonarLint Compliance

All main source code (not test code) must comply with SonarLint rules. Compliance is enforced automatically by the **Checkstyle Maven plugin** which runs during the `validate` phase and **fails the build** on violations.

The configuration lives in `checkstyle.xml` at the project root. The following rules are enforced (with the corresponding SonarLint rule ID):

| Rule | SonarLint ID | Description |
|---|---|---|
| `TypeName` | `java:S101` | Class/interface names must be PascalCase |
| `MethodName` | `java:S100` | Method names must be camelCase |
| `MemberName` | `java:S116` | Instance field names must be camelCase |
| `LocalVariableName` / `ParameterName` | `java:S117` | Local variable and parameter names must be camelCase |
| `ConstantName` | `java:S115` | `static final` fields must be `UPPER_SNAKE_CASE` (exception: `log` / `logger`) |
| `PackageName` | `java:S120` | Package names must be all lowercase |
| `AvoidStarImport` | `java:S2208` | Wildcard imports must not be used |
| `UnusedImports` | `java:S1128` | Unused imports must be removed |
| `RedundantImport` | `java:S1128` | Duplicate imports must be removed |
| `EmptyCatchBlock` | `java:S108` | Empty catch blocks must contain an explanatory comment |
| `NeedBraces` | `java:S121` | All code blocks must use braces `{}` |
| `SimplifyBooleanExpression` / `SimplifyBooleanReturn` | `java:S1125` | Redundant boolean expressions should be simplified |
| `UpperEll` | `java:S818` | Long literals must use uppercase `L` suffix |
| `ModifierOrder` | — | Modifiers must follow the standard Java order |
| `WhitespaceAround` | — | Operators and keywords must have surrounding whitespace |
| `NoFinalizer` | — | `finalize()` must not be overridden |
| Trailing whitespace (RegexpSingleline) | `java:S1109` | No trailing whitespace on any line |
| Newline at end of file | — | Every `.java` file must end with a newline |
| No `System.out`/`System.err` (RegexpSingleline) | `java:S106` | Use a proper logger instead of direct standard output |

### Running the lint check

```bash
# Run Checkstyle only (fast, no compilation needed)
mvn checkstyle:check

# Checkstyle also runs automatically as part of any build phase >= validate
mvn validate        # lint only
mvn clean compile   # lint + compile
mvn clean install   # lint + compile + test + package
```

### Writing compliant code

- **Naming**: use `UPPER_SNAKE_CASE` for every `static final` field (except `log`/`logger`).
- **Imports**: never use wildcard imports (`import foo.bar.*`). Remove imports that are no longer needed.
- **Braces**: always wrap `if`, `for`, `while`, and `else` bodies in `{ }`, even for single-statement bodies.
- **Logging**: never use `System.out.println` or `System.err.println`; use the SLF4J `log` field provided by `@Slf4j`.
- **Whitespace**: no trailing spaces on any line; every file must end with a newline character.
- **Long literals**: write `1000L`, not `1000l`.
- **Catch blocks**: if you genuinely need to swallow an exception, add a comment explaining why.

> **Note:** Checkstyle only checks hand-written main sources (`src/main/java`). Generated protobuf stubs and test sources are excluded.

---

## Critical Rules (Do Not Break)

1. **Application-level connection pools MUST be disabled** when using OJP. Double-pooling (e.g., HikariCP on the app side + server side) causes incorrect behavior.
2. **Always start ojp-server with `-Duser.timezone=UTC`**. The server receives timestamps from multiple timezones; UTC prevents incorrect conversions.
3. **JDBC drivers are not bundled** (since 0.4.0-beta). Place them in `ojp-libs/` or set `ojp.drivers.path` / `OJP_DRIVERS_PATH`. Use `download-drivers.sh` for open-source drivers.
4. **The SQL enhancer (Apache Calcite) is EXPERIMENTAL and disabled by default.** Do not enable it in production. See `INVESTIGATION_SQL_ENHANCER.md` before touching it.
5. **Do not modify the slow-query slot management logic** without careful review — it is concurrency-sensitive (20% slots reserved, adaptive learning, slot borrowing).

---

## Key Areas and Tips

- **gRPC protocol changes**: Edit `.proto` files in `ojp-grpc-commons/src/main/proto/`, then run `mvn clean install -pl ojp-grpc-commons`. Both driver and server must be updated together.
- **New database support**: Supply the JDBC driver JAR in `ojp-libs/`. Add integration tests behind a new `-DenableXxxTests=true` flag.
- **Multinode**: Logic lives in `ojp-jdbc-driver`. URL format: `jdbc:ojp[host1:port1,host2:port2]_actual_jdbc_url`. Session stickiness must survive failover.
- **Spring Boot starter**: Disable Spring Boot's HikariCP auto-configuration when using OJP.
- **Circuit breaker timeout** defaults to 60 seconds — account for this in failure-simulation tests.
- **SPI implementations**: Register via `META-INF/services/` as required by `ServiceLoader`.

---

## Documentation Map

| Topic | Location |
|---|---|
| Quick start | `README.md` |
| Architecture | `documents/ebook/part1-chapter2-architecture.md` |
| Server configuration | `documents/configuration/ojp-server-configuration.md` |
| JDBC driver configuration | `documents/configuration/ojp-jdbc-configuration.md` |
| SPI guide | `documents/Understanding-OJP-SPIs.md` |
| Multinode setup | `documents/multinode/README.md` |
| XA transactions | `documents/multinode/XA_MANAGEMENT.md` |
| Slow query segregation | `documents/designs/SLOW_QUERY_SEGREGATION.md` |
| Telemetry / OpenTelemetry | `documents/telemetry/README.md` |
| All ADRs | `documents/ADRs/` |
| SQL Enhancer investigation | `INVESTIGATION_SQL_ENHANCER.md` |
| Roadmap | `ROADMAP.md` |