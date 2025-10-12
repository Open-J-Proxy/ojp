package org.openjproxy.grpc.client;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Proxy Server interface to handle the Jdbc requests.
 */
public interface StatementService {

        /**
         * Open a new JDBC connection with the database if one does not yet exit.
         */
        SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException;

        // DML Operations
        OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                        Map<String, Object> properties)
                        throws SQLException;

        OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params, String statementUUID,
                        Map<String, Object> properties) throws SQLException;

        Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                        String statementUUID,
                        Map<String, Object> properties) throws SQLException;

        Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                        Map<String, Object> properties) throws SQLException;

        OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException;

        // LOB (Large objects) management.
        LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException;

        Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException;

        // Session management.
        void terminateSession(SessionInfo session);

        // Transaction management.
        SessionInfo startTransaction(SessionInfo session) throws SQLException;

        SessionInfo commitTransaction(SessionInfo session) throws SQLException;

        SessionInfo rollbackTransaction(SessionInfo session) throws SQLException;

        CallResourceResponse callResource(CallResourceRequest request) throws SQLException;
}
