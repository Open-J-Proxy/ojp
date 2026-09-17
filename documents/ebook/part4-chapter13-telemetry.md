# Chapter 13: Telemetry and Monitoring

Understanding how your database proxy performs in production is essential for maintaining reliable systems. Open J Proxy provides comprehensive observability through OpenTelemetry integration, allowing you to track metrics, monitor performance, and identify issues before they impact your applications.

## 13.1 Understanding OJP's Observability Strategy

When we designed OJP's observability features, we made a deliberate choice to embrace industry standards rather than creating proprietary monitoring solutions. This decision, documented in ADR-005, brings several advantages to your operations team.

OpenTelemetry emerged as the clear winner in modern observability frameworks. It's vendor-neutral, widely supported, and provides seamless integration with the tools you're likely already using—Prometheus, Grafana, Datadog, or New Relic. By adopting OpenTelemetry, OJP ensures you're not locked into any particular monitoring ecosystem while still getting comprehensive insights into your proxy's behavior.

The observability architecture in OJP focuses on what matters most: understanding how your proxy processes database requests. Every gRPC call between your applications and OJP Server generates telemetry data. Connection pool behavior, session management, query routing decisions—all of these activities create metrics that help you understand system health and performance characteristics.

**[AI Image Prompt: Create a clean architecture diagram showing the OpenTelemetry observability flow in OJP. Show three layers: 1) Application layer with multiple microservices making database calls, 2) OJP Server with OpenTelemetry instrumentation highlighted, collecting metrics and traces, and 3) Observability backends (Prometheus, Grafana, cloud providers) receiving exported data. Use modern flat design with subtle icons for each component. Include arrows showing data flow from applications through OJP to monitoring systems. Style: Technical diagram, professional, clear visual hierarchy.]**

### What OJP Monitors

OJP's telemetry system captures several categories of operational data. At the gRPC communication level, you'll see metrics for request counts, latency distributions, and error rates. These metrics help you understand how efficiently applications communicate with the proxy and identify any network-related bottlenecks.

The server operational metrics provide insights into OJP's internal health. You can track thread pool utilization, memory usage, and garbage collection behavior. Understanding these metrics helps you right-size your OJP Server deployment and identify resource constraints before they cause problems.

Connection pool metrics form a critical category of telemetry data. OJP exposes comprehensive metrics for all pool types—XA pools (Commons Pool 2), HikariCP, and DBCP. You can monitor active and idle connections, pool utilization percentages, threads waiting for connections, connection lifecycle events (opened, destroyed), and health indicators like exhaustion events, validation failures, and leak detection. These metrics directly impact your database's performance, as connection management efficiency determines how well your applications can scale.

SQL execution metrics provide per-statement visibility into every query that flows through the proxy. For each unique SQL statement, OJP records an execution time histogram enabling p50/p95/p99 latency analysis, a total execution count, and a slow-execution count. Crucially, these metrics are emitted identically for both standard and XA connections, giving you a unified view of database performance regardless of transaction model.

The pool metrics follow the same patterns as HikariCP's native metrics implementation, providing familiar observability for teams already monitoring HikariCP pools. Each metric is tagged with the pool name, allowing you to monitor multiple pools independently in multi-database environments.

### Current Capabilities and Future Direction

Right now, OJP implements metrics collection with Prometheus export capabilities. This means you can scrape metrics from OJP's HTTP endpoint and visualize them in your monitoring dashboard of choice. The implementation uses OpenTelemetry's metrics SDK, ensuring compatibility with the broader observability ecosystem.

OJP also supports distributed tracing through OpenTelemetry. Traces are automatically emitted for all gRPC calls handled by the server and can be exported to any compatible backend—Zipkin, Jaeger, Grafana Tempo, or any OTLP-compatible collector. Tracing is disabled by default to avoid unnecessary overhead and must be explicitly enabled via configuration. Once enabled, you can follow individual requests through your entire stack—from application through OJP to the database and back—providing the end-to-end visibility needed to diagnose latency issues and understand request flows.

```mermaid
graph TB
    subgraph "Application Layer"
        A1[Microservice A]
        A2[Microservice B]
        A3[Microservice C]
    end
    
    subgraph "OJP Server"
        OJP[OJP Server<br/>OpenTelemetry<br/>Instrumentation]
        ME[Metrics<br/>Collector]
        TR[Trace Collector<br/>Zipkin / OTLP]
    end
    
    subgraph "Backends"
        PR[Prometheus]
        GR[Grafana]
        CL[Cloud Providers / Zipkin / Jaeger]
    end
    
    A1 -->|gRPC Calls| OJP
    A2 -->|gRPC Calls| OJP
    A3 -->|gRPC Calls| OJP
    
    OJP --> ME
    OJP --> TR
    
    ME -->|Scrape| PR
    PR --> GR
    ME -.->|Push| CL
    TR -.->|Push| CL
    
    style TR fill:#9f9,stroke:#333
    style ME fill:#9f9,stroke:#333
```

## 13.2 Setting Up Prometheus Metrics

Getting metrics from OJP into your monitoring system requires minimal configuration. The proxy exposes a Prometheus-compatible HTTP endpoint that any Prometheus server can scrape. Let's walk through setting this up from scratch.

### Basic Configuration

OJP's telemetry system activates by default when you start the server. The metrics endpoint listens on port 9159, and OpenTelemetry metrics collection starts automatically. For many deployments, you won't need to change anything—just point your Prometheus server at the endpoint and start collecting data.

However, production environments often require more specific configuration. You might need to change the metrics port to avoid conflicts with other services, or you might want to restrict which IP addresses can access telemetry data for security reasons. OJP provides configuration options through either JVM system properties or environment variables, giving you flexibility in how you manage configuration across different environments.

The configuration hierarchy works simply: JVM properties take precedence over environment variables, which take precedence over defaults. This means you can set defaults with environment variables in your deployment manifests while still allowing ops teams to override specific values at runtime using JVM properties when needed.

**[AI Image Prompt: Create an infographic showing OJP's metrics configuration hierarchy. Display three levels as a pyramid or stacked layers: bottom layer labeled "Defaults" (9159 port, enabled telemetry, open access), middle layer labeled "Environment Variables" (OJP_PROMETHEUS_PORT, OJP_TELEMETRY_ENABLED, OJP_PROMETHEUS_ALLOWED_IPS), and top layer labeled "JVM Properties" (ojp.prometheus.port, ojp.telemetry.enabled, ojp.prometheus.allowedIps). Use arrows showing override direction from bottom to top. Include example values for each configuration method. Style: Educational infographic, modern colors, clear typography.]**

### Configuration Examples

Here's how you configure OJP's telemetry using JVM properties. This approach works well when you're starting OJP directly from the command line or when you want to override environment-based configuration in specific scenarios:

```bash
java -Duser.timezone=UTC -jar ojp-server.jar \
  -Dojp.telemetry.enabled=true \
  -Dojp.prometheus.port=9159 \
  -Dojp.prometheus.allowedIps=127.0.0.1,10.0.0.0/8
```

The `allowedIps` property deserves special attention. It accepts a comma-separated list of IP addresses or CIDR blocks that can access the metrics endpoint. The default value of `0.0.0.0/0` allows access from anywhere, which works fine for development but poses security risks in production. Always restrict this to your monitoring infrastructure's IP ranges in production deployments.

For containerized deployments, environment variables provide a cleaner configuration approach. Most container orchestrators like Kubernetes or Docker Compose make it easy to inject environment variables into containers:

```bash
export OJP_TELEMETRY_ENABLED=true
export OJP_PROMETHEUS_PORT=9159
export OJP_PROMETHEUS_ALLOWED_IPS=127.0.0.1,10.0.0.0/8
java -Duser.timezone=UTC -jar ojp-server.jar
```

In Kubernetes environments, you might configure these through ConfigMaps or directly in your deployment manifests. The ojp-helm chart provides convenient parameters for telemetry configuration, making it easy to enable monitoring in cloud-native deployments.

### Accessing the Metrics Endpoint

Once OJP is running with telemetry enabled, the metrics endpoint becomes immediately available. You can verify it's working by curling the endpoint directly:

```bash
curl http://localhost:9159/metrics
```

This returns metrics in Prometheus's text-based exposition format. You'll see lines like:

```
# HELP grpc_server_started_total Total number of RPCs started on the server
# TYPE grpc_server_started_total counter
grpc_server_started_total{grpc_method="ExecuteQuery",grpc_service="ojp.OjpService",grpc_type="unary"} 1234

# HELP grpc_server_handled_total Total number of RPCs completed on the server
# TYPE grpc_server_handled_total counter
grpc_server_handled_total{grpc_code="OK",grpc_method="ExecuteQuery",grpc_service="ojp.OjpService",grpc_type="unary"} 1230
```

Each metric includes labels that provide context—the gRPC method being called, the service name, the response code. These labels allow you to slice and dice the metrics data in Grafana or your monitoring tool of choice.

**[AI Image Prompt: Create a screenshot mockup showing a terminal window displaying Prometheus metrics output from OJP. Show realistic metric names and values for gRPC server metrics, including counter and gauge types with labels. Highlight key metrics like grpc_server_started_total, grpc_server_handled_total, and connection pool metrics. Use a modern terminal theme (dark background, colored syntax highlighting). Include curl command at the top. Style: Terminal screenshot mockup, realistic, professional.]**

## 13.3 Configuring Distributed Tracing

OJP supports distributed tracing out of the box via OpenTelemetry. Every gRPC call handled by the server automatically generates a trace span, which can be pushed to any Zipkin-compatible or OTLP-compatible backend. Tracing is **disabled by default**—you must explicitly opt in through configuration.

### Tracing Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.tracing.enabled` | `OJP_TRACING_ENABLED` | `false` | Enable/disable distributed tracing |
| `ojp.tracing.exporter` | `OJP_TRACING_EXPORTER` | `zipkin` | Exporter type: `zipkin` or `otlp` |
| `ojp.tracing.endpoint` | `OJP_TRACING_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Exporter endpoint URL |
| `ojp.tracing.serviceName` | `OJP_TRACING_SERVICENAME` | `ojp-server` | Service name attached to all spans |
| `ojp.tracing.sampleRate` | `OJP_TRACING_SAMPLERATE` | `1.0` | Fraction of requests to sample (0.0–1.0) |

### Enabling Zipkin Tracing

Start a local Zipkin instance:

```bash
docker run -d -p 9411:9411 openzipkin/zipkin
```

Start OJP with tracing enabled:

```bash
java -Duser.timezone=UTC -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://localhost:9411/api/v2/spans \
  -Dojp.tracing.serviceName=my-ojp-server
```

Open `http://localhost:9411` in your browser to view traces.

### Enabling OTLP Tracing (Jaeger / Grafana Tempo)

Start a local Jaeger instance:

```bash
docker run -d \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Start OJP with OTLP tracing enabled:

```bash
java -Duser.timezone=UTC -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://localhost:4317 \
  -Dojp.tracing.sampleRate=0.1
```

Open `http://localhost:16686` in your browser to view traces in Jaeger.

### Using Environment Variables

For containerized deployments, configure tracing via environment variables:

```bash
export OJP_TRACING_ENABLED=true
export OJP_TRACING_EXPORTER=zipkin
export OJP_TRACING_ENDPOINT=http://zipkin:9411/api/v2/spans
export OJP_TRACING_SERVICENAME=ojp-server
java -Duser.timezone=UTC -jar ojp-server.jar
```

### Docker Compose Example (OJP + Zipkin)

```yaml
version: '3.8'
services:
  ojp-server:
    image: rrobetti/ojp:latest
    ports:
      - "1059:1059"
      - "9159:9159"
    environment:
      OJP_TRACING_ENABLED: "true"
      OJP_TRACING_ENDPOINT: "http://zipkin:9411/api/v2/spans"
      OJP_TRACING_SERVICENAME: "ojp-server"

  zipkin:
    image: openzipkin/zipkin
    ports:
      - "9411:9411"
```

### Sampling

The `ojp.tracing.sampleRate` property controls what fraction of requests are traced:

| Value | Behavior |
|-------|-----------|
| `1.0` | 100% of requests traced (default when tracing is enabled) |
| `0.5` | 50% of requests traced |
| `0.1` | 10% of requests traced |
| `0.0` | No requests traced (effectively disables tracing) |

For high-throughput production environments, a sample rate of `0.01` to `0.1` is recommended to reduce overhead without losing meaningful visibility.

**[AI Image Prompt: Create a distributed tracing visualization showing a waterfall diagram of nested spans for an OJP request. Show: "gRPC Call" span at the top, nested "OJP Server Processing" span (highlighted in orange), nested "Connection Acquisition" span, and "Database Round-trip" span at the bottom. Include span timing bars, duration labels (e.g., 45ms total), trace ID and span IDs. Use different colors for each layer. Style: Modern APM tool waterfall display, clean and readable.]**

## 13.4 Integrating with Prometheus and Grafana

Now that OJP exposes metrics, you need to collect and visualize them. Prometheus and Grafana form the de facto standard for this in modern infrastructure, and they integrate seamlessly with OJP's telemetry system.

### Configuring Prometheus to Scrape OJP

Prometheus uses a pull model for metrics collection—it periodically scrapes HTTP endpoints to gather metrics. You configure these scrape jobs in Prometheus's configuration file. Here's a minimal configuration for monitoring OJP:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'ojp-server'
    static_configs:
      - targets: ['ojp-server:9159']
    metrics_path: '/metrics'
    scrape_interval: 5s
```

The scrape interval determines how frequently Prometheus collects metrics. We set it to 5 seconds for OJP because database proxy metrics can change rapidly under load. A 5-second interval provides good resolution for understanding performance patterns without overwhelming Prometheus with data.

In production environments, you'll likely have multiple OJP Server instances. Prometheus supports service discovery mechanisms that automatically find all your OJP instances. In Kubernetes, for example, you can use the Kubernetes SD configuration to discover OJP pods automatically:

```yaml
scrape_configs:
  - job_name: 'ojp-server'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
            - production
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: ojp-server
        action: keep
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        regex: true
        action: keep
```

This configuration tells Prometheus to discover all pods in the production namespace labeled with `app=ojp-server` that have the annotation `prometheus.io/scrape=true`. It's a flexible pattern that scales as you add or remove OJP instances.

### Building Grafana Dashboards

Once Prometheus collects OJP metrics, Grafana provides the visualization layer. Creating effective dashboards requires understanding which metrics matter most for operational health.

Start with high-level health indicators. Create panels showing:

The total request rate (requests per second) helps you understand how busy OJP is. Use the `rate()` function in Prometheus to calculate this from the `grpc_server_started_total` counter:

```promql
sum(rate(grpc_server_started_total{job="ojp-server"}[1m]))
```

Error rate shows the percentage of failed requests. Calculate this by dividing failed requests by total requests:

```promql
sum(rate(grpc_server_handled_total{grpc_code!="OK",job="ojp-server"}[1m]))
/
sum(rate(grpc_server_handled_total{job="ojp-server"}[1m])) * 100
```

Request latency percentiles reveal performance characteristics. The histogram buckets let you calculate p50, p95, and p99 latency:

```promql
histogram_quantile(0.95, 
  sum(rate(grpc_server_handling_seconds_bucket{job="ojp-server"}[1m])) by (le, grpc_method)
)
```

**[AI Image Prompt: Create a modern Grafana dashboard mockup showing OJP monitoring panels. Include 4-6 panels: 1) Line graph showing request rate over time with multiple OJP instances, 2) Gauge showing current error rate percentage, 3) Line graph showing latency percentiles (p50, p95, p99), 4) Bar chart comparing request counts by gRPC method, 5) Connection pool utilization heatmap, 6) Server health status indicator. Use Grafana's dark theme, realistic metrics data with trends, proper axis labels. Style: Dashboard mockup, professional, data visualization best practices.]**

Beyond these basics, you'll want panels specific to OJP's functionality. Monitor connection pool metrics to understand how effectively OJP manages database connections. Track session durations to identify long-running transactions that might be holding connections. When running multiple OJP servers, aggregate metrics across instances to get a complete picture of your deployment's health.

### Setting Up Alerts

Metrics become actionable when you alert on problematic conditions. Prometheus's Alertmanager handles this through alert rules you define in Prometheus configuration:

```yaml
groups:
  - name: ojp_alerts
    interval: 30s
    rules:
      - alert: OJPHighErrorRate
        expr: |
          (sum(rate(grpc_server_handled_total{grpc_code!="OK",job="ojp-server"}[5m]))
          /
          sum(rate(grpc_server_handled_total{job="ojp-server"}[5m]))) > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "OJP Server high error rate"
          description: "OJP error rate is {{ $value | humanizePercentage }} (threshold: 5%)"
```

This alert fires when more than 5% of requests fail for 2 consecutive minutes. The `for` clause prevents flapping—temporary spikes won't trigger the alert unless they persist.

Consider alerting on these conditions:
- Error rate exceeding acceptable thresholds (typically 1-5% depending on your SLAs)
- Latency percentiles degrading beyond normal ranges
- Connection pool exhaustion (available connections dropping to zero)
- Server instance unavailability (scrape failures from Prometheus)
- Memory pressure approaching configured limits

**[AI Image Prompt: Create an alert notification mockup showing a Slack or PagerDuty alert for an OJP issue. Display alert details: severity (warning/critical), metric that triggered (high error rate), current value vs threshold, affected OJP server instances, time period, and a link to Grafana dashboard. Include a graph thumbnail showing the metric spike. Use realistic alert formatting with proper color coding (yellow for warning, red for critical). Style: Modern alert notification, professional UI, clear information hierarchy.]**

## 13.5 Production Monitoring Best Practices

Effective monitoring requires more than just collecting metrics—you need a strategy for making those metrics actionable. Let's explore patterns that work well in production OJP deployments.

### Metric Aggregation Strategies

When running multiple OJP Server instances, you need to aggregate metrics intelligently. Some metrics should be summed across all instances—total request count, for example, makes sense as an aggregate. Other metrics work better as averages or percentiles—latency measurements typically aggregate this way.

Use Prometheus's aggregation functions appropriately. For request counts and error counts, sum across all instances:

```promql
sum(rate(grpc_server_started_total{job="ojp-server"}[1m]))
```

For latency and other percentile metrics, calculate percentiles from the already-aggregated histogram data:

```promql
histogram_quantile(0.95,
  sum(rate(grpc_server_handling_seconds_bucket{job="ojp-server"}[1m])) by (le)
)
```

Be careful with averages of averages—they can mislead. If one OJP instance handles much more traffic than others, simple averaging gives equal weight to all instances even though they don't contribute equally. Weight your averages by request volume when this matters.

### Capacity Planning with Metrics

Metrics data helps you plan for future capacity needs. Track these trends over time:

Request rate growth shows how quickly your traffic increases. Plot this over weeks or months to identify seasonal patterns and long-term trends. A steadily growing request rate suggests you'll need to scale OJP eventually.

Connection pool utilization reveals how close you are to saturation. If utilization regularly exceeds 80-90%, you're at risk of connection exhaustion during traffic spikes. Scale your connection pools or add more OJP instances before you hit this threshold.

Resource utilization at the host level (CPU, memory, network) indicates when your servers approach capacity. OJP is relatively lightweight, but heavy traffic can push CPU usage up. Monitor these metrics alongside OJP-specific metrics to get a complete picture.

### Correlating OJP Metrics with Application Performance

OJP sits between your applications and databases, so problems in either layer affect OJP's metrics. Learning to correlate metrics across these layers helps you diagnose issues faster.

When application response times degrade, check OJP latency metrics first. If OJP latency is normal but applications are slow, the problem likely lies in application code or non-database dependencies. If OJP latency spikes, investigate further down the stack.

Database performance directly impacts OJP. Slow database queries increase OJP's connection hold times and can cause connection pool exhaustion. When OJP metrics show connection pool pressure, query your database's slow query log to identify problematic queries.

Network issues between applications and OJP manifest as increased gRPC call latency or timeout errors. Network problems between OJP and databases show up as database connection errors or query timeouts. The error messages and specific metrics affected help you pinpoint the network segment having issues.

```mermaid
graph LR
    subgraph "Monitoring Correlation"
        A[Application<br/>Metrics]
        O[OJP<br/>Metrics]
        D[Database<br/>Metrics]
    end
    
    A -->|Slow response<br/>but normal OJP?| A1[App Code<br/>Issue]
    A -->|Slow response<br/>+ high OJP latency?| O
    O -->|Check connection<br/>pool + latency| O1{OJP<br/>Issue?}
    O1 -->|Yes| O2[Scale/Tune<br/>OJP]
    O1 -->|No| D
    D -->|Check slow<br/>query log| D1[Database<br/>Issue]
    
    style A fill:#e1f5ff,stroke:#333
    style O fill:#ffe1e1,stroke:#333
    style D fill:#e1ffe1,stroke:#333
```

## 13.5 Connection Pool Metrics

Understanding how your connection pools behave is crucial for maintaining optimal database performance. OJP exposes comprehensive metrics for all supported pool types—XA pools (Commons Pool 2), HikariCP, and DBCP—providing visibility into pool health, utilization, and performance.

### Enabling Pool Metrics

Pool metrics collection is controlled independently from gRPC metrics through the `ojp.telemetry.pool.metrics.enabled` configuration flag. This separation allows you to enable pool monitoring without collecting gRPC telemetry, or vice versa:

```bash
# Enable telemetry infrastructure
-Dojp.telemetry.enabled=true

# Control specific metric categories
-Dojp.telemetry.grpc.metrics.enabled=true     # gRPC server metrics (default: true)
-Dojp.telemetry.pool.metrics.enabled=true     # Pool metrics (default: true)

# Prometheus configuration
-Dojp.prometheus.port=9159
-Dojp.prometheus.allowedIps=127.0.0.1,10.0.0.0/8
```

When pool metrics are enabled, OJP automatically registers metrics collectors for all connection pools created by the server. Each pool's metrics are tagged with its configured pool name, allowing you to monitor multiple pools independently.

### XA Pool Metrics (Commons Pool 2)

XA pools expose 12 metrics covering pool state, performance, and health:

**Gauge Metrics** (current state):
- `ojp.xa.pool.connections.active` - Currently borrowed connections
- `ojp.xa.pool.connections.idle` - Available idle connections  
- `ojp.xa.pool.connections.pending` - Threads waiting for connections
- `ojp.xa.pool.connections.max` - Maximum pool size
- `ojp.xa.pool.connections.min` - Minimum idle connections
- `ojp.xa.pool.connections.utilization` - Pool utilization percentage (0-100)
- `ojp.xa.pool.connections.size` - Total connections (active + idle)
- `ojp.xa.pool.connections.opened` - Total connections created since pool start
- `ojp.xa.pool.connections.destroyed` - Total connections destroyed since pool start

**Counter Metrics** (cumulative events):
- `ojp.xa.pool.connections.exhausted` - Pool exhaustion events
- `ojp.xa.pool.connections.validation.failed` - Connection validation failures
- `ojp.xa.pool.connections.leaks.detected` - Connection leaks detected

**Histogram Metrics** (distribution):
- `ojp.xa.pool.connections.acquisition.time` - Connection acquisition time distribution (milliseconds). Exposes `_bucket`, `_count`, and `_sum` in Prometheus, enabling p50/p95/p99 percentile analysis.

> **Note on metric naming:** Prometheus reserves the suffixes `.total` and `.created` for counters. To avoid name collisions in the Prometheus exposition format, these gauges use the suffixes `.size` and `.opened` respectively.

### HikariCP Pool Metrics

HikariCP pools expose 7 core metrics using HikariCP's native MetricsTrackerFactory integration:

**Gauge Metrics**:
- `ojp.hikari.pool.connections.active` - Connections currently in use
- `ojp.hikari.pool.connections.idle` - Idle connections in pool
- `ojp.hikari.pool.connections.total` - Total connections (active + idle)
- `ojp.hikari.pool.connections.pending` - Threads waiting for connections
- `ojp.hikari.pool.connections.max` - Maximum pool size
- `ojp.hikari.pool.connections.min` - Minimum idle connections

**Histogram Metrics** (distribution):
- `ojp.hikari.pool.connections.acquisition.time` - Connection acquisition time distribution (milliseconds). Exposes `_bucket`, `_count`, and `_sum` in Prometheus, enabling p50/p95/p99 percentile analysis of how long applications wait to obtain a pooled connection.

The HikariCP implementation leverages HikariCP's built-in PoolStats callback mechanism and MetricsTrackerFactory integration, ensuring metrics accurately reflect the pool's internal state.

### Monitoring Pool Health

Several key patterns indicate pool health issues:

**High Utilization** - When `utilization` consistently exceeds 80-90%, the pool is nearing capacity. Consider increasing the maximum pool size or optimizing connection usage patterns in your application code.

**Pending Threads** - Any non-zero value in the `pending` metric indicates contention. Threads are waiting for connections, suggesting the pool can't keep up with demand. Sustained high values warrant immediate investigation.

**Pool Exhaustion** - The `exhausted` counter incrementing means applications are timing out waiting for connections. This is a critical condition requiring immediate action—either increase the pool size, reduce connection hold time, or add more database capacity.

**Connection Leaks** - The `leaks.detected` counter should always be zero. Any detected leaks indicate application bugs where connections aren't being properly closed. Use the housekeeping logs to identify which application connections are leaking.

**Validation Failures** - Occasional validation failures are normal (network blips, database restarts). Frequent failures suggest connectivity problems or database health issues that need investigation.

### Grafana Dashboard Example

Create focused dashboards for pool monitoring with these key panels:

**Pool Overview Panel** - Time series showing active and idle connections over time. This gives you a quick visual of pool behavior and identifies unusual patterns.

**Utilization Gauge** - Display current pool utilization percentage with color thresholds: green (0-70%), yellow (70-85%), red (>85%). This provides at-a-glance health status.

**Performance Metrics** - Graph p95 connection acquisition time using the histogram. Increasing acquisition latency indicates pool contention or database latency.

**Health Indicators** - Stats showing pending threads, exhaustion events, and leak detections. These should typically be zero or very low.

Example PromQL queries for your dashboards:

```promql
# Pool utilization percentage
ojp_xa_pool_connections_utilization{pool_name="my-pool"}

# p95 XA connection acquisition time (milliseconds)
histogram_quantile(0.95,
  sum(rate(ojp_xa_pool_connections_acquisition_time_milliseconds_bucket[5m]))
  by (le, pool_name)
)

# p95 HikariCP connection acquisition time (milliseconds)
histogram_quantile(0.95,
  sum(rate(ojp_hikari_pool_connections_acquisition_time_milliseconds_bucket[5m]))
  by (le, pool_name)
)

# Connection exhaustion rate (events per second)
rate(ojp_xa_pool_connections_exhausted_total[5m])

# Active connections across all HikariCP pools
sum by (pool_name) (ojp_hikari_pool_connections_active)

# p95 SQL execution time per statement (milliseconds) – works for both XA and non-XA
histogram_quantile(0.95,
  sum(rate(ojp_sql_execution_time_milliseconds_bucket[5m]))
  by (le, sql_statement)
)
```

### Alerting on Pool Issues

Configure alerts for critical pool conditions:

```yaml
groups:
  - name: ojp_pool_alerts
    rules:
      - alert: PoolHighUtilization
        expr: ojp_xa_pool_connections_utilization > 85
        for: 5m
        annotations:
          summary: "Pool {{ $labels.pool_name }} utilization is {{ $value }}%"
      
      - alert: PoolExhaustion
        expr: increase(ojp_xa_pool_connections_exhausted_total[5m]) > 0
        annotations:
          summary: "Pool {{ $labels.pool_name }} exhausted {{ $value }} times"
      
      - alert: ConnectionLeak
        expr: increase(ojp_xa_pool_connections_leaks_detected_total[5m]) > 0
        annotations:
          summary: "Connection leak detected in {{ $labels.pool_name }}"
      
      - alert: PoolContention
        expr: ojp_xa_pool_connections_pending > 5
        for: 2m
        annotations:
          summary: "{{ $value }} threads waiting in {{ $labels.pool_name }}"
      
      - alert: SlowSqlDetected
        expr: |
          histogram_quantile(0.95,
            sum(rate(ojp_sql_execution_time_milliseconds_bucket[5m]))
            by (le, sql_statement)
          ) > 1000
        for: 5m
        annotations:
          summary: "p95 SQL execution time for '{{ $labels.sql_statement }}' is {{ $value }}ms"
```

For detailed information about all available pool metrics, configuration options, and monitoring best practices, see the `METRICS.md` documentation in the `ojp-xa-pool-commons` module.

### Security and Access Control

Production monitoring systems often contain sensitive operational data. Protect your OJP metrics appropriately.

The `ojp.prometheus.allowedIps` configuration restricts which IP addresses can scrape metrics. In production, set this to your monitoring infrastructure's IP ranges. Never leave it at the default `0.0.0.0/0` in production environments.

Consider using network policies in Kubernetes to further restrict access. Create a NetworkPolicy that allows only your Prometheus pods to access OJP's metrics port:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ojp-metrics-access
spec:
  podSelector:
    matchLabels:
      app: ojp-server
  ingress:
    - from:
      - podSelector:
          matchLabels:
            app: prometheus
      ports:
      - protocol: TCP
        port: 9159
```

If your organization requires it, you can place an authentication proxy in front of OJP's metrics endpoint. Tools like oauth2-proxy or Vouch work well for this purpose, adding OAuth2 authentication before requests reach OJP's metrics endpoint.

### Monitoring Overhead Considerations

Telemetry collection itself consumes resources. While OJP's OpenTelemetry implementation is efficient, heavy metrics collection can impact performance in extreme scenarios.

The scrape interval affects both data granularity and overhead. A 5-second interval provides good resolution for most use cases. Scraping every second gives you finer granularity but quadruples the overhead. Scraping every 15 seconds reduces overhead but might miss short-lived issues.

If you observe performance degradation correlated with metrics collection, consider these mitigations:
- Increase the scrape interval to reduce collection frequency
- Filter metrics at the Prometheus level to drop unnecessary ones
- If running many OJP instances, shard your Prometheus servers to distribute the scraping load

In practice, OJP's metrics overhead is minimal—typically well under 1% of CPU time. You're much more likely to hit capacity limits from actual request processing than from metrics collection.

**[AI Image Prompt: Create an isometric illustration showing monitoring architecture for a production OJP deployment. Display multiple layers: bottom layer with database servers, middle layer with a cluster of OJP Server instances (3-5 servers), top layer with monitoring stack (Prometheus, Grafana, Alert Manager). Show data flow arrows from OJP servers being scraped by Prometheus, feeding into Grafana dashboards, and triggering alerts. Include network security elements like firewalls and IP whitelisting symbols. Use modern isometric style, subtle gradients, professional color scheme. Style: Technical isometric illustration, clean, informative.]**

## 13.6 Understanding Available Metrics

OJP exposes dozens of metrics through its Prometheus endpoint. Understanding what each metric tells you helps you build effective monitoring dashboards and alerts. Let's explore the key metric categories.

### gRPC Server Metrics

These metrics track OJP's gRPC API—the interface applications use to communicate with the proxy.

`grpc_server_started_total` counts every RPC call OJP receives. It's labeled by gRPC method (like `ExecuteQuery`, `ExecuteUpdate`, `GetConnection`) and service. This metric helps you understand request volume and identify which database operations dominate your workload.

`grpc_server_handled_total` counts completed RPCs, labeled with response codes. The `grpc_code` label indicates success (`OK`) or various error conditions (`INVALID_ARGUMENT`, `UNAVAILABLE`, etc.). Monitoring this metric shows your error rate and helps you identify problematic API calls.

`grpc_server_handling_seconds` provides latency histograms for gRPC calls. The histogram buckets let you calculate percentiles—p50, p95, p99—which reveal performance characteristics better than simple averages. Use this to understand how responsive OJP is from the application's perspective.

`grpc_server_msg_received_total` and `grpc_server_msg_sent_total` track message volume. Large message counts relative to RPC counts might indicate applications sending batch operations or receiving large result sets.

### Connection Pool Metrics

Connection pool behavior directly impacts how well OJP performs. These metrics help you understand pool utilization and identify capacity issues.

`hikaricp_connections_active` shows how many connections currently execute queries. This should stay well below your maximum pool size under normal load. Sustained high values (>80% of max) suggest you need more database connections or fewer long-running queries.

`hikaricp_connections_idle` indicates available connections ready for use. This should typically be positive. If it drops to zero, you've hit connection pool exhaustion—new requests must wait for connections to become available.

`hikaricp_connections_pending` counts requests waiting for connections. Any non-zero value here indicates connection pool saturation. Persistent pending connection counts signal a need to scale your pool or reduce query execution times.

`hikaricp_connections_timeout_total` tracks how many connection acquisition requests timed out. Timeouts indicate severe connection pool pressure and directly impact application performance.

`hikaricp_connections_usage_seconds` provides latency histograms for connection acquisition. This tells you how long applications wait to get a database connection from OJP's pool.

### SQL Execution Metrics

OJP records per-statement SQL execution metrics for every query that flows through the proxy—for both standard (non-XA) and XA connections. These metrics are emitted under the `ojp.sql` scope and are labeled with the actual SQL statement text.

`ojp.sql.execution.time` is a histogram recording execution time in milliseconds for each SQL statement. In Prometheus, this becomes:
- `ojp_sql_execution_time_milliseconds_bucket{sql_statement="...", le="..."}` — histogram bucket counts, enabling p50/p95/p99 analysis
- `ojp_sql_execution_time_milliseconds_count{sql_statement="..."}` — total number of executions
- `ojp_sql_execution_time_milliseconds_sum{sql_statement="..."}` — cumulative execution time

`ojp.sql.executions` counts every SQL execution, labeled by statement text. Use this to understand which statements are most frequently executed.

`ojp.sql.slow.executions` counts executions classified as "slow"—those whose average execution time is 2× or more than the overall average. Combined with `ojp.sql.executions`, this gives you a slow-execution ratio per statement.

Example output from the Prometheus endpoint:

```
# HELP ojp_sql_execution_time_milliseconds SQL execution time histogram
# TYPE ojp_sql_execution_time_milliseconds histogram
ojp_sql_execution_time_milliseconds_bucket{otel_scope_name="ojp.sql",sql_statement="SELECT * FROM orders WHERE id = ?",le="5.0"} 0
ojp_sql_execution_time_milliseconds_bucket{otel_scope_name="ojp.sql",sql_statement="SELECT * FROM orders WHERE id = ?",le="10.0"} 12
ojp_sql_execution_time_milliseconds_bucket{otel_scope_name="ojp.sql",sql_statement="SELECT * FROM orders WHERE id = ?",le="+Inf"} 15
ojp_sql_execution_time_milliseconds_count{otel_scope_name="ojp.sql",sql_statement="SELECT * FROM orders WHERE id = ?"} 15
ojp_sql_execution_time_milliseconds_sum{otel_scope_name="ojp.sql",sql_statement="SELECT * FROM orders WHERE id = ?"} 127.4
```

> **XA and non-XA parity:** SQL execution metrics are emitted identically for both XA and non-XA connections. You can use the same PromQL queries regardless of the transaction model your application uses.

Example PromQL queries for SQL execution dashboards:

```promql
# p95 execution time per SQL statement
histogram_quantile(0.95,
  sum(rate(ojp_sql_execution_time_milliseconds_bucket[5m]))
  by (le, sql_statement)
)

# Average execution time per SQL statement (ms)
rate(ojp_sql_execution_time_milliseconds_sum[5m])
/
rate(ojp_sql_execution_time_milliseconds_count[5m])

# Top 10 most-executed statements
topk(10,
  sum(rate(ojp_sql_executions_total[5m])) by (sql_statement)
)

# Slow execution ratio per statement
rate(ojp_sql_slow_executions_total[5m])
/
rate(ojp_sql_executions_total[5m])
```

### Server Health Metrics

These metrics indicate OJP's overall operational health.

JVM memory metrics (`jvm_memory_bytes_used`, `jvm_memory_bytes_max`) show memory consumption. Watch for memory usage approaching the maximum—this can trigger garbage collection storms that degrade performance.

Thread pool metrics reveal how effectively OJP processes concurrent requests. If all worker threads are busy, additional requests queue up, increasing latency.

Process metrics like CPU usage and file descriptor counts help you understand resource consumption at the OS level. Running out of file descriptors is a common issue in high-connection environments.

**[AI Image Prompt: Create an infographic categorizing OJP's Prometheus metrics into 4 categories: 1) gRPC Server Metrics (request counts, latency, errors), 2) Connection Pool Metrics (active, idle, pending connections, acquisition time histograms), 3) SQL Execution Metrics (per-statement execution time histograms, slow-query counts), and 4) Server Health Metrics (JVM memory, threads, CPU, file descriptors). For each category, show 2-3 key metric names with example values and simple sparkline graphs. Use icons to represent each category (API for gRPC, pool for connections, database/clock for SQL, heartbeat for health). Style: Educational infographic, clean layout, modern flat design, professional colors.]**

## 13.7 Troubleshooting Telemetry Issues

Sometimes telemetry doesn't work as expected. Let's walk through common issues and their solutions.

### Metrics Endpoint Not Accessible

If you can't reach the metrics endpoint, start with the basics. Verify OJP Server is actually running and healthy. Check that the metrics port (default 9159) isn't blocked by firewall rules or security groups. Test connectivity from your Prometheus server to the OJP metrics endpoint using curl or telnet.

If you can reach the endpoint from the OJP server itself but not from Prometheus, you likely have a network issue. Check that the `ojp.prometheus.allowedIps` configuration includes your Prometheus server's IP address. Network policies in Kubernetes might also block access—verify your NetworkPolicy allows traffic from Prometheus to OJP's metrics port.

### Metrics Not Showing in Prometheus

When the endpoint is accessible but Prometheus doesn't show metrics, the issue usually lies in Prometheus configuration. Check Prometheus's targets page (`/targets` endpoint) to see if it's successfully scraping OJP. A red status indicates scrape failures.

Common configuration issues include incorrect target addresses (check the hostname or IP), wrong metrics path (should be `/metrics`), or SSL/TLS configuration mismatches if you've enabled HTTPS for the metrics endpoint.

Look at Prometheus logs for scrape errors. They'll often reveal exactly what's wrong—DNS resolution failures, connection timeouts, or HTTP error responses.

### Missing or Incomplete Metrics

Sometimes Prometheus scrapes OJP successfully but certain metrics are missing. This usually happens when the feature generating those metrics hasn't activated yet.

Connection pool metrics only appear after OJP creates at least one connection pool. If you're monitoring a fresh OJP instance that hasn't received any requests yet, pool metrics won't exist.

Some metrics are counters that start at zero. They won't be visible in queries until they increment at least once. This is normal Prometheus behavior—counters don't appear in the metrics output until they have a non-zero value.

### Performance Impact from Metrics Collection

If you notice performance degradation correlated with metrics collection, check these areas. Verify your scrape interval isn't too aggressive—scraping every second usually provides more granularity than necessary and increases overhead proportionally.

Check Prometheus's series cardinality. If you have many OJP instances or high-cardinality labels (like query patterns), the sheer number of time series might overwhelm Prometheus. Use PromQL aggregations to reduce cardinality before storing data.

In extreme cases, consider using remote write to push metrics to a more scalable backend like Thanos or Cortex. These systems handle much higher metric volumes than a single Prometheus instance.

### Debugging with Logs

When troubleshooting telemetry issues, enable debug logging for OpenTelemetry in OJP. Add this to your server startup:

```bash
-Dojp.log.level=DEBUG
```

This increases log verbosity and shows exactly what OpenTelemetry is doing—initializing exporters, creating metric instruments, and reporting values. The logs often reveal configuration issues or errors that aren't obvious from external observation.

## 13.8 Roadmap: Future Telemetry Capabilities

Understanding OJP's telemetry roadmap helps you plan your monitoring strategy for the future.

### SQL-Level Instrumentation (Available Now)

OJP provides full SQL-level metrics today. Every query flowing through the proxy—for both standard and XA connections—is instrumented with per-statement execution time histograms, execution counts, and slow-query classification. See section 13.6 "SQL Execution Metrics" for the complete reference and example PromQL queries.

### Custom Metrics

Application-specific metrics could flow through OJP's telemetry system in future releases. Imagine adding business-level metrics (like "orders processed" or "user registrations") that get exported alongside OJP's operational metrics. This would provide a unified view of both technical and business KPIs.

**[AI Image Prompt: Create a roadmap timeline visualization showing OJP's telemetry evolution. Display current state (Prometheus metrics + Distributed Tracing via Zipkin/OTLP + SQL Execution Histograms) and future phases: 1) Advanced Features (custom metrics, enhanced multinode observability). For each phase, show a representative icon and key capabilities. Use a horizontal timeline with milestone markers. Style: Roadmap infographic, modern design, professional color scheme with gradient elements, clear information hierarchy.]**

---

This chapter covered OJP's observability capabilities comprehensively. You learned how OpenTelemetry integration provides metrics through Prometheus and distributed traces through Zipkin and OTLP backends, how to set up monitoring infrastructure with Grafana, and how to interpret the metrics OJP exposes. Both metrics and distributed tracing are available today, giving you comprehensive operational visibility into your proxy. SQL execution time histograms give you per-statement p50/p95/p99 latency data for all connections—XA and non-XA alike—without any application-side instrumentation.

In the next chapter, we'll stay on operations and focus on practical production deployment guidance—[Chapter 13a: Running OJP Server in Production](part4-chapter13a-running-ojp-server-in-production.md).
