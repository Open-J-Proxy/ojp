package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.postgresql.util.PGobject;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;
import org.openjproxy.grpc.server.SessionManager;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ParameterHandler}.
 *
 * <p>Verifies that safe scalar types are bound directly on the calling thread
 * (no Future/Executor overhead) and that risky I/O types are classified correctly.</p>
 */
class ParameterHandlerTest {

    @Mock
    private PreparedStatement ps;

    @Mock
    private SessionManager sessionManager;

    private SessionInfo session;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        session = SessionInfo.newBuilder()
                .setSessionUUID("test-session")
                .setConnHash("test-hash")
                .setClientUUID("test-client")
                .build();
    }

    // -------------------------------------------------------------------------
    // isRiskyParameterType classification
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ParameterType.class, names = {
        "BLOB", "CLOB", "BINARY_STREAM", "ASCII_STREAM", "UNICODE_STREAM",
        "CHARACTER_READER", "N_CHARACTER_STREAM", "N_CLOB", "SQL_XML", "OBJECT", "ARRAY", "REF"
    })
    void shouldClassifyIoTypesAsRisky(ParameterType type) {
        assertTrue(ParameterHandler.isRiskyParameterType(type),
                type + " should be classified as risky");
    }

    @ParameterizedTest
    @EnumSource(value = ParameterType.class, names = {
        "NULL", "BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE",
        "BIG_DECIMAL", "STRING", "BYTES", "DATE", "TIME", "TIMESTAMP", "URL", "ROW_ID", "N_STRING"
    })
    void shouldClassifyScalarTypesAsSafe(ParameterType type) {
        assertFalse(ParameterHandler.isRiskyParameterType(type),
                type + " should not be classified as risky");
    }

    // -------------------------------------------------------------------------
    // Safe scalar types — direct binding, no Future overhead
    // -------------------------------------------------------------------------

    @Test
    void shouldBindIntegerParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.INT).values(List.of(42)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setInt(1, 42);
        verifyNoInteractions(sessionManager);
    }

    @Test
    void shouldBindLongParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.LONG).values(List.of(1_000_000L)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setLong(1, 1_000_000L);
    }

    @Test
    void shouldBindDoubleParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.DOUBLE).values(List.of(3.14d)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setDouble(1, 3.14d);
    }

    @Test
    void shouldBindStringParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.STRING).values(List.of("hello")).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setString(1, "hello");
    }

    @Test
    void shouldBindBooleanParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.BOOLEAN).values(List.of(true)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setBoolean(1, true);
    }

    @Test
    void shouldBindShortParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.SHORT).values(List.of(7)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setShort(1, (short) 7);
    }

    @Test
    void shouldBindFloatParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.FLOAT).values(List.of(1.5f)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setFloat(1, 1.5f);
    }

    @Test
    void shouldBindBigDecimalParameterDirectly() throws SQLException {
        BigDecimal value = new BigDecimal("123.456");
        Parameter param = Parameter.builder().index(1).type(ParameterType.BIG_DECIMAL).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setBigDecimal(1, value);
    }

    @Test
    void shouldBindDateParameterDirectly() throws SQLException {
        Date value = Date.valueOf("2024-01-15");
        Parameter param = Parameter.builder().index(1).type(ParameterType.DATE).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setDate(1, value);
    }

    @Test
    void shouldBindTimeParameterDirectly() throws SQLException {
        Time value = Time.valueOf("12:30:00");
        Parameter param = Parameter.builder().index(1).type(ParameterType.TIME).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setTime(1, value);
    }

    @Test
    void shouldBindTimestampParameterDirectly() throws SQLException {
        Timestamp value = Timestamp.valueOf("2024-01-15 12:30:00");
        Parameter param = Parameter.builder().index(1).type(ParameterType.TIMESTAMP).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setTimestamp(1, value);
    }

    @Test
    void shouldBindOffsetDateTimeTimestampParameterViaSetObject() throws SQLException {
        java.time.OffsetDateTime value = java.time.OffsetDateTime.parse("2024-01-15T12:30:00+02:00");
        Parameter param = Parameter.builder().index(1).type(ParameterType.TIMESTAMP).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setObject(1, value);
    }

    @Test
    void shouldBindLocalDateTimeTimestampParameterViaSetObject() throws SQLException {
        java.time.LocalDateTime value = java.time.LocalDateTime.parse("2024-01-15T12:30:00");
        Parameter param = Parameter.builder().index(1).type(ParameterType.TIMESTAMP).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setObject(1, value);
    }

    @Test
    void shouldBindInstantTimestampParameterAsConvertedTimestamp() throws SQLException {
        java.time.Instant value = java.time.Instant.parse("2024-01-15T12:30:00Z");
        Parameter param = Parameter.builder().index(1).type(ParameterType.TIMESTAMP).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setTimestamp(1, Timestamp.from(value));
    }

    @Test
    void shouldKeepLegacyObjectBindingWhenTargetSqlTypeMetadataExists() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.OBJECT)
                .values(List.of("value", java.sql.Types.OTHER)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setObject(1, "value");
    }

    @Test
    void shouldKeepLegacyObjectBindingWhenTargetSqlTypeAndScaleMetadataExist() throws SQLException {
        BigDecimal value = new BigDecimal("12.34");
        Parameter param = Parameter.builder().index(1).type(ParameterType.OBJECT)
                .values(List.of(value, java.sql.Types.DECIMAL, 2)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setObject(1, value);
    }

    @Test
    void shouldRebuildRelayedPgObjectBeforeBinding() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.OBJECT)
                .values(List.of("{\"name\":\"ojp\"}", CommonConstants.OJP_RELAYED_PGOBJECT_MARKER, "jsonb"))
                .build();

        ParameterHandler.addParam(sessionManager, session, 1, ps, param);

        verify(ps).setObject(eq(1), argThat(value ->
                value instanceof PGobject
                        && hasExpectedPgObjectState(value, "jsonb", "{\"name\":\"ojp\"}")));
    }

    @Test
    void shouldBindNullParameterDirectly() throws SQLException {
        Parameter param = Parameter.builder().index(1).type(ParameterType.NULL)
                .values(List.of(java.sql.Types.VARCHAR)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setNull(1, java.sql.Types.VARCHAR);
    }

    @Test
    void shouldBindBytesParameterDirectly() throws SQLException {
        byte[] value = new byte[]{1, 2, 3};
        Parameter param = Parameter.builder().index(1).type(ParameterType.BYTES).values(List.of(value)).build();
        ParameterHandler.addParam(sessionManager, session, 1, ps, param);
        verify(ps).setBytes(1, value);
    }

    // -------------------------------------------------------------------------
    // addParametersPreparedStatement — multiple parameters in order
    // -------------------------------------------------------------------------

    @Test
    void shouldBindMultipleParametersInOrder() throws SQLException {
        Parameter p1 = Parameter.builder().index(1).type(ParameterType.INT).values(List.of(10)).build();
        Parameter p2 = Parameter.builder().index(2).type(ParameterType.STRING).values(List.of("world")).build();
        Parameter p3 = Parameter.builder().index(3).type(ParameterType.BOOLEAN).values(List.of(false)).build();

        ParameterHandler.addParametersPreparedStatement(sessionManager, session, ps, List.of(p1, p2, p3));

        verify(ps).setInt(1, 10);
        verify(ps).setString(2, "world");
        verify(ps).setBoolean(3, false);
    }

    // -------------------------------------------------------------------------
    // SQLException propagation
    // -------------------------------------------------------------------------

    @Test
    void shouldPropagateSqlExceptionFromBinding() throws SQLException {
        org.mockito.Mockito.doThrow(new SQLException("bind error")).when(ps).setInt(1, 99);
        Parameter param = Parameter.builder().index(1).type(ParameterType.INT).values(List.of(99)).build();

        assertThrows(SQLException.class, () ->
                ParameterHandler.addParam(sessionManager, session, 1, ps, param));
    }

    private static boolean hasExpectedPgObjectState(Object value, String expectedType, String expectedPayload) {
        try {
            String actualType = (String) value.getClass().getMethod("getType").invoke(value);
            String actualPayload = (String) value.getClass().getMethod("getValue").invoke(value);
            return expectedType.equals(actualType) && expectedPayload.equals(actualPayload);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
