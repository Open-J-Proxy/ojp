package org.openjproxy.grpc.server;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import com.openjproxy.grpc.TargetCall;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.utils.DateTimeUtils;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.server.utils.DriverUtils;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.openjproxy.grpc.server.utils.UrlParser;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjproxy.constants.CommonConstants.MAX_LOB_DATA_BLOCK_SIZE;
import static org.openjproxy.grpc.SerializationHandler.deserialize;
import static org.openjproxy.grpc.SerializationHandler.serialize;
import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.Constants.EMPTY_MAP;
import static org.openjproxy.grpc.server.Constants.EMPTY_STRING;
import static org.openjproxy.grpc.server.Constants.SHA_256;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

@Slf4j
@RequiredArgsConstructor
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, HikariDataSource> datasourceMap = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;
    
    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();
    
    // Server configuration for creating segregation managers
    private final ServerConfiguration serverConfiguration;
    
    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");
    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    private final static String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    static {
        DriverUtils.registerDrivers();
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);
        log.info("connect connHash = {}, isXA = {}", connHash, connectionDetails.getIsXA());

        HikariDataSource ds = this.datasourceMap.get(connHash);
        if (ds == null) {
            try {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(UrlParser.parseUrl(connectionDetails.getUrl()));
                config.setUsername(connectionDetails.getUser());
                config.setPassword(connectionDetails.getPassword());

                // Configure HikariCP using datasource-specific configuration
                DataSourceConfigurationManager.DataSourceConfiguration dsConfig = 
                        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

                ds = new HikariDataSource(config);
                this.datasourceMap.put(connHash, ds);
                
                // Create a slow query segregation manager for this datasource
                createSlowQuerySegregationManagerForDatasource(connHash, config.getMaximumPoolSize());
                
                log.info("Created new HikariDataSource for dataSource '{}' with connHash: {}", 
                        dsConfig.getDataSourceName(), connHash);
                
            } catch (Exception e) {
                log.error("Failed to create datasource for connection hash {}: {}", connHash, e.getMessage(), e);
                SQLException sqlException = new SQLException("Failed to create datasource: " + e.getMessage(), e);
                sendSQLExceptionMetadata(sqlException, responseObserver);
                return;
            }
        }

        this.sessionManager.registerClientUUID(connHash, connectionDetails.getClientUUID());

        SessionInfo sessionInfo;
        if (connectionDetails.getIsXA()) {
            // For XA connections, we need to get an XA connection directly from the underlying XADataSource
            // HikariCP doesn't expose XADataSource interface, so we create XADataSource directly
            try {
                // For PostgreSQL/MySQL, we need to create an XADataSource and get XAConnection from it
                // Determine database type from URL
                String url = connectionDetails.getUrl();
                javax.sql.XADataSource xaDataSource = createXADataSource(url, connectionDetails);
                javax.sql.XAConnection xaConnection = xaDataSource.getXAConnection(
                    connectionDetails.getUser(), 
                    connectionDetails.getPassword()
                );
                Connection connection = xaConnection.getConnection();
                
                // Note: Do NOT call connection.setAutoCommit(false) here
                // For PostgreSQL XA, the connection's auto-commit state is managed by the XAResource
                // Calling setAutoCommit on a connection from XAConnection can interfere with XA transaction control
                
                sessionInfo = sessionManager.createXASession(connectionDetails.getClientUUID(), connection, xaConnection);
                log.debug("Created XA session: {}", sessionInfo.getSessionUUID());
            } catch (Exception e) {
                log.error("Failed to create XA session: {}", e.getMessage(), e);
                SQLException sqlException = new SQLException("Failed to create XA session: " + e.getMessage(), e);
                sendSQLExceptionMetadata(sqlException, responseObserver);
                return;
            }
        } else {
            // For regular connections, just return session info without creating a session yet
            sessionInfo = SessionInfo.newBuilder()
                    .setConnHash(connHash)
                    .setClientUUID(connectionDetails.getClientUUID())
                    .setIsXA(false)
                    .build();
        }

        responseObserver.onNext(sessionInfo);

        this.dbNameMap.put(connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));

        responseObserver.onCompleted();
    }
    
    /**
     * Creates an XADataSource for the specified database type.
     */
    private javax.sql.XADataSource createXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        String lowerUrl = url.toLowerCase();
        
        try {
            if (lowerUrl.contains("postgresql")) {
                // PostgreSQL XADataSource
                org.postgresql.xa.PGXADataSource xaDS = new org.postgresql.xa.PGXADataSource();
                
                // Parse connection URL to extract host, port, database
                // Format: jdbc:postgresql://host:port/database or ojp[...]:host:port/database
                String cleanUrl = url;
                if (cleanUrl.toLowerCase().contains("_postgresql:")) {
                    cleanUrl = cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_postgresql:") + 1);
                } else if (cleanUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
                    cleanUrl = cleanUrl.substring("jdbc:".length());
                }
                
                // Parse postgresql://host:port/database
                if (cleanUrl.startsWith("postgresql://")) {
                    cleanUrl = cleanUrl.substring("postgresql://".length());
                    String[] parts = cleanUrl.split("/");
                    if (parts.length >= 2) {
                        String hostPort = parts[0];
                        String database = parts[1].split("\\?")[0]; // Remove query params
                        
                        String[] hostPortParts = hostPort.split(":");
                        String host = hostPortParts[0];
                        int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 5432;
                        
                        xaDS.setServerNames(new String[]{host});
                        xaDS.setPortNumbers(new int[]{port});
                        xaDS.setDatabaseName(database);
                    }
                }
                
                xaDS.setUser(connectionDetails.getUser());
                xaDS.setPassword(connectionDetails.getPassword());
                
                log.info("Created PostgreSQL XADataSource");
                return xaDS;
                
            } else if (lowerUrl.contains("mysql")) {
                // MySQL XADataSource
                com.mysql.cj.jdbc.MysqlXADataSource xaDS = new com.mysql.cj.jdbc.MysqlXADataSource();
                xaDS.setUrl(url);
                xaDS.setUser(connectionDetails.getUser());
                xaDS.setPassword(connectionDetails.getPassword());
                log.info("Created MySQL XADataSource");
                return xaDS;
                
            } else {
                throw new SQLException("XA transactions not supported for database type in URL: " + url);
            }
        } catch (Exception e) {
            log.error("Failed to create XADataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create XADataSource: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates a slow query segregation manager for a specific datasource.
     * Each datasource gets its own manager with pool size based on actual HikariCP configuration.
     */
    private void createSlowQuerySegregationManagerForDatasource(String connHash, int actualPoolSize) {
        if (serverConfiguration.isSlowQuerySegregationEnabled()) {
            SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                actualPoolSize,
                serverConfiguration.getSlowQuerySlotPercentage(),
                serverConfiguration.getSlowQueryIdleTimeout(),
                serverConfiguration.getSlowQuerySlowSlotTimeout(),
                serverConfiguration.getSlowQueryFastSlotTimeout(),
                serverConfiguration.getSlowQueryUpdateGlobalAvgInterval(),
                true
            );
            slowQuerySegregationManagers.put(connHash, manager);
            log.info("Created SlowQuerySegregationManager for datasource {} with pool size {}", 
                    connHash, actualPoolSize);
        } else {
            // Create disabled manager for consistency
            SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                1, 0, 0, 0, 0, 0, false
            );
            slowQuerySegregationManagers.put(connHash, manager);
            log.info("Created disabled SlowQuerySegregationManager for datasource {}", connHash);
        }
    }
    
    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback", connHash);
            // Create a disabled manager as fallback
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        
        try {
            circuitBreaker.preCheck(stmtHash);
            
            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);
            
            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () -> {
                return executeUpdateInternal(request);
            });
            
            responseObserver.onNext(result);
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);
            
        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException) {
                circuitBreaker.onFailure(stmtHash, (SQLException) e.getCause());
                sendSQLExceptionMetadata((SQLException) e.getCause(), responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }
    
    /**
     * Internal method for executing updates without segregation logic.
     */
    private OpResult executeUpdateInternal(StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo = request.getSession();
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        try {
            dto = sessionConnection(request.getSession(), StatementRequestValidator.isAddBatchOperation(request) || StatementRequestValidator.hasAutoGeneratedKeysFlag(request));
            returnSessionInfo = dto.getSession();

            List<Parameter> params = deserialize(request.getParameters().toByteArray(), List.class);
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID()) ?
                    sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID()) : null;
            if (CollectionUtils.isNotEmpty(params) || ps != null) {
                if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                    Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
                    for (Object o : lobs) {
                        LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
                        Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(), lobIS.getUuid());
                        Integer parameterIndex = (Integer) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX);
                        ps.setBinaryStream(parameterIndex, lobIS);
                    }
                    if (DbName.POSTGRES.equals(dto.getDbName())) {//Postgres requires check if the lob streams are fully consumed.
                        sessionManager.waitLobStreamsConsumption(dto.getSession());
                    }
                    if (ps != null) {
                        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
                    }
                } else {
                    ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params, request);
                    if (StatementRequestValidator.hasAutoGeneratedKeysFlag(request)) {
                        String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        opResultBuilder.setUuid(psNewUUID);
                    }
                }
                if (StatementRequestValidator.isAddBatchOperation(request)) {
                    ps.addBatch();
                    if (request.getStatementUUID().isBlank()) {
                        psUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                    } else {
                        psUUID = request.getStatementUUID();
                    }
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            if (StatementRequestValidator.isAddBatchOperation(request)) {
                return opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setValue(ByteString.copyFrom(serialize(psUUID))).build();
            } else {
                return opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setValue(ByteString.copyFrom(serialize(updated))).build();
            }
        } finally {
            //If there is no session, close statement and connection
            if (dto.getSession() == null || StringUtils.isEmpty(dto.getSession().getSessionUUID())) {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        log.error("Failure closing statement: " + e.getMessage(), e);
                    }
                    try {
                        stmt.getConnection().close();
                    } catch (SQLException e) {
                        log.error("Failure closing connection: " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        
        try {
            circuitBreaker.preCheck(stmtHash);
            
            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);
            
            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                executeQueryInternal(request, responseObserver);
                return null; // Void return for query execution
            });
            
            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException) {
                circuitBreaker.onFailure(stmtHash, (SQLException) e.getCause());
                sendSQLExceptionMetadata((SQLException) e.getCause(), responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }
    
    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver) throws SQLException {
        ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), true);

        List<Parameter> params = deserialize(request.getParameters().toByteArray(), List.class);
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params, request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(request.getSql()));
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        }
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());
        try {
            ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), false);
            this.handleResultSet(dto.getSession(), request.getResultSetUUID(), responseObserver);
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        log.info("Creating LOB");
        return new ServerCallStreamObserver<>() {
            private SessionInfo sessionInfo;
            private String lobUUID;
            private String stmtUUID;
            private LobType lobType;
            private LobDataBlocksInputStream lobDataBlocksInputStream = null;
            private final AtomicBoolean isFirstBlock = new AtomicBoolean(true);
            private final AtomicInteger countBytesWritten = new AtomicInteger(0);

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void setOnCancelHandler(Runnable runnable) {

            }

            @Override
            public void setCompression(String s) {

            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setOnReadyHandler(Runnable runnable) {

            }

            @Override
            public void request(int i) {

            }

            @Override
            public void setMessageCompression(boolean b) {

            }

            @Override
            public void disableAutoInboundFlowControl() {

            }

            @Override
            public void onNext(LobDataBlock lobDataBlock) {
                try {
                    this.lobType = lobDataBlock.getLobType();
                    log.info("lob data block received, lob type {}", this.lobType);
                    ConnectionSessionDTO dto = sessionConnection(lobDataBlock.getSession(), true);
                    Connection conn = dto.getConnection();
                    if (StringUtils.isEmpty(lobDataBlock.getSession().getSessionUUID()) || this.lobUUID == null) {
                        if (LobType.LT_BLOB.equals(this.lobType)) {
                            Blob newBlob = conn.createBlob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newBlob, this.lobUUID);
                        } else if (LobType.LT_CLOB.equals(this.lobType)) {
                            Clob newClob = conn.createClob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newClob, this.lobUUID);
                        }
                    }

                    int bytesWritten = 0;
                    switch (this.lobType) {
                        case LT_BLOB: {
                            Blob blob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            if (blob == null) {
                                throw new SQLException("Unable to write LOB of type " + this.lobType + ": Blob object is null for UUID " + this.lobUUID + 
                                    ". This may indicate a race condition or session management issue.");
                            }
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            bytesWritten = blob.setBytes(lobDataBlock.getPosition(), byteArrayData);
                            break;
                        }
                        case LT_CLOB: {
                            Clob clob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            if (clob == null) {
                                throw new SQLException("Unable to write LOB of type " + this.lobType + ": Clob object is null for UUID " + this.lobUUID + 
                                    ". This may indicate a race condition or session management issue.");
                            }
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            Writer writer = clob.setCharacterStream(lobDataBlock.getPosition());
                            writer.write(new String(byteArrayData, StandardCharsets.UTF_8).toCharArray());
                            bytesWritten = byteArrayData.length;
                            break;
                        }
                        case LT_BINARY_STREAM: {
                            if (this.lobUUID == null) {
                                byte[] metadataBytes = lobDataBlock.getMetadata().toByteArray();
                                if (metadataBytes == null && metadataBytes.length < 1) {
                                    throw new SQLException("Metadata empty for binary stream type.");
                                }
                                Map<Integer, Object> metadata = deserialize(metadataBytes, Map.class);
                                String sql = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_SQL);
                                PreparedStatement ps;
                                String preparedStatementUUID = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_UUID_BINARY_STREAM);
                                if (StringUtils.isNotEmpty(preparedStatementUUID)) {
                                    stmtUUID = preparedStatementUUID;
                                } else {
                                    ps = dto.getConnection().prepareStatement(sql);
                                    stmtUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                                }

                                //Add bite stream as parameter to the prepared statement
                                lobDataBlocksInputStream = new LobDataBlocksInputStream(lobDataBlock);
                                this.lobUUID = lobDataBlocksInputStream.getUuid();
                                //Only needs to be registered so we can wait it to receive all bytes before performing the update.
                                sessionManager.registerLob(dto.getSession(), lobDataBlocksInputStream, lobDataBlocksInputStream.getUuid());
                                sessionManager.registerAttr(dto.getSession(), lobDataBlocksInputStream.getUuid(), metadata);
                                //Need to first send the ref to the client before adding the stream as a parameter
                                sendLobRef(dto, lobDataBlock.getData().toByteArray().length);
                            } else {
                                lobDataBlocksInputStream.addBlock(lobDataBlock);
                            }
                            break;
                        }
                    }
                    this.countBytesWritten.addAndGet(bytesWritten);
                    this.sessionInfo = dto.getSession();

                    if (isFirstBlock.get()) {
                        sendLobRef(dto, bytesWritten);
                    }

                } catch (SQLException e) {
                    sendSQLExceptionMetadata(e, responseObserver);
                } catch (Exception e) {
                    sendSQLExceptionMetadata(new SQLException("Unable to write data: " + e.getMessage(), e), responseObserver);
                }
            }

            private void sendLobRef(ConnectionSessionDTO dto, int bytesWritten) {
                log.info("Returning lob ref {}", this.lobUUID);
                //Send one flag response to indicate that the Blob has been created successfully and the first
                // block fo data has been written successfully.
                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(dto.getSession())
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(bytesWritten);
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }
                responseObserver.onNext(lobRefBuilder.build());
                isFirstBlock.set(false);
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Failure lob stream: " + throwable.getMessage(), throwable);
                if (lobDataBlocksInputStream != null) {
                    lobDataBlocksInputStream.finish(true);
                }
            }

            @SneakyThrows
            @Override
            public void onCompleted() {
                if (lobDataBlocksInputStream != null) {
                    CompletableFuture.runAsync(() -> {
                        log.info("Finishing lob stream for lob ref {}", this.lobUUID);
                        lobDataBlocksInputStream.finish(true);
                    });
                }

                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(this.sessionInfo)
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(this.countBytesWritten.get());
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }

                //Send the final Lob reference with total count of written bytes.
                responseObserver.onNext(lobRefBuilder.build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        log.debug("Reading lob {}", request.getLobReference().getUuid());
        try {
            LobReference lobRef = request.getLobReference();
            ReadLobContext readLobContext = this.findLobContext(request);
            InputStream inputStream = readLobContext.getInputStream();
            if (inputStream == null) {
                responseObserver.onNext(LobDataBlock.newBuilder()
                        .setSession(lobRef.getSession())
                        .setPosition(-1)
                        .setData(ByteString.copyFrom(new byte[0]))
                        .build());
                responseObserver.onCompleted();
                return;
            }
            //If the lob length is known the exact size of the next block is also known.
            boolean exactSizeKnown = readLobContext.getLobLength().isPresent() && readLobContext.getAvailableLength().isPresent();
            int nextByte = inputStream.read();
            int nextBlockSize = nextByte == -1 ? 1 : this.nextBlockSize(readLobContext, request.getPosition());
            byte[] nextBlock = new byte[nextBlockSize];
            int idx = -1;
            int currentPos = (int) request.getPosition();
            boolean nextBlockFullyEmpty = false;
            while (nextByte != -1) {
                nextBlock[++idx] = (byte) nextByte;
                nextBlockFullyEmpty = false;
                if (idx == nextBlockSize - 1) {
                    currentPos += (idx + 1);
                    log.info("Sending block of data size {} pos {}", idx + 1, currentPos);
                    //Send data to client in limited size blocks to safeguard server memory.
                    responseObserver.onNext(LobDataBlock.newBuilder()
                            .setSession(lobRef.getSession())
                            .setPosition(currentPos)
                            .setData(ByteString.copyFrom(nextBlock))
                            .build()
                    );
                    nextBlockSize = this.nextBlockSize(readLobContext, currentPos - 1);
                    if (nextBlockSize > 0) {//Might be a single small block then nextBlockSize will return negative.
                        nextBlock = new byte[nextBlockSize];
                    } else {
                        nextBlock = new byte[0];
                    }
                    nextBlockFullyEmpty = true;
                    idx = -1;
                }
                nextByte = inputStream.read();
            }

            //Send leftover bytes
            if (!nextBlockFullyEmpty && nextBlock.length > 0 && nextBlock[0] != -1) {

                byte[] adjustedSizeArray = (idx % MAX_LOB_DATA_BLOCK_SIZE != 0 && !exactSizeKnown) ?
                        trim(nextBlock) : nextBlock;
                if (nextByte == -1 && adjustedSizeArray.length == 1 && adjustedSizeArray[0] != nextByte) {
                    // For cases where the amount of bytes is a multiple of the block size and last read only reads the end of the stream.
                    adjustedSizeArray = new byte[0];
                }
                currentPos = (int) request.getPosition() + idx;
                log.info("Sending leftover bytes size {} pos {}", idx, currentPos);
                responseObserver.onNext(LobDataBlock.newBuilder()
                        .setSession(lobRef.getSession())
                        .setPosition(currentPos)
                        .setData(ByteString.copyFrom(adjustedSizeArray))
                        .build()
                );
            }

            responseObserver.onCompleted();

        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] trim(byte[] nextBlock) {
        int lastBytePos = 0;
        for (int i = nextBlock.length - 1; i >= 0; i--) {
            int currentByte = nextBlock[i];
            if (currentByte != 0) {
                lastBytePos = i;
                break;
            }
        }

        byte[] trimmedArray = new byte[lastBytePos + 1];
        System.arraycopy(nextBlock, 0, trimmedArray, 0, lastBytePos + 1);
        return trimmedArray;
    }

    @Builder
    static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @SneakyThrows
    private ReadLobContext findLobContext(ReadLobRequest request) throws SQLException {
        InputStream inputStream = null;
        LobReference lobReference = request.getLobReference();
        ReadLobContext.ReadLobContextBuilder readLobContextBuilder = ReadLobContext.builder();
        switch (request.getLobReference().getLobType()) {
            case LT_BLOB: {
                inputStream = this.inputStreamFromBlob(sessionManager, lobReference, request, readLobContextBuilder);
                break;
            }
            case LT_BINARY_STREAM: {
                readLobContextBuilder.lobLength(Optional.empty());
                readLobContextBuilder.availableLength(Optional.empty());
                Object lobObj = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
                if (lobObj instanceof Blob) {
                    inputStream = this.inputStreamFromBlob(sessionManager, lobReference, request, readLobContextBuilder);
                } else if (lobObj instanceof InputStream) {
                    inputStream = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
                    inputStream.reset();//Might be a second read of the same stream, this guarantees that the position is at the start.
                    if (inputStream instanceof ByteArrayInputStream) {// Only used in SQL Server
                        ByteArrayInputStream bais = (ByteArrayInputStream) inputStream;
                        bais.reset();
                        readLobContextBuilder.lobLength(Optional.of((long) bais.available()));
                        readLobContextBuilder.availableLength(Optional.of(bais.available()));
                    }
                }
                break;
            }
            case LT_CLOB: {
                inputStream = this.inputStreamFromClob(sessionManager, lobReference, request, readLobContextBuilder);
                break;
            }
        }
        readLobContextBuilder.inputStream(inputStream);

        return readLobContextBuilder.build();
    }

    @SneakyThrows
    private InputStream inputStreamFromClob(SessionManager sessionManager, LobReference lobReference,
                                            ReadLobRequest request,
                                            ReadLobContext.ReadLobContextBuilder readLobContextBuilder) {
        Clob clob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = clob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength() :
                (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        Reader reader = clob.getCharacterStream(request.getPosition(), availableLength);
        return ReaderInputStream.builder()
                .setReader(reader)
                .setCharset(StandardCharsets.UTF_8)
                .getInputStream();
    }

    @SneakyThrows
    private InputStream inputStreamFromBlob(SessionManager sessionManager, LobReference lobReference,
                                            ReadLobRequest request,
                                            ReadLobContext.ReadLobContextBuilder readLobContextBuilder) {
        Blob blob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = blob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength() :
                (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        return blob.getBinaryStream(request.getPosition(), availableLength);
    }

    private int nextBlockSize(ReadLobContext readLobContext, long position) {

        //BinaryStreams do not have means to know the size of the lob like Blobs or Clobs.
        if (readLobContext.getAvailableLength().isEmpty() || readLobContext.getLobLength().isEmpty()) {
            return MAX_LOB_DATA_BLOCK_SIZE;
        }

        long lobLength = readLobContext.getLobLength().get();
        int length = readLobContext.getAvailableLength().get();

        //Single read situations
        int nextBlockSize = Math.min(MAX_LOB_DATA_BLOCK_SIZE, length);
        if ((int) lobLength == length && position == 1) {
            return length;
        }
        int nextPos = (int) (position + nextBlockSize);
        if (nextPos > lobLength) {
            nextBlockSize = Math.toIntExact(nextBlockSize - (nextPos - lobLength));
        } else if ((position + 1) % length == 0) {
            nextBlockSize = 0;
        }

        return nextBlockSize;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        try {
            log.info("Terminating session");
            this.sessionManager.terminateSession(sessionInfo);
            responseObserver.onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to terminate session: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");
        try {
            SessionInfo activeSessionInfo = sessionInfo;

            //Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                Connection conn = this.datasourceMap.get(sessionInfo.getConnHash()).getConnection();
                activeSessionInfo = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
            }
            Connection sessionConnection = sessionManager.getConnection(activeSessionInfo);
            //Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Commiting transaction");
        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.commit();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to commit transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Rollback transaction");
        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.rollback();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ROLLBACK)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to rollback transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        try {
            if (!request.hasSession()) {
                throw new SQLException("No active session.");
            }

            CallResourceResponse.Builder responseBuilder = CallResourceResponse.newBuilder();

            if (this.db2SpecialResultSetMetadata(request, responseObserver)) {
                return;
            }

            Object resource;
            switch (request.getResourceType()) {
                case RES_RESULT_SET:
                    resource = sessionManager.getResultSet(request.getSession(), request.getResourceUUID());
                    break;
                case RES_LOB:
                    resource = sessionManager.getLob(request.getSession(), request.getResourceUUID());
                    break;
                case RES_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    Statement statement = null;
                    if (!request.getResourceUUID().isBlank()) {
                        statement = sessionManager.getStatement(csDto.getSession(), request.getResourceUUID());
                    } else {
                        statement = csDto.getConnection().createStatement();
                        String uuid = sessionManager.registerStatement(csDto.getSession(), statement);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = statement;
                    break;
                }
                case RES_PREPARED_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    PreparedStatement ps = null;
                    if (!request.getResourceUUID().isBlank()) {
                        ps = sessionManager.getPreparedStatement(request.getSession(), request.getResourceUUID());
                    } else {
                        Map<String, Object> mapProperties = EMPTY_MAP;
                        if (!request.getProperties().isEmpty()) {
                            mapProperties = deserialize(request.getProperties().toByteArray(), Map.class);
                        }
                        ps = csDto.getConnection().prepareStatement((String) mapProperties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY));
                        String uuid = sessionManager.registerPreparedStatement(csDto.getSession(), ps);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = ps;
                    break;
                }
                case RES_CALLABLE_STATEMENT:
                    resource = sessionManager.getCallableStatement(request.getSession(), request.getResourceUUID());
                    break;
                case RES_CONNECTION: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    resource = csDto.getConnection();
                    break;
                }
                case RES_SAVEPOINT:
                    resource = sessionManager.getAttr(request.getSession(), request.getResourceUUID());
                    break;
                default:
                    throw new RuntimeException("Resource type invalid");
            }

            if (responseBuilder.getSession() == null || StringUtils.isBlank(responseBuilder.getSession().getSessionUUID())) {
                responseBuilder.setSession(request.getSession());
            }

            List<Object> paramsReceived = (request.getTarget().getParams().size() > 0) ?
                    deserialize(request.getTarget().getParams().toByteArray(), List.class) : EMPTY_LIST;
            Class<?> clazz = resource.getClass();
            if ((paramsReceived != null && paramsReceived.size() > 0) &&
                    ((CallType.CALL_RELEASE.equals(request.getTarget().getCallType()) &&
                            "Savepoint".equalsIgnoreCase(request.getTarget().getResourceName())) ||
                            (CallType.CALL_ROLLBACK.equals(request.getTarget().getCallType()))
                    )
            ) {
                Savepoint savepoint = (Savepoint) this.sessionManager.getAttr(request.getSession(),
                        (String) paramsReceived.get(0));
                paramsReceived.set(0, savepoint);
            }
            Method method = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazz),
                    MethodNameGenerator.methodName(request.getTarget()), paramsReceived);
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object resultFirstLevel = null;
            if (params != null && params.length > 0) {
                resultFirstLevel = method.invoke(resource, paramsReceived.toArray());
                if (resultFirstLevel instanceof CallableStatement) {
                    CallableStatement cs = (CallableStatement) resultFirstLevel;
                    resultFirstLevel = this.sessionManager.registerCallableStatement(responseBuilder.getSession(), cs);
                }
            } else {
                resultFirstLevel = method.invoke(resource);
                if (resultFirstLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultFirstLevel;
                    resultFirstLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                } else if (resultFirstLevel instanceof Array) {
                    Array array = (Array) resultFirstLevel;
                    String arrayUUID = UUID.randomUUID().toString();
                    this.sessionManager.registerAttr(responseBuilder.getSession(), arrayUUID, array);
                    resultFirstLevel = arrayUUID;
                }
            }
            if (resultFirstLevel instanceof Savepoint) {
                Savepoint sp = (Savepoint) resultFirstLevel;
                String uuid = UUID.randomUUID().toString();
                resultFirstLevel = uuid;
                this.sessionManager.registerAttr(responseBuilder.getSession(), uuid, sp);
            }
            if (request.getTarget().hasNextCall()) {
                //Second level calls, for cases like getMetadata().isAutoIncrement(int column)
                Class<?> clazzNext = resultFirstLevel.getClass();
                List<Object> paramsReceived2 = (request.getTarget().getNextCall().getParams().size() > 0) ?
                        deserialize(request.getTarget().getNextCall().getParams().toByteArray(), List.class) :
                        EMPTY_LIST;
                Method methodNext = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazzNext),
                        MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                        paramsReceived2);
                params = methodNext.getParameters();
                Object resultSecondLevel = null;
                if (params != null && params.length > 0) {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel, paramsReceived2.toArray());
                } else {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel);
                }
                if (resultSecondLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultSecondLevel;
                    resultSecondLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                }
                responseBuilder.setValues(ByteString.copyFrom(serialize(resultSecondLevel)));
            } else {
                responseBuilder.setValues(ByteString.copyFrom(serialize(resultFirstLevel)));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException) {
                SQLException sqlException = (SQLException) e.getTargetException();
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getTargetException().getMessage()),
                        responseObserver);
            }
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getMessage(), e), responseObserver);
        }
    }

    /**
     * As DB2 eagerly closes result sets in multiple situations the result set metadata is saved a priori in a session
     * attribute and has to be read in a special manner treated in this method.
     *
     * @param request
     * @param responseObserver
     * @return boolean
     * @throws SQLException
     */
    @SneakyThrows
    private boolean db2SpecialResultSetMetadata(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(this.dbNameMap.get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) this.sessionManager.getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParams().size() > 0) ?
                    deserialize(request.getTarget().getNextCall().getParams().toByteArray(), List.class) :
                    EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
            responseObserver.onNext(CallResourceResponse.newBuilder()
                    .setSession(request.getSession())
                    .setValues(ByteString.copyFrom(serialize(metadataResult)))
                    .build());
            responseObserver.onCompleted();
            return true;
        }
        return false;
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not get a fresh one from the data source.
     *
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other reason)
     */
    private ConnectionSessionDTO sessionConnection(SessionInfo sessionInfo, boolean startSessionIfNone) throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;
        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            conn = this.sessionManager.getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            // Get the datasource for this connection hash
            HikariDataSource dataSource = this.datasourceMap.get(sessionInfo.getConnHash());
            if (dataSource == null) {
                throw new SQLException("No datasource found for connection hash: " + sessionInfo.getConnHash());
            }
            
            try {
                // Use enhanced connection acquisition with timeout protection
                conn = ConnectionAcquisitionManager.acquireConnection(dataSource, sessionInfo.getConnHash());
                log.debug("Successfully acquired connection from pool for hash: {}", sessionInfo.getConnHash());
            } catch (SQLException e) {
                log.error("Failed to acquire connection from pool for hash: {}. Error: {}",
                    sessionInfo.getConnHash(), e.getMessage());
                
                // Re-throw the enhanced exception from ConnectionAcquisitionManager
                throw e;
            }
            
            if (startSessionIfNone) {
                SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                dtoBuilder.session(updatedSession);
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }

    private void handleResultSet(SessionInfo session, String resultSetUUID, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        ResultSet rs = this.sessionManager.getResultSet(session, resultSetUUID);
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder();
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }
        queryResultBuilder.labels(labels);

        List<Object[]> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        DbName dbName = DatabaseUtils.resolveDbName(rs.getStatement().getConnection().getMetaData().getURL());
        //Only used if result set contains LOBs in SQL Server and DB2 (if LOB's present), so cursor is not read in advance,
        // every row has to be requested by the jdbc client.
        String resultSetMode = "";
        boolean resultSetMetadataCollected = false;

        forEachRow:
        while (rs.next()) {
            if (DbName.DB2.equals(dbName) && !resultSetMetadataCollected) {
                this.collectResultSetMetadata(session, resultSetUUID, rs);
            }
            justSent = false;
            row++;
            Object[] rowValues = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue = null;
                //Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.VARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, dbNameMap);
                        } else {
                            currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i, INPUT_STREAM_TYPES);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, dbNameMap);
                        break;
                    }
                    case Types.CLOB: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        Clob clob = rs.getClob(i + 1);
                        if (clob == null) {
                            currentValue = null;
                        } else {
                            String clobUUID = UUID.randomUUID().toString();
                            //CLOB needs to be prefixed as per it can be read in the JDBC driver by getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            this.sessionManager.registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i, INPUT_STREAM_TYPES);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        currentValue = rs.getObject(i + 1);
                        //com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowValues[i] = currentValue;

            }
            results.add(rowValues);

            if ((DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                    && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
                break forEachRow;
            }

            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                //Send a block of records
                responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
                queryResultBuilder = OpQueryResult.builder();// Recreate the builder to not send labels in every block.
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            //Send a block of remaining records
            responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
        }

        responseObserver.onCompleted();

    }

    @SneakyThrows
    private void collectResultSetMetadata(SessionInfo session, String resultSetUUID, ResultSet rs) {
        this.sessionManager.registerAttr(session, RESULT_SET_METADATA_ATTR_PREFIX +
                resultSetUUID, new HydratedResultSetMetadata(rs.getMetaData()));
    }

    /**
     * Backward compatibility wrapper for configureHikariPool method.
     * This method was moved to ConnectionPoolConfigurer but tests still access it via reflection.
     * 
     * @param config The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     */
    private void configureHikariPool(HikariConfig config, ConnectionDetails connectionDetails) {
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);
    }


    // ===== XA Transaction Operations =====

    @Override
    public void xaStart(com.openjproxy.grpc.XaStartRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaStart: session={}, xid={}, flags={}", 
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().start(xid, request.getFlags());
            
            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA start successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaStart", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaEnd(com.openjproxy.grpc.XaEndRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaEnd: session={}, xid={}, flags={}", 
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().end(xid, request.getFlags());
            
            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA end successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaEnd", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaPrepare(com.openjproxy.grpc.XaPrepareRequest request, StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        log.debug("xaPrepare: session={}, xid={}", 
                request.getSession().getSessionUUID(), request.getXid());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            int result = session.getXaResource().prepare(xid);
            
            com.openjproxy.grpc.XaPrepareResponse response = com.openjproxy.grpc.XaPrepareResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setResult(result)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaPrepare", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaCommit(com.openjproxy.grpc.XaCommitRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaCommit: session={}, xid={}, onePhase={}", 
                request.getSession().getSessionUUID(), request.getXid(), request.getOnePhase());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().commit(xid, request.getOnePhase());
            
            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA commit successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaCommit", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaRollback(com.openjproxy.grpc.XaRollbackRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaRollback: session={}, xid={}", 
                request.getSession().getSessionUUID(), request.getXid());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().rollback(xid);
            
            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA rollback successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRollback", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaRecover(com.openjproxy.grpc.XaRecoverRequest request, StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        log.debug("xaRecover: session={}, flag={}", 
                request.getSession().getSessionUUID(), request.getFlag());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid[] xids = session.getXaResource().recover(request.getFlag());
            
            com.openjproxy.grpc.XaRecoverResponse.Builder responseBuilder = com.openjproxy.grpc.XaRecoverResponse.newBuilder()
                    .setSession(session.getSessionInfo());
            
            for (javax.transaction.xa.Xid xid : xids) {
                responseBuilder.addXids(convertXidToProto(xid));
            }
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRecover", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaForget(com.openjproxy.grpc.XaForgetRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaForget: session={}, xid={}", 
                request.getSession().getSessionUUID(), request.getXid());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().forget(xid);
            
            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA forget successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaForget", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request, 
                                        StreamObserver<com.openjproxy.grpc.XaSetTransactionTimeoutResponse> responseObserver) {
        log.debug("xaSetTransactionTimeout: session={}, seconds={}", 
                request.getSession().getSessionUUID(), request.getSeconds());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            boolean success = session.getXaResource().setTransactionTimeout(request.getSeconds());
            if (success) {
                session.setTransactionTimeout(request.getSeconds());
            }
            
            com.openjproxy.grpc.XaSetTransactionTimeoutResponse response = 
                    com.openjproxy.grpc.XaSetTransactionTimeoutResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(success)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaSetTransactionTimeout", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request, 
                                        StreamObserver<com.openjproxy.grpc.XaGetTransactionTimeoutResponse> responseObserver) {
        log.debug("xaGetTransactionTimeout: session={}", request.getSession().getSessionUUID());
        
        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }
            
            int timeout = session.getXaResource().getTransactionTimeout();
            
            com.openjproxy.grpc.XaGetTransactionTimeoutResponse response = 
                    com.openjproxy.grpc.XaGetTransactionTimeoutResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSeconds(timeout)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaGetTransactionTimeout", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request, 
                           StreamObserver<com.openjproxy.grpc.XaIsSameRMResponse> responseObserver) {
        log.debug("xaIsSameRM: session1={}, session2={}", 
                request.getSession1().getSessionUUID(), request.getSession2().getSessionUUID());
        
        try {
            Session session1 = sessionManager.getSession(request.getSession1());
            Session session2 = sessionManager.getSession(request.getSession2());
            
            if (session1 == null || !session1.isXA() || session1.getXaResource() == null) {
                throw new SQLException("Session1 is not an XA session");
            }
            if (session2 == null || !session2.isXA() || session2.getXaResource() == null) {
                throw new SQLException("Session2 is not an XA session");
            }
            
            boolean isSame = session1.getXaResource().isSameRM(session2.getXaResource());
            
            com.openjproxy.grpc.XaIsSameRMResponse response = com.openjproxy.grpc.XaIsSameRMResponse.newBuilder()
                    .setIsSame(isSame)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaIsSameRM", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    /**
     * Convert protobuf Xid to javax.transaction.xa.Xid.
     */
    private javax.transaction.xa.Xid convertXid(com.openjproxy.grpc.XidProto xidProto) {
        return new XidImpl(
                xidProto.getFormatId(),
                xidProto.getGlobalTransactionId().toByteArray(),
                xidProto.getBranchQualifier().toByteArray()
        );
    }

    /**
     * Convert javax.transaction.xa.Xid to protobuf Xid.
     */
    private com.openjproxy.grpc.XidProto convertXidToProto(javax.transaction.xa.Xid xid) {
        return com.openjproxy.grpc.XidProto.newBuilder()
                .setFormatId(xid.getFormatId())
                .setGlobalTransactionId(com.google.protobuf.ByteString.copyFrom(xid.getGlobalTransactionId()))
                .setBranchQualifier(com.google.protobuf.ByteString.copyFrom(xid.getBranchQualifier()))
                .build();
    }
}
