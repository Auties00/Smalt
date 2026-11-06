package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.jpeg.internal.ComponentFrames;
import com.github.auties00.smalt.image.jpeg.internal.ComponentFrames.Scans;
import com.github.auties00.smalt.image.jpeg.internal.JpegCoefficients;
import com.github.auties00.smalt.image.jpeg.internal.JpegPlanes;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the lossless transforms of frames of two or more than four components, and of frames whose MCU holds
 * more blocks than one interleaved scan may (T.81 B.2.3): every output block against a reference built from the
 * source's coefficients, for the eight transforms with no crop, a crop on MCU boundaries and one off them, in
 * every output coding; the decoded component planes against the oriented planes of the source; byte identity
 * with {@code jpegtran} wherever it writes the frame (two components, and CMYK as a regression); and robustness
 * against mutated and truncated sources. libjpeg-turbo reads no scan of a component after the fourth and writes
 * no MCU over ten blocks, so frames of five to seven components are also checked against the coefficients IJG
 * jpegtran 9f writes (pinned vectors, its bytes differ from libjpeg-turbo's), and the ITU-T T.83 streams A1 (ten
 * blocks per MCU, sampling factors of 3 and 4) and B2 (255 components) against jpegtran and the reference.
 */
class JpegTransformComponentsTest {
    // jpegtran's transforms as a transposition followed by flips of the transposed image
    private static boolean flipsHorizontally(Orientation orientation) {
        return switch (orientation) {
            case FLIP_HORIZONTAL, ROTATE_180, ROTATE_90, TRANSVERSE -> true;
            default -> false;
        };
    }

    private static boolean flipsVertically(Orientation orientation) {
        return switch (orientation) {
            case FLIP_VERTICAL, ROTATE_180, ROTATE_270, TRANSVERSE -> true;
            default -> false;
        };
    }

    private record Source(String name, byte[] jpeg, boolean perfect) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final int[] ONES_6 = {1, 1, 1, 1, 1, 1};

    private static List<Source> sources() {
        var sources = new ArrayList<Source>();
        sources.add(new Source("2 components 2x2,1x1 48x32",
                ComponentFrames.write(48, 32, new int[]{2, 1}, new int[]{2, 1}, 1, Scans.INTERLEAVED), true));
        sources.add(new Source("2 components 2x2,1x1 45x29",
                ComponentFrames.write(45, 29, new int[]{2, 1}, new int[]{2, 1}, 2, Scans.INTERLEAVED), false));
        sources.add(new Source("2 components 1x1,1x2 37x27 separate scans",
                ComponentFrames.write(37, 27, new int[]{1, 1}, new int[]{1, 2}, 3, Scans.SEPARATE), false));
        sources.add(new Source("2 components 4x2,2x2 64x32 (12 blocks per MCU)",
                ComponentFrames.write(64, 32, new int[]{4, 2}, new int[]{2, 2}, 4, Scans.SEPARATE), true));
        sources.add(new Source("2 components 4x2,2x2 61x27 progressive (12 blocks per MCU)",
                ComponentFrames.write(61, 27, new int[]{4, 2}, new int[]{2, 2}, 5, Scans.PROGRESSIVE), false));
        sources.add(new Source("5 components 48x32",
                ComponentFrames.write(48, 32, new int[]{2, 1, 1, 2, 1}, new int[]{2, 1, 2, 1, 1}, 6, Scans.SEPARATE),
                true));
        sources.add(new Source("5 components 45x29 progressive",
                ComponentFrames.write(45, 29, new int[]{2, 1, 1, 2, 1}, new int[]{2, 1, 2, 1, 1}, 7,
                        Scans.PROGRESSIVE), false));
        sources.add(new Source("6 components 1x1 40x24",
                ComponentFrames.write(40, 24, ONES_6, ONES_6, 8, Scans.SEPARATE), true));
        sources.add(new Source("6 components 1x1 35x21", ComponentFrames.write(35, 21, ONES_6, ONES_6, 9,
                Scans.PROGRESSIVE), false));
        sources.add(new Source("6 components mixed 32x32", ComponentFrames.write(32, 32,
                new int[]{1, 2, 1, 1, 2, 1}, new int[]{1, 1, 2, 1, 2, 1}, 10, Scans.PROGRESSIVE), true));
        sources.add(new Source("YCbCr 4x2,2x2,1x1 64x32 (13 blocks per MCU)", ComponentFrames.write(64, 32,
                new int[]{4, 2, 1}, new int[]{2, 2, 1}, 11, Scans.SEPARATE, true, -1), true));
        sources.add(new Source("CMYK 2x2 each 32x32 (16 blocks per MCU)", ComponentFrames.write(32, 32,
                new int[]{2, 2, 2, 2}, new int[]{2, 2, 2, 2}, 12, Scans.SEPARATE, false, 0), true));
        // sampling factors of 3, whose component grids are not a power-of-two fraction of the MCU
        sources.add(new Source("5 components 3x1,1x1,1x3,2x2,1x1 48x48", ComponentFrames.write(48, 48,
                new int[]{3, 1, 1, 2, 1}, new int[]{1, 1, 3, 2, 1}, 21, Scans.SEPARATE), true));
        sources.add(new Source("5 components 3x1,1x1,1x3,2x2,1x1 50x37 progressive", ComponentFrames.write(50, 37,
                new int[]{3, 1, 1, 2, 1}, new int[]{1, 1, 3, 2, 1}, 22, Scans.PROGRESSIVE), false));
        sources.add(new Source("2 components 3x2,1x1 48x32", ComponentFrames.write(48, 32, new int[]{3, 1},
                new int[]{2, 1}, 23, Scans.INTERLEAVED), true));
        sources.add(new Source("2 components 3x4,1x1 47x37 (13 blocks per MCU)", ComponentFrames.write(47, 37,
                new int[]{3, 1}, new int[]{4, 1}, 24, Scans.SEPARATE), false));
        return sources;
    }

    private static final List<Orientation> ORIENTATIONS = List.of(Orientation.values());

    // codings of the output, all over the same coefficients
    private static final Map<String, JpegTransformOptions> CODINGS = codings();

    private static Map<String, JpegTransformOptions> codings() {
        var codings = new LinkedHashMap<String, JpegTransformOptions>();
        var base = JpegTransformOptions.jpegtran();
        codings.put("sequential", base);
        codings.put("optimized restart 2", base.toBuilder().optimizeHuffman(true).restartInterval(2).build());
        codings.put("progressive", base.toBuilder().progressive(true).build());
        codings.put("arithmetic", base.toBuilder().arithmetic(true).restartRows(1).build());
        codings.put("progressive arithmetic", base.toBuilder().progressive(true).arithmetic(true).build());
        return codings;
    }

    static Stream<Arguments> references() {
        var list = new ArrayList<Arguments>();
        for (var source : sources()) {
            var info = JpegDecoder.probe(MediaSource.of(source.jpeg()));
            for (var orientation : ORIENTATIONS) {
                for (var coding : CODINGS.entrySet()) {
                    list.add(Arguments.of(source, orientation, coding.getKey(), null));
                }
                if (source.perfect()) {
                    var width = orientation.swapsDimensions() ? info.height() : info.width();
                    var height = orientation.swapsDimensions() ? info.width() : info.height();
                    // one crop starting on an MCU boundary and one inside an MCU, both reaching the opposite edge
                    // only for the first
                    list.add(Arguments.of(source, orientation, "sequential",
                            new Region(width / 2 - width / 2 % 32, 0, width - (width / 2 - width / 2 % 32), height / 2)));
                    list.add(Arguments.of(source, orientation, "progressive",
                            new Region(13, 9, Math.min(20, width - 13), Math.min(17, height - 9))));
                }
            }
        }
        return list.stream();
    }

    private static JpegCoefficients coefficients(byte[] jpeg) {
        return new JpegReader(jpeg, 0, jpeg.length, WIDE).decodeCoefficients();
    }

    private static JpegPlanes planes(byte[] jpeg) {
        return new JpegReader(jpeg, 0, jpeg.length, WIDE).decodePlanes(0);
    }

    private static byte[] transform(byte[] jpeg, JpegTransformOptions options) {
        return JpegTransforms.transform(MediaSource.of(jpeg), options);
    }

    /**
     * Checks every block of a transformed image against the source block it came from: transposed when the
     * transform swaps the axes, with the odd horizontal frequencies negated by a horizontal flip and the odd
     * vertical frequencies by a vertical one. A trimmed flip keeps only the whole MCUs of its flipped axis, and a
     * crop starts on the MCU boundary at or before its corner.
     */
    private static void assertTransformed(byte[] sourceJpeg, byte[] outputJpeg, Orientation orientation,
                                          Region crop) {
        var source = coefficients(sourceJpeg);
        var output = coefficients(outputJpeg);
        var frame = source.frame();
        var outFrame = output.frame();
        var transposed = orientation.swapsDimensions();
        var flipX = flipsHorizontally(orientation);
        var flipY = flipsVertically(orientation);
        var width = transposed ? frame.height() : frame.width();
        var height = transposed ? frame.width() : frame.height();
        var hMax = transposed ? frame.vMax() : frame.hMax();
        var vMax = transposed ? frame.hMax() : frame.vMax();
        var mcuWidth = 8 * hMax;
        var mcuHeight = 8 * vMax;
        var trimmedWidth = flipX ? width / mcuWidth * mcuWidth : width;
        var trimmedHeight = flipY ? height / mcuHeight * mcuHeight : height;
        var x0 = crop == null ? 0 : crop.x() / mcuWidth * mcuWidth;
        var y0 = crop == null ? 0 : crop.y() / mcuHeight * mcuHeight;
        assertEquals(crop == null ? trimmedWidth : crop.x() + crop.width() - x0, outFrame.width(), "width");
        assertEquals(crop == null ? trimmedHeight : crop.y() + crop.height() - y0, outFrame.height(), "height");
        assertEquals(frame.precision(), outFrame.precision());
        var count = frame.components().size();
        assertEquals(count, outFrame.components().size(), "component count");
        for (var c = 0; c < count; c++) {
            var in = frame.components().get(c);
            var out = outFrame.components().get(c);
            var h = transposed ? in.verticalSampling() : in.horizontalSampling();
            var v = transposed ? in.horizontalSampling() : in.verticalSampling();
            assertEquals(in.id(), out.id(), "component id");
            assertEquals(h, out.horizontalSampling(), "horizontal sampling of component " + c);
            assertEquals(v, out.verticalSampling(), "vertical sampling of component " + c);
            var table = source.quantization(c);
            var expectedTable = new int[64];
            for (var k = 0; k < 64; k++) {
                expectedTable[k] = transposed ? table[(k % 8) * 8 + k / 8] : table[k];
            }
            assertArrayEquals(expectedTable, output.quantization(c), "quantization of component " + c);
            var blocksWide = trimmedWidth / mcuWidth * h;
            var blocksHigh = trimmedHeight / mcuHeight * v;
            var xb0 = x0 / mcuWidth * h;
            var yb0 = y0 / mcuHeight * v;
            var sourceCoefficients = source.coefficients(c);
            var outputCoefficients = output.coefficients(c);
            for (var by = 0; by < out.blocksHigh(); by++) {
                for (var bx = 0; bx < out.blocksWide(); bx++) {
                    var x = bx + xb0;
                    var y = by + yb0;
                    var xt = flipX ? blocksWide - 1 - x : x;
                    var yt = flipY ? blocksHigh - 1 - y : y;
                    var sx = transposed ? yt : xt;
                    var sy = transposed ? xt : yt;
                    assertTrue(sx >= 0 && sy >= 0 && sx < in.blocksWide() && sy < in.blocksHigh(),
                            "block " + bx + "," + by + " of component " + c + " maps outside the source");
                    var sourceOffset = (sy * in.paddedBlocksWide() + sx) * 64;
                    var outputOffset = (by * out.paddedBlocksWide() + bx) * 64;
                    for (var k = 0; k < 64; k++) {
                        var row = k / 8;
                        var column = k % 8;
                        int value = sourceCoefficients[sourceOffset + (transposed ? column * 8 + row : k)];
                        if (flipX && column % 2 == 1) {
                            value = -value;
                        }
                        if (flipY && row % 2 == 1) {
                            value = -value;
                        }
                        if (value != outputCoefficients[outputOffset + k]) {
                            throw new AssertionError("component " + c + " block " + bx + "," + by + " coefficient "
                                    + k + ": " + outputCoefficients[outputOffset + k] + " instead of " + value);
                        }
                    }
                }
            }
        }
    }

    // the scans of a file: their component counts, in order
    private static List<Integer> scanSizes(byte[] jpeg) {
        var sizes = new ArrayList<Integer>();
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xD9) {
                break;
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker == 0xDA) {
                sizes.add(jpeg[position + 4] & 0xFF);
                position += 2 + length;
                while (position + 1 < jpeg.length && ((jpeg[position] & 0xFF) != 0xFF
                        || (jpeg[position + 1] & 0xFF) == 0 || (jpeg[position + 1] & 0xF8) == 0xD0)) {
                    position++;
                }
            } else {
                position += 2 + length;
            }
        }
        return sizes;
    }

    private static boolean hasSegment(byte[] jpeg, int marker, String identifier) {
        var position = 2;
        while (position + 4 <= jpeg.length && (jpeg[position + 1] & 0xFF) != 0xDA) {
            var code = jpeg[position + 1] & 0xFF;
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (code == marker && new String(jpeg, position + 4, Math.min(identifier.length(), length - 2),
                    java.nio.charset.StandardCharsets.ISO_8859_1).equals(identifier)) {
                return true;
            }
            position += 2 + length;
        }
        return false;
    }

    @Nested
    @DisplayName("against the source coefficients")
    class Reference {
        @ParameterizedTest(name = "{0} {1} {2} crop {3}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformComponentsTest#references")
        @DisplayName("moves, transposes and mirrors every block of every component on its own sampling grid")
        void blocks(Source source, Orientation orientation, String coding, Region crop) {
            var builder = CODINGS.get(coding).toBuilder().transform(orientation).trim(true);
            if (crop != null) {
                builder.crop(crop);
            }
            var output = transform(source.jpeg(), builder.build());
            assertTransformed(source.jpeg(), output, orientation, crop);
            var frame = coefficients(output).frame();
            assertEquals(coding.contains("progressive"), frame.progressive(), "progressive");
            assertEquals(coding.contains("arithmetic"), frame.arithmetic(), "arithmetic");
        }
    }

    @Nested
    @DisplayName("scan layout")
    class Layout {
        @Test
        @DisplayName("interleaves components that fit one scan and gives one scan to each of those that do not")
        void scans() {
            var sources = sources();
            var two = sources.get(0).jpeg();
            assertEquals(List.of(2), scanSizes(transform(two, JpegTransformOptions.jpegtran())));
            var large = sources.get(3).jpeg();
            assertEquals(List.of(1, 1), scanSizes(transform(large, JpegTransformOptions.jpegtran())));
            var five = sources.get(5).jpeg();
            assertEquals(List.of(1, 1, 1, 1, 1), scanSizes(transform(five, JpegTransformOptions.jpegtran())));
            // jpeg_simple_progression: 2 DC and 4 AC scans per component, one per component beyond four
            var progressive = JpegTransformOptions.jpegtran().toBuilder().progressive(true).build();
            assertEquals(30, scanSizes(transform(five, progressive)).size());
            assertTrue(scanSizes(transform(five, progressive)).stream().allMatch(size -> size == 1));
            var sizes = scanSizes(transform(two, progressive));
            assertEquals(2 + 4 * 2, sizes.size());
            assertEquals(2, sizes.getFirst());
            var ycbcr = sources.get(10).jpeg();
            var ycbcrSizes = scanSizes(transform(ycbcr, progressive));
            assertEquals(14, ycbcrSizes.size(), "the YCbCr script with its two DC scans split per component");
            assertTrue(ycbcrSizes.stream().allMatch(size -> size == 1));
        }

        @Test
        @DisplayName("writes no JFIF or Adobe segment for a frame without a color space, and keeps those of the others")
        void headers() {
            var sources = sources();
            for (var index : List.of(0, 5, 7)) {
                var output = transform(sources.get(index).jpeg(), JpegTransformOptions.jpegtran());
                assertFalse(hasSegment(output, 0xE0, "JFIF"), sources.get(index).name());
                assertFalse(hasSegment(output, 0xEE, "Adobe"), sources.get(index).name());
            }
            assertTrue(hasSegment(transform(sources.get(10).jpeg(), JpegTransformOptions.jpegtran()), 0xE0, "JFIF"));
            assertTrue(hasSegment(transform(sources.get(11).jpeg(), JpegTransformOptions.jpegtran()), 0xEE, "Adobe"));
        }

        @Test
        @DisplayName("four quarter turns give back the coefficients of a perfect frame of any component count")
        void fourTurns() {
            for (var source : sources()) {
                if (!source.perfect()) {
                    continue;
                }
                var options = JpegTransformOptions.jpegtran().toBuilder().transform(Orientation.ROTATE_90).build();
                var turned = source.jpeg();
                for (var i = 0; i < 4; i++) {
                    turned = transform(turned, options);
                }
                assertArrayEquals(transform(source.jpeg(), JpegTransformOptions.jpegtran()), turned, source.name());
            }
        }
    }

    @Nested
    @DisplayName("decoded component planes")
    class Planes {
        static Stream<Arguments> cases() {
            var list = new ArrayList<Arguments>();
            for (var source : sources()) {
                if (source.perfect()) {
                    for (var orientation : ORIENTATIONS) {
                        list.add(Arguments.of(source, orientation));
                    }
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("cases")
        @DisplayName("hold the oriented source planes: exactly for mirrors, within the IDCT's rounding for quarter"
                + " turns")
        void oriented(Source source, Orientation orientation) {
            var output = transform(source.jpeg(), JpegTransformOptions.of(orientation));
            var before = planes(source.jpeg());
            var after = planes(output);
            var transposed = orientation.swapsDimensions();
            var flipX = flipsHorizontally(orientation);
            var flipY = flipsVertically(orientation);
            var count = before.frame().components().size();
            for (var c = 0; c < count; c++) {
                var width = before.componentWidth(c);
                var height = before.componentHeight(c);
                var outWidth = transposed ? height : width;
                var outHeight = transposed ? width : height;
                assertEquals(outWidth, after.componentWidth(c));
                assertEquals(outHeight, after.componentHeight(c));
                var samples = before.plane(c);
                var plane = after.plane(c);
                for (var y = 0; y < outHeight; y++) {
                    for (var x = 0; x < outWidth; x++) {
                        var xt = flipX ? outWidth - 1 - x : x;
                        var yt = flipY ? outHeight - 1 - y : y;
                        var sx = transposed ? yt : xt;
                        var sy = transposed ? xt : yt;
                        var expected = samples[sy * before.stride(c) + sx] & 0xFF;
                        var actual = plane[y * after.stride(c) + x] & 0xFF;
                        var difference = Math.abs(expected - actual);
                        if (transposed ? difference > 1 : difference != 0) {
                            throw new AssertionError("component " + c + " sample " + x + "," + y + ": " + actual
                                    + " instead of " + expected);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("decode as channel pictures of the source's channel count")
        void channels() {
            var five = sources().get(5).jpeg();
            var output = transform(five, JpegTransformOptions.of(Orientation.ROTATE_90));
            var image = JpegDecoder.decode(MediaSource.of(output),
                    ImageDecoderOptions.builder().keepChannels(true).build());
            var picture = (com.github.auties00.smalt.image.ChannelPicture) image.picture();
            assertEquals(5, picture.channelCount());
            assertEquals(32, picture.width());
            assertEquals(48, picture.height());
        }
    }

    // jpegtran writes frames of at most four components whose MCU holds at most ten blocks
    private static Map<String, byte[]> jpegtranSources() {
        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("2 components 2x2,1x1 45x29",
                ComponentFrames.write(45, 29, new int[]{2, 1}, new int[]{2, 1}, 2, Scans.INTERLEAVED));
        sources.put("2 components 1x1,1x2 37x27",
                ComponentFrames.write(37, 27, new int[]{1, 1}, new int[]{1, 2}, 3, Scans.SEPARATE));
        sources.put("2 components 1x1 41x23 progressive",
                ComponentFrames.write(41, 23, new int[]{1, 1}, new int[]{1, 1}, 13, Scans.PROGRESSIVE));
        sources.put("CMYK 1x1 45x29", ComponentFrames.write(45, 29, new int[]{1, 1, 1, 1}, new int[]{1, 1, 1, 1},
                14, Scans.INTERLEAVED, false, 0));
        sources.put("CMYK 2x2,1x1,1x1,2x2 45x29", ComponentFrames.write(45, 29, new int[]{2, 1, 1, 2},
                new int[]{2, 1, 1, 2}, 15, Scans.SEPARATE, false, 0));
        sources.put("YCCK 2x1,1x1,1x1,2x1 37x27", ComponentFrames.write(37, 27, new int[]{2, 1, 1, 2},
                new int[]{1, 1, 1, 1}, 16, Scans.INTERLEAVED, false, 2));
        sources.put("2 components 3x2,1x1 47x29", ComponentFrames.write(47, 29, new int[]{3, 1}, new int[]{2, 1},
                25, Scans.INTERLEAVED));
        sources.put("2 components 2x2,1x1 13x11 (smaller than one MCU)", ComponentFrames.write(13, 11,
                new int[]{2, 1}, new int[]{2, 1}, 26, Scans.INTERLEAVED));
        return sources;
    }

    private static final List<List<String>> SWITCHES = List.of(
            List.of(),
            List.of("-optimize"),
            List.of("-progressive"),
            List.of("-arithmetic"),
            List.of("-arithmetic", "-progressive", "-restart", "1"),
            List.of("-rotate", "90"),
            List.of("-rotate", "180"),
            List.of("-rotate", "270"),
            List.of("-flip", "horizontal"),
            List.of("-flip", "vertical"),
            List.of("-transpose"),
            List.of("-transverse"),
            List.of("-rotate", "90", "-trim"),
            List.of("-rotate", "180", "-trim", "-progressive"),
            List.of("-rotate", "270", "-trim", "-arithmetic"),
            List.of("-flip", "horizontal", "-trim"),
            List.of("-flip", "vertical", "-trim", "-optimize"),
            List.of("-transverse", "-trim", "-restart", "2B"),
            List.of("-crop", "20x18+9+5"),
            List.of("-crop", "16x16+16+0", "-rotate", "90"),
            List.of("-crop", "17x13+3+5", "-flip", "horizontal", "-trim"),
            List.of("-crop", "21x19+1+7", "-transverse", "-trim", "-progressive"),
            List.of("-crop", "300rx200+40+8"),
            List.of("-crop", "300fx20f+33+3"),
            List.of("-wipe", "20x18+9+5"),
            List.of("-wipe", "20fx18+9+5", "-progressive"),
            List.of("-wipe", "17rx13+0+8"),
            List.of("-perfect", "-rotate", "90"),
            List.of("-grayscale"),
            List.of("-copy", "none", "-rotate", "180"));

    static Stream<Arguments> jpegtranPairs() {
        var list = new ArrayList<Arguments>();
        for (var entry : jpegtranSources().entrySet()) {
            for (var switches : SWITCHES) {
                list.add(Arguments.of(entry.getKey() + " " + String.join(" ", switches), entry.getValue(), switches));
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("against jpegtran")
    class Jpegtran {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformComponentsTest#jpegtranPairs")
        @DisplayName("writes the same bytes as jpegtran, or fails where it fails")
        void identical(String name, byte[] source, List<String> switches) {
            byte[] expected;
            try {
                expected = JpegTransformsTest.run("jpegtran", switches, source);
            } catch (JpegTransformsTest.ToolFailure failure) {
                var thrown = assertThrows(RuntimeException.class,
                        () -> transform(source, JpegtranSwitches.options(switches)));
                assertTrue(thrown instanceof MediaException || thrown instanceof IllegalArgumentException,
                        thrown.toString());
                return;
            }
            var actual = transform(source, JpegtranSwitches.options(switches));
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError(name + ": " + actual.length + " bytes instead of " + expected.length
                        + ", first difference at " + Arrays.mismatch(expected, actual));
            }
        }
    }

    // YCbCr frames whose MCU is over ten blocks: jpegtran refuses to write them, but djpeg reads them
    static Stream<Arguments> djpegCases() {
        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("YCbCr 4x2,2x2,1x1 64x32", sources().get(10).jpeg());
        sources.put("YCbCr 4x2,2x2,1x1 61x27 progressive", ComponentFrames.write(61, 27, new int[]{4, 2, 1},
                new int[]{2, 2, 1}, 31, Scans.PROGRESSIVE, true, -1));
        sources.put("YCbCr 3x4,1x1,1x2 47x37", ComponentFrames.write(47, 37, new int[]{3, 1, 1},
                new int[]{4, 1, 2}, 32, Scans.SEPARATE, true, -1));
        var list = new ArrayList<Arguments>();
        for (var source : sources.entrySet()) {
            for (var orientation : List.of(Orientation.NORMAL, Orientation.ROTATE_90, Orientation.FLIP_HORIZONTAL,
                    Orientation.TRANSVERSE)) {
                for (var coding : CODINGS.keySet()) {
                    list.add(Arguments.of(source.getKey(), source.getValue(), orientation, coding));
                }
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("against djpeg")
    class Djpeg {
        @ParameterizedTest(name = "{0} {2} {3}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformComponentsTest#djpegCases")
        @DisplayName("one scan per component of an MCU over ten blocks decodes in djpeg without a warning to the"
                + " luma Smalt decodes")
        void luma(String name, byte[] source, Orientation orientation, String coding) {
            com.github.auties00.smalt.testkit.Oracle.assume("djpeg");
            var output = transform(source, CODINGS.get(coding).toBuilder().transform(orientation).trim(true).build());
            assertTrue(scanSizes(output).stream().allMatch(size -> size == 1), "one component per scan");
            var result = com.github.auties00.smalt.testkit.Oracle.execute(
                    com.github.auties00.smalt.testkit.Oracle.Tool.DJPEG, Duration.ofMinutes(1), output,
                    List.of("-dct", "int", "-grayscale", "-pnm"));
            assertEquals(0, result.exitCode(), name + ": " + result.stderr());
            assertTrue(result.stderr().isBlank(), name + ": " + result.stderr());
            var parsed = JpegTestSupport.parsePnm(result.stdout());
            var planes = planes(output);
            assertEquals(planes.frame().width(), parsed.width());
            assertEquals(planes.frame().height(), parsed.height());
            assertArrayEquals(JpegTestSupport.plane(planes.plane(0), planes.stride(0), parsed.width(),
                    parsed.height()), JpegTestSupport.plane(parsed.pixels(), parsed.width(), parsed.width(),
                    parsed.height()), name);
            assertTransformed(source, output, orientation, null);
        }
    }

    @Nested
    @DisplayName("edits and rejections")
    class Edits {
        @Test
        @DisplayName("a wipe grays the MCUs of the region in every component and keeps the others")
        void wipe() {
            var source = sources().get(5).jpeg();
            var output = transform(source, JpegTransformOptions.jpegtran().toBuilder().wipe(new Region(16, 0, 16, 16))
                    .build());
            var before = coefficients(source);
            var after = coefficients(output);
            for (var c = 0; c < 5; c++) {
                var component = before.frame().components().get(c);
                var h = component.horizontalSampling();
                var v = component.verticalSampling();
                for (var by = 0; by < component.blocksHigh(); by++) {
                    for (var bx = 0; bx < component.blocksWide(); bx++) {
                        var offset = (by * component.paddedBlocksWide() + bx) * 64;
                        var wiped = bx >= h && bx < 2 * h && by < v;
                        var expected = wiped ? new short[64]
                                : Arrays.copyOfRange(before.coefficients(c), offset, offset + 64);
                        var outOffset = (by * after.frame().components().get(c).paddedBlocksWide() + bx) * 64;
                        assertArrayEquals(expected, Arrays.copyOfRange(after.coefficients(c), outOffset, outOffset + 64),
                                "component " + c + " block " + bx + "," + by);
                    }
                }
            }
        }

        @Test
        @DisplayName("a drop pastes a frame of the same components and sampling into one")
        void drop() {
            var source = sources().get(5).jpeg();
            var image = ComponentFrames.write(16, 16, new int[]{2, 1, 1, 2, 1}, new int[]{2, 1, 2, 1, 1}, 20,
                    Scans.SEPARATE);
            var output = transform(source, JpegTransformOptions.jpegtran().toBuilder().drop(image, 16, 16).build());
            var before = coefficients(source);
            var dropped = coefficients(image);
            var after = coefficients(output);
            for (var c = 0; c < 5; c++) {
                var component = before.frame().components().get(c);
                var h = component.horizontalSampling();
                var v = component.verticalSampling();
                var dropComponent = dropped.frame().components().get(c);
                for (var by = 0; by < component.blocksHigh(); by++) {
                    for (var bx = 0; bx < component.blocksWide(); bx++) {
                        var inside = bx >= h && bx < 2 * h && by >= v && by < 2 * v;
                        short[] expected;
                        if (inside) {
                            var offset = ((by - v) * dropComponent.paddedBlocksWide() + bx - h) * 64;
                            expected = Arrays.copyOfRange(dropped.coefficients(c), offset, offset + 64);
                        } else {
                            var offset = (by * component.paddedBlocksWide() + bx) * 64;
                            expected = Arrays.copyOfRange(before.coefficients(c), offset, offset + 64);
                        }
                        var outOffset = (by * after.frame().components().get(c).paddedBlocksWide() + bx) * 64;
                        assertArrayEquals(expected, Arrays.copyOfRange(after.coefficients(c), outOffset, outOffset + 64),
                                "component " + c + " block " + bx + "," + by);
                    }
                }
            }
        }

        @Test
        @DisplayName("a grayscale conversion, an imperfect transform under perfect and a crop outside are rejected")
        void rejects() {
            var five = sources().get(6).jpeg();
            assertThrows(MediaException.Unsupported.class, () -> JpegTransforms.grayscale(MediaSource.of(five)));
            assertThrows(MediaException.Unsupported.class, () -> transform(five,
                    JpegTransformOptions.of(Orientation.ROTATE_90).toBuilder().perfect(true).build()));
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.crop(MediaSource.of(five),
                    new Region(40, 0, 16, 16)));
        }
    }

    // the vectors of tools/testdata/jpeg-ijg9f/make-vectors.sh, checked against their pinned SHA-256
    private static byte[] ijg(String name) {
        var resource = "ijg9f/" + name;
        try (var input = JpegTransformComponentsTest.class.getResourceAsStream(resource)) {
            assertTrue(input != null, resource);
            var bytes = input.readAllBytes();
            try (var pins = JpegTransformComponentsTest.class.getResourceAsStream("ijg9f/ijg9f.sha256")) {
                assertTrue(pins != null, "ijg9f.sha256");
                var digest = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                var pinned = new String(pins.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).lines()
                        .filter(line -> line.endsWith("*" + name) || line.endsWith(" " + name))
                        .map(line -> line.substring(0, 64))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(name + " is not pinned"));
                assertEquals(pinned, digest, name);
            }
            return bytes;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static Stream<Arguments> ijgCases() {
        var cases = new String(ijg("cases.tsv"), java.nio.charset.StandardCharsets.US_ASCII);
        return cases.lines().filter(line -> !line.isBlank()).map(line -> {
            var columns = line.split("\t");
            return Arguments.of(columns[0], columns[1], List.of(columns[2].split(" ")));
        });
    }

    // the coefficients, component ids, sampling factors and quantization tables of two files are the same
    private static void assertSameCoefficients(byte[] expectedJpeg, byte[] actualJpeg) {
        var expected = coefficients(expectedJpeg);
        var actual = coefficients(actualJpeg);
        var expectedFrame = expected.frame();
        var actualFrame = actual.frame();
        assertEquals(expectedFrame.width(), actualFrame.width(), "width");
        assertEquals(expectedFrame.height(), actualFrame.height(), "height");
        assertEquals(expectedFrame.components().size(), actualFrame.components().size(), "component count");
        for (var c = 0; c < expectedFrame.components().size(); c++) {
            var e = expectedFrame.components().get(c);
            var a = actualFrame.components().get(c);
            assertEquals(e.id(), a.id(), "id of component " + c);
            assertEquals(e.horizontalSampling(), a.horizontalSampling(), "horizontal sampling of component " + c);
            assertEquals(e.verticalSampling(), a.verticalSampling(), "vertical sampling of component " + c);
            assertArrayEquals(expected.quantization(c), actual.quantization(c), "quantization of component " + c);
            assertEquals(e.blocksWide(), a.blocksWide());
            assertEquals(e.blocksHigh(), a.blocksHigh());
            for (var by = 0; by < e.blocksHigh(); by++) {
                var eo = by * e.paddedBlocksWide() * 64;
                var ao = by * a.paddedBlocksWide() * 64;
                assertArrayEquals(Arrays.copyOfRange(expected.coefficients(c), eo, eo + e.blocksWide() * 64),
                        Arrays.copyOfRange(actual.coefficients(c), ao, ao + a.blocksWide() * 64),
                        "component " + c + " block row " + by);
            }
        }
    }

    @Nested
    @DisplayName("against IJG jpegtran 9f, which transforms frames of more than four components")
    class Ijg {
        @ParameterizedTest(name = "{0}: {2}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformComponentsTest#ijgCases")
        @DisplayName("writes the coefficients IJG jpegtran writes")
        void coefficients(String name, String source, List<String> switches) {
            var actual = transform(ijg(source), JpegtranSwitches.options(switches));
            assertSameCoefficients(ijg(name + ".jpg"), actual);
            assertTrue(scanSizes(actual).stream().allMatch(size -> size == 1), "one component per scan");
        }
    }

    // ITU-T T.83 compliance streams of conformance data (itu-t83-data-files.zip), fetched, never committed
    private static byte[] t83(String name) {
        var archive = com.github.auties00.smalt.testkit.Conformance.fetchListed("itu-t83-data-files.zip");
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entry = zip.getEntry(name);
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final MediaLimits WIDE = MediaLimits.builder().maxChannels(255).build();

    @Nested
    @DisplayName("on the ITU-T T.83 compliance streams")
    class Compliance {
        static Stream<Arguments> a1Switches() {
            return SWITCHES.stream().map(switches -> Arguments.of(String.join(" ", switches), switches));
        }

        @ParameterizedTest(name = "A1 {0}")
        @MethodSource("a1Switches")
        @DisplayName("A1 (four components sampled 1x1, 1x2, 3x1 and 1x4, ten blocks per MCU) as jpegtran writes it")
        void a1(String name, List<String> switches) {
            var source = t83("T83_process1/A1.JPG");
            byte[] expected;
            try {
                expected = JpegTransformsTest.run("jpegtran", switches, source);
            } catch (JpegTransformsTest.ToolFailure failure) {
                var thrown = assertThrows(RuntimeException.class,
                        () -> transform(source, JpegtranSwitches.options(switches)));
                assertTrue(thrown instanceof MediaException || thrown instanceof IllegalArgumentException,
                        thrown.toString());
                return;
            }
            var actual = transform(source, JpegtranSwitches.options(switches));
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError(name + ": " + actual.length + " bytes instead of " + expected.length
                        + ", first difference at " + Arrays.mismatch(expected, actual));
            }
        }

        static Stream<Arguments> b2Cases() {
            return Stream.of(Arguments.of(Orientation.ROTATE_90, "sequential"),
                    Arguments.of(Orientation.TRANSVERSE, "progressive arithmetic"),
                    Arguments.of(Orientation.FLIP_HORIZONTAL, "progressive"),
                    Arguments.of(Orientation.TRANSPOSE, "optimized restart 2"));
        }

        @ParameterizedTest(name = "B2 {0} {1}")
        @MethodSource("b2Cases")
        @DisplayName("B2 (255 components, with the tables of B1) moves every block of every component")
        void b2(Orientation orientation, String coding) {
            var tables = t83("T83_process1/B1.JPG");
            var image = t83("T83_process1/B2.JPG");
            var source = new byte[tables.length - 2 + image.length - 2];
            System.arraycopy(tables, 0, source, 0, tables.length - 2);
            System.arraycopy(image, 2, source, tables.length - 2, image.length - 2);
            var options = CODINGS.get(coding).toBuilder().transform(orientation).trim(true).limits(WIDE).build();
            var output = transform(source, options);
            assertTransformed(source, output, orientation, null);
            assertEquals(255, scanSizes(output).stream().filter(size -> size == 1).count()
                    / (coding.contains("progressive") ? 6 : 1), "one scan per component and pass");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("mutated and truncated sources transform to decodable files or raise MediaException")
        void mutations() {
            var seeds = new ArrayList<byte[]>();
            for (var index : List.of(3, 6, 7, 9)) {
                seeds.add(sources().get(index).jpeg());
            }
            var options = new ArrayList<JpegTransformOptions>();
            options.add(JpegTransformOptions.of(Orientation.ROTATE_90));
            options.add(JpegTransformOptions.of(Orientation.TRANSVERSE).toBuilder().progressive(true).build());
            options.add(JpegTransformOptions.of(Orientation.FLIP_HORIZONTAL).toBuilder().arithmetic(true).build());
            var decodeOptions = ImageDecoderOptions.builder().keepChannels(true).build();
            var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds() * 3), () ->
                    RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(150), 0x4A54, source -> {
                        var bytes = new byte[(int) source.size()];
                        source.readFully(0, bytes, 0, bytes.length);
                        for (var option : options) {
                            var output = JpegTransforms.transform(MediaSource.of(bytes), option);
                            JpegDecoder.decode(MediaSource.of(output), decodeOptions);
                        }
                    }));
            assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
            for (var seed : seeds) {
                for (var length = 0; length < seed.length; length += 3) {
                    var truncated = Arrays.copyOf(seed, length);
                    try {
                        var output = JpegTransforms.transform(MediaSource.of(truncated), options.getFirst());
                        JpegDecoder.decode(MediaSource.of(output), decodeOptions);
                    } catch (MediaException expected) {
                        // rejected
                    }
                }
            }
        }
    }
}
