package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Frame;
import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Tone;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the DTS Express (LBR) band limits no reference decoder accepts (2/3, 1/3 and 1/8) and the LFE channel
 * below 28 kHz, which ffmpeg's decoder drops, against Smalt's own decode of the same frames in a form ffmpeg
 * verifies: a band limited stream must sound like the same frames coded at the limited rate, resampled to the
 * header's, and the LFE channel at a lower rate must be the one at 48 kHz, decimated.
 */
final class LbrBandLimitTest {
    record LimitCase(String name, int rate, int flags, int limitedRate, int ratio, int divisor, double snr) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<LimitCase> limitCases() {
        // the plain stream at the limited rate stands for it: 8 kHz for the 6 kHz of an eighth of 48 kHz, whose
        // frames have the same lines; the bounds are what the synthesis at more lines leaves, as for the 1/2 limit
        // ffmpeg decodes (42 dB with residual noise up to the top)
        return Stream.of(
                new LimitCase("two thirds of 48 kHz", 48000, 0x04, 32000, 3, 2, 45),
                new LimitCase("a third of 48 kHz", 48000, 0x0C, 16000, 3, 1, 35),
                new LimitCase("two thirds of 24 kHz", 24000, 0x04, 16000, 3, 2, 35),
                new LimitCase("an eighth of 48 kHz", 48000, 0x18, 8000, 8, 1, 25),
                new LimitCase("a half of 48 kHz", 48000, 0x08, 24000, 2, 1, 35));
    }

    /**
     * {@return frames of tonal components in the lowest lines of every layout, followed by silent frames}
     */
    private static List<Frame> tones(long seed) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < 4; f++) {
            var tones = new ArrayList<Tone>();
            tones.add(new Tone(0, 20 + random.nextInt(30), 10, random.nextInt(8)));
            tones.add(new Tone(1, 60 + random.nextInt(20), 11, random.nextInt(8)));
            tones.add(new Tone(2, 30 + random.nextInt(40), 9, random.nextInt(8)));
            frames.add(new Frame(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                    LbrStreamWriter.tonalGroup(1, tones, 1),
                    LbrStreamWriter.tonalGroup(3, List.of(new Tone(0, 150 + random.nextInt(100), 9, 3)), 1)));
        }
        for (var f = 0; f < 3; f++) {
            frames.add(new Frame());
        }
        return frames;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitCases")
    @DisplayName("a band limited stream decodes as its frames at the limited rate, resampled")
    void limit(LimitCase test) throws IOException {
        var frames = tones(test.rate() + test.flags());
        var directory = Scratch.directory("dts-lbr");
        var limited = directory.resolve("limit-" + test.name().replace(' ', '-') + ".dts");
        Files.write(limited, LbrStreamWriter.stream(test.rate(), 0x1, 1, test.flags(), frames));
        var plain = directory.resolve("limit-plain-" + test.name().replace(' ', '-') + ".dts");
        Files.write(plain, LbrStreamWriter.stream(test.limitedRate(), 0x1, 1, 0x14, frames));
        var decoded = LbrDifferentialTest.decode(limited);
        var reference = LbrDifferentialTest.decode(plain);
        assertEquals((long) reference.length * test.ratio() / test.divisor(), decoded.length, "sample count");
        var ratio = (double) test.ratio() / test.divisor();
        // a block of more lines starts as many samples later: half the extra samples of a sample at the limited rate
        var expected = resample(reference, ratio, (ratio - 1) / 2, decoded.length);
        Tolerance.snr(test.snr()).assertPcm(test.name(), expected, decoded);
        // the same frames delayed by another amount fit worse, so the timing is the measured one
        var shifted = resample(reference, ratio, (ratio - 1) / 2 + 1, decoded.length);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> Tolerance.snr(test.snr()).assertPcm(test.name(), shifted, decoded));
    }

    /**
     * {@return a band limited resampling of a signal by a ratio: output sample {@code j} is the signal at input time
     * {@code (j - delay) / ratio}, interpolated with a sinc of 64 zero crossings per side under a Blackman-Harris
     * window}
     */
    private static float[] resample(float[] x, double ratio, double delay, int length) {
        var half = 64;
        var out = new float[length];
        for (var j = 0; j < length; j++) {
            var t = (j - delay) / ratio;
            var center = (int) Math.floor(t);
            var sum = 0.0;
            for (var n = Math.max(0, center - half + 1); n <= Math.min(x.length - 1, center + half); n++) {
                var d = t - n;
                var u = Math.PI * d / half;
                var w = 0.35875 + 0.48829 * Math.cos(u) + 0.14128 * Math.cos(2 * u) + 0.01168 * Math.cos(3 * u);
                var sinc = d == 0 ? 1 : Math.sin(Math.PI * d) / (Math.PI * d);
                sum += x[n] * sinc * w;
            }
            out[j] = (float) sum;
        }
        return out;
    }

    record LfeCase(String name, int rate, int step) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<LfeCase> lfeCases() {
        return Stream.of(new LfeCase("24 kHz", 24000, 2), new LfeCase("16 kHz", 16000, 2),
                new LfeCase("12 kHz", 12000, 4), new LfeCase("8 kHz", 8000, 4));
    }

    /**
     * {@return frames of 64 random 16-bit LFE codes each, followed by silent frames that let the filter decay}
     */
    private static List<Frame> lfe(long seed) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < 8; f++) {
            var codes = new int[64];
            for (var i = 0; i < codes.length; i++) {
                codes[i] = random.nextInt(16);
            }
            frames.add(new Frame(LbrStreamWriter.lfe(random.nextInt(0x3000) | (random.nextBoolean() ? 0x8000 : 0),
                    random.nextInt(60), codes, false)));
        }
        for (var f = 0; f < 8; f++) {
            frames.add(new Frame());
        }
        return frames;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lfeCases")
    @DisplayName("the LFE channel below 28 kHz reads 64 codes per frame and is the one at 48 kHz, decimated")
    void lfeBelow28k(LfeCase test) throws IOException {
        var frames = lfe(test.rate());
        var directory = Scratch.directory("dts-lbr");
        var low = directory.resolve("lfe-low-" + test.rate() + ".dts");
        Files.write(low, LbrStreamWriter.stream(test.rate(), 0x1, 1, 0x16, frames));
        // the frame of the 48 kHz stream that lasts as long: the same 64 codes per frame
        var high = directory.resolve("lfe-high-" + test.rate() + ".dts");
        Files.write(high, LbrStreamWriter.stream(48000, 0x1, 1, 0x16, frames));
        var decoded = LbrDifferentialTest.decode(low);
        var reference = LbrDifferentialTest.decode(high);
        var frameRatio = test.step();
        assertEquals(reference.length / frameRatio, decoded.length, "sample count");
        var expected = new float[decoded.length / 2];
        var actual = new float[decoded.length / 2];
        for (var i = 0; i < actual.length; i++) {
            actual[i] = decoded[2 * i + 1];
            expected[i] = reference[2 * (i * frameRatio) + 1];
        }
        Tolerance.snr(100).assertPcm(test.name(), expected, actual);
    }

    @Test
    @DisplayName("the LFE chunk of a band limited stream carries the codes of a frame at the header's LFE rate")
    void limitedLfe() throws IOException {
        // two thirds of 48 kHz: 6144 samples a frame at 1/64 of the rate, so 96 codes; 64 are too few
        var random = new SplittableRandom(5);
        for (var count : new int[]{96, 64}) {
            var codes = new int[count];
            for (var i = 0; i < codes.length; i++) {
                codes[i] = random.nextInt(16);
            }
            var frames = new ArrayList<Frame>();
            frames.add(new Frame(LbrStreamWriter.lfe(0x1000, 20, codes, false)));
            for (var f = 0; f < 4; f++) {
                frames.add(new Frame());
            }
            var file = Scratch.directory("dts-lbr").resolve("lfe-limited-" + count + ".dts");
            Files.write(file, LbrStreamWriter.stream(48000, 0x1, 1, 0x06, frames));
            var decoded = LbrDifferentialTest.decode(file);
            assertEquals(5 * 6144 * 2, decoded.length, "sample count");
            var energy = 0.0;
            for (var i = 1; i < decoded.length; i += 2) {
                energy += decoded[i] * decoded[i];
            }
            if (count == 96) {
                org.junit.jupiter.api.Assertions.assertTrue(energy > 1, "LFE energy " + energy);
            } else {
                assertEquals(0, energy, "LFE energy");
            }
        }
    }
}
