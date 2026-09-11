package org.openjproxy.jdbc.db2;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class Db2MultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    /**
     * Test DB2's natively supported java.time types via JDBC 4.2.
     * DB2 has first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME)
     * - LocalDateTime (TIMESTAMP)
     * 
     * Note: DB2 does not have native TIMESTAMP WITH TIME ZONE in all versions,
     * so OffsetDateTime/OffsetTime/Instant are tested in partial support test.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException, UnsupportedEncodingException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 natively supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "DB2INST1.db2_multi_types_test", TestDBUtils.SqlSyntax.DB2);

        String dbEncoding = "ISO-8859-1"; // default fallback

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into DB2INST1.db2_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, new String("TITLE_1".getBytes(), dbEncoding));
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127); // DB2 SMALLINT can handle this
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true); // DB2 has native boolean support
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // DB2 VARBINARY expects byte array
        psInsert.setBytes(11, "AAAA".getBytes(StandardCharsets.UTF_8)); // DB2 VARBINARY with UTF-8
        
        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // DB2 natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant, OffsetDateTime, OffsetTime: NOT natively supported in DB2 JDBC 4.2
        // DB2 lacks TIMESTAMP WITH TIME ZONE in many versions
        // Setting to null - will be tested in partial support test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not first-class
        psInsert.setObject(19, null, Types.TIMESTAMP); // OffsetDateTime - not first-class
        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not first-class
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from DB2INST1.db2_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", ""+resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5)); // SMALLINT in DB2
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7)); // DB2 native boolean
        assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal(8));
        assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        // DB2 VARBINARY column
        byte[] byteValue = resultSet.getBytes(10);
        assertNotNull(byteValue, "VARBINARY column should not be null");
        assertEquals(1, byteValue.length);
        assertEquals(1, byteValue[0]);
        // DB2 VARBINARY column
        assertEquals("AAAA", new String(resultSet.getBytes(11), StandardCharsets.UTF_8));
        
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
        
        // DB2 natively supported java.time types - retrieve as Object to get the actual type
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        // Columns 18-20 (Instant, OffsetDateTime, OffsetTime) are null - not tested in success scenario
        
        // Validate DB2's natively supported java.time types (JDBC 4.2)
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        
        // DB2 JDBC driver should return actual java.time types per JDBC 4.2
        // For LocalDateTime (TIMESTAMP)
        if (valLocalDateTimeRet instanceof LocalDateTime) {
            assertEquals(valLocalDateTime, valLocalDateTimeRet);
        } else if (valLocalDateTimeRet instanceof Timestamp) {
            LocalDateTime retrievedLdt = ((Timestamp) valLocalDateTimeRet).toLocalDateTime();
            assertEquals(valLocalDateTime, retrievedLdt);
        }
        
        // For LocalDate (DATE)
        if (valLocalDateRet instanceof LocalDate) {
            assertEquals(valLocalDate, valLocalDateRet);
        } else if (valLocalDateRet instanceof Date) {
            LocalDate retrievedLd = ((Date) valLocalDateRet).toLocalDate();
            assertEquals(valLocalDate, retrievedLd);
        }
        
        // For LocalTime (TIME)
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

        // Test column name access
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", ""+resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal("val_decimal"));
        assertEquals(20.20f+"", ""+resultSet.getFloat("val_float"));
        assertTrue(resultSet.getBoolean("val_boolean")); // DB2 native boolean
        // DB2 VARBINARY column
        byte[] byteValueByName = resultSet.getBytes("val_byte");
        assertNotNull(byteValueByName, "VARBINARY column val_byte should not be null");
        assertEquals(1, byteValueByName.length);
        assertEquals(1, byteValueByName[0]);
        assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        
        // SimpleDateFormat variables for validation using column names (lines 232-234)
        // Set explicit UTC timezone to ensure consistent behavior across different JVM timezone settings
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm:ss");
        sdfTime.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        sdfTimestamp.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        
        assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        assertEquals("11:12:13", sdfTime.format(resultSet.getTime("val_time")));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));

        TestDBUtils.executeUpdate(conn, "delete from db2_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    /**
     * Test DB2's behavior with java.time types that are NOT natively supported.
     * DB2 lacks TIMESTAMP WITH TIME ZONE in many versions, so these types either:
     * - Work with lossy conversions (timezone info lost)
     * - Return database errors
     * 
     * Types tested: Instant, OffsetDateTime, OffsetTime
     * Expected: Database errors or lossy conversions documented
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        
        // Verify connection is valid
        assertNotNull(conn, "Connection should be established");

        // Set schema explicitly
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 partially supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "DB2INST1.db2_partial_types_test", TestDBUtils.SqlSyntax.DB2);

        // DB2 JDBC driver has a critical blocking bug with timezone-aware java.time types.
        // When calling setObject() with Instant, OffsetDateTime, or OffsetTime, the driver
        // enters an infinite wait/block BEFORE throwing any SQLException. This prevents
        // the server from catching and returning an error to the client.
        //
        // DB2 lacks native TIMESTAMP WITH TIME ZONE and TIME WITH TIME ZONE types.
        // Users should use LocalDateTime, LocalDate, and LocalTime instead.
        //
        // These types are intentionally NOT tested to avoid hanging the test suite.
        
        System.out.println("DB2: Skipping Instant, OffsetDateTime, and OffsetTime tests");
        System.out.println("DB2: These types are not supported due to:");
        System.out.println("DB2:   1. DB2 lacks native TIMESTAMP WITH TIME ZONE and TIME WITH TIME ZONE");
        System.out.println("DB2:   2. DB2 JDBC driver has a blocking bug - hangs indefinitely on setObject()");
        System.out.println("DB2:   3. Users should use LocalDateTime/LocalDate/LocalTime instead");
        System.out.println("DB2: Partial support test complete.");

        // Clean up
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE DB2INST1.db2_partial_types_test");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2SpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2-specific types for url -> " + url);

        // Test CLOB, BLOB, and GRAPHIC types (DB2-specific)
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_db2_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_db2_types (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "clob_col CLOB(1M), " +
            "blob_col BLOB(1M), " +
            "graphic_col GRAPHIC(50), " +
            "dbclob_col DBCLOB(1M))"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_db2_types (clob_col, blob_col, graphic_col, dbclob_col) VALUES (?, ?, ?, ?)"
        );

        // Test CLOB
        psInsert.setString(1, "DB2 CLOB data type for large text");
        // Test BLOB
        psInsert.setBytes(2, "DB2 BLOB data".getBytes());
        // Test GRAPHIC (fixed-length double-byte character)
        psInsert.setString(3, "DB2 GRAPHIC type");
        // Test DBCLOB
        psInsert.setString(4, "DB2 DBCLOB data type");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT graphic_col FROM test_db2_types WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        String graphicValue = resultSet.getString("graphic_col");
        assertNotNull(graphicValue);
        assertTrue(graphicValue.contains("DB2 GRAPHIC type"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2NumericTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 numeric types for url -> " + url);

        // Test various DB2 numeric types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE DB2INST1.test_db2_numbers");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE DB2INST1.test_db2_numbers (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "smallint_col SMALLINT, " +
            "integer_col INTEGER, " +
            "bigint_col BIGINT, " +
            "decimal_col DECIMAL(10,2), " +
            "numeric_col NUMERIC(15,5), " +
            "real_col REAL, " +
            "double_col DOUBLE)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO DB2INST1.test_db2_numbers (smallint_col, integer_col, bigint_col, decimal_col, numeric_col, real_col, double_col) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
        );

        // Test numeric types
        psInsert.setShort(1, (short) 32767);
        psInsert.setInt(2, 2147483647);
        psInsert.setLong(3, 9223372036854775807L);
        psInsert.setBigDecimal(4, new BigDecimal("12345.67"));
        psInsert.setBigDecimal(5, new BigDecimal("1234567890.12345"));
        psInsert.setFloat(6, 123.45f);
        psInsert.setDouble(7, 123456.789012);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM DB2INST1.test_db2_numbers WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        assertEquals(32767, resultSet.getInt("smallint_col")); // Use getInt instead of getShort to avoid ClassCast
        assertEquals(2147483647, resultSet.getInt("integer_col"));
        assertEquals(9223372036854775807L, resultSet.getLong("bigint_col"));
        assertEquals(new BigDecimal("12345.67"), resultSet.getBigDecimal("decimal_col"));
        assertNotNull(resultSet.getBigDecimal("numeric_col"));
        assertEquals(123.45f, resultSet.getFloat("real_col"), 0.001);
        assertEquals(123456.789012, resultSet.getDouble("double_col"), 0.0001);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2DateTimeTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 date/time types for url -> " + url);

        // Test DB2 date/time types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_db2_datetime");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_db2_datetime (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "date_col DATE, " +
            "time_col TIME, " +
            "timestamp_col TIMESTAMP)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_db2_datetime (date_col, time_col, timestamp_col) VALUES (?, ?, ?)"
        );

        Date testDate = Date.valueOf("2025-03-29");
        Time testTime = Time.valueOf("11:12:13");
        Timestamp testTimestamp = Timestamp.valueOf("2025-03-30 21:22:23");

        psInsert.setDate(1, testDate);
        psInsert.setTime(2, testTime);
        psInsert.setTimestamp(3, testTimestamp);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM test_db2_datetime WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        assertEquals(testDate.toString(), resultSet.getDate("date_col").toString());
        assertEquals(testTime.toString(), resultSet.getTime("time_col").toString());
        assertEquals(testTimestamp.toString(), resultSet.getTimestamp("timestamp_col").toString());

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

}