package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares proofing transforms, black-preserving chains, floating-point transforms and the profiles Smalt builds with
 * Little CMS 2.17, through the reference outputs that {@code tools/testdata/icc-proofing/smalt-lcmsproof.c} writes into
 * {@code proofing/}: 8-bit outputs within one code value, the gamut alarm marks exactly, the linked pipeline within
 * one 16-bit unit of Little CMS's unoptimized transform, 16-bit outputs at least as close to it as Little CMS's own,
 * and floating-point outputs within {@code 1e-5} of Little CMS's floating-point transform (L*a*b* in units of the
 * encoding range). Profiles named {@code lcms-*} are built by Little CMS on one side and by Smalt with the same
 * parameters on the other; profiles named {@code smalt-*} are the bytes Smalt writes, read by both.
 */
class LcmsProofingDifferentialTest {
    private static final Tolerance EIGHT_BIT = Tolerance.offByOne();

    private static final Tolerance PIPELINE = Tolerance.offByOne();

    private static final Tolerance MASK = Tolerance.exact();

    private static final Tolerance FLOAT = Tolerance.maxAbs(1e-5);

    static Stream<Arguments> cases() {
        return ProofingFixtures.table("cases.tsv").stream().map(row -> Arguments.of(row[0], row));
    }

    static Stream<Arguments> smaltProfiles() {
        return ProofingFixtures.table("smalt-profiles.tsv").stream().map(row -> Arguments.of(row[0], row[1]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smaltProfiles")
    @DisplayName("Smalt writes the profile bytes the Little CMS outputs were computed from")
    void smaltProfileBytes(String file, String sha256) throws NoSuchAlgorithmException {
        var name = file.substring(0, file.length() - ".icc".length());
        var bytes = ProofingFixtures.profile(name).toByteArray();
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(sha256, digest, file + " changed: regenerate proofing/ with tools/testdata/icc-proofing/build.sh");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches Little CMS at 8 bits, 16 bits and in floating point")
    void matchesLittleCms(String name, String[] row) {
        var transform = ProofingFixtures.transform(row);
        var inputs = transform.inputChannels();
        var outputs = transform.outputChannels();
        if (transform.isNamed()) {
            named(name, transform);
            return;
        }
        var gamut = row[1].equals("proof") && row[6].equals("1");
        var in8 = ProofingFixtures.inputs8(inputs);
        var pixels8 = in8.length / inputs;
        var actual8 = new byte[pixels8 * outputs];
        transform.transform(in8, 0, inputs, actual8, 0, outputs, pixels8);
        var expected8 = ProofingFixtures.resource(name + ".8");
        if (gamut) {
            var wide = new short[in8.length];
            for (var i = 0; i < in8.length; i++) {
                wide[i] = (short) ((in8[i] & 0xFF) * 257);
            }
            MASK.assertWithin(name + " 8-bit gamut marks", ProofingFixtures.resource(name + ".g8"),
                    bytes(marks(transform, wide, pixels8)));
        }
        EIGHT_BIT.assertWithin(name + " 8-bit", expected8, actual8);

        var in16 = ProofingFixtures.inputs16(inputs);
        var pixels16 = in16.length / inputs;
        var actual16 = new short[pixels16 * outputs];
        transform.transform(in16, 0, inputs, actual16, 0, outputs, pixels16);
        var optimized16 = ProofingFixtures.words(ProofingFixtures.resource(name + ".16"));
        var unoptimized16 = ProofingFixtures.words(ProofingFixtures.resource(name + ".x16"));
        boolean[] marks16 = null;
        if (gamut) {
            marks16 = marks(transform, in16, pixels16);
            MASK.assertWithin(name + " 16-bit gamut marks", ProofingFixtures.resource(name + ".g16"), bytes(marks16));
        }
        var pipeline = transform.pipeline();
        var in = new int[inputs];
        var out = new int[outputs];
        var fromPipeline = new int[pixels16 * outputs];
        var expectedPipeline = new int[pixels16 * outputs];
        for (var p = 0; p < pixels16; p++) {
            if (marks16 != null && marks16[p]) {
                continue;
            }
            for (var c = 0; c < inputs; c++) {
                in[c] = in16[p * inputs + c] & 0xFFFF;
            }
            pipeline.evaluate16(in, out);
            for (var o = 0; o < outputs; o++) {
                fromPipeline[p * outputs + o] = out[o];
                expectedPipeline[p * outputs + o] = unoptimized16[p * outputs + o];
            }
        }
        PIPELINE.assertWithin(name + " pipeline", expectedPipeline, fromPipeline);
        var squares = 0.0;
        var squaresOptimized = 0.0;
        var max = 0;
        var maxOptimized = 0;
        for (var i = 0; i < actual16.length; i++) {
            var diff = Math.abs((actual16[i] & 0xFFFF) - unoptimized16[i]);
            var optimized = Math.abs(optimized16[i] - unoptimized16[i]);
            squares += (double) diff * diff;
            squaresOptimized += (double) optimized * optimized;
            max = Math.max(max, diff);
            maxOptimized = Math.max(maxOptimized, optimized);
        }
        var rms = Math.sqrt(squares / actual16.length);
        var rmsOptimized = Math.sqrt(squaresOptimized / actual16.length);
        if (marks16 != null) {
            var alarm = transform.proofingOptions().orElseThrow().alarm(outputs);
            for (var p = 0; p < pixels16; p++) {
                if (marks16[p]) {
                    for (var c = 0; c < outputs; c++) {
                        assertEquals(alarm[c], actual16[p * outputs + c] & 0xFFFF, name + " 16-bit alarm");
                        assertEquals(alarm[c], optimized16[p * outputs + c], name + " Little CMS 16-bit alarm");
                    }
                }
            }
        }
        System.out.printf("lcms %-30s 16-bit rms %8.2f max %5d, Little CMS rms %8.2f max %5d%n", name, rms, max,
                rmsOptimized, maxOptimized);
        assertTrue(rms <= rmsOptimized + 0.5 || max <= maxOptimized + 1, name + ": 16-bit rms error " + rms
                + " and max error " + max + ", Little CMS " + rmsOptimized + " and " + maxOptimized);

        var inf = ProofingFixtures.inputsFloat(transform.source().colorSpace(), inputs);
        if (BlackPreservingIntents.isInkSpace(transform.entrySpace())) {
            // Little CMS reads ink amounts as percents: give Smalt the values it divided back
            for (var i = 0; i < inf.length; i++) {
                inf[i] = (inf[i] * 100.0f) / 100.0f;
            }
        }
        var pixelsFloat = inf.length / inputs;
        var actualFloat = new float[pixelsFloat * outputs];
        transform.transform(inf, 0, inputs, actualFloat, 0, outputs, pixelsFloat);
        var expectedFloat = ProofingFixtures.floats(ProofingFixtures.resource(name + ".f32"));
        FLOAT.assertPcm(name + " float", normalize(expectedFloat, transform), normalize(actualFloat, transform));
    }

    // named color transforms are compared at 16 bits only, which Little CMS never optimizes
    private static void named(String name, ColorTransform transform) {
        var colors = transform.source().namedColors().size();
        var outputs = transform.outputChannels();
        var indexes = new short[colors + 4];
        for (var i = 0; i < colors; i++) {
            indexes[i] = (short) i;
        }
        indexes[colors] = (short) colors;
        indexes[colors + 1] = (short) (colors + 1);
        indexes[colors + 2] = 1000;
        indexes[colors + 3] = (short) 65535;
        var actual = new short[indexes.length * outputs];
        transform.transform(indexes, 0, 1, actual, 0, outputs, indexes.length);
        var expected = ProofingFixtures.words(ProofingFixtures.resource(name + ".16"));
        var widened = new int[actual.length];
        for (var i = 0; i < actual.length; i++) {
            widened[i] = actual[i] & 0xFFFF;
        }
        PIPELINE.assertWithin(name + " 16-bit", expected, widened);
    }

    static boolean[] marks(ColorTransform transform, short[] in, int pixels) {
        var check = transform.proofingGamutCheck();
        var inputs = transform.inputChannels();
        var words = new int[check.inputs()];
        var marker = new int[1];
        var levels = check.scratch();
        var marks = new boolean[pixels];
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < inputs; c++) {
                words[c] = in[p * inputs + c] & 0xFFFF;
            }
            marks[p] = check.isOutOfGamut(words, marker, levels);
        }
        return marks;
    }

    private static byte[] bytes(boolean[] marks) {
        var out = new byte[marks.length];
        for (var i = 0; i < marks.length; i++) {
            out[i] = (byte) (marks[i] ? 1 : 0);
        }
        return out;
    }

    // L*a*b* outputs are compared in units of the encoding range
    private static float[] normalize(float[] values, ColorTransform transform) {
        if (transform.exitSpace() != IccReader.signature("Lab ")) {
            return values;
        }
        var out = new float[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = (float) (i % 3 == 0 ? values[i] / 100.0 : (values[i] + 128.0) / 255.0);
        }
        return out;
    }
}
