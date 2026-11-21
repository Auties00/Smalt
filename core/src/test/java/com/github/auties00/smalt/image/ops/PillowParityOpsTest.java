package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the operations ported for Pillow parity (rank, mode, equalize, posterize, solarize, the
 * perspective, quad and mesh warps, histograms, channel split and merge, alpha replacement, hue
 * rotation, vignette and white balance) beyond {@link PillowOracleTest}: brute-force references
 * for the window filters on random pictures, 16-bit results against the 8-bit ones, invariants,
 * degenerate sizes and argument validation.
 */
@DisplayName("Pillow parity operations")
class PillowParityOpsTest {
    // one generator per test instance, so every test sees the same pictures whatever the order
    private final SplittableRandom random = new SplittableRandom(0x5EED);

    private static Rgba16Picture wide(RgbaPicture picture) {
        return ImageOps.toSixteenBit(picture);
    }

    private static int[] samples(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var out = new int[4 * width * height];
        switch (picture) {
            case RgbaPicture rgba -> {
                for (var y = 0; y < height; y++) {
                    for (var i = 0; i < 4 * width; i++) {
                        out[y * 4 * width + i] = rgba.pixels()[y * rgba.stride() + i] & 0xFF;
                    }
                }
            }
            case Rgba16Picture rgba -> {
                for (var y = 0; y < height; y++) {
                    for (var i = 0; i < 4 * width; i++) {
                        out[y * 4 * width + i] = rgba.pixels()[y * rgba.stride() + i] & 0xFFFF;
                    }
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        if (!picture.hasAlpha()) {
            // the stored alpha of a picture that reports none is not read
            var opaque = picture instanceof RgbaPicture ? 255 : 65535;
            for (var i = 3; i < out.length; i += 4) {
                out[i] = opaque;
            }
        }
        return out;
    }

    private static int[] scaled(Picture picture, int factor) {
        var out = samples(picture);
        for (var i = 0; i < out.length; i++) {
            out[i] *= factor;
        }
        return out;
    }

    private static void assertWithin(int[] expected, int[] actual, int tolerance) {
        assertEquals(expected.length, actual.length);
        var worst = 0;
        for (var i = 0; i < expected.length; i++) {
            worst = Math.max(worst, Math.abs(expected[i] - actual[i]));
        }
        assertTrue(worst <= tolerance, "worst difference " + worst + " over " + tolerance);
    }

    private static int[] premultiplied(int[] rgba) {
        var out = rgba.clone();
        for (var i = 0; i < out.length; i += 4) {
            for (var c = 0; c < 3; c++) {
                out[i + c] = (out[i + c] * out[i + 3] + 127) / 255;
            }
        }
        return out;
    }

    private static RgbaPicture posterized(SplittableRandom random, int width, int height, boolean alpha) {
        var picture = Pictures.randomRgba(random, width, height, alpha);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (byte) (picture.pixels()[i] & 0xE0);
        }
        return picture;
    }

    // the sample of a rank in the edge-replicated window, by sorting
    private static int[] bruteRank(Picture picture, int radius, int rank) {
        var width = picture.width();
        var height = picture.height();
        var in = samples(picture);
        var out = new int[in.length];
        var side = 2 * radius + 1;
        var window = new int[side * side];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 4; c++) {
                    if (c == 3 && !picture.hasAlpha()) {
                        out[4 * (y * width + x) + c] = in[4 * (y * width + x) + c];
                        continue;
                    }
                    var n = 0;
                    for (var dy = -radius; dy <= radius; dy++) {
                        for (var dx = -radius; dx <= radius; dx++) {
                            var sx = Math.clamp(x + dx, 0, width - 1);
                            var sy = Math.clamp(y + dy, 0, height - 1);
                            window[n++] = in[4 * (sy * width + sx) + c];
                        }
                    }
                    Arrays.sort(window);
                    out[4 * (y * width + x) + c] = window[rank];
                }
            }
        }
        return out;
    }

    // Pillow's mode rule on the window cut at the edges, by counting
    private static int[] bruteMode(Picture picture, int radius) {
        var width = picture.width();
        var height = picture.height();
        var in = samples(picture);
        var out = new int[in.length];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 4; c++) {
                    var own = in[4 * (y * width + x) + c];
                    if (c == 3 && !picture.hasAlpha()) {
                        out[4 * (y * width + x) + c] = own;
                        continue;
                    }
                    var values = new ArrayList<Integer>();
                    for (var sy = Math.max(0, y - radius); sy <= Math.min(height - 1, y + radius); sy++) {
                        for (var sx = Math.max(0, x - radius); sx <= Math.min(width - 1, x + radius); sx++) {
                            values.add(in[4 * (sy * width + sx) + c]);
                        }
                    }
                    values.sort(null);
                    var best = -1;
                    var bestCount = 0;
                    for (var i = 0; i < values.size(); ) {
                        var j = i;
                        while (j < values.size() && values.get(j).equals(values.get(i))) {
                            j++;
                        }
                        if (j - i > bestCount) {
                            bestCount = j - i;
                            best = values.get(i);
                        }
                        i = j;
                    }
                    out[4 * (y * width + x) + c] = bestCount > 2 ? best : own;
                }
            }
        }
        return out;
    }

    @Nested
    @DisplayName("rank and mode filters")
    class Windows {
        @ParameterizedTest(name = "radius {0}")
        @ValueSource(ints = {1, 2, 3, 5})
        @DisplayName("every rank equals sorting the edge-replicated window, at 8 and 16 bits")
        void ranks(int radius) {
            var side = 2 * radius + 1;
            for (var alpha : new boolean[]{false, true}) {
                var picture = Pictures.randomRgba(random, 23, 17, alpha);
                var deep = wide(picture);
                for (var rank : new int[]{0, 1, side * side / 2 - 1, side * side / 2, side * side - 2, side * side - 1}) {
                    var expected = bruteRank(picture, radius, rank);
                    assertArrayEquals(expected, samples(ImageFilters.rank(picture, radius, rank)),
                            "rank " + rank + " alpha " + alpha);
                    var wideExpected = Arrays.stream(expected).map(v -> v * 257).toArray();
                    assertArrayEquals(wideExpected, samples(ImageFilters.rank(deep, radius, rank)),
                            "16-bit rank " + rank + " alpha " + alpha);
                }
            }
        }

        @Test
        @DisplayName("minimum, median and maximum are the extreme and middle ranks")
        void namedRanks() {
            var picture = Pictures.randomRgba(random, 31, 9, true);
            assertArrayEquals(samples(ImageFilters.rank(picture, 2, 0)), samples(ImageFilters.minimum(picture, 2)));
            assertArrayEquals(samples(ImageFilters.rank(picture, 2, 24)), samples(ImageFilters.maximum(picture, 2)));
            assertArrayEquals(samples(ImageFilters.rank(picture, 2, 12)), samples(ImageFilters.median(picture, 2)));
            assertArrayEquals(samples(ImageFilters.rank(picture, 1, 4)), samples(ImageFilters.median(picture, 1)));
        }

        @ParameterizedTest(name = "radius {0}")
        @ValueSource(ints = {1, 2, 4})
        @DisplayName("the mode follows Pillow's rule on windows cut at the edges, at 8 and 16 bits")
        void modes(int radius) {
            for (var alpha : new boolean[]{false, true}) {
                var picture = posterized(random, 27, 19, alpha);
                var expected = bruteMode(picture, radius);
                assertArrayEquals(expected, samples(ImageFilters.mode(picture, radius)), "alpha " + alpha);
                var wideExpected = Arrays.stream(expected).map(v -> v * 257).toArray();
                assertArrayEquals(wideExpected, samples(ImageFilters.mode(wide(picture), radius)), "16-bit alpha " + alpha);
            }
            var noisy = Pictures.randomRgba(random, 27, 19, false);
            assertArrayEquals(bruteMode(noisy, radius), samples(ImageFilters.mode(noisy, radius)), "noise");
        }

        @Test
        @DisplayName("pictures large enough to split into bands equal the brute-force filters")
        void bands() {
            // 192x160 is several bands of work for every filter, so band edges and per-band rings are exercised
            var picture = posterized(random, 192, 160, true);
            var deep = wide(picture);
            for (var rank : new int[]{0, 7, 24}) {
                var expected = bruteRank(picture, 2, rank);
                assertArrayEquals(expected, samples(ImageFilters.rank(picture, 2, rank)), "rank " + rank);
                assertArrayEquals(Arrays.stream(expected).map(v -> v * 257).toArray(),
                        samples(ImageFilters.rank(deep, 2, rank)), "16-bit rank " + rank);
            }
            var expected = bruteMode(picture, 2);
            assertArrayEquals(expected, samples(ImageFilters.mode(picture, 2)), "mode");
            assertArrayEquals(Arrays.stream(expected).map(v -> v * 257).toArray(),
                    samples(ImageFilters.mode(deep, 2)), "16-bit mode");
        }

        @Test
        @DisplayName("windows larger than the picture and single pixels")
        void tiny() {
            for (var size : new int[][]{{1, 1}, {1, 5}, {6, 1}, {3, 2}}) {
                var picture = Pictures.randomRgba(random, size[0], size[1], true);
                assertArrayEquals(bruteRank(picture, 32, 0), samples(ImageFilters.minimum(picture, 32)));
                assertArrayEquals(bruteRank(picture, 32, 65 * 65 - 1), samples(ImageFilters.maximum(picture, 32)));
                assertArrayEquals(bruteRank(picture, 3, 17), samples(ImageFilters.rank(picture, 3, 17)));
                assertArrayEquals(bruteMode(picture, 32), samples(ImageFilters.mode(picture, 32)));
            }
        }

        @Test
        @DisplayName("rejects radii and ranks out of range")
        void validation() {
            var picture = Pictures.randomRgba(random, 4, 4, false);
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.minimum(picture, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.maximum(picture, 33));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.mode(picture, -1));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.rank(picture, 1, 9));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.rank(picture, 1, -1));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.rank(picture, Integer.MAX_VALUE, 0));
        }
    }

    @Nested
    @DisplayName("tone operations")
    class Tones {
        @Test
        @DisplayName("16-bit posterize and solarize equal the 8-bit results")
        void sixteenBit() {
            var picture = Pictures.randomRgba(random, 29, 13, true);
            var deep = wide(picture);
            for (var bits = 1; bits <= 8; bits++) {
                // the kept bits of v * 257 are those of v, shifted into the high byte; alpha is kept
                var expected = samples(ImageAdjustments.posterize(picture, bits));
                for (var i = 0; i < expected.length; i++) {
                    expected[i] *= i % 4 == 3 ? 257 : 256;
                }
                assertArrayEquals(expected, samples(ImageAdjustments.posterize(deep, bits)), "bits " + bits);
            }
            assertArrayEquals(samples(deep), samples(ImageAdjustments.posterize(deep, 16)));
            for (var threshold : new int[]{0, 1, 100, 128, 255, 256}) {
                assertArrayEquals(scaled(ImageAdjustments.solarize(picture, threshold), 257),
                        samples(ImageAdjustments.solarize(deep, threshold)), "threshold " + threshold);
            }
        }

        @Test
        @DisplayName("posterize keeps alpha and the top bits; 8-bit pictures reject more than 8 bits")
        void posterizeRules() {
            var picture = Pictures.randomRgba(random, 7, 5, true);
            var out = samples(ImageAdjustments.posterize(picture, 3));
            var in = samples(picture);
            for (var i = 0; i < in.length; i++) {
                assertEquals(i % 4 == 3 ? in[i] : in[i] & 0xE0, out[i]);
            }
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.posterize(picture, 9));
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.posterize(picture, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.posterize(wide(picture), 17));
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.solarize(picture, 257));
        }

        @Test
        @DisplayName("16-bit equalization follows its exact-step formula and spreads the levels")
        void equalize16() {
            var picture = Pictures.smoothRgba(64, 48, false);
            var deep = wide(picture);
            var histogram = ImageOps.histogram(deep);
            var out = samples(ImageAdjustments.equalize(deep));
            var in = samples(deep);
            for (var c = 0; c < 3; c++) {
                var counts = histogram.counts(c);
                long total = 0;
                long last = 0;
                for (var count : counts) {
                    if (count != 0) {
                        total += count;
                        last = count;
                    }
                }
                var n = total - last;
                var cumulative = new long[65537];
                for (var v = 0; v < 65536; v++) {
                    cumulative[v + 1] = cumulative[v] + counts[v];
                }
                for (var i = c; i < in.length; i += 4) {
                    var expected = Math.min(65535, Math.floorDiv(2 * cumulative[in[i]] * 65535 + n, 2 * n));
                    assertEquals((int) expected, out[i], "channel " + c);
                }
            }
            var eight = samples(ImageAdjustments.equalize(picture));
            var narrowed = samples(ImageOps.toEightBit(ImageAdjustments.equalize(deep)));
            // the 8-bit step n / 255 is rounded down to an integer, the 16-bit one is exact: the levels
            // differ by the ratio of the two steps, plus the roundings
            var tolerance = 0;
            for (var c = 0; c < 3; c++) {
                long total = 0;
                long last = 0;
                for (var count : histogram.counts(c)) {
                    if (count != 0) {
                        total += count;
                        last = count;
                    }
                }
                var n = total - last;
                tolerance = Math.max(tolerance, (int) Math.ceil(255.0 * n / (255 * (n / 255)) - 255) + 2);
            }
            assertWithin(eight, narrowed, tolerance);
        }

        @Test
        @DisplayName("equalization leaves a single-valued channel and an empty mask unchanged")
        void equalizeDegenerate() {
            var picture = RgbaPicture.allocate(20, 20, false);
            Arrays.fill(picture.pixels(), (byte) 77);
            assertArrayEquals(samples(picture), samples(ImageAdjustments.equalize(picture)));
            var noise = Pictures.randomRgba(random, 20, 20, false);
            var empty = Channel8Picture.allocate(20, 20, PixelLayout.GRAY);
            assertArrayEquals(samples(noise), samples(ImageAdjustments.equalize(noise, empty)));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAdjustments.equalize(noise, Channel8Picture.allocate(19, 20, PixelLayout.GRAY)));
        }

        @Test
        @DisplayName("hue rotation keeps gray, turns primaries exactly and composes full turns to the identity")
        void hue() {
            var picture = Pictures.randomRgba(random, 33, 21, true);
            assertArrayEquals(samples(picture), samples(ImageAdjustments.rotateHue(picture, 0)));
            assertArrayEquals(samples(picture), samples(ImageAdjustments.rotateHue(picture, 360)));
            assertArrayEquals(samples(ImageAdjustments.rotateHue(picture, 75)),
                    samples(ImageAdjustments.rotateHue(picture, 75 - 720)));
            var primaries = RgbaPicture.allocate(4, 1, false);
            var colors = new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0x808080};
            for (var i = 0; i < 4; i++) {
                primaries.pixels()[4 * i] = (byte) (colors[i] >> 16);
                primaries.pixels()[4 * i + 1] = (byte) (colors[i] >> 8);
                primaries.pixels()[4 * i + 2] = (byte) colors[i];
                primaries.pixels()[4 * i + 3] = (byte) 255;
            }
            var turned = samples(ImageAdjustments.rotateHue(primaries, 120));
            assertArrayEquals(new int[]{0, 255, 0, 255, 0, 0, 255, 255, 255, 0, 0, 255, 128, 128, 128, 255}, turned);
            var in = samples(picture);
            var out = samples(ImageAdjustments.rotateHue(picture, 47.5));
            for (var i = 0; i < in.length; i += 4) {
                assertEquals(Math.max(in[i], Math.max(in[i + 1], in[i + 2])),
                        Math.max(out[i], Math.max(out[i + 1], out[i + 2])), "value of pixel " + i / 4);
                assertEquals(Math.min(in[i], Math.min(in[i + 1], in[i + 2])),
                        Math.min(out[i], Math.min(out[i + 1], out[i + 2])), "minimum of pixel " + i / 4);
                assertEquals(in[i + 3], out[i + 3]);
            }
            var narrowed = samples(ImageOps.toEightBit(ImageAdjustments.rotateHue(wide(picture), 47.5)));
            assertWithin(out, narrowed, 1);
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.rotateHue(picture, Double.NaN));
        }

        @Test
        @DisplayName("vignette: no change inside the radius, darker corners, 16-bit agreeing with 8-bit")
        void vignette() {
            var picture = Pictures.smoothRgba(40, 30, true);
            assertArrayEquals(samples(picture), samples(ImageAdjustments.vignette(picture, 0, 0.5)));
            var dark = samples(ImageAdjustments.vignette(picture, 1, 0.4));
            var in = samples(picture);
            var center = 4 * (15 * 40 + 20);
            assertArrayEquals(Arrays.copyOfRange(in, center, center + 4), Arrays.copyOfRange(dark, center, center + 4));
            for (var c = 0; c < 3; c++) {
                assertTrue(dark[c] <= in[c] / 3 + 1, "corner channel " + c + ": " + in[c] + " -> " + dark[c]);
            }
            var light = samples(ImageAdjustments.vignette(picture, -1, 0.4));
            for (var i = 0; i < in.length; i++) {
                assertTrue(i % 4 == 3 ? light[i] == in[i] : light[i] >= in[i], "sample " + i);
            }
            assertWithin(dark, samples(ImageOps.toEightBit(ImageAdjustments.vignette(wide(picture), 1, 0.4))), 1);
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.vignette(picture, 1.5, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.vignette(picture, 0.5, 1));
        }

        @Test
        @DisplayName("white balance: the reference is the identity, warm lights turn the picture bluer")
        void whiteBalance() {
            var picture = Pictures.smoothRgba(40, 30, true);
            assertArrayEquals(samples(picture), samples(ImageAdjustments.whiteBalance(picture, 6504, 0)));
            var gray = RgbaPicture.allocate(8, 8, false);
            Arrays.fill(gray.pixels(), (byte) 128);
            var warm = samples(ImageAdjustments.whiteBalance(gray, 3000, 0));
            assertTrue(warm[2] > 128 && warm[0] < 128, "3000 K: " + Arrays.toString(Arrays.copyOf(warm, 3)));
            var cool = samples(ImageAdjustments.whiteBalance(gray, 10000, 0));
            assertTrue(cool[2] < 128 && cool[0] > 128, "10000 K: " + Arrays.toString(Arrays.copyOf(cool, 3)));
            var green = samples(ImageAdjustments.whiteBalance(gray, 6504, 0.02));
            assertTrue(green[1] < green[0] && green[1] < green[2], "green tint: " + Arrays.toString(Arrays.copyOf(green, 3)));
            var eight = samples(ImageAdjustments.whiteBalance(picture, 4100, -0.01));
            assertWithin(eight, samples(ImageOps.toEightBit(ImageAdjustments.whiteBalance(wide(picture), 4100, -0.01))), 1);
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.whiteBalance(picture, 999, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.whiteBalance(picture, 5000, 0.06));
        }

        @Test
        @DisplayName("the source white of the reference light sits on the Planckian locus near D65")
        void referenceWhite() {
            var white = Lighting.white(6504, 0);
            var x = white[0] / (white[0] + white[1] + white[2]);
            var y = white[1] / (white[0] + white[1] + white[2]);
            // the Planckian point at 6504 K is (0.3135, 0.3236), 0.006 below D65 in y
            assertEquals(0.3135, x, 5e-4);
            assertEquals(0.3236, y, 5e-4);
        }
    }

    @Nested
    @DisplayName("warps")
    class Warps {
        @Test
        @DisplayName("16-bit perspective and quad warps equal the 8-bit ones")
        void sixteenBit() {
            var picture = Pictures.randomRgba(random, 41, 37, true);
            var transform = PerspectiveTransform.mapping(Quad.of(0, 0, 41, 37), new Quad(3, 1, 38, 4, 40, 36, 1, 30));
            var nearest = ImageOps.perspective(picture, transform, 44, 40, Interpolation.NEAREST, 0x80102030);
            assertArrayEquals(scaled(nearest, 257),
                    samples(ImageOps.perspective(wide(picture), transform, 44, 40, Interpolation.NEAREST, 0x80102030)));
            var bicubic = ImageOps.perspective(picture, transform, 44, 40, Interpolation.BICUBIC, 0x80102030);
            var deep = ImageOps.toEightBit(
                    ImageOps.perspective(wide(picture), transform, 44, 40, Interpolation.BICUBIC, 0x80102030));
            // the colors of nearly transparent pixels are ill-conditioned: compare premultiplied
            assertWithin(premultiplied(samples(bicubic)), premultiplied(samples(deep)), 1);
            var quad = new Quad(2, 3, 35, 1, 39, 33, 5, 36);
            assertArrayEquals(scaled(ImageOps.quad(picture, quad, 30, 20, Interpolation.NEAREST, 0), 257),
                    samples(ImageOps.quad(wide(picture), quad, 30, 20, Interpolation.NEAREST, 0)));
        }

        @Test
        @DisplayName("an affine perspective transform equals the affine warp")
        void affine() {
            var picture = Pictures.randomRgba(random, 30, 24, false);
            var affine = AffineTransform.rotation(12, 15, 12).then(AffineTransform.scaling(1.25, 0.75));
            for (var interpolation : Interpolation.values()) {
                var expected = samples(ImageOps.affine(picture, affine, 36, 20, interpolation, 0xFF000000));
                var actual = samples(ImageOps.perspective(picture, PerspectiveTransform.of(affine), 36, 20,
                        interpolation, 0xFF000000));
                assertWithin(expected, actual, interpolation == Interpolation.NEAREST ? 255 : 1);
                var differing = 0;
                for (var i = 0; i < expected.length; i++) {
                    differing += expected[i] == actual[i] ? 0 : 1;
                }
                assertTrue(differing <= expected.length / 200, interpolation + ": " + differing + " samples differ");
            }
        }

        @Test
        @DisplayName("the identity quad and a full-canvas mesh cell copy the picture")
        void identity() {
            var picture = Pictures.randomRgba(random, 17, 11, false);
            var whole = Quad.of(0, 0, 17, 11);
            assertArrayEquals(samples(picture), samples(ImageOps.quad(picture, whole, 17, 11, Interpolation.NEAREST, 0)));
            assertArrayEquals(samples(picture), samples(ImageOps.quad(picture, whole, 17, 11, Interpolation.BILINEAR, 0)));
            assertArrayEquals(samples(picture), samples(ImageOps.mesh(picture,
                    List.of(new MeshCell(new Region(0, 0, 17, 11), whole)), 17, 11, Interpolation.NEAREST, 0)));
            assertArrayEquals(samples(picture), samples(ImageOps.perspective(picture, PerspectiveTransform.identity(),
                    17, 11, Interpolation.BICUBIC, 0)));
        }

        @Test
        @DisplayName("mesh pixels outside every cell, and pixels behind the horizon, take the background")
        void background() {
            var picture = Pictures.randomRgba(random, 10, 10, false);
            var empty = samples(ImageOps.mesh(picture, List.of(), 6, 4, Interpolation.BILINEAR, 0xFF336699));
            for (var i = 0; i < empty.length; i += 4) {
                assertArrayEquals(new int[]{0x33, 0x66, 0x99, 0xFF}, Arrays.copyOfRange(empty, i, i + 4));
            }
            var cell = new MeshCell(new Region(2, 1, 50, 2), Quad.of(0, 0, 10, 10));
            var partial = samples(ImageOps.mesh(picture, List.of(cell), 6, 4, Interpolation.NEAREST, 0xFF336699));
            assertArrayEquals(new int[]{0x33, 0x66, 0x99, 0xFF}, Arrays.copyOfRange(partial, 0, 4));
            assertTrue(partial[4 * (6 + 2)] != 0x33 || partial[4 * (6 + 2) + 1] != 0x66, "covered pixel");
            // w = 1 - x / 5 vanishes at the column 5 of the result: the right half lies behind the horizon
            var horizon = new PerspectiveTransform(1, 0, 0, 0, 1, 0, -0.2, 0, 1).inverse();
            var behind = samples(ImageOps.perspective(picture, horizon, 10, 2, Interpolation.NEAREST, 0xFF336699));
            assertArrayEquals(new int[]{0x33, 0x66, 0x99, 0xFF}, Arrays.copyOfRange(behind, 4 * 8, 4 * 9));
        }

        @Test
        @DisplayName("perspective transforms map quadrilateral corners and invert")
        void transforms() {
            var from = new Quad(10, 12, 90, 5, 95, 70, 3, 60);
            var to = Quad.of(0, 0, 100, 80);
            var map = PerspectiveTransform.mapping(from, to);
            double[][] corners = {{10, 12, 0, 0}, {90, 5, 100, 0}, {95, 70, 100, 80}, {3, 60, 0, 80}};
            for (var corner : corners) {
                assertEquals(corner[2], map.mapX(corner[0], corner[1]), 1e-9);
                assertEquals(corner[3], map.mapY(corner[0], corner[1]), 1e-9);
            }
            assertEquals(1, map.m22(), 1e-15);
            var back = map.then(map.inverse());
            assertEquals(7.5, back.mapX(7.5, 31.25), 1e-9);
            assertEquals(31.25, back.mapY(7.5, 31.25), 1e-9);
            assertThrows(IllegalArgumentException.class,
                    () -> PerspectiveTransform.mapping(new Quad(0, 0, 1, 1, 2, 2, 0, 5), to));
            assertThrows(IllegalStateException.class,
                    () -> new PerspectiveTransform(1, 2, 3, 2, 4, 6, 0, 0, 1).inverse());
            assertThrows(IllegalArgumentException.class, () -> ImageOps.perspective(Pictures.randomRgba(random, 2, 2,
                    false), new PerspectiveTransform(0, 0, 0, 0, 0, 0, 0, 0, 0), 2, 2, Interpolation.NEAREST, 0));
            assertThrows(IllegalArgumentException.class, () -> PerspectiveTransform.ofCoefficients(1, 2, 3));
            assertThrows(IllegalArgumentException.class, () -> new Quad(0, 0, 1, 1, 2, 2, Double.NaN, 5));
        }
    }

    @Nested
    @DisplayName("histograms")
    class Histograms {
        @Test
        @DisplayName("a 16-bit histogram holds the 8-bit counts at v * 257, with scaled statistics")
        void sixteenBit() {
            var picture = Pictures.randomRgba(random, 37, 29, true);
            var eight = ImageOps.histogram(picture);
            var deep = ImageOps.histogram(wide(picture));
            assertEquals(4, eight.channels());
            assertEquals(256, eight.bins());
            assertEquals(65536, deep.bins());
            assertEquals(37 * 29, deep.pixels());
            for (var c = 0; c < 4; c++) {
                for (var v = 0; v < 256; v++) {
                    assertEquals(eight.count(c, v), deep.count(c, v * 257));
                }
                var a = eight.statistics(c);
                var b = deep.statistics(c);
                assertEquals(a.count(), b.count());
                assertEquals(a.minimum() * 257, b.minimum());
                assertEquals(a.maximum() * 257, b.maximum());
                assertEquals(a.median() * 257, b.median());
                assertEquals(a.mean() * 257, b.mean(), 1e-9 * b.mean());
                assertEquals(a.standardDeviation() * 257, b.standardDeviation(), 1e-6 * b.standardDeviation());
            }
        }

        @Test
        @DisplayName("masks select pixels by their color channels, of any picture type")
        void masks() {
            var picture = Pictures.randomRgba(random, 8, 6, false);
            var mask = Channel16Picture.allocate(8, 6, PixelLayout.GRAY_ALPHA);
            mask.pixels()[2 * 9] = 1;
            mask.pixels()[2 * 10 + 1] = (short) 0xFFFF;
            var histogram = ImageOps.histogram(picture, mask);
            assertEquals(1, histogram.pixels());
            assertEquals(1, histogram.count(0, samples(picture)[4 * 9]));
            var empty = ImageOps.histogram(picture, RgbaPicture.allocate(8, 6, true));
            assertEquals(0, empty.pixels());
            assertEquals(new ChannelStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), empty.statistics(1));
            assertThrows(IndexOutOfBoundsException.class, () -> histogram.count(3, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> histogram.count(0, 256));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageOps.histogram(picture, RgbaPicture.allocate(8, 5, true)));
            assertThrows(MediaException.Unsupported.class,
                    () -> ImageOps.histogram(Channel8Picture.allocate(2, 2, PixelLayout.CMYK)));
        }

        @Test
        @DisplayName("the counts array is a copy")
        void copies() {
            var histogram = ImageOps.histogram(Pictures.randomRgba(random, 4, 4, false));
            var counts = histogram.counts(0);
            counts[0] += 1000;
            assertTrue(histogram.counts(0)[0] < 1000);
        }
    }

    @Nested
    @DisplayName("channels")
    class Channels {
        @Test
        @DisplayName("split and merge round trip every sample type and layout")
        void roundTrip() {
            var rgba = Pictures.randomRgba(random, 9, 7, true);
            for (var picture : List.<Picture>of(rgba, wide(rgba), ImageOps.toChannel8(rgba), ImageOps.toChannelFloat(rgba),
                    cmyk())) {
                var planes = ImageOps.split(picture);
                var channels = assertInstanceOf(ChannelPicture.class, picture instanceof ChannelPicture c ? c
                        : picture instanceof Rgba16Picture ? ImageOps.toChannel16(picture) : ImageOps.toChannel8(picture));
                assertEquals(channels.channelCount(), planes.size());
                for (var plane : planes) {
                    assertEquals(PixelLayout.GRAY, plane.layout());
                    assertSame(channels.getClass(), plane.getClass());
                }
                var merged = ImageOps.merge(channels.layout(), planes);
                assertEquals(channels.layout(), merged.layout());
                assertTrue(java.util.Objects.deepEquals(tight(channels), tight(merged)), "round trip of " + picture);
            }
        }

        @Test
        @DisplayName("merge validates its channels")
        void mergeValidation() {
            var planes = ImageOps.split(Pictures.randomRgba(random, 4, 3, false));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.merge(PixelLayout.RGBA, planes));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.merge(PixelLayout.RGB,
                    List.of(planes.get(0), planes.get(1), ImageOps.toChannel16(planes.get(2)))));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.merge(PixelLayout.RGB,
                    List.of(planes.get(0), planes.get(1), Channel8Picture.allocate(4, 2, PixelLayout.GRAY))));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.merge(PixelLayout.GRAY,
                    List.of(Pictures.randomRgba(random, 4, 3, false))));
            assertThrows(NullPointerException.class, () -> ImageOps.merge(PixelLayout.GRAY, Arrays.asList((Picture) null)));
        }

        @Test
        @DisplayName("putAlpha replaces or appends straight alpha, rescaling across depths")
        void putAlpha() {
            var rgb = Pictures.randomRgba(random, 6, 4, false);
            var alpha = Channel16Picture.allocate(6, 4, PixelLayout.GRAY);
            for (var i = 0; i < 24; i++) {
                alpha.pixels()[i] = (short) (i * 2000);
            }
            var packed = (RgbaPicture) ImageOps.putAlpha(rgb, alpha);
            assertTrue(packed.hasAlpha());
            var colors = samples(rgb);
            for (var i = 0; i < 24; i++) {
                assertEquals((i * 2000 * 255 + 32767) / 65535, packed.pixels()[4 * i + 3] & 0xFF);
                assertEquals(colors[4 * i], packed.pixels()[4 * i] & 0xFF);
            }
            var deep = (Rgba16Picture) ImageOps.putAlpha(wide(rgb), 100);
            assertEquals(100 * 257, deep.pixels()[3] & 0xFFFF);
            var cmyk = cmyk();
            var small = Channel16Picture.allocate(5, 3, PixelLayout.GRAY);
            for (var i = 0; i < 15; i++) {
                small.pixels()[i] = (short) (i * 2000);
            }
            var withAlpha = assertInstanceOf(Channel16Picture.class, ImageOps.putAlpha(cmyk, small));
            assertEquals(PixelLayout.CMYKA, withAlpha.layout());
            assertEquals(2000 * 5, withAlpha.pixels()[5 * 5 + 4] & 0xFFFF);
            assertEquals(cmyk.pixels()[4 * 5 + 2], withAlpha.pixels()[5 * 5 + 2]);
            var replaced = assertInstanceOf(Channel16Picture.class, ImageOps.putAlpha(withAlpha, 0));
            assertEquals(PixelLayout.CMYKA, replaced.layout());
            assertEquals(0, replaced.pixels()[5 * 5 + 4]);
            var floating = assertInstanceOf(ChannelFloatPicture.class,
                    ImageOps.putAlpha(ImageOps.toChannelFloat(rgb), alpha));
            assertEquals(2000 * 7 / 65535f, floating.pixels()[4 * 7 + 3], 1e-7);
            var premultiplied = Channel8Picture.allocate(6, 4, PixelLayout.GRAY.withChannel(
                    Channel.of(ChannelType.PREMULTIPLIED_ALPHA)));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.putAlpha(premultiplied, 3));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.putAlpha(rgb, 256));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.putAlpha(rgb, Channel8Picture.allocate(6, 3,
                    PixelLayout.GRAY)));
        }

        private Channel16Picture cmyk() {
            var picture = Channel16Picture.allocate(5, 3, PixelLayout.CMYK);
            for (var i = 0; i < picture.pixels().length; i++) {
                picture.pixels()[i] = (short) random.nextInt(65536);
            }
            return picture;
        }

        private static Object tight(ChannelPicture picture) {
            var count = picture.width() * picture.channelCount();
            return switch (picture) {
                case Channel8Picture eight -> {
                    var out = new byte[count * picture.height()];
                    for (var y = 0; y < picture.height(); y++) {
                        System.arraycopy(eight.pixels(), y * eight.stride(), out, y * count, count);
                    }
                    yield out;
                }
                case Channel16Picture sixteen -> {
                    var out = new short[count * picture.height()];
                    for (var y = 0; y < picture.height(); y++) {
                        System.arraycopy(sixteen.pixels(), y * sixteen.stride(), out, y * count, count);
                    }
                    yield out;
                }
                case ChannelFloatPicture floating -> {
                    var out = new float[count * picture.height()];
                    for (var y = 0; y < picture.height(); y++) {
                        System.arraycopy(floating.pixels(), y * floating.stride(), out, y * count, count);
                    }
                    yield out;
                }
            };
        }
    }

    @Test
    @DisplayName("every operation keeps the input unchanged and handles a single pixel")
    void singlePixel() {
        List<UnaryOperator<Picture>> operations = List.of(
                p -> ImageAdjustments.equalize(p), p -> ImageAdjustments.posterize(p, 2),
                p -> ImageAdjustments.solarize(p, 100), p -> ImageAdjustments.rotateHue(p, 90),
                p -> ImageAdjustments.vignette(p, 0.7, 0.2), p -> ImageAdjustments.whiteBalance(p, 2500, 0.01),
                p -> ImageFilters.minimum(p, 3), p -> ImageFilters.rank(p, 2, 7), p -> ImageFilters.mode(p, 1),
                p -> ImageOps.perspective(p, PerspectiveTransform.identity(), 1, 1, Interpolation.BICUBIC, 0),
                p -> ImageOps.quad(p, Quad.of(0, 0, 1, 1), 2, 2, Interpolation.BILINEAR, 0),
                p -> ImageOps.putAlpha(p, p));
        for (var alpha : new boolean[]{false, true}) {
            for (var picture : List.<Picture>of(Pictures.randomRgba(random, 1, 1, alpha),
                    wide(Pictures.randomRgba(random, 1, 1, alpha)))) {
                var before = samples(picture);
                for (var operation : operations) {
                    var result = operation.apply(picture);
                    assertTrue(result.width() >= 1 && result.height() >= 1);
                    assertArrayEquals(before, samples(picture));
                }
            }
        }
    }
}
