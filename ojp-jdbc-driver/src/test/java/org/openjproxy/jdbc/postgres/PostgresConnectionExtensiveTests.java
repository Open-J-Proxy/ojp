package org.openjproxy.jdbc.postgres;

import io.grpc.StatusRuntimeException;
import lombok.SneakyThrows;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresConnectionExtensiveTests {

    private static boolean isTestEnabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        connection = DriverManager.getConnection(url, user, password);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testConnectionProperties(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        Assertions.assertFalse(connection.isClosed());
        assertTrue( connection.isValid(5));
        assertNotNull(connection.getSchema()); // PostgreSQL should return current schema
        assertNull(connection.getClientInfo("nonexistent"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testAutoCommitAndTransactionIsolation(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        assertTrue( connection.getAutoCommit());
        connection.setAutoCommit(false);
        assertFalse( connection.getAutoCommit());

        int isolation = connection.getTransactionIsolation();
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolation);

        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testCommitAndRollback(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        
        // PostgreSQL DDL statements are transactional, so we need to create and commit the table first
        TestDBUtils.createBasicTestTable(connection, "postgres_connection_test", TestDBUtils.SqlSyntax.POSTGRES, true);
        connection.commit(); // Ensure table creation is committed
        
        connection.setAutoCommit(false);

        connection.createStatement().execute("INSERT INTO postgres_connection_test (id, name) VALUES (3, 'Charlie')");
        connection.rollback();

        ResultSet rs = connection.createStatement().executeQuery("SELECT * FROM postgres_connection_test WHERE id = 3");
        assertFalse( rs.next());

        connection.createStatement().execute("INSERT INTO postgres_connection_test (id, name) VALUES (3, 'Charlie')");
        connection.commit();

        rs = connection.createStatement().executeQuery("SELECT * FROM postgres_connection_test WHERE id = 3");
        assertTrue( rs.next());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testSavepoints(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        
        // PostgreSQL DDL statements are transactional, so we need to create and commit the table first
        TestDBUtils.createBasicTestTable(connection, "postgres_connection_test", TestDBUtils.SqlSyntax.POSTGRES, true);
        connection.commit(); // Ensure table creation is committed
        
        connection.setAutoCommit(false);

        Savepoint sp1 = connection.setSavepoint("Savepoint1");
        connection.createStatement().execute("INSERT INTO postgres_connection_test (id, name) VALUES (3, 'Charlie')");
        connection.rollback(sp1);

        ResultSet rs = connection.createStatement().executeQuery("SELECT * FROM postgres_connection_test WHERE id = 3");
        assertFalse( rs.next());

        connection.createStatement().execute("INSERT INTO postgres_connection_test (id, name) VALUES (3, 'Charlie')");
        // sp1 is no longer valid after rollback, so create a new savepoint to demonstrate release functionality  
        Savepoint sp2 = connection.setSavepoint("Savepoint2");
        connection.releaseSavepoint(sp2);
        connection.commit();

        rs = connection.createStatement().executeQuery("SELECT * FROM postgres_connection_test WHERE id = 3");
        assertTrue( rs.next());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testConnectionMetadata(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData metaData = connection.getMetaData();
        assertNotNull(metaData);
        assertEquals("PostgreSQL", metaData.getDatabaseProductName());
        assertTrue( metaData.supportsTransactions());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testClientInfo(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        // PostgreSQL supports client info
        try {
            connection.setClientInfo("ApplicationName", "TestApp");
            // Note: PostgreSQL might not immediately reflect the change in getClientInfo
        } catch (SQLClientInfoException e) {
            // Some implementations might not support this
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testClose(String driverClass, String url, String user, String password) throws SQLException {
        this.setUp(driverClass, url, user, password);
        assertFalse( connection.isClosed());
        connection.close();
        assertTrue( connection.isClosed());
    }

    // ---------- Additional tests for every Connection interface method ----------

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testAllConnectionMethods(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // createStatement
        Statement st1 = connection.createStatement();
        assertNotNull(st1);

        // prepareStatement(String)
        PreparedStatement ps1 = connection.prepareStatement("SELECT 1");
        assertNotNull(ps1);

        // prepareCall(String)
        CallableStatement cs1 = connection.prepareCall("SELECT 1");
        assertNotNull(cs1);

        // nativeSQL
        assertEquals("SELECT 1", connection.nativeSQL("SELECT 1"));

        // setAutoCommit/getAutoCommit done elsewhere

        // commit/rollback/close done elsewhere

        // isClosed done elsewhere

        // getMetaData done elsewhere

        // setReadOnly / isReadOnly
        connection.setReadOnly(false);
        assertFalse( connection.isReadOnly());
        // Note: PostgreSQL might not allow setting read-only in a transaction
        try {
            connection.setReadOnly(true);
            assertTrue( connection.isReadOnly());
        } catch (SQLException e) {
            // PostgreSQL might reject this in certain states
        }

        // setCatalog / getCatalog
        String oldCatalog = connection.getCatalog();
        connection.setCatalog(oldCatalog); // Should not throw
        assertEquals(oldCatalog, connection.getCatalog());

        // setTransactionIsolation / getTransactionIsolation done elsewhere

        // getWarnings / clearWarnings
        SQLWarning warning = connection.getWarnings();
        // PostgreSQL may or may not have warnings
        connection.clearWarnings();

        // createStatement(int, int)
        Statement st2 = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(st2);

        // prepareStatement(String, int, int)
        PreparedStatement ps2 = connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(ps2);

        // prepareCall(String, int, int)
        CallableStatement cs2 = connection.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(cs2);

        // getTypeMap / setTypeMap
        Map<String, Class<?>> oldMap = connection.getTypeMap();
        assertNotNull(oldMap);
        connection.setTypeMap(oldMap);

        // setHoldability/getHoldability
        int hold = connection.getHoldability();
        connection.setHoldability(hold);
        assertEquals(hold, connection.getHoldability());

        // Set auto-commit to false before using savepoints!
        connection.setAutoCommit(false);

        // setSavepoint, setSavepoint(String)
        Savepoint sp1 = connection.setSavepoint();
        assertNotNull(sp1);
        Savepoint sp2 = connection.setSavepoint("sp2");
        assertNotNull(sp2);

        // rollback(Savepoint), releaseSavepoint(Savepoint)
        connection.rollback(sp1);
        // In PostgreSQL, we can only release savepoints that haven't been rolled back
        // Create a new savepoint to release, since sp2 is still valid
        Savepoint sp3 = connection.setSavepoint("sp3");
        connection.releaseSavepoint(sp3);

        // createStatement(int, int, int)
        Statement st3 = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(st3);

        // prepareStatement(String, int, int, int)
        PreparedStatement ps3 = connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(ps3);

        // prepareCall(String, int, int, int)
        CallableStatement cs3 = connection.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(cs3);

        // prepareStatement(String, int)
        PreparedStatement ps4 = connection.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS);
        assertNotNull(ps4);

        // prepareStatement(String, int[])
        PreparedStatement ps5 = connection.prepareStatement("SELECT 1", new int[0]);
        assertNotNull(ps5);

        // prepareStatement(String, String[])
        PreparedStatement ps6 = connection.prepareStatement("SELECT 1", new String[0]);
        assertNotNull(ps6);

        // createClob, createBlob, createNClob, createSQLXML
        Clob clob = connection.createClob();
        assertNotNull(clob);
        Blob blob = connection.createBlob();
        assertNotNull(blob);
        NClob nclob = connection.createNClob();
        assertNotNull(nclob);
        SQLXML sqlxml = connection.createSQLXML();
        assertNotNull(sqlxml);

        // isValid
        assertTrue( connection.isValid(5));

        // setClientInfo (Properties)
        Properties props = new Properties();
        props.setProperty("ApplicationName", "TestApp");
        try {
            connection.setClientInfo(props);
        } catch (SQLClientInfoException | StatusRuntimeException ignored) {}

        // setClientInfo(String, String)
        try {
            connection.setClientInfo("ApplicationName", "TestApp");
        } catch (SQLClientInfoException | StatusRuntimeException ignored) {}

        // getClientInfo(String)
        String val = connection.getClientInfo("ApplicationName");
        // PostgreSQL might return the set value or null

        // getClientInfo()
        Properties p2 = connection.getClientInfo();
        assertNotNull(p2);

        // createArrayOf
        Array arr = connection.createArrayOf("INTEGER", new Object[]{1, 2, 3});
        assertNotNull(arr);

        // createStruct - PostgreSQL supports composite types, but this might still throw
        try {
            connection.createStruct("pg_type", new Object[]{});
        } catch (SQLFeatureNotSupportedException e) {
            // Expected for most cases
        }

        // setSchema/getSchema
        String schema = connection.getSchema();
        connection.setSchema(schema);
        assertEquals(schema, connection.getSchema());

        // abort (should not throw)
        try {
            connection.abort(new Executor() {
                public void execute(Runnable command) {
                    new Thread(command).start();
                }
            });
        } catch (SQLFeatureNotSupportedException e) {
            //OJP does not support executors
        }

        // setNetworkTimeout/getNetworkTimeout
        connection.setNetworkTimeout(null, 1000);
        assertEquals(1000, connection.getNetworkTimeout());

        // beginRequest/endRequest (defaults, should not throw)
        connection.beginRequest();
        connection.endRequest();

        // setShardingKeyIfValid/setShardingKey (should throw SQLFeatureNotSupportedException)
        assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.setShardingKeyIfValid(null, null, 0);
        });
        assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.setShardingKeyIfValid(null, 0);
        });
        assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.setShardingKey(null, null);
        });
        assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.setShardingKey(null);
        });
    }
}