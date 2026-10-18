package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the platform-independent halves of the sessions: the newest-frame hand-off of the
 * camera session and the pacing of the screen session, with simulated producers and grabbers.
 */
@DisplayName("capture sessions")
class SessionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Nested
    @DisplayName("newest-frame exchange")
    class Exchange {
        private static void produce(RawFrameExchange exchange, int value, long timestamp) {
            var frame = exchange.producerFrame();
            frame.data()[0] = (byte) value;
            frame.set(1, timestamp);
            exchange.publish();
        }

        @Test
        @DisplayName("a slow consumer receives the newest frame and skips the older ones")
        void newest() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(4);
                for (var i = 1; i <= 5; i++) {
                    produce(exchange, i, i * 100L);
                }
                var frame = exchange.take();
                assertEquals(5, frame.data()[0]);
                assertEquals(500, frame.timestampMicros());
                produce(exchange, 6, 600);
                assertEquals(6, exchange.take().data()[0]);
            });
        }

        @Test
        @DisplayName("the taken frame is not overwritten until the next take")
        void ownership() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(1);
                produce(exchange, 1, 1);
                var taken = exchange.take();
                for (var i = 2; i < 20; i++) {
                    produce(exchange, i, i);
                    assertEquals(1, taken.data()[0]);
                }
                assertEquals(19, exchange.take().data()[0]);
            });
        }

        @Test
        @DisplayName("a close from another thread wakes a waiting consumer with null")
        void closeUnblocks() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(1);
                var waiting = new CountDownLatch(1);
                var result = CompletableFuture.supplyAsync(() -> {
                    waiting.countDown();
                    try {
                        return exchange.take();
                    } catch (InterruptedException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                waiting.await();
                Thread.sleep(50);
                exchange.close();
                exchange.close();
                assertNull(result.get(5, TimeUnit.SECONDS));
                assertNull(exchange.take());
            });
        }

        @Test
        @DisplayName("a stopped producer's last frame is delivered before null")
        void stopped() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(1);
                produce(exchange, 7, 70);
                exchange.finish(null);
                assertEquals(7, exchange.take().data()[0]);
                assertNull(exchange.take());
            });
        }

        @Test
        @DisplayName("a failed producer makes the consumer raise Io")
        void failed() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(1);
                exchange.finish(new IOException("VIDIOC_DQBUF failed"));
                var failure = assertThrows(MediaException.Io.class, exchange::take);
                assertEquals("VIDIOC_DQBUF failed", failure.getMessage());
            });
        }

        @Test
        @DisplayName("an interrupted consumer raises InterruptedException")
        void interrupted() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(1);
                Thread.currentThread().interrupt();
                assertThrows(InterruptedException.class, exchange::take);
            });
        }

        @Test
        @DisplayName("a concurrent producer and consumer never see a torn or repeated frame")
        void concurrent() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var exchange = new RawFrameExchange(64);
                var producer = Thread.ofPlatform().start(() -> {
                    for (var i = 1; i <= 20_000; i++) {
                        var frame = exchange.producerFrame();
                        Arrays.fill(frame.data(), (byte) i);
                        frame.set(64, i);
                        exchange.publish();
                    }
                    exchange.finish(null);
                });
                var last = 0L;
                var received = 0;
                while (true) {
                    var frame = exchange.take();
                    if (frame == null) {
                        break;
                    }
                    assertTrue(frame.timestampMicros() > last, "frames arrive in order, each at most once");
                    last = frame.timestampMicros();
                    for (var sample : frame.data()) {
                        assertEquals((byte) frame.timestampMicros(), sample, "the frame is not torn");
                    }
                    received++;
                }
                producer.join();
                assertEquals(20_000, last);
                assertTrue(received >= 1);
            });
        }
    }

    @Nested
    @DisplayName("paced screen session")
    class Paced {
        private static final CaptureDevice.Screen SCREEN = new CaptureDevice.Screen("DP-1", "DP-1", 0, 0, 64, 32, true);

        private static Rational fps(int fps) {
            return new Rational(fps, 1);
        }

        /** A grabber that counts grabs and closes, and can report the screen gone. */
        private static final class FakeGrabber implements ScreenGrabber {
            final YuvPicture picture = YuvPicture.allocate(64, 32, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            final AtomicInteger grabs = new AtomicInteger();
            final AtomicInteger closes = new AtomicInteger();
            volatile boolean gone;
            volatile long delayMillis;

            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 32;
            }

            @Override
            public YuvPicture grab() {
                grabs.incrementAndGet();
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                return gone ? null : picture;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        }

        @Test
        @DisplayName("frames are paced to the requested rate with increasing timestamps")
        void pacing() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var grabber = new FakeGrabber();
                try (VideoCaptureSession session = new PacedCaptureSession(SCREEN, grabber, fps(50))) {
                    assertEquals(64, session.width());
                    assertEquals(32, session.height());
                    assertEquals(SCREEN, session.device());
                    var start = System.nanoTime();
                    var previous = -1L;
                    for (var i = 0; i < 6; i++) {
                        var frame = session.read();
                        assertNotNull(frame);
                        assertTrue(frame.timestampMicros() > previous);
                        previous = frame.timestampMicros();
                    }
                    var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    assertTrue(elapsed >= 95, "six frames at 50 fps span at least five periods, took " + elapsed + " ms");
                    assertTrue(previous < TimeUnit.SECONDS.toMicros(5), "timestamps start near zero");
                }
                assertEquals(1, grabber.closes.get());
            });
        }

        @Test
        @DisplayName("a reader slower than the rate grabs immediately")
        void slowReader() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var grabber = new FakeGrabber();
                try (var session = new PacedCaptureSession(SCREEN, grabber, fps(100))) {
                    session.read();
                    Thread.sleep(80);
                    var start = System.nanoTime();
                    session.read();
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 8, "no wait after a late read");
                }
            });
        }

        @Test
        @DisplayName("a close from another thread ends a waiting read with null, once")
        void closeUnblocks() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var grabber = new FakeGrabber();
                var session = new PacedCaptureSession(SCREEN, grabber, fps(1));
                assertNotNull(session.read());
                var result = CompletableFuture.supplyAsync(() -> {
                    try {
                        return session.read();
                    } catch (InterruptedException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                Thread.sleep(50);
                var closed = System.nanoTime();
                session.close();
                assertNull(result.get(5, TimeUnit.SECONDS));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closed) < 500);
                session.close();
                assertNull(session.read());
                assertEquals(1, grabber.closes.get());
                assertEquals(1, grabber.grabs.get());
            });
        }

        @Test
        @DisplayName("a close waits for a grab in progress")
        void closeDuringGrab() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var grabber = new FakeGrabber();
                grabber.delayMillis = 100;
                var session = new PacedCaptureSession(SCREEN, grabber, fps(30));
                var reading = CompletableFuture.supplyAsync(() -> {
                    try {
                        return session.read();
                    } catch (InterruptedException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                while (grabber.grabs.get() == 0) {
                    Thread.onSpinWait();
                }
                session.close();
                assertNotNull(reading.get(5, TimeUnit.SECONDS), "the grab in progress completes");
                assertEquals(1, grabber.closes.get());
            });
        }

        @Test
        @DisplayName("a vanished screen ends the session with null")
        void vanished() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var grabber = new FakeGrabber();
                try (var session = new PacedCaptureSession(SCREEN, grabber, fps(1000))) {
                    assertNotNull(session.read());
                    grabber.gone = true;
                    assertNull(session.read());
                    grabber.gone = false;
                    assertNull(session.read());
                }
            });
        }

        @Test
        @DisplayName("an interrupted reader raises InterruptedException")
        void interrupted() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                try (var session = new PacedCaptureSession(SCREEN, new FakeGrabber(), fps(1))) {
                    session.read();
                    Thread.currentThread().interrupt();
                    assertThrows(InterruptedException.class, session::read);
                }
            });
        }

        @Test
        @DisplayName("frames are paced to a fractional rate's period")
        void fractionalRate() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                try (var session = new PacedCaptureSession(SCREEN, new FakeGrabber(), new Rational(100, 3))) {
                    var start = System.nanoTime();
                    for (var i = 0; i < 6; i++) {
                        assertNotNull(session.read());
                    }
                    var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    assertTrue(elapsed >= 145, "six frames at 100/3 fps span at least five 30 ms periods, took " + elapsed + " ms");
                }
            });
        }

        @Test
        @DisplayName("a null rate is rejected")
        void rate() {
            assertThrows(NullPointerException.class, () -> new PacedCaptureSession(SCREEN, new FakeGrabber(), null));
        }
    }

    @Nested
    @DisplayName("X11 pixel layout")
    class Layout {
        @Test
        @DisplayName("the TrueColor masks of an LSBFirst image are B, G, R, X in memory")
        void bgrx() {
            assertEquals(new RgbLayout(2, 1, 0), X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0xFF0000, 0xFF00, 0xFF));
        }

        @Test
        @DisplayName("an MSBFirst image stores X, R, G, B")
        void xrgb() {
            assertEquals(new RgbLayout(1, 2, 3), X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.MSB_FIRST, 0xFF0000, 0xFF00, 0xFF));
        }

        @Test
        @DisplayName("swapped masks and a top-byte channel are located")
        void rgbx() {
            assertEquals(new RgbLayout(0, 1, 2), X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0xFF, 0xFF00, 0xFF0000));
            assertEquals(new RgbLayout(3, 2, 1), X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0xFF000000L, 0xFF0000, 0xFF00));
        }

        @Test
        @DisplayName("16-bit, 10-bit and inconsistent formats are unsupported")
        void unsupported() {
            assertNull(X11ScreenGrabber.layoutOf(16, X11ScreenGrabber.LSB_FIRST, 0xF800, 0x7E0, 0x1F));
            assertNull(X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0x3FF00000, 0xFFC00, 0x3FF));
            assertNull(X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0xFF, 0xFF, 0xFF0000));
            assertNull(X11ScreenGrabber.layoutOf(32, 2, 0xFF0000, 0xFF00, 0xFF));
            assertNull(X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0, 0xFF00, 0xFF));
            assertNull(X11ScreenGrabber.layoutOf(32, X11ScreenGrabber.LSB_FIRST, 0xFF00000000L, 0xFF00, 0xFF));
        }
    }

    @Test
    @DisplayName("screens are ordered primary first, then by position")
    void screenOrder() {
        var left = new CaptureDevice.Screen("HDMI-1", "HDMI-1", 0, 0, 1920, 1080, false);
        var right = new CaptureDevice.Screen("DP-1", "DP-1", 1920, 0, 2560, 1440, true);
        var below = new CaptureDevice.Screen("DP-2", "DP-2", 0, 1080, 1920, 1080, false);
        var screens = new ArrayList<>(List.of(below, left, right));
        screens.sort(X11Screens.order());
        assertEquals(List.of(right, left, below), screens);
    }
}
