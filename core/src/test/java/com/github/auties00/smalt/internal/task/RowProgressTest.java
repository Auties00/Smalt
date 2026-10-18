package com.github.auties00.smalt.internal.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the row progress tracker of the wavefronts: ordering, reuse across batches and failure
 * release, on platform threads so that the parking path runs.
 */
class RowProgressTest {
    @Test
    @DisplayName("lets each row run only behind the row above, by two blocks, as a wavefront does")
    void wavefront() throws InterruptedException {
        var rows = 8;
        var width = 200;
        var progress = new RowProgress();
        var violation = new AtomicBoolean();
        var seen = new AtomicIntegerArray(rows);
        for (var batch = 0; batch < 3; batch++) {
            progress.begin(rows);
            var threads = new ArrayList<Thread>();
            for (var row = 0; row < rows; row++) {
                var current = row;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (var x = 0; x < width; x++) {
                        if (current > 0) {
                            var needed = Math.min(x + 2, width);
                            if (!progress.await(current - 1, needed) || seen.get(current - 1) < needed) {
                                violation.set(true);
                            }
                        }
                        if ((x & 15) == 0) {
                            Thread.yield();
                        }
                        seen.set(current, x + 1);
                        progress.advance(current, x + 1);
                    }
                }));
            }
            for (var thread : threads) {
                thread.join();
            }
            for (var row = 0; row < rows; row++) {
                assertEquals(width, progress.progress(row));
                seen.set(row, 0);
            }
        }
        assertFalse(violation.get());
    }

    @Test
    @DisplayName("releases a parked row when the batch fails")
    void failure() throws InterruptedException {
        var progress = new RowProgress();
        progress.begin(2);
        var result = new AtomicBoolean(true);
        var waiter = Thread.ofPlatform().start(() -> result.set(progress.await(0, 5)));
        Thread.sleep(50);
        progress.fail();
        waiter.join(10_000);
        assertFalse(waiter.isAlive());
        assertFalse(result.get());
        assertTrue(progress.failed());
        progress.begin(2);
        assertFalse(progress.failed());
        assertEquals(0, progress.progress(0));
        progress.advance(0, 5);
        assertTrue(progress.await(0, 5));
    }
}
