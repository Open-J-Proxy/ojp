## Target problem
In modern architectures, such as microservices, event-driven systems, or serverless (Lambda) architectures, a common issue arises in managing the number of open connections to relational databases. When applications need to elastically scale, they often maintain too many database connections. These connections can be held for longer than necessary, locking resources and making scalability difficult. In some cases, this can lead to excessive resource consumption, placing immense pressure on the database. In extreme scenarios, this can even result in database outages.

Connection storms are only the most visible symptom. The same workloads also tend to mix short OLTP queries with long reporting/analytical queries on the same database, leak observability (it is hard to see *why* the data tier is under pressure), and provide no coordinated way for clients to back off when the database is saturated.

---

## The solution
OJP acts as a **smart database control plane**: a programmable layer that sits between applications and their relational databases and enforces quality-of-service across the whole data tier.

- **Just-in-time connection allocation.** Real connections to the database are established only when an actual operation is performed, instead of being held open continuously.
- **Global backpressure.** Per-database admission control caps the number of in-flight operations, so elastic application fleets cannot overwhelm the database.
- **Client-side reactive throttling.** When the server detects pressure, clients are signaled to throttle themselves and recover automatically.
- **Slow vs fast query segregation.** Optional lane-based segregation prevents long analytical queries from starving fast OLTP traffic on the same database.
- **Built-in observability.** OpenTelemetry traces and Prometheus metrics expose pool, admission, classification and throttling behaviour, so operators can see what the data tier is doing.
- **Client-side load balancing & automatic failover.** Configure multiple OJP server endpoints in the JDBC URL; the driver selects healthy servers to balance new work and retries on connection-level failures. Session-bound work retains server affinity, so failover is subject to session semantics.

## How OJP differs from common proxy approaches

Database proxies often solve a narrower problem or operate at a different layer:

- **Wire-protocol proxies are database-specific.** A proxy that speaks a database's wire protocol typically supports one database or a related family; adding another engine can require a separate protocol implementation.
- **Cloud-managed proxies are provider-scoped.** Their supported databases and deployment options depend on the cloud provider and services they target.
- **Network-level proxies generally cannot shape work at each client.** They can manage database-side connections, but without a coordinated client driver they cannot adaptively tell individual applications to reduce concurrent requests. OJP's JDBC driver and server exchange pressure signals so each client can throttle its own workload.

OJP instead uses JDBC between its server and the database, so it can support databases with compatible JDBC drivers without implementing a separate wire protocol for each engine. Along with client-aware throttling, OJP combines global admission control, optional fast/slow query lanes, observability, and multinode routing and failover. These are common trade-offs rather than universal limits; capabilities vary across products.

This intelligent, control-plane–style allocation of connections helps prevent overloading databases and ensures that the number of open connections remains efficient, even during heavy elastic scaling of applications — while also giving teams a single place to enforce policy and observe behaviour across many databases.
