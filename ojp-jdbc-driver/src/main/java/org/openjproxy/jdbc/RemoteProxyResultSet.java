package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * ResultSet linked to a remote instance of ResultSet in OJP server, it delegates all calls to server instance.
 */
@Slf4j
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RemoteProxyResultSet implements java.sql.ResultSet {

    private String resultSetUUID;
    private StatementService statementService;
    private Connection connection;
    @Getter
    protected Statement statement;

    public Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        if (this.getStatement() != null && !this.getStatement().isClosed() &&
                this.getStatement().getConnection() != null) {
            return (Connection) this.getStatement().getConnection();
        } else {
            return this.connection;
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        throw new SQLException("unwrap not supported.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        throw new SQLException("unwrap not supported.");
    }

    @Override
    public boolean next() throws SQLException {
        log.debug("next called");
        return this.callProxy(CallType.CALL_NEXT, "", Boolean.class);
    }

    @Override
    public void close() throws SQLException {
        log.debug("close called");
        this.callProxy(CallType.CALL_CLOSE, "", Void.class);
    }

    @Override
    public boolean wasNull() throws SQLException {
        log.debug("wasNull called");
        return this.callProxy(CallType.CALL_WAS, "Null", Boolean.class);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        log.debug("getString: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "String", String.class, List.of(columnIndex));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        log.debug("getBoolean: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Boolean", Boolean.class, List.of(columnIndex));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        log.debug("getByte: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Byte", Byte.class, List.of(columnIndex));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        log.debug("getShort: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Short", Short.class, List.of(columnIndex));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        log.debug("getInt: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Int", Integer.class, List.of(columnIndex));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        log.debug("getLong: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Long", Long.class, List.of(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        log.debug("getFloat: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Float", Float.class, List.of(columnIndex));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        log.debug("getDouble: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Double", Double.class, List.of(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        log.debug("getBigDecimal: {}, {}", columnIndex, scale);
        return this.callProxy(CallType.CALL_GET, "BigDecimal", BigDecimal.class, List.of(columnIndex, scale));
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        log.debug("getBytes: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Bytes", byte[].class, List.of(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        log.debug("getDate: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Date", Date.class, List.of(columnIndex));
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        log.debug("getTime: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Time", Time.class, List.of(columnIndex));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        log.debug("getTimestamp: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Timestamp", Timestamp.class, List.of(columnIndex));
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        log.debug("getAsciiStream: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "AsciiStream", InputStream.class, List.of(columnIndex));
    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        log.debug("getUnicodeStream: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "UnicodeStream", InputStream.class, List.of(columnIndex));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        log.debug("getBinaryStream: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "BinaryStream", InputStream.class, List.of(columnIndex));
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        log.debug("getString: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "String", String.class, List.of(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        log.debug("getBoolean: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Boolean", Boolean.class, List.of(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        log.debug("getByte: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Byte", Byte.class, List.of(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        log.debug("getShort: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Short", Short.class, List.of(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        log.debug("getInt: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Int", Integer.class, List.of(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        log.debug("getLong: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Long", Long.class, List.of(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        log.debug("getFloat: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Float", Float.class, List.of(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        log.debug("getDouble: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Double", Double.class, List.of(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        log.debug("getBigDecimal: {}, {}", columnLabel, scale);
        return this.callProxy(CallType.CALL_GET, "BigDecimal", BigDecimal.class, List.of(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        log.debug("getBytes: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Bytes", byte[].class, List.of(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        log.debug("getDate: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Date", Date.class, List.of(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        log.debug("getTime: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Time", Time.class, List.of(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        log.debug("getTimestamp: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Timestamp", Timestamp.class, List.of(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        log.debug("getAsciiStream: {}", columnLabel);
        return this.retrieveBinaryStream(CallType.CALL_GET, "AsciiStream", LobType.LT_ASCII_STREAM, List.of(columnLabel));
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        log.debug("getUnicodeStream: {}", columnLabel);
        return this.retrieveBinaryStream(CallType.CALL_GET, "AsciiStream", LobType.LT_UNICODE_STREAM, List.of(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        log.debug("getBinaryStream: {}", columnLabel);
        return this.retrieveBinaryStream(CallType.CALL_GET, "BinaryStream", LobType.LT_BINARY_STREAM, List.of(columnLabel));
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.debug("getWarnings called");
        return this.callProxy(CallType.CALL_GET, "Warnings", SQLWarning.class);
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
        this.callProxy(CallType.CALL_CLEAR, "Warnings", Void.class);
    }

    @Override
    public String getCursorName() throws SQLException {
        log.debug("getCursorName called");
        return this.callProxy(CallType.CALL_GET, "CursorName", String.class);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        log.debug("getMetaData called");
        return new org.openjproxy.jdbc.ResultSetMetaData(this, this.statementService);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        log.debug("getObject: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Object", Object.class, List.of(columnIndex));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        log.debug("getObject: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Object", Object.class, List.of(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        log.debug("findColumn: {}", columnLabel);
        return this.callProxy(CallType.CALL_FIND, "Column", Integer.class, List.of(columnLabel));
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        log.debug("getCharacterStream: {}", columnIndex);
        return this.retrieveReader(CallType.CALL_GET, "CharacterStream", LobType.LT_CHARACTER_STREAM, List.of(columnIndex));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        log.debug("getCharacterStream: {}", columnLabel);
        return this.retrieveReader(CallType.CALL_GET, "CharacterStream", LobType.LT_CHARACTER_STREAM, List.of(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        log.debug("getBigDecimal: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "BigDecimal", BigDecimal.class, List.of(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        log.debug("getBigDecimal: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "BigDecimal", BigDecimal.class, List.of(columnLabel));
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        log.debug("isBeforeFirst called");
        return this.callProxy(CallType.CALL_IS, "BeforeFirst", Boolean.class);
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        log.debug("isAfterLast called");
        return this.callProxy(CallType.CALL_IS, "AfterLast", Boolean.class);
    }

    @Override
    public boolean isFirst() throws SQLException {
        log.debug("isFirst called");
        return this.callProxy(CallType.CALL_IS, "First", Boolean.class);
    }

    @Override
    public boolean isLast() throws SQLException {
        log.debug("isLast called");
        return this.callProxy(CallType.CALL_IS, "Last", Boolean.class);
    }

    @Override
    public void beforeFirst() throws SQLException {
        log.debug("beforeFirst called");
        this.callProxy(CallType.CALL_BEFORE, "First", Void.class);
    }

    @Override
    public void afterLast() throws SQLException {
        log.debug("afterLast called");
        this.callProxy(CallType.CALL_AFTER, "Last", Void.class);
    }

    @Override
    public boolean first() throws SQLException {
        log.debug("first called");
        return this.callProxy(CallType.CALL_FIRST, "", Boolean.class);
    }

    @Override
    public boolean last() throws SQLException {
        log.debug("last called");
        return this.callProxy(CallType.CALL_LAST, "", Boolean.class);
    }

    @Override
    public int getRow() throws SQLException {
        log.debug("getRow called");
        return this.callProxy(CallType.CALL_GET, "Row", Integer.class);
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        log.debug("absolute: {}", row);
        return this.callProxy(CallType.CALL_ABSOLUTE, "", Boolean.class, List.of(row));
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        log.debug("relative: {}", rows);
        return this.callProxy(CallType.CALL_RELATIVE, "", Boolean.class, List.of(rows));
    }

    @Override
    public boolean previous() throws SQLException {
        log.debug("previous called");
        return this.callProxy(CallType.CALL_PREVIOUS, "", Boolean.class);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        log.debug("setFetchDirection: {}", direction);
        this.callProxy(CallType.CALL_SET, "FetchDirection", Void.class, List.of(direction));
    }

    @Override
    public int getFetchDirection() throws SQLException {
        log.debug("getFetchDirection called");
        return this.callProxy(CallType.CALL_GET, "FetchDirection", Integer.class);
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        log.debug("setFetchSize: {}", rows);
        this.callProxy(CallType.CALL_SET, "FetchSize", Void.class, List.of(rows));
    }

    @Override
    public int getFetchSize() throws SQLException {
        log.debug("getFetchSize called");
        return this.callProxy(CallType.CALL_GET, "FetchSize", Integer.class);
    }

    @Override
    public int getType() throws SQLException {
        log.debug("getType called");
        return this.callProxy(CallType.CALL_GET, "Type", Integer.class);
    }

    @Override
    public int getConcurrency() throws SQLException {
        log.debug("getConcurrency called");
        return this.callProxy(CallType.CALL_GET, "Concurrency", Integer.class);
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        log.debug("rowUpdated called");
        return this.callProxy(CallType.CALL_ROW, "Updated", Boolean.class);
    }

    @Override
    public boolean rowInserted() throws SQLException {
        log.debug("rowInserted called");
        return this.callProxy(CallType.CALL_ROW, "Inserted", Boolean.class);
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        log.debug("rowDeleted called");
        return this.callProxy(CallType.CALL_ROW, "Deleted", Boolean.class);
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        log.debug("updateNull: {}", columnIndex);
        this.callProxy(CallType.CALL_UPDATE, "Null", Void.class, List.of(columnIndex));
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        log.debug("updateBoolean: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Boolean", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        log.debug("updateByte: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Byte", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        log.debug("updateShort: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Byte", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        log.debug("updateInt: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Int", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        log.debug("updateLong: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Long", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        log.debug("updateFloat: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Float", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        log.debug("updateDouble: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Double", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        log.debug("updateBigDecimal: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "BigDecimal", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        log.debug("updateString: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "String", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        log.debug("updateBytes: {}, <byte[]>", columnIndex);
        this.callProxy(CallType.CALL_UPDATE, "Bytes", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        log.debug("updateDate: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Date", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        log.debug("updateTime: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Time", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        log.debug("updateTimestamp: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Timestamp", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnIndex, x, scaleOrLength);
        this.callProxy(CallType.CALL_UPDATE, "Object", Void.class, List.of(columnIndex, x, scaleOrLength));
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        log.debug("updateObject: {}, {}", columnIndex, x);
        this.callProxy(CallType.CALL_UPDATE, "Object", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        log.debug("updateNull: {}", columnLabel);
        this.callProxy(CallType.CALL_UPDATE, "Null", Void.class, List.of(columnLabel));
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        log.debug("updateBoolean: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Boolean", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        log.debug("updateByte: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Byte", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        log.debug("updateShort: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Short", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        log.debug("updateInt: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Int", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        log.debug("updateLong: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Long", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        log.debug("updateFloat: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Float", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        log.debug("updateDouble: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Double", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        log.debug("updateBigDecimal: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "BigDecimal", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        log.debug("updateString: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "String", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        log.debug("updateBytes: {}, <byte[]>", columnLabel);
        this.callProxy(CallType.CALL_UPDATE, "Bytes", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        log.debug("updateDate: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Date", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        log.debug("updateTime: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Time", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        log.debug("updateTimestamp: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Timestamp", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnLabel, x, scaleOrLength);
        this.callProxy(CallType.CALL_UPDATE, "Object", Void.class, List.of(columnLabel, x, scaleOrLength));
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        log.debug("updateObject: {}, {}", columnLabel, x);
        this.callProxy(CallType.CALL_UPDATE, "Object", Void.class, List.of(columnLabel, x));
    }

    @Override
    public void insertRow() throws SQLException {
        log.debug("insertRow called");
        this.callProxy(CallType.CALL_INSERT, "Row", Void.class);
    }

    @Override
    public void updateRow() throws SQLException {
        log.debug("updateRow called");
        this.callProxy(CallType.CALL_UPDATE, "Row", Void.class);
    }

    @Override
    public void deleteRow() throws SQLException {
        log.debug("deleteRow called");
        this.callProxy(CallType.CALL_DELETE, "Row", Void.class);
    }

    @Override
    public void refreshRow() throws SQLException {
        log.debug("refreshRow called");
        this.callProxy(CallType.CALL_REFRESH, "Row", Void.class);
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        log.debug("cancelRowUpdates called");
        this.callProxy(CallType.CALL_CANCEL, "Row", Void.class);
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        log.debug("moveToInsertRow called");
        this.callProxy(CallType.CALL_MOVE, "ToInsertRow", Void.class);
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        log.debug("moveToCurrentRow called");
        this.callProxy(CallType.CALL_MOVE, "ToCurrentRow", Void.class);
    }

    @Override
    public Statement getStatement() throws SQLException {
        log.debug("getStatement called");
        return this.statement;
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        log.debug("getObject: {}, <Map>", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Object", Object.class, List.of(columnIndex, map));
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        log.debug("getRef: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        log.debug("getBlob: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        log.debug("getClob: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        log.debug("getArray: {}", columnIndex);
        String arrayUUID = this.callProxy(CallType.CALL_GET, "Array", String.class, List.of(columnIndex));
        if (arrayUUID == null || arrayUUID.isBlank()) {
            return null;
        }
        return new org.openjproxy.jdbc.Array(this.getConnection(), this.statementService, arrayUUID);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        log.debug("getObject: {}, <Map>", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Object", Object.class, List.of(columnLabel, map));
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        log.debug("getRef: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        log.debug("getBlob: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        log.debug("getClob: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        log.debug("getArray: {}", columnLabel);
        String arrayUUID = this.callProxy(CallType.CALL_GET, "Array", String.class, List.of(columnLabel));
        if (arrayUUID == null || arrayUUID.isBlank()) {
            return null;
        }
        return new org.openjproxy.jdbc.Array(this.getConnection(), this.statementService, arrayUUID);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getDate: {}, <Calendar>", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Date", Date.class, List.of(columnIndex, cal));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getDate: {}, <Calendar>", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Date", Date.class, List.of(columnLabel, cal));
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getTime: {}, <Calendar>", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Time", Date.class, List.of(columnIndex, cal));
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getTime: {}, <Calendar>", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Time", Date.class, List.of(columnLabel, cal));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        log.debug("getTimestamp: {}, <Calendar>", columnIndex);
        return this.callProxy(CallType.CALL_GET, "Timestamp", Timestamp.class, List.of(columnIndex, cal));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        log.debug("getTimestamp: {}, <Calendar>", columnLabel);
        return this.callProxy(CallType.CALL_GET, "Timestamp", Timestamp.class, List.of(columnLabel, cal));
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        log.debug("getURL: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "URL", Timestamp.class, List.of(columnIndex));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        log.debug("getURL: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "URL", Timestamp.class, List.of(columnLabel));
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        log.debug("updateRef: {}, <Ref>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        log.debug("updateRef: {}, <Ref>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        log.debug("updateBlob: {}, <Blob>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        log.debug("updateBlob: {}, <Blob>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        log.debug("updateClob: {}, <Clob>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        log.debug("updateClob: {}, <Clob>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        log.debug("updateArray: {}, <Array>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        log.debug("updateArray: {}, <Array>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        log.debug("getRowId: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        log.debug("getRowId: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        log.debug("updateRowId: {}, <RowId>", columnIndex);
        this.callProxy(CallType.CALL_UPDATE, "RowId", Void.class, List.of(columnIndex, x));
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        log.debug("updateRowId: {}, <RowId>", columnLabel);
        this.callProxy(CallType.CALL_UPDATE, "RowId", Void.class, List.of(columnLabel, x));
    }

    @Override
    public int getHoldability() throws SQLException {
        log.debug("getHoldability called");
        return this.callProxy(CallType.CALL_GET, "Holdability", Integer.class);
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.debug("isClosed called");
        return this.callProxy(CallType.CALL_IS, "Closed", Boolean.class);
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        log.debug("updateNString: {}, {}", columnIndex, nString);
        this.callProxy(CallType.CALL_UPDATE, "NString", Void.class, List.of(columnIndex, nString));
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        log.debug("updateNString: {}, {}", columnLabel, nString);
        this.callProxy(CallType.CALL_UPDATE, "NString", Void.class, List.of(columnLabel, nString));
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        log.debug("updateNClob: {}, <NClob>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        log.debug("updateNClob: {}, <NClob>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        log.debug("getNClob: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        log.debug("getNClob: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        log.debug("getSQLXML: {}", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        log.debug("getSQLXML: {}", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        log.debug("updateSQLXML: {}, <SQLXML>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        log.debug("updateSQLXML: {}, <SQLXML>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        log.debug("getNString: {}", columnIndex);
        return this.callProxy(CallType.CALL_GET, "NString", String.class, List.of(columnIndex));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        log.debug("getNString: {}", columnLabel);
        return this.callProxy(CallType.CALL_GET, "NString", String.class, List.of(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        log.debug("getNCharacterStream: {}", columnIndex);
        return this.retrieveReader(CallType.CALL_GET, "NCharacterStream", LobType.LT_CHARACTER_STREAM, List.of(columnIndex));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        log.debug("getNCharacterStream: {}", columnLabel);
        return this.retrieveReader(CallType.CALL_GET, "NCharacterStream", LobType.LT_CHARACTER_STREAM, List.of(columnLabel));
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>, {}", columnLabel, length);
        // No-op for unsupported
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.debug("updateClob: {}, <Reader>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateClob: {}, <Reader>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.debug("updateNClob: {}, <Reader>, {}", columnIndex, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.debug("updateNClob: {}, <Reader>, {}", columnLabel, length);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateNCharacterStream: {}, <Reader>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        log.debug("updateAsciiStream: {}, <InputStream>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        log.debug("updateBinaryStream: {}, <InputStream>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateCharacterStream: {}, <Reader>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        log.debug("updateBlob: {}, <InputStream>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        log.debug("updateClob: {}, <Reader>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateClob: {}, <Reader>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        log.debug("updateNClob: {}, <Reader>", columnIndex);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        log.debug("updateNClob: {}, <Reader>", columnLabel);
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        log.debug("getObject: {}, {}", columnIndex, type);
        return this.callProxy(CallType.CALL_GET, "Object", type, List.of(columnIndex, type));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        log.debug("getObject: {}, {}", columnLabel, type);
        return this.callProxy(CallType.CALL_GET, "Object", type, List.of(columnLabel, type));
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}, {}", columnIndex, x, targetSqlType, scaleOrLength);
        ResultSet.super.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        log.debug("updateObject: {}, {}, {}, {}", columnLabel, x, targetSqlType, scaleOrLength);
        ResultSet.super.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnIndex, x, targetSqlType);
        ResultSet.super.updateObject(columnIndex, x, targetSqlType);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType) throws SQLException {
        log.debug("updateObject: {}, {}, {}", columnLabel, x, targetSqlType);
        ResultSet.super.updateObject(columnLabel, x, targetSqlType);
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.getConnection().getSession())
                .setResourceType(ResourceType.RES_RESULT_SET)
                .setResourceUUID(this.resultSetUUID);
    }

    private <T> T callProxy(CallType callType, String target, Class returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, target, returnType);
        return this.callProxy(callType, target, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    /**
     * Calls a method or attribute in the remote OJP proxy server.
     *
     * @param callType   - Call type prefix, for example GET, SET, UPDATE...
     * @param target     - Target name of the method or attribute being called.
     * @param returnType - Type returned if a return is present, if not Void.class
     * @param params     - List of parameters required to execute the method.
     * @return - Returns the type passed as returnType parameter.
     * @throws SQLException - In case of failure of call or interface not supported.
     */
    private <T> T callProxy(CallType callType, String target, Class returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, <params>", callType, target, returnType);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                        TargetCall.newBuilder()
                                .setCallType(callType)
                                .setResourceName(target)
                                .addAllParams(ProtoConverter.objectListToParameterValues(params))
                                .build()
                );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.getConnection().setSession(response.getSession());
        if (Void.class.equals(returnType)) {
            return null;
        }

        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));

        // Handle numeric type conversions (Byte/Short come as Integer from proto)
        if (result instanceof Integer && !Integer.class.equals(returnType)) {
            Integer intValue = (Integer) result;
            if (Short.class.equals(returnType) || short.class.equals(returnType)) {
                result = intValue.shortValue();
            } else if (Byte.class.equals(returnType) || byte.class.equals(returnType)) {
                result = intValue.byteValue();
            }
        }

        return (T) result;
    }

    private Reader retrieveReader(CallType callType, String attrName, LobType lobType, List<Object> params)
            throws SQLException {
        log.debug("retrieveReader: {}, {}, {}, <params>", callType, attrName, lobType);
        return new InputStreamReader(this.retrieveBinaryStream(callType, attrName, lobType, params));
    }

    /**
     * Retrieves an attribute from the linked remote instance of the ResultSet.
     *
     * @param callType - Call type prefix, for example GET.
     * @param attrName - Name of the target attribute.
     * @param lobType  - Type of the LOB being retrieved LT_BINARY_STREAM.
     * @param params   - List of parameters required to execute the method.
     * @return InputStream - resulting input stream.
     * @throws SQLException
     */
    private InputStream retrieveBinaryStream(CallType callType, String attrName, LobType lobType, List<Object> params)
            throws SQLException {
        log.debug("retrieveBinaryStream: {}, {}, {}, <params>", callType, attrName, lobType);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(attrName)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.getConnection().setSession(response.getSession());

        List<ParameterValue> values = response.getValuesList();
        String lobRefUUID = values.isEmpty() ? null : (String) ProtoConverter.fromParameterValue(values.get(0));
        BinaryStream binaryStream = new BinaryStream(
                this.presentConnection(),
                new LobServiceImpl(this.presentConnection(), this.getStatementService()),
                this.getStatementService(),
                LobReference.newBuilder()
                        .setSession(this.getConnection().getSession())
                        .setLobType(lobType)
                        .setUuid(lobRefUUID)
                        .build());
        return binaryStream.getBinaryStream();
    }

    private Connection presentConnection() throws SQLException {
        //Statement is not guaranteed to be present, for example when the result set is created from the DatabaseMetaData.
        if (this.statement != null && this.statement.getConnection() != null) {
            return (Connection) this.statement.getConnection();
        }
        return this.getConnection();
    }
}
