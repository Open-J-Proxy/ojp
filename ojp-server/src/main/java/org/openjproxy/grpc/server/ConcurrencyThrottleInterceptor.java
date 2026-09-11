package org.openjproxy.grpc.server;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interceptor that limits concurrent in-flight gRPC requests and fast-fails when overloaded.
 */
@Slf4j
public class ConcurrencyThrottleInterceptor implements ServerInterceptor {

    private final AtomicInteger currentInFlightRequests = new AtomicInteger(0);
    private final int maxConcurrentRequests;

    public ConcurrencyThrottleInterceptor(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (maxConcurrentRequests <= 0) {
            return next.startCall(call, headers);
        }

        int currentInFlight = currentInFlightRequests.incrementAndGet();
        if (currentInFlight > maxConcurrentRequests) {
            currentInFlightRequests.decrementAndGet();
            String methodName = call.getMethodDescriptor().getFullMethodName();
            log.warn("Request rejected due to concurrency limit: method={}, inFlight={}, maxConcurrentRequests={}",
                    methodName, currentInFlight, maxConcurrentRequests);
            call.close(Status.RESOURCE_EXHAUSTED.withDescription("Server overloaded: too many concurrent requests"),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        }

        AtomicBoolean requestReleased = new AtomicBoolean(false);
        ServerCall<ReqT, RespT> trackingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                releaseRequestCounter(requestReleased);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener = next.startCall(trackingCall, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onCancel() {
                releaseRequestCounter(requestReleased);
                super.onCancel();
            }
        };
    }

    private void releaseRequestCounter(AtomicBoolean requestReleased) {
        if (requestReleased.compareAndSet(false, true)) {
            currentInFlightRequests.decrementAndGet();
        }
    }

    /**
     * Exposed for tests to validate in-flight accounting.
     */
    int getCurrentInFlightRequests() {
        return currentInFlightRequests.get();
    }
}
