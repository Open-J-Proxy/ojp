# OJP Documentation Index

Choose the information you need for your role or task. Start with the purpose or practical steps, then look up details when needed; source code and flow diagrams are not required reading for using OJP.

| Your goal | Start here | Next information |
|---|---|---|
| Evaluate OJP — managers, architects, and teams | [Problem and solution](targeted-problem/README.md) · [Introduction and suitability](ebook/part1-chapter1-introduction.md) | [Support policy](../SUPPORT.md) · [Roadmap](../ROADMAP.md) · [Production rollout](monitoring/PRODUCTION_DEPLOYMENT_GUIDE.md) |
| Use OJP in an application | [Quick start](ebook/part1-chapter3-quickstart.md) · [Framework integration](java-frameworks/README.md) | [JDBC settings](configuration/ojp-jdbc-configuration.md) · [Client capabilities](#multi-language-clients) |
| Deploy and operate OJP — ops teams and DBAs | [Docker](configuration/DOCKER_DEPLOYMENT.md) · [Runnable JAR](runnable-jar/README.md) · [Production guide](monitoring/PRODUCTION_DEPLOYMENT_GUIDE.md) | [Server settings](configuration/ojp-server-configuration.md) · [Security](configuration/mtls-configuration-guide.md) · [Monitoring and troubleshooting](#telemetry) |
| Understand behaviour or contribute | [System overview](#understand-ojp) · [Development guides](#develop-and-extend-ojp) | Optional flow diagrams, specialised explanations, contracts, and source checkpoints |

Browse [use and operate OJP](#use-and-operate-ojp) for practical guides, or the [ebook reading paths](ebook/README.md#reading-paths) for narrative learning. The [complete inventory](#document-organization) includes reference material, proposals, investigations, and publishing resources.

## Understand OJP

For an overview without implementation details, read the [problem and solution](targeted-problem/README.md) and [introduction](ebook/part1-chapter1-introduction.md). To explore runtime behaviour, the optional path below starts with the component picture and reveals flows, notes, specialised scenarios, and source checkpoints as needed. This is one learning route, not the structure every reader must follow.

<a id="architecture-and-design"></a>

### 1. Component picture

```text
Application → ojp-jdbc-driver → gRPC / HTTP2 → ojp-server → JDBC → Database
                                             owns backend connection pools
```

- [OJP Components](OJPComponents.md) - Component responsibilities and architecture
- [Architecture chapter](ebook/part1-chapter2-architecture.md) - Broader explanation
- [Targeted Problem Statement](targeted-problem/README.md) - Why a server-side connection limit matters

<a id="design-documents"></a>

### 2. Operation flows

Start with [executeQuery](designs/EXECUTE_QUERY_FLOW.md), then follow the lifecycle. Each simplified flow includes essential notes and source links; use those before diving into implementation details.

- [Simplified Flow Diagrams](designs/MAIN_FLOWS.md) - Documentation method and main-flow index
- [Connect — Simplified Flow Diagram](designs/CONNECT_FLOW.md) - Datasource setup and lazy sessions
- [executeQuery — Simplified Flow Diagram](designs/EXECUTE_QUERY_FLOW.md) - Query execution, row streaming, and result closure
- [executeUpdate — Simplified Flow Diagram](designs/EXECUTE_UPDATE_FLOW.md) - SQL changes and affected-row counts
- [Commit / rollback — Simplified Flow Diagram](designs/TRANSACTION_FLOW.md) - Transaction completion
- [Close connection — Simplified Flow Diagram](designs/CLOSE_CONNECTION_FLOW.md) - Session cleanup and resource release
- [Call Proxy — Simplified Flow Diagram](designs/CALL_PROXY_FLOW.md) - Shared remote JDBC operations

### 3. Essential notes

- Disable application-side connection pools: OJP owns pooling on the server.
- Run the server with `-Duser.timezone=UTC`; supply database driver JARs separately. See [drivers and libraries](configuration/DRIVERS_AND_LIBS.md).
- Connection creation, backend borrowing, transaction completion, and resource closure are different lifecycle boundaries; read the notes accompanying each flow.
- [Request Admission, Timeouts, and Backpressure](analysis/ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md) explains the current admission gate and overload model.
- [Transaction Isolation Handling](analysis/TRANSACTION_ISOLATION_HANDLING.md) explains isolation reset behavior.
- [Session cleanup](configuration/SESSION_CLEANUP.md) explains resource lifetime and cleanup settings.
- The SQL enhancer is experimental and disabled by default; its [investigations](analysis/sql_enhancer/) are not production guidance.

### 4. Specialized scenarios

#### XA Transactions (Distributed Transactions)

The transaction manager coordinates distributed transactions; OJP relays participant operations. Start with the simplified lifecycle, then use the detailed references for pooling, configuration, and multinode behavior.

- [XA connection setup — Simplified Flow Diagram](designs/XA_CONNECT_FLOW.md) - Server binding and backend borrowing
- [XA branch work — Simplified Flow Diagram](designs/XA_BRANCH_FLOW.md) - Enlistment, SQL work, and branch end
- [XA prepare / commit / rollback — Simplified Flow Diagram](designs/XA_COMPLETION_FLOW.md) - Coordinator-controlled completion
- [XA connection closure — Simplified Flow Diagram](designs/XA_CLOSE_FLOW.md) - Logical closure and pooled backend return
- [XA recovery scan — Simplified Flow Diagram](designs/XA_RECOVERY_FLOW.md) - Recoverable branch discovery and its limits
- [XA Transaction Management](multinode/XA_MANAGEMENT.md) - XA support, pool configuration, lifecycle, and multinode constraints
- [XA Transaction Flow](multinode/XA_TRANSACTION_FLOW.md) - Detailed transaction-manager and participant interaction

#### Multinode Deployments

- [Multinode Overview](multinode/README.md) - Introduction to multinode deployments
- [Multinode Architecture](multinode/multinode-architecture.md) - Detailed multinode architecture
- [Multinode Flow](multinode/MULTINODE_FLOW.md) - Detailed operation flows
- [Per-Endpoint Datasources](multinode/per-endpoint-datasources.md) - Different datasource configurations per endpoint
- [Server Recovery and Redistribution](multinode/server-recovery-and-redistribution.md) - Server recovery and connection redistribution

Other specialized behavior:

- [Slow Query Segregation](designs/SLOW_QUERY_SEGREGATION.md) - Especially relevant to mixed fast/slow workloads; usually unnecessary for pure OLTP or pure OLAP
- [Query result caching](guides/CACHE_USER_GUIDE.md) - Usage and configuration
- [SQL warning transfer](designs/SQLWARNING_FULL_TRANSFER.md) - Warning handling design

### 5. Source checkpoints

Use the source links at the end of each flow to verify behavior in the current checkout:

- [Driver entry point](../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Driver.java), [connection lifecycle](../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java), and [statement operations](../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java)
- [Server statement service](../ojp-server/src/main/java/org/openjproxy/grpc/server/StatementServiceImpl.java)
- [Shared gRPC contracts](../ojp-grpc-commons/src/main/proto/)
- [StatementServiceImpl Action Pattern Migration](designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md) - Implementation background

## Use and operate OJP

### Getting Started

- [Main README](../README.md) - Project overview and quick start
- [JDBC driver README](../ojp-jdbc-driver/README.md) and [server README](../ojp-server/README.md) - Deployable components
- [GitHub releases](https://github.com/Open-J-Proxy/ojp/releases) - Version history and release notes
- [Quick-start chapter](ebook/part1-chapter3-quickstart.md) - Guided introduction

### Configuration

- [OJP JDBC Configuration](configuration/ojp-jdbc-configuration.md) - Driver settings
- [OJP Server Configuration](configuration/ojp-server-configuration.md) - Server settings
- [Drivers and libraries](configuration/DRIVERS_AND_LIBS.md) - External JDBC drivers
- [Docker deployment](configuration/DOCKER_DEPLOYMENT.md)
- [mTLS configuration](configuration/mtls-configuration-guide.md) and [certificate placeholders](configuration/ssl-tls-certificate-placeholders.md)
- [Configuration inventory](configuration/) - Environment-specific properties and examples

#### Connection Pool

- [Connection Pool Overview](connection-pool/README.md) - Abstraction and providers
- [Connection Pool Configuration](connection-pool/configuration.md) - Reference
- [Migration Guide](connection-pool/migration-guide.md) - Migration from previous versions

### Framework Integration

- [Framework overview](java-frameworks/README.md)
- [Spring Boot Integration](java-frameworks/spring-boot/README.md)
- [Micronaut Integration](java-frameworks/micronaut/README.md)
- [Quarkus Integration](java-frameworks/quarkus/README.md)
- [Jakarta EE Integration](java-frameworks/jakarta-ee/README.md)

### Multi-language Clients

- [Go client](../ojp-client-go-database-sql/README.md) - Go `database/sql`
- [Dart client](../ojp-client-dart-drift/README.md) - Drift executor
- [.NET client](../ojp-client-dotnet-ado-net/README.md) - ADO.NET provider
- [PHP client](../ojp-client-php-pdo/README.md) - PDO-compatible API
- [Python client](../ojp-client-python-dbapi/README.rst) - DB-API 2.0 (reStructuredText README)
- [Ruby client](../ojp-client-ruby-dbi/README.md) - DBI
- [C++ ODBC client](../ojp-client-cpp-odbc/README.md) - ODBC driver

These clients have different capabilities from the Java driver. Consult [Client Implementation Levels](multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md) for L1–L10 capabilities, tested scope, and integration-test evidence rather than assuming feature parity.

### Telemetry

- [Telemetry Overview](telemetry/README.md) - OpenTelemetry setup
- [Monitoring overview](monitoring/README.md) and [production deployment guide](monitoring/PRODUCTION_DEPLOYMENT_GUIDE.md)
- [Monitoring resources](monitoring/) - Includes the Grafana dashboard
- [Troubleshooting inventory](troubleshooting/) - Includes multinode connection redistribution

### Runnable JAR

- [Runnable JAR Guide](runnable-jar/README.md)
- [Building from source](runnable-jar/BUILDING_FROM_SOURCE.md)

### Database Setup Guides

- [Run Local Databases](environment-setup/run-local-databases.md) - Local testing setup
- [CockroachDB Testing Guide](environment-setup/cockroachdb-testing-guide.md)
- [DB2 Testing Guide](environment-setup/db2-testing-guide.md)
- [Oracle Testing Guide](environment-setup/oracle-testing-guide.md)
- [SQL Server Testing Guide](environment-setup/sqlserver-testing-guide.md)
- [SQL Server Testcontainer Guide](SQLSERVER_TESTCONTAINER_GUIDE.md)
- [DBeaver Tutorial](guides/DBEAVER.md) - Use the JDBC driver in DBeaver

## Develop and extend OJP

### Code Contributions

- [Contributing](../CONTRIBUTING.md) - Contribution workflow
- [Setup and Testing OJP Source](code-contributions/setup_and_testing_ojp_source.md) - Development setup (use Java 25 for builds/tests)
- [OJP Testcontainers](../ojp-testcontainers/README.md) - Integration testing support
- [Agent guide](../Agents.md) - Repository-specific coding guidance

### Developer Guides

- [Understanding OJP SPIs](Understanding-OJP-SPIs.md) - Pool-provider extension points
- [Adding Database XA Support](guides/ADDING_DATABASE_XA_SUPPORT.md)
- [Release Process & Maven Central Integration](guides/RELEASE_PROCESS.md)
- [Versioning](VERSIONING.md), [LTS branching](guides/LTS_BRANCHING.md), and [1.0.0 release guide](guides/RELEASE_1_0_0.md)

### Protocol

- [Shared protocol sources](../ojp-grpc-commons/src/main/proto/)
- [BigDecimal Wire Format](protocol/BIGDECIMAL_WIRE_FORMAT.md)
- [Protobuf Non-Java Serializations](protobuf-nonjava-serializations.md)
- [Multi-language Client Specification](multi-language-client-spec/CLIENT_SPEC.md) - Protocol and client responsibilities
- [AI-oriented client specification](multi-language-client-spec/CLIENT_SPEC_AI.md)
- [Client Implementation Levels](multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md) - Test-proven coverage

### Architecture Decision Records (ADRs)

Located in [ADRs/](ADRs/):

- [ADR-001: Use Java](ADRs/adr-001-use-java.md)
- [ADR-002: Use gRPC](ADRs/adr-002-use-grpc.md)
- [ADR-003: Use HikariCP](ADRs/adr-003-use-hikaricp.md)
- [ADR-004: Implement JDBC Interface](ADRs/adr-004-implement-jdbc-interface.md)
- [ADR-005: Use OpenTelemetry](ADRs/adr-005-use-opentelemetry.md)
- [ADR-006: Adopt SPI Pattern](ADRs/adr-006-adopt-spi-pattern.md)
- [ADR-007: Use Commons Pool 2 for XA](ADRs/adr-007-use-commons-pool2-for-xa.md)
- [ADR-008: Use Caffeine for Caching](ADRs/adr-008-use-caffeine-for-caching.md)
- [ADR-009: Action Pattern for StatementServiceImpl](ADRs/adr-009-action-pattern-for-statement-service.md)

### Contributor Recognition

- [Contributor Recognition Program](contributor-badges/contributor-recognition-program.md)

## Additional Topics

### Analysis and Technical Documentation

The [analysis index](analysis/README.md) mixes current behavior explanations, evaluations, and proposals. It is not an obsolete archive: check each document's status and the current implementation before treating a recommendation as implemented.

- Current behavior references: [Transaction Isolation Handling](analysis/TRANSACTION_ISOLATION_HANDLING.md) and [Request Admission, Timeouts, and Backpressure](analysis/ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md)
- [Full analysis inventory](analysis/) - Includes caching, driver, pool, protocol, admission, and connection-budget studies
- [XA pool SPI studies](analysis/xa-pool-spi/) - Evaluations and implementation background

### Proposals and historical investigations

These resources are separate from the primary usage and current-flow reading paths. They may contain useful design rationale, experiments, or planned work rather than current operating instructions.

- [Implementation plans](implementation_plans/) - Go client and schema-loader plans
- [SQL enhancer investigations](analysis/sql_enhancer/) - Experimental Calcite background
- [Experimental feature guides](features/) - SQL enhancer quick start and configuration examples
- [Roadmap](../ROADMAP.md) - Planned direction, not a guarantee of delivered features

### Fixed Issues

- [Issue 29 Fix Documentation](fixed-issues/ISSUE_29_FIX_DOCUMENTATION.md)
- [Fixed-issue inventory](fixed-issues/) - Historical fix records

### Targeted Problem

- [Targeted Problem Statement](targeted-problem/README.md)

## Images and Diagrams

- [Images](images/) - Project diagrams, logos, and contributor badges
- [Design resources](designs/) - Flow documents and editable Draw.io diagrams
- [Ebook visual assets](ebook/images/README.md)

## Quick Navigation

### By Topic

| Goal | Start here |
|---|---|
| Understand a JDBC operation | [Simplified flows](designs/MAIN_FLOWS.md) → notes → source checkpoints |
| Understand XA | [XA setup](designs/XA_CONNECT_FLOW.md) → [branch work](designs/XA_BRANCH_FLOW.md) → [completion](designs/XA_COMPLETION_FLOW.md) → [closure](designs/XA_CLOSE_FLOW.md) / [recovery](designs/XA_RECOVERY_FLOW.md) |
| Configure XA or examine multinode constraints | [XA management](multinode/XA_MANAGEMENT.md) and [detailed XA flow](multinode/XA_TRANSACTION_FLOW.md) |
| Deploy multiple servers | [Multinode overview](multinode/README.md), [architecture](multinode/multinode-architecture.md), [flow](multinode/MULTINODE_FLOW.md), [server recovery](multinode/server-recovery-and-redistribution.md) |
| Configure OJP | [Driver](configuration/ojp-jdbc-configuration.md) and [server](configuration/ojp-server-configuration.md) |
| Contribute or add features | [Setup and testing](code-contributions/setup_and_testing_ojp_source.md), [SPIs](Understanding-OJP-SPIs.md), [database XA support](guides/ADDING_DATABASE_XA_SUPPORT.md) |
| Release | [Release process](guides/RELEASE_PROCESS.md) |

## Document Organization

The inventory below covers every documentation directory and standalone document. Directory links include all files within that area, including examples and supporting assets; this avoids repeating long lists of investigations.

### Documentation directories

| Directory | Contents |
|---|---|
| [ADRs/](ADRs/) | Architecture decisions |
| [analysis/](analysis/) | Current technical explanations, evaluations, proposals, and implementation summaries |
| [analysis/sql_enhancer/](analysis/sql_enhancer/) | SQL enhancer investigations |
| [analysis/xa-pool-spi/](analysis/xa-pool-spi/) | XA pool SPI studies and index |
| [code-contributions/](code-contributions/) | Source setup and testing |
| [configuration/](configuration/) | Driver/server settings, deployment, TLS, cleanup, and example properties |
| [connection-pool/](connection-pool/) | Provider abstraction, configuration, and migration |
| [contributor-badges/](contributor-badges/) | Recognition program |
| [designs/](designs/) | Simplified flows, feature designs, migration notes, and Draw.io assets |
| [ebook/](ebook/) | Chapters, appendices, revision note, and reading paths |
| [ebook/images/](ebook/images/) | Ebook image guidance and visual resources |
| [ebook/scripts/](ebook/scripts/) | Image preparation and reference-update scripts |
| [ebook/single-html-page-generator/](ebook/single-html-page-generator/) | Publishing instructions and generated single-page HTML |
| [ebook/single-html-page-generator/vendor/](ebook/single-html-page-generator/vendor/) | Vendored publishing resources |
| [environment-setup/](environment-setup/) | Local database and database-specific testing guides |
| [features/](features/) | Experimental SQL enhancer guides |
| [fixed-issues/](fixed-issues/) | Fix records |
| [guides/](guides/) | DBeaver, XA extension, caching, branching, and release guides |
| [images/](images/) | Diagrams, logos, and badges |
| [implementation_plans/](implementation_plans/) | Proposed implementation plans |
| [java-frameworks/](java-frameworks/) | Framework integration index |
| [java-frameworks/jakarta-ee/](java-frameworks/jakarta-ee/) | Jakarta EE integration |
| [java-frameworks/micronaut/](java-frameworks/micronaut/) | Micronaut integration |
| [java-frameworks/quarkus/](java-frameworks/quarkus/) | Quarkus integration |
| [java-frameworks/spring-boot/](java-frameworks/spring-boot/) | Spring Boot integration |
| [monitoring/](monitoring/) | Production monitoring and Grafana dashboard |
| [multi-language-client-spec/](multi-language-client-spec/) | Client specifications and implementation levels |
| [multinode/](multinode/) | Deployment, architecture, redistribution, and XA documentation |
| [protocol/](protocol/) | Wire-format reference |
| [runnable-jar/](runnable-jar/) | Running and building the server JAR |
| [targeted-problem/](targeted-problem/) | Problem statement |
| [telemetry/](telemetry/) | OpenTelemetry guidance |
| [troubleshooting/](troubleshooting/) | Operational fix explanations |

### Standalone documents

- [This documentation index](README.md)
- [OJP Components](OJPComponents.md)
- [Understanding OJP SPIs](Understanding-OJP-SPIs.md)
- [Protobuf Non-Java Serializations](protobuf-nonjava-serializations.md)
- [Session Cleanup Summary](SESSION_CLEANUP_SUMMARY.md)
- [SQL Server Testcontainer Guide](SQLSERVER_TESTCONTAINER_GUIDE.md)
- [Versioning](VERSIONING.md)
- [Ebook Structure](EBOOK_STRUCTURE.md)

### Module and client documentation

Modules with READMEs:

- [JDBC driver](../ojp-jdbc-driver/README.md)
- [Server](../ojp-server/README.md)
- [Testcontainers](../ojp-testcontainers/README.md)

Other module directories (source, configuration, and SPI registration resources):

- [gRPC commons](../ojp-grpc-commons/)
- [Datasource API](../ojp-datasource-api/)
- [Hikari provider](../ojp-datasource-hikari/)
- [DBCP provider](../ojp-datasource-dbcp/)
- [XA pool commons](../ojp-xa-pool-commons/)
- [Spring Boot starter](../spring-boot-starter-ojp/)

All client READMEs are listed under [Multi-language Clients](#multi-language-clients), including the Python `README.rst`.

### Ebook and publishing resources

- [Ebook index](ebook/README.md) - Chapters, appendices, and reading paths
- [Complete chapter and appendix inventory](ebook/) - Includes compatibility, visual assets, and troubleshooting appendices
- [Revision note](ebook/REVISION_NOTE.md) and [ebook structure](EBOOK_STRUCTURE.md)
- [Image resources](ebook/images/README.md) and [image scripts](ebook/scripts/README.md)
- [Single-page publishing guide](ebook/single-html-page-generator/README.md) and [HTML edition](ebook/single-html-page-generator/ebook-single-page.html)

### Root governance, support, and release resources

- [Project README](../README.md)
- [Contributing](../CONTRIBUTING.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Expectations](../EXPECTATIONS.md)
- [Support](../SUPPORT.md)
- [License](../LICENSE)
- [Agent guide](../Agents.md)
- [Roadmap](../ROADMAP.md)
- [GitHub releases](https://github.com/Open-J-Proxy/ojp/releases)
- [Versioning](VERSIONING.md) and [release process](guides/RELEASE_PROCESS.md)
- [Repository scripts](../scripts/) - Supporting development and release automation
