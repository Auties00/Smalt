package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.VideoDevices;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures the monitors and cameras of the machine running the tests. Screen tests assume at
 * least one monitor; camera tests skip when there is no camera, when it is busy, or when it
 * delivers no frame within a few seconds (a virtual camera without its source). Measured costs
 * are printed to the test output.
 */
@EnabledOnOs(value = OS.WINDOWS, disabledReason = "[platform:windows] the Windows backend runs only on Windows")
@DisplayName("Windows capture on this machine")
class WindowsCaptureTest {
    /** Reads one frame on another thread, giving up after a timeout; returns null on timeout. */
    private static VideoCaptureSession.Frame readWithin(VideoCaptureSession session, Duration timeout) throws InterruptedException {
        var result = new AtomicReference<VideoCaptureSession.Frame>();
        var failure = new AtomicReference<Throwable>();
        var reader = Thread.ofPlatform().daemon().start(() -> {
            try {
                result.set(session.read());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        reader.join(timeout);
        if (reader.isAlive()) {
            return null;
        }
        if (failure.get() instanceof RuntimeException runtime) {
            throw runtime;
        }
        assertNull(failure.get());
        return result.get();
    }

    private static boolean hasContent(YuvPicture picture) {
        var min = 255;
        var max = 0;
        var y = picture.y();
        for (var i = 0; i < y.length; i += 7) {
            min = Math.min(min, y[i] & 0xFF);
            max = Math.max(max, y[i] & 0xFF);
        }
        return max - min > 8;
    }

    private static long processCpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
    }

    @Test
    @DisplayName("leaves no capture thread running once every session is closed")
    void noLeakedThreads() throws InterruptedException {
        var screens = VideoDevices.screens();
        Skip.unlessDevice(!screens.isEmpty(), "screen", "no monitor");
        try (var session = VideoDevices.openCapture(screens.getFirst(), 30)) {
            assertNotNull(readWithin(session, Duration.ofSeconds(5)));
        }
        var cameras = VideoDevices.cameras();
        if (!cameras.isEmpty()) {
            try (var session = VideoDevices.openCapture(cameras.getFirst())) {
                readWithin(session, Duration.ofSeconds(5));
            } catch (MediaException.Io exception) {
                // a busy camera is not what this test checks
            }
        }
        var leaked = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("smalt-camera-") || thread.getName().startsWith("smalt-screen-"))
                .map(Thread::getName)
                .toList();
        assertEquals(List.of(), leaked);
    }

    @Nested
    @DisplayName("screens")
    class Screens {
        private CaptureDevice.Screen primary() {
            var screens = VideoDevices.screens();
            Skip.unlessDevice(!screens.isEmpty(), "screen", "no monitor");
            return screens.getFirst();
        }

        @Test
        @DisplayName("lists the monitors with unique ids, the primary one first at the origin")
        void enumerate() {
            var screens = VideoDevices.screens();
            Skip.unlessDevice(!screens.isEmpty(), "screen", "no monitor");
            var ids = new HashSet<String>();
            for (var screen : screens) {
                assertTrue(ids.add(screen.id()), "duplicate id " + screen.id());
                assertFalse(screen.name().isBlank());
                assertTrue(screen.id().toUpperCase(Locale.ROOT).startsWith("\\\\.\\DISPLAY"), screen.id());
            }
            var primary = screens.getFirst();
            assertTrue(primary.primary());
            assertEquals(0, primary.x());
            assertEquals(0, primary.y());
            assertEquals(1, screens.stream().filter(CaptureDevice.Screen::primary).count());
            System.out.println("screens: " + screens);
        }

        @ParameterizedTest(name = "gdi={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("captures even-sized, non-black frames with monotonic timestamps starting near zero")
        void capture(boolean gdi) throws InterruptedException {
            var screen = primary();
            try (var session = ScreenSession.open(screen, new Rational(30, 1), gdi)) {
                assertEquals(!gdi, ((ScreenSession) session).duplicating());
                assertEquals(screen.width() & ~1, session.width());
                assertEquals(screen.height() & ~1, session.height());
                var previous = -1L;
                var content = false;
                for (var i = 0; i < 5; i++) {
                    var frame = readWithin(session, Duration.ofSeconds(5));
                    assertNotNull(frame, "no frame within five seconds");
                    var picture = frame.picture();
                    assertEquals(session.width(), picture.width());
                    assertEquals(session.height(), picture.height());
                    assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
                    assertEquals(YuvCoefficients.BT709_LIMITED.color(), picture.color());
                    if (i == 0) {
                        assertTrue(frame.timestampMicros() < 1_000_000, "first timestamp " + frame.timestampMicros());
                    }
                    assertTrue(frame.timestampMicros() > previous, "timestamps must increase");
                    previous = frame.timestampMicros();
                    content |= hasContent(picture);
                }
                assertTrue(content, "every frame is uniformly dark");
            }
        }

        @Test
        @DisplayName("delivers frames at the requested pace when the reader keeps up")
        void pace() throws InterruptedException {
            try (var session = VideoDevices.openCapture(primary(), 20)) {
                readWithin(session, Duration.ofSeconds(5));
                var first = -1L;
                var last = -1L;
                var frames = 0;
                var start = System.nanoTime();
                while (System.nanoTime() - start < 1_000_000_000L) {
                    var frame = session.read();
                    last = frame.timestampMicros();
                    if (first < 0) {
                        first = last;
                    }
                    frames++;
                }
                var interval = (last - first) / (double) (frames - 1);
                assertTrue(frames >= 14 && frames <= 23, "frames in one second: " + frames);
                assertTrue(interval > 40_000 && interval < 60_000, "mean interval " + interval);
            }
        }

        @Test
        @DisplayName("delivers frames at a fractional rate's exact period")
        void fractionalPace() throws InterruptedException {
            try (var session = VideoDevices.openCapture(primary(), new Rational(50, 3))) {
                readWithin(session, Duration.ofSeconds(5));
                var first = -1L;
                var last = -1L;
                var frames = 0;
                var start = System.nanoTime();
                while (System.nanoTime() - start < 1_000_000_000L) {
                    var frame = session.read();
                    last = frame.timestampMicros();
                    if (first < 0) {
                        first = last;
                    }
                    frames++;
                }
                var interval = (last - first) / (double) (frames - 1);
                assertTrue(frames >= 12 && frames <= 20, "frames in one second: " + frames);
                assertTrue(interval > 50_000 && interval < 70_000, "mean interval " + interval);
            }
        }

        @Test
        @DisplayName("finds a monitor by id, and nothing for an unknown id")
        void lookup() {
            var primary = primary();
            assertEquals(Optional.of(primary), VideoDevices.screen(primary.id()));
            assertEquals(Optional.empty(), VideoDevices.screen("\\\\.\\DISPLAY99"));
        }

        @Test
        @DisplayName("openCapture opens a monitor at an integer or a fractional rate")
        void openCapture() throws InterruptedException {
            var screen = primary();
            try (var session = VideoDevices.openCapture(screen, 30)) {
                assertEquals(screen, session.device());
                assertNotNull(readWithin(session, Duration.ofSeconds(5)));
            }
            try (var session = VideoDevices.openCapture(screen, new Rational(30_000, 1_001))) {
                assertNotNull(readWithin(session, Duration.ofSeconds(5)));
            }
        }

        @Test
        @DisplayName("close from another thread unblocks read, which returns null; closing twice is harmless")
        void closeWhileReading() throws InterruptedException {
            var session = VideoDevices.openCapture(primary(), 1);
            session.read();
            var result = new AtomicReference<Object>("pending");
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    result.set(session.read());
                } catch (Throwable throwable) {
                    result.set(throwable);
                }
            });
            Thread.sleep(100);
            var start = System.nanoTime();
            session.close();
            reader.join(Duration.ofSeconds(2));
            assertFalse(reader.isAlive(), "read did not return after close");
            assertNull(result.get());
            assertTrue(System.nanoTime() - start < 1_000_000_000L, "close took too long");
            session.close();
            assertNull(session.read());
        }

        @Test
        @DisplayName("reopens the same monitor after closing it, and opens two sessions at once")
        void reopen() throws InterruptedException {
            var screen = primary();
            for (var round = 0; round < 3; round++) {
                try (var session = VideoDevices.openCapture(screen, 30)) {
                    assertNotNull(readWithin(session, Duration.ofSeconds(5)));
                }
            }
            try (var first = VideoDevices.openCapture(screen, 30); var second = VideoDevices.openCapture(screen, 30)) {
                assertNotNull(readWithin(first, Duration.ofSeconds(5)));
                assertNotNull(readWithin(second, Duration.ofSeconds(5)));
            }
        }

        @Test
        @DisplayName("fails to open a monitor that does not exist")
        void missing() {
            var ghost = new CaptureDevice.Screen("\\\\.\\DISPLAY99", "ghost", 0, 0, 640, 480, false);
            assertThrows(MediaException.Io.class, () -> VideoDevices.openCapture(ghost, 30));
        }

        @Test
        @DisplayName("measures the CPU cost per frame")
        void cost() throws InterruptedException {
            var screen = primary();
            for (var gdi : new boolean[]{false, true}) {
                try (var session = ScreenSession.open(screen, new Rational(30, 1), gdi)) {
                    for (var i = 0; i < 30; i++) {
                        session.read();
                    }
                    var cpu = processCpuNanos();
                    var frames = 90;
                    for (var i = 0; i < frames; i++) {
                        session.read();
                    }
                    // process CPU is accounted in 15.6 ms ticks on Windows, so the figure is +-0.2 ms
                    System.out.printf(Locale.ROOT, "screen %dx%d at 30 fps with %s, static desktop: %.2f ms of process CPU per frame%n",
                            session.width(), session.height(), gdi ? "GDI" : "desktop duplication",
                            (processCpuNanos() - cpu) / 1e6 / frames);
                }
            }
            var cost = new double[2];
            var worker = Thread.ofPlatform().start(() -> {
                Gdi.usePhysicalPixels();
                try (var grabber = new DuplicationGrabber(screen.id())) {
                    var out = YuvPicture.allocate(grabber.width() & ~1, grabber.height() & ~1, ChromaFormat.YUV420, false, YuvCoefficients.BT709_LIMITED.color());
                    var deadline = System.nanoTime() + 3_000_000_000L;
                    while (grabber.redraw(out) != ScreenGrabber.Result.CHANGED && System.nanoTime() < deadline) {
                        grabber.grab(out);
                        Thread.sleep(10);
                    }
                    if (grabber.redraw(out) != ScreenGrabber.Result.CHANGED) {
                        return;
                    }
                    for (var i = 0; i < 30; i++) {
                        grabber.redraw(out);
                    }
                    var cpu = processCpuNanos();
                    var wall = System.nanoTime();
                    for (var i = 0; i < 60; i++) {
                        grabber.redraw(out);
                    }
                    cost[0] = (processCpuNanos() - cpu) / 1e6 / 60;
                    cost[1] = (System.nanoTime() - wall) / 1e6 / 60;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            worker.join();
            if (cost[1] > 0) {
                System.out.printf(Locale.ROOT, "screen %dx%d, changing every frame: %.2f ms CPU, %.2f ms wall per frame (map + convert)%n",
                        screen.width(), screen.height(), cost[0], cost[1]);
            }
            try (var arena = Arena.ofConfined()) {
                var width = 1920;
                var height = 1080;
                var memory = arena.allocate(4L * width * height);
                var out = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, YuvCoefficients.BT709_LIMITED.color());
                var converter = new BgraConverter();
                for (var i = 0; i < 50; i++) {
                    converter.convert(memory, 0, 4L * width, width, height, 0, null, out);
                }
                var wall = System.nanoTime();
                for (var i = 0; i < 100; i++) {
                    converter.convert(memory, 0, 4L * width, width, height, 0, null, out);
                }
                System.out.printf(Locale.ROOT, "1920x1080 BGRX to I420 from native memory: %.2f ms per frame%n",
                        (System.nanoTime() - wall) / 1e6 / 100);
            }
        }
    }

    @Nested
    @DisplayName("cameras")
    class Cameras {
        private CaptureDevice.Camera camera() {
            var cameras = VideoDevices.cameras();
            Skip.unlessDevice(!cameras.isEmpty(), "camera", "no camera");
            return cameras.getFirst();
        }

        private VideoCaptureSession openOrSkip(CaptureDevice.Camera camera, CaptureMode mode) {
            try {
                return mode == null ? VideoDevices.openCapture(camera) : VideoDevices.openCapture(camera, mode);
            } catch (MediaException.Io exception) {
                Skip.unlessDevice(false, "camera", "the camera cannot be opened: " + exception.getMessage());
                throw exception;
            }
        }

        private VideoCaptureSession.Frame frameOrSkip(VideoCaptureSession session) throws InterruptedException {
            var frame = readWithin(session, Duration.ofSeconds(5));
            if (frame == null) {
                session.close();
            }
            Skip.unlessDevice(frame != null, "camera", "the camera delivered no frame within five seconds");
            return frame;
        }

        @Test
        @DisplayName("lists the cameras with unique ids and sorted modes")
        void enumerate() {
            var cameras = VideoDevices.cameras();
            var ids = new HashSet<String>();
            for (var camera : cameras) {
                assertTrue(ids.add(camera.id()));
                assertFalse(camera.name().isBlank());
                var modes = camera.modes();
                for (var i = 1; i < modes.size(); i++) {
                    var previous = (long) modes.get(i - 1).width() * modes.get(i - 1).height();
                    var current = (long) modes.get(i).width() * modes.get(i).height();
                    assertTrue(previous >= current, "modes must be largest first");
                }
                System.out.println("camera: " + camera.name() + " modes=" + modes.size());
            }
        }

        @Test
        @DisplayName("finds a camera by id, and nothing for an unknown id")
        void lookup() {
            for (var camera : VideoDevices.cameras()) {
                assertEquals(Optional.of(camera), VideoDevices.camera(camera.id()));
            }
            assertEquals(Optional.empty(), VideoDevices.camera("\\\\?\\no-such-camera"));
        }

        @Test
        @DisplayName("captures frames in the default mode with the session's size and increasing timestamps")
        void capture() throws InterruptedException {
            var camera = camera();
            try (var session = openOrSkip(camera, null)) {
                assertEquals(0, session.width() & 1);
                assertEquals(0, session.height() & 1);
                var first = frameOrSkip(session);
                var previous = first.timestampMicros();
                assertTrue(previous < 5_000_000);
                for (var i = 0; i < 3; i++) {
                    var frame = readWithin(session, Duration.ofSeconds(5));
                    assertNotNull(frame);
                    assertEquals(session.width(), frame.picture().width());
                    assertEquals(session.height(), frame.picture().height());
                    assertEquals(ChromaFormat.YUV420, frame.picture().chromaFormat());
                    assertTrue(frame.timestampMicros() > previous);
                    previous = frame.timestampMicros();
                }
            }
        }

        @Test
        @DisplayName("measures the CPU cost per camera frame")
        void cost() throws InterruptedException {
            var camera = camera();
            try (var session = openOrSkip(camera, null)) {
                frameOrSkip(session);
                for (var i = 0; i < 10; i++) {
                    session.read();
                }
                var cpu = processCpuNanos();
                var start = System.nanoTime();
                var frames = 40;
                for (var i = 0; i < frames; i++) {
                    session.read();
                }
                System.out.printf(Locale.ROOT, "camera %dx%d: %.1f fps, %.2f ms of process CPU per frame%n", session.width(),
                        session.height(), frames * 1e9 / (System.nanoTime() - start), (processCpuNanos() - cpu) / 1e6 / frames);
            }
        }

        @Test
        @DisplayName("captures in the smallest listed mode at exactly that size")
        void mode() throws InterruptedException {
            var camera = camera();
            Skip.unlessDevice(!camera.modes().isEmpty(), "camera", "the camera lists no mode");
            var mode = camera.modes().getLast();
            try (var session = openOrSkip(camera, mode)) {
                assertEquals(mode.width() & ~1, session.width());
                assertEquals(mode.height() & ~1, session.height());
                var frame = frameOrSkip(session);
                assertEquals(session.width(), frame.picture().width());
            }
        }

        @Test
        @DisplayName("rejects a mode the camera does not offer")
        void unsupportedMode() {
            var camera = camera();
            assertThrows(MediaException.Unsupported.class, () -> VideoDevices.openCapture(camera, CaptureMode.of(98, 54, 1000)));
        }

        @Test
        @DisplayName("fails to open a camera that does not exist")
        void missing() {
            var ghost = new CaptureDevice.Camera("\\\\?\\no#such#camera", "ghost", List.of());
            assertThrows(MediaException.Io.class, () -> VideoDevices.openCapture(ghost));
        }

        @Test
        @DisplayName("close from another thread unblocks read; closing twice and reopening work")
        void closeAndReopen() throws InterruptedException {
            var camera = camera();
            var session = openOrSkip(camera, null);
            frameOrSkip(session);
            var result = new AtomicReference<Object>("pending");
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    while (true) {
                        var frame = session.read();
                        if (frame == null) {
                            result.set(null);
                            return;
                        }
                    }
                } catch (Throwable throwable) {
                    result.set(throwable);
                }
            });
            Thread.sleep(200);
            session.close();
            reader.join(Duration.ofSeconds(5));
            assertFalse(reader.isAlive());
            assertNull(result.get());
            session.close();
            try (var again = openOrSkip(camera, null)) {
                assertNotNull(frameOrSkip(again));
            }
        }
    }
}
