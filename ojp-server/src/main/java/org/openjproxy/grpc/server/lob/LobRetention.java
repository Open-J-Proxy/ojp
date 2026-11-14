package org.openjproxy.grpc.server.lob;

import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the LOB retention policy ({@code ojp.server.lob.keepUntilSessionEnd}).
 *
 * <p>LOBs created by the client through {@code createLob} are registered in the session and,
 * by default, kept until the session ends. With long-lived sessions (connection pools,
 * desktop applications) every inserted LOB stays in the server heap until the connection is
 * closed. When the property is {@code false}, the LOBs referenced by a statement's parameters
 * are released as soon as that statement has been executed.</p>
 */
public final class LobRetention {

    private LobRetention() {
    }

    /**
     * Returns the UUIDs of the session LOBs referenced by BLOB, CLOB and NCLOB parameters.
     */
    public static List<String> parameterLobUuids(List<Parameter> params) {
        List<String> uuids = new ArrayList<>();
        if (params == null) {
            return uuids;
        }
        for (Parameter param : params) {
            ParameterType type = param.getType();
            if ((type == ParameterType.BLOB || type == ParameterType.CLOB || type == ParameterType.N_CLOB)
                    && param.getValues() != null && !param.getValues().isEmpty()
                    && param.getValues().get(0) instanceof String uuid) {
                uuids.add(uuid);
            }
        }
        return uuids;
    }

    /**
     * Releases the LOBs referenced by the parameters of an executed statement, unless the
     * configuration keeps LOBs until the session ends.
     */
    public static void releaseExecutedParameterLobs(ServerConfiguration configuration, SessionManager sessionManager,
                                                    SessionInfo session, List<Parameter> params) {
        if (configuration == null || configuration.isLobKeepUntilSessionEnd() || session == null) {
            return;
        }
        List<String> uuids = parameterLobUuids(params);
        if (!uuids.isEmpty()) {
            sessionManager.releaseLobs(session, uuids);
        }
    }
}
