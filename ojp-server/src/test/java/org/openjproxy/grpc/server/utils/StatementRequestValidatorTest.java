package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.StatementRequest;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementRequestValidatorTest {

    @Test
    void shouldRequestUpdateResultSetWhenFlagIsTrue() {
        StatementRequest request = StatementRequest.newBuilder()
                .addProperties(PropertyEntry.newBuilder()
                        .setKey(CommonConstants.STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY)
                        .setBoolValue(true))
                .build();

        assertTrue(StatementRequestValidator.requestsUpdateResultSet(request));
    }

    @Test
    void shouldNotRequestUpdateResultSetWhenFlagIsMissingOrFalse() {
        StatementRequest noFlag = StatementRequest.newBuilder().build();
        StatementRequest falseFlag = StatementRequest.newBuilder()
                .addProperties(PropertyEntry.newBuilder()
                        .setKey(CommonConstants.STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY)
                        .setBoolValue(false))
                .build();

        assertFalse(StatementRequestValidator.requestsUpdateResultSet(noFlag));
        assertFalse(StatementRequestValidator.requestsUpdateResultSet(falseFlag));
    }

    @Test
    void shouldReadParameterSetSize() {
        StatementRequest request = StatementRequest.newBuilder()
                .addProperties(PropertyEntry.newBuilder()
                        .setKey(CommonConstants.PREPARED_STATEMENT_PARAMETER_SET_SIZE_KEY)
                        .setIntValue(3))
                .build();

        assertEquals(3, StatementRequestValidator.parameterSetSize(request));
        assertEquals(0, StatementRequestValidator.parameterSetSize(StatementRequest.newBuilder().build()));
    }
}
