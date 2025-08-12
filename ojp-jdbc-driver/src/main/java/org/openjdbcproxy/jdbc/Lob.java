package org.openjdbcproxy.jdbc;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.CallResourceRequest;
import com.openjdbcproxy.grpc.CallResourceResponse;
import com.openjdbcproxy.grpc.CallType;
import com.openjdbcproxy.grpc.LobDataBlock;
import com.openjdbcproxy.grpc.LobReference;
import com.openjdbcproxy.grpc.LobType;
import com.openjdbcproxy.grpc.ResourceType;
import com.openjdbcproxy.grpc.TargetCall;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.grpc.client.StatementService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.openjdbcproxy.constants.CommonConstants.MAX_LOB_DATA_BLOCK_SIZE;
import static org.openjdbcproxy.grpc.SerializationHandler.deserialize;
import static org.openjdbcproxy.grpc.SerializationHandler.serialize;
import static org.openjdbcproxy.grpc.client.GrpcExceptionHandler.handle;

@Slf4j
public class Lob {

    protected final Connection connection;
    protected final LobService lobService;
    protected final StatementService statementService;
    @Getter
    protected final SettableFuture<LobReference> lobReference = SettableFuture.create();

    public Lob(Connection connection, LobService lobService, StatementService statementService, LobReference lobReference) {
        log.debug("Lob constructor called");
        this.connection = connection;
        this.lobService = lobService;
        this.statementService = statementService;
        if (lobReference != null) {
            this.lobReference.set(lobReference);
        }
    }

    @SneakyThrows
    public String getUUID() {
        log.debug("getUUID called");
        return (this.lobReference != null) ? this.lobReference.get().getUuid() : null;
    }

    public long length() throws SQLException {
        log.debug("length called");
        return this.callProxy(CallType.CALL_LENGTH, "", Long.class);
    }

    protected OutputStream setBinaryStream(LobType lobType, long pos) {
        log.debug("setBinaryStream called: {}, {}", lobType, pos);
        try {
            //connect the pipes. Makes the OutputStream written by the caller feed into the InputStream read by the sender.
            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);

            CompletableFuture.supplyAsync(() -> {
                try {
                    this.lobReference.set(this.lobService.sendBytes(lobType, pos, in));
                } catch (SQLException e) {
                    log.error("SQLException in setBinaryStream async - sendBytes", e);
                    // Set the exception on the future to ensure it's propagated
                    this.lobReference.setException(e);
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    log.error("Unexpected exception in setBinaryStream async - sendBytes", e);
                    // Set the exception on the future to ensure it's propagated
                    this.lobReference.setException(e);
                    throw new RuntimeException(e);
                }
                //Refresh Session object.
                try {
                    this.connection.setSession(this.lobReference.get().getSession());
                } catch (InterruptedException e) {
                    log.error("InterruptedException in setBinaryStream async - setSession", e);
                    this.lobReference.setException(e);
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    log.error("ExecutionException in setBinaryStream async - setSession", e);
                    this.lobReference.setException(e);
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    log.error("Unexpected exception in setBinaryStream async - setSession", e);
                    this.lobReference.setException(e);
                    throw new RuntimeException(e);
                }
                return null;
            });

            return out;
        } catch (Exception e) {
            log.error("Exception in setBinaryStream", e);
            throw new RuntimeException(e);
        }
    }

    protected LobReference sendBinaryStream(LobType lobType, InputStream inputStream, Map<Integer, Object> metadata) {
        log.debug("sendBinaryStream called: {}, <InputStream>, <metadata>", lobType);
        try {
            try {
                this.lobReference.set(this.lobService.sendBytes(lobType, 1, inputStream, metadata));
            } catch (SQLException e) {
                log.error("SQLException in sendBinaryStream - sendBytes", e);
                throw new RuntimeException(e);
            }
            //Refresh Session object. Will wait until lobReference is set to progress.
            this.connection.setSession(this.lobReference.get().getSession());
            return this.lobReference.get();
        } catch (Exception e) {
            log.error("Exception in sendBinaryStream", e);
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    protected void haveLobReferenceValidation() throws SQLException {
        log.debug("haveLobReferenceValidation called");
        if (this.lobReference.get() == null) {
            log.error("No reference to a LOB object found.");
            throw new SQLException("No reference to a LOB object found.");
        }
    }

    protected InputStream getBinaryStream(long pos, long length) throws SQLException {
        log.debug("getBinaryStream called: {}, {}", pos, length);
        try {
            this.haveLobReferenceValidation();

            return new InputStream() {
                private InputStream currentBlockInputStream;
                private long currentPos = pos - 1;//minus 1 because it will increment it in the loop

                @SneakyThrows
                @Override
                public int read() throws IOException {
                    int currentByte = this.currentBlockInputStream != null ? this.currentBlockInputStream.read() : -1;
                    int TWO_BLOCKS_SIZE = 2 * MAX_LOB_DATA_BLOCK_SIZE;
                    boolean lastBlockReached = (currentByte == -1 && currentPos > 1 && currentPos % TWO_BLOCKS_SIZE != 0);
                    if (currentByte != -1) {
                        currentPos++;
                    }

                    if ((currentBlockInputStream == null || currentByte == -1) && !lastBlockReached) {
                        //Read next 2 blocks
                        Iterator<LobDataBlock> dataBlocks = null;
                        try {
                            dataBlocks = statementService.readLob(lobReference.get(), currentPos + 1, TWO_BLOCKS_SIZE);
                            this.currentBlockInputStream = lobService.parseReceivedBlocks(dataBlocks);
                            if (currentBlockInputStream == null) {
                                return -1;
                            }
                            currentByte = this.currentBlockInputStream.read();
                            currentPos++;
                        } catch (SQLException e) {
                            log.error("SQLException in getBinaryStream InputStream.read() - readLob/parseReceivedBlocks", e);
                            throw new RuntimeException(e);
                        } catch (StatusRuntimeException e) {
                            try {
                                throw handle(e);
                            } catch (SQLException ex) {
                                log.error("SQLException in handle(StatusRuntimeException)", ex);
                                throw new RuntimeException(ex);
                            }
                        } catch (Exception e) {
                            log.error("Exception in getBinaryStream InputStream.read()", e);
                            throw new RuntimeException(e);
                        }
                    }

                    if (currentPos >= length) {
                        return -1;//Finish stream if reached the length required
                    }

                    return currentByte;
                }
            };
        } catch (SQLException e) {
            log.error("SQLException in getBinaryStream", e);
            throw e;
        } catch (StatusRuntimeException e) {
            log.error("StatusRuntimeException in getBinaryStream", e);
            throw handle(e);
        } catch (Exception e) {
            log.error("Exception in getBinaryStream", e);
            throw new SQLException("Unable to read all bytes from LOB object: " + e.getMessage(), e);
        }
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_LOB)
                .setResourceUUID(this.getUUID());
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
                        .setParams(ByteString.copyFrom(serialize(params)))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());
        if (Void.class.equals(returnType)) {
            return null;
        }
        return (T) deserialize(response.getValues().toByteArray(), returnType);
    }
}