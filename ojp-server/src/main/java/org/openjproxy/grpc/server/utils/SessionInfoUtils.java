package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;

import java.util.Map;

/**
 * Utility class for creating SessionInfo builders.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class SessionInfoUtils {

    /**
     * Creates a new SessionInfo builder from an existing SessionInfo.
     *
     * @param activeSessionInfo The source session info
     * @return A new builder with copied values
     */
    public static SessionInfo.Builder newBuilderFrom(SessionInfo activeSessionInfo) {
        return SessionInfo.newBuilder()
                .setConnHash(activeSessionInfo.getConnHash())
                .setClientUUID(activeSessionInfo.getClientUUID())
                .setSessionUUID(activeSessionInfo.getSessionUUID())
                .setSessionStatus(activeSessionInfo.getSessionStatus())
                .setTransactionInfo(activeSessionInfo.getTransactionInfo())
                .setIsXA(activeSessionInfo.getIsXA())
                .setTargetServer(activeSessionInfo.getTargetServer())
                .setClusterHealth(activeSessionInfo.getClusterHealth());
    }

    /**
     * Creates a new SessionInfo builder from an existing SessionInfo with client-side
     * throttle data (maxAdmission, observedPeak, clientCount) stamped from the live
     * AdmissionControlManager. This allows the JDBC driver to update its reactive
     * throttle limit from every response, not just the initial connect response.
     *
     * <p>If no AdmissionControlManager is registered for this connHash, the throttle
     * fields are left at zero and the driver-side {@code updateFromSessionInfo()} will
     * ignore them (preserving any reactive limit already in effect).</p>
     */
    public static SessionInfo.Builder newBuilderFrom(SessionInfo activeSessionInfo, ActionContext context) {
        SessionInfo.Builder builder = newBuilderFrom(activeSessionInfo);
        stampThrottle(builder, activeSessionInfo.getConnHash(), context);
        return builder;
    }

    /**
     * Stamps client-side throttle data on an existing SessionInfo. Returns a new
     * SessionInfo with the throttle fields populated when an AdmissionControlManager
     * is registered for this connHash; otherwise returns the input unchanged.
     */
    public static SessionInfo enrichWithThrottle(SessionInfo sessionInfo, ActionContext context) {
        if (sessionInfo == null || context == null
                || sessionInfo.getConnHash() == null || sessionInfo.getConnHash().isEmpty()) {
            return sessionInfo;
        }
        SessionInfo.Builder builder = sessionInfo.toBuilder();
        boolean stamped = stampThrottle(builder, sessionInfo.getConnHash(), context);
        return stamped ? builder.build() : sessionInfo;
    }

    /**
     * Populates {@code maxAdmission}, {@code observedPeak}, and {@code clientCount} on
     * the given builder from the live AdmissionControlManager. Returns true if any
     * field was stamped; false if no manager is registered for this connHash.
     */
    private static boolean stampThrottle(SessionInfo.Builder builder, String connHash, ActionContext context) {
        if (connHash == null || connHash.isEmpty() || context == null) {
            return false;
        }
        Map<String, AdmissionControlManager> managers = context.getAdmissionControlManagers();
        AdmissionControlManager acm = managers == null ? null : managers.get(connHash);
        if (acm == null || !acm.isEnabled() || acm.getSlotManager() == null) {
            return false;
        }
        int maxAdmission = acm.getSlotManager().getEffectiveMaxAdmission();
        int observedPeak = acm.getSlotManager().getObservedPeak();
        SessionManager sm = context.getSessionManager();
        int clientCount = sm == null ? 0 : sm.getClientCount(connHash);
        builder.setMaxAdmission(maxAdmission)
                .setObservedPeak(observedPeak)
                .setClientCount(clientCount);
        return true;
    }

    /**
     * Adds targetServer to an existing SessionInfo.
     * If targetServer is already set, it is preserved. Otherwise, the provided targetServer is set.
     *
     * @param sessionInfo The source session info
     * @param targetServer The target server to set if not already present
     * @return A new SessionInfo with targetServer set
     */
    public static SessionInfo withTargetServer(SessionInfo sessionInfo, String targetServer) {
        if (sessionInfo == null) {
            return null;
        }

        // If targetServer is already set, preserve it; otherwise use the provided one
        String effectiveTargetServer = sessionInfo.getTargetServer();
        if (effectiveTargetServer == null || effectiveTargetServer.isEmpty()) {
            effectiveTargetServer = targetServer;
        }

        return SessionInfo.newBuilder()
                .setConnHash(sessionInfo.getConnHash())
                .setClientUUID(sessionInfo.getClientUUID())
                .setSessionUUID(sessionInfo.getSessionUUID())
                .setSessionStatus(sessionInfo.getSessionStatus())
                .setTransactionInfo(sessionInfo.getTransactionInfo())
                .setIsXA(sessionInfo.getIsXA())
                .setTargetServer(effectiveTargetServer != null ? effectiveTargetServer : "")
                .setClusterHealth(sessionInfo.getClusterHealth())
                .build();
    }
}
