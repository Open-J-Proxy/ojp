package org.openjproxy.jdbc.xa;

import org.junit.jupiter.api.Test;

import javax.transaction.xa.Xid;
import java.sql.SQLException;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OJP XA classes.
 */
public class OjpXADataSourceTest {

    @Test
    public void testXADataSourceCreation() {
        OjpXADataSource xaDataSource = new OjpXADataSource();
        assertNotNull(xaDataSource);
        
        xaDataSource.setUrl("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb");
        xaDataSource.setUser("testuser");
        xaDataSource.setPassword("testpass");
        
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb", xaDataSource.getUrl());
        assertEquals("testuser", xaDataSource.getUser());
        assertEquals("testpass", xaDataSource.getPassword());
    }

    @Test
    public void testXADataSourceWithConstructor() {
        OjpXADataSource xaDataSource = new OjpXADataSource(
            "jdbc:ojp[localhost:1059]_postgresql://localhost/testdb",
            "testuser",
            "testpass"
        );
        
        assertNotNull(xaDataSource);
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/testdb", xaDataSource.getUrl());
        assertEquals("testuser", xaDataSource.getUser());
        assertEquals("testpass", xaDataSource.getPassword());
    }

    @Test
    public void testSetProperty() {
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setProperty("testKey", "testValue");
        
        assertEquals("testValue", xaDataSource.getProperty("testKey"));
        
        Properties props = xaDataSource.getProperties();
        assertEquals("testValue", props.getProperty("testKey"));
    }

    @Test
    public void testLoginTimeout() throws Exception {
        OjpXADataSource xaDataSource = new OjpXADataSource();
        
        assertEquals(0, xaDataSource.getLoginTimeout());
        
        xaDataSource.setLoginTimeout(30);
        assertEquals(30, xaDataSource.getLoginTimeout());
    }

    @Test
    public void testGetXAConnectionWithoutUrl() {
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUser("testuser");
        xaDataSource.setPassword("testpass");
        
        assertThrows(SQLException.class, () -> {
            xaDataSource.getXAConnection();
        });
    }

    /**
     * Test Xid implementation used by OjpXAResource.
     */
    @Test
    public void testXidImplementation() {
        // Create a test Xid
        int formatId = 1;
        byte[] globalTxId = "global-tx-id".getBytes();
        byte[] branchQual = "branch-qual".getBytes();
        
        Xid xid = new TestXid(formatId, globalTxId, branchQual);
        
        assertEquals(formatId, xid.getFormatId());
        assertArrayEquals(globalTxId, xid.getGlobalTransactionId());
        assertArrayEquals(branchQual, xid.getBranchQualifier());
    }

    /**
     * Simple Xid implementation for testing.
     */
    private static class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        public TestXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
