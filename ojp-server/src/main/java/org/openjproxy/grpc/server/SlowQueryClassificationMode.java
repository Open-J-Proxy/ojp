package org.openjproxy.grpc.server;

/**
 * Classification strategies for Slow Query Segregation.
 */
public enum SlowQueryClassificationMode {
    RELATIVE_FAST_BASELINE,
    ABSOLUTE_THRESHOLD
}
