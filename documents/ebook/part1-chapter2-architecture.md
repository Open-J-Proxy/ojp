# Chapter 2: Architecture Deep Dive

> **Chapter Overview**: This chapter explores the internal architecture of OJP, detailing its components, communication protocols, and connection pool management. You'll understand how the pieces fit together to deliver intelligent database connection management.

---

## 2.1 System Components

OJP's architecture consists of three main components that work together to provide transparent connection management:

```mermaid
graph TB
    subgraph "Client Side"
    APP[Java Application]
    DRIVER[ojp-jdbc-driver<br/>JDBC Implementation]
    end
    
    subgraph "Shared Contract"
    GRPC[ojp-grpc-commons<br/>Protocol Definitions]
    end
    
    subgraph "Server Side"
    SERVER[ojp-server<br/>gRPC Server]
    HIKARI[HikariCP<br/>Connection Pool]
    end
    
    subgraph "Databases"
    DB1[(PostgreSQL)]
    DB2[(MySQL)]
    DB3[(Oracle)]
    end
    
    APP --> DRIVER
    DRIVER -.->|uses| GRPC
    DRIVER -->|gRPC/HTTP2| SERVER
    SERVER -.->|uses| GRPC
    SERVER --> HIKARI
    HIKARI --> DB1
    HIKARI --> DB2
    HIKARI --> DB3
    
    style DRIVER fill:#81c784
    style SERVER fill:#ffd54f
    style GRPC fill:#90caf9
    style HIKARI fill:#ff8a65
```

### ojp-server: The gRPC Server

```mermaid
graph TD
    subgraph "ojp-server Component View"
    subgraph "Network Endpoints"
    GRPC_ENDPOINT[gRPC Server Endpoint<br/>Port 1059]
    PROM_ENDPOINT[Prometheus Metrics Endpoint<br/>Port 9159]
    end
    subgraph "Request Processing"
    HANDLERS[Request Handler Threads<br/>or Virtual Threads]
    STMT_SERVICE[StatementServiceImpl<br/>SQL Execution API]
    SESSION_TRACKER[Session and Transaction Tracker]
    end
    subgraph "Pool Management"
    POOL_MANAGER[HikariCP Connection Pool Manager]
    PG_POOL[PostgreSQL Pool]
    MYSQL_POOL[MySQL Pool]
    ORACLE_POOL[Oracle Pool]
    end
    subgraph "Infrastructure"
    CONFIG_MANAGER[Configuration Manager<br/>Env Vars and JVM Properties]
    TELEMETRY[Metrics and Telemetry Collector<br/>OpenTelemetry]
    end
    subgraph "Database Backends"
    PG_DB[(PostgreSQL)]
    MYSQL_DB[(MySQL)]
    ORACLE_DB[(Oracle)]
    end
    GRPC_ENDPOINT --> HANDLERS
    HANDLERS --> STMT_SERVICE
    STMT_SERVICE --> SESSION_TRACKER
    STMT_SERVICE --> POOL_MANAGER
    POOL_MANAGER --> PG_POOL
    POOL_MANAGER --> MYSQL_POOL
    POOL_MANAGER --> ORACLE_POOL
    PG_POOL --> PG_DB
    MYSQL_POOL --> MYSQL_DB
    ORACLE_POOL --> ORACLE_DB
    CONFIG_MANAGER --> GRPC_ENDPOINT
    CONFIG_MANAGER --> POOL_MANAGER
    CONFIG_MANAGER --> PROM_ENDPOINT
    STMT_SERVICE --> TELEMETRY
    POOL_MANAGER --> TELEMETRY
    TELEMETRY --> PROM_ENDPOINT
    end
    style GRPC_ENDPOINT fill:#90caf9
    style PROM_ENDPOINT fill:#90caf9
    style HANDLERS fill:#81c784
    style STMT_SERVICE fill:#81c784
    style SESSION_TRACKER fill:#81c784
    style POOL_MANAGER fill:#ff8a65
    style PG_POOL fill:#ffcc80
    style MYSQL_POOL fill:#ffcc80
    style ORACLE_POOL fill:#ffcc80
    style CONFIG_MANAGER fill:#b0bec5
    style TELEMETRY fill:#b0bec5
```

The **ojp-server** is the heart of the OJP system. It's a standalone gRPC server that manages database connections and executes SQL operations on behalf of client applications.

At its core, the server listens for client requests over gRPC on port 1059 by default. When requests arrive, the server maintains HikariCP connection pools for each configured database, ensuring efficient resource utilization. The server then executes queries and updates against real database connections, managing the entire lifecycle of SQL operations. Throughout this process, it tracks client sessions and their transactional state, ensuring data consistency across distributed applications. For observability, the server exports comprehensive metrics via Prometheus on port 9159, and enforces security through IP whitelisting and access controls.

**Architecture Layers**:

```mermaid
graph TD
    subgraph "ojp-server Architecture"
    
    subgraph "API Layer"
    GRPC_API[gRPC API<br/>StatementService]
    end
    
    subgraph "Business Logic Layer"
    STMT[Statement Executor]
    CONN[Connection Manager]
    SESS[Session Manager]
    CIRC[Circuit Breaker]
    SLOW[Slow Query Segregation]
    end
    
    subgraph "Data Access Layer"
    POOL[HikariCP Pool Manager]
    PROV[Connection Pool Provider SPI]
    end
    
    subgraph "Infrastructure Layer"
    CONFIG[Configuration]
    METRICS[OpenTelemetry/Prometheus]
    LOG[Logging]
    end
    
    end
    
    GRPC_API --> STMT
    STMT --> CONN
    STMT --> SESS
    STMT --> CIRC
    STMT --> SLOW
    CONN --> POOL
    POOL --> PROV
    STMT --> METRICS
    STMT --> LOG
    POOL --> CONFIG
    
    style GRPC_API fill:#90caf9
    style STMT fill:#81c784
    style POOL fill:#ff8a65
```

The server offers flexible deployment options to fit various infrastructure needs. You can run it as a Docker container (drivers must be downloaded and mounted into the `ojp-libs` directory), as a runnable JAR for standalone execution with external driver support, or deploy it to Kubernetes using Helm charts for cloud-native environments.

**Configuration**: Server behavior is controlled through environment variables or JVM system properties:

```properties
# Core Server Settings
ojp.server.port=1059
ojp.prometheus.port=9159
ojp.server.virtualThreads.enabled=true
ojp.server.threadPoolSize=200
ojp.server.maxRequestSize=4194304

# Security
ojp.server.allowedIps=0.0.0.0/0

# Connection Management
ojp.server.connectionIdleTimeout=30000
ojp.server.circuitBreakerTimeout=60000
```

`ojp.server.threadPoolSize` is used only when `ojp.server.virtualThreads.enabled=false`.

### ojp-jdbc-driver: The JDBC Implementation

The **ojp-jdbc-driver** is a complete JDBC 4.2 specification implementation that applications use as a drop-in replacement for traditional JDBC drivers.

The driver implements the JDBC API interfaces to ensure compliance with the standard. Rather than maintaining actual database connections, it provides lightweight virtual connection objects that delegate to the server. Under the hood, it acts as a gRPC client, communicating with ojp-server to execute all database operations. The driver handles result set streaming efficiently to minimize memory overhead, and manages transaction state across the network boundary. For high availability scenarios, it supports connecting to multiple OJP servers simultaneously, automatically failing over when needed.

**JDBC Implementation Mapping**:

```mermaid
classDiagram
    class Connection {
        <<interface>>
        +createStatement()
        +prepareStatement(sql)
        +commit()
        +rollback()
    }
    
    class OjpConnection {
        -grpcStub
        -sessionId
        -serverEndpoint
        +createStatement()
        +prepareStatement(sql)
        +commit()
        +rollback()
    }
    
    class Statement {
        <<interface>>
        +executeQuery(sql)
        +executeUpdate(sql)
        +execute(sql)
    }
    
    class OjpStatement {
        -connection
        -grpcStub
        +executeQuery(sql)
        +executeUpdate(sql)
        +execute(sql)
    }
    
    class ResultSet {
        <<interface>>
        +next()
        +getString(column)
        +getInt(column)
    }
    
    class OjpResultSet {
        -resultData
        -currentRow
        +next()
        +getString(column)
        +getInt(column)
    }
    
    Connection <|.. OjpConnection
    Statement <|.. OjpStatement
    ResultSet <|.. OjpResultSet
    
    OjpConnection --> OjpStatement : creates
    OjpStatement --> OjpResultSet : returns
```

**Maven Dependency**:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

**Usage Example**:

```java
// Load the OJP JDBC driver
Class.forName("org.openjproxy.jdbc.Driver");

// Connect with OJP URL format
String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");

// Use standard JDBC operations
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM users");

while (rs.next()) {
    System.out.println(rs.getString("username"));
}

rs.close();
stmt.close();
conn.close();
```

**Important Consideration**: When using OJP, you must **disable** any existing application-level connection pooling (HikariCP, C3P0, DBCP2) as it creates double-pooling and defeats OJP's purpose.

### ojp-grpc-commons: Shared Contracts

```mermaid
graph LR
    subgraph "ojp-jdbc-driver"
    DRIVER_CODE[Driver Code<br/>Connection, Statement]
    DRIVER_GEN[Generated Java Classes<br/>StatementRequest<br/>OpResult<br/>StatementServiceGrpc]
    end
    subgraph "ojp-grpc-commons"
    PROTO[Protocol Buffer Contracts<br/>StatementService.proto]
    SAMPLE_REQ[StatementRequest<br/>session<br/>sql<br/>parameters<br/>statementUUID]
    SAMPLE_RES[OpResult Stream<br/>query_result<br/>labels<br/>rows]
    end
    subgraph "ojp-server"
    SERVER_GEN[Generated Java Classes<br/>StatementRequest<br/>OpResult<br/>StatementServiceGrpc]
    SERVER_CODE[Server Code<br/>StatementServiceImpl]
    end
    PROTO -->|protoc generates| DRIVER_GEN
    PROTO -->|protoc generates| SERVER_GEN
    SAMPLE_REQ -.->|defined in| PROTO
    SAMPLE_RES -.->|defined in| PROTO
    DRIVER_CODE --> DRIVER_GEN
    SERVER_GEN --> SERVER_CODE
    DRIVER_GEN -->|shared language| SERVER_GEN
    DRIVER_CODE -->|gRPC messages| SERVER_CODE
    style PROTO fill:#90caf9
    style SAMPLE_REQ fill:#bbdefb
    style SAMPLE_RES fill:#bbdefb
    style DRIVER_GEN fill:#81c784
    style DRIVER_CODE fill:#c8e6c9
    style SERVER_GEN fill:#ffd54f
    style SERVER_CODE fill:#fff59d
```

The **ojp-grpc-commons** module contains the gRPC service definitions and Protocol Buffer message schemas shared between the driver and server.

This module serves as the contract between client and server. It defines the gRPC service interfaces in `.proto` files, specifying exactly how client and server communicate. These files describe all request and response message structures with precise field types and semantics. From these proto files, Java classes are auto-generated at build time, ensuring both sides work with identical data structures. This approach guarantees version compatibility—when driver and server are compiled against the same proto definitions, protocol mismatches become impossible.

**Protocol Buffer Example**:

```protobuf
// Simplified example of protocol definition
service StatementService {
  rpc ExecuteQuery(StatementRequest) returns (stream ResultSetResponse);
  rpc ExecuteUpdate(StatementRequest) returns (UpdateResponse);
  rpc CreateSession(SessionRequest) returns (SessionResponse);
  rpc Commit(TransactionRequest) returns (TransactionResponse);
}

message StatementRequest {
  string session_id = 1;
  string sql = 2;
  repeated Parameter parameters = 3;
  int32 fetch_size = 4;
}

message ResultSetResponse {
  repeated Row rows = 1;
  ColumnMetadata metadata = 2;
  bool has_more = 3;
}
```

**Why This Matters**: The shared contract ensures type safety and version compatibility. Both driver and server are compiled against the same proto definitions, preventing protocol mismatches.

---

## 2.2 Communication Protocol

OJP uses **gRPC** (Google Remote Procedure Call) as its communication protocol, providing high-performance, low-latency communication between the driver and server.

### Why gRPC?

```mermaid
block-beta
    columns 3
    title["REST/JSON vs gRPC/Protocol Buffers"]:3
    metric["Metric"]
    rest["Traditional REST/JSON"]
    grpc["gRPC/Protocol Buffers"]
    latency["Latency"]
    restLatency["Higher<br/>HTTP/1.1 request overhead"]
    grpcLatency["Lower<br/>HTTP/2 + compact payloads"]
    throughput["Throughput"]
    restThroughput["Good<br/>One request/response at a time"]
    grpcThroughput["Excellent<br/>Multiplexed concurrent streams"]
    payload["Payload Size"]
    restPayload["Larger<br/>Text JSON"]
    grpcPayload["60-80% smaller<br/>Binary Protobuf"]
    connection["Connection Efficiency"]
    restConnection["Lower<br/>More connection overhead"]
    grpcConnection["Higher<br/>Single HTTP/2 channel, many streams"]
    features["Protocol Features"]
    restFeatures["Runtime validation<br/>Limited streaming"]
    grpcFeatures["HTTP/2<br/>Binary encoding<br/>Streaming<br/>Type safety"]
```

From the Architectural Decision Record (ADR-002):

> "gRPC's HTTP/2 transport enables multiplexed streams and low-latency communication, aligning perfectly with the project's scalability goals."

gRPC brings several compelling advantages to OJP's architecture. Its HTTP/2 multiplexing allows multiple requests to travel over a single TCP connection, dramatically reducing connection overhead. The binary protocol using Protocol Buffers results in much smaller payload sizes compared to JSON—typically 60-80% smaller. This isn't just about bandwidth; smaller payloads mean faster serialization and deserialization. gRPC provides native support for bi-directional streaming, perfect for handling large result sets efficiently. Protocol Buffers also bring compile-time type safety, catching errors during development rather than at runtime. The protocol is language-agnostic, making it straightforward to implement clients in languages beyond Java. Overall, gRPC delivers significantly better performance than traditional REST/JSON approaches, which is critical when database operations are involved.

When comparing REST/JSON to gRPC/Protobuf, the differences are striking. REST typically uses HTTP/1.1 with text-based JSON encoding, leading to larger payloads and limited streaming capabilities. Type checking happens at runtime, and performance is merely good. gRPC, on the other hand, uses HTTP/2 with binary encoding, achieving 60-80% payload size reduction, native streaming support, compile-time type safety, and excellent performance.

### Request-Response Flow

Let's trace a complete SQL query execution:

```mermaid
sequenceDiagram
    autonumber
    participant App as Java Application
    participant Driver as OJP JDBC Driver
    participant gRPC as gRPC/HTTP2
    participant Server as OJP Server
    participant Pool as HikariCP Pool
    participant DB as Database

    rect rgb(227, 242, 253)
    Note over App,Server: Steps 1-3: Request creation, protobuf serialization, and HTTP/2 transmission (~1-2ms)
    App->>Driver: 1. executeQuery("SELECT * FROM users")
    Driver->>Driver: 2. Serialize StatementRequest to Protobuf
    Driver->>gRPC: 3a. Send StatementRequest
    gRPC->>Server: 3b. HTTP/2 stream delivers request
    end

    rect rgb(255, 243, 224)
    Note over Server,DB: Steps 4-7: Server processing and database work (duration depends on query)
    Server->>Server: 4. Deserialize Protobuf
    Server->>Pool: 5. Acquire pooled connection
    Pool->>DB: 6. Execute SQL
    DB-->>Pool: 7a. Return ResultSet rows
    Pool-->>Server: 7b. Return rows for serialization
    end

    rect rgb(232, 245, 233)
    Note over Server,App: Steps 8-10: Result streaming and driver deserialization (~2-5ms for 1000 rows)
    Server->>gRPC: 8. Stream serialized ResultSetResponse chunks
    gRPC->>Driver: 8b. HTTP/2 response stream
    Driver->>Driver: 9. Deserialize Protobuf chunks
    Driver-->>App: 10. Return JDBC ResultSet
    end

    Note over Pool,DB: Database connection held during steps 6-7<br/>from SQL execution until rows are handed back
    Note over Driver,Server: Total network overhead: ~5-10ms<br/>request + response path, typically less than database work
    Note over Server,App: Streaming keeps large result sets incremental
```

**Key Observations**:

- **Step 1-3**: Request serialization and transmission (~1-2ms)
- **Step 4-7**: Database operation execution (depends on query)
- **Step 8-10**: Result streaming and deserialization (~2-5ms for 1000 rows)
- **Connection Held**: Only during steps 6-7 (actual SQL execution)
- **Total Network Overhead**: ~5-10ms (much less than database operation time)

### Session Management

OJP sessions provide the bridge between stateless gRPC calls and stateful JDBC behavior. Every driver request carries a `sessionId`, and the server uses that identifier to find the right transaction state, isolation level, connection, and cached statement resources.

```mermaid
graph LR
    subgraph "Application Threads and Requests"
    REQ1[Thread 1<br/>sessionId: A<br/>executeQuery]
    REQ2[Thread 2<br/>sessionId: B<br/>commit]
    REQ3[Thread 3<br/>sessionId: A<br/>fetchNextRows]
    end

    subgraph "Session Manager"
    SESS_A[Session A<br/>transaction: active<br/>isolation: READ_COMMITTED]
    SESS_B[Session B<br/>transaction: auto-commit<br/>isolation: SERIALIZABLE]
    end

    subgraph "Server-Side Resources"
    CONN_A[(Connection A<br/>sticky while transaction active)]
    CONN_B[(Connection B<br/>borrowed per operation)]
    PS_A[Prepared Statement Cache<br/>session A statements]
    PS_B[Prepared Statement Cache<br/>session B statements]
    end

    REQ1 -->|sessionId A| SESS_A
    REQ3 -->|sessionId A| SESS_A
    REQ2 -->|sessionId B| SESS_B
    SESS_A --> CONN_A
    SESS_A --> PS_A
    SESS_B --> CONN_B
    SESS_B --> PS_B

    style REQ1 fill:#90caf9
    style REQ2 fill:#90caf9
    style REQ3 fill:#90caf9
    style SESS_A fill:#81c784
    style SESS_B fill:#81c784
    style CONN_A fill:#ffcc80
    style CONN_B fill:#ffcc80
    style PS_A fill:#ffe0b2
    style PS_B fill:#ffe0b2
```

This resource mapping diagram shows how multiple request streams can safely share the same OJP server without losing JDBC semantics. Requests with the same `sessionId` converge on the same session record, while different sessions remain isolated from each other.

The next diagram focuses on the lifecycle of a single session as it moves between creation, normal query execution, transaction handling, and cleanup:

```mermaid
stateDiagram-v2
    [*] --> Created: CreateSession
    Created --> Active: First Query
    Active --> InTransaction: beginTransaction()
    InTransaction --> Active: commit()
    InTransaction --> Active: rollback()
    Active --> [*]: CloseSession
    InTransaction --> [*]: CloseSession (rollback)
    
    note right of Created
        SessionID assigned
        No DB resources yet
    end note
    
    note right of Active
        Executing queries
        Auto-commit mode
    end note
    
    note right of InTransaction
        Transaction active
        Connection sticky
    end note
```

Each OJP session has several important characteristics that make the system work smoothly. Every connection gets a unique UUID as its session identifier, ensuring that requests are properly tracked across the distributed system. The session tracks transaction state meticulously, knowing whether a transaction is in progress and what operations have been committed or rolled back. Prepared statements are cached at the server side for reuse, avoiding repeated parsing overhead. The system maintains JDBC isolation level settings across the network boundary, preserving transactional semantics. In multi-node deployments, sessions exhibit connection affinity—once a session starts on a particular server, it sticks to that server for consistency.

### Connection Multiplexing

One of gRPC's killer features is connection multiplexing:

```mermaid
graph LR
    subgraph "Application Threads"
    T1[Thread 1<br/>Query 1]
    T2[Thread 2<br/>Query 2]
    T3[Thread 3<br/>Query 3]
    TM[...]
    T10[Thread 10<br/>Query 10]
    end

    subgraph "gRPC Channel"
    CH[1 TCP Connection<br/>gRPC over HTTP/2<br/>10 concurrent streams]
    end

    subgraph "OJP Server"
    S1[Handler 1]
    S2[Handler 2]
    S3[Handler 3]
    SM[...]
    S10[Handler 10]
    end

    subgraph "Database Operations"
    DB1[DB Operation 1]
    DB2[DB Operation 2]
    DB3[DB Operation 3]
    DBM[...]
    DB10[DB Operation 10]
    end

    SUMMARY[10 concurrent queries<br/>→ 1 TCP connection<br/>→ 10 database operations]

    SUMMARY --> CH
    T1 -.->|Stream 1| CH
    T2 -.->|Stream 2| CH
    T3 -.->|Stream 3| CH
    TM -.->|Streams 4-9| CH
    T10 -.->|Stream 10| CH

    CH -->|Stream 1| S1
    CH -->|Stream 2| S2
    CH -->|Stream 3| S3
    CH -->|Streams 4-9| SM
    CH -->|Stream 10| S10

    S1 --> DB1
    S2 --> DB2
    S3 --> DB3
    SM --> DBM
    S10 --> DB10

    style SUMMARY fill:#90caf9
    style CH fill:#4caf50
    style DB1 fill:#ffcc80
    style DB2 fill:#ffcc80
    style DB3 fill:#ffcc80
    style DBM fill:#ffcc80
    style DB10 fill:#ffcc80
```

This multiplexing delivers several concrete benefits. The reduced overhead from maintaining one TCP connection instead of many translates to better performance and resource utilization. Server resources are conserved—fewer sockets and file descriptors mean more capacity for actual work. Most importantly, the system can handle concurrent operations gracefully, with multiple queries in flight simultaneously without the connection management overhead of traditional approaches.

---

## 2.3 Connection Pool Management

At the core of OJP's efficiency is its use of **HikariCP**, the fastest JDBC connection pool available.

### Why HikariCP?

```mermaid
block-beta
    columns 4
    title["Connection Pool Performance Comparison"]:4
    pool["Pool"]
    throughput["Throughput<br/>(ops/sec)"]
    latency["Average Latency<br/>(ms, lower is better)"]
    memory["Memory Usage<br/>(relative footprint)"]
    hikari["HikariCP"]
    hikariThroughput["45,000<br/>Very high"]
    hikariLatency["0.9<br/>Lowest"]
    hikariMemory["Lowest<br/>Minimal footprint"]
    tomcat["Tomcat Pool"]
    tomcatThroughput["23,000<br/>Medium"]
    tomcatLatency["1.8<br/>Medium"]
    tomcatMemory["Moderate<br/>Average footprint"]
    dbcp["DBCP2"]
    dbcpThroughput["20,000<br/>Medium"]
    dbcpLatency["2.1<br/>Higher"]
    dbcpMemory["Moderate<br/>Average footprint"]
    c3p0["C3P0"]
    c3p0Throughput["18,000<br/>Lower"]
    c3p0Latency["2.3<br/>Highest"]
    c3p0Memory["Higher<br/>Larger footprint"]
    quote["HikariCP is the fastest JDBC connection pool"]:4
```

From the Architectural Decision Record (ADR-003):

> "HikariCP is the fastest JDBC connection pool, with extensive configuration options and a proven track record in high-performance systems."

HikariCP brings multiple advantages that make it the ideal choice for OJP. Benchmark results consistently show it's 2-10x faster than alternatives like C3P0, DBCP2, or Tomcat's pool. This speed isn't theoretical—HikariCP has been extensively tested in production environments across thousands of deployments. It maintains minimal memory and CPU footprint, leaving more resources for your actual application logic. The pool works great out-of-the-box with smart defaults, though it offers extensive tuning options when needed. Finally, it's actively developed and well-maintained, with responsive support for issues and continuous improvements.

Performance benchmarks tell the story clearly. HikariCP achieves approximately 45,000 operations per second with 0.9ms average latency. Compare this to Tomcat's pool at 23,000 ops/sec with 1.8ms latency, C3P0 at 18,000 ops/sec with 2.3ms latency, or DBCP2 at 20,000 ops/sec with 2.1ms latency. HikariCP essentially doubles the throughput while halving the latency.

### Pool Sizing and Configuration

```mermaid
graph LR
    INPUTS[Modern SSD / cloud inputs<br/>8 CPU cores<br/>50 concurrent handlers<br/>5ms query / 25ms think time]
    SIMPLE[Simple rule<br/>cores + 2<br/>8 + 2 = 10]
    WORKLOAD["Workload formula<br/>T_n x (C_m / T_m) + buffer<br/>50 x (5 / 25) + 3 = 13"]
    RESULT[Start with 10-13 connections]
    CHECK[Then watch metrics<br/>connection wait time<br/>database CPU headroom]
    LEGACY["Legacy spinning disk only<br/>(cores x 2) + effective_spindle_count"]

    INPUTS --> SIMPLE
    INPUTS --> WORKLOAD
    SIMPLE --> RESULT
    WORKLOAD --> RESULT
    RESULT --> CHECK
    LEGACY -.->|separate legacy formula| RESULT

    style INPUTS fill:#bbdefb
    style SIMPLE fill:#c8e6c9
    style WORKLOAD fill:#c8e6c9
    style RESULT fill:#81c784
    style CHECK fill:#e3f2fd
    style LEGACY fill:#ffcc80
```

#### Modern Pool Sizing Formula

For modern cloud and SSD-based deployments, the traditional spindle-count formula is outdated. Research and production data show that smaller pool sizes often perform better than traditional formulas suggest.

**Modern Formula** (recommended for SSD/cloud databases):
```
pool_size = T_n × (C_m / T_m) + buffer
```

Where:
- `T_n` = Number of threads (concurrent request handlers in your application)
- `C_m` = Average database query time (milliseconds)
- `T_m` = Average think time between queries (milliseconds)
- `buffer` = Small safety margin (typically 2-5 connections)

**Simplified Rule of Thumb**: For most modern OLTP workloads with sub-10ms queries:
```
pool_size ≈ number_of_cpu_cores + 2
```

**Example 1** (High-frequency OLTP): 
- 50 application threads
- 5ms average query time
- 25ms think time between queries
- Pool size = 50 × (5/25) + 3 = 13 connections

**Example 2** (Simple rule): 
- 8-core database server
- Pool size = 8 + 2 = 10 connections

**Why This Works Better**:
- **SSDs eliminate I/O wait**: Modern storage doesn't block on disk seeks
- **Context switching overhead**: Too many connections cause CPU thrashing
- **Research-backed**: Studies show pools larger than (cores × 2) rarely help and often hurt performance
- **Real-world validation**: PostgreSQL, MySQL, and Oracle all recommend similar conservative sizing

**Key Insight**: With modern hardware, the bottleneck is rarely I/O—it's CPU scheduling. A connection doing actual work needs a CPU core. Having 100 connections on an 8-core database means 92 connections are just waiting, consuming memory and causing context switch overhead.

**Traditional Formula** (for spinning disk legacy systems):
```
connections = ((core_count * 2) + effective_spindle_count)
```
This formula made sense when databases were I/O-bound waiting for spinning disks. With mechanical drives, having extra connections meant some could compute while others waited for disk I/O. Modern SSDs with microsecond latencies have eliminated this trade-off.

OJP Server manages separate HikariCP pools for each database:

```mermaid
graph TB
    subgraph "OJP Server"
    PM[Pool Manager]
    
    subgraph "PostgreSQL Pool"
    PG_POOL[Max: 20<br/>Min Idle: 5<br/>Timeout: 30s]
    PG1[Conn 1]
    PG2[Conn 2]
    PG3[...]
    end
    
    subgraph "MySQL Pool"
    MY_POOL[Max: 15<br/>Min Idle: 3<br/>Timeout: 30s]
    MY1[Conn 1]
    MY2[Conn 2]
    end
    
    subgraph "Oracle Pool"
    OR_POOL[Max: 10<br/>Min Idle: 2<br/>Timeout: 30s]
    OR1[Conn 1]
    OR2[Conn 2]
    end
    
    end
    
    PM --> PG_POOL
    PM --> MY_POOL
    PM --> OR_POOL
    
    PG_POOL --> PG1
    PG_POOL --> PG2
    PG_POOL --> PG3
    
    MY_POOL --> MY1
    MY_POOL --> MY2
    
    OR_POOL --> OR1
    OR_POOL --> OR2
    
    style PM fill:#ffd54f
    style PG_POOL fill:#336791
    style MY_POOL fill:#4479a1
    style OR_POOL fill:#f80000
```

**Key Configuration Parameters**:

```properties
# Maximum pool size (most important setting)
maximumPoolSize=20

# Minimum idle connections (kept ready)
minimumIdle=5

# Connection timeout (wait for available connection)
connectionTimeout=30000

# Idle timeout (close unused connections)
idleTimeout=600000

# Max lifetime (force connection refresh)
maxLifetime=1800000

# Validation query (test connections)
connectionTestQuery=SELECT 1
```

**Pool Sizing Guidelines**:

Use the modern formula above for SSD-backed and cloud databases. For an 8-core database server, a good starting point is often around 10 connections using the `cores + 2` rule, or 13 connections in the high-frequency OLTP example. Increase only when metrics show connection wait time while database CPU still has headroom.

For legacy spinning-disk systems, the traditional formula still applies:
```
connections = ((core_count * 2) + effective_spindle_count)
```

### Connection Abstraction Layer

OJP includes a **Connection Pool Provider SPI** (Service Provider Interface) that abstracts the underlying connection pool implementation:

```mermaid
classDiagram
    class ConnectionPoolProvider {
        <<interface>>
        +createDataSource(config) DataSource
        +closeDataSource(ds)
        +getStatistics(ds) Map
        +getId() String
        +getPriority() int
    }
    
    class HikariConnectionPoolProvider {
        +createDataSource(config)
        +closeDataSource(ds)
        +getStatistics(ds)
        +getId() "hikari"
        +getPriority() 100
    }
    
    class DbcpConnectionPoolProvider {
        +createDataSource(config)
        +closeDataSource(ds)
        +getStatistics(ds)
        +getId() "dbcp"
        +getPriority() 10
    }
    
    class CustomPoolProvider {
        +createDataSource(config)
        +closeDataSource(ds)
        +getStatistics(ds)
        +getId() "custom"
        +getPriority() 50
    }
    
    ConnectionPoolProvider <|.. HikariConnectionPoolProvider
    ConnectionPoolProvider <|.. DbcpConnectionPoolProvider
    ConnectionPoolProvider <|.. CustomPoolProvider
    
    class ConnectionPoolProviderRegistry {
        +createDataSource(config) DataSource
        +createDataSource(providerId, config) DataSource
        +getStatistics(providerId, ds) Map
    }
    
    ConnectionPoolProviderRegistry --> ConnectionPoolProvider : discovers via ServiceLoader
```

The abstraction layer brings several important benefits. It provides flexibility—you can switch connection pool implementations without changing any code. The system is extensible through the SPI, allowing organizations to add custom pool providers. This abstraction makes testing easier, as you can mock the pool provider for unit tests. Most importantly, it future-proofs the architecture, making it simple to adapt to new pool technologies as they emerge.

OJP ships with multiple providers out of the box. HikariCP is the default provider with ID "hikari" and priority 100, recommended for its high performance. Apache DBCP2 is available as an alternative with ID "dbcp" and priority 10, offering a feature-rich implementation. Organizations can also create custom providers with user-defined IDs and priorities for specialized requirements. The provider with the highest priority is used by default, though you can explicitly specify which provider to use when creating a data source.

The provider with the highest priority is used by default, but you can explicitly specify a provider:

```java
// Default (highest priority - HikariCP)
DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);

// Explicit provider selection
DataSource ds = ConnectionPoolProviderRegistry.createDataSource("dbcp", config);
```

### Pool Provider SPI

The SPI allows organizations to plug in their own connection pool implementations:

**Example Custom Provider**:

```java
public class MyCustomPoolProvider implements ConnectionPoolProvider {
    
    @Override
    public String getId() {
        return "mypool";
    }
    
    @Override
    public int getPriority() {
        return 50; // Between hikari (100) and dbcp (10)
    }
    
    @Override
    public DataSource createDataSource(PoolConfig config) {
        // Create and configure your custom pool
        MyCustomPool pool = new MyCustomPool();
        pool.setUrl(config.getUrl());
        pool.setMaxConnections(config.getMaxPoolSize());
        return pool;
    }
    
    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.example.MyCustomPool");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

Register via ServiceLoader:
```
META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider
com.example.MyCustomPoolProvider
```

This architecture makes OJP adaptable to specialized requirements while maintaining HikariCP as the proven, high-performance default.

**XA Transaction Support**: Beyond the standard connection pool, OJP also provides an **XA Pool SPI** for distributed transactions. This pluggable architecture allows custom XA-aware pooling providers while defaulting to Apache Commons Pool 2. The XA pooling strategy and its unique dual-condition lifecycle are covered in detail in **Chapter 10: XA Distributed Transactions**.

---

## 2.4 Architecture Diagrams

### Complete System Architecture

Here's the complete OJP architecture from end to end:

```mermaid
graph TB
    subgraph "Application Tier"
    APP1[Spring Boot App 1]
    APP2[Quarkus App 2]
    APP3[Micronaut App 3]
    end
    
    subgraph "OJP JDBC Driver"
    DRIVER1[OjpConnection]
    DRIVER2[OjpStatement]
    DRIVER3[OjpResultSet]
    GRPC_CLIENT[gRPC Client]
    end
    
    subgraph "Network Layer"
    GRPC_PROTO[gRPC/HTTP2<br/>Port 1059]
    end
    
    subgraph "OJP Server"
    GRPC_SERVER[gRPC Server]
    
    subgraph "Service Layer"
    STMT_SVC[Statement Service]
    SESS_MGR[Session Manager]
    CIRC_BRK[Circuit Breaker]
    SLOW_SEG[Slow Query Segregation]
    end
    
    subgraph "Pool Management"
    POOL_MGR[Pool Manager]
    HIKARI1[HikariCP Pool 1]
    HIKARI2[HikariCP Pool 2]
    HIKARI3[HikariCP Pool 3]
    end
    
    subgraph "Observability"
    OTEL[OpenTelemetry]
    PROM[Prometheus<br/>Port 9159]
    end
    
    end
    
    subgraph "Database Tier"
    PG[(PostgreSQL)]
    MY[(MySQL)]
    OR[(Oracle)]
    end
    
    APP1 --> DRIVER1
    APP2 --> DRIVER1
    APP3 --> DRIVER1
    
    DRIVER1 --> DRIVER2
    DRIVER2 --> DRIVER3
    DRIVER1 --> GRPC_CLIENT
    
    GRPC_CLIENT --> GRPC_PROTO
    GRPC_PROTO --> GRPC_SERVER
    
    GRPC_SERVER --> STMT_SVC
    STMT_SVC --> SESS_MGR
    STMT_SVC --> CIRC_BRK
    STMT_SVC --> SLOW_SEG
    STMT_SVC --> POOL_MGR
    
    POOL_MGR --> HIKARI1
    POOL_MGR --> HIKARI2
    POOL_MGR --> HIKARI3
    
    HIKARI1 --> PG
    HIKARI2 --> MY
    HIKARI3 --> OR
    
    STMT_SVC --> OTEL
    OTEL --> PROM
    
    style APP1 fill:#e1f5fe
    style APP2 fill:#e1f5fe
    style APP3 fill:#e1f5fe
    style DRIVER1 fill:#81c784
    style GRPC_SERVER fill:#ffd54f
    style POOL_MGR fill:#ff8a65
    style PG fill:#336791
    style MY fill:#4479a1
    style OR fill:#f80000
```

### Component Interaction

Let's examine how components interact during a typical database operation:

```mermaid
sequenceDiagram
    autonumber
    participant App as Spring Boot App
    participant Driver as ojp-jdbc-driver
    participant Commons as ojp-grpc-commons
    participant Server as ojp-server
    participant HikariCP as HikariCP Pool
    participant DB as PostgreSQL

    Note over App: Application starts up
    App->>Driver: DriverManager.getConnection(url)
    Driver->>Driver: Parse OJP URL
    Driver->>Commons: Use protobuf definitions
    Driver->>Server: CreateSession RPC
    Server->>Server: Generate sessionUUID
    Server-->>Driver: SessionResponse
    Driver-->>App: Connection object (virtual)
    
    Note over App: Execute query
    App->>Driver: stmt.executeQuery(sql)
    Driver->>Commons: Serialize StatementRequest
    Driver->>Server: ExecuteQuery RPC
    Server->>Server: Validate session
    Server->>HikariCP: getConnection()
    HikariCP->>DB: Use real connection
    DB-->>HikariCP: ResultSet
    Note over HikariCP,Server: Connection remains associated with the OJP session after acquisition
    Server->>Commons: Serialize ResultSetResponse
    Server-->>Driver: Stream results
    Driver->>Driver: Deserialize to JDBC ResultSet
    Driver-->>App: ResultSet object
    
    Note over App: Process results
    App->>Driver: rs.next(), rs.getString()
    Driver-->>App: Data from cached results
    
    Note over App: Close resources
    App->>Driver: rs.close()
    Driver->>Server: CloseResultSet RPC
    Server->>Server: Close ResultSet and free cursor state
    App->>Driver: conn.close()
    Driver->>Server: CloseSession RPC
    Server->>HikariCP: Release connection to pool
    Server->>Server: Cleanup session
    Server-->>Driver: Acknowledged
```

### Data Flow Diagram

The flow of data through the OJP system:

```mermaid
graph LR
    subgraph "External Entities"
    A[Applications]
    D[Databases]
    M[Monitoring<br/>Systems]
    end
    
    subgraph "OJP Driver Process"
    P1[Parse JDBC<br/>Calls]
    P2[Serialize to<br/>Protobuf]
    P3[Deserialize<br/>Results]
    end
    
    subgraph "OJP Server Process"
    P4[Receive<br/>Requests]
    P5[Execute<br/>SQL]
    P8[Manage<br/>Connection Pools]
    P6[Stream<br/>Results]
    P7[Collect<br/>Metrics]
    end
    
    subgraph "Data Stores"
    DS1[(Session<br/>State)]
    DS2[(Prepared<br/>Statement<br/>Cache)]
    DS3[(Metrics<br/>Store)]
    DS4[(Configuration)]
    end
    
    A -->|SQL Queries| P1
    P1 --> P2
    P2 -->|gRPC| P4
    P4 --> DS1
    P4 --> DS2
    DS4 -->|Pool and server settings| P4
    DS4 -->|Pool sizing| P8
    P4 --> P5
    P5 -->|Connection request| P8
    P8 -->|JDBC connection| P5
    P8 -->|Pooled JDBC| D
    D -->|Result sets| P8
    P8 -->|Result sets| P5
    P5 --> P6
    P6 -->|gRPC| P3
    P3 -->|JDBC Results| A
    P5 --> P7
    P7 --> DS3
    DS3 -->|Metrics| M
    
    style A fill:#e1f5fe
    style D fill:#90caf9
    style M fill:#fff59d
    style DS1 fill:#c5e1a5
    style DS2 fill:#c5e1a5
    style DS3 fill:#c5e1a5
    style DS4 fill:#c5e1a5
```

---

## 2.5 Action Pattern Architecture (Server-Side)

### From Monolith to Actions

In January 2026, OJP underwent a significant architectural refactoring, transforming the `StatementServiceImpl` class from a 2,500+ line monolith into a collection of focused Action classes. This improved code maintainability, testability, and scalability.

```mermaid
graph LR
    subgraph before ["❌ Before — God Class"]
        SSI["StatementServiceImpl\n━━━━━━━━━━━━━━━━━━━━\n2,500+ lines · 21 methods\nconnect · executeUpdate\nexecuteQuery · createLob\nstartTransaction · commit\nrollback · xaStart · …"]
    end

    before -- "Action Pattern\nRefactoring\nJanuary 2026\n(PRs #261 – #284)" --> after

    subgraph after ["✅ After — Focused Actions"]
        direction TB
        CA["ConnectAction"]
        EUA["ExecuteUpdateAction"]
        EQA["ExecuteQueryAction"]
        STA["StartTransactionAction"]
        CTA["CommitTransactionAction"]
        RTA["RollbackTransactionAction"]
        XSA["XaStartAction"]
        CLA["CreateLobAction"]
        MORE["…20+ more Actions"]
    end

    style SSI fill:#ffcdd2,stroke:#c62828
    style CA fill:#c8e6c9,stroke:#2e7d32
    style EUA fill:#c8e6c9,stroke:#2e7d32
    style EQA fill:#c8e6c9,stroke:#2e7d32
    style STA fill:#c8e6c9,stroke:#2e7d32
    style CTA fill:#c8e6c9,stroke:#2e7d32
    style RTA fill:#c8e6c9,stroke:#2e7d32
    style XSA fill:#c8e6c9,stroke:#2e7d32
    style CLA fill:#c8e6c9,stroke:#2e7d32
    style MORE fill:#c8e6c9,stroke:#2e7d32
```

### The Action Interface Hierarchy

The refactoring introduced four thin Action interfaces, each covering a different method shape present in the gRPC service. Every Action implementation is a stateless singleton; all per-request state is passed via method parameters rather than stored as instance fields.

```mermaid
classDiagram
    class Action~TRequest,TResponse~ {
        <<interface>>
        +execute(ActionContext, TRequest, StreamObserver~TResponse~) void
    }
    note for Action~TRequest,TResponse~ "Standard unary / server-streaming RPCs\n(21 of 21 gRPC methods use this or StreamingAction)"

    class StreamingAction~TRequest,TResponse~ {
        <<interface>>
        +execute(ActionContext, StreamObserver~TResponse~) StreamObserver~TRequest~
    }
    note for StreamingAction~TRequest,TResponse~ "Bidirectional streaming\n(createLob)"

    class InitAction {
        <<interface>>
        +execute() void
    }
    note for InitAction "One-time server init\n(e.g., XA pool provider setup)"

    class ValueAction~TRequest,TResult~ {
        <<interface>>
        +execute(TRequest) TResult
    }
    note for ValueAction~TRequest,TResult~ "Internal helpers that return a value\ndirectly instead of via StreamObserver"

    ConnectAction ..|> Action : implements
    ExecuteUpdateAction ..|> Action : implements
    ExecuteQueryAction ..|> Action : implements
    CommitTransactionAction ..|> Action : implements
    XaStartAction ..|> Action : implements
    TerminateSessionAction ..|> Action : implements
    CreateLobAction ..|> StreamingAction : implements
    ProcessClusterHealthAction ..|> InitAction : implements
    ResultSetHelper ..|> ValueAction : implements
```

### ActionContext — Shared State Holder

The `ActionContext` is created once at server startup and injected into every Action call. It holds:

- `datasourceMap` / `xaDataSourceMap` — pooled and XA datasources, keyed by connection hash
- `sessionManager` — session lifecycle and connection retrieval
- `circuitBreakerRegistry` — per-datasource circuit breakers
- `slowQuerySegregationManagers` — per-datasource slow-query slot managers
- `serverConfiguration` — runtime configuration flags
- `xaPoolProvider` / `xaRegistries` — XA transaction infrastructure

All internal maps are `ConcurrentHashMap`, making the context itself thread-safe.

### Core Action Categories

Actions are organised into sub-packages by concern:

| Package | Key Actions |
|---|---|
| `action.connection` | `ConnectAction`, `HandleXAConnectionWithPoolingAction`, `CreateSlowQuerySegregationManagerAction` |
| `action.transaction` | `ExecuteUpdateAction`, `ExecuteQueryAction`, `FetchNextRowsAction`, `StartTransactionAction`, `CommitTransactionAction`, `RollbackTransactionAction`, and XA lifecycle helpers: `XaForgetAction`, `XaIsSameRMAction`, `XaGetTransactionTimeoutAction`, `XaSetTransactionTimeoutAction` |
| `action.xa` | Core XA protocol operations: `XaStartAction`, `XaEndAction`, `XaPrepareAction`, `XaCommitAction`, `XaRollbackAction`, `XaRecoverAction` |
| `action.streaming` | `CreateLobAction` (`StreamingAction`), `ReadLobAction` (`Action`) |
| `action.session` | `TerminateSessionAction` |
| `action.resource` | `CallResourceAction` |
| `action.util` | `ProcessClusterHealthAction` |

### Benefits Realised

**Code Quality**: From one 2,500-line class to 30+ focused classes averaging 75–150 lines each, with improved cohesion and organisation.

**Testability**: Each action can be unit-tested independently with a minimal `ActionContext` mock containing only the fields that action reads.

**Performance**: Singleton pattern eliminates per-request object allocation, reducing GC pressure on the hot path.

**Developer Experience**: Easier code navigation, no merge conflicts on `StatementServiceImpl`, clear extension points for new operations.

### Implementation Example

```java
// StatementServiceImpl — thin orchestrator
@Override
public void connect(ConnectionDetails connectionDetails,
                    StreamObserver<SessionInfo> responseObserver) {
    ConnectAction.getInstance().execute(actionContext, connectionDetails, responseObserver);
}

// ConnectAction — focused singleton (~150 lines)
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private static final ConnectAction INSTANCE = new ConnectAction();

    private ConnectAction() {}

    public static ConnectAction getInstance() { return INSTANCE; }

    @Override
    public void execute(ActionContext context, ConnectionDetails request,
                        StreamObserver<SessionInfo> responseObserver) {
        // All state comes from context — the action itself is stateless.
        Map<String, DataSource> datasourceMap = context.getDatasourceMap();
        SessionManager sessionManager = context.getSessionManager();
        // … connection logic …
    }
}
```

The refactoring maintains full backward compatibility while making the internal implementation modular and maintainable. The phased rollout throughout January 2026 (PRs #261–#284) ensured continuous integration without disrupting development.

For the full contributor guide, singleton rationale, and step-by-step instructions for adding a new Action, see [ADR-009](../ADRs/adr-009-action-pattern-for-statement-service.md) and the [Action Pattern Migration Guide](../designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md).

---

## Summary

OJP's architecture is built on three core components working in harmony:

1. **ojp-jdbc-driver**: A complete JDBC implementation that provides virtual connections and communicates via gRPC
2. **ojp-server**: A gRPC server managing HikariCP connection pools and executing SQL operations
3. **ojp-grpc-commons**: Shared Protocol Buffer contracts ensuring type-safe communication

The use of **gRPC** enables high-performance, low-latency communication with HTTP/2 multiplexing, while **HikariCP** provides industry-leading connection pool performance. The modular architecture with the **Connection Pool Provider SPI** ensures flexibility and extensibility.

This architecture enables OJP to deliver on its promise: **elastic scalability without proportional database connection growth**, all while maintaining JDBC compliance and requiring minimal application changes.

In the next chapter, we'll explore how OJP's architecture enables smart load balancing and automatic failover capabilities that surpass traditional database proxies.

---

**Previous Chapter**: [← Chapter 1: Introduction to OJP](part1-chapter1-introduction.md)  
**Next Chapter**: [Chapter 2a: OJP as Smart Load Balancer and Automatic Failover →](part1-chapter2a-smart-load-balancing.md)
