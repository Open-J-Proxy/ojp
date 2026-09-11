package org.openjproxy.jdbc;

import java.util.Locale;

/**
 * Best-effort static classifier that decides, without executing the SQL, whether a statement
 * is expected to return a {@link java.sql.ResultSet} (e.g. SELECT-style queries) or an update
 * count (INSERT/UPDATE/DELETE/DDL/stored procedure calls).
 *
 * <p>OJP's wire protocol exposes two distinct RPCs for query and update execution
 * ({@code executeQuery} streams result rows, {@code executeUpdate} returns a row count). The
 * generic {@link java.sql.Statement#execute(String)} / {@link java.sql.PreparedStatement#execute()}
 * contract does not tell the caller in advance which one applies, so this classifier inspects the
 * SQL text once, before any network round trip, to route the call to the correct RPC.
 *
 * <p>This is a heuristic, not a SQL parser: it cannot detect every case (e.g. a stored procedure
 * that conditionally returns rows depending on its arguments). Statements it cannot recognize as
 * query-shaped default to "update", matching the historical OJP behaviour. Tools that rely heavily
 * on {@code DatabaseMetaData} (e.g. SQL IDEs) frequently issue metadata calls that do not start
 * with {@code SELECT} (CTEs via {@code WITH}, catalog stored procedures such as
 * {@code sp_columns}/{@code sp_tables} on SQL Server, {@code EXEC} statements, etc.),
 * which is why the prefix list below is broader than a plain {@code SELECT} check.
 *
 * <p>{@code CALL} is intentionally not treated as query-shaped. Stored procedures are ambiguous:
 * some return rows, some only update state, and some only emit warnings. Routing all {@code CALL}
 * statements to {@code executeQuery} breaks procedures that do not produce a navigable result set,
 * which is worse than falling back to the historical "update" default for ambiguous statements.
 */
final class SqlStatementClassifier {

    private static final String[] RESULT_SET_PREFIXES = {
        "SELECT", "WITH", "EXEC ", "EXEC(", "EXECUTE ", "EXECUTE(",
        "SHOW ", "DESCRIBE ", "DESC ", "EXPLAIN ", "VALUES ", "VALUES(", "PRAGMA ", "SP_",
    };

    private SqlStatementClassifier() {
        // Utility class, no instances.
    }

    /**
     * Returns {@code true} when the given SQL text is likely to produce a {@link java.sql.ResultSet}
     * rather than an update count.
     *
     * @param sql the raw SQL text as supplied by the caller
     * @return {@code true} if the statement should be routed to {@code executeQuery}
     */
    static boolean looksLikeQuery(String sql) {
        if (sql == null) {
            return false;
        }
        String withoutLeadingComments = skipLeadingWhitespaceAndComments(sql);
        String upperSql = withoutLeadingComments.toUpperCase(Locale.ROOT);
        for (String prefix : RESULT_SET_PREFIXES) {
            if (upperSql.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Skips leading whitespace and SQL comments ({@code --} line comments and {@code /* *}{@code /}
     * block comments) from the given text, using a manual scan instead of a repeated-group regex
     * to avoid catastrophic backtracking on large inputs.
     *
     * @param sql the raw SQL text
     * @return the text with leading whitespace/comments removed
     */
    private static String skipLeadingWhitespaceAndComments(String sql) {
        int length = sql.length();
        int index = 0;
        boolean advanced = true;
        while (advanced) {
            advanced = false;
            while (index < length && Character.isWhitespace(sql.charAt(index))) {
                index++;
                advanced = true;
            }
            if (index + 1 < length && sql.charAt(index) == '-' && sql.charAt(index + 1) == '-') {
                int newLine = sql.indexOf('\n', index + 2);
                index = newLine < 0 ? length : newLine + 1;
                advanced = true;
            } else if (index + 1 < length && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                int endComment = sql.indexOf("*/", index + 2);
                index = endComment < 0 ? length : endComment + 2;
                advanced = true;
            }
        }
        return sql.substring(index);
    }
}
