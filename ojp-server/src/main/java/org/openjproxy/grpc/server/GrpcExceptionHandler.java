package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Handles exceptions that need to be reported via GRPC.
 */
@Slf4j
public class GrpcExceptionHandler {
    private static final String SQLSTATE_CONNECTION_FAILURE = "08001";
    private static final String SQLSTATE_CONNECTION_DOES_NOT_EXIST = "08003";

    /**
     * Handles the reporting or SQLExceptions.
     * @param e SQLException
     * @param streamObserver target stream observer.
     * @param <T> Stream observer generic type.
     */
    public static <T> void sendSQLExceptionMetadata(SQLException e, StreamObserver<T> streamObserver) {
        SqlErrorType sqlErrorType = resolveSqlErrorType(e);
        sendSQLExceptionMetadata(e, streamObserver, sqlErrorType);
    }

    private static SqlErrorType resolveSqlErrorType(SQLException exception) {
        if (exception instanceof SQLTransientConnectionException) {
            return SqlErrorType.SQL_TRANSIENT_CONNECTION_EXCEPTION;
        }
        String sqlState = exception.getSQLState();
        if (SQLSTATE_CONNECTION_FAILURE.equals(sqlState)
                || SQLSTATE_CONNECTION_DOES_NOT_EXIST.equals(sqlState)) {
            return SqlErrorType.SQL_TRANSIENT_CONNECTION_EXCEPTION;
        }
        return SqlErrorType.SQL_EXCEPTION;
    }

    /**
     * Handles the reporting or SQLExceptions.
     * @param e SQLException
     * @param streamObserver target stream observer.
     * @param <T> Stream observer generic type.
     * @param sqlErrorType Indicates the type of error.
     */
    public static <T> void sendSQLExceptionMetadata(SQLException e, StreamObserver<T> streamObserver, SqlErrorType sqlErrorType) {
        if (isServerDraining(e)) {
            sendServerDraining(streamObserver);
            return;
        }
        Metadata metadata = new Metadata();
        try {
            SqlErrorResponse.Builder responseBuilder = SqlErrorResponse.newBuilder()
                    .setReason(e.getMessage() != null ? e.getMessage() : "")
                    .setSqlErrorType(sqlErrorType)
                    .setVendorCode(e.getErrorCode());
            if (e.getSQLState() != null) {
                responseBuilder.setSqlState(e.getSQLState());
            }

            SqlErrorResponse sqlErrorResponse = responseBuilder.build();
            Metadata.Key<SqlErrorResponse> errorResponseKey = ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance());
            metadata.put(errorResponseKey, sqlErrorResponse);
        } catch (RuntimeException re) {
            log.error("Failed while sending error to client: " + re.getMessage() + ": " + e.getMessage(), e);
        }
        streamObserver.onError(Status.INTERNAL.asRuntimeException(metadata));
    }

    /**
     * Trailer metadata key for the JDBC driver to identify which admission lane
     * triggered the overload. Values: {@code fast}, {@code slow}, {@code queue},
     * {@code unknown}. The driver applies different back-off policies per lane —
     * notably, slow-lane overloads should not depress the (predominantly fast)
     * client-side reactive throttle.
     */
    public static final Metadata.Key<String> OVERLOAD_LANE_KEY =
            Metadata.Key.of("ojp-overload-lane", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Sends an overload signal to clients so they can retry with backoff.
     *
     * <p>The {@code ojp-overload-lane} trailer carries the saturated lane so the JDBC
     * driver can apply lane-aware back-off (see {@link ServerOverloadException.Lane}).</p>
     *
     * @param e overload exception
     * @param streamObserver target stream observer
     * @param <T> Stream observer generic type.
     */
    public static <T> void sendServerOverload(ServerOverloadException e, StreamObserver<T> streamObserver) {
        String description = e.getMessage() != null ? e.getMessage() : "Server overloaded";
        Metadata trailers = new Metadata();
        ServerOverloadException.Lane lane = e.getLane();
        trailers.put(OVERLOAD_LANE_KEY, lane == null ? "unknown" : lane.name().toLowerCase());
        streamObserver.onError(Status.RESOURCE_EXHAUSTED
                .withDescription(description)
                .asRuntimeException(trailers));
    }

    /**
     * Trailer metadata key signalling that the server is draining (graceful shutdown).
     * The JDBC driver uses it to stop routing new sessions to this server without
     * invalidating sessions that are already bound to it.
     */
    public static final Metadata.Key<String> SERVER_DRAINING_KEY =
            Metadata.Key.of(CommonConstants.SERVER_DRAINING_TRAILER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Sends {@code Status.UNAVAILABLE} with the {@code ojp-server-draining} trailer.
     *
     * @param streamObserver target stream observer
     * @param <T> Stream observer generic type.
     */
    public static <T> void sendServerDraining(StreamObserver<T> streamObserver) {
        Metadata trailers = new Metadata();
        trailers.put(SERVER_DRAINING_KEY, "true");
        streamObserver.onError(Status.UNAVAILABLE
                .withDescription(CommonConstants.SERVER_DRAINING_DESCRIPTION)
                .asRuntimeException(trailers));
    }

    /**
     * Returns {@code true} if the throwable or any of its causes is a {@link ServerDrainingException}.
     */
    static boolean isServerDraining(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof ServerDrainingException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
