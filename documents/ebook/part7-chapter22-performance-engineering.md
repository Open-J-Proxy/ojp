# Chapter 22: Performance Engineering and Capacity Planning

Understanding OJP's performance characteristics is essential for successful production deployments. This chapter provides comprehensive guidance on capacity planning, performance profiling, resource consumption patterns, and benchmarking methodologies to help you right-size your OJP infrastructure and meet your performance objectives.

**The contents of this chapter are merely guidance and must be adapted to each use case's specificities.**

## 22.1 Performance Fundamentals

OJP introduces a network hop between your application and the database, which fundamentally changes the performance profile compared to in-process connection pooling. Understanding these characteristics is critical for setting realistic expectations and making informed architectural decisions.

### Latency Overhead Profile

Every database operation through OJP incurs additional latency from network communication and gRPC serialization. The predicted typical overhead ranges from 1-3ms per operation, broken down as follows:
NOTE: The times shown below are predictions, not accurate measurements.

**Network Round-Trip Time (RTT)**:
- Same datacenter: 0.1-0.5ms (sub-millisecond)
- Same availability zone: 0.5-1.5ms
- Cross-AZ same region: 1-3ms
- Cross-region: 10-100ms+ (not recommended)

**gRPC Serialization/Deserialization**:
- Simple queries (no parameters): 0.1-0.3ms
- Parameterized queries (5-10 params): 0.3-0.6ms
- Complex queries (20+ params): 0.6-1.2ms

**OJP Server Processing**:
- Connection acquisition from pool: 0.05-0.2ms
- Statement preparation: 0.1-0.3ms
- Result set header processing: 0.1-0.2ms
- SQL Enhancer (when enabled): 0-1ms (cached) or 5-150ms (first time)

**Total Typical Overhead**: 1-3ms for cached paths, 5-150ms for cold paths with SQL Enhancer.

This overhead is acceptable for most OLTP workloads where query execution time dominates (10ms+), but becomes significant for:
- Ultra-low latency requirements (<5ms end-to-end)
- Chatty applications making hundreds of tiny queries per request
- Tight loops with single-row fetches

### Throughput Characteristics

OJP's throughput depends on multiple factors including hardware, network, configuration, and workload patterns. Typical performance profiles:

**Single OJP Server Instance**:
- Simple SELECT queries: 8,000-12,000 QPS
- INSERT/UPDATE operations: 6,000-10,000 QPS
- Mixed workload (70% SELECT, 30% writes): 7,000-11,000 QPS
- PreparedStatement-heavy workload: 10,000-15,000 QPS
- Large result sets (1000+ rows): 2,000-5,000 QPS

**Factors Affecting Throughput**:
- **CPU cores**: Linear scaling up to 8-16 cores, then diminishing returns
- **Network bandwidth**: 1 Gbps sufficient for most workloads, 10 Gbps for high throughput
- **Connection pool size**: Sweet spot typically 50-200 connections per database
- **gRPC worker threads**: Default 2x CPU cores works well for most cases
- **ResultSet fetch size**: Larger sizes improve throughput for big result sets

**Scalability Pattern**: Near-linear horizontal scaling with multiple OJP servers. A 3-node cluster typically achieves 2.7-2.9x the throughput of a single node due to minor coordination overhead.

### Memory Consumption Patterns

OJP's memory footprint consists of several components, each with different scaling characteristics:

**Base Server Memory** (per OJP server instance):
- JVM heap minimum: 512MB (development)
- JVM heap recommended: 2-4GB (production)
- JVM heap maximum: 8-16GB (high-throughput deployments)
- Native memory (off-heap): 100-500MB for gRPC buffers and thread stacks

**Per-Connection Memory** (scales with pool size):
- Connection metadata: ~2-5KB per connection
- Statement cache: 50-200KB per connection (if enabled)
- Prepared statement metadata: 1-10KB per prepared statement
- ResultSet buffers: 100KB-10MB depending on fetch size and row width

**Per-Active-Request Memory** (scales with concurrent requests):
- Request context: 5-20KB per active request
- Result set streaming buffer: 100KB-5MB per active large result set
- Parameter serialization: 1-50KB per request depending on parameter count

**SQL Enhancer Cache Memory** (when enabled):
- Cache entry: ~500 bytes overhead + SQL string length
- Parsed AST: 2-20KB per unique query
- Total cache size: Grows to working set, typically 10-100MB

**Typical Production Memory Profile**:
- Small deployment (50 connections, 100 QPS): 2GB heap
- Medium deployment (200 connections, 500 QPS): 4GB heap
- Large deployment (500 connections, 2000+ QPS): 8GB heap

### CPU Utilization Patterns

OJP's CPU consumption is primarily driven by serialization, deserialization, and context switching:

**CPU Breakdown by Component**:
- gRPC serialization/deserialization: 40-50%
- JDBC driver interaction: 20-30%
- Connection pool management: 10-15%
- SQL Enhancer (when enabled): 5-10%
- Telemetry and monitoring: 3-5%
- Housekeeping tasks: 1-2%

**CPU Scaling Characteristics**:
- **Light workload** (0-20% capacity): <10% CPU per core
- **Moderate workload** (20-60% capacity): 20-40% CPU per core
- **Heavy workload** (60-90% capacity): 50-80% CPU per core
- **Saturation** (>90% capacity): 90%+ CPU, increased latency variance

**CPU Recommendations**:
- Start with 4-8 cores for most deployments
- Add cores for >5,000 QPS sustained throughput
- Monitor CPU utilization targeting 40-60% average (peak ~80%)
- Consider vertical scaling before horizontal for CPU-bound workloads

## 22.2 Capacity Planning Framework

Proper capacity planning prevents both under-provisioning (performance degradation, outages) and over-provisioning (wasted resources). This framework guides you through the sizing process.

### Step 1: Workload Characterization

Begin by profiling your application's database access patterns:

**Query Volume Metrics**:
- Peak queries per second (QPS)
- Average QPS over 24 hours
- 95th and 99th percentile QPS
- Daily, weekly, monthly growth trends

**Query Complexity Distribution**:
- Simple SELECTs (1-2 tables, <10 rows): X%
- Complex SELECTs (3+ tables, JOINs, subqueries): Y%
- INSERTs/UPDATEs: Z%
- Batch operations: W%
- Large result sets (>1000 rows): V%

**Concurrency Patterns**:
- Peak concurrent connections from application layer
- Average connection hold time
- Connection churn rate (connections/sec opened and closed)
- Long-running query frequency

**Data Transfer Characteristics**:
- Average row count per query
- Average row size in bytes
- Total data transferred per hour/day
- Large BLOB/CLOB frequency and sizes

### Step 2: Resource Dimensioning

Use these formulas to estimate required resources:

**OJP Server Count**:
```
server_count = ceil(peak_qps / target_qps_per_server)
redundancy_multiplier = 1.5 to 2.0 (for HA)
final_server_count = server_count * redundancy_multiplier
```

Where `target_qps_per_server` = 8,000 for mixed workloads (conservative), 12,000 for simple SELECT-heavy workloads.

**CPU Cores per Server**:
```
cores = max(4, ceil(target_qps_per_server / 1500))
```

This formula assumes ~1500 QPS per core for moderate complexity queries.

**Memory per Server**:
```
base_heap = 2GB
per_connection = 5KB * total_pool_connections
per_concurrent_request = 20KB * peak_concurrent_requests
result_set_buffer = 2MB * peak_large_result_sets
total_heap = base_heap + per_connection + per_concurrent_request + result_set_buffer
recommended_heap = total_heap * 1.5 (headroom)
```

**Network Bandwidth**:
```
avg_request_size = 5KB (conservative estimate)
avg_response_size = 50KB (includes typical result set)
bandwidth_mbps = ((avg_request_size + avg_response_size) * peak_qps * 8) / 1_000_000
recommended_bandwidth = bandwidth_mbps * 2 (headroom)
```

### Step 3: Database Connection Pool Sizing

The connection pool size is critical for both performance and database protection:

**Pool Size Calculation**:
```
connections_per_server = peak_concurrent_requests * 1.2
total_pool_connections = connections_per_server * server_count
database_connection_limit = check with DBA
```

**Validation Rules**:
- Minimum: 20 connections per OJP server (avoid too few)
- Maximum: 200 connections per OJP server (avoid excessive context switching)
- Total across cluster: Not exceed database max_connections * 0.8

### Step 4: Validation and Load Testing

Before production deployment, validate your capacity plan:

**Load Test Scenarios**:
1. **Sustained Load**: Run at 60% of peak QPS for 1 hour
   - Validate: CPU <70%, memory stable, latency <5ms p99
2. **Peak Load**: Run at 100% of peak QPS for 15 minutes
   - Validate: CPU <85%, no memory growth, latency <10ms p99
3. **Spike Load**: Run at 150% of peak QPS for 5 minutes
   - Validate: Graceful degradation, recovery within 2 minutes
4. **Endurance**: Run at 40% of peak QPS for 24 hours
   - Validate: No memory leaks, stable performance

**Success Criteria**:
- P50 latency increase <2ms vs direct connection
- P99 latency increase <5ms vs direct connection
- Zero connection pool exhaustion events
- CPU utilization <70% at sustained load
- Memory utilization stable (no growth >5% over 24h)

## 22.3 Performance Profiling and Optimization

Once deployed, continuous profiling identifies bottlenecks and optimization opportunities.

### Profiling Tools and Techniques

**Key Metrics to Monitor**:
- Request latency histogram (p50, p95, p99, p999)
- Connection pool utilization and wait times
- Query execution time distribution
- Network I/O bytes in/out
- CPU and memory utilization trends
- GC pause frequency and duration

**JVM Profiling**:
```bash
# Enable JFR (Java Flight Recorder)
-XX:StartFlightRecording=duration=300s,filename=ojp-profile.jfr

# Analyze with jfr tool
jfr print --events jdk.ExecutionSample ojp-profile.jfr
```

**Thread Dump Analysis**:
```bash
# Capture thread dump
jstack <ojp-pid> > ojp-threads.txt

# Look for blocked threads
grep -A 5 "BLOCKED" ojp-threads.txt
```

### Common Performance Bottlenecks

**Bottleneck 1: Small fetchSize for Large Result Sets**

**Symptom**: High CPU, network round-trips for queries returning 1000+ rows.

**Detection**:
```sql
-- Check query performance logs
SELECT query, row_count, execution_time_ms 
FROM ojp_query_log 
WHERE row_count > 1000 AND execution_time_ms > 1000;
```

**Solution**: Increase fetchSize in application code:
```java
statement.setFetchSize(500);  // Fetch 500 rows per round-trip
```

**Impact**: 5-10x throughput improvement for large result sets.

---

**Bottleneck 2: Connection Pool Exhaustion**

**Symptom**: "Connection wait timeout" errors, increasing request latency.

**Detection**:
```
# Check pool statistics in logs
grep "PoolStatistics" ojp-server.log | tail -20

# Look for:
# - active connections = maxPoolSize
# - waiting threads > 0
```

**Solutions**:
1. **Increase pool size** (if database can handle it):
   ```properties
   ojp.connection.pool.maximumPoolSize=150  # was 100
   ```

2. **Reduce connection hold time** in application:
   ```java
   try (Connection conn = dataSource.getConnection()) {
       // Keep connection open for minimal time
       // Avoid: holding connection during external API calls
   }
   ```

3. **Enable leak detection** to find connection leaks:
   ```properties
   ojp.connection.pool.leakDetectionThreshold=30000  # 30 seconds
   ```

---

**Bottleneck 3: Excessive PreparedStatement Recreation**

**Symptom**: High CPU in statement parsing, low statement cache hit rate.

**Detection**:
```
# Check statement cache metrics
grep "StatementCacheStats" ojp-server.log

# Look for low hit rates (<70%)
```

**Solution**: Reuse PreparedStatements in application:
```java
// BAD: Creates new PreparedStatement each time
for (User user : users) {
    PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE user_id = ?");
    ps.setInt(1, user.getId());
    // ...
    ps.close();
}

// GOOD: Reuse PreparedStatement
PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE user_id = ?");
for (User user : users) {
    ps.setInt(1, user.getId());
    ResultSet rs = ps.executeQuery();
    // ...
}
ps.close();
```

**Impact**: 2-3x throughput improvement for statement-heavy workloads.

---

**Bottleneck 4: Unnecessary ResultSet Scrolling**

**Symptom**: High memory usage, slow result set traversal.

**Detection**: Memory profiler showing large ResultSet buffers.

**Solution**: Use forward-only result sets when possible:
```java
// Forward-only (efficient)
Statement stmt = conn.createStatement(
    ResultSet.TYPE_FORWARD_ONLY,
    ResultSet.CONCUR_READ_ONLY
);

// Avoid unless necessary
Statement stmt = conn.createStatement(
    ResultSet.TYPE_SCROLL_INSENSITIVE,  // Buffers entire result set
    ResultSet.CONCUR_UPDATABLE
);
```

---

**Bottleneck 5: Long GC Pauses**

**Symptom**: Periodic latency spikes (>100ms), high GC CPU time.

**Detection**:
```bash
# Analyze GC logs
-Xlog:gc*:file=gc.log:time,uptime,level,tags

# Look for:
# - Full GC frequency
# - Pause times >50ms
# - Old generation occupancy trending upward
```

**Solutions**:
1. **Tune heap size**:
   ```bash
   -Xms4g -Xmx4g  # Equal min/max to avoid resizing
   ```

2. **Use G1GC** (default in Java 11+):
   ```bash
   -XX:+UseG1GC
   -XX:MaxGCPauseMillis=50  # Target 50ms pauses
   ```

3. **Enable GC ergonomics**:
   ```bash
   -XX:+UseStringDeduplication  # Reduce string memory
   -XX:G1HeapRegionSize=16m     # Larger regions for big heaps
   ```

### Optimization Best Practices

**Application-Side Optimizations**:
1. **Do not add an application-side connection pool** when using OJP. OJP clients provide virtual connections; OJP manages the physical database connection pool on the server.
2. **Batch operations** to reduce round-trips: `PreparedStatement.addBatch()`
3. **Close resources** promptly with try-with-resources
4. **Set appropriate fetchSize** based on expected result set size
5. **Reuse PreparedStatements** for repeated queries

**OJP Server Optimizations**:
1. **Enable SQL Enhancer** for validation and caching:
   ```properties
   ojp.sql-enhancer.enabled=true
   ```
2. **Tune gRPC thread pool**:
   ```properties
   ojp.grpc.executor.core-pool-size=16  # 2x CPU cores
   ojp.grpc.executor.max-pool-size=32
   ```
3. **Configure pool aggressively**:
   ```properties
   ojp.connection.pool.maximumPoolSize=150
   ojp.connection.pool.minimumIdle=150
   ojp.connection.pool.connectionTimeout=5000  # Admission wait budget (ms)
   ojp.connection.pool.idleTimeout=300000      # 5 minutes
   ```
   Matching minimum and maximum sizes can reduce connection creation during
   traffic spikes. Use this for latency-sensitive workloads only when the
   database budget permits; the pool still needs time to create connections.
4. **Enable housekeeping features**:
   ```properties
   ojp.connection.pool.leakDetectionThreshold=60000
   ojp.connection.pool.maxLifetime=1800000  # 30 minutes
   ```

**Network Optimizations**:
1. **Co-locate** OJP servers with application servers (same AZ)
2. **Use dedicated network** if >1 Gbps traffic expected
3. **Enable TCP tuning** on OS level:
   ```bash
   net.core.rmem_max = 134217728
   net.core.wmem_max = 134217728
   net.ipv4.tcp_rmem = 4096 87380 67108864
   net.ipv4.tcp_wmem = 4096 65536 67108864
   ```

## 22.4 Benchmarking Methodology

Effective benchmarking provides accurate performance data for capacity planning and optimization validation.

### Benchmark Design Principles

**1. Realistic Workload Modeling**

Your benchmark should mirror production traffic patterns:

```java
// BAD: Unrealistic uniform workload
for (int i = 0; i < 10000; i++) {
    executeQuery("SELECT * FROM users WHERE id = " + i);
}

// GOOD: Realistic mixed workload
WorkloadMix workload = new WorkloadMix()
    .addOperation("simple_select", 0.50, () -> simpleSelect())
    .addOperation("complex_join", 0.30, () -> complexJoin())
    .addOperation("insert", 0.15, () -> insertData())
    .addOperation("update", 0.05, () -> updateData());

for (int i = 0; i < 10000; i++) {
    workload.executeRandomOperation();
}
```

**2. Proper Warm-Up Period**

Always include a warm-up phase to allow JIT compilation and cache population:

```java
// Warm-up phase (5 minutes)
long warmupDuration = 5 * 60 * 1000;
long warmupStart = System.currentTimeMillis();
while (System.currentTimeMillis() - warmupStart < warmupDuration) {
    executeWorkload();
}

// Clear metrics from warm-up
metrics.reset();

// Actual benchmark (10 minutes)
long benchmarkDuration = 10 * 60 * 1000;
long benchmarkStart = System.currentTimeMillis();
while (System.currentTimeMillis() - benchmarkStart < benchmarkDuration) {
    executeWorkload();
}

// Report metrics
metrics.report();
```

**3. Statistical Rigor**

Run multiple iterations and report confidence intervals:

```java
List<BenchmarkResult> results = new ArrayList<>();

// Run 5 iterations
for (int i = 0; i < 5; i++) {
    BenchmarkResult result = runBenchmark();
    results.add(result);
}

// Calculate statistics
double avgThroughput = results.stream()
    .mapToDouble(BenchmarkResult::getThroughput)
    .average().orElse(0);

double stdDev = calculateStdDev(results);
double ci95 = 1.96 * stdDev / Math.sqrt(results.size());

System.out.printf("Throughput: %.2f ± %.2f QPS (95%% CI)%n", 
    avgThroughput, ci95);
```

### Sample Benchmark: Throughput Test

```java
public class OJPThroughputBenchmark {
    private static final int WARMUP_SECONDS = 300;
    private static final int BENCHMARK_SECONDS = 600;
    private static final int THREAD_COUNT = 50;
    
    public static void main(String[] args) throws Exception {
        DataSource directDataSource = createDirectDataSource();
        DataSource ojpDataSource = createOJPDataSource();
        
        System.out.println("=== Warming up ===");
        runPhase(ojpDataSource, WARMUP_SECONDS, THREAD_COUNT, false);
        
        System.out.println("=== Benchmarking Direct Connection ===");
        Metrics directMetrics = runPhase(directDataSource, BENCHMARK_SECONDS, 
            THREAD_COUNT, true);
        
        System.out.println("=== Benchmarking OJP ===");
        Metrics ojpMetrics = runPhase(ojpDataSource, BENCHMARK_SECONDS, 
            THREAD_COUNT, true);
        
        System.out.println("\n=== Results ===");
        System.out.printf("Direct - Throughput: %.2f QPS, P99 Latency: %.2f ms%n",
            directMetrics.getThroughput(), directMetrics.getP99Latency());
        System.out.printf("OJP    - Throughput: %.2f QPS, P99 Latency: %.2f ms%n",
            ojpMetrics.getThroughput(), ojpMetrics.getP99Latency());
        System.out.printf("Overhead: %.2f ms (%.1f%%)%n",
            ojpMetrics.getP99Latency() - directMetrics.getP99Latency(),
            100.0 * (ojpMetrics.getP99Latency() / directMetrics.getP99Latency() - 1));
    }
    
    private static Metrics runPhase(DataSource ds, int durationSeconds, 
            int threads, boolean collect) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Metrics metrics = new Metrics();
        AtomicBoolean running = new AtomicBoolean(true);
        
        // Submit worker threads
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                while (running.get()) {
                    long start = System.nanoTime();
                    try {
                        executeWorkload(ds);
                        if (collect) {
                            long latencyNs = System.nanoTime() - start;
                            metrics.recordLatency(latencyNs / 1_000_000.0);
                        }
                    } catch (Exception e) {
                        if (collect) {
                            metrics.recordError();
                        }
                    }
                }
            });
        }
        
        // Run for specified duration
        try {
            Thread.sleep(durationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Stop workers
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        
        return metrics;
    }
    
    private static void executeWorkload(DataSource ds) throws SQLException {
        // Mix of operations mirroring production
        double rand = Math.random();
        if (rand < 0.50) {
            simpleSelect(ds);
        } else if (rand < 0.80) {
            complexJoin(ds);
        } else if (rand < 0.95) {
            insertData(ds);
        } else {
            updateData(ds);
        }
    }
    
    private static void simpleSelect(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, (int) (Math.random() * 100000));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getString("name");
                }
            }
        }
    }
    
    // ... other workload methods
}
```

### Metrics Collection and Reporting

Use HdrHistogram for accurate latency percentiles:

```java
public class Metrics {
    private final Histogram histogram = new Histogram(3600000000000L, 3);
    private final AtomicLong operations = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final long startTime = System.currentTimeMillis();
    
    public void recordLatency(double latencyMs) {
        histogram.recordValue((long) (latencyMs * 1000));  // Convert to µs
        operations.incrementAndGet();
    }
    
    public void recordError() {
        errors.incrementAndGet();
    }
    
    public double getThroughput() {
        long durationMs = System.currentTimeMillis() - startTime;
        return operations.get() * 1000.0 / durationMs;
    }
    
    public double getP50Latency() {
        return histogram.getValueAtPercentile(50.0) / 1000.0;
    }
    
    public double getP95Latency() {
        return histogram.getValueAtPercentile(95.0) / 1000.0;
    }
    
    public double getP99Latency() {
        return histogram.getValueAtPercentile(99.0) / 1000.0;
    }
    
    public double getP999Latency() {
        return histogram.getValueAtPercentile(99.9) / 1000.0;
    }
    
    public long getErrorCount() {
        return errors.get();
    }
    
    public void report() {
        System.out.printf("Operations: %d%n", operations.get());
        System.out.printf("Errors: %d%n", errors.get());
        System.out.printf("Throughput: %.2f QPS%n", getThroughput());
        System.out.printf("Latency P50: %.2f ms%n", getP50Latency());
        System.out.printf("Latency P95: %.2f ms%n", getP95Latency());
        System.out.printf("Latency P99: %.2f ms%n", getP99Latency());
        System.out.printf("Latency P99.9: %.2f ms%n", getP999Latency());
    }
}
```

## 22.5 Resource Consumption Guidance

Understanding resource consumption patterns helps with cost optimization and capacity forecasting.

### CPU Consumption by Workload Type

**OLTP Workload (Typical Web Application)**:
- Characteristics: Short queries, high concurrency, mixed reads/writes
- CPU pattern: Steady utilization, responsive to traffic spikes
- Expected utilization: 30-50% average, 70-80% peak
- Scaling trigger: Sustained >70% for 15+ minutes

**Batch Workload (ETL, Reporting)**:
- Characteristics: Long-running queries, sequential processing, large result sets
- CPU pattern: Sustained high utilization during batch windows
- Expected utilization: 60-80% during batch, <10% off-peak
- Scaling trigger: Batch completion time exceeding SLA

**Analytics Workload (BI Queries)**:
- Characteristics: Complex JOINs, aggregations, full table scans
- CPU pattern: Bursty high utilization, query-dependent
- Expected utilization: Highly variable (10-90%)
- Scaling trigger: Query timeout errors, P99 latency >10s

### Memory Consumption Patterns

**Steady State Memory**:
```
Base:                  1.5 GB
Connection pools:      0.5 GB (100 connections)
Statement cache:       0.2 GB
Housekeeping:          0.1 GB
SQL Enhancer cache:    0.05 GB
-----------------------------------
Total steady state:    2.35 GB

Recommended heap:      4 GB (70% headroom for GC)
```

**Under Load Memory Growth**:
```
Concurrent requests:   +0.3 GB (100 concurrent requests @ 3MB each)
Result set buffers:    +0.5 GB (50 large result sets @ 10MB each)
Temporary objects:     +0.2 GB (GC collection)
-----------------------------------
Peak memory:           3.35 GB

Maximum heap:          6 GB (safe margin before OOM)
```

**Memory Leak Detection**:
Monitor for memory growth patterns over time:
```bash
# Check for increasing old generation over 24h
jstat -gcutil <pid> 60000 1440 | awk '{print $4}' > old-gen.txt

# Alert if old generation grows >20% without Full GC
```

### Network Bandwidth Consumption

**Bandwidth Calculator**:
```
Request overhead:      ~200 bytes (gRPC frame headers)
Average parameter:     ~20 bytes
Average row:           ~500 bytes (varies by schema)

Example calculation for 1000 QPS:
- Requests:  1000 QPS * 200 bytes = 0.2 MB/s = 1.6 Mbps
- Parameters: 1000 QPS * 5 params * 20 bytes = 0.1 MB/s = 0.8 Mbps
- Results:   1000 QPS * 10 rows * 500 bytes = 5 MB/s = 40 Mbps
-----------------------------------
Total:       5.3 MB/s = 42.4 Mbps

With protocol overhead (×1.15): ~49 Mbps
Recommended bandwidth:          100+ Mbps (2x headroom)
```

**High Bandwidth Scenarios**:
- Large BLOB/CLOB transfers: 100+ Mbps per active transfer
- Bulk exports (1000+ rows): 500+ Mbps sustained
- High-frequency small queries: 10-50 Mbps per 1000 QPS

**Network Optimization**:
```properties
# Increase gRPC message size limits for large result sets
ojp.grpc.max-inbound-message-size=67108864  # 64 MB

# Enable flow control
ojp.grpc.flow-control.enabled=true
ojp.grpc.flow-control.window-size=1048576  # 1 MB
```

### Disk I/O Patterns

OJP itself has minimal disk I/O, limited to:
- **Logging**: 100-500 MB/day (INFO level), 1-5 GB/day (DEBUG level)
- **Metrics**: 50-100 MB/day if persisting to disk
- **Temporary files**: Rare, only for extremely large result sets (>1 GB)

**Log Management**:
```properties
# Use appropriate log level
logging.level.com.openj.proxy=INFO

# Rotate logs frequently
logging.file.max-size=100MB
logging.file.max-history=7

# Use async logging to reduce overhead
logging.pattern.console=%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n
```

## 22.6 Performance Monitoring and Alerting

Continuous monitoring ensures you detect and resolve performance issues before they impact users.

### Key Performance Indicators (KPIs)

**Tier 1 - Critical KPIs** (Alert immediately):
1. **Request Error Rate**: >0.1% (100 errors per 100K requests)
   - Indicates: Connection failures, query errors, timeouts
   - Action: Investigate logs, check database connectivity

2. **P99 Latency**: >50ms increase from baseline
   - Indicates: Performance degradation, resource contention
   - Action: Check CPU, memory, GC, network latency

3. **Connection Pool Exhaustion**: Any occurrence
   - Indicates: Under-provisioned pool, connection leaks
   - Action: Increase pool size, enable leak detection

4. **OJP Server Availability**: <99.9%
   - Indicates: Server crashes, unplanned restarts
   - Action: Check logs for OOM, segfaults, panic errors

**Tier 2 - Warning KPIs** (Alert after sustained threshold):
1. **CPU Utilization**: >70% for 15 minutes
2. **Memory Utilization**: >80% heap used
3. **GC Pause Time**: >100ms for any pause
4. **Network Errors**: >10 per minute
5. **Thread Pool Exhaustion**: >90% threads active

**Tier 3 - Informational KPIs** (Monitor trends):
1. **Request Rate**: Trend analysis for capacity planning
2. **P50/P95 Latency**: Gradual degradation detection
3. **Cache Hit Rate**: SQL Enhancer effectiveness
4. **Connection Churn**: Application connection management

### Monitoring Stack Recommendations

**Option 1: Prometheus + Grafana** (Most popular):
```yaml
# OJP Prometheus metrics
ojp:
  telemetry:
    metrics:
      enabled: true
      prometheus:
        enabled: true
        port: 9090
        path: /metrics
```

**Sample Prometheus Queries**:
```promql
# P99 latency
histogram_quantile(0.99, sum(rate(ojp_request_duration_seconds_bucket[5m])) by (le))

# Error rate
sum(rate(ojp_request_errors_total[5m])) / sum(rate(ojp_requests_total[5m]))

# Connection pool utilization
ojp_pool_active_connections / ojp_pool_max_connections
```

**Option 2: Datadog** (Managed solution):
```properties
# Datadog integration
ojp.telemetry.datadog.enabled=true
ojp.telemetry.datadog.api-key=${DD_API_KEY}
ojp.telemetry.datadog.host=api.datadoghq.com
```

**Option 3: Custom Solution** (JDBC + Time-series DB):
```java
// Collect metrics periodically
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    PoolStatistics stats = poolManager.getStatistics();
    metricsClient.gauge("ojp.pool.active", stats.getActiveConnections());
    metricsClient.gauge("ojp.pool.idle", stats.getIdleConnections());
    metricsClient.gauge("ojp.pool.waiting", stats.getWaitingThreads());
}, 0, 10, TimeUnit.SECONDS);
```

### Alert Configuration Examples

**Critical Alert: Connection Pool Exhaustion**:
```yaml
- alert: OJPConnectionPoolExhausted
  expr: ojp_pool_waiting_threads > 0
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "OJP connection pool exhausted on {{ $labels.instance }}"
    description: "{{ $value }} threads waiting for connections"
```

**Critical Alert: High Error Rate**:
```yaml
- alert: OJPHighErrorRate
  expr: sum(rate(ojp_request_errors_total[5m])) / sum(rate(ojp_requests_total[5m])) > 0.01
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "OJP error rate above 1% on {{ $labels.instance }}"
    description: "Error rate: {{ $value | humanizePercentage }}"
```

**Warning Alert: High CPU**:
```yaml
- alert: OJPHighCPU
  expr: process_cpu_usage{job="ojp"} > 0.70
  for: 15m
  labels:
    severity: warning
  annotations:
    summary: "OJP CPU usage high on {{ $labels.instance }}"
    description: "CPU: {{ $value | humanizePercentage }}"
```

## 22.7 Cost Optimization Strategies

Balancing performance with cost is essential for sustainable operations.

### Right-Sizing Strategies

**1. Vertical Scaling vs. Horizontal Scaling**:
- **Vertical** (larger instances): Better for CPU-bound workloads, simpler operations
- **Horizontal** (more instances): Better for I/O-bound, better HA, linear scaling

**Cost Comparison**:
```
Single 16-core server:  $500/month
Four 4-core servers:    $600/month (20% higher cost, better HA)
```

Recommendation: Start vertical (simplicity), scale horizontal when >8 cores needed.

**2. Reserved vs. On-Demand Instances**:
- **Reserved** (1-3 year commitment): 40-60% savings
- **On-Demand**: Flexibility, no commitment

Strategy: Use reserved for baseline capacity, on-demand for bursts.

**3. Spot Instances for Non-Critical Workloads**:
- **Savings**: 70-90% off on-demand pricing
- **Use cases**: Development, testing, batch processing (with retry logic)
- **Risks**: Termination with 2-minute warning

### Resource Utilization Optimization

**Consolidation Opportunities**:
```
Before: 10 underutilized servers (20% CPU avg)
After:  3 properly-sized servers (60% CPU avg)
Savings: 70% infrastructure cost
```

**Connection Pool Optimization**:
```
Over-provisioned: 500 connections per server (200 idle = wasted DB resources)
Optimized:        150 connections per server (20 idle = right-sized)
Impact:           Reduced database load, potential to downsize database tier
```

**Workload-Specific Tuning**:
- **OLTP**: Smaller instances (4 cores), more of them (scale out)
- **Batch**: Larger instances (16 cores), fewer of them (scale up)
- **Mixed**: Medium instances (8 cores), moderate count

### Cost Monitoring Dashboard

Track these metrics for cost optimization:
1. **Cost per Million Requests**: Total cost / monthly requests
2. **CPU Utilization Efficiency**: Actual utilization / target utilization (60%)
3. **Memory Efficiency**: Actual usage / allocated memory
4. **Idle Resource Time**: Hours with <20% utilization
5. **Right-Sizing Opportunities**: Instances with sustained <30% or >80% utilization

**Monthly Review Checklist**:
- [ ] Analyze CPU/memory utilization trends
- [ ] Identify underutilized instances for downsizing
- [ ] Review connection pool sizes for optimization
- [ ] Assess reserved instance opportunities
- [ ] Evaluate workload migration to spot instances

---

## AI-Ready Image Prompts for Chapter 22

**Image 22-1: Performance Overhead Breakdown**
Create a stacked bar chart visualization showing the latency overhead components of OJP. The chart should have three bars representing "Same Datacenter," "Same AZ," and "Cross-AZ" deployments. Each bar is divided into colored segments for: Network RTT (blue), gRPC Serialization (green), OJP Server Processing (orange), and Database Execution (gray, for reference). Include exact millisecond values for each component and a total overhead number at the top of each bar. Use a clean, technical style with a white background and clear labels.

**Image 23-2: Throughput Scaling Curve**
Create a line graph showing OJP throughput (QPS on Y-axis) vs. number of servers (X-axis from 1 to 8 servers). Show three lines: "Simple SELECT" (top line, blue), "Mixed Workload" (middle line, green), and "Large Result Sets" (bottom line, orange). Each line should show near-linear scaling up to 4 servers, then diminishing returns beyond that. Include specific QPS values at key points (1, 2, 4, 8 servers). Add a dotted "Linear Scaling" reference line for comparison. Use a professional technical style with grid lines.

**Image 23-3: Memory Consumption Stack**
Create a stacked area chart showing memory usage over a 24-hour period (X-axis: time, Y-axis: memory in GB). The stack layers from bottom to top: "Base Server" (dark blue), "Connection Pools" (blue), "Active Requests" (light blue), "Result Set Buffers" (green), and "GC Headroom" (light gray). Show a typical daily pattern with higher usage during business hours (8am-6pm) and lower usage at night. Include a horizontal red dashed line indicating the configured heap size. Add a small traffic pattern overlay showing QPS correlation.

**Image 23-4: Capacity Planning Flowchart**
Create a decision tree flowchart for capacity planning. Start with "Characterize Workload" at the top, branching to three paths: "OLTP" (left), "Batch" (middle), and "Mixed" (right). Each path shows key sizing decisions: CPU cores, memory, pool size, and server count recommendations. Use different colors for each workload type. Include decision diamonds for questions like "Peak QPS >5000?" and "Avg query time >500ms?" Each path ends with a box showing example configurations (e.g., "4 cores, 4GB, 100 connections, 2 servers"). Use a clean, professional style with icons for servers and databases.

**Image 23-5: Resource Exhaustion Troubleshooting Matrix**
Create a 3x3 matrix showing common resource exhaustion scenarios. Columns: "CPU Exhaustion," "Memory Exhaustion," "Connection Exhaustion." Rows: "Symptoms," "Detection," "Resolution." Each cell contains concise bullet points with icons. Use color coding: red for symptoms, yellow for detection methods, green for resolutions. Include small charts/gauges in symptom cells showing what to look for (e.g., CPU at 100%, memory growing over time, pool waiting threads >0). Professional technical documentation style.

**Image 23-6: Benchmark Results Comparison**
Create a dual-panel comparison chart showing "Direct JDBC" vs "OJP" performance. Each panel contains: a latency histogram (P50/P95/P99/P999 bars), a throughput gauge (QPS), and a small line graph showing latency over time. Use green for Direct JDBC and blue for OJP. Clearly show the overhead (e.g., "+2.3ms P99 latency", "-5% throughput"). Include a "Verdict" section at the bottom with acceptable/marginal/poor indicators based on the overhead. Clean, data-focused visualization style.

---

## Summary

This chapter equipped you with comprehensive performance engineering knowledge:

- **Performance fundamentals** including latency overhead (1-3ms), throughput characteristics (8K-12K QPS), and resource consumption patterns
- **Capacity planning framework** with step-by-step sizing calculations for servers, CPU, memory, and connection pools
- **Profiling and optimization** techniques to identify and resolve common bottlenecks like small fetchSize, pool exhaustion, and GC issues
- **Benchmarking methodology** with realistic workload modeling, proper warm-up, and statistical rigor
- **Resource consumption guidance** breaking down CPU, memory, network, and disk I/O patterns by workload type
- **Monitoring and alerting** with tiered KPIs and practical alert configurations
- **Cost optimization strategies** balancing performance with infrastructure spend

With this knowledge, you can confidently size your OJP deployment, optimize for your workload, and maintain excellent performance in production. The next chapter explores important topics and edge cases not covered in the main body of the book.
