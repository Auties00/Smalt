package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the device sample codecs, the rate converter and the polling device watch.
 */
@Timeout(10)
@DisplayName("sample conversion and device watching")
class SampleConversionTest {
    @ParameterizedTest
    @EnumSource(AudioSampleFormat.class)
    @DisplayName("round-trips samples through every device encoding within its precision")
    void roundTrip(AudioSampleFormat format) {
        var samples = new float[]{0.0f, 0.5f, -0.5f, 0.999f, -1.0f, 0.123456f, -0.0001f};
        try (var arena = Arena.ofConfined()) {
            var native_ = arena.allocate((long) samples.length * format.bytes());
            SampleCodec.encode(format, samples, 0, native_, samples.length);
            var decoded = new float[samples.length];
            SampleCodec.decode(format, native_, decoded, 0, samples.length);
            var tolerance = switch (format) {
                case PCM_S16 -> 1.0 / 32768;
                case PCM_S24 -> 1.0 / 8_388_608;
                case PCM_S32, FLOAT32 -> 1e-7;
            };
            for (var i = 0; i < samples.length; i++) {
                assertEquals(samples[i], decoded[i], tolerance, format + " sample " + i);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AudioSampleFormat.class)
    @DisplayName("clamps out-of-range samples and reads NaN as silence when encoding")
    void clamp(AudioSampleFormat format) {
        var samples = new float[]{4.0f, -4.0f, Float.NaN};
        try (var arena = Arena.ofConfined()) {
            var native_ = arena.allocate((long) samples.length * format.bytes());
            SampleCodec.encode(format, samples, 0, native_, samples.length);
            var decoded = new float[3];
            SampleCodec.decode(format, native_, decoded, 0, 3);
            assertEquals(1.0f, decoded[0], 1.0 / 32768);
            assertEquals(-1.0f, decoded[1], 1e-6);
            assertEquals(0.0f, decoded[2], 1e-6);
        }
    }

    @Test
    @DisplayName("decodes little-endian integers with their sign")
    void endianness() {
        try (var arena = Arena.ofConfined()) {
            var bytes = arena.allocate(3);
            bytes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
            bytes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
            bytes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 2, (byte) 0xC0);
            var decoded = new float[1];
            SampleCodec.decode(AudioSampleFormat.PCM_S24, bytes, decoded, 0, 1);
            assertEquals(-0.5f, decoded[0]);
        }
    }

    @Test
    @DisplayName("maps 16-bit samples the way the voice processor does")
    void shorts() {
        assertEquals(16384, SampleCodec.toShort(0.5f));
        assertEquals(32767, SampleCodec.toShort(1.0f));
        assertEquals(-32768, SampleCodec.toShort(-1.5f));
        assertEquals(-0.5f, SampleCodec.toFloat((short) -16384));
    }

    @Test
    @DisplayName("passes audio at an unchanged rate through without delay")
    void identity() {
        var converter = new RateConverter(48_000, 48_000, 2);
        assertTrue(converter.identity());
        assertEquals(0, converter.delayFrames());
        assertEquals(1_000, converter.outputNanos(1_000));
        assertEquals(2, converter.convert(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 2));
        assertEquals(0.4f, converter.output()[3]);
    }

    @Test
    @DisplayName("resamples 48 kHz to 16 kHz keeping a tone's frequency and the frame count")
    void resample() {
        var converter = new RateConverter(48_000, 16_000, 1);
        var produced = 0;
        var output = new ArrayList<Float>();
        for (var block = 0; block < 100; block++) {
            var input = new float[480];
            for (var i = 0; i < 480; i++) {
                input[i] = (float) Math.sin(2 * Math.PI * 1_000 * (block * 480 + i) / 48_000.0);
            }
            var count = converter.convert(input, 480);
            for (var i = 0; i < count; i++) {
                output.add(converter.output()[i]);
            }
            produced += count;
        }
        assertTrue(Math.abs(produced - 16_000) <= converter.delayFrames(), "frames " + produced);
        var crossings = 0;
        for (var i = 1000; i < 9000; i++) {
            if (output.get(i - 1) < 0 && output.get(i) >= 0) {
                crossings++;
            }
        }
        assertEquals(500, crossings, 2, "1 kHz over 0.5 s");
    }

    @Test
    @DisplayName("stamps resampled audio with the capture time of its first frame, before the look-ahead")
    void outputTime() {
        var converter = new RateConverter(48_000, 16_000, 1);
        assertTrue(converter.delayFrames() > 1, "look-ahead " + converter.delayFrames());
        // A click at input frame 4800 (100 ms) of 10 ms blocks captured from time 0; every output block is
        // stamped from its input block's capture time, so the click's output frame must land at 100 ms.
        var clickNanos = 100_000_000L;
        var outputFrameNanos = 1_000_000_000L / 16_000;
        var found = Long.MIN_VALUE;
        var naive = Long.MIN_VALUE;
        var peak = 0f;
        for (var block = 0; block < 30; block++) {
            var input = new float[480];
            if (block == 10) {
                input[0] = 1f;
            }
            var captured = block * 10_000_000L;
            var count = converter.convert(input, 480);
            var stamp = converter.outputNanos(captured);
            for (var i = 0; i < count; i++) {
                var sample = Math.abs(converter.output()[i]);
                if (sample > peak) {
                    peak = sample;
                    found = stamp + i * outputFrameNanos;
                    naive = captured + i * outputFrameNanos;
                }
            }
        }
        assertEquals(clickNanos, found, 2 * outputFrameNanos, "click time with the look-ahead removed");
        assertTrue(Math.abs(naive - clickNanos) > outputFrameNanos, "a stamp without the look-ahead is late by it");
    }

    @Test
    @DisplayName("reports devices added and removed and a new default after a poke")
    void watch() throws InterruptedException {
        var inputs = new AtomicReference<List<AudioDevice.Input>>(List.of(new AudioDevice.Input("a", "A", true, List.of())));
        var outputs = new AtomicReference<List<AudioDevice.Output>>(List.of(new AudioDevice.Output("x", "X", true, List.of())));
        var events = new CopyOnWriteArrayList<AudioDeviceEvent>();
        try (var watch = new PollingDeviceWatch(inputs::get, outputs::get, events::add, Duration.ofSeconds(60))) {
            inputs.set(List.of(new AudioDevice.Input("b", "B", true, List.of()), new AudioDevice.Input("a", "A", false, List.of())));
            outputs.set(List.of());
            watch.poke();
            var deadline = System.nanoTime() + 5_000_000_000L;
            while (events.size() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
        assertTrue(events.contains(new AudioDeviceEvent.DeviceAdded("b")), events.toString());
        assertTrue(events.contains(new AudioDeviceEvent.DeviceRemoved("x")), events.toString());
        assertTrue(events.contains(new AudioDeviceEvent.DefaultChanged(AudioDevice.Direction.INPUT, Optional.of("b"))), events.toString());
        assertTrue(events.contains(new AudioDeviceEvent.DefaultChanged(AudioDevice.Direction.OUTPUT, Optional.empty())), events.toString());
        assertEquals(4, events.size(), events.toString());
    }
}
