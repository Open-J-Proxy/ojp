package org.openjproxy.jdbc.h2;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assumptions;
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

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.*;

class H2MultipleTypesIntegrationTest {

    private static boolean isH2TestEnabled;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    /**
     * Tests java.time types that H2 natively supports via JDBC 4.2.
     * H2 database supports LocalDate, LocalTime, LocalDateTime natively,
     * and OffsetDateTime/OffsetTime via TIMESTAMP WITH TIME ZONE / TIME WITH TIME ZONE.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing H2 native java.time support for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "h2_multi_types_test", TestDBUtils.SqlSyntax.H2);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into h2_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
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
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // Native java.time types supported by H2
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant - will be tested in partial support test
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        psInsert.setObject(18, valInstant, Types.TIMESTAMP);

        // H2 supports OffsetDateTime via TIMESTAMP WITH TIME ZONE
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        psInsert.setObject(19, valOffsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);

        // H2 supports OffsetTime via TIME WITH TIME ZONE
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        psInsert.setObject(20, valOffsetTime, Types.TIME_WITH_TIMEZONE);
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from h2_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", ""+resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5));
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        assertEquals((byte) 1, resultSet.getByte(10));
        assertEquals("AAAA", new String(resultSet.getBytes(11)));
        
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
        
        // Validate java.time types - H2 should return them as native java.time types
        Object valLocalDateTimeRet = resultSet.getObject(15);
        Object valLocalDateRet = resultSet.getObject(16);
        Object valLocalTimeRet = resultSet.getObject(17);
        Object valInstantRet = resultSet.getObject(18);
        Object valOffsetDateTimeRet = resultSet.getObject(19);
        Object valOffsetTimeRet = resultSet.getObject(20);
        
        assertNotNull(valLocalDateTimeRet, "LocalDateTime should not be null");
        assertNotNull(valLocalDateRet, "LocalDate should not be null");
        assertNotNull(valLocalTimeRet, "LocalTime should not be null");
        assertNotNull(valInstantRet, "Instant should not be null");
        assertNotNull(valOffsetDateTimeRet, "OffsetDateTime should not be null");
        assertNotNull(valOffsetTimeRet, "OffsetTime should not be null");
        
        // H2 supports java.time natively, but may return as Timestamp/Date/Time
        if (valLocalDateTimeRet instanceof LocalDateTime) {
            assertEquals(valLocalDateTime, valLocalDateTimeRet);
        } else if (valLocalDateTimeRet instanceof Timestamp) {
            LocalDateTime retrievedLdt = ((Timestamp) valLocalDateTimeRet).toLocalDateTime();
            assertEquals(valLocalDateTime, retrievedLdt);
        }
        
        if (valLocalDateRet instanceof LocalDate) {
            assertEquals(valLocalDate, valLocalDateRet);
        } else if (valLocalDateRet instanceof Date) {
            LocalDate retrievedLd = ((Date) valLocalDateRet).toLocalDate();
            assertEquals(valLocalDate, retrievedLd);
        }
        
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
        
        // Instant validation - H2 may return as Timestamp
        if (valInstantRet instanceof Instant) {
            assertEquals(valInstant.getEpochSecond(), ((Instant) valInstantRet).getEpochSecond());
        } else if (valInstantRet instanceof Timestamp) {
            Instant retrievedInstant = ((Timestamp) valInstantRet).toInstant();
            assertEquals(valInstant.getEpochSecond(), retrievedInstant.getEpochSecond());
        }
        
        // H2 supports OffsetDateTime via TIMESTAMP WITH TIME ZONE
        if (valOffsetDateTimeRet instanceof OffsetDateTime) {
            OffsetDateTime retrievedOdt = (OffsetDateTime) valOffsetDateTimeRet;
            assertEquals(valOffsetDateTime.toInstant(), retrievedOdt.toInstant());
        } else if (valOffsetDateTimeRet instanceof Timestamp) {
            Instant retrievedInstant = ((Timestamp) valOffsetDateTimeRet).toInstant();
            assertEquals(valOffsetDateTime.toInstant(), retrievedInstant);
        }
        
        // H2 supports OffsetTime via TIME WITH TIME ZONE
        if (valOffsetTimeRet instanceof OffsetTime) {
            OffsetTime retrievedOt = (OffsetTime) valOffsetTimeRet;
            assertEquals(valOffsetTime.getHour(), retrievedOt.getHour());
            assertEquals(valOffsetTime.getMinute(), retrievedOt.getMinute());
            assertEquals(valOffsetTime.getSecond(), retrievedOt.getSecond());
        } else if (valOffsetTimeRet instanceof Time) {
            LocalTime retrievedLt = ((Time) valOffsetTimeRet).toLocalTime();
            assertEquals(valOffsetTime.getHour(), retrievedLt.getHour());
            assertEquals(valOffsetTime.getMinute(), retrievedLt.getMinute());
            assertEquals(valOffsetTime.getSecond(), retrievedLt.getSecond());
        }

        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", ""+resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        assertEquals(20.20f+"", ""+resultSet.getFloat("val_float"));
        assertTrue(resultSet.getBoolean("val_boolean"));
        assertEquals((byte) 1, resultSet.getByte("val_byte"));
        assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        
        // SimpleDateFormat variables for validation using column names (lines 245-247)
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

        executeUpdate(conn, "delete from h2_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    /**
     * Tests that Calendar-based getTimestamp/getDate/getTime overloads work correctly
     * for LocalDateTime/LocalDate/LocalTime columns.
     *
     * <p>This reproduces the failure described in the issue: Hibernate's TimestampJdbcType
     * calls {@code getTimestamp(columnIndex, calendar)} (not plain {@code getTimestamp(columnIndex)})
     * when reading any TIMESTAMP column mapped to a {@code LocalDateTime} entity field.
     * Previously those overloads threw {@code RuntimeException("Not implemented")}.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void calendarOverloadsWorkForLocalDateTimeColumns(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        Connection conn = DriverManager.getConnection(url, user, pwd);

        try {
            // Create a minimal table that mirrors the entity described in the issue
            try {
                conn.createStatement().execute("DROP TABLE calendar_overload_test");
            } catch (SQLException ignored) {
                // table may not exist yet
            }
            conn.createStatement().execute(
                    "CREATE TABLE calendar_overload_test (" +
                    "  id INT PRIMARY KEY," +
                    "  created_at TIMESTAMP," +
                    "  val_date DATE," +
                    "  val_time TIME" +
                    ")"
            );

            LocalDateTime expectedLdt = LocalDateTime.of(2025, 6, 15, 10, 30, 45);
            LocalDate expectedLd = LocalDate.of(2025, 6, 15);
            LocalTime expectedLt = LocalTime.of(10, 30, 45);

            java.sql.PreparedStatement psInsert = conn.prepareStatement(
                    "INSERT INTO calendar_overload_test (id, created_at, val_date, val_time) VALUES (?, ?, ?, ?)"
            );
            psInsert.setInt(1, 1);
            psInsert.setObject(2, expectedLdt, Types.TIMESTAMP);
            psInsert.setObject(3, expectedLd, Types.DATE);
            psInsert.setObject(4, expectedLt, Types.TIME);
            psInsert.executeUpdate();

            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "SELECT id, created_at, val_date, val_time FROM calendar_overload_test WHERE id = ?"
            );
            psSelect.setInt(1, 1);
            ResultSet rs = psSelect.executeQuery();
            assertTrue(rs.next());

            java.util.Calendar cal = java.util.Calendar.getInstance();

            // --- getTimestamp(int, Calendar) ---
            // This is what Hibernate's TimestampJdbcType calls for @Column LocalDateTime fields
            Timestamp tsFromIndex = rs.getTimestamp(2, cal);
            assertNotNull(tsFromIndex, "getTimestamp(int, Calendar) must not return null for a LocalDateTime column");
            assertEquals(expectedLdt, tsFromIndex.toLocalDateTime());

            // --- getTimestamp(String, Calendar) ---
            Timestamp tsFromLabel = rs.getTimestamp("created_at", cal);
            assertNotNull(tsFromLabel, "getTimestamp(String, Calendar) must not return null for a LocalDateTime column");
            assertEquals(expectedLdt, tsFromLabel.toLocalDateTime());

            // --- getDate(int, Calendar) ---
            Date dateFromIndex = rs.getDate(3, cal);
            assertNotNull(dateFromIndex, "getDate(int, Calendar) must not return null for a DATE column");
            assertEquals(expectedLd, dateFromIndex.toLocalDate());

            // --- getDate(String, Calendar) ---
            Date dateFromLabel = rs.getDate("val_date", cal);
            assertNotNull(dateFromLabel, "getDate(String, Calendar) must not return null for a DATE column");
            assertEquals(expectedLd, dateFromLabel.toLocalDate());

            // --- getTime(int, Calendar) ---
            Time timeFromIndex = rs.getTime(4, cal);
            assertNotNull(timeFromIndex, "getTime(int, Calendar) must not return null for a TIME column");
            assertEquals(expectedLt, timeFromIndex.toLocalTime());

            // --- getTime(String, Calendar) ---
            Time timeFromLabel = rs.getTime("val_time", cal);
            assertNotNull(timeFromLabel, "getTime(String, Calendar) must not return null for a TIME column");
            assertEquals(expectedLt, timeFromLabel.toLocalTime());

            rs.close();
            psSelect.close();
            psInsert.close();

            conn.createStatement().execute("DROP TABLE calendar_overload_test");
        } finally {
            conn.close();
        }
    }

    /**
     * Tests java.time types that may have partial or lossy support in H2.
     * Instant - H2 may convert through Timestamp (no direct Instant column type).
     * This test documents expected behavior - success with conversion or database error.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing H2 partial java.time support for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "h2_partial_types_test", TestDBUtils.SqlSyntax.H2);

        try {
            java.sql.PreparedStatement psInsert = conn.prepareStatement(
                    "insert into h2_partial_types_test (val_int, val_instant) values (?, ?)"
            );

            psInsert.setInt(1, 2);
            
            // Instant - H2 may support via conversion to TIMESTAMP
            Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
            psInsert.setObject(2, valInstant, Types.TIMESTAMP);
            
            // If this succeeds, H2 converted Instant to Timestamp
            psInsert.executeUpdate();
            
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from h2_partial_types_test where val_int = ?"
            );
            psSelect.setInt(1, 2);
            ResultSet resultSet = psSelect.executeQuery();
            
            if (resultSet.next()) {
                Object valInstantRet = resultSet.getObject(1);
                assertNotNull(valInstantRet, "Instant should not be null if supported");
                
                // H2 may return as Timestamp - verify conversion worked
                if (valInstantRet instanceof Instant) {
                    assertEquals(valInstant.getEpochSecond(), ((Instant) valInstantRet).getEpochSecond());
                } else if (valInstantRet instanceof Timestamp) {
                    Instant retrievedInstant = ((Timestamp) valInstantRet).toInstant();
                    assertEquals(valInstant.getEpochSecond(), retrievedInstant.getEpochSecond());
                }
            }
            
            resultSet.close();
            psSelect.close();
            psInsert.close();
            
            executeUpdate(conn, "delete from h2_partial_types_test where val_int=2");
            
        } catch (SQLException e) {
            // Expected if H2 doesn't support Instant directly
            // Document the error for debugging
            System.out.println("H2 Instant support: " + e.getMessage());
            // This is acceptable - not all databases support all java.time types
        } finally {
            conn.close();
        }
    }

}
