package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the black-preserving CMYK intents: the black curves and the total area coverage against Little CMS 2.17
 * ({@code proofing/ktone.tsv}, {@code proofing/tac.tsv}), and the properties each mode guarantees on the CMYK input
 * sets; the outputs themselves are compared with Little CMS by {@link LcmsProofingDifferentialTest}.
 */
class BlackPreservationTest {
    static Stream<Arguments> kTones() {
        return ProofingFixtures.table("ktone.tsv").stream().map(row -> Arguments.of(row[0], row[1], row[2], row[3]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kTones")
    @DisplayName("the black curve equals Little CMS's _cmsBuildKToneCurve sample for sample")
    void kTone(String name, String profiles, String intent, String bpc) {
        var list = ProofingFixtures.profiles(profiles);
        var code = ProofingFixtures.intent(intent.charAt(0)).code();
        var nodes = new Linker.Node[list.size()];
        for (var i = 0; i < nodes.length; i++) {
            nodes[i] = Linker.Node.of(list.get(i).icc(), code, bpc.equals("1"));
        }
        var curve = BlackPreservingIntents.kTone(nodes);
        assertTrue(curve != null, name + ": no black curve");
        var expected = ProofingFixtures.words(ProofingFixtures.resource(name + ".kt"));
        var actual = new int[expected.length];
        for (var i = 0; i < actual.length; i++) {
            actual[i] = curve.evaluate16(ColorMath.saturateWord(i * 65535.0 / (actual.length - 1)));
        }
        Tolerance.offByOne().assertWithin(name, expected, actual);
    }

    static Stream<Arguments> coverages() {
        return ProofingFixtures.table("tac.tsv").stream().map(row -> Arguments.of(row[0], Double.parseDouble(row[1])));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coverages")
    @DisplayName("the total area coverage equals Little CMS's cmsDetectTAC")
    void totalAreaCoverage(String name, double expected) {
        var actual = BlackPreservingIntents.detectTotalAreaCoverage(ProofingFixtures.profile(name).icc());
        assertEquals(expected, actual, 1e-4, name);
    }

    static ColorTransform transform(BlackPreservation preservation, RenderingIntent intent) {
        return ColorTransform.create(List.of(ProofingFixtures.profile("swop"), ProofingFixtures.profile("fogra")),
                ColorTransformOptions.builder().intent(intent).blackPreservation(preservation).build());
    }

    @Nested
    @DisplayName("black-only preservation")
    class KOnly {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RenderingIntent.class, names = {"PERCEPTUAL", "RELATIVE_COLORIMETRIC", "SATURATION"})
        @DisplayName("keeps black-only colors on the black channel at every depth")
        void blackOnly(RenderingIntent intent) {
            var transform = transform(BlackPreservation.K_ONLY, intent);
            var in8 = new byte[256 * 4];
            var in16 = new short[256 * 4];
            var inf = new float[256 * 4];
            for (var k = 0; k < 256; k++) {
                in8[k * 4 + 3] = (byte) k;
                in16[k * 4 + 3] = (short) (k * 257);
                inf[k * 4 + 3] = k / 255.0f;
            }
            var out8 = new byte[in8.length];
            var out16 = new short[in16.length];
            var outf = new float[inf.length];
            transform.transform(in8, 0, 4, out8, 0, 4, 256);
            transform.transform(in16, 0, 4, out16, 0, 4, 256);
            transform.transform(inf, 0, 4, outf, 0, 4, 256);
            var previous = -1;
            for (var k = 0; k < 256; k++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(0, out8[k * 4 + c], "8-bit ink " + c + " at K " + k);
                    assertEquals(0, out16[k * 4 + c], "16-bit ink " + c + " at K " + k);
                    assertEquals(0.0f, outf[k * 4 + c], "float ink " + c + " at K " + k);
                }
                var black = out16[k * 4 + 3] & 0xFFFF;
                assertTrue(black >= previous - 2, "black decreases at K " + k);
                previous = black;
            }
            assertEquals(0, out16[3]);
        }

        @Test
        @DisplayName("converts colors with other inks as the plain intent does")
        void otherColors() {
            var preserving = transform(BlackPreservation.K_ONLY, RenderingIntent.PERCEPTUAL);
            var plain = transform(BlackPreservation.NONE, RenderingIntent.PERCEPTUAL);
            var in = new short[]{(short) 0xFFFF, 0, 0, 0, (short) 0x8000, 0x4000, 0x2000, 0x1000};
            var a = new short[8];
            var b = new short[8];
            preserving.transform(in, 0, 4, a, 0, 4, 2);
            plain.transform(in, 0, 4, b, 0, 4, 2);
            for (var i = 0; i < 8; i++) {
                assertTrue(Math.abs((a[i] & 0xFFFF) - (b[i] & 0xFFFF)) <= 1024, "sample " + i);
            }
        }
    }

    @Nested
    @DisplayName("black-plane preservation")
    class KPlane {
        @Test
        @DisplayName("keeps black on the curve and the ink within the coverage of the target")
        void blackPlane() {
            var transform = transform(BlackPreservation.K_PLANE, RenderingIntent.RELATIVE_COLORIMETRIC);
            var nodes = new Linker.Node[]{Linker.Node.of(ProofingFixtures.profile("swop").icc(), Linker.RELATIVE, false),
                    Linker.Node.of(ProofingFixtures.profile("fogra").icc(), Linker.RELATIVE, false)};
            var kTone = BlackPreservingIntents.kTone(nodes);
            var limit = BlackPreservingIntents.detectTotalAreaCoverage(ProofingFixtures.profile("fogra").icc()) / 100.0;
            var table = ((Stage.Clut) transform.pipeline().stages().getFirst()).table;
            var points = 17;
            var checked = 0;
            for (var node = 0; node < table.length / 4; node++) {
                var k = ColorMath.saturateWord((node % points) * 65535.0 / (points - 1));
                var expectedBlack = ColorMath.saturateWord(kTone.evaluate((float) (k / 65535.0)) * 65535.0);
                var black = table[node * 4 + 3];
                var cmy = (long) table[node * 4] + table[node * 4 + 1] + table[node * 4 + 2];
                if (Math.abs(black - expectedBlack) <= 3) {
                    checked++;
                    assertTrue((cmy + black) / 65535.0 <= limit + 0.02, "ink above the coverage at node " + node);
                }
            }
            assertTrue(checked > table.length / 8, "black kept at only " + checked + " nodes");
        }

        @Test
        @DisplayName("differs from black-only preservation on mixed colors")
        void differsFromKOnly() {
            var plane = transform(BlackPreservation.K_PLANE, RenderingIntent.PERCEPTUAL);
            var only = transform(BlackPreservation.K_ONLY, RenderingIntent.PERCEPTUAL);
            var in = new short[]{0x4000, 0x4000, 0x4000, (short) 0xC000};
            var a = new short[4];
            var b = new short[4];
            plane.transform(in, 0, 4, a, 0, 4, 1);
            only.transform(in, 0, 4, b, 0, 4, 1);
            assertNotEquals(a[3], b[3]);
        }
    }

    @Test
    @DisplayName("converts with the rendering intent alone when the chain does not end with a CMYK output profile")
    void fallsBack() {
        var srgb = ProofingFixtures.profile("srgb");
        var swop = ProofingFixtures.profile("swop");
        var link = ColorProfile.inkLimiting(300);
        for (var preservation : List.of(BlackPreservation.K_ONLY, BlackPreservation.K_PLANE)) {
            var options = ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC)
                    .blackPreservation(preservation).build();
            var plain = ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC).build();
            for (var chain : List.of(List.of(srgb, swop), List.of(link))) {
                var preserving = ColorTransform.create(chain, options);
                var reference = ColorTransform.create(chain, plain);
                var in = ProofingFixtures.inputs16(preserving.inputChannels());
                var pixels = in.length / preserving.inputChannels();
                var a = new short[pixels * preserving.outputChannels()];
                var b = new short[a.length];
                preserving.transform(in, 0, preserving.inputChannels(), a, 0, preserving.outputChannels(), pixels);
                reference.transform(in, 0, reference.inputChannels(), b, 0, reference.outputChannels(), pixels);
                Tolerance.exact().assertWithin(preservation + " " + chain, b, a, 16);
            }
            // a CMYK profile followed by a CMYK device link does not connect, whatever the intent
            assertThrows(MediaException.Unsupported.class, () -> ColorTransform.create(List.of(swop, link), options));
        }
    }

    @Test
    @DisplayName("rejects the absolute colorimetric intent")
    void rejectsAbsolute() {
        var builder = ColorTransformOptions.builder().intent(RenderingIntent.ABSOLUTE_COLORIMETRIC)
                .blackPreservation(BlackPreservation.K_ONLY);
        assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals(BlackPreservation.NONE, ColorTransformOptions.defaults().blackPreservation());
        assertNotEquals(ColorTransformOptions.defaults(),
                ColorTransformOptions.builder().blackPreservation(BlackPreservation.K_PLANE).build());
    }
}
