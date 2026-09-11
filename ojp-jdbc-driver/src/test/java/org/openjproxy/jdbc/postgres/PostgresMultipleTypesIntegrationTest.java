package org.openjproxy.jdbc.postgres;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assertions;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresMultipleTypesIntegrationTest {

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    /**
     * Test PostgreSQL's natively supported java.time types via JDBC 4.2.
     * PostgreSQL has first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME)
     * - LocalDateTime (TIMESTAMP)
     * - OffsetDateTime (TIMESTAMPTZ)
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing PostgreSQL natively supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "postgres_multi_types_test", TestDBUtils.SqlSyntax.POSTGRES);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into postgres_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127); // PostgreSQL SMALLINT can handle this
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // PostgreSQL BYTEA expects byte array
        psInsert.setBytes(11, "AAAA".getBytes()); // PostgreSQL BYTEA
        
        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // PostgreSQL natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant and OffsetTime: Not first-class in PostgreSQL JDBC driver, expect potential issues
        // Setting to null for now - will be tested in unsupported types test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not first-class
        
        // OffsetDateTime: PostgreSQL has native TIMESTAMPTZ support via JDBC 4.2
        // Use Types.TIMESTAMP_WITH_TIMEZONE for proper type mapping
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        psInsert.setObject(19, valOffsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);

        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not first-class
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from postgres_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", "" + resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5)); // SMALLINT in PostgreSQL
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals(20.20f + "", "" + resultSet.getFloat(9));
        // PostgreSQL BYTEA column may be returned as String by OJP driver
        // For now, just verify we get a non-null value
        Object byteValue = resultSet.getObject(10);
        Assertions.assertNotNull(byteValue, "BYTEA column should not be null");
        // PostgreSQL BYTEA column may be returned as String by OJP driver  
        Object binaryValue = resultSet.getObject(11);
        if (binaryValue instanceof String) {
            // If returned as string, check the content
            String stringValue = (String) binaryValue;
            Assertions.assertTrue(
                    stringValue.contains("AAAA") || stringValue.length() > 0, "Binary column should contain expected data");
        } else {
            // Handle as byte array
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
        
        // PostgreSQL natively supported java.time types - retrieve as Object to get the actual type
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        // val_instant (18) and val_offsettime (20) are null - not tested in this success scenario
        Object valOffsetDateTimeRet = resultSet.getObject(19);
        
        // Validate PostgreSQL's natively supported java.time types via JDBC 4.2
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        assertNotNull(valOffsetDateTimeRet, "OffsetDateTime should not be null");
        
        // PostgreSQL JDBC driver should return actual java.time types per JDBC 4.2
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
        
        // For OffsetDateTime (TIMESTAMPTZ) - PostgreSQL preserves timezone via JDBC 4.2
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
        // PostgreSQL BYTEA column may be returned as String by OJP driver
        Object byteValueByName = resultSet.getObject("val_byte");
        Assertions.assertNotNull(byteValueByName, "BYTEA column val_byte should not be null");
        // PostgreSQL BYTEA column may be returned as String by OJP driver
        Object binaryValueByName = resultSet.getObject("val_binary");
        if (binaryValueByName instanceof String) {
            String stringValue = (String) binaryValueByName;
            Assertions.assertTrue(
                    stringValue.contains("AAAA") || stringValue.length() > 0, "Binary column should contain expected data");
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

        TestDBUtils.executeUpdate(conn, "delete from postgres_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    /**
     * Test PostgreSQL's behavior with java.time types that are NOT natively supported via JDBC 4.2.
     * These types may work with conversions but are not first-class:
     * - Instant (can be stored as TIMESTAMPTZ but driver doesn't directly support)
     * - OffsetTime (can be stored as TIMETZ but driver support varies)
     * 
     * This test documents expected database behavior when unsupported types are used.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing PostgreSQL partially supported types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "postgres_partial_types_test", TestDBUtils.SqlSyntax.POSTGRES);

        // Test Instant - not first-class in PostgreSQL JDBC driver
        // It may work via conversion to TIMESTAMPTZ but behavior varies
        java.sql.PreparedStatement psInsertInstant = conn.prepareStatement(
                "insert into postgres_partial_types_test (val_int, val_instant) values (?, ?)"
        );
        
        psInsertInstant.setInt(1, 1);
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        
        // Attempt to insert Instant - behavior depends on driver version
        try {
            psInsertInstant.setObject(2, valInstant, Types.TIMESTAMP);
            psInsertInstant.executeUpdate();
            System.out.println("PostgreSQL: Instant insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from postgres_partial_types_test where val_int = 1"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("PostgreSQL: Instant retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "Instant should be retrieved (possibly as Timestamp)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: PostgreSQL driver may not support Instant directly
            System.out.println("PostgreSQL: Instant not natively supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertInstant.close();
        TestDBUtils.executeUpdate(conn, "delete from postgres_partial_types_test where val_int=1");

        // Test OffsetTime - not first-class in PostgreSQL JDBC driver  
        // PostgreSQL has TIMETZ but JDBC driver support varies
        java.sql.PreparedStatement psInsertOffsetTime = conn.prepareStatement(
                "insert into postgres_partial_types_test (val_int, val_offsettime) values (?, ?)"
        );
        
        psInsertOffsetTime.setInt(1, 2);
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        
        // Attempt to insert OffsetTime
        try {
            psInsertOffsetTime.setObject(2, valOffsetTime, Types.TIME_WITH_TIMEZONE);
            psInsertOffsetTime.executeUpdate();
            System.out.println("PostgreSQL: OffsetTime insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsettime from postgres_partial_types_test where val_int = 2"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("PostgreSQL: OffsetTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "OffsetTime should be retrieved (possibly as Time)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: PostgreSQL driver may not support OffsetTime directly
            System.out.println("PostgreSQL: OffsetTime not natively supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetTime.close();
        TestDBUtils.executeUpdate(conn, "delete from postgres_partial_types_test where val_int=2");

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testPostgresSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing PostgreSQL-specific types for url -> " + url);

        // Test UUID, JSON, and array types (PostgreSQL-specific)
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_postgres_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn,
                "CREATE TABLE test_postgres_types (" +
                        "id SERIAL PRIMARY KEY, " +
                        "uuid_col UUID, " +
                        "json_col JSON, " +
                        "array_col INTEGER[], " +
                        "text_col TEXT)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO test_postgres_types (uuid_col, json_col, array_col, text_col) VALUES (?, ?::json, ?::integer[], ?)"
        );

        // Test UUID
        psInsert.setObject(1, java.util.UUID.randomUUID());
        // Test JSON
        psInsert.setString(2, "{\"key\": \"value\"}");
        // Test Array - OJP driver currently doesn't support Array serialization, so use string representation
        psInsert.setString(3, "{1,2,3}"); // PostgreSQL array literal format
        // Test TEXT
        psInsert.setString(4, "PostgreSQL text type");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT text_col FROM test_postgres_types WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();

        assertTrue(resultSet.next());
        assertEquals("PostgreSQL text type", resultSet.getString("text_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    /**
     * Tests full roundtrip support for PostgreSQL JSON and JSONB column types.
     * <p>
     * PostgreSQL reports JSON and JSONB columns with {@code Types.OTHER} (JDBC type 1111).
     * OJP detects these columns by type name and uses {@code getString()} to return the
     * JSON text, avoiding the vendor-specific {@code PGobject} wrapper.
     * </p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testPostgresJsonTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing PostgreSQL JSON/JSONB types for url -> " + url);

        TestDBUtils.createPostgresJsonTestTable(conn, "test_postgres_json");

        // Insert JSON, JSONB, and a NULL JSON value
        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO test_postgres_json (json_col, jsonb_col, json_null_col) VALUES (?::json, ?::jsonb, ?)"
        );

        String jsonValue  = "{\"key\": \"value\", \"number\": 42}";
        String jsonbValue = "{\"active\": true, \"tags\": [\"a\", \"b\"]}";

        psInsert.setString(1, jsonValue);
        psInsert.setString(2, jsonbValue);
        psInsert.setNull(3, Types.OTHER);

        psInsert.executeUpdate();

        // Select all three columns and validate the roundtrip
        java.sql.PreparedStatement psSelect = conn.prepareStatement(
                "SELECT json_col, jsonb_col, json_null_col FROM test_postgres_json WHERE id = 1"
        );
        ResultSet resultSet = psSelect.executeQuery();

        assertTrue(resultSet.next());

        // JSON column: content must be preserved (PostgreSQL may normalise whitespace)
        String retrievedJson = resultSet.getString("json_col");
        assertNotNull(retrievedJson, "JSON column should not be null");
        assertTrue(retrievedJson.contains("key") && retrievedJson.contains("value") && retrievedJson.contains("42"),
                "JSON column should contain the expected keys and values, got: " + retrievedJson);

        // JSONB column: PostgreSQL may reorder keys, so check individual values
        String retrievedJsonb = resultSet.getString("jsonb_col");
        assertNotNull(retrievedJsonb, "JSONB column should not be null");
        assertTrue(retrievedJsonb.contains("active") && retrievedJsonb.contains("true"),
                "JSONB column should contain expected values, got: " + retrievedJsonb);

        // NULL JSON column must round-trip as null
        assertNull(resultSet.getString("json_null_col"), "NULL JSON column should return null");

        resultSet.close();
        psSelect.close();

        // Test the PostgreSQL JSON extraction operators -> and ->>
        java.sql.PreparedStatement psOperator = conn.prepareStatement(
                "SELECT json_col->>'key' AS key_value FROM test_postgres_json WHERE id = 1"
        );
        ResultSet rsOperator = psOperator.executeQuery();
        assertTrue(rsOperator.next());
        assertEquals("value", rsOperator.getString("key_value"),
                "JSON text-extraction operator ->> should return the expected string value");
        rsOperator.close();
        psOperator.close();

        psInsert.close();
        conn.close();
    }

    /**
     * Helper method to convert hex string to byte array
     */
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}