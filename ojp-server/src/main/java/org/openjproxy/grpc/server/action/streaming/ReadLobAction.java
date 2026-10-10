package org.openjproxy.grpc.server.action.streaming;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.ReadLobRequest;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.ReaderInputStream;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.StatementServiceImpl;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Optional;

import static org.openjproxy.constants.CommonConstants.MAX_LOB_DATA_BLOCK_SIZE;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * gRPC server-side action that streams LOB (BLOB/CLOB/binary stream) contents
 * to a client.
 * <p>
 * The action resolves a previously stored LOB via {@link SessionManager} using
 * the {@link LobReference} provided in
 * a {@link ReadLobRequest}, then emits one or more {@link LobDataBlock}
 * messages containing the LOB bytes in bounded
 * chunks to limit server memory usage.
 */
@Slf4j
public class ReadLobAction implements Action<ReadLobRequest, LobDataBlock> {

    private static final ReadLobAction INSTANCE = new ReadLobAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private ReadLobAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of ReadLobAction.
     *
     * @return the singleton instance
     */
    public static ReadLobAction getInstance() {
        return INSTANCE;
    }

    /**
     * Streams the requested LOB content to the client as a sequence of
     * {@link LobDataBlock} messages.
     * <p>
     * The LOB is resolved from the {@link SessionManager} using the
     * {@link LobReference} in the request. Data is read
     * from the underlying {@link InputStream} and sent in blocks (up to
     * {@link org.openjproxy.constants.CommonConstants#MAX_LOB_DATA_BLOCK_SIZE}) to
     * avoid loading the full LOB into
     * memory. Each emitted {@link LobDataBlock} includes the session id and an
     * updated position indicating progress
     * through the stream.
     * <p>
     * If the LOB cannot be resolved (i.e. the input stream is {@code null}), a
     * single {@link LobDataBlock} with
     * {@code position = -1} and empty data is sent and the stream is completed.
     * SQLExceptions are propagated to the
     * client via gRPC metadata.
     *
     * @param context          the action context containing the session manager
     * @param request          the read request containing the LOB reference, start
     *                         position and requested length
     * @param responseObserver observer used to emit {@link LobDataBlock} messages
     *                         and completion
     */
    @Override
    public void execute(ActionContext context, ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        log.debug("Reading lob {}", request.getLobReference().getUuid());
        try {
            LobReference lobRef = request.getLobReference();
            StatementServiceImpl.ReadLobContext readLobContext = this.findLobContext(context.getSessionManager(),
                    request);
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
            // If the lob length is known the exact size of the next block is also known.
            boolean exactSizeKnown = readLobContext.getLobLength().isPresent()
                    && readLobContext.getAvailableLength().isPresent();
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
                    log.debug("Sending block of data size {} pos {}", idx + 1, currentPos);
                    // Send data to client in limited size blocks to safeguard server memory.
                    responseObserver.onNext(LobDataBlock.newBuilder()
                            .setSession(lobRef.getSession())
                            .setPosition(currentPos)
                            .setData(ByteString.copyFrom(nextBlock))
                            .build());
                    nextBlockSize = this.nextBlockSize(readLobContext, currentPos - 1L);
                    if (nextBlockSize > 0) { // Might be a single small block then nextBlockSize will return negative.
                        nextBlock = new byte[nextBlockSize];
                    } else {
                        nextBlock = new byte[0];
                    }
                    nextBlockFullyEmpty = true;
                    idx = -1;
                }
                nextByte = inputStream.read();
            }

            // Send leftover bytes
            if (!nextBlockFullyEmpty && nextBlock[0] != -1) {

                byte[] adjustedSizeArray = (idx % MAX_LOB_DATA_BLOCK_SIZE != 0 && !exactSizeKnown) ? trim(nextBlock)
                        : nextBlock;
                if (adjustedSizeArray.length == 1 && adjustedSizeArray[0] != nextByte) {
                    // For cases where the amount of bytes is a multiple of the block size and last
                    // read only reads the end of the stream.
                    adjustedSizeArray = new byte[0];
                }
                currentPos = (int) request.getPosition() + idx;
                log.debug("Sending leftover bytes size {} pos {}", idx, currentPos);
                responseObserver.onNext(LobDataBlock.newBuilder()
                        .setSession(lobRef.getSession())
                        .setPosition(currentPos)
                        .setData(ByteString.copyFrom(adjustedSizeArray))
                        .build());
            }

            responseObserver.onCompleted();

        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Trims trailing {@code 0x00} padding bytes from a read block.
     * <p>
     * This is used to shrink the last block when the read buffer is larger than the
     * actual remaining LOB bytes.
     * Note: if the input contains only {@code 0x00} bytes, this method returns a
     * single-byte array.
     *
     * @param nextBlock the block to trim (must not be {@code null})
     * @return a new array containing the bytes up to the last non-zero byte
     *         (inclusive)
     */
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

    /**
     * Resolves the referenced LOB and builds a
     * {@link StatementServiceImpl.ReadLobContext} for streaming it.
     * <p>
     * Depending on {@link LobReference#getLobType()}, this method:
     * <ul>
     * <li>creates a bounded {@link InputStream} from a {@link Blob}
     * ({@code LT_BLOB})</li>
     * <li>creates a UTF-8 {@link InputStream} from a {@link Clob}
     * ({@code LT_CLOB})</li>
     * <li>retrieves either a {@link Blob} or an already-stored {@link InputStream}
     * ({@code LT_BINARY_STREAM}); when
     * the latter is used, the stream is {@link InputStream#reset() reset} to ensure
     * reads start at the beginning</li>
     * </ul>
     * For binary streams, LOB length/available length are generally unknown; if the
     * underlying stream is a
     * {@link ByteArrayInputStream}, the lengths are derived from
     * {@link ByteArrayInputStream#available()}.
     *
     * @param request the read request containing the LOB reference, position and
     *                length
     * @return a context containing the resolved input stream and any known length
     *         metadata; the input stream may be
     *         {@code null} if the LOB cannot be resolved
     * @throws SQLException if the LOB cannot be accessed via the JDBC driver
     */
    @SneakyThrows
    private StatementServiceImpl.ReadLobContext findLobContext(SessionManager sessionManager, ReadLobRequest request)
            throws SQLException {
        InputStream inputStream = null;
        LobReference lobReference = request.getLobReference();
        StatementServiceImpl.ReadLobContext.ReadLobContextBuilder readLobContextBuilder = StatementServiceImpl.ReadLobContext
                .builder();

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
                    inputStream = this.inputStreamFromBlob(sessionManager, lobReference, request,
                            readLobContextBuilder);
                } else if (lobObj instanceof InputStream) {
                    inputStream = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
                    inputStream.reset();// Might be a second read of the same stream, this guarantees that the position
                    // is at the start.
                    if (inputStream instanceof ByteArrayInputStream bais) { // Only used in SQL Server
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

    /**
     * Creates an {@link InputStream} for reading a slice of a {@link Clob}.
     * <p>
     * This method resolves the {@link Clob} from the {@link SessionManager},
     * determines the effective number of
     * characters that can be read given the requested {@code position} and
     * {@code length}, stores the derived
     * length metadata in the provided context builder, and exposes the JDBC
     * {@link Reader} as a UTF-8 encoded
     * {@link InputStream}.
     *
     * @param sessionManager        the session manager used to resolve the stored
     *                              LOB
     * @param lobReference          the LOB reference identifying the {@link Clob}
     * @param request               the read request containing the starting
     *                              position and requested length
     * @param readLobContextBuilder builder updated with LOB length and computed
     *                              available length
     * @return an {@link InputStream} reading the requested clob range as UTF-8
     *         bytes
     */
    @SneakyThrows
    private InputStream inputStreamFromClob(SessionManager sessionManager, LobReference lobReference,
            ReadLobRequest request,
            StatementServiceImpl.ReadLobContext.ReadLobContextBuilder readLobContextBuilder) {
        Clob clob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = clob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength()
                : (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        Reader reader = clob.getCharacterStream(request.getPosition(), availableLength);
        return ReaderInputStream.builder()
                .setReader(reader)
                .setCharset(StandardCharsets.UTF_8)
                .getInputStream();
    }

    /**
     * Creates an {@link InputStream} for reading a slice of a {@link Blob}.
     * <p>
     * This method resolves the {@link Blob} from the {@link SessionManager},
     * determines the effective number of bytes
     * that can be read given the requested {@code position} and {@code length},
     * stores the derived length metadata in
     * the provided context builder, and returns the JDBC
     * {@link Blob#getBinaryStream(long, long)} for that range.
     *
     * @param sessionManager        the session manager used to resolve the stored
     *                              LOB
     * @param lobReference          the LOB reference identifying the {@link Blob}
     * @param request               the read request containing the starting
     *                              position and requested length
     * @param readLobContextBuilder builder updated with LOB length and computed
     *                              available length
     * @return an {@link InputStream} reading the requested blob range as bytes
     */
    @SneakyThrows
    private InputStream inputStreamFromBlob(SessionManager sessionManager, LobReference lobReference,
            ReadLobRequest request,
            StatementServiceImpl.ReadLobContext.ReadLobContextBuilder readLobContextBuilder) {
        Blob blob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = blob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength()
                : (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        return blob.getBinaryStream(request.getPosition(), availableLength);
    }

    /**
     * Computes the size (in bytes) of the next data block to read and stream.
     * <p>
     * If the LOB length and the requested available length are unknown (e.g. some
     * binary streams), this method returns
     * {@link org.openjproxy.constants.CommonConstants#MAX_LOB_DATA_BLOCK_SIZE} so
     * reads proceed in fixed-size blocks.
     * When the lengths are known, it selects the minimum of the block size and
     * requested length, then adjusts the
     * final block so it does not read past the end of the LOB.
     * <p>
     * In some boundary cases where the caller has already reached the end of the
     * requested range, this method returns
     * {@code 0} to indicate that no further bytes should be read.
     *
     * @param readLobContext context containing (optional) LOB length and available
     *                       length metadata
     * @param position       the current read position (1-based) used to compute the
     *                       next block boundary
     * @return the number of bytes to read for the next block; may be {@code 0} when
     *         no further bytes are needed
     */
    private int nextBlockSize(StatementServiceImpl.ReadLobContext readLobContext, long position) {

        // BinaryStreams do not have means to know the size of the lob like Blobs or
        // Clobs.
        if (readLobContext.getAvailableLength().isEmpty() || readLobContext.getLobLength().isEmpty()) {
            return MAX_LOB_DATA_BLOCK_SIZE;
        }

        long lobLength = readLobContext.getLobLength().get();
        int length = readLobContext.getAvailableLength().get();

        // Single read situations
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
}
