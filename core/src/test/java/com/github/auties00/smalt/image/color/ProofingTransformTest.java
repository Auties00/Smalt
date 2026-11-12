package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what a proofing transform is (the chain through the proofing profile in both directions), what its gamut
 * alarm replaces, its accessors and its options; {@link LcmsProofingDifferentialTest} compares it with Little CMS.
 */
class ProofingTransformTest {
    private static ColorProfile srgb() {
        return ProofingFixtures.profile("srgb");
    }

    private static ColorProfile fogra() {
        return ProofingFixtures.profile("fogra");
    }

    @Test
    @DisplayName("is the chain through the proofing profile in both directions")
    void chain() {
        var relative = ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC).build();
        var proofing = ProofingOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC).build();
        var proof = ColorTransform.createProofing(srgb(), fogra(), srgb(), relative, proofing);
        var chain = ColorTransform.create(List.of(srgb(), fogra(), fogra(), srgb()), relative);
        var in = ProofingFixtures.inputs16(3);
        var pixels = in.length / 3;
        var a = new short[in.length];
        var b = new short[in.length];
        proof.transform(in, 0, 3, a, 0, 3, pixels);
        chain.transform(in, 0, 3, b, 0, 3, pixels);
        assertArrayEquals(b, a);
        var plain = ColorTransform.create(srgb(), srgb(), relative);
        var c = new short[in.length];
        plain.transform(in, 0, 3, c, 0, 3, pixels);
        assertFalse(java.util.Arrays.equals(a, c), "proofing changes nothing");
    }

    @Test
    @DisplayName("replaces the colors the gamut check marks with the alarm, and only them")
    void alarm() {
        var options = ColorTransformOptions.defaults();
        var unchecked = ColorTransform.createProofing(srgb(), fogra(), srgb(), options, ProofingOptions.defaults());
        var alarm = new int[]{0xFFFF, 0, 0xFFFF};
        var checked = ColorTransform.createProofing(srgb(), fogra(), srgb(), options,
                ProofingOptions.builder().gamutCheck(true).alarm(alarm).build());
        var in8 = ProofingFixtures.inputs8(3);
        var pixels = in8.length / 3;
        var wide = new short[in8.length];
        for (var i = 0; i < in8.length; i++) {
            wide[i] = (short) ((in8[i] & 0xFF) * 257);
        }
        var marks = LcmsProofingDifferentialTest.marks(checked, wide, pixels);
        var a = new byte[in8.length];
        var b = new byte[in8.length];
        checked.transform(in8, 0, 3, a, 0, 3, pixels);
        unchecked.transform(in8, 0, 3, b, 0, 3, pixels);
        var outside = 0;
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < 3; c++) {
                var expected = marks[p] ? ColorMath.wordToByte(alarm[c]) : b[p * 3 + c] & 0xFF;
                assertEquals(expected, a[p * 3 + c] & 0xFF, "pixel " + p);
            }
            outside += marks[p] ? 1 : 0;
        }
        assertTrue(outside > pixels / 20 && outside < pixels, outside + " of " + pixels + " pixels out of gamut");
        var inf = ProofingFixtures.inputsFloat(ProfileColorSpace.RGB, 3);
        var floatPixels = inf.length / 3;
        var floatMarks = new short[inf.length];
        for (var i = 0; i < inf.length; i++) {
            floatMarks[i] = (short) ColorMath.saturateWord(inf[i] * 65535.0);
        }
        var marked = LcmsProofingDifferentialTest.marks(checked, floatMarks, floatPixels);
        var outf = new float[inf.length];
        checked.transform(inf, 0, 3, outf, 0, 3, floatPixels);
        for (var p = 0; p < floatPixels; p++) {
            if (marked[p]) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(alarm[c] / 65535.0f, outf[p * 3 + c], "float pixel " + p);
                }
            }
        }
    }

    @Test
    @DisplayName("a matrix-shaper proofing profile marks the colors outside its gamut")
    void matrixShaperGamut() {
        var prophoto = ProofingFixtures.profile("prophoto");
        var checked = ColorTransform.createProofing(prophoto, srgb(), prophoto, ColorTransformOptions.defaults(),
                ProofingOptions.forGamutCheck());
        var outside = LcmsProofingDifferentialTest.marks(checked, new short[]{0, (short) 0xFFFF, 0}, 1);
        var inside = LcmsProofingDifferentialTest.marks(checked, new short[]{0x4000, 0x4000, 0x4000}, 1);
        assertTrue(outside[0], "saturated ProPhoto green is inside sRGB");
        assertFalse(inside[0], "ProPhoto gray is outside sRGB");
    }

    @Test
    @DisplayName("the gamut check of a CMYK source proofed on RGB reads the black channel too")
    void blackCounts() {
        var swop = ProofingFixtures.profile("swop");
        var checked = ColorTransform.createProofing(swop, srgb(), srgb(), ColorTransformOptions.defaults(),
                ProofingOptions.forGamutCheck());
        assertEquals(4, checked.inputChannels());
        // Little CMS reads only cyan, magenta and yellow here, so the marks of colors differing in black alone agree
        var levels = 5;
        var blacks = new int[]{0, 0x8000, 0xFFFF};
        var in = new short[levels * levels * levels * blacks.length * 4];
        var pixels = 0;
        for (var cmy = 0; cmy < levels * levels * levels; cmy++) {
            for (var black : blacks) {
                in[pixels * 4] = (short) (cmy / (levels * levels) * 0xFFFF / (levels - 1));
                in[pixels * 4 + 1] = (short) (cmy / levels % levels * 0xFFFF / (levels - 1));
                in[pixels * 4 + 2] = (short) (cmy % levels * 0xFFFF / (levels - 1));
                in[pixels * 4 + 3] = (short) black;
                pixels++;
            }
        }
        var marks = LcmsProofingDifferentialTest.marks(checked, in, pixels);
        var differing = 0;
        var marked = 0;
        for (var cmy = 0; cmy < levels * levels * levels; cmy++) {
            var first = marks[cmy * blacks.length];
            for (var k = 0; k < blacks.length; k++) {
                marked += marks[cmy * blacks.length + k] ? 1 : 0;
                if (marks[cmy * blacks.length + k] != first) {
                    differing++;
                    break;
                }
            }
        }
        assertTrue(marked > 0 && marked < pixels, marked + " of " + pixels + " colors out of gamut");
        assertTrue(differing > 0, "no color changes its mark with black alone");
        assertFalse(marks[0], "SWOP paper white is outside sRGB");
        var out = new short[pixels * 3];
        checked.transform(in, 0, 4, out, 0, 3, pixels);
        for (var p = 0; p < pixels; p++) {
            if (marks[p]) {
                assertArrayEquals(new short[]{0x7F00, 0x7F00, 0x7F00}, java.util.Arrays.copyOfRange(out, p * 3,
                        p * 3 + 3), "pixel " + p);
            }
        }
    }

    @Test
    @DisplayName("reports its profiles and options and is cached")
    void accessors() {
        var proofing = ProofingOptions.forGamutCheck();
        var transform = ColorTransform.createProofing(srgb(), fogra(), srgb(), ColorTransformOptions.defaults(),
                proofing);
        assertEquals(Optional.of(fogra()), transform.proof());
        assertEquals(Optional.of(proofing), transform.proofingOptions());
        assertEquals(List.of(srgb(), srgb()), transform.profiles());
        assertEquals(3, transform.inputChannels());
        assertEquals(3, transform.outputChannels());
        assertTrue(transform.toString().contains("proof"));
        assertSame(transform, ColorTransform.createProofing(srgb(), fogra(), srgb(), ColorTransformOptions.defaults(),
                ProofingOptions.forGamutCheck()));
        var plain = ColorTransform.create(srgb(), srgb());
        assertEquals(Optional.empty(), plain.proof());
        assertEquals(Optional.empty(), plain.proofingOptions());
        assertEquals(ColorTransform.createProofing(srgb(), fogra(), srgb()).proofingOptions(),
                Optional.of(ProofingOptions.defaults()));
    }

    @Test
    @DisplayName("needs profiles that convert between device values and the connection space")
    void rejects() {
        var pq = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ, com.github.auties00.smalt.image.ChromaSiting.UNSPECIFIED, Optional.empty(),
                Optional.empty()));
        var srgb = srgb();
        var fogra = fogra();
        var link = ColorProfile.inkLimiting(300);
        assertThrows(IllegalArgumentException.class, () -> ColorTransform.createProofing(pq, fogra, srgb));
        assertThrows(MediaException.Unsupported.class, () -> ColorTransform.createProofing(srgb, link, srgb));
        assertThrows(MediaException.Unsupported.class, () -> ColorTransform.createProofing(srgb, fogra,
                ColorProfile.lab()));
        assertThrows(NullPointerException.class, () -> ColorTransform.createProofing(srgb, null, srgb));
    }

    @Test
    @DisplayName("options are built and checked")
    void options() {
        var defaults = ProofingOptions.defaults();
        assertEquals(RenderingIntent.PERCEPTUAL, defaults.intent());
        assertFalse(defaults.gamutCheck());
        assertArrayEquals(new int[]{0x7F00, 0x7F00, 0x7F00}, defaults.alarm());
        assertArrayEquals(new int[]{0x7F00, 0x7F00, 0x7F00, 0}, defaults.alarm(4));
        assertEquals(defaults, defaults.toBuilder().build());
        assertNotEquals(defaults, ProofingOptions.forGamutCheck());
        var alarm = new int[]{1, 2};
        var built = ProofingOptions.builder().alarm(alarm).build();
        alarm[0] = 9;
        assertEquals(1, built.alarm()[0]);
        built.alarm()[1] = 7;
        assertEquals(2, built.alarm()[1]);
        assertThrows(IllegalArgumentException.class, () -> ProofingOptions.builder().alarm().build());
        assertThrows(IllegalArgumentException.class, () -> ProofingOptions.builder().alarm(new int[17]).build());
        assertThrows(IllegalArgumentException.class, () -> ProofingOptions.builder().alarm(0x10000).build());
        assertThrows(IllegalArgumentException.class, () -> ProofingOptions.builder().alarm(-1).build());
        assertThrows(NullPointerException.class, () -> ProofingOptions.builder().intent(null));
    }

    @Test
    @DisplayName("the 16-bit gamut alarm is written unchanged")
    void sixteenBitAlarm() {
        var alarm = new int[]{0x1234, 0x5678, 0x9ABC};
        var checked = ColorTransform.createProofing(srgb(), fogra(), srgb(), ColorTransformOptions.defaults(),
                ProofingOptions.builder().gamutCheck(true).alarm(alarm).build());
        var in = ProofingFixtures.inputs16(3);
        var pixels = in.length / 3;
        var marks = LcmsProofingDifferentialTest.marks(checked, in, pixels);
        var out = new short[in.length];
        checked.transform(in, 0, 3, out, 0, 3, pixels);
        var expected = new int[pixels];
        var actual = new int[pixels];
        for (var p = 0; p < pixels; p++) {
            if (marks[p]) {
                for (var c = 0; c < 3; c++) {
                    expected[p] += alarm[c];
                    actual[p] += out[p * 3 + c] & 0xFFFF;
                }
            }
        }
        Tolerance.exact().assertWithin("alarm", expected, actual);
    }
}
