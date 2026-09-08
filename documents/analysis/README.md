# OJP Analysis Documents Index

This directory contains technical analysis documents for various OJP features and decisions.

## Latest Analysis (September 2026)

### 🆕 Generic OJP Messaging Protocol (server-to-server and server-to-client)

**Question:** How should OJP servers exchange messages with each other (e.g.
RAFT leader election, cache-invalidation broadcasts) and notify connected
JDBC clients (e.g. server restarting), reusing the existing
`ojp-jdbc-driver` for transport wherever possible, and — by default — without
any new connection between OJP servers?

**Quick Answer:** Add a generic `MessagingService` gRPC contract (`Publish`,
`Subscribe`, `Ack`; topic + opaque payload + delivery mode), consumed by
every participant through the existing JDBC driver's connection/session/
failover machinery. One topology setting governs every message type
(consensus included) — a default, always-on **client-relay** mode that adds
no new connections at all, or an opt-in **direct mesh** mode
(`ojp.server.mesh.enabled`) for deployments that genuinely need a link
independent of client presence (e.g. serverless), built by reusing the
driver's client-side gRPC plumbing rather than a bespoke protocol.

**Documents:**
- **Executive Summary**: [OJP_MESSAGING_PROTOCOL_SUMMARY.md](./OJP_MESSAGING_PROTOCOL_SUMMARY.md)
  - Recommended protocol shape and delivery modes
  - Options considered with verdicts
  - Biggest open concerns and questions
- **Full Analysis**: [OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
  - Detailed envelope/proto sketch, delivery-mode comparison table
  - Server-to-server and server-to-client topology
  - Mapping to RAFT / cache invalidation / restart-notice use cases
  - Concerns, open questions, and suggested phasing
- **Related Analysis**: [OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md)
  - RAFT vs. Byzantine-fault-tolerant alternatives (PBFT, HotStuff, Tendermint,
    BFT-SMaRt) for the leader-election use case
  - How consensus runs over each topology: direct mesh when mesh is ON,
    encrypted client-relay when mesh is OFF (the approach for that case,
    not a fallback)
  - Pros/cons, recommendation, and open questions — RAFT is a running example
    in the messaging protocol documents above, not a committed decision

**Key Takeaway:** OJP servers become driver-backed clients of each other
(and of connected application clients, via a client-initiated subscribe
stream) by default, with an opt-in direct mesh for deployments where client
connectivity can't be relied upon — both built on the driver's existing
resiliency features rather than a new bespoke transport. One switch decides
the topology for every message type, consensus included.

---

## Previous Latest Analysis (May 2026)

### 🆕 Prepared Statement Cache Server Settings Design

**Question:** How should OJP expose default-enabled prepared statement caching using standard OJP server properties and runtime datasource translation?

**Quick Answer:** Add canonical `ojp.connection.pool.statementCache.*` and `ojp.xa.connection.pool.statementCache.*` settings (default enabled), then translate to driver-specific datasource properties during pool creation.

**Documents:**
- **Executive Summary**: [PREPARED_STATEMENT_CACHE_SETTINGS_SUMMARY.md](./PREPARED_STATEMENT_CACHE_SETTINGS_SUMMARY.md)
  - Proposed canonical property keys
  - Default values and precedence
  - Runtime translation model by database
  
- **Full Analysis**: [PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md](./PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md)
  - Detailed property model (global server settings only)
  - Canonical-to-driver translation matrix
  - Scope/units clarification for each setting
  - Validation, observability, risk assessment
  - Future implementation outline (design-only report)

**Key Takeaway:** Keep user configuration database-agnostic in OJP notation and centralize DB-specific behavior in a server-side translation layer applied at datasource creation time.

---

## Previous Latest Analysis (January 2026)

### 🆕 Agroal Connection Pool Evaluation

**Question:** Should OJP replace Apache Commons Pool 2 with Agroal for XA connection pooling?

**Quick Answer:** NO - Enhance existing implementation instead.

**Documents:**
- **Executive Summary**: [AGROAL_EVALUATION_SUMMARY.md](./AGROAL_EVALUATION_SUMMARY.md) - 5 min read
  - Quick decision reference
  - Key findings and recommendation
  - Action items
  
- **Full Analysis**: [AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md](./AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md) - 30 min read
  - Comprehensive technical analysis (25+ feature comparisons)
  - Architecture compatibility analysis
  - Migration challenges and risks (7 challenges, 8 risk factors)
  - Alternative approaches (4 detailed options)
  - Implementation plan for enhancement approach

**Key Takeaway:** Agroal is excellent for standalone JDBC pools, but OJP's architecture (pooling XABackendSession wrappers, not raw XAConnections) makes it incompatible. The recommended approach is to enhance Commons Pool 2 with leak detection and monitoring features - same benefits, 80% less effort and risk.

---

## Other Analysis Documents

### XA Pool Architecture

- [xa-pool-spi/](./xa-pool-spi/) - XA Connection Pool SPI design
  - API Reference
  - Configuration Guide
  - Database XA Pool Libraries Comparison
  - Implementation Guide
  - Oracle UCP Integration Analysis
  - XA Pool Provider SPI Migration Analysis
  - XA Transaction Flow Diagrams

### Transaction Isolation

- [TRANSACTION_ISOLATION_ANALYSIS_SUMMARY.md](./TRANSACTION_ISOLATION_ANALYSIS_SUMMARY.md) - Summary of transaction isolation analysis
- [TRANSACTION_ISOLATION_HANDLING.md](./TRANSACTION_ISOLATION_HANDLING.md) - Detailed transaction isolation handling

### Pool Management

- [POOL_DISABLE_FINAL_SUMMARY.md](./POOL_DISABLE_FINAL_SUMMARY.md) - Analysis of pool disable functionality
- [ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md) - Always-on semaphore admission control rationale and implementation notes
- [ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md](./ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md) - Short summary of the current request admission, timeout, and backpressure model
- [PREPARED_STATEMENT_CACHE_SETTINGS_SUMMARY.md](./PREPARED_STATEMENT_CACHE_SETTINGS_SUMMARY.md) - Prepared statement cache settings design (summary)
- [PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md](./PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md) - Prepared statement cache settings design (detailed)

### Driver Architecture

- [DRIVER_EXTERNALIZATION_IMPLEMENTATION_SUMMARY.md](./DRIVER_EXTERNALIZATION_IMPLEMENTATION_SUMMARY.md) - Driver externalization implementation

---

## How to Use These Documents

### For Stakeholders / Decision Makers
Start with executive summaries:
1. Read **AGROAL_EVALUATION_SUMMARY.md** for latest recommendation
2. Review other `*_SUMMARY.md` files for quick context

### For Developers
Dive into full analyses:
1. Read **AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md** for technical details
2. Explore [xa-pool-spi/](./xa-pool-spi/) for architecture documentation

### For Reviewers
Both summaries and detailed analyses are available:
1. Summaries for quick approval decisions
2. Full analyses for technical review

---

## Document Status Legend

- 🆕 **Latest** - Recently completed analysis
- ✅ **Approved** - Decision made and implemented
- 📋 **Draft** - Under review
- 📚 **Reference** - Background/architecture documentation

---

## Contributing

When adding new analysis documents:
1. Create both a summary (< 10 pages) and full analysis (detailed)
2. Use consistent markdown formatting
3. Include tables for feature comparisons
4. Add risk assessments where applicable
5. Update this index

---

**Last Updated:** 2026-09-02  
**Maintained By:** OJP Core Team
