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
- **Load balancing & failover.** Multinode support inside the JDBC driver routes load across multiple OJP servers and fails over transparently.

This intelligent, control-plane–style allocation of connections helps prevent overloading databases and ensures that the number of open connections remains efficient, even during heavy elastic scaling of applications — while also giving teams a single place to enforce policy and observe behaviour across many databases.
