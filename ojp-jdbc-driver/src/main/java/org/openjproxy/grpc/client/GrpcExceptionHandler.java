package org.openjproxy.grpc.client;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;

import java.sql.SQLDataException;
import java.sql.SQLException;

public class GrpcExceptionHandler {
    /**
     * Handler for StatusRuntimeException, converting it to a SQLException when SQL
     * metadata returned.
     *
     * @param sre StatusRuntimeException
     * @return StatusRuntimeException if SQL metadata not found just return the
     *         exception received.
     * @throws SQLException If conversion possible.
     */
    public static StatusRuntimeException handle(StatusRuntimeException sre) throws SQLException {
        Metadata metadata = Status.trailersFromThrowable(sre);
        SqlErrorResponse errorResponse = metadata.get(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()));
        if (errorResponse == null) {
            return sre;
        }
        if (SqlErrorType.SQL_DATA_EXCEPTION.equals(errorResponse.getSqlErrorType())) {
            throw new SQLDataException(errorResponse.getReason(), errorResponse.getSqlState(),
                    errorResponse.getVendorCode());
        } else {
            throw new SQLException(errorResponse.getReason(), errorResponse.getSqlState(),
                    errorResponse.getVendorCode());
        }
    }
}
