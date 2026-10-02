package org.openjproxy.grpc.client;

import java.util.Objects;

/**
 * Represents an OJP server endpoint with host and port information.
 * Tracks the health status of each server for failover purposes.
 */
public class ServerEndpoint {
    private final String host;
    private final int port;
    private final String dataSourceName;
    private volatile boolean healthy = true;
    private volatile boolean draining = false;
    private volatile long lastFailureTime = 0;

    public ServerEndpoint(String host, int port) {
        this(host, port, "default");
    }

    public ServerEndpoint(String host, int port, String dataSourceName) {
        this.host = Objects.requireNonNull(host, "Host cannot be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.dataSourceName = dataSourceName != null ? dataSourceName : "default";
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

    /**
     * Sets the health flag. Any explicit health change also clears the draining flag:
     * {@code true} means the server is back in service, {@code false} means it really failed.
     */
    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
        this.draining = false;
    }

    /**
     * Returns {@code true} while the server is draining (graceful shutdown). A draining server
     * is not {@link #isHealthy() healthy}, so it receives no new sessions, but sessions already
     * bound to it keep being routed there until they finish.
     */
    public boolean isDraining() {
        return draining;
    }

    /**
     * Returns {@code true} if requests for sessions already bound to this server may still be
     * sent to it, i.e. it is healthy or draining.
     */
    public boolean acceptsBoundSessions() {
        return healthy || draining;
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
        this.draining = false;
        this.lastFailureTime = 0;
    }

    /**
     * Marks this server as unhealthy.
     */
    public void markUnhealthy() {
        this.healthy = false;
        this.draining = false;
        this.lastFailureTime = System.nanoTime();
    }

    /**
     * Marks this server as draining: excluded from new-session selection but still serving
     * sessions already bound to it. Recovery happens through the normal health check path,
     * which calls {@link #markHealthy()} once the restarted server answers again.
     */
    public void markDraining() {
        // Set draining before clearing healthy so concurrent readers of acceptsBoundSessions()
        // never observe a window where the server looks fully down.
        this.draining = true;
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
