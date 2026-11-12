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
- `documents/ebook/part2-chapter5-jdbc-configuration.md` — pool sizing and
  configuration examples.
- `documents/ebook/part2-chapter6-server-configuration.md` — admission and
  acquisition timeout behavior.
- `documents/ebook/part3-chapter9-multinode-deployment.md` — pool sizing and
  aggregate database connection budgets across nodes.
- `documents/ebook/part3-chapter12-pool-provider-spi.md` — provider behavior
  and examples.
- `documents/ebook/part7-chapter22-performance-engineering.md` — correct the
  application-side pooling recommendation and document server-side pool sizing.
- `documents/ebook/appendix-a-command-reference.md`,
  `appendix-b-database-guides.md`, `appendix-c-glossary.md`, and
  `appendix-g-troubleshooting.md` — align examples, terms, and troubleshooting.

The single-page eBook HTML is rendered dynamically from the Markdown chapters,
so no generated HTML content needs to be edited.
