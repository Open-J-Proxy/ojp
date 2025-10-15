package org.openjproxy.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for gRPC client settings.
 * <p>
 * Loads values such as maximum inbound and outbound message sizes
 * from a properties file named {@code ojp.properties} located in the classpath.
 * </p>
 * <p>
 * If properties are missing, default values (16MB) are applied.
 * </p>
 */
public class GrpcClientConfig {
    /** Default message size in bytes (16MB) */
    private static final String DEFAULT_SIZE = "16777216";

    private int maxInboundMessageSize;
    private int maxOutboundMessageSize;

    /**
     * Constructs a new {@code GrpcClientConfig} using the provided {@link Properties}.
     * <p>
     * If expected keys are missing, default values are used.
     * </p>
     *
     * @param props the {@link Properties} object containing configuration values
     */
    public GrpcClientConfig(Properties props) {
        this.maxInboundMessageSize = Integer.parseInt(
                props.getProperty("ojp.grpc.maxInboundMessageSize", DEFAULT_SIZE));
        this.maxOutboundMessageSize = Integer.parseInt(
                props.getProperty("ojp.grpc.maxOutboundMessageSize", DEFAULT_SIZE));
    }

    /**
     * Returns the maximum allowed inbound message size (in bytes).
     *
     * @return the max inbound message size
     */
    public int getMaxInboundMessageSize() {
        return this.maxInboundMessageSize;
    }

    /**
     * Returns the maximum allowed outbound message size (in bytes).
     *
     * @return the max outbound message size
     */
    public int getMaxOutboundMessageSize() {
        return this.maxOutboundMessageSize;
    }

    /**
     * Loads the gRPC client configuration from a {@code ojp.properties} file
     * located in the classpath.
     *
     * @return a new instance of {@code GrpcClientConfig} with loaded values
     * @throws IOException if the file is not found or cannot be read
     */
    public static GrpcClientConfig load() throws IOException {
        try (InputStream in = GrpcClientConfig.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (in == null) {
                throw new FileNotFoundException("Could not find ojp.properties in classpath");
            }
            Properties props = new Properties();
            props.load(in);
            return new GrpcClientConfig(props);
        }
    }
}
