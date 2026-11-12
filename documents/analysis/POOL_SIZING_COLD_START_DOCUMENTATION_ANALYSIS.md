# Connection Pool Sizing and Cold-Start Documentation Analysis

## Findings

OJP performs admission control before database work. The configured
`ojp.connection.pool.connectionTimeout` is the admission wait budget; after
admission, backend pool acquisition is fail-fast. OJP configures HikariCP's
internal acquisition timeout to 1 ms, which HikariCP clamps to 250 ms.

When `minimumIdle` is below `maximumPoolSize`, the pool can create physical
connections as demand grows. If creation takes longer than the internal
acquisition timeout, requests can fail even while the pool has not reached its
maximum. Equal minimum and maximum sizes reduce this risk after the pool has
populated, but do not make all connections ready immediately.

## Documents to Update

- `documents/connection-pool/configuration.md` — core property reference,
  provider mappings, and sizing examples.
- `documents/connection-pool/migration-guide.md` and `README.md` — explain
  sizing and fail-fast acquisition consistently for HikariCP and DBCP2.
- `documents/configuration/ojp-server-configuration.md` — clarify admission
  waiting versus the HikariCP acquisition timeout.
- `documents/configuration/ojp-jdbc-configuration.md` — align named datasource
  and production examples with the optional low-latency sizing guidance.
- `documents/configuration/ojp-prod.properties` — explain the optional
  full-minimum configuration for high-concurrency production workloads.
- `documents/analysis/ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md` — keep the
  existing admission-control explanation consistent with the HikariCP floor.
- `documents/ebook/part2-chapter5-jdbc-configuration.md` — pool sizing and
  configuration examples.
- `documents/ebook/part1-chapter2-architecture.md` — correct pool property
  names and timeout descriptions in the configuration example.
- `documents/ebook/part2-chapter6-server-configuration.md` — admission and
  acquisition timeout behavior.
- `documents/ebook/part2-chapter7-framework-integration.md` — distinguish
  framework, admission, and backend acquisition timeouts.
- `documents/ebook/part3-chapter9-multinode-deployment.md` — pool sizing and
  aggregate database connection budgets across nodes.
- `documents/ebook/part3-chapter10-xa-transactions.md` — correct claims that
  every configured XA idle session is ready at startup.
- `documents/ebook/part3-chapter12-pool-provider-spi.md` — provider behavior
  and examples.
- `documents/ebook/part7-chapter22-performance-engineering.md` — correct the
  application-side pooling recommendation and document server-side pool sizing.
- `documents/ebook/part6-chapter20-lessons-learned.md` — qualify older guidance
  about idle reserves and connection timeout behavior.
- `documents/ebook/appendix-a-command-reference.md`,
  `appendix-b-database-guides.md`, `appendix-c-glossary.md`,
  `appendix-f-visual-assets.md`, and
  `appendix-g-troubleshooting.md` — align examples, terms, and troubleshooting.
- `documents/multi-language-client-spec/CLIENT_SPEC.md` and
  `documents/multinode/XA_MANAGEMENT.md` — clarify that pool creation or
  resizing does not guarantee every connection is ready immediately.

The single-page eBook HTML is rendered dynamically from the Markdown chapters,
so no generated HTML content needs to be edited.
