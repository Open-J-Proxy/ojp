package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.ParameterProto;
import com.openjproxy.grpc.ParameterTypeProto;
import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EagerCloseHelper}.
 */
class EagerCloseHelperTest {

    private static final String CONN_HASH = "testhash";

    // ========== isPlainDml tests ==========

    @Test
    void shouldReturnTrueWhenSqlIsInsert() {
        assertTrue(EagerCloseHelper.isPlainDml("INSERT INTO foo VALUES (1)"));
    }

    @Test
    void shouldReturnTrueWhenSqlIsUpdate() {
        assertTrue(EagerCloseHelper.isPlainDml("UPDATE foo SET x=1 WHERE id=1"));
    }

    @Test
    void shouldReturnTrueWhenSqlIsDelete() {
        assertTrue(EagerCloseHelper.isPlainDml("DELETE FROM foo WHERE id=1"));
    }

    @Test
    void shouldReturnTrueWhenSqlIsMerge() {
        assertTrue(EagerCloseHelper.isPlainDml(
                "MERGE INTO foo USING bar ON foo.id=bar.id WHEN MATCHED THEN UPDATE SET x=1"));
    }

    @Test
    void shouldReturnTrueWhenSqlIsInsertLowerCase() {
        assertTrue(EagerCloseHelper.isPlainDml("insert into foo values (1)"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsSelect() {
        assertFalse(EagerCloseHelper.isPlainDml("SELECT * FROM foo"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsSet() {
        assertFalse(EagerCloseHelper.isPlainDml("SET @var = 1"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsCreateTable() {
        assertFalse(EagerCloseHelper.isPlainDml("CREATE TABLE foo (id INT)"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsDropTable() {
        assertFalse(EagerCloseHelper.isPlainDml("DROP TABLE foo"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsPrepare() {
        assertFalse(EagerCloseHelper.isPlainDml("PREPARE stmt FROM 'SELECT 1'"));
    }

    @Test
    void shouldReturnFalseWhenSqlIsNull() {
        assertFalse(EagerCloseHelper.isPlainDml(null));
    }

    @Test
    void shouldReturnFalseWhenSqlIsEmpty() {
        assertFalse(EagerCloseHelper.isPlainDml(""));
    }

    @Test
    void shouldReturnFalseWhenSqlIsBlank() {
        assertFalse(EagerCloseHelper.isPlainDml("   "));
    }

    // ========== stripLeadingCommentsAndWhitespace tests ==========

    @Test
    void shouldStripLeadingWhitespace() {
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace("   INSERT INTO foo VALUES (1)");
        assertTrue(result.startsWith("INSERT"));
    }

    @Test
    void shouldStripLeadingBlockComment() {
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace(
                "/* comment */ INSERT INTO foo VALUES (1)");
        assertTrue(result.startsWith("INSERT"));
    }

    @Test
    void shouldStripLeadingLineComment() {
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace(
                "-- line comment\nINSERT INTO foo VALUES (1)");
        assertTrue(result.startsWith("INSERT"));
    }

    @Test
    void shouldStripMultipleLeadingComments() {
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace(
                "/* c1 */\n-- c2\n/* c3 */ INSERT INTO foo VALUES (1)");
        assertTrue(result.startsWith("INSERT"));
    }

    @Test
    void shouldReturnSqlUnchangedWhenNoLeadingComments() {
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace("UPDATE foo SET x=1");
        assertTrue(result.startsWith("UPDATE"));
    }

    @Test
    void shouldHandleMalformedNestedBlockCommentByStoppingAtFirstClose() {
        // SQL does not support nested block comments; the first */ ends the comment.
        // /* outer /* inner */ is treated as: comment ends at first */, leaving " is still outer */"
        // which does not start with a comment token, so the rest is kept as-is.
        String result = EagerCloseHelper.stripLeadingCommentsAndWhitespace(
                "/* outer /* inner */ still outer */INSERT INTO foo VALUES (1)");
        // After the first block comment is stripped, remaining text starts with " still outer */"
        // which is not a DML keyword — isPlainDml should return false
        assertFalse(EagerCloseHelper.isPlainDml(
                "/* outer /* inner */ still outer */INSERT INTO foo VALUES (1)"));
    }

    // ========== canEagerCloseExecuteUpdate tests ==========

    @Test
    void shouldReturnTrueForSimpleInsertWithNoSession() {
        StatementRequest request = buildRequest("INSERT INTO foo VALUES (1)", noSession());
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnTrueForSimpleUpdateWithNoSession() {
        StatementRequest request = buildRequest("UPDATE foo SET x=1 WHERE id=1", noSession());
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnTrueForSimpleDeleteWithNoSession() {
        StatementRequest request = buildRequest("DELETE FROM foo WHERE id=1", noSession());
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseWhenSessionUuidIsPresent() {
        SessionInfo session = SessionInfo.newBuilder()
                .setConnHash(CONN_HASH)
                .setSessionUUID("some-uuid")
                .build();
        StatementRequest request = buildRequest("INSERT INTO foo VALUES (1)", session);
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, session));
    }

    @Test
    void shouldReturnFalseWhenInsideActiveTransaction() {
        TransactionInfo txInfo = TransactionInfo.newBuilder()
                .setTransactionUUID("tx-uuid")
                .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                .build();
        SessionInfo session = SessionInfo.newBuilder()
                .setConnHash(CONN_HASH)
                .setTransactionInfo(txInfo)
                .build();
        StatementRequest request = buildRequest("INSERT INTO foo VALUES (1)", session);
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, session));
    }

    @Test
    void shouldReturnFalseForBatchOperation() {
        PropertyEntry batchFlag = PropertyEntry.newBuilder()
                .setKey(CommonConstants.PREPARED_STATEMENT_ADD_BATCH_FLAG)
                .setBoolValue(true)
                .build();
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (1)")
                .setSession(noSession())
                .addProperties(batchFlag)
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseWhenGeneratedKeysRequested() {
        PropertyEntry genKeys = PropertyEntry.newBuilder()
                .setKey(CommonConstants.STATEMENT_AUTO_GENERATED_KEYS_KEY)
                .setIntValue(Statement.RETURN_GENERATED_KEYS)
                .build();
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (1)")
                .setSession(noSession())
                .addProperties(genKeys)
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseWhenStatementUuidIsPresent() {
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (1)")
                .setSession(noSession())
                .setStatementUUID("existing-stmt-uuid")
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForSessionAffinitySqlCreateTempTable() {
        StatementRequest request = buildRequest("CREATE TEMPORARY TABLE temp_foo (id INT)", noSession());
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForSessionVariableStatement() {
        StatementRequest request = buildRequest("SET SESSION var = 1", noSession());
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForBlobParameter() {
        ParameterProto blobParam = ParameterProto.newBuilder()
                .setIndex(1)
                .setType(ParameterTypeProto.PT_BLOB)
                .build();
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (?)")
                .setSession(noSession())
                .addParameters(blobParam)
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForClobParameter() {
        ParameterProto clobParam = ParameterProto.newBuilder()
                .setIndex(1)
                .setType(ParameterTypeProto.PT_CLOB)
                .build();
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (?)")
                .setSession(noSession())
                .addParameters(clobParam)
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForBinaryStreamParameter() {
        ParameterProto streamParam = ParameterProto.newBuilder()
                .setIndex(1)
                .setType(ParameterTypeProto.PT_BINARY_STREAM)
                .build();
        StatementRequest request = StatementRequest.newBuilder()
                .setSql("INSERT INTO foo VALUES (?)")
                .setSession(noSession())
                .addParameters(streamParam)
                .build();
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnFalseForSelectStatement() {
        StatementRequest request = buildRequest("SELECT * FROM foo", noSession());
        assertFalse(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnTrueForInsertAfterLeadingBlockComment() {
        StatementRequest request = buildRequest("/* audit log */ INSERT INTO foo VALUES (1)", noSession());
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnTrueForInsertAfterLeadingLineComment() {
        StatementRequest request = buildRequest("-- insert comment\nINSERT INTO foo VALUES (1)", noSession());
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession()));
    }

    @Test
    void shouldReturnTrueWhenNullSessionProvided() {
        StatementRequest request = buildRequest("INSERT INTO foo VALUES (1)", noSession());
        // Null session means no active session — eligible for eager close
        assertTrue(EagerCloseHelper.canEagerCloseExecuteUpdate(request, null));
    }

    // ========== Helpers ==========

    private static SessionInfo noSession() {
        return SessionInfo.newBuilder()
                .setConnHash(CONN_HASH)
                .build();
    }

    private static StatementRequest buildRequest(String sql, SessionInfo session) {
        return StatementRequest.newBuilder()
                .setSql(sql)
                .setSession(session)
                .build();
    }
}
