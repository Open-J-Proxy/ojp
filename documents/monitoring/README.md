# OJP Monitoring and Production Deployment

This directory contains resources for monitoring and deploying OJP in production environments.

## Contents

### Production Deployment Guide
- **[PRODUCTION_DEPLOYMENT_GUIDE.md](PRODUCTION_DEPLOYMENT_GUIDE.md)** - Comprehensive guide for deploying OJP to production environments with a 3-phase rollout strategy (Pilot → Limited → General).

### Monitoring Dashboards
- **[grafana-dashboard.json](grafana-dashboard.json)** - Grafana dashboard configuration for monitoring OJP cache performance with 11 visualization panels including:
  - Cache hit/miss rates
  - Query execution times
  - Cache size and entry metrics
  - Invalidation and eviction rates
  - Performance by data source

## Using the Grafana Dashboard

To import the dashboard into your Grafana instance:

1. Open Grafana and navigate to **Dashboards → Import**
2. Upload the `grafana-dashboard.json` file or paste its contents
3. Configure your Prometheus data source
4. The dashboard will automatically start displaying OJP cache metrics

## Related Documentation

- **[Cache User Guide](../guides/CACHE_USER_GUIDE.md)** - How to configure and use OJP caching features
- **[Caching Implementation Analysis](../analysis/CACHING_IMPLEMENTATION_ANALYSIS.md)** - Technical analysis and design decisions
- **[ADR-008](../ADRs/adr-008-use-caffeine-for-caching.md)** - Architecture Decision Record for the caching library
- **[Query flow](../designs/EXECUTE_QUERY_FLOW.md)** - Locate capacity checks, execution, and streaming before interpreting metrics
- **[Documentation hub](../README.md)** - Other operation flows, configuration references, and production guides
