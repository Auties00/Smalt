package com.github.auties00.smalt.internal.field;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the field kernels sample for sample against direct implementations of their definitions, on 8-bit
 * and 16-bit planes, planar and packed, and sizes large enough to split into bands; and checks the combing
 * measures on synthetic interlaced pictures of a moving edge.
 */
@DisplayName("Field operations")
class FieldOpsTest {
    static Plane random(boolean wide, int width, int height, int step, long seed) {
        var random = new SplittableRandom(seed);
        var stride = width + 3;
        if (wide) {
            var samples = new short[stride * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(1 << 10);
            }
            return new Plane(null, samples, stride, width, height, step);
        }
        var samples = new byte[stride * height];
        random.nextBytes(samples);
        return new Plane(samples, null, stride, width, height, step);
    }

    static Plane empty(Plane like) {
        return like.wide() ? new Plane(null, new short[like.shorts().length], like.stride(), like.width(), like.height(),
                like.step()) : new Plane(new byte[like.bytes().length], null, like.stride(), like.width(), like.height(),
                like.step());
    }

    static int get(Plane plane, int x, int y) {
        var index = y * plane.stride() + x;
        return plane.wide() ? plane.shorts()[index] & 0xFFFF : plane.bytes()[index] & 0xFF;
    }

    static int[][] matrix(Plane plane) {
        var rows = new int[plane.height()][plane.width()];
        for (var y = 0; y < plane.height(); y++) {
            for (var x = 0; x < plane.width(); x++) {
                rows[y][x] = get(plane, x, y);
            }
        }
        return rows;
    }

    /** The reference rebuild of one missing sample, written from the method definitions. */
    static int rebuilt(Plane source, int x, int y, int method) {
        var height = source.height();
        var hasAbove = y - 1 >= 0;
        var hasBelow = y + 1 < height;
        if (!hasAbove && !hasBelow) {
            return get(source, x, y);
        }
        if (!hasAbove) {
            return get(source, x, y + 1);
        }
        if (!hasBelow || method == FieldOps.BOB) {
            return get(source, x, y - 1);
        }
        var a = get(source, x, y - 1);
        var b = get(source, x, y + 1);
        if (method == FieldOps.LINEAR) {
            return (a + b + 1) / 2;
        }
        var step = source.step();
        var bestDiff = Math.abs(a - b);
        var bestSum = a + b;
        if (x - step >= 0 && x + step < source.width()) {
            int[][] pairs = {{x - step, x + step}, {x + step, x - step}};
            for (var pair : pairs) {
                var up = get(source, pair[0], y - 1);
                var down = get(source, pair[1], y + 1);
                if (Math.abs(up - down) < bestDiff) {
                    bestDiff = Math.abs(up - down);
                    bestSum = up + down;
                }
            }
        }
        return (bestSum + 1) / 2;
    }

    static Stream<Arguments> shapes() {
        var list = new ArrayList<Arguments>();
        for (var wide : new boolean[]{false, true}) {
            for (var step : new int[]{1, 4}) {
                for (var size : new int[][]{{1, 1}, {7, 2}, {9, 5}, {33, 17}, {640, 241}}) {
                    list.add(Arguments.of(wide, step, size[0] * step, size[1]));
                }
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("rebuilding a frame from one field")
    class Deinterlace {
        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("matches the definitions of bob, linear and edge-based line average for both fields")
        void reference(boolean wide, int step, int width, int height) {
            var source = random(wide, width, height, step, width * 31L + height);
            for (var method : new int[]{FieldOps.BOB, FieldOps.LINEAR, FieldOps.ELA}) {
                for (var bottom : new boolean[]{false, true}) {
                    var target = empty(source);
                    FieldOps.deinterlace(source, target, bottom, method);
                    for (var y = 0; y < height; y++) {
                        var kept = ((y & 1) == 1) == bottom;
                        for (var x = 0; x < width; x++) {
                            var expected = kept ? get(source, x, y) : rebuilt(source, x, y, method);
                            assertEquals(expected, get(target, x, y), "method " + method + " bottom " + bottom + " at "
                                    + x + "," + y);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("follows a diagonal edge with the edge-based line average where linear blurs it")
        void diagonal() {
            // a 45-degree edge: sample (x, y) is 200 left of the line x = y and 20 right of it
            var width = 32;
            var height = 32;
            var samples = new byte[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    samples[y * width + x] = (byte) (x < y ? 200 : 20);
                }
            }
            var source = new Plane(samples, null, width, width, height, 1);
            var ela = empty(source);
            var linear = empty(source);
            FieldOps.deinterlace(source, ela, false, FieldOps.ELA);
            FieldOps.deinterlace(source, linear, false, FieldOps.LINEAR);
            var elaError = 0;
            var linearError = 0;
            for (var y = 1; y < height - 1; y += 2) {
                for (var x = 1; x < width - 1; x++) {
                    elaError += Math.abs(get(ela, x, y) - get(source, x, y));
                    linearError += Math.abs(get(linear, x, y) - get(source, x, y));
                }
            }
            assertEquals(0, elaError, "the edge-based average reproduces the missing rows of a diagonal edge");
            assertTrue(linearError > 1000, "linear interpolation blurs the edge: " + linearError);
        }
    }

    @Nested
    @DisplayName("weaving and blending")
    class WeaveBlend {
        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("weaves the even rows of one plane with the odd rows of the other")
        void weave(boolean wide, int step, int width, int height) {
            var top = random(wide, width, height, step, 1);
            var bottom = random(wide, width, height, step, 2);
            var target = empty(top);
            FieldOps.weave(top, bottom, target);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    assertEquals(get((y & 1) == 0 ? top : bottom, x, y), get(target, x, y));
                }
            }
        }

        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("blends two planes with the rounded fixed-point weight")
        void blend(boolean wide, int step, int width, int height) {
            var a = random(wide, width, height, step, 3);
            var b = random(wide, width, height, step, 4);
            for (var weight : new int[]{0, 1, 77, 128, 255, 256}) {
                var target = empty(a);
                FieldOps.blend(a, b, weight, target);
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var expected = (get(a, x, y) * (256 - weight) + get(b, x, y) * weight + 128) >> 8;
                        assertEquals(expected, get(target, x, y), "weight " + weight);
                    }
                }
            }
        }

        @Test
        @DisplayName("blends in place")
        void inPlace() {
            var a = random(false, 50, 9, 1, 5);
            var b = random(false, 50, 9, 1, 6);
            var expected = empty(a);
            FieldOps.blend(a, b, 100, expected);
            FieldOps.blend(a, b, 100, a);
            assertArrayEquals(matrix(expected), matrix(a));
        }
    }

    @Nested
    @DisplayName("measuring combing and differences")
    class Measures {
        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("sums |2 * sample - above - below| over the inner rows, and counts combed samples")
        void reference(boolean wide, int step, int width, int height) {
            var top = random(wide, width, height, step, 7);
            var bottom = random(wide, width, height, step, 8);
            long sum = 0;
            long count = 0;
            var threshold = wide ? 64 : 16;
            for (var y = 1; y < height - 1; y++) {
                var middle = (y & 1) == 0 ? top : bottom;
                var outer = (y & 1) == 0 ? bottom : top;
                for (var x = 0; x < width; x++) {
                    var m = get(middle, x, y);
                    var u = get(outer, x, y - 1);
                    var d = get(outer, x, y + 1);
                    sum += Math.abs(2 * m - u - d);
                    if (m - u > threshold && m - d > threshold || m - u < -threshold && m - d < -threshold) {
                        count++;
                    }
                }
            }
            assertEquals(sum, FieldOps.combing(top, bottom));
            assertEquals(count, FieldOps.combedSamples(top, bottom, threshold));
            long difference = 0;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    difference += Math.abs(get(top, x, y) - get(bottom, x, y));
                }
            }
            assertEquals(difference, FieldOps.difference(top, bottom));
        }

        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("counts the samples combed across fields and smooth within their own")
        void frameReference(boolean wide, int step, int width, int height) {
            var plane = random(wide, width, height, step, 9);
            var threshold = wide ? 300 : 70;
            long count = 0;
            for (var y = 2; y < height - 2; y++) {
                for (var x = 0; x < width; x++) {
                    var m = get(plane, x, y);
                    var up = m - get(plane, x, y - 1);
                    var down = m - get(plane, x, y + 1);
                    var across = up > threshold && down > threshold || up < -threshold && down < -threshold;
                    var own = Math.abs(m - get(plane, x, y - 2)) <= threshold
                            || Math.abs(m - get(plane, x, y + 2)) <= threshold;
                    if (across && own) {
                        count++;
                    }
                }
            }
            assertEquals(count, FieldOps.combedFrameSamples(plane, threshold));
        }

        @Test
        @DisplayName("does not take the thin horizontal lines of a progressive frame for combing")
        void thinLines() {
            var width = 32;
            var height = 32;
            var samples = new byte[width * height];
            for (var y = 0; y < height; y++) {
                java.util.Arrays.fill(samples, y * width, (y + 1) * width, (byte) (y % 6 == 3 ? 230 : 30));
            }
            var lines = new Plane(samples, null, width, width, height, 1);
            assertTrue(FieldOps.combedSamples(lines, lines, 16) > 100, "the three-row test counts the lines");
            assertEquals(0, FieldOps.combedFrameSamples(lines, 16));
            var woven = movingBar(6);
            assertTrue(FieldOps.combedFrameSamples(woven, 12) >= 40L * 12, "motion still counts");
        }

        @ParameterizedTest(name = "wide={0} step={1} {2}x{3}")
        @MethodSource("com.github.auties00.smalt.internal.field.FieldOpsTest#shapes")
        @DisplayName("finds the densest combing over 16 by 16 windows placed every 8 samples")
        void densest(boolean wide, int step, int width, int height) {
            var plane = random(wide, width, height, step, 10);
            var threshold = wide ? 300 : 70;
            var best = 0;
            for (var top = 0; top < height; top += 8) {
                for (var left = 0; left < width; left += 8) {
                    var count = 0;
                    for (var y = Math.max(2, top); y < Math.min(height - 2, top + 16); y++) {
                        for (var x = left; x < Math.min(width, left + 16); x++) {
                            var m = get(plane, x, y);
                            var up = m - get(plane, x, y - 1);
                            var down = m - get(plane, x, y + 1);
                            var across = up > threshold && down > threshold || up < -threshold && down < -threshold;
                            if (across && (Math.abs(m - get(plane, x, y - 2)) <= threshold
                                    || Math.abs(m - get(plane, x, y + 2)) <= threshold)) {
                                count++;
                            }
                        }
                    }
                    best = Math.max(best, count);
                }
            }
            // the scratch starts dirty: every cell must be overwritten
            var cells = new int[FieldOps.combingCells(width, height) + 3];
            java.util.Arrays.fill(cells, 1000);
            assertEquals(best, FieldOps.densestCombing(plane, threshold, cells));
            assertEquals(best, FieldOps.densestCombing(plane, threshold, cells), "a reused scratch");
            var bar = movingBar(6);
            assertTrue(FieldOps.densestCombing(bar, 12, new int[FieldOps.combingCells(bar.width(), bar.height())]) >= 64);
        }

        /** A frame woven from a bright bar seen at two positions, as a camera scanning fields would see it. */
        static Plane movingBar(int shift) {
            var width = 64;
            var height = 48;
            var samples = new byte[width * height];
            for (var y = 0; y < height; y++) {
                var offset = (y & 1) == 0 ? 0 : shift;
                for (var x = 0; x < width; x++) {
                    samples[y * width + x] = (byte) (x >= 20 + offset && x < 36 + offset ? 235 : 16);
                }
            }
            return new Plane(samples, null, width, width, height, 1);
        }

        @Test
        @DisplayName("finds combing in a woven frame of motion and none once each method rebuilds a field")
        void combedPattern() {
            var woven = movingBar(6);
            var still = movingBar(0);
            assertEquals(0, FieldOps.combedSamples(still, still, 12));
            var combed = FieldOps.combedSamples(woven, woven, 12);
            assertTrue(combed >= 46L * 12, "combed samples " + combed);
            for (var method : List.of(FieldOps.BOB, FieldOps.LINEAR, FieldOps.ELA)) {
                var target = empty(woven);
                FieldOps.deinterlace(woven, target, false, method);
                assertEquals(0, FieldOps.combedSamples(target, target, 12), "method " + method);
                assertTrue(FieldOps.combing(target, target) < FieldOps.combing(woven, woven) / 4, "method " + method);
            }
        }
    }

    @Nested
    @DisplayName("planes of pictures")
    class Planes {
        @Test
        @DisplayName("describes the planes of each picture type")
        void planes() {
            var yuv = YuvPicture.allocate(10, 6, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED);
            var planes = Plane.of(yuv);
            assertEquals(4, planes.length);
            assertEquals(5, planes[1].width());
            assertEquals(3, planes[1].height());
            var deep = Yuv16Picture.allocate(10, 6, ChromaFormat.YUV422, 10, false, ColorInfo.BT709_LIMITED);
            var deepPlanes = Plane.of(deep);
            assertEquals(3, deepPlanes.length);
            assertTrue(deepPlanes[0].wide());
            assertEquals(6, deepPlanes[2].height());
            var rgba = RgbaPicture.allocate(10, 6, false);
            var packed = Plane.of(rgba);
            assertEquals(1, packed.length);
            assertEquals(40, packed[0].width());
            assertEquals(4, packed[0].step());
        }
    }
}
