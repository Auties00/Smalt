package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs exclusive streams against the hog mode of a real device, the loopback pair named by
 * {@code smalt.test.audioLoopback} (CI provisions BlackHole, one device with both directions).
 */
@Timeout(120)
@DisplayName("CoreAudio hog mode (live)")
class CoreAudioHogLiveTest {
    private static final long PID = ProcessHandle.current().pid();

    private static final AudioStreamOptions EXCLUSIVE = AudioStreamOptions.builder().shareMode(AudioShareMode.EXCLUSIVE).build();

    /** Holds a device's hog mode from another process until its standard input closes; JDK only, run as a source file. */
    private static final String HOLDER = """
            import java.lang.foreign.*;
            public class Holder {
                public static void main(String[] args) throws Throwable {
                    var library = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreAudio.framework/Versions/A/CoreAudio", Arena.global());
                    var set = Linker.nativeLinker().downcallHandle(library.find("AudioObjectSetPropertyData").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    var arena = Arena.global();
                    var address = arena.allocate(ValueLayout.JAVA_INT, 3);
                    address.setAtIndex(ValueLayout.JAVA_INT, 0, 0x6F696E6B);
                    address.setAtIndex(ValueLayout.JAVA_INT, 1, 0x676C6F62);
                    address.setAtIndex(ValueLayout.JAVA_INT, 2, 0);
                    var value = arena.allocate(ValueLayout.JAVA_INT);
                    value.set(ValueLayout.JAVA_INT, 0, (int) ProcessHandle.current().pid());
                    var status = (int) set.invokeExact(Integer.parseInt(args[0]), address, 0, MemorySegment.NULL, 4, value);
                    System.out.println("held " + status);
                    System.out.flush();
                    System.in.read();
                }
            }
            """;

    private record Pair(AudioDevice.Output output, AudioDevice.Input input) {
    }

    @BeforeAll
    static void requireMac() {
        Skip.unless(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac"), "platform:macos",
                "the macOS backend runs only on macOS");
        Skip.unless(Boolean.parseBoolean(Skip.setting("smalt.capture.live", "SMALT_CAPTURE_LIVE")), "opt-in:capture-live",
                "set -Dsmalt.capture.live=true (or SMALT_CAPTURE_LIVE=true) to open devices");
    }

    private static Pair pair() {
        var parts = Skip.setting("smalt.test.audioLoopback", "SMALT_TEST_AUDIO_LOOPBACK").split("\\|", -1);
        var output = parts.length != 2 ? null : find(AudioDevices.outputs(), parts[0]);
        var input = parts.length != 2 ? null : find(AudioDevices.inputs(), parts[1]);
        Skip.unlessDevice(output != null && input != null, "audio-loopback", "no loopback pair; set smalt.test.audioLoopback");
        return new Pair(output, input);
    }

    private static <D extends AudioDevice> D find(List<D> devices, String part) {
        return devices.stream().filter(device -> device.name().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
    }

    private static int owner(AudioDevice device) {
        return CoreAudio.intProperty(CoreAudioDevices.resolve(device.id()), CoreAudio.HOG_MODE, CoreAudio.SCOPE_GLOBAL, Integer.MIN_VALUE);
    }

    private static int nominalRate(AudioDevice device) {
        return (int) Math.round(CoreAudio.doubleProperty(CoreAudioDevices.resolve(device.id()), CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 0));
    }

    private static double goertzelRatio(float[] mono, int rate, double frequency) {
        var coefficient = 2 * Math.cos(2 * Math.PI * frequency / rate);
        double s1 = 0;
        double s2 = 0;
        double energy = 0;
        for (var sample : mono) {
            var s0 = sample + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
            energy += sample * sample;
        }
        var power = s1 * s1 + s2 * s2 - coefficient * s1 * s2;
        return energy == 0 ? 0 : 2 * power / (mono.length * energy);
    }

    @Test
    @DisplayName("an exclusive capture takes the device and frees it on close")
    void capture() throws InterruptedException {
        var device = pair().input();
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
        try (var session = AudioDevices.openCapture(device, EXCLUSIVE)) {
            assertEquals(PID, owner(device));
            var frames = new float[session.periodFrames() * session.channels()];
            assertEquals(session.periodFrames(), session.read(frames, 0, session.periodFrames()));
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }

    @Test
    @DisplayName("an exclusive playback takes the device and frees it on close")
    void playback() {
        var device = pair().output();
        try (var session = AudioDevices.openPlayback(device, EXCLUSIVE)) {
            assertEquals(PID, owner(device));
            assertEquals(nominalRate(device), session.sampleRate());
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }

    @Test
    @DisplayName("exclusive streams of both directions on one device carry a tone through the loopback")
    void duplexTone() throws InterruptedException {
        var pair = pair();
        var rate = nominalRate(pair.output());
        var capture = EXCLUSIVE.toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
        try (var player = AudioDevices.openPlayback(pair.output(), EXCLUSIVE);
             var recorder = AudioDevices.openCapture(pair.input(), capture)) {
            assertEquals(PID, owner(pair.output()));
            assertEquals(rate, recorder.sampleRate());
            var channels = player.channels();
            var frames = player.periodFrames();
            var writer = Thread.ofPlatform().daemon().start(() -> {
                var samples = new float[frames * channels];
                var position = 0L;
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        for (var i = 0; i < frames; i++) {
                            var value = (float) (0.5 * Math.sin(2 * Math.PI * 1_000 * (position + i) / rate));
                            for (var c = 0; c < channels; c++) {
                                samples[i * channels + c] = value;
                            }
                        }
                        if (player.write(samples, 0, frames) < frames) {
                            return;
                        }
                        position += frames;
                    }
                } catch (InterruptedException exception) {
                    // stopped
                }
            });
            try {
                var settle = new float[rate / 2 * recorder.channels()];
                recorder.read(settle, 0, rate / 2);
                var captured = new float[rate / 2 * recorder.channels()];
                assertEquals(rate / 2, recorder.read(captured, 0, rate / 2));
                var mono = new float[rate / 2];
                for (var i = 0; i < mono.length; i++) {
                    mono[i] = captured[i * recorder.channels()];
                }
                var ratio = goertzelRatio(mono, rate, 1_000);
                System.out.println("exclusive duplex tone: ratio " + ratio);
                assertTrue(ratio > 0.9, "1 kHz power ratio " + ratio);
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(pair.output()));
    }

    @Test
    @DisplayName("a second exclusive stream of one direction is refused and does not free the device")
    void second() {
        var device = pair().input();
        try (var ignored = AudioDevices.openCapture(device, EXCLUSIVE)) {
            assertThrows(MediaException.Io.class, () -> AudioDevices.openCapture(device, EXCLUSIVE));
            assertEquals(PID, owner(device));
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }

    @Test
    @DisplayName("runs the device at the asked rate while exclusive and restores it on close")
    void rate() throws InterruptedException {
        var device = pair().input();
        var previous = nominalRate(device);
        var wanted = device.formats().stream().mapToInt(format -> format.sampleRate()).filter(rate -> rate != previous).findFirst();
        Skip.unlessDevice(wanted.isPresent(), "audio-loopback", "the device offers one rate");
        var options = EXCLUSIVE.toBuilder().sampleRate(wanted.orElseThrow()).build();
        try (var session = AudioDevices.openCapture(device, options)) {
            assertEquals(wanted.orElseThrow(), nominalRate(device));
            assertEquals(wanted.orElseThrow(), session.sampleRate());
            var frames = new float[session.periodFrames() * session.channels()];
            assertEquals(session.periodFrames(), session.read(frames, 0, session.periodFrames()));
        }
        assertEquals(previous, nominalRate(device));
    }

    private static int otherRate(AudioDevice device) {
        var previous = nominalRate(device);
        var wanted = device.formats().stream().mapToInt(format -> format.sampleRate()).filter(rate -> rate != previous).findFirst();
        Skip.unlessDevice(wanted.isPresent(), "audio-loopback", "the device offers one rate");
        return wanted.orElseThrow();
    }

    @Test
    @DisplayName("an exclusive playback runs the device at the asked rate and restores it on close")
    void playbackRate() {
        var device = pair().output();
        var previous = nominalRate(device);
        var wanted = otherRate(device);
        try (var session = AudioDevices.openPlayback(device, EXCLUSIVE.toBuilder().sampleRate(wanted).build())) {
            assertEquals(wanted, nominalRate(device));
            assertEquals(wanted, session.sampleRate());
        }
        assertEquals(previous, nominalRate(device));
    }

    @Test
    @DisplayName("the second direction of a device must run at the first one's rate, and is refused without disturbing it")
    void secondDirectionRate() {
        var pair = pair();
        var previous = nominalRate(pair.input());
        var wanted = otherRate(pair.input());
        try (var ignored = AudioDevices.openCapture(pair.input(), EXCLUSIVE.toBuilder().sampleRate(wanted).build())) {
            var other = EXCLUSIVE.toBuilder().sampleRate(previous).build();
            assertThrows(MediaException.Unsupported.class, () -> AudioDevices.openPlayback(pair.output(), other));
            assertEquals(PID, owner(pair.input()));
            assertEquals(wanted, nominalRate(pair.input()));
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(pair.input()));
        assertEquals(previous, nominalRate(pair.input()));
    }

    @Test
    @DisplayName("a shared capture resumes once an exclusive stream of another rate has closed")
    void sharedAcrossRate() throws InterruptedException {
        var device = pair().input();
        var wanted = otherRate(device);
        try (var shared = AudioDevices.openCapture(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build())) {
            var frames = new float[shared.periodFrames() * shared.channels()];
            assertEquals(shared.periodFrames(), shared.read(frames, 0, shared.periodFrames()));
            try (var ignored = AudioDevices.openCapture(device, EXCLUSIVE.toBuilder().sampleRate(wanted).build())) {
                Thread.sleep(300);
            }
            var more = shared.available() + shared.sampleRate() / 4;
            var longer = new float[more * shared.channels()];
            assertEquals(more, shared.read(longer, 0, more));
        }
    }

    @Test
    @DisplayName("refuses a format the device lacks and leaves it free")
    void format() {
        var device = pair().input();
        var channels = device.formats().getFirst().channels();
        var rate = EXCLUSIVE.toBuilder().sampleRate(12_345).build();
        assertThrows(MediaException.Unsupported.class, () -> AudioDevices.openCapture(device, rate));
        var count = EXCLUSIVE.toBuilder().channels(channels + 1).build();
        assertThrows(MediaException.Unsupported.class, () -> AudioDevices.openCapture(device, count));
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }

    @Test
    @DisplayName("a shared stream of this process coexists with its own exclusive one")
    void sharedBeside() throws InterruptedException {
        var device = pair().input();
        try (var exclusive = AudioDevices.openCapture(device, EXCLUSIVE);
             var shared = AudioDevices.openCapture(device, AudioStreamOptions.defaults())) {
            var frames = new float[shared.periodFrames() * shared.channels()];
            assertEquals(shared.periodFrames(), shared.read(frames, 0, shared.periodFrames()));
            assertEquals(PID, owner(device));
            assertEquals(exclusive.channels(), shared.channels());
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }

    @Test
    @DisplayName("a device another process owns is reported as in use, and taken once that process exits")
    void otherProcess(@TempDir Path directory) throws IOException, InterruptedException {
        var device = pair().input();
        var source = directory.resolve("Holder.java");
        Files.writeString(source, HOLDER);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var holder = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", source.toString(),
                Integer.toString(CoreAudioDevices.resolve(device.id())))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var reader = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertEquals("held 0", reader.readLine());
            assertEquals(holder.pid(), owner(device));
            var failure = assertThrows(MediaException.Io.class, () -> AudioDevices.openCapture(device, EXCLUSIVE));
            assertTrue(failure.getMessage().contains("process " + holder.pid()), failure.getMessage());
            assertEquals(holder.pid(), owner(device), "the refused open does not touch the owner");
            var shared = assertThrows(MediaException.Io.class, () -> AudioDevices.openCapture(device));
            assertTrue(shared.getMessage().contains("process " + holder.pid()), shared.getMessage());
            assertTrue(shared.getMessage().contains("uses the device exclusively"), shared.getMessage());
            var playback = assertThrows(MediaException.Io.class, () -> AudioDevices.openPlayback(pair().output()));
            assertTrue(playback.getMessage().contains("uses the device exclusively"), playback.getMessage());
            assertEquals(holder.pid(), owner(device));
        } finally {
            holder.getOutputStream().close();
            holder.waitFor();
        }
        for (var i = 0; i < 300 && owner(device) != CoreAudioHog.NO_OWNER; i++) {
            Thread.sleep(10);
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device), "the operating system frees the device of an exited process");
        try (var ignored = AudioDevices.openCapture(device, EXCLUSIVE)) {
            assertEquals(PID, owner(device));
        }
        assertEquals(CoreAudioHog.NO_OWNER, owner(device));
    }
}
