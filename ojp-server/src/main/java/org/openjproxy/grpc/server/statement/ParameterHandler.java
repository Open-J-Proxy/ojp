package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;
import org.openjproxy.grpc.server.SessionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;



/**
 * Handles parameter setting for prepared statements.
 * Extracted from StatementServiceImpl to improve modularity.
 *
 * <p>Normal primitive and string parameters are bound directly on the calling thread
 * to avoid per-parameter thread-scheduling overhead on the hot path.</p>
 *
 * <p>Risky I/O types (BLOB, CLOB, streams, readers, SQLXML, OBJECT, ARRAY) still use a
 * timeout-protected Future to guard against JDBC driver hangs during large-object transfers.
 * The timeout executor is intentionally not shut down as it is a shared, application-lifetime
 * resource; its daemon threads will be terminated on JVM shutdown.</p>
 */
@Slf4j
@SuppressWarnings("java:S2142") // InterruptedException handling is appropriate for timeout scenarios
public class ParameterHandler {

    // Timeout for risky parameter setting operations (5 seconds)
    private static final int PARAMETER_TIMEOUT_SECONDS = 5;

    // SQL error code for timeout errors
    private static final int SQL_ERROR_CODE_TIMEOUT = 1234;

    // Thread counter for unique thread names
    private static final AtomicLong THREAD_COUNTER = new AtomicLong(0);

    // Executor used only for risky I/O parameter types (BLOB, CLOB, streams, etc.)
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setName("ParameterSetter-Timeout-" + THREAD_COUNTER.incrementAndGet());
        return thread;
    });

    /**
     * Adds parameters to a prepared statement.
     *
     * @param sessionManager The session manager for LOB retrieval
     * @param session        The current session
     * @param ps             The prepared statement
     * @param params         The parameters to add
     * @throws SQLException if parameter setting fails
     */
    public static void addParametersPreparedStatement(SessionManager sessionManager, SessionInfo session,
                                                     PreparedStatement ps, List<Parameter> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Parameter parameter = params.get(i);
            addParam(sessionManager, session, parameter.getIndex(), ps, parameter);
        }
    }

    /**
     * Adds a single parameter to a prepared statement.
     *
     * <p>Safe scalar types (primitives, strings, dates, NULL, etc.) are bound directly on the
     * calling thread to avoid per-parameter thread-scheduling overhead on the hot path.
     * Risky I/O types (BLOB, CLOB, streams, readers, SQLXML, OBJECT, ARRAY) are still wrapped
     * in a timeout-protected {@link Future} to guard against JDBC driver hangs.</p>
     *
     * @param sessionManager The session manager
     * @param session        The current session
     * @param idx            The parameter index
     * @param ps             The prepared statement
     * @param param          The parameter to add
     * @throws SQLException if parameter setting fails
     */
    public static void addParam(SessionManager sessionManager, SessionInfo session, int idx,
                               PreparedStatement ps, Parameter param) throws SQLException {
        log.info("Adding parameter idx {} type {}", idx, param.getType().toString());

        if (isRiskyParameterType(param.getType())) {
            setParameterWithTimeout(sessionManager, session, idx, ps, param);
        } else {
            setParameterInternal(sessionManager, session, idx, ps, param);
        }
    }

    /**
     * Returns {@code true} for parameter types that may perform blocking I/O or involve
     * complex JDBC driver internals (BLOB, CLOB, streams, readers, SQLXML, OBJECT, ARRAY).
     * These types still run inside a timeout-protected {@link Future}.
     *
     * <p>Package-private to allow direct testing without exposing as public API.</p>
     */
    static boolean isRiskyParameterType(ParameterType type) {
        switch (type) {
            case BLOB:
            case CLOB:
            case BINARY_STREAM:
            case ASCII_STREAM:
            case UNICODE_STREAM:
            case CHARACTER_READER:
            case N_CHARACTER_STREAM:
            case N_CLOB:
            case SQL_XML:
            case OBJECT:
            case ARRAY:
            case REF:
                return true;
            default:
                return false;
        }
    }

    /**
     * Wraps {@link #setParameterInternal} in a timeout-protected {@link Future}.
     * Used only for risky I/O parameter types.
     */
    private static void setParameterWithTimeout(SessionManager sessionManager, SessionInfo session, int idx,
                                                PreparedStatement ps, Parameter param) throws SQLException {
        Future<Void> future = TIMEOUT_EXECUTOR.submit(() -> {
            setParameterInternal(sessionManager, session, idx, ps, param);
            return null;
        });

        try {
            future.get(PARAMETER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            String errorMsg = String.format(
                "OJP timeout (%d seconds) while trying to set parameter at index %d with type %s and value %s. " +
                "This might be caused because the JDBC driver does not support this parameter type. " +
                "Consider using a different data type that is natively supported by your database.",
                PARAMETER_TIMEOUT_SECONDS, idx, param.getType(),
                param.getValues().isEmpty() ? "null" : param.getValues().get(0));
            log.error(errorMsg);
            throw new SQLException(errorMsg, "HY000", SQL_ERROR_CODE_TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            } else {
                throw new SQLException("Error setting parameter: " + cause.getMessage(), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Parameter setting was interrupted", e);
        }
    }

    /**
     * Internal method that actually sets the parameter without timeout.
     * This is called by addParam within a timeout wrapper.
     */
    private static void setParameterInternal(SessionManager sessionManager, SessionInfo session, int idx,
                                            PreparedStatement ps, Parameter param) throws SQLException {
        switch (param.getType()) {
            case INT:
                ps.setInt(idx, (int) param.getValues().get(0));
                break;
            case SHORT:
                ps.setShort(idx, ((Integer) param.getValues().get(0)).shortValue());
                break;
            case DOUBLE:
                ps.setDouble(idx, (double) param.getValues().get(0));
                break;
            case STRING:
                ps.setString(idx, (String) param.getValues().get(0));
                break;
            case LONG:
                ps.setLong(idx, (long) param.getValues().get(0));
                break;
            case BOOLEAN:
                ps.setBoolean(idx, (boolean) param.getValues().get(0));
                break;
            case BIG_DECIMAL:
                ps.setBigDecimal(idx, (BigDecimal) param.getValues().get(0));
                break;
            case FLOAT:
                ps.setFloat(idx, (float) param.getValues().get(0));
                break;
            case BYTES:
                ps.setBytes(idx, (byte[]) param.getValues().get(0));
                break;
            case BYTE:
                setByteParameter(ps, idx, param);
                break;
            case DATE:
                ps.setDate(idx, (Date) param.getValues().get(0));
                break;
            case TIME:
                ps.setTime(idx, (Time) param.getValues().get(0));
                break;
            case TIMESTAMP:
                setTimestampParameter(ps, idx, param.getValues().get(0));
                break;
            case BLOB:
                setBlobParameter(sessionManager, session, ps, idx, param);
                break;
            case CLOB:
                setClobParameter(sessionManager, session, ps, idx, param);
                break;
            case BINARY_STREAM:
                setBinaryStreamParameter(ps, idx, param);
                break;
            case NULL:
                ps.setNull(idx, (int) param.getValues().get(0));
                break;
            case URL:
                setUrlParameter(ps, idx, param);
                break;
            case ROW_ID:
                setRowIdParameter(ps, idx, param);
                break;
            case ARRAY:
                setArrayParameter(sessionManager, session, ps, idx, param);
                break;
            default:
                ps.setObject(idx, param.getValues().get(0));
                break;
        }
    }

    /**
     * Handles BYTE parameter setting with proper type conversion.
     */
    private static void setByteParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object value = param.getValues().get(0);
        if (value instanceof byte[]) {
            byte[] byteArray = (byte[]) value;
            ps.setByte(idx, byteArray.length > 0 ? byteArray[0] : (byte) 0);
        } else {
            ps.setByte(idx, ((Integer) value).byteValue());
        }
    }

    /**
     * Handles TIMESTAMP parameter setting. {@code TemporalConverter.fromTimestampWithZoneToObject}
     * reconstructs the original Java temporal type the client bound (a plain {@link Timestamp}
     * covers most cases, but {@link java.time.OffsetDateTime}, {@link java.time.LocalDateTime},
     * {@link Instant}, {@link java.time.OffsetTime} or {@link Calendar} are all possible,
     * depending on the {@code TemporalType} hint the client sent) — a single unconditional
     * {@code ps.setTimestamp(idx, (Timestamp) value)} cast would throw a {@link ClassCastException}
     * for every one of those non-{@code Timestamp} variants.
     */
    private static void setTimestampParameter(PreparedStatement ps, int idx, Object value) throws SQLException {
        if (value instanceof Timestamp) {
            ps.setTimestamp(idx, (Timestamp) value);
        } else if (value instanceof Instant) {
            ps.setTimestamp(idx, Timestamp.from((Instant) value));
        } else if (value instanceof Calendar) {
            Calendar calendar = (Calendar) value;
            ps.setTimestamp(idx, new Timestamp(calendar.getTimeInMillis()), calendar);
        } else {
            // java.time.OffsetDateTime / LocalDateTime / OffsetTime: bind via setObject(),
            // relying on the JDBC 4.2 standard object mapping the driver provides for these types.
            ps.setObject(idx, value);
        }
    }

    /**
     * Handles BLOB parameter setting with session management.
     */
    private static void setBlobParameter(SessionManager sessionManager, SessionInfo session,
                                        PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object blobUUID = param.getValues().get(0);
        if (blobUUID == null) {
            ps.setBlob(idx, (Blob) null);
        } else {
            ps.setBlob(idx, sessionManager.<Blob>getLob(session, (String) blobUUID));
        }
    }

    /**
     * Handles CLOB parameter setting with session management.
     */
    private static void setClobParameter(SessionManager sessionManager, SessionInfo session,
                                        PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object clobUUID = param.getValues().get(0);
        if (clobUUID == null) {
            ps.setClob(idx, (Clob) null);
        } else {
            Clob clob = sessionManager.getLob(session, (String) clobUUID);
            ps.setClob(idx, clob.getCharacterStream());
        }
    }

    /**
     * Handles BINARY_STREAM parameter setting with appropriate stream handling.
     */
    private static void setBinaryStreamParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object inputStreamValue = param.getValues().get(0);
        if (inputStreamValue == null) {
            ps.setBinaryStream(idx, null);
        } else if (inputStreamValue instanceof byte[]) {
            // DB2 requires the full binary stream to be sent at once
            ps.setBinaryStream(idx, new ByteArrayInputStream((byte[]) inputStreamValue));
        } else {
            InputStream is = (InputStream) inputStreamValue;
            if (param.getValues().size() > 1) {
                Long size = (Long) param.getValues().get(1);
                ps.setBinaryStream(idx, is, size);
            } else {
                ps.setBinaryStream(idx, is);
            }
        }
    }

    /**
     * Handles URL parameter setting with null safety.
     */
    private static void setUrlParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object urlValue = param.getValues().get(0);
        if (urlValue == null) {
            ps.setURL(idx, null);
        } else {
            ps.setURL(idx, (URL) urlValue);
        }
    }

    /**
     * Handles ROW_ID parameter setting as byte array.
     * RowId is transmitted as opaque byte array - cannot reconstruct java.sql.RowId from bytes.
     */
    private static void setRowIdParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object rowIdBytes = param.getValues().get(0);
        if (rowIdBytes == null) {
            ps.setBytes(idx, null);
        } else {
            ps.setBytes(idx, (byte[]) rowIdBytes);
        }
    }

    /**
     * Handles ARRAY parameters stored as server-side session attributes.
     */
    private static void setArrayParameter(SessionManager sessionManager, SessionInfo session, PreparedStatement ps, int idx,
                                          Parameter param) throws SQLException {
        Object arrayReference = param.getValues().get(0);
        if (arrayReference == null) {
            ps.setArray(idx, null);
        } else {
            ps.setArray(idx, (Array) sessionManager.getAttr(session, (String) arrayReference));
        }
    }
}
