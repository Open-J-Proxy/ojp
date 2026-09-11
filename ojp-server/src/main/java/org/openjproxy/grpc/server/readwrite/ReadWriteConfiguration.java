package org.openjproxy.grpc.server.readwrite;

import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for read/write splitting feature.
 * Holds settings for a primary datasource and its associated read replicas.
 */
@Getter
@ToString
public class ReadWriteConfiguration {

    /**
     * Name of the primary datasource
     */
    private final String primaryName;

    /**
     * Whether read/write splitting is enabled for this primary
     */
    private final boolean enabled;

    /**
     * Replica selection strategy (ROUND_ROBIN, RANDOM, LEAST_CONNECTIONS)
     */
    private final ReplicaSelectionStrategy strategy;

    /**
     * Duration in seconds to stick to primary after a write operation.
     * This ensures read-your-writes consistency.
     */
    private final int stickySessionSeconds;

    /**
     * Whether to fail over to primary when all replicas are unavailable
     */
    private final boolean failoverToPrimary;

    /**
     * List of replica datasource names associated with this primary
     */
    private final List<String> replicaNames;

    /**
     * Replica selection strategies
     */
    public enum ReplicaSelectionStrategy {
        ROUND_ROBIN,
        RANDOM,
        LEAST_CONNECTIONS
    }

    /**
     * Creates a new ReadWriteConfiguration
     *
     * @param primaryName          name of the primary datasource
     * @param enabled              whether read/write splitting is enabled
     * @param strategy             replica selection strategy
     * @param stickySessionSeconds sticky session duration in seconds
     * @param failoverToPrimary    whether to failover to primary when replicas unavailable
     * @param replicaNames         list of replica datasource names
     */
    public ReadWriteConfiguration(String primaryName, boolean enabled, ReplicaSelectionStrategy strategy,
                                   int stickySessionSeconds, boolean failoverToPrimary, List<String> replicaNames) {
        this.primaryName = Objects.requireNonNull(primaryName, "primaryName cannot be null");
        this.enabled = enabled;
        this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
        this.stickySessionSeconds = stickySessionSeconds;
        this.failoverToPrimary = failoverToPrimary;
        this.replicaNames = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(replicaNames, "replicaNames cannot be null")));
    }

    /**
     * Validates this configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (primaryName == null || primaryName.trim().isEmpty()) {
            throw new IllegalArgumentException("Primary datasource name cannot be empty");
        }

        if (enabled && replicaNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Read/write splitting is enabled but no replicas configured for primary: " + primaryName);
        }

        // stickySessionSeconds is optional - 0 means disabled, positive values enable sticky sessions
        if (stickySessionSeconds < 0) {
            throw new IllegalArgumentException("stickySessionSeconds cannot be negative: " + stickySessionSeconds);
        }

        // Check for duplicate replica names
        if (replicaNames.size() != replicaNames.stream().distinct().count()) {
            throw new IllegalArgumentException("Duplicate replica names found for primary: " + primaryName);
        }
    }

    /**
     * Checks if read/write splitting has replicas configured
     *
     * @return true if enabled and has replicas
     */
    public boolean hasReplicas() {
        return enabled && !replicaNames.isEmpty();
    }

    /**
     * Gets the number of configured replicas
     *
     * @return replica count
     */
    public int getReplicaCount() {
        return replicaNames.size();
    }

    /**
     * Builder for ReadWriteConfiguration
     */
    public static class Builder {
        private String primaryName;
        private boolean enabled = false;
        private ReplicaSelectionStrategy strategy = ReplicaSelectionStrategy.ROUND_ROBIN;
        private int stickySessionSeconds = 5;
        private boolean failoverToPrimary = true;
        private List<String> replicaNames = new ArrayList<>();

        public Builder primaryName(String primaryName) {
            this.primaryName = primaryName;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder strategy(ReplicaSelectionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder stickySessionSeconds(int stickySessionSeconds) {
            this.stickySessionSeconds = stickySessionSeconds;
            return this;
        }

        public Builder failoverToPrimary(boolean failoverToPrimary) {
            this.failoverToPrimary = failoverToPrimary;
            return this;
        }

        public Builder addReplica(String replicaName) {
            this.replicaNames.add(replicaName);
            return this;
        }

        public Builder replicas(List<String> replicaNames) {
            this.replicaNames = new ArrayList<>(replicaNames);
            return this;
        }

        public ReadWriteConfiguration build() {
            ReadWriteConfiguration config = new ReadWriteConfiguration(
                    primaryName, enabled, strategy, stickySessionSeconds, failoverToPrimary, replicaNames);
            config.validate();
            return config;
        }
    }
}
