# Appendix C: Glossary

This glossary defines key terms, acronyms, and concepts used throughout this book and in the OJP project. Terms are organized alphabetically for quick reference.

---

## A

**ADR (Architecture Decision Record)**  
A document that captures an important architectural decision made during a project, including the context, decision, and consequences. OJP maintains ADRs in the `documents/ADRs/` directory to track the evolution of architectural choices.

**Apache Commons Pool**  
An object pooling library used by OJP for XA backend session pooling. It provides generic object pool management with configurable lifecycle policies, eviction strategies, and resource management.

**Atomikos**  
A popular JTA transaction manager that provides XA transaction coordination for distributed transactions. Often used with OJP for multi-database XA transactions.

---

## B

**Backend Session Pool**  
In OJP's XA implementation, the server-side pool that manages `XAConnection` instances from the database. These connections are reused across multiple XA transactions to improve performance by avoiding repeated XA connection establishment overhead.

**Backpressure**  
A flow control mechanism where the system signals to clients that it's under load and they should slow down request rates. OJP implements backpressure through connection limits, timeout management, and connection acquisition queueing.

**Bitr onix**  
A JTA transaction manager that can coordinate XA transactions. Similar to Atomikos but with different features and licensing model.

---

## C

**Circuit Breaker**  
A resilience pattern that prevents cascading failures by temporarily blocking requests to a failing service. OJP can be configured with circuit breaker patterns to protect database resources during degraded conditions.

**Connection Acquisition**  
The process of obtaining a database connection from the pool. In OJP, clients request virtual connections while the server manages real database connections, with timeout protection to prevent indefinite blocking.

**Connection Leak**  
A programming error where a connection is obtained but never returned to the pool (typically because `close()` was never called). OJP provides leak detection through the `leakDetectionThreshold` configuration.

**Connection Pool**  
A cache of database connections maintained to avoid the overhead of creating new connections for every request. OJP centralizes pooling on the server side, allowing lightweight clients.

**Connection Pool Provider SPI**  
An extensibility mechanism in OJP that allows plugging in different connection pool implementations. The default is HikariCP, but the SPI enables alternatives through the Java ServiceLoader mechanism.

---

## D

**DataSource**  
A JDBC interface that represents a factory for database connections. OJP implements `DataSource` in its JDBC driver, returning virtual connections that delegate to the server.

**Driver Manager**  
The traditional JDBC mechanism for loading and managing JDBC drivers. OJP registers itself with `DriverManager` so applications can use it with the standard `DriverManager.getConnection()` API.

**Dual-Condition Lifecycle**  
In OJP's XA implementation, the policy that an `XAConnection` on the server is only returned to the backend session pool when both (1) the XA transaction completes and (2) the client closes its `XAConnection`. This prevents premature connection recycling.

---

## E

**Eviction Policy**  
In connection pooling, the strategy for removing idle connections from the pool. OJP delegates this to HikariCP, which uses time-based eviction with configurable idle timeout.

---

## F

**Failover**  
The automatic switching to a backup server when the primary server fails. OJP's multinode support provides automatic failover by detecting server failures and routing requests to healthy servers.

---

## G

**Grafana**  
An open-source analytics and monitoring platform commonly used to visualize Prometheus metrics. OJP metrics can be displayed in Grafana dashboards showing pool health, connection rates, and slow query statistics.

**gRPC (gRPC Remote Procedure Call)**  
A high-performance RPC framework created by Google. OJP uses gRPC as its communication protocol between the JDBC driver (client) and the server, leveraging HTTP/2 multiplexing and Protocol Buffers.

---

## H

**Health Check**  
A mechanism to verify that a service is operational. OJP servers respond to health check probes (Kubernetes liveness/readiness probes) to indicate availability.

**HikariCP**  
A high-performance JDBC connection pool library. OJP uses HikariCP as its default connection pool implementation on the server side. "Hikari" means "light" in Japanese, reflecting its fast, lightweight design.

**HTTP/2**  
The network protocol underlying gRPC communication in OJP. HTTP/2's multiplexing capability allows multiple concurrent requests over a single TCP connection, reducing connection overhead.

---

## I

**Idempotent**  
An operation that produces the same result whether executed once or multiple times. In distributed transactions, idempotent operations are crucial for handling retries and failures safely.

**Idle Connection**  
A connection in the pool that is not currently being used by a client. OJP maintains a minimum number of idle connections (`minimumIdle`) to ensure quick response times.

---

## J

**JAR (Java Archive)**  
A package file format used to aggregate many Java class files and associated metadata. OJP is distributed as a standalone JAR (`ojp-server-*.jar`) that can be executed directly.

**JDBC (Java Database Connectivity)**  
The standard Java API for connecting to relational databases. OJP implements the JDBC specification, providing a `Driver` that clients can use like any standard JDBC driver.

**JTA (Java Transaction API)**  
The Java specification for managing distributed transactions. OJP's XA support integrates with JTA transaction managers like Atomikos and Narayana.

---

## K

**Keepalive**  
A mechanism to keep connections alive by sending periodic pings. Both gRPC (for client-server connections) and HikariCP (for database connections) support keepalive to prevent connection timeouts.

**Kubernetes**  
An open-source container orchestration platform. OJP can be deployed on Kubernetes using Helm charts, with support for autoscaling, health checks, and configuration management.

---

## L

**Layer 7 Proxy**  
A proxy that operates at the application layer (Layer 7 of the OSI model), understanding the protocol being proxied. OJP is a Layer 7 proxy for JDBC, understanding SQL semantics and connection state.

**Liveness Probe**  
In Kubernetes, a health check that determines if a container should be restarted. OJP servers respond to liveness probes on the health endpoint.

**Load Balancing**  
The distribution of requests across multiple servers. In OJP's multinode configuration, the JDBC driver performs load-aware balancing, routing requests to the least-loaded server.

---

## M

**Mermaid**  
A text-based diagramming tool that generates diagrams from markdown-like syntax. This book uses Mermaid for sequence diagrams, flowcharts, and architecture diagrams.

**Metrics**  
Quantitative measurements of system behavior. OJP exposes metrics in Prometheus format, including connection pool stats, gRPC server metrics, and slow query counts.

**Microservices**  
An architectural style where applications are built as a collection of small, independently deployable services. OJP is designed for microservices architectures where centralized connection pooling provides significant benefits.

**Multinode Deployment**  
Running multiple OJP server instances for high availability and scalability. Clients connect to multiple servers, and the driver automatically distributes load and handles failover.

---

## N

**Narayana**  
A JTA transaction manager from Red Hat, commonly used with JBoss/WildFly. It can coordinate XA transactions with OJP.

**NetworkPolicy**  
In Kubernetes, a specification of how pods are allowed to communicate. OJP Helm charts can configure NetworkPolicy to restrict traffic to authorized sources.

---

## O

**OpenTelemetry**  
An observability framework for cloud-native software, providing APIs for metrics, logs, and distributed traces. OJP integrates OpenTelemetry for observability, exposing metrics in Prometheus format.

**OSI Model (Open Systems Interconnection)**  
A conceptual model that standardizes network functions into seven layers. OJP operates at Layer 7 (Application Layer), understanding JDBC protocol semantics.

---

## P

**Pool Exhaustion**  
A condition where all connections in the pool are in use and new requests must wait (or time out). OJP protects against indefinite blocking with configurable connection acquisition timeouts.

**Prepared Statement**  
A precompiled SQL statement that can be executed multiple times with different parameters. Prepared statements improve performance and prevent SQL injection. OJP proxies prepared statements through gRPC.

**Prometheus**  
An open-source monitoring system and time series database. OJP exposes metrics in Prometheus format, allowing integration with Prometheus-based monitoring stacks.

**Protocol Buffers (protobuf)**  
Google's language-neutral, platform-neutral serialization mechanism. OJP uses Protocol Buffers to define its gRPC service contract and message formats.

---

## Q

**Quarkus**  
A Kubernetes-native Java framework optimized for GraalVM and OpenJDK HotSpot. OJP integrates with Quarkus, with special considerations for native compilation and connection pooling.

---

## R

**Readiness Probe**  
In Kubernetes, a health check that determines if a container is ready to serve traffic. OJP servers respond to readiness probes, signaling when they're ready to accept connections.

**Read Replica**  
A database instance that replicates data from a primary instance, typically for read-only queries. OJP's multinode support can route read queries to replicas for load distribution.

**ResultSet**  
A JDBC interface representing the result of a database query. In OJP, ResultSet instances maintain their server-side connection until explicitly closed, ensuring data consistency.

**Rolling Update**  
A deployment strategy that gradually replaces old instances with new ones, maintaining availability. OJP supports rolling updates in Kubernetes through proper readiness probe configuration.

---

## S

**Saga Pattern**  
An alternative to XA transactions for distributed transactions, using compensating transactions instead of two-phase commit. OJP supports XA, but saga patterns may be more appropriate for certain use cases.

**Segregation (Query)**  
The separation of slow queries into a dedicated connection pool to prevent them from blocking fast queries. OJP's slow query segregation feature automatically classifies queries and routes them appropriately.

**Service Discovery**  
The mechanism by which clients find available service instances. In OJP multinode deployments, discovery is configuration-based (JDBC URL lists all servers).

**Session Affinity**  
Also called "sticky sessions," the routing of related requests to the same backend server. OJP implements session affinity for transactions and temporary tables. In the context of read/write splitting, sticky sessions ensure reads go to the primary for a configurable window after a write (see `stickySessionSeconds`). Sticky sessions are **opt-in** (default: disabled) — only enable when the application must see its own writes immediately outside of an explicit transaction.

**Slow Query**  
A database query that takes significantly longer than average to execute. OJP automatically detects slow queries and can segregate them to prevent resource contention.

**SPI (Service Provider Interface)**  
A Java pattern for providing pluggable implementations. OJP uses SPI for the Connection Pool Provider, allowing custom pool implementations.

**Spring Boot**  
An opinionated framework for building Spring applications with minimal configuration. OJP integrates with Spring Boot through standard JDBC DataSource configuration.

---

## T

**TCP/IP**  
The transport protocol used by both gRPC (HTTP/2 over TCP) and database connections. Connection issues often relate to TCP-level problems like firewalls or network partitions.

**Timeout**  
A limit on how long an operation can take before being cancelled. OJP uses timeouts extensively: connection acquisition timeout, gRPC call timeout, and connection validation timeout.

**Transaction**  
A unit of work performed against a database, with ACID properties (Atomicity, Consistency, Isolation, Durability). OJP maintains transaction state properly, ensuring connections aren't released mid-transaction.

**Two-Phase Commit (2PC)**  
A distributed transaction protocol that ensures all participants either commit or rollback a transaction. OJP's XA support implements 2PC for distributed transactions across multiple databases.

**Type 3 JDBC Driver**  
In JDBC driver classifications, a Type 3 driver is a pure Java driver that communicates with a middle-tier server, which then connects to the database. OJP is a modern Type 3 driver using gRPC.

---

## V

**Virtual Connection**  
In OJP, the lightweight connection object returned to clients by the JDBC driver. Virtual connections delegate operations to real database connections on the server via gRPC.

---

## X

**XA (eXtended Architecture)**  
A specification for distributed transaction processing. XA defines a protocol for two-phase commit across multiple resource managers (like databases). OJP supports XA through `XADataSource` and `XAConnection` interfaces.

**XAConnection**  
A JDBC interface representing a connection that can participate in XA distributed transactions. OJP's server maintains a backend pool of XAConnections for efficient XA transaction processing.

**XADataSource**  
A factory for creating XAConnection instances. OJP provides an `OJPXADataSource` class that clients can use for XA transactions.

**XAResource**  
The interface that represents a resource manager's participation in an XA transaction. Each XAConnection provides an XAResource for transaction coordination.

---

## Z

**Zero-Downtime Deployment**  
A deployment strategy where the system remains available during updates. OJP supports zero-downtime deployments through health probes, rolling updates, and graceful connection draining.

---

**IMAGE_PROMPT_C1:** Create an alphabetical glossary visualization showing key OJP terms arranged in a spiral or circular pattern, with related terms connected by colored lines. Use icons for categories: database icons for JDBC terms, network icons for gRPC terms, gauge icons for metrics terms, and tools icons for operational terms. Make it visually navigable like a mind map.

**IMAGE_PROMPT_C2:** Create a "terminology relationship map" showing how major OJP concepts connect. Center it on "OJP Architecture" with branches to "Client Side" (JDBC, Driver, Virtual Connection), "Server Side" (Pool, HikariCP, Backend Session), "Communication" (gRPC, Protocol Buffers, HTTP/2), and "Operations" (Metrics, Health Checks, Monitoring). Use distinct colors and connecting arrows to show dependencies.

---

This glossary serves as a quick reference for terms encountered throughout this book and in the OJP documentation. For more detailed explanations, refer to the relevant chapters where these concepts are discussed in depth.
