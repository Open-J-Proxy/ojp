package org.openjproxy.jdbc.mySql;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class MySQLMultipleTypesIntegrationTest {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    /**
     * Test MySQL/MariaDB's natively supported java.time types via JDBC 4.2.
     * MySQL and MariaDB have first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME)
     * - LocalDateTime (TIMESTAMP/DATETIME)
     * 
     * Note: MySQL/MariaDB do NOT have native TIMESTAMP WITH TIME ZONE,
     * so OffsetDateTime/OffsetTime/Instant are NOT tested here (see unsupported test).
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);

        boolean isMariaDB = url.toLowerCase().contains("mariadb");
        String dbType = isMariaDB ? "MariaDB" : "MySQL";
        System.out.println("Testing " + dbType + " natively supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "mysql_multi_types_test", TestDBUtils.SqlSyntax.MYSQL);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into mysql_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127);
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setByte(10, (byte) 1);
        psInsert.setBytes(11, "AAAA".getBytes());

        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2024, 12, 1);
        psInsert.setObject(12, valDate, Types.DATE);

        LocalTime valTime = LocalTime.of(10, 10, 10);
        psInsert.setObject(13, valTime, Types.TIME);

        LocalDateTime valTimestamp = LocalDateTime.of(2024, 12, 1, 10, 10, 10);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);

        // MySQL/MariaDB natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant, OffsetDateTime, OffsetTime: NOT natively supported in MySQL/MariaDB
        // MySQL lacks TIMESTAMP WITH TIME ZONE, so these would require lossy conversions
        // Setting to null - will be tested in unsupported types test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not natively supported
        psInsert.setObject(19, null, Types.TIMESTAMP); // OffsetDateTime - not natively supported
        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not natively supported

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from mysql_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Validate basic numeric and boolean types
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals(127, resultSet.getInt(5));
        assertEquals(32767, resultSet.getInt(6));
        // MySQL/MariaDB BOOLEAN is TINYINT(1) under the hood; MariaDB Connector/J returns Boolean
        // for TINYINT(1)/BOOLEAN columns; getBoolean() must work for both Boolean and Integer values
        assertTrue(resultSet.getBoolean(7));
        // getInt() must work even when the stored value is a Boolean (MariaDB returns Boolean for TINYINT(1))
        assertEquals(1, resultSet.getInt(7));

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

        // New java.time types - retrieve as Object to get the actual type
        // MySQL/MariaDB natively supported java.time types - retrieve and validate
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        // Columns 18-20 (Instant, OffsetDateTime, OffsetTime) are null - not tested in success scenario
        
        // Validate MySQL/MariaDB's natively supported java.time types (JDBC 4.2)
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        
        // MySQL/MariaDB JDBC driver should return actual java.time types per JDBC 4.2
        // For LocalDateTime
        if (valLocalDateTimeRet instanceof LocalDateTime) {
            assertEquals(valLocalDateTime, valLocalDateTimeRet);
        } else if (valLocalDateTimeRet instanceof Timestamp) {
            LocalDateTime retrievedLdt = ((Timestamp) valLocalDateTimeRet).toLocalDateTime();
            assertEquals(valLocalDateTime, retrievedLdt);
        }
        
        // For LocalDate
        if (valLocalDateRet instanceof LocalDate) {
            assertEquals(valLocalDate, valLocalDateRet);
        } else if (valLocalDateRet instanceof Date) {
            LocalDate retrievedLd = ((Date) valLocalDateRet).toLocalDate();
            assertEquals(valLocalDate, retrievedLd);
        }
        
        // For LocalTime
        if (valLocalTimeRet instanceof LocalTime) {
            LocalTime retrievedLt = (LocalTime) valLocalTimeRet;
            assertEquals(valLocalTime.getHour(), retrievedLt.getHour());
            assertEquals(valLocalTime.getMinute(), retrievedLt.getMinute());
            assertEquals(valLocalTime.getSecond(), retrievedLt.getSecond());
        } else if (valLocalTimeRet instanceof Time) {
            LocalTime retrievedLt = ((Time) valLocalTimeRet).toLocalTime();
            assertEquals(valLocalTime.getHour(), retrievedLt.getHour());
            assertEquals(valLocalTime.getMinute(), retrievedLt.getMinute());
            assertEquals(valLocalTime.getSecond(), retrievedLt.getSecond());
        }

        // Validate basic types by column name
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        // Validate boolean column using both getBoolean() and getInt()
        // MariaDB Connector/J returns Boolean for TINYINT(1)/BOOLEAN columns;
        // getInt("val_boolean") must return 1 for true and not throw a parse error.
        assertTrue(resultSet.getBoolean("val_boolean"));
        assertEquals(1, resultSet.getInt("val_boolean"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    /**
     * Test MySQL/MariaDB's behavior with java.time types that are NOT natively supported.
     * MySQL/MariaDB lack native TIMESTAMP WITH TIME ZONE, so these types either:
     * - Work with lossy conversions (timezone info lost)
     * - Return database errors
     * 
     * Types tested: Instant, OffsetDateTime, OffsetTime
     * Expected: Database errors or lossy conversions documented
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void typesUnsupportedTest(String driverClass, String url, String user, String pwd) throws SQLException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);
        
        boolean isMariaDB = url.toLowerCase().contains("mariadb");
        String dbType = isMariaDB ? "MariaDB" : "MySQL";
        System.out.println("Testing " + dbType + " unsupported java.time types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "mysql_unsupported_test", TestDBUtils.SqlSyntax.MYSQL);

        // Test Instant - MySQL/MariaDB don't have native instant support
        java.sql.PreparedStatement psInsertInstant = conn.prepareStatement(
                "insert into mysql_unsupported_test (val_int, val_instant) values (?, ?)"
        );
        
        psInsertInstant.setInt(1, 1);
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        
        // Attempt to insert Instant
        try {
            psInsertInstant.setObject(2, valInstant, Types.TIMESTAMP);
            psInsertInstant.executeUpdate();
            System.out.println(dbType + ": Instant insertion succeeded with lossy conversion (no timezone preservation)");
            
            // If it succeeded, document that timezone info is lost
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from mysql_unsupported_test where val_int = 1"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println(dbType + ": Instant retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null") +
                        " (timezone info lost - converted to local/UTC)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: MySQL/MariaDB may reject Instant
            System.out.println(dbType + ": Instant not supported - " + e.getMessage());
            // MySQL/MariaDB JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertInstant.close();
        TestDBUtils.executeUpdate(conn, "delete from mysql_unsupported_test where val_int=1");

        // Test OffsetDateTime - MySQL/MariaDB lack TIMESTAMP WITH TIME ZONE
        java.sql.PreparedStatement psInsertOffsetDateTime = conn.prepareStatement(
                "insert into mysql_unsupported_test (val_int, val_offsetdatetime) values (?, ?)"
        );
        
        psInsertOffsetDateTime.setInt(1, 2);
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        
        // Attempt to insert OffsetDateTime
        try {
            psInsertOffsetDateTime.setObject(2, valOffsetDateTime, Types.TIMESTAMP);
            psInsertOffsetDateTime.executeUpdate();
            System.out.println(dbType + ": OffsetDateTime insertion succeeded with lossy conversion (timezone lost)");
            
            // If it succeeded, document that timezone info is lost
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsetdatetime from mysql_unsupported_test where val_int = 2"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println(dbType + ": OffsetDateTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null") +
                        " (timezone offset lost - stored as local time)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: MySQL/MariaDB may reject OffsetDateTime
            System.out.println(dbType + ": OffsetDateTime not supported - " + e.getMessage());
            // MySQL/MariaDB JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetDateTime.close();
        TestDBUtils.executeUpdate(conn, "delete from mysql_unsupported_test where val_int=2");

        // Test OffsetTime - MySQL/MariaDB lack TIME WITH TIME ZONE
        java.sql.PreparedStatement psInsertOffsetTime = conn.prepareStatement(
                "insert into mysql_unsupported_test (val_int, val_offsettime) values (?, ?)"
        );
        
        psInsertOffsetTime.setInt(1, 3);
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        
        // Attempt to insert OffsetTime
        try {
            psInsertOffsetTime.setObject(2, valOffsetTime, Types.TIMESTAMP);
            psInsertOffsetTime.executeUpdate();
            System.out.println(dbType + ": OffsetTime insertion succeeded with lossy conversion (timezone lost)");
            
            // If it succeeded, document that timezone info is lost
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsettime from mysql_unsupported_test where val_int = 3"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println(dbType + ": OffsetTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null") +
                        " (timezone offset lost - stored as local time)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: MySQL/MariaDB may reject OffsetTime
            System.out.println(dbType + ": OffsetTime not supported - " + e.getMessage());
            // MySQL/MariaDB JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetTime.close();
        TestDBUtils.executeUpdate(conn, "delete from mysql_unsupported_test where val_int=3");

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void mysqlSpecificTypesTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled  
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing MySQL-specific types for url -> " + url);

        TestDBUtils.createMySQLSpecificTestTable(conn, "mysql_specific_types_test");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into mysql_specific_types_test (enum_col, json_col, text_col, mediumtext_col, " +
                        "longtext_col, blob_col, mediumblob_col, longblob_col, set_col, year_col, bit_col) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setString(1, "medium");
        psInsert.setString(2, "{\"key\": \"value\", \"number\": 42}");
        psInsert.setString(3, "This is a TEXT column");
        psInsert.setString(4, "This is a MEDIUMTEXT column with more content");
        psInsert.setString(5, "This is a LONGTEXT column with even more content");
        psInsert.setBytes(6, "BLOB data".getBytes());
        psInsert.setBytes(7, "MEDIUMBLOB data".getBytes());
        psInsert.setBytes(8, "LONGBLOB data".getBytes());
        psInsert.setString(9, "option1,option3");
        psInsert.setInt(10, 2024);
        psInsert.setByte(11, (byte) 0b101010);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from mysql_specific_types_test where id = LAST_INSERT_ID()");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Validate MySQL-specific data types
        int id = resultSet.getInt(1);
        String enumCol = resultSet.getString(2);
        String jsonCol = resultSet.getString(3);
        String textCol = resultSet.getString(4);
        String mediumtextCol = resultSet.getString(5);
        String longtextCol = resultSet.getString(6);
        byte[] blobCol = resultSet.getBytes(7);
        byte[] mediumblobCol = resultSet.getBytes(8);
        byte[] longblobCol = resultSet.getBytes(9);
        String setCol = resultSet.getString(10);
        int yearCol = resultSet.getInt(11);
        byte bitCol = resultSet.getByte(12);

        // Assertions
        assertTrue(id > 0);
        assertEquals("medium", enumCol);
        assertEquals("{\"key\": \"value\", \"number\": 42}", jsonCol);
        assertEquals("This is a TEXT column", textCol);
        assertEquals("This is a MEDIUMTEXT column with more content", mediumtextCol);
        assertEquals("This is a LONGTEXT column with even more content", longtextCol);
        assertEquals("BLOB data", new String(blobCol));
        assertEquals("MEDIUMBLOB data", new String(mediumblobCol));
        assertEquals("LONGBLOB data", new String(longblobCol));
        assertEquals("option1,option3", setCol);
        assertEquals(2024, yearCol);
        assertEquals((byte) 0b101010, bitCol);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }
}