package org.openjproxy.grpc.client;

import io.grpc.*;

public class OutboundSizeLimitingInterceptor implements ClientInterceptor {
    private final int maxSizeBytes;

    public OutboundSizeLimitingInterceptor(int maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {

            @Override
            public void sendMessage(ReqT message) {
                try {
                    MethodDescriptor.Marshaller<ReqT> marshaller = method.getRequestMarshaller();
                    byte[] serialized = marshaller.stream(message).readAllBytes();
                    if (serialized.length > maxSizeBytes) {
                        throw new IllegalArgumentException(
                                "Outbound message too large: " + serialized.length + " bytes");
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize message for size check", e);
                }

                super.sendMessage(message);
            }
        };
    }
}
