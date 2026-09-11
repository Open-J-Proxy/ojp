package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SqlStatementClassifier#looksLikeQuery(String)}.
 */
class SqlStatementClassifierTest {

    @ParameterizedTest
    @NullSource
    void shouldReturnFalseForNullSql(String sql) {
        assertFalse(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM t",
        "select * from t",
        "Select * From t",
        "  SELECT * FROM t",
        "\n\tSELECT * FROM t",
    })
    void shouldRecognizeSelectStatements(String sql) {
        assertTrue(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH cte AS (SELECT 1) SELECT * FROM cte",
        "with cte as (select 1) select * from cte",
    })
    void shouldRecognizeCommonTableExpressions(String sql) {
        assertTrue(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "EXEC sp_help",
        "exec sp_help",
        "EXEC(sp_help)",
        "EXECUTE sp_help",
        "EXECUTE(sp_help)",
        "SHOW TABLES",
        "DESCRIBE t",
        "DESC t",
        "EXPLAIN SELECT * FROM t",
        "VALUES (1, 2, 3)",
        "VALUES(1)",
        "PRAGMA table_info(t)",
        "sp_columns t",
        "SP_TABLES",
    })
    void shouldRecognizeOtherQueryShapedStatements(String sql) {
        assertTrue(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CALL my_proc()",
        "call my_proc()",
        "CALL(1)",
    })
    void shouldTreatCallStatementsAsAmbiguousAndDefaultToUpdate(String sql) {
        assertFalse(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO t VALUES (1)",
        "UPDATE t SET x = 1",
        "DELETE FROM t",
        "CREATE TABLE t (id INT)",
        "DROP TABLE t",
        "ALTER TABLE t ADD COLUMN x INT",
        "MERGE INTO t USING s ON t.id = s.id",
        "TRUNCATE TABLE t",
    })
    void shouldNotRecognizeUpdateShapedStatements(String sql) {
        assertFalse(SqlStatementClassifier.looksLikeQuery(sql));
    }

    @Test
    void shouldSkipLeadingLineCommentBeforeSelect() {
        assertTrue(SqlStatementClassifier.looksLikeQuery("-- a leading line comment\nSELECT * FROM t"));
    }

    @Test
    void shouldSkipLeadingBlockCommentBeforeSelect() {
        assertTrue(SqlStatementClassifier.looksLikeQuery("/* a leading block comment */ SELECT * FROM t"));
    }

    @Test
    void shouldSkipMultipleLeadingCommentsAndWhitespaceBeforeSelect() {
        assertTrue(SqlStatementClassifier.looksLikeQuery(
                "/* multi\nline block comment */\n-- then a line comment\nSELECT * FROM t"));
    }

    @Test
    void shouldNotRecognizeStatementThatIsOnlyALineComment() {
        // Nothing follows the comment (it consumes the rest of the input), so no prefix matches.
        assertFalse(SqlStatementClassifier.looksLikeQuery("  \n  -- comment only, nothing after (until EOF)"));
    }

    @Test
    void shouldNotRecognizeStatementWithUnterminatedBlockComment() {
        // The unterminated block comment consumes the rest of the input, including the SELECT keyword.
        assertFalse(SqlStatementClassifier.looksLikeQuery("/* unterminated block comment SELECT * FROM t"));
    }
}
