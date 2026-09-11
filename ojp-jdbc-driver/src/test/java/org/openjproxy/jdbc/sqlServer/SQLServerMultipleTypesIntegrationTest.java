package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerMultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    /**
     * Test SQL Server's natively supported java.time types via JDBC 4.2.
     * SQL Server has first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME)
     * - LocalDateTime (DATETIME2/TIMESTAMP)
     * 
     * Note: SQL Server has DATETIMEOFFSET for timezone-aware types,
     * so OffsetDateTime/OffsetTime/Instant are tested in partial support test.
     */
    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server natively supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_multi_types_test", TestDBUtils.SqlSyntax.SQLSERVER);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 255); // SQL Server TINYINT is 0-255
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true); // SQL Server BIT type
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // SQL Server VARBINARY
        psInsert.setBytes(11, "AAAA".getBytes()); // SQL Server VARBINARY
        
        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // SQL Server natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant, OffsetDateTime, OffsetTime: NOT natively supported in SQL Server JDBC 4.2
        // SQL Server has DATETIMEOFFSET but requires special handling
        // Setting to null - will be tested in partial support test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not first-class
        psInsert.setObject(19, null, Types.TIMESTAMP); // OffsetDateTime - not first-class
        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not first-class
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", ""+resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(255, resultSet.getInt(5)); // SQL Server TINYINT max value
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7)); // SQL Server BIT
        assertEquals(1, resultSet.getInt(7)); // SQL Server BIT as int
        assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal(8));
        assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        
        // Validate binary columns
        validateSQLServerBinaryColumns(resultSet);
        
        // Validate columns 12, 13, 14 using getObject with java.time types
        Object valDateRet = resultSet.getObject(12);
        Object valTimeRet = resultSet.getObject(13);
        Object valTimestampRet = resultSet.getObject(14);
        
        assertNotNull(valDateRet, "Date column should not be null");
        assertNotNull(valTimeRet, "Time column should not be null");
        assertNotNull(valTimestampRet, "Timestamp column should not be null");
        
        // Validate date (column 12)
        if (valDateRet instanceof LocalDate) {
            assertEquals(valDate, valDateRet);
        } else if (valDateRet instanceof Date) {
            LocalDate retrievedDate = ((Date) valDateRet).toLocalDate();
            assertEquals(valDate, retrievedDate);
        }
        
        // Validate time (column 13)
        if (valTimeRet instanceof LocalTime) {
            LocalTime retrievedTime = (LocalTime) valTimeRet;
            assertEquals(valTime.getHour(), retrievedTime.getHour());
            assertEquals(valTime.getMinute(), retrievedTime.getMinute());
            assertEquals(valTime.getSecond(), retrievedTime.getSecond());
        } else if (valTimeRet instanceof Time) {
            LocalTime retrievedTime = ((Time) valTimeRet).toLocalTime();
            assertEquals(valTime.getHour(), retrievedTime.getHour());
            assertEquals(valTime.getMinute(), retrievedTime.getMinute());
            assertEquals(valTime.getSecond(), retrievedTime.getSecond());
        }
        
        // Validate timestamp (column 14)
        if (valTimestampRet instanceof LocalDateTime) {
            assertEquals(valTimestamp, valTimestampRet);
        } else if (valTimestampRet instanceof Timestamp) {
            LocalDateTime retrievedTimestamp = ((Timestamp) valTimestampRet).toLocalDateTime();
            assertEquals(valTimestamp, retrievedTimestamp);
        }
        
        // Validate SQL Server's natively supported java.time types
        validateSQLServerJavaTimeTypes(resultSet, valLocalDateTime, valLocalDate, valLocalTime);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_multi_types_test");
        conn.close();
    }

    /**
     * Test SQL Server's behavior with java.time types that require special handling.
     * SQL Server has DATETIMEOFFSET for timezone-aware types but JDBC 4.2 support varies:
     * - Instant (can be stored but driver doesn't directly support)
     * - OffsetDateTime (can use DATETIMEOFFSET but requires special handling)
     * - OffsetTime (no native TIME WITH TIMEZONE equivalent)
     * 
     * This test documents expected database behavior when these types are used.
     */
    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server partially supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_partial_types_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Test Instant - SQL Server JDBC driver may not support directly
        java.sql.PreparedStatement psInsertInstant = conn.prepareStatement(
                "insert into sqlserver_partial_types_test (val_int, val_instant) values (?, ?)"
        );
        
        psInsertInstant.setInt(1, 1);
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        
        // Attempt to insert Instant - behavior depends on driver version
        try {
            psInsertInstant.setObject(2, valInstant, Types.TIMESTAMP);
            psInsertInstant.executeUpdate();
            System.out.println("SQL Server: Instant insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from sqlserver_partial_types_test where val_int = 1"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("SQL Server: Instant retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "Instant should be retrieved (possibly as Timestamp)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: SQL Server driver may not support Instant directly
            System.out.println("SQL Server: Instant not natively supported - " + e.getMessage());
            // SQL Server JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertInstant.close();
        TestDBUtils.executeUpdate(conn, "delete from sqlserver_partial_types_test where val_int=1");

        // Test OffsetDateTime - SQL Server has DATETIMEOFFSET but JDBC 4.2 support varies
        java.sql.PreparedStatement psInsertOffsetDateTime = conn.prepareStatement(
                "insert into sqlserver_partial_types_test (val_int, val_offsetdatetime) values (?, ?)"
        );
        
        psInsertOffsetDateTime.setInt(1, 2);
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        
        // Attempt to insert OffsetDateTime
        try {
            psInsertOffsetDateTime.setObject(2, valOffsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);
            psInsertOffsetDateTime.executeUpdate();
            System.out.println("SQL Server: OffsetDateTime insertion succeeded");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsetdatetime from sqlserver_partial_types_test where val_int = 2"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("SQL Server: OffsetDateTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "OffsetDateTime should be retrieved");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: SQL Server driver may require special handling
            System.out.println("SQL Server: OffsetDateTime requires special handling - " + e.getMessage());
            // SQL Server JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetDateTime.close();
        TestDBUtils.executeUpdate(conn, "delete from sqlserver_partial_types_test where val_int=2");

        // Test OffsetTime - SQL Server lacks TIME WITH TIMEZONE
        java.sql.PreparedStatement psInsertOffsetTime = conn.prepareStatement(
                "insert into sqlserver_partial_types_test (val_int, val_offsettime) values (?, ?)"
        );
        
        psInsertOffsetTime.setInt(1, 3);
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        
        // Attempt to insert OffsetTime
        try {
            psInsertOffsetTime.setObject(2, valOffsetTime, Types.TIME_WITH_TIMEZONE);
            psInsertOffsetTime.executeUpdate();
            System.out.println("SQL Server: OffsetTime insertion succeeded with lossy conversion");
            
            // If it succeeded, timezone info is likely lost
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsettime from sqlserver_partial_types_test where val_int = 3"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("SQL Server: OffsetTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null") +
                        " (timezone likely lost)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: SQL Server may not support OffsetTime
            System.out.println("SQL Server: OffsetTime not supported - " + e.getMessage());
            // SQL Server JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetTime.close();
        TestDBUtils.executeUpdate(conn, "delete from sqlserver_partial_types_test where val_int=3");

        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_partial_types_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server specific types for url -> " + url);

        // Create table with SQL Server-specific types
        TestDBUtils.createSqlServerSpecificTestTable(conn, "sqlserver_specific_types_test");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_specific_types_test (ntext_col, text_col, money_col, smallmoney_col, " +
                        "uniqueidentifier_col, datetimeoffset_col, datetime2_col, smalldatetime_col) " +
                        "values (?, ?, ?, ?, NEWID(), ?, ?, ?)"
        );

        psInsert.setString(1, "NTEXT content with Unicode: 中文 🚀");
        psInsert.setString(2, "TEXT content");
        psInsert.setBigDecimal(3, new BigDecimal("123.45")); // MONEY
        psInsert.setBigDecimal(4, new BigDecimal("67.89")); // SMALLMONEY
        // UNIQUEIDENTIFIER is auto-generated with NEWID()
        psInsert.setTimestamp(5, new Timestamp(System.currentTimeMillis())); // DATETIMEOFFSET
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis())); // DATETIME2
        psInsert.setTimestamp(7, new Timestamp(System.currentTimeMillis())); // SMALLDATETIME

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_specific_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        
        // Verify NTEXT with Unicode
        String ntextValue = resultSet.getString("ntext_col");
        assertEquals("NTEXT content with Unicode: 中文 🚀", ntextValue);
        
        // Verify TEXT
        String textValue = resultSet.getString("text_col");
        assertEquals("TEXT content", textValue);
        
        // Verify MONEY
        BigDecimal moneyValue = resultSet.getBigDecimal("money_col");
        assertEquals(new BigDecimal("123.4500"), moneyValue);
        
        // Verify SMALLMONEY
        BigDecimal smallmoneyValue = resultSet.getBigDecimal("smallmoney_col");
        assertEquals(new BigDecimal("67.8900"), smallmoneyValue);
        
        // Verify UNIQUEIDENTIFIER was generated
        String guidValue = resultSet.getString("uniqueidentifier_col");
        assertNotNull(guidValue);
        assertTrue(guidValue.length() > 30); // GUID format
        
        // Verify date/time types are not null
        assertNotNull(resultSet.getObject("datetimeoffset_col", java.time.OffsetDateTime.class));
        assertNotNull(resultSet.getTimestamp("datetime2_col"));
        assertNotNull(resultSet.getTimestamp("smalldatetime_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_specific_types_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNullValues(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server null values for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_null_test", TestDBUtils.SqlSyntax.SQLSERVER);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_null_test (val_int, val_varchar) values (?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "Test");
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_null_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        // Verify non-null values
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("Test", resultSet.getString("val_varchar"));
        
        // Verify null values for columns not inserted
        assertEquals(0.0, resultSet.getDouble("val_double_precision"), 0.0);
        assertTrue(resultSet.wasNull());
        
        assertEquals(0L, resultSet.getLong("val_bigint"));
        assertTrue(resultSet.wasNull());
        
        assertNull(resultSet.getBytes("val_byte"));
        assertNull(resultSet.getDate("val_date"));
        assertNull(resultSet.getTime("val_time"));
        assertNull(resultSet.getTimestamp("val_timestamp"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerLargeTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server large types for url -> " + url);

        // Create table with large types
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_large_types_test', 'U') IS NOT NULL DROP TABLE sqlserver_large_types_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_large_types_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "nvarchar_max NVARCHAR(MAX), " +
                "varchar_max VARCHAR(MAX), " +
                "varbinary_max VARBINARY(MAX))");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_large_types_test (nvarchar_max, varchar_max, varbinary_max) values (?, ?, ?)"
        );

        // Create large text data
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("This is a large text string for testing purposes. ");
        }
        String largeTextStr = largeText.toString();
        
        // Create large binary data
        byte[] largeBinary = new byte[10000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        psInsert.setString(1, largeTextStr + " Unicode: 中文 🚀"); // NVARCHAR(MAX)
        psInsert.setString(2, largeTextStr); // VARCHAR(MAX)
        psInsert.setBytes(3, largeBinary); // VARBINARY(MAX)

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_large_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        
        // Verify large NVARCHAR(MAX)
        String nvarcharValue = resultSet.getString("nvarchar_max");
        assertTrue(nvarcharValue.length() > 40000);
        assertTrue(nvarcharValue.contains("Unicode: 中文 🚀"));
        
        // Verify large VARCHAR(MAX)
        String varcharValue = resultSet.getString("varchar_max");
        assertTrue(varcharValue.length() > 40000);
        
        // Verify large VARBINARY(MAX)
        byte[] varbinaryValue = resultSet.getBytes("varbinary_max");
        assertNotNull(varbinaryValue);
        assertEquals(10000, varbinaryValue.length);
        
        // Verify binary data integrity
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            assertEquals((byte) (i % 256), varbinaryValue[i]);
        }

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_types_test");
        conn.close();
    }
    
    /**
     * Helper method to validate SQL Server VARBINARY columns.
     * Extracts assertion logic to reduce cognitive complexity.
     */
    private void validateSQLServerBinaryColumns(ResultSet resultSet) throws SQLException {
        byte[] byteValue = resultSet.getBytes(10);
        Assertions.assertNotNull(byteValue, "VARBINARY column should not be null");
        assertEquals(1, byteValue.length);
        assertEquals((byte) 1, byteValue[0]);
        
        byte[] binaryValue = resultSet.getBytes(11);
        Assertions.assertNotNull(binaryValue, "VARBINARY column should not be null");
        assertEquals("AAAA", new String(binaryValue));
    }
    
    /**
     * Helper method to validate SQL Server java.time types.
     * Extracts assertion logic to reduce cognitive complexity.
     */
    private void validateSQLServerJavaTimeTypes(ResultSet resultSet, LocalDateTime expectedLdt, 
                                                LocalDate expectedLd, LocalTime expectedLt) throws SQLException {
        // SQL Server natively supported java.time types - retrieve as Object to get the actual type
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        // Columns 18-20 (Instant, OffsetDateTime, OffsetTime) are null - not tested in success scenario
        
        // Validate SQL Server's natively supported java.time types (JDBC 4.2)
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        
        // SQL Server JDBC driver should return actual java.time types per JDBC 4.2
        // For LocalDateTime (DATETIME2/TIMESTAMP)
        if (valLocalDateTimeRet instanceof LocalDateTime) {
            assertEquals(expectedLdt, valLocalDateTimeRet);
        } else if (valLocalDateTimeRet instanceof Timestamp) {
            LocalDateTime retrievedLdt = ((Timestamp) valLocalDateTimeRet).toLocalDateTime();
            assertEquals(expectedLdt, retrievedLdt);
        }
        
        // For LocalDate (DATE)
        if (valLocalDateRet instanceof LocalDate) {
            assertEquals(expectedLd, valLocalDateRet);
        } else if (valLocalDateRet instanceof Date) {
            LocalDate retrievedLd = ((Date) valLocalDateRet).toLocalDate();
            assertEquals(expectedLd, retrievedLd);
        }
        
        // For LocalTime (TIME)
        if (valLocalTimeRet instanceof LocalTime) {
            LocalTime retrievedLt = (LocalTime) valLocalTimeRet;
            assertEquals(expectedLt.getHour(), retrievedLt.getHour());
            assertEquals(expectedLt.getMinute(), retrievedLt.getMinute());
            assertEquals(expectedLt.getSecond(), retrievedLt.getSecond());
        } else if (valLocalTimeRet instanceof Time) {
            LocalTime retrievedLt = ((Time) valLocalTimeRet).toLocalTime();
            assertEquals(expectedLt.getHour(), retrievedLt.getHour());
            assertEquals(expectedLt.getMinute(), retrievedLt.getMinute());
            assertEquals(expectedLt.getSecond(), retrievedLt.getSecond());
        }
    }
}