package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the queue between device threads and session callers: wrap-around, dropping the oldest
 * frames, blocking reads and writes, and the three ways it ends.
 */
@Timeout(10)
@DisplayName("audio ring")
class AudioRingTest {
    private static float[] ramp(int from, int frames, int channels) {
        var samples = new float[frames * channels];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                samples[i * channels + c] = from + i + c * 0.5f;
            }
        }
        return samples;
    }

    private static MediaException.Io deviceGone() {
        return new MediaException.Io("gone", new IOException("gone"));
    }

    @Nested
    @DisplayName("capture side")
    class Capture {
        @Test
        @DisplayName("keeps frames in order across the wrap-around")
        void wrap() throws InterruptedException {
            var ring = new AudioRing(2, 10);
            var out = new float[14];
            for (var round = 0; round < 5; round++) {
                assertEquals(0, ring.append(ramp(round * 7, 7, 2), 0, 7));
                assertEquals(7, ring.read(out, 0, 7));
                assertArrayEquals(ramp(round * 7, 7, 2), out);
            }
            assertEquals(35, ring.head());
            assertEquals(35, ring.tail());
        }

        @Test
        @DisplayName("drops the oldest frames when full and counts them")
        void overflow() throws InterruptedException {
            var ring = new AudioRing(1, 8);
            assertEquals(0, ring.append(ramp(0, 6, 1), 0, 6));
            assertEquals(4, ring.append(ramp(6, 6, 1), 0, 6));
            var out = new float[8];
            assertEquals(8, ring.read(out, 0, 8));
            assertArrayEquals(ramp(4, 8, 1), out);
            assertEquals(4, ring.readStart());
        }

        @Test
        @DisplayName("keeps only the newest frames of a block larger than the ring")
        void hugeAppend() throws InterruptedException {
            var ring = new AudioRing(1, 4);
            ring.append(ramp(0, 2, 1), 0, 2);
            assertEquals(2 + 6, ring.append(ramp(2, 10, 1), 0, 10));
            var out = new float[4];
            assertEquals(4, ring.read(out, 0, 4));
            assertArrayEquals(ramp(8, 4, 1), out);
            assertEquals(12, ring.head());
        }

        @Test
        @DisplayName("serves a read longer than the ring in parts as frames arrive")
        void longRead() throws InterruptedException {
            var ring = new AudioRing(1, 8);
            var producer = Thread.ofPlatform().start(() -> {
                for (var i = 0; i < 10; i++) {
                    ring.append(ramp(i * 3, 3, 1), 0, 3);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException exception) {
                        return;
                    }
                }
            });
            var out = new float[30];
            assertEquals(30, ring.read(out, 0, 30));
            assertArrayEquals(ramp(0, 30, 1), out);
            producer.join();
        }

        @Test
        @DisplayName("blocks a read until enough frames arrive")
        void blockingRead() throws InterruptedException {
            var ring = new AudioRing(1, 16);
            var result = new AtomicInteger();
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    result.set(ring.read(new float[5], 0, 5));
                } catch (InterruptedException exception) {
                    result.set(-100);
                }
            });
            ring.append(ramp(0, 3, 1), 0, 3);
            Thread.sleep(50);
            assertTrue(reader.isAlive());
            ring.append(ramp(3, 3, 1), 0, 3);
            reader.join(1_000);
            assertEquals(5, result.get());
            assertEquals(1, ring.size());
        }

        @Test
        @DisplayName("throws InterruptedException and consumes nothing when interrupted before any frame")
        void interrupt() throws InterruptedException {
            var ring = new AudioRing(1, 16);
            ring.append(ramp(0, 2, 1), 0, 2);
            var failure = new AtomicReference<Throwable>();
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    ring.read(new float[4], 0, 4);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            Thread.sleep(50);
            reader.interrupt();
            reader.join(1_000);
            assertInstanceOf(InterruptedException.class, failure.get());
            assertEquals(2, ring.size());
        }

        @Test
        @DisplayName("delivers the frames queued before a failure, then the failure once, then the end")
        void failure() throws InterruptedException {
            var ring = new AudioRing(1, 16);
            ring.append(ramp(0, 3, 1), 0, 3);
            var exception = deviceGone();
            ring.fail(exception);
            assertTrue(ring.stopped());
            var out = new float[5];
            assertEquals(3, ring.read(out, 0, 5));
            assertSame(exception, assertThrows(MediaException.Io.class, () -> ring.read(out, 0, 5)));
            assertEquals(-1, ring.read(out, 0, 5));
            assertEquals(0, ring.append(ramp(0, 3, 1), 0, 3), "appends after the end are discarded");
        }

        @Test
        @DisplayName("returns the end at once after a close, even with frames queued")
        void close() throws InterruptedException {
            var ring = new AudioRing(1, 16);
            ring.append(ramp(0, 3, 1), 0, 3);
            var result = new AtomicInteger(Integer.MIN_VALUE);
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    result.set(ring.read(new float[8], 0, 8));
                } catch (InterruptedException exception) {
                    result.set(-100);
                }
            });
            Thread.sleep(30);
            ring.close();
            reader.join(1_000);
            assertEquals(-1, result.get());
            assertEquals(-1, ring.read(new float[1], 0, 1));
        }
    }

    @Nested
    @DisplayName("playback side")
    class Playback {
        @Test
        @DisplayName("polls what is queued without blocking")
        void poll() throws InterruptedException {
            var ring = new AudioRing(2, 8);
            assertEquals(0, ring.poll(new float[4], 0, 2));
            assertEquals(3, ring.write(ramp(0, 3, 2), 0, 3));
            var out = new float[8];
            assertEquals(3, ring.poll(out, 0, 4));
            assertArrayEquals(ramp(0, 3, 2), java.util.Arrays.copyOf(out, 6));
        }

        @Test
        @DisplayName("blocks a write while full and resumes as frames are polled")
        void backpressure() throws InterruptedException {
            var ring = new AudioRing(1, 4);
            var written = new AtomicInteger();
            var writer = Thread.ofPlatform().start(() -> {
                try {
                    written.set(ring.write(ramp(0, 10, 1), 0, 10));
                } catch (InterruptedException exception) {
                    written.set(-100);
                }
            });
            var out = new float[10];
            var taken = 0;
            while (taken < 10) {
                taken += ring.poll(out, taken, 10 - taken);
                Thread.onSpinWait();
            }
            writer.join(1_000);
            assertEquals(10, written.get());
            assertArrayEquals(ramp(0, 10, 1), out);
        }

        @Test
        @DisplayName("returns a partial write when closed, and throws a device failure once")
        void endings() throws InterruptedException {
            var ring = new AudioRing(1, 4);
            var written = new AtomicInteger();
            var writer = Thread.ofPlatform().start(() -> {
                try {
                    written.set(ring.write(ramp(0, 10, 1), 0, 10));
                } catch (InterruptedException exception) {
                    written.set(-100);
                }
            });
            while (ring.size() < 4) {
                Thread.onSpinWait();
            }
            ring.close();
            writer.join(1_000);
            assertEquals(4, written.get());

            var failed = new AudioRing(1, 4);
            var exception = deviceGone();
            failed.fail(exception);
            assertSame(exception, assertThrows(MediaException.Io.class, () -> failed.write(ramp(0, 2, 1), 0, 2)));
            assertEquals(0, failed.write(ramp(0, 2, 1), 0, 2));
        }

        @Test
        @DisplayName("waits for the queue to drain")
        void awaitEmpty() throws InterruptedException {
            var ring = new AudioRing(1, 8);
            ring.write(ramp(0, 6, 1), 0, 6);
            var consumer = Thread.ofPlatform().start(() -> {
                var out = new float[2];
                for (var i = 0; i < 3; i++) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException exception) {
                        return;
                    }
                    ring.poll(out, 0, 2);
                }
            });
            ring.awaitEmpty();
            assertEquals(0, ring.size());
            consumer.join();
            assertFalse(ring.stopped());
        }
    }

    @Test
    @DisplayName("rejects an empty or oversized ring")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> new AudioRing(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AudioRing(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AudioRing(64, Integer.MAX_VALUE / 2));
    }
}
