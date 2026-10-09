package org.openjproxy.grpc.server.xa;

import com.openjproxy.grpc.ConnectionDetails;
import lombok.extern.slf4j.Slf4j;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * Factory for creating XADataSource instances for different database types.
 * Uses Class.forName() to check driver availability before attempting to create XADataSource.
 * This avoids compile-time dependencies on proprietary database JDBC drivers.
 */
@Slf4j
public class XADataSourceFactory {

    /**
     * Creates an XADataSource for the specified database type based on the URL.
     *
     * @param url JDBC URL
     * @param connectionDetails Connection details including credentials
     * @return XADataSource instance for the database
     * @throws SQLException if XADataSource creation fails or database type not supported
     */
    public static XADataSource createXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        String lowerUrl = url.toLowerCase();

        try {
            if (lowerUrl.contains("postgresql")) {
                return createPostgreSQLXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("mysql")) {
                return createMySQLXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("h2:")) {
                return createH2XADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("oracle")) {
                return createOracleXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("sqlserver")) {
                return createSQLServerXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("db2")) {
                return createDB2XADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("cockroachdb") || lowerUrl.contains("cockroach")) {
                // CockroachDB uses PostgreSQL protocol/driver
                return createCockroachDBXADataSource(url, connectionDetails);
            } else {
                throw new SQLException("XA transactions not supported for database type in URL: " + url);
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create XADataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create XADataSource: " + e.getMessage(), e);
        }
    }

    private static XADataSource createH2XADataSource(String url, ConnectionDetails connectionDetails)
            throws SQLException {
        try {
            Class.forName("org.h2.jdbcx.JdbcDataSource");
            XADataSource xaDataSource = (XADataSource) Class.forName("org.h2.jdbcx.JdbcDataSource")
                    .getDeclaredConstructor()
                    .newInstance();
            xaDataSource.getClass().getMethod("setURL", String.class).invoke(xaDataSource, url);
            xaDataSource.getClass().getMethod("setUser", String.class)
                    .invoke(xaDataSource, connectionDetails.getUser());
            xaDataSource.getClass().getMethod("setPassword", String.class)
                    .invoke(xaDataSource, connectionDetails.getPassword());
            return xaDataSource;
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 JDBC driver not found. Add the H2 JDBC driver to the server classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create H2 XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a PostgreSQL XADataSource.
     */
    private static XADataSource createPostgreSQLXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if PostgreSQL driver is available
            Class.forName("org.postgresql.xa.PGXADataSource");

            // Use reflection to create and configure PGXADataSource
            XADataSource xaDS = (XADataSource) Class.forName("org.postgresql.xa.PGXADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            // Parse connection URL to extract host, port, database
            // Format: jdbc:postgresql://host:port/database or ojp[...]:host:port/database
            String cleanUrl = url;
            if (cleanUrl.toLowerCase().contains("_postgresql:")) {
                cleanUrl = cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_postgresql:") + 1);
            } else if (cleanUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
                cleanUrl = cleanUrl.substring("jdbc:".length());
            }

            // Parse postgresql://host:port/database
            if (cleanUrl.startsWith("postgresql://")) {
                cleanUrl = cleanUrl.substring("postgresql://".length());
                String[] parts = cleanUrl.split("/");
                if (parts.length >= 2) {
                    String hostPort = parts[0];
                    String database = parts[1].split("\\?")[0]; // Remove query params

                    String[] hostPortParts = hostPort.split(":");
                    String host = hostPortParts[0];
                    int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 5432;

                    // Set properties using reflection
                    xaDS.getClass().getMethod("setServerNames", String[].class).invoke(xaDS, (Object) new String[]{host});
                    xaDS.getClass().getMethod("setPortNumbers", int[].class).invoke(xaDS, (Object) new int[]{port});
                    xaDS.getClass().getMethod("setDatabaseName", String.class).invoke(xaDS, database);
                }
            }

            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            // Get server names for logging
            String[] serverNames = (String[]) xaDS.getClass().getMethod("getServerNames").invoke(xaDS);
            String host = (serverNames != null && serverNames.length > 0) ? serverNames[0] : "unknown";
            log.info("Created PostgreSQL XADataSource for host: {}", host);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found. Add postgresql JDBC driver to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create PostgreSQL XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a MySQL XADataSource.
     */
    private static XADataSource createMySQLXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if MySQL driver is available
            Class.forName("com.mysql.cj.jdbc.MysqlXADataSource");

            // Use reflection to create and configure MysqlXADataSource
            XADataSource xaDS = (XADataSource) Class.forName("com.mysql.cj.jdbc.MysqlXADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            xaDS.getClass().getMethod("setUrl", String.class).invoke(xaDS, url);
            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            log.info("Created MySQL XADataSource for URL: {}", url);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found. Add mysql-connector-j to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create MySQL XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an Oracle XADataSource.
     *
     * NOTE: Oracle XA requires specific database privileges for the user:
     * - GRANT SELECT ON sys.dba_pending_transactions TO user;
     * - GRANT SELECT ON sys.pending_trans$ TO user;
     * - GRANT SELECT ON sys.dba_2pc_pending TO user;
     * - GRANT EXECUTE ON sys.dbms_system TO user;
     * - GRANT FORCE ANY TRANSACTION TO user;
     *
     * If the user doesn't have these privileges, XA operations will fail with ORA-6550 or similar errors.
     * For testing/development, you can grant DBA role or execute: GRANT XA_RECOVER_ADMIN TO user;
     */
    private static XADataSource createOracleXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if Oracle driver is available
            Class.forName("oracle.jdbc.xa.client.OracleXADataSource");

            // Use reflection to create and configure OracleXADataSource
            XADataSource xaDS = (XADataSource) Class.forName("oracle.jdbc.xa.client.OracleXADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            // Clean the URL - remove OJP wrapper if present
            String cleanUrl = url;
            if (cleanUrl.toLowerCase().contains("_oracle:")) {
                cleanUrl = "jdbc:oracle:" + cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_oracle:") + 8);
            }

            // Parse Oracle connection URL to extract components
            // Format: jdbc:oracle:thin:@host:port/service or jdbc:oracle:thin:@host:port:sid
            if (cleanUrl.toLowerCase().startsWith("jdbc:oracle:thin:@")) {
                String connectionPart = cleanUrl.substring("jdbc:oracle:thin:@".length());

                // Parse host:port/service or host:port:sid
                String host = "localhost";
                int port = 1521;
                String serviceName = null;

                // Set driver type first - required for Oracle to construct proper URL internally
                xaDS.getClass().getMethod("setDriverType", String.class).invoke(xaDS, "thin");

                if (connectionPart.contains("/")) {
                    // Service name format: host:port/service
                    String[] parts = connectionPart.split("/");
                    String[] hostPort = parts[0].split(":");
                    host = hostPort[0];
                    if (hostPort.length > 1) {
                        port = Integer.parseInt(hostPort[1]);
                    }
                    serviceName = parts[1];

                    // Set properties using reflection
                    xaDS.getClass().getMethod("setServerName", String.class).invoke(xaDS, host);
                    xaDS.getClass().getMethod("setPortNumber", int.class).invoke(xaDS, port);
                    xaDS.getClass().getMethod("setServiceName", String.class).invoke(xaDS, serviceName);

                } else if (connectionPart.contains(":")) {
                    // SID format: host:port:sid
                    String[] parts = connectionPart.split(":");
                    host = parts[0];
                    if (parts.length > 1) {
                        port = Integer.parseInt(parts[1]);
                    }
                    if (parts.length > 2) {
                        String sid = parts[2];
                        xaDS.getClass().getMethod("setServerName", String.class).invoke(xaDS, host);
                        xaDS.getClass().getMethod("setPortNumber", int.class).invoke(xaDS, port);
                        xaDS.getClass().getMethod("setDatabaseName", String.class).invoke(xaDS, sid);
                    }
                } else {
                    // Fallback: try setting just the service name from the connection part
                    xaDS.getClass().getMethod("setServerName", String.class).invoke(xaDS, host);
                    xaDS.getClass().getMethod("setPortNumber", int.class).invoke(xaDS, port);
                    xaDS.getClass().getMethod("setServiceName", String.class).invoke(xaDS, connectionPart);
                }
            } else {
                // For non-thin URLs or unparseable formats, set driver type and try to parse
                xaDS.getClass().getMethod("setDriverType", String.class).invoke(xaDS, "thin");
                // Set sensible defaults
                xaDS.getClass().getMethod("setServerName", String.class).invoke(xaDS, "localhost");
                xaDS.getClass().getMethod("setPortNumber", int.class).invoke(xaDS, 1521);
            }

            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            // Oracle XA requires specific properties to work correctly
            // Set connection properties that enable XA support
            try {
                // Enable XA connection mode explicitly
                java.util.Properties props = new java.util.Properties();
                props.setProperty("user", connectionDetails.getUser());
                props.setProperty("password", connectionDetails.getPassword());
                // Oracle XA specific properties
                props.setProperty("v$session.program", "OJP-XA");

                xaDS.getClass().getMethod("setConnectionProperties", java.util.Properties.class).invoke(xaDS, props);
            } catch (Exception e) {
                log.warn("Could not set connection properties on Oracle XADataSource: {}", e.getMessage());
            }

            log.info("Created Oracle XADataSource for URL: {}", url);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("Oracle JDBC driver not found. Add ojdbc (ojdbc8 or ojdbc11) to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create Oracle XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a SQL Server XADataSource.
     */
    private static XADataSource createSQLServerXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if SQL Server driver is available
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerXADataSource");

            // Use reflection to create and configure SQLServerXADataSource
            XADataSource xaDS = (XADataSource) Class.forName("com.microsoft.sqlserver.jdbc.SQLServerXADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            // Set URL using reflection
            xaDS.getClass().getMethod("setURL", String.class).invoke(xaDS, url);
            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            log.info("Created SQL Server XADataSource for URL: {}", url);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("SQL Server JDBC driver not found. Add mssql-jdbc to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create SQL Server XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a DB2 XADataSource.
     */
    private static XADataSource createDB2XADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if DB2 driver is available
            Class.forName("com.ibm.db2.jcc.DB2XADataSource");

            // Use reflection to create and configure DB2XADataSource
            XADataSource xaDS = (XADataSource) Class.forName("com.ibm.db2.jcc.DB2XADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            // Parse DB2 URL: jdbc:db2://host:port/database
            String cleanUrl = url;
            if (cleanUrl.toLowerCase().contains("_db2:")) {
                cleanUrl = cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_db2:") + 1);
            } else if (cleanUrl.toLowerCase().startsWith("jdbc:db2:")) {
                cleanUrl = cleanUrl.substring("jdbc:".length());
            }

            // Parse db2://host:port/database
            if (cleanUrl.startsWith("db2://")) {
                cleanUrl = cleanUrl.substring("db2://".length());
                String[] parts = cleanUrl.split("/");
                if (parts.length >= 2) {
                    String hostPort = parts[0];
                    String database = parts[1].split("\\?")[0]; // Remove query params

                    String[] hostPortParts = hostPort.split(":");
                    String host = hostPortParts[0];
                    int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 50000;

                    // Set properties using reflection
                    xaDS.getClass().getMethod("setServerName", String.class).invoke(xaDS, host);
                    xaDS.getClass().getMethod("setPortNumber", int.class).invoke(xaDS, port);
                    xaDS.getClass().getMethod("setDatabaseName", String.class).invoke(xaDS, database);
                    xaDS.getClass().getMethod("setDriverType", int.class).invoke(xaDS, 4); // Type 4 driver
                }
            }

            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            log.info("Created DB2 XADataSource for URL: {}", url);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("DB2 JDBC driver not found. Add db2jcc or db2jcc4 to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create DB2 XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a CockroachDB XADataSource.
     * CockroachDB is PostgreSQL-compatible, so we use the PostgreSQL XADataSource.
     */
    private static XADataSource createCockroachDBXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            // Check if PostgreSQL driver is available (CockroachDB uses PostgreSQL protocol)
            Class.forName("org.postgresql.xa.PGXADataSource");

            // Use reflection to create and configure PGXADataSource
            XADataSource xaDS = (XADataSource) Class.forName("org.postgresql.xa.PGXADataSource")
                    .getDeclaredConstructor()
                    .newInstance();

            // Parse connection URL to extract host, port, database
            // CockroachDB URL format: jdbc:postgresql://host:port/database
            String cleanUrl = url;
            if (cleanUrl.toLowerCase().contains("_postgresql:") || cleanUrl.toLowerCase().contains("_cockroach")) {
                int startIdx = cleanUrl.toLowerCase().indexOf("_postgresql:");
                if (startIdx == -1) {
                    startIdx = cleanUrl.toLowerCase().indexOf("_cockroach");
                }
                cleanUrl = cleanUrl.substring(startIdx + 1);
                // If it says cockroachdb://, change to postgresql://
                cleanUrl = cleanUrl.replace("cockroachdb://", "postgresql://");
                cleanUrl = cleanUrl.replace("cockroach://", "postgresql://");
            } else if (cleanUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
                cleanUrl = cleanUrl.substring("jdbc:".length());
            } else if (cleanUrl.toLowerCase().startsWith("jdbc:cockroachdb:")) {
                cleanUrl = cleanUrl.substring("jdbc:".length()).replace("cockroachdb:", "postgresql:");
            }

            // Parse postgresql://host:port/database
            if (cleanUrl.startsWith("postgresql://")) {
                cleanUrl = cleanUrl.substring("postgresql://".length());
                String[] parts = cleanUrl.split("/");
                if (parts.length >= 2) {
                    String hostPort = parts[0];
                    String database = parts[1].split("\\?")[0]; // Remove query params

                    String[] hostPortParts = hostPort.split(":");
                    String host = hostPortParts[0];
                    int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 26257; // CockroachDB default port

                    // Set properties using reflection
                    xaDS.getClass().getMethod("setServerNames", String[].class).invoke(xaDS, (Object) new String[]{host});
                    xaDS.getClass().getMethod("setPortNumbers", int[].class).invoke(xaDS, (Object) new int[]{port});
                    xaDS.getClass().getMethod("setDatabaseName", String.class).invoke(xaDS, database);
                }
            }

            xaDS.getClass().getMethod("setUser", String.class).invoke(xaDS, connectionDetails.getUser());
            xaDS.getClass().getMethod("setPassword", String.class).invoke(xaDS, connectionDetails.getPassword());

            // Get server names for logging
            String[] serverNames = (String[]) xaDS.getClass().getMethod("getServerNames").invoke(xaDS);
            String host = (serverNames != null && serverNames.length > 0) ? serverNames[0] : "unknown";
            log.info("Created CockroachDB XADataSource (using PostgreSQL driver) for host: {}", host);
            return xaDS;

        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found (required for CockroachDB). Add postgresql JDBC driver to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create CockroachDB XADataSource: " + e.getMessage(), e);
        }
    }
}
