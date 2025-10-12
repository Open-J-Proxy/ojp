package org.openjproxy.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.Properties;

public class GrpcChannelFactory {
    // Initialized with 4MB as default
    private static int maxInboundMessageSize = 4194304, maxOutboundMessageSize = 4194304;

    static GrpcConfig grpcConfig;

    public GrpcChannelFactory() {
        initializeGrpcConfig();
    }

    public static void initializeGrpcConfig() {
        try {
            grpcConfig = GrpcConfig.load();
        } catch (IOException e) {
            e.printStackTrace();
            grpcConfig = new GrpcConfig(new Properties());
        }

        maxInboundMessageSize = grpcConfig.getMaxInboundMessageSize();
        maxOutboundMessageSize = grpcConfig.getMaxOutboundMessageSize();
    }

    public static ManagedChannel createChannel(String host, int port, int maxInboundSize, int maxOutboundSize) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(maxInboundSize)
                .maxOutboundMessageSize(maxOutboundSize)
                .build();
    }

    public static ManagedChannel createChannel(String host, int port) {
        return createChannel(host, port, maxInboundMessageSize, maxOutboundMessageSize);
    }

    public static ManagedChannel createChannel(String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .maxInboundMessageSize(maxInboundMessageSize)
                .maxOutboundMessageSize(maxOutboundMessageSize)
                .build();
    }
}
