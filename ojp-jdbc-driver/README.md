# Deploy OJP JDBC Driver

> mvn clean deploy

Also need to deploy the ojp parent. Navigate to root folder uncomment the sign-artifacts plugin and execute:

> mvn deploy -N

## Client Implementation Levels (L1–L10)

Reference: [`../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

| Level | Focus |
|---|---|
| L1 | Basic connectivity + CRUD |
| L2 | Typed parameters + statement variants |
| L3 | Result-set streaming/pagination protocol |
| L4 | Non-XA transaction semantics + savepoints |
| L5 | LOB/stream handling |
| L6 | Session affinity correctness |
| L7 | Multinode load balancing + health + cluster sync |
| L8 | Failover/recovery/redistribution operational resilience |
| L9 | XA transaction support |
| L10 | Full operational conformance in multinode scenarios |
