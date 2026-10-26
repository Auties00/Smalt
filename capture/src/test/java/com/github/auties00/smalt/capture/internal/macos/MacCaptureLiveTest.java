package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.VideoDevices;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureBackend;
import com.github.auties00.smalt.image.ChromaFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the macOS backend against the real frameworks. Skipped on other systems. Enumeration always
 * runs on macOS; opening devices (which may show the Camera and Screen Recording prompts) runs only
 * with {@code -Dsmalt.capture.live=true}, from a terminal that has or may ask for the
 * permissions.
 */
@DisplayName("macOS capture (live)")
class MacCaptureLiveTest {
    @BeforeAll
    static void requireMac() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "the macOS backend runs only on macOS");
    }

    private static void requireLive() {
        Skip.unless(Boolean.parseBoolean(Skip.setting("smalt.capture.live", "SMALT_CAPTURE_LIVE")), "opt-in:capture-live",
                "set -Dsmalt.capture.live=true (or SMALT_CAPTURE_LIVE=true) to open devices");
    }

    @Test
    @DisplayName("selects the macOS backend")
    void backend() {
        assertTrue(CaptureBackend.current() instanceof MacCaptureBackend);
    }

    @Test
    @DisplayName("lists screens with unique numeric ids, the primary one first")
    void screens() {
        var screens = VideoDevices.screens();
        var ids = new HashSet<String>();
        for (var screen : screens) {
            assertTrue(ids.add(screen.id()));
            Integer.parseUnsignedInt(screen.id());
        }
        if (!screens.isEmpty()) {
            assertTrue(screens.getFirst().primary());
            assertEquals(1, screens.stream().filter(CaptureDevice.Screen::primary).count());
        }
    }

    @Test
    @DisplayName("lists cameras with ids, names and valid modes")
    void cameras() {
        for (var camera : VideoDevices.cameras()) {
            assertFalse(camera.id().isEmpty());
            assertFalse(camera.name().isBlank());
            for (var mode : camera.modes()) {
                assertTrue(mode.width() > 0 && mode.height() > 0);
            }
        }
    }

    @Test
    @DisplayName("finds the default camera and the cameras by identifier as the list reports them")
    void cameraLookups() {
        var cameras = VideoDevices.cameras();
        assertEquals(cameras.stream().findFirst(), VideoDevices.defaultCamera());
        for (var camera : cameras) {
            assertEquals(Optional.of(camera), VideoDevices.camera(camera.id()));
        }
        assertEquals(Optional.empty(), VideoDevices.camera("no-such-camera"));
    }

    @Test
    @DisplayName("finds the default audio devices and the devices by identifier as the lists report them")
    void audioLookups() {
        var inputs = AudioDevices.inputs();
        assertEquals(inputs.stream().filter(AudioDevice::defaultDevice).findFirst(), AudioDevices.defaultInput());
        for (var input : inputs) {
            assertEquals(Optional.of(input), AudioDevices.input(input.id()));
        }
        var outputs = AudioDevices.outputs();
        assertEquals(outputs.stream().filter(AudioDevice::defaultDevice).findFirst(), AudioDevices.defaultOutput());
        for (var output : outputs) {
            assertEquals(Optional.of(output), AudioDevices.output(output.id()));
        }
        assertEquals(Optional.empty(), AudioDevices.input("no-such-device"));
    }

    @Test
    @DisplayName("captures the primary screen at the requested pace with monotonic timestamps")
    void captureScreen() {
        requireLive();
        var screen = VideoDevices.primaryScreen();
        Skip.unlessDevice(screen.isPresent(), "screen", "no screen");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try (var session = VideoDevices.openCapture(screen.get(), 10)) {
                assertFrames(session, 15);
            } catch (MediaException.Io permission) {
                Skip.unlessDevice(!permission.getMessage().contains("Screen Recording"), "screen-permission", permission.getMessage());
                throw permission;
            }
        });
    }

    @Test
    @DisplayName("captures the default camera and closes from another thread")
    void captureCamera() {
        requireLive();
        var camera = VideoDevices.defaultCamera();
        Skip.unlessDevice(camera.isPresent(), "camera", "no camera");
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            try (var session = VideoDevices.openCapture(camera.get())) {
                assertFrames(session, 10);
                var blocked = new CountDownLatch(1);
                var last = new AtomicReference<VideoCaptureSession.Frame>();
                var reader = Thread.ofVirtual().start(() -> {
                    try {
                        VideoCaptureSession.Frame frame;
                        blocked.countDown();
                        while ((frame = session.read()) != null) {
                            last.set(frame);
                        }
                        last.set(null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                blocked.await();
                Thread.sleep(300);
                session.close();
                session.close();
                reader.join();
                assertNull(last.get());
                assertNull(session.read());
            } catch (MediaException.Io denied) {
                Skip.unlessDevice(!denied.getMessage().contains("camera access"), "camera-permission", denied.getMessage());
                throw denied;
            }
        });
    }

    private static void assertFrames(VideoCaptureSession session, int count) throws InterruptedException {
        assertEquals(0, session.width() & 1);
        assertEquals(0, session.height() & 1);
        var previous = Long.MIN_VALUE;
        for (var i = 0; i < count; i++) {
            var frame = session.read();
            assertNotNull(frame);
            assertEquals(ChromaFormat.YUV420, frame.picture().chromaFormat());
            assertEquals(session.width(), frame.picture().width());
            assertEquals(session.height(), frame.picture().height());
            assertTrue(frame.timestampMicros() > previous);
            assertTrue(frame.timestampMicros() > -1_000_000 && frame.timestampMicros() < 60_000_000);
            previous = frame.timestampMicros();
        }
    }
}
