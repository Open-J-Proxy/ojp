package org.openjproxy.grpc.client;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;
import org.openjproxy.jdbc.LobGrpcIterator;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openjproxy.grpc.SerializationHandler.serialize;
import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;

/**
 * Interacts with the GRPC client stub and handles exceptions.
 */
@Slf4j
public class StatementServiceGrpcClient implements StatementService {

    private static final String DEFAULT_HOST = "localhost";
    private static final String DNS_PREFIX = "dns:///";
    private static final String COLON = ":";
    private final Pattern pattern = Pattern.compile(CommonConstants.OJP_REGEX_PATTERN);

    private StatementServiceGrpc.StatementServiceBlockingStub statemetServiceBlockingStub;
    private StatementServiceGrpc.StatementServiceStub statemetServiceStub;

    public StatementServiceGrpcClient() {
    }

    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        this.grpcChannelOpenAndStubsInitialized(connectionDetails.getUrl());
        try {
            return this.statemetServiceBlockingStub.connect(connectionDetails);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        }
    }

    private void grpcChannelOpenAndStubsInitialized(String url) {
        if (this.statemetServiceStub == null && this.statemetServiceBlockingStub == null) {
            Matcher matcher = pattern.matcher(url);
            String host = DEFAULT_HOST;
            int port = CommonConstants.DEFAULT_PORT_NUMBER;

            if (matcher.find()) {
                String hostPort = matcher.group(1);
                String[] hostPortSplit = hostPort.split(":");
                host = hostPortSplit[0];
                port = Integer.parseInt(hostPortSplit[1]);
            } else {
                throw new RuntimeException("Invalid OJP host or port.");
            }

            //Once channel is open it remains open and is shared among all requests.
            String target = DNS_PREFIX + host + COLON + port;
            ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                    .usePlaintext()
                    .build();

            this.statemetServiceBlockingStub = StatementServiceGrpc.newBlockingStub(channel);
            this.statemetServiceStub = StatementServiceGrpc.newStub(channel);
        }
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  Map<String, Object> properties) throws SQLException {
        return this.executeUpdate(sessionInfo, sql, params, "", properties);
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params, String statementUUID,
                                  Map<String, Object> properties)
            throws SQLException {
        try {
            StatementRequest.Builder builder = StatementRequest.newBuilder();
            if (properties != null) {
                builder.setProperties(ByteString.copyFrom(serialize(properties)));
            }
            return this.statemetServiceBlockingStub.executeUpdate(builder
                    .setSession(sessionInfo)
                    .setStatementUUID(statementUUID != null ? statementUUID : "")
                    .setSql(sql)
                    .setParameters(ByteString.copyFrom(serialize(params)))
                    .build());
        } catch (StatusRuntimeException e) {
            throw handle(e);
        }
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           Map<String, Object> properties) throws SQLException {
        return this.executeQuery(sessionInfo, sql, params, "", properties);
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params, String statementUUID,
                                           Map<String, Object> properties) throws SQLException {
        try {
            StatementRequest.Builder builder = StatementRequest.newBuilder();
            if (properties != null) {
                builder.setProperties(ByteString.copyFrom(serialize(properties)));
            }
            return this.statemetServiceBlockingStub.executeQuery(builder
                    .setStatementUUID(statementUUID != null ? statementUUID : "")
                    .setSession(sessionInfo).setSql(sql).setParameters(ByteString.copyFrom(serialize(params))).build());
        } catch (StatusRuntimeException e) {
            throw handle(e);
        }
    }

    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.fetchNextRows(
                    ResultSetFetchRequest.newBuilder()
                            .setSession(sessionInfo)
                            .setResultSetUUID(resultSetUUID)
                            .setSize(size)
                            .build()
            );
        } catch (StatusRuntimeException e) {
            throw handle(e);
        }
    }

    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        try {
            log.info("Creating new lob");
            //Indicates that the server acquired a connection to the DB and wrote the first block successfully.
            SettableFuture<LobReference> sfFirstLobReference = SettableFuture.create();
            //Indicates that the server has finished writing the last block successfully.
            SettableFuture<LobReference> sfFinalLobReference = SettableFuture.create();

            StreamObserver<LobDataBlock> lobDataBlockStream = this.statemetServiceStub.createLob(
                    new ServerCallStreamObserver<>() {
                        private final AtomicBoolean abFirstResponseReceived = new AtomicBoolean(true);
                        private LobReference lobReference;

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
                        public void onNext(LobReference lobReference) {
                            log.debug("Lob reference received");
                            if (this.abFirstResponseReceived.get()) {
                                sfFirstLobReference.set(lobReference);
                                log.debug("First lob reference trigger");
                            }
                            this.lobReference = lobReference;
                            //Update connection session on first confirmation to get the session id if session is new.
                            connection.setSession(lobReference.getSession());
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            if (throwable instanceof StatusRuntimeException) {
                                try {
                                    StatusRuntimeException sre = (StatusRuntimeException) throwable;
                                    handle(sre);//To convert to SQLException if possible
                                    sfFirstLobReference.setException(sre);
                                    sfFinalLobReference.setException(sre); //When conversion to SQLException not possible
                                } catch (SQLException e) {
                                    sfFirstLobReference.setException(e);
                                    sfFinalLobReference.setException(e);
                                }
                            } else {
                                sfFirstLobReference.setException(throwable);
                                sfFinalLobReference.setException(throwable);
                            }
                        }

                        @Override
                        public void onCompleted() {
                            log.debug("Final lob reference received");
                            sfFinalLobReference.set(this.lobReference);
                            log.debug("Final lob reference notified");
                        }
                    }
            );

            //Send all data blocks one by one only after server finished consuming the previous block
            boolean firstBlockProcessedSuccessfully = false;
            while (lobDataBlock.hasNext()) {
                lobDataBlockStream.onNext(lobDataBlock.next());
                if (!firstBlockProcessedSuccessfully) {
                    //Wait first block to be processed by the server to avoid sending more data before the server actually acquired a connection and wrote the first block.
                    log.debug("Waiting first lob reference arrival");
                    sfFirstLobReference.get();
                    log.debug("First lob reference arrived");
                    firstBlockProcessedSuccessfully = true;
                }
            }
            lobDataBlockStream.onCompleted();

            log.debug("Waiting for final lob ref");
            LobReference finalLobRef = sfFinalLobReference.get();
            log.debug("Final lob ref received");
            return finalLobRef;
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to write LOB: " + e.getMessage(), e);
        }

    }

    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        try {
            LobGrpcIterator lobGrpcIterator = new LobGrpcIterator();
            SettableFuture<Boolean> sfFirstBlockReceived = SettableFuture.create();
            ReadLobRequest readLobRequest = ReadLobRequest.newBuilder()
                    .setLobReference(lobReference)
                    .setPosition(pos)
                    .setLength(length)
                    .build();

            final Throwable[] errorReceived = {null};

            this.statemetServiceStub.readLob(readLobRequest, new ServerCallStreamObserver<LobDataBlock>() {
                private final AtomicBoolean abFirstResponseReceived = new AtomicBoolean(true);

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
                    lobGrpcIterator.addBlock(lobDataBlock);
                    if (abFirstResponseReceived.get()) {
                        sfFirstBlockReceived.set(true);
                        abFirstResponseReceived.set(false);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorReceived[0] = throwable;
                    lobGrpcIterator.setError(throwable);
                    sfFirstBlockReceived.set(false);
                }

                @Override
                public void onCompleted() {
                    lobGrpcIterator.finished();
                }
            });

            //Wait to receive at least one successful block before returning.
            if (!sfFirstBlockReceived.get() && errorReceived[0] != null) {
                if (errorReceived[0] instanceof Exception) {
                    throw (Exception) errorReceived[0];
                } else {
                    throw new RuntimeException(errorReceived[0]);
                }
            }

            return lobGrpcIterator;
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to write LOB: " + e.getMessage(), e);
        }
    }

    @Override
    public void terminateSession(SessionInfo session) {
        //Fire and forget - done async intentionally to improve client performance.
        this.statemetServiceStub.terminateSession(session, new ServerCallStreamObserver<>() {
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
            public void onNext(SessionTerminationStatus sessionTerminationStatus) {
            }

            @Override
            public void onError(Throwable throwable) {
                Throwable t = throwable;
                if (throwable instanceof StatusRuntimeException) {
                    try {
                        handle((StatusRuntimeException) throwable);
                    } catch (SQLException e) {
                        t = e;
                    }
                }
                log.error("Error while terminating session: " + t.getMessage(), t);
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.startTransaction(session);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to start a new transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.commitTransaction(session);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to commit transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.rollbackTransaction(session);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to rollback transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.callResource(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to call resource: " + e.getMessage(), e);
        }
    }

    // XA Transaction Operations
    @Override
    public com.openjproxy.grpc.XaResponse xaStart(com.openjproxy.grpc.XaStartRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaStart(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to start XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaEnd(com.openjproxy.grpc.XaEndRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaEnd(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to end XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaPrepareResponse xaPrepare(com.openjproxy.grpc.XaPrepareRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaPrepare(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to prepare XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaCommit(com.openjproxy.grpc.XaCommitRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaCommit(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to commit XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaRollback(com.openjproxy.grpc.XaRollbackRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaRollback(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to rollback XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaRecoverResponse xaRecover(com.openjproxy.grpc.XaRecoverRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaRecover(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to recover XA transactions: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaForget(com.openjproxy.grpc.XaForgetRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaForget(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to forget XA transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaSetTransactionTimeoutResponse xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaSetTransactionTimeout(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to set XA transaction timeout: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaGetTransactionTimeoutResponse xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaGetTransactionTimeout(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to get XA transaction timeout: " + e.getMessage(), e);
        }
    }

    @Override
    public com.openjproxy.grpc.XaIsSameRMResponse xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request) throws SQLException {
        try {
            return this.statemetServiceBlockingStub.xaIsSameRM(request);
        } catch (StatusRuntimeException e) {
            throw handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to check XA resource manager: " + e.getMessage(), e);
        }
    }
}
