package org.openjproxy.datasource.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CreationGraceHikariDataSourceTest {

    @Test
    void shouldAcquireSlowlyCreatedConnectionDuringGrace() throws Exception {
        Fixture fixture = new Fixture();
        try (CreationGraceHikariDataSource pool = fixture.createPool(2000)) {
            fixture.blockCreation.set(true);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var acquisition = executor.submit(() -> pool.getConnection());
                assertTrue(fixture.creationStarted.await(2, TimeUnit.SECONDS));
                assertTrue(fixture.borrowTimedOut.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(1, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                fixture.allowCreation.countDown();
                try (Connection connection = acquisition.get(2, TimeUnit.SECONDS);
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT 1")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                assertEquals(1, fixture.timeouts.get());
                assertEquals(250, pool.getConnectionTimeout(), "Shared timeout must not be changed");
                assertTrue(pool.getHikariPoolMXBean().getTotalConnections() <= pool.getMaximumPoolSize());
            } finally {
                fixture.allowCreation.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void shouldNotGrantGraceWhenPoolIsFull() throws Exception {
        Fixture fixture = new Fixture();
        try (CreationGraceHikariDataSource pool = fixture.createPool(5000);
             Connection held = pool.getConnection()) {
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertThrows(SQLTransientConnectionException.class, pool::getConnection));
            assertEquals(1, fixture.timeouts.get());
            assertFalse(held.isClosed());
        }
    }

    @Test
    void shouldStopAfterOneGracePeriodWhenCreationIsStillBlocked() throws Exception {
        Fixture fixture = new Fixture();
        try (CreationGraceHikariDataSource pool = fixture.createPool(400)) {
            fixture.blockCreation.set(true);
            try {
                long start = System.nanoTime();
                SQLException failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> assertThrows(SQLTransientConnectionException.class, pool::getConnection));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(elapsedMs >= 600, "Must allow the base timeout plus the extra grace");
                assertEquals(2, fixture.timeouts.get(), "Only one retry is allowed");
                assertEquals(1, failure.getSuppressed().length, "Original timeout must be preserved");
            } finally {
                fixture.allowCreation.countDown();
            }
        }
    }

    @Test
    void shouldDisableGraceWhenConfiguredToZero() throws Exception {
        Fixture fixture = new Fixture();
        try (CreationGraceHikariDataSource pool = fixture.createPool(0)) {
            fixture.blockCreation.set(true);
            try {
                SQLException failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> assertThrows(SQLTransientConnectionException.class, pool::getConnection));
                assertEquals(1, fixture.timeouts.get());
                assertEquals(0, failure.getSuppressed().length);
            } finally {
                fixture.allowCreation.countDown();
            }
        }
    }

    @Test
    void shouldNotRetryClosedPool() throws Exception {
        Fixture fixture = new Fixture();
        CreationGraceHikariDataSource pool = fixture.createPool(5000);
        pool.close();
        assertThrows(SQLException.class, pool::getConnection);
        assertEquals(0, fixture.timeouts.get());
    }

    private static class Fixture {
        final AtomicBoolean blockCreation = new AtomicBoolean();
        final AtomicInteger timeouts = new AtomicInteger();
        final CountDownLatch creationStarted = new CountDownLatch(1);
        final CountDownLatch borrowTimedOut = new CountDownLatch(1);
        final CountDownLatch allowCreation = new CountDownLatch(1);

        CreationGraceHikariDataSource createPool(long graceMs) {
            JdbcDataSource database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:creation_grace");
            DataSource delayed = (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getConnection") && blockCreation.get()) {
                            creationStarted.countDown();
                            if (!allowCreation.await(10, TimeUnit.SECONDS)) {
                                throw new SQLException("Test did not release connection creation");
                            }
                        }
                        try {
                            return method.invoke(database, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            HikariConfig config = new HikariConfig();
            config.setDataSource(delayed);
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(250);
            config.setMetricsTrackerFactory((name, stats) -> new IMetricsTracker() {
                @Override
                public void recordConnectionTimeout() {
                    timeouts.incrementAndGet();
                    borrowTimedOut.countDown();
                }
            });
            return new CreationGraceHikariDataSource(config, graceMs);
        }
    }
}
