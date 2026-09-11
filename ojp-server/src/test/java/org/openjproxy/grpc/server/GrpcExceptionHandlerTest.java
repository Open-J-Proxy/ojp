package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcExceptionHandlerTest {

    @Test
    void shouldSendResourceExhaustedForServerOverload() {
        CapturingObserver<Object> observer = new CapturingObserver<>();
        ServerOverloadException exception = new ServerOverloadException("too busy");

        GrpcExceptionHandler.sendServerOverload(exception, observer);

        assertNotNull(observer.error);
        StatusRuntimeException statusRuntimeException = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, statusRuntimeException.getStatus().getCode());
        assertTrue(statusRuntimeException.getStatus().getDescription().contains("too busy"));
    }

    @Test
    void shouldIncludeLaneMetadataOnSlowLaneOverload() {
        CapturingObserver<Object> observer = new CapturingObserver<>();
        ServerOverloadException exception = new ServerOverloadException(
                "slow lane saturated", ServerOverloadException.Lane.SLOW);

        GrpcExceptionHandler.sendServerOverload(exception, observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        Metadata trailers = sre.getTrailers();
        assertNotNull(trailers);
        assertEquals("slow", trailers.get(GrpcExceptionHandler.OVERLOAD_LANE_KEY));
    }

    @Test
    void shouldIncludeLaneMetadataOnFastLaneOverload() {
        CapturingObserver<Object> observer = new CapturingObserver<>();
        ServerOverloadException exception = new ServerOverloadException(
                "fast lane saturated", ServerOverloadException.Lane.FAST);

        GrpcExceptionHandler.sendServerOverload(exception, observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals("fast", sre.getTrailers().get(GrpcExceptionHandler.OVERLOAD_LANE_KEY));
    }

    @Test
    void shouldDefaultLaneToUnknownWhenNotSpecified() {
        CapturingObserver<Object> observer = new CapturingObserver<>();
        ServerOverloadException exception = new ServerOverloadException("legacy overload");

        GrpcExceptionHandler.sendServerOverload(exception, observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals("unknown", sre.getTrailers().get(GrpcExceptionHandler.OVERLOAD_LANE_KEY));
    }

    @Test
    void shouldEncodeTransientConnectionErrorTypeForSqlState08001() {
        CapturingObserver<Object> observer = new CapturingObserver<>();

        GrpcExceptionHandler.sendSQLExceptionMetadata(
                new SQLTransientConnectionException("pool exhausted", "08001"),
                observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        Metadata trailers = sre.getTrailers();
        assertNotNull(trailers);
        SqlErrorResponse errorResponse =
                trailers.get(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()));
        assertNotNull(errorResponse);
        assertEquals(SqlErrorType.SQL_TRANSIENT_CONNECTION_EXCEPTION, errorResponse.getSqlErrorType());
    }

    @Test
    void shouldEncodeTransientConnectionErrorTypeForSqlState08003() {
        CapturingObserver<Object> observer = new CapturingObserver<>();

        GrpcExceptionHandler.sendSQLExceptionMetadata(new SQLException("connection closed", "08003"), observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        Metadata trailers = sre.getTrailers();
        assertNotNull(trailers);
        SqlErrorResponse errorResponse =
                trailers.get(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()));
        assertNotNull(errorResponse);
        assertEquals(SqlErrorType.SQL_TRANSIENT_CONNECTION_EXCEPTION, errorResponse.getSqlErrorType());
    }

    private static class CapturingObserver<T> implements StreamObserver<T> {
        private Throwable error;

        @Override
        public void onNext(T value) {
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
        }
    }
}
