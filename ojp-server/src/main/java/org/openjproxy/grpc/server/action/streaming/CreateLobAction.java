package org.openjproxy.grpc.server.action.streaming;

import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.LobDataBlocksInputStream;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.StreamingAction;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

/**
 * Action for creating Large Objects (LOBs) via bidirectional streaming.
 * <p>
 * This action handles the creation of BLOBs, CLOBs, and binary streams by
 * receiving
 * data blocks from the client and writing them to the appropriate LOB type.
 * <p>
 * The action implements the singleton pattern as required by the
 * StreamingAction interface.
 *
 * @author OpenJProxy
 * @since 1.0
 */
@Slf4j
public class CreateLobAction implements StreamingAction<LobDataBlock, LobReference> {

    private static final CreateLobAction INSTANCE = new CreateLobAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private CreateLobAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of CreateLobAction.
     *
     * @return the singleton instance
     */
    public static CreateLobAction getInstance() {
        return INSTANCE;
    }

    @Override
    public StreamObserver<LobDataBlock> execute(ActionContext context, StreamObserver<LobReference> responseObserver) {
        log.debug("Creating LOB");
        return new LobStreamObserver(context, responseObserver);
    }

    /**
     * Internal StreamObserver implementation that handles the LOB creation stream.
     */
    private static class LobStreamObserver implements StreamObserver<LobDataBlock> {
        private final ActionContext context;
        private final StreamObserver<LobReference> responseObserver;
        private final org.openjproxy.grpc.server.SessionManager sessionManager;

        private SessionInfo sessionInfo;
        private String lobUUID;
        private String stmtUUID;
        private LobType lobType;
        private LobDataBlocksInputStream lobDataBlocksInputStream;
        private final AtomicBoolean isFirstBlock = new AtomicBoolean(true);
        private final AtomicInteger countBytesWritten = new AtomicInteger(0);
        private long mariaDbNextUtf16Position = 1;
        private long mariaDbNextCodePointPosition = 1;

        LobStreamObserver(ActionContext context, StreamObserver<LobReference> responseObserver) {
            this.context = context;
            this.responseObserver = responseObserver;
            this.sessionManager = context.getSessionManager();
        }

        @Override
        public void onNext(LobDataBlock lobDataBlock) {
            try {
                this.lobType = lobDataBlock.getLobType();
                log.debug("lob data block received, lob type {}", this.lobType);

                ConnectionSessionDTO dto = sessionConnection(context, lobDataBlock.getSession(), true);

                // Initialize LOB if needed
                initializeLobIfNeeded(dto, lobDataBlock);

                // Write data based on LOB type
                int bytesWritten = writeLobData(dto, lobDataBlock);

                this.countBytesWritten.addAndGet(bytesWritten);
                this.sessionInfo = dto.getSession();

                // Send reference after first block
                if (isFirstBlock.getAndSet(false)) {
                    sendLobRef(dto, bytesWritten);
                }

            } catch (SQLException e) {
                sendSQLExceptionMetadata(e, responseObserver);
            } catch (Exception e) {
                sendSQLExceptionMetadata(new SQLException("Unable to write data: " + e.getMessage(), e),
                        responseObserver);
            }
        }

        /**
         * Initializes a new LOB if one doesn't exist yet.
         */
        private void initializeLobIfNeeded(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            if (lobDataBlock.getSession().getSessionUUID().isEmpty() || this.lobUUID == null) {
                Connection conn = dto.getConnection();
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
        }

        /**
         * Writes data to the appropriate LOB type.
         *
         * @return the number of bytes written
         */
        private int writeLobData(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            return switch (this.lobType) {
                case LT_BLOB -> writeBlobData(dto, lobDataBlock);
                case LT_CLOB -> writeClobData(dto, lobDataBlock);
                case LT_BINARY_STREAM -> writeBinaryStreamData(dto, lobDataBlock);
                default -> throw new SQLException("Unsupported LOB type: " + this.lobType);
            };
        }

        /**
         * Writes data to a BLOB.
         */
        private int writeBlobData(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            Blob blob = sessionManager.getLob(dto.getSession(), this.lobUUID);
            if (blob == null) {
                throw new SQLException("Unable to write LOB of type " + this.lobType
                        + ": Blob object is null for UUID " + this.lobUUID +
                        ". This may indicate a race condition or session management issue.");
            }
            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
            return blob.setBytes(lobDataBlock.getPosition(), byteArrayData);
        }

        /**
         * Writes data to a CLOB.
         */
        private int writeClobData(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            Clob clob = sessionManager.getLob(dto.getSession(), this.lobUUID);
            if (clob == null) {
                throw new SQLException("Unable to write LOB of type " + this.lobType
                        + ": Clob object is null for UUID " + this.lobUUID +
                        ". This may indicate a race condition or session management issue.");
            }
            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
            String chunk = new String(byteArrayData, StandardCharsets.UTF_8);
            long position = lobDataBlock.getPosition();
            long lengthBefore = clob.length();
            boolean mariaDb = DbName.MARIADB.equals(dto.getDbName())
                    || DbName.MARIADB.equals(context.getDbNameMap().get(lobDataBlock.getSession().getConnHash()));
            boolean sequential = mariaDb && position == mariaDbNextUtf16Position;
            long driverPosition = !mariaDb ? position : sequential
                    ? mariaDbNextCodePointPosition : mariaDbClobPosition(clob, position);
            try (Writer writer = clob.setCharacterStream(driverPosition)) {
                writer.write(chunk.toCharArray());
            } catch (IOException e) {
                throw new SQLException("Failed to write CLOB data: " + e.getMessage(), e);
            }
            if (mariaDb) {
                if (sequential) {
                    mariaDbNextUtf16Position += chunk.length();
                    mariaDbNextCodePointPosition += chunk.codePointCount(0, chunk.length());
                } else {
                    mariaDbNextUtf16Position = -1;
                }
            }
            log.debug("CLOB block written: position={}, bytes={}, codePoints={}, utf16Units={}, "
                            + "lengthBefore={}, lengthAfter={}",
                    position, byteArrayData.length, chunk.codePointCount(0, chunk.length()),
                    chunk.length(), lengthBefore, clob.length());
            return byteArrayData.length;
        }

        /**
         * Writes data to a binary stream.
         */
        private int writeBinaryStreamData(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            if (this.lobUUID == null) {
                return initializeBinaryStream(dto, lobDataBlock);
            } else {
                lobDataBlocksInputStream.addBlock(lobDataBlock);
                return lobDataBlock.getData().toByteArray().length;
            }

        }

        /**
         * Initializes a binary stream LOB.
         */
        private int initializeBinaryStream(ConnectionSessionDTO dto, LobDataBlock lobDataBlock) throws SQLException {
            if (lobDataBlock.getMetadataCount() < 1) {
                throw new SQLException("Metadata empty for binary stream type.");
            }

            Map<Integer, Object> metadata = convertMetadata(lobDataBlock);
            String sql = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_SQL);
            String preparedStatementUUID = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_UUID_BINARY_STREAM);

            if (preparedStatementUUID != null && !preparedStatementUUID.isEmpty()) {
                stmtUUID = preparedStatementUUID;
            } else {
                PreparedStatement ps = dto.getConnection().prepareStatement(sql);
                stmtUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
            }

            // Create and register the binary stream input stream
            lobDataBlocksInputStream = new LobDataBlocksInputStream(lobDataBlock);
            this.lobUUID = lobDataBlocksInputStream.getUuid();

            sessionManager.registerLob(dto.getSession(), lobDataBlocksInputStream, this.lobUUID);
            sessionManager.registerAttr(dto.getSession(), this.lobUUID, metadata);

            int initialBytes = lobDataBlock.getData().toByteArray().length;
            sendLobRef(dto, initialBytes);
            return initialBytes;
        }

        /**
         * Converts metadata from proto format to integer-keyed map for backward
         * compatibility.
         */
        private Map<Integer, Object> convertMetadata(LobDataBlock lobDataBlock) {
            Map<String, Object> metadataStringKey = ProtoConverter.propertiesFromProto(lobDataBlock.getMetadataList());
            Map<Integer, Object> metadata = new HashMap<>();

            for (Map.Entry<String, Object> entry : metadataStringKey.entrySet()) {
                try {
                    metadata.put(Integer.parseInt(entry.getKey()), entry.getValue());
                } catch (NumberFormatException e) {
                    // Keep as string key if not parseable
                    metadata.put(entry.getKey().hashCode(), entry.getValue());
                }
            }
            return metadata;
        }

        /**
         * Sends a LOB reference to the client.
         */
        private void sendLobRef(ConnectionSessionDTO dto, int bytesWritten) {
            log.debug("Returning lob ref {}", this.lobUUID);
            LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                    .setSession(dto.getSession())
                    .setUuid(this.lobUUID)
                    .setLobType(this.lobType)
                    .setBytesWritten(bytesWritten);
            if (this.stmtUUID != null) {
                lobRefBuilder.setStmtUUID(this.stmtUUID);
            }
            responseObserver.onNext(lobRefBuilder.build());
        }

        @Override
        public void onError(Throwable throwable) {
            log.error("Failure lob stream: " + throwable.getMessage(), throwable);
            if (lobDataBlocksInputStream != null) {
                lobDataBlocksInputStream.finish(true);
            }
        }

        @Override
        public void onCompleted() {
            if (lobDataBlocksInputStream != null) {
                CompletableFuture.runAsync(() -> {
                    log.debug("Finishing lob stream for lob ref {}", this.lobUUID);
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

            // Send the final Lob reference with total count of written bytes.
            responseObserver.onNext(lobRefBuilder.build());
            responseObserver.onCompleted();
        }
    }

    /**
     * Converts a 1-based UTF-16 position to the code-point position used by MariaDB's CLOB writer.
     * MariaDB's {@link Clob#length()} counts UTF-16 units, but its write positions count code points.
     *
     * <p>For example, {@code A🚀B} has four UTF-16 units (A, the emoji's two surrogate units, B)
     * but three code points (A, 🚀, B). To write at B, the client sends UTF-16 position 4,
     * but the MariaDB CLOB writer expects position 3. Passing 4 directly targets the wrong place.
     *
     * <p>For sequential chunks, the server tracks both positions as it writes, avoiding a scan.
     * For non-sequential positions, this method scans the CLOB and counts each surrogate pair as
     * one code point. It rejects position 3 in the example because it splits the emoji's pair,
     * as well as positions before 1 or beyond the position immediately after the CLOB.
     *
     * @param clob the existing CLOB
     * @param utf16Position the 1-based UTF-16 position
     * @return the corresponding 1-based code-point position
     * @throws SQLException if the position is invalid or the CLOB cannot be read
     */
    static long mariaDbClobPosition(Clob clob, long utf16Position) throws SQLException {
        if (utf16Position < 1) {
            throw new SQLException("CLOB position must be at least 1");
        }
        long codePoints = 0;
        boolean previousHigh = false;
        try (Reader reader = clob.getCharacterStream()) {
            for (long index = 1; index < utf16Position; index++) {
                int next = reader.read();
                if (next == -1) {
                    throw new SQLException("CLOB position exceeds its length");
                }
                char current = (char) next;
                if (!previousHigh || !Character.isLowSurrogate(current)) {
                    codePoints++;
                }
                previousHigh = Character.isHighSurrogate(current);
            }
            if (previousHigh) {
                int next = reader.read();
                if (next != -1 && Character.isLowSurrogate((char) next)) {
                    throw new SQLException("CLOB position splits a surrogate pair");
                }
            }
        } catch (IOException e) {
            throw new SQLException("Failed to determine MariaDB CLOB position", e);
        }
        return codePoints + 1;
    }
}
