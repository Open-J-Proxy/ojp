# Connection Count Validation for Pool Resizing - Analysis

## Overview

This directory contains a comprehensive analysis of implementing database connection count validation before resizing OJP server connection pools in multinode deployments. The goal is to distinguish between true node failures and network partitions to prevent unnecessary pool resizing.

## Problem Statement

In a multinode OJP setup (e.g., 3 servers with 10 max connections each), when a server appears down to some clients, the remaining servers expand their pools (e.g., to 15 connections each). However, in a network partition scenario, the "failed" server may still be serving other clients, leading to:

- Total connections exceeding the configured limit (10 + 15 + 15 = 40 instead of 30)
- Potential database overload
- Unnecessary resource consumption

## Proposed Solution

Before resizing connection pools, query the database to verify the actual number of connections from the current user:

- **High connection count** (near total max): Likely network partition → **Skip resize**
- **Low connection count** (well below max): True node failure → **Proceed with resize**

## Documents in This Analysis

### 1. [ANALYSIS.md](./ANALYSIS.md)

**Primary analysis document** containing:

- ✅ Detailed feasibility assessment
- ✅ Critical questions and concerns
- ✅ Design opinions and suggestions
- ✅ Criticisms and risk analysis
- ✅ Alternative approaches considered
- ✅ Implementation recommendations
- ✅ Testing strategy
- ✅ Go/No-Go decision framework

**Key Findings:**

- **Feasible** across all major databases (PostgreSQL, MySQL, Oracle, SQL Server, DB2, H2, CockroachDB)
- **Moderate complexity** (~500-1000 lines of code)
- **Low overhead** (~10-100ms per validation, rare execution)
- **Recommended approach**: Opt-in feature with fail-open behavior

**Key Concerns:**

1. Multiple database users sharing OJP deployment
2. Database-level connection pooling interference
3. Query failure handling strategy
4. Threshold tuning requirements
5. Incomplete solution for all partition scenarios

**Recommendation**: **GO** with opt-in, fail-open implementation

### 2. [DATABASE_CONNECTION_COUNT_QUERIES.md](./DATABASE_CONNECTION_COUNT_QUERIES.md)

**Database-specific query reference** containing:

- ✅ SQL queries for each supported database
- ✅ Permission requirements per database
- ✅ Query performance characteristics
- ✅ Implementation considerations
- ✅ Testing strategies
- ✅ Monitoring recommendations

**Highlights:**

- All queries use system views/tables (no application schema changes)
- Most databases require no special permissions for current user queries
- Queries exclude the validation query connection itself
- Typical execution time: 10-100ms

### 3. [IMPLEMENTATION_GUIDE.md](./IMPLEMENTATION_GUIDE.md)

**Detailed implementation guide** containing:

- ✅ Architecture diagrams
- ✅ Component specifications
- ✅ Code structure and interfaces
- ✅ Complete implementation examples
- ✅ Integration points
- ✅ Testing approach
- ✅ Deployment plan

**Key Components:**

1. `ConnectionCountQuery` interface - Database-specific query abstraction
2. `ConnectionCountQueryFactory` - Maps database types to queries
3. `PoolResizeValidator` - Orchestrates validation logic
4. `ValidationResult` - Encapsulates validation decision
5. Integration with `ProcessClusterHealthAction`

**Estimated Effort**: 3-4 weeks (development + testing + documentation)

## Quick Decision Summary

### Should We Implement This?

**YES**, with the following approach:

| Aspect | Recommendation | Rationale |
|--------|----------------|-----------|
| **Default State** | Disabled (opt-in) | Minimize risk, let users enable when needed |
| **Failure Handling** | Fail-open (proceed with resize) | Prioritize availability over correctness |
| **Threshold** | 85% of max pool size | Balance between false positives and false negatives |
| **Rate Limiting** | 1 query per 5 seconds per datasource | Prevent excessive database load |
| **Time Override** | Force resize after 5 minutes | Prevent permanent pool size mismatch |
| **Initial Databases** | PostgreSQL, MySQL first | Cover 80% of users, add others incrementally |

### Configuration Example

```properties
# Enable connection count validation (opt-in)
ojp.pool.resize.validation.enabled=true

# Connection count threshold (85% of max pool size)
ojp.pool.resize.validation.connectionThreshold=0.85

# Fail-open: proceed with resize on validation failure
ojp.pool.resize.validation.failureMode=PROCEED

# Query timeout
ojp.pool.resize.validation.queryTimeout=5000

# Rate limiting (max 1 query per 5 seconds)
ojp.pool.resize.validation.rateLimitMs=5000

# Force resize after 5 minutes regardless of validation
ojp.pool.resize.validation.forceResizeAfterMs=300000
```

## Benefits vs Trade-offs

### Benefits ✅

1. **Prevents unnecessary pool expansion** in network partition scenarios
2. **Reduces risk** of exceeding database connection limits
3. **Provides operators control** over pool behavior
4. **Works across all major databases** with minimal overhead
5. **Safe fallback** (proceeds with resize on errors)

### Trade-offs ⚠️

1. **Added complexity** (~500-1000 lines of code)
2. **Requires database permissions** in some cases (Oracle, SQL Server, DB2)
3. **Heuristic-based** (not 100% accurate)
4. **Small performance overhead** (~10-100ms per health change)
5. **Maintenance burden** (7 database-specific implementations)

## When to Use This Feature

### ✅ Good Use Cases

- Multi-region deployments with potential network partitions
- Environments where exceeding connection limits is critical
- Deployments with strict database connection quotas
- Operators who can monitor and tune thresholds

### ❌ Not Recommended For

- Single-region, reliable network environments
- Deployments without monitoring/observability
- Environments where database permissions are restricted
- Simple single-node deployments

## Next Steps

If approved:

1. **Week 1-2**: Implement core validation logic (PostgreSQL, MySQL first)
2. **Week 3**: Add remaining database support and integration
3. **Week 4**: Comprehensive testing (unit, integration, performance)
4. **Week 5**: Documentation and beta testing
5. **Week 6**: Final QA and release

## Questions or Feedback?

This analysis is intended to facilitate discussion and decision-making. Key topics for review:

1. **Is the opt-in approach acceptable?** (vs default-enabled)
2. **Is the 85% threshold reasonable?** (configurable, but needs a good default)
3. **Is fail-open the right default?** (vs fail-closed)
4. **Should we support all databases initially?** (vs PostgreSQL/MySQL first)
5. **Are there use cases we haven't considered?**

## Related Documentation

- [Multinode Architecture](../../multinode/README.md)
- [Server Recovery and Redistribution](../../multinode/server-recovery-and-redistribution.md)
- [Connection Pool Configuration](../../connection-pool/configuration.md)
- [OJP Server Configuration](../../configuration/ojp-server-configuration.md)

---

**Analysis Version**: 1.0  
**Date**: 2026-01-19  
**Status**: Complete - Ready for Review  
**Recommendation**: Implement as opt-in feature with fail-open behavior
