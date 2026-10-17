package org.openjproxy.xa.pool.commons;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonsPool2XAProviderTest {

    @Test
    void shouldFailWhenProviderDoesNotApplyResize() throws SQLException {
        XAConnectionPoolProvider provider = mock(XAConnectionPoolProvider.class);
        when(provider.supportsDynamicResizing()).thenReturn(true);
        when(provider.resizePool(any(), eq(10), eq(2))).thenReturn(false);
        when(provider.id()).thenReturn("test");
        XATransactionRegistry registry = new XATransactionRegistry(provider, new Object(), "test", 5, 1);

        assertThrows(IllegalStateException.class, () -> registry.resizeBackendPool(10, 2));
        assertEquals(5, registry.getMaxPoolSize());
        assertEquals(1, registry.getMinIdle());
    }

    @Test
    void shouldGrowAndShrinkPoolSizes() {
        JdbcDataSource vendorDataSource = new JdbcDataSource();
        vendorDataSource.setURL("jdbc:h2:mem:xa-resize;DB_CLOSE_DELAY=-1");
        CommonsPool2XADataSource pooledDataSource = new CommonsPool2XADataSource(vendorDataSource, Map.of(
                "xa.maxPoolSize", "8",
                "xa.minIdle", "4",
                "xa.timeBetweenEvictionRunsMs", "0"));
        CommonsPool2XAProvider provider = new CommonsPool2XAProvider();

        try {
            assertTrue(provider.supportsDynamicResizing());
            assertTrue(provider.resizePool(pooledDataSource, 3, 1));
            assertEquals(3, pooledDataSource.getMaxTotal());
            assertEquals(3, pooledDataSource.getMaxIdle());
            assertEquals(1, pooledDataSource.getMinIdle());

            assertTrue(provider.resizePool(pooledDataSource, 10, 5));
            assertEquals(10, pooledDataSource.getMaxTotal());
            assertEquals(10, pooledDataSource.getMaxIdle());
            assertEquals(5, pooledDataSource.getMinIdle());

            assertTrue(provider.resizePool(pooledDataSource, 2, 0));
            assertEquals(2, pooledDataSource.getMaxTotal());
            assertEquals(2, pooledDataSource.getMaxIdle());
            assertEquals(0, pooledDataSource.getMinIdle());
        } finally {
            pooledDataSource.close();
        }
    }
}
