package org.openjproxy.jdbc;

import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.OpResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.jdbc.sqlserver.HydratedBlob;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;

@Slf4j
public class ResultSet extends RemoteProxyResultSet {

    @Getter
    private final Map<String, Integer> labelsMap;

    private Iterator<OpResult> itResults;//Iterator of blocks of data
    private List<Object[]> currentDataBlock;//Current block of data being processed.
    private AtomicInteger blockIdx = new AtomicInteger(-1);//Current block index
    private AtomicInteger blockCount = new AtomicInteger(1);//Current block count
    private int completedBlocksRowCount; // running total of rows in all completed blocks
    private java.sql.ResultSetMetaData resultSetMetadata;
    private boolean inProxyMode;
    private boolean closed;
    private AtomicInteger currentIdx = new AtomicInteger(0);
    private boolean inRowByRowMode;

    private Object lastValueRead;

    // Local cache for setFetchDirection()/getFetchDirection() when not in proxy mode.
    // FETCH_FORWARD is the only direction compatible with this ResultSet's forward-only,
    // block-streaming model (see getType()), so it must not force proxy mode: doing so would
    // turn every subsequent next()/getXxx() call into an individual round trip to the server,
    // even though FETCH_FORWARD is the default hint set by virtually every JDBC client/tool.
    private int fetchDirectionHint = java.sql.ResultSet.FETCH_FORWARD;

    // Local cache for setFetchSize()/getFetchSize() when not in proxy mode. Per the JDBC spec,
    // fetch size is only a hint that a driver is free to ignore, so it must not throw for a
    // well-formed value even though this streaming block-based ResultSet does not actually vary
    // its block size based on it.
    private int fetchSizeHint;

    public ResultSet(Iterator<OpResult> itOpResult, StatementService statementService, java.sql.Statement statement) throws SQLException {
        this.itResults = itOpResult;
        this.inProxyMode = false;
        this.closed = false;
        try {
            this.statement = statement;
            OpResult result = nextWithSessionUpdate(itOpResult.next());
            OpQueryResult opQueryResult = ProtoConverter.fromProto(result.getQueryResult());
            this.inRowByRowMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(result.getFlag());
            this.setStatementService(statementService);
            this.setResultSetUUID(opQueryResult.getResultSetUUID());
            this.currentDataBlock = opQueryResult.getRows();
            this.labelsMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);//During tests CockroachDB returned column names capital, this is so that the search for a column to be case insensitive.
            List<String> labels = opQueryResult.getLabels();
            for (int i = 0; i < labels.size(); i++) {
                labelsMap.put(labels.get(i).toUpperCase(), i);
            }
        } catch (StatusRuntimeException e) {
            throw handle(onServerOverload(e));
        }
    }

    @Override
    public boolean next() throws SQLException {
        log.debug("next called");
        if (this.inProxyMode) {
            return super.next();
        }
        this.currentIdx.incrementAndGet();
        blockIdx.incrementAndGet();
        if (this.inRowByRowMode && blockIdx.get() > 0) {
            try {
                // Row by row mode is used in SQL Server and DB2 only when working with LOBs as per moving the cursor earlier
                // would invalidate the LOB object(s) and therefore for SQL Server and DB2 the read is only done when asked
                // by the client.
                OpResult result = this.nextWithSessionUpdate(
                        this.getStatementService().fetchNextRows(((Connection) this.statement.getConnection()).getSession(),
                        this.getResultSetUUID(), 1));
                this.setNextOpResult(result);
            } catch (StatusRuntimeException e) {
                throw handle(onServerOverload(e));
            }
        }
        if (!this.inRowByRowMode && blockIdx.get() >= currentDataBlock.size() && itResults.hasNext()) {
            try {
                this.setNextOpResult(this.nextWithSessionUpdate(itResults.next()));
            } catch (StatusRuntimeException e) {
                throw handle(onServerOverload(e));
            }
        }
        return blockIdx.get() < currentDataBlock.size();
    }

    private StatusRuntimeException onServerOverload(StatusRuntimeException statusRuntimeException) {
        if (statusRuntimeException.getStatus().getCode() != Status.Code.RESOURCE_EXHAUSTED) {
            return statusRuntimeException;
        }

        try {
            if (this.statement == null) {
                return statusRuntimeException;
            }
            java.sql.Connection sqlConnection = this.statement.getConnection();
            if (!(sqlConnection instanceof Connection)) {
                return statusRuntimeException;
            }
            Connection connection = (Connection) sqlConnection;
            ClientThrottleMode mode = connection.getThrottleMode();
            if (mode == ClientThrottleMode.OFF) {
                return statusRuntimeException;
            }

            ClientThrottleManager throttle = connection.getThrottleManager();
            if (throttle != null) {
                io.grpc.Metadata trailers = statusRuntimeException.getTrailers();
                ClientThrottleManager.OverloadLane lane = ClientThrottleManager.OverloadLane.UNKNOWN;
                if (trailers != null) {
                    io.grpc.Metadata.Key<String> key = io.grpc.Metadata.Key.of(ClientThrottleManager.OVERLOAD_LANE_HEADER,
                            io.grpc.Metadata.ASCII_STRING_MARSHALLER);
                    lane = ClientThrottleManager.OverloadLane.parse(trailers.get(key));
                }
                throttle.notifyServerOverload(lane);
            }
        } catch (SQLException sqlException) {
            log.debug("Unable to apply client overload backoff from ResultSet path", sqlException);
        }
        return statusRuntimeException;
    }

    private void setNextOpResult(OpResult result) {
        OpQueryResult opQueryResult = ProtoConverter.fromProto(result.getQueryResult());
        // Accumulate the row count of the outgoing block before replacing it,
        // so that getRow() can compute correct absolute row numbers for any block size.
        this.completedBlocksRowCount += this.currentDataBlock.size();
        this.currentDataBlock = opQueryResult.getRows();
        this.blockCount.incrementAndGet();
        this.blockIdx.set(0);
    }

    private OpResult nextWithSessionUpdate(OpResult next) throws SQLException {
        log.debug("nextWithSessionUpdate called");
        ((Connection) this.statement.getConnection()).setSession(next.getSession());
        return next;
    }

    @Override
    public void close() throws SQLException {
        log.debug("close called");
        this.closed = true;
        this.blockIdx = null;
        this.itResults = null;
        this.currentDataBlock = null;
        //If the parent statement is closed the result set is closed already, attempting to close it again would produce an error.
        if (this.statement == null || !this.statement.isClosed()) {
            super.close();
        }
    }

    @Override
    public boolean wasNull() throws SQLException {
        log.debug("wasNull called");
        if (this.inProxyMode) {
            return super.wasNull();
        }
        return lastValueRead == null;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        log.debug("getString: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getString(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        if (String.valueOf(lastValueRead).startsWith(CommonConstants.OJP_CLOB_PREFIX)) {
            Clob clob = this.getClob(columnIndex);
            if (clob.length() > Integer.MAX_VALUE) {
                throw new SQLException("Attempt to read large CLOB (>2MB) via getString not allowed due to memory overflow danger.");
            }
            return clob.getSubString(1, (int) clob.length());
        }

        return lastValueRead.toString();
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        log.debug("getBoolean: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getBoolean(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return false;
        }
        if (lastValueRead instanceof Boolean) {
            return (Boolean) lastValueRead;
        }
        if (lastValueRead instanceof Number) {
            return ((Number) lastValueRead).intValue() != 0;
        }
        if (lastValueRead instanceof String) {
            String s = (String) lastValueRead;
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return (boolean) lastValueRead;
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        log.debug("getByte: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getByte(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0;
        } else if (lastValueRead instanceof byte[]) {
            return ((byte[]) lastValueRead)[0];
        } else if (lastValueRead instanceof Boolean) {
            return (boolean) lastValueRead ? (byte) 1 : (byte) 0;
        } else if (lastValueRead instanceof Short) {
            return (byte)(short) lastValueRead;
        } else if (lastValueRead instanceof Integer) {
            return (byte)(int) lastValueRead;
        }
        return (byte) lastValueRead;
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        log.debug("getShort: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getShort(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0;
        } else if (lastValueRead instanceof Boolean) {
            return (boolean) lastValueRead ? (short) 1 : (short) 0;
        } else if (lastValueRead instanceof Integer) {
            return (short)(int) lastValueRead;
        }
        return (short) lastValueRead;
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        log.debug("getInt: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getInt(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0;
        }
        Object value = lastValueRead;
        if (value instanceof Integer) {
            return (int) value;
        } else if (value instanceof Long) {
            Long lValue = (Long) value;
            return lValue.intValue();
        } else if (value instanceof Short) {
            Short sValue = (Short) value;
            return sValue.intValue();
        } else if (value instanceof Boolean) {
            return (boolean) value ? 1 : 0;
        } else if (value instanceof Date) {
            Date dValue = (Date) value;
            LocalDate ld = LocalDate.ofEpochDay(dValue.getTime());
            if (ld.getDayOfMonth() > 0) {
                return ld.getDayOfMonth();
            } else if (ld.getMonth().getValue() > 0) {
                return ld.getMonth().getValue();
            } else if (ld.getYear() > 0) {
                return ld.getYear();
            }
            return (int) dValue.getTime();
        } else {
            return Integer.parseInt("" + value);
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        log.debug("getLong: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getLong(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0;
        }
        if (lastValueRead instanceof BigInteger) {
            return ((BigInteger) lastValueRead).longValue();
        }
        if (lastValueRead instanceof Boolean) {
            return (boolean) lastValueRead ? 1L : 0L;
        }
        if (lastValueRead instanceof Integer) {
            return ((Integer) lastValueRead).longValue();
        }
        if (lastValueRead instanceof BigDecimal) {
            return ((BigDecimal) lastValueRead).longValue();
        }
        return (long) lastValueRead;
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        log.debug("getFloat: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getFloat(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0;
        }
        Object value = lastValueRead;
        if (value instanceof BigDecimal) {
            BigDecimal bdValue = (BigDecimal) value;
            return bdValue.floatValue();
        }
        if (value instanceof Boolean) {
            return (boolean) value ? 1.0f : 0.0f;
        }
        return (float) value;
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        log.debug("getDouble: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getDouble(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return 0d;
        }
        Object value = lastValueRead;
        if (value instanceof BigDecimal) {
            BigDecimal bdValue = (BigDecimal) value;
            return bdValue.doubleValue();
        }
        if (value instanceof Boolean) {
            return (boolean) value ? 1.0d : 0.0d;
        }
        return (double) value;
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        log.debug("getBigDecimal: {}, {}", columnIndex, scale);
        if (this.inProxyMode) {
            return super.getBigDecimal(columnIndex, scale);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        return (BigDecimal) lastValueRead;
    }

    @SneakyThrows
    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        log.debug("getBytes: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getBytes(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead instanceof String) { // Means the server is treating it as a binary stream
            InputStream is = this.getBinaryStream(columnIndex);
            return is.readAllBytes();
        }
        return (byte[]) lastValueRead;
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        log.debug("getDate: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getDate(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        Object result = lastValueRead;
        if (result instanceof Timestamp) {
            Timestamp timestamp = (Timestamp) result;
            return new Date(timestamp.getTime());
        }
        return (Date) result;
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        log.debug("getTime: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getTime(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        return (Time) lastValueRead;
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        log.debug("getTimestamp: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getTimestamp(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        if (lastValueRead instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) lastValueRead);
        }
        return (Timestamp) lastValueRead;
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        log.debug("getAsciiStream: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getAsciiStream(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        log.debug("getUnicodeStream: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getUnicodeStream(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        log.debug("getBinaryStream: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getBinaryStream(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        } else if (lastValueRead instanceof byte[]) { // Only used by SQL server
            return new ByteArrayInputStream((byte[]) lastValueRead);
        }
        Object objUUID = lastValueRead;
        String lobRefUUID = String.valueOf(objUUID);
        LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                .setSession(this.getConnection().getSession())
                .setLobType(LobType.LT_BINARY_STREAM)
                .setUuid(lobRefUUID)
                .setColumnIndex(columnIndex);
        if (this.statement != null) {
            if (this.statement instanceof Statement) {
                Statement stmt = (Statement) this.statement;
                if (StringUtils.isNotBlank(stmt.getStatementUUID())) {
                    lobRefBuilder.setStmtUUID(stmt.getStatementUUID());
                }
            }
        }
        BinaryStream binaryStream = new BinaryStream((Connection) this.statement.getConnection(),
                new LobServiceImpl((Connection) this.statement.getConnection(), this.getStatementService()),
                this.getStatementService(), lobRefBuilder.build());
        return binaryStream.getBinaryStream();
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        log.debug("getString: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getString(columnLabel);
        }
        return this.getString(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        log.debug("getBoolean: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getBoolean(columnLabel);
        }
        return this.getBoolean(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        log.debug("getByte: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getByte(columnLabel);
        }
        return this.getByte(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        log.debug("getShort: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getShort(columnLabel);
        }
        return this.getShort(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        log.debug("getInt: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getInt(columnLabel);
        }
        return this.getInt(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        log.debug("getLong: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getLong(columnLabel);
        }
        return this.getLong(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        log.debug("getFloat: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getFloat(columnLabel);
        }
        return this.getFloat(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        log.debug("getDouble: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getDouble(columnLabel);
        }
        return this.getDouble(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        log.debug("getBigDecimal: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getBigDecimal(columnLabel);
        }
        return this.getBigDecimal(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        log.debug("getBytes: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getBytes(columnLabel);
        }
        return this.getBytes(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        log.debug("getDate: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getDate(columnLabel);
        }
        return this.getDate(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        log.debug("getTime: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getTime(columnLabel);
        }
        return this.getTime(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        log.debug("getTimestamp: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getTimestamp(columnLabel);
        }
        return this.getTimestamp(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        log.debug("getObject: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getObject(columnLabel);
        }
        return this.getObject(this.labelsMap.get(columnLabel.toUpperCase()) + 1);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        log.debug("getBigDecimal: {}, {}", columnLabel, scale);
        if (this.inProxyMode) {
            return super.getBigDecimal(columnLabel, scale);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[this.labelsMap.get(columnLabel.toUpperCase())];
        if (lastValueRead == null) {
            return null;
        }
        return (BigDecimal) lastValueRead;
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        log.debug("getAsciiStream: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getAsciiStream(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        log.debug("getUnicodeStream: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getUnicodeStream(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        log.debug("getBinaryStream: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getBinaryStream(columnLabel);
        }
        int colIdx = this.labelsMap.get(columnLabel.toUpperCase()) + 1;
        lastValueRead = currentDataBlock.get(blockIdx.get())[colIdx - 1];
        if (lastValueRead == null) {
            return null;
        }
        return this.getBinaryStream(colIdx);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.debug("getWarnings called");
        return super.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
        if (this.inProxyMode) {
            super.clearWarnings();
        }
    }

    @Override
    public String getCursorName() throws SQLException {
        log.debug("getCursorName called");
        if (this.inProxyMode) {
            return super.getCursorName();
        }
        return "";
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        log.debug("getMetaData called");
        if (this.inProxyMode) {
            return super.getMetaData();
        }
        if (this.getResultSetUUID() == null) {
            throw new SQLException("No result set reference found.");
        }
        if (this.resultSetMetadata == null) {
            this.resultSetMetadata = new ResultSetMetaData(this, this.getStatementService());
        }
        return this.resultSetMetadata;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        log.debug("getObject: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getObject(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        return lastValueRead;
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        log.debug("findColumn: {}", columnLabel);
        if (this.inProxyMode) {
            return super.findColumn(columnLabel);
        }
        return this.labelsMap.get(columnLabel) + 1;
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        log.debug("getCharacterStream: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getCharacterStream(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        log.debug("getCharacterStream: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getCharacterStream(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        log.debug("getBigDecimal: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getBigDecimal(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        return (BigDecimal) lastValueRead;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        log.debug("isBeforeFirst called");
        if (this.inProxyMode) {
            return super.isBeforeFirst();
        }
        return blockIdx.get() == -1;
    }

    @Override
    public boolean first() throws SQLException {
        log.debug("first called");
        this.inProxyMode = true;
        return super.first();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        log.debug("isAfterLast called");
        if (this.inProxyMode) {
            return super.isAfterLast();
        }
        return !itResults.hasNext() && blockIdx.get() >= currentDataBlock.size();
    }

    @Override
    public boolean isFirst() throws SQLException {
        log.debug("isFirst called");
        if (this.inProxyMode) {
            return super.isFirst();
        }
        return this.blockCount.get() == 1 && this.blockIdx.get() == 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        log.debug("isLast called");
        if (this.inProxyMode) {
            return super.isLast();
        }
        return !itResults.hasNext() && blockIdx.get() == (currentDataBlock.size() - 1);
    }

    @Override
    public void beforeFirst() throws SQLException {
        log.debug("beforeFirst called");
        this.inProxyMode = true;
        super.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        log.debug("afterLast called");
        this.inProxyMode = true;
        super.afterLast();
    }

    @Override
    public boolean last() throws SQLException {
        log.debug("last called");
        this.inProxyMode = true;
        return super.last();
    }

    @Override
    public int getRow() throws SQLException {
        log.debug("getRow called");
        if (this.inProxyMode) {
            return super.getRow();
        }
        return this.completedBlocksRowCount + this.blockIdx.get() + 1;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        log.debug("absolute: {}", row);
        this.inProxyMode = true;
        return super.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        log.debug("relative: {}", rows);
        this.inProxyMode = true;
        return super.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        log.debug("previous called");
        if (this.inProxyMode) {
            return super.previous();
        }
        this.inProxyMode = true;
        return super.absolute(this.currentIdx.get() - 1);// Will reposition the cursor in the current row being processed.
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        log.debug("setFetchDirection: {}", direction);
        if (this.inProxyMode) {
            super.setFetchDirection(direction);
            return;
        }
        if (direction != java.sql.ResultSet.FETCH_FORWARD) {
            // This ResultSet only implements forward, block-streaming iteration; any other
            // direction is delegated to the remote proxy so JDBC scrollable semantics stay correct.
            super.setFetchDirection(direction);
            this.inProxyMode = true;
            return;
        }
        this.fetchDirectionHint = direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        log.debug("getFetchDirection called");
        if (this.inProxyMode) {
            return super.getFetchDirection();
        }
        return this.fetchDirectionHint;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        log.debug("setFetchSize: {}", rows);
        if (rows < 0) {
            throw new SQLException("Fetch size cannot be negative: " + rows);
        }
        if (this.inProxyMode) {
            super.setFetchSize(rows);
            return;
        }
        // Fetch size is only a hint (JDBC spec); this ResultSet streams fixed-size blocks
        // from the server, so the hint is stored but does not change the streaming behavior.
        this.fetchSizeHint = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        log.debug("getFetchSize called");
        if (this.inProxyMode) {
            return super.getFetchSize();
        }
        return this.fetchSizeHint;
    }

    @Override
    public int getType() throws SQLException {
        log.debug("getType called");
        if (this.inProxyMode) {
            return super.getType();
        }
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        log.debug("getConcurrency called");
        if (this.inProxyMode) {
            return super.getConcurrency();
        }
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        log.debug("rowUpdated called");
        if (this.inProxyMode) {
            return super.rowUpdated();
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean rowInserted() throws SQLException {
        log.debug("rowInserted called");
        if (this.inProxyMode) {
            return super.rowInserted();
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        log.debug("rowDeleted called");
        if (this.inProxyMode) {
            return super.rowDeleted();
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        log.debug("updateNull: {}", columnIndex);
        this.inProxyMode = true;
        super.absolute(this.currentIdx.get());
        super.updateNull(columnIndex);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        log.debug("updateBoolean: {}, {}", columnIndex, x);
        this.inProxyMode = true;
        super.absolute(this.currentIdx.get());
        super.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        log.debug("updateByte: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateByte(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        log.debug("updateShort: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateShort(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        log.debug("updateInt: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateInt(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        log.debug("updateLong: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateLong(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        log.debug("updateFloat: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateFloat(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        log.debug("updateDouble: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateDouble(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        log.debug("updateBigDecimal: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateBigDecimal(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        log.debug("updateString: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateString(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        log.debug("updateBytes: {}, <byte[]>", columnIndex);
        if (this.inProxyMode) {
            super.updateBytes(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        log.debug("updateDate: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateDate(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        log.debug("updateTime: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateTime(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        log.debug("updateTimestamp: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateTimestamp(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnIndex, x, scaleOrLength);
        if (this.inProxyMode) {
            super.updateObject(columnIndex, x, scaleOrLength);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        log.debug("updateObject: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateObject(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        log.debug("updateNull: {}", columnLabel);
        if (this.inProxyMode) {
            super.updateNull(columnLabel);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        log.debug("updateBoolean: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateBoolean(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        log.debug("updateByte: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateByte(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        log.debug("updateShort: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateShort(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        log.debug("updateInt: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateInt(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        log.debug("updateLong: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateLong(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        log.debug("updateFloat: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateFloat(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        log.debug("updateDouble: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateDouble(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        log.debug("updateBigDecimal: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateBigDecimal(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        log.debug("updateString: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateString(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        log.debug("updateBytes: {}, <byte[]>", columnLabel);
        if (this.inProxyMode) {
            super.updateBytes(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        log.debug("updateDate: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateDate(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        log.debug("updateTime: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateTime(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        log.debug("updateTimestamp: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateTimestamp(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnLabel, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnLabel, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnLabel, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnLabel, x, scaleOrLength);
        if (this.inProxyMode) {
            super.updateObject(columnLabel, x, scaleOrLength);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        log.debug("updateObject: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateObject(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void insertRow() throws SQLException {
        log.debug("insertRow called");
        if (this.inProxyMode) {
            super.insertRow();
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateRow() throws SQLException {
        log.debug("updateRow called");
        if (this.inProxyMode) {
            super.updateRow();
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void deleteRow() throws SQLException {
        log.debug("deleteRow called");
        if (this.inProxyMode) {
            super.deleteRow();
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void refreshRow() throws SQLException {
        log.debug("refreshRow called");
        if (this.inProxyMode) {
            super.refreshRow();
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        log.debug("cancelRowUpdates called");
        if (this.inProxyMode) {
            super.cancelRowUpdates();
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        log.debug("moveToInsertRow called");
        super.moveToInsertRow();
        this.inProxyMode = true;
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        log.debug("moveToCurrentRow called");
        super.moveToCurrentRow();
        this.inProxyMode = true;
    }

    @Override
    public java.sql.Statement getStatement() throws SQLException {
        log.debug("getStatement called");
        if (this.inProxyMode) {
            return super.getStatement();
        }
        return this.statement;
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        log.debug("getObject: {}, <Map>", columnIndex);
        if (this.inProxyMode) {
            return super.getObject(columnIndex, map);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        log.debug("getRef: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getRef(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        log.debug("getBlob: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getBlob(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        } else if (lastValueRead instanceof byte[]) { //Only for SQL server
            return new HydratedBlob((byte[]) lastValueRead);
        }
        Object objUUID = lastValueRead;
        String blobRefUUID = String.valueOf(objUUID);
        LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                .setSession(((Connection) this.statement.getConnection()).getSession())
                .setUuid(blobRefUUID);
        if (this.statement != null) {
            if (this.statement instanceof Statement) {
                Statement stmt = (Statement) this.statement;
                if (stmt.getStatementUUID() != null) {
                    lobRefBuilder.setStmtUUID(stmt.getStatementUUID());
                }
            }
        }
        return new org.openjproxy.jdbc.Blob((Connection) this.statement.getConnection(),
                new LobServiceImpl((Connection) this.statement.getConnection(), this.getStatementService()),
                this.getStatementService(), lobRefBuilder.build());
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        log.debug("getClob: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getClob(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        String clobRefUUID = (String) lastValueRead;
        if (clobRefUUID != null && clobRefUUID.startsWith(CommonConstants.OJP_CLOB_PREFIX)) {
            clobRefUUID = clobRefUUID.replaceAll(CommonConstants.OJP_CLOB_PREFIX, "");
        }
        return new org.openjproxy.jdbc.Clob((Connection) this.statement.getConnection(),
                new LobServiceImpl((Connection) this.statement.getConnection(), this.getStatementService()),
                this.getStatementService(),
                LobReference.newBuilder()
                        .setSession(((Connection) this.statement.getConnection()).getSession())
                        .setUuid(clobRefUUID)
                        .setLobType(LobType.LT_CLOB)
                        .build()
        );
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        log.debug("getArray: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getArray(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        log.debug("getObject: {}, <Map>", columnLabel);
        if (this.inProxyMode) {
            return super.getObject(columnLabel, map);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        log.debug("getRef: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getRef(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        log.debug("getBlob: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getBlob(columnLabel);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[this.labelsMap.get(columnLabel.toUpperCase())];
        //For databases where LOBs get invalidated once cursor moves (SQL Server and DB2) must eagerly hydrate LOBs.
        if (lastValueRead instanceof byte[]) {
            return new HydratedBlob((byte[]) lastValueRead);
        }
        String blobRefUUID = (String) lastValueRead;
        return new org.openjproxy.jdbc.Blob((Connection) this.statement.getConnection(),
                new LobServiceImpl((Connection) this.statement.getConnection(), this.getStatementService()),
                this.getStatementService(),
                LobReference.newBuilder()
                        .setSession(((Connection) this.statement.getConnection()).getSession())
                        .setUuid(blobRefUUID)
                        .build()
        );
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        log.debug("getClob: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getClob(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        log.debug("getArray: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getArray(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getDate: {}, <Calendar>", columnIndex);
        if (this.inProxyMode) {
            return super.getDate(columnIndex, cal);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        if (lastValueRead instanceof Timestamp) {
            return new Date(((Timestamp) lastValueRead).getTime());
        }
        if (lastValueRead instanceof LocalDate) {
            return Date.valueOf((LocalDate) lastValueRead);
        }
        if (lastValueRead instanceof LocalDateTime) {
            return Date.valueOf(((LocalDateTime) lastValueRead).toLocalDate());
        }
        return (Date) lastValueRead;
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getDate: {}, <Calendar>", columnLabel);
        if (this.inProxyMode) {
            return super.getDate(columnLabel, cal);
        }
        return this.getDate(this.labelsMap.get(columnLabel.toUpperCase()) + 1, cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getTime: {}, <Calendar>", columnIndex);
        if (this.inProxyMode) {
            return super.getTime(columnIndex, cal);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        if (lastValueRead instanceof LocalTime) {
            return Time.valueOf((LocalTime) lastValueRead);
        }
        if (lastValueRead instanceof LocalDateTime) {
            return Time.valueOf(((LocalDateTime) lastValueRead).toLocalTime());
        }
        return (Time) lastValueRead;
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getTime: {}, <Calendar>", columnLabel);
        if (this.inProxyMode) {
            return super.getTime(columnLabel, cal);
        }
        return this.getTime(this.labelsMap.get(columnLabel.toUpperCase()) + 1, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getTimestamp: {}, <Calendar>", columnIndex);
        if (this.inProxyMode) {
            return super.getTimestamp(columnIndex, cal);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        if (lastValueRead instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) lastValueRead);
        }
        return (Timestamp) lastValueRead;
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getTimestamp: {}, <Calendar>", columnLabel);
        if (this.inProxyMode) {
            return super.getTimestamp(columnLabel, cal);
        }
        return this.getTimestamp(this.labelsMap.get(columnLabel.toUpperCase()) + 1, cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        log.debug("getURL: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getURL(columnIndex);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        return (URL) lastValueRead;
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        log.debug("getURL: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getURL(columnLabel);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[this.labelsMap.get(columnLabel.toUpperCase())];
        if (lastValueRead == null) {
            return null;
        }
        return (URL) lastValueRead;
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        log.debug("updateRef: {}, {}", columnIndex, x);
        if (this.inProxyMode) {
            super.updateRef(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        log.debug("updateRef: {}, {}", columnLabel, x);
        if (this.inProxyMode) {
            super.updateRef(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        log.debug("updateBlob: {}, <Blob>", columnIndex);
        if (this.inProxyMode) {
            super.updateBlob(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        log.debug("updateBlob: {}, <Blob>", columnLabel);
        if (this.inProxyMode) {
            super.updateBlob(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        log.debug("updateClob: {}, <Clob>", columnIndex);
        if (this.inProxyMode) {
            super.updateClob(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        log.debug("updateClob: {}, <Clob>", columnLabel);
        if (this.inProxyMode) {
            super.updateClob(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        log.debug("updateArray: {}, <Array>", columnIndex);
        if (this.inProxyMode) {
            super.updateArray(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        log.debug("updateArray: {}, <Array>", columnLabel);
        if (this.inProxyMode) {
            super.updateArray(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        log.debug("getRowId: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getRowId(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        log.debug("getRowId: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getRowId(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        log.debug("updateRowId: {}, <RowId>", columnIndex);
        if (this.inProxyMode) {
            super.updateRowId(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        log.debug("updateRowId: {}, <RowId>", columnLabel);
        if (this.inProxyMode) {
            super.updateRowId(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int getHoldability() throws SQLException {
        log.debug("getHoldability called");
        if (this.inProxyMode) {
            return super.getHoldability();
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.debug("isClosed called");
        if (this.inProxyMode) {
            return super.isClosed();
        }
        return this.closed;
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        log.debug("updateNString: {}, {}", columnIndex, nString);
        if (this.inProxyMode) {
            super.updateNString(columnIndex, nString);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        log.debug("updateNString: {}, {}", columnLabel, nString);
        if (this.inProxyMode) {
            super.updateNString(columnLabel, nString);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        log.debug("updateNClob: {}, <NClob>", columnIndex);
        if (this.inProxyMode) {
            super.updateNClob(columnIndex, nClob);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        log.debug("updateNClob: {}, <NClob>", columnLabel);
        if (this.inProxyMode) {
            super.updateNClob(columnLabel, nClob);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        log.debug("getNClob: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getNClob(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        log.debug("getNClob: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getNClob(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        log.debug("getSQLXML: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getSQLXML(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        log.debug("getSQLXML: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getSQLXML(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        log.debug("updateSQLXML: {}, <SQLXML>", columnIndex);
        if (this.inProxyMode) {
            super.updateSQLXML(columnIndex, xmlObject);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        log.debug("updateSQLXML: {}, <SQLXML>", columnLabel);
        if (this.inProxyMode) {
            super.updateSQLXML(columnLabel, xmlObject);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        log.debug("getNString: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getNString(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        log.debug("getNString: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getNString(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        log.debug("getNCharacterStream: {}", columnIndex);
        if (this.inProxyMode) {
            return super.getNCharacterStream(columnIndex);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        log.debug("getNCharacterStream: {}", columnLabel);
        if (this.inProxyMode) {
            return super.getNCharacterStream(columnLabel);
        }
        lastValueRead = null;
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateNCharacterStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateNCharacterStream(columnLabel, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnIndex, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnLabel, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnLabel, x, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnLabel, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateBlob(columnIndex, inputStream, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateBlob(columnLabel, inputStream, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.debug("updateClob: {}, <Reader>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateClob(columnIndex, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateClob: {}, <Reader>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateClob(columnLabel, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.debug("updateNClob: {}, <Reader>, {}", columnIndex, length);
        if (this.inProxyMode) {
            super.updateNClob(columnIndex, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateNClob: {}, <Reader>, {}", columnLabel, length);
        if (this.inProxyMode) {
            super.updateNClob(columnLabel, reader, length);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>", columnIndex);
        if (this.inProxyMode) {
            super.updateNCharacterStream(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>", columnLabel);
        if (this.inProxyMode) {
            super.updateNCharacterStream(columnLabel, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>", columnIndex);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>", columnIndex);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>", columnIndex);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnIndex, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>", columnLabel);
        if (this.inProxyMode) {
            super.updateAsciiStream(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>", columnLabel);
        if (this.inProxyMode) {
            super.updateBinaryStream(columnLabel, x);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>", columnLabel);
        if (this.inProxyMode) {
            super.updateCharacterStream(columnLabel, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>", columnIndex);
        if (this.inProxyMode) {
            super.updateBlob(columnIndex, inputStream);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>", columnLabel);
        if (this.inProxyMode) {
            super.updateBlob(columnLabel, inputStream);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        log.debug("updateClob: {}, <Reader>", columnIndex);
        //TODO review if we could fall back to proxy when these update methods are called, would have to reposition the curor to the current row being read before doing the update though
        if (this.inProxyMode) {
            super.updateClob(columnIndex, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateClob: {}, <Reader>", columnLabel);
        if (this.inProxyMode) {
            super.updateClob(columnLabel, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        log.debug("updateNClob: {}, <Reader>", columnIndex);
        if (this.inProxyMode) {
            super.updateNClob(columnIndex, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateNClob: {}, <Reader>", columnLabel);
        if (this.inProxyMode) {
            super.updateNClob(columnLabel, reader);
            return;
        }
        throw new RuntimeException("Not implemented");
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        log.debug("getObject: {}, {}", columnIndex, type);
        if (this.inProxyMode) {
            return super.getObject(columnIndex, type);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[columnIndex - 1];
        if (lastValueRead == null) {
            return null;
        }
        return (T) lastValueRead;
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        log.debug("getObject: {}, {}", columnLabel, type);
        if (this.inProxyMode) {
            return super.getObject(columnLabel, type);
        }
        lastValueRead = currentDataBlock.get(blockIdx.get())[this.labelsMap.get(columnLabel.toUpperCase())];
        if (lastValueRead == null) {
            return null;
        }
        return (T) lastValueRead;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        if (this.inProxyMode) {
            return super.isWrapperFor(iface);
        }
        throw new RuntimeException("Not implemented");
    }
}
