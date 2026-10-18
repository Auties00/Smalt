package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compares {@link WaveformMeter} and {@link Waveform#of} with a reference computed directly from
 * every sample in the test: bucket {@code k} of {@code n} covers the frames
 * {@code ceil(k * total / n)} to {@code ceil((k + 1) * total / n)}.
 */
@DisplayName("A waveform")
class WaveformTest {
    /** A two-channel signal whose loudness changes over time, deterministic. */
    static float[][] signal(int frames, long seed) {
        var random = new Random(seed);
        var planes = new float[2][frames];
        for (var frame = 0; frame < frames; frame++) {
            var envelope = (float) (0.5 + 0.5 * Math.sin(frame * 0.0007));
            planes[0][frame] = envelope * (float) Math.sin(frame * 0.05) * 0.8f + (random.nextFloat() - 0.5f) * 0.05f;
            planes[1][frame] = envelope * (float) Math.cos(frame * 0.031) * 0.6f;
        }
        return planes;
    }

    record Reference(float[] min, float[] max, float[] rms, float[] mean) {
    }

    /** Computes the mixed-down reference directly. */
    static Reference reference(float[][] planes, int buckets) {
        var total = planes[0].length;
        var min = new float[buckets];
        var max = new float[buckets];
        var rms = new float[buckets];
        var mean = new float[buckets];
        for (var bucket = 0; bucket < buckets; bucket++) {
            var start = Math.ceilDiv((long) bucket * total, buckets);
            var end = Math.max(Math.ceilDiv((long) (bucket + 1) * total, buckets), Math.min(start + 1, total));
            start = Math.min(start, end - 1);
            var low = Float.POSITIVE_INFINITY;
            var high = Float.NEGATIVE_INFINITY;
            var abs = 0.0;
            var squares = 0.0;
            for (var frame = (int) start; frame < end; frame++) {
                var value = (planes[0][frame] + planes[1][frame]) / 2;
                low = Math.min(low, value);
                high = Math.max(high, value);
                abs += Math.abs(value);
                squares += (double) value * value;
            }
            min[bucket] = low;
            max[bucket] = high;
            mean[bucket] = (float) (abs / (end - start));
            rms[bucket] = (float) Math.sqrt(squares / (end - start));
        }
        return new Reference(min, max, rms, mean);
    }

    static Waveform feed(float[][] planes, WaveformOptions options, long seed) {
        var builder = new WaveformMeter(options);
        var random = new Random(seed);
        var offset = 0;
        while (offset < planes[0].length) {
            var length = Math.min(planes[0].length - offset, 1 + random.nextInt(5000));
            builder.accept(planes, offset, length, 48_000);
            offset += length;
        }
        return builder.finish();
    }

    @ParameterizedTest(name = "{0} frames in {1} buckets")
    @CsvSource({"1, 64", "63, 64", "64, 64", "1000, 64", "32767, 100", "30000, 7"})
    @DisplayName("matches the direct computation exactly while the audio fits the chunk store")
    void exact(int frames, int buckets) {
        var planes = signal(frames, frames);
        var waveform = feed(planes, WaveformOptions.ofBuckets(buckets), buckets);
        var expected = reference(planes, buckets);
        assertEquals(frames, waveform.frames());
        assertArrayEquals(expected.min(), waveform.minimums());
        assertArrayEquals(expected.max(), waveform.maximums());
        assertArrayEquals(expected.mean(), waveform.values(Waveform.Measure.MEAN_ABSOLUTE), 1e-6f);
        assertArrayEquals(expected.rms(), waveform.values(Waveform.Measure.RMS), 1e-6f);
    }

    @ParameterizedTest(name = "{0} frames in {1} buckets")
    @CsvSource({"100000, 64", "1000000, 64", "3000001, 100"})
    @DisplayName("stays within a fraction of a percent of the direct computation on long audio")
    void bounded(int frames, int buckets) {
        var planes = signal(frames, 7);
        var waveform = feed(planes, WaveformOptions.ofBuckets(buckets), 3);
        var expected = reference(planes, buckets);
        for (var bucket = 0; bucket < buckets; bucket++) {
            assertEquals(expected.mean()[bucket], waveform.meanAbsolute(bucket), 0.01 * expected.mean()[bucket] + 1e-4,
                    "mean of bucket " + bucket);
            assertEquals(expected.rms()[bucket], waveform.rms(bucket), 0.01 * expected.rms()[bucket] + 1e-4,
                    "rms of bucket " + bucket);
            // a chunk that a bucket boundary splits counts for both buckets, so extremes may only widen
            assertEquals(true, waveform.max(bucket) >= expected.max()[bucket], "max of bucket " + bucket);
            assertEquals(true, waveform.min(bucket) <= expected.min()[bucket], "min of bucket " + bucket);
        }
    }

    @Test
    @DisplayName("gives exact buckets of a fixed width, the last one partial")
    void framesPerBucket() {
        var planes = signal(10_050, 11);
        var waveform = feed(planes, WaveformOptions.ofFramesPerBucket(100).toBuilder()
                .channels(WaveformOptions.Channels.FIRST).build(), 5);
        assertEquals(101, waveform.bucketCount());
        for (var bucket = 0; bucket < 101; bucket++) {
            var slice = Arrays.copyOfRange(planes[0], bucket * 100, Math.min(10_050, bucket * 100 + 100));
            var max = Float.NEGATIVE_INFINITY;
            var abs = 0.0;
            for (var value : slice) {
                max = Math.max(max, value);
                abs += Math.abs(value);
            }
            assertEquals(max, waveform.max(bucket));
            assertEquals(abs / slice.length, waveform.meanAbsolute(bucket), 1e-6);
        }
    }

    @Test
    @DisplayName("takes every channel's extremes in ALL mode")
    void allChannels() {
        var planes = new float[][]{{0.1f, -0.2f, 0.3f}, {-0.9f, 0.5f, 0.0f}};
        var builder = new WaveformMeter(WaveformOptions.ofBuckets(1).toBuilder()
                .channels(WaveformOptions.Channels.ALL).build());
        builder.accept(planes, 0, 3, 48_000);
        var waveform = builder.finish();
        assertEquals(-0.9f, waveform.min(0));
        assertEquals(0.5f, waveform.max(0));
        assertEquals(0.9f, waveform.peak(0));
        assertEquals((0.1 + 0.2 + 0.3 + 0.9 + 0.5) / 6, waveform.meanAbsolute(0), 1e-6);
    }

    @Test
    @DisplayName("scales a measure to the loudest bucket, and silence to zeros")
    void scaled() {
        var builder = new WaveformMeter(WaveformOptions.ofBuckets(4).toBuilder()
                .channels(WaveformOptions.Channels.FIRST).build());
        builder.accept(new float[][]{{0.1f, 0.2f, 0.4f, 0.8f}}, 0, 4, 48_000);
        assertArrayEquals(new int[]{12, 25, 50, 100}, builder.finish().scaled(Waveform.Measure.PEAK, 100));
        var silent = new WaveformMeter(WaveformOptions.ofBuckets(3));
        silent.accept(new float[][]{new float[30]}, 0, 30, 48_000);
        assertArrayEquals(new int[3], silent.finish().scaled(Waveform.Measure.RMS, 100));
        assertArrayEquals(new int[5], new WaveformMeter(WaveformOptions.ofBuckets(5)).finish().scaled(Waveform.Measure.PEAK, 9));
        assertThrows(IllegalArgumentException.class, () -> WaveformOptions.ofBuckets(0));
    }

    @Test
    @DisplayName("reads a file through the audio reader, mixed down at the requested rate")
    void ofFile() {
        var file = Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                "aevalsrc=0.5*sin(2*PI*220*t)*t/3|0.2*sin(2*PI*330*t):s=16000:d=3"), List.of("-c:a", "pcm_f32le"));
        var bytes = Fixtures.read(file);
        var options = WaveformOptions.ofBuckets(64);
        var waveform = Waveform.measure(MediaSource.of(bytes), options);
        float[][] planes;
        try (var reader = AudioReader.open(MediaSource.of(bytes))) {
            var left = new float[48_000];
            var right = new float[48_000];
            var count = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                System.arraycopy(block.plane(0), 0, left, count, block.frames());
                System.arraycopy(block.plane(1), 0, right, count, block.frames());
                count += block.frames();
            }
            planes = new float[][]{Arrays.copyOf(left, count), Arrays.copyOf(right, count)};
        }
        var expected = reference(planes, 64);
        assertEquals(48_000, waveform.frames());
        assertEquals(16_000, waveform.sampleRate());
        assertArrayEquals(expected.rms(), waveform.values(Waveform.Measure.RMS), 1e-4f);
        assertArrayEquals(expected.mean(), waveform.values(Waveform.Measure.MEAN_ABSOLUTE), 1e-4f);
    }
}
