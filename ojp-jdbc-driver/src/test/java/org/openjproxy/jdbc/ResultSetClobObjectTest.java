package org.openjproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.ResultRow;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.io.Reader;
import java.io.StringWriter;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultSetClobObjectTest {

    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("test-conn-hash").build();
    private static final String CLOB_UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String CLOB_TEXT = "Oracle CLOB text for GUI clients";

    private FakeStatementService fakeStatementService;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() {
        this.fakeStatementService = new FakeStatementService();
        this.connection = new Connection(SESSION, this.fakeStatementService, DbName.ORACLE);
        this.statement = new Statement(this.connection, this.fakeStatementService);
    }

    @Test
    void shouldReturnClobFromGetObjectWhenValueIsClobPlaceholder() throws Exception {
        ResultSet resultSet = newResultSetWithClobPlaceholder();

        assertTrue(resultSet.next());

        Object value = resultSet.getObject(1);

        assertInstanceOf(Clob.class, value);
        assertEquals(CLOB_TEXT, ((Clob) value).getSubString(1, CLOB_TEXT.length()));
    }

    @Test
    void shouldReturnStringFromTypedGetObjectWhenStringRequested() throws Exception {
        ResultSet resultSet = newResultSetWithClobPlaceholder();
        this.fakeStatementService.setCallResourceReturnValue((long) CLOB_TEXT.length());

        assertTrue(resultSet.next());

        String value = resultSet.getObject("clob_col", String.class);

        assertEquals(CLOB_TEXT, value);
    }

    @Test
    void shouldReadCharacterStreamFromClobResolvedByGetObject() throws Exception {
        ResultSet resultSet = newResultSetWithClobPlaceholder();
        this.fakeStatementService.setCallResourceReturnValue((long) CLOB_TEXT.length());

        assertTrue(resultSet.next());

        Clob clob = resultSet.getObject(1, Clob.class);
        try (Reader reader = clob.getCharacterStream()) {
            StringWriter writer = new StringWriter();
            reader.transferTo(writer);
            assertEquals(CLOB_TEXT, writer.toString());
        }
    }

    @Test
    void shouldReadCharacterStreamDirectlyFromResultSetForClobColumn() throws Exception {
        ResultSet resultSet = newResultSetWithClobPlaceholder();
        this.fakeStatementService.setCallResourceReturnValue((long) CLOB_TEXT.length());

        assertTrue(resultSet.next());

        try (Reader reader = resultSet.getCharacterStream("clob_col")) {
            StringWriter writer = new StringWriter();
            reader.transferTo(writer);
            assertEquals(CLOB_TEXT, writer.toString());
        }
    }

    private ResultSet newResultSetWithClobPlaceholder() throws SQLException {
        this.fakeStatementService.setReadLobResult(Collections.singletonList(
                LobDataBlock.newBuilder()
                        .setPosition(1)
                        .setData(ByteString.copyFromUtf8(CLOB_TEXT))
                        .build()
        ).iterator());
        OpResult singleBlock = OpResult.newBuilder()
                .setSession(SESSION)
                .setType(ResultType.RESULT_SET_DATA)
                .setQueryResult(OpQueryResultProto.newBuilder()
                        .setResultSetUUID("rs-uuid")
                        .addLabels("clob_col")
                        .addRows(ResultRow.newBuilder()
                                .addColumns(org.openjproxy.grpc.ProtoConverter.toParameterValue(
                                        CommonConstants.OJP_CLOB_PREFIX + CLOB_UUID))
                                .build())
                        .build())
                .build();
        Iterator<OpResult> iterator = Collections.singletonList(singleBlock).iterator();
        ResultSet resultSet = new ResultSet(iterator, this.fakeStatementService, this.statement);
        resultSet.setConnection(this.connection);
        return resultSet;
    }
}
