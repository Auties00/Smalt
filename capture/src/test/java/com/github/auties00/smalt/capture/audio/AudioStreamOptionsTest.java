package com.github.auties00.smalt.capture.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the stream options, the device model records and the event records.
 */
@DisplayName("audio stream options and device model")
class AudioStreamOptionsTest {
    @Test
    @DisplayName("defaults to the device's format, 10 ms periods, 40 ms latency, a 200 ms queue, shared")
    void defaults() {
        var options = AudioStreamOptions.defaults();
        assertEquals(OptionalInt.empty(), options.sampleRate());
        assertEquals(OptionalInt.empty(), options.channels());
        assertEquals(Duration.ofMillis(10), options.period());
        assertEquals(Duration.ofMillis(40), options.latency());
        assertEquals(Duration.ofMillis(200), options.bufferDuration());
        assertEquals(AudioShareMode.SHARED, options.shareMode());
        assertEquals(AudioStreamOptions.builder().build(), options);
    }

    @Test
    @DisplayName("the default constants are the durations of the defaults")
    void defaultConstants() {
        var options = AudioStreamOptions.defaults();
        assertEquals(AudioStreamOptions.DEFAULT_PERIOD, options.period());
        assertEquals(AudioStreamOptions.DEFAULT_LATENCY, options.latency());
        assertEquals(AudioStreamOptions.DEFAULT_BUFFER_DURATION, options.bufferDuration());
        assertEquals(Duration.ofMillis(10), AudioStreamOptions.DEFAULT_PERIOD);
        assertEquals(Duration.ofMillis(40), AudioStreamOptions.DEFAULT_LATENCY);
        assertEquals(Duration.ofMillis(200), AudioStreamOptions.DEFAULT_BUFFER_DURATION);
    }

    @Test
    @DisplayName("deviceSampleRate and deviceChannels select the device's own format")
    void deviceResets() {
        var voice = AudioStreamOptions.forVoice();
        var device = voice.toBuilder().deviceSampleRate().deviceChannels().build();
        assertEquals(OptionalInt.empty(), device.sampleRate());
        assertEquals(OptionalInt.empty(), device.channels());
        assertEquals(AudioStreamOptions.forRealtime(), device);
    }

    @Test
    @DisplayName("every setting is reachable from the builder")
    void builder() {
        var built = AudioStreamOptions.builder()
                .sampleRate(48_000)
                .channels(2)
                .period(Duration.ofMillis(5))
                .latency(Duration.ofMillis(15))
                .bufferDuration(Duration.ofSeconds(1))
                .shareMode(AudioShareMode.EXCLUSIVE)
                .build();
        assertEquals(OptionalInt.of(48_000), built.sampleRate());
        assertEquals(OptionalInt.of(2), built.channels());
        assertEquals(Duration.ofMillis(5), built.period());
        assertEquals(Duration.ofMillis(15), built.latency());
        assertEquals(Duration.ofSeconds(1), built.bufferDuration());
        assertEquals(AudioShareMode.EXCLUSIVE, built.shareMode());
        assertEquals(built, built.toBuilder().build());
        assertEquals(AudioStreamOptions.defaults(), built.toBuilder()
                .deviceSampleRate()
                .deviceChannels()
                .period(AudioStreamOptions.DEFAULT_PERIOD)
                .latency(AudioStreamOptions.DEFAULT_LATENCY)
                .bufferDuration(AudioStreamOptions.DEFAULT_BUFFER_DURATION)
                .shareMode(AudioShareMode.SHARED)
                .build());
    }

    @Test
    @DisplayName("forVoice is forRealtime at 16 kHz mono")
    void presets() {
        var realtime = AudioStreamOptions.forRealtime();
        assertEquals(Duration.ofMillis(20), realtime.latency());
        assertEquals(Duration.ofMillis(60), realtime.bufferDuration());
        var voice = AudioStreamOptions.forVoice();
        assertEquals(OptionalInt.of(16_000), voice.sampleRate());
        assertEquals(OptionalInt.of(1), voice.channels());
        assertEquals(realtime, voice.toBuilder().deviceSampleRate().deviceChannels().build());
    }

    @Test
    @DisplayName("toBuilder changes settings and leaves the original untouched")
    void toBuilderCopies() {
        var base = AudioStreamOptions.defaults();
        var changed = base.toBuilder().sampleRate(48_000).channels(2).period(Duration.ofMillis(5))
                .latency(Duration.ofMillis(15)).bufferDuration(Duration.ofSeconds(1)).shareMode(AudioShareMode.EXCLUSIVE)
                .build();
        assertEquals(OptionalInt.of(48_000), changed.sampleRate());
        assertEquals(OptionalInt.of(2), changed.channels());
        assertEquals(Duration.ofMillis(5), changed.period());
        assertEquals(Duration.ofMillis(15), changed.latency());
        assertEquals(Duration.ofSeconds(1), changed.bufferDuration());
        assertEquals(AudioShareMode.EXCLUSIVE, changed.shareMode());
        assertNotEquals(base, changed);
        assertEquals(AudioStreamOptions.defaults(), base);
        assertEquals(changed, changed.toBuilder().build());
        assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
        assertTrue(changed.toString().contains("48000"));
        assertTrue(base.toString().contains("device"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 999, 768_001, -16_000})
    @DisplayName("rejects sampling rates outside [1000, 768000]")
    void badRate(int rate) {
        assertThrows(IllegalArgumentException.class, () -> AudioStreamOptions.defaults().toBuilder().sampleRate(rate).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 33})
    @DisplayName("rejects channel counts outside [1, 32]")
    void badChannels(int channels) {
        assertThrows(IllegalArgumentException.class, () -> AudioStreamOptions.defaults().toBuilder().channels(channels).build());
    }

    @Test
    @DisplayName("rejects durations outside [1 ms, 10 s] and nulls")
    void badDurations() {
        var options = AudioStreamOptions.defaults();
        for (var duration : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofSeconds(11), Duration.ofMillis(-5))) {
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().period(duration).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().latency(duration).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bufferDuration(duration).build());
        }
        assertThrows(NullPointerException.class, () -> options.toBuilder().period(null).build());
        assertThrows(NullPointerException.class, () -> options.toBuilder().shareMode(null).build());
    }

    @Test
    @DisplayName("device formats validate and report their frame size")
    void deviceFormat() {
        var format = new AudioDeviceFormat(48_000, 2, AudioSampleFormat.PCM_S24);
        assertEquals(6, format.frameBytes());
        assertThrows(IllegalArgumentException.class, () -> new AudioDeviceFormat(0, 2, AudioSampleFormat.FLOAT32));
        assertThrows(IllegalArgumentException.class, () -> new AudioDeviceFormat(48_000, 0, AudioSampleFormat.FLOAT32));
        assertThrows(NullPointerException.class, () -> new AudioDeviceFormat(48_000, 2, null));
    }

    @Test
    @DisplayName("devices freeze their formats and report their direction")
    void devices() {
        var formats = new java.util.ArrayList<>(List.of(new AudioDeviceFormat(48_000, 2, AudioSampleFormat.FLOAT32)));
        var input = new AudioDevice.Input("id", "Microphone", true, formats);
        formats.clear();
        assertEquals(1, input.formats().size());
        assertEquals(AudioDevice.Direction.INPUT, input.direction());
        var output = new AudioDevice.Output("id", "Speakers", false, List.of());
        assertEquals(AudioDevice.Direction.OUTPUT, output.direction());
        assertFalse(output.defaultDevice());
        assertThrows(NullPointerException.class, () -> new AudioDevice.Input(null, "x", false, List.of()));
    }

    @Test
    @DisplayName("events validate their arguments")
    void events() {
        assertThrows(NullPointerException.class, () -> new AudioDeviceEvent.DefaultChanged(null, java.util.Optional.empty()));
        assertThrows(NullPointerException.class, () -> new AudioDeviceEvent.DeviceAdded(null));
        assertThrows(NullPointerException.class, () -> new AudioDeviceEvent.DeviceRemoved(null));
    }
}
