package org.openjproxy.jdbc.testutil;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reusable utility for placing Toxiproxy between JDBC client tests and OJP server endpoints.
 */
public final class ToxiproxyOjpUrlBridge implements AutoCloseable {

    private static final DockerImageName TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0");
    private final ToxiproxyContainer toxiproxyContainer;
    private final String proxiedJdbcUrl;

    private ToxiproxyOjpUrlBridge(ToxiproxyContainer toxiproxyContainer, String proxiedJdbcUrl) {
        this.toxiproxyContainer = toxiproxyContainer;
        this.proxiedJdbcUrl = proxiedJdbcUrl;
    }

    public static ToxiproxyOjpUrlBridge withRequestLatency(String ojpJdbcUrl, int latencyMs) throws IOException {
        ParsedOjpUrl parsed = ParsedOjpUrl.parse(ojpJdbcUrl);

        for (HostPort hostPort : parsed.getHostPorts()) {
            if (isLocalHost(hostPort.getHost())) {
                Testcontainers.exposeHostPorts(hostPort.getPort());
            }
        }

        ToxiproxyContainer container = new ToxiproxyContainer(TOXIPROXY_IMAGE);
        container.start();

        List<String> proxiedHosts = new ArrayList<>(parsed.getHostPorts().size());
        for (HostPort hostPort : parsed.getHostPorts()) {
            String targetHost = isLocalHost(hostPort.getHost()) ? "host.testcontainers.internal" : hostPort.getHost();
            ToxiproxyContainer.ContainerProxy proxy = container.getProxy(targetHost, hostPort.getPort());
            proxy.toxics().latency("request-latency-5ms", ToxicDirection.UPSTREAM, latencyMs);
            proxiedHosts.add(container.getHost() + ":" + proxy.getProxyPort());
        }

        String proxiedUrl = parsed.withHosts(proxiedHosts);
        return new ToxiproxyOjpUrlBridge(container, proxiedUrl);
    }

    public String proxiedJdbcUrl() {
        return proxiedJdbcUrl;
    }

    @Override
    public void close() {
        toxiproxyContainer.stop();
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }

    private static final class ParsedOjpUrl {
        private final String prefix;
        private final List<HostPort> hostPorts;
        private final String suffix;

        private ParsedOjpUrl(String prefix, List<HostPort> hostPorts, String suffix) {
            this.prefix = prefix;
            this.hostPorts = hostPorts;
            this.suffix = suffix;
        }

        private static ParsedOjpUrl parse(String jdbcUrl) {
            int startBracket = jdbcUrl.indexOf('[');
            int endBracket = jdbcUrl.indexOf(']');
            if (startBracket < 0 || endBracket <= startBracket) {
                throw new IllegalArgumentException("Invalid OJP JDBC URL format: " + jdbcUrl);
            }

            String prefix = jdbcUrl.substring(0, startBracket + 1);
            String suffix = jdbcUrl.substring(endBracket);
            String hosts = jdbcUrl.substring(startBracket + 1, endBracket);

            String[] hostParts = hosts.split(",");
            List<HostPort> parsedHosts = new ArrayList<>(hostParts.length);
            for (String hostPart : hostParts) {
                String trimmed = hostPart.trim();
                int separator = trimmed.lastIndexOf(':');
                if (separator < 0 || separator == trimmed.length() - 1) {
                    throw new IllegalArgumentException("Invalid OJP host:port entry: " + trimmed);
                }
                String host = trimmed.substring(0, separator).trim();
                int port = Integer.parseInt(trimmed.substring(separator + 1).trim());
                parsedHosts.add(new HostPort(host, port));
            }
            return new ParsedOjpUrl(prefix, parsedHosts, suffix);
        }

        private String withHosts(List<String> newHosts) {
            return prefix + String.join(",", newHosts) + suffix;
        }

        private List<HostPort> getHostPorts() {
            return hostPorts;
        }
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            if (host == null) {
                throw new IllegalArgumentException("host must not be null");
            }
            this.host = host;
            this.port = port;
        }

        private String getHost() {
            return host;
        }

        private int getPort() {
            return port;
        }
    }
}
