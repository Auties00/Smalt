package com.github.auties00.smalt.internal.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the task shapes of the shared worker pool beyond bands: results in submission order,
 * failures reported deterministically, nested fan-outs and tasks that wait for earlier ones, and
 * the pool-wide guarantees: never more helper threads than the limit whatever the number of
 * concurrent callers, progress while every helper is busy, and idle helpers exiting.
 */
@DisplayName("shared worker pool")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ParallelTasksTest {
    private static final String WORKER_PREFIX = "smalt-worker-";

    static List<Callable<Integer>> squares(int count) {
        var tasks = new ArrayList<Callable<Integer>>();
        for (var i = 0; i < count; i++) {
            var value = i;
            tasks.add(() -> value * value);
        }
        return tasks;
    }

    static List<Thread> workers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(WORKER_PREFIX) && thread.isAlive())
                .toList();
    }

    @Nested
    @DisplayName("invokeAll")
    class InvokeAll {
        @Test
        @DisplayName("returns results index for index, including nulls")
        void order() {
            var expected = new ArrayList<Integer>();
            for (var i = 0; i < 100; i++) {
                expected.add(i * i);
            }
            assertEquals(expected, ParallelBands.invokeAll(squares(100)));
            assertEquals(List.of(), ParallelBands.invokeAll(squares(0)));
            assertEquals(List.of(0), ParallelBands.invokeAll(squares(1)));
            List<Callable<Object>> nulls = List.of(() -> null, () -> 1, () -> null);
            var results = ParallelBands.invokeAll(nulls);
            assertEquals(3, results.size());
            assertEquals(null, results.get(0));
            assertEquals(1, results.get(1));
        }

        @Test
        @DisplayName("runs every task and rethrows the first failure in submission order, suppressing the others")
        void failures() {
            for (var round = 0; round < 50; round++) {
                var first = new IllegalArgumentException("first");
                var second = new IllegalStateException("second");
                var ran = new AtomicIntegerArray(6);
                List<Callable<Integer>> tasks = List.of(() -> {
                    ran.incrementAndGet(0);
                    return 1;
                }, () -> {
                    ran.incrementAndGet(1);
                    Thread.onSpinWait();
                    throw first;
                }, () -> {
                    ran.incrementAndGet(2);
                    return 3;
                }, () -> {
                    ran.incrementAndGet(3);
                    throw second;
                }, () -> {
                    ran.incrementAndGet(4);
                    throw second;
                }, () -> {
                    ran.incrementAndGet(5);
                    return 6;
                });
                var thrown = assertThrows(IllegalArgumentException.class, () -> ParallelBands.invokeAll(tasks));
                assertSame(first, thrown);
                assertEquals(1, thrown.getSuppressed().length);
                assertSame(second, thrown.getSuppressed()[0]);
                for (var i = 0; i < 6; i++) {
                    assertEquals(1, ran.get(i));
                }
            }
            var error = new AssertionError("error");
            List<Callable<Integer>> failing = List.of(() -> 1, () -> {
                throw error;
            });
            assertSame(error, assertThrows(AssertionError.class, () -> ParallelBands.invokeAll(failing)));
        }

        @Test
        @DisplayName("wraps a checked exception, which codec tasks never throw, in an unchecked one")
        void checked() {
            List<Callable<Integer>> tasks = List.of(() -> 1, () -> {
                throw new Exception("checked");
            });
            var thrown = assertThrows(IllegalStateException.class, () -> ParallelBands.invokeAll(tasks));
            assertEquals("checked", thrown.getCause().getMessage());
        }

        @Test
        @DisplayName("runs nested batches inside tasks without deadlocking")
        void nested() {
            var tasks = new ArrayList<Callable<Integer>>();
            for (var i = 0; i < 4 * ParallelBands.THREADS; i++) {
                var outer = i;
                tasks.add(() -> {
                    var inner = ParallelBands.invokeAll(squares(8));
                    var runs = new AtomicInteger();
                    ParallelBands.run(5, band -> runs.addAndGet(band));
                    var started = ParallelBands.submit(() -> 7);
                    return outer + inner.stream().mapToInt(Integer::intValue).sum() + runs.get() + started.join();
                });
            }
            var results = ParallelBands.invokeAll(tasks);
            for (var i = 0; i < results.size(); i++) {
                assertEquals(i + 140 + 10 + 7, results.get(i));
            }
        }
    }

    @Nested
    @DisplayName("pipeline")
    class Pipeline {
        @Test
        @DisplayName("completes stages that wait for earlier ones")
        void waiting() {
            for (var round = 0; round < 200; round++) {
                var first = new CountDownLatch(1);
                var second = new CountDownLatch(1);
                var third = new AtomicInteger();
                ParallelBands.pipeline(3, stage -> {
                    try {
                        switch (stage) {
                            case 0 -> first.countDown();
                            case 1 -> {
                                assertTrue(first.await(30, TimeUnit.SECONDS));
                                second.countDown();
                            }
                            default -> {
                                assertTrue(second.await(30, TimeUnit.SECONDS));
                                third.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                });
                assertEquals(1, third.get());
            }
        }

        @Test
        @DisplayName("the calling thread runs the first stage, and a later one only after every stage before it")
        void callerNeverWaits() throws Exception {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<?>>();
                for (var caller = 0; caller < 16; caller++) {
                    futures.add(executor.submit(() -> {
                        for (var round = 0; round < 50; round++) {
                            var self = Thread.currentThread();
                            var finished = new AtomicIntegerArray(4);
                            var ranFirst = new AtomicBoolean();
                            ParallelBands.pipeline(4, stage -> {
                                if (Thread.currentThread() == self) {
                                    if (stage == 0) {
                                        ranFirst.set(true);
                                    }
                                    for (var earlier = 0; earlier < stage; earlier++) {
                                        assertEquals(1, finished.get(earlier), "caller ran stage " + stage + " early");
                                    }
                                }
                                Pool.spin(ConcurrentHashMap.newKeySet());
                                finished.set(stage, 1);
                            });
                            assertTrue(ranFirst.get());
                        }
                        return null;
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
            }
        }

        @Test
        @DisplayName("rejects stage counts out of range and runs one stage inline")
        void range() {
            assertThrows(IllegalArgumentException.class, () -> ParallelBands.pipeline(0, stage -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> ParallelBands.pipeline(32, stage -> {
            }));
            var self = Thread.currentThread();
            ParallelBands.pipeline(1, stage -> assertSame(self, Thread.currentThread()));
        }
    }

    @Nested
    @DisplayName("submit")
    class Submit {
        @Test
        @DisplayName("runs every submitted task once and hands back its result or failure on each join")
        void results() {
            var started = new AtomicInteger();
            var pending = new ArrayList<ParallelBands.Pending<Integer>>();
            for (var i = 0; i < 64; i++) {
                var value = i;
                pending.add(ParallelBands.submit(() -> {
                    started.incrementAndGet();
                    return value + 1;
                }));
            }
            for (var i = 0; i < 64; i++) {
                assertEquals(i + 1, pending.get(i).join());
                assertEquals(i + 1, pending.get(i).join());
            }
            assertEquals(64, started.get());
            var failure = new IllegalStateException("failed");
            var failed = ParallelBands.<Integer>submit(() -> {
                throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class, failed::join));
            assertSame(failure, assertThrows(IllegalStateException.class, failed::join));
            var checked = ParallelBands.<Integer>submit(() -> {
                throw new Exception("checked");
            });
            assertEquals("checked", assertThrows(IllegalStateException.class, checked::join).getCause().getMessage());
        }

        @Test
        @DisplayName("joins from another thread than the one that submitted")
        void otherThread() throws Exception {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var round = 0; round < 100; round++) {
                    var pending = ParallelBands.submit(() -> 42);
                    assertEquals(42, executor.submit(pending::join).get());
                }
            }
        }

        @Test
        @DisplayName("a thread waiting for a started computation never takes on its own computations started after it")
        void orderedHelp() throws Exception {
            var release = new CountDownLatch(1);
            var entered = new AtomicInteger();
            var bands = ParallelBands.THREADS;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var blocker = executor.submit(() -> ParallelBands.run(bands, band -> {
                    entered.incrementAndGet();
                    try {
                        assertTrue(release.await(60, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                }));
                try {
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (entered.get() < bands && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals(bands, entered.get(), "every helper should be occupied");
                    // the owner is a virtual thread: a parked platform thread would let one more helper run
                    executor.submit(() -> {
                        var gate = new CountDownLatch(1);
                        var first = ParallelBands.submit(() -> {
                            assertTrue(gate.await(60, TimeUnit.SECONDS));
                            return 1;
                        });
                        var later = new java.util.concurrent.atomic.AtomicReference<Thread>();
                        var second = ParallelBands.submit(() -> {
                            later.set(Thread.currentThread());
                            return 2;
                        });
                        var runner = executor.submit(first::join);
                        while (!first.started()) {
                            Thread.sleep(1);
                        }
                        var opener = Thread.ofPlatform().start(() -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException interrupted) {
                                return;
                            }
                            gate.countDown();
                        });
                        assertEquals(1, first.join());
                        assertFalse(second.started(), "the waiting thread took on a later computation");
                        assertEquals(2, second.join());
                        assertSame(Thread.currentThread(), later.get());
                        assertEquals(1, runner.get());
                        opener.join();
                        return null;
                    }).get();
                } finally {
                    release.countDown();
                }
                blocker.get();
            }
        }

        @Test
        @DisplayName("canceling stops a task no thread started, or waits for one that started")
        void canceled() {
            for (var round = 0; round < 500; round++) {
                var ran = new AtomicInteger();
                var pending = ParallelBands.submit(() -> ran.incrementAndGet());
                pending.cancel();
                if (ran.get() == 0) {
                    assertThrows(IllegalStateException.class, pending::join);
                    assertEquals(0, ran.get());
                } else {
                    assertEquals(1, pending.join());
                }
            }
        }
    }

    @Nested
    @DisplayName("operations")
    class Operations {
        @Test
        @DisplayName("available() leaves a processor to every other thread running an operation, counting nested calls once")
        void load() throws Exception {
            if (!ParallelBands.enabled()) {
                return;
            }
            var helpers = ParallelBands.THREADS - 1;
            var others = Math.min(3, helpers);
            var entered = new CountDownLatch(others);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<?>>();
                for (var i = 0; i < others; i++) {
                    futures.add(executor.submit(() -> {
                        try (var _ = ParallelBands.operation(); var _ = ParallelBands.operation()) {
                            entered.countDown();
                            assertTrue(release.await(60, TimeUnit.SECONDS));
                        }
                        return null;
                    }));
                }
                assertTrue(entered.await(30, TimeUnit.SECONDS));
                assertEquals(helpers - others, ParallelBands.available());
                try (var _ = ParallelBands.operation()) {
                    assertEquals(helpers - others, ParallelBands.available());
                }
                release.countDown();
                for (var future : futures) {
                    future.get();
                }
            }
            assertEquals(helpers, ParallelBands.available());
        }

        @Test
        @DisplayName("a stream operation can be entered again on later calls, from another thread, and fails nothing on exceptions")
        void stream() throws Exception {
            var operation = new ParallelBands.Operation();
            for (var round = 0; round < 3; round++) {
                try (var _ = ParallelBands.operation(operation)) {
                    ParallelBands.run(4, band -> ParallelBands.checkpoint());
                }
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit((Runnable) () -> {
                    try (var _ = ParallelBands.operation(operation)) {
                        throw new IllegalStateException("failed call");
                    }
                });
            }
            if (ParallelBands.enabled()) {
                assertEquals(ParallelBands.THREADS - 1, ParallelBands.available());
            }
        }
    }

    @Nested
    @DisplayName("pool")
    class Pool {
        @Test
        @DisplayName("never runs more helper threads than the limit, however many callers fan out at once")
        void bounded() throws Exception {
            var executing = ConcurrentHashMap.<Thread>newKeySet();
            var peak = new AtomicInteger();
            var stop = new AtomicBoolean();
            var sampler = Thread.ofPlatform().daemon().start(() -> {
                while (!stop.get()) {
                    peak.accumulateAndGet(workers().size(), Math::max);
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<?>>();
                for (var caller = 0; caller < 64; caller++) {
                    futures.add(executor.submit(() -> {
                        for (var round = 0; round < 20; round++) {
                            var results = ParallelBands.invokeAll(nestedWork(executing));
                            assertEquals(8, results.size());
                            var pending = ParallelBands.submit(() -> spin(executing));
                            ParallelBands.run(6, band -> spin(executing));
                            pending.join();
                        }
                        return null;
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
            } finally {
                stop.set(true);
                sampler.join();
            }
            var helpers = executing.stream().filter(thread -> !thread.isVirtual()).count();
            assertTrue(helpers <= ParallelBands.THREADS - 1, helpers + " helper threads ran tasks");
            assertTrue(peak.get() <= ParallelBands.THREADS - 1, peak.get() + " helper threads alive at once");
        }

        @Test
        @DisplayName("brings every helper into a batch after a narrow batch, however the wake-ups are chained")
        void fullWidth() {
            assumeTrue(ParallelBands.THREADS >= 4, "needs at least four threads");
            ParallelBands.run(2, band -> spin(ConcurrentHashMap.newKeySet()));
            for (var round = 0; round < 50; round++) {
                var width = ParallelBands.THREADS;
                var arrived = new AtomicInteger();
                var met = new AtomicInteger();
                ParallelBands.run(width, band -> {
                    arrived.incrementAndGet();
                    var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
                    while (arrived.get() < width && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    if (arrived.get() == width) {
                        met.incrementAndGet();
                    }
                });
                assertEquals(width, met.get(), "round " + round);
                if (round % 10 == 9) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
            }
        }

        private static List<Callable<Integer>> nestedWork(Set<Thread> executing) {
            var tasks = new ArrayList<Callable<Integer>>();
            for (var i = 0; i < 8; i++) {
                var value = i;
                tasks.add(() -> {
                    ParallelBands.run(3, band -> spin(executing));
                    return value;
                });
            }
            return tasks;
        }

        static int spin(Set<Thread> executing) {
            executing.add(Thread.currentThread());
            var sum = 0;
            for (var i = 0; i < 20_000; i++) {
                sum += i * i;
            }
            return sum;
        }

        @Test
        @DisplayName("callers still progress while every helper is busy with another operation")
        void progressWhenBusy() throws Exception {
            var release = new CountDownLatch(1);
            var entered = new AtomicInteger();
            var bands = ParallelBands.THREADS;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var blocker = executor.submit(() -> ParallelBands.run(bands, band -> {
                    entered.incrementAndGet();
                    try {
                        assertTrue(release.await(60, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                }));
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (entered.get() < bands && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(bands, entered.get(), "every helper should be occupied");
                var results = ParallelBands.invokeAll(squares(16));
                assertEquals(225, results.get(15));
                var runs = new AtomicIntegerArray(12);
                ParallelBands.run(12, runs::incrementAndGet);
                for (var i = 0; i < 12; i++) {
                    assertEquals(1, runs.get(i));
                }
                assertEquals(9, ParallelBands.submit(() -> 9).join());
                assertFalse(blocker.isDone());
                release.countDown();
                blocker.get();
            }
        }

        @Test
        @DisplayName("while a platform thread is parked waiting, one more helper takes posted work, THREADS helpers at most")
        void compensation() throws Exception {
            if (!ParallelBands.enabled()) {
                return;
            }
            var release = new CountDownLatch(1);
            var entered = new AtomicInteger();
            var bands = ParallelBands.THREADS;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var blocker = executor.submit(() -> ParallelBands.run(bands, band -> {
                    entered.incrementAndGet();
                    try {
                        assertTrue(release.await(60, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                }));
                try {
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (entered.get() < bands && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals(bands, entered.get(), "every helper should be occupied");
                    var latch = new CountDownLatch(1);
                    var first = ParallelBands.submit(() -> {
                        assertTrue(latch.await(60, TimeUnit.SECONDS));
                        return 0;
                    });
                    var ranOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
                    var second = ParallelBands.submit(() -> {
                        ranOn.set(Thread.currentThread());
                        latch.countDown();
                        return 1;
                    });
                    var runner = executor.submit(first::join);
                    while (!first.started()) {
                        Thread.sleep(1);
                    }
                    assertEquals(0, first.join());
                    assertEquals(1, second.join());
                    assertTrue(ranOn.get().getName().startsWith(WORKER_PREFIX), ranOn.get().getName());
                    assertTrue(workers().size() <= ParallelBands.THREADS, workers().size() + " helpers alive");
                    assertEquals(0, runner.get());
                } finally {
                    release.countDown();
                }
                blocker.get();
            }
        }

        @Test
        @DisplayName("helpers blocked in a declared wait let other helpers take posted work, at most twice THREADS minus one in all")
        void blockingCompensation() throws Exception {
            if (!ParallelBands.enabled()) {
                return;
            }
            var release = new CountDownLatch(1);
            var blocked = new AtomicInteger();
            var bands = ParallelBands.THREADS;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var blocker = executor.submit(() -> ParallelBands.run(bands, band -> {
                    ParallelBands.beginBlocking();
                    try {
                        blocked.incrementAndGet();
                        assertTrue(release.await(60, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    } finally {
                        ParallelBands.endBlocking();
                    }
                }));
                try {
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (blocked.get() < bands && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals(bands, blocked.get(), "every helper should be blocked");
                    var ranOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
                    var owner = executor.submit(() -> {
                        var pending = ParallelBands.submit(() -> {
                            ranOn.set(Thread.currentThread());
                            return 1;
                        });
                        var wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (!pending.started() && System.nanoTime() < wait) {
                            Thread.sleep(1);
                        }
                        return pending.join();
                    });
                    assertEquals(1, owner.get());
                    assertTrue(ranOn.get().getName().startsWith(WORKER_PREFIX), ranOn.get().getName());
                    assertTrue(workers().size() <= 2 * ParallelBands.THREADS - 1, workers().size() + " helpers alive");
                } finally {
                    release.countDown();
                }
                blocker.get();
            }
        }

        @Test
        @DisplayName("keeps working after tasks fail")
        void survivesFailures() {
            for (var round = 0; round < 100; round++) {
                assertThrows(IllegalStateException.class, () -> ParallelBands.run(8, band -> {
                    throw new IllegalStateException("band " + band);
                }));
            }
            assertEquals(49, ParallelBands.invokeAll(squares(8)).get(7));
        }

        @Test
        @DisplayName("idle helpers exit on their own")
        void idleExit() throws Exception {
            ParallelBands.run(ParallelBands.THREADS, band -> spin(ConcurrentHashMap.newKeySet()));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!workers().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of(), workers());
            assertEquals(ParallelBands.enabled() ? ParallelBands.THREADS - 1 : 0, ParallelBands.available());
            assertEquals(List.of(0, 1, 4), ParallelBands.invokeAll(squares(3)));
        }
    }
}
