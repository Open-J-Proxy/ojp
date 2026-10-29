# Chapter 6: OJP Server Configuration

Configuring the OJP server is straightforward yet powerful. The server gives you control over every aspect of its operation, from network settings to advanced features like slow query segregation and circuit breakers. In this chapter, we'll explore how to configure your OJP server for different deployment scenarios, whether you're running in development mode on your laptop or deploying to production Kubernetes clusters.

## 6.1 Understanding Configuration Hierarchy

Before diving into specific settings, it's important to understand how OJP handles configuration. The server supports two primary configuration methods that work together in a well-defined hierarchy. JVM system properties always take precedence over environment variables, giving you the flexibility to override environment-level settings when needed. This design allows you to set baseline configurations through environment variables while making targeted overrides for specific scenarios using system properties.

This hierarchy becomes particularly valuable in containerized environments. You might set production defaults through environment variables in your Docker or Kubernetes configuration, then override specific settings for troubleshooting or testing without modifying your deployment files. The server reads both configuration sources at startup and merges them intelligently, ensuring you always get the behavior you expect.

**[IMAGE PROMPT: Create a layered diagram showing configuration hierarchy with three levels: "JVM System Properties" at the top (highest priority, shown in bold color), "Environment Variables" in the middle (medium priority), and "Default Values" at the bottom (lowest priority, shown in faded color). Use arrows flowing upward labeled "Overrides" to show precedence. Include example values at each level like `-Dojp.server.port=9059`, `OJP_SERVER_PORT=1059`, and `default: 1059`. Style: Clean, hierarchical infographic with color-coded priority levels.]**

```mermaid
graph TD
    A[Configuration Request] --> B{JVM Property Set?}
    B -->|Yes| C[Use JVM Property]
    B -->|No| D{Environment Variable Set?}
    D -->|Yes| E[Use Environment Variable]
    D -->|No| F[Use Default Value]
    C --> G[Configuration Applied]
    E --> G
    F --> G
```

## 6.2 Core Server Settings

Let's start with the foundational settings that control how your OJP server operates. The most fundamental setting is the gRPC server port, which determines where clients connect to access your database connections. By default, OJP listens on port 1059, though you'll often want to change this for production deployments or when running multiple instances on the same host.

The server also exposes a separate Prometheus metrics endpoint on port 9159 by default. This separation is intentional—it allows you to apply different network policies and access controls to your operational metrics versus your database traffic. In production, you might expose the gRPC port only to your application network while making the Prometheus endpoint available to your monitoring infrastructure on a separate network segment.

By default, OJP uses a fixed platform thread pool for gRPC request handling, with `ojp.server.threadPoolSize` (default 200) controlling concurrency. If you want to use Java virtual threads instead — which can give high concurrency without tuning a large platform thread pool — set `ojp.server.virtualThreads.enabled=true`.

> **Why virtual threads are off by default:** During heavy-concurrency testing with the current OJP code, virtual threads proved less efficient than platform threads, so they are disabled by default. This may change as the OJP code evolves — further investigation is needed to determine whether future upgrades could make virtual threads beneficial.

**[IMAGE PROMPT: Create a technical server architecture diagram showing OJP Server as a central component with two network interfaces: one labeled "gRPC Port :1059" (shown with database connection icons) and another labeled "Prometheus Port :9159" (shown with metrics/monitoring icons). Include a thread pool visualization showing multiple worker threads (default: 200) handling concurrent requests. Use professional blue and gray color scheme with clear labels and connection lines. Style: Modern technical architecture diagram.]**

The maximum request size setting provides protection against oversized requests that could impact server stability. The default of 4MB is generous for typical JDBC operations, but you might increase it if you're working with very large result sets or binary data. Just remember that larger request sizes consume more memory, so balance this against your available resources.

Connection idle timeout controls how long the server waits before closing inactive **gRPC connections** from clients. The default of 30 seconds strikes a balance between resource conservation and connection overhead. When a gRPC connection times out due to inactivity, the client automatically reconnects on demand when the next JDBC operation is requested, so there's no need for manual reconnection handling.

OJP also enforces a global hard cap on concurrent in-flight gRPC calls via `ojp.server.maxConcurrentRequests` (default `200`, `0` disables). When tripped, over-limit calls are rejected with `RESOURCE_EXHAUSTED`. This cap is **shared across all datasources and all clients on the JVM**, so it is intended as JVM self-protection (gRPC threads, heap, file descriptors), not as a workload-shaping tool. Per-datasource backpressure should come from HikariCP pool sizing, `ojp.server.admissionControl.maxQueueDepth`, and SQS fast/slow lanes. As a rule of thumb, size the global cap to roughly the sum of per-datasource `(poolSize + maxQueueDepth)` × 1.5 so that the per-datasource limits reject first under expected load.

**Important**: These are gRPC connection timeouts, not database connection timeouts. Each gRPC connection uses HTTP/2 multiplexing, allowing many virtual JDBC connections to share a single gRPC connection. This multiplexed architecture means one gRPC connection can handle hundreds of `getConnection()` calls from the client side.

For most deployments, the default 30-second timeout works well. However, if you want to avoid frequent reconnections, consider increasing this timeout (or even disabling it with a very high value like 3600000 for 1 hour) for environments with consistent traffic patterns. The gRPC connection reestablishment is relatively lightweight, but avoiding it entirely is even better for performance.

Here's how you configure these core settings:

```bash
# Using JVM system properties
java -Duser.timezone=UTC \
     -Dojp.server.port=9059 \
     -Dojp.prometheus.port=9091 \
     -Dojp.server.virtualThreads.enabled=true \
     -Dojp.server.threadPoolSize=100 \
     -Dojp.server.maxRequestSize=8388608 \
     -Dojp.server.connectionIdleTimeout=60000 \
     -Dojp.server.maxConcurrentRequests=200 \
     -jar ojp-server.jar
```

Or using environment variables for container deployments:

```bash
export OJP_SERVER_PORT=9059
export OJP_PROMETHEUS_PORT=9091
export OJP_SERVER_VIRTUALTHREADS_ENABLED=true
export OJP_SERVER_THREADPOOLSIZE=100
export OJP_SERVER_MAXREQUESTSIZE=8388608
export OJP_SERVER_CONNECTIONIDLETIMEOUT=60000
java -Duser.timezone=UTC -jar ojp-server.jar
```

```mermaid
sequenceDiagram
    participant Client
    participant Server
    participant ThreadPool
    participant Database
    
    Client->>Server: Connect to :9059
    Server->>ThreadPool: Acquire Thread
    ThreadPool->>Server: Thread Assigned
    Client->>Server: JDBC Request (< 4MB)
    Server->>Database: Execute Query
    Database->>Server: Result
    Server->>Client: Response
    Note over Client,Server: Idle for 30s
    Server->>Client: Close Idle Connection
```

## 6.3 Security Configuration

Security in OJP includes multiple layers: IP-based access control, TLS/mTLS encryption, and network segregation. This section covers IP whitelisting for basic access control. For comprehensive security guidance including SSL/TLS configuration between OJP Server and databases, mTLS between JDBC driver and OJP Server, and network architecture patterns, see **Chapter 11: Security**.

### IP-Based Access Control

IP whitelisting gives you fine-grained control over who can connect to your server. The security model is simple yet effective: you define IP whitelists that specify which clients can access the gRPC endpoint and which can access the Prometheus metrics endpoint.

By default, both endpoints accept connections from anywhere (0.0.0.0/0), which is perfect for development but inappropriate for production. In production environments, you'll want to lock down access to specific networks or IP addresses. The server supports multiple formats for defining allowed addresses, including individual IPs, CIDR notation for network ranges, and comma-separated lists for multiple rules.

**[IMAGE PROMPT: Create a network security diagram showing OJP Server at the center with two separate firewalls/shields. The left shield guards "gRPC Endpoint" with rules like "192.168.1.0/24" and "10.0.0.0/8" (labeled "Application Network"). The right shield guards "Prometheus Endpoint" with rules like "192.168.100.0/24" (labeled "Monitoring Network"). Show blocked connections (red X) and allowed connections (green checkmark) from different IP ranges. Style: Professional security diagram with red, green, and blue color coding.]**

A common pattern is to allow gRPC connections from your application network while restricting Prometheus access to your monitoring infrastructure. This separation of concerns reflects the principle of least privilege—applications need database access, but they don't need access to operational metrics.

```bash
# Restrict gRPC to internal application network
# Allow Prometheus from monitoring subnet only
java -Duser.timezone=UTC \
     -Dojp.server.allowedIps="10.0.0.0/8,172.16.0.0/12" \
     -Dojp.prometheus.allowedIps="192.168.100.0/24" \
     -jar ojp-server.jar
```

The IP whitelist supports several formats to match your network topology. You can specify individual hosts for precise control, use CIDR notation for subnet ranges, or combine multiple rules. Here are some practical examples:

```bash
# Allow specific development machines
-Dojp.server.allowedIps="192.168.1.100,192.168.1.101,192.168.1.102"

# Allow entire department subnet
-Dojp.server.allowedIps="192.168.10.0/24"

# Multiple subnets plus localhost
-Dojp.server.allowedIps="10.0.0.0/8,192.168.1.0/24,127.0.0.1"

# Production: restrict to Kubernetes pod network
-Dojp.server.allowedIps="10.244.0.0/16"
```

Remember that security configuration is checked on every connection attempt. The server performs efficient CIDR matching, so even with complex rulesets, the performance impact is negligible. However, overly permissive rules can't be fixed with monitoring—start restrictive and open up as needed.

**[IMAGE PROMPT: Create a flowchart diagram showing IP access control decision process. Start with "Incoming Connection" at top, flow through "Extract Client IP", then "Check Against Whitelist Rules" (shown as a list icon with CIDR rules), then a diamond decision "Match Found?". On "Yes" path: "Allow Connection" (green box), on "No" path: "Reject Connection" (red box) with "Log Security Event" beneath it. Style: Clean technical flowchart with green/red color coding for accept/reject paths.]**

## 6.4 Logging and Debugging

Effective logging is crucial for understanding server behavior and troubleshooting issues. OJP provides granular log level control, allowing you to adjust verbosity based on your needs. The server supports five log levels that follow the standard severity hierarchy: TRACE, DEBUG, INFO, WARN, and ERROR.

For production environments, **ERROR level is recommended** for maximum performance unless investigating issues. This logs only critical errors while minimizing overhead. INFO level provides more operational visibility but increases log volume. Use DEBUG or TRACE only temporarily when troubleshooting specific issues.

When troubleshooting, DEBUG level reveals the internal decision-making process, showing you exactly how the server handles requests, manages connections, and applies configuration.

TRACE level is your deep-dive diagnostic tool. It logs every operation in exhaustive detail, including gRPC message contents, connection lifecycle events, and thread pool activity. Use TRACE sparingly and temporarily—the log volume can be substantial, and the performance impact is noticeable. Enable it when you need to understand exact request flow or diagnose subtle timing issues, then switch back to DEBUG or INFO once you've gathered your diagnostic data.

```bash
# Production: ERROR level for maximum performance
-Dojp.server.logLevel=ERROR

# Production with operational visibility: INFO level
-Dojp.server.logLevel=INFO

# Development: DEBUG for detailed behavior
-Dojp.server.logLevel=DEBUG

# Troubleshooting: TRACE for complete diagnostics
-Dojp.server.logLevel=TRACE
```

Here's what you'll see at each level:

At ERROR level, you'll only see critical failures and exceptions that require immediate attention. This is the recommended production setting for maximum performance.

WARN level adds warnings about non-critical issues like configuration inconsistencies, deprecated features, or recoverable errors. Useful when you want to catch potential problems before they become critical.

INFO level adds server lifecycle events (startup, shutdown), configuration summaries, connection pool statistics, and slow query notifications. This gives you operational awareness without excessive detail.

DEBUG level adds request routing decisions, connection acquisition and release events, circuit breaker state changes, and detailed error contexts. This level helps you understand why the server makes specific decisions.

TRACE level includes everything from DEBUG plus gRPC message contents, thread scheduling details, raw protocol events, and internal state transitions. This is your "show me everything" level for deep debugging sessions.

**[IMAGE PROMPT: Create a layered visualization showing three log level views of the same event. Top layer labeled "INFO" shows a single line: "Connection established to PostgreSQL". Middle layer labeled "DEBUG" shows INFO plus 3-4 additional lines with connection details. Bottom layer labeled "TRACE" shows DEBUG plus many detailed lines including raw protocol messages and timestamps. Use different background shades (light, medium, dark) to distinguish layers. Style: Code editor-style display with monospace font and subtle line numbers.]**

The log format is designed for both human readability and machine parsing. Each log entry includes a timestamp, thread ID, log level, logger name, and message. This structured format works well with log aggregation tools like Splunk, ELK Stack, or CloudWatch Logs.

```mermaid
graph LR
    A[Log Event] --> B{Log Level Setting}
    B -->|INFO| C[Basic Events Only]
    B -->|DEBUG| D[Events + Decisions]
    B -->|TRACE| E[Complete Detail]
    C --> F[Log Output]
    D --> F
    E --> F
    F --> G[Log Aggregation]
    G --> H[Monitoring Dashboard]
```

## 6.5 OpenTelemetry Integration

Modern observability goes beyond logs. OJP integrates with OpenTelemetry to provide comprehensive metrics through Prometheus, covering both gRPC operations and connection pool behavior. The server automatically instruments gRPC communications and all supported pool types (XA, HikariCP, DBCP), providing deep insights into request processing, pool health, and server performance.

**Note**: OJP exports metrics via Prometheus and also supports distributed tracing via OpenTelemetry. Metrics are enabled by default; distributed tracing must be explicitly enabled via configuration (see Chapter 13 for full tracing configuration details). The OpenTelemetry integration provides operational metrics such as request rates, error rates, and latency through the Prometheus endpoint, as well as distributed traces to Zipkin or OTLP-compatible backends such as Jaeger and Grafana Tempo.


OpenTelemetry support is enabled by default, making the Prometheus metrics endpoint available at the configured port (default 9159). The server provides separate control over different metric categories, allowing you to enable or disable gRPC, pool, and circuit breaker metrics independently.

**[IMAGE PROMPT: Create a metrics dashboard visualization showing Prometheus metrics from OJP Server. Display panels for: "Request Rate" (line graph), "Connection Pool Usage" (gauge showing active/idle/max), "Pool Utilization %" (multi-pool comparison), "Query Latency p95/p99" (histogram), "Error Rate" (area chart), "Pool Health" (stat panel showing exhaustion/leaks). Use modern Grafana-style UI with dark theme, multiple time series, and clear metric labels. Style: Modern observability dashboard with color-coded metrics and real-time graphs.]**

The configuration provides three levels of control:

```bash
# Master switch - controls OpenTelemetry infrastructure
-Dojp.telemetry.enabled=true

# Granular control over metric categories (both default to true when telemetry is enabled)
-Dojp.telemetry.grpc.metrics.enabled=true      # gRPC server metrics
-Dojp.telemetry.pool.metrics.enabled=true      # Connection pool metrics (XA, HikariCP, DBCP)
-Dojp.telemetry.circuitbreaker.enabled=true    # Circuit breaker metrics

# Disable telemetry completely for performance-critical scenarios
-Dojp.telemetry.enabled=false
```

This three-tier approach lets you optimize your observability setup. The master switch (`ojp.telemetry.enabled`) controls whether the OpenTelemetry SDK and Prometheus server initialize at all. When disabled, the system uses no-op telemetry with zero overhead. The granular flags (`ojp.telemetry.grpc.metrics.enabled`, `ojp.telemetry.pool.metrics.enabled`, `ojp.telemetry.circuitbreaker.enabled`) control which metrics are collected within an already-initialized OpenTelemetry system, allowing you to focus on the metrics that matter most for your deployment.

### Available Metrics

**gRPC Metrics** - When gRPC metrics are enabled, you'll see standard gRPC instrumentation covering request counts, request durations, error rates, and method-level breakdowns. These help you understand how efficiently applications communicate with OJP.

**Pool Metrics** - When pool metrics are enabled, OJP exposes comprehensive metrics for all pool types:
- XA Pools: Active/idle connections, utilization %, pending threads, lifecycle events (created/destroyed), exhaustion events, validation failures, leak detection, and acquisition time statistics
- HikariCP Pools: Active/idle/total connections, pending threads, max/min pool sizes
- DBCP Pools: Similar metrics to HikariCP for consistency

All pool metrics are tagged with the pool name, enabling multi-pool monitoring in environments with multiple databases. The metrics follow the same patterns as HikariCP's native metrics, providing familiarity for teams already monitoring HikariCP deployments.

For detailed information about available metrics, monitoring best practices, and Grafana dashboard examples, see Chapter 13: Telemetry and Monitoring.

```mermaid
sequenceDiagram
    participant App
    participant OJP
    participant Pool
    participant Database
    participant Prometheus
    
    App->>OJP: Execute Query
    OJP->>OJP: Record gRPC Metrics
    OJP->>Pool: Get Connection
    Pool->>Pool: Record Pool Metrics
    Pool-->>OJP: Connection
    OJP->>Database: Database Query
    Database-->>OJP: Result
    OJP-->>App: Complete Response
    Prometheus->>OJP: Scrape :9159/metrics
    OJP-->>Prometheus: gRPC + Pool Metrics
    Prometheus->>Prometheus: Store & Index
```

## 6.6 Circuit Breaker Configuration

Resilience patterns are built into OJP, starting with the circuit breaker. When database operations consistently fail, the circuit breaker prevents your applications from overwhelming the database with retry attempts. Instead, it fails fast after a configured threshold, giving the database time to recover while protecting your application threads from hanging on impossible operations.

The circuit breaker operates in three states: closed (normal operation), open (failing fast), and half-open (testing recovery). When connection attempts fail repeatedly, the breaker trips to the open state and rejects new requests immediately. After a timeout period, it enters half-open state to test if the database has recovered. If test requests succeed, it closes again; if they fail, it remains open for another timeout period.

**[IMAGE PROMPT: Create a state machine diagram showing circuit breaker states. Three circles labeled "CLOSED" (green), "OPEN" (red), and "HALF-OPEN" (yellow). Arrows between states: "CLOSED to OPEN" labeled "3 failures" (configurable threshold), "OPEN to HALF-OPEN" labeled "60s timeout", "HALF-OPEN to CLOSED" labeled "Success", "HALF-OPEN to OPEN" labeled "Failure". Include small icons: green checkmark for CLOSED, red X for OPEN, yellow caution symbol for HALF-OPEN. Style: Clean state diagram with color-coded states and clear transition labels.]**

The failure threshold defaults to 3 consecutive failures, which strikes a balance between resilience and responsiveness. If you're experiencing intermittent network issues, you might increase this threshold to avoid unnecessary circuit trips. In environments where database downtime is rare but catastrophic, a lower threshold helps protect application resources more aggressively.

The timeout period controls how long the circuit breaker stays open before attempting recovery. The default of 60 seconds gives most transient issues time to resolve without excessive retry overhead. Adjust this based on your recovery time objectives and database restart characteristics.

```bash
# Production: Aggressive failure detection
-Dojp.server.circuitBreakerThreshold=3 \
-Dojp.server.circuitBreakerTimeout=60000

# Development: More tolerant of temporary issues
-Dojp.server.circuitBreakerThreshold=5 \
-Dojp.server.circuitBreakerTimeout=30000

# High-availability: Quick recovery attempts
-Dojp.server.circuitBreakerThreshold=2 \
-Dojp.server.circuitBreakerTimeout=15000
```

When the circuit breaker trips, clients receive immediate failures instead of hanging connections. This fail-fast behavior is crucial for maintaining application responsiveness. Your application can handle these failures gracefully, perhaps by serving cached data or returning degraded functionality, rather than leaving users waiting for doomed requests.

The circuit breaker state transitions are logged and exposed as Prometheus metrics, giving you visibility into database health and resilience events. Monitor these metrics to understand if you're experiencing connection issues that need investigation.

```mermaid
stateDiagram-v2
    [*] --> Closed
    Closed --> Open : Threshold failures reached
    Open --> HalfOpen : Timeout expires
    HalfOpen --> Closed : Test request succeeds
    HalfOpen --> Open : Test request fails
    
    note right of Closed
        Normal operation
        Tracking failures
    end note
    
    note right of Open
        Failing fast
        Protecting resources
    end note
    
    note right of HalfOpen
        Testing recovery
        Limited requests
    end note
```

## 6.7 Slow Query Segregation

One of OJP's useful features is slow query segregation. **Use it strongly when one server handles mixed workloads** (fast OLTP-style queries plus slower analytics/reporting queries). The server classifies operations as fast or slow based on historical patterns and manages separate slots for each category, so long-running queries do not starve fast requests.

The feature works by monitoring operation execution times and building a statistical model of each operation's performance characteristics. When an operation consistently takes longer than average, the server classifies it as slow and routes it to the slow slot pool. Fast operations continue using the fast slot pool, maintaining their responsiveness even under mixed workload pressure.

**[IMAGE PROMPT: Create a side-by-side comparison showing connection pool behavior. Left side labeled "Without Segregation": single queue with fast queries (lightning bolt icons) blocked behind slow queries (turtle icons), showing red warning indicators. Right side labeled "With Segregation": two separate queues, top queue "Fast Slots (80%)" with lightning bolts flowing freely, bottom queue "Slow Slots (20%)" with turtle icons, showing green success indicators. Style: Before/after comparison with color-coded performance indicators.]**

For mixed workloads, this feature usually gives clear value with minimal configuration. The default of 20% slow slots is a good starting point. You might increase this if you have many legitimate long-running queries.

For **pure OLTP** (mostly short queries) or **pure OLAP** (mostly long-running queries), this feature is often not useful. In those cases, keep it disabled unless monitoring shows real slow-vs-fast contention.

```bash
# Enable with default 20% slow slots
-Dojp.server.slowQuerySegregation.enabled=true \
-Dojp.server.slowQuerySegregation.slowSlotPercentage=20

# Analytical workload: more slow slots
-Dojp.server.slowQuerySegregation.slowSlotPercentage=40

# Transactional workload: fewer slow slots
-Dojp.server.slowQuerySegregation.slowSlotPercentage=10

# Disable (default behavior)
-Dojp.server.slowQuerySegregation.enabled=false
```

The idle timeout setting controls when slots can borrow from the other pool. If the fast pool is empty but slow slots sit idle, fast operations can temporarily borrow those slots. This prevents resource waste while maintaining the segregation benefits when both pools are active. The default 10-second timeout means slots must be idle briefly before lending—preventing constant oscillation.

Timeout settings for acquiring slots provide backpressure when pools are exhausted. With slow query segregation enabled, fast/slow lane timeout settings control admission waits per lane. Backend pool borrow remains fail-fast after admission.

For pooled lazy session allocation, OJP uses admission semaphores as the timeout owner and backend pool borrow is forced to fail fast. This avoids additive waits (admission wait + pool borrow wait) under saturation and keeps behavior consistent across XA and non-XA paths.
When slow query segregation is enabled, `ojp.server.slowQuerySegregation.fastSlotTimeout` and `ojp.server.slowQuerySegregation.slowSlotTimeout` take precedence for fast/slow lane admission waits.

Admission queue depth is also bounded to prevent unbounded waiter buildup under heavy surge traffic. Configure this with `ojp.server.admissionControl.maxQueueDepth` (default `0`, which auto-calculates as `totalSlots × 2` per semaphore, where `totalSlots` is the pool slot count used by admission control). This limit applies to **all** admission-control modes.

```bash
# Keep auto queue depth (recommended starting point)
-Dojp.server.admissionControl.maxQueueDepth=0

# Or set an explicit queue cap
-Dojp.server.admissionControl.maxQueueDepth=128
```

**[IMAGE PROMPT: Create a dynamic allocation diagram showing how idle slots can be borrowed between pools. Show two pools: "Fast Slots" (4 boxes, 3 active, 1 idle) and "Slow Slots" (2 boxes, 1 active, 1 idle). Draw a curved arrow labeled "Temporary Borrow (if idle >10s)" from the idle slow slot to fast pool. Include a timer icon and "Returns when fast demand drops" annotation. Use green for active, gray for idle, and dotted lines for temporary borrowing. Style: Technical system diagram with clear state visualization.]**

The classification algorithm adapts to your workload patterns over time. An operation that starts fast but becomes slow under load will gradually migrate to slow slot management. This dynamic behavior means you don't need to manually categorize your queries—the server learns from observation.

```mermaid
graph TD
    A[Operation Request] --> B{Historical Data?}
    B -->|No| C[Use Fast Pool]
    B -->|Yes| D{Classified as Slow?}
    D -->|No| E[Try Fast Pool]
    D -->|Yes| F[Try Slow Pool]
    E --> G{Fast Pool Available?}
    F --> H{Slow Pool Available?}
    G -->|Yes| I[Execute in Fast Pool]
    G -->|No| J{Slow Pool Idle >10s?}
    H -->|Yes| K[Execute in Slow Pool]
    H -->|No| L{Fast Pool Idle >10s?}
    J -->|Yes| M[Borrow from Slow Pool]
    J -->|No| N[Wait for Fast Slot]
    L -->|Yes| O[Borrow from Fast Pool]
    L -->|No| P[Wait for Slow Slot]
    M --> K
    O --> I
    I --> Q[Update Statistics]
    K --> Q
    Q --> R[Adjust Classification]
```

## 6.8 Client Throttling Signals

While client-side throttling is configured on the **driver side** (see
[Chapter 8a: Client-Side Throttling](../ebook/part3-chapter8a-client-throttling.md)),
the server plays an important role: it provides the signals that clients use to compute
their per-instance limit.

On every `connect()` response, the server populates three fields in `SessionInfo`:

| Field | What it carries | Source |
|---|---|---|
| `maxAdmission` | The configured pool size on this node | `SlotManager.totalSlots` |
| `clientCount` | Number of distinct application instances (JVMs) connected for this datasource/credential pair | `SessionManagerImpl` ref-count map |
| `observedPeak` | Real peak in-flight count before the last admission timeout; `0` = no timeout yet | `SlotManager` AIMD tracking |

The server updates `observedPeak` automatically whenever an admission timeout occurs —
no configuration needed. By default, `observedPeak` recovers toward `maxAdmission` at a
rate of +1 every `totalSlots × 2` successful releases. If you need to tune recovery speed,
set `ojp.server.admissionControl.observedPeakRecoveryFactor` to a different multiplier:

```bash
# Default: recover observedPeak by +1 every (totalSlots × 2) successful releases
-Dojp.server.admissionControl.observedPeakRecoveryFactor=2

# Faster recovery (useful for bursty workloads that return to normal quickly)
-Dojp.server.admissionControl.observedPeakRecoveryFactor=1

# Slower recovery (more conservative — useful for systems that stay degraded)
-Dojp.server.admissionControl.observedPeakRecoveryFactor=4
```

No other server-side configuration is needed to support client throttling.

## 6.9 Configuration Best Practices

With all these configuration options available, how do you choose the right settings? Start with the defaults—they're designed for typical workloads and provide good performance out of the box. Then adjust based on monitoring data and observed behavior. Don't preemptively tune settings based on assumptions; let your actual workload guide your configuration.

For development environments, prioritize visibility and fast feedback. Use INFO logging (or DEBUG for active development), generous timeouts, and tolerant circuit breaker settings. This makes it easier to understand server behavior and diagnose issues during development.

```bash
# Development configuration
export OJP_SERVER_PORT=1059
export OJP_SERVER_LOGLEVEL=DEBUG
export OJP_PROMETHEUS_PORT=9159
export OJP_SERVER_VIRTUALTHREADS_ENABLED=true
export OJP_SERVER_THREADPOOLSIZE=50
export OJP_SERVER_CIRCUITBREAKERTHRESHOLD=5
export OJP_SERVER_ALLOWEDIPS="0.0.0.0/0"
export OJP_TELEMETRY_ENABLED=true
```

Production environments require different trade-offs. Use ERROR or INFO logging (ERROR recommended for maximum performance; INFO for operational visibility). Implement proper IP restrictions for security. Enable OpenTelemetry for distributed tracing. Configure appropriate timeouts for your SLAs. Be very careful with DEBUG and TRACE in production—they are extremely verbose and can impact performance significantly.

```bash
# Production configuration
export OJP_SERVER_PORT=1059
export OJP_SERVER_LOGLEVEL=ERROR  # Recommended for production performance
export OJP_PROMETHEUS_PORT=9159
export OJP_SERVER_VIRTUALTHREADS_ENABLED=true
# OJP_SERVER_THREADPOOLSIZE applies only when virtual threads are disabled
export OJP_SERVER_CIRCUITBREAKERTHRESHOLD=3
export OJP_SERVER_CIRCUITBREAKERTIMEOUT=60000
export OJP_SERVER_ALLOWEDIPS="10.0.0.0/8"
export OJP_PROMETHEUS_ALLOWEDIPS="192.168.100.0/24"
export OJP_TELEMETRY_ENABLED=true
export OJP_OPENTELEMETRY_ENDPOINT=http://jaeger:4317
export OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED=true
```

**[IMAGE PROMPT: Create a comparison table visualization showing recommended configurations for three environments. Three columns labeled "Development", "Staging", and "Production". Rows for key settings like Log Level (DEBUG/INFO/INFO), Security (Open/Restricted/Locked Down), Telemetry (Optional/Enabled/Required), Thread Pool (Low/Medium/High), Circuit Breaker (Tolerant/Balanced/Strict). Use color coding: green for development-friendly, yellow for balanced, red for production-strict. Style: Professional configuration matrix with clear visual hierarchy.]**

Monitor your configuration's effectiveness through the Prometheus metrics endpoint. Watch for circuit breaker trips, connection pool saturation, slow query segregation balance, and request latencies. These metrics tell you if your configuration matches your workload or needs adjustment.

Remember that configuration is not set-and-forget. As your application evolves and workload patterns change, revisit your OJP server configuration. What worked for 1000 requests per second might not work for 10000. Continuous monitoring and periodic tuning keep your server running optimally.

```mermaid
graph LR
    A[Default Config] --> B[Deploy]
    B --> C[Monitor Metrics]
    C --> D{Issues Detected?}
    D -->|No| E[Continue Monitoring]
    D -->|Yes| F[Analyze Patterns]
    F --> G[Adjust Config]
    G --> B
    E --> C
```

## 6.10 Configuration Validation and Troubleshooting

When things don't work as expected, configuration issues are often the culprit. OJP provides clear error messages when configuration values are invalid or inconsistent. The server validates configuration at startup and fails fast if critical settings are problematic.

Common configuration mistakes include conflicting port assignments, invalid IP whitelist formats, out-of-range percentage values, and mismatched property names. The startup logs show exactly which configuration values were loaded and from which sources, making it easy to verify your settings took effect.

If you're experiencing unexpected behavior, enable DEBUG logging temporarily and watch the startup sequence. You'll see each configuration property being loaded, any validation errors, and the final active configuration. This diagnostic information usually points directly to the problem.

**[IMAGE PROMPT: Create a troubleshooting flowchart for common configuration issues. Start with "Server Won't Start" diamond. Branch to "Check Logs" which shows code snippet of startup logs. From there, multiple paths: "Port Conflict?" leads to "Change Port", "Invalid IP Format?" leads to "Fix CIDR Syntax", "Missing Required Value?" leads to "Set Property". Each resolution path leads back to "Restart Server" then "Verify Success" (green checkmark). Style: Decision tree flowchart with code snippets and clear resolution paths.]**

For Docker deployments, remember that environment variables must use the `OJP_` prefix and uppercase with underscores. JVM system properties use the `ojp.` prefix with lowercase and dots. Mixing these formats is a common mistake that results in configuration being silently ignored.

```bash
# Correct environment variable format
export OJP_SERVER_PORT=9059

# Incorrect: will be ignored
export ojp.server.port=9059

# Correct JVM property format
-Dojp.server.port=9059

# Incorrect: will cause error
-DOJP_SERVER_PORT=9059
```

The server logs its active configuration at INFO level during startup. Review this output to confirm your settings were applied correctly. If you see unexpected defaults, it means your configuration wasn't recognized—check for typos, case sensitivity, and format issues.

## 6.8 Read/Write Splitting

OJP can automatically route read and write traffic to separate database instances. Write operations (INSERT, UPDATE, DELETE, DDL) always go to the primary. Stateless auto-commit reads (SELECT, WITH, EXPLAIN, SHOW, DESCRIBE) are routed to a replica chosen according to a configurable selection strategy. All operations inside an explicit transaction stay on the primary.

Read/write splitting is configured entirely through the client's `ojp.properties` file — no server-side configuration changes are required. The OJP server reads the `*.ojp.readwrite.*` properties forwarded by the driver on first connection and creates isolated replica connection pools automatically.

### 6.8.1 Enabling Read/Write Splitting

Mark the primary datasource and each replica in `ojp.properties`:

```properties
# Primary datasource
mydb.ojp.readwrite.role=primary
mydb.ojp.readwrite.enabled=true
mydb.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN

# Replica 1
replica1.ojp.readwrite.role=replica
replica1.ojp.readwrite.primary=mydb
replica1.ojp.connection.url=jdbc:postgresql://replica1:5432/mydb
replica1.ojp.connection.user=app_ro
replica1.ojp.connection.password=secret

# Replica 2
replica2.ojp.readwrite.role=replica
replica2.ojp.readwrite.primary=mydb
replica2.ojp.connection.url=jdbc:postgresql://replica2:5432/mydb
replica2.ojp.connection.user=app_ro
replica2.ojp.connection.password=secret
```

Three replica selection strategies are available:

- **`ROUND_ROBIN`** (default) — distributes reads evenly across all replicas in order
- **`RANDOM`** — picks a replica at random for each request
- **`LEAST_CONNECTIONS`** — selects the replica with fewest active connections (Phase 3)

### 6.8.2 Sticky Sessions (Read-Your-Writes)

Sticky sessions keep reads on the primary for a short window after every write, giving replicas time to catch up. This is **opt-in**: the default `stickySessionSeconds` is `0` (disabled).

```properties
# Keep reads on primary for 3 seconds after every write
mydb.ojp.readwrite.stickySessionSeconds=3
```

> **Important:** Only enable sticky sessions when the application must see its own writes immediately outside of a transaction. If all reads following a write are in the same transaction, the transaction itself already guarantees read-your-writes via the primary, and sticky sessions add unnecessary overhead.

### 6.8.3 Routing Rules

| Operation | Inside Transaction | Sticky Window Active | Routes To |
|---|---|---|---|
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | — | — | Replica |
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | ✓ | — | Primary |
| SELECT / WITH / EXPLAIN / SHOW / DESCRIBE | — | ✓ | Primary |
| INSERT / UPDATE / DELETE / DDL | any | any | Primary |

### 6.8.4 Replica Pool Configuration

Each replica has its own connection pool. Size it to handle peak read traffic independently of the primary pool.

| Property | Default | Description |
|---|---|---|
| `{replica}.ojp.pool.maxPoolSize` | `10` | Maximum replica pool size |
| `{replica}.ojp.pool.minIdle` | `2` | Minimum idle connections |
| `{replica}.ojp.pool.connectionTimeout` | `30000` | Acquire timeout (ms) |
| `{replica}.ojp.pool.idleTimeout` | `600000` | Idle connection timeout (ms) |
| `{replica}.ojp.pool.maxLifetime` | `1800000` | Maximum connection lifetime (ms) |

For the complete property reference see [OJP JDBC Configuration — Read/Write Splitting](../../documents/configuration/ojp-jdbc-configuration.md#readwrite-splitting-configuration).

### 6.8.5 Connection Pool Leak Detection

| Property | Default | Description |
|---|---|---|
| `ojp.connection.pool.leakDetectionThreshold` | `0` | HikariCP leak detection threshold (ms). `0` = disabled. |

The default is `0` (disabled) because OJP intentionally holds one physical connection per client session for the entire lifetime of that session — that's the whole point of the proxy. HikariCP's leak detector, however, is designed around the assumption that connections are borrowed and returned quickly, so enabling it produces false-positive "leak" warnings for tools with long-lived sessions (e.g. SQL IDEs). Only set this to a non-zero value temporarily, when you need to diagnose genuinely abandoned sessions, and turn it back off afterward.

## Summary

OJP server configuration gives you precise control over server behavior, security, performance, and observability. The hierarchical configuration system with JVM properties and environment variables provides flexibility for different deployment scenarios. Default settings work well for most use cases, but understanding the available options lets you optimize for your specific workload.

Key configuration areas include core server settings for network and threading, security controls through IP whitelisting, logging levels for operational visibility, OpenTelemetry integration for observability, circuit breakers for resilience, slow query segregation for performance under mixed workloads, and read/write splitting for scaling read traffic across replicas. Each area offers sensible defaults that you can refine based on monitoring data.

Start simple, monitor closely, and adjust based on observed behavior. Good configuration emerges from understanding your workload and using OJP's flexibility to match it, not from cargo-culting settings from other environments.

**[IMAGE PROMPT: Create a summary mind map with "OJP Server Configuration" at the center. Seven main branches radiating outward: "Core Settings" (server icon), "Security" (lock icon), "Logging" (document icon), "Telemetry" (graph icon), "Circuit Breaker" (shield icon), "Slow Query Segregation" (speedometer icon), and "Read/Write Splitting" (fork/branch icon). Each branch has 2-3 sub-branches with key points. Use colors to group related concepts and make it visually hierarchical. Style: Modern mind map with icons and color coding.]**
