package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConnectionBudgetManagerTest {

    @Test
    void shouldAllocateWeightedPoolCapsWhenBudgetIsOversubscribed() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(
                budgetProperties(60, 0, "app_rw", 4, "reporting_ro", 1));
        DatabaseConnectionBudgetManager.Registration application = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "app_rw", 100, 10, true);
        RecordingPoolResizer applicationResizer = new RecordingPoolResizer();
        manager.attachPool(application, applicationResizer, application.getMaximumPoolSize(),
                application.getMinimumIdle());

        DatabaseConnectionBudgetManager.Registration reporting = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "reporting_ro", 100, 10, true);

        assertEquals(48, application.getMaximumPoolSize());
        assertEquals(12, reporting.getMaximumPoolSize());
        assertEquals(48, applicationResizer.maximumPoolSize);
        assertEquals(10, application.getMinimumIdle());
        assertEquals(10, reporting.getMinimumIdle());
    }

    @Test
    void shouldApplyReserveAndClampMinimumIdleToAllocatedCap() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(8, 2));

        DatabaseConnectionBudgetManager.Registration first = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 10, 5, true);
        DatabaseConnectionBudgetManager.Registration second = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 10, 5, true);

        assertEquals(3, first.getMaximumPoolSize());
        assertEquals(3, second.getMaximumPoolSize());
        assertEquals(3, first.getMinimumIdle());
    }

    @Test
    void shouldShareUsernameWeightAcrossItsPools() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(
                budgetProperties(60, 0, "app_rw", 4, "reporting_ro", 1));

        DatabaseConnectionBudgetManager.Registration firstApplication = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "app_rw", 100, 0, true);
        DatabaseConnectionBudgetManager.Registration secondApplication = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "app_rw", 100, 0, true);
        DatabaseConnectionBudgetManager.Registration reporting = manager.registerPool(
                "pool-c", "jdbc:postgresql://db/orders", "reporting_ro", 100, 0, true);

        assertEquals(24, firstApplication.getMaximumPoolSize());
        assertEquals(24, secondApplication.getMaximumPoolSize());
        assertEquals(12, reporting.getMaximumPoolSize());
    }

    @Test
    void shouldNotApplyBudgetToUnmatchedJdbcUrl() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(5, 0));

        DatabaseConnectionBudgetManager.Registration registration = manager.registerPool(
                "pool-a", "jdbc:postgresql://other/orders", "user-a", 10, 5, false);

        assertEquals(10, registration.getMaximumPoolSize());
        assertEquals(5, registration.getMinimumIdle());
    }

    @Test
    void shouldRejectOverlappingBudgetPatterns() {
        Properties properties = budgetProperties(10, 0);
        properties.setProperty("ojp.server.databaseBudgets.other.match.jdbcUrlPattern", "jdbc:postgresql://db/*");
        properties.setProperty("ojp.server.databaseBudgets.other.maxTotalConnections", "20");

        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(properties);

        assertThrows(IllegalStateException.class, () -> manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 10, 2, true));
    }

    @Test
    void shouldRejectRebalanceWhenAnExistingProviderCannotResize() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(10, 0));
        DatabaseConnectionBudgetManager.Registration first = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 10, 2, false);
        manager.attachPool(first, new RecordingPoolResizer(), first.getMaximumPoolSize(),
                first.getMinimumIdle());

        assertThrows(IllegalStateException.class, () -> manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 10, 2, true));
        assertEquals(10, first.getMaximumPoolSize());
    }

    @Test
    void shouldRequireAtLeastOneConnectionForEveryBudgetedPool() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(1, 0));
        manager.registerPool("pool-a", "jdbc:postgresql://db/orders", "user-a", 10, 2, true);

        assertThrows(IllegalStateException.class, () -> manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 10, 2, true));
    }

    private Properties budgetProperties(int maximum, int reserve, Object... priorities) {
        Properties properties = new Properties();
        properties.setProperty("ojp.server.databaseBudgets.orders.match.jdbcUrlPattern",
                "jdbc:postgresql://db/*");
        properties.setProperty("ojp.server.databaseBudgets.orders.maxTotalConnections",
                String.valueOf(maximum));
        properties.setProperty("ojp.server.databaseBudgets.orders.reserveConnections",
                String.valueOf(reserve));
        for (int i = 0; i < priorities.length; i += 2) {
            properties.setProperty("ojp.server.databaseBudgets.orders.priorities.username."
                            + priorities[i] + ".weight",
                    String.valueOf(priorities[i + 1]));
        }
        return properties;
    }

    private static final class RecordingPoolResizer implements DatabaseConnectionBudgetManager.PoolResizer {
        private int maximumPoolSize;

        @Override
        public void resize(int newMaximumPoolSize, int minimumIdle) {
            maximumPoolSize = newMaximumPoolSize;
        }
    }
}
