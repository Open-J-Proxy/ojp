package org.openjproxy.grpc.server;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyThrottleInterceptorTest {

    @Test
    void shouldAllowRequestWhenUnderLimit() {
        ConcurrencyThrottleInterceptor interceptor = new ConcurrencyThrottleInterceptor(1);
        TrackingServerCall call = new TrackingServerCall(newMethodDescriptor());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };
        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        assertNotNull(result);
        assertTrue(nextCalled.get());
        assertEquals(0, call.getCloseCount());
        assertEquals(1, interceptor.getCurrentInFlightRequests());

        result.onCancel();
        assertEquals(0, interceptor.getCurrentInFlightRequests());
    }

    @Test
    void shouldRejectRequestWhenOverLimit() {
        ConcurrencyThrottleInterceptor interceptor = new ConcurrencyThrottleInterceptor(1);
        TrackingServerCall firstCall = new TrackingServerCall(newMethodDescriptor());
        TrackingServerCall secondCall = new TrackingServerCall(newMethodDescriptor());
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return new ServerCall.Listener<>() { };
        };

        interceptor.interceptCall(firstCall, new Metadata(), next);
        nextCalled.set(false);
        ServerCall.Listener<String> secondResult = interceptor.interceptCall(secondCall, new Metadata(), next);

        assertNotNull(secondResult);
        assertFalse(nextCalled.get());
        assertEquals(1, secondCall.getCloseCount());
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, secondCall.getClosedStatus().getCode());
        assertEquals(1, interceptor.getCurrentInFlightRequests());
    }

    @Test
    void shouldSkipThrottlingWhenLimitDisabled() {
        ConcurrencyThrottleInterceptor interceptor = new ConcurrencyThrottleInterceptor(0);
        TrackingServerCall call = new TrackingServerCall(newMethodDescriptor());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        ServerCall.Listener<String> listener = new ServerCall.Listener<>() { };

        ServerCallHandler<String, String> next = (callArg, headers) -> {
            nextCalled.set(true);
            return listener;
        };

        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        assertNotNull(result);
        assertSame(listener, result);
        assertTrue(nextCalled.get());
        assertEquals(0, interceptor.getCurrentInFlightRequests());
    }

    private MethodDescriptor<String, String> newMethodDescriptor() {
        MethodDescriptor.Marshaller<String> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(String value) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public String parse(InputStream stream) {
                return "";
            }
        };

        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("test.Service/TestMethod")
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }

    private static final class TrackingServerCall extends ServerCall<String, String> {
        private final MethodDescriptor<String, String> methodDescriptor;
        private Status closedStatus;
        private int closeCount;

        private TrackingServerCall(MethodDescriptor<String, String> methodDescriptor) {
            this.methodDescriptor = methodDescriptor;
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
            this.closeCount++;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void setMessageCompression(boolean enabled) {
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Attributes getAttributes() {
            return Attributes.EMPTY;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return methodDescriptor;
        }

        Status getClosedStatus() {
            return closedStatus;
        }

        int getCloseCount() {
            return closeCount;
        }
    }
}
