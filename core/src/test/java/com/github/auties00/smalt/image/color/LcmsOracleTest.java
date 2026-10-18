package com.github.auties00.smalt.image.color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares 8-bit transforms with the outputs of Little CMS 2.17 recorded by
 * {@code tools/testdata/lcms-oracle.py} for the cases of {@code lcms/cases.tsv}: matrix/TRC,
 * gray, lookup-table, L*a*b* connection space and CMYK profiles, with every intent and with black
 * point compensation.
 */
class LcmsOracleTest {
    static Stream<Arguments> cases() {
        return TestProfiles.table("cases.tsv").stream().map(row -> Arguments.of(row[0], row[1], row[2], row[3], row[4]));
    }

    static ColorTransformOptions options(String intent, String bpc) {
        var renderingIntent = switch (intent) {
            case "P" -> RenderingIntent.PERCEPTUAL;
            case "R" -> RenderingIntent.RELATIVE_COLORIMETRIC;
            case "S" -> RenderingIntent.SATURATION;
            default -> RenderingIntent.ABSOLUTE_COLORIMETRIC;
        };
        return ColorTransformOptions.builder().intent(renderingIntent).blackPointCompensation(bpc.equals("1")).build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches Little CMS within one code value")
    void matchesLittleCms(String name, String source, String target, String intent, String bpc) {
        var from = TestProfiles.profile(source);
        var to = TestProfiles.profile(target);
        var transform = ColorTransform.create(from, to, options(intent, bpc));
        var input = TestProfiles.inputs(from.colorSpace());
        var expected = TestProfiles.resource(name + ".bin");
        var pixels = input.length / from.channels();
        var actual = new byte[pixels * to.channels()];
        transform.transform(input, 0, from.channels(), actual, 0, to.channels(), pixels);
        assertEquals(expected.length, actual.length);
        var max = 0;
        var off = 0;
        var worst = -1;
        for (var i = 0; i < actual.length; i++) {
            var diff = Math.abs((actual[i] & 0xFF) - (expected[i] & 0xFF));
            if (diff > 0) {
                off++;
            }
            if (diff > max) {
                max = diff;
                worst = i;
            }
        }
        System.out.printf("lcms %-28s max %3d, %5d of %6d samples differ%s%n", name, max, off, actual.length,
                worst < 0 ? "" : " (worst pixel " + worst / to.channels() + ")");
        assertTrue(max <= 1, name + ": max difference " + max + " at sample " + worst);
    }
}
