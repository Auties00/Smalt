package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioDeviceListener;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures what the test itself plays through a process tap on a real device: the output half of
 * {@code smalt.test.audioLoopback} (CI provisions BlackHole). A system without process taps skips, as
 * does a process without the System Audio Recording permission; CI grants it and lists
 * {@code audio-tap} in {@code smalt.capture.requireDevices}, so there both fail. The refusal test needs
 * {@code smalt.test.tapPermissionScript}, a script that writes a decision into the permission database.
 */
@Timeout(120)
@DisplayName("CoreAudio process tap (live)")
class CoreAudioTapLiveTest {
    @BeforeAll
    static void requireMac() {
        Skip.unless(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac"), "platform:macos",
                "the macOS backend runs only on macOS");
        Skip.unless(Boolean.parseBoolean(Skip.setting("smalt.capture.live", "SMALT_CAPTURE_LIVE")), "opt-in:capture-live",
                "set -Dsmalt.capture.live=true (or SMALT_CAPTURE_LIVE=true) to open devices");
        Skip.unlessDevice(CoreAudio.processTapsAvailable(), "audio-tap", "process taps need macOS 14.2");
        Skip.unlessDevice(CoreAudio.tapPermission() == 0, "audio-tap", "the process lacks the System Audio Recording permission");
    }

    private static AudioDevice.Output output() {
        var part = Skip.setting("smalt.test.audioLoopback", "SMALT_TEST_AUDIO_LOOPBACK").split("\\|", -1)[0];
        Optional<AudioDevice.Output> output = part.isEmpty() ? Optional.empty() : AudioDevices.outputs().stream()
                .filter(device -> device.name().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT)))
                .findFirst();
        Skip.unlessDevice(output.isPresent(), "audio-loopback", "no output named " + part + "; set smalt.test.audioLoopback");
        return output.orElseThrow();
    }

    /**
     * Sines of prime frequencies in hertz: the period is exactly one second, the range the correlation searches
     * however long the signal has played, and within it only one lag correlates.
     */
    private static double signal(double frame, int rate) {
        var t = frame / rate;
        return 0.2 * Math.sin(2 * Math.PI * 317 * t) + 0.15 * Math.sin(2 * Math.PI * 733 * t)
               + 0.1 * Math.sin(2 * Math.PI * 1571 * t) + 0.05 * Math.sin(2 * Math.PI * 3203 * t);
    }

    /** Keeps a device running with silence. */
    private static Thread playSilence(AudioPlaybackSession player) {
        return Thread.ofPlatform().daemon().start(() -> {
            var samples = new float[player.periodFrames() * player.channels()];
            try {
                while (!Thread.currentThread().isInterrupted() && player.write(samples, 0, player.periodFrames()) == player.periodFrames()) {
                    Thread.onSpinWait();
                }
            } catch (InterruptedException exception) {
                // stopped
            }
        });
    }

    private static Thread play(AudioPlaybackSession player) {
        return play(player, false);
    }

    /** Plays the signal on every channel, inverted on the second one when asked, so the channels can be told apart. */
    private static Thread play(AudioPlaybackSession player, boolean invertSecond) {
        return Thread.ofPlatform().daemon().start(() -> {
            var position = 0L;
            var frames = player.periodFrames();
            var channels = player.channels();
            var samples = new float[frames * channels];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    for (var i = 0; i < frames; i++) {
                        var value = (float) signal(position + i, player.sampleRate());
                        for (var c = 0; c < channels; c++) {
                            samples[i * channels + c] = invertSecond && c == 1 ? -value : value;
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
    }

    /**
     * Returns the largest normalized cross-correlation of a window with the signal over one second of lags, the best
     * whole-sample lag refined to a sixteenth of a sample: the delay through a tap is not a whole number of samples at
     * a converted rate, and half a sample at 16 kHz alone costs the 3.2 kHz sine 0.63 radians, a correlation of 0.983.
     */
    private static double bestCorrelation(float[] captured, int rate) {
        var length = captured.length;
        var reference = new double[length + rate];
        for (var i = 0; i < reference.length; i++) {
            reference[i] = signal(i, rate);
        }
        var capturedEnergy = 0.0;
        for (var sample : captured) {
            capturedEnergy += sample * sample;
        }
        if (capturedEnergy == 0) {
            return 0;
        }
        var best = 0.0;
        var bestLag = 0;
        var referenceEnergy = 0.0;
        for (var i = 0; i < length; i++) {
            referenceEnergy += reference[i] * reference[i];
        }
        for (var lag = 0; lag < rate; lag++) {
            var product = 0.0;
            for (var i = 0; i < length; i++) {
                product += captured[i] * reference[lag + i];
            }
            var correlation = product / Math.sqrt(capturedEnergy * referenceEnergy);
            if (correlation > best) {
                best = correlation;
                bestLag = lag;
            }
            referenceEnergy += reference[lag + length] * reference[lag + length] - reference[lag] * reference[lag];
        }
        for (var step = -16; step <= 16; step++) {
            var lag = bestLag + step / 16.0;
            var product = 0.0;
            var energy = 0.0;
            for (var i = 0; i < length; i++) {
                var value = signal(lag + i, rate);
                product += captured[i] * value;
                energy += value * value;
            }
            best = Math.max(best, product / Math.sqrt(capturedEnergy * energy));
        }
        return best;
    }

    private static float[] mono(float[] interleaved, int channels) {
        var mono = new float[interleaved.length / channels];
        for (var i = 0; i < mono.length; i++) {
            mono[i] = interleaved[i * channels];
        }
        return mono;
    }

    private static double rms(float[] samples) {
        var sum = 0.0;
        for (var sample : samples) {
            sum += sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    /**
     * Reads a settling second and then six windows, correlates them only then, so the correlation of a cold JVM takes
     * no time from the threads that play, and checks the tap's reports. A scheduling hiccup of the runner may break a
     * window; a broken tap fails them all.
     */
    private static void assertCaptures(AudioCaptureSession tap, String label) throws InterruptedException {
        var window = tap.sampleRate() / 4;
        var captured = new float[window * tap.channels()];
        var settle = new float[tap.sampleRate() * tap.channels()];
        assertEquals(tap.sampleRate(), tap.read(settle, 0, tap.sampleRate()));
        var windows = new float[6][captured.length];
        for (var samples : windows) {
            assertEquals(window, tap.read(samples, 0, window));
        }
        var best = 0.0;
        var loudest = 0.0;
        for (var samples : windows) {
            var mono = mono(samples, tap.channels());
            var correlation = bestCorrelation(mono, tap.sampleRate());
            if (correlation > best) {
                best = correlation;
                loudest = rms(mono);
            }
        }
        System.out.println(label + " of " + tap.device().name() + ": " + tap.sampleRate() + " Hz, " + tap.channels()
                           + " channels, correlation " + best + ", rms " + loudest + ", latency "
                           + tap.latencyMicros() + " us, delay " + tap.delayMicros() + " us, overruns " + tap.overruns());
        assertTrue(best >= 0.999, "correlation " + best);
        assertTrue(loudest > 0.1 && loudest < 0.4, "rms " + loudest);
        assertTrue(tap.timestampMicros() > 0);
        assertTrue(tap.delayMicros() >= 0 && tap.delayMicros() < 10_000_000, "delay " + tap.delayMicros());
        assertAdvances(tap, captured, window);
    }

    /** The stamps come from the callback's host time, so they advance with the frames read unless frames were dropped. */
    private static void assertAdvances(AudioCaptureSession tap, float[] captured, int window) throws InterruptedException {
        var overruns = tap.overruns();
        assertEquals(window, tap.read(captured, 0, window));
        var first = tap.timestampMicros();
        assertEquals(window, tap.read(captured, 0, window));
        var advance = tap.timestampMicros() - first;
        var expected = window * 1_000_000L / tap.sampleRate();
        System.out.println("timestamps advance " + advance + " us over " + expected + " us of frames");
        if (tap.overruns() == overruns) {
            assertTrue(Math.abs(advance - expected) < 50_000, "advance " + advance + " over " + expected);
        }
    }

    private static void setPermission(String script, int decision) throws IOException, InterruptedException {
        var process = new ProcessBuilder("bash", script, Integer.toString(decision)).inheritIO().start();
        assertEquals(0, process.waitFor(), "the permission script failed");
    }

    @Test
    @DisplayName("captures the played signal with correlation of at least 0.999, each channel its own")
    void correlation() throws InterruptedException {
        var device = output();
        var playOptions = AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build();
        var tapOptions = AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(4)).build();
        try (var player = AudioDevices.openPlayback(device, playOptions);
             var tap = AudioDevices.openLoopback(device, tapOptions)) {
            assertSame(device, tap.device());
            assertEquals(player.sampleRate(), tap.sampleRate(), "the tap runs at the device's rate");
            assertEquals(player.channels(), tap.channels(), "the tap has the device's channels");
            var writer = play(player, true);
            try {
                assertCaptures(tap, "tap");
                var frames = tap.sampleRate() / 4;
                var captured = new float[frames * tap.channels()];
                assertEquals(frames, tap.read(captured, 0, frames));
                var sum = 0.0;
                var left = 0.0;
                for (var i = 0; i < frames; i++) {
                    var l = captured[i * tap.channels()];
                    sum += Math.abs(l + captured[i * tap.channels() + 1]);
                    left += Math.abs(l);
                }
                assertTrue(sum < 0.01 * left, "the second channel is the first one inverted: " + sum + " over " + left);
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
    }

    @Test
    @DisplayName("delivers the signal at 16 kHz mono through the rate and channel conversion")
    void converted() throws InterruptedException {
        var device = output();
        var tapOptions = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(4)).build();
        try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build());
             var tap = AudioDevices.openLoopback(device, tapOptions)) {
            assertEquals(16_000, tap.sampleRate());
            assertEquals(1, tap.channels());
            var writer = play(player);
            try {
                var settle = new float[16_000];
                assertEquals(16_000, tap.read(settle, 0, 16_000));
                var captured = new float[4_000];
                var windows = new float[6][4_000];
                for (var samples : windows) {
                    assertEquals(4_000, tap.read(samples, 0, 4_000));
                }
                var best = 0.0;
                var loudest = 0.0;
                for (var samples : windows) {
                    var correlation = bestCorrelation(samples, 16_000);
                    if (correlation > best) {
                        best = correlation;
                        loudest = rms(samples);
                    }
                }
                System.out.println("converted tap of " + device.name() + ": correlation " + best + ", rms " + loudest);
                assertTrue(best >= 0.999, "correlation " + best);
                assertTrue(loudest > 0.1 && loudest < 0.4, "rms " + loudest);
                assertAdvances(tap, captured, 4_000);
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
    }

    @Test
    @DisplayName("opened on an idle device, captures what starts playing afterwards")
    void startsIdle() throws InterruptedException {
        var device = output();
        try (var tap = AudioDevices.openLoopback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(4)).build())) {
            Thread.sleep(500);
            try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build())) {
                var writer = play(player);
                try {
                    assertCaptures(tap, "tap opened idle");
                } finally {
                    writer.interrupt();
                    player.close();
                    writer.join(2_000);
                }
            }
        }
    }

    @Test
    @DisplayName("delivers silence while a client keeps the device running without playing anything")
    void silence() throws InterruptedException {
        var device = output();
        try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build());
             var tap = AudioDevices.openLoopback(device, AudioStreamOptions.forVoice())) {
            var writer = playSilence(player);
            try {
                var captured = new float[8_000];
                assertEquals(8_000, tap.read(captured, 0, 8_000));
                var peak = 0f;
                for (var sample : captured) {
                    peak = Math.max(peak, Math.abs(sample));
                }
                assertTrue(peak < 0.01f, "peak " + peak);
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
    }

    @Test
    @DisplayName("is reported as unsupported, naming the permission, once the user has refused it")
    void refused() throws IOException, InterruptedException {
        var script = Skip.setting("smalt.test.tapPermissionScript", "SMALT_TEST_TAP_PERMISSION_SCRIPT");
        Skip.unless(!script.isEmpty(), "opt-in:capture-tap-refused",
                "set smalt.test.tapPermissionScript to a script that writes the permission decision");
        var device = output();
        setPermission(script, 0);
        try {
            assertEquals(CoreAudio.TAP_PERMISSION_DENIED, CoreAudio.tapPermission());
            var failure = assertThrows(MediaException.Unsupported.class, () -> AudioDevices.openLoopback(device));
            assertTrue(failure.getMessage().contains("System Audio Recording"), failure.getMessage());
        } finally {
            setPermission(script, 2);
        }
        assertEquals(0, CoreAudio.tapPermission());
    }

    @Test
    @DisplayName("keeps the session's rate and signal across a change of the device's rate")
    void rateChange() throws InterruptedException {
        var device = output();
        var object = CoreAudioDevices.resolve(device.id());
        var previous = (int) Math.round(CoreAudio.doubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 0));
        var other = previous == 44_100 ? 48_000 : 44_100;
        try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build());
             var tap = AudioDevices.openLoopback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(4)).build())) {
            assertEquals(previous, tap.sampleRate());
            var writer = play(player);
            try {
                assertEquals(0, CoreAudio.setDoubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, other));
                Thread.sleep(1_000);
                assertEquals(previous, tap.sampleRate());
                assertCaptures(tap, "tap across a rate change");
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
                CoreAudio.setDoubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, previous);
                Thread.sleep(500);
            }
        }
    }

    @Test
    @DisplayName("hears what this process plays to the device exclusively")
    void beside() throws InterruptedException {
        var device = output();
        var exclusive = AudioStreamOptions.builder().shareMode(AudioShareMode.EXCLUSIVE).bufferDuration(Duration.ofSeconds(1)).build();
        try (var player = AudioDevices.openPlayback(device, exclusive);
             var tap = AudioDevices.openLoopback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(4)).build())) {
            var writer = play(player);
            try {
                assertCaptures(tap, "tap beside an exclusive playback");
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
    }

    @Test
    @DisplayName("two taps of one device capture side by side")
    void twoTaps() throws InterruptedException {
        var device = output();
        var options = AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(4)).build();
        try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build());
             var first = AudioDevices.openLoopback(device, options);
             var second = AudioDevices.openLoopback(device, options)) {
            var writer = play(player);
            try {
                assertCaptures(first, "first tap");
                assertCaptures(second, "second tap");
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
    }

    @Test
    @DisplayName("refuses the exclusive mode")
    void exclusive() {
        var options = AudioStreamOptions.builder().shareMode(AudioShareMode.EXCLUSIVE).build();
        assertThrows(MediaException.Unsupported.class, () -> AudioDevices.openLoopback(output(), options));
    }

    @Test
    @DisplayName("fails on a device that does not exist")
    void missingDevice() {
        var ghost = new AudioDevice.Output("no-such-device-uid", "ghost", false,
                List.of(new AudioDeviceFormat(48_000, 2, AudioSampleFormat.FLOAT32)));
        assertThrows(MediaException.Io.class, () -> AudioDevices.openLoopback(ghost));
    }

    @Test
    @DisplayName("leaves no device behind, reopens repeatedly, and ends a blocked read on close")
    void lifecycle() throws InterruptedException {
        var device = output();
        var before = AudioDevices.inputs().size() + AudioDevices.outputs().size();
        var events = new CopyOnWriteArrayList<AudioDeviceEvent>();
        AudioDeviceListener listener = events::add;
        AudioDevices.addListener(listener);
        try (var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build())) {
            var writer = playSilence(player);
            try {
                for (var i = 0; i < 5; i++) {
                    var tap = AudioDevices.openLoopback(device, AudioStreamOptions.forVoice());
                    var frame = new float[160];
                    assertEquals(160, tap.read(frame, 0, 160));
                    assertFalse(AudioDevices.inputs().stream().anyMatch(input -> input.id().startsWith(CoreAudioTap.NAME_PREFIX)),
                            "the aggregate is not listed");
                    assertFalse(AudioDevices.outputs().stream().anyMatch(other -> other.id().startsWith(CoreAudioTap.NAME_PREFIX)),
                            "the aggregate is not listed");
                    tap.close();
                    tap.close();
                    assertNull(tap.read());
                }
            } finally {
                writer.interrupt();
                player.close();
                writer.join(2_000);
            }
        }
        assertEquals(before, AudioDevices.inputs().size() + AudioDevices.outputs().size());
        Thread.sleep(500);
        AudioDevices.removeListener(listener);
        assertFalse(events.stream().anyMatch(event -> event.toString().contains(CoreAudioTap.NAME_PREFIX)), "no listener hears of the aggregate: " + events);
        var tap = AudioDevices.openLoopback(device, AudioStreamOptions.forVoice());
        var reader = Thread.ofPlatform().daemon().start(() -> {
            var frame = new float[160];
            try {
                while (tap.read(frame, 0, 160) == 160) {
                    Thread.onSpinWait();
                }
            } catch (InterruptedException exception) {
                // stopped
            }
        });
        Thread.sleep(200);
        tap.close();
        reader.join(5_000);
        assertFalse(reader.isAlive(), "close ends the read");
    }
}
