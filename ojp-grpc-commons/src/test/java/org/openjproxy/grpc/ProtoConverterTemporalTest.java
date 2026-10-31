package org.openjproxy.grpc;

import com.openjproxy.grpc.ParameterProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.TimestampWithZone;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;
import org.postgresql.util.PGobject;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProtoConverter with temporal types.
 * Tests the full round-trip conversion from Parameter DTO to ParameterProto and back.
 */
class ProtoConverterTemporalTest {

    @Test
    void testTimestampWithoutCalendarUsesSystemDefault() {
        // When no Calendar is provided, system default timezone should be used
        Timestamp timestamp = Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(List.of(timestamp))
            .build();
        
        // Convert to proto
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        assertEquals(1, proto.getIndex());
        assertEquals(1, proto.getValuesCount());
        assertSame(proto.getValues(0).getValueCase(), ParameterValue.ValueCase.TIMESTAMP_VALUE);
        
        // Verify timezone is set (should be system default)
        TimestampWithZone tsWithZone = proto.getValues(0).getTimestampValue();
        assertNotNull(tsWithZone.getTimezone());
        assertFalse(tsWithZone.getTimezone().isEmpty());
        assertEquals(ZoneId.systemDefault().getId(), tsWithZone.getTimezone());
        
        // Convert back
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(ParameterType.TIMESTAMP, result.getType());
        assertEquals(1, result.getValues().size());
        
        Timestamp resultTimestamp = (Timestamp) result.getValues().get(0);
        assertEquals(timestamp, resultTimestamp);
        assertEquals(timestamp.getNanos(), resultTimestamp.getNanos());
    }
    
    @Test
    void testTimestampWithCalendar() {
        // When Calendar is provided, its timezone should be used
        Timestamp timestamp = Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Arrays.asList(timestamp, calendar))
            .build();
        
        // Convert to proto
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        assertSame(proto.getValues(0).getValueCase(), ParameterValue.ValueCase.TIMESTAMP_VALUE);
        
        // Verify timezone from calendar is used
        TimestampWithZone tsWithZone = proto.getValues(0).getTimestampValue();
        assertEquals("America/New_York", tsWithZone.getTimezone());
        
        // Convert back
        Parameter result = ProtoConverter.fromProto(proto);
        Timestamp resultTimestamp = (Timestamp) result.getValues().get(0);
        assertEquals(timestamp, resultTimestamp);
    }
    
    @Test
    void testTimestampWithOffsetTimezone() {
        Timestamp timestamp = Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+02:00"));
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Arrays.asList(timestamp, calendar))
            .build();
        
        ParameterProto proto = ProtoConverter.toProto(param);
        TimestampWithZone tsWithZone = proto.getValues(0).getTimestampValue();
        
        // Should accept offset timezone
        assertTrue(tsWithZone.getTimezone().contains("02:00") 
                   || tsWithZone.getTimezone().equals("GMT+02:00"));
        
        // Should convert back successfully
        Parameter result = ProtoConverter.fromProto(proto);
        Timestamp resultTimestamp = (Timestamp) result.getValues().get(0);
        assertEquals(timestamp, resultTimestamp);
    }
    
    @Test
    void testTimestampNull() {
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(null))
            .build();
        
        ParameterProto proto = ProtoConverter.toProto(param);
        // Should not throw, just have no values since null timestamp doesn't convert
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
    }
    
    @Test
    void testDateRoundTrip() {
        Date date = Date.valueOf("2024-11-02");
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.DATE)
            .values(Collections.singletonList(date))
            .build();
        
        // Convert to proto
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        assertEquals(1, proto.getValuesCount());
        assertSame(proto.getValues(0).getValueCase(), ParameterValue.ValueCase.DATE_VALUE);
        
        // Verify date values
        com.google.type.Date protoDate = proto.getValues(0).getDateValue();
        assertEquals(2024, protoDate.getYear());
        assertEquals(11, protoDate.getMonth());
        assertEquals(2, protoDate.getDay());
        
        // Convert back
        Parameter result = ProtoConverter.fromProto(proto);
        assertEquals(ParameterType.DATE, result.getType());
        Date resultDate = (Date) result.getValues().get(0);
        assertEquals(date, resultDate);
    }
    
    @Test
    void testDateNull() {
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.DATE)
            .values(Collections.singletonList(null))
            .build();
        
        ParameterProto proto = ProtoConverter.toProto(param);
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
    }
    
    @Test
    void testTimeRoundTrip() {
        Time time = Time.valueOf("14:30:45");
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIME)
            .values(List.of(time))
            .build();
        
        // Convert to proto
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        assertEquals(1, proto.getValuesCount());
        assertSame(proto.getValues(0).getValueCase(), ParameterValue.ValueCase.TIME_VALUE);
        
        // Verify time values
        com.google.type.TimeOfDay protoTime = proto.getValues(0).getTimeValue();
        assertEquals(14, protoTime.getHours());
        assertEquals(30, protoTime.getMinutes());
        assertEquals(45, protoTime.getSeconds());
        
        // Convert back
        Parameter result = ProtoConverter.fromProto(proto);
        assertEquals(ParameterType.TIME, result.getType());
        Time resultTime = (Time) result.getValues().get(0);
        assertEquals(time.toString(), resultTime.toString());
    }
    
    @Test
    void testTimeNull() {
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIME)
            .values(Collections.singletonList(null))
            .build();
        
        ParameterProto proto = ProtoConverter.toProto(param);
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
    }
    
    @Test
    void testMultipleParametersMixedTypes() {
        // Test multiple parameters including temporal types
        Timestamp timestamp = Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        Date date = Date.valueOf("2024-11-02");
        Time time = Time.valueOf("14:30:45");
        
        Parameter timestampParam = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(List.of(timestamp))
            .build();
        
        Parameter dateParam = Parameter.builder()
            .index(2)
            .type(ParameterType.DATE)
            .values(Collections.singletonList(date))
            .build();
        
        Parameter timeParam = Parameter.builder()
            .index(3)
            .type(ParameterType.TIME)
            .values(List.of(time))
            .build();
        
        // Convert all to proto
        ParameterProto proto1 = ProtoConverter.toProto(timestampParam);
        ParameterProto proto2 = ProtoConverter.toProto(dateParam);
        ParameterProto proto3 = ProtoConverter.toProto(timeParam);
        
        // Verify all have correct typed values
        assertSame(proto1.getValues(0).getValueCase(), ParameterValue.ValueCase.TIMESTAMP_VALUE);
        assertSame(proto2.getValues(0).getValueCase(), ParameterValue.ValueCase.DATE_VALUE);
        assertSame(proto3.getValues(0).getValueCase(), ParameterValue.ValueCase.TIME_VALUE);
        
        // Convert back
        Parameter result1 = ProtoConverter.fromProto(proto1);
        Parameter result2 = ProtoConverter.fromProto(proto2);
        Parameter result3 = ProtoConverter.fromProto(proto3);
        
        assertEquals(timestamp, result1.getValues().get(0));
        assertEquals(date, result2.getValues().get(0));
        assertEquals(time.toString(), result3.getValues().get(0).toString());
    }

    @Test
    void testObjectWithTargetSqlTypePreservesMetadata() {
        Parameter param = Parameter.builder()
                .index(7)
                .type(ParameterType.OBJECT)
                .values(Arrays.asList(LocalTime.of(10, 15, 30), java.sql.Types.TIME, 12))
                .build();

        Parameter result = ProtoConverter.fromProto(ProtoConverter.toProto(param));

        assertEquals(ParameterType.OBJECT, result.getType());
        assertEquals(3, result.getValues().size());
        assertEquals("10:15:30", result.getValues().get(0).toString());
        assertEquals(java.sql.Types.TIME, result.getValues().get(1));
        assertEquals(12, result.getValues().get(2));
    }

    @Test
    void testPgObjectRelayPreservesTypeMetadata() {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue("{\"active\":true}");

        Parameter param = Parameter.builder()
                .index(1)
                .type(ParameterType.OBJECT)
                .values(List.of(pgObject))
                .build();

        Parameter result = ProtoConverter.fromProto(ProtoConverter.toProto(param));

        assertEquals(ParameterType.OBJECT, result.getType());
        assertEquals(3, result.getValues().size());
        assertEquals("{\"active\":true}", result.getValues().get(0));
        assertEquals(CommonConstants.OJP_RELAYED_PGOBJECT_MARKER, result.getValues().get(1));
        assertEquals("jsonb", result.getValues().get(2));
    }
}
