package org.openjproxy.grpc.server.pool;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class ConnectionPoolWarmup {

    private ConnectionPoolWarmup() {
    }

    public static void warmUp(DataSource dataSource, int minimumConnections, long timeoutMs) throws SQLException {
        if (minimumConnections <= 0) {
            return;
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Pool warm-up timeout must be positive");
        }

        long startTime = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long deadline = startTime > Long.MAX_VALUE - timeoutNanos
                ? Long.MAX_VALUE
                : startTime + timeoutNanos;
        CountDownLatch acquired = new CountDownLatch(minimumConnections);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(minimumConnections, task -> {
            Thread thread = new Thread(task, "ojp-pool-warmup");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> tasks = new ArrayList<>(minimumConnections);
        boolean completed = false;

        try {
            for (int i = 0; i < minimumConnections; i++) {
                tasks.add(executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection()) {
                        if (connection.isClosed()) {
                            throw new SQLException("Pool warm-up obtained a closed connection");
                        }
                        acquired.countDown();
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0 || !release.await(remaining, TimeUnit.NANOSECONDS)) {
                            throw new TimeoutException("Timed out while holding warm-up connections");
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                        if (e instanceof Exception) {
                            throw (Exception) e;
                        }
                        throw (Error) e;
                    } finally {
                        acquired.countDown();
                    }
                    return null;
                }));
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !acquired.await(remaining, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Timed out waiting for " + minimumConnections
                        + " pool warm-up connections");
            }
            Throwable acquisitionFailure = failure.get();
            if (acquisitionFailure != null) {
                throw new ExecutionException(acquisitionFailure);
            }

            release.countDown();
            for (Future<?> task : tasks) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Timed out returning pool warm-up connections");
                }
                task.get(remaining, TimeUnit.NANOSECONDS);
            }
            completed = true;
            log.info("Pool warm-up completed with {} connections in {}ms",
                    minimumConnections, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while warming up the connection pool", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new SQLException("Failed to warm up " + minimumConnections
                    + " pool connections within " + timeoutMs + "ms", e.getCause() == null ? e : e.getCause());
        } finally {
            release.countDown();
            if (!completed) {
                tasks.forEach(task -> task.cancel(true));
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
        }
    }
}
