package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DriverConnectUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:h2:mem:test",
            "jdbc:postgresql://localhost:5432/db",
            "jdbc:oracle:thin:@localhost:1521/XEPDB1",
            ""
    })
    void shouldReturnNullWhenUrlIsNotAnOjpUrl(String url) throws SQLException {
        assertNull(new Driver().connect(url, new Properties()));
    }

    @Test
    void shouldThrowSQLExceptionWhenUrlIsNull() {
        assertThrows(SQLException.class, () -> new Driver().connect(null, new Properties()));
    }

    @Test
    void shouldLetDriverManagerUseNextDriverWhenUrlIsNotAnOjpUrl() throws SQLException {
        // Loading Driver registers it with DriverManager ahead of the stub registered below.
        new Driver();
        StubDriver stub = new StubDriver();
        DriverManager.registerDriver(stub);
        try {
            assertSame(stub.connection, DriverManager.getConnection(StubDriver.URL, new Properties()));
        } finally {
            DriverManager.deregisterDriver(stub);
        }
    }

    private static final class StubDriver implements java.sql.Driver {
        static final String URL = "jdbc:stubdb:test";

        final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> null);

        @Override
        public Connection connect(String url, Properties info) {
            return acceptsURL(url) ? connection : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return URL.equals(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
