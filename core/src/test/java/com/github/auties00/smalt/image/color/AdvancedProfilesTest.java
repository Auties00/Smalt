package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks floating-point {@code multiProcessElementType} tables, device links, abstract profiles, named colors and
 * profile chains against Little CMS 2.17, through the profiles and reference outputs that
 * {@code tools/testdata/icc-advanced/smalt-lcmsadvanced.c} writes into {@code advanced/}: the pipelines of the
 * floating-point tags value for value; for the input sets that program documents, 8-bit transforms within one code
 * value of Little CMS's, the linked pipeline within one 16-bit unit of Little CMS's unoptimized one, and 16-bit
 * transforms at least as close to that unoptimized pipeline as Little CMS's 16-bit transforms in root mean square
 * or in maximum error (both sample the pipeline into lookup tables, Smalt with prelinearization curves when they
 * measure better, or without tables on some shapes).
 */
class AdvancedProfilesTest {
    static byte[] resource(String name) {
        try (InputStream in = AdvancedProfilesTest.class.getResourceAsStream("advanced/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static ColorProfile profile(String name) {
        return ColorProfile.parse(resource(name + ".icc"));
    }

    static List<String[]> table(String name) {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource(name), StandardCharsets.UTF_8).split("\r?\n")) {
            if (!line.isEmpty()) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    static int levels8(int channels) {
        return switch (channels) {
            case 2, 3 -> 17;
            case 4 -> 9;
            default -> 4;
        };
    }

    static int levels16(int channels) {
        return switch (channels) {
            case 1 -> 257;
            case 2, 3 -> 9;
            case 4 -> 5;
            default -> 3;
        };
    }

    static int power(int base, int exponent) {
        var result = 1;
        for (var i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }

    static byte[] inputs8(int channels) {
        if (channels == 1) {
            var out = new byte[256];
            for (var i = 0; i < 256; i++) {
                out[i] = (byte) i;
            }
            return out;
        }
        var levels = levels8(channels);
        var total = power(levels, channels);
        var out = new byte[(total + 2048) * channels];
        for (var index = 0; index < total; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                var i = rest % levels;
                out[index * channels + c] = (byte) ((i * 255 + (levels - 1) / 2) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0x9E3779B97F4A7C15L;
        for (var i = total * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    static short[] inputs16(int channels) {
        var levels = levels16(channels);
        var total = power(levels, channels);
        var out = new short[(total + 1024) * channels];
        for (var index = 0; index < total; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                var i = rest % levels;
                out[index * channels + c] = (short) ((i * 65535 + (levels - 1) / 2) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0xD1B54A32D192ED03L;
        for (var i = total * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (short) (state >>> 48);
        }
        return out;
    }

    static int levelsFloat(int channels) {
        return channels <= 6 ? new int[]{101, 17, 9, 6, 5, 4}[channels - 1] : 3;
    }

    static float[][] inputsFloat(double[] lo, double[] hi) {
        var channels = lo.length;
        var levels = levelsFloat(channels);
        var lattice = power(levels, channels);
        var out = new float[lattice + 256][channels];
        for (var index = 0; index < lattice; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index][c] = (float) (lo[c] + (hi[c] - lo[c]) * (double) (rest % levels) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0x2545F4914F6CDD1DL;
        for (var index = lattice; index < out.length; index++) {
            for (var c = 0; c < channels; c++) {
                state = lcg(state);
                var u = (double) (state >>> 40) / 16777216.0;
                out[index][c] = (float) (lo[c] + (hi[c] - lo[c]) * (u * 1.4 - 0.2));
            }
        }
        return out;
    }

    static int[] indexes(int colors) {
        var out = new int[colors + 4];
        for (var i = 0; i < colors; i++) {
            out[i] = i;
        }
        out[colors] = colors;
        out[colors + 1] = colors + 1;
        out[colors + 2] = 1000;
        out[colors + 3] = 65535;
        return out;
    }

    static ColorTransformOptions options(String intent, String bpc) {
        return LcmsOracleTest.options(intent, bpc);
    }

    static List<ColorProfile> chain(String names) {
        var chain = new ArrayList<ColorProfile>();
        for (var name : names.split(",")) {
            chain.add(profile(name));
        }
        return chain;
    }

    static int[] words(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var out = new int[data.length / 2];
        for (var i = 0; i < out.length; i++) {
            out[i] = buffer.getShort() & 0xFFFF;
        }
        return out;
    }

    static Stream<Arguments> cases() {
        return table("cases.tsv").stream().map(row -> Arguments.of(row[0], row[1], row[2], row[3], row[4]));
    }

    static Stream<Arguments> pipelines() {
        return table("pipelines.tsv").stream().map(row -> Arguments.of(row[0], row[1], row[2]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("transforms match Little CMS: 8-bit within one code value, the pipeline within one 16-bit unit")
    void matchesLittleCms(String name, String profiles, String intent, String bpc, String input) {
        var transform = ColorTransform.create(chain(profiles), options(intent, bpc));
        var outputs = transform.outputChannels();
        var expected8 = resource(name + ".8");
        var optimized16 = words(resource(name + ".16"));
        var expected16 = words(resource(name + ".x16"));
        byte[] actual8;
        byte[] reference8;
        short[] in16;
        if (input.equals("index")) {
            assertEquals(1, transform.inputChannels());
            var colors = transform.source().namedColors().size();
            var all = indexes(colors);
            var pixels = expected16.length / outputs;
            in16 = new short[pixels];
            for (var i = 0; i < pixels; i++) {
                in16[i] = (short) all[i];
            }
            var narrow = new ArrayList<Integer>();
            for (var i = 0; i < pixels; i++) {
                if (all[i] < 256) {
                    narrow.add(i);
                }
            }
            var in8 = new byte[narrow.size()];
            reference8 = new byte[narrow.size() * outputs];
            for (var k = 0; k < narrow.size(); k++) {
                in8[k] = (byte) all[narrow.get(k)];
                System.arraycopy(expected8, narrow.get(k) * outputs, reference8, k * outputs, outputs);
            }
            actual8 = new byte[in8.length * outputs];
            transform.transform(in8, 0, 1, actual8, 0, outputs, in8.length);
        } else {
            var channels = Integer.parseInt(input);
            assertEquals(channels, transform.inputChannels());
            var in8 = inputs8(channels);
            actual8 = new byte[in8.length / channels * outputs];
            transform.transform(in8, 0, channels, actual8, 0, outputs, in8.length / channels);
            reference8 = expected8;
            in16 = inputs16(channels);
        }
        assertEquals(reference8.length, actual8.length, name + ": 8-bit output length");
        var actual16 = new short[in16.length / transform.inputChannels() * outputs];
        transform.transform(in16, 0, transform.inputChannels(), actual16, 0, outputs,
                in16.length / transform.inputChannels());
        assertEquals(expected16.length, actual16.length, name + ": 16-bit output length");
        var max8 = 0;
        var worst8 = -1;
        for (var i = 0; i < actual8.length; i++) {
            var diff = Math.abs((actual8[i] & 0xFF) - (reference8[i] & 0xFF));
            if (diff > max8) {
                max8 = diff;
                worst8 = i;
            }
        }
        var max16 = 0;
        var worst16 = -1;
        var squares = 0.0;
        var squaresOptimized = 0.0;
        var maxOptimized = 0;
        for (var i = 0; i < actual16.length; i++) {
            var diff = Math.abs((actual16[i] & 0xFFFF) - expected16[i]);
            if (diff > max16) {
                max16 = diff;
                worst16 = i;
            }
            squares += (double) diff * diff;
            var optimized = optimized16[i] - expected16[i];
            squaresOptimized += (double) optimized * optimized;
            maxOptimized = Math.max(maxOptimized, Math.abs(optimized));
        }
        var rms = Math.sqrt(squares / actual16.length);
        var rmsOptimized = Math.sqrt(squaresOptimized / actual16.length);
        var pipeline = transform.pipeline();
        var channels = transform.inputChannels();
        var in = new int[channels];
        var out = new int[outputs];
        var maxPipeline = 0;
        var worstPipeline = -1;
        for (var p = 0; p < in16.length / channels; p++) {
            for (var c = 0; c < channels; c++) {
                in[c] = in16[p * channels + c] & 0xFFFF;
            }
            pipeline.evaluate16(in, out);
            for (var o = 0; o < outputs; o++) {
                var diff = Math.abs(out[o] - expected16[p * outputs + o]);
                if (diff > maxPipeline) {
                    maxPipeline = diff;
                    worstPipeline = p * outputs + o;
                }
            }
        }
        System.out.printf("lcms %-26s 8-bit max %3d, pipeline max %3d, 16-bit max %5d (sample %6d) rms %8.2f, Little"
                + " CMS rms %8.2f%n", name, max8, maxPipeline, max16, worst16, rms, rmsOptimized);
        assertTrue(max8 <= 1, name + ": 8-bit max difference " + max8 + " at sample " + worst8);
        assertTrue(maxPipeline <= 1, name + ": pipeline max difference " + maxPipeline + " at sample "
                + worstPipeline);
        assertTrue(rms <= rmsOptimized + 0.5 || max16 <= maxOptimized + 1, name + ": 16-bit rms error " + rms
                + " and max error " + max16 + " (sample " + worst16 + "), Little CMS " + rmsOptimized + " and "
                + maxOptimized);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pipelines")
    @DisplayName("floating-point tags evaluate as Little CMS evaluates them, within two units in the last place")
    void floatPipelines(String name, String tag, String ranges) {
        var parts = ranges.split(",");
        var lo = new double[parts.length];
        var hi = new double[parts.length];
        for (var c = 0; c < parts.length; c++) {
            var bounds = parts[c].split(":");
            lo[c] = Double.parseDouble(bounds[0]);
            hi[c] = Double.parseDouble(bounds[1]);
        }
        var pipeline = profile(name).icc().lut(IccReader.signature(tag));
        assertEquals(parts.length, pipeline.inputs());
        var outputs = pipeline.outputs();
        var expected = ByteBuffer.wrap(resource(name + "." + tag + ".f32")).order(ByteOrder.LITTLE_ENDIAN);
        var inputs = inputsFloat(lo, hi);
        assertEquals(inputs.length * outputs * 4, expected.capacity());
        var out = new float[outputs];
        var worst = 0.0;
        for (var input : inputs) {
            pipeline.evaluate(input, out);
            for (var o = 0; o < outputs; o++) {
                var reference = expected.getFloat();
                var ulps = Math.abs(out[o] - reference) / Math.ulp(Math.max(Math.abs(reference), 1e-3f));
                worst = Math.max(worst, ulps);
                if (!(ulps <= 2)) {
                    fail(name + " " + tag + " at " + Arrays.toString(input) + ": " + out[o] + " instead of "
                            + reference);
                }
            }
        }
        System.out.printf("lcms %s %s: worst %.2f ulp%n", name, tag, worst);
    }

    @Nested
    @DisplayName("segmented curves")
    class Segmented {
        static ToneCurve.Segment formula(float x0, float x1, int type, double... parameters) {
            return new ToneCurve.Segment(x0, x1, type, parameters, null);
        }

        @Test
        @DisplayName("evaluate the three formulas with their guards")
        void formulas() {
            var minus = ToneCurve.MINUS_INFINITY;
            var plus = ToneCurve.PLUS_INFINITY;
            var power = ToneCurve.segmented(new ToneCurve.Segment[]{formula(minus, plus, 6, 2.0, 1.0, -0.25, 0.125)});
            assertEquals((float) (Math.pow(0.5, 2.0) + 0.125), power.evaluate(0.75f));
            assertEquals(0.125f, power.evaluate(0.1f));
            var linear = ToneCurve.segmented(new ToneCurve.Segment[]{formula(minus, plus, 6, 1.0, 2.0, -0.5, 0.0)});
            assertEquals(-0.5f, linear.evaluate(0.0f));
            var log = ToneCurve.segmented(new ToneCurve.Segment[]{formula(minus, plus, 7, 1.0, 2.0, 9.0, 1.0, 0.5)});
            assertEquals((float) (2.0 * Math.log10(9.0 * 0.5 + 1.0) + 0.5), log.evaluate(0.5f));
            assertEquals(0.5f, log.evaluate(-0.2f));
            var exponential = ToneCurve.segmented(new ToneCurve.Segment[]{formula(minus, plus, 8, 0.5, 2.0, 3.0, 1.0,
                    -0.25)});
            assertEquals((float) (0.5 * Math.pow(2.0, 3.0 * 0.25 + 1.0) - 0.25), exponential.evaluate(0.25f));
            assertEquals(ToneCurve.PLUS_INFINITY, exponential.evaluate(1e20f));
            assertEquals(ToneCurve.MINUS_INFINITY, exponential.evaluate(Float.NaN));
        }

        @Test
        @DisplayName("start a sampled segment at the value of the segment before it")
        void sampledStart() {
            var curve = ToneCurve.segmented(new ToneCurve.Segment[]{
                formula(ToneCurve.MINUS_INFINITY, 0.5f, 6, 1.0, 1.0, 0.25, 0.0),
                new ToneCurve.Segment(0.5f, 1.0f, 0, null, new float[]{99, 1.0f, 2.0f}),
                formula(1.0f, ToneCurve.PLUS_INFINITY, 6, 1.0, 0.0, 5.0, 0.0)});
            // the implicit first sample is the first segment at 0.5, that is 0.75
            assertEquals(0.875f, curve.evaluate(0.625f));
            assertEquals(1.0f, curve.evaluate(0.75f));
            assertEquals(2.0f, curve.evaluate(1.0f));
            assertEquals(5.0f, curve.evaluate(3.0f));
        }

        @Test
        @DisplayName("apply the last segment whose domain holds the input when breakpoints descend")
        void descending() {
            var curve = ToneCurve.segmented(new ToneCurve.Segment[]{
                formula(ToneCurve.MINUS_INFINITY, 0.5f, 6, 1.0, 0.0, 1.0, 0.0),
                formula(0.5f, 0.2f, 6, 1.0, 0.0, 2.0, 0.0),
                formula(0.2f, ToneCurve.PLUS_INFINITY, 6, 1.0, 0.0, 3.0, 0.0)});
            assertEquals(1.0f, curve.evaluate(0.1f));
            assertEquals(3.0f, curve.evaluate(0.3f));
            assertEquals(3.0f, curve.evaluate(0.7f));
            var contiguous = ToneCurve.segmented(new ToneCurve.Segment[]{
                formula(ToneCurve.MINUS_INFINITY, 0.2f, 6, 1.0, 0.0, 1.0, 0.0),
                formula(0.2f, 0.2f, 6, 1.0, 0.0, 2.0, 0.0),
                formula(0.2f, 0.5f, 6, 1.0, 0.0, 3.0, 0.0),
                formula(0.5f, ToneCurve.PLUS_INFINITY, 6, 1.0, 0.0, 4.0, 0.0)});
            assertEquals(1.0f, contiguous.evaluate(0.2f));
            assertEquals(3.0f, contiguous.evaluate(0.2000001f));
            assertEquals(3.0f, contiguous.evaluate(0.5f));
            assertEquals(4.0f, contiguous.evaluate(0.6f));
            assertEquals(1.0f, contiguous.evaluate(-1e21f));
            assertEquals(ToneCurve.MINUS_INFINITY, contiguous.evaluate(-1e23f));
        }

        @Test
        @DisplayName("pick the segment a reverse scan of every segment picks, for any breakpoints")
        void matchesReverseScan() {
            var random = new SplittableRandom(0x5E6E);
            for (var round = 0; round < 200; round++) {
                var count = 1 + random.nextInt(40);
                var breaks = new float[count + 1];
                breaks[0] = ToneCurve.MINUS_INFINITY;
                breaks[count] = ToneCurve.PLUS_INFINITY;
                for (var i = 1; i < count; i++) {
                    // a coarse grid makes equal and descending breakpoints common
                    breaks[i] = random.nextInt(5) == 0 ? breaks[i - 1] : (random.nextInt(21) - 10) / 8.0f;
                }
                var segments = new ToneCurve.Segment[count];
                for (var i = 0; i < count; i++) {
                    // the constant output names the segment
                    segments[i] = formula(breaks[i], breaks[i + 1], 6, 1.0, 0.0, i, 0.0);
                }
                var curve = ToneCurve.segmented(segments);
                for (var probe = 0; probe < 300; probe++) {
                    var x = probe < 21 ? (probe - 10) / 8.0f : (float) (random.nextDouble() * 3.0 - 1.5);
                    var expected = (double) ToneCurve.MINUS_INFINITY;
                    for (var i = count - 1; i >= 0; i--) {
                        if (x > segments[i].x0() && x <= segments[i].x1()) {
                            expected = i;
                            break;
                        }
                    }
                    assertEquals((float) expected, curve.evaluate(x), "round " + round + " at " + x);
                }
                assertEquals(ToneCurve.MINUS_INFINITY, curve.evaluate(Float.NaN));
            }
        }
    }

    @Nested
    @DisplayName("named colors")
    class NamedColors {
        @Test
        @DisplayName("lists the names, connection space values and device coordinates Little CMS reads")
        void listMatchesLittleCms() {
            var rows = table("named.tsv");
            assertFalse(rows.isEmpty());
            for (var row : rows) {
                var colors = profile(row[0]).namedColors();
                var color = colors.get(Integer.parseInt(row[1]));
                assertEquals(Integer.parseInt(row[1]), color.index());
                assertEquals(row[3], color.rootName());
                assertEquals(row[2] + row[3] + row[4], color.name());
                var pcs = Arrays.stream(row[5].split(",")).mapToInt(Integer::parseInt).toArray();
                var values = color.connectionSpaceValues();
                if (color.connectionSpace() == ProfileColorSpace.LAB) {
                    assertEquals(pcs[0] * 100.0 / 65280.0, values[0], 1e-12);
                    assertEquals(pcs[1] / 256.0 - 128.0, values[1], 1e-12);
                    assertEquals(pcs[2] / 256.0 - 128.0, values[2], 1e-12);
                } else {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(pcs[c] / 32768.0, values[c], 1e-12);
                    }
                }
                var device = Arrays.stream(row[6].split(",")).mapToInt(Integer::parseInt).toArray();
                var coordinates = color.deviceValues();
                assertEquals(device.length, coordinates.length);
                for (var c = 0; c < device.length; c++) {
                    assertEquals(device[c] / 65535.0, coordinates[c], 1e-12);
                }
            }
        }

        @Test
        @DisplayName("finds colors by root or full name, ignoring case")
        void lookup() {
            var profile = profile("named-cmyk");
            assertEquals(50, profile.namedColors().size());
            assertEquals(3, profile.namedColor("warm red 003").orElseThrow().index());
            assertEquals(3, profile.namedColor("SMALT WARM RED 003 C").orElseThrow().index());
            assertEquals(4, profile.namedColor("Tone 004").orElseThrow().index());
            assertEquals("Smalt Tone 004 C", profile.namedColor("Tone 004").orElseThrow().name());
            assertTrue(profile.namedColor("Tone 999").isEmpty());
            assertTrue(profile.namedColor("Smalt").isEmpty());
            assertEquals(ProfileClass.NAMED_COLOR, profile.deviceClass());
            assertEquals(ProfileColorSpace.XYZ, profile("named-rgb-xyz").namedColors().getFirst().connectionSpace());
        }

        @Test
        @DisplayName("other profiles have no named colors")
        void otherProfiles() {
            assertTrue(ColorProfile.srgb().namedColors().isEmpty());
            assertTrue(ColorProfile.srgb().namedColor("Tone 001").isEmpty());
            assertTrue(profile("mpe-cmyk").namedColors().isEmpty());
        }

        @Test
        @DisplayName("8-bit indexes past the last color convert as the first index past it")
        void outOfRange8() {
            var transform = ColorTransform.create(List.of(profile("named-cmyk"), profile("lcms-srgb")));
            var in = new byte[]{(byte) 50, (byte) 200, (byte) 255};
            var out = new byte[9];
            transform.transform(in, 0, 1, out, 0, 3, 3);
            var wide = new short[]{(short) 50};
            var wideOut = new short[3];
            transform.transform(wide, 0, 1, wideOut, 0, 3, 1);
            for (var p = 0; p < 3; p++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(ColorMath.wordToByte(wideOut[c] & 0xFFFF), out[p * 3 + c] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("a named color profile converts its listed values")
        void convertsListedValues() {
            var named = profile("named-cmyk");
            var transform = ColorTransform.create(List.of(named));
            assertEquals(4, transform.outputChannels());
            for (var color : named.namedColors()) {
                var out = new short[4];
                transform.transform(new short[]{(short) color.index()}, 0, 1, out, 0, 4, 1);
                var device = color.deviceValues();
                for (var c = 0; c < 4; c++) {
                    assertEquals((int) Math.round(device[c] * 65535.0), out[c] & 0xFFFF);
                }
            }
        }
    }

    @Nested
    @DisplayName("chains")
    class Chains {
        @Test
        @DisplayName("a pair is the transform between its two profiles")
        void pair() {
            var source = profile("lcms-srgb");
            var target = profile("lcms-p3");
            var options = ColorTransformOptions.relativeColorimetric();
            var transform = ColorTransform.create(source, target, options);
            assertSame(transform, ColorTransform.create(List.of(source, target), options));
            assertEquals(List.of(source, target), transform.profiles());
            assertSame(source, transform.source());
            assertSame(target, transform.target());
            assertThrows(UnsupportedOperationException.class, () -> transform.profiles().add(source));
        }

        @Test
        @DisplayName("an abstract profile changes the colors of a chain")
        void abstractChangesColors() {
            var srgb = profile("lcms-srgb");
            var plain = ColorTransform.create(List.of(srgb, srgb));
            var adjusted = ColorTransform.create(List.of(srgb, profile("abstract-mpe-lab"), srgb));
            var in = inputs8(3);
            var a = new byte[in.length];
            var b = new byte[in.length];
            plain.transform(in, 0, 3, a, 0, 3, in.length / 3);
            adjusted.transform(in, 0, 3, b, 0, 3, in.length / 3);
            assertFalse(Arrays.equals(a, b));
        }

        @Test
        @DisplayName("a device link read through its D2B0 fallback scales its L*a*b* input like the tag of the intent")
        void deviceLinkFallback() {
            var link = profile("link-mpe-lab-rgb");
            var perceptual = ColorTransform.create(List.of(link), ColorTransformOptions.defaults());
            var relative = ColorTransform.create(List.of(link), ColorTransformOptions.relativeColorimetric());
            var in = inputs16(3);
            var a = new short[in.length];
            var b = new short[in.length];
            perceptual.transform(in, 0, 3, a, 0, 3, in.length / 3);
            relative.transform(in, 0, 3, b, 0, 3, in.length / 3);
            assertArrayEquals(a, b);
        }

        @Test
        @DisplayName("floating-point tables take precedence over the 16-bit tables of the same intent")
        void floatPrecedence() {
            var profile = profile("mpe-rgb");
            var srgb = profile("lcms-srgb");
            var perceptual = ColorTransform.create(profile, srgb, ColorTransformOptions.defaults());
            var relative = ColorTransform.create(profile, srgb, ColorTransformOptions.relativeColorimetric());
            var in = new byte[]{(byte) 200, (byte) 100, (byte) 50};
            var a = new byte[3];
            var b = new byte[3];
            perceptual.transform(in, 0, 3, a, 0, 3, 1);
            relative.transform(in, 0, 3, b, 0, 3, 1);
            assertFalse(Arrays.equals(a, b));
            assertFalse(profile.isSrgb());
        }

        @Test
        @DisplayName("rejects empty and overlong chains")
        void length() {
            assertThrows(IllegalArgumentException.class, () -> ColorTransform.create(List.of()));
            var many = new ArrayList<ColorProfile>();
            for (var i = 0; i < 256; i++) {
                many.add(ColorProfile.srgb());
            }
            assertThrows(IllegalArgumentException.class, () -> ColorTransform.create(many));
            var nulls = new ArrayList<ColorProfile>();
            nulls.add(null);
            assertThrows(NullPointerException.class, () -> ColorTransform.create(nulls));
        }

        @Test
        @DisplayName("rejects named color profiles after the first position")
        void namedNotFirst() {
            var named = profile("named-cmyk");
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(List.of(ColorProfile.srgb(), named)));
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(List.of(ColorProfile.srgb(), named, ColorProfile.srgb())));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"link-rgb-rgb-v2", "link-mpe-rgb", "abstract-bchsw-v4", "abstract-mpe-lab",
            "named-rgb-xyz"})
        @DisplayName("a source-target pair rejects device links, abstract and named color profiles, which only chain")
        void pairRejectsChainProfiles(String name) {
            var profile = profile(name);
            var srgb = ColorProfile.srgb();
            assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(profile, srgb));
            assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(srgb, profile));
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(profile, srgb, ColorTransformOptions.relativeColorimetric()));
            var chain = profile.deviceClass() == ProfileClass.ABSTRACT ? List.of(srgb, profile, srgb)
                    : List.of(profile, srgb);
            assertEquals(3, ColorTransform.create(chain).outputChannels());
        }

        @Test
        @DisplayName("rejects profiles whose color spaces do not connect")
        void mismatch() {
            var link = profile("link-rgb-cmyk-v4");
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(List.of(link, profile("lcms-srgb"))));
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(List.of(profile("lcms-srgb"), link)));
        }

        @Test
        @DisplayName("rejects high-dynamic-range profiles in chains that are not pairs")
        void highDynamicRange() {
            var pq = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ));
            assertThrows(IllegalArgumentException.class,
                    () -> ColorTransform.create(List.of(pq, profile("abstract-mpe-lab"), ColorProfile.srgb())));
            assertThrows(IllegalArgumentException.class, () -> ColorTransform.create(List.of(pq)));
            assertNotEquals(null, ColorTransform.create(List.of(pq, ColorProfile.srgb())));
        }

        @Test
        @DisplayName("named colors convert to a high-dynamic-range target by index at both depths")
        void namedToHighDynamicRange() {
            var pq = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ));
            var named = profile("named-cmyk");
            assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(named, pq));
            var transform = ColorTransform.create(List.of(named, pq));
            assertEquals(1, transform.inputChannels());
            assertEquals(3, transform.outputChannels());
            var colors = named.namedColors().size();
            var in8 = new byte[colors + 1];
            var in16 = new short[colors + 1];
            for (var i = 0; i <= colors; i++) {
                in8[i] = (byte) i;
                in16[i] = (short) i;
            }
            var out8 = new byte[in8.length * 3];
            var out16 = new short[in16.length * 3];
            transform.transform(in8, 0, 1, out8, 0, 3, in8.length);
            transform.transform(in16, 0, 1, out16, 0, 3, in16.length);
            var distinct = new HashSet<String>();
            for (var i = 0; i < in8.length; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals((out16[i * 3 + c] & 0xFFFF) / 257.0, out8[i * 3 + c] & 0xFF, 1.0, "color " + i);
                }
                distinct.add(Arrays.toString(Arrays.copyOfRange(out16, i * 3, i * 3 + 3)));
            }
            // the colors are random, so nearly every one converts to its own value
            assertTrue(distinct.size() > colors / 2, distinct.size() + " distinct outputs");
        }

        @Test
        @DisplayName("a single input profile ends in its connection space")
        void connectionSpaceOutput() {
            var transform = ColorTransform.create(List.of(profile("lcms-srgb")));
            assertEquals(3, transform.outputChannels());
            var out = new short[3];
            transform.transform(new short[]{(short) 0xFFFF, (short) 0xFFFF, (short) 0xFFFF}, 0, 3, out, 0, 3, 1);
            // D50 white in the u1Fixed15 XYZ encoding
            assertEquals(0.9642 * 32768, out[0] & 0xFFFF, 40);
            assertEquals(32768, out[1] & 0xFFFF, 40);
            assertEquals(0.8249 * 32768, out[2] & 0xFFFF, 40);
        }
    }

    @Nested
    @DisplayName("damaged profiles")
    class Damaged {
        static void exercise(byte[] data) {
            ColorProfile profile;
            try {
                profile = ColorProfile.parse(data);
            } catch (MediaException expected) {
                return;
            }
            try {
                profile.namedColors();
                profile.namedColor("Tone 001");
            } catch (MediaException expected) {
                // a damaged ncl2 tag
            }
            var srgb = ColorProfile.srgb();
            for (var chain : List.of(List.of(profile), List.of(profile, srgb), List.of(srgb, profile),
                    List.of(srgb, profile, srgb))) {
                ColorTransform transform;
                try {
                    transform = ColorTransform.create(chain, ColorTransformOptions.relativeColorimetric());
                } catch (MediaException expected) {
                    continue;
                }
                var in = new byte[transform.inputChannels() * 64];
                new SplittableRandom(data.length).nextBytes(in);
                var out = new byte[transform.outputChannels() * 64];
                transform.transform(in, 0, transform.inputChannels(), out, 0, transform.outputChannels(), 64);
                var in16 = new short[in.length];
                var out16 = new short[out.length];
                transform.transform(in16, 0, transform.inputChannels(), out16, 0, transform.outputChannels(), 64);
            }
        }

        static int[] tagRange(byte[] data, String... signatures) {
            var count = ByteBuffer.wrap(data).getInt(128);
            for (var i = 0; i < count; i++) {
                var entry = 132 + i * 12;
                var signature = new String(data, entry, 4, StandardCharsets.ISO_8859_1);
                for (var wanted : signatures) {
                    if (signature.equals(wanted)) {
                        var buffer = ByteBuffer.wrap(data);
                        return new int[]{buffer.getInt(entry + 4), buffer.getInt(entry + 8)};
                    }
                }
            }
            return new int[]{128, data.length - 128};
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mpe-rgb", "mpe-cmyk", "mpe-gray", "mpe-2clr", "link-mpe-rgb", "link-mpe-lab-rgb",
            "link-rgb-rgb-v2", "abstract-mpe-xyz", "abstract-mpe-lab", "abstract-bchsw-v4", "named-cmyk",
            "named-rgb-xyz"})
        @DisplayName("mutated mpet and ncl2 tags fail only with MediaException")
        void mutations(String name) {
            var original = resource(name + ".icc");
            var range = tagRange(original, "D2B0", "ncl2", "A2B0");
            var random = new SplittableRandom(name.hashCode());
            for (var round = 0; round < 300; round++) {
                var data = original.clone();
                var edits = 1 + random.nextInt(3);
                for (var e = 0; e < edits; e++) {
                    var at = range[0] + random.nextInt(Math.min(range[1], 160));
                    switch (random.nextInt(4)) {
                        case 0 -> data[at] = (byte) random.nextInt(256);
                        case 1 -> data[at] = (byte) 0xFF;
                        case 2 -> data[at] = 0;
                        default -> data[at] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                exercise(data);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mpe-rgb", "link-mpe-rgb", "abstract-mpe-lab", "named-cmyk"})
        @DisplayName("truncated profiles fail only with MediaException")
        void truncations(String name) {
            var original = resource(name + ".icc");
            var range = tagRange(original, "D2B0", "ncl2");
            for (var length = range[0]; length < range[0] + range[1]; length += Math.max(1, range[1] / 97)) {
                var data = Arrays.copyOf(original, length);
                ByteBuffer.wrap(data).putInt(0, length);
                exercise(data);
            }
        }

        @Test
        @DisplayName("rejects floats that are not zero or normal, and unknown elements")
        void invalidElements() {
            var original = resource("abstract-mpe-xyz.icc");
            var range = tagRange(original, "D2B0");
            var tag = range[0];
            var buffer = ByteBuffer.wrap(original);
            var element = tag + buffer.getInt(tag + 16);
            assertEquals("matf", new String(original, element, 4, StandardCharsets.ISO_8859_1));
            for (var bits : new int[]{Float.floatToRawIntBits(Float.NaN), Float.floatToRawIntBits(Float.MIN_VALUE),
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY), Float.floatToRawIntBits(2e20f)}) {
                var data = original.clone();
                ByteBuffer.wrap(data).putInt(element + 12, bits);
                var profile = ColorProfile.parse(data);
                assertThrows(MediaException.Malformed.class,
                        () -> ColorTransform.create(List.of(ColorProfile.srgb(), profile, ColorProfile.srgb())));
            }
            var data = original.clone();
            data[element] = 'x';
            var profile = ColorProfile.parse(data);
            assertThrows(MediaException.Unsupported.class,
                    () -> ColorTransform.create(List.of(ColorProfile.srgb(), profile, ColorProfile.srgb())));
            var ignored = original.clone();
            System.arraycopy("bACS".getBytes(StandardCharsets.ISO_8859_1), 0, ignored, element, 4);
            var identity = ColorTransform.create(List.of(ColorProfile.srgb(), ColorProfile.parse(ignored),
                    ColorProfile.srgb()), ColorTransformOptions.relativeColorimetric());
            var in = new byte[]{(byte) 10, (byte) 128, (byte) 240};
            var out = new byte[3];
            identity.transform(in, 0, 3, out, 0, 3, 1);
            for (var c = 0; c < 3; c++) {
                assertEquals(in[c] & 0xFF, out[c] & 0xFF, 1);
            }
        }
    }
}
