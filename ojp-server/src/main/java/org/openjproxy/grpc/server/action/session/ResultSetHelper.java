package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultRow;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.HydratedResultSetMetadata;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.utils.DateTimeUtils;

import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Utility class for processing JDBC {@link ResultSet}s and streaming query
 * results to gRPC clients.
 * <p>
 * Handles conversion of JDBC result set rows into the format expected by the
 * OpenJProxy gRPC API,
 * including special handling for LOBs (BLOBs, CLOBs), binary data, date/time
 * types, and database-specific
 * behaviors (e.g., row-by-row mode for SQL Server and DB2 when LOBs are
 * present).
 * </p>
 */
@Slf4j
public class ResultSetHelper {

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";
    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");

    /**
     * Private constructor to prevent instantiation.
     * This class provides only static utility methods.
     */
    private ResultSetHelper() {
        // Empty constructor
    }

    /**
     * Processes a JDBC result set and streams its rows to the gRPC response
     * observer.
     * <p>
     * Iterates over all rows in the result set, converting column values according
     * to their SQL types.
     * Results are sent in blocks whose size is controlled by the
     * {@code ojp.resultset.rowsPerBlock} server configuration property
     * (default: {@value org.openjproxy.constants.CommonConstants#ROWS_PER_RESULT_SET_DATA_BLOCK}).
     * For SQL Server and DB2, when LOB columns are present, only one row is
     * sent per call to support
     * row-by-row fetching.
     * </p>
     *
     * @param context          the action context providing session and database
     *                         access
     * @param session          the session information for the current client
     * @param resultSetUUID    the unique identifier of the result set to process
     * @param responseObserver the gRPC stream observer to send results to
     * @throws SQLException if a database access error occurs while reading the
     *                      result set
     */
    public static void handleResultSet(ActionContext context, SessionInfo session, String resultSetUUID,
            StreamObserver<OpResult> responseObserver)
            throws SQLException {
        var sessionManager = context.getSessionManager();

        ResultSet rs = sessionManager.getResultSet(session, resultSetUUID);
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }

        List<ResultRow> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        DbName dbName = DatabaseUtils.resolveDbName(rs.getStatement().getConnection().getMetaData().getURL());
        // Only used if result set contains LOBs in SQL Server and DB2 (if LOB's
        // present), so cursor is not read in advance,
        // every row has to be requested by the jdbc client.
        String resultSetMode = "";
        boolean resultSetMetadataCollected = false;

        while (rs.next()) {
            if (DbName.DB2.equals(dbName) && !resultSetMetadataCollected) {
                collectResultSetMetadata(context, session, resultSetUUID, rs);
            }
            justSent = false;
            row++;
            ResultRow.Builder rowBuilder = ResultRow.newBuilder();
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue = null;

                boolean isSQLOrDB2 = DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName);

                // Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.OTHER: {
                        // PostgreSQL reports JSON and JSONB columns as Types.OTHER.
                        // Use getString() which is supported by all JDBC drivers for JSON columns
                        // and returns the JSON text directly without vendor-specific wrapper objects.
                        if ("json".equalsIgnoreCase(colTypeName) || "jsonb".equalsIgnoreCase(colTypeName)) {
                            currentValue = rs.getString(i + 1);
                        } else {
                            currentValue = rs.getObject(i + 1);
                        }
                        break;
                    }
                    case Types.VARBINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i,
                                    context.getDbNameMap());
                        } else {
                            currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i,
                                    INPUT_STREAM_TYPES);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, context.getDbNameMap());
                        break;
                    }
                    case Types.CLOB: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        Clob clob = rs.getClob(i + 1);
                        if (clob != null) {
                            String clobUUID = UUID.randomUUID().toString();
                            // CLOB needs to be prefixed as per it can be read in the JDBC driver by
                            // getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            sessionManager.registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i,
                                INPUT_STREAM_TYPES);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        // Oracle 21c+ native JSON columns use a vendor-specific type code (not Types.OTHER).
                        // Detect them by column type name and use getString() to return plain JSON text.
                        if ("json".equalsIgnoreCase(colTypeName)) {
                            currentValue = rs.getString(i + 1);
                        } else {
                            currentValue = rs.getObject(i + 1);
                        }
                        // com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not
                        // implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowBuilder.addColumns(ProtoConverter.toParameterValue(currentValue));
            }
            results.add(rowBuilder.build());

            if ((DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                    && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
                break;
            }

            if (row % context.getServerConfiguration().getResultsetRowsPerBlock() == 0) {
                justSent = true;
                // Enrich session with throttle data so the driver-side ClientThrottleManager
                // can update its reactive limit from each block, not just the connect response.
                SessionInfo enrichedSession = org.openjproxy.grpc.server.utils.SessionInfoUtils
                        .enrichWithThrottle(session, context);
                // Send a block of records
                responseObserver.onNext(ResultSetWrapper.wrapResults(enrichedSession, results, labels,
                        resultSetUUID, resultSetMode));
                labels = null; // Labels only included in the first block
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            // Send a block of remaining records
            SessionInfo enrichedSession = org.openjproxy.grpc.server.utils.SessionInfoUtils
                    .enrichWithThrottle(session, context);
            responseObserver.onNext(
                    ResultSetWrapper.wrapResults(enrichedSession, results, labels, resultSetUUID, resultSetMode));
        }

        responseObserver.onCompleted();

    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * <p>
     * This should be called at the beginning of any method that operates on a
     * session
     * to ensure the session is not evicted by idle timeout while processing.
     * </p>
     *
     * @param context     the action context providing session manager access
     * @param sessionInfo the session information; no-op if null or has empty
     *                    session UUID
     */
    public static void updateSessionActivity(ActionContext context, SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            context.getSessionManager().updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Collects and registers result set metadata in the session for DB2 databases.
     * <p>
     * DB2 requires metadata to be collected before row iteration when LOBs may be
     * present,
     * since the cursor is not read in advance. The metadata is stored under a
     * session attribute
     * keyed by {@link #RESULT_SET_METADATA_ATTR_PREFIX} plus the result set UUID.
     * </p>
     *
     * @param context       the action context providing session manager access
     * @param session       the session to register the metadata in
     * @param resultSetUUID the unique identifier of the result set
     * @param rs            the JDBC result set whose metadata to collect
     */
    @SneakyThrows
    private static void collectResultSetMetadata(ActionContext context, SessionInfo session, String resultSetUUID,
            ResultSet rs) {
        context.getSessionManager().registerAttr(session, RESULT_SET_METADATA_ATTR_PREFIX +
                resultSetUUID, new HydratedResultSetMetadata(rs.getMetaData()));
    }
}
