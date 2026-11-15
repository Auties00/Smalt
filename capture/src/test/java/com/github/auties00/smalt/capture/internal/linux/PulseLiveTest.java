package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioDeviceListener;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the sound-server devices against the PulseAudio server CI provisions, whose default sink is a
 * null sink: loopback capture of the default output and of the sound-server sink, compared with the
 * noise played on it by cross-correlation; the change events of a sink added, made the default and
 * removed with {@code pactl}, which must arrive well before the watch's periodic listing would find
 * them. The mapping of an ALSA card's device to the sink the server drives it with needs an ALSA
 * card, which the hosted runners lack; PulseDevicesTest covers it.
 */
@DisplayName("sound server (live)")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class PulseLiveTest {
    /**
     * The longest time a change event may take: well under the watch's periodic listing.
     */
    private static final Duration EVENT_LATENCY = Duration.ofSeconds(4);

    private static PulseServer.Snapshot snapshot() {
        try (var server = PulseServer.connect()) {
            Skip.unlessDevice(server != null, "sound-server", "no PulseAudio or PipeWire server answers");
            var snapshot = server.snapshot();
            assertNotNull(snapshot);
            return snapshot;
        }
    }

    @Test
    @DisplayName("the server's sinks, sources and defaults are listed with the default flags")
    void listing() {
        var snapshot = snapshot();
        var outputs = AudioDevices.outputs();
        var inputs = AudioDevices.inputs();
        assertEquals(1, outputs.stream().filter(AudioDevice::defaultDevice).count(), outputs.toString());
        assertEquals(Optional.of(PulseDevices.PREFIX + snapshot.defaultSink()), AudioDevices.defaultOutput().map(AudioDevice::id));
        assertEquals(Optional.of(PulseDevices.PREFIX + snapshot.defaultSource()), AudioDevices.defaultInput().map(AudioDevice::id));
        for (var sink : snapshot.sinks()) {
            assertTrue(outputs.stream().anyMatch(device -> device.id().equals(PulseDevices.PREFIX + sink.name())), sink.name());
            assertTrue(inputs.stream().anyMatch(device -> device.id().equals(PulseDevices.PREFIX + sink.monitor())), sink.monitor());
        }
        assertTrue(outputs.stream().anyMatch(device -> device.id().equals(AlsaDevices.DEFAULT) && !device.defaultDevice()));
    }

    @Test
    @DisplayName("the loopback of the default output and of the default sink record the noise played on it")
    void loopback() {
        snapshot();
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            var sinkDevice = AudioDevices.defaultOutput().orElseThrow();
            var alsaDefault = AudioDevices.output(AlsaDevices.DEFAULT).orElseThrow();
            for (var loopback : List.of(sinkDevice, alsaDefault)) {
                for (var player : List.of(sinkDevice, alsaDefault)) {
                    var correlation = correlate(player, loopback);
                    assertTrue(correlation > 0.8, "playing on " + player.id() + ", the loopback of " + loopback.id()
                            + " correlates by " + correlation);
                }
            }
        });
    }

    @Test
    @DisplayName("a sink added, made the default and removed is reported as it happens")
    void events() {
        var snapshot = snapshot();
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            var events = new LinkedBlockingQueue<AudioDeviceEvent>();
            var seen = new java.util.ArrayList<AudioDeviceEvent>();
            AudioDeviceListener listener = events::add;
            AudioDevices.addListener(listener);
            String module = null;
            try {
                Thread.sleep(500);
                events.clear();
                seen.clear();
                module = pactl("load-module", "module-null-sink", "sink_name=smalt_hotplug",
                        "sink_properties=device.description=SmaltHotplug").trim();
                await(events, seen, event -> event instanceof AudioDeviceEvent.DeviceAdded added
                        && added.deviceId().equals(PulseDevices.PREFIX + "smalt_hotplug"), "the sink added");
                await(events, seen, event -> event instanceof AudioDeviceEvent.DeviceAdded added
                        && added.deviceId().equals(PulseDevices.PREFIX + "smalt_hotplug.monitor"), "its monitor added");
                pactl("set-default-sink", "smalt_hotplug");
                await(events, seen, event -> event instanceof AudioDeviceEvent.DefaultChanged changed
                        && changed.direction() == AudioDevice.Direction.OUTPUT
                        && changed.deviceId().equals(Optional.of(PulseDevices.PREFIX + "smalt_hotplug")), "the default changed");
                pactl("set-default-sink", snapshot.defaultSink());
                pactl("unload-module", module);
                module = null;
                await(events, seen, event -> event instanceof AudioDeviceEvent.DeviceRemoved removed
                        && removed.deviceId().equals(PulseDevices.PREFIX + "smalt_hotplug"), "the sink removed");
            } finally {
                AudioDevices.removeListener(listener);
                if (module != null) {
                    pactl("set-default-sink", snapshot.defaultSink());
                    pactl("unload-module", module);
                }
            }
        });
    }

    /**
     * Waits for an event among those seen so far and those still to come, failing after
     * {@link #EVENT_LATENCY}; the events of one listing arrive in no particular order.
     */
    private static void await(LinkedBlockingQueue<AudioDeviceEvent> events, java.util.List<AudioDeviceEvent> seen,
                              Predicate<AudioDeviceEvent> wanted, String what) throws InterruptedException {
        var deadline = System.nanoTime() + EVENT_LATENCY.toNanos();
        while (seen.stream().noneMatch(wanted)) {
            var left = deadline - System.nanoTime();
            var event = left <= 0 ? null : events.poll(left, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new AssertionError(what + " was not reported within " + EVENT_LATENCY + "; seen " + seen);
            }
            seen.add(event);
        }
    }

    /**
     * Plays noise on an output and records the loopback of another, returning the peak of their
     * normalized cross-correlation.
     */
    private static double correlate(AudioDevice.Output player, AudioDevice.Output loopback) throws InterruptedException {
        var rate = 16_000;
        var options = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(2)).build();
        var noise = new float[rate * 2];
        var random = new SplittableRandom(7);
        for (var i = 0; i < noise.length; i++) {
            noise[i] = (float) (random.nextDouble() - 0.5) * 0.8f;
        }
        var failure = new AtomicReference<Throwable>();
        try (AudioCaptureSession recorder = AudioDevices.openLoopback(loopback, options);
             AudioPlaybackSession output = AudioDevices.openPlayback(player, AudioStreamOptions.forVoice())) {
            assertEquals(rate, recorder.sampleRate());
            assertEquals(1, recorder.channels());
            var writer = Thread.ofPlatform().daemon().start(() -> {
                try {
                    var position = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        var frames = Math.min(output.periodFrames(), noise.length - position);
                        var chunk = new float[frames * output.channels()];
                        for (var i = 0; i < frames; i++) {
                            for (var c = 0; c < output.channels(); c++) {
                                chunk[i * output.channels() + c] = noise[position + i];
                            }
                        }
                        if (output.write(chunk, 0, frames) < frames) {
                            return;
                        }
                        position = (position + frames) % noise.length;
                    }
                } catch (InterruptedException exception) {
                    // stopped
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            try {
                var settle = new float[rate / 2];
                recorder.read(settle, 0, settle.length);
                var captured = new float[rate];
                assertEquals(captured.length, recorder.read(captured, 0, captured.length));
                return peakCorrelation(noise, captured);
            } finally {
                writer.interrupt();
                writer.join(2_000);
            }
        } finally {
            assertNull(failure.get());
        }
    }

    /**
     * Returns the largest normalized correlation between the capture and any cyclic shift of the
     * played noise, which loops.
     */
    static double peakCorrelation(float[] played, float[] captured) {
        var window = Math.min(captured.length, played.length) / 2;
        var best = 0.0;
        double capturedEnergy = 0;
        for (var i = 0; i < window; i++) {
            capturedEnergy += captured[i] * captured[i];
        }
        if (capturedEnergy == 0) {
            return 0;
        }
        for (var shift = 0; shift < played.length; shift++) {
            double dot = 0;
            double playedEnergy = 0;
            for (var i = 0; i < window; i++) {
                var sample = played[(shift + i) % played.length];
                dot += sample * captured[i];
                playedEnergy += sample * sample;
            }
            best = Math.max(best, dot / Math.sqrt(capturedEnergy * playedEnergy));
        }
        return best;
    }

    private static String pactl(String... arguments) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("pactl");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
        return output;
    }
}
