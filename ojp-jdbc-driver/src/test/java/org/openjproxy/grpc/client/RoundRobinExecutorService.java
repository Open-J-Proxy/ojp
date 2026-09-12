package org.openjproxy.grpc.client;

import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executor that distributes tasks in a round-robin fashion across multiple single-threaded executors.
 */
public class RoundRobinExecutorService implements ExecutorService, AutoCloseable {

    private final ExecutorService[] executors;
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile boolean shutdown = false;

    public RoundRobinExecutorService(int threads) {
        if (threads <= 0)
            throw new IllegalArgumentException("threads must be > 0");

        this.executors = new ExecutorService[threads];
        for (int i = 0; i < threads; i++) {
            int finalI = i;
            executors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("rr-exec-" + finalI);
                return t;
            });
        }
    }

    private ExecutorService next() {
        int idx = Math.floorMod(counter.getAndIncrement(), executors.length);
        return executors[idx];
    }

    /* ---------------- Executor ---------------- */

    @Override
    public void execute(Runnable command) {
        if (shutdown)
            throw new RejectedExecutionException("ExecutorService is shut down");
        next().execute(command);
    }

    /* ---------------- ExecutorService ---------------- */

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (shutdown) throw new RejectedExecutionException();
        return next().submit(task);
    }

    @Override
    public Future<?> submit(Runnable task) {
        if (shutdown) throw new RejectedExecutionException();
        return next().submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        if (shutdown) throw new RejectedExecutionException();
        return next().submit(task, result);
    }

    @SneakyThrows
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        // Distribute each task round-robin
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        // Wait for them
        for (Future<?> f : futures) f.get();
        return futures;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks)
            futures.add(submit(task));

        for (Future<?> f : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { f.get(remaining, TimeUnit.NANOSECONDS); }
            catch (TimeoutException | ExecutionException ignored) {}
        }
        return futures;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> task : tasks)
                futures.add(submit(task));
            if (futures.isEmpty())
                throw new ExecutionException("No tasks completed", null);
            return futures.get(0).get();
        } finally {
            for (Future<?> f : futures) f.cancel(true);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> task : tasks)
                futures.add(submit(task));

            for (Future<T> f : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    return f.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {}
            }
            throw new TimeoutException();
        } finally {
            for (Future<?> f : futures) f.cancel(true);
        }
    }

    /* ---------------- Shutdown / lifecycle ---------------- */

    @Override
    public void shutdown() {
        shutdown = true;
        for (ExecutorService es : executors) es.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> out = new ArrayList<>();
        for (ExecutorService es : executors) out.addAll(es.shutdownNow());
        return out;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        for (ExecutorService es : executors)
            if (!es.isTerminated()) return false;
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        for (ExecutorService es : executors) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0 || !es.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                return false;
        }
        return true;
    }

    /* AutoCloseable */
    @Override
    public void close() {
        shutdown();
        try { awaitTermination(30, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) {}
    }
}