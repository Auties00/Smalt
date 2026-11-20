package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.Ieee1180;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.OpusCompare;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's tolerance model: each rule passes at its bound and fails just past it, failures name the
 * statistics and the first difference, the ISO/IEC 11172-4 limits scale with full scale, the opus_compare port agrees
 * with libopus' tool where CI provisions it, the IEEE 1180 harness accepts the exact transform and rejects broken
 * ones, and every differential test of the repository declares its tolerance through the model.
 */
@DisplayName("Tolerance model")
class ToleranceTest {
    private static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Nested
    @DisplayName("measured rules")
    class Measured {
        @Test
        @DisplayName("exact passes identical samples and names the first difference otherwise")
        void exact() {
            var expected = bytes(10, 20, 30, 40);
            assertDoesNotThrow(() -> Tolerance.exact().assertWithin("same", expected, expected.clone()));
            var error = assertThrows(AssertionFailedError.class,
                    () -> Tolerance.exact().assertWithin("plane", expected, bytes(10, 20, 31, 40)));
            assertTrue(error.getMessage().contains("plane: exact violated"), error.getMessage());
            assertTrue(error.getMessage().contains("first at 2 (expected 30, actual 31)"), error.getMessage());
        }

        @Test
        @DisplayName("off by one and max abs hold at their bound and fail past it")
        void maxAbs() {
            var expected = bytes(100, 100, 100);
            assertDoesNotThrow(() -> Tolerance.offByOne().assertWithin("x", expected, bytes(101, 99, 100)));
            assertThrows(AssertionFailedError.class,
                    () -> Tolerance.offByOne().assertWithin("x", expected, bytes(102, 100, 100)));
            assertDoesNotThrow(() -> Tolerance.maxAbs(3).assertWithin("x", expected, bytes(103, 97, 100)));
            assertThrows(AssertionFailedError.class,
                    () -> Tolerance.maxAbs(3).assertWithin("x", expected, bytes(104, 100, 100)));
        }

        @Test
        @DisplayName("mean abs, rms, psnr and snr measure the textbook values")
        void aggregates() {
            var expected = new byte[1000];
            var actual = new byte[1000];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (byte) 128;
                actual[i] = (byte) (i % 2 == 0 ? 129 : 127);
            }
            var difference = Tolerance.Difference.of(expected, actual);
            assertEquals(1.0, difference.meanAbs(), 1e-12);
            assertEquals(1.0, difference.rms(), 1e-12);
            assertEquals(20 * Math.log10(255), difference.psnr(), 1e-9);
            assertEquals(10 * Math.log10(128.0 * 128.0), difference.snr(), 1e-9);
            assertDoesNotThrow(() -> Tolerance.rms(1).assertWithin("x", expected, actual));
            assertThrows(AssertionFailedError.class, () -> Tolerance.rms(0.99).assertWithin("x", expected, actual));
            assertDoesNotThrow(() -> Tolerance.meanAbs(1).assertWithin("x", expected, actual));
            assertThrows(AssertionFailedError.class, () -> Tolerance.meanAbs(0.99).assertWithin("x", expected, actual));
            assertDoesNotThrow(() -> Tolerance.psnr(48.13).assertWithin("x", expected, actual));
            assertThrows(AssertionFailedError.class, () -> Tolerance.psnr(48.14).assertWithin("x", expected, actual));
            assertDoesNotThrow(() -> Tolerance.snr(42.14).assertWithin("x", expected, actual));
            assertThrows(AssertionFailedError.class, () -> Tolerance.snr(42.15).assertWithin("x", expected, actual));
        }

        @Test
        @DisplayName("differing bounds the fraction of samples that differ at all")
        void differing() {
            var expected = new byte[100];
            var actual = new byte[100];
            actual[3] = 1;
            actual[50] = 1;
            assertDoesNotThrow(() -> Tolerance.differing(0.02).assertWithin("x", expected, actual));
            assertThrows(AssertionFailedError.class, () -> Tolerance.differing(0.01).assertWithin("x", expected, actual));
        }

        @Test
        @DisplayName("deep samples use their bit depth's peak")
        void deep() {
            var expected = new short[]{0, 1023};
            var actual = new short[]{1, 1023};
            var difference = Tolerance.Difference.of(expected, actual, 10);
            assertEquals(1023, difference.peak());
            assertEquals(10 * Math.log10(1023.0 * 1023.0 * 2), difference.psnr(), 1e-9);
        }

        @Test
        @DisplayName("combined rules report every bound they break")
        void combined() {
            var rule = Tolerance.psnr(60).and(Tolerance.maxAbs(1)).and(Tolerance.differing(0.5));
            var error = assertThrows(AssertionFailedError.class,
                    () -> rule.assertWithin("x", bytes(0, 0, 0, 0), bytes(5, 0, 0, 0)));
            assertTrue(error.getMessage().contains("psnr 60 dB and off by one and differing 0.5 violated"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("max 5 > 1"), error.getMessage());
            assertFalse(error.getMessage().contains("of the samples differ >"), error.getMessage());
        }

        @Test
        @DisplayName("frame-by-frame checks name the first frame that breaks the rule")
        void frames() {
            var expected = new byte[12];
            var actual = new byte[12];
            actual[9] = 1;
            var error = assertThrows(AssertionFailedError.class,
                    () -> Tolerance.exact().assertFrames("clip", expected, actual, 4));
            assertTrue(error.getMessage().startsWith("clip frame 2: exact violated"), error.getMessage());
            assertThrows(AssertionFailedError.class,
                    () -> Tolerance.exact().assertFrames("clip", expected, new byte[8], 4));
        }

        @Test
        @DisplayName("a length mismatch fails before any statistic")
        void length() {
            var error = assertThrows(AssertionFailedError.class,
                    () -> Tolerance.maxAbs(255).assertWithin("x", new byte[3], new byte[4]));
            assertTrue(error.getMessage().contains("sample count"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("ISO/IEC 11172-4 compliance")
    class Iso11172 {
        @Test
        @DisplayName("full accuracy bounds the RMS by 2^-15/sqrt(12) and every difference by 2^-14 of full scale")
        void full() {
            var full = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);
            var expected = new float[4096];
            var actual = new float[4096];
            var random = new Random(7);
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (float) (0.1 * Math.sin(i * 0.05));
                actual[i] = expected[i] + (float) ((random.nextDouble() - 0.5) * Math.pow(2, -15) * 0.9);
            }
            assertDoesNotThrow(() -> full.assertPcm("near", expected, actual));
            actual[100] = expected[100] + (float) Math.pow(2, -13);
            var error = assertThrows(AssertionFailedError.class, () -> full.assertPcm("spike", expected, actual));
            assertTrue(error.getMessage().contains("max"), error.getMessage());
            assertDoesNotThrow(() -> Tolerance.iso11172(Tolerance.Iso11172Accuracy.LIMITED)
                    .assertPcm("spike", expected, actual));
        }

        @Test
        @DisplayName("16-bit PCM is measured against a full scale of 32768")
        void pcm16() {
            var expected = new short[2000];
            var actual = new short[2000];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (short) (i * 7 - 7000);
                actual[i] = (short) (expected[i] + (i % 12 == 0 ? 1 : 0));
            }
            // one sample in twelve off by one LSB: an RMS of 2^-15/sqrt(12), which full accuracy excludes
            assertThrows(AssertionFailedError.class,
                    () -> Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL).assertPcm("x", expected, actual));
            actual[0] = expected[0];
            assertDoesNotThrow(() -> Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL).assertPcm("x", expected,
                    actual));
            for (var i = 0; i < actual.length; i++) {
                actual[i] = (short) (expected[i] + 2);
            }
            assertThrows(AssertionFailedError.class,
                    () -> Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL).assertPcm("x", expected, actual));
            assertDoesNotThrow(() -> Tolerance.iso11172(Tolerance.Iso11172Accuracy.LIMITED).assertPcm("x", expected,
                    actual));
        }
    }

    @Nested
    @DisplayName("opus_compare")
    class Opus {
        private static short[] signal(int channels, int frames, double noise, long seed) {
            var random = new Random(seed);
            var out = new short[frames * channels];
            for (var frame = 0; frame < frames; frame++) {
                for (var channel = 0; channel < channels; channel++) {
                    var value = 6000 * Math.sin(2 * Math.PI * (300 + 170 * channel) * frame / 48000.0)
                            + 3000 * Math.sin(2 * Math.PI * 2500 * frame / 48000.0)
                            + noise * random.nextGaussian();
                    out[frame * channels + channel] = (short) Math.max(-32768, Math.min(32767, Math.round(value)));
                }
            }
            return out;
        }

        private static short[] distorted(short[] reference, double noise, long seed) {
            var random = new Random(seed);
            var out = reference.clone();
            for (var i = 0; i < out.length; i++) {
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(out[i] + noise * random.nextGaussian())));
            }
            return out;
        }

        @Test
        @DisplayName("passes a decode against itself at 100 % and fails a heavily distorted one")
        void verdicts() {
            var reference = signal(2, 48000, 0, 1);
            var self = OpusCompare.compare(reference, reference, 2, 48000);
            assertTrue(self.passes());
            assertEquals(100.0, self.quality(), 1e-6);
            assertDoesNotThrow(() -> Tolerance.opusCompare(2, 48000).assertPcm("self", reference, reference));
            // rounding-level noise; opus_compare weighs added energy in bands the signal leaves empty heavily
            assertDoesNotThrow(() -> Tolerance.opusCompare(2, 48000).assertPcm("mild", reference,
                    distorted(reference, 0.5, 2)));
            assertThrows(AssertionFailedError.class, () -> Tolerance.opusCompare(2, 48000).assertPcm("noise",
                    reference, distorted(reference, 6000, 3)));
        }

        @Test
        @DisplayName("compares a signal of more than two channels channel by channel")
        void channels() {
            var reference = signal(6, 24000, 0, 4);
            var decoded = reference.clone();
            assertDoesNotThrow(() -> Tolerance.opusCompare(6, 48000).assertPcm("6 ch", reference, decoded));
            var random = new Random(5);
            for (var frame = 0; frame < 24000; frame++) {
                decoded[frame * 6 + 4] = (short) (random.nextGaussian() * 8000);
            }
            var error = assertThrows(AssertionFailedError.class,
                    () -> Tolerance.opusCompare(6, 48000).assertPcm("6 ch", reference, decoded));
            assertTrue(error.getMessage().contains("channel 4"), error.getMessage());
        }

        @Test
        @DisplayName("the port reports the quality libopus' opus_compare prints, where CI provisions it")
        void againstTool() throws IOException {
            if (Oracle.find(Oracle.Tool.OPUS_COMPARE).isEmpty()) {
                return;
            }
            var directory = Files.createDirectories(Path.of("target", "opus-compare"));
            var reference = signal(2, 48000, 0, 6);
            var cases = new ArrayList<short[]>();
            for (var noise : new double[]{0, 40, 400, 2500, 9000}) {
                cases.add(distorted(reference, noise, (long) noise + 7));
            }
            var referenceFile = write(directory.resolve("reference.sw"), reference);
            for (var index = 0; index < cases.size(); index++) {
                var decodedFile = write(directory.resolve("decoded-" + index + ".sw"), cases.get(index));
                for (var channels = 1; channels <= 2; channels++) {
                    var arguments = new ArrayList<String>();
                    if (channels == 2) {
                        arguments.add("-s");
                    }
                    arguments.add(referenceFile.toString());
                    var mono = new short[cases.get(index).length / 2];
                    for (var i = 0; i < mono.length; i++) {
                        mono[i] = cases.get(index)[2 * i];
                    }
                    var decoded = channels == 2 ? cases.get(index) : mono;
                    arguments.add(channels == 2 ? decodedFile.toString()
                            : write(directory.resolve("mono-" + index + ".sw"), mono).toString());
                    var result = Oracle.execute(Oracle.Tool.OPUS_COMPARE, Duration.ofMinutes(1), null, arguments);
                    var port = OpusCompare.compare(reference, decoded, channels, 48000);
                    var text = result.stderr();
                    var quality = Pattern.compile("Opus quality metric: (-?[0-9.]+) %").matcher(text);
                    assertEquals(port.passes(), result.exitCode() == 0, "verdict of case " + index + ": " + text);
                    if (quality.find()) {
                        assertEquals(Double.parseDouble(quality.group(1)), port.quality(), 0.15,
                                "quality of case " + index + " with " + channels + " channels");
                    } else {
                        var error = Pattern.compile("weighted error is ([0-9.]+)").matcher(text);
                        assertTrue(error.find(), text);
                        assertEquals(Double.parseDouble(error.group(1)), port.error(), 1e-3 * port.error() + 1e-4);
                    }
                }
            }
        }

        private static Path write(Path file, short[] samples) throws IOException {
            var buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var sample : samples) {
                buffer.putShort(sample);
            }
            Files.write(file, buffer.array());
            return file.toAbsolutePath();
        }
    }

    @Nested
    @DisplayName("IEEE 1180")
    class Idct {
        private static Ieee1180.Idct rounded(java.util.function.DoubleUnaryOperator rounding) {
            return (coefficients, samples) -> {
                var input = new double[64];
                for (var i = 0; i < 64; i++) {
                    input[i] = coefficients[i];
                }
                var output = new double[64];
                Ieee1180.inverse(input, output);
                for (var i = 0; i < 64; i++) {
                    samples[i] = (int) rounding.applyAsDouble(output[i]);
                }
            };
        }

        @Test
        @DisplayName("accepts the double precision transform rounded to nearest, with no error at all")
        void reference() {
            var runs = Ieee1180.assertCompliant("reference", rounded(value -> Math.floor(value + 0.5)));
            assertEquals(6, runs.size());
            for (var run : runs) {
                assertEquals(0, run.peakError(), run.toString());
                assertEquals(0, run.overallMse(), run.toString());
            }
        }

        @Test
        @DisplayName("rejects a transform that truncates instead of rounding")
        void truncating() {
            var error = assertThrows(AssertionFailedError.class,
                    () -> Ieee1180.assertCompliant("floor", rounded(Math::floor)));
            assertTrue(error.getMessage().contains("mean error"), error.getMessage());
        }

        @Test
        @DisplayName("rejects a transform with coarse fixed-point constants")
        void coarse() {
            Ieee1180.Idct coarse = (coefficients, samples) -> {
                var basis = new double[8][8];
                for (var u = 0; u < 8; u++) {
                    for (var x = 0; x < 8; x++) {
                        var exact = (u == 0 ? Math.sqrt(0.125) : 0.5) * Math.cos((2 * x + 1) * u * Math.PI / 16);
                        basis[u][x] = Math.round(exact * 32) / 32.0;
                    }
                }
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        var sum = 0.0;
                        for (var v = 0; v < 8; v++) {
                            for (var u = 0; u < 8; u++) {
                                sum += basis[u][x] * basis[v][y] * coefficients[v * 8 + u];
                            }
                        }
                        samples[y * 8 + x] = (int) Math.floor(sum + 0.5);
                    }
                }
            };
            assertThrows(AssertionFailedError.class, () -> Ieee1180.assertCompliant("coarse", coarse));
        }

        @Test
        @DisplayName("rejects a transform that swaps rows and columns, and one that leaves a DC offset on zeros")
        void structure() {
            var exact = rounded(value -> Math.floor(value + 0.5));
            Ieee1180.Idct transposed = (coefficients, samples) -> {
                var swapped = new int[64];
                for (var v = 0; v < 8; v++) {
                    for (var u = 0; u < 8; u++) {
                        swapped[u * 8 + v] = coefficients[v * 8 + u];
                    }
                }
                exact.inverse(swapped, samples);
            };
            assertThrows(AssertionFailedError.class, () -> Ieee1180.assertCompliant("transposed", transposed));
            Ieee1180.Idct offset = (coefficients, samples) -> {
                exact.inverse(coefficients, samples);
                var zero = true;
                for (var coefficient : coefficients) {
                    zero &= coefficient == 0;
                }
                if (zero) {
                    samples[0] = 1;
                }
            };
            var error = assertThrows(AssertionFailedError.class, () -> Ieee1180.assertCompliant("offset", offset));
            assertTrue(error.getMessage().contains("all-zero block"), error.getMessage());
        }

        @Test
        @DisplayName("draws the standard's first sample of the -256..255 run from its generator")
        void generator() {
            var first = new int[1];
            Ieee1180.measure((coefficients, samples) -> {
                if (first[0] == 0) {
                    var input = new double[64];
                    for (var i = 0; i < 64; i++) {
                        input[i] = coefficients[i];
                    }
                    var block = new double[64];
                    Ieee1180.inverse(input, block);
                    first[0] = (int) Math.floor(block[0] + 0.5) + 1000;
                }
            }, 256, 255, false, 1);
            // randx = 1 * 1103515245 + 12345 = 1103527590; 1103527590 / 0x7fffffff * 512 = 263.1, so 263 - 256 = 7;
            // the rounded coefficients reproduce it within one
            assertEquals(7, first[0] - 1000, 1);
        }
    }

    @Nested
    @DisplayName("policy")
    class Policy {
        /**
         * Returns the test classes of every module whose name ends with a suffix, found from the working directory (a
         * module directory under Maven).
         */
        private static List<Path> tests(String suffix) throws IOException {
            var root = Path.of("").toAbsolutePath();
            if (!Files.isDirectory(root.resolve("core")) && root.getParent() != null) {
                root = root.getParent();
            }
            var files = new ArrayList<Path>();
            try (var modules = Files.list(root)) {
                for (var module : modules.toList()) {
                    var tests = module.resolve("src").resolve("test").resolve("java");
                    if (Files.isDirectory(tests)) {
                        try (var walk = Files.walk(tests)) {
                            walk.filter(file -> file.getFileName().toString().endsWith(suffix))
                                    .forEach(files::add);
                        }
                    }
                }
            }
            return files;
        }

        @Test
        @DisplayName("every differential test declares its tolerance through the model, not with its own arithmetic")
        void declared() throws IOException {
            var files = tests("DifferentialTest.java");
            assertTrue(files.size() >= 16, "found the differential tests: " + files);
            var offenders = new ArrayList<String>();
            for (var file : files) {
                var text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("Tolerance.")) {
                    offenders.add(file.getFileName() + " declares no Tolerance");
                }
                var raw = Pattern.compile("Quality\\.(psnr|snr|maxDifference)\\(").matcher(text);
                if (raw.find()) {
                    offenders.add(file.getFileName() + " compares with Quality." + raw.group(1) + " directly");
                }
            }
            assertEquals(List.of(), offenders);
        }

        @Test
        @DisplayName("conformance tests compare with a reference through the model too, or bit for bit")
        void conformance() throws IOException {
            var files = tests("ConformanceTest.java");
            assertTrue(files.size() >= 20, "found the conformance tests: " + files);
            var offenders = new ArrayList<String>();
            for (var file : files) {
                var raw = Pattern.compile("Quality\\.(psnr|snr|maxDifference)\\(")
                        .matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (raw.find()) {
                    offenders.add(file.getFileName() + " compares with Quality." + raw.group(1) + " directly");
                }
            }
            assertEquals(List.of(), offenders);
        }
    }
}
