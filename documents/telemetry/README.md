# Telemetry Documentation

OJP (Open J Proxy) provides observability features to monitor database operations through its telemetry system.

## Supported Telemetry Features

### Metrics via Prometheus Exporter
OJP exposes operational metrics through a Prometheus-compatible endpoint, providing insights into:
- gRPC communication metrics (request counts, latency, errors)
- Server operational metrics
- Connection pool metrics (XA pools and HikariCP) including connection acquisition time histograms
- SQL execution time histograms per statement (for both XA and non-XA connections)
- Connection and session information
- Circuit breaker state, trips, blocked calls, and recovery timing

### Distributed Tracing via OpenTelemetry
OJP supports distributed tracing using the OpenTelemetry SDK. Traces are automatically emitted for all gRPC calls handled by the server and can be sent to any compatible backend.

**Supported exporters:**
- **Zipkin** (default) — compatible with Zipkin and any Zipkin-compatible backend
- **OTLP** — compatible with Jaeger, Grafana Tempo, OpenTelemetry Collector, and cloud-native APM tools

Tracing is **disabled by default** and must be explicitly enabled via configuration.

## Accessing Telemetry Data

### Prometheus Metrics
Metrics are exposed via HTTP endpoint and can be scraped by Prometheus:
- **Default endpoint**: `http://localhost:9159/metrics`
- **Format**: Prometheus text-based exposition format
- **Update frequency**: Real-time metrics updated on each operation

To access metrics:
1. Configure Prometheus to scrape the OJP server metrics endpoint
2. Set up Grafana dashboards to visualize the metrics
3. Create alerts based on server performance and error thresholds

### Distributed Traces
Traces are pushed by OJP to the configured exporter endpoint:
- **Zipkin default endpoint**: `http://localhost:9411/api/v2/spans`
- **OTLP default endpoint**: `http://localhost:4317` (gRPC)

Each trace includes span attributes such as gRPC method, status code, and service name.

## Circuit Breaker Metrics

When `ojp.telemetry.enabled=true`, OJP emits circuit breaker metrics through the same Prometheus endpoint. These metrics are labeled with `query_hash` and `datasource`; transition and trip metrics include additional labels noted below.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `ojp.circuit_breaker.state` | Gauge | `query_hash`, `datasource` | Current circuit state: `0` closed, `1` open, `2` half-open |
| `ojp.circuit_breaker.transitions.total` | Counter | `query_hash`, `datasource`, `from_state`, `to_state` | Number of state transitions |
| `ojp.circuit_breaker.trips.total` | Counter | `query_hash`, `datasource`, `reason` | Number of times a circuit opens due to failure conditions |
| `ojp.circuit_breaker.open_duration.seconds` | Histogram | `query_hash`, `datasource` | Time spent in open state before recovery is attempted |
| `ojp.circuit_breaker.blocked_calls.total` | Counter | `query_hash`, `datasource` | Requests rejected because the circuit is open |

Prometheus converts OpenTelemetry metric names to Prometheus format by replacing dots with underscores. For example, `ojp.circuit_breaker.trips.total` is queried as `ojp_circuit_breaker_trips_total`.

### Example Prometheus Queries

Circuits currently open:
```promql
ojp_circuit_breaker_state == 1
```

Circuit trips by datasource over five minutes:
```promql
sum by (datasource) (increase(ojp_circuit_breaker_trips_total[5m]))
```

Blocked calls by query over five minutes:
```promql
sum by (datasource, query_hash) (increase(ojp_circuit_breaker_blocked_calls_total[5m]))
```

95th percentile open duration:
```promql
histogram_quantile(
  0.95,
  sum by (le, datasource) (rate(ojp_circuit_breaker_open_duration_seconds_bucket[5m]))
)
```

State transitions by direction:
```promql
sum by (from_state, to_state) (increase(ojp_circuit_breaker_transitions_total[5m]))
```

### Grafana Panel Snippets

Current open circuits:
```json
{
  "title": "Open circuits",
  "type": "stat",
  "targets": [
    {
      "expr": "count(ojp_circuit_breaker_state == 1)",
      "legendFormat": "open"
    }
  ]
}
```

Circuit trips by datasource:
```json
{
  "title": "Circuit trips by datasource",
  "type": "timeseries",
  "targets": [
    {
      "expr": "sum by (datasource) (increase(ojp_circuit_breaker_trips_total[5m]))",
      "legendFormat": "{{datasource}}"
    }
  ]
}
```

## Configuration Options

The telemetry system can be configured through JVM system properties or environment variables. JVM properties take precedence over environment variables.

### Metrics Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.telemetry.enabled` | `OJP_TELEMETRY_ENABLED` | `true` | Master switch: Enable/disable OpenTelemetry infrastructure (Prometheus server, MeterProvider, TracerProvider) |
| `ojp.prometheus.port` | `OJP_PROMETHEUS_PORT` | `9159` | Port for Prometheus metrics HTTP server |
| `ojp.prometheus.allowedIps` | `OJP_PROMETHEUS_ALLOWED_IPS` | `0.0.0.0/0` | Comma-separated list of allowed IP addresses/CIDR blocks for metrics endpoint |
| `ojp.telemetry.circuitbreaker.enabled` | `OJP_TELEMETRY_CIRCUITBREAKER_ENABLED` | `true` | Enable/disable circuit breaker metrics while keeping other telemetry enabled |

### Tracing Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.tracing.enabled` | `OJP_TRACING_ENABLED` | `false` | Enable/disable distributed tracing (disabled by default) |
| `ojp.tracing.exporter` | `OJP_TRACING_EXPORTER` | `zipkin` | Trace exporter type: `zipkin` or `otlp` |
| `ojp.tracing.endpoint` | `OJP_TRACING_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Exporter endpoint URL |
| `ojp.tracing.serviceName` | `OJP_TRACING_SERVICENAME` | `ojp-server` | Service name attached to all spans |
| `ojp.tracing.sampleRate` | `OJP_TRACING_SAMPLERATE` | `1.0` | Fraction of requests to sample (0.0–1.0) |

### Configuration Examples

**Enable Zipkin tracing (JVM properties):**
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://zipkin:9411/api/v2/spans \
  -Dojp.tracing.serviceName=my-ojp-server
```

**Enable OTLP tracing (e.g. Jaeger, Grafana Tempo):**
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://jaeger:4317 \
  -Dojp.tracing.sampleRate=0.1
```

**Using Environment Variables:**
```bash
export OJP_TELEMETRY_ENABLED=true
export OJP_PROMETHEUS_PORT=9159
export OJP_PROMETHEUS_ALLOWED_IPS=127.0.0.1,10.0.0.0/8
export OJP_TELEMETRY_CIRCUITBREAKER_ENABLED=false
export OJP_TRACING_ENABLED=true
export OJP_TRACING_ENDPOINT=http://zipkin:9411/api/v2/spans
java -Duser.timezone=UTC -jar ojp-server.jar
```

## SQL Execution Metrics

OJP records per-statement SQL execution metrics for every query flowing through the proxy, for both standard and XA connections. Metrics are emitted under the `ojp.sql` OpenTelemetry meter scope.

| Metric | Type | Description |
|--------|------|-------------|
| `ojp.sql.execution.time` | Histogram | Execution time in ms per SQL statement. Exposes `_bucket`, `_count`, `_sum` in Prometheus. |
| `ojp.sql.executions` | Counter | Total number of executions per SQL statement. |
| `ojp.sql.slow.executions` | Counter | Executions classified as "slow" (≥2× overall average) per SQL statement. |

All three metrics carry an `sql.statement` label containing the actual SQL text (with parameters as `?` placeholders).

### Example Prometheus Output

```
ojp_sql_execution_time_milliseconds_bucket{otel_scope_name="ojp.sql",sql_statement="SELECT id FROM users WHERE email = ?",le="10.0"} 47
ojp_sql_execution_time_milliseconds_bucket{otel_scope_name="ojp.sql",sql_statement="SELECT id FROM users WHERE email = ?",le="+Inf"} 50
ojp_sql_execution_time_milliseconds_count{otel_scope_name="ojp.sql",sql_statement="SELECT id FROM users WHERE email = ?"} 50
ojp_sql_execution_time_milliseconds_sum{otel_scope_name="ojp.sql",sql_statement="SELECT id FROM users WHERE email = ?"} 312.5
```

### Example PromQL Queries

```promql
# p95 SQL execution time per statement
histogram_quantile(0.95,
  sum(rate(ojp_sql_execution_time_milliseconds_bucket[5m]))
  by (le, sql_statement)
)

# Slow execution ratio
rate(ojp_sql_slow_executions_total[5m])
/
rate(ojp_sql_executions_total[5m])
```

## Connection Pool Metrics

### XA Pool Metrics (`ojp.xa.pool` scope)

| Metric | Type | Description |
|--------|------|-------------|
| `ojp.xa.pool.connections.active` | Gauge | Currently borrowed connections |
| `ojp.xa.pool.connections.idle` | Gauge | Available idle connections |
| `ojp.xa.pool.connections.pending` | Gauge | Threads waiting for connections |
| `ojp.xa.pool.connections.max` | Gauge | Maximum pool size |
| `ojp.xa.pool.connections.min` | Gauge | Minimum idle connections |
| `ojp.xa.pool.connections.utilization` | Gauge | Pool utilization percentage (0–100) |
| `ojp.xa.pool.connections.size` | Gauge | Total connections (active + idle) |
| `ojp.xa.pool.connections.opened` | Gauge | Total connections created since pool start |
| `ojp.xa.pool.connections.destroyed` | Gauge | Total connections destroyed since pool start |
| `ojp.xa.pool.connections.exhausted` | Counter | Pool exhaustion events |
| `ojp.xa.pool.connections.validation.failed` | Counter | Connection validation failures |
| `ojp.xa.pool.connections.leaks.detected` | Counter | Connection leaks detected |
| `ojp.xa.pool.connections.acquisition.time` | Histogram | Connection acquisition latency (ms), enabling p50/p95/p99 analysis |

> **Note:** `.size` and `.opened` are used instead of `.total` and `.created` because Prometheus reserves those suffixes for counters.

### HikariCP Pool Metrics (`ojp.hikari.pool` scope)

| Metric | Type | Description |
|--------|------|-------------|
| `ojp.hikari.pool.connections.active` | Gauge | Connections currently in use |
| `ojp.hikari.pool.connections.idle` | Gauge | Idle connections in pool |
| `ojp.hikari.pool.connections.total` | Gauge | Total connections (active + idle) |
| `ojp.hikari.pool.connections.pending` | Gauge | Threads waiting for connections |
| `ojp.hikari.pool.connections.max` | Gauge | Maximum pool size |
| `ojp.hikari.pool.connections.min` | Gauge | Minimum idle connections |
| `ojp.hikari.pool.connections.acquisition.time` | Histogram | Connection acquisition latency (ms), enabling p50/p95/p99 analysis |



### With Prometheus and Grafana
1. Configure Prometheus to scrape OJP metrics:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ojp-server'
    static_configs:
      - targets: ['localhost:9159']
    metrics_path: '/metrics'
    scrape_interval: 5s
```

2. Import OJP metrics into Grafana by adding Prometheus as a data source
3. Create dashboards to visualize gRPC call metrics, error rates, and server performance
4. Set up alerts for server errors and performance degradation

### With Zipkin

Start a local Zipkin instance:
```bash
docker run -d -p 9411:9411 openzipkin/zipkin
```

Start OJP with tracing enabled:
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://localhost:9411/api/v2/spans
```

Open `http://localhost:9411` in your browser to view traces.

### With Jaeger via OTLP

Start a local Jaeger instance:
```bash
docker run -d \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Start OJP with OTLP tracing enabled:
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://localhost:4317
```

Open `http://localhost:16686` in your browser to view traces in Jaeger.

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
      OJP_TRACING_SERVICE_NAME: "ojp-server"

  zipkin:
    image: openzipkin/zipkin
    ports:
      - "9411:9411"
```

## Sampling

The `ojp.tracing.sampleRate` property controls what fraction of requests are traced:

| Value | Behaviour |
|-------|-----------|
| `1.0` | 100% of requests are traced (default when tracing is enabled) |
| `0.5` | 50% of requests are traced |
| `0.1` | 10% of requests are traced |
| `0.0` | No requests are traced (effectively disables tracing) |

For high-throughput production environments, a sample rate of `0.01` to `0.1` is recommended to reduce overhead.

## Best Practices

- **Default off**: Tracing is disabled by default to avoid any overhead until explicitly needed
- **Sampling**: In high-throughput production environments, set `ojp.tracing.sampleRate` to a value less than `1.0` (e.g. `0.1`) to reduce overhead
- **Security**: Ensure telemetry endpoints are properly secured in production environments using the IP whitelist feature
- **Performance**: Monitor the performance impact of telemetry collection on the proxy
- **Monitoring**: Set up alerts for server errors and unusual traffic patterns
