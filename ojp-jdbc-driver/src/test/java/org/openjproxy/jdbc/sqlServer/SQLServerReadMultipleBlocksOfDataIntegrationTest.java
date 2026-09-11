package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific tests for reading multiple blocks of data.
 * Tests SQL Server-specific large result set handling and streaming capabilities.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerReadMultipleBlocksOfDataIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerLargeResultSetReading(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server large result set for url -> " + url);

        // Create table for large result set test
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_large_resultset_test', 'U') IS NOT NULL DROP TABLE sqlserver_large_resultset_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_large_resultset_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "text_data NVARCHAR(255), " +
                "number_data INT)");

        // Insert a large number of rows
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_large_resultset_test (text_data, number_data) VALUES (?, ?)"
        );

        final int TOTAL_ROWS = 1000;
        for (int i = 1; i <= TOTAL_ROWS; i++) {
            psInsert.setString(1, "Row " + i + " data");
            psInsert.setInt(2, i * 10);
            psInsert.addBatch();
            
            if (i % 100 == 0) {
                psInsert.executeBatch();
                psInsert.clearBatch();
            }
        }
        psInsert.executeBatch(); // Execute remaining batch
        psInsert.close();

        // Read all data back to test multiple block reading
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_large_resultset_test ORDER BY id");

        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            int id = rs.getInt("id");
            String textData = rs.getString("text_data");
            int numberData = rs.getInt("number_data");
            
            assertEquals(rowCount, id);
            assertEquals("Row " + rowCount + " data", textData);
            assertEquals(rowCount * 10, numberData);
        }

        assertEquals(TOTAL_ROWS, rowCount);

        rs.close();
        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_resultset_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerStreamingLargeData(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server streaming large data for url -> " + url);

        // Create table with large data types
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_streaming_test', 'U') IS NOT NULL DROP TABLE sqlserver_streaming_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_streaming_test (" +
                "id INT PRIMARY KEY, " +
                "large_text NVARCHAR(MAX), " +
                "large_binary VARBINARY(MAX))");

        // Create large text and binary data
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeText.append("This is line ").append(i).append(" of the large text data. ");
        }
        String largeTextStr = largeText.toString();

        byte[] largeBinary = new byte[50000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        // Insert large data using streaming
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_streaming_test (id, large_text, large_binary) VALUES (?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, largeTextStr);
        psInsert.setBinaryStream(3, new ByteArrayInputStream(largeBinary));
        psInsert.executeUpdate();
        psInsert.close();

        // Read back using streaming
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_streaming_test WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());
        
        // Verify large text
        String retrievedText = rs.getString("large_text");
        assertEquals(largeTextStr.length(), retrievedText.length());
        assertTrue(retrievedText.contains("This is line 0 of the large text data."));
        assertTrue(retrievedText.contains("This is line 9999 of the large text data."));

        // Verify large binary using stream
        InputStream binaryStream = rs.getBinaryStream("large_binary");
        assertNotNull(binaryStream);
        
        byte[] retrievedBinary = binaryStream.readAllBytes();
        assertEquals(largeBinary.length, retrievedBinary.length);
        
        // Verify binary data integrity (sample check)
        for (int i = 0; i < 1000; i += 100) {
            assertEquals(largeBinary[i], retrievedBinary[i]);
        }

        binaryStream.close();
        rs.close();
        psSelect.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_streaming_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerPaginatedReading(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server paginated reading for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_pagination_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_pagination_test (id, name) VALUES (?, ?)"
        );

        final int TOTAL_ROWS = 100;
        for (int i = 1; i <= TOTAL_ROWS; i++) {
            psInsert.setInt(1, i);
            psInsert.setString(2, "Name " + i);
            psInsert.addBatch();
        }
        psInsert.executeBatch();
        psInsert.close();

        // Test SQL Server-specific pagination using OFFSET/FETCH
        final int PAGE_SIZE = 10;
        int totalRetrieved = 0;

        for (int page = 0; page < (TOTAL_ROWS / PAGE_SIZE); page++) {
            int offset = page * PAGE_SIZE;
            
            PreparedStatement psSelect = conn.prepareStatement(
                    "SELECT id, name FROM sqlserver_pagination_test " +
                    "ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
            );
            psSelect.setInt(1, offset);
            psSelect.setInt(2, PAGE_SIZE);
            
            ResultSet rs = psSelect.executeQuery();
            
            int pageCount = 0;
            while (rs.next()) {
                pageCount++;
                totalRetrieved++;
                
                int expectedId = offset + pageCount;
                assertEquals(expectedId, rs.getInt("id"));
                assertEquals("Name " + expectedId, rs.getString("name"));
            }
            
            assertEquals(PAGE_SIZE, pageCount);
            rs.close();
            psSelect.close();
        }

        assertEquals(TOTAL_ROWS, totalRetrieved);
        TestDBUtils.cleanupTestTables(conn, "sqlserver_pagination_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerCursorBasedReading(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server cursor-based reading for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_cursor_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_cursor_test (id, name) VALUES (?, ?)"
        );

        final int TOTAL_ROWS = 50;
        for (int i = 1; i <= TOTAL_ROWS; i++) {
            psInsert.setInt(1, i);
            psInsert.setString(2, "Cursor Test " + i);
            psInsert.addBatch();
        }
        psInsert.executeBatch();
        psInsert.close();

        // Test with different fetch sizes
        Statement stmt = conn.createStatement();
        stmt.setFetchSize(10); // Set fetch size for cursor-based reading
        
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_cursor_test ORDER BY id");

        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            assertEquals(rowCount, rs.getInt("id"));
            assertEquals("Cursor Test " + rowCount, rs.getString("name"));
        }

        assertEquals(TOTAL_ROWS, rowCount);

        rs.close();
        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_cursor_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerConcurrentReading(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server concurrent reading for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_concurrent_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_concurrent_test (id, name) VALUES (?, ?)"
        );

        final int TOTAL_ROWS = 20;
        for (int i = 1; i <= TOTAL_ROWS; i++) {
            psInsert.setInt(1, i);
            psInsert.setString(2, "Concurrent " + i);
            psInsert.addBatch();
        }
        psInsert.executeBatch();
        psInsert.close();

        // Test multiple concurrent result sets
        Statement stmt1 = conn.createStatement();
        Statement stmt2 = conn.createStatement();

        ResultSet rs1 = stmt1.executeQuery("SELECT * FROM sqlserver_concurrent_test WHERE id <= 10 ORDER BY id");
        ResultSet rs2 = stmt2.executeQuery("SELECT * FROM sqlserver_concurrent_test WHERE id > 10 ORDER BY id");

        // Read from both result sets concurrently
        int count1 = 0, count2 = 0;
        boolean hasNext1 = rs1.next();
        boolean hasNext2 = rs2.next();

        while (hasNext1 || hasNext2) {
            if (hasNext1) {
                count1++;
                assertEquals(count1, rs1.getInt("id"));
                hasNext1 = rs1.next();
            }
            
            if (hasNext2) {
                count2++;
                assertEquals(10 + count2, rs2.getInt("id"));
                hasNext2 = rs2.next();
            }
        }

        assertEquals(10, count1);
        assertEquals(10, count2);

        rs1.close();
        rs2.close();
        stmt1.close();
        stmt2.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_concurrent_test");
        conn.close();
    }
}