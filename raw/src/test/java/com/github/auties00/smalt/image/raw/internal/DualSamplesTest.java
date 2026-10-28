package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the merge of the two readings of each photosite against scenes of known light: a sensor of two
 * photodiodes of different sensitivity (and per-color ratios between them), two exposures stored in either order,
 * two equal readings, saturated samples and empty images.
 */
class DualSamplesTest {
    private static final CfaPattern RGGB = CfaPattern.bayer("RGGB");

    // the light of each photosite, in units of the primary sample's white: a ramp to the given top, plus texture
    private static double[] scene(int width, int height, double top, long seed) {
        var random = new Random(seed);
        var light = new double[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                light[y * width + x] = top * (x + 0.5) / width * (0.9 + 0.1 * random.nextDouble());
            }
        }
        return light;
    }

    // an image of two samples: the primary clips at its white, the secondary reads the light divided by the
    // ratio of each color plane and clips at its own white
    private static LinearImage image(int width, int height, double[] light, double[] ratios, double secondaryWhite,
                                     boolean secondaryFirst, double noise, long seed) {
        var random = new Random(seed);
        var data = new float[width * height * 2];
        var planes = new int[]{0, 1, 1, 2};
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var index = y * width + x;
                var plane = planes[(y % 2) * 2 + x % 2];
                var primary = Math.min(1, light[index] + noise * random.nextGaussian());
                var secondary = Math.min(secondaryWhite, light[index] / ratios[plane] + noise * random.nextGaussian());
                data[index * 2 + (secondaryFirst ? 1 : 0)] = (float) primary;
                data[index * 2 + (secondaryFirst ? 0 : 1)] = (float) secondary;
            }
        }
        return new LinearImage(width, height, 2, data, RGGB, planes);
    }

    @ParameterizedTest(name = "secondary stored first: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("photodiodes of different sensitivity merge into the light, beyond the primary's white, whatever "
            + "their order")
    void sensitivities(boolean secondaryFirst) {
        var width = 256;
        var height = 64;
        var light = scene(width, height, 12, 3);
        var ratios = new double[]{17.2, 16.0, 14.5};
        var merged = DualSamples.merge(image(width, height, light, ratios, 1, secondaryFirst, 0, 4), MediaLimits.DEFAULT);
        assertEquals(secondaryFirst ? 1 : 0, merged.primary());
        assertArrayEquals(ratios, merged.factors(), 1e-4);
        var scale = Math.pow(2, merged.stops());
        assertEquals(14.5, scale, 1e-3, "the top of the range is the secondary's white at the smallest ratio");
        for (var index = 0; index < light.length; index++) {
            var expected = Math.min(light[index], 14.5);
            assertEquals(expected, merged.image().data[index] * scale, 1e-4 * Math.max(1, expected),
                    "photosite " + index);
        }
        assertEquals(1, merged.image().samplesPerPixel);
        assertEquals(RGGB, merged.image().cfa);
    }

    @Test
    @DisplayName("noisy readings blend smoothly from the primary sample to the secondary one")
    void blend() {
        var width = 512;
        var height = 32;
        var light = scene(width, height, 2, 5);
        var ratios = new double[]{16, 16, 16};
        var merged = DualSamples.merge(image(width, height, light, ratios, 1, false, 0.0005, 6), MediaLimits.DEFAULT);
        var scale = Math.pow(2, merged.stops());
        var error = 0.0;
        var count = 0;
        for (var index = 0; index < light.length; index++) {
            if (light[index] > 0.05) {
                error += Math.abs(merged.image().data[index] * scale / light[index] - 1);
                count++;
            }
        }
        assertTrue(error / count < 0.01, "mean relative error " + error / count);
    }

    @Test
    @DisplayName("a secondary sample that saturates below its white sets the top of the range there")
    void saturated() {
        var width = 256;
        var height = 64;
        var light = scene(width, height, 12, 7);
        var ratios = new double[]{16, 16, 16};
        var merged = DualSamples.merge(image(width, height, light, ratios, 0.5, false, 0, 8), MediaLimits.DEFAULT);
        assertEquals(8, Math.pow(2, merged.stops()), 1e-3);
        var top = 0f;
        for (var value : merged.image().data) {
            top = Math.max(top, value);
        }
        assertEquals(1, top, 1e-6, "saturated photosites reach 1 in every color");
    }

    @Test
    @DisplayName("two readings of the same exposure are averaged, which halves the noise variance")
    void sameExposure() {
        var width = 256;
        var height = 64;
        var light = new double[width * height];
        java.util.Arrays.fill(light, 0.4);
        var ratios = new double[]{1.04, 1.05, 1.06};
        var image = image(width, height, light, ratios, 1, false, 0.01, 9);
        var merged = DualSamples.merge(image, MediaLimits.DEFAULT);
        assertArrayEquals(ratios, merged.factors(), 0.01);
        assertEquals(0, merged.stops(), 0.1);
        var scale = Math.pow(2, merged.stops());
        var primaryVariance = 0.0;
        var mergedVariance = 0.0;
        for (var index = 0; index < light.length; index++) {
            primaryVariance += Math.pow(image.data[index * 2 + merged.primary()] - 0.4, 2);
            mergedVariance += Math.pow(merged.image().data[index] * scale - 0.4, 2);
        }
        assertTrue(mergedVariance < primaryVariance * 0.6, "variance " + mergedVariance + " against "
                + primaryVariance);
    }

    @Test
    @DisplayName("an empty image merges to zeros, at the primary's scale")
    void empty() {
        var merged = DualSamples.merge(new LinearImage(8, 4, 2, new float[64], RGGB, new int[]{0, 1, 1, 2}),
                MediaLimits.DEFAULT);
        assertEquals(0, merged.stops());
        assertArrayEquals(new float[32], merged.image().data);
    }

    @Test
    @DisplayName("an image of one sample per photosite is refused")
    void refused() {
        assertThrows(IllegalArgumentException.class, () -> DualSamples.merge(new LinearImage(2, 2, 1, new float[4],
                RGGB, new int[]{0, 1, 1, 2}), MediaLimits.DEFAULT));
    }

    @Test
    @DisplayName("interleaving a filter array doubles each of its columns")
    void interleaved() {
        assertEquals("RRGG/GGBB", DualSamples.interleaved(RGGB).toString());
        assertEquals("BBGG/GGRR", DualSamples.interleaved(CfaPattern.bayer("BGGR")).toString());
    }

    @Test
    @DisplayName("an opcode list of a two-sample image applies to its staggered interleaving, sample 1 in the even "
            + "columns")
    void pairedOpcodes() {
        var data = new float[2 * 8];
        java.util.Arrays.fill(data, 0.25f);
        var image = new LinearImage(8, 2, 1, data, DualSamples.interleaved(RGGB), new int[]{0, 0, 1, 1, 1, 1, 2, 2});
        // ScalePerColumn on plane 0 (the second sample, in the odd columns) over the four pixels of each row
        var scales = com.github.auties00.smalt.image.raw.TinyDng.Opcodes.area(0, 0, 2, 4, 0, 1, 1, 1, 4 + 4 * 4);
        scales.putInt(4);
        for (var column = 0; column < 4; column++) {
            scales.putFloat(column + 1);
        }
        Opcodes.applyToPairs(image, new com.github.auties00.smalt.image.raw.TinyDng.Opcodes().add(13, 0, scales)
                .build());
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 4; column++) {
                assertEquals(0.25f, data[row * 8 + 2 * column], "sample 1 of pixel " + column);
                assertEquals(0.25f * (column + 1), data[row * 8 + 2 * column + 1], "sample 0 of pixel " + column);
            }
        }
    }
}
