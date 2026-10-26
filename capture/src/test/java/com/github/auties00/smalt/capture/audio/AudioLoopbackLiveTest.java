package com.github.auties00.smalt.capture.audio;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays a tone through a loopback pair of audio devices and captures it back, on whichever backend the
 * machine selects (ALSA, WASAPI or CoreAudio). The pair is named by {@code smalt.test.audioLoopback}, as
 * {@code <output>|<input>} (or {@code SMALT_TEST_AUDIO_LOOPBACK}), each part a device id or a part of a device name: CI provisions a PulseAudio null
 * sink whose monitor is the default source on Linux ({@code default|default}), VB-Audio Virtual Cable on
 * Windows ({@code Speakers (VB-Audio|CABLE Output}, the names its driver gives the endpoints there) and BlackHole on macOS ({@code BlackHole 2ch|BlackHole 2ch}). The
 * tests skip with {@code device:audio-loopback} without the property or the devices, and fail instead where
 * {@code smalt.capture.requireDevices} names {@code audio-loopback}.
 */
@Timeout(60)
@DisplayName("audio loopback through the platform backend")
class AudioLoopbackLiveTest {
    private record Pair(AudioDevice.Output output, AudioDevice.Input input) {
    }

    private static Pair pair() {
        var spec = Skip.setting("smalt.test.audioLoopback", "SMALT_TEST_AUDIO_LOOPBACK");
        var parts = spec.split("\\|", -1);
        Optional<AudioDevice.Output> output = Optional.empty();
        Optional<AudioDevice.Input> input = Optional.empty();
        if (parts.length == 2) {
            output = find(AudioDevices.outputs(), parts[0]);
            input = find(AudioDevices.inputs(), parts[1]);
        }
        Skip.unlessDevice(output.isPresent() && input.isPresent(), "audio-loopback",
                "no loopback pair " + (spec.isEmpty() ? "(set -Dsmalt.test.audioLoopback=<output>|<input>)" : spec)
                        + "; outputs " + names(AudioDevices.outputs()) + ", inputs " + names(AudioDevices.inputs()));
        return new Pair(output.orElseThrow(), input.orElseThrow());
    }

    /** Finds the device whose id is the part, or else the first whose name contains it, ignoring case. */
    private static <D extends AudioDevice> Optional<D> find(List<D> devices, String part) {
        if (part.isEmpty()) {
            return Optional.empty();
        }
        var byId = devices.stream().filter(device -> device.id().equals(part)).findFirst();
        return byId.isPresent() ? byId : devices.stream()
                .filter(device -> device.name().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    private static List<String> names(List<? extends AudioDevice> devices) {
        return devices.stream().map(device -> device.name() + " (" + device.id() + ")").toList();
    }

    private static float[] tone(int frames, int rate, int channels, double frequency, double amplitude, long start) {
        var samples = new float[frames * channels];
        for (var i = 0; i < frames; i++) {
            var value = (float) (amplitude * Math.sin(2 * Math.PI * frequency * (start + i) / rate));
            for (var c = 0; c < channels; c++) {
                samples[i * channels + c] = value;
            }
        }
        return samples;
    }

    /** Returns the power of one frequency relative to the total power of a mono signal (Goertzel). */
    private static double toneRatio(float[] mono, int rate, double frequency) {
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

    private static Thread play(AudioPlaybackSession session, double frequency, AtomicReference<Throwable> failure) {
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

    @Test
    @DisplayName("captures a 1 kHz tone played on the loopback output at 16 kHz mono")
    void tone() throws InterruptedException {
        var pair = pair();
        var failure = new AtomicReference<Throwable>();
        var options = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
        try (var player = AudioDevices.openPlayback(pair.output(), AudioStreamOptions.forVoice());
             var recorder = AudioDevices.openCapture(pair.input(), options)) {
            assertEquals(16_000, recorder.sampleRate());
            assertEquals(1, recorder.channels());
            var writer = play(player, 1_000, failure);
            try {
                var settle = new float[16_000 / 2];
                recorder.read(settle, 0, settle.length);
                var captured = new float[16_000 / 2];
                assertEquals(captured.length, recorder.read(captured, 0, captured.length));
                var ratio = toneRatio(captured, 16_000, 1_000);
                var rms = 0.0;
                for (var sample : captured) {
                    rms += sample * sample;
                }
                rms = Math.sqrt(rms / captured.length);
                System.out.println("loopback tone through " + pair + ": 1 kHz power ratio " + ratio + ", rms " + rms
                                   + " (0.354 sent), underruns " + player.underruns() + ", overruns "
                                   + recorder.overruns());
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
    @DisplayName("captures silence once the output stops playing")
    void silence() throws InterruptedException {
        var pair = pair();
        var options = AudioStreamOptions.forVoice().toBuilder().bufferDuration(Duration.ofSeconds(1)).build();
        try (var recorder = AudioDevices.openCapture(pair.input(), options)) {
            var settle = new float[16_000 / 4];
            recorder.read(settle, 0, settle.length);
            var captured = new float[16_000 / 4];
            assertEquals(captured.length, recorder.read(captured, 0, captured.length));
            var peak = 0f;
            for (var sample : captured) {
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue(peak < 0.01f, "peak " + peak + " with nothing playing");
        }
    }
}
