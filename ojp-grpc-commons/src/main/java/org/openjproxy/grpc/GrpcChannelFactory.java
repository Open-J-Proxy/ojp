package org.openjproxy.grpc;

import org.openjproxy.config.GrpcClientConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.Properties;
/**
 * Factory class for creating and configuring gRPC {@link ManagedChannel} instances.
 * 
 * <p>This class reads configuration from {@link GrpcClientConfig} and provides overloaded methods
 * to create channels with default or custom settings.</p>
 * 
 * <p>By default, it uses a maximum inbound and outbound message size of 16MB.</p>
 */
public class GrpcChannelFactory {
    /** Default maximum inbound message size (16MB) */
    private static int maxInboundMessageSize = 16777216;

    /** Default maximum outbound message size (16MB) */
    private static int maxOutboundMessageSize = 16777216;

    /** gRPC client configuration loaded from properties file */
    static GrpcClientConfig grpcConfig;

    /**
     * Constructor that initializes gRPC client configuration.
     */
    public GrpcChannelFactory() {
        initializeGrpcConfig();
    }

    /**
     * Initializes the gRPC client configuration from external properties.
     * 
     * <p>If loading fails, it falls back to an empty configuration.</p>
     */
    public static void initializeGrpcConfig() {
        try {
            grpcConfig = GrpcClientConfig.load();
        } catch (IOException e) {
            e.printStackTrace();
            grpcConfig = new GrpcClientConfig(new Properties());
        }

        maxInboundMessageSize = grpcConfig.getMaxInboundMessageSize();
        maxOutboundMessageSize = grpcConfig.getMaxOutboundMessageSize();
    }

    /**
     * Creates a new {@link ManagedChannel} for the given host and port with specified
     * inbound and outbound message size limits.
     *
     * @param host The gRPC server host
     * @param port The gRPC server port
     * @param maxInboundSize Maximum allowed inbound message size in bytes
     * @param maxOutboundSize Maximum allowed outbound message size in bytes (unused)
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String host, int port, int maxInboundSize, int maxOutboundSize) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(maxInboundSize)
                .build();
    }

    /**
     * Creates a new {@link ManagedChannel} for the given host and port using default message size limits.
     *
     * @param host The gRPC server host
     * @param port The gRPC server port
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String host, int port) {
        return createChannel(host, port, maxInboundMessageSize, maxOutboundMessageSize);
    }

    /**
     * Creates a new {@link ManagedChannel} for the given target string (e.g., "localhost:50051").
     * Uses default message size limits.
     *
     * @param target A target string in the form "host:port"
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .maxInboundMessageSize(maxInboundMessageSize)
                .build();
    }
}
