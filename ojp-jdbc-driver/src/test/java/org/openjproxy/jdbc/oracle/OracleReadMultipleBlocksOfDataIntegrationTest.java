package org.openjproxy.jdbc.oracle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Oracle-specific multiple blocks of data integration tests.
 * Tests Oracle pagination and large result set handling.
 */
class OracleReadMultipleBlocksOfDataIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections_with_record_counts.csv")
    void multiplePagesOfRowsResultSetSuccessful(int totalRecords, String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle retrieving " + totalRecords + " records from url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table with Oracle-specific syntax
        executeUpdate(conn, "create table oracle_read_blocks_test_multi(" +
                "id NUMBER(10) NOT NULL, " +
                "title VARCHAR2(50) NOT NULL)");

        for (int i = 0; i < totalRecords; i++) {
            executeUpdate(conn,
                    "insert into oracle_read_blocks_test_multi (id, title) values (" + i + ", 'ORACLE_TITLE_" + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from oracle_read_blocks_test_multi order by id");
        ResultSet resultSet = psSelect.executeQuery();

        for (int i = 0; i < totalRecords; i++) {
            resultSet.next();
            int id = resultSet.getInt(1);
            String title = resultSet.getString(2);
            assertEquals(i, id);
            assertEquals("ORACLE_TITLE_" + i, title);
        }

        executeUpdate(conn, "delete from oracle_read_blocks_test_multi");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleLargeDataSetPagination(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle large dataset pagination for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_pagination_test");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table with Oracle-specific data types
        executeUpdate(conn, "create table oracle_pagination_test(" +
                "id NUMBER(10) PRIMARY KEY, " +
                "name VARCHAR2(100) NOT NULL, " +
                "value NUMBER(19,2), " +
                "description CLOB)");

        // Insert 5000 records for pagination testing
        int totalRecords = 5000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into oracle_pagination_test (id, name, value, description) values (" + 
                    i + ", 'Oracle_Name_" + i + "', " + (i * 10.5) + ", 'Description for record " + i + "')"
            );
        }

        // Test pagination with ROWNUM (Oracle-specific)
        java.sql.PreparedStatement psPage1 = conn.prepareStatement(
                "SELECT * FROM (SELECT id, name, value, description FROM oracle_pagination_test ORDER BY id) WHERE ROWNUM <= 1000");
        ResultSet page1 = psPage1.executeQuery();
        
        int count = 0;
        while (page1.next()) {
            count++;
            assertTrue(page1.getInt("id") <= 1000);
        }
        assertEquals(1000, count);

        // Test pagination with OFFSET/FETCH (Oracle 12c+)
        java.sql.PreparedStatement psPage2 = conn.prepareStatement(
                "SELECT id, name, value, description FROM oracle_pagination_test ORDER BY id OFFSET 1000 ROWS FETCH NEXT 1000 ROWS ONLY");
        ResultSet page2 = psPage2.executeQuery();
        
        count = 0;
        while (page2.next()) {
            count++;
            int id = page2.getInt("id");
            assertTrue(id > 1000 && id <= 2000);
        }
        assertEquals(1000, count);

        executeUpdate(conn, "delete from oracle_pagination_test");

        page1.close();
        page2.close();
        psPage1.close();
        psPage2.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleResultSetScrolling(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle ResultSet scrolling for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_scroll_test");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table with Oracle NUMBER and VARCHAR2 types
        executeUpdate(conn, "create table oracle_scroll_test(" +
                "id NUMBER(10) PRIMARY KEY, " +
                "data VARCHAR2(100))");

        // Insert test data
        int totalRecords = 100;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into oracle_scroll_test (id, data) values (" + i + ", 'Oracle Data " + i + "')"
            );
        }

        // Create scrollable ResultSet
        java.sql.Statement scrollableStmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, 
                ResultSet.CONCUR_READ_ONLY);
        
        ResultSet scrollableRs = scrollableStmt.executeQuery(
                "SELECT id, data FROM oracle_scroll_test ORDER BY id");

        // Test forward navigation
        assertTrue(scrollableRs.next());
        assertEquals(1, scrollableRs.getInt("id"));

        // Test jumping to specific position
        assertTrue(scrollableRs.absolute(50));
        assertEquals(50, scrollableRs.getInt("id"));

        // Test backward navigation
        assertTrue(scrollableRs.previous());
        assertEquals(49, scrollableRs.getInt("id"));

        // Test last position
        assertTrue(scrollableRs.last());
        assertEquals(totalRecords, scrollableRs.getInt("id"));

        // Test first position
        assertTrue(scrollableRs.first());
        assertEquals(1, scrollableRs.getInt("id"));

        executeUpdate(conn, "delete from oracle_scroll_test");

        scrollableRs.close();
        scrollableStmt.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleMultipleDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle multiple data types in large result set for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_multi_types_test");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table with various Oracle data types
        executeUpdate(conn, "create table oracle_multi_types_test(" +
                "id NUMBER(10) PRIMARY KEY, " +
                "int_col NUMBER(10), " +
                "decimal_col NUMBER(19,2), " +
                "varchar_col VARCHAR2(100), " +
                "char_col CHAR(10), " +
                "date_col DATE, " +
                "timestamp_col TIMESTAMP, " +
                "clob_col CLOB)");

        // Insert records with various data types
        int totalRecords = 1000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into oracle_multi_types_test " +
                    "(id, int_col, decimal_col, varchar_col, char_col, date_col, timestamp_col, clob_col) values (" + 
                    i + ", " + (i * 10) + ", " + (i * 100.5) + ", 'Varchar " + i + "', 'Char" + i + "', " +
                    "DATE '2023-01-01', TIMESTAMP '2023-01-01 12:00:00', 'CLOB data for record " + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement(
                "SELECT * FROM oracle_multi_types_test ORDER BY id");
        ResultSet resultSet = psSelect.executeQuery();

        int count = 0;
        while (resultSet.next()) {
            count++;
            int id = resultSet.getInt("id");
            assertEquals(count, id);
            assertEquals(id * 10, resultSet.getInt("int_col"));
            assertEquals(id * 100.5, resultSet.getDouble("decimal_col"), 0.01);
            assertEquals("Varchar " + id, resultSet.getString("varchar_col"));
            assertNotNull(resultSet.getDate("date_col"));
            assertNotNull(resultSet.getTimestamp("timestamp_col"));
            assertEquals("CLOB data for record " + id, resultSet.getString("clob_col"));
        }

        assertEquals(totalRecords, count);

        executeUpdate(conn, "delete from oracle_multi_types_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}