package com.github.auties00.smalt.image.jxl.internal.vardct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the lazily computed natural coefficient orders: their shape and their publication to
 * threads that race to compute them. The race clears the cache through reflection before every
 * round, so each round publishes the orders again.
 */
class HfPassTest {
    @Test
    @DisplayName("the natural 8x8 order is the JPEG zig-zag order")
    void zigZag() {
        var order = HfPass.dct8NaturalOrder();
        assertEquals(64, order.length);
        assertEquals(0, order[0]);
        assertEquals(1, order[1]);
        assertEquals(1 << 16, order[2]);
        assertEquals(2 << 16, order[3]);
        assertEquals(7 | 7 << 16, order[63]);
    }

    @Test
    @DisplayName("every racing thread sees the complete natural orders")
    void racingThreadsSeeCompleteOrders() throws Exception {
        var expected = new int[13][];
        for (var order = 0; order < 13; order++) {
            expected[order] = HfPass.natural(order).clone();
        }
        var cache = cache();
        var threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        for (var round = 0; round < 200; round++) {
            Arrays.fill(cache, null);
            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var seen = new int[threads][][];
            var workers = new ArrayList<Thread>();
            for (var t = 0; t < threads; t++) {
                var index = t;
                workers.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        var mine = new int[13][];
                        for (var order = 12; order >= 0; order--) {
                            var natural = HfPass.natural((order + index) % 13);
                            assertArrayEquals(expected[(order + index) % 13], natural);
                            mine[(order + index) % 13] = natural;
                        }
                        seen[index] = mine;
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }));
            }
            start.countDown();
            for (var worker : workers) {
                worker.join();
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            for (var t = 1; t < threads; t++) {
                for (var order = 0; order < 13; order++) {
                    assertSame(seen[0][order], seen[t][order], "every thread shares one array per order");
                }
            }
        }
    }

    private static int[][] cache() throws ReflectiveOperationException {
        var field = HfPass.class.getDeclaredField("NATURAL");
        field.setAccessible(true);
        return (int[][]) field.get(null);
    }
}
