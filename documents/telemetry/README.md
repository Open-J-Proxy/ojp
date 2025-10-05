# Telemetry Documentation

OJP (Open J Proxy) provides observability features to monitor database operations through its telemetry system.

## Currently Supported Telemetry Features

### Metrics via Prometheus Exporter
OJP exposes operational metrics through a Prometheus-compatible endpoint, providing insights into:
- gRPC communication metrics (request counts, latency, errors)
- Server operational metrics
- Connection and session information

**Note**: OJP currently implements metrics collection via OpenTelemetry with Prometheus export. Distributed tracing export capabilities are not yet implemented.

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

## Configuration Options

The telemetry system can be configured through JVM system properties or environment variables. JVM properties take precedence over environment variables.

### Available Configuration Properties

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `ojp.opentelemetry.enabled` | `OJP_OPENTELEMETRY_ENABLED` | `true` | Enable/disable OpenTelemetry metrics collection |
| `ojp.prometheus.port` | `OJP_PROMETHEUS_PORT` | `9159` | Port for Prometheus metrics HTTP server |
| `ojp.prometheus.allowedIps` | `OJP_PROMETHEUS_ALLOWED_IPS` | `0.0.0.0/0` | Comma-separated list of allowed IP addresses/CIDR blocks for metrics endpoint |

### Configuration Examples

**Using JVM Properties:**
```bash
java -jar ojp-server.jar \
  -Dojp.opentelemetry.enabled=true \
  -Dojp.prometheus.port=9159 \
  -Dojp.prometheus.allowedIps=127.0.0.1,10.0.0.0/8
```

**Using Environment Variables:**
```bash
export OJP_OPENTELEMETRY_ENABLED=true
export OJP_PROMETHEUS_PORT=9159
export OJP_PROMETHEUS_ALLOWED_IPS=127.0.0.1,10.0.0.0/8
java -jar ojp-server.jar
```

## Integration Examples

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

## Limitations

**Current Limitations:**
- Distributed tracing export is not yet implemented
- Trace exporters for Zipkin, Jaeger, OTLP, and cloud providers are not available
- SQL-level tracing is not currently supported
- Only gRPC-level metrics and basic server metrics are collected

## Best Practices

- **Security**: Ensure telemetry endpoints are properly secured in production environments using the IP whitelist feature
- **Performance**: Monitor the performance impact of telemetry collection on the proxy
- **Monitoring**: Set up alerts for server errors and unusual traffic patterns
