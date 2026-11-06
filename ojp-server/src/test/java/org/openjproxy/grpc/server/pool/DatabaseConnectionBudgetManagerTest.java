package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionBudgetManagerTest {

    @Test
    void shouldLoadServerPropertiesFileAndPreferSystemProperties(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("ojp.properties"),
                "ojp.server.databaseBudgets.orders.match.jdbcUrlPattern=jdbc:postgresql://db/*\n"
                        + "ojp.server.databaseBudgets.orders.maxTotalConnections=10\n");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("ojp.server.databaseBudgets.orders.maxTotalConnections", "6");

        Properties properties = DatabaseConnectionBudgetManager.loadBudgetProperties(
                directory, getClass().getClassLoader(), systemProperties);
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(properties);
        DatabaseConnectionBudgetManager.Registration registration = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 20, 0, true);

        assertEquals(6, registration.getMaximumPoolSize());
    }

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
    void shouldRebalanceWhenCoordinatorChangesRequestedPoolSizes() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(20, 0));
        DatabaseConnectionBudgetManager.Registration first = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 5, 2, true);
        RecordingPoolResizer firstResizer = new RecordingPoolResizer();
        manager.attachPool(first, firstResizer, first.getMaximumPoolSize(), first.getMinimumIdle());
        DatabaseConnectionBudgetManager.Registration second = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 20, 2, true);
        RecordingPoolResizer secondResizer = new RecordingPoolResizer();
        manager.attachPool(second, secondResizer, second.getMaximumPoolSize(), second.getMinimumIdle());

        assertTrue(manager.updatePoolRequest("pool-a", 30, 4));

        assertEquals(10, first.getMaximumPoolSize());
        assertEquals(10, second.getMaximumPoolSize());
        assertEquals(10, firstResizer.maximumPoolSize);
        assertEquals(10, secondResizer.maximumPoolSize);
        assertEquals(4, firstResizer.minimumIdle);
        assertEquals(2, secondResizer.minimumIdle);
    }

    @Test
    void shouldDivideClusterBudgetAcrossHealthyServersAndExpandOnFailure() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(24, 0));
        DatabaseConnectionBudgetManager.Registration application = manager.registerPool(
                "regular:application", "jdbc:postgresql://db/orders", "user-a", 24, 24, true, 3);
        RecordingPoolResizer applicationResizer = new RecordingPoolResizer();
        manager.attachPool(application, applicationResizer, application.getMaximumPoolSize(),
                application.getMinimumIdle());
        DatabaseConnectionBudgetManager.Registration reporting = manager.registerPool(
                "regular:reporting", "jdbc:postgresql://db/orders", "user-b", 24, 24, true, 3);
        RecordingPoolResizer reportingResizer = new RecordingPoolResizer();
        manager.attachPool(reporting, reportingResizer, reporting.getMaximumPoolSize(),
                reporting.getMinimumIdle());

        assertEquals(4, application.getMaximumPoolSize());
        assertEquals(4, reporting.getMaximumPoolSize());
        assertEquals(8, application.getMaximumPoolSize() + reporting.getMaximumPoolSize());

        manager.updateHealthyServerCount("regular:application", 2);

        // The reporting pool still sees three servers, and the budget is divided by the largest count reported.
        assertEquals(4, application.getMaximumPoolSize());
        assertEquals(4, reporting.getMaximumPoolSize());

        manager.updateHealthyServerCount("regular:reporting", 2);

        assertEquals(6, application.getMaximumPoolSize());
        assertEquals(6, reporting.getMaximumPoolSize());
        assertEquals(12, application.getMaximumPoolSize() + reporting.getMaximumPoolSize());
        assertEquals(6, applicationResizer.maximumPoolSize);
        assertEquals(6, reportingResizer.maximumPoolSize);
    }

    @Test
    void shouldNotLetAPoolWithANarrowerClusterViewEnlargeTheSharedAllocation() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(24, 0));
        DatabaseConnectionBudgetManager.Registration application = manager.registerPool(
                "regular:application", "jdbc:postgresql://db/orders", "user-a", 24, 0, true, 3);
        RecordingPoolResizer applicationResizer = new RecordingPoolResizer();
        manager.attachPool(application, applicationResizer, application.getMaximumPoolSize(),
                application.getMinimumIdle());
        assertEquals(8, application.getMaximumPoolSize());

        // A client whose URL lists a single OJP server registers a pool against the same budget.
        DatabaseConnectionBudgetManager.Registration narrowView = manager.registerPool(
                "regular:single-endpoint", "jdbc:postgresql://db/orders", "user-b", 24, 0, true, 1);

        // Each server must still stay within a third of the 24 connections.
        assertEquals(4, application.getMaximumPoolSize());
        assertEquals(4, narrowView.getMaximumPoolSize());
        assertEquals(4, applicationResizer.maximumPoolSize);
    }

    @Test
    void shouldKeepRecordedSizesInSyncWhenAResizeFails() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(12, 0));
        DatabaseConnectionBudgetManager.Registration first = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 6, 0, true, 2);
        ControllableResizer firstResizer = new ControllableResizer(first.getMaximumPoolSize());
        manager.attachPool(first, firstResizer, first.getMaximumPoolSize(), first.getMinimumIdle());
        DatabaseConnectionBudgetManager.Registration second = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 6, 0, true, 2);
        ControllableResizer secondResizer = new ControllableResizer(second.getMaximumPoolSize());
        manager.attachPool(second, secondResizer, second.getMaximumPoolSize(), second.getMinimumIdle());
        assertEquals(3, first.getMaximumPoolSize());
        assertEquals(3, second.getMaximumPoolSize());

        // Shrinking pool-a succeeds, then growing pool-b fails.
        secondResizer.failGrowth = true;
        assertThrows(IllegalStateException.class, () -> manager.updatePoolRequest("pool-a", 2, 0));

        // The rollback must bring the real pools back to what the manager reports.
        assertEquals(3, first.getMaximumPoolSize());
        assertEquals(3, second.getMaximumPoolSize());
        assertEquals(first.getMaximumPoolSize(), firstResizer.maximumPoolSize);
        assertEquals(second.getMaximumPoolSize(), secondResizer.maximumPoolSize);
    }

    @Test
    void shouldKeepLastGoodAllocationWhenServerCountMakesBudgetInfeasible() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(4, 0));
        DatabaseConnectionBudgetManager.Registration first = manager.registerPool(
                "pool-a", "jdbc:postgresql://db/orders", "user-a", 4, 0, true, 1);
        DatabaseConnectionBudgetManager.Registration second = manager.registerPool(
                "pool-b", "jdbc:postgresql://db/orders", "user-b", 4, 0, true, 1);
        assertEquals(2, first.getMaximumPoolSize());
        assertEquals(2, second.getMaximumPoolSize());

        // Three servers would leave one connection per server for two pools.
        assertThrows(DatabaseConnectionBudgetManager.InfeasibleBudgetException.class,
                () -> manager.updateHealthyServerCount("pool-a", 3));

        assertEquals(2, first.getMaximumPoolSize());
        assertEquals(2, second.getMaximumPoolSize());
        assertTrue(manager.updatePoolRequest("pool-a", 4, 0));
    }

    @Test
    void shouldRejectBudgetWithoutMaximumConnections() {
        Properties properties = new Properties();
        properties.setProperty("ojp.server.databaseBudgets.orders.match.jdbcUrlPattern", "jdbc:postgresql://db/*");

        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionBudgetManager(properties));
    }

    @Test
    void shouldRoundNonDivisibleClusterBudgetDown() {
        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(26, 1));
        DatabaseConnectionBudgetManager.Registration application = manager.registerPool(
                "regular:application", "jdbc:postgresql://db/orders", "user-a", 24, 0, true, 3);
        DatabaseConnectionBudgetManager.Registration reporting = manager.registerPool(
                "regular:reporting", "jdbc:postgresql://db/orders", "user-b", 24, 0, true, 3);

        assertEquals(4, application.getMaximumPoolSize());
        assertEquals(4, reporting.getMaximumPoolSize());
        assertEquals(8, application.getMaximumPoolSize() + reporting.getMaximumPoolSize());
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

    private static final class ControllableResizer implements DatabaseConnectionBudgetManager.PoolResizer {
        private int maximumPoolSize;
        private boolean failGrowth;

        private ControllableResizer(int initialMaximumPoolSize) {
            this.maximumPoolSize = initialMaximumPoolSize;
        }

        @Override
        public void resize(int newMaximumPoolSize, int newMinimumIdle) throws SQLException {
            if (failGrowth && newMaximumPoolSize > maximumPoolSize) {
                throw new SQLException("grow failed");
            }
            maximumPoolSize = newMaximumPoolSize;
        }
    }

    private static final class RecordingPoolResizer implements DatabaseConnectionBudgetManager.PoolResizer {
        private int maximumPoolSize;
        private int minimumIdle;

        @Override
        public void resize(int newMaximumPoolSize, int newMinimumIdle) {
            maximumPoolSize = newMaximumPoolSize;
            minimumIdle = newMinimumIdle;
        }
    }
}
