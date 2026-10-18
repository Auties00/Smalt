package com.github.auties00.smalt.internal.quantize;

import com.github.auties00.smalt.image.ops.Dithering;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the quantization building blocks: the scalar and vector palette search kernels against
 * each other on randomized palettes and colors, the cached and guessed palette lookup against a
 * brute-force search, the exact color collector, NeuQuant and median cut (determinism, size,
 * error), and the index mapper's dithering methods.
 */
class QuantizationTest {
    static int[] randomColors(int count, long seed) {
        var random = new Random(seed);
        var colors = new int[count];
        for (var i = 0; i < count; i++) {
            colors[i] = random.nextInt(1 << 24);
        }
        return colors;
    }

    static int bruteForce(int[] palette, int color) {
        var best = Integer.MAX_VALUE;
        for (var i = 0; i < palette.length; i++) {
            var dr = (color & 0xFF) - (palette[i] & 0xFF);
            var dg = ((color >>> 8) & 0xFF) - ((palette[i] >>> 8) & 0xFF);
            var db = ((color >>> 16) & 0xFF) - ((palette[i] >>> 16) & 0xFF);
            best = Math.min(best, (dr * dr + dg * dg + db * db) << 8 | i);
        }
        return best;
    }

    /** A smooth gradient with noise: many distinct colors, like a photograph. */
    static int[] gradient(int width, int height, long seed) {
        var random = new Random(seed);
        var pixels = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var r = Math.clamp(x * 255 / width + random.nextInt(9) - 4, 0, 255);
                var g = Math.clamp(y * 255 / height + random.nextInt(9) - 4, 0, 255);
                var b = Math.clamp((x + y) * 255 / (width + height) + random.nextInt(9) - 4, 0, 255);
                pixels[y * width + x] = r | g << 8 | b << 16 | 0xFF00_0000;
            }
        }
        return pixels;
    }

    static double meanError(int[] pixels, Palette palette) {
        var total = 0.0;
        for (var pixel : pixels) {
            total += palette.nearest(pixel) >>> 8;
        }
        return total / pixels.length;
    }

    @Nested
    @DisplayName("palette search")
    class Search {
        @ParameterizedTest(name = "{0} entries")
        @ValueSource(ints = {1, 2, 15, 16, 17, 100, 255, 256})
        @DisplayName("the vector kernel returns the scalar kernel's key")
        void kernels(int size) {
            var palette = randomColors(size, size);
            var padded = (size + 15) / 16 * 16;
            var reds = new int[padded];
            var greens = new int[padded];
            var blues = new int[padded];
            Arrays.fill(reds, PaletteSearchKernel.SENTINEL);
            Arrays.fill(greens, PaletteSearchKernel.SENTINEL);
            Arrays.fill(blues, PaletteSearchKernel.SENTINEL);
            for (var i = 0; i < size; i++) {
                reds[i] = palette[i] & 0xFF;
                greens[i] = (palette[i] >>> 8) & 0xFF;
                blues[i] = palette[i] >>> 16;
            }
            var scalar = new ScalarPaletteSearchKernel();
            var vector = new VectorPaletteSearchKernel();
            var random = new Random(size * 7L);
            for (var i = 0; i < 20_000; i++) {
                var r = random.nextInt(256);
                var g = random.nextInt(256);
                var b = random.nextInt(256);
                var expected = scalar.nearest(r, g, b, reds, greens, blues, padded);
                assertEquals(expected, vector.nearest(r, g, b, reds, greens, blues, padded));
                assertEquals(bruteForce(palette, r | g << 8 | b << 16), expected);
            }
        }

        @Test
        @DisplayName("ties go to the lowest index")
        void ties() {
            var palette = new Palette(new int[]{0x000010, 0x000000, 0x000010, 0x000000});
            assertEquals(1, palette.indexOf(0x000004));
            assertEquals(0, palette.indexOf(0x000008));
        }

        @Test
        @DisplayName("cached and guessed lookups equal a brute-force search, duplicate entries included")
        void lookups() {
            var colors = randomColors(200, 11);
            colors[50] = colors[10];
            var palette = new Palette(colors);
            var random = new Random(12);
            var last = 0;
            for (var i = 0; i < 100_000; i++) {
                var color = random.nextInt(4) == 0 ? last : random.nextInt(8) == 0 ? colors[random.nextInt(200)]
                        : (last + random.nextInt(0x0F0F0F)) & 0xFF_FFFF;
                last = color;
                assertEquals(bruteForce(colors, color), palette.nearest(color), "color " + Integer.toHexString(color));
            }
            assertTrue(palette.containsAll(colors, colors.length));
            var absent = 0;
            while (bruteForce(colors, absent) >>> 8 == 0) {
                absent++;
            }
            assertFalse(palette.containsAll(new int[]{absent}, 1));
        }
    }

    @Nested
    @DisplayName("palettes")
    class Palettes {
        @Test
        @DisplayName("exact colors are collected in first-seen order up to the limit")
        void exact() {
            var collector = new ExactColors();
            var pixels = new int[]{0xFF00_0001, 0xFF00_0001, 0x7F00_0002, 0xFF00_0001, 0xFF00_0003};
            assertTrue(collector.collect(pixels, pixels.length, 3));
            assertEquals(3, collector.size());
            assertArrayEquals(new int[]{1, 2, 3}, Arrays.copyOf(collector.colors(), 3));
            assertFalse(collector.collect(pixels, pixels.length, 2));
            var many = randomColors(5000, 3);
            assertFalse(collector.collect(many, many.length, 256));
        }

        @ParameterizedTest(name = "{0} colors")
        @ValueSource(ints = {1, 2, 16, 64, 255, 256})
        @DisplayName("NeuQuant and median cut are deterministic, sized and accurate")
        void quantizers(int size) {
            var pixels = gradient(200, 150, size);
            var neuquant = NeuQuant.quantize(pixels, pixels.length, size, 10);
            var mediancut = MedianCut.quantize(pixels, pixels.length, size);
            assertArrayEquals(neuquant, NeuQuant.quantize(pixels, pixels.length, size, 10));
            assertArrayEquals(mediancut, MedianCut.quantize(pixels, pixels.length, size));
            assertEquals(size, neuquant.length);
            assertTrue(mediancut.length <= size && mediancut.length >= Math.min(size, 2));
            if (size >= 16) {
                // The gradient carries +-4 noise per channel, so even a good palette keeps an error.
                var medianCutError = meanError(pixels, new Palette(mediancut));
                var neuQuantError = meanError(pixels, new Palette(neuquant));
                assertTrue(medianCutError < 16_000.0 / size, "median cut error " + medianCutError);
                assertTrue(neuQuantError < 3 * medianCutError, "NeuQuant error " + neuQuantError);
            }
        }

        @Test
        @DisplayName("quantizers handle single-color and tiny inputs")
        void degenerate() {
            var flat = new int[1000];
            Arrays.fill(flat, 0x00FF_8040);
            assertEquals(0x00FF_8040, MedianCut.quantize(flat, flat.length, 16)[0]);
            var palette = NeuQuant.quantize(flat, flat.length, 16, 30);
            assertTrue(new Palette(palette).nearest(0x00FF_8040) >>> 8 <= 12);
            assertEquals(4, NeuQuant.quantize(new int[]{1, 2, 3}, 3, 4, 10).length);
        }
    }

    @Nested
    @DisplayName("index mapping")
    class Mapping {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Dithering.class)
        @DisplayName("transparent pixels take the transparent index and exact colors their own")
        void transparency(Dithering method) {
            var palette = new Palette(new int[]{0x0000FF, 0x00FF00, 0xFF0000});
            var pixels = new int[]{0xFF00_00FF, 0, 0xFF00_FF00, 0x00FF_FFFF, 0xFFFF_0000, 0};
            var out = new byte[6];
            new IndexMapper().map(pixels, 3, 2, palette, 3, method, out);
            assertArrayEquals(new byte[]{0, 3, 1, 3, 2, 3}, out);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Dithering.class, names = {"ORDERED", "FLOYD_STEINBERG", "SIERRA"})
        @DisplayName("dithering a flat color between two entries mixes them in proportion")
        void mixes(Dithering method) {
            var palette = new Palette(new int[]{0x000000, 0xFFFFFF});
            var pixels = new int[64 * 64];
            Arrays.fill(pixels, 0xFF40_4040);
            var out = new byte[pixels.length];
            new IndexMapper().map(pixels, 64, 64, palette, 2, method, out);
            var white = 0;
            for (var index : out) {
                white += index;
            }
            assertEquals(0.25, white / (double) out.length, 0.05);
        }
    }
}
