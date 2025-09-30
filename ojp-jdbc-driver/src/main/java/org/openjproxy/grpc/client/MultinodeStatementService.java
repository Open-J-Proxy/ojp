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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;
import org.openjproxy.jdbc.LobGrpcIterator;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openjproxy.grpc.SerializationHandler.serialize;

/**
 * Multinode-aware implementation of StatementService that routes requests
 * to multiple OJP servers with session stickiness and failover support.
 */
@Slf4j
public class MultinodeStatementService implements StatementService {

    private final MultinodeConnectionManager connectionManager;

    public MultinodeStatementService(List<ServerEndpoint> serverEndpoints) {
        this.connectionManager = new MultinodeConnectionManager(serverEndpoints);
    }

    public MultinodeStatementService(List<ServerEndpoint> serverEndpoints,
                                   int retryAttempts, long retryDelayMs) {
        this.connectionManager = new MultinodeConnectionManager(serverEndpoints, retryAttempts, retryDelayMs);
    }

    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        // Create enhanced connection details with server list information
        ConnectionDetails enhancedDetails = enhanceConnectionDetailsWithServerList(connectionDetails);
        return connectionManager.connect(enhancedDetails);
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  Map<String, Object> properties) throws SQLException {
        return executeUpdate(sessionInfo, sql, params, "", properties);
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                String statementUUID, Map<String, Object> properties) throws SQLException {
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            StatementRequest.Builder builder = StatementRequest.newBuilder();
            if (properties != null) {
                builder.setProperties(ByteString.copyFrom(serialize(properties)));
            }

            return channelAndStub.blockingStub.executeUpdate(builder
                    .setSession(sessionInfo)
                    .setStatementUUID(statementUUID != null ? statementUUID : "")
                    .setSql(sql)
                    .setParameters(ByteString.copyFrom(serialize(params)))
                    .build());

        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        }
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                          Map<String, Object> properties) throws SQLException {
        return executeQuery(sessionInfo, sql, params, "", properties);
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                         String statementUUID, Map<String, Object> properties) throws SQLException {
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            StatementRequest.Builder builder = StatementRequest.newBuilder();
            if (properties != null) {
                builder.setProperties(ByteString.copyFrom(serialize(properties)));
            }

            return channelAndStub.blockingStub.executeQuery(builder
                    .setStatementUUID(statementUUID != null ? statementUUID : "")
                    .setSession(sessionInfo)
                    .setSql(sql)
                    .setParameters(ByteString.copyFrom(serialize(params)))
                    .build());

        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        }
    }

    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            return channelAndStub.blockingStub.fetchNextRows(
                    ResultSetFetchRequest.newBuilder()
                            .setSession(sessionInfo)
                            .setResultSetUUID(resultSetUUID)
                            .setSize(size)
                            .build()
            );
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        }
    }

    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        SessionInfo sessionInfo = connection.getSession();
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            log.info("Creating new lob on server {}", server.getAddress());

            SettableFuture<LobReference> sfFirstLobReference = SettableFuture.create();
            SettableFuture<LobReference> sfFinalLobReference = SettableFuture.create();

            StreamObserver<LobDataBlock> lobDataBlockStream = channelAndStub.asyncStub.createLob(
                    new ServerCallStreamObserver<>() {
                        private final AtomicBoolean abFirstResponseReceived = new AtomicBoolean(true);
                        private LobReference lobReference;

                        @Override
                        public boolean isCancelled() { return false; }

                        @Override
                        public void setOnCancelHandler(Runnable runnable) {}

                        @Override
                        public void setCompression(String s) {}

                        @Override
                        public boolean isReady() { return false; }

                        @Override
                        public void setOnReadyHandler(Runnable runnable) {}

                        @Override
                        public void request(int i) {}

                        @Override
                        public void setMessageCompression(boolean b) {}

                        @Override
                        public void disableAutoInboundFlowControl() {}

                        @Override
                        public void onNext(LobReference lobReference) {
                            log.debug("Lob reference received from server {}", server.getAddress());
                            if (this.abFirstResponseReceived.get()) {
                                sfFirstLobReference.set(lobReference);
                                log.debug("First lob reference trigger");
                            }
                            this.lobReference = lobReference;
                            connection.setSession(lobReference.getSession());
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            if (throwable instanceof StatusRuntimeException) {
                                try {
                                    StatusRuntimeException sre = (StatusRuntimeException) throwable;
                                    GrpcExceptionHandler.handle(sre);
                                    sfFirstLobReference.setException(sre);
                                    sfFinalLobReference.setException(sre);
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
                            log.debug("Final lob reference received from server {}", server.getAddress());
                            sfFinalLobReference.set(this.lobReference);
                            log.debug("Final lob reference notified");
                        }
                    }
            );

            boolean firstBlockProcessedSuccessfully = false;
            while (lobDataBlock.hasNext()) {
                lobDataBlockStream.onNext(lobDataBlock.next());
                if (!firstBlockProcessedSuccessfully) {
                    log.debug("Waiting first lob reference arrival from server {}", server.getAddress());
                    sfFirstLobReference.get();
                    log.debug("First lob reference arrived from server {}", server.getAddress());
                    firstBlockProcessedSuccessfully = true;
                }
            }
            lobDataBlockStream.onCompleted();

            log.debug("Waiting for final lob ref from server {}", server.getAddress());
            LobReference finalLobRef = sfFinalLobReference.get();
            log.debug("Final lob ref received from server {}", server.getAddress());
            return finalLobRef;

        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to write LOB: " + e.getMessage(), e);
        }
    }

    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        SessionInfo sessionInfo = lobReference.getSession();
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            LobGrpcIterator lobGrpcIterator = new LobGrpcIterator();
            SettableFuture<Boolean> sfFirstBlockReceived = SettableFuture.create();
            ReadLobRequest readLobRequest = ReadLobRequest.newBuilder()
                    .setLobReference(lobReference)
                    .setPosition(pos)
                    .setLength(length)
                    .build();

            final Throwable[] errorReceived = {null};

            channelAndStub.asyncStub.readLob(readLobRequest, new ServerCallStreamObserver<LobDataBlock>() {
                private final AtomicBoolean abFirstResponseReceived = new AtomicBoolean(true);

                @Override
                public boolean isCancelled() { return false; }

                @Override
                public void setOnCancelHandler(Runnable runnable) {}

                @Override
                public void setCompression(String s) {}

                @Override
                public boolean isReady() { return false; }

                @Override
                public void setOnReadyHandler(Runnable runnable) {}

                @Override
                public void request(int i) {}

                @Override
                public void setMessageCompression(boolean b) {}

                @Override
                public void disableAutoInboundFlowControl() {}

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

            if (!sfFirstBlockReceived.get() && errorReceived[0] != null) {
                if (errorReceived[0] instanceof Exception) {
                    throw (Exception) errorReceived[0];
                } else {
                    throw new RuntimeException(errorReceived[0]);
                }
            }

            return lobGrpcIterator;

        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to read LOB: " + e.getMessage(), e);
        }
    }

    @Override
    public void terminateSession(SessionInfo session) {
        connectionManager.terminateSession(session);

        ServerEndpoint server = connectionManager.getServerForSession(session);
        if (server != null) {
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);
            if (channelAndStub != null) {
                channelAndStub.asyncStub.terminateSession(session, new ServerCallStreamObserver<>() {
                    @Override
                    public boolean isCancelled() { return false; }

                    @Override
                    public void setOnCancelHandler(Runnable runnable) {}

                    @Override
                    public void setCompression(String s) {}

                    @Override
                    public boolean isReady() { return false; }

                    @Override
                    public void setOnReadyHandler(Runnable runnable) {}

                    @Override
                    public void request(int i) {}

                    @Override
                    public void setMessageCompression(boolean b) {}

                    @Override
                    public void disableAutoInboundFlowControl() {}

                    @Override
                    public void onNext(SessionTerminationStatus sessionTerminationStatus) {}

                    @Override
                    public void onError(Throwable throwable) {
                        Throwable t = throwable;
                        if (throwable instanceof StatusRuntimeException) {
                            try {
                                GrpcExceptionHandler.handle((StatusRuntimeException) throwable);
                            } catch (SQLException e) {
                                t = e;
                            }
                        }
                        log.error("Error while terminating session on server {}: {}",
                                server.getAddress(), t.getMessage(), t);
                    }

                    @Override
                    public void onCompleted() {}
                });
            }
        }
    }

    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        return executeTransactionOperation(session, "startTransaction",
                (channelAndStub) -> channelAndStub.blockingStub.startTransaction(session));
    }

    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        return executeTransactionOperation(session, "commitTransaction",
                (channelAndStub) -> channelAndStub.blockingStub.commitTransaction(session));
    }

    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        return executeTransactionOperation(session, "rollbackTransaction",
                (channelAndStub) -> channelAndStub.blockingStub.rollbackTransaction(session));
    }

    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            return channelAndStub.blockingStub.callResource(request);
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to call resource: " + e.getMessage(), e);
        }
    }

    private SessionInfo executeTransactionOperation(SessionInfo session, String operationName,
                                                  TransactionOperation operation) throws SQLException {
        ServerEndpoint server = connectionManager.getServerForSession(session);
        if (server == null) {
            throw new SQLException("No healthy servers available for request");
        }

        MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(server);
        if (channelAndStub == null) {
            throw new SQLException("Unable to get connection to server: " + server.getAddress());
        }

        try {
            return operation.execute(channelAndStub);
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionHandler.handle(e);
        } catch (Exception e) {
            throw new SQLException("Unable to " + operationName + ": " + e.getMessage(), e);
        }
    }

    private ConnectionDetails enhanceConnectionDetailsWithServerList(ConnectionDetails original) {
        // Create a comma-separated list of all known servers
        String serverList = MultinodeUrlParser.formatServerList(connectionManager.getServerEndpoints());

        // For now, we'll add this as a property that the server can read
        // In the future, we might want to extend the protobuf definition
        log.debug("Enhanced connection details with server list: {}", serverList);

        return original; // Return original for now, enhancement can be added later
    }

    @FunctionalInterface
    private interface TransactionOperation {
        SessionInfo execute(MultinodeConnectionManager.ChannelAndStub channelAndStub) throws StatusRuntimeException;
    }
}
