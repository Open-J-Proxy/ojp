package org.openjproxy.jdbc.testutil;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for SQL Server integration tests.
 * Provides connection details from TestContainers when SQL Server tests are enabled.
 * This allows tests to use TestContainers instead of external SQL Server instances.
 */
public class SQLServerConnectionProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!SQLServerTestContainer.isEnabled()) {
            // Return empty stream when tests are disabled
            return Stream.empty();
        }

        ConnectionProps result = getConnectionProps();

        // Return a single set of arguments with the TestContainer connection details
        return Stream.of(
            Arguments.of(result.driverClass, result.ojpUrl, result.username, result.password)
        );
    }

    public static String getOjpProxyAddress() {
        return getConnectionProps().ojpUrl;
    }

    private static @NotNull ConnectionProps getConnectionProps() {
        // Initialize and start the TestContainer
        SQLServerTestContainer.getInstance();

        // Get connection details from the TestContainer
        String containerJdbcUrl = SQLServerTestContainer.getJdbcUrl();
        String username = SQLServerTestContainer.getUsername();
        String password = SQLServerTestContainer.getPassword();

        // Build OJP JDBC URL from the container URL
        // TestContainer URL format: jdbc:sqlserver://localhost:RANDOM_PORT;encrypt=false;...
        // We need to extract the connection string and wrap it with OJP format
        // OJP format: jdbc:ojp[localhost:1059]_sqlserver://...
        String driverClass = "org.openjproxy.jdbc.Driver";

        // Remove "jdbc:" prefix and add OJP wrapper
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX)
            ? containerJdbcUrl.substring(JDBC_PREFIX.length())
            : containerJdbcUrl;

        if (!urlWithoutPrefix.toLowerCase().contains("databasename=")) {
            urlWithoutPrefix = urlWithoutPrefix + ";databaseName=defaultdb";
        }

        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;

        return new ConnectionProps(username, password, driverClass, ojpUrl);
    }


    private static class ConnectionProps {
        private final String username;
        private final String password;
        private final String driverClass;
        private final String ojpUrl;

        public ConnectionProps(String username, String password, String driverClass, String ojpUrl) {
            this.username = username;
            this.password = password;
            this.driverClass = driverClass;
            this.ojpUrl = ojpUrl;
        }

    }
}
