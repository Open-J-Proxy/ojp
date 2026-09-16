# Deploy OJP JDBC Driver

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest achieved level in this module | **L10** |
| Summary | `ojp-jdbc-driver` is the reference implementation and includes the full protocol/operational feature surface. Database-specific validated level still varies by database and is tracked in the central matrix. |

| Level | Status in `ojp-jdbc-driver` | Notes |
|---|---|---|
| L1 | ✅ Achieved | Core connect/query/update/session lifecycle implemented. |
| L2 | ✅ Achieved | Typed parameters and JDBC statement variants are implemented. |
| L3 | ✅ Achieved | Streaming + paged result/resource protocol is implemented. |
| L4 | ✅ Achieved | Non-XA transactions and savepoints are implemented. |
| L5 | ✅ Achieved | LOB and stream APIs are implemented. |
| L6 | ✅ Achieved | Session affinity and target-server routing are implemented. |
| L7 | ✅ Achieved | Multinode load balancing, health checks, and cluster sync are implemented. |
| L8 | ✅ Achieved | Failover/recovery operational mechanisms are implemented. |
| L9 | ✅ Achieved | XA transaction APIs are implemented. |
| L10 | ✅ Achieved | Full client capability surface is implemented in this module. |

Level definitions: [`../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

> mvn clean deploy

Also need to deploy the ojp parent. Navigate to root folder uncomment the sign-artifacts plugin and execute:

> mvn deploy -N

