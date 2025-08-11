package org.openjdbcproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.CallResourceRequest;
import com.openjdbcproxy.grpc.CallResourceResponse;
import com.openjdbcproxy.grpc.CallType;
import com.openjdbcproxy.grpc.DbName;
import com.openjdbcproxy.grpc.LobReference;
import com.openjdbcproxy.grpc.LobType;
import com.openjdbcproxy.grpc.OpResult;
import com.openjdbcproxy.grpc.ResourceType;
import com.openjdbcproxy.grpc.ResultType;
import com.openjdbcproxy.grpc.TargetCall;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.grpc.client.StatementService;
import org.openjdbcproxy.grpc.dto.Parameter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.openjdbcproxy.grpc.SerializationHandler.deserialize;
import static org.openjdbcproxy.grpc.SerializationHandler.serialize;
import static org.openjdbcproxy.grpc.dto.ParameterType.ARRAY;
import static org.openjdbcproxy.grpc.dto.ParameterType.ASCII_STREAM;
import static org.openjdbcproxy.grpc.dto.ParameterType.BIG_DECIMAL;
import static org.openjdbcproxy.grpc.dto.ParameterType.BINARY_STREAM;
import static org.openjdbcproxy.grpc.dto.ParameterType.BLOB;
import static org.openjdbcproxy.grpc.dto.ParameterType.BOOLEAN;
import static org.openjdbcproxy.grpc.dto.ParameterType.BYTE;
import static org.openjdbcproxy.grpc.dto.ParameterType.BYTES;
import static org.openjdbcproxy.grpc.dto.ParameterType.CHARACTER_READER;
import static org.openjdbcproxy.grpc.dto.ParameterType.CLOB;
import static org.openjdbcproxy.grpc.dto.ParameterType.DATE;
import static org.openjdbcproxy.grpc.dto.ParameterType.DOUBLE;
import static org.openjdbcproxy.grpc.dto.ParameterType.FLOAT;
import static org.openjdbcproxy.grpc.dto.ParameterType.INT;
import static org.openjdbcproxy.grpc.dto.ParameterType.LONG;
import static org.openjdbcproxy.grpc.dto.ParameterType.NULL;
import static org.openjdbcproxy.grpc.dto.ParameterType.N_CHARACTER_STREAM;
import static org.openjdbcproxy.grpc.dto.ParameterType.N_CLOB;
import static org.openjdbcproxy.grpc.dto.ParameterType.N_STRING;
import static org.openjdbcproxy.grpc.dto.ParameterType.OBJECT;
import static org.openjdbcproxy.grpc.dto.ParameterType.REF;
import static org.openjdbcproxy.grpc.dto.ParameterType.ROW_ID;
import static org.openjdbcproxy.grpc.dto.ParameterType.SHORT;
import static org.openjdbcproxy.grpc.dto.ParameterType.SQL_XML;
import static org.openjdbcproxy.grpc.dto.ParameterType.STRING;
import static org.openjdbcproxy.grpc.dto.ParameterType.TIME;
import static org.openjdbcproxy.grpc.dto.ParameterType.TIMESTAMP;
import static org.openjdbcproxy.grpc.dto.ParameterType.UNICODE_STREAM;
import static org.openjdbcproxy.grpc.dto.ParameterType.URL;

@Slf4j
public class PreparedStatement extends Statement implements java.sql.PreparedStatement {
    private final Connection connection;
    private String sql;
    private SortedMap<Integer, Parameter> paramsMap;
    private Map<String, Object> properties;
    private StatementService statementService;

    public PreparedStatement(Connection connection, String sql, StatementService statementService) {
        super(connection, statementService, null, ResourceType.RES_PREPARED_STATEMENT);
        log.debug("PreparedStatement: constructor(connection, sql, statementService) called");
        this.connection = connection;
        this.sql = sql;
        this.properties = null;
        this.paramsMap = new TreeMap<>();
        this.statementService = statementService;
    }

    public PreparedStatement(Connection connection, String sql, StatementService statementService,
                             Map<String, Object> properties) {
        super(connection, statementService, properties, ResourceType.RES_PREPARED_STATEMENT);
        log.debug("PreparedStatement: constructor(connection, sql, statementService, properties) called");
        this.connection = connection;
        this.sql = sql;
        this.properties = properties;
        this.paramsMap = new TreeMap<>();
        this.statementService = statementService;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        log.debug("executeQuery called");
        this.checkClosed();
        log.info("Executing query for -> {}", this.sql);
        Iterator<OpResult> itOpResult = this.statementService
                .executeQuery(this.connection.getSession(), this.sql, new ArrayList<>(this.paramsMap.values()), this.properties);
        return new ResultSet(itOpResult, this.statementService, this);
    }

    @Override
    public int executeUpdate() throws SQLException {
        log.debug("executeUpdate called");
        this.checkClosed();
        log.info("Executing update for -> {}", this.sql);
        OpResult result = this.statementService.executeUpdate(this.connection.getSession(), this.sql,
                new ArrayList<>(this.paramsMap.values()), this.getStatementUUID(), this.properties);
        this.connection.setSession(result.getSession());
        if (StringUtils.isNotBlank(result.getUuid())) {
            this.setStatementUUID(result.getUuid());
        }
        return deserialize(result.getValue().toByteArray(), Integer.class);
    }

    @Override
    public void addBatch() throws SQLException {
        log.debug("addBatch called");
        this.checkClosed();
        log.info("Executing add batch for -> {}", this.sql);
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonConstants.PREPARED_STATEMENT_ADD_BATCH_FLAG, Boolean.TRUE);
        OpResult result = this.statementService.executeUpdate(this.connection.getSession(), this.sql,
                new ArrayList<>(this.paramsMap.values()), this.getStatementUUID(), properties);
        this.connection.setSession(result.getSession());
        if (StringUtils.isBlank(this.getStatementUUID()) && ResultType.UUID_STRING.equals(result.getType()) &&
                !result.getValue().isEmpty()) {
            String psUUID = deserialize(result.getValue().toByteArray(), String.class);
            this.setStatementUUID(psUUID);
        }
        this.paramsMap = new TreeMap<>();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        log.debug("setNull: {}, {}", parameterIndex, sqlType);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(NULL)
                        .index(parameterIndex)
                        .values(Arrays.asList(sqlType))
                        .build());
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        log.debug("setBoolean: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(BOOLEAN)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        log.debug("setByte: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(BYTE)
                        .index(parameterIndex)
                        .values(Arrays.asList(new byte[]{x}))//Transform to byte array as it becomes an Object facilitating serialization.
                        .build());
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        log.debug("setShort: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(SHORT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        log.debug("setInt: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(INT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        log.debug("setLong: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(LONG)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        log.debug("setFloat: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(FLOAT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        log.debug("setDouble: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(DOUBLE)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        log.debug("setBigDecimal: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(BIG_DECIMAL)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        log.debug("setString: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(STRING)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        log.debug("setBytes: {}, <byte[]>", parameterIndex);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(BYTES)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        log.debug("setDate: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(DATE)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        log.debug("setTime: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(TIME)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        log.debug("setTimestamp: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(TIMESTAMP)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        log.debug("setAsciiStream: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(ASCII_STREAM)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        log.debug("setUnicodeStream: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(UNICODE_STREAM)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream inputStream, int length) throws SQLException {
        log.debug("setBinaryStream: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        this.setBinaryStream(parameterIndex, inputStream, (long) length);
    }

    @Override
    public void clearParameters() throws SQLException {
        log.debug("clearParameters called");
        this.checkClosed();
        this.paramsMap = new TreeMap<>();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        log.debug("setObject: {}, {}, {}", parameterIndex, x, targetSqlType);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(OBJECT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, targetSqlType))
                        .build());
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        log.debug("setObject: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(OBJECT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public boolean execute() throws SQLException {
        log.debug("execute called");
        this.checkClosed();
        String trimmedSql = this.sql.trim().toUpperCase();
        if (trimmedSql.startsWith("SELECT")) {
            // Delegate to executeQuery
            ResultSet resultSet = this.executeQuery();
            // Store the ResultSet for later retrieval if needed
            this.lastResultSet = resultSet;
            this.lastUpdateCount = -1;
            return true; // Indicates a ResultSet was returned
        } else {
            // Delegate to executeUpdate
            this.lastUpdateCount = this.executeUpdate();
            return false; // Indicates no ResultSet was returned
        }
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        log.debug("setCharacterStream: {}, <Reader>, {}", parameterIndex, length);
        this.checkClosed();
        //TODO this will require an implementation of Reader that communicates across GRPC or maybe a conversion to InputStream
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(CHARACTER_READER)
                        .index(parameterIndex)
                        .values(Arrays.asList(reader, length))
                        .build());
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        log.debug("setRef: {}, {}", parameterIndex, x);
        this.checkClosed();
        if (DbName.H2.equals(this.getConnection().getDbName())) {
            throw new SQLException("Not supported.");
        }
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(REF)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        log.debug("setBlob: {}, {}", parameterIndex, x);
        this.checkClosed();
        String blobUUID = null;
        if (x != null) {
            org.openjdbcproxy.jdbc.Blob ojpBlob = (org.openjdbcproxy.jdbc.Blob) x;
            // Ensure the LOB reference is available before getting UUID
            try {
                ojpBlob.getLobReference().get(); // Wait for async operation to complete
                
                // Validate that the server-side blob is actually available
                ojpBlob.validateServerSideLobAvailabilityWithRetry();
                
                blobUUID = ojpBlob.getUUID();
                log.debug("Blob UUID obtained successfully: {}", blobUUID);
            } catch (Exception e) {
                log.error("Unable to get Blob UUID", e);
                throw new SQLException("Unable to get Blob UUID: " + e.getMessage(), e);
            }
        }
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(BLOB)
                        .index(parameterIndex)
                        .values(Arrays.asList(blobUUID)) //Only send the Id as per the blob has been streamed in advance.
                        .build());
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        log.debug("setClob: {}, {}", parameterIndex, x);
        this.checkClosed();
        String clobUUID = (x != null) ? ((org.openjdbcproxy.jdbc.Clob) x).getUUID() : null;
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(CLOB)
                        .index(parameterIndex)
                        .values(Arrays.asList(clobUUID))
                        .build());
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        log.debug("setArray: {}, {}", parameterIndex, x);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(ARRAY)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        log.debug("getMetaData called");
        this.checkClosed();
        return new org.openjdbcproxy.jdbc.ResultSetMetaData(this, this.statementService);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        log.debug("setDate: {}, {}, {}", parameterIndex, x, cal);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(DATE)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, cal))
                        .build());
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        log.debug("setTime: {}, {}, {}", parameterIndex, x, cal);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(TIME)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, cal))
                        .build());
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        log.debug("setTimestamp: {}, {}, {}", parameterIndex, x, cal);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(TIMESTAMP)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, cal))
                        .build());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        log.debug("setNull: {}, {}, {}", parameterIndex, sqlType, typeName);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(NULL)
                        .index(parameterIndex)
                        .values(Arrays.asList(sqlType, typeName))
                        .build());
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        log.debug("setURL: {}, {}", parameterIndex, x);
        this.checkClosed();
        if (DbName.H2.equals(this.getConnection().getDbName())) {
            throw new SQLException("Not supported.");
        }
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(URL)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        log.debug("getParameterMetaData called");
        this.checkClosed();
        return new org.openjdbcproxy.jdbc.ParameterMetaData();
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        log.debug("setRowId: {}, {}", parameterIndex, x);
        this.checkClosed();
        if (DbName.H2.equals(this.getConnection().getDbName())) {
            throw new SQLException("Not supported.");
        }
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(ROW_ID)
                        .index(parameterIndex)
                        .values(Arrays.asList(x))
                        .build());
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        log.debug("setNString: {}, {}", parameterIndex, value);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(N_STRING)
                        .index(parameterIndex)
                        .values(Arrays.asList(value))
                        .build());
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        log.debug("setNCharacterStream: {}, <Reader>, {}", parameterIndex, length);
        this.checkClosed();
        //TODO see if can use similar/same reader communication layer as other methods that require reader
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(N_CHARACTER_STREAM)
                        .index(parameterIndex)
                        .values(Arrays.asList(value, length))
                        .build());
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        log.debug("setNClob: {}, {}", parameterIndex, value);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(N_CLOB)
                        .index(parameterIndex)
                        .values(Arrays.asList(value))
                        .build());
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        log.debug("setClob: {}, <Reader>, {}", parameterIndex, length);
        this.checkClosed();
        try {
            org.openjdbcproxy.jdbc.Clob clob = (org.openjdbcproxy.jdbc.Clob) this.getConnection().createClob();
            OutputStream os = clob.setAsciiStream(1);
            int byteRead = reader.read();
            int writtenLength = 0;
            while (byteRead != -1 && length > writtenLength) {
                os.write(byteRead);
                writtenLength++;
                byteRead = reader.read();
            }
            os.close();
            this.paramsMap.put(parameterIndex,
                    Parameter.builder()
                            .type(CLOB)
                            .index(parameterIndex)
                            .values(Arrays.asList(clob.getUUID()))
                            .build()
            );
        } catch (IOException e) {
            throw new SQLException("Unable to write CLOB bytes: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        log.debug("setBlob: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        try {
            org.openjdbcproxy.jdbc.Blob blob = this.getConnection().createBlob();
            OutputStream os = blob.setBinaryStream(1);
            int byteRead = inputStream.read();
            int writtenLength = 0;
            while (byteRead != -1 && length > writtenLength) {
                os.write(byteRead);
                writtenLength++;
                byteRead = inputStream.read();
            }
            os.close(); // This will wait for async operation and validate server-side availability
            
            // Additional validation with retry logic for getting UUID
            String blobUUID = null;
            int maxRetries = 3;
            int retryDelayMs = 50;
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    blob.getLobReference().get(); // Wait for async operation to complete
                    blob.validateServerSideLobAvailabilityWithRetry(); // Validate server-side availability
                    blobUUID = blob.getUUID();
                    log.debug("Blob UUID obtained successfully on attempt {}: {}", attempt, blobUUID);
                    break;
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Failed to get Blob UUID on attempt {}: {}", attempt, e.getMessage());
                    
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(retryDelayMs);
                            retryDelayMs *= 2; // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Interrupted while retrying to get Blob UUID", ie);
                        }
                    } else {
                        throw new SQLException("Unable to get Blob UUID after writing: " + e.getMessage(), e);
                    }
                }
            }
            
            this.paramsMap.put(parameterIndex,
                    Parameter.builder()
                            .type(BLOB)
                            .index(parameterIndex)
                            .values(Arrays.asList(blobUUID))
                            .build()
            );
        } catch (IOException e) {
            log.error("IOException in setBlob with InputStream", e);
            throw new SQLException("Unable to write BLOB bytes: " + e.getMessage(), e);
        }
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        log.debug("setNClob: {}, <Reader>, {}", parameterIndex, length);
        this.checkClosed();
        //TODO see if can use similar/same reader communication layer as other methods that require reader
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(N_CLOB)
                        .index(parameterIndex)
                        .values(Arrays.asList(reader, length))
                        .build());
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        log.debug("setSQLXML: {}, {}", parameterIndex, xmlObject);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(SQL_XML)
                        .index(parameterIndex)
                        .values(Arrays.asList(xmlObject))
                        .build());
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        log.debug("setObject: {}, {}, {}, {}", parameterIndex, x, targetSqlType, scaleOrLength);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(OBJECT)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, targetSqlType, scaleOrLength))
                        .build());
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        log.debug("setAsciiStream: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        this.paramsMap.put(parameterIndex,
                Parameter.builder()
                        .type(ASCII_STREAM)
                        .index(parameterIndex)
                        .values(Arrays.asList(x, length))
                        .build());
    }

    @SneakyThrows
    @Override
    public void setBinaryStream(int parameterIndex, InputStream is, long length) throws SQLException {
        log.debug("setBinaryStream: {}, <InputStream>, {}", parameterIndex, length);
        this.checkClosed();
        try {
            if (DbName.DB2.equals(this.getConnection().getDbName())) {
                int readLength = (length > 0) ? (int) length : Integer.MAX_VALUE;
                byte[] allBytes = is.readNBytes((int) readLength);
                this.paramsMap.put(parameterIndex,
                        Parameter.builder()
                                .type(BINARY_STREAM)
                                .index(parameterIndex)
                                .values(Arrays.asList(allBytes, length))
                                .build());
                return;
            }
            BinaryStream binaryStream = new BinaryStream(this.getConnection(),
                    new LobServiceImpl(this.connection, this.statementService),
                    this.statementService, null);
            Map<Integer, Object> metadata = new HashMap<>();
            metadata.put(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX, parameterIndex);
            metadata.put(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_LENGTH, length);
            metadata.put(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_SQL, this.sql);
            metadata.put(CommonConstants.PREPARED_STATEMENT_UUID_BINARY_STREAM, this.getStatementUUID());
            LobReference lobReference = binaryStream.sendBinaryStream(LobType.LT_BINARY_STREAM, is, metadata);
            this.setStatementUUID(lobReference.getStmtUUID());
            binaryStream.getLobReference().set(lobReference);
        } catch (RuntimeException e) {
            throw new SQLException("Unable to write binary stream: " + e.getMessage(), e);
        }
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        log.debug("setCharacterStream: {}, <Reader>, {}", parameterIndex, length);
        this.checkClosed();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        log.debug("setAsciiStream: {}, <InputStream>", parameterIndex);
        this.checkClosed();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        log.debug("setBinaryStream: {}, <InputStream>", parameterIndex);
        this.checkClosed();
        if (x == null) {
            this.paramsMap.put(parameterIndex,
                    Parameter.builder()
                            .type(BINARY_STREAM)
                            .index(parameterIndex)
                            .values(Arrays.asList(x))
                            .build());
        } else {
            this.setBinaryStream(parameterIndex, x, -1); //-1 means not provided.
        }
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        log.debug("setCharacterStream: {}, <Reader>", parameterIndex);
        this.checkClosed();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        log.debug("setNCharacterStream: {}, <Reader>", parameterIndex);
        this.checkClosed();
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        log.debug("setClob: {}, <Reader>", parameterIndex);
        this.checkClosed();
        this.setClob(parameterIndex, reader, Long.MAX_VALUE);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        log.debug("setBlob: {}, <InputStream>", parameterIndex);
        this.setBlob(parameterIndex, inputStream, Integer.MAX_VALUE);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        log.debug("setNClob: {}, <Reader>", parameterIndex);
        this.checkClosed();
    }

    public Map<String, Object> getProperties() {
        log.debug("getProperties called");
        this.propertiesHaveSqlStatement();
        return this.properties;
    }

    /**
     * Has to override the Statement implementation because PreparedStatement has to send extra properties like the SQL
     * being executed, which Statement does not.
     *
     * @return RemoteProxyResultSet
     * @throws SQLException
     */
    @Override
    public RemoteProxyResultSet getGeneratedKeys() throws SQLException {
        log.debug("getGeneratedKeys called");
        checkClosed();
        String resultSetUUID = this.callProxy(CallType.CALL_GET, "GeneratedKeys", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this);
    }

    /**
     * Has to override the Statement implementation because PreparedStatement has to send extra properties like the SQL
     * being executed, which Statement does not.
     *
     * @return int Query Timeout.
     * @throws SQLException
     */
    @Override
    public int getQueryTimeout() throws SQLException {
        log.debug("getQueryTimeout called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "QueryTimeout", Integer.class);
    }

    /**
     * Has to override the Statement implementation because PreparedStatement has to send extra properties like the SQL
     * being executed, which Statement does not.
     *
     * @throws SQLException
     */
    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Warnings", Void.class);
    }

    /**
     * Has to override the Statement implementation because PreparedStatement has to send extra properties like the SQL
     * being executed, which Statement does not.
     *
     * @throws SQLException
     */
    @Override
    public void clearBatch() throws SQLException {
        log.debug("clearBatch called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Batch", Void.class);
    }

    /**
     * Has to override the Statement implementation because PreparedStatement has to send extra properties like the SQL
     * being executed, which Statement does not.
     *
     * @return int max field size
     * @throws SQLException
     */
    @Override
    public int getMaxFieldSize() throws SQLException {
        log.debug("getMaxFieldSize called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "MaxFieldSize", Integer.class);
    }

    /**
     * Guarantees that the properties map has the sql statement set in this prepared statement.
     */
    private void propertiesHaveSqlStatement() {
        String sqlProperty = (this.properties != null &&
                this.properties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY) != null) ?
                this.properties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY).toString() : null;
        if (StringUtils.isBlank(sqlProperty) && StringUtils.isNotBlank(this.sql)) {
            if (this.properties == null) {
                this.properties = new HashMap<>();
            }
            this.properties.put(CommonConstants.PREPARED_STATEMENT_SQL_KEY, this.sql);
        }
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        this.propertiesHaveSqlStatement();
        CallResourceRequest.Builder builder = CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_PREPARED_STATEMENT);
        if (this.getStatementUUID() != null) {
            builder.setResourceUUID(this.getStatementUUID());
        }
        if (this.properties != null) {
            builder.setProperties(ByteString.copyFrom(serialize(this.properties)));
        }
        return builder;
    }

    private <T> T callProxy(CallType callType, String targetName, Class<?> returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, targetName, returnType);
        return this.callProxy(callType, targetName, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    private <T> T callProxy(CallType callType, String targetName, Class<?> returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, params.size={}", callType, targetName, returnType, params != null ? params.size() : 0);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(targetName)
                        .setParams(ByteString.copyFrom(serialize(params)))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());
        if (this.getStatementUUID() == null && StringUtils.isNotBlank(response.getResourceUUID())) {
            this.setStatementUUID(response.getResourceUUID());
        }
        if (Void.class.equals(returnType)) {
            return null;
        }
        return (T) deserialize(response.getValues().toByteArray(), returnType);
    }
}