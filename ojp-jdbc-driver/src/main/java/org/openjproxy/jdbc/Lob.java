package org.openjproxy.jdbc;

import com.google.common.util.concurrent.SettableFuture;
import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;

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
        return this.lobReference.get().getUuid();
    }

    public long length() throws SQLException {
        log.debug("length called");
        return this.callProxy();
    }

    protected OutputStream setBinaryStream(LobType lobType, long pos) {
        log.debug("setBinaryStream called: {}, {}", lobType, pos);
        try {
            //connect the pipes. Makes the OutputStream written by the caller feed into the InputStream read by the sender.
            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);

            CompletableFuture.supplyAsync(() -> sendBinaryStreamAsync(lobType, pos, in));

            return out;
        } catch (Exception e) {
            log.error("Exception in setBinaryStream", e);
            throw new RuntimeException(e);
        }
    }

    private Void sendBinaryStreamAsync(LobType lobType, long pos, PipedInputStream in) {
        sendBytesForBinaryStream(lobType, pos, in);
        refreshSessionFromLobReference();
        return null;
    }

    private void sendBytesForBinaryStream(LobType lobType, long pos, PipedInputStream in) {
        try {
            this.lobReference.set(this.lobService.sendBytes(lobType, pos, in));
        } catch (SQLException e) {
            log.error("SQLException in setBinaryStream async - sendBytes", e);
            this.lobReference.setException(e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Unexpected exception in setBinaryStream async - sendBytes", e);
            this.lobReference.setException(e);
            throw new RuntimeException(e);
        }
    }

    private void refreshSessionFromLobReference() {
        try {
            this.connection.setSession(this.lobReference.get().getSession());
        } catch (InterruptedException e) {
            log.error("InterruptedException in setBinaryStream async - setSession", e);
            Thread.currentThread().interrupt();
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
    }

    protected LobReference sendBinaryStream(LobType lobType, InputStream inputStream, Map<Integer, Object> metadata) {
        log.debug("sendBinaryStream called: {}, <InputStream>, <metadata>", lobType);
        try {
            setLobReferenceFromStream(lobType, inputStream, metadata);
            //Refresh Session object. Will wait until lobReference is set to progress.
            this.connection.setSession(this.lobReference.get().getSession());
            return this.lobReference.get();
        } catch (InterruptedException e) {
            log.error("InterruptedException in sendBinaryStream", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Exception in sendBinaryStream", e);
            throw new RuntimeException(e);
        }
    }

    private void setLobReferenceFromStream(LobType lobType, InputStream inputStream, Map<Integer, Object> metadata) {
        try {
            this.lobReference.set(this.lobService.sendBytes(lobType, 1, inputStream, metadata));
        } catch (SQLException e) {
            log.error("SQLException in sendBinaryStream - sendBytes", e);
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

            // In hydrated approach, request all the LOB data at once
            // The server will return the complete LOB data in a single response
            Iterator<LobDataBlock> dataBlocks = statementService.readLob(lobReference.get(), pos, (int) length);
            InputStream fullDataStream = lobService.parseReceivedBlocks(dataBlocks);

            // Return empty stream
            return Objects.requireNonNullElseGet(fullDataStream, () -> new ByteArrayInputStream(new byte[0]));

        } catch (SQLException e) {
            log.error("SQLException in getBinaryStream", e);
            throw e;
        } catch (StatusRuntimeException e) {
            log.error("StatusRuntimeException in getBinaryStream", e);
            throw handle(e);
        } catch (InterruptedException e) {
            log.error("InterruptedException in getBinaryStream", e);
            Thread.currentThread().interrupt();
            throw new SQLException("Unable to read all bytes from LOB object: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Exception in getBinaryStream", e);
            throw new SQLException("Unable to read all bytes from LOB object: " + e.getMessage(), e);
        }
    }

    private CallResourceRequest.Builder newCallBuilder() {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_LOB)
                .setResourceUUID(this.getUUID());
    }

    private <T> T callProxy() throws SQLException {
        log.debug("callProxy: {}, {}, {}", CallType.CALL_LENGTH, "", Long.class);
        return this.callProxy(CallType.CALL_LENGTH, "", Long.class, Constants.EMPTY_OBJECT_LIST);
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
        this.connection.setSession(response.getSession());
        if (Void.class.equals(returnType)) {
            return null;
        }

        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
