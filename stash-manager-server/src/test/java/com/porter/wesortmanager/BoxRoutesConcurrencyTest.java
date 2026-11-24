package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lazy holders must never take IndexStore's monitor and the routes monitor in opposite
 * orders: the box-job list took the routes monitor then IndexStore, while the agent claim path
 * took IndexStore then the routes monitor, so the first calls after startup could wait on each
 * other forever and stall index writes and order handling behind them. Both holders now read the
 * ledger (IndexStore first) outside their private lock, so the orders can no longer interleave.
 * This drives the real holder methods against each other with the interleaving forced, not hoped
 * for: it deadlocks on the old ordering and passes on the new one.
 */
class BoxRoutesConcurrencyTest {
    @TempDir
    Path data;

    /**
     * Daemon worker threads: if the lock ordering ever regresses, the deadlocked pair must fail
     * the test instead of wedging the test JVM forever (blocked monitor entry is not
     * interruptible, so plain pool threads would never die).
     */
    private static final ThreadFactory DAEMONS =
            new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    var t = new Thread(r, "boxlock-race-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            };

    private IndexStore store(Path root) throws Exception {
        var store = new IndexStore(root, CreativeTabs.NONE);
        store.load();
        return store;
    }

    private static Object callJobs(Object holder) throws Exception {
        Method jobs = holder.getClass().getDeclaredMethod("jobs");
        jobs.setAccessible(true);
        try {
            return jobs.invoke(holder);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw new AssertionError(e.getCause());
        }
    }

    private static Object callOperations(Object holder) throws Exception {
        Method operations = holder.getClass().getDeclaredMethod("operations");
        operations.setAccessible(true);
        try {
            return operations.invoke(holder);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw new AssertionError(e.getCause());
        }
    }

    @Test
    void boxJobsHolderNeverDeadlocksAgainstTheClaimLockOrder() {
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (int round = 0; round < 10; round++) {
            Path root = Files.createTempDirectory(data, "boxlock");
            var config = Config.load(root);
            var store = store(root);
            var routes = new BoxRoutes(
                    store, new AuditLog(root), new OrderStore(root, store), config, new StashDefs(root));
            var barrier = new CyclicBarrier(2);
            var errors = new AtomicReference<Throwable>();
            // No try-with-resources: pool.close() would await the deadlocked workers
            // forever and turn a clean failure into a hung CI job. Daemon threads
            // never block JVM exit, so shutdownNow-without-await is enough; the
            // stuck pair is simply abandoned after the failure is recorded.
            var pool = Executors.newFixedThreadPool(2, DAEMONS);
            try {
                // Models the agent claim path: holds IndexStore, then takes the holder.
                var claimSide = pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        synchronized (store) {
                            Thread.sleep(50);
                            callJobs(routes);
                        }
                    } catch (Throwable t) {
                        errors.compareAndSet(null, t);
                    }
                    return null;
                });
                // Models the list path: holds the routes monitor, then takes IndexStore.
                // Old code deadlocks here: jobs() is synchronized, so this thread keeps the
                // monitor while index.stock() waits on the claim side, which waits on the monitor.
                var listSide = pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        synchronized (routes) {
                            Thread.sleep(200);
                            callJobs(routes);
                        }
                    } catch (Throwable t) {
                        errors.compareAndSet(null, t);
                    }
                    return null;
                });
                try {
                    claimSide.get(15, TimeUnit.SECONDS);
                    listSide.get(15, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException hung) {
                    claimSide.cancel(true);
                    listSide.cancel(true);
                    fail("round " + round + ": box-job holder and claim path deadlocked");
                    return;
                } finally {
                    pool.shutdownNow();
                }
                if (errors.get() != null) throw new AssertionError("round " + round, errors.get());
            } finally {
                pool.shutdownNow();
            }
        }
        });
    }

    @Test
    void physicalOperationsHolderNeverDeadlocksAgainstTheAgentLockOrder() throws Exception {
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (int round = 0; round < 10; round++) {
            Path root = Files.createTempDirectory(data, "boxlock");
            var config = Config.load(root);
            var store = store(root);
            var routes = new PhysicalRoutes(store, new AuditLog(root), config);
            var barrier = new CyclicBarrier(2);
            var errors = new AtomicReference<Throwable>();
            // Same no-join rule as above: never await deadlocked workers.
            var pool = Executors.newFixedThreadPool(2, DAEMONS);
            try {
                var agentSide = pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        synchronized (store) {
                            Thread.sleep(50);
                            callOperations(routes);
                        }
                    } catch (Throwable t) {
                        errors.compareAndSet(null, t);
                    }
                    return null;
                });
                var listSide = pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        synchronized (routes) {
                            Thread.sleep(200);
                            callOperations(routes);
                        }
                    } catch (Throwable t) {
                        errors.compareAndSet(null, t);
                    }
                    return null;
                });
                try {
                    agentSide.get(15, TimeUnit.SECONDS);
                    listSide.get(15, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException hung) {
                    agentSide.cancel(true);
                    listSide.cancel(true);
                    fail("round " + round + ": physical-operations holder and agent path deadlocked");
                    return;
                } finally {
                    pool.shutdownNow();
                }
                if (errors.get() != null) throw new AssertionError("round " + round, errors.get());
            } finally {
                pool.shutdownNow();
            }
        }
        });
    }
}
