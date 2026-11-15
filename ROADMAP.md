# OJP Roadmap

This document outlines the planned releases and key milestones for the Open J Proxy project.

---

## ✅ Version 0.4.0-beta — March 2026 (Released)

**Theme: Service Provider Interfaces & Query Enhancement**

- Full implementation of OJP Service Provider Interfaces (SPIs), enabling custom connection pool providers and extensibility hooks
- Spring Boot integration via spring-boot-starter-ojp with automatic datasource configuration
- Official TestContainers integration module for reproducible integration testing
- Mutual TLS (mTLS) support between the JDBC driver and OJP server
- Expanded observability: additional OpenTelemetry metrics and distributed tracing spans
- Improved developer experience: refined configuration, better error messages, and expanded documentation
- Enhanced test coverage and integration testing infrastructure
- Experimental integration with [Apache Calcite](https://calcite.apache.org/) for SQL query optimization (disabled by default)

---

## 🔄 Version 0.5.0-beta — June 2026

**Theme: Read/Write Segregation & Caching**

- Read/write segregation support: route read queries to replicas and write queries to primary nodes automatically
- Query result caching layer to reduce database load for repeated read operations
- Configuration-driven cache invalidation and TTL policies
- Client-side throttling for failing fast when the system is overloaded.

---

## 🎯 Version 1.0.0 — September 20, 2026

**Theme: Production Ready (LTS)**

- First stable, production-grade release — no longer beta
- Full SPI ecosystem with stable public APIs
- Performance benchmarks and tuning guides
- Comprehensive documentation covering all features, deployment patterns, and upgrade paths
- **Long-term support (LTS) begins**: `1.0.x` receives bug fixes and security patches for 3 years
- LTS branch `lts/1.0` created from the `v1.0.0` tag
- Development on `main` continues toward `1.1.0`

---

## 🚀 Version 1.1.0 — November 2026 (planned)

**Theme: Post-LTS feature cycle**

This non-LTS feature release collects the changes merged into `main` since `v1.0.0`; `lts/1.0` continues to receive maintenance patches.

- JDBC and connection management: PostgreSQL JSON/JSONB `PGobject` type metadata relay; shared per-database connection budgets; optional local-first server priority tiers; configurable regular-pool connection-creation grace.
- Client ecosystem: expanded native .NET, Python, Go, PHP, Ruby, and Dart clients, plus substantial C++ ODBC work in transactions/autocommit, streaming and LOBs, metadata, multinode resilience, XA, and mTLS; capabilities and readiness vary by client.
- Shared protocol and correctness: centralized canonical gRPC contracts for Java and non-Java clients, alongside notable JDBC/ODBC compatibility and lifecycle fixes.
- CI, release, and dependency reliability: executable server artifact and release smoke checks; safer CI suite selection and shared test-image infrastructure; dependency and security updates.
- Documentation and developer experience: consolidated client capability, configuration, deployment, and architecture guidance.

---

## 🚀 Version 1.2.0 — Late December 2026 (planned)

**Theme: Per-pool Slow Query Segregation**

- Configure Slow Query Segregation independently for each connection pool instead of relying only on the current server-wide on/off setting
- [PR #636](https://github.com/Open-J-Proxy/ojp/pull/636) is the current open draft for analysis and design; the proposed per-datasource configuration is not implemented yet

---

## 💡 Future Considerations (post 1.0.0)

Items under consideration for future releases:

- Native reactive/non-blocking driver support
- gRPC streaming improvements for high-throughput workloads
- Graceful shutdown support
- Kubernetes operator for automated OJP cluster management
- Support for additional connection pool providers via SPI
- `2.0.0` only when breaking changes justify a new major version

---

## Versioning policy

Open J Proxy mostly follows the [Semantic Versioning](https://semver.org/):

| Version segment | When it changes |
|---|---|
| MAJOR | Breaking, API-incompatible changes |
| MINOR | Backwards-compatible new features |
| PATCH | Bug and security fixes |

See [`documents/VERSIONING.md`](documents/VERSIONING.md) for full details and branching rules.
See [`SUPPORT.md`](SUPPORT.md) for the support and LTS maintenance policy.

---

> 📣 Want to influence the roadmap? Open a [GitHub Discussion](https://github.com/Open-J-Proxy/ojp/discussions) or join us on [Discord](https://discord.gg/J5DdHpaUzu).
