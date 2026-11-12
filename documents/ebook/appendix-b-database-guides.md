# Appendix B: Database-Specific Configuration Guides

Different databases have unique characteristics, configuration requirements, and quirks when used with OJP. This appendix provides database-specific guidance to help you get the best performance and reliability from each supported database system.

> **🚨 Important for Version 0.4.0-beta and Later:**  
> JDBC drivers must be downloaded before running ojp-server. See [Chapter 4: Database Drivers](part2-chapter4-database-drivers.md) for complete setup instructions for both JAR and Docker deployments.

The pool properties below apply to OJP's server-side pool. `connectionTimeout`
is the admission wait budget, not the physical connection creation timeout.
With HikariCP, OJP's internal acquisition timeout is effectively 250 ms. For
predictable, low latency, consider setting `minimumIdle` equal to
`maximumPoolSize` when the database connection budget permits; this reduces
connection creation during spikes but does not make connections ready
immediately.

## PostgreSQL

PostgreSQL is one of the most popular open-source databases and works exceptionally well with OJP. Its robust connection handling and standards compliance make it a natural fit.

### Driver Configuration

PostgreSQL driver setup is covered in [Chapter 4: Database Drivers](part2-chapter4-database-drivers.md#using-docker-with-open-source-drivers).

**JDBC URL Format:**
```
jdbc:ojp://localhost:9059/postgres?user=myuser&password=mypass
```

### Recommended Pool Settings

For PostgreSQL, the following pool settings provide a resource-conscious starting point:

```properties
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

PostgreSQL handles connections efficiently, so you can be generous with pool sizes. However, remember that each connection consumes about 10MB of memory on the database server.

### Connection Validation

The default HikariCP provider uses the driver's JDBC4 connection validity
check; no validation-query property is needed for this example.

### SSL/TLS Configuration

When connecting to PostgreSQL with SSL:

```
jdbc:ojp://localhost:9059/postgres?user=myuser&password=mypass&ssl=true&sslmode=require
```

Available SSL modes: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`

### XA Transaction Support

PostgreSQL has excellent XA support. Enable XA transactions on the server side:

```properties
ojp.xa.backend-session-pool.max-total=50
ojp.xa.backend-session-pool.max-idle=10
```

On the client side, use `PGXADataSource`:

```java
PGXADataSource xaDS = new PGXADataSource();
xaDS.setUrl("jdbc:ojp://localhost:9059/postgres");
xaDS.setUser("myuser");
xaDS.setPassword("mypass");
```

### Performance Tips

**Prepared Statement Caching:** PostgreSQL benefits from prepared statement caching. Enable it in your application:

```properties
preparedStatementCacheSize=250
preparedStatementCacheSqlLimit=2048
```

**Connection Initialization:** Set the search path at connection creation:

```properties
connectionInitSql=SET search_path TO myschema,public
```

**Timezone Handling:** Explicitly set timezone to avoid confusion:

```properties
connectionInitSql=SET TIME ZONE 'UTC'
```

### Common Issues

**Too many connections:** PostgreSQL has a default `max_connections` of 100. Monitor active connections:

```sql
SELECT count(*) FROM pg_stat_activity;
```

Increase if needed in `postgresql.conf`:
```
max_connections = 200
```

**Idle in transaction:** Watch for long-running transactions that hold connections:

```sql
SELECT pid, usename, state, query_start, state_change
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND state_change < NOW() - INTERVAL '5 minutes';
```

Set a timeout to automatically kill them:
```
idle_in_transaction_session_timeout = 300000  -- 5 minutes
```

---

## MySQL / MariaDB

MySQL and MariaDB are widely deployed databases with excellent performance characteristics. They work smoothly with OJP, though there are a few configuration considerations.

### Driver Configuration

MySQL and MariaDB drivers must be downloaded before use. Use the `download-drivers.sh` script to get them. Use the appropriate driver for your database:

**MySQL URL:**
```
jdbc:ojp://localhost:9059/mydb?user=root&password=secret
```

**MariaDB URL:**
```
jdbc:ojp://localhost:9059/mariadb?user=root&password=secret
```

### Recommended Pool Settings

Use an admission wait budget that fits your workload:

```properties
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=20000
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=1200000
```

MySQL has a default `wait_timeout` of 8 hours, but connections can become stale. Use a shorter `maxLifetime` to recycle them.

### Connection Validation

The default HikariCP provider uses the driver's JDBC4 connection validity
check; no validation-query property is needed for this example.

### SSL/TLS Configuration

Enable SSL for MySQL connections:

```
jdbc:ojp://localhost:9059/mydb?user=root&password=secret&useSSL=true&requireSSL=true
```

For MariaDB:
```
jdbc:ojp://localhost:9059/mariadb?user=root&password=secret&useSsl=true
```

### Character Encoding

Always specify UTF-8 encoding explicitly:

```
jdbc:ojp://localhost:9059/mydb?user=root&password=secret&characterEncoding=utf8mb4&connectionCollation=utf8mb4_unicode_ci
```

### XA Transaction Support

Both MySQL and MariaDB support XA transactions:

```java
MysqlXADataSource xaDS = new MysqlXADataSource();
xaDS.setUrl("jdbc:ojp://localhost:9059/mydb");
xaDS.setUser("root");
xaDS.setPassword("secret");
```

### Performance Tips

**Server Timezone:** Set server timezone to match application:

```properties
serverTimezone=UTC
```

**Batch Operations:** Enable rewriting of batch statements:

```properties
rewriteBatchedStatements=true
```

**Cache Prepared Statements:** MySQL benefits from client-side caching:

```properties
cachePrepStmts=true
prepStmtCacheSize=250
prepStmtCacheSqlLimit=2048
useServerPrepStmts=true
```

### Common Issues

**Max connections exceeded:** MySQL has a default `max_connections` of 151. Check current usage:

```sql
SHOW STATUS WHERE Variable_name = 'Threads_connected';
SHOW STATUS WHERE Variable_name = 'Max_used_connections';
```

Increase in `my.cnf`:
```
max_connections = 300
```

**Connection timeout:** If seeing "Communications link failure", the database may have closed idle connections:

```sql
SHOW VARIABLES LIKE 'wait_timeout';
SHOW VARIABLES LIKE 'interactive_timeout';
```

Set in `my.cnf`:
```
wait_timeout = 28800
interactive_timeout = 28800
```

Ensure `maxLifetime` is less than `wait_timeout`.

---

## Oracle Database

Oracle is a feature-rich enterprise database that requires additional setup with OJP due to its proprietary driver.

### Driver Configuration

Oracle drivers must be downloaded separately and placed in the `ojp-libs` directory. Use the download script:

```bash
./download-drivers.sh
```

Or download manually from Oracle Technology Network and place `ojdb8.jar` (or appropriate version) in `ojp-libs/`.

**JDBC URL Format:**
```
jdbc:ojp://localhost:9059/ORCL?user=system&password=oracle
```

For service name instead of SID:
```
jdbc:ojp://localhost:9059/orcl.example.com?user=system&password=oracle
```

### Recommended Pool Settings

Oracle connections are heavyweight. Use conservative pool settings:

```properties
ojp.connection.pool.maximumPoolSize=10
ojp.connection.pool.minimumIdle=3
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

### Connection Validation

The default HikariCP provider uses the Oracle driver's JDBC4 connection
validity check. For Oracle-specific driver options, use the driver's documented
properties:
```properties
oracle.jdbc.implicitStatementCacheSize=25
oracle.net.CONNECT_TIMEOUT=20000
```

### XA Transaction Support

Oracle has robust XA support with its `OracleXADataSource`:

```java
OracleXADataSource xaDS = new OracleXADataSource();
xaDS.setURL("jdbc:ojp://localhost:9059/ORCL");
xaDS.setUser("system");
xaDS.setPassword("oracle");
```

Server-side configuration:
```properties
ojp.xa.backend-session-pool.max-total=30
ojp.xa.backend-session-pool.max-idle=5
```

### Performance Tips

**Statement Caching:** Oracle performs best with statement caching enabled:

```properties
oracle.jdbc.implicitStatementCacheSize=25
oracle.jdbc.maxCachedBufferSize=30
```

**Batch Execution:** Enable Oracle's batch execution:

```properties
oracle.jdbc.defaultBatchValue=50
```

**Connection Properties:** Set important connection properties:

```properties
oracle.net.CONNECT_TIMEOUT=20000
oracle.jdbc.ReadTimeout=60000
defaultRowPrefetch=20
```

### Common Issues

**Listener not listening:** Verify Oracle listener is running:

```bash
lsnrctl status
```

**Too many connections:** Check Oracle's `processes` parameter:

```sql
SELECT name, value FROM v$parameter WHERE name = 'processes';
SELECT count(*) FROM v$session;
```

Increase if needed in `init.ora`:
```
processes = 300
sessions = 335  -- (processes * 1.1) + 5
```

**ORA-12505: TNS:listener does not currently know of SID:** Verify the SID or service name is correct:

```sql
SELECT instance_name FROM v$instance;
SELECT name FROM v$database;
```

---

## SQL Server

Microsoft SQL Server is a popular enterprise database that works well with OJP. It requires the Microsoft JDBC driver.

### Driver Configuration

SQL Server drivers must be downloaded separately:

```bash
./download-drivers.sh
```

Or download `mssql-jdbc-<version>.jre8.jar` from Microsoft and place in `ojp-libs/`.

**JDBC URL Format:**
```
jdbc:ojp://localhost:9059/master?user=sa&password=YourStrong!Passw0rd
```

With instance name:
```
jdbc:ojp://localhost:9059/master;instanceName=SQLEXPRESS?user=sa&password=YourStrong!Passw0rd
```

### Recommended Pool Settings

SQL Server handles connections efficiently:

```properties
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=30000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000
```

### Connection Validation

The default HikariCP provider uses the driver's JDBC4 connection validity
check; no validation-query property is needed for this example.

### SSL/TLS Configuration

Enable encryption for SQL Server:

```
jdbc:ojp://localhost:9059/master?user=sa&password=pass&encrypt=true&trustServerCertificate=true
```

For production, use proper certificates:
```
jdbc:ojp://localhost:9059/master?user=sa&password=pass&encrypt=true&trustServerCertificate=false&hostNameInCertificate=sqlserver.example.com
```

### XA Transaction Support

SQL Server supports XA transactions:

```java
SQLServerXADataSource xaDS = new SQLServerXADataSource();
xaDS.setURL("jdbc:ojp://localhost:9059/master");
xaDS.setUser("sa");
xaDS.setPassword("YourStrong!Passw0rd");
```

### Performance Tips

**Set Application Name:** Helps with monitoring:

```properties
applicationName=MyApp
```

**Packet Size:** Adjust for large result sets:

```properties
packetSize=8192
```

**Prepared Statement Handling:** Enable prepared statement caching:

```properties
statementPoolingCacheSize=50
disableStatementPooling=false
```

### Common Issues

**Login failed for user:** Verify SQL Server authentication mode allows SQL logins:

```sql
SELECT CASE SERVERPROPERTY('IsIntegratedSecurityOnly')
  WHEN 1 THEN 'Windows Authentication'
  WHEN 0 THEN 'Mixed Mode'
END AS AuthenticationMode;
```

**Connection pool timeout:** Check max worker threads:

```sql
SELECT max_workers_count FROM sys.dm_os_sys_info;
```

**TCP/IP not enabled:** Ensure TCP/IP protocol is enabled in SQL Server Configuration Manager.

---

## H2 Database

H2 is a lightweight Java database perfect for development and testing. Download the H2 driver using the `download-drivers.sh` script before starting OJP.

### Driver Configuration

Download the H2 driver using the provided script, then no additional configuration is needed.

**In-Memory Database:**
```
jdbc:ojp://localhost:9059/mem:testdb?user=sa&password=
```

**File-Based Database:**
```
jdbc:ojp://localhost:9059/~/testdb?user=sa&password=
```

### Recommended Pool Settings

H2 is lightweight, use minimal pooling:

```properties
ojp.connection.pool.maximumPoolSize=5
ojp.connection.pool.minimumIdle=1
ojp.connection.pool.connectionTimeout=10000
```

### Performance Tips

**Compatibility Mode:** Emulate other databases:

```
jdbc:ojp://localhost:9059/mem:testdb;MODE=PostgreSQL
jdbc:ojp://localhost:9059/mem:testdb;MODE=MySQL
jdbc:ojp://localhost:9059/mem:testdb;MODE=Oracle
```

**In-Memory Performance:** For tests, disable durability:

```
jdbc:ojp://localhost:9059/mem:testdb;DB_CLOSE_DELAY=-1
```

---

**IMAGE_PROMPT_B1:** Create a database comparison matrix showing PostgreSQL, MySQL, Oracle, SQL Server, and H2 across rows, with columns for: "Driver Setup", "Pool Size Recommendation", "XA Support", "Best Use Case", and "Special Considerations". Use database vendor colors and icons. Include traffic light indicators (green/yellow/red) for ease of setup and XA maturity.

**IMAGE_PROMPT_B2:** Create a troubleshooting decision tree for database connection issues. Start with "Connection Failed" at the top, branch into "Driver Not Found", "Authentication Failed", "Network Issue", and "Pool Exhausted". Each branch shows specific commands to diagnose (colored terminal screenshots) and solutions. Use distinct colors for each database vendor's solutions.

---

## Cross-Database Considerations

### Connection Pooling Best Practices

Regardless of database, follow these principles:

1. **Size pools appropriately:** More isn't always better. Start conservative and scale based on metrics.
2. **Monitor pool metrics:** Watch for exhaustion, timeouts, and leaks.
3. **Set reasonable timeouts:** Don't let clients wait forever for connections.
4. **Validate connections:** Use lightweight test queries.
5. **Recycle connections:** Set `maxLifetime` to prevent stale connections.

### Character Encoding

Always specify UTF-8 encoding explicitly for international character support. The exact syntax varies by database, but the principle is universal.

### Transaction Isolation

Different databases have different default isolation levels:

- **PostgreSQL:** Read Committed
- **MySQL:** Repeatable Read
- **Oracle:** Read Committed
- **SQL Server:** Read Committed
- **H2:** Read Committed

Set explicitly in your application if you need different semantics:

```java
connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
```

### Timezone Handling

Database servers, OJP servers, and clients may be in different timezones. Best practice:

1. Store all timestamps in UTC
2. Set server timezone to UTC
3. Convert to local timezone in the application layer

This appendix provides the essential database-specific knowledge you need to configure OJP effectively. For the most current driver versions and compatibility information, always check the official OJP documentation and the database vendor's documentation.
