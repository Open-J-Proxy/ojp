# Slow Query Segregation Feature

## Overview

The Slow Query Segregation feature monitors all database operations (`executeQuery` and `executeUpdate`) and classifies them as "slow" or "fast" based on their execution time. It then manages the number of concurrently executing operations in each category to prevent slow operations from blocking the system.

**Recommendation:** Enable this feature when your system runs a **mix** of fast OLTP-style queries and slower reporting/analytics queries on the same OJP server. In that scenario, it is strongly recommended because it protects fast user-facing traffic.

For **pure OLTP** systems (almost all queries are short) or **pure OLAP** systems (most queries are long), this feature often provides little benefit and can stay disabled unless your metrics show clear slow-vs-fast contention.

## How It Works

### 1. Operation Monitoring
- Every SQL operation is tracked using a hash of the SQL statement
- Execution times are recorded and averaged using a weighted formula: `new_average = ((stored_average * 4) + new_measurement) / 5`
- This gives 20% weight to the newest measurement, smoothing out outliers

### 2. Slow vs Fast Classification
- Default mode is `RELATIVE_FAST_BASELINE`.
- An operation enters slow classification when:
  - `operationAverageMs >= minimumSlowQueryMs`
  - `operationAverageMs >= fastBaselineMs * slowMultiplier`
- An operation recovers to fast when:
  - `operationAverageMs < minimumSlowQueryMs`, or
  - `operationAverageMs <= fastBaselineMs * recoveryMultiplier`
- Fast baseline is computed from currently-fast query-shape averages only (slow-classified operations are excluded).

### 3. Execution Slot Management
- The total number of concurrent operations is limited by the HikariCP connection pool maximum size
- By default, 20% of slots are allocated to slow operations, 80% to fast operations
- Operations must acquire an appropriate slot before executing

### 4. Slot Borrowing
- If one pool (slow/fast) is idle for a configurable time (default: 10 seconds), the other pool can borrow its slots
- This ensures efficient resource utilization while maintaining segregation
- Borrowed slots are returned to their original pool after use

## Configuration

Add these properties to your server configuration:

```properties
# Enable/disable the feature
ojp.server.slowQuerySegregation.enabled=false

# Percentage of slots for slow operations (0-100)
ojp.server.slowQuerySegregation.slowSlotPercentage=20

# Idle timeout for slot borrowing (milliseconds)
ojp.server.slowQuerySegregation.idleTimeout=10000

# Timeout for acquiring slow operation slots (milliseconds)
ojp.server.slowQuerySegregation.slowSlotTimeout=120000

# Timeout for acquiring fast operation slots (milliseconds)
ojp.server.slowQuerySegregation.fastSlotTimeout=60000
```

## Benefits

1. **Prevents Resource Starvation**: Fast operations aren't blocked by slow ones
2. **Maintains Throughput in Mixed Workloads**: System remains responsive when fast and slow queries run together
3. **Adaptive**: Automatically learns which operations are slow based on historical data
4. **Efficient**: Allows slot borrowing when pools are idle
5. **Configurable**: Tune the balance between slow and fast operation slots

## Monitoring

The feature provides status information including:
- Number of tracked operations
- Overall average execution time
- Current slot usage (slow/fast/borrowed)
- Classification of individual operations

## Thread Safety

All components are designed to be thread-safe and can handle concurrent operations without data corruption or deadlocks.

## Backwards Compatibility

The feature is designed to be non-intrusive:
- When disabled, it only performs performance monitoring without slot management
- Existing applications continue to work without modifications
- No changes to client code are required

## Example Scenarios

### Scenario 1: Mixed Workload
- Fast queries: `SELECT * FROM users WHERE id = ?` (avg: 10ms) and `SELECT * FROM orders WHERE id = ?` (avg: 20ms)
- Slow queries: `SELECT * FROM large_table ORDER BY date` (avg: 500ms)
- Fast baseline (median of currently-fast shapes): 15ms
- Slow entry threshold: max(minimumSlowQueryMs=100ms, 5 × 15ms=75ms) → 100ms
- Result: Only the complex query (500ms ≥ 100ms) is classified as slow

### Scenario 1b: Pure Workloads (When to Keep Disabled)
- **Pure OLTP**: Almost all queries are already fast and similar in latency
- **Pure OLAP**: Most queries are long-running and belong to the same performance class
- **Result**: Segregation usually adds little value; keep it disabled unless monitoring shows blocking between clearly different query classes

### Scenario 2: Resource Protection
- 20 total connection slots
- 4 slow slots, 8 fast slots
- If 4 slow operations are running, additional slow operations must wait
- Fast operations can still use their 16 slots unimpeded

### Scenario 3: Slot Borrowing
- No slow operations for 10+ seconds (idle)
- Fast pool is at capacity (16/16 used)
- New fast operation can borrow from slow pool
- System maintains high throughput

## Related Documentation

For complete configuration details including all server settings, see:
- **[Complete OJP Server Configuration Guide](../configuration/ojp-server-configuration.md)** - Comprehensive configuration reference with all available options
