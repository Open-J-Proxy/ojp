package org.openjproxy.jdbc;

import java.sql.SQLWarning;
import java.util.List;
import java.util.Map;

/**
 * Utility class for reconstructing a {@link SQLWarning} chain from the list-of-maps
 * representation used by OJP's gRPC transport layer.
 *
 * <p>The server encodes a {@code SQLWarning} chain as a {@code List<Map<String, Object>>}
 * where each map contains {@code "message"} (String), {@code "sqlState"} (String), and
 * {@code "vendorCode"} (Number). This class converts that representation back into a proper
 * linked {@code SQLWarning} chain that callers receive from {@code getWarnings()}.
 *
 * @see java.sql.Statement#getWarnings()
 * @see java.sql.Connection#getWarnings()
 */
final class SqlWarningUtils {

    private SqlWarningUtils() {
        // Utility class – no instances
    }

    /**
     * Reconstructs a {@link SQLWarning} chain from the list-of-maps produced by the server.
     *
     * <p>Each entry in {@code entries} must be a {@code Map<String, Object>} with the keys
     * {@code "message"}, {@code "sqlState"}, and {@code "vendorCode"}. Null or missing values
     * for any key are handled gracefully:
     * <ul>
     *   <li>{@code "message"} – defaults to {@code null} (accepted by {@link SQLWarning})</li>
     *   <li>{@code "sqlState"} – defaults to {@code null} (accepted by {@link SQLWarning})</li>
     *   <li>{@code "vendorCode"} – defaults to {@code 0}</li>
     * </ul>
     *
     * @param entries the ordered list of warning attribute maps, or {@code null}
     * @return the head of the reconstructed warning chain, or {@code null} if the list is
     *         null or empty
     */
    @SuppressWarnings("unchecked")
    static SQLWarning buildWarningChain(List<?> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        SQLWarning head = null;
        SQLWarning tail = null;
        for (Object raw : entries) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) raw;
            String message = (String) entry.get("message");
            String sqlState = (String) entry.get("sqlState");
            Number vendorCode = (Number) entry.get("vendorCode");
            SQLWarning warning = new SQLWarning(message, sqlState, vendorCode != null ? vendorCode.intValue() : 0);
            if (head == null) {
                head = warning;
                tail = warning;
            } else {
                tail.setNextWarning(warning);
                tail = warning;
            }
        }
        return head;
    }
}
