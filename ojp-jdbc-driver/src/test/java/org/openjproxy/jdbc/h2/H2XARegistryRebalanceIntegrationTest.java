package org.openjproxy.jdbc.h2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class H2XARegistryRebalanceIntegrationTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_xa_registry_rebalance.csv")
    void shouldPreserveActiveTransactionWhenNewConnectionsReuseExpandedPool(
            String driverClass, String url, String user, String password) throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enableH2Tests", "false")),
                "H2 integration tests are disabled");
        Class.forName(driverClass);
        OjpXADataSource dataSource = new OjpXADataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setProperty("ojp.xa.connection.pool.maximumPoolSize", "20");
        dataSource.setProperty("ojp.xa.connection.pool.minimumIdle", "5");
        String table = "xa_rebalance_" + UUID.randomUUID().toString().replace("-", "");
        XAConnection first = dataSource.getXAConnection(user, password);
        try (Connection connection = first.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY)");
            }
            XAResource resource = first.getXAResource();
            byte[] transactionId = table.getBytes(StandardCharsets.US_ASCII);
            Xid xid = new Xid() {
                public int getFormatId() {
                    return 640;
                }

                public byte[] getGlobalTransactionId() {
                    return transactionId;
                }

                public byte[] getBranchQualifier() {
                    return new byte[]{1};
                }
            };
            resource.start(xid, XAResource.TMNOFLAGS);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO " + table + " VALUES (1)");
            }
            // Each new XA connection must reuse the registry owning the active transaction.
            for (int i = 0; i < 3; i++) {
                XAConnection additional = dataSource.getXAConnection(user, password);
                try (Connection other = additional.getConnection();
                     Statement statement = other.createStatement();
                     ResultSet result = statement.executeQuery("SELECT 1")) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                } finally {
                    additional.close();
                }
            }
            resource.end(xid, XAResource.TMSUCCESS);
            resource.commit(xid, true);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                assertEquals(1, result.getInt(1), "The transaction must survive pool expansion and new connects");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            }
        } finally {
            first.close();
        }
    }
}
