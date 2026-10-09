package org.openjproxy.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import com.openjproxy.grpc.IntArray;
import com.openjproxy.grpc.LongArray;
import com.openjproxy.grpc.StringArray;
import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterProto;
import com.openjproxy.grpc.ParameterTypeProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.ResultRow;
import com.openjproxy.grpc.TimestampWithZone;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Date;
import java.sql.RowId;
import java.sql.RowIdLifetime;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Converter between Java DTOs and Protocol Buffer messages.
 */
public class ProtoConverter {

    private static final Optional<Class<?>> POSTGRES_PGOBJECT_CLASS = loadOptionalClass("org.postgresql.util.PGobject");

    /**
     * Convert a Parameter DTO to ParameterProto message.
     * Handles temporal types (DATE, TIME, TIMESTAMP) with special conversion logic.
     */
    public static ParameterProto toProto(Parameter parameter) {
        if (parameter == null) {
            return null;
        }

        ParameterProto.Builder builder = ParameterProto.newBuilder()
                .setIndex(parameter.getIndex() != null ? parameter.getIndex() : 0)
                .setType(toProto(parameter.getType()));

        if (parameter.getValues() != null) {
            // Special handling for temporal types
            if (parameter.getType() == ParameterType.TIMESTAMP && !parameter.getValues().isEmpty()) {
                // TIMESTAMP: first value is Timestamp or java.time temporal type, optional second value is Calendar
                Object firstValue = parameter.getValues().get(0);
                if (firstValue != null && firstValue instanceof Timestamp) {
                    Timestamp timestamp = (Timestamp) firstValue;
                    java.time.ZoneId zoneId = null;

                    // Check if Calendar was provided (for timezone)
                    if (parameter.getValues().size() > 1 && parameter.getValues().get(1) instanceof java.util.Calendar) {
                        java.util.Calendar cal = (java.util.Calendar) parameter.getValues().get(1);
                        zoneId = cal.getTimeZone().toZoneId();
                    } else {
                        // Use system default timezone as per requirements
                        zoneId = java.time.ZoneId.systemDefault();
                    }

                    // Convert to TimestampWithZone and add as ParameterValue
                    builder.addValues(toParameterValue(timestamp, zoneId));
                } else if (firstValue == null) {
                    builder.addValues(toParameterValue(null));
                } else {
                    // For java.time types with TIMESTAMP parameter type, convert directly
                    builder.addValues(toParameterValue(firstValue));
                }
            } else if (parameter.getType() == ParameterType.DATE && !parameter.getValues().isEmpty()) {
                // DATE: convert using typed proto field
                Object firstValue = parameter.getValues().get(0);
                builder.addValues(toParameterValueDate(firstValue));
            } else if (parameter.getType() == ParameterType.TIME && !parameter.getValues().isEmpty()) {
                // TIME: convert using typed proto field
                Object firstValue = parameter.getValues().get(0);
                builder.addValues(toParameterValueTime(firstValue));
            } else if (parameter.getType() == ParameterType.OBJECT && !parameter.getValues().isEmpty()
                    && isPostgresPgObject(parameter.getValues().get(0))) {
                addPostgresPgObjectValues(builder, parameter.getValues());
            } else if (parameter.getType() == ParameterType.OBJECT && parameter.getValues().size() == 2
                    && parameter.getValues().get(1) instanceof Integer) {
                // OBJECT with targetSqlType: first value is the object, second value is java.sql.Types constant
                // This happens when setObject(index, value, targetSqlType) is called
                Object value = parameter.getValues().get(0);
                Integer targetSqlType = (Integer) parameter.getValues().get(1);
                addObjectValueForTargetSqlType(builder, value, targetSqlType);
                builder.addValues(toParameterValue(targetSqlType));
            } else if (parameter.getType() == ParameterType.OBJECT && parameter.getValues().size() == 3
                    && parameter.getValues().get(1) instanceof Integer
                    && parameter.getValues().get(2) instanceof Integer) {
                Object value = parameter.getValues().get(0);
                Integer targetSqlType = (Integer) parameter.getValues().get(1);
                Integer scaleOrLength = (Integer) parameter.getValues().get(2);
                addObjectValueForTargetSqlType(builder, value, targetSqlType);
                builder.addValues(toParameterValue(targetSqlType));
                builder.addValues(toParameterValue(scaleOrLength));
            } else {
                // For all other types, use standard conversion
                for (Object value : parameter.getValues()) {
                    builder.addValues(toParameterValue(value));
                }
            }
        }

        return builder.build();
    }

    /**
     * Convert a ParameterProto message to Parameter DTO.
     */
    public static Parameter fromProto(ParameterProto proto) {
        if (proto == null) {
            return null;
        }

        ParameterType type = fromProto(proto.getType());
        List<Object> values = new ArrayList<>();
        for (ParameterValue pv : proto.getValuesList()) {
            Object value = fromParameterValue(pv, type);
            values.add(value);
        }

        return Parameter.builder()
                .index(proto.getIndex())
                .type(type)
                .values(values)
                .build();
    }

    /**
     * Convert a list of Parameter DTOs to a list of ParameterProto messages.
     */
    public static List<ParameterProto> toProtoList(List<Parameter> parameters) {
        if (parameters == null) {
            return new ArrayList<>();
        }

        List<ParameterProto> result = new ArrayList<>();
        for (Parameter param : parameters) {
            result.add(toProto(param));
        }
        return result;
    }

    /**
     * Convert a list of ParameterProto messages to a list of Parameter DTOs.
     */
    public static List<Parameter> fromProtoList(List<ParameterProto> protos) {
        if (protos == null) {
            return new ArrayList<>();
        }

        List<Parameter> result = new ArrayList<>();
        for (ParameterProto proto : protos) {
            result.add(fromProto(proto));
        }
        return result;
    }

    /**
     * Convert ParameterType enum to ParameterTypeProto.
     */
    public static ParameterTypeProto toProto(ParameterType type) {
        if (type == null) {
            return ParameterTypeProto.PT_NULL;
        }

        switch (type) {
            case NULL: return ParameterTypeProto.PT_NULL;
            case BOOLEAN: return ParameterTypeProto.PT_BOOLEAN;
            case BYTE: return ParameterTypeProto.PT_BYTE;
            case SHORT: return ParameterTypeProto.PT_SHORT;
            case INT: return ParameterTypeProto.PT_INT;
            case LONG: return ParameterTypeProto.PT_LONG;
            case FLOAT: return ParameterTypeProto.PT_FLOAT;
            case DOUBLE: return ParameterTypeProto.PT_DOUBLE;
            case BIG_DECIMAL: return ParameterTypeProto.PT_BIG_DECIMAL;
            case STRING: return ParameterTypeProto.PT_STRING;
            case BYTES: return ParameterTypeProto.PT_BYTES;
            case DATE: return ParameterTypeProto.PT_DATE;
            case TIME: return ParameterTypeProto.PT_TIME;
            case TIMESTAMP: return ParameterTypeProto.PT_TIMESTAMP;
            case ASCII_STREAM: return ParameterTypeProto.PT_ASCII_STREAM;
            case UNICODE_STREAM: return ParameterTypeProto.PT_UNICODE_STREAM;
            case BINARY_STREAM: return ParameterTypeProto.PT_BINARY_STREAM;
            case OBJECT: return ParameterTypeProto.PT_OBJECT;
            case CHARACTER_READER: return ParameterTypeProto.PT_CHARACTER_READER;
            case REF: return ParameterTypeProto.PT_REF;
            case BLOB: return ParameterTypeProto.PT_BLOB;
            case CLOB: return ParameterTypeProto.PT_CLOB;
            case ARRAY: return ParameterTypeProto.PT_ARRAY;
            case URL: return ParameterTypeProto.PT_URL;
            case ROW_ID: return ParameterTypeProto.PT_ROW_ID;
            case N_STRING: return ParameterTypeProto.PT_N_STRING;
            case N_CHARACTER_STREAM: return ParameterTypeProto.PT_N_CHARACTER_STREAM;
            case N_CLOB: return ParameterTypeProto.PT_N_CLOB;
            case SQL_XML: return ParameterTypeProto.PT_SQL_XML;
            default: return ParameterTypeProto.PT_OBJECT;
        }
    }

    /**
     * Convert ParameterTypeProto to ParameterType enum.
     */
    public static ParameterType fromProto(ParameterTypeProto proto) {
        if (proto == null) {
            return ParameterType.NULL;
        }

        switch (proto) {
            case PT_NULL: return ParameterType.NULL;
            case PT_BOOLEAN: return ParameterType.BOOLEAN;
            case PT_BYTE: return ParameterType.BYTE;
            case PT_SHORT: return ParameterType.SHORT;
            case PT_INT: return ParameterType.INT;
            case PT_LONG: return ParameterType.LONG;
            case PT_FLOAT: return ParameterType.FLOAT;
            case PT_DOUBLE: return ParameterType.DOUBLE;
            case PT_BIG_DECIMAL: return ParameterType.BIG_DECIMAL;
            case PT_STRING: return ParameterType.STRING;
            case PT_BYTES: return ParameterType.BYTES;
            case PT_DATE: return ParameterType.DATE;
            case PT_TIME: return ParameterType.TIME;
            case PT_TIMESTAMP: return ParameterType.TIMESTAMP;
            case PT_ASCII_STREAM: return ParameterType.ASCII_STREAM;
            case PT_UNICODE_STREAM: return ParameterType.UNICODE_STREAM;
            case PT_BINARY_STREAM: return ParameterType.BINARY_STREAM;
            case PT_OBJECT: return ParameterType.OBJECT;
            case PT_CHARACTER_READER: return ParameterType.CHARACTER_READER;
            case PT_REF: return ParameterType.REF;
            case PT_BLOB: return ParameterType.BLOB;
            case PT_CLOB: return ParameterType.CLOB;
            case PT_ARRAY: return ParameterType.ARRAY;
            case PT_URL: return ParameterType.URL;
            case PT_ROW_ID: return ParameterType.ROW_ID;
            case PT_N_STRING: return ParameterType.N_STRING;
            case PT_N_CHARACTER_STREAM: return ParameterType.N_CHARACTER_STREAM;
            case PT_N_CLOB: return ParameterType.N_CLOB;
            case PT_SQL_XML: return ParameterType.SQL_XML;
            default: return ParameterType.OBJECT;
        }
    }

    /**
     * Convert a Java object to ParameterValue.
     * For temporal types (Date, Time, Timestamp), this method should not be called directly
     * as they need special handling. Use toParameterValueDate, toParameterValueTime, or
     * toParameterValue(Timestamp, ZoneId) instead.
     */
    public static ParameterValue toParameterValue(Object value) {
        ParameterValue.Builder builder = ParameterValue.newBuilder();

        if (value == null) {
            // Set explicit null marker to distinguish null from empty bytes
            builder.setIsNull(true);
            return builder.build();
        } else if (value instanceof Boolean) {
            builder.setBoolValue((Boolean) value);
        } else if (value instanceof Byte) {
            // Byte is stored as int32 for efficiency, will be cast back on server
            builder.setIntValue(((Byte) value).intValue());
        } else if (value instanceof Short) {
            // Short is stored as int32 for efficiency, will be cast back on server
            builder.setIntValue(((Short) value).intValue());
        } else if (value instanceof Integer) {
            builder.setIntValue((Integer) value);
        } else if (value instanceof Long) {
            builder.setLongValue((Long) value);
        } else if (value instanceof Float) {
            builder.setFloatValue((Float) value);
        } else if (value instanceof Double) {
            builder.setDoubleValue((Double) value);
        } else if (value instanceof String) {
            builder.setStringValue((String) value);
        } else if (value instanceof byte[]) {
            builder.setBytesValue(ByteString.copyFrom((byte[]) value));
        } else if (value instanceof int[]) {
            // Handle int array
            int[] arr = (int[]) value;
            IntArray.Builder intArrayBuilder = IntArray.newBuilder();
            for (int i : arr) {
                intArrayBuilder.addValues(i);
            }
            builder.setIntArrayValue(intArrayBuilder.build());
        } else if (value instanceof long[]) {
            // Handle long array
            long[] arr = (long[]) value;
            LongArray.Builder longArrayBuilder = LongArray.newBuilder();
            for (long l : arr) {
                longArrayBuilder.addValues(l);
            }
            builder.setLongArrayValue(longArrayBuilder.build());
        } else if (value instanceof BigDecimal) {
            // Use BigDecimalWire for compact, language-neutral serialization
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                BigDecimalWire.writeBigDecimal(dos, (BigDecimal) value);
                dos.flush();
                builder.setBytesValue(ByteString.copyFrom(baos.toByteArray()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to serialize BigDecimal", e);
            }
        } else if (value instanceof Date) {
            // java.sql.Date - convert to typed proto field
            // This can happen when setObject() is used instead of setDate()
            return toParameterValueDate(value);
        } else if (value instanceof Time) {
            // java.sql.Time - convert to typed proto field
            // This can happen when setObject() is used instead of setTime()
            return toParameterValueTime(value);
        } else if (value instanceof Timestamp) {
            // java.sql.Timestamp - convert to typed proto field with system default timezone
            // This can happen when setObject() is used instead of setTimestamp()
            // Use system default timezone since no Calendar was provided
            return toParameterValue((Timestamp) value, java.time.ZoneId.systemDefault());
        } else if (value instanceof URL) {
            // java.net.URL - convert to string representation using toExternalForm()
            // Use language-independent string encoding instead of Java serialization
            Optional<StringValue> urlWrapper = ProtoTypeConverters.urlToProto((URL) value);
            if (urlWrapper.isPresent()) {
                builder.setUrlValue(urlWrapper.get());
            } else {
                // This branch shouldn't be reached since value is not null, but handle defensively
                builder.setIsNull(true);
            }
        } else if (value instanceof RowId) {
            // java.sql.RowId - convert to base64-encoded bytes
            // Use language-independent string encoding instead of Java serialization
            // RowId bytes are opaque and vendor-specific
            Optional<StringValue> rowIdWrapper = ProtoTypeConverters.rowIdToProto((RowId) value);
            if (rowIdWrapper.isPresent()) {
                builder.setRowidValue(rowIdWrapper.get());
            } else {
                // This branch shouldn't be reached since value is not null, but handle defensively
                builder.setIsNull(true);
            }
        } else if (value instanceof UUID) {
            // java.util.UUID - convert to canonical string representation
            Optional<StringValue> uuidWrapper = ProtoTypeConverters.uuidToProto((UUID) value);
            if (uuidWrapper.isPresent()) {
                builder.setUuidValue(uuidWrapper.get());
            } else {
                // This branch shouldn't be reached since value is not null, but handle defensively
                builder.setIsNull(true);
            }
        } else if (value instanceof BigInteger) {
            // java.math.BigInteger - convert to decimal string representation
            builder.setBigintegerValue(StringValue.of(((BigInteger) value).toString()));
        } else if (value instanceof RowIdLifetime) {
            // java.sql.RowIdLifetime - convert to enum name string
            builder.setRowidlifetimeValue(StringValue.of(((RowIdLifetime) value).name()));
        } else if (value instanceof String[]) {
            // String array for JDBC methods like execute(sql, String[] columnNames)
            String[] arr = (String[]) value;
            StringArray.Builder stringArrayBuilder = StringArray.newBuilder();
            for (String s : arr) {
                if (s != null) {
                    stringArrayBuilder.addValues(s);
                } else {
                    stringArrayBuilder.addValues("");  // Treat null as empty string in arrays
                }
            }
            builder.setStringArrayValue(stringArrayBuilder.build());
        } else if (value instanceof Calendar) {
            // java.util.Calendar or GregorianCalendar - convert to TimestampWithZone
            // Use calendarToTimestampWithZone to preserve original type as CALENDAR
            builder.setTimestampValue(TemporalConverter.calendarToTimestampWithZone((Calendar) value));
        } else if (value instanceof OffsetDateTime) {
            // java.time.OffsetDateTime - convert to TimestampWithZone
            // Use offsetDateTimeToTimestampWithZone to preserve original type as OFFSET_DATE_TIME
            builder.setTimestampValue(TemporalConverter.offsetDateTimeToTimestampWithZone((OffsetDateTime) value));
        } else if (value instanceof java.time.LocalDateTime) {
            // java.time.LocalDateTime - convert to TimestampWithZone
            // Use localDateTimeToTimestampWithZone to preserve original type as LOCAL_DATE_TIME
            builder.setTimestampValue(TemporalConverter.localDateTimeToTimestampWithZone((java.time.LocalDateTime) value));
        } else if (value instanceof Instant) {
            // java.time.Instant - convert to TimestampWithZone
            // Use instantToTimestampWithZone to preserve original type as INSTANT
            builder.setTimestampValue(TemporalConverter.instantToTimestampWithZone((Instant) value));
        } else if (value instanceof java.time.OffsetTime) {
            // java.time.OffsetTime - convert to TimestampWithZone
            // Use offsetTimeToTimestampWithZone to preserve original type as OFFSET_TIME
            builder.setTimestampValue(TemporalConverter.offsetTimeToTimestampWithZone((java.time.OffsetTime) value));
        } else if (value instanceof LocalDate) {
            // java.time.LocalDate - convert to google.type.Date
            return toParameterValueLocalDate(value);
        } else if (value instanceof LocalTime) {
            // java.time.LocalTime - convert to google.type.TimeOfDay
            return toParameterValueLocalTime(value);
        } else if (value instanceof Map || value instanceof List || value instanceof java.util.Properties) {
            // For Map, List, and Properties objects, use protobuf serialization
            // instead of Java serialization for language independence
            try {
                byte[] protoBytes = org.openjproxy.grpc.transport.ProtoSerialization.serializeToTransport(value);
                builder.setBytesValue(ByteString.copyFrom(protoBytes));
            } catch (org.openjproxy.grpc.transport.ProtoSerialization.SerializationException e) {
                throw new RuntimeException("Failed to serialize Map/List/Properties to protobuf", e);
            }
        } else if (isJsonWrapperObject(value)) {
            // Database-specific JSON wrapper types (e.g., org.postgresql.util.PGobject,
            // oracle.sql.json.OracleJsonValue and its subclasses). These objects are not
            // in the standard Java API, so we detect them by class name to avoid compile-time
            // dependencies on vendor JDBC libraries. Calling toString() on them returns the
            // JSON text, which is the correct value to transport.
            builder.setStringValue(value.toString());
        } else {
            // For all other complex types, throw an exception as they are not supported
            // Only primitives, Maps, Lists, Properties, and null are supported for transport
            throw new IllegalArgumentException(
                    "Unsupported parameter value type: " + value.getClass().getName() +
                    ". Only primitives, Map, List, Properties, and null are supported.");
        }

        return builder.build();
    }

    /**
     * Returns true if {@code value} is a vendor-specific JSON wrapper object that can be
     * safely serialised by calling {@link Object#toString()}, which returns the JSON text.
     * <p>
     * Detected types (by class name, to avoid compile-time vendor JDBC dependencies):
     * <ul>
     *   <li>{@code org.postgresql.util.PGobject} — PostgreSQL JSON / JSONB / other custom types</li>
     *   <li>{@code oracle.sql.json.OracleJsonValue} and its subclasses — Oracle 21c+ native JSON</li>
     * </ul>
     * </p>
     */
    private static boolean isJsonWrapperObject(Object value) {
        if (value == null) {
            return false;
        }
        return isPostgresPgObject(value)
                || value.getClass().getName().startsWith("oracle.sql.json.Oracle");
    }

    private static boolean isPostgresPgObject(Object value) {
        return value != null
                && POSTGRES_PGOBJECT_CLASS.map(pgObjectClass -> pgObjectClass.isInstance(value)).orElse(false);
    }

    private static Optional<Class<?>> loadOptionalClass(String className) {
        try {
            return Optional.of(Class.forName(className));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private static void addPostgresPgObjectValues(ParameterProto.Builder builder, List<Object> values) {
        Object pgObject = values.get(0);
        builder.addValues(toParameterValue(extractPgObjectValue(pgObject)));
        builder.addValues(toParameterValue(CommonConstants.OJP_RELAYED_PGOBJECT_MARKER));
        builder.addValues(toParameterValue(extractPgObjectType(pgObject)));
        for (int i = 1; i < values.size(); i++) {
            builder.addValues(toParameterValue(values.get(i)));
        }
    }

    private static String extractPgObjectValue(Object pgObject) {
        try {
            return (String) pgObject.getClass().getMethod("getValue").invoke(pgObject);
        } catch (ReflectiveOperationException e) {
            return pgObject.toString();
        }
    }

    private static String extractPgObjectType(Object pgObject) {
        try {
            return (String) pgObject.getClass().getMethod("getType").invoke(pgObject);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unsupported PostgreSQL PGobject without accessible getType()", e);
        }
    }

    private static void addObjectValueForTargetSqlType(ParameterProto.Builder builder, Object value, int targetSqlType) {
        if (targetSqlType == java.sql.Types.TIMESTAMP || targetSqlType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
            // For TIMESTAMP types, use toParameterValue which handles java.time types
            builder.addValues(toParameterValue(value));
        } else if (targetSqlType == java.sql.Types.DATE) {
            // For DATE type, use toParameterValueDate
            builder.addValues(toParameterValueDate(value));
        } else if (targetSqlType == java.sql.Types.TIME) {
            // For plain TIME type, use toParameterValueTime (handles Time and LocalTime only)
            builder.addValues(toParameterValueTime(value));
        } else if (targetSqlType == java.sql.Types.TIME_WITH_TIMEZONE) {
            // For TIME_WITH_TIMEZONE, use toParameterValue which handles OffsetTime
            builder.addValues(toParameterValue(value));
        } else {
            // For other types, just convert the value directly
            builder.addValues(toParameterValue(value));
        }
    }

    /**
     * Convert java.sql.Timestamp with ZoneId to ParameterValue with TimestampWithZone.
     *
     * @param timestamp The timestamp to convert (can be null)
     * @param zoneId The timezone (must not be null if timestamp is not null)
     * @return ParameterValue with timestamp_value set, or is_null if timestamp is null
     */
    public static ParameterValue toParameterValue(Timestamp timestamp, java.time.ZoneId zoneId) {
        if (timestamp == null) {
            return ParameterValue.newBuilder().setIsNull(true).build();
        }

        TimestampWithZone timestampWithZone = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        return ParameterValue.newBuilder()
            .setTimestampValue(timestampWithZone)
            .build();
    }

    /**
     * Convert java.sql.Date or java.time.LocalDate to ParameterValue with google.type.Date.
     *
     * @param date The date to convert (can be null, java.sql.Date, or java.time.LocalDate)
     * @return ParameterValue with date_value set, or is_null if date is null
     */
    public static ParameterValue toParameterValueDate(Object date) {
        if (date == null) {
            return ParameterValue.newBuilder().setIsNull(true).build();
        }

        com.google.type.Date protoDate;
        if (date instanceof Date) {
            protoDate = TemporalConverter.toProtoDate((Date) date);
        } else if (date instanceof LocalDate) {
            protoDate = TemporalConverter.localDateToProtoDate((LocalDate) date);
        } else {
            throw new IllegalArgumentException("Expected java.sql.Date or java.time.LocalDate but got " + date.getClass().getName());
        }

        return ParameterValue.newBuilder()
            .setDateValue(protoDate)
            .build();
    }

    /**
     * Convert java.sql.Time or java.time.LocalTime to ParameterValue with google.type.TimeOfDay.
     *
     * @param time The time to convert (can be null, java.sql.Time, or java.time.LocalTime)
     * @return ParameterValue with time_value set, or is_null if time is null
     */
    public static ParameterValue toParameterValueTime(Object time) {
        if (time == null) {
            return ParameterValue.newBuilder().setIsNull(true).build();
        }

        com.google.type.TimeOfDay protoTimeOfDay;
        if (time instanceof Time) {
            protoTimeOfDay = TemporalConverter.toProtoTimeOfDay((Time) time);
        } else if (time instanceof LocalTime) {
            protoTimeOfDay = TemporalConverter.localTimeToProtoTimeOfDay((LocalTime) time);
        } else {
            throw new IllegalArgumentException("Expected java.sql.Time or java.time.LocalTime but got " + time.getClass().getName());
        }

        return ParameterValue.newBuilder()
            .setTimeValue(protoTimeOfDay)
            .build();
    }

    /**
     * Convert java.time.LocalDate to ParameterValue with google.type.Date.
     *
     * @param localDate The LocalDate to convert (can be null or Object that will be cast)
     * @return ParameterValue with date_value set, or is_null if localDate is null
     */
    public static ParameterValue toParameterValueLocalDate(Object localDate) {
        if (localDate == null) {
            return ParameterValue.newBuilder().setIsNull(true).build();
        }

        if (!(localDate instanceof LocalDate)) {
            throw new IllegalArgumentException("Expected java.time.LocalDate but got " + localDate.getClass().getName());
        }

        com.google.type.Date protoDate = TemporalConverter.localDateToProtoDate((LocalDate) localDate);
        return ParameterValue.newBuilder()
            .setDateValue(protoDate)
            .build();
    }

    /**
     * Convert java.time.LocalTime to ParameterValue with google.type.TimeOfDay.
     *
     * @param localTime The LocalTime to convert (can be null or Object that will be cast)
     * @return ParameterValue with time_value set, or is_null if localTime is null
     */
    public static ParameterValue toParameterValueLocalTime(Object localTime) {
        if (localTime == null) {
            return ParameterValue.newBuilder().setIsNull(true).build();
        }

        if (!(localTime instanceof LocalTime)) {
            throw new IllegalArgumentException("Expected java.time.LocalTime but got " + localTime.getClass().getName());
        }

        com.google.type.TimeOfDay protoTimeOfDay = TemporalConverter.localTimeToProtoTimeOfDay((LocalTime) localTime);
        return ParameterValue.newBuilder()
            .setTimeValue(protoTimeOfDay)
            .build();
    }

    /**
     * Convert ParameterValue to Java object.
     * Note: This returns a generic Object, caller needs to handle type casting.
     *
     * @param value The ParameterValue to convert
     * @param type The ParameterType to help determine how to handle bytes
     */
    public static Object fromParameterValue(ParameterValue value, ParameterType type) {
        if (value == null) {
            return null;
        }

        switch (value.getValueCase()) {
            case IS_NULL:
                // Explicit null marker - return null
                return null;
            case BOOL_VALUE:
                return value.getBoolValue();
            case INT_VALUE:
                return value.getIntValue();
            case LONG_VALUE:
                return value.getLongValue();
            case FLOAT_VALUE:
                return value.getFloatValue();
            case DOUBLE_VALUE:
                return value.getDoubleValue();
            case STRING_VALUE:
                return value.getStringValue();
            case BYTES_VALUE:
                byte[] bytes = value.getBytesValue().toByteArray();

                // For binary data types (BYTES, BLOB, BINARY_STREAM), preserve empty byte arrays
                // and don't attempt deserialization
                if (type != null && !shouldDeserializeBytes(type)) {
                    // Binary data types - return raw bytes (including empty arrays)
                    return bytes;
                }

                // Use BigDecimalWire deserialization for BIG_DECIMAL type
                if (type == ParameterType.BIG_DECIMAL) {
                    try {
                        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                        DataInputStream dis = new DataInputStream(bais);
                        return BigDecimalWire.readBigDecimal(dis);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to deserialize BigDecimal", e);
                    }
                }

                // When type is unknown, check if bytes look like protobuf Container message
                // Try protobuf first for Map/List/Properties.
                if (type == null && bytes.length > 0) {
                    // Try protobuf Container first (for Map/List/Properties)
                    try {
                        return org.openjproxy.grpc.transport.ProtoSerialization.deserializeFromTransport(bytes);
                    } catch (org.openjproxy.grpc.transport.ProtoSerialization.SerializationException e) {
                        // Not a protobuf Container, try BigDecimalWire
                        try {
                            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                            DataInputStream dis = new DataInputStream(bais);
                            BigDecimal result = BigDecimalWire.readBigDecimal(dis);
                            // Only return if we got a non-null result
                            if (result != null) {
                                return result;
                            }
                        } catch (IOException ex) {
                            // Not a BigDecimal either
                        }
                    }
                }

                // For unknown bytes that couldn't be deserialized, return raw bytes
                return bytes;
            case INT_ARRAY_VALUE:
                // Convert IntArray proto message to int[]
                IntArray intArray = value.getIntArrayValue();
                int[] intArr = new int[intArray.getValuesCount()];
                for (int i = 0; i < intArray.getValuesCount(); i++) {
                    intArr[i] = intArray.getValues(i);
                }
                return intArr;
            case LONG_ARRAY_VALUE:
                // Convert LongArray proto message to long[]
                LongArray longArray = value.getLongArrayValue();
                long[] longArr = new long[longArray.getValuesCount()];
                for (int i = 0; i < longArray.getValuesCount(); i++) {
                    longArr[i] = longArray.getValues(i);
                }
                return longArr;
            case TIMESTAMP_VALUE:
                // Convert TimestampWithZone proto message to appropriate Java type
                // Returns java.sql.Timestamp or java.util.Calendar based on original_type
                return TemporalConverter.fromTimestampWithZoneToObject(value.getTimestampValue());
            case DATE_VALUE:
                // Convert google.type.Date proto message to java.sql.Date
                return TemporalConverter.fromProtoDate(value.getDateValue());
            case TIME_VALUE:
                // Convert google.type.TimeOfDay proto message to java.sql.Time
                return TemporalConverter.fromProtoTimeOfDay(value.getTimeValue());
            case URL_VALUE:
                // Convert StringValue proto wrapper to java.net.URL
                // Uses language-independent string encoding (URL.toExternalForm)
                return ProtoTypeConverters.urlFromProto(value.getUrlValue());
            case ROWID_VALUE:
                // Convert StringValue proto wrapper to raw bytes (opaque, vendor-specific)
                // Cannot reconstruct java.sql.RowId from bytes alone
                // Return bytes for use by database layer
                return ProtoTypeConverters.rowIdBytesFromProto(value.getRowidValue());
            case UUID_VALUE:
                // Convert StringValue proto wrapper to java.util.UUID
                return ProtoTypeConverters.uuidFromProto(value.getUuidValue());
            case BIGINTEGER_VALUE:
                // Convert StringValue proto wrapper to java.math.BigInteger
                StringValue bigIntWrapper = value.getBigintegerValue();
                if (bigIntWrapper != null && !bigIntWrapper.getValue().isEmpty()) {
                    return new BigInteger(bigIntWrapper.getValue());
                }
                return null;
            case ROWIDLIFETIME_VALUE:
                // Convert StringValue proto wrapper to java.sql.RowIdLifetime
                StringValue rowidLifetimeWrapper = value.getRowidlifetimeValue();
                if (rowidLifetimeWrapper != null && !rowidLifetimeWrapper.getValue().isEmpty()) {
                    return RowIdLifetime.valueOf(rowidLifetimeWrapper.getValue());
                }
                return null;
            case STRING_ARRAY_VALUE:
                // Convert StringArray proto message to String[]
                StringArray stringArray = value.getStringArrayValue();
                String[] strArr = new String[stringArray.getValuesCount()];
                for (int i = 0; i < stringArray.getValuesCount(); i++) {
                    String s = stringArray.getValues(i);
                    strArr[i] = s.isEmpty() ? null : s;  // Treat empty string as null
                }
                return strArr;
            case VALUE_NOT_SET:
            default:
                return null;
        }
    }

    /**
     * Determine if bytes should be deserialized based on ParameterType.
     * Only deserialize for OBJECT and complex types that were serialized.
     * For binary data types (BYTES, BINARY_STREAM), return raw bytes.
     * Note: BLOB, CLOB, etc. are serialized Java objects and should be deserialized.
     * Note: DATE, TIME, TIMESTAMP should now use typed proto fields and never reach BYTES_VALUE case.
     */
    private static boolean shouldDeserializeBytes(ParameterType type) {
        // If no type information, try to deserialize (for CallResourceResponse compatibility)
        // This may fail for raw binary data, but caller should handle that
        if (type == null) {
            return true;
        }

        switch (type) {
            case BYTES:
            case BINARY_STREAM:
            case ASCII_STREAM:
            case UNICODE_STREAM:
            case CHARACTER_READER:
            case N_CHARACTER_STREAM:
                // These are raw binary/text data - don't deserialize
                return false;
            case BLOB:
            case CLOB:
            case N_CLOB:
            case OBJECT:
            case ARRAY:
            case REF:
            case SQL_XML:
            case BIG_DECIMAL:
            case URL:
            case ROW_ID:
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case STRING:
            case DATE:
            case TIME:
            case TIMESTAMP:
                // These types now use typed proto fields and should not reach BYTES_VALUE case.
                // However, we keep them in this list to handle any legacy serialized data
                // during migration. After full deployment, they will always use typed fields.
                // Attempt to deserialize
                return true;
            default:
                // For unknown types, try to deserialize
                return true;
        }
    }

    /**
     * Convert ParameterValue to Java object without type information.
     * This assumes bytes should be deserialized (for backward compatibility with CallResourceResponse).
     * Use fromParameterValue(ParameterValue, ParameterType) when type information is available.
     */
    public static Object fromParameterValue(ParameterValue value) {
        // For calls without type info, try to deserialize bytes
        // This is mainly for CallResourceResponse values where we don't have ParameterType
        return fromParameterValue(value, null);
    }

    /**
     * Convert OpQueryResult DTO to OpQueryResultProto message.
     */
    public static OpQueryResultProto toProto(OpQueryResult result) {
        if (result == null) {
            return null;
        }

        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder()
                .setResultSetUUID(result.getResultSetUUID() != null ? result.getResultSetUUID() : "");

        if (result.getLabels() != null) {
            builder.addAllLabels(result.getLabels());
        }

        if (result.getRows() != null) {
            for (Object[] row : result.getRows()) {
                ResultRow.Builder rowBuilder = ResultRow.newBuilder();
                if (row != null) {
                    for (Object col : row) {
                        rowBuilder.addColumns(toParameterValue(col));
                    }
                }
                builder.addRows(rowBuilder.build());
            }
        }

        return builder.build();
    }

    /**
     * Convert OpQueryResultProto message to OpQueryResult DTO.
     */
    public static OpQueryResult fromProto(OpQueryResultProto proto) {
        if (proto == null) {
            return null;
        }

        List<Object[]> rows = new ArrayList<>();
        for (ResultRow row : proto.getRowsList()) {
            Object[] rowData = new Object[row.getColumnsCount()];
            for (int i = 0; i < row.getColumnsCount(); i++) {
                rowData[i] = fromParameterValue(row.getColumns(i));
            }
            rows.add(rowData);
        }

        return OpQueryResult.builder()
                .resultSetUUID(proto.getResultSetUUID())
                .labels(new ArrayList<>(proto.getLabelsList()))
                .rows(rows)
                .build();
    }

    /**
     * Convert a Map of properties to a list of PropertyEntry messages.
     */
    public static List<PropertyEntry> propertiesToProto(Map<String, Object> properties) {
        if (properties == null) {
            return new ArrayList<>();
        }

        List<PropertyEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            PropertyEntry.Builder builder = PropertyEntry.newBuilder()
                    .setKey(entry.getKey());

            Object value = entry.getValue();
            if (value == null) {
                // Empty builder for null values
            } else if (value instanceof Boolean) {
                builder.setBoolValue((Boolean) value);
            } else if (value instanceof Byte) {
                builder.setIntValue((Byte) value);
            } else if (value instanceof Short) {
                builder.setIntValue((Short) value);
            } else if (value instanceof Integer) {
                builder.setIntValue((Integer) value);
            } else if (value instanceof Long) {
                builder.setLongValue((Long) value);
            } else if (value instanceof Float) {
                builder.setFloatValue((Float) value);
            } else if (value instanceof Double) {
                builder.setDoubleValue((Double) value);
            } else if (value instanceof String) {
                builder.setStringValue((String) value);
            } else if (value instanceof byte[]) {
                builder.setBytesValue(ByteString.copyFrom((byte[]) value));
            } else if (value instanceof BigDecimal) {
                // Use BigDecimalWire for compact, language-neutral serialization
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    BigDecimalWire.writeBigDecimal(dos, (BigDecimal) value);
                    dos.flush();
                    builder.setBytesValue(ByteString.copyFrom(baos.toByteArray()));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to serialize BigDecimal", e);
                }
            } else if (value instanceof Map || value instanceof List || value instanceof java.util.Properties) {
                // For Map, List, and Properties objects, use protobuf serialization
                try {
                    byte[] protoBytes = org.openjproxy.grpc.transport.ProtoSerialization.serializeToTransport(value);
                    builder.setBytesValue(ByteString.copyFrom(protoBytes));
                } catch (org.openjproxy.grpc.transport.ProtoSerialization.SerializationException e) {
                    throw new RuntimeException("Failed to serialize Map/List/Properties to protobuf", e);
                }
            } else {
                // For complex objects that are not supported, throw an exception
                throw new IllegalArgumentException(
                        "Unsupported property value type: " + value.getClass().getName() +
                        ". Only primitives, BigDecimal, Map, List, Properties, and null are supported.");
            }

            entries.add(builder.build());
        }

        return entries;
    }

    /**
     * Convert a list of PropertyEntry messages to a Map of properties.
     */
    public static Map<String, Object> propertiesFromProto(List<PropertyEntry> entries) {
        if (entries == null) {
            return new HashMap<>();
        }

        Map<String, Object> properties = new HashMap<>();
        for (PropertyEntry entry : entries) {
            Object value = null;
            switch (entry.getValueCase()) {
                case BOOL_VALUE:
                    value = entry.getBoolValue();
                    break;
                case INT_VALUE:
                    value = entry.getIntValue();
                    break;
                case LONG_VALUE:
                    value = entry.getLongValue();
                    break;
                case FLOAT_VALUE:
                    value = entry.getFloatValue();
                    break;
                case DOUBLE_VALUE:
                    value = entry.getDoubleValue();
                    break;
                case STRING_VALUE:
                    value = entry.getStringValue();
                    break;
                case BYTES_VALUE:
                    // Try to deserialize as protobuf Container for Map/List/Properties
                    byte[] bytes = entry.getBytesValue().toByteArray();
                    try {
                        // Try protobuf deserialization first for Map/List/Properties
                        value = org.openjproxy.grpc.transport.ProtoSerialization.deserializeFromTransport(bytes);
                    } catch (org.openjproxy.grpc.transport.ProtoSerialization.SerializationException e) {
                        // Not a protobuf message, return raw bytes
                        value = bytes;
                    }
                    break;
                case VALUE_NOT_SET:
                default:
                    value = null;
                    break;
            }
            properties.put(entry.getKey(), value);
        }

        return properties;
    }

    /**
     * Convert a list of objects to a list of ParameterValue messages.
     */
    public static List<ParameterValue> objectListToParameterValues(List<Object> objects) {
        if (objects == null) {
            return new ArrayList<>();
        }

        List<ParameterValue> values = new ArrayList<>();
        for (Object obj : objects) {
            values.add(toParameterValue(obj));
        }
        return values;
    }

    /**
     * Convert a list of ParameterValue messages to a list of objects.
     */
    public static List<Object> parameterValuesToObjectList(List<ParameterValue> values) {
        if (values == null) {
            return new ArrayList<>();
        }

        List<Object> objects = new ArrayList<>();
        for (ParameterValue value : values) {
            objects.add(fromParameterValue(value));
        }
        return objects;
    }
}
