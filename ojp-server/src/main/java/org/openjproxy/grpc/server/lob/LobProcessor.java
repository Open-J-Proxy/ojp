package org.openjproxy.grpc.server.lob;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import lombok.SneakyThrows;
import org.openjproxy.grpc.server.SessionManager;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Utility class for handling LOB (Large Object) operations.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class LobProcessor {

    /**
     * Processes a BLOB from a result set using hydrated approach.
     *
     * NOTE: This method now uses a hydrated approach for all databases, where the entire
     * BLOB content is materialized in memory as a byte array. This ensures consistent
     * behavior across all database types and eliminates the complexity of streaming LOBs.
     *
     * Performance implications:
     * - Higher memory usage for large BLOBs (entire content loaded into memory)
     * - Faster access once loaded, but higher initial load time
     * - May limit maximum BLOB size due to memory constraints
     *
     * @param sessionManager The session manager (unused in hydrated approach)
     * @param session       The current session
     * @param rs           The result set
     * @param columnIndex  The column index (0-based)
     * @param dbNameMap    Map of connection hash to database name (unused in hydrated approach)
     * @return The processed BLOB value as byte array
     * @throws SQLException if BLOB processing fails
     */
    @SneakyThrows
    public static Object treatAsBlob(SessionManager sessionManager, SessionInfo session,
                                   ResultSet rs, int columnIndex, Map<String, DbName> dbNameMap) throws SQLException {
        Blob blob = rs.getBlob(columnIndex + 1);
        if (blob == null) {
            return null;
        }
        // Hydrated approach: materialize the entire BLOB content in memory for all databases
        // This provides consistent behavior and eliminates streaming complexity
        return blob.getBinaryStream().readAllBytes();
    }

    /**
     * Processes binary data from a result set using hydrated approach.
     *
     * NOTE: This method now uses a hydrated approach for all databases, where binary streams
     * are materialized in memory as byte arrays. This ensures consistent behavior across all
     * database types and eliminates the complexity of streaming binary data.
     *
     * Performance implications:
     * - Higher memory usage for large binary data (entire content loaded into memory)
     * - Faster access once loaded, but higher initial load time
     * - May limit maximum binary data size due to memory constraints
     *
     * @param sessionManager The session manager (unused in hydrated approach)
     * @param session       The current session
     * @param dbName        The database name (unused in hydrated approach)
     * @param rs           The result set
     * @param columnIndex  The column index (0-based)
     * @param inputStreamTypes List of input stream types
     * @return The processed binary value as byte array or primitive byte
     * @throws SQLException if binary processing fails
     */
    @SneakyThrows
    public static Object treatAsBinary(SessionManager sessionManager, SessionInfo session,
                                     DbName dbName, ResultSet rs, int columnIndex,
                                     java.util.List<String> inputStreamTypes) throws SQLException {
        int precision = rs.getMetaData().getPrecision(columnIndex + 1);
        String catalogName = rs.getMetaData().getCatalogName(columnIndex + 1);
        String colClassName = rs.getMetaData().getColumnClassName(columnIndex + 1);
        String colTypeName = rs.getMetaData().getColumnTypeName(columnIndex + 1);
        colTypeName = colTypeName != null ? colTypeName : "";
        Object binaryValue = null;

        if (precision == 1 && !"[B".equalsIgnoreCase(colClassName) && !"byte[]".equalsIgnoreCase(colClassName)) {
            //it is a single byte and is not of class byte array([B)
            binaryValue = rs.getByte(columnIndex + 1);
        } else if (((catalogName != null && !catalogName.isEmpty()) ||
                   "[B".equalsIgnoreCase(colClassName) || "byte[]".equalsIgnoreCase(colClassName)) &&
                   !inputStreamTypes.contains(colTypeName.toUpperCase())) {
            binaryValue = rs.getBytes(columnIndex + 1);
        } else {
            InputStream inputStream = rs.getBinaryStream(columnIndex + 1);
            if (inputStream == null) {
                return null;
            }

            // Hydrated approach: materialize the entire binary stream content in memory for all databases
            // This provides consistent behavior and eliminates streaming complexity
            byte[] allBytes = inputStream.readAllBytes();
            binaryValue = allBytes;
        }
        return binaryValue;
    }
}
