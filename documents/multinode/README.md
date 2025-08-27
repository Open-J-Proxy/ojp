# OJP Multinode Configuration Guide

## Overview

OJP (Open JDBC Proxy) supports multinode deployment for high availability, load distribution, and fault tolerance. The multinode functionality allows JDBC clients to connect to multiple OJP servers simultaneously, providing automatic failover and load balancing capabilities.

## Features

- **Multinode URL Support**: Connect to multiple OJP servers with a single JDBC URL
- **Round-Robin Load Balancing**: Distributes requests across healthy servers
- **Session Stickiness**: Ensures session-bound operations stay on the same server
- **Automatic Failover**: Seamlessly handles server failures with retry logic
- **Server Health Monitoring**: Tracks server health and attempts recovery
- **Backward Compatibility**: Single-node configurations continue to work unchanged

## JDBC URL Format

### Single Node (Existing Format)
```
jdbc:ojp[host:port]_actual_jdbc_url
```

### Multinode Format
```
jdbc:ojp[host1:port1,host2:port2,host3:port3]_actual_jdbc_url
```

### Examples

#### PostgreSQL with Three OJP Servers
```java
String url = "jdbc:ojp[192.168.1.10:1059,192.168.1.11:1059,192.168.1.12:1059]_postgresql://localhost:5432/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

#### MySQL with Two OJP Servers
```java
String url = "jdbc:ojp[server1.example.com:1059,server2.example.com:1059]_mysql://localhost:3306/testdb";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

#### Oracle with Mixed Ports
```java
String url = "jdbc:ojp[db-proxy1:1059,db-proxy2:1060]_oracle:thin:@localhost:1521/XEPDB1";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

## Configuration

### Client-Side Configuration

The multinode functionality works with the existing `ojp.properties` configuration file. Additional multinode-specific properties can be configured:

```properties
# Standard connection pool configuration (applied to each server)
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# Multinode-specific configuration (future enhancement)
# ojp.multinode.retryAttempts=-1
# ojp.multinode.retryDelayMs=5000
```

### Server-Side Configuration

Each OJP server in a multinode setup should be configured identically with the same database connection settings. The servers will automatically coordinate pool sizes based on the number of active servers in the cluster.

## How It Works

### Connection Establishment

1. **URL Parsing**: The JDBC driver parses the comma-separated list of server addresses
2. **Initial Connection**: The driver attempts to connect to all servers in round-robin fashion
3. **Health Tracking**: Each server's health status is monitored continuously
4. **Load Balancing**: New connections are distributed across healthy servers

### Session Management

1. **Session Stickiness**: Once a session is established (identified by `SessionInfo.sessionUUID`), all subsequent requests for that session are routed to the same server
2. **Session Tracking**: The client maintains a mapping of session UUIDs to server endpoints
3. **Failover Handling**: If a session's server becomes unhealthy, the session is removed from the mapping and subsequent requests use round-robin routing

### Request Routing

- **Non-Session Requests**: Routed using round-robin load balancing
- **Session-Bound Requests**: Routed to the specific server associated with the session
- **Transaction Requests**: Always routed to the session's server to maintain ACID properties

### Failure Handling

1. **Server Failure Detection**: Servers are marked unhealthy when gRPC calls fail
2. **Automatic Retry**: Failed requests are retried on other healthy servers
3. **Recovery Attempts**: Unhealthy servers are periodically tested for recovery
4. **Graceful Degradation**: System continues operating with remaining healthy servers

## Best Practices

### Server Setup

1. **Identical Configuration**: All OJP servers should have identical database connection settings
2. **Shared Database**: All servers should connect to the same database instance or cluster
3. **Network Reliability**: Ensure reliable network connectivity between clients and all servers
4. **Resource Planning**: Plan total connection pool capacity across all servers

### Client Configuration

1. **Connection Pool Sizing**: Consider total pool capacity across all servers when configuring `maximumPoolSize`
2. **Health Check Frequency**: Configure appropriate retry delays to balance responsiveness and resource usage
3. **DNS Configuration**: Use DNS names instead of IP addresses when possible for easier maintenance

### Monitoring

1. **Server Health**: Monitor the health status of all OJP servers
2. **Connection Distribution**: Verify that connections are being distributed evenly
3. **Failover Testing**: Regularly test failover scenarios
4. **Performance Metrics**: Monitor response times across all servers

## Deployment Scenarios

### High Availability Setup
```
Application Tier
├── App Instance 1 → OJP[server1:1059,server2:1059]
├── App Instance 2 → OJP[server1:1059,server2:1059]
└── App Instance 3 → OJP[server1:1059,server2:1059]

OJP Proxy Tier
├── OJP Server 1 (Primary)
└── OJP Server 2 (Secondary)

Database Tier
└── PostgreSQL Database
```

### Load Distribution Setup
```
Application Tier
├── App Instance 1 → OJP[proxy1:1059,proxy2:1059,proxy3:1059]
├── App Instance 2 → OJP[proxy1:1059,proxy2:1059,proxy3:1059]
└── App Instance N → OJP[proxy1:1059,proxy2:1059,proxy3:1059]

OJP Proxy Tier
├── OJP Server 1 (Load Balanced)
├── OJP Server 2 (Load Balanced)
└── OJP Server 3 (Load Balanced)

Database Tier
└── MySQL Database Cluster
```

## Troubleshooting

### Common Issues

**Issue**: Client fails to connect to any server
- **Solution**: Verify all server addresses and ports are correct
- **Check**: Ensure at least one OJP server is running and accessible

**Issue**: Uneven load distribution
- **Solution**: Check server health status and network connectivity
- **Verify**: All servers are configured identically

**Issue**: Session-bound operations failing
- **Solution**: Verify session stickiness is working correctly
- **Check**: Transaction boundaries and session management

### Debug Logging

Enable debug logging to troubleshoot multinode issues:

```java
// Enable debug logging for OJP multinode
System.setProperty("org.slf4j.simpleLogger.log.org.openjdbcproxy.grpc.client", "debug");
```

### Health Check

Verify server health by checking the logs for connection attempts and failures:

```
[DEBUG] MultinodeConnectionManager - Selected server proxy1.example.com:1059 for request (round-robin)
[WARN]  MultinodeConnectionManager - Connection failed to server proxy2.example.com:1059: UNAVAILABLE: io exception
[INFO]  MultinodeConnectionManager - Successfully recovered server proxy2.example.com:1059
```

## Migration from Single Node

Migrating from single-node to multinode is straightforward:

### Before (Single Node)
```java
String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
```

### After (Multinode)
```java
String url = "jdbc:ojp[server1:1059,server2:1059]_postgresql://localhost:5432/mydb";
```

No code changes are required - simply update the JDBC URL to include multiple server addresses.

## Limitations

1. **Pool Coordination**: Currently, pool rebalancing based on server count is not implemented
2. **Configuration Sync**: Servers do not automatically synchronize configuration changes
3. **Split-Brain**: No coordination mechanism exists between servers to prevent split-brain scenarios

## Future Enhancements

1. **Dynamic Pool Rebalancing**: Automatically adjust pool sizes based on active server count
2. **Server Discovery**: Automatic discovery of new servers in the cluster
3. **Health Check Endpoints**: Dedicated health check endpoints for monitoring systems
4. **Configuration Synchronization**: Automatic synchronization of configuration changes across servers