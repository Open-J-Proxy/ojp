# Quick Reference: SPI Development for OJP

## One-Command JAR Creation

```bash
# Compile and package in one go
javac -cp ojp-datasource-api.jar com/example/MyProvider.java && \
./ojp-server/create-spi-jar.sh \
    com/example/MyProvider.class \
    org.openjproxy.datasource.ConnectionPoolProvider \
    my-provider.jar && \
cp my-provider.jar ojp-libs/
```

## Minimal Provider Template

Save as `MinimalProvider.java`:

```java
package com.example;

import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

public class MinimalProvider implements ConnectionPoolProvider {
    public String id() { return "minimal"; }
    public int getPriority() { return 150; }
    public boolean isAvailable() { return true; }
    
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        // TODO: Implement
        throw new UnsupportedOperationException();
    }
    
    public void closeDataSource(DataSource ds) { }
    public Map<String, Object> getStatistics(DataSource ds) { return Map.of(); }
}
```

## Full Documentation

- [Class File Loading Analysis](../analysis/CLASS_FILE_LOADING_ANALYSIS.md) - **READ THIS** for full analysis
- [Understanding OJP SPIs](../Understanding-OJP-SPIs.md) - Comprehensive SPI guide
- [create-spi-jar.sh](../../ojp-server/create-spi-jar.sh) - JAR creation script

## Summary

**Question**: Can we load .class files instead of JARs?

**Answer**: Technically yes, but **NOT RECOMMENDED** due to:
- ❌ Security risks (easier code injection)
- ❌ Complexity (custom ClassLoader needed)
- ❌ Poor UX (package structure confusion)
- ❌ No dependency management
- ❌ Against Java conventions

**Recommendation**: Use `create-spi-jar.sh` script to make JAR creation trivial.
