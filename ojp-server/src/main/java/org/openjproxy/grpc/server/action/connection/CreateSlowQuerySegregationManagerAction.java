package org.openjproxy.grpc.server.action.connection;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.action.ActionContext;

/**
 * Helper action for creating slow query segregation managers.
 * This is extracted from createSlowQuerySegregationManagerForDatasource method.
 *
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 */
@Slf4j
public class CreateSlowQuerySegregationManagerAction {

    private static final CreateSlowQuerySegregationManagerAction INSTANCE = new CreateSlowQuerySegregationManagerAction();

    private CreateSlowQuerySegregationManagerAction() {
        // Private constructor prevents external instantiation
    }

    public static CreateSlowQuerySegregationManagerAction getInstance() {
        return INSTANCE;
    }

    /**
     * Create slow query segregation manager for non-XA datasource.
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize) {
        execute(context, connHash, actualPoolSize,
                context.getServerConfiguration().getSlowQueryFastSlotTimeout());
    }

    /**
     * Create slow query segregation manager for non-XA datasource with explicit admission timeout.
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize, long nonXaFastSlotTimeoutMillis) {
        execute(context, connHash, actualPoolSize, false, nonXaFastSlotTimeoutMillis);
    }

    /**
     * Create slow query segregation manager with XA-specific handling.
     *
     * @param context The action context
     * @param connHash The connection hash
     * @param actualPoolSize The actual pool size (max XA transactions for XA, max pool size for non-XA)
     * @param isXA Whether this is an XA connection
     * @param admissionTimeoutMillis The fallback timeout budget for admission-control semaphores in milliseconds.
     *                              When slow query segregation is enabled, configured fast/slow slot timeouts
     *                              take precedence.
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize, boolean isXA, long admissionTimeoutMillis) {
        boolean slowQueryEnabled = context.getServerConfiguration().isSlowQuerySegregationEnabled();
        long configuredSlowSlotTimeoutMillis = context.getServerConfiguration().getSlowQuerySlowSlotTimeout();
        long configuredFastSlotTimeoutMillis = context.getServerConfiguration().getSlowQueryFastSlotTimeout();

        if (isXA) {
            // XA-specific handling
            if (slowQueryEnabled) {
                // XA with slow query segregation enabled: use configured slow/fast slot allocation
                AdmissionControlManager manager = new AdmissionControlManager(
                    actualPoolSize,
                    context.getServerConfiguration().getSlowQuerySlotPercentage(),
                    context.getServerConfiguration().getSlowQueryIdleTimeout(),
                    configuredSlowSlotTimeoutMillis,
                    configuredFastSlotTimeoutMillis,
                    context.getServerConfiguration().getSlowQueryUpdateGlobalAvgInterval(),
                    context.getServerConfiguration().getSlowQueryClassificationMode(),
                    context.getServerConfiguration().getSlowQueryThresholdMs(),
                    context.getServerConfiguration().getSlowQueryMinimumSlowQueryMs(),
                    context.getServerConfiguration().getSlowQuerySlowMultiplier(),
                    context.getServerConfiguration().getSlowQueryRecoveryMultiplier(),
                    context.getServerConfiguration().getSlowQueryMinSamples(),
                    context.getServerConfiguration().getSlowQueryBaselinePercentile(),
                    context.getServerConfiguration().getSlowQueryBaselineRefreshIntervalSeconds(),
                    context.getServerConfiguration().getAdmissionControlMaxQueueDepth(),
                    true
                );
                context.getAdmissionControlManagers().put(connHash, manager);
                log.info("Created AdmissionControlManager for XA datasource {} with pool size {}, fastSlotTimeout={}ms, slowSlotTimeout={}ms, classificationMode={}, minimumSlowQueryMs={}, slowMultiplier={}, recoveryMultiplier={}, minSamples={}, baselinePercentile={}, baselineRefreshIntervalSeconds={}, slowQueryThreshold={}ms (slow query segregation enabled)",
                        connHash, actualPoolSize, configuredFastSlotTimeoutMillis, configuredSlowSlotTimeoutMillis,
                        context.getServerConfiguration().getSlowQueryClassificationMode(),
                        context.getServerConfiguration().getSlowQueryMinimumSlowQueryMs(),
                        context.getServerConfiguration().getSlowQuerySlowMultiplier(),
                        context.getServerConfiguration().getSlowQueryRecoveryMultiplier(),
                        context.getServerConfiguration().getSlowQueryMinSamples(),
                        context.getServerConfiguration().getSlowQueryBaselinePercentile(),
                        context.getServerConfiguration().getSlowQueryBaselineRefreshIntervalSeconds(),
                        context.getServerConfiguration().getSlowQueryThresholdMs());
            } else {
                // XA with slow query segregation disabled: use SlotManager only (no QueryPerformanceMonitor)
                // Set totalSlots=actualPoolSize, fastSlots=actualPoolSize, slowSlots=0
                // Use xaStartTimeoutMillis as the fast slot timeout
                AdmissionControlManager manager = new AdmissionControlManager(
                    actualPoolSize,
                    0, // slowSlotPercentage = 0 means all slots are fast
                    0, // idleTimeout not relevant
                    0, // slowSlotTimeout not relevant
                    admissionTimeoutMillis, // Use configured admission timeout for fast slot timeout
                    0, // updateGlobalAvgInterval = 0 means no performance monitoring
                    context.getServerConfiguration().getAdmissionControlMaxQueueDepth(),
                    true // enabled = true to use SlotManager
                );
                context.getAdmissionControlManagers().put(connHash, manager);
                log.info("Created AdmissionControlManager for XA datasource {} with {} slots (all fast, timeout={}ms, no performance monitoring)",
                        connHash, actualPoolSize, admissionTimeoutMillis);
            }
        } else {
            // Non-XA handling
            if (slowQueryEnabled) {
                AdmissionControlManager manager = new AdmissionControlManager(
                    actualPoolSize,
                    context.getServerConfiguration().getSlowQuerySlotPercentage(),
                    context.getServerConfiguration().getSlowQueryIdleTimeout(),
                    configuredSlowSlotTimeoutMillis,
                    configuredFastSlotTimeoutMillis,
                    context.getServerConfiguration().getSlowQueryUpdateGlobalAvgInterval(),
                    context.getServerConfiguration().getSlowQueryClassificationMode(),
                    context.getServerConfiguration().getSlowQueryThresholdMs(),
                    context.getServerConfiguration().getSlowQueryMinimumSlowQueryMs(),
                    context.getServerConfiguration().getSlowQuerySlowMultiplier(),
                    context.getServerConfiguration().getSlowQueryRecoveryMultiplier(),
                    context.getServerConfiguration().getSlowQueryMinSamples(),
                    context.getServerConfiguration().getSlowQueryBaselinePercentile(),
                    context.getServerConfiguration().getSlowQueryBaselineRefreshIntervalSeconds(),
                    context.getServerConfiguration().getAdmissionControlMaxQueueDepth(),
                    true
                );
                context.getAdmissionControlManagers().put(connHash, manager);
                log.info("Created AdmissionControlManager for datasource {} with pool size {}, fastSlotTimeout={}ms, slowSlotTimeout={}ms, classificationMode={}, minimumSlowQueryMs={}, slowMultiplier={}, recoveryMultiplier={}, minSamples={}, baselinePercentile={}, baselineRefreshIntervalSeconds={}, slowQueryThreshold={}ms",
                        connHash, actualPoolSize, configuredFastSlotTimeoutMillis, configuredSlowSlotTimeoutMillis,
                        context.getServerConfiguration().getSlowQueryClassificationMode(),
                        context.getServerConfiguration().getSlowQueryMinimumSlowQueryMs(),
                        context.getServerConfiguration().getSlowQuerySlowMultiplier(),
                        context.getServerConfiguration().getSlowQueryRecoveryMultiplier(),
                        context.getServerConfiguration().getSlowQueryMinSamples(),
                        context.getServerConfiguration().getSlowQueryBaselinePercentile(),
                        context.getServerConfiguration().getSlowQueryBaselineRefreshIntervalSeconds(),
                        context.getServerConfiguration().getSlowQueryThresholdMs());
            } else {
                // Create admission-control-only manager (always-on semaphore)
                AdmissionControlManager manager = new AdmissionControlManager(
                    actualPoolSize,
                    0, // 0% slow slots => all slots used for admission control
                    0,
                    0,
                    admissionTimeoutMillis,
                    0,
                    context.getServerConfiguration().getAdmissionControlMaxQueueDepth(),
                    true
                );
                context.getAdmissionControlManagers().put(connHash, manager);
                log.info("Created admission-control-only AdmissionControlManager for datasource {} with pool size {} and timeout {}ms",
                        connHash, actualPoolSize, admissionTimeoutMillis);
            }
        }
    }
}
