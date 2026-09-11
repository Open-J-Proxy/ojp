# OJP Documentation Index

This directory contains comprehensive documentation for the Open J Proxy (OJP) project.

## Getting Started

- [Main README](../README.md) - Project overview and quick start guide
- [CHANGELOG](../CHANGELOG.md) - Version history and changes

## Architecture and Design

- [OJP Components](OJPComponents.md) - Overview of OJP components and architecture
- [Multinode Architecture](multinode-architecture.md) - Detailed multinode architecture
- [Protobuf Non-Java Serializations](protobuf-nonjava-serializations.md) - Serialization details

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

## XA Transactions (Distributed Transactions)

Located in [xa/](xa/):
- [XA Support](xa/XA_SUPPORT.md) - Overview of XA transaction support
- [XA Transaction Flow](xa/XA_TRANSACTION_FLOW.md) - Detailed flow of XA transactions
- [Atomikos XA Integration](xa/ATOMIKOS_XA_INTEGRATION.md) - Integration with Atomikos transaction manager
- [XA Multinode Failover](xa/XA_MULTINODE_FAILOVER.md) - Automatic retry and failover for XA in multinode deployments

## Multinode Deployments

Located in [multinode/](multinode/):
- [Multinode Overview](multinode/README.md) - Introduction to multinode deployments
- [Multinode Flow](multinode/MULTINODE_FLOW.md) - Detailed flow documentation for multinode operations
- [Per-Endpoint Datasources](multinode/per-endpoint-datasources.md) - Different datasource configurations per endpoint
- [Server Recovery and Redistribution](multinode/server-recovery-and-redistribution.md) - Automatic server recovery and connection redistribution

## Configuration

Located in [configuration/](configuration/):
- [OJP JDBC Configuration](configuration/ojp-jdbc-configuration.md) - JDBC driver configuration
- [OJP Server Configuration](configuration/ojp-server-configuration.md) - Server configuration

### Connection Pool

Located in [connection-pool/](connection-pool/):
- [Connection Pool Overview](connection-pool/README.md) - Connection pool abstraction and providers
- [Connection Pool Configuration](connection-pool/configuration.md) - Configuration reference
- [Migration Guide](connection-pool/migration-guide.md) - Migration from previous versions

### Analysis and Technical Documentation

Located in [analysis/](analysis/):
- [Transaction Isolation Handling](analysis/TRANSACTION_ISOLATION_HANDLING.md) - Complete technical documentation on transaction isolation reset behavior
- [Request Admission, Timeouts, and Backpressure](analysis/ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md) - Short explanation of the current admission gate and overload model

## Database Setup Guides

Located in [environment-setup/](environment-setup/):
- [Run Local Databases](environment-setup/run-local-databases.md) - Quick setup for local testing
- [CockroachDB Testing Guide](environment-setup/cockroachdb-testing-guide.md)
- [DB2 Testing Guide](environment-setup/db2-testing-guide.md)
- [Oracle Testing Guide](environment-setup/oracle-testing-guide.md)
- [SQL Server Testing Guide](environment-setup/sqlserver-testing-guide.md)

## Framework Integration

Located in [java-frameworks/](java-frameworks/):
- [Spring Boot Integration](java-frameworks/spring-boot/README.md)
- [Micronaut Integration](java-frameworks/micronaut/README.md)
- [Quarkus Integration](java-frameworks/quarkus/README.md)

## Developer Guides

Located in [guides/](guides/):
- [Release Process & Maven Central Integration](guides/RELEASE_PROCESS.md) - One-click release workflow, Sonatype setup, and suggestions
- [Adding Database XA Support](guides/ADDING_DATABASE_XA_SUPPORT.md) - How to add XA support for new databases

### Code Contributions

Located in [code-contributions/](code-contributions/):
- [Setup and Testing OJP Source](code-contributions/setup_and_testing_ojp_source.md)

## Additional Topics

### Protocol

Located in [protocol/](protocol/):
- [BigDecimal Wire Format](protocol/BIGDECIMAL_WIRE_FORMAT.md)

### Telemetry

Located in [telemetry/](telemetry/):
- [Telemetry Overview](telemetry/README.md)

### Runnable JAR

Located in [runnable-jar/](runnable-jar/):
- [Runnable JAR Guide](runnable-jar/README.md)

### Targeted Problem

Located in [targeted-problem/](targeted-problem/):
- [Targeted Problem Statement](targeted-problem/README.md)

### Design Documents

Located in [designs/](designs/):
- [Slow Query Segregation](designs/SLOW_QUERY_SEGREGATION.md) (strongly recommended for mixed fast+slow workloads; usually unnecessary for pure OLTP or pure OLAP)
- [StatementServiceImpl Action Pattern Migration](designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md)

### Fixed Issues

Located in [fixed-issues/](fixed-issues/):
- [Issue 29 Fix Documentation](fixed-issues/ISSUE_29_FIX_DOCUMENTATION.md)

### Contributor Recognition

Located in [contributor-badges/](contributor-badges/):
- [Contributor Recognition Program](contributor-badges/contributor-recognition-program.md)

## Images and Diagrams

Diagrams and images are located in [images/](images/)

## Quick Navigation

### By Topic

**XA Transactions:**
- Getting Started: [XA Support](xa/XA_SUPPORT.md)
- Implementation Details: [XA Transaction Flow](xa/XA_TRANSACTION_FLOW.md)
- Configuration: [Atomikos XA Integration](xa/ATOMIKOS_XA_INTEGRATION.md)
- Multinode: [XA Multinode Failover](xa/XA_MULTINODE_FAILOVER.md)

**Multinode Deployments:**
- Overview: [Multinode README](multinode/README.md)
- Architecture: [Multinode Flow](multinode/MULTINODE_FLOW.md)
- Advanced: [Server Recovery](multinode/server-recovery-and-redistribution.md)

**Configuration:**
- JDBC Driver: [ojp-jdbc-configuration.md](configuration/ojp-jdbc-configuration.md)
- Server: [ojp-server-configuration.md](configuration/ojp-server-configuration.md)

**Contributing:**
- Getting Started: [Setup and Testing](code-contributions/setup_and_testing_ojp_source.md)
- Adding Features: [Adding Database XA Support](guides/ADDING_DATABASE_XA_SUPPORT.md)

**Releasing:**
- [Release Process & Maven Central Integration](guides/RELEASE_PROCESS.md)

## Document Organization

All documentation is organized under the `documents/` folder with the following structure:

```
documents/
├── ADRs/                      # Architecture Decision Records
├── code-contributions/        # Contributing guides
├── configuration/             # Configuration documentation
├── contributor-badges/        # Recognition program
├── designs/                   # Design documents
├── environment-setup/         # Database setup guides
├── fixed-issues/              # Issue fix documentation
├── guides/                    # Developer guides
├── images/                    # Diagrams and images
├── java-frameworks/           # Framework integration guides
├── multinode/                 # Multinode deployment documentation
├── protocol/                  # Protocol specifications
├── runnable-jar/              # JAR execution guides
├── targeted-problem/          # Problem statements
├── telemetry/                 # Telemetry documentation
└── xa/                        # XA transaction documentation
```
