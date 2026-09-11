package org.openjproxy.grpc.server.resultset;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultRow;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;

import java.util.List;

/**
 * Utility class for wrapping result set data into OpResult objects.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class ResultSetWrapper {

    /**
     * Wraps result set data into an OpResult for GRPC response.
     *
     * @param sessionInfo   The session information
     * @param rows          The result data rows as protobuf ResultRow objects
     * @param labels        Column labels for the first block, or null for subsequent blocks
     * @param resultSetUUID The result set UUID
     * @param resultSetMode The result set mode flag
     * @return OpResult containing wrapped data
     */
    public static OpResult wrapResults(SessionInfo sessionInfo,
                                       List<ResultRow> rows,
                                       List<String> labels,
                                       String resultSetUUID, String resultSetMode) {

        OpResult.Builder resultsBuilder = OpResult.newBuilder();
        resultsBuilder.setSession(sessionInfo);
        resultsBuilder.setType(ResultType.RESULT_SET_DATA);

        OpQueryResultProto.Builder queryResultBuilder = OpQueryResultProto.newBuilder()
                .setResultSetUUID(resultSetUUID != null ? resultSetUUID : "");
        if (labels != null) {
            queryResultBuilder.addAllLabels(labels);
        }
        queryResultBuilder.addAllRows(rows);

        resultsBuilder.setQueryResult(queryResultBuilder.build());
        resultsBuilder.setFlag(resultSetMode);

        return resultsBuilder.build();
    }
}
