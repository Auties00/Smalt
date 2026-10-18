package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the platform-independent helpers of the macOS backend: timestamp mapping, the callback
 * registry, screen naming and ordering, color attachment resolution, error wrapping, and the
 * move of Objective-C work off virtual threads.
 */
@DisplayName("macOS capture support")
class SupportTest {
    @Nested
    @DisplayName("capture clock")
    class Clock {
        private final CaptureClock clock = new CaptureClock(5_000_000_000L, 1_000);

        @Test
        @DisplayName("maps host presentation times onto the session clock")
        void maps() {
            // host clock 5000 s at open, session clock 1 ms at open; a frame captured 2.5 s later
            var presentation = CmTime.of(5_002_500_000_000L, 1_000_000_000);
            assertEquals(2_501_000, clock.timestamp(presentation, 2_520_000));
        }

        @Test
        @DisplayName("falls back to the arrival time for missing or implausible presentation times")
        void fallsBack() {
            assertEquals(7, clock.timestamp(new CmTime(0, 0, 0, 0), 7));
            assertEquals(500_000, clock.timestamp(CmTime.of(9_000, 1), 500_000));
            assertEquals(20_000_000, clock.timestamp(CmTime.of(5_000, 1), 20_000_000));
            assertEquals(3, new CaptureClock(Long.MIN_VALUE, 0).timestamp(CmTime.of(Long.MAX_VALUE / 2, 1_000_000), 3));
        }
    }

    @Nested
    @DisplayName("callback registry")
    class Registry {
        @Test
        @DisplayName("issues distinct positive keys, even from many threads at once")
        void keys() {
            var registry = new CallbackRegistry();
            var keys = ConcurrentHashMap.<Long>newKeySet();
            IntStream.range(0, 2_000).parallel().forEach(i -> keys.add(registry.register(new Object())));
            assertEquals(2_000, keys.size());
            assertTrue(keys.stream().allMatch(key -> key > 0));
            assertEquals(2_000, registry.size());
        }

        @Test
        @DisplayName("finds a target only under its key, of its type, until unregistered")
        void lookup() {
            var registry = new CallbackRegistry();
            var completion = new BlockCompletion();
            var key = registry.register(completion);
            assertSame(completion, registry.lookup(key, BlockCompletion.class));
            assertNull(registry.lookup(key, DelegateSink.class));
            assertNull(registry.lookup(0, BlockCompletion.class));
            registry.unregister(key);
            registry.unregister(key);
            assertNull(registry.lookup(key, BlockCompletion.class));
            assertEquals(0, registry.size());
        }
    }

    @Nested
    @DisplayName("block completions")
    class Completions {
        @Test
        @DisplayName("keep the first result and report later ones as refused")
        void firstWins() throws InterruptedException {
            var completion = new BlockCompletion();
            var error = new NsError("domain", 1, "first");
            assertTrue(completion.complete(MemorySegment.NULL, error));
            assertFalse(completion.complete(MemorySegment.NULL, new NsError("domain", 2, "second")));
            completion.completeFlag(true);
            assertTrue(completion.await(0));
            assertSame(error, completion.error());
            assertFalse(completion.flag());
        }

        @Test
        @DisplayName("time out when no result arrives")
        void timeout() throws InterruptedException {
            assertFalse(new BlockCompletion().await(10));
        }
    }

    @Nested
    @DisplayName("screens")
    class Screens {
        @Test
        @DisplayName("put the main display first, mark it primary and name the displays")
        void ordering() {
            var screens = MacDisplays.screens(List.of(
                    new MacDisplays.Display(2, -1920, 0, 1920, 1080, false),
                    new MacDisplays.Display(1, 0, 0, 3024, 1964, true),
                    new MacDisplays.Display(3, 1512, -200, 2560, 1440, false)), 1);
            assertEquals(List.of(
                    new CaptureDevice.Screen("1", "Built-in Display", 0, 0, 3024, 1964, true),
                    new CaptureDevice.Screen("2", "External Display", -1920, 0, 1920, 1080, false),
                    new CaptureDevice.Screen("3", "External Display 2", 1512, -200, 2560, 1440, false)), screens);
        }

        @Test
        @DisplayName("drop displays without a size and render ids as unsigned numbers")
        void edges() {
            var screens = MacDisplays.screens(List.of(
                    new MacDisplays.Display(0x8000_0001, 0, 0, 1280, 800, false),
                    new MacDisplays.Display(5, 0, 0, 0, 800, false)), 42);
            assertEquals(1, screens.size());
            assertEquals("2147483649", screens.getFirst().id());
            assertFalse(screens.getFirst().primary());
            assertEquals(0x8000_0001, MacDisplays.displayId(screens.getFirst()));
            assertTrue(MacDisplays.screens(List.of(), 1).isEmpty());
        }

        @Test
        @DisplayName("reject screen ids that are not display identifiers")
        void badId() {
            var screen = new CaptureDevice.Screen("\\\\.\\DISPLAY1", "Monitor", 0, 0, 10, 10, true);
            assertThrows(NumberFormatException.class, () -> MacDisplays.displayId(screen));
        }
    }

    @Nested
    @DisplayName("color attachments")
    class Attachments {
        @Test
        @DisplayName("resolve every known constant to its H.273 value")
        void known() {
            assertEquals(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.FULL, ColorPrimaries.BT2020, TransferCharacteristics.PQ),
                    ColorAttachments.resolve(3, 6, 4, ColorRange.FULL, 2160));
            assertEquals(new ColorInfo(MatrixCoefficients.BT601, ColorRange.LIMITED, ColorPrimaries.SMPTE170M, TransferCharacteristics.BT709),
                    ColorAttachments.resolve(1, 2, 0, ColorRange.LIMITED, 480));
        }

        @Test
        @DisplayName("default by picture height and matrix when attachments are missing")
        void defaults() {
            assertEquals(ColorInfo.BT709_LIMITED, ColorAttachments.resolve(-1, -1, -1, ColorRange.LIMITED, 720));
            assertEquals(ColorInfo.BT601_LIMITED, ColorAttachments.resolve(-1, -1, -1, ColorRange.LIMITED, 480));
            assertEquals(ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL), ColorAttachments.resolve(0, 99, -7, ColorRange.FULL, 100));
        }

        @Test
        @DisplayName("name exported CoreVideo constants, each once")
        void tables() {
            var symbols = new HashSet<String>();
            for (var table : List.of(ColorAttachments.MATRICES, ColorAttachments.PRIMARIES, ColorAttachments.TRANSFERS)) {
                for (var constant : table) {
                    assertTrue(constant.symbol().startsWith("kCVImageBuffer"), constant.symbol());
                    assertTrue(symbols.add(constant.symbol()), constant.symbol());
                }
            }
            assertEquals(19, symbols.size());
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {
        @Test
        @DisplayName("wrap an NSError into an I/O failure naming the domain and code")
        void wrap() {
            var error = new NsError("AVFoundationErrorDomain", -11814, "Cannot Record");
            var failure = error.toException("cannot open the camera");
            assertEquals("cannot open the camera: Cannot Record", failure.getMessage());
            assertTrue(failure.getCause().getMessage().contains("AVFoundationErrorDomain error -11814"));
            assertTrue(error.is("AVFoundationErrorDomain", -11814));
            assertFalse(error.is("AVFoundationErrorDomain", -11800));
            assertEquals("cannot start", new NsError("d", 1, " ").toException("cannot start").getMessage());
        }
    }

    @Nested
    @DisplayName("platform threads")
    class Threads {
        private static <T> T direct(Supplier<T> work) {
            return work.get();
        }

        @Test
        @DisplayName("run work in place on a platform thread")
        void inPlace() {
            var caller = Thread.currentThread();
            assertSame(caller, PlatformThreads.call(Thread::currentThread, Threads::direct));
        }

        @Test
        @DisplayName("move work off a virtual thread and hand back its result")
        void offVirtual() throws InterruptedException {
            var ran = new AtomicReference<Thread>();
            var virtual = Thread.ofVirtual().start(() -> ran.set(PlatformThreads.call(Thread::currentThread, Threads::direct)));
            virtual.join();
            assertFalse(ran.get().isVirtual());
            assertEquals("smalt-capture-macos", ran.get().getName());
        }

        @Test
        @DisplayName("rethrow what the moved work throws and keep the caller's interrupt")
        void failures() throws InterruptedException {
            var thrown = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var virtual = Thread.ofVirtual().start(() -> {
                try {
                    PlatformThreads.call(() -> {
                        throw new IllegalStateException("boom");
                    }, Threads::direct);
                } catch (Throwable failure) {
                    thrown.set(failure);
                }
                Thread.currentThread().interrupt();
                PlatformThreads.call(() -> 1, Threads::direct);
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            virtual.join();
            assertInstanceOf(IllegalStateException.class, thrown.get());
            assertTrue(interrupted.get());
        }
    }
}
