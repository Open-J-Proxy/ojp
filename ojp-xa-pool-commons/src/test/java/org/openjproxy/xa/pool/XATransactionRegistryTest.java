package org.openjproxy.xa.pool;

import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XATransactionRegistryTest {

    @Test
    void shouldReturnReadOnlyBackendOnlyWhenOwningSessionTerminates() throws Exception {
        XAConnectionPoolProvider provider = mock(XAConnectionPoolProvider.class);
        Object pool = new Object();
        XATransactionRegistry registry = new XATransactionRegistry(provider, pool, "test", 2, 0);
        XABackendSession backend = mock(XABackendSession.class);
        XAResource resource = mock(XAResource.class);
        when(backend.getXAResource()).thenReturn(resource);
        when(resource.prepare(any(Xid.class))).thenReturn(XAResource.XA_RDONLY);

        XidKey first = new XidKey(1, new byte[]{1}, new byte[]{1});
        XidKey second = new XidKey(1, new byte[]{2}, new byte[]{1});
        for (XidKey xid : new XidKey[]{first, second}) {
            registry.registerExistingSession(xid, backend, XAResource.TMNOFLAGS, "owner");
            registry.xaEnd(xid, XAResource.TMSUCCESS);
            assertEquals(XAResource.XA_RDONLY, registry.xaPrepare(xid));
        }

        verify(provider, never()).returnSession(pool, backend);
        assertEquals(0, registry.returnCompletedSessions("another-session"));
        assertEquals(1, registry.returnCompletedSessions("owner"));
        assertEquals(0, registry.returnCompletedSessions("owner"));
        verify(provider, times(1)).returnSession(pool, backend);
        assertNull(registry.getContext(first));
        assertNull(registry.getContext(second));
    }
}
