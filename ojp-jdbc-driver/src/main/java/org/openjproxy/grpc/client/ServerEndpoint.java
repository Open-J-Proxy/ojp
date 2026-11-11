package org.openjproxy.grpc.client;

import lombok.Getter;

import java.util.Objects;

/**
 * Represents an OJP server endpoint with host and port information.
 * Tracks the health status of each server for failover purposes.
 */
public class ServerEndpoint {
    private final String host;
    private final int port;
    private final String dataSourceName;
    @Getter
    private final int priority;
    private volatile boolean healthy = true;
    private volatile long lastFailureTime = 0;

    public ServerEndpoint(String host, int port) {
        this(host, port, "default");
    }

    public ServerEndpoint(String host, int port, String dataSourceName) {
        this(host, port, dataSourceName, 1);
    }

    public ServerEndpoint(String host, int port, String dataSourceName, int priority) {
        this.host = Objects.requireNonNull(host, "Host cannot be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.dataSourceName = dataSourceName != null ? dataSourceName : "default";
        if (priority < 1) {
            throw new IllegalArgumentException("Server priority must be a positive integer, got: " + priority);
        }
        this.priority = priority;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public String getAddress() {
        return host + ":" + port;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public long getLastFailureTime() {
        return lastFailureTime;
    }

    public void setLastFailureTime(long lastFailureTime) {
        this.lastFailureTime = lastFailureTime;
    }

    /**
     * Marks this server as healthy.
     */
    public void markHealthy() {
        this.healthy = true;
        this.lastFailureTime = 0;
    }

    /**
     * Marks this server as unhealthy.
     */
    public void markUnhealthy() {
        this.healthy = false;
        this.lastFailureTime = System.nanoTime();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ServerEndpoint that = (ServerEndpoint) o;
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return getAddress();
    }
}
