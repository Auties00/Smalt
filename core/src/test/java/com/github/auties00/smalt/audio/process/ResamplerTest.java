package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the polyphase resampler: accuracy against analytically resampled sines, stopband
 * attenuation measured with tone sweeps, streaming invariants (chunking, exact length, zero
 * delay), agreement with FFmpeg's swresample, and scalar/vector kernel equivalence. Measured
 * numbers are printed so they can be reported.
 */
class ResamplerTest {
    static Stream<Arguments> ratios() {
        return Stream.of(
                Arguments.of(44100, 16000), Arguments.of(48000, 44100), Arguments.of(8000, 48000),
                Arguments.of(22050, 16000), Arguments.of(96000, 48000), Arguments.of(44100, 48000),
                Arguments.of(16000, 48000), Arguments.of(48000, 16000), Arguments.of(11025, 16000),
                Arguments.of(35467, 16000), Arguments.of(48000, 8000), Arguments.of(16000, 44100));
    }

    /** Resamples a whole signal, feeding it in the given chunk sizes (cycled), then flushing. */
    static float[][] resample(Resampler resampler, float[][] input, int[] chunks) {
        var channels = input.length;
        var frames = input[0].length;
        var output = new float[channels][resampler.maxOutputFrames(frames) + 16];
        var written = 0;
        var consumed = 0;
        var index = 0;
        var chunk = new float[channels][];
        while (consumed < frames) {
            var count = Math.min(chunks[index++ % chunks.length], frames - consumed);
            for (var c = 0; c < channels; c++) {
                chunk[c] = Arrays.copyOfRange(input[c], consumed, consumed + count);
            }
            var bound = resampler.maxOutputFrames(count);
            var produced = resampler.process(chunk, count, output, written);
            assertTrue(produced <= bound, "process wrote more than maxOutputFrames");
            written += produced;
            consumed += count;
        }
        var bound = resampler.maxOutputFrames(0);
        var flushed = resampler.flush(output, written);
        assertTrue(flushed <= bound, "flush wrote more than maxOutputFrames(0)");
        written += flushed;
        var result = new float[channels][];
        for (var c = 0; c < channels; c++) {
            result[c] = Arrays.copyOf(output[c], written);
        }
        return result;
    }

    static float[][] resample(Resampler resampler, float[][] input) {
        return resample(resampler, input, new int[]{4096});
    }

    static float[] sine(double frequency, int rate, int frames, double amplitude) {
        var result = new float[frames];
        for (var i = 0; i < frames; i++) {
            result[i] = (float) (amplitude * Math.sin(2 * Math.PI * frequency * i / rate));
        }
        return result;
    }

    /** Returns the SNR of {@code actual} against the ideal sine over {@code [from, to)}. */
    static double sineSnr(float[] actual, double frequency, int rate, double amplitude, int from, int to) {
        double signal = 0;
        double noise = 0;
        for (var i = from; i < to; i++) {
            var ideal = amplitude * Math.sin(2 * Math.PI * frequency * i / rate);
            signal += ideal * ideal;
            noise += (ideal - actual[i]) * (ideal - actual[i]);
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }

    /** Returns the RMS of {@code data} over {@code [from, to)} after removing the best-fit sinusoid at {@code frequency}. */
    static double residualRms(float[] data, double frequency, int rate, int from, int to) {
        double ss = 0, sc = 0, cc = 0, ys = 0, yc = 0;
        for (var i = from; i < to; i++) {
            var s = Math.sin(2 * Math.PI * frequency * i / rate);
            var c = Math.cos(2 * Math.PI * frequency * i / rate);
            ss += s * s;
            sc += s * c;
            cc += c * c;
            ys += data[i] * s;
            yc += data[i] * c;
        }
        var det = ss * cc - sc * sc;
        var a = (ys * cc - yc * sc) / det;
        var b = (yc * ss - ys * sc) / det;
        double residual = 0;
        for (var i = from; i < to; i++) {
            var fit = a * Math.sin(2 * Math.PI * frequency * i / rate) + b * Math.cos(2 * Math.PI * frequency * i / rate);
            residual += (data[i] - fit) * (data[i] - fit);
        }
        return Math.sqrt(residual / (to - from));
    }

    static double rms(float[] data, int from, int to) {
        double sum = 0;
        for (var i = from; i < to; i++) {
            sum += (double) data[i] * data[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    @Nested
    @DisplayName("accuracy")
    class Accuracy {
        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("reproduces a 1 kHz sine with SNR >= 80 dB at STANDARD quality")
        void sine1k(int in, int out) {
            var input = sine(1000, in, in, 0.5);
            var report = new StringBuilder("sine 1 kHz " + in + " -> " + out + ":");
            for (var quality : Resampler.Quality.values()) {
                var resampler = new Resampler(in, out, 1, quality);
                var output = resample(resampler, new float[][]{input})[0];
                // skip the transient caused by the signal starting and stopping abruptly
                var edge = 2 * resampler.delay() + 8;
                var snr = sineSnr(output, 1000, out, 0.5, edge, output.length - edge);
                report.append(String.format(" %s=%.1f dB", quality, snr));
                if (quality == Resampler.Quality.STANDARD) {
                    assertTrue(snr >= 80, "STANDARD SNR " + snr + " dB");
                }
                if (quality == Resampler.Quality.HIGH) {
                    assertTrue(snr >= 90, "HIGH SNR " + snr + " dB");
                }
            }
            System.out.println(report);
        }

        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("keeps a passband tone at 30% of the lower rate within 0.01 dB")
        void passband(int in, int out) {
            var frequency = 0.3 * Math.min(in, out);
            var input = sine(frequency, in, in, 0.5);
            var resampler = new Resampler(in, out, 1, Resampler.Quality.STANDARD);
            var output = resample(resampler, new float[][]{input})[0];
            var edge = 2 * resampler.delay() + 8;
            var gain = 20 * Math.log10(rms(output, edge, output.length - edge) / (0.5 / Math.sqrt(2)));
            assertEquals(0, gain, 0.01, "passband gain at " + frequency + " Hz");
        }

        @Test
        @DisplayName("is the identity for equal rates")
        void identity() {
            var random = new SplittableRandom(1);
            var input = new float[1000];
            for (var i = 0; i < input.length; i++) {
                input[i] = (float) random.nextDouble(-1, 1);
            }
            var output = resample(new Resampler(48000, 48000, 1, Resampler.Quality.STANDARD), new float[][]{input})[0];
            assertEquals(input.length, output.length);
            for (var i = 0; i < input.length; i++) {
                assertEquals(input[i], output[i], 1e-6f);
            }
        }
    }

    @Nested
    @DisplayName("stopband")
    class Stopband {
        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @CsvSource({"48000, 16000", "44100, 16000", "96000, 48000", "44100, 22050", "16000, 48000", "8000, 44100", "22050, 44100"})
        @DisplayName("attenuates aliases and images by the designed amount")
        void sweep(int in, int out) {
            var report = new StringBuilder("stopband " + in + " -> " + out + ":");
            for (var quality : Resampler.Quality.values()) {
                var low = Math.min(in, out);
                var taps = quality.taps();
                var attenuation = quality.beta() / 0.1102 + 8.7;
                var transition = (attenuation - 8) / (2.285 * 2 * Math.PI * (taps - 1));
                var stopEdge = (0.485 + transition / 2) * low;
                var worst = Double.NEGATIVE_INFINITY;
                var worstFrequency = 0.0;
                var frames = in / 2;
                if (in > out) {
                    // downsampling: tones above the stop edge must vanish from the output
                    for (var k = 0; k < 48; k++) {
                        var frequency = stopEdge + (in / 2.0 - stopEdge) * k / 48.0;
                        var output = resample(new Resampler(in, out, 1, quality), new float[][]{sine(frequency, in, frames, 0.5)})[0];
                        var edge = 2 * new Resampler(in, out, 1, quality).delay() + 8;
                        var level = 20 * Math.log10(rms(output, edge, output.length - edge) / (0.5 / Math.sqrt(2)));
                        if (level > worst) {
                            worst = level;
                            worstFrequency = frequency;
                        }
                    }
                } else {
                    // upsampling: images of tones below (inputRate - stop edge) must vanish
                    var top = in - stopEdge;
                    for (var k = 1; k <= 48; k++) {
                        var frequency = top * k / 48.0;
                        var output = resample(new Resampler(in, out, 1, quality), new float[][]{sine(frequency, in, frames, 0.5)})[0];
                        var edge = 2 * new Resampler(in, out, 1, quality).delay() + 8;
                        var level = 20 * Math.log10(residualRms(output, frequency, out, edge, output.length - edge) / (0.5 / Math.sqrt(2)));
                        if (level > worst) {
                            worst = level;
                            worstFrequency = frequency;
                        }
                    }
                }
                report.append(String.format(" %s: stop edge %.0f Hz, worst %.1f dB at %.0f Hz;", quality, stopEdge, worst, worstFrequency));
                var required = switch (quality) {
                    case FAST -> -70;
                    case STANDARD -> -90;
                    case HIGH -> -105;
                };
                assertTrue(worst <= required, quality + " stopband " + worst + " dB");
            }
            System.out.println(report);
        }
    }

    @Nested
    @DisplayName("streaming")
    class Streaming {
        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("produces bit-identical output whatever the chunk sizes")
        void chunking(int in, int out) {
            var random = new SplittableRandom(in * 31L + out);
            var frames = 20_000;
            var input = new float[2][frames];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < frames; i++) {
                    input[c][i] = (float) random.nextDouble(-1, 1);
                }
            }
            var reference = resample(new Resampler(in, out, 2, Resampler.Quality.STANDARD), input, new int[]{frames});
            var chunks = new int[64];
            for (var i = 0; i < chunks.length; i++) {
                chunks[i] = random.nextInt(0, i % 7 == 0 ? 9000 : 300);
            }
            chunks[0] = Math.max(1, chunks[0]);
            var chunked = resample(new Resampler(in, out, 2, Resampler.Quality.STANDARD), input, chunks);
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(reference[c], chunked[c], "channel " + c);
            }
            var single = resample(new Resampler(in, out, 2, Resampler.Quality.STANDARD), input, new int[]{1});
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(reference[c], single[c], "channel " + c + " fed one frame at a time");
            }
        }

        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("outputs exactly round(frames * out / in) frames")
        void exactLength(int in, int out) {
            var random = new SplittableRandom(7);
            var lengths = new ArrayList<>(List.of(0, 1, 2, 3, 5, 17, 159, 160, 441, 1000, 4095, 4096, 4097));
            for (var i = 0; i < 20; i++) {
                lengths.add(random.nextInt(1, 50_000));
            }
            var resampler = new Resampler(in, out, 1, Resampler.Quality.FAST);
            for (int length : lengths) {
                var output = resample(resampler, new float[][]{new float[length]}, new int[]{777, 1, 3000});
                var expected = (2L * length * out + in) / (2L * in);
                assertEquals(expected, output[0].length, "length for " + length + " input frames");
            }
        }

        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("has zero net delay: an impulse at N peaks at output N * out / in")
        void impulse(int in, int out) {
            for (var n : new int[]{0, 1, 7, 100, 999, 4096, 12345}) {
                var input = new float[n + 3 * in / 100 + 200];
                input[n] = 1;
                var output = resample(new Resampler(in, out, 1, Resampler.Quality.STANDARD), new float[][]{input})[0];
                var peak = 0;
                for (var i = 1; i < output.length; i++) {
                    if (output[i] > output[peak]) {
                        peak = i;
                    }
                }
                var ideal = (double) n * out / in;
                assertTrue(Math.abs(peak - ideal) <= 0.5 + 1e-9,
                        "impulse at " + n + " peaks at " + peak + ", ideal " + ideal);
                if (Math.abs(ideal - Math.rint(ideal)) != 0.5) {
                    assertEquals(Math.round(ideal), peak, "impulse at " + n);
                }
            }
        }

        @Test
        @DisplayName("resets after flush and reproduces the same stream")
        void reuse() {
            var input = new float[][]{sine(440, 44100, 5000, 0.8)};
            var resampler = new Resampler(44100, 16000, 1, Resampler.Quality.STANDARD);
            var first = resample(resampler, input, new int[]{123});
            var second = resample(resampler, input, new int[]{4000, 7});
            assertArrayEquals(first[0], second[0]);
        }

        @Test
        @DisplayName("reports the look-ahead in output frames")
        void delay() {
            assertEquals(48, new Resampler(16000, 48000, 1, Resampler.Quality.STANDARD).delay());
            assertEquals(16, new Resampler(48000, 16000, 1, Resampler.Quality.STANDARD).delay());
            assertEquals(0, new Resampler(48000, 48000, 1, Resampler.Quality.FAST).delay());
        }
    }

    @Nested
    @DisplayName("FFmpeg swresample")
    class Swresample {
        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @CsvSource({"44100, 16000", "48000, 44100", "8000, 48000", "22050, 16000", "96000, 48000", "44100, 48000", "16000, 48000"})
        @DisplayName("agrees with aresample filter_size=32 cutoff=0.97 at SNR >= 60 dB")
        void compare(int in, int out) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var low = Math.min(in, out);
            var frames = in * 2;
            var input = new float[frames];
            var frequencies = new double[]{0.02 * low, 0.071 * low, 0.19 * low, 0.33 * low};
            for (var i = 0; i < frames; i++) {
                // raised-cosine fade-in so neither resampler sees an abrupt start
                var fade = i < in / 10 ? 0.5 - 0.5 * Math.cos(Math.PI * i / (in / 10.0)) : 1.0;
                var value = 0.0;
                for (var f : frequencies) {
                    value += 0.2 * Math.sin(2 * Math.PI * f * i / in + f);
                }
                input[i] = (float) (fade * value);
            }
            var raw = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            raw.asFloatBuffer().put(input);
            var produced = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), raw.array(), List.of("-v", "error",
                    "-f", "f32le", "-ar", Integer.toString(in), "-ac", "1", "-i", "pipe:0",
                    "-af", "aresample=" + out + ":filter_size=32:cutoff=0.97", "-f", "f32le", "pipe:1"));
            var reference = new float[produced.length / 4];
            ByteBuffer.wrap(produced).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(reference);
            var ours = resample(new Resampler(in, out, 1, Resampler.Quality.STANDARD), new float[][]{input})[0];
            var common = Math.min(ours.length, reference.length);
            var edge = out / 50;
            var bestLag = 0;
            var best = Double.NEGATIVE_INFINITY;
            for (var lag = -8; lag <= 8; lag++) {
                var snr = Quality.snr(Arrays.copyOfRange(reference, edge, common - edge),
                        Arrays.copyOfRange(ours, edge + lag, common - edge + lag));
                if (snr > best) {
                    best = snr;
                    bestLag = lag;
                }
            }
            var aligned = Quality.snr(Arrays.copyOfRange(reference, edge, common - edge), Arrays.copyOfRange(ours, edge, common - edge));
            System.out.printf("swresample %d -> %d: ours %d frames, ffmpeg %d frames, SNR %.1f dB at lag 0 (best lag %d: %.1f dB)%n",
                    in, out, ours.length, reference.length, aligned, bestLag, best);
            assertTrue(aligned >= 60, "SNR against swresample " + aligned + " dB");
        }
    }

    @Nested
    @DisplayName("kernels")
    class Kernels {
        /** The arithmetic FirKernel defines, written out independently. */
        static float reference(float[] x, int xo, float[] h, int ho, int taps) {
            var chains = new float[8];
            for (var k = 0; k < taps; k++) {
                chains[k % 8] = Math.fma(x[xo + k], h[ho + k], chains[k % 8]);
            }
            return ((chains[0] + chains[1]) + (chains[2] + chains[3])) + ((chains[4] + chains[5]) + (chains[6] + chains[7]));
        }

        @Test
        @DisplayName("scalar and vector dot products match the defined arithmetic bit for bit")
        void dot() {
            var scalar = new ScalarFirKernel();
            var vector = VectorProcessKernels.fir();
            var random = new SplittableRandom(3);
            for (var trial = 0; trial < 3000; trial++) {
                var taps = 8 * random.nextInt(0, 40);
                var x = new float[taps + 40];
                var h = new float[taps + 40];
                for (var i = 0; i < x.length; i++) {
                    x[i] = (float) random.nextDouble(-1, 1);
                    h[i] = (float) random.nextDouble(-1, 1);
                }
                var xo = random.nextInt(0, 40);
                var ho = random.nextInt(0, 40);
                var expected = reference(x, xo, h, ho, taps);
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(scalar.dot(x, xo, h, ho, taps)), "scalar, taps " + taps);
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(vector.dot(x, xo, h, ho, taps)), "vector, taps " + taps);
            }
        }

        @Test
        @DisplayName("scalar and vector phase runs are bit-identical")
        void run() {
            var scalar = new ScalarFirKernel();
            var vector = VectorProcessKernels.fir();
            var random = new SplittableRandom(4);
            for (var trial = 0; trial < 300; trial++) {
                var up = random.nextInt(1, 50);
                var down = random.nextInt(1, 50);
                var taps = 8 * random.nextInt(1, 12);
                var count = random.nextInt(0, 40);
                var x = new float[count * (down / up + 1) + taps + 10];
                for (var i = 0; i < x.length; i++) {
                    x[i] = (float) random.nextDouble(-1, 1);
                }
                var table = new float[up * taps];
                var slopes = new float[up * taps];
                for (var i = 0; i < table.length; i++) {
                    table[i] = (float) random.nextDouble(-1, 1);
                    slopes[i] = (float) random.nextDouble(-0.01, 0.01);
                }
                var phase = random.nextInt(0, up);
                var a = new float[count + 3];
                var b = new float[count + 3];
                scalar.run(x, 1, phase, down / up, down % up, up, table, taps, a, 3, count);
                vector.run(x, 1, phase, down / up, down % up, up, table, taps, b, 3, count);
                assertArrayEquals(a, b, "trial " + trial);
                var phases = random.nextInt(1, up + 1);
                scalar.runInterpolated(x, 1, phase, down / up, down % up, up, phases, table, slopes, taps, a, 3, count);
                vector.runInterpolated(x, 1, phase, down / up, down % up, up, phases, table, slopes, taps, b, 3, count);
                assertArrayEquals(a, b, "interpolated trial " + trial);
            }
        }

        @ParameterizedTest(name = "{0} Hz -> {1} Hz")
        @MethodSource("com.github.auties00.smalt.audio.process.ResamplerTest#ratios")
        @DisplayName("scalar and vector resamplers are bit-identical")
        void resamplers(int in, int out) {
            var random = new SplittableRandom(5);
            var input = new float[2][30_000];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < input[c].length; i++) {
                    input[c][i] = (float) random.nextDouble(-1, 1);
                }
            }
            for (var quality : Resampler.Quality.values()) {
                var scalar = resample(new Resampler(in, out, 2, quality, new ScalarFirKernel()), input, new int[]{1000, 3});
                var vector = resample(new Resampler(in, out, 2, quality, VectorProcessKernels.fir()), input, new int[]{4096});
                for (var c = 0; c < 2; c++) {
                    assertArrayEquals(scalar[c], vector[c], quality + " channel " + c);
                }
            }
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        @Test
        @DisplayName("rejects invalid rates, ratios and channel counts")
        void arguments() {
            assertThrows(IllegalArgumentException.class, () -> new Resampler(0, 48000, 1, Resampler.Quality.FAST));
            assertThrows(IllegalArgumentException.class, () -> new Resampler(48000, -1, 1, Resampler.Quality.FAST));
            assertThrows(IllegalArgumentException.class, () -> new Resampler(100, 100_000, 1, Resampler.Quality.FAST));
            assertThrows(IllegalArgumentException.class, () -> new Resampler(1 << 25, 1 << 24, 1, Resampler.Quality.FAST));
            assertThrows(IllegalArgumentException.class, () -> new Resampler(48000, 44100, 0, Resampler.Quality.FAST));
            assertThrows(NullPointerException.class, () -> new Resampler(48000, 44100, 1, null));
        }

        @Test
        @DisplayName("rejects mismatched planes and short output")
        void planes() {
            var resampler = new Resampler(8000, 48000, 2, Resampler.Quality.FAST);
            assertThrows(IllegalArgumentException.class, () -> resampler.process(new float[1][100], 100, new float[2][1000], 0));
            assertThrows(IllegalArgumentException.class, () -> resampler.process(new float[2][10], 100, new float[2][1000], 0));
            assertThrows(IllegalArgumentException.class, () -> resampler.process(new float[2][1000], 1000, new float[2][10], 0));
            assertThrows(IllegalArgumentException.class, () -> resampler.maxOutputFrames(-1));
        }

        @Test
        @DisplayName("bounds the phase table for rates with no small common divisor")
        void interpolatedTable() {
            // 35467 and 16001 are coprime: 16001 exact phases would be needed
            var resampler = new Resampler(35467, 16001, 1, Resampler.Quality.HIGH);
            var output = resample(resampler, new float[][]{sine(1000, 35467, 35467, 0.5)})[0];
            var edge = 2 * resampler.delay() + 8;
            assertTrue(sineSnr(output, 1000, 16001, 0.5, edge, output.length - edge) >= 90);
        }
    }
}
