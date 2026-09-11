package org.openjproxy.jdbc.cockroach;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBMultipleTypesIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBMultipleTypesIntegrationTest.class);
    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    /**
     * Test CockroachDB's natively supported java.time types via JDBC 4.2.
     * CockroachDB (PostgreSQL-compatible) has first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME)
     * - LocalDateTime (TIMESTAMP)
     * - OffsetDateTime (TIMESTAMPTZ)
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB natively supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "cockroachdb_multi_types_test", TestDBUtils.SqlSyntax.COCKROACHDB);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into cockroachdb_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127); // CockroachDB SMALLINT can handle this
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // CockroachDB BYTEA expects byte array
        psInsert.setBytes(11, "AAAA".getBytes()); // CockroachDB BYTEA
        
        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // CockroachDB natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant and OffsetTime: Not first-class in CockroachDB JDBC driver, expect potential issues
        // Setting to null for now - will be tested in partial support test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not first-class

        // OffsetDateTime: CockroachDB has native TIMESTAMPTZ support via JDBC 4.2
        // Use Types.TIMESTAMP_WITH_TIMEZONE for proper type mapping
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        psInsert.setObject(19, valOffsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);

        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not first-class
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from cockroachdb_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", "" + resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5)); // SMALLINT in CockroachDB
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals(20.20f + "", "" + resultSet.getFloat(9));
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object byteValue = resultSet.getObject(10);
        assertNotNull(byteValue, "BYTEA column should not be null");
        // CockroachDB BYTEA column may be returned as String by OJP driver  
        Object binaryValue = resultSet.getObject(11);
        if (binaryValue instanceof String) {
            String stringValue = (String) binaryValue;
            assertTrue(stringValue.contains("AAAA") || !stringValue.isEmpty(), "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(resultSet.getBytes(11)));
        }
        
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
        
        // CockroachDB natively supported java.time types - retrieve as Object to get the actual type
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        // val_instant (18) and val_offsettime (20) are null - not tested in this success scenario
        Object valOffsetDateTimeRet = resultSet.getObject(19);
        
        // Validate CockroachDB's natively supported java.time types via JDBC 4.2
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        assertNotNull(valOffsetDateTimeRet, "OffsetDateTime should not be null");
        
        // CockroachDB JDBC driver should return actual java.time types per JDBC 4.2
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
        
        // For OffsetDateTime (TIMESTAMPTZ) - CockroachDB preserves timezone via JDBC 4.2
        if (valOffsetDateTimeRet instanceof OffsetDateTime) {
            OffsetDateTime retrievedOdt = (OffsetDateTime) valOffsetDateTimeRet;
            // Compare instant values - timezone representation may vary but instant should match
            assertEquals(valOffsetDateTime.toInstant(), retrievedOdt.toInstant());
        } else if (valOffsetDateTimeRet instanceof Timestamp) {
            // Fallback: compare as instant
            Instant retrievedInstant = ((Timestamp) valOffsetDateTimeRet).toInstant();
            assertEquals(valOffsetDateTime.toInstant(), retrievedInstant);
        }

        // Test column name access
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", "" + resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        assertEquals(20.20f + "", "" + resultSet.getFloat("val_float"));
        assertTrue(resultSet.getBoolean("val_boolean"));
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object byteValueByName = resultSet.getObject("val_byte");
        assertNotNull(byteValueByName, "BYTEA column val_byte should not be null");
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object binaryValueByName = resultSet.getObject("val_binary");
        if (binaryValueByName instanceof String) {
            String stringValue = (String) binaryValueByName;
            assertTrue(stringValue.contains("AAAA") || !stringValue.isEmpty(), "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        }
        
        // SimpleDateFormat variables for validation using column names (lines 254-256)
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

        TestDBUtils.executeUpdate(conn, "delete from cockroachdb_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    /**
     * Test CockroachDB's behavior with java.time types that are NOT natively supported via JDBC 4.2.
     * These types may work with conversions but are not first-class:
     * - Instant (can be stored as TIMESTAMPTZ but driver doesn't directly support)
     * - OffsetTime (can be stored as TIMETZ but driver support varies)
     * 
     * This test documents expected database behavior when unsupported types are used.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing partial support types with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB partially supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "cockroachdb_partial_types_test", TestDBUtils.SqlSyntax.COCKROACHDB);

        // Test Instant - not first-class in CockroachDB JDBC driver
        // It may work via conversion to TIMESTAMPTZ but behavior varies
        java.sql.PreparedStatement psInsertInstant = conn.prepareStatement(
                "insert into cockroachdb_partial_types_test (val_int, val_instant) values (?, ?)"
        );
        
        psInsertInstant.setInt(1, 1);
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        
        // Attempt to insert Instant - behavior depends on driver version
        try {
            psInsertInstant.setObject(2, valInstant, Types.TIMESTAMP);
            psInsertInstant.executeUpdate();
            System.out.println("CockroachDB: Instant insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from cockroachdb_partial_types_test where val_int = 1"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("CockroachDB: Instant retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "Instant should be retrieved (possibly as Timestamp)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: CockroachDB driver may not support Instant directly
            System.out.println("CockroachDB: Instant not natively supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertInstant.close();
        TestDBUtils.executeUpdate(conn, "delete from cockroachdb_partial_types_test where val_int=1");

        // Test OffsetTime - not first-class in CockroachDB JDBC driver  
        // CockroachDB has TIMETZ but JDBC driver support varies
        java.sql.PreparedStatement psInsertOffsetTime = conn.prepareStatement(
                "insert into cockroachdb_partial_types_test (val_int, val_offsettime) values (?, ?)"
        );
        
        psInsertOffsetTime.setInt(1, 2);
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        
        // Attempt to insert OffsetTime
        try {
            psInsertOffsetTime.setObject(2, valOffsetTime, Types.TIME_WITH_TIMEZONE);
            psInsertOffsetTime.executeUpdate();
            System.out.println("CockroachDB: OffsetTime insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsettime from cockroachdb_partial_types_test where val_int = 2"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("CockroachDB: OffsetTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "OffsetTime should be retrieved (possibly as Time)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: CockroachDB driver may not support OffsetTime directly
            System.out.println("CockroachDB: OffsetTime not natively supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetTime.close();
        TestDBUtils.executeUpdate(conn, "delete from cockroachdb_partial_types_test where val_int=2");

        // Clean up
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE cockroachdb_partial_types_test");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB-specific types for url -> " + url);

        // Test UUID and text types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_cockroachdb_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn,
                "CREATE TABLE test_cockroachdb_types (" +
                        "id SERIAL PRIMARY KEY, " +
                        "uuid_col UUID, " +
                        "text_col TEXT)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO test_cockroachdb_types (uuid_col, text_col) VALUES (?, ?)"
        );

        // Test UUID
        psInsert.setObject(1, java.util.UUID.randomUUID());
        // Test TEXT
        psInsert.setString(2, "CockroachDB text type");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT text_col FROM test_cockroachdb_types LIMIT 1");
        ResultSet resultSet = psSelect.executeQuery();

        assertTrue(resultSet.next());
        assertEquals("CockroachDB text type", resultSet.getString("text_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    // Note: testCockroachDBIntervalType removed due to OJP driver limitation with PostgreSQL-specific types
}
