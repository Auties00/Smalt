package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceListener;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lists and streams the audio endpoints of the machine running the tests through WASAPI. Tests
 * that need a microphone or an output skip when there is none. Tests that play a tone use only a
 * VB-Audio Virtual Cable ("CABLE Input" render endpoint, "CABLE Output" capture endpoint) and skip
 * without one, so nothing is ever heard; the default output only ever receives silence. Measured
 * latencies are printed to the test output.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
@DisplayName("WASAPI audio devices on this machine")
class WasapiAudioTest {
    private static Optional<AudioDevice.Output> cableInput() {
        return AudioDevices.outputs().stream().filter(device -> device.name().startsWith("CABLE Input")).findFirst();
    }

    private static Optional<AudioDevice.Input> cableOutput() {
        return AudioDevices.inputs().stream().filter(device -> device.name().startsWith("CABLE Output")).findFirst();
    }

    private static float[] tone(int frames, int rate, int channels, double frequency, double amplitude, long startFrame) {
        var samples = new float[frames * channels];
        for (var i = 0; i < frames; i++) {
            var value = (float) (amplitude * Math.sin(2 * Math.PI * frequency * (startFrame + i) / rate));
            for (var c = 0; c < channels; c++) {
                samples[i * channels + c] = value;
            }
        }
        return samples;
    }

    /** Returns the power of one frequency relative to the total power of a mono signal (Goertzel). */
    private static double toneRatio(float[] mono, int length, int rate, double frequency) {
        var coefficient = 2 * Math.cos(2 * Math.PI * frequency / rate);
        double s1 = 0;
        double s2 = 0;
        double energy = 0;
        for (var i = 0; i < length; i++) {
            var s0 = mono[i] + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
            energy += mono[i] * mono[i];
        }
        var power = s1 * s1 + s2 * s2 - coefficient * s1 * s2;
        return energy == 0 ? 0 : 2 * power / (length * energy);
    }

    private static List<String> audioThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("smalt-audio-"))
                .toList();
    }

    /** Plays a tone on a session from a background thread until stopped. */
    private static Thread playTone(AudioPlaybackSession session, double frequency, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().daemon().start(() -> {
            var position = 0L;
            var frames = session.periodFrames();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    var samples = tone(frames, session.sampleRate(), session.channels(), frequency, 0.5, position);
                    if (session.write(samples, 0, frames) < frames) {
                        return;
                    }
                    position += frames;
                }
            } catch (InterruptedException exception) {
                // stopped
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
    }

    @Nested
    @DisplayName("enumeration")
    class Enumeration {
        @Test
        @DisplayName("lists active endpoints with unique ids, the default first, with their mix format first")
        void list() {
            var start = System.nanoTime();
            var inputs = AudioDevices.inputs();
            var outputs = AudioDevices.outputs();
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            System.out.println("enumerated " + inputs.size() + " inputs and " + outputs.size() + " outputs in " + elapsed.toMillis() + " ms");
            inputs.forEach(device -> System.out.println("  input  " + device));
            outputs.forEach(device -> System.out.println("  output " + device));
            var ids = new HashSet<String>();
            for (var device : List.<AudioDevice>copyOf(concat(inputs, outputs))) {
                assertTrue(ids.add(device.id()), "duplicate id " + device.id());
                assertFalse(device.name().isBlank());
                assertTrue(device.id().startsWith("{0.0."), device.id());
                assertFalse(device.formats().isEmpty(), "no mix format for " + device.name());
            }
            if (!inputs.isEmpty() && inputs.stream().anyMatch(AudioDevice::defaultDevice)) {
                assertTrue(inputs.getFirst().defaultDevice());
                assertEquals(1, inputs.stream().filter(AudioDevice::defaultDevice).count());
            }
            if (!outputs.isEmpty() && outputs.stream().anyMatch(AudioDevice::defaultDevice)) {
                assertTrue(outputs.getFirst().defaultDevice());
                assertEquals(1, outputs.stream().filter(AudioDevice::defaultDevice).count());
            }
            inputs.forEach(device -> assertEquals(AudioDevice.Direction.INPUT, device.direction()));
            outputs.forEach(device -> assertEquals(AudioDevice.Direction.OUTPUT, device.direction()));
        }

        private static List<AudioDevice> concat(List<? extends AudioDevice> first, List<? extends AudioDevice> second) {
            var all = new ArrayList<AudioDevice>(first);
            all.addAll(second);
            return all;
        }

        @Test
        @DisplayName("finds a device again by its id, and nothing for an unknown id")
        void lookup() {
            var outputs = AudioDevices.outputs();
            assumeFalse(outputs.isEmpty(), "no output");
            var first = outputs.getFirst();
            assertEquals(first.id(), AudioDevices.output(first.id()).orElseThrow().id());
            assertTrue(AudioDevices.output("{0.0.0.00000000}.{00000000-0000-0000-0000-000000000000}").isEmpty());
            assertTrue(AudioDevices.input(first.id()).isEmpty(), "an output id must not resolve to an input");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("fails with MediaException.Io when the device does not exist")
        void missingDevice() {
            var missing = new AudioDevice.Input("{0.0.1.00000000}.{00000000-0000-0000-0000-000000000000}", "missing", false, List.of());
            var failure = assertThrows(MediaException.Io.class, () -> AudioDevices.openCapture(missing, AudioStreamOptions.forVoice()));
            System.out.println("missing device: " + failure.getMessage());
            var missingOutput = new AudioDevice.Output("{0.0.0.00000000}.{00000000-0000-0000-0000-000000000000}", "missing", false, List.of());
            assertThrows(MediaException.Io.class, () -> AudioDevices.openPlayback(missingOutput, AudioStreamOptions.forVoice()));
            assertEquals(List.of(), audioThreads());
        }

        @Test
        @DisplayName("opens and closes capture and playback 20 times without leaking a thread")
        void openCloseCycles() throws InterruptedException {
            var input = AudioDevices.defaultInput();
            var output = AudioDevices.defaultOutput();
            assumeTrue(input.isPresent() || output.isPresent(), "no audio device");
            var start = System.nanoTime();
            for (var i = 0; i < 20; i++) {
                if (input.isPresent()) {
                    try (var session = AudioDevices.openCapture(input.get(), AudioStreamOptions.forVoice())) {
                        if (i % 5 == 0) {
                            var frame = new short[session.periodFrames() * session.channels()];
                            assertEquals(session.periodFrames(), session.read(frame, 0, session.periodFrames()));
                        }
                    }
                }
                if (output.isPresent()) {
                    try (var session = AudioDevices.openPlayback(output.get(), AudioStreamOptions.forVoice())) {
                        session.write(new short[session.periodFrames()], 0, session.periodFrames());
                    }
                }
            }
            System.out.println("20 open/close cycles in " + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms");
            assertEquals(List.of(), audioThreads());
        }

        @Test
        @DisplayName("a close from another thread ends a blocked read, and later reads report the end")
        void closeUnblocksRead() throws InterruptedException {
            var cable = cableInput();
            assumeTrue(cable.isPresent(), "no VB-Audio Virtual Cable");
            var session = AudioDevices.openLoopback(cable.get(), AudioStreamOptions.forVoice());
            var result = new AtomicInteger(Integer.MIN_VALUE);
            var reader = Thread.ofPlatform().start(() -> {
                try {
                    // ten seconds: far more than arrives before the close
                    result.set(session.read(new float[160_000], 0, 160_000));
                } catch (InterruptedException exception) {
                    result.set(Integer.MAX_VALUE);
                }
            });
            Thread.sleep(200);
            session.close();
            reader.join(2_000);
            assertFalse(reader.isAlive());
            assertTrue(result.get() == -1 || (result.get() >= 0 && result.get() < 160_000), "read returned " + result.get());
            assertNull(session.read());
            assertEquals(-1, session.read(new short[160], 0, 160));
            assertEquals(List.of(), audioThreads());
        }

        @Test
        @DisplayName("an interrupt ends a blocked read promptly and leaves the session usable")
        void interruptedRead() throws InterruptedException {
            var input = AudioDevices.defaultInput();
            assumeTrue(input.isPresent(), "no input");
            try (var session = AudioDevices.openCapture(input.get(), AudioStreamOptions.forVoice())) {
                var outcome = new AtomicReference<Object>();
                var reader = Thread.ofPlatform().start(() -> {
                    try {
                        var count = session.read(new float[160_000], 0, 160_000);
                        outcome.set(Thread.currentThread().isInterrupted() ? "partial " + count : "complete " + count);
                    } catch (Throwable throwable) {
                        outcome.set(throwable);
                    }
                });
                Thread.sleep(100);
                reader.interrupt();
                reader.join(2_000);
                assertFalse(reader.isAlive());
                var result = outcome.get();
                assertTrue(result instanceof InterruptedException || (result instanceof String text && text.startsWith("partial ")),
                        "outcome " + result);
                assertNotNull(session.read());
            }
        }

        @Test
        @DisplayName("opens the loopback of an output with the default options")
        void defaultLoopback() {
            var output = AudioDevices.defaultOutput();
            assumeTrue(output.isPresent(), "no output");
            var defaults = AudioStreamOptions.defaults();
            try (var session = AudioDevices.openLoopback(output.get())) {
                assertEquals(output.get(), session.device());
                assertTrue(session.sampleRate() > 0);
                assertTrue(session.periodFrames() >= session.sampleRate() * defaults.period().toNanos() / 1_000_000_000L / 2,
                        "period frames " + session.periodFrames());
            }
            assertEquals(List.of(), audioThreads());
        }

        @Test
        @DisplayName("rejects an exclusive loopback")
        void exclusiveLoopback() {
            var output = AudioDevices.defaultOutput();
            assumeTrue(output.isPresent(), "no output");
            assertThrows(MediaException.Unsupported.class,
                    () -> AudioDevices.openLoopback(output.get(),
                            AudioStreamOptions.forVoice().toBuilder().shareMode(AudioShareMode.EXCLUSIVE).build()));
        }

        @Test
        @DisplayName("registers and unregisters for device notifications")
        void listeners() {
            AudioDeviceListener first = event -> System.out.println("event " + event);
            AudioDeviceListener second = event -> {
            };
            assertDoesNotThrow(() -> AudioDevices.addListener(first));
            assertDoesNotThrow(() -> AudioDevices.addListener(second));
            assertTrue(audioThreads().contains("smalt-audio-notifications"));
            AudioDevices.removeListener(first);
            AudioDevices.removeListener(second);
            AudioDevices.removeListener(second);
            assertEquals(List.of(), audioThreads());
        }
    }

    @Nested
    @DisplayName("microphone")
    class Microphone {
        @Test
        @DisplayName("captures 10 ms frames of 16 kHz mono with increasing timestamps and a plausible delay")
        void captureVoice() throws InterruptedException {
            var input = AudioDevices.defaultInput();
            assumeTrue(input.isPresent(), "no input");
            try (var session = AudioDevices.openCapture(input.get(), AudioStreamOptions.forVoice())) {
                assertEquals(16_000, session.sampleRate());
                assertEquals(1, session.channels());
                assertEquals(160, session.periodFrames());
                var previous = -1L;
                var start = System.nanoTime();
                var delays = new ArrayList<Long>();
                for (var i = 0; i < 100; i++) {
                    var buffer = session.read();
                    assertNotNull(buffer);
                    assertEquals(160, buffer.frames());
                    var timestamp = session.timestampMicros();
                    assertTrue(timestamp > previous, "timestamps must increase");
                    if (previous >= 0) {
                        assertTrue(Math.abs(timestamp - previous - 10_000) < 3_000, "10 ms apart: " + (timestamp - previous));
                    }
                    previous = timestamp;
                    delays.add(session.delayMicros());
                }
                var elapsed = Duration.ofNanos(System.nanoTime() - start);
                System.out.println("captured 1 s from " + input.get().name() + " in " + elapsed.toMillis() + " ms, latency "
                                   + session.latencyMicros() + " us, delays (us) " + delays.subList(90, 100) + ", overruns " + session.overruns());
                assertTrue(elapsed.toMillis() > 800 && elapsed.toMillis() < 1_500, "paced by the device: " + elapsed);
                assertTrue(delays.getLast() < 200_000, "delay " + delays.getLast());
            }
        }

        @Test
        @DisplayName("captures at the device's own format by default")
        void captureNative() throws InterruptedException {
            var input = AudioDevices.defaultInput();
            assumeTrue(input.isPresent(), "no input");
            var mix = input.get().formats().getFirst();
            try (var session = AudioDevices.openCapture(input.get())) {
                assertEquals(mix.sampleRate(), session.sampleRate());
                assertEquals(mix.channels(), session.channels());
                assertNotNull(session.read());
            }
        }

        @Test
        @DisplayName("asks for a short engine period at the native format")
        void lowLatency() throws InterruptedException {
            var input = AudioDevices.defaultInput();
            assumeTrue(input.isPresent(), "no input");
            var options = AudioStreamOptions.forRealtime().toBuilder().period(Duration.ofMillis(3)).latency(Duration.ofMillis(3))
                    .build();
            try (var session = AudioDevices.openCapture(input.get(), options)) {
                for (var i = 0; i < 50; i++) {
                    assertNotNull(session.read());
                }
                System.out.println("low-latency capture of " + input.get().name() + ": device buffer " + session.latencyMicros()
                                   + " us, delay " + session.delayMicros() + " us");
            }
        }
    }

    @Nested
    @DisplayName("playback")
    class Playback {
        @Test
        @DisplayName("counts underruns once the writer stops, and reports a delay within the queue plus the device buffer")
        void underruns() throws InterruptedException {
            var output = cableInput().or(AudioDevices::defaultOutput);
            assumeTrue(output.isPresent(), "no output");
            try (var session = AudioDevices.openPlayback(output.get(), AudioStreamOptions.forVoice())) {
                var silence = new short[session.periodFrames()];
                assertEquals(0, session.underruns());
                for (var i = 0; i < 5; i++) {
                    session.write(silence, 0, silence.length);
                }
                var delay = session.delayMicros();
                System.out.println("playback on " + output.get().name() + ": device buffer " + session.latencyMicros() + " us, delay after 50 ms queued "
                                   + delay + " us");
                assertTrue(delay > 0 && delay < 500_000, "delay " + delay);
                session.drain();
                assertEquals(0, session.queued());
                Thread.sleep(200);
                assertTrue(session.underruns() > 0, "silence after the last write counts");
            }
        }

        @Test
        @DisplayName("blocks a write while the queue is full")
        void backpressure() throws InterruptedException {
            var output = cableInput().or(AudioDevices::defaultOutput);
            assumeTrue(output.isPresent(), "no output");
            try (var session = AudioDevices.openPlayback(output.get(), AudioStreamOptions.forVoice())) {
                var second = new short[16_000];
                var start = System.nanoTime();
                assertEquals(16_000, session.write(second, 0, 16_000));
                var elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                // a second of audio through a 60 ms queue takes about a second minus the queue
                assertTrue(elapsed > 800, "write returned after " + elapsed + " ms");
                assertTrue(session.queued() <= session.bufferFrames());
            }
        }

        @Test
        @DisplayName("rejects a buffer of another format")
        void wrongBuffer() {
            var output = AudioDevices.defaultOutput();
            assumeTrue(output.isPresent(), "no output");
            try (var session = AudioDevices.openPlayback(output.get(), AudioStreamOptions.forVoice())) {
                var stereo = new com.github.auties00.smalt.audio.AudioBuffer(com.github.auties00.smalt.audio.ChannelLayout.STEREO, 16_000, 160);
                assertThrows(IllegalArgumentException.class, () -> session.write(stereo));
            }
        }
    }

    @Nested
    @DisplayName("virtual cable loopback")
    class Cable {
        @Test
        @DisplayName("captures a 1 kHz tone played on the cable at 16 kHz mono")
        void tone() throws InterruptedException {
            var render = cableInput();
            var capture = cableOutput();
            assumeTrue(render.isPresent() && capture.isPresent(), "no VB-Audio Virtual Cable");
            var failure = new AtomicReference<Throwable>();
            var options = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
            try (var player = AudioDevices.openPlayback(render.get(), AudioStreamOptions.forVoice());
                 var recorder = AudioDevices.openCapture(capture.get(), options)) {
                var writer = playTone(player, 1_000, failure);
                try {
                    var settle = new float[16_000 / 4];
                    recorder.read(settle, 0, settle.length);
                    var captured = new float[16_000 / 2];
                    assertEquals(captured.length, recorder.read(captured, 0, captured.length));
                    var ratio = toneRatio(captured, captured.length, 16_000, 1_000);
                    var rms = 0.0;
                    for (var sample : captured) {
                        rms += sample * sample;
                    }
                    rms = Math.sqrt(rms / captured.length);
                    System.out.println("cable tone: 1 kHz power ratio " + ratio + ", rms " + rms + " (0.354 sent), underruns "
                                       + player.underruns() + ", overruns " + recorder.overruns());
                    assertEquals(0, recorder.overruns(), "no capture lost with a 1 s queue");
                    assertTrue(ratio > 0.9, "the 1 kHz tone dominates: " + ratio);
                    assertTrue(rms > 0.2 && rms < 0.5, "rms " + rms);
                } finally {
                    writer.interrupt();
                    player.close();
                    writer.join(2_000);
                }
            }
            assertNull(failure.get());
        }

        @Test
        @DisplayName("captures the tone through the WASAPI loopback of the render endpoint")
        void loopbackTap() throws InterruptedException {
            var render = cableInput();
            assumeTrue(render.isPresent(), "no VB-Audio Virtual Cable");
            var failure = new AtomicReference<Throwable>();
            var options = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
            try (var player = AudioDevices.openPlayback(render.get(), AudioStreamOptions.forVoice());
                 var tap = AudioDevices.openLoopback(render.get(), options)) {
                assertEquals(render.get(), tap.device());
                var writer = playTone(player, 700, failure);
                try {
                    var settle = new float[16_000 / 4];
                    tap.read(settle, 0, settle.length);
                    var captured = new float[16_000 / 2];
                    assertEquals(captured.length, tap.read(captured, 0, captured.length));
                    var ratio = toneRatio(captured, captured.length, 16_000, 700);
                    System.out.println("loopback tap: 700 Hz power ratio " + ratio + ", overruns " + tap.overruns());
                    assertTrue(ratio > 0.9, "the 700 Hz tone dominates: " + ratio);
                } finally {
                    writer.interrupt();
                    player.close();
                    writer.join(2_000);
                }
            }
            assertNull(failure.get());
        }

        @Test
        @DisplayName("captures the cable exclusively when the driver allows it")
        void exclusive() throws InterruptedException {
            var capture = cableOutput();
            assumeTrue(capture.isPresent(), "no VB-Audio Virtual Cable");
            var device = capture.get();
            var native_ = device.formats().stream().skip(1).findFirst();
            assumeTrue(native_.isPresent(), "the cable lists no exclusive format");
            var format = native_.get();
            var options = AudioStreamOptions.forRealtime().toBuilder().sampleRate(format.sampleRate()).channels(format.channels())
                    .shareMode(AudioShareMode.EXCLUSIVE).build();
            try (var session = AudioDevices.openCapture(device, options)) {
                for (var i = 0; i < 20; i++) {
                    assertNotNull(session.read());
                }
                System.out.println("exclusive capture at " + format + ": device buffer " + session.latencyMicros() + " us");
            } catch (MediaException.Unsupported exception) {
                assumeTrue(false, exception.getMessage());
            }
        }

        @Test
        @DisplayName("measures the click round trip against the delays the sessions report")
        void latencyHarness() throws InterruptedException {
            var render = cableInput();
            var capture = cableOutput();
            assumeTrue(render.isPresent() && capture.isPresent(), "no VB-Audio Virtual Cable");
            var options = AudioStreamOptions.forVoice();
            var recorderOptions = options.toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
            try (var player = AudioDevices.openPlayback(render.get(), options);
                 var recorder = AudioDevices.openCapture(capture.get(), recorderOptions)) {
                var frames = player.periodFrames();
                var silence = new float[frames];
                var click = new float[frames];
                click[0] = 0.9f;
                var expectedPlay = new long[5];
                var captured = new long[5];
                var found = new AtomicInteger();
                var failure = new AtomicReference<Throwable>();
                var listener = Thread.ofPlatform().start(() -> {
                    var chunk = new float[recorder.periodFrames()];
                    try {
                        while (found.get() < captured.length) {
                            if (recorder.read(chunk, 0, chunk.length) < chunk.length) {
                                return;
                            }
                            var firstFrameNanos = System.nanoTime() - recorder.delayMicros() * 1_000;
                            for (var i = 0; i < chunk.length; i++) {
                                if (Math.abs(chunk[i]) > 0.3f) {
                                    captured[found.getAndIncrement()] = firstFrameNanos + i * 1_000_000_000L / recorder.sampleRate();
                                    break;
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                for (var i = 0; i < 3; i++) {
                    player.write(silence, 0, frames);
                }
                for (var n = 0; n < expectedPlay.length; n++) {
                    for (var i = 0; i < 30; i++) {
                        player.write(silence, 0, frames);
                    }
                    expectedPlay[n] = System.nanoTime() + player.delayMicros() * 1_000;
                    player.write(click, 0, frames);
                }
                for (var i = 0; i < 50 && found.get() < captured.length; i++) {
                    player.write(silence, 0, frames);
                }
                listener.join(5_000);
                assertNull(failure.get());
                assertEquals(captured.length, found.get(), "every click was captured");
                var errors = new ArrayList<Long>();
                for (var n = 0; n < captured.length; n++) {
                    errors.add((captured[n] - expectedPlay[n]) / 1_000);
                }
                System.out.println("click round trip: capture time minus predicted play time (us) " + errors
                                   + "; includes the cable's own latency");
                for (var error : errors) {
                    assertTrue(Math.abs(error) < 300_000, "prediction off by " + error + " us");
                }
                var spread = errors.stream().mapToLong(Long::longValue).max().orElseThrow()
                             - errors.stream().mapToLong(Long::longValue).min().orElseThrow();
                assertTrue(spread < 25_000, "the prediction is stable to 25 ms: spread " + spread + " us");
            }
        }

        @Test
        @DisplayName("lists formats whose encodings are known")
        void formats() {
            var capture = cableOutput();
            assumeTrue(capture.isPresent(), "no VB-Audio Virtual Cable");
            for (var format : capture.get().formats()) {
                assertNotNull(format.sampleFormat());
                assertTrue(format.frameBytes() >= format.channels() * AudioSampleFormat.PCM_S16.bytes());
            }
        }
    }
}
