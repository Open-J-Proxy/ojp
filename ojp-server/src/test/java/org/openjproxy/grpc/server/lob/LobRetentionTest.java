package org.openjproxy.grpc.server.lob;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.SessionManagerImpl;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LobRetentionTest {

    private static final String KEEP_PROPERTY = "ojp.server.lob.keepUntilSessionEnd";

    private SessionManager sessionManager;
    private SessionInfo sessionInfo;
    private Session session;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManagerImpl();
        sessionManager.registerClientUUID("conn-hash-lob", "client-lob");
        sessionInfo = sessionManager.createSession("client-lob", mock(Connection.class));
        session = sessionManager.getSession(sessionInfo);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(KEEP_PROPERTY);
    }

    @Test
    void parameterLobUuidsReturnsOnlyLobParameters() {
        List<Parameter> params = List.of(
                parameter(ParameterType.BLOB, "blob-1"),
                parameter(ParameterType.CLOB, "clob-1"),
                parameter(ParameterType.N_CLOB, "nclob-1"),
                parameter(ParameterType.STRING, "not-a-lob"));

        List<String> uuids = LobRetention.parameterLobUuids(params);

        assertEquals(List.of("blob-1", "clob-1", "nclob-1"), uuids);
    }

    @Test
    void parameterLobUuidsHandlesNullList() {
        assertTrue(LobRetention.parameterLobUuids(null).isEmpty());
    }

    @Test
    void keepsLobsByDefault() {
        Blob blob = mock(Blob.class);
        sessionManager.registerLob(sessionInfo, blob, "blob-1");

        LobRetention.releaseExecutedParameterLobs(new ServerConfiguration(), sessionManager, sessionInfo,
                List.of(parameter(ParameterType.BLOB, "blob-1")));

        assertNotNull(session.getLob("blob-1"));
    }

    @Test
    void releasesAndFreesLobsWhenNotKeptUntilSessionEnd() throws Exception {
        System.setProperty(KEEP_PROPERTY, "false");
        Blob blob = mock(Blob.class);
        Clob clob = mock(Clob.class);
        sessionManager.registerLob(sessionInfo, blob, "blob-1");
        sessionManager.registerLob(sessionInfo, clob, "clob-1");

        LobRetention.releaseExecutedParameterLobs(new ServerConfiguration(), sessionManager, sessionInfo,
                List.of(parameter(ParameterType.BLOB, "blob-1"), parameter(ParameterType.CLOB, "clob-1")));

        assertNull(session.getLob("blob-1"));
        assertNull(session.getLob("clob-1"));
        verify(blob).free();
        verify(clob).free();
    }

    @Test
    void releaseLobIgnoresUnknownUuid() {
        assertFalse(session.releaseLob("unknown"));
        assertFalse(session.releaseLob(null));
    }

    private static Parameter parameter(ParameterType type, Object value) {
        return Parameter.builder().index(1).type(type).values(List.of(value)).build();
    }
}
