package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the true-peak limiter: the true peak of its output, measured by {@link LoudnessMeter} as
 * BS.1770-4 defines it, stays at or below the ceiling on sines, square waves, noise, impulses and the
 * inter-sample peak signals of EBU Tech 3341; quiet input passes unchanged; the output has the input's
 * length and timing whatever the call split; the gain recovers after a peak.
 */
@DisplayName("TruePeakLimiter")
class TruePeakLimiterTest {
    private static final int RATE = 48_000;

    /** Limits a whole signal, feeding it in the given chunk sizes (cycled), then flushing. */
    static float[][] limit(TruePeakLimiter limiter, float[][] input, int... chunks) {
        var channels = input.length;
        var frames = input[0].length;
        var output = new float[channels][frames + limiter.delay()];
        var written = 0;
        var consumed = 0;
        var index = 0;
        while (consumed < frames) {
            var count = Math.min(chunks[index++ % chunks.length], frames - consumed);
            var produced = limiter.process(input, consumed, count, output, written);
            assertTrue(produced <= count);
            written += produced;
            consumed += count;
        }
        written += limiter.flush(output, written);
        assertEquals(frames, written, "the stream keeps its length");
        var result = new float[channels][];
        for (var c = 0; c < channels; c++) {
            result[c] = Arrays.copyOf(output[c], frames);
        }
        return result;
    }

    private static double truePeakDb(float[][] planes, int rate) {
        var layout = planes.length == 2 ? ChannelLayout.STEREO : ChannelLayout.defaultFor(planes.length);
        return 20 * Math.log10(Signals.measure(planes, rate, layout).truePeak());
    }

    private static float[][] sine(double frequency, double amplitude, int frames, double phase) {
        var plane = new float[frames];
        for (var i = 0; i < frames; i++) {
            plane[i] = (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / RATE + phase));
        }
        return new float[][]{plane, plane.clone()};
    }

    static Stream<Arguments> loudSignals() {
        var random = new SplittableRandom(9);
        var noise = new float[2][RATE * 2];
        for (var plane : noise) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (float) (random.nextGaussian() * 0.6);
            }
        }
        var square = new float[2][RATE];
        for (var i = 0; i < RATE; i++) {
            square[0][i] = (i / 24) % 2 == 0 ? 1f : -1f;
            square[1][i] = -square[0][i];
        }
        var impulses = new float[2][RATE];
        for (var i = 3000; i < RATE; i += 4801) {
            impulses[0][i] = 1f;
            impulses[1][i + 1] = -1f;
        }
        var bursts = new float[2][RATE * 2];
        for (var i = 0; i < bursts[0].length; i++) {
            var envelope = (i / 2400) % 5 == 0 ? 2.0 : 0.05;
            bursts[0][i] = (float) (envelope * Math.sin(2 * Math.PI * 440 * i / RATE));
            bursts[1][i] = (float) (envelope * Math.sin(2 * Math.PI * 660 * i / RATE));
        }
        return Stream.of(
                Arguments.of("+6 dBFS 1 kHz sine", sine(1000, 2, RATE, 0)),
                Arguments.of("0 dBFS fs/4 sine at 45 degrees (Tech 3341 case 19)",
                        Signals.truePeakSine(RATE, RATE / 4.0, 1.0, 45)),
                Arguments.of("0 dBFS fs/6 sine at 60 degrees (Tech 3341 case 16)",
                        Signals.truePeakSine(RATE, RATE / 6.0, 1.0, 60)),
                Arguments.of("Tech 3341 inter-sample burst, offset 0", Signals.truePeakBurst(RATE, 0)),
                Arguments.of("Tech 3341 inter-sample burst, offset 3", Signals.truePeakBurst(RATE, 3)),
                Arguments.of("Gaussian noise at -4.4 dBFS RMS", noise),
                Arguments.of("full-scale square wave", square),
                Arguments.of("isolated full-scale impulses", impulses),
                Arguments.of("+6 dBFS tone bursts over a quiet bed", bursts));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loudSignals")
    @DisplayName("keeps the true peak at or below the ceiling")
    void ceiling(String name, float[][] signal) {
        for (var ceiling : new double[]{-1, -3, -0.1}) {
            var limiter = new TruePeakLimiter(2, RATE, TruePeakLimiterOptions.of(ceiling));
            var output = limit(limiter, signal, 4096);
            var peak = truePeakDb(output, RATE);
            assertTrue(peak <= ceiling + 1e-4, name + ": true peak " + peak + " dBTP over " + ceiling);
            assertTrue(truePeakDb(signal, RATE) <= ceiling || limiter.maxGainReductionDb() > 0);
        }
    }

    @ParameterizedTest(name = "{0} Hz")
    @MethodSource("rates")
    @DisplayName("keeps the ceiling at every oversampling factor")
    void rates(int rate) {
        var frames = rate;
        var plane = new float[frames];
        for (var i = 0; i < frames; i++) {
            plane[i] = (float) (1.5 * Math.sin(2 * Math.PI * rate / 4.1 * i / rate + 0.7));
        }
        var signal = new float[][]{plane};
        var output = limit(new TruePeakLimiter(1, rate, TruePeakLimiterOptions.defaults()), signal, 1000);
        assertTrue(truePeakDb(output, rate) <= -1 + 1e-4, "true peak " + truePeakDb(output, rate));
    }

    static Stream<Integer> rates() {
        return Stream.of(8000, 44_100, 96_000, 192_000);
    }

    @Test
    @DisplayName("passes a signal under the ceiling through unchanged, sample for sample")
    void transparent() {
        var signal = sine(1000, 0.5, RATE, 0.3);
        var output = limit(new TruePeakLimiter(2, RATE, TruePeakLimiterOptions.defaults()), signal, 333);
        assertArrayEquals(signal[0], output[0]);
        assertArrayEquals(signal[1], output[1]);
    }

    @Test
    @DisplayName("has zero net delay and leaves samples far from a peak unchanged")
    void timing() {
        var signal = sine(500, 0.25, RATE, 0);
        // one loud cycle in the middle
        for (var i = 24_000; i < 24_096; i++) {
            signal[0][i] *= 8;
            signal[1][i] *= 8;
        }
        var options = TruePeakLimiterOptions.builder().lookaheadMillis(2).releaseMillis(5).build();
        var output = limit(new TruePeakLimiter(2, RATE, options), signal, 4096);
        for (var i = 0; i < 23_700; i++) {
            assertEquals(signal[0][i], output[0][i], "before the peak, sample " + i);
        }
        // 40 release time constants after the peak the gain is exactly one again
        for (var i = 34_000; i < RATE; i++) {
            assertEquals(signal[0][i], output[0][i], "after release, sample " + i);
        }
        assertNotEquals(signal[0][24_200], output[0][24_200], "still releasing");
        assertTrue(truePeakDb(output, RATE) <= -1 + 1e-4);
    }

    @Test
    @DisplayName("is bit-identical however the input is split and resets after flush")
    void chunking() {
        var signal = (float[][]) loudSignals().filter(arguments -> arguments.get()[0].toString().contains("noise"))
                .findFirst().orElseThrow().get()[1];
        var limiter = new TruePeakLimiter(2, RATE, TruePeakLimiterOptions.defaults());
        var whole = limit(limiter, signal, signal[0].length);
        var split = limit(limiter, signal, 1, 999, 4096, 7);
        assertArrayEquals(whole[0], split[0]);
        assertArrayEquals(whole[1], split[1]);
        assertTrue(limiter.maxGainReductionDb() > 0);
    }

    @Test
    @DisplayName("treats NaN as silence and links the gain of every channel")
    void linkedAndNaN() {
        var signal = new float[][]{sine(1000, 2, RATE, 0)[0], new float[RATE]};
        Arrays.fill(signal[1], 0.1f);
        signal[1][100] = Float.NaN;
        var output = limit(new TruePeakLimiter(2, RATE, TruePeakLimiterOptions.defaults()), signal, 4096);
        assertEquals(0f, output[1][100]);
        // the quiet channel is turned down with the loud one
        assertTrue(output[1][RATE / 2] < 0.1f * 0.6f, "linked gain");
    }

    @Test
    @DisplayName("rejects out-of-range settings, channel counts, rates and buffers")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> TruePeakLimiterOptions.of(0.5));
        assertThrows(IllegalArgumentException.class, () -> TruePeakLimiterOptions.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TruePeakLimiterOptions.builder().lookaheadMillis(0.5).build());
        assertThrows(IllegalArgumentException.class, () -> TruePeakLimiterOptions.builder().releaseMillis(20_000).build());
        var options = TruePeakLimiterOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> new TruePeakLimiter(0, RATE, options));
        assertThrows(IllegalArgumentException.class, () -> new TruePeakLimiter(2, 4000, options));
        var limiter = new TruePeakLimiter(2, RATE, options);
        assertThrows(IllegalArgumentException.class, () -> limiter.process(new float[1][10], 0, 10, new float[2][10], 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.process(new float[2][10], 5, 10, new float[2][10], 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.process(new float[2][10], 0, 10, new float[2][5], 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.flush(new float[1][10], 0));
        assertEquals(options, options.toBuilder().build());
        assertEquals(List.of(-1.0, 5.0, 100.0),
                List.of(options.ceilingDb(), options.lookaheadMillis(), options.releaseMillis()));
    }
}
