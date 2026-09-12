package org.openjproxy.grpc.server.sql;

import java.util.regex.Pattern;

/**
 * Detects SQL statements that require session affinity to ensure they execute
 * on the same database connection consistently.
 *
 * This includes:
 * - Temporary tables (CREATE TEMPORARY TABLE, CREATE TEMP TABLE)
 * - Session variables (SET @var, SET SESSION, SET LOCAL)
 * - Prepared statements (PREPARE)
 *
 * Session affinity is critical in multinode deployments to ensure these
 * session-specific database features work correctly across requests.
 */
public class SqlSessionAffinityDetector {

    /**
     * Pattern to detect temporary table creation.
     * Matches:
     * - CREATE TEMPORARY TABLE
     * - CREATE TEMP TABLE
     * - CREATE GLOBAL TEMPORARY TABLE (Oracle)
     * - CREATE LOCAL TEMPORARY TABLE (H2, PostgreSQL)
     * - CREATE LOCAL TEMP TABLE
     * - DECLARE GLOBAL TEMPORARY TABLE (DB2)
     */
    private static final Pattern TEMP_TABLE_PATTERN = Pattern.compile(
        "^\\s*(CREATE\\s+(GLOBAL\\s+|LOCAL\\s+)?TEMP(ORARY)?\\s+TABLE|DECLARE\\s+GLOBAL\\s+TEMPORARY\\s+TABLE)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to detect session variable assignment.
     * Matches:
     * - SET @variable = value (MySQL)
     * - SET SESSION variable = value (MySQL, PostgreSQL)
     * - SET LOCAL variable = value (PostgreSQL)
     */
    private static final Pattern SESSION_VAR_PATTERN = Pattern.compile(
        "^\\s*SET\\s+(@|SESSION\\s+|LOCAL\\s+)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to detect prepared statement creation.
     * Matches:
     * - PREPARE statement_name FROM 'sql'
     */
    private static final Pattern PREPARE_PATTERN = Pattern.compile(
        "^\\s*PREPARE\\s+",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to detect SQL Server local temporary table creation.
     * Matches:
     * - CREATE TABLE #temp_table (SQL Server local temp)
     *
     * Note: ##global_temp is intentionally not matched as it's accessible
     * across sessions and doesn't require session affinity.
     */
    private static final Pattern SQLSERVER_TEMP_TABLE_PATTERN = Pattern.compile(
        "^\\s*CREATE\\s+TABLE\\s+#[^#]",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Determines if a SQL statement requires session affinity.
     *
     * This method performs lightweight pattern matching on the SQL to detect
     * session-specific operations. It only examines the beginning of the SQL
     * statement for performance.
     *
     * @param sql The SQL statement to analyze
     * @return true if the statement requires session affinity, false otherwise
     */
    public static boolean requiresSessionAffinity(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }

        // Only examine first 200 characters for performance
        // (session-specific keywords appear at the start)
        String sqlPrefix = sql.length() > 200 ? sql.substring(0, 200) : sql;
        String normalized = sqlPrefix.trim();

        // Check for temporary tables
        if (TEMP_TABLE_PATTERN.matcher(normalized).find()) {
            return true;
        }

        // Check for session variables
        if (SESSION_VAR_PATTERN.matcher(normalized).find()) {
            return true;
        }

        // Check for prepared statements
        if (PREPARE_PATTERN.matcher(normalized).find()) {
            return true;
        }

        // Check for SQL Server local temp tables
        if (SQLSERVER_TEMP_TABLE_PATTERN.matcher(normalized).find()) {
            return true;
        }

        return false;
    }

    /**
     * Determines if a SQL statement requires session affinity, with additional
     * context about the database type.
     *
     * This method allows for database-specific detection logic in the future
     * if needed. Currently, it delegates to the general detection method.
     *
     * @param sql The SQL statement to analyze
     * @param databaseProductName The database product name (e.g., "PostgreSQL", "MySQL")
     * @return true if the statement requires session affinity, false otherwise
     */
    public static boolean requiresSessionAffinity(String sql, String databaseProductName) {
        // For now, use general detection regardless of database type
        // Future enhancement: Add database-specific patterns if needed
        return requiresSessionAffinity(sql);
    }
}
