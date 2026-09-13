package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.ParameterTypeProto;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementRequest;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an executeUpdate operation qualifies for the eager-close path.
 *
 * <p>Eager close acquires a connection, runs the DML, and immediately releases the
 * connection without creating a server-side session. This avoids holding JDBC
 * resources longer than necessary for simple non-transactional DML operations.
 *
 * <p>All checks are conservative: when in doubt, {@code canEagerCloseExecuteUpdate}
 * returns {@code false} and the standard path is used.
 */
public class EagerCloseHelper {

    /** Matches the first keyword of a plain DML statement (after comment stripping). */
    private static final Pattern DML_PATTERN = Pattern.compile(
            "^(INSERT|UPDATE|DELETE|MERGE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches a leading SQL block comment {@code /* ... *\/}.
     * Uses non-greedy {@code .*?} which is safe for the short SQL strings typical in this context. */
    private static final Pattern LEADING_BLOCK_COMMENT = Pattern.compile(
            "^/\\*.*?\\*/",
            Pattern.DOTALL
    );
    /** Matches a leading SQL line comment {@code -- ...} up to the end of the line. */
    private static final Pattern LEADING_LINE_COMMENT = Pattern.compile(
            "^--[^\r\n]*[\r\n]?"
    );

    private EagerCloseHelper() {
    }

    /**
     * Returns {@code true} if the given executeUpdate request can use the eager-close path.
     *
     * <p>Eager close is safe only when all of the following hold:
     * <ul>
     *   <li>No active server-side session (session-pinned connection)</li>
     *   <li>No active transaction</li>
     *   <li>Not a batch operation</li>
     *   <li>No generated-keys tracking requested</li>
     *   <li>No existing prepared statement UUID (session-held statement)</li>
     *   <li>No session-affinity SQL (temp tables, session variables, PREPARE, etc.)</li>
     *   <li>No LOB or stream parameters that require session-backed storage</li>
     *   <li>SQL is a plain DML statement: INSERT, UPDATE, DELETE, or MERGE</li>
     * </ul>
     *
     * @param request the statement request
     * @param session the session info from the request
     * @return {@code true} if eager close is safe for this operation
     */
    public static boolean canEagerCloseExecuteUpdate(StatementRequest request, SessionInfo session) {
        // Must not have an active session (session-pinned connection)
        if (session != null && !session.getSessionUUID().isEmpty()) {
            return false;
        }

        // Must not be inside an active transaction
        if (session != null
                && session.getTransactionInfo() != null
                && !session.getTransactionInfo().getTransactionUUID().isEmpty()) {
            return false;
        }

        // Must not be a batch operation
        if (StatementRequestValidator.isAddBatchOperation(request)) {
            return false;
        }

        // Must not request generated keys (cannot snapshot without proto changes)
        if (StatementRequestValidator.requiresGeneratedKeysTracking(request)) {
            return false;
        }

        // Must not reuse an existing session-held prepared statement
        if (request.getStatementUUID() != null && !request.getStatementUUID().isEmpty()) {
            return false;
        }

        // Must not require session affinity (temp tables, session vars, PREPARE, etc.)
        if (SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql())) {
            return false;
        }

        // Must not have LOB or stream parameters that require session-backed storage
        if (hasSessionRequiringParameters(request)) {
            return false;
        }

        // SQL must be a plain DML statement
        return isPlainDml(request.getSql());
    }

    /**
     * Returns {@code true} if the SQL (after stripping leading whitespace and comments)
     * starts with INSERT, UPDATE, DELETE, or MERGE.
     *
     * @param sql the SQL string to inspect
     * @return {@code true} if the SQL is a plain DML statement
     */
    static boolean isPlainDml(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String stripped = stripLeadingCommentsAndWhitespace(sql);
        return DML_PATTERN.matcher(stripped).find();
    }

    /**
     * Strips leading whitespace and SQL comments (block {@code /* *\/} and line {@code --})
     * from the given SQL string. Iterates until no more leading comments are found.
     *
     * @param sql the SQL string to process
     * @return the SQL with leading comments and whitespace removed
     */
    static String stripLeadingCommentsAndWhitespace(String sql) {
        String current = sql.trim();
        String prev;
        do {
            prev = current;
            Matcher blockMatcher = LEADING_BLOCK_COMMENT.matcher(current);
            if (blockMatcher.find()) {
                current = current.substring(blockMatcher.end()).trim();
            }
            Matcher lineMatcher = LEADING_LINE_COMMENT.matcher(current);
            if (lineMatcher.find()) {
                current = current.substring(lineMatcher.end()).trim();
            }
        } while (!current.equals(prev));
        return current;
    }

    /**
     * Returns {@code true} if any parameter in the request requires session-backed storage
     * (BLOB, CLOB, ASCII_STREAM, UNICODE_STREAM, or BINARY_STREAM). Such parameters cannot
     * be safely handled without an active session.
     *
     * @param request the statement request
     * @return {@code true} if any session-requiring parameter is present
     */
    private static boolean hasSessionRequiringParameters(StatementRequest request) {
        for (var param : request.getParametersList()) {
            ParameterTypeProto type = param.getType();
            if (type == ParameterTypeProto.PT_BLOB
                    || type == ParameterTypeProto.PT_CLOB
                    || type == ParameterTypeProto.PT_ASCII_STREAM
                    || type == ParameterTypeProto.PT_UNICODE_STREAM
                    || type == ParameterTypeProto.PT_BINARY_STREAM) {
                return true;
            }
        }
        return false;
    }
}
