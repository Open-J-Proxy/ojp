package org.openjproxy.grpc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class GrpcConfig {
    private int maxInboundMessageSize;
    private int maxOutboundMessageSize;

    public GrpcConfig(Properties props) {
        // 4MB default (To avoid null references)
        this.maxInboundMessageSize = Integer.parseInt(
                props.getProperty("ojp.grpc.maxInboundMessageSize", "4194304"));
        this.maxOutboundMessageSize = Integer.parseInt(
                props.getProperty("ojp.grpc.maxOutboundMessageSize", "4194304"));
    }

    public int getMaxInboundMessageSize() {
        return this.maxInboundMessageSize;
    }

    public int getMaxOutboundMessageSize() {
        return this.maxOutboundMessageSize;
    }

    public static GrpcConfig load() throws IOException {

        try (InputStream in = GrpcConfig.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (in == null) {
                throw new FileNotFoundException("Could not find ojp.properties in classpath");
            }
            Properties props = new Properties();
            props.load(in);
            return new GrpcConfig(props);
        }
    }
}
