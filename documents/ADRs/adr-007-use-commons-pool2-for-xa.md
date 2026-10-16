# ADR 007: Use Apache Commons Pool 2 with Custom XA Session Pooling Instead of Agroal

## Understanding OJP's Role in XA Architecture

**Critical Context**: Before diving into the pooling decision, it's essential to understand OJP's architectural role:

- **OJP is NOT a Transaction Manager (TM)**: The TM sits on the application side (e.g., Spring Boot + Atomikos/Narayana). The TM coordinates distributed transactions, maintains durable transaction logs, and drives recovery procedures.

- **OJP is NOT a Resource Manager (RM)**: The databases (PostgreSQL, Oracle, MySQL, SQL Server) are the RMs. They execute prepare/commit/rollback operations and persist transaction state durably in their write-ahead logs (WAL). OJP simply delegates RM operations to the databases.

- **OJP is a Connection Proxy**: OJP provides connection pooling, high availability, load balancing, and transparent proxying of JDBC/XA calls from the application TM to database RMs.

**XA Session Stickiness**: Each new `XAConnection` can be assigned to a different OJP node, but its session stays on that node for the lifetime of the `XAConnection`, including sequential transactions. The driver routes requests using the session UUID and its server binding, not a transaction-long gRPC stream. See [Session Management](../ebook/part1-chapter2-architecture.md#session-management) for a simple example and the limits during network or server failures.

## Decision Context

In the context of the OJP proxy server,  
facing the need to pool XA-enabled database connections for distributed transactions with leak detection and validation monitoring capabilities,  

we decided for implementing **custom XA session pooling on top of Apache Commons Pool 2**  
and neglected using Agroal connection pool,  

to achieve universal database compatibility, zero vendor dependencies, and compatibility with OJP's XABackendSession architecture,  
accepting the responsibility of implementing leak detection and monitoring features ourselves,  

because:
1. **Architecture Compatibility**: OJP pools `XABackendSession` objects (wrappers containing transaction state and OJP-specific logic), not raw `XAConnection` objects. Agroal pools `XAConnection` directly and its leak detection cannot track leaks in our wrapper layer, negating the primary benefit.

2. **Universal Provider Model**: Our Commons Pool 2 implementation works with ALL databases (PostgreSQL, SQL Server, DB2, MySQL, MariaDB) via reflection with zero compile-time vendor dependencies. Agroal requires explicit DataSource configuration and would compromise this universal model.

3. **Flexibility**: Apache Commons Pool 2's generic `PooledObjectFactory<T>` design allows pooling of arbitrary object types, perfectly matching our need to pool `XABackendSession` objects rather than raw connections.

4. **Battle-Tested Maturity**: Commons Pool 2 has 15+ years of production use (Tomcat, DBCP) versus Agroal's 5+ years, and our implementation is already working well in production without reported issues.

5. **Migration Risk vs. Benefit**: Switching to Agroal would require 3-6 months of development with major architectural changes, 5-10x testing burden, and high risk of breaking production XA transaction handling—all for benefits that wouldn't materialize due to architectural incompatibility.

6. **Enhancement Path**: Required features (leak detection, validation tasks, monitoring) can be added to our existing implementation in 4-5 weeks with low risk and no breaking changes, achieving the same goals more efficiently.

The custom implementation includes:
- `CommonsPool2XAProvider`: Universal XA connection pool provider working with any XADataSource via reflection
- `BackendSessionFactory`: Manages `XABackendSession` lifecycle (creation, validation, passivation, destruction)
- `CommonsPool2XADataSource`: Wrapper providing pool management for XA sessions
- Configurable pool sizing, validation, eviction, and timeout policies
- Planned enhancements for leak detection, background monitoring, and enhanced validation

| Status      | APPROVED        |  
|-------------|-----------------| 
| Proposer(s) | GitHub Copilot Analysis | 
| Proposal date | 08/01/2026      | 
| Approver(s) | Pending Review  |
| Approval date | Pending         | 

## References

- **Analysis Document**: `documents/analysis/AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md`
- **Executive Summary**: `documents/analysis/AGROAL_EVALUATION_SUMMARY.md`
- **Implementation**: `ojp-xa-pool-commons` module

## Decision Factors Summary

| Factor | Commons Pool 2 Custom | Agroal | Winner |
|--------|----------------------|--------|--------|
| Compatible with XABackendSession wrapper | ✅ Yes | ❌ No | Commons Pool 2 |
| Universal (works with all databases via reflection) | ✅ Yes | ❌ No | Commons Pool 2 |
| Zero vendor dependencies | ✅ Yes | ❌ No | Commons Pool 2 |
| Built-in leak detection | ⚠️ Must implement | ✅ Yes (but incompatible) | Can enhance |
| Production maturity | ✅ 15+ years | ⚠️ 5+ years | Commons Pool 2 |
| Migration risk | ✅ None (current) | 🔴 High | Commons Pool 2 |
| Development effort for enhancement | ✅ 4-5 weeks | 🔴 3-6 months | Commons Pool 2 |

## Future Considerations

- Monitor Agroal evolution and potential architectural changes that might enable future compatibility
- Consider Agroal as an optional alternative provider if OJP's architecture changes to pool raw connections
- Planned enhancement phases to add leak detection and monitoring to current implementation (maintaining backward compatibility)
