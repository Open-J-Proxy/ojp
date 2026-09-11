package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * DB2-specific multiple blocks of data integration tests.
 * Tests DB2 pagination and large result set handling.
 */
class Db2ReadMultipleBlocksOfDataIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connections_with_record_counts.csv")
    void multiplePagesOfRowsResultSetSuccessful(int totalRecords, String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 retrieving " + totalRecords + " records from url -> " + url);

        try {
            executeUpdate(conn, "drop table DB2INST1.db2_read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with DB2-specific syntax
        executeUpdate(conn, "create table DB2INST1.db2_read_blocks_test_multi(" +
                "id INTEGER NOT NULL, " +
                "title VARCHAR(50) NOT NULL)");

        for (int i = 0; i < totalRecords; i++) {
            executeUpdate(conn,
                    "insert into db2_read_blocks_test_multi (id, title) values (" + i + ", 'DB2_TITLE_" + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from db2_read_blocks_test_multi order by id");
        ResultSet resultSet = psSelect.executeQuery();

        for (int i = 0; i < totalRecords; i++) {
            resultSet.next();
            int id = resultSet.getInt(1);
            String title = resultSet.getString(2);
            assertEquals(i, id);
            assertEquals("DB2_TITLE_" + i, title);
        }

        executeUpdate(conn, "delete from db2_read_blocks_test_multi");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2LargeDataSetPagination(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 large dataset pagination for url -> " + url);

        try {
            executeUpdate(conn, "drop table db2_pagination_test");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with DB2-specific data types
        executeUpdate(conn, "create table db2_pagination_test(" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "name VARCHAR(100) NOT NULL, " +
                "value DECIMAL(19,2), " +
                "description CLOB(1M))");

        // Insert 5000 records for pagination testing
        int totalRecords = 5000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into db2_pagination_test (id, name, value, description) values (" +
                            i + ", 'DB2_Name_" + i + "', " + (i * 10.5) + ", 'Description for record " + i + "')"
            );
        }

        // Test pagination with LIMIT/OFFSET (DB2 v9.7+)
        java.sql.PreparedStatement psPage1 = conn.prepareStatement(
                "SELECT id, name, value, description FROM db2_pagination_test ORDER BY id LIMIT 1000");
        ResultSet page1 = psPage1.executeQuery();

        int count = 0;
        while (page1.next()) {
            count++;
            assertTrue(page1.getInt("id") <= 1000);
        }
        assertEquals(1000, count);

        // Test pagination with OFFSET/FETCH
        java.sql.PreparedStatement psPage2 = conn.prepareStatement(
                "SELECT id, name, value, description FROM db2_pagination_test ORDER BY id OFFSET 1000 ROWS FETCH NEXT 1000 ROWS ONLY");
        ResultSet page2 = psPage2.executeQuery();

        count = 0;
        while (page2.next()) {
            count++;
            int id = page2.getInt("id");
            assertTrue(id > 1000 && id <= 2000);
        }
        assertEquals(1000, count);

        executeUpdate(conn, "delete from db2_pagination_test");

        page1.close();
        page2.close();
        psPage1.close();
        psPage2.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2ResultSetScrolling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 ResultSet scrolling for url -> " + url);

        try {
            executeUpdate(conn, "drop table db2_scroll_test");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with DB2 INTEGER and VARCHAR types
        executeUpdate(conn, "create table db2_scroll_test(" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "data VARCHAR(100))");

        // Insert test data
        int totalRecords = 100;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into db2_scroll_test (id, data) values (" + i + ", 'DB2 Data " + i + "')"
            );
        }

        // Create scrollable ResultSet
        java.sql.Statement scrollableStmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY);

        ResultSet scrollableRs = scrollableStmt.executeQuery(
                "SELECT id, data FROM db2_scroll_test ORDER BY id");

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

        executeUpdate(conn, "delete from db2_scroll_test");

        scrollableRs.close();
        scrollableStmt.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2MultipleDataTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 multiple data types in large result set for url -> " + url);

        try {
            executeUpdate(conn, "drop table db2_multi_types_test");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with various DB2 data types
        executeUpdate(conn, "create table db2_multi_types_test(" +
                "id INTEGER  NOT NULL PRIMARY KEY, " +
                "int_col INTEGER, " +
                "decimal_col DECIMAL(19,2), " +
                "varchar_col VARCHAR(100), " +
                "char_col CHAR(10), " +
                "date_col DATE, " +
                "timestamp_col TIMESTAMP, " +
                "clob_col CLOB(1M))");

        // Insert records with various data types
        int totalRecords = 1000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "insert into db2_multi_types_test " +
                            "(id, int_col, decimal_col, varchar_col, char_col, date_col, timestamp_col, clob_col) values (" +
                            i + ", " + (i * 10) + ", " + (i * 100.5) + ", 'Varchar " + i + "', 'Char" + i + "', " +
                            "DATE('2023-01-01'), TIMESTAMP('2023-01-01-12.00.00'), 'CLOB data for record " + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement(
                "SELECT * FROM db2_multi_types_test ORDER BY id");
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

        executeUpdate(conn, "delete from db2_multi_types_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}