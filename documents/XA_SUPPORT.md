# XA Transaction Support in OJP

## Overview

OJP now supports distributed transactions (XA) through the Java Transaction API (JTA). This allows OJP to participate in distributed transactions coordinated by JTA transaction managers like Atomikos, Bitronix, or application server transaction managers.

## Features

- Full XA protocol implementation (2PC - Two-Phase Commit)
- Support for all XA operations: start, end, prepare, commit, rollback, recover, forget
- Transaction timeout management
- Resource manager identification (isSameRM)
- UUID-based session tracking on the server
- Thread-safe concurrent session management
- PostgreSQL XA support (extensible to other databases)

## Architecture

### Client Side (ojp-jdbc-driver)

1. **OjpXADataSource** - Entry point for JTA transaction managers
2. **OjpXAConnection** - Manages XA connections
3. **OjpXAResource** - Implements the XA protocol, delegates to server via gRPC
4. **XaService** - gRPC client for XA operations

### Server Side (ojp-server)

1. **XaServiceImpl** - gRPC service that handles XA operations
2. **XaSession** - Server-side XA session management
3. **XaSessionManager** - Registry for active XA sessions
4. **Database XA Integration** - Delegates to underlying database XA resources

## Usage

### Basic Setup

```java
import org.openjproxy.jdbc.xa.OjpXADataSource;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;

// Create XA DataSource
OjpXADataSource xaDataSource = new OjpXADataSource();
xaDataSource.setUrl("jdbc:ojp[localhost:1059]_postgresql://localhost/mydb");
xaDataSource.setUser("username");
xaDataSource.setPassword("password");
xaDataSource.setServerHost("localhost");
xaDataSource.setServerPort(1059);

// Get XA Connection
XAConnection xaConnection = xaDataSource.getXAConnection();

// Get XA Resource for transaction manager
XAResource xaResource = xaConnection.getXAResource();

// Get regular connection for SQL operations
java.sql.Connection connection = xaConnection.getConnection();
```

### With JTA Transaction Manager

```java
import javax.transaction.xa.Xid;
import javax.transaction.xa.XAResource;

// Transaction manager creates Xid
Xid xid = createXid();

// Start transaction
xaResource.start(xid, XAResource.TMNOFLAGS);

// Execute SQL operations
try (java.sql.Statement stmt = connection.createStatement()) {
    stmt.executeUpdate("INSERT INTO table VALUES (...)");
}

// End transaction
xaResource.end(xid, XAResource.TMSUCCESS);

// Prepare (Phase 1 of 2PC)
int result = xaResource.prepare(xid);

// Commit (Phase 2 of 2PC)
if (result == XAResource.XA_OK) {
    xaResource.commit(xid, false);
}
```

### With Atomikos Transaction Manager

```java
import com.atomikos.jdbc.AtomikosDataSourceBean;

AtomikosDataSourceBean atomikosDS = new AtomikosDataSourceBean();
atomikosDS.setUniqueResourceName("ojpDS");
atomikosDS.setXaDataSourceClassName("org.openjproxy.jdbc.xa.OjpXADataSource");

Properties props = new Properties();
props.setProperty("url", "jdbc:ojp[localhost:1059]_postgresql://localhost/mydb");
props.setProperty("user", "username");
props.setProperty("password", "password");
atomikosDS.setXaProperties(props);

atomikosDS.init();
```

## Configuration

### Server Configuration

The OJP server automatically handles XA connections when using databases with XA support. Ensure your database driver supports XA (e.g., PostgreSQL XA DataSource).

### Client Configuration

Configure the XA DataSource with the following properties:

- `url` - OJP connection URL (required)
- `user` - Database username
- `password` - Database password
- `serverHost` - OJP server hostname (default: localhost)
- `serverPort` - OJP server port (default: 1059)
- `loginTimeout` - Login timeout in seconds (default: 0)

## Supported Databases

Currently supported XA-capable databases:

1. **PostgreSQL** - Fully supported with org.postgresql.xa.PGXADataSource

### Adding Support for Other Databases

To add support for additional XA-capable databases, extend the `XaServiceImpl.createXADataSource()` method in the server:

```java
private XADataSource createXADataSource(XaConnectionRequest request) throws SQLException {
    String url = UrlParser.parseUrl(request.getUrl());
    
    if (url.contains("mysql")) {
        // Create MySQL XA DataSource
        return createMySQLXADataSource(url, request);
    } else if (url.contains("oracle")) {
        // Create Oracle XA DataSource
        return createOracleXADataSource(url, request);
    }
    // ... etc
}
```

## XA Operations

### Supported XA Operations

- **start(Xid, flags)** - Start work on a transaction branch
- **end(Xid, flags)** - End work on a transaction branch
- **prepare(Xid)** - Prepare to commit (Phase 1 of 2PC)
- **commit(Xid, onePhase)** - Commit transaction (Phase 2 of 2PC or one-phase)
- **rollback(Xid)** - Rollback transaction
- **recover(flag)** - Obtain list of prepared transaction branches
- **forget(Xid)** - Forget about a heuristically completed transaction
- **setTransactionTimeout(seconds)** - Set transaction timeout
- **getTransactionTimeout()** - Get current transaction timeout
- **isSameRM(XAResource)** - Check if another XAResource is from the same resource manager

### Transaction Flags

- `TMNOFLAGS` (0) - No flags
- `TMJOIN` (0x200000) - Join existing transaction
- `TMRESUME` (0x8000000) - Resume suspended transaction
- `TMSUSPEND` (0x2000000) - Suspend transaction
- `TMSUCCESS` (0x4000000) - Successful end
- `TMFAIL` (0x20000000) - Failed end
- `TMONEPHASE` (0x40000000) - One-phase commit optimization

## Error Handling

XA operations may throw `XAException` with the following error codes:

- `XA_OK` (0) - Normal execution completion
- `XA_RDONLY` (3) - Read-only transaction, no commit needed
- `XA_HEURCOM` (7) - Heuristic commit decision made
- `XA_HEURMIX` (5) - Heuristic mixed outcome
- `XAER_RMERR` (-3) - Resource manager error
- `XAER_NOTA` (-4) - XID not valid
- `XAER_INVAL` (-5) - Invalid arguments
- `XAER_PROTO` (-6) - Protocol error
- `XAER_RMFAIL` (-7) - Resource manager failed
- `XAER_DUPID` (-8) - Duplicate XID
- `XAER_OUTSIDE` (-9) - Outside valid state

## Limitations and Best Practices

### Current Limitations

1. **Database Support**: Currently only PostgreSQL is fully implemented. Other databases need XA DataSource factory methods.
2. **Connection Pooling**: XA connections are created on-demand. For production, consider implementing XA-aware connection pooling.
3. **Recovery**: Transaction recovery is supported via the `recover()` method, but requires transaction manager support.

### Best Practices

1. **Always Close Resources**: Close XA connections when done to release server resources.
2. **Transaction Timeouts**: Set appropriate transaction timeouts to prevent hanging transactions.
3. **Error Handling**: Always handle XAException and implement proper rollback logic.
4. **Testing**: Test distributed transaction scenarios thoroughly, including failure cases.
5. **Monitoring**: Monitor XA sessions on the server to detect leaks or hung transactions.

## Testing

### Unit Tests

Run the XA unit tests:

```bash
cd ojp-jdbc-driver
mvn test -Dtest=OjpXADataSourceTest
```

### Integration Testing

Integration tests require a running OJP server with an XA-capable database. These tests would need to be created to validate the full XA transaction flow.

Example test scenario:
```bash
# Start OJP server
cd ojp-server
mvn exec:java

# In another terminal, create integration tests for:
# - Basic XA transaction (start, prepare, commit)
# - Two-phase commit with multiple branches
# - Rollback scenarios
# - Recovery scenarios
```

## Troubleshooting

### Common Issues

1. **"XA not supported for database"** - The database driver doesn't support XA or isn't configured. Verify the database URL and ensure XA DataSource creation is implemented for that database.

2. **"XA session not found"** - The XA session was closed or expired on the server. Check for proper connection management and ensure connections aren't closed prematurely.

3. **Connection timeouts** - Increase transaction timeout or check network connectivity between client and server.

4. **"Cannot enable auto-commit on XA connection"** - XA connections must always operate with auto-commit disabled. This is enforced by the driver.

## Performance Considerations

XA transactions have overhead compared to regular transactions:

- **Network Overhead**: Each XA operation requires a gRPC call
- **Two-Phase Commit**: Prepare and commit are separate operations
- **Locking**: Resources may be locked longer during 2PC

For best performance:
- Use XA only when distributed transactions are required
- Minimize transaction duration
- Use connection pooling
- Consider one-phase commit optimization when possible

## Examples

Example code for using XA transactions is shown throughout this document in the "Usage" sections above. For more complex scenarios, refer to the test cases in `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/xa/`.

Future enhancements may include complete working examples for:
- Simple XA transaction
- Multi-database distributed transaction
- Integration with specific JTA transaction managers
- Recovery scenarios

## API Reference

Complete JavaDoc API documentation is available in the generated documentation:

```bash
mvn javadoc:javadoc
```

View at: `target/site/apidocs/index.html`

## Contributing

To contribute XA support for additional databases:

1. Implement the XA DataSource factory method in `XaServiceImpl`
2. Add unit tests for the new database
3. Update this documentation
4. Submit a pull request

## License

Apache License 2.0 - Same as the OJP project
