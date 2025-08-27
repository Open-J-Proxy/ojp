package org.openjproxy.grpc.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Represents an OJP server endpoint with host and port information.
 */
@Data
@AllArgsConstructor
@EqualsAndHashCode
public class ServerEndpoint {
    private final String host;
    private final int port;
    private volatile boolean healthy = true;
    private volatile long lastFailureTime = 0;

    public ServerEndpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return getAddress();
    }
}
