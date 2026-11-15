# Update on PR #601: Node.js driver

**As of 15 Nov 2026**

## Where things stand

PR [#601](https://github.com/Open-J-Proxy/ojp/pull/601) is still open, but its work has since been split into smaller, merged PRs. The Node client is now in `ojp-node-driver`; the TypeORM adapter is in `ojp-node-typeorm-adapter`; and `ojp-grpc-contract` is the shared source for the gRPC contract.

The Node client is assessed at **L10 for SQL Server** and **L1 for H2**. It supports PostgreSQL through the TypeORM adapter, but the Node client README does not claim a PostgreSQL implementation level. The adapter is experimental and supports PostgreSQL and SQL Server.

## Main follow-ups

- **Shared standards and contract:** [#602](https://github.com/Open-J-Proxy/ojp/pull/602) defined client levels L1–L10; [#735](https://github.com/Open-J-Proxy/ojp/pull/735) clarified guidance for non-Java clients; [#775](https://github.com/Open-J-Proxy/ojp/pull/775) made `ojp-grpc-contract` the shared proto source.
- **Core client extraction:** [#782](https://github.com/Open-J-Proxy/ojp/pull/782) extracted the Node driver from #601, adopted the shared contract, fixed core review gaps, and added integration CI.
- **Capability coverage:** [#785](https://github.com/Open-J-Proxy/ojp/pull/785), [#786](https://github.com/Open-J-Proxy/ojp/pull/786), [#787](https://github.com/Open-J-Proxy/ojp/pull/787), [#788](https://github.com/Open-J-Proxy/ojp/pull/788), and [#789](https://github.com/Open-J-Proxy/ojp/pull/789) built SQL Server coverage from L1 through L5: lifecycle, statements, result streaming, transactions, and LOBs. [#792](https://github.com/Open-J-Proxy/ojp/pull/792), [#793](https://github.com/Open-J-Proxy/ojp/pull/793), [#794](https://github.com/Open-J-Proxy/ojp/pull/794), [#795](https://github.com/Open-J-Proxy/ojp/pull/795), and [#796](https://github.com/Open-J-Proxy/ojp/pull/796) extended it from L6 through L10: session affinity, multinode operation, failover/recovery, XA, and mTLS.
- **Dependency maintenance:** [#797](https://github.com/Open-J-Proxy/ojp/pull/797), [#799](https://github.com/Open-J-Proxy/ojp/pull/799), [#800](https://github.com/Open-J-Proxy/ojp/pull/800), and [#801](https://github.com/Open-J-Proxy/ojp/pull/801) addressed gRPC/protobuf vulnerabilities and removed or updated problematic dependencies.
- **TypeORM adapter:** [#802](https://github.com/Open-J-Proxy/ojp/pull/802) landed the adapter separately, with PostgreSQL and SQL Server integration tests.

## What changed from the draft

- The proto files are no longer a Node-only copy; Java and non-Java clients use the shared contract.
- Client maturity is measured and backed by real-server integration suites and CI, rather than broad feature claims.
- The adapter now opens sessions per operation and keeps a session only for a transaction; client-side session pooling was removed.
- The TypeORM package is explicitly experimental, is not a full `pg` or `mssql` replacement, and does not support SQL Server SNAPSHOT isolation.

**In short:** the draft became a shared-contract foundation, a separately maintained Node client with certified SQL Server L1–L10 coverage, and a separately tested but still experimental TypeORM adapter.
