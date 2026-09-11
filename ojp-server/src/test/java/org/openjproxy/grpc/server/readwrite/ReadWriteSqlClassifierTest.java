package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for ReadWriteSqlClassifier
 */
class ReadWriteSqlClassifierTest {

    @Test
    void shouldClassifySelectAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("SELECT * FROM users"));
    }

    @Test
    void shouldClassifySelectWithLeadingWhitespaceAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("  SELECT id FROM orders"));
    }

    @Test
    void shouldClassifyLowercaseSelectAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("select count(*) from items"));
    }

    @Test
    void shouldClassifyWithClauseAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void shouldClassifyExplainAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("EXPLAIN SELECT * FROM users"));
    }

    @Test
    void shouldClassifyShowAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("SHOW TABLES"));
    }

    @Test
    void shouldClassifyDescribeAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("DESCRIBE users"));
    }

    @Test
    void shouldClassifyDescAbbreviationAsRead() {
        assertEquals(ReadWriteSqlClassifier.QueryType.READ,
                ReadWriteSqlClassifier.classify("DESC users"));
    }

    @Test
    void shouldClassifyInsertAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("INSERT INTO users VALUES (1, 'alice')"));
    }

    @Test
    void shouldClassifyUpdateAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("UPDATE users SET name = 'bob' WHERE id = 1"));
    }

    @Test
    void shouldClassifyDeleteAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("DELETE FROM users WHERE id = 1"));
    }

    @Test
    void shouldClassifyCreateTableAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("CREATE TABLE users (id INT)"));
    }

    @Test
    void shouldClassifyDropTableAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("DROP TABLE users"));
    }

    @Test
    void shouldClassifyNullAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify(null));
    }

    @Test
    void shouldClassifyBlankStringAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("   "));
    }

    @Test
    void shouldClassifyMergeAsWrite() {
        assertEquals(ReadWriteSqlClassifier.QueryType.WRITE,
                ReadWriteSqlClassifier.classify("MERGE INTO users USING src ON (users.id = src.id)"));
    }
}
