package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

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
}
