package org.openjproxy.jdbc;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BasicCrudIntegrationTest {

    private static boolean isH2TestEnabled;
    private static boolean isPostgresTestEnabled;
    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static boolean isCockroachDBTestEnabled;
    private static boolean isOracleTestEnabled;
    private static boolean isSqlServerTestEnabled;
    private static boolean isDb2TestEnabled;
    private static String tablePrefix = "";

    @BeforeAll
     static void setup() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isCockroachDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
        isSqlServerTestEnabled = Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv")
     void crudTestSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        // Skip H2 tests if not enabled
        if (url.toLowerCase().contains("_h2:") && !isH2TestEnabled) {
            Assumptions.assumeFalse(true, "Skipping H2 tests");
        }

        // Skip PostgreSQL tests if not enabled
        if (url.toLowerCase().contains("postgresql") && !isPostgresTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping Postgres tests");
        }

        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping MySQL tests");
        }

        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping MariaDB tests");
        }

        // Skip Oracle tests if not enabled
        if (url.toLowerCase().contains("oracle") && !isOracleTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping Oracle tests - not enabled");
        }

        // Skip SQL Server tests if not enabled
        if (url.toLowerCase().contains("sqlserver") && !isSqlServerTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping SQL Server tests - not enabled");
        }

        // Skip DB2 tests if not enabled
        if (url.toLowerCase().contains("db2") && !isDb2TestEnabled) {
            Assumptions.assumeFalse(true, "Skipping DB2 tests - not enabled");
        }

        // Skip CockroachDB tests if not enabled
        if (url.toLowerCase().contains("26257") && !isCockroachDBTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping CockroachDB tests");
        }

        // Set table prefix based on database type
        if (url.toLowerCase().contains("postgresql")) {
            tablePrefix = "postgres_";
        } else if (url.toLowerCase().contains("mysql")) {
            tablePrefix = "mysql_";
        } else if (url.toLowerCase().contains("mariadb")) {
            tablePrefix = "mariadb_";
        } else if (url.toLowerCase().contains("oracle")) {
            tablePrefix = "oracle_";
        } else if (url.toLowerCase().contains("sqlserver")) {
            url = SQLServerConnectionProvider.getOjpProxyAddress();
            tablePrefix = "sqlserver_" + (isXA ? "xa_" : "nonxa_");
        } else if (url.toLowerCase().contains("db2")) {
            tablePrefix = "db2_";
        } else if (url.toLowerCase().contains("26257")) {
            tablePrefix = "cockroachdb_";
        } else {
            tablePrefix = "h2_";
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        // For non-XA connections, set autocommit to false for explicit transaction control
        if (!isXA) {
            conn.setAutoCommit(false);
        }

        // Set schema for DB2 connections to avoid "object not found" errors
        if (url.toLowerCase().contains("db2")) {
            try (java.sql.Statement schemaStmt = conn.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
        }

        System.out.println("Testing for url -> " + url);

        // Use qualified table names for DB2
        String tableName = tablePrefix + "basic_crud_test";
        if (url.toLowerCase().contains("db2")) {
            tableName = "DB2INST1." + tableName;
        }

        try {
            executeUpdate(conn, "drop table " + tableName);
            connResult.commit();
        } catch (Exception e) {
            //Does not matter - table might not exist
            try {
                connResult.rollback();
            } catch (Exception ex) {
                // Ignore rollback errors
            }
        }

        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        executeUpdate(conn, "create table " + tableName + "(" +
                "id INT NOT NULL," +
                "title VARCHAR(50) NOT NULL" +
                ")");
        connResult.commit();

        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        executeUpdate(conn, " insert into " + tableName + " (id, title) values (1, 'TITLE_1')");
        connResult.commit();

        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from " + tableName + " where id = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        int id = resultSet.getInt(1);
        String title = resultSet.getString(2);
        assertEquals(1, id);
        assertEquals("TITLE_1", title);

        executeUpdate(conn, "update " + tableName + " set title='TITLE_1_UPDATED'");
        connResult.commit();

        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        ResultSet resultSetUpdated = psSelect.executeQuery();
        resultSetUpdated.next();
        int idUpdated = resultSetUpdated.getInt(1);
        String titleUpdated = resultSetUpdated.getString(2);
        assertEquals(1, idUpdated);
        assertEquals("TITLE_1_UPDATED", titleUpdated);

        executeUpdate(conn, " delete from " + tableName + " where id=1 and title='TITLE_1_UPDATED'");
        connResult.commit();

        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();

        // Clean up - drop the test table
        try {
            executeUpdate(conn, "drop table " + tableName);
            connResult.commit();
        } catch (Exception e) {
            // Ignore cleanup errors
        }

        connResult.close();
    }

}
