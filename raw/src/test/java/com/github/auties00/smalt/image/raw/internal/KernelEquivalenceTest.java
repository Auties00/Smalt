package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.CfaPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API kernels return bit-identical results to their scalar twins, that the
 * Bayer kernel equals the stencil bilinear interpolation it accelerates, and that the hue,
 * saturation and value kernels reproduce the per-pixel {@link BaselineHueSatTable}.
 */
class KernelEquivalenceTest {
    @ParameterizedTest(name = "{0} pixels")
    @ValueSource(ints = {1, 7, 8, 15, 16, 31, 64, 257, 1000})
    @DisplayName("the color kernels agree bit for bit, NaN and out-of-range inputs included")
    void color(int count) {
        var random = new SplittableRandom(count);
        var r = new float[count];
        var g = new float[count];
        var b = new float[count];
        for (var i = 0; i < count; i++) {
            r[i] = (float) (random.nextDouble() * 1.4 - 0.2);
            g[i] = (float) (random.nextDouble() * 1.4 - 0.2);
            b[i] = i % 13 == 5 ? Float.NaN : (float) (random.nextDouble() * 1.4 - 0.2);
        }
        var matrix = new float[9];
        for (var i = 0; i < 9; i++) {
            matrix[i] = (float) (random.nextDouble() * 3 - 1);
        }
        var scalar = new float[][]{r.clone(), g.clone(), b.clone()};
        var vector = new float[][]{r.clone(), g.clone(), b.clone()};
        new ScalarColorKernel().transform(scalar[0], scalar[1], scalar[2], count, matrix, 65535f);
        VectorColorKernel.create().transform(vector[0], vector[1], vector[2], count, matrix, 65535f);
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(scalar[c], vector[c]);
        }
        new ScalarColorKernel().scale(scalar[0], scalar[1], scalar[2], count, 1 / 65535f);
        VectorColorKernel.create().scale(vector[0], vector[1], vector[2], count, 1 / 65535f);
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(scalar[c], vector[c]);
        }
    }

    @ParameterizedTest(name = "pattern {0}")
    @ValueSource(strings = {"RGGB", "BGGR", "GRBG", "GBRG"})
    @DisplayName("the Bayer kernels agree bit for bit and match the stencil interpolation")
    void bayer(String name) {
        var width = 75;
        var height = 9;
        var random = new SplittableRandom(name.hashCode());
        var data = new float[width * height];
        for (var i = 0; i < data.length; i++) {
            data[i] = (float) random.nextDouble();
        }
        var cfa = CfaPattern.bayer(name);
        var planes = new int[4];
        for (var i = 0; i < 4; i++) {
            planes[i] = cfa.colors().get(i).code();
        }
        var image = new LinearImage(width, height, 1, data, cfa, planes);
        var stencil = new PatternDemosaic(image);
        for (var y = 1; y < height - 1; y++) {
            for (var start = 1; start <= 2; start++) {
                var count = width - 1 - start;
                var first = image.plane(y, start);
                var second = image.plane(y, start + 1);
                var pair = first == 0 ? BayerKernel.RED_GREEN : first == 2 ? BayerKernel.BLUE_GREEN
                        : second == 0 ? BayerKernel.GREEN_RED : BayerKernel.GREEN_BLUE;
                var scalar = new float[3][count];
                var vector = new float[3][count];
                new ScalarBayerKernel().row(data, (y - 1) * width + start, y * width + start, (y + 1) * width + start,
                        count, pair, scalar[0], scalar[1], scalar[2], 0);
                VectorBayerKernel.create().row(data, (y - 1) * width + start, y * width + start,
                        (y + 1) * width + start, count, pair, vector[0], vector[1], vector[2], 0);
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(scalar[c], vector[c], "row " + y + " channel " + c);
                }
                var reference = new float[3][1];
                for (var x = 0; x < count; x++) {
                    stencil.pixel(image, start + x, y, reference[0], reference[1], reference[2], 0);
                    for (var c = 0; c < 3; c++) {
                        assertEquals(reference[c][0], scalar[c][x], "photosite " + (start + x) + "," + y);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RGGB", "GBRG"})
    @DisplayName("Menon demosaicing gives the same result however the image is tiled")
    void menonTiles(String name) {
        var width = 97;
        var height = 61;
        var random = new SplittableRandom(5);
        var data = new float[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                data[y * width + x] = (float) (0.5 + 0.3 * Math.sin(x * 0.4) * Math.cos(y * 0.3) + random.nextDouble() * 0.05);
            }
        }
        var cfa = CfaPattern.bayer(name);
        var planes = new int[4];
        for (var i = 0; i < 4; i++) {
            planes[i] = cfa.colors().get(i).code();
        }
        var image = new LinearImage(width, height, 1, data, cfa, planes);
        var whole = new float[3][width * height];
        new MenonDemosaic().run(image, 0, 0, width, height, whole[0], whole[1], whole[2]);
        var menon = new MenonDemosaic();
        var tile = new float[3][16 * 16];
        for (var y0 = 0; y0 < height; y0 += 16) {
            for (var x0 = 0; x0 < width; x0 += 16) {
                var w = Math.min(16, width - x0);
                var h = Math.min(16, height - y0);
                menon.run(image, x0, y0, w, h, tile[0], tile[1], tile[2]);
                for (var row = 0; row < h; row++) {
                    for (var column = 0; column < w; column++) {
                        for (var c = 0; c < 3; c++) {
                            assertEquals(whole[c][(y0 + row) * width + x0 + column], tile[c][row * w + column],
                                    "photosite " + (x0 + column) + "," + (y0 + row));
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1}x{2}, sRGB {3}, {4} pixels, special cells {5}")
    @CsvSource({
            "90, 30, 1, false, 1000, false", "36, 8, 16, true, 1029, false", "36, 8, 16, false, 3000, false",
            "6, 2, 2, true, 7, false", "6, 2, 2, false, 1024, false", "1, 2, 1, false, 33, false",
            "4, 3, 3, true, 2051, false", "90, 30, 1, false, 1031, true", "36, 8, 16, true, 2047, true",
            "1, 2, 2, false, 517, true"
    })
    @DisplayName("the hue, saturation and value kernels agree bit for bit with the per-pixel baseline")
    void hueSat(int hues, int saturations, int values, boolean srgb, int count, boolean special) {
        var random = new SplittableRandom(hues * 31L + saturations * 7L + values + count);
        var cells = new float[hues * saturations * values * 3];
        for (var i = 0; i < cells.length; i += 3) {
            cells[i] = (float) (random.nextDouble() * 60 - 30);
            cells[i + 1] = (float) (random.nextDouble() * 0.8 + 0.6);
            cells[i + 2] = (float) (random.nextDouble() * 0.8 + 0.6);
        }
        if (special) {
            // NaN, infinite and signed-zero adjustments must reach the output as the scalar kernel computes them
            var specials = new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f, 0f, -1e-38f};
            for (var i = 0; i < cells.length; i += 7) {
                cells[i] = specials[(i / 7) % specials.length];
            }
        }
        var dims = new int[]{hues, saturations, values};
        var table = new HueSatTable(dims, cells, srgb);
        var baseline = new BaselineHueSatTable(dims, cells, srgb);
        var r = new float[count];
        var g = new float[count];
        var b = new float[count];
        for (var i = 0; i < count; i++) {
            r[i] = (float) (random.nextDouble() * 1.6 - 0.1);
            g[i] = i % 11 == 3 ? r[i] : (float) (random.nextDouble() * 1.6 - 0.1);
            b[i] = switch (i % 17) {
                case 2 -> Float.NaN;
                case 5 -> 0f;
                case 9 -> g[i];
                default -> (float) (random.nextDouble() * 1.6 - 0.1);
            };
            if (i % 23 == 4) {
                r[i] = 0f;
                g[i] = -0f;
                b[i] = -0.5f;
            }
        }
        var expected = new float[][]{r.clone(), g.clone(), b.clone()};
        var rgb = new float[3];
        for (var i = 0; i < count; i++) {
            rgb[0] = expected[0][i];
            rgb[1] = expected[1][i];
            rgb[2] = expected[2][i];
            baseline.apply(rgb);
            expected[0][i] = rgb[0];
            expected[1][i] = rgb[1];
            expected[2][i] = rgb[2];
        }
        var scalar = new float[][]{r.clone(), g.clone(), b.clone()};
        var vector = new float[][]{r.clone(), g.clone(), b.clone()};
        new ScalarHueSatKernel().apply(table, scalar[0], scalar[1], scalar[2], count);
        VectorHueSatKernel.create().apply(table, vector[0], vector[1], vector[2], count);
        for (var c = 0; c < 3; c++) {
            if (!special) {
                assertArrayEquals(expected[c], scalar[c], "scalar channel " + c);
            }
            assertArrayEquals(scalar[c], vector[c], "vector channel " + c);
        }
    }
}
