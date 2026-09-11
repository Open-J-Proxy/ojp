package org.openjproxy.grpc.server.readwrite;

/**
 * Classifies SQL statements as read-only or write operations to support
 * read/write traffic splitting.
 *
 * <p>SELECT, WITH (CTEs), EXPLAIN, SHOW, and DESCRIBE statements are treated
 * as read-only and may be routed to a replica.  All other statements
 * (INSERT, UPDATE, DELETE, MERGE, DDL, etc.) are treated as writes and are
 * always routed to the primary.
 */
public final class ReadWriteSqlClassifier {

    /** Indicates whether a SQL statement modifies data. */
    public enum QueryType {
        READ,
        WRITE
    }

    private ReadWriteSqlClassifier() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Classifies the given SQL statement as {@link QueryType#READ} or
     * {@link QueryType#WRITE}.
     *
     * @param sql the SQL statement to classify (may be {@code null})
     * @return {@link QueryType#READ} for read-only statements,
     *         {@link QueryType#WRITE} for all other statements and for
     *         {@code null} / blank input
     */
    public static QueryType classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return QueryType.WRITE;
        }
        String upper = sql.stripLeading().toUpperCase();
        if (isKeyword(upper, "SELECT")
                || isKeyword(upper, "WITH")
                || isKeyword(upper, "EXPLAIN")
                || isKeyword(upper, "SHOW")
                || isKeyword(upper, "DESCRIBE")
                || isKeyword(upper, "DESC")) {
            return QueryType.READ;
        }
        return QueryType.WRITE;
    }

    /**
     * Returns {@code true} when {@code upper} starts with {@code keyword} and the
     * character immediately following the keyword (if any) is not an alphanumeric
     * character.  This ensures that e.g. {@code "DESCusers"} is not treated as a
     * {@code DESC} statement while {@code "DESC users"}, {@code "DESC\nusers"}, and
     * {@code "DESCRIBE users"} are all recognised correctly.
     */
    private static boolean isKeyword(String upper, String keyword) {
        if (!upper.startsWith(keyword)) {
            return false;
        }
        if (upper.length() == keyword.length()) {
            return true;
        }
        return !Character.isLetterOrDigit(upper.charAt(keyword.length()));
    }
}
