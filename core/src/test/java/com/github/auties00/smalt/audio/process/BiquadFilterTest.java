package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the Audio EQ Cookbook sections against their closed-form responses, the filter against a direct-form
 * recurrence and against sines measured after it, its independence from block splitting, and, as a black box, its
 * agreement with FFmpeg's biquad filters, which implement the same cookbook formulas.
 */
@DisplayName("Biquad filters")
class BiquadFilterTest {
    static final int RATE = 48_000;

    static float[] noise(int frames, long seed) {
        var random = new SplittableRandom(seed);
        var samples = new float[frames];
        for (var index = 0; index < frames; index++) {
            samples[index] = (float) (random.nextDouble() * 1.6 - 0.8);
        }
        return samples;
    }

    /**
     * The direct form I recurrence of the cookbook, in double precision.
     */
    static float[] reference(Biquad section, float[] input) {
        var c = section.coefficients(RATE);
        var output = new float[input.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (var index = 0; index < input.length; index++) {
            double x = input[index];
            var y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            output[index] = (float) y;
        }
        return output;
    }

    static Stream<Biquad> sections() {
        return Stream.of(Biquad.lowPass(1_000), Biquad.highPass(250, 1.2), Biquad.bandPass(3_000, 2),
                Biquad.notch(60, 10), Biquad.allPass(2_000, 0.7), Biquad.peaking(1_000, 1.4, 6),
                Biquad.peaking(8_000, 0.5, -12), Biquad.lowShelf(200, 9), Biquad.highShelf(6_000, -6));
    }

    @Nested
    @DisplayName("closed-form responses")
    class Responses {
        @Test
        @DisplayName("a Butterworth low-pass and high-pass fall 3 dB at the corner and pass the other end")
        void corners() {
            var low = Biquad.lowPass(1_000);
            assertEquals(1 / Math.sqrt(2), low.magnitude(RATE, 1_000), 1e-9);
            assertEquals(1, low.magnitude(RATE, 0), 1e-12);
            assertEquals(0, low.magnitude(RATE, RATE / 2.0), 1e-9);
            var high = Biquad.highPass(1_000);
            assertEquals(1 / Math.sqrt(2), high.magnitude(RATE, 1_000), 1e-9);
            assertEquals(0, high.magnitude(RATE, 0), 1e-12);
            assertEquals(1, high.magnitude(RATE, RATE / 2.0), 1e-9);
        }

        @Test
        @DisplayName("a peaking band has its gain at the center, shelves at their end, the notch a zero")
        void gains() {
            assertEquals(Math.pow(10, 6 / 20.0), Biquad.peaking(1_000, 1.4, 6).magnitude(RATE, 1_000), 1e-9);
            assertEquals(1, Biquad.peaking(1_000, 1.4, 6).magnitude(RATE, 0), 1e-9);
            assertEquals(Math.pow(10, 9 / 20.0), Biquad.lowShelf(200, 9).magnitude(RATE, 0), 1e-9);
            assertEquals(1, Biquad.lowShelf(200, 9).magnitude(RATE, RATE / 2.0), 1e-9);
            assertEquals(Math.pow(10, -6 / 20.0), Biquad.highShelf(6_000, -6).magnitude(RATE, RATE / 2.0), 1e-9);
            assertEquals(Math.pow(10, -3 / 20.0), Biquad.lowShelf(200, -6).magnitude(RATE, 200), 1e-3,
                    "half the shelf gain in decibels at the corner");
            assertEquals(0, Biquad.notch(60, 10).magnitude(RATE, 60), 1e-9);
            assertEquals(1, Biquad.bandPass(3_000, 2).magnitude(RATE, 3_000), 1e-9);
            for (var frequency = 0; frequency <= RATE / 2; frequency += 997) {
                assertEquals(1, Biquad.allPass(2_000, 0.7).magnitude(RATE, frequency), 1e-9);
            }
        }

        @Test
        @DisplayName("rejects frequencies at or above half the sampling rate and invalid parameters")
        void rejections() {
            assertThrows(IllegalArgumentException.class, () -> Biquad.lowPass(24_000).coefficients(RATE));
            assertThrows(IllegalArgumentException.class, () -> new BiquadFilter(List.of(Biquad.lowPass(30_000)), 1,
                    RATE));
            assertThrows(IllegalArgumentException.class, () -> Biquad.lowPass(0));
            assertThrows(IllegalArgumentException.class, () -> Biquad.lowPass(100, -1));
            assertThrows(IllegalArgumentException.class, () -> Biquad.peaking(100, 1, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> new BiquadFilter(List.of(), 0, RATE));
        }
    }

    @Nested
    @DisplayName("filtering")
    class Filtering {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.process.BiquadFilterTest#sections")
        @DisplayName("matches the direct-form recurrence sample for sample")
        void recurrence(Biquad section) {
            var input = noise(20_000, section.hashCode());
            var planes = new float[][]{input.clone()};
            new BiquadFilter(List.of(section), 1, RATE).process(planes, 0, input.length);
            Tolerance.maxAbs(2e-6).assertPcm(section.toString(), reference(section, input), planes[0]);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.process.BiquadFilterTest#sections")
        @DisplayName("scales a steady sine by the closed-form magnitude")
        void sines(Biquad section) {
            for (var frequency : new double[]{50, 200, 1_000, 3_000, 9_000, 18_000}) {
                var frames = RATE;
                var input = new float[frames];
                for (var index = 0; index < frames; index++) {
                    input[index] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * index / RATE));
                }
                var planes = new float[][]{input.clone()};
                new BiquadFilter(List.of(section), 1, RATE).process(planes, 0, frames);
                var measured = rms(planes[0], frames / 2, frames) / rms(input, frames / 2, frames);
                var expected = section.magnitude(RATE, frequency);
                assertEquals(expected, measured, 0.01 * Math.max(expected, 0.05),
                        section + " at " + frequency + " Hz");
            }
        }

        @Test
        @DisplayName("filters every channel alike and in order through a cascade, whatever the block sizes")
        void blocks() {
            var cascade = List.of(Biquad.highPass(80), Biquad.peaking(2_500, 1, 4), Biquad.lowPass(12_000));
            var left = noise(30_000, 1);
            var whole = new float[][]{left.clone(), left.clone()};
            new BiquadFilter(cascade, 2, RATE).process(whole, 0, left.length);
            assertArrayEquals(whole[0], whole[1]);
            var expected = left.clone();
            for (var section : cascade) {
                expected = reference(section, expected);
            }
            Tolerance.maxAbs(5e-6).assertPcm("cascade", expected, whole[0]);
            var split = new float[][]{left.clone(), left.clone()};
            var filter = new BiquadFilter(cascade, 2, RATE);
            var random = new SplittableRandom(7);
            for (var offset = 0; offset < left.length; ) {
                var count = Math.min(left.length - offset, 1 + random.nextInt(700));
                filter.process(split, offset, count);
                offset += count;
            }
            assertArrayEquals(whole[0], split[0]);
            assertArrayEquals(whole[1], split[1]);
            filter.reset();
            var again = new float[][]{left.clone(), left.clone()};
            filter.process(again, 0, left.length);
            assertArrayEquals(whole[0], again[0], "reset starts the stream again");
        }
    }

    static double rms(float[] samples, int from, int to) {
        var sum = 0.0;
        for (var index = from; index < to; index++) {
            sum += (double) samples[index] * samples[index];
        }
        return Math.sqrt(sum / (to - from));
    }

    enum FfmpegFilter {
        LOW_PASS("lowpass=f=1000:t=q:w=0.7071067811865476", Biquad.lowPass(1_000)),
        HIGH_PASS("highpass=f=300:t=q:w=1.3", Biquad.highPass(300, 1.3)),
        BAND_PASS("bandpass=f=2000:t=q:w=2", Biquad.bandPass(2_000, 2)),
        NOTCH("bandreject=f=1000:t=q:w=4", Biquad.notch(1_000, 4)),
        ALL_PASS("allpass=f=1500:t=q:w=0.8", Biquad.allPass(1_500, 0.8)),
        EQUALIZER("equalizer=f=1000:t=q:w=1.4:g=6", Biquad.peaking(1_000, 1.4, 6)),
        LOW_SHELF("lowshelf=f=250:t=q:w=0.7071067811865476:g=8", Biquad.lowShelf(250, 8)),
        HIGH_SHELF("highshelf=f=5000:t=q:w=0.7071067811865476:g=-5", Biquad.highShelf(5_000, -5));

        final String argument;
        final Biquad section;

        FfmpegFilter(String argument, Biquad section) {
            this.argument = argument;
            this.section = section;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FfmpegFilter.class)
    @DisplayName("agrees with FFmpeg's filter of the same cookbook shape")
    void ffmpeg(FfmpegFilter filter) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = noise(48_000, filter.ordinal());
        var raw = ByteBuffer.allocate(4 * input.length).order(ByteOrder.LITTLE_ENDIAN);
        raw.asFloatBuffer().put(input);
        var path = Scratch.directory("biquad-oracle").resolve(filter.name() + ".f32");
        Files.write(path, raw.array());
        var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "f32le",
                "-ar", String.valueOf(RATE), "-ac", "1", "-i", path.toString(), "-af", filter.argument + ":precision=f64",
                "-f", "f32le", "-"));
        var expected = new float[output.length / 4];
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected);
        assertEquals(input.length, expected.length);
        var planes = new float[][]{input.clone()};
        new BiquadFilter(List.of(filter.section), 1, RATE).process(planes, 0, input.length);
        Tolerance.maxAbs(1e-5).assertPcm(filter.name(), expected, planes[0]);
    }
}
