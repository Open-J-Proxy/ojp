package org.openjproxy.grpc.client;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import org.junit.jupiter.api.Test;

import java.sql.SQLDataException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrpcExceptionHandlerTest {

    @Test
    void shouldThrowSqlTransientConnectionExceptionWhenResourceExhaustedWithoutSqlMetadata() {
        StatusRuntimeException statusRuntimeException = Status.RESOURCE_EXHAUSTED
                .withDescription("Server overloaded: too many concurrent requests")
                .asRuntimeException();

        SQLTransientConnectionException exception = assertThrows(SQLTransientConnectionException.class,
                () -> GrpcExceptionHandler.handle(statusRuntimeException));

        assertEquals("08001", exception.getSQLState());
        assertEquals("Server overloaded: too many concurrent requests", exception.getMessage());
    }

    @Test
    void shouldThrowSqlTransientConnectionExceptionWhenSqlMetadataTypeIsTransientConnection() {
        Metadata metadata = new Metadata();
        SqlErrorResponse errorResponse = SqlErrorResponse.newBuilder()
                .setReason("Pool exhausted")
                .setSqlState("08001")
                .setSqlErrorType(SqlErrorType.SQL_TRANSIENT_CONNECTION_EXCEPTION)
                .build();
        metadata.put(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()), errorResponse);
        StatusRuntimeException statusRuntimeException = Status.INTERNAL
                .withDescription("sql error")
                .asRuntimeException(metadata);

        SQLTransientConnectionException exception = assertThrows(SQLTransientConnectionException.class,
                () -> GrpcExceptionHandler.handle(statusRuntimeException));

        assertEquals("08001", exception.getSQLState());
        assertEquals("Pool exhausted", exception.getMessage());
    }

    @Test
    void shouldThrowSqlDataExceptionWhenSqlMetadataTypeIsSqlDataException() {
        Metadata metadata = new Metadata();
        SqlErrorResponse errorResponse = SqlErrorResponse.newBuilder()
                .setReason("invalid data")
                .setSqlState("22001")
                .setSqlErrorType(SqlErrorType.SQL_DATA_EXCEPTION)
                .build();
        metadata.put(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()), errorResponse);
        StatusRuntimeException statusRuntimeException = Status.INTERNAL
                .withDescription("sql error")
                .asRuntimeException(metadata);

        SQLDataException exception = assertThrows(SQLDataException.class,
                () -> GrpcExceptionHandler.handle(statusRuntimeException));

        assertEquals("22001", exception.getSQLState());
        assertEquals("invalid data", exception.getMessage());
    }
}
