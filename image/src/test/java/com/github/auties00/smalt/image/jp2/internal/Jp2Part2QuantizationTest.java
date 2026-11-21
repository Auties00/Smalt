package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.decode;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.samples;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.smooth;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the quantization extensions of ISO/IEC 15444-2: variable deadzones (annex C), trellis coded quantization
 * (annex D) and precinct-dependent quantization ({@code QPD} and {@code QPC}, A.3.11 and A.3.12), against codestreams
 * whose indices and expected reconstructions the test computes from the annexes independently of the decoder (with no
 * decomposition level the wavelet is the identity, so a decoded sample is its coefficient's reconstruction
 * exactly), and the derived step sizes of sub-bands split in one direction against codestreams Kakadu wrote and decoded.
 */
@DisplayName("JPEG 2000 Part 2 quantization extensions")
class Jp2Part2QuantizationTest {
    private static final int RSIZ_VARQ = 0x0002;
    private static final int RSIZ_TCQ = 0x0004;
    private static final int PRECISION = 12;
    private static final long OFFSET = 1L << (PRECISION - 1);

    /** {@return the step size of equation E-3 for the LL band of a {@value #PRECISION}-bit component} */
    private static double step(int exponent, int mantissa) {
        return (1 + mantissa / 2048.0) * Math.pow(2, PRECISION - exponent);
    }

    /** Random indices, a third of them zero, of magnitude at most {@code limit}. */
    private static long[] indices(Random random, int count, int limit) {
        var values = new long[count];
        for (var i = 0; i < count; i++) {
            values[i] = random.nextInt(3) == 0 ? 0 : random.nextInt(2 * limit + 1) - limit;
        }
        return values;
    }

    /** The reconstruction of equation C-3 (r = 1/2), mirrored for negative indices. */
    private static double deadzone(long index, double nz, double step) {
        if (index == 0) {
            return 0;
        }
        var magnitude = (Math.abs(index) + 0.5 - nz) * step;
        return index < 0 ? -magnitude : magnitude;
    }

    /** The sample a reconstructed coefficient of a 0-level tile-component decodes to. */
    private static long sample(double coefficient) {
        return Math.clamp((long) Math.rint(coefficient) + OFFSET, 0, (1L << PRECISION) - 1);
    }

    private static Part2Writer flat(int width, int height, int components) {
        var writer = new Part2Writer(width, height, components, PRECISION, false);
        writer.levels = 0;
        writer.wavelet = 0;
        writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
        writer.exponent = 11;
        writer.mantissa = 0;
        writer.guardBits = 2;
        return writer;
    }

    private static byte[] words(int... values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) {
            Part2Forward.u16(out, value & 0xFFFF);
        }
        return out.toByteArray();
    }

    /** A {@code QPD} segment, with the two-byte precinct index when {@code wide}. */
    static byte[] qpd(boolean wide, int resolution, int precinct, int sqcx, byte[] values) {
        var out = new ByteArrayOutputStream();
        out.write(wide ? 128 + resolution : resolution);
        if (wide) {
            Part2Forward.u16(out, precinct);
        } else {
            out.write(precinct);
        }
        out.write(sqcx);
        out.writeBytes(values);
        return Part2Forward.segment(Codestream.QPD, out.toByteArray());
    }

    /** A {@code QPC} segment of a codestream of at most 256 components. */
    static byte[] qpc(int component, boolean wide, int resolution, int precinct, int sqcx, byte[] values) {
        var body = qpd(wide, resolution, precinct, sqcx, values);
        var out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOf(body, 2));
        Part2Forward.u16(out, ((body[2] & 0xFF) << 8 | (body[3] & 0xFF)) + 1);
        out.write(component);
        out.write(body, 4, body.length - 4);
        var result = out.toByteArray();
        result[1] = (byte) (Codestream.QPC & 0xFF);
        return result;
    }

    static byte[] qcd(int sqcx, byte[] values) {
        var out = new ByteArrayOutputStream();
        out.write(sqcx);
        out.writeBytes(values);
        return Part2Forward.segment(Codestream.QCD, out.toByteArray());
    }

    private static Quantization quantization(int style, int guard, int exponent, int mantissa, int deadzone) {
        var exponents = new int[Quantization.MAX_BANDS];
        var mantissas = new int[Quantization.MAX_BANDS];
        Arrays.fill(exponents, exponent);
        Arrays.fill(mantissas, mantissa);
        int[] deadzones = null;
        if (deadzone != 0) {
            deadzones = new int[Quantization.MAX_BANDS];
            Arrays.fill(deadzones, deadzone);
        }
        return new Quantization(style, guard, exponents, mantissas, deadzones);
    }

    private static float[] floats(double[] values) {
        var result = new float[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = (float) values[i];
        }
        return result;
    }

    @Nested
    @DisplayName("quantization segments")
    class Segments {
        @Test
        @DisplayName("the variable deadzone styles read one deadzone or one per sub-band (Tables A.12 to A.15)")
        void deadzoneStyles() {
            var derived = Quantization.parse(new byte[]{0x43, (byte) 0xC0, 0x00, 0x58, 0x05}, 0, 5);
            assertEquals(Quantization.DEADZONE_DERIVED, derived.style());
            assertEquals(2, derived.guardBits());
            assertTrue(derived.derived());
            assertFalse(derived.trellis());
            assertEquals(-0.5f, derived.deadzone(0));
            assertEquals(-0.5f, derived.deadzone(7));
            assertEquals(11, derived.exponent(0));
            assertEquals(5, derived.mantissa(0));
            assertEquals(10, derived.exponent(4));
            var shared = Quantization.parse(concat(new byte[]{0x24}, words(0x2000, 11 << 11, 10 << 11 | 7)), 0, 7);
            assertEquals(Quantization.DEADZONE_DERIVED_STEPS_EXPOUNDED, shared.style());
            assertFalse(shared.derived());
            assertEquals(0.25f, shared.deadzone(0));
            assertEquals(0.25f, shared.deadzone(1));
            assertEquals(10, shared.exponent(1));
            assertEquals(7, shared.mantissa(1));
            var expounded = Quantization.parse(concat(new byte[]{0x25}, words(0x7FFF, 11 << 11, 0x8000, 9 << 11)),
                    0, 9);
            assertEquals(Quantization.DEADZONE_EXPOUNDED, expounded.style());
            assertEquals(32767 / 32768f, expounded.deadzone(0));
            assertEquals(-1f, expounded.deadzone(1));
            assertEquals(9, expounded.exponent(1));
        }

        @Test
        @DisplayName("the trellis coded styles read like the scalar ones and declare trellis coding")
        void trellisStyles() {
            var derived = Quantization.parse(concat(new byte[]{0x29}, words(12 << 11 | 3)), 0, 3);
            assertTrue(derived.trellis());
            assertTrue(derived.derived());
            assertEquals(12, derived.exponent(1));
            assertEquals(11, derived.exponent(4));
            assertEquals(3, derived.mantissa(9));
            assertEquals(0f, derived.deadzone(0));
            var expounded = Quantization.parse(concat(new byte[]{0x2A}, words(12 << 11, 9 << 11 | 4)), 0, 5);
            assertTrue(expounded.trellis());
            assertFalse(expounded.derived());
            assertEquals(9, expounded.exponent(1));
            assertEquals(4, expounded.mantissa(1));
        }

        @ParameterizedTest(name = "style {0}")
        @ValueSource(ints = {6, 7, 8, 11, 31})
        @DisplayName("a reserved quantization style is malformed")
        void reserved(int style) {
            assertThrows(MediaException.Malformed.class,
                    () -> Quantization.parse(concat(new byte[]{(byte) style}, words(1, 2, 3, 4)), 0, 9));
        }

        @ParameterizedTest(name = "style {0}")
        @ValueSource(ints = {3, 4, 5, 9, 10})
        @DisplayName("a quantization segment too short for its style is malformed")
        void truncated(int style) {
            assertThrows(MediaException.Malformed.class,
                    () -> Quantization.parse(new byte[]{(byte) style, 0x40}, 0, 2));
        }

        @Test
        @DisplayName("a QPD repeating a resolution level and precinct, naming a level above 32 or truncated is "
                + "malformed")
        void malformedPrecinctSegments() {
            for (var segments : new byte[][][]{
                    {qpd(false, 0, 3, 2, words(10 << 11)), qpd(true, 0, 3, 2, words(9 << 11))},
                    {qpd(false, 33, 0, 2, words(10 << 11))},
                    {Arrays.copyOf(qpd(false, 0, 0, 2, words(10 << 11)), 6)},
                    {qpc(5, false, 0, 0, 2, words(10 << 11))}}) {
                var writer = flat(16, 16, 1);
                writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
                for (var segment : segments) {
                    writer.mainSegments.add(segment);
                }
                var data = fixLength(writer.write(new long[][]{new long[256]}), segments);
                assertThrows(MediaException.Malformed.class, () -> decode(data));
            }
        }

        /** Rewrites the length of a truncated segment so that it stays a well-formed marker segment. */
        private static byte[] fixLength(byte[] data, byte[][] segments) {
            for (var segment : segments) {
                if (segment.length >= 4 && ((segment[2] & 0xFF) << 8 | (segment[3] & 0xFF)) != segment.length - 2) {
                    var at = indexOf(data, segment);
                    data[at + 2] = 0;
                    data[at + 3] = (byte) (segment.length - 2);
                }
            }
            return data;
        }
    }

    @Nested
    @DisplayName("variable deadzones (annex C)")
    class Deadzones {
        @ParameterizedTest(name = "style {0}, num_nz {1}")
        @CsvSource({"3,9830", "3,-16384", "4,32767", "4,-32768", "5,8192", "5,-3277"})
        @DisplayName("every decoded index is reconstructed as equation C-3 gives, mirrored for negative indices")
        void exact(int style, int deadzone) {
            var random = new Random(style * 31L + deadzone);
            var writer = flat(48, 40, 1);
            writer.rsiz |= RSIZ_VARQ;
            writer.quantizationStyle = style;
            writer.deadzone = deadzone;
            writer.bandDeadzones = new int[]{deadzone};
            writer.mantissa = 0;
            var q = indices(random, 48 * 40, 300);
            var decoded = samples(decode(writer.write(new long[][]{q})).channels().getFirst());
            var nz = deadzone / 32768.0;
            var expected = new long[q.length];
            for (var i = 0; i < q.length; i++) {
                expected[i] = sample(deadzone(q[i], nz, step(11, 0)));
            }
            assertArrayEquals(expected, decoded);
        }

        @Test
        @DisplayName("a deadzone that is not applied would decode differently")
        void applied() {
            var random = new Random(3);
            var writer = flat(32, 32, 1);
            writer.rsiz |= RSIZ_VARQ;
            writer.quantizationStyle = Quantization.DEADZONE_DERIVED;
            writer.deadzone = 16384;
            var data = writer.write(new long[][]{indices(random, 1024, 100)});
            var plain = data.clone();
            var qcd = indexOf(plain, new byte[]{(byte) 0xFF, 0x5C});
            plain[qcd + 4] = (byte) ((plain[qcd + 4] & 0xE0) | Quantization.DEADZONE_DERIVED);
            plain[qcd + 5] = 0;
            plain[qcd + 6] = 0;
            assertFalse(Arrays.equals(samples(decode(data).channels().getFirst()),
                    samples(decode(plain).channels().getFirst())));
        }

        @Test
        @DisplayName("indices whose last bit-plane was not decoded keep the deadzone of equation C-3")
        void partial() {
            var random = new Random(4);
            var writer = flat(48, 48, 1);
            writer.rsiz |= RSIZ_VARQ;
            writer.quantizationStyle = Quantization.DEADZONE_EXPOUNDED;
            writer.bandDeadzones = new int[]{-9830};
            writer.codeBlockStyle = ComponentCoding.TERMINATE_ALL;
            writer.droppedPasses = 3;
            var q = indices(random, 48 * 48, 200);
            var decoded = samples(decode(writer.write(new long[][]{q})).channels().getFirst());
            var nz = -9830 / 32768.0;
            for (var i = 0; i < q.length; i++) {
                var truncated = Math.abs(q[i]) & ~1L;
                var expected = truncated == 0 ? 0 : Math.signum(q[i]) * (truncated + 1 - nz) * step(11, 0);
                assertEquals(sample(expected), decoded[i], "coefficient " + i);
            }
        }

        @ParameterizedTest(name = "num_nz {0}")
        @ValueSource(ints = {-16384, 0, 9830})
        @DisplayName("an image quantized with equation C-4 round-trips within the quantizer's error")
        void roundTrip(int deadzone) {
            var random = new Random(5 + deadzone);
            var writer = new Part2Writer(40, 36, 1, 8, false);
            writer.rsiz |= RSIZ_VARQ;
            writer.wavelet = 0;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.DEADZONE_DERIVED;
            writer.deadzone = deadzone;
            writer.guardBits = 3;
            writer.exponent = 11;
            var plane = smooth(random, 40, 36, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decompose(coefficients, 40, 0, 0, 0, 0, 40, 36, Part2Forward.Tree.dyadic(3),
                    Part2Forward.DAUBECHIES);
            var nz = deadzone / 32768.0;
            var data = writer.write(new float[][]{floats(coefficients)},
                    (values, indices, w, h, stepSize, resolution, orientation, precinct) -> {
                        for (var i = 0; i < values.length; i++) {
                            var a = values[i];
                            var magnitude = Math.abs(a);
                            indices[i] = magnitude < -nz * stepSize ? 0
                                    : (int) (Math.signum(a) * Math.floor((magnitude + nz * stepSize) / stepSize));
                        }
                    });
            var decoded = samples(decode(data).channels().getFirst());
            Tolerance.maxAbs(2).assertWithin("deadzone round trip", toInts(plane), toInts(decoded));
        }
    }

    @Nested
    @DisplayName("trellis coded quantization (annex D)")
    class Trellis {
        private byte[] write(Part2Writer writer, double[] coefficients, double[] reconstructions, long[] transmitted) {
            return writer.write(new float[][]{floats(coefficients)},
                    (values, indices, w, h, stepSize, resolution, orientation, precinct) -> {
                        var scaled = new double[values.length];
                        for (var i = 0; i < values.length; i++) {
                            scaled[i] = values[i] / (stepSize / 2.0);
                        }
                        var q = new long[values.length];
                        var levels = new double[values.length];
                        Part2Tcq.quantize(scaled, w, h, q, levels);
                        for (var i = 0; i < values.length; i++) {
                            indices[i] = (int) q[i];
                        }
                        if (reconstructions != null) {
                            // the 0-level tests code their component as one code-block
                            for (var i = 0; i < values.length; i++) {
                                reconstructions[i] = levels[i] * stepSize / 2.0;
                                transmitted[i] = q[i];
                            }
                        }
                    });
        }

        @ParameterizedTest(name = "style {0}")
        @ValueSource(ints = {Quantization.TRELLIS_DERIVED, Quantization.TRELLIS_EXPOUNDED})
        @DisplayName("fully decoded code-blocks are reconstructed along the trellis as the forward quantizer chose")
        void full(int style) {
            var random = new Random(style);
            var writer = flat(40, 36, 1);
            writer.rsiz |= RSIZ_TCQ;
            writer.quantizationStyle = style;
            writer.codeBlock = 6;
            var coefficients = new double[40 * 36];
            for (var i = 0; i < coefficients.length; i++) {
                coefficients[i] = random.nextInt(4) == 0 ? 0 : random.nextGaussian() * 60;
            }
            var reconstructions = new double[coefficients.length];
            var transmitted = new long[coefficients.length];
            var decoded = samples(decode(write(writer, coefficients, reconstructions, transmitted)).channels()
                    .getFirst());
            var nonZero = 0;
            for (var i = 0; i < coefficients.length; i++) {
                assertEquals(sample(reconstructions[i]), decoded[i], "coefficient " + i);
                assertTrue(Math.abs(reconstructions[i] - coefficients[i]) <= 2.5, "quantizer error at " + i);
                nonZero += transmitted[i] != 0 ? 1 : 0;
            }
            assertTrue(nonZero > coefficients.length / 2, "the test codes nonzero indices");
        }

        @Test
        @DisplayName("every code-block restarts the trellis and scans its stripes, partial ones included, in order")
        void codeBlocks() {
            var random = new Random(11);
            var width = 40;
            var height = 38;
            var size = 16;
            var writer = flat(width, height, 1);
            writer.rsiz |= RSIZ_TCQ;
            writer.quantizationStyle = Quantization.TRELLIS_EXPOUNDED;
            writer.codeBlock = 4;
            var coefficients = new double[width * height];
            for (var i = 0; i < coefficients.length; i++) {
                coefficients[i] = random.nextInt(4) == 0 ? 0 : random.nextGaussian() * 60;
            }
            var decoded = samples(decode(write(writer, coefficients, null, null)).channels().getFirst());
            // the code-blocks of a 0-level component tile it from the origin; each is quantized on its own
            var stepSize = (float) step(11, 0);
            var expected = new double[coefficients.length];
            for (var y0 = 0; y0 < height; y0 += size) {
                for (var x0 = 0; x0 < width; x0 += size) {
                    var w = Math.min(size, width - x0);
                    var h = Math.min(size, height - y0);
                    var scaled = new double[w * h];
                    for (var j = 0; j < h; j++) {
                        for (var i = 0; i < w; i++) {
                            scaled[j * w + i] = (float) coefficients[(y0 + j) * width + x0 + i] / (stepSize / 2.0);
                        }
                    }
                    var levels = new double[w * h];
                    Part2Tcq.quantize(scaled, w, h, new long[w * h], levels);
                    for (var j = 0; j < h; j++) {
                        for (var i = 0; i < w; i++) {
                            expected[(y0 + j) * width + x0 + i] = levels[j * w + i] * stepSize / 2.0;
                        }
                    }
                }
            }
            for (var i = 0; i < coefficients.length; i++) {
                assertEquals(sample(expected[i]), decoded[i], "coefficient " + i);
            }
        }

        @ParameterizedTest(name = "{0} passes dropped")
        @ValueSource(ints = {1, 2})
        @DisplayName("a code-block missing its last cleanup pass is dequantized approximately, whole indices included")
        void approximateMixed(int dropped) {
            var random = new Random(20 + dropped);
            var writer = flat(32, 32, 1);
            writer.rsiz |= RSIZ_TCQ;
            writer.quantizationStyle = Quantization.TRELLIS_EXPOUNDED;
            writer.codeBlock = 6;
            writer.codeBlockStyle = ComponentCoding.TERMINATE_ALL;
            writer.droppedPasses = dropped;
            var coefficients = new double[32 * 32];
            for (var i = 0; i < coefficients.length; i++) {
                coefficients[i] = random.nextGaussian() * 80;
            }
            var reconstructions = new double[coefficients.length];
            var transmitted = new long[coefficients.length];
            var decoded = samples(decode(write(writer, coefficients, reconstructions, transmitted)).channels()
                    .getFirst());
            var stepSize = step(11, 0);
            var whole = 0;
            var truncated = 0;
            for (var i = 0; i < coefficients.length; i++) {
                // Table D.6: the LSB of the index is cleared whether or not it was decoded, and the reconstruction
                // offset of equation E-6 spans the bit-planes the coefficient is missing
                var cleared = Math.abs(transmitted[i]) & ~1L;
                var sign = Math.signum(transmitted[i]);
                if (cleared == 0) {
                    assertEquals(sample(0), decoded[i], "coefficient " + i);
                } else if (decoded[i] == sample(sign * (cleared + 0.5) * stepSize)) {
                    whole++;
                } else {
                    assertEquals(sample(sign * (cleared + 1) * stepSize), decoded[i], "coefficient " + i);
                    truncated++;
                }
            }
            // without the last cleanup pass the refinement pass still decodes the LSB of every index of magnitude 2 or
            // more; with the refinement pass dropped too, only indices of magnitude 1 can have their LSB decoded
            if (dropped == 1) {
                assertTrue(whole > coefficients.length / 2 && truncated == 0, "decoded and truncated indices: "
                        + whole + ", " + truncated);
            } else {
                assertTrue(whole == 0 && truncated > coefficients.length / 2, "decoded and truncated indices: "
                        + whole + ", " + truncated);
            }
        }

        @Test
        @DisplayName("code-blocks missing their last bit-plane are reconstructed by the approximate dequantization")
        void approximate() {
            var random = new Random(9);
            var writer = flat(32, 32, 1);
            writer.rsiz |= RSIZ_TCQ;
            writer.quantizationStyle = Quantization.TRELLIS_EXPOUNDED;
            writer.codeBlock = 6;
            writer.codeBlockStyle = ComponentCoding.TERMINATE_ALL;
            writer.droppedPasses = 3;
            var coefficients = new double[32 * 32];
            for (var i = 0; i < coefficients.length; i++) {
                coefficients[i] = random.nextGaussian() * 80;
            }
            var reconstructions = new double[coefficients.length];
            var transmitted = new long[coefficients.length];
            var decoded = samples(decode(write(writer, coefficients, reconstructions, transmitted)).channels()
                    .getFirst());
            var stepSize = step(11, 0);
            for (var i = 0; i < coefficients.length; i++) {
                var truncated = Math.abs(transmitted[i]) & ~1L;
                var expected = truncated == 0 ? 0 : Math.signum(transmitted[i]) * (truncated + 1) * stepSize;
                assertEquals(sample(expected), decoded[i], "coefficient " + i);
            }
        }

        @Test
        @DisplayName("an image quantized along the trellis round-trips within the quantizer's error")
        void roundTrip() {
            var random = new Random(10);
            var writer = new Part2Writer(40, 36, 1, 8, false);
            writer.rsiz |= RSIZ_TCQ;
            writer.wavelet = 0;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.TRELLIS_DERIVED;
            writer.guardBits = 3;
            writer.exponent = 11;
            var plane = smooth(random, 40, 36, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decompose(coefficients, 40, 0, 0, 0, 0, 40, 36, Part2Forward.Tree.dyadic(3),
                    Part2Forward.DAUBECHIES);
            var decoded = samples(decode(write(writer, coefficients, null, null)).channels().getFirst());
            Tolerance.maxAbs(2).assertWithin("trellis round trip", toInts(plane), toInts(decoded));
        }

        @Test
        @DisplayName("HT code-blocks whose cleanup pass reached the last bit-plane are dequantized along the trellis")
        void highThroughput() {
            var width = 48;
            var height = 40;
            var pixels = new byte[width * height * 4];
            var random = new Random(15);
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) ((i & 3) == 3 ? 255 : 96 + random.nextInt(64) + (i / 4 % width) * 2);
            }
            var picture = RgbaPicture.of(width, height, width * 4, pixels, false);
            var options = Jp2EncoderOptions.builder().reversible(false).decompositionLevels(2).codeBlockSize(16, 16)
                    .format(Jp2Format.CODESTREAM).build();
            var part1 = Jp2Encoder.encode(picture, options);
            var ht = Jp2Encoder.encode(picture, options.toBuilder().highThroughput(true).build());
            var scalar = samples(decode(part1).channels().getFirst());
            assertArrayEquals(scalar, samples(decode(ht).channels().getFirst()), "the coders code the same indices");
            // the step sizes of the scalar expounded style read the same under the trellis coded expounded style
            var trellis = samples(decode(trellisCoded(part1)).channels().getFirst());
            assertNotEquals(Arrays.toString(scalar), Arrays.toString(trellis));
            assertArrayEquals(trellis, samples(decode(trellisCoded(ht)).channels().getFirst()),
                    "fully decoded HT code-blocks take the full dequantization of D.4.1");
        }

        /** Rewrites the {@code Sqcd} of an encoder's scalar expounded {@code QCD} to the trellis coded style. */
        private static byte[] trellisCoded(byte[] codestream) {
            var data = codestream.clone();
            data[6] |= (byte) ((Codestream.RSIZ_PART2 | RSIZ_TCQ) >> 8);
            data[7] |= (byte) RSIZ_TCQ;
            var qcd = indexOf(data, new byte[]{(byte) 0xFF, (byte) (Codestream.QCD & 0xFF)});
            assertEquals(Quantization.SCALAR_EXPOUNDED, data[qcd + 4] & 0x1F, "the encoder's quantization style");
            data[qcd + 4] = (byte) (data[qcd + 4] & 0xE0 | Quantization.TRELLIS_EXPOUNDED);
            return data;
        }
    }

    @Nested
    @DisplayName("precinct-dependent quantization (QPD and QPC)")
    class Precincts {
        /** The step size, guard bits and deadzone of one precinct, as the test resolves them. */
        private record Effective(int style, int guard, int exponent, int mantissa, int deadzone) {
            double reconstruct(long index) {
                var nz = deadzone / 32768.0;
                return Jp2Part2QuantizationTest.deadzone(index, nz, step(exponent, mantissa));
            }

            Quantization quantization() {
                return Jp2Part2QuantizationTest.quantization(style, guard, exponent, mantissa, deadzone);
            }
        }

        /**
         * Writes a two-component, 0-level codestream of 16 precincts and checks every sample against the precinct
         * quantization the test resolved for it.
         */
        private void check(Part2Writer writer, Effective fallback, Map<Integer, Effective> component0,
                           Map<Integer, Effective> component1) {
            var random = new Random(component0.hashCode() * 31L + component1.hashCode());
            writer.precinctSizes = new int[]{4};
            writer.precinctQuantizations = new PrecinctQuantizations[2];
            var overrides = java.util.List.of(component0, component1);
            for (var c = 0; c < 2; c++) {
                var map = new HashMap<Long, Quantization>();
                for (var entry : overrides.get(c).entrySet()) {
                    map.put(PrecinctQuantizations.key(0, entry.getKey()), entry.getValue().quantization());
                }
                writer.precinctQuantizations[c] = new PrecinctQuantizations(map, Map.of(), Map.of(), Map.of(), false);
            }
            var q = new long[][]{indices(random, 64 * 64, 100), indices(random, 64 * 64, 100)};
            var image = decode(writer.write(q));
            for (var c = 0; c < 2; c++) {
                var decoded = samples(image.channels().get(c));
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < 64; x++) {
                        var precinct = (y / 16) * 4 + x / 16;
                        var effective = overrides.get(c).getOrDefault(precinct, fallback);
                        var i = y * 64 + x;
                        assertEquals(sample(effective.reconstruct(q[c][i])), decoded[i],
                                "component " + c + " precinct " + precinct + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("main header QPC and QPD segments override its QCD, the QPC first")
        void mainHeader() {
            var writer = flat(64, 64, 2);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION | RSIZ_VARQ;
            writer.mainSegments.add(qpd(false, 0, 5, 1 << 5 | 2, words(10 << 11)));
            writer.mainSegments.add(qpc(1, false, 0, 5, 2 << 5 | 2, words(9 << 11 | 1024)));
            writer.mainSegments.add(qpd(true, 0, 6, 2 << 5 | 5, words(-8192, 12 << 11)));
            writer.mainSegments.add(qpc(0, true, 0, 9, 3 << 5 | 3, words(16384, 11 << 11 | 512)));
            var fallback = new Effective(Quantization.SCALAR_EXPOUNDED, 2, 11, 0, 0);
            var quarter = new Effective(Quantization.SCALAR_EXPOUNDED, 1, 10, 0, 0);
            var deadzoned = new Effective(Quantization.DEADZONE_EXPOUNDED, 2, 12, 0, -8192);
            check(writer, fallback,
                    Map.of(5, quarter, 6, deadzoned,
                            9, new Effective(Quantization.DEADZONE_DERIVED, 3, 11, 512, 16384)),
                    Map.of(5, new Effective(Quantization.SCALAR_EXPOUNDED, 2, 9, 1024, 0), 6, deadzoned));
        }

        @Test
        @DisplayName("a tile's QCD hides the main header's precinct segments, and its own QPD and QPC override it")
        void tileHeader() {
            var writer = flat(64, 64, 2);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
            writer.exponent = 10;
            writer.mainSegments.add(qpd(false, 0, 5, 2 << 5 | 2, words(12 << 11)));
            writer.mainSegments.add(qpc(1, false, 0, 7, 2 << 5 | 2, words(12 << 11)));
            writer.tileSegments.add(qcd(2 << 5 | 2, words(10 << 11)));
            writer.tileSegments.add(qpd(false, 0, 7, 2 << 5 | 2, words(9 << 11)));
            writer.tileSegments.add(qpc(1, false, 0, 7, 1 << 5 | 2, words(13 << 11 | 100)));
            var fallback = new Effective(Quantization.SCALAR_EXPOUNDED, 2, 10, 0, 0);
            check(writer, fallback,
                    Map.of(7, new Effective(Quantization.SCALAR_EXPOUNDED, 2, 9, 0, 0)),
                    Map.of(7, new Effective(Quantization.SCALAR_EXPOUNDED, 1, 13, 100, 0)));
        }

        @Test
        @DisplayName("a tile without QCD or QCC keeps the main header's precinct segments under its own")
        void mixedHeaders() {
            var writer = flat(64, 64, 2);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
            writer.mainSegments.add(qpd(false, 0, 2, 2 << 5 | 2, words(10 << 11)));
            writer.mainSegments.add(qpd(false, 0, 3, 2 << 5 | 2, words(10 << 11)));
            writer.tileSegments.add(qpd(false, 0, 3, 2 << 5 | 2, words(12 << 11)));
            var fallback = new Effective(Quantization.SCALAR_EXPOUNDED, 2, 11, 0, 0);
            var main =new Effective(Quantization.SCALAR_EXPOUNDED, 2, 10, 0, 0);
            var tile = new Effective(Quantization.SCALAR_EXPOUNDED, 2, 12, 0, 0);
            check(writer, fallback, Map.of(2, main, 3, tile), Map.of(2, main, 3, tile));
        }

        @Test
        @DisplayName("a trellis coded QPD dequantizes its precinct along the trellis, the other precincts as scalars")
        void trellis() {
            var random = new Random(14);
            var writer = flat(64, 64, 1);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION | RSIZ_TCQ;
            writer.precinctSizes = new int[]{4};
            writer.mainSegments.add(qpd(false, 0, 5, 2 << 5 | Quantization.TRELLIS_EXPOUNDED, words(10 << 11)));
            var override = Jp2Part2QuantizationTest.quantization(Quantization.TRELLIS_EXPOUNDED, 2, 10, 0, 0);
            writer.precinctQuantizations = new PrecinctQuantizations[]{new PrecinctQuantizations(Map.of(),
                    Map.of(PrecinctQuantizations.key(0, 5), override), Map.of(), Map.of(), false)};
            var coefficients = new double[64 * 64];
            for (var i = 0; i < coefficients.length; i++) {
                coefficients[i] = random.nextInt(4) == 0 ? 0 : random.nextGaussian() * 60;
            }
            var expected = new double[coefficients.length];
            var scalar = new double[coefficients.length];
            var data = writer.write(new float[][]{floats(coefficients)},
                    (values, indices, w, h, stepSize, resolution, orientation, precinct) -> {
                        // 0 levels and 16x16 code-blocks and precincts: one code-block per precinct
                        assertEquals((float) step(precinct == 5 ? 10 : 11, 0), stepSize, "precinct " + precinct);
                        var levels = new double[values.length];
                        if (precinct == 5) {
                            var scaled = new double[values.length];
                            for (var i = 0; i < values.length; i++) {
                                scaled[i] = values[i] / (stepSize / 2.0);
                            }
                            var q = new long[values.length];
                            Part2Tcq.quantize(scaled, w, h, q, levels);
                            for (var i = 0; i < values.length; i++) {
                                indices[i] = (int) q[i];
                                levels[i] *= stepSize / 2.0;
                            }
                        } else {
                            for (var i = 0; i < values.length; i++) {
                                indices[i] = (int) (Math.signum(values[i]) * Math.floor(Math.abs(values[i])
                                        / stepSize));
                                levels[i] = deadzone(indices[i], 0, stepSize);
                            }
                        }
                        var x0 = (precinct % 4) * 16;
                        var y0 = (precinct / 4) * 16;
                        for (var j = 0; j < h; j++) {
                            for (var i = 0; i < w; i++) {
                                expected[(y0 + j) * 64 + x0 + i] = levels[j * w + i];
                                scalar[(y0 + j) * 64 + x0 + i] = deadzone(indices[j * w + i], 0, stepSize);
                            }
                        }
                    });
            var decoded = samples(decode(data).channels().getFirst());
            var differing = 0;
            for (var i = 0; i < decoded.length; i++) {
                assertEquals(sample(expected[i]), decoded[i], "sample " + i);
                differing += sample(scalar[i]) != decoded[i] ? 1 : 0;
            }
            assertTrue(differing > 64, "the trellis precinct is not dequantized as scalar indices: " + differing);
        }

        @Test
        @DisplayName("a derived QPD derives the step sizes of its resolution level's sub-bands by equation E-5")
        void derived() {
            var random = new Random(12);
            var writer = new Part2Writer(64, 64, 1, 8, false);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
            writer.wavelet = 0;
            writer.levels = 2;
            writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
            writer.bandExponents = new int[]{10, 10, 10, 10, 10, 10, 10};
            writer.guardBits = 3;
            writer.exponent = 10;
            writer.precinctSizes = new int[]{3, 4, 5};
            // resolution 2 (the highest) holds 2x2 precincts of 32x32; precinct 1 derives from epsilon_0 = 12
            writer.mainSegments.add(qpd(false, 2, 1, 3 << 5 | 1, words(12 << 11 | 300)));
            var override = Jp2Part2QuantizationTest.quantization(Quantization.SCALAR_DERIVED, 3, 12, 300, 0);
            writer.precinctQuantizations = new PrecinctQuantizations[]{new PrecinctQuantizations(
                    Map.of(PrecinctQuantizations.key(2, 1), override), Map.of(), Map.of(), Map.of(), false)};
            var plane = smooth(random, 64, 64, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decompose(coefficients, 64, 0, 0, 0, 0, 64, 64, Part2Forward.Tree.dyadic(2),
                    Part2Forward.DAUBECHIES);
            var checked = new int[1];
            var data = writer.write(new float[][]{floats(coefficients)},
                    (values, indices, w, h, stepSize, resolution, orientation, precinct) -> {
                        if (resolution == 2 && precinct == 1) {
                            // n_b = 1 in a 2-level decomposition: epsilon_b = 12 - 2 + 1; gains 1, 1, 2 (E-4)
                            var gain = orientation == 3 ? 2 : 1;
                            assertEquals((float) ((1 + 300 / 2048.0) * Math.pow(2, 8 + gain - 11)), stepSize,
                                    "orientation " + orientation);
                            checked[0]++;
                        }
                        for (var i = 0; i < values.length; i++) {
                            indices[i] = (int) (Math.signum(values[i]) * Math.floor(Math.abs(values[i]) / stepSize));
                        }
                    });
            assertTrue(checked[0] >= 3, "the precinct's three sub-bands are quantized");
            var decoded = samples(decode(data).channels().getFirst());
            Tolerance.maxAbs(2).assertWithin("precinct round trip", toInts(plane), toInts(decoded));
            var ignored = indexOf(data, new byte[]{(byte) 0xFF, 0x5A});
            data[ignored + 1] = 0x64;
            assertNotEquals(Arrays.toString(toInts(plane)), Arrays.toString(toInts(samples(decode(data).channels()
                    .getFirst()))));
        }

        @Test
        @DisplayName("a tile-part header with 16-bit TPsot and TNsot fields is read under the precinct capability")
        void wideStartOfTile() {
            var writer = flat(32, 32, 1);
            writer.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
            var q = indices(new Random(13), 1024, 50);
            var data = writer.write(new long[][]{q});
            var expected = samples(decode(data).channels().getFirst());
            var widened = widenSot(data);
            assertArrayEquals(expected, samples(decode(widened).channels().getFirst()));
            var plain = widened.clone();
            plain[6] &= (byte) ~(Codestream.RSIZ_PRECINCT_QUANTIZATION >> 8);
            assertThrows(MediaException.Malformed.class, () -> decode(plain));
        }

        /** Rewrites the only SOT segment with 16-bit TPsot and TNsot fields (ISO/IEC 15444-2 Table A.3). */
        private static byte[] widenSot(byte[] data) {
            var at = indexOf(data, new byte[]{(byte) 0xFF, (byte) 0x90});
            var out = new ByteArrayOutputStream();
            out.write(data, 0, at);
            Part2Forward.u16(out, Codestream.SOT);
            Part2Forward.u16(out, 12);
            out.write(data, at + 4, 2);
            var length = ((data[at + 6] & 0xFFL) << 24) | ((data[at + 7] & 0xFF) << 16) | ((data[at + 8] & 0xFF) << 8)
                    | (data[at + 9] & 0xFF);
            Part2Forward.u32(out, length + 2);
            Part2Forward.u16(out, data[at + 10] & 0xFF);
            Part2Forward.u16(out, data[at + 11] & 0xFF);
            out.write(data, at + 12, data.length - at - 12);
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("derived step sizes of sub-bands split in one direction (E-5)")
    class OneDirection {
        private static final Path FIXTURES = Path.of("src", "test", "resources", "com", "github", "auties00",
                "smalt", "image", "jp2", "kakadu-e5");

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hv-b", "vh-b-guard3", "b-hv-floor", "b-subbands", "mixed", "nested", "vertical"})
        @DisplayName("decodes Kakadu's codestreams as Kakadu decodes them")
        void kakadu(String name) throws Exception {
            var sums = new HashMap<String, String>();
            for (var line : new String(Fixtures.read(FIXTURES.resolve("SHA256SUMS")), StandardCharsets.US_ASCII)
                    .split("\n")) {
                if (!line.isBlank()) {
                    var parts = line.trim().split(" +");
                    sums.put(parts[1], parts[0]);
                }
            }
            var codestream = Fixtures.read(FIXTURES.resolve(name + ".j2c"));
            var reference = Fixtures.read(FIXTURES.resolve(name + ".pgm"));
            var digest = MessageDigest.getInstance("SHA-256");
            assertEquals(sums.get(name + ".j2c"), HexFormat.of().formatHex(digest.digest(codestream)), "fixture hash");
            assertEquals(sums.get(name + ".pgm"), HexFormat.of().formatHex(digest.digest(reference)), "fixture hash");
            var decoded = decode(codestream).channels().getFirst().samples();
            var kakadu = new int[decoded.length];
            var offset = reference.length - decoded.length;
            for (var i = 0; i < kakadu.length; i++) {
                kakadu[i] = reference[offset + i] & 0xFF;
            }
            // Kakadu's 9-7 synthesis rounds differently from the decoder's single-precision one
            Tolerance.offByOne().assertWithin(name, kakadu, decoded);
            Tolerance.rms(0.35).assertWithin(name, kakadu, decoded);
        }
    }

    private static int[] toInts(long[] values) {
        var result = new int[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = (int) values[i];
        }
        return result;
    }

    static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i <= data.length - pattern.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("pattern not found");
    }
}
