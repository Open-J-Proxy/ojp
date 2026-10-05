package org.openjproxy.grpc.server.action.util;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.ShutdownCoordinator;
import org.openjproxy.grpc.server.action.ActionContext;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessClusterHealthActionDrainingTest {

    @AfterEach
    void reset() {
        ShutdownCoordinator.getInstance().resetForTesting();
    }

    @Test
    void shouldNotResizePoolsWhenServerDraining() {
        ShutdownCoordinator.getInstance().startDraining();
        ClusterHealthTracker tracker = mock(ClusterHealthTracker.class);
        ActionContext context = mock(ActionContext.class);
        when(context.getClusterHealthTracker()).thenReturn(tracker);
        when(context.getXaRegistries()).thenReturn(new HashMap<>());
        SessionInfo sessionInfo = SessionInfo.newBuilder()
                .setConnHash("hash")
                .setClusterHealth("server1:1059(DOWN);server2:1059(UP)")
                .build();

        ProcessClusterHealthAction.getInstance().execute(context, sessionInfo);

        verify(tracker, never()).hasHealthChanged(anyString(), anyString());
        verify(tracker, never()).countHealthyServers(anyString());
    }
}
