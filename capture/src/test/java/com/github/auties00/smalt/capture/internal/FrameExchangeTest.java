package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the triple-buffer hand-off behind the Windows and macOS sessions: newest-frame delivery,
 * picture ownership, producer-side and reader-side repeats, the end states, and a close from another
 * thread while a reader waits.
 */
@DisplayName("frame exchange")
class FrameExchangeTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static FrameExchange exchange() {
        return new FrameExchange(4, 2, ColorInfo.BT601_LIMITED);
    }

    private static FrameExchange repeating(long repeatNanos) {
        return new FrameExchange(4, 4, ColorInfo.BT709_LIMITED, repeatNanos, System::nanoTime, System.nanoTime());
    }

    private static void write(FrameExchange exchange, int value, long timestamp) {
        var target = exchange.acquire();
        target.y()[0] = (byte) value;
        exchange.publish(target, timestamp);
    }

    @Nested
    @DisplayName("delivery")
    class Delivery {
        @Test
        @DisplayName("starts with black pictures of the requested size")
        void black() {
            var exchange = exchange();
            var picture = exchange.acquire();
            assertEquals(4, exchange.width());
            assertEquals(2, exchange.height());
            assertEquals(16, picture.y()[0]);
            assertEquals((byte) 128, picture.u()[0]);
        }

        @Test
        @DisplayName("delivers the newest picture to a slow reader and skips the older ones")
        void newest() throws InterruptedException {
            var exchange = exchange();
            write(exchange, 1, 10);
            write(exchange, 2, 20);
            write(exchange, 3, 30);
            var frame = exchange.take();
            assertEquals(3, frame.picture().y()[0]);
            assertEquals(30, frame.timestampMicros());
            assertNull(exchange.poll(0));
        }

        @Test
        @DisplayName("never hands the producer the published picture or the one the reader holds")
        void rotation() throws InterruptedException {
            var exchange = exchange();
            var used = new IdentityHashMap<Object, Boolean>();
            for (var i = 0; i < 20; i++) {
                var written = exchange.acquire();
                exchange.publish(written, 2 * i);
                var held = exchange.take().picture();
                assertSame(written, held);
                var next = exchange.acquire();
                assertNotSame(held, next);
                exchange.publish(next, 2 * i + 1);
                var free = exchange.acquire();
                assertNotSame(held, free);
                assertNotSame(next, free);
                used.put(written, true);
                used.put(next, true);
                used.put(free, true);
                exchange.take();
            }
            assertEquals(3, used.size());
        }

        @Test
        @DisplayName("keeps a delivered picture intact while the producer keeps writing")
        void ownership() throws InterruptedException {
            var exchange = exchange();
            for (var round = 0; round < 50; round++) {
                write(exchange, round, round);
                var held = exchange.poll(0).picture();
                for (var write = 0; write < 3; write++) {
                    assertNotSame(held, exchange.acquire());
                    write(exchange, 200, 1_000 + round * 10L + write);
                }
                assertEquals((byte) round, held.y()[0]);
                exchange.poll(0);
            }
        }

        @Test
        @DisplayName("keeps delivered timestamps non-negative and strictly increasing")
        void monotonic() throws InterruptedException {
            var exchange = exchange();
            var stamps = new ArrayList<Long>();
            for (var timestamp : new long[]{-5, 100, 50, 50, 400, 399}) {
                write(exchange, 0, timestamp);
                stamps.add(exchange.poll(0).timestampMicros());
            }
            assertEquals(List.of(0L, 100L, 101L, 102L, 400L, 401L), stamps);
        }

        @Test
        @DisplayName("accepts a relabeled picture sharing the acquired planes")
        void relabel() throws InterruptedException {
            var exchange = repeating(0);
            var target = exchange.acquire();
            var relabeled = YuvPicture.builder().size(4, 4).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT601_LIMITED)
                    .luma(target.y(), target.yStride())
                    .chroma(target.u(), target.v(), target.uvStride())
                    .build();
            exchange.publish(relabeled, 5);
            var frame = exchange.poll(0);
            assertSame(relabeled, frame.picture());
            assertEquals(ColorInfo.BT601_LIMITED, frame.picture().color());
            assertNotSame(target, exchange.acquire());
        }

        @Test
        @DisplayName("rejects a picture that is not one of its own")
        void foreign() {
            var exchange = exchange();
            var foreign = YuvPicture.allocate(4, 2, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> exchange.publish(foreign, 0));
        }

        @Test
        @DisplayName("returns nothing when the timeout elapses without a picture")
        void timeout() throws InterruptedException {
            var exchange = exchange();
            var start = System.nanoTime();
            assertNull(exchange.poll(TimeUnit.MILLISECONDS.toNanos(30)));
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(25));
            assertFalse(exchange.isDone());
        }
    }

    @Nested
    @DisplayName("repeats")
    class Repeats {
        @Test
        @DisplayName("the producer republishes the previous picture with a later timestamp")
        void republish() throws InterruptedException {
            var exchange = exchange();
            assertFalse(exchange.republish(5));
            var picture = exchange.acquire();
            exchange.publish(picture, 100);
            assertEquals(100, exchange.take().timestampMicros());
            assertTrue(exchange.republish(100));
            var again = exchange.take();
            assertSame(picture, again.picture());
            assertEquals(101, again.timestampMicros());
            exchange.publish(exchange.acquire(), 50);
            assertEquals(102, exchange.take().timestampMicros());
        }

        @Test
        @DisplayName("the reader receives the last picture again after one interval")
        void repeats() throws InterruptedException {
            var interval = TimeUnit.MILLISECONDS.toNanos(40);
            var exchange = repeating(interval);
            write(exchange, 9, 1);
            var first = exchange.poll(SECOND);
            var start = System.nanoTime();
            var second = exchange.poll(SECOND);
            var waited = System.nanoTime() - start;
            assertSame(first.picture(), second.picture());
            assertTrue(second.timestampMicros() > first.timestampMicros());
            assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(30), "repeated after " + waited + " ns");
            assertTrue(waited < SECOND / 2, "repeated after " + waited + " ns");
        }

        @Test
        @DisplayName("nothing repeats before a first picture exists")
        void noFirstPicture() throws InterruptedException {
            var exchange = repeating(TimeUnit.MILLISECONDS.toNanos(5));
            assertNull(exchange.poll(TimeUnit.MILLISECONDS.toNanos(40)));
        }

        @Test
        @DisplayName("a new picture wins over a repeat")
        void newWins() throws InterruptedException {
            var exchange = repeating(TimeUnit.MILLISECONDS.toNanos(5));
            write(exchange, 1, 1);
            exchange.poll(SECOND);
            write(exchange, 2, 1_000_000);
            var frame = exchange.poll(SECOND);
            assertEquals(2, frame.picture().y()[0]);
            assertEquals(1_000_000, frame.timestampMicros());
        }
    }

    @Nested
    @DisplayName("ending")
    class Ending {
        @Test
        @DisplayName("a close from another thread unblocks a waiting reader, which gets null, idempotently")
        void closeUnblocks() {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                var exchange = exchange();
                var waiting = new CountDownLatch(1);
                var result = new AtomicReference<>(new VideoCaptureSession.Frame(null, -1));
                var reader = Thread.ofVirtual().start(() -> {
                    waiting.countDown();
                    try {
                        result.set(exchange.take());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                waiting.await();
                Thread.sleep(50);
                exchange.close();
                exchange.close();
                reader.join();
                assertNull(result.get());
                assertTrue(exchange.isClosed());
                assertTrue(exchange.isDone());
                assertTrue(exchange.isStopped());
                assertNull(exchange.poll(SECOND));
                assertFalse(exchange.sleepUntil(System.nanoTime() + SECOND));
            });
        }

        @Test
        @DisplayName("ignores pictures published after the end")
        void publishAfterClose() throws InterruptedException {
            var exchange = exchange();
            exchange.close();
            write(exchange, 1, 1);
            assertNull(exchange.poll(0));
        }

        @Test
        @DisplayName("delivers a pending picture before reporting that the source finished")
        void finish() throws InterruptedException {
            var exchange = exchange();
            write(exchange, 5, 5);
            exchange.finish();
            write(exchange, 6, 6);
            assertFalse(exchange.isDone());
            assertEquals(5, exchange.take().picture().y()[0]);
            assertTrue(exchange.isDone());
            assertNull(exchange.take());
        }

        @Test
        @DisplayName("reports the first failure once, after the pictures published before it, then null")
        void failure() throws InterruptedException {
            var exchange = repeating(TimeUnit.MILLISECONDS.toNanos(5));
            var error = new MediaException.Io("gone", new IOException("gone"));
            write(exchange, 1, 1);
            exchange.fail(error);
            exchange.fail(new MediaException.Io("second", new IOException("second")));
            assertTrue(exchange.isStopped());
            assertEquals(1, exchange.poll(SECOND).timestampMicros());
            assertFalse(exchange.isDone());
            assertSame(error, assertThrows(MediaException.Io.class, () -> exchange.poll(SECOND)));
            assertTrue(exchange.isDone());
            assertNull(exchange.poll(SECOND));
        }

        @Test
        @DisplayName("propagates an interrupt of the waiting reader")
        void interrupt() {
            var exchange = exchange();
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, exchange::take);
            assertFalse(Thread.interrupted());
        }
    }

    @Nested
    @DisplayName("clock")
    class Clock {
        @Test
        @DisplayName("the producer sleeps until its deadline while the exchange runs")
        void sleep() {
            var exchange = exchange();
            var start = System.nanoTime();
            assertTrue(exchange.sleepUntil(start + 20_000_000L));
            assertTrue(System.nanoTime() - start >= 20_000_000L);
        }

        @Test
        @DisplayName("timestamps count from the origin")
        void micros() {
            var exchange = new FrameExchange(4, 4, ColorInfo.BT709_LIMITED, 0, () -> 0, 1_000_000);
            assertEquals(1_000, exchange.micros(2_000_000));
            assertEquals(-1, exchange.micros(999_000));
            assertThrows(IllegalArgumentException.class,
                    () -> new FrameExchange(4, 4, ColorInfo.BT709_LIMITED, -1, System::nanoTime, 0));
        }
    }
}
