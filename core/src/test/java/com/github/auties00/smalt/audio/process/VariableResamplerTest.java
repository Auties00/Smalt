package com.github.auties00.smalt.audio.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the variable-ratio mode of {@link Resampler}: output positions against the analytic sine
 * evaluated at the fixed-point positions the ratio schedule implies, swept ratios, switches from the
 * exact and pass-through modes in mid-stream, stream length, chunking invariance and kernel equivalence.
 */
@DisplayName("Resampler variable ratio")
class VariableResamplerTest {
    private static final double FREQUENCY = 997;
    private static final double AMPLITUDE = 0.5;

    /**
     * The result of a run: the output samples of channel 0 and the input position of each output frame.
     */
    record Run(float[] output, double[] positions) {
    }

    /**
     * Resamples a sine fed in chunks of {@code chunk} frames; before chunk {@code k} the ratio becomes
     * {@code schedule(k)} (NaN keeps it), and the positions follow the fixed-point steps the resampler uses.
     */
    static Run run(Resampler resampler, int inputRate, int frames, int chunk, IntToDoubleFunction schedule) {
        var input = new float[][]{ResamplerTest.sine(FREQUENCY, inputRate, frames, AMPLITUDE)};
        var output = new float[1][(int) (frames * 2.2 * resampler.outputRate() / resampler.inputRate()) + 4096];
        var positions = new double[output[0].length];
        var written = 0;
        var nominalUp = resampler.outputRate();
        var nominalDown = resampler.inputRate();
        var variable = false;
        long whole = 0;
        long fraction = 0;
        long step = 0;
        for (var k = 0; k * chunk < frames; k++) {
            var ratio = schedule.applyAsDouble(k);
            if (!Double.isNaN(ratio)) {
                resampler.setRatio(ratio);
                if (!variable && ratio != (double) nominalUp / nominalDown) {
                    variable = true;
                    whole = Math.floorDiv((long) written * nominalDown, nominalUp);
                    var remainder = (long) written * nominalDown % nominalUp;
                    fraction = (remainder << 32) / nominalUp;
                }
                if (variable) {
                    step = Math.round(0x1p32 / ratio);
                }
            }
            var count = Math.min(chunk, frames - k * chunk);
            var slice = new float[][]{Arrays.copyOfRange(input[0], k * chunk, k * chunk + count)};
            var produced = resampler.process(slice, count, output, written);
            for (var n = written; n < written + produced; n++) {
                if (variable) {
                    positions[n] = whole + fraction * 0x1p-32;
                    fraction += step;
                    whole += fraction >>> 32;
                    fraction &= 0xFFFF_FFFFL;
                } else {
                    positions[n] = (double) n * nominalDown / nominalUp;
                }
            }
            written += produced;
        }
        var flushed = resampler.flush(output, written);
        for (var n = written; n < written + flushed; n++) {
            if (variable) {
                positions[n] = whole + fraction * 0x1p-32;
                fraction += step;
                whole += fraction >>> 32;
                fraction &= 0xFFFF_FFFFL;
            } else {
                positions[n] = (double) n * nominalDown / nominalUp;
            }
        }
        written += flushed;
        return new Run(Arrays.copyOf(output[0], written), Arrays.copyOf(positions, written));
    }

    /**
     * Returns the SNR of a run against the sine evaluated at its positions, over the frames whose
     * filter lies wholly inside the input.
     */
    static double snr(Run run, int inputRate, int inputFrames, int margin) {
        double signal = 0;
        double noise = 0;
        for (var n = 0; n < run.output().length; n++) {
            var position = run.positions()[n];
            if (position < margin || position > inputFrames - margin) {
                continue;
            }
            var ideal = AMPLITUDE * Math.sin(2 * Math.PI * FREQUENCY * position / inputRate);
            signal += ideal * ideal;
            noise += (ideal - run.output()[n]) * (ideal - run.output()[n]);
        }
        return 10 * Math.log10(signal / noise);
    }

    @ParameterizedTest(name = "{0} Hz -> {1} Hz x {2}")
    @CsvSource({"48000, 48000, 1.001", "48000, 48000, 0.997", "44100, 48000, 1.0005", "48000, 16000, 0.9995",
            "8000, 48000, 1.002"})
    @DisplayName("a constant ratio set before the input places every frame at n / ratio, SNR >= 80 dB")
    void constant(int inputRate, int outputRate, double factor) {
        var frames = inputRate * 2;
        var ratio = (double) outputRate / inputRate * factor;
        var run = run(new Resampler(inputRate, outputRate, 1, Resampler.Quality.STANDARD), inputRate, frames, 4096,
                k -> k == 0 ? ratio : Double.NaN);
        var snr = snr(run, inputRate, frames, 200);
        assertTrue(snr >= 80, "SNR " + snr);
        // the last frame lies at least half a spacing before the end of the input
        var count = run.output().length;
        assertTrue(run.positions()[count - 1] + 0.5 / ratio <= frames + 1e-9);
        assertTrue(run.positions()[count - 1] + 1.5 / ratio > frames - 1e-9);
        assertEquals(Math.round(frames * ratio), count, 1);
    }

    @Test
    @DisplayName("follows a ratio that drifts every chunk without accumulating error, SNR >= 80 dB")
    void sweep() {
        var rate = 48_000;
        var frames = rate * 4;
        var run = run(new Resampler(rate, 44_100, 1, Resampler.Quality.STANDARD), rate, frames, 480,
                k -> 44_100.0 / 48_000 * (1 + 0.003 * Math.sin(2 * Math.PI * k / 37.0)));
        var snr = snr(run, rate, frames, 200);
        assertTrue(snr >= 80, "SNR " + snr);
        // the drift averages out: the output length stays within the mean ratio's
        assertEquals(frames * 44_100.0 / 48_000, run.output().length, frames * 0.003);
    }

    @Test
    @DisplayName("switches from pass-through in mid-stream without a discontinuity")
    void fromPassThrough() {
        var rate = 48_000;
        var frames = rate * 2;
        var run = run(new Resampler(rate, rate, 1, Resampler.Quality.STANDARD), rate, frames, 1000,
                k -> k == 40 ? 0.999 : Double.NaN);
        var input = ResamplerTest.sine(FREQUENCY, rate, frames, AMPLITUDE);
        assertArrayEquals(Arrays.copyOf(input, 40_000), Arrays.copyOf(run.output(), 40_000), "copied before the switch");
        var snr = snr(run, rate, frames, 200);
        assertTrue(snr >= 80, "SNR " + snr);
    }

    @Test
    @DisplayName("switches from the exact mode in mid-stream without a discontinuity")
    void fromExact() {
        var rate = 44_100;
        var frames = rate * 2;
        var nominal = 48_000.0 / 44_100;
        var run = run(new Resampler(rate, 48_000, 1, Resampler.Quality.HIGH), rate, frames, 3000,
                k -> k == 13 ? nominal * 1.002 : Double.NaN);
        var snr = snr(run, rate, frames, 200);
        assertTrue(snr >= 80, "SNR " + snr);
    }

    @Test
    @DisplayName("is bit-identical however the input is split, and the vector kernel matches the scalar one")
    void chunking() {
        var random = new SplittableRandom(11);
        var input = new float[2][50_000];
        for (var plane : input) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (float) random.nextDouble(-1, 1);
            }
        }
        var expected = variableRun(new Resampler(44_100, 48_000, 2, Resampler.Quality.STANDARD, new ScalarFirKernel()),
                input, new int[]{4096});
        var split = variableRun(new Resampler(44_100, 48_000, 2, Resampler.Quality.STANDARD, VectorProcessKernels.fir()),
                input, new int[]{1000, 3, 777, 1});
        for (var c = 0; c < 2; c++) {
            assertArrayEquals(expected[c], split[c], "channel " + c);
        }
    }

    private static float[][] variableRun(Resampler resampler, float[][] input, int[] chunks) {
        resampler.setRatio(48_000.0 / 44_100 * 0.9993);
        return ResamplerTest.resample(resampler, input, chunks);
    }

    @Test
    @DisplayName("keeps the exact mode for the nominal ratio and returns to it after flush")
    void nominal() {
        var input = new float[][]{ResamplerTest.sine(FREQUENCY, 44_100, 20_000, AMPLITUDE)};
        var plain = ResamplerTest.resample(new Resampler(44_100, 48_000, 1, Resampler.Quality.STANDARD), input);
        var resampler = new Resampler(44_100, 48_000, 1, Resampler.Quality.STANDARD);
        resampler.setRatio(48_000.0 / 44_100);
        assertArrayEquals(plain[0], ResamplerTest.resample(resampler, input)[0]);
        resampler.setRatio(1.1);
        assertEquals(1.1, resampler.ratio());
        // STANDARD from 44.1 kHz up has 32 taps, a look-ahead of 16 input frames
        assertEquals((int) Math.ceil(16 * 1.1), resampler.delay());
        ResamplerTest.resample(resampler, input);
        assertEquals(48_000.0 / 44_100, resampler.ratio());
        assertArrayEquals(plain[0], ResamplerTest.resample(resampler, input)[0], "flush returned to the exact mode");
    }

    @ParameterizedTest(name = "{0} Hz -> {1} Hz")
    @CsvSource({"48000, 48000", "8000, 2048000", "2048000, 8000", "44100, 16000", "11025, 96000"})
    @DisplayName("ends every stream at the last frame half a spacing before the input's end, at the ratio limits")
    void randomSchedules(int inputRate, int outputRate) {
        var random = new SplittableRandom(inputRate ^ outputRate);
        var nominal = (double) outputRate / inputRate;
        for (var trial = 0; trial < 6; trial++) {
            var frames = 1 + random.nextInt(Math.max(2, inputRate / 4));
            var chunk = 1 + random.nextInt(5000);
            var run = run(new Resampler(inputRate, outputRate, 1, Resampler.Quality.FAST), inputRate, frames, chunk,
                    k -> random.nextInt(3) == 0 ? Double.NaN
                            : nominal * (random.nextBoolean() ? random.nextDouble(0.5, 2.0) : random.nextBoolean() ? 0.5 : 2.0));
            var count = run.output().length;
            for (var sample : run.output()) {
                assertTrue(Float.isFinite(sample) && Math.abs(sample) < 2, "sample " + sample);
            }
            if (count > 0) {
                assertTrue(run.positions()[count - 1] <= frames, "last position " + run.positions()[count - 1]);
            }
        }
    }

    @Test
    @DisplayName("rejects ratios that are not finite or more than a factor of two from nominal")
    void validation() {
        var resampler = new Resampler(48_000, 24_000, 1, Resampler.Quality.FAST);
        assertThrows(IllegalArgumentException.class, () -> resampler.setRatio(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> resampler.setRatio(0));
        assertThrows(IllegalArgumentException.class, () -> resampler.setRatio(0.24));
        assertThrows(IllegalArgumentException.class, () -> resampler.setRatio(1.01));
        assertThrows(IllegalArgumentException.class, () -> resampler.setRatio(Double.POSITIVE_INFINITY));
        resampler.setRatio(0.25);
        resampler.setRatio(1.0);
        assertEquals(1.0, resampler.ratio());
    }
}
