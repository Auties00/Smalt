package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.UnciWriter;
import com.github.auties00.smalt.testkit.UnciWriter.Component;
import com.github.auties00.smalt.testkit.UnciWriter.Image;
import com.github.auties00.smalt.testkit.UnciWriter.Layout;
import com.github.auties00.smalt.testkit.UnciWriter.PaletteComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static com.github.auties00.smalt.testkit.UnciWriter.A;
import static com.github.auties00.smalt.testkit.UnciWriter.B;
import static com.github.auties00.smalt.testkit.UnciWriter.BLACK;
import static com.github.auties00.smalt.testkit.UnciWriter.CB;
import static com.github.auties00.smalt.testkit.UnciWriter.COMPLEX;
import static com.github.auties00.smalt.testkit.UnciWriter.CR;
import static com.github.auties00.smalt.testkit.UnciWriter.CYAN;
import static com.github.auties00.smalt.testkit.UnciWriter.FILTER_ARRAY;
import static com.github.auties00.smalt.testkit.UnciWriter.FLOAT;
import static com.github.auties00.smalt.testkit.UnciWriter.G;
import static com.github.auties00.smalt.testkit.UnciWriter.MAGENTA;
import static com.github.auties00.smalt.testkit.UnciWriter.MONO;
import static com.github.auties00.smalt.testkit.UnciWriter.PADDED;
import static com.github.auties00.smalt.testkit.UnciWriter.PALETTE;
import static com.github.auties00.smalt.testkit.UnciWriter.R;
import static com.github.auties00.smalt.testkit.UnciWriter.SIGNED;
import static com.github.auties00.smalt.testkit.UnciWriter.UNSIGNED;
import static com.github.auties00.smalt.testkit.UnciWriter.Y;
import static com.github.auties00.smalt.testkit.UnciWriter.YELLOW;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decodes uncompressed items (ISO/IEC 23001-17) that {@link UnciWriter} writes from random values, in every component
 * format and depth, interleaving, sampling, packing and padding, with palettes, filter arrays, CMYK components and
 * generic compression in whole items and in units, and compares every sample with the exact conversion of
 * {@link UnciReference}.
 */
@DisplayName("uncompressed HEIF items of every format and layout")
class HeifUncompressedFormatsTest {
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    /** Decodes an image the writer lays out, with extra properties. */
    private static HeifComponents decode(Image image, int[] definition, byte[]... extra) {
        return decodeData(image, UnciWriter.data(image), definition, extra);
    }

    private static HeifComponents decodeData(Image image, byte[] data, int[] definition, byte[]... extra) {
        var properties = new ArrayList<byte[]>();
        properties.add(UnciWriter.uncC(image.components(), image.layout(), definition));
        properties.add(UnciWriter.cmpd(definition));
        properties.addAll(List.of(extra));
        return UnciReference.decode(UnciWriter.file(image.width(), image.height(), data,
                properties.toArray(byte[][]::new)), LIMITS);
    }

    /** Fills the planes of every component type of an image with random values of its component. */
    private static Map<Integer, BigInteger[]> fill(Random random, int width, int height, Layout layout,
                                                   List<Component> components) {
        var planes = UnciWriter.planes();
        for (var component : components) {
            if (component.type() == PADDED || planes.containsKey(component.type())) {
                continue;
            }
            var chroma = UnciWriter.isChroma(component.type());
            var w = chroma ? (width + layout.sx() - 1) / layout.sx() : width;
            var h = chroma ? (height + layout.sy() - 1) / layout.sy() : height;
            var values = new BigInteger[w * h];
            for (var i = 0; i < values.length; i++) {
                values[i] = UnciReference.random(random, component.format(), component.depth());
            }
            planes.put(component.type(), values);
        }
        return planes;
    }

    /** Asserts that every decoded plane holds the reference conversion of its raw values. */
    private static void assertPlanes(String what, Image image, HeifComponents decoded) {
        for (var component : image.components()) {
            var type = component.type();
            if (type == PADDED || type == PALETTE || type == 8 || type == 9) {
                continue;
            }
            var plane = decoded.plane(type);
            assertNotNull(plane, what + ": plane " + type);
            var raw = image.planes().get(type);
            var expected = new int[raw.length];
            for (var i = 0; i < raw.length; i++) {
                expected[i] = UnciReference.sample(component.format(), component.depth(), raw[i]);
            }
            assertEquals(UnciWriter.planeWidth(image, type), plane.width(), what + ": width of " + type);
            assertEquals(UnciWriter.planeHeight(image, type), plane.height(), what + ": height of " + type);
            assertEquals(UnciReference.outputDepth(component.format(), component.depth()), plane.bitDepth(),
                    what + ": depth of " + type);
            assertArrayEquals(expected, unsigned(plane.samples()), what + ": samples of " + type);
        }
    }

    private static int[] unsigned(short[] samples) {
        var out = new int[samples.length];
        for (var i = 0; i < out.length; i++) {
            out[i] = samples[i] & 0xFFFF;
        }
        return out;
    }

    @Nested
    @DisplayName("component formats")
    class Formats {
        static Stream<int[]> cases() {
            var cases = new ArrayList<int[]>();
            for (var depth : new int[]{1, 2, 7, 8, 12, 16, 17, 20, 24, 31, 32, 33, 47, 48, 63, 64, 65, 100, 127, 128,
                    129, 200, 255, 256}) {
                cases.add(new int[]{UNSIGNED, depth});
                cases.add(new int[]{SIGNED, depth});
            }
            for (var depth : new int[]{16, 32, 64, 128, 256}) {
                cases.add(new int[]{FLOAT, depth});
            }
            for (var depth : new int[]{32, 64, 128, 256}) {
                cases.add(new int[]{COMPLEX, depth});
            }
            return cases.stream();
        }

        @TestFactory
        @DisplayName("every format and depth, packed, byte-aligned and aligned in either byte order")
        Stream<DynamicTest> formats() {
            return cases().flatMap(c -> Stream.of(0, 1, 2).map(packing -> {
                var format = c[0];
                var depth = c[1];
                var name = switch (format) {
                    case UNSIGNED -> "unsigned";
                    case SIGNED -> "signed";
                    case FLOAT -> "float";
                    default -> "complex";
                } + " " + depth + " bits, " + switch (packing) {
                    case 0 -> "packed after a 3-bit value";
                    case 1 -> "big-endian";
                    default -> "aligned little-endian";
                };
                return DynamicTest.dynamicTest(name, () -> {
                    var random = new Random(31L * depth + 7L * format + packing);
                    var align = packing == 2 ? (depth + 7) / 8 + 1 : 0;
                    var layout = Layout.of(0, 1).littleEndian(packing == 2);
                    var components = new ArrayList<Component>();
                    if (packing == 0) {
                        components.add(Component.of(A, 3));
                    }
                    components.add(new Component(MONO, depth, format, align));
                    var image = new Image(7, 5, components, layout, fill(random, 7, 5, layout, components));
                    assertPlanes(name, image, decode(image, UnciWriter.definition(components)));
                });
            }));
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {17, 18, 23, 24, 25, 31, 32, 33, 40, 47, 48, 49, 63, 64, 65, 96, 128, 160, 255, 256})
        @DisplayName("integers above 16 bits rescale exactly to 16 bits, near every rounding boundary")
        void rescale(int depth) {
            var random = new Random(depth);
            var count = 64 * 64;
            var values = new BigInteger[count];
            var divisor = BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.ONE);
            for (var i = 0; i < count; i++) {
                if (i % 2 == 0) {
                    values[i] = new BigInteger(depth, random);
                } else {
                    // the values next to a rounding boundary (k + 1/2) * (2^n - 1) / 65535
                    var k = BigInteger.valueOf(random.nextInt(65535));
                    var boundary = k.shiftLeft(1).add(BigInteger.ONE).multiply(divisor)
                            .divide(BigInteger.valueOf(2 * 65535L));
                    values[i] = boundary.add(BigInteger.valueOf(random.nextInt(5) - 2)).max(BigInteger.ZERO)
                            .min(divisor);
                }
            }
            var layout = Layout.of(0, 0);
            var components = List.of(Component.of(MONO, depth));
            var planes = UnciWriter.planes();
            planes.put(MONO, values);
            var image = new Image(64, 64, components, layout, planes);
            assertPlanes(depth + " bits", image, decode(image, UnciWriter.definition(components)));
        }

        @Test
        @DisplayName("signed integers are offset by half their range")
        void signedOffset() {
            var layout = Layout.of(0, 0);
            var components = List.of(new Component(MONO, 8, SIGNED, 0));
            var planes = UnciWriter.planes();
            // -128, -1, 0, 127 in two's complement
            planes.put(MONO, new BigInteger[]{BigInteger.valueOf(0x80), BigInteger.valueOf(0xFF), BigInteger.ZERO,
                    BigInteger.valueOf(0x7F)});
            var decoded = decode(new Image(4, 1, components, layout, planes), UnciWriter.definition(components));
            assertArrayEquals(new int[]{0, 127, 128, 255}, unsigned(decoded.plane(MONO).samples()));
        }

        @Test
        @DisplayName("floating-point values map 0 to 1 onto 0 to 65535, clamped, NaN becoming 0")
        void floatRange() {
            var layout = Layout.of(0, 0);
            var components = List.of(new Component(MONO, 32, FLOAT, 0));
            var values = new float[]{0f, 1f, 0.5f, -3f, 7f, Float.NaN, Float.POSITIVE_INFINITY, 1f / 65535 * 100};
            var planes = UnciWriter.planes();
            planes.put(MONO, Arrays.stream(new int[values.length]).mapToObj(i -> BigInteger.ZERO)
                    .toArray(BigInteger[]::new));
            for (var i = 0; i < values.length; i++) {
                planes.get(MONO)[i] = BigInteger.valueOf(Float.floatToRawIntBits(values[i]) & 0xFFFFFFFFL);
            }
            var decoded = decode(new Image(values.length, 1, components, layout, planes),
                    UnciWriter.definition(components));
            assertArrayEquals(new int[]{0, 65535, 32768, 0, 65535, 0, 65535, 100},
                    unsigned(decoded.plane(MONO).samples()));
            assertEquals(16, decoded.plane(MONO).bitDepth());
        }

        @Test
        @DisplayName("complex values contribute their magnitude")
        void complexMagnitude() {
            var layout = Layout.of(0, 0);
            var components = List.of(new Component(MONO, 64, COMPLEX, 0));
            var planes = UnciWriter.planes();
            var real = Float.floatToRawIntBits(0.3f) & 0xFFFFFFFFL;
            var imaginary = Float.floatToRawIntBits(-0.4f) & 0xFFFFFFFFL;
            planes.put(MONO, new BigInteger[]{BigInteger.valueOf(real).shiftLeft(32).or(BigInteger.valueOf(imaginary))});
            var decoded = decode(new Image(1, 1, components, layout, planes), UnciWriter.definition(components));
            assertEquals(UnciReference.quantize(StrictMath.hypot(0.3f, -0.4f)), decoded.plane(MONO).samples()[0] & 0xFFFF);
        }
    }

    @Nested
    @DisplayName("layouts")
    class Layouts {
        /** One random layout of an interleaving and sampling. */
        private static Image randomImage(Random random, int interleave, int sampling) {
            var layout = Layout.of(sampling, interleave);
            var sx = layout.sx();
            var sy = layout.sy();
            var tiled = random.nextInt(3) == 0;
            var columns = tiled ? 2 : 1;
            var rows = tiled ? 2 : 1;
            var width = tiled ? 2 * sx * (1 + random.nextInt(4)) : 1 + random.nextInt(13);
            var height = tiled ? 2 * sy * (1 + random.nextInt(4)) : 1 + random.nextInt(9);
            var components = new ArrayList<Component>();
            var depths = new int[]{1, 3, 5, 8, 10, 12, 16, 24, 32};
            var formatPick = random.nextInt(4);
            var format = formatPick == 0 ? SIGNED : formatPick == 1 && interleave != 5 ? FLOAT : UNSIGNED;
            var depth = format == FLOAT ? (random.nextBoolean() ? 16 : 32) : depths[random.nextInt(depths.length)];
            if (interleave == 5) {
                for (var i = 0; i < sx * sy; i++) {
                    components.add(new Component(Y, depth, format, 0));
                }
                components.add(new Component(CB, depth, format, 0));
                components.add(new Component(CR, depth, format, 0));
                Collections.shuffle(components, random);
            } else if (sampling != 0 || random.nextBoolean()) {
                components.add(new Component(Y, depth, format, 0));
                components.add(new Component(CB, depths[random.nextInt(depths.length)], UNSIGNED, 0));
                components.add(new Component(CR, depths[random.nextInt(depths.length)], UNSIGNED, 0));
                if (interleave != 2 || random.nextBoolean()) {
                    components.add(Component.of(A, depths[random.nextInt(depths.length)]));
                }
                if (interleave != 2) {
                    Collections.shuffle(components, random);
                }
            } else {
                components.add(new Component(R, depth, format, 0));
                components.add(Component.of(G, depths[random.nextInt(depths.length)]));
                components.add(Component.of(B, depths[random.nextInt(depths.length)]));
                components.add(Component.of(PADDED, 1 + random.nextInt(8)));
                Collections.shuffle(components, random);
            }
            var maxDepth = components.stream().mapToInt(Component::depth).max().orElseThrow();
            var blockSize = switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> (maxDepth + 7) / 8;
                case 2 -> Math.max(4, (maxDepth + 7) / 8 + random.nextInt(3));
                default -> 9 + random.nextInt(8);
            };
            if (blockSize != 0) {
                layout = layout.blocks(blockSize, random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
            } else if (random.nextBoolean()) {
                var aligned = new ArrayList<Component>();
                for (var component : components) {
                    aligned.add(random.nextBoolean() ? component : new Component(component.type(), component.depth(),
                            component.format(), (component.depth() + 7) / 8 + random.nextInt(2)));
                }
                components = aligned;
            }
            layout = layout.littleEndian(random.nextBoolean());
            if ((interleave == 1 || interleave == 5) && random.nextBoolean()) {
                var bits = components.stream().mapToInt(c -> c.align() != 0 ? 8 * c.align() : c.depth()).sum();
                var bytes = blockSize != 0 ? components.size() * blockSize : (bits + 7) / 8 + components.size();
                layout = layout.pixelSize(bytes + random.nextInt(3));
            }
            if (random.nextBoolean()) {
                layout = layout.rowAlign(1 + random.nextInt(16));
            }
            if (tiled) {
                layout = layout.tiles(columns, rows, random.nextBoolean() ? 0 : 1 + random.nextInt(32));
            }
            return new Image(width, height, components, layout, fill(random, width, height, layout, components));
        }

        @TestFactory
        @DisplayName("every interleaving and sampling with random depths, formats, blocks, alignment and padding")
        Stream<DynamicTest> random() {
            var cases = new ArrayList<DynamicTest>();
            int[][] combinations = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {1, 0}, {2, 1}, {2, 2}, {2, 3}, {3, 0}, {3, 1},
                    {3, 2}, {3, 3}, {4, 0}, {4, 1}, {4, 2}, {5, 1}, {5, 2}, {5, 3}};
            for (var combination : combinations) {
                for (var seed = 0; seed < 24; seed++) {
                    var interleave = combination[0];
                    var sampling = combination[1];
                    var finalSeed = seed;
                    cases.add(DynamicTest.dynamicTest("interleave " + interleave + ", sampling " + sampling + ", seed "
                            + seed, () -> {
                        var image = randomImage(new Random(1000L * interleave + 100L * sampling + finalSeed), interleave,
                                sampling);
                        var what = image.layout() + " " + image.components() + " " + image.width() + "x"
                                + image.height();
                        assertPlanes(what, image, decode(image, UnciWriter.definition(image.components())));
                    }));
                }
            }
            return cases.stream();
        }

        @Test
        @DisplayName("row interleaving of 4:2:0 gives chroma rows with the even luma rows")
        void rows420() {
            // 4x3 8-bit Y'CbCr 4:2:0: Y row 0, Cb row 0, Cr row 0, Y row 1, Y row 2, Cb row 1, Cr row 1
            var data = new byte[]{1, 2, 3, 4, 50, 51, 60, 61, 5, 6, 7, 8, 9, 10, 11, 12, 52, 53, 62, 63};
            var components = List.of(Component.of(Y, 8), Component.of(CB, 8), Component.of(CR, 8));
            var image = new Image(4, 3, components, Layout.of(2, 3), UnciWriter.planes());
            var decoded = decodeData(image, data, UnciWriter.definition(components));
            assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, unsigned(decoded.plane(Y).samples()));
            assertArrayEquals(new int[]{50, 51, 52, 53}, unsigned(decoded.plane(CB).samples()));
            assertArrayEquals(new int[]{60, 61, 62, 63}, unsigned(decoded.plane(CR).samples()));
        }

        @Test
        @DisplayName("multi-Y interleaving of 4:2:0 gives the 2x2 luma block of each chroma sample")
        void multiY420() {
            // 3x3 8-bit: units Y00 Y01 Y10 Y11 Cb Cr; the luma outside the image is padding
            var data = new byte[]{1, 2, 4, 5, 50, 60, 3, 99, 6, 99, 51, 61, 7, 8, 99, 99, 52, 62, 9, 99, 99, 99, 53, 63};
            var components = List.of(Component.of(Y, 8), Component.of(Y, 8), Component.of(Y, 8), Component.of(Y, 8),
                    Component.of(CB, 8), Component.of(CR, 8));
            var image = new Image(3, 3, components, Layout.of(2, 5), UnciWriter.planes());
            var decoded = decodeData(image, data, UnciWriter.definition(components));
            assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, unsigned(decoded.plane(Y).samples()));
            assertArrayEquals(new int[]{50, 51, 52, 53}, unsigned(decoded.plane(CB).samples()));
            assertArrayEquals(new int[]{60, 61, 62, 63}, unsigned(decoded.plane(CR).samples()));
            var picture = assertInstanceOf(YuvPicture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
        }

        @ParameterizedTest(name = "{0}-byte blocks")
        @ValueSource(ints = {9, 12, 16, 24, 32, 255})
        @DisplayName("blocks above 8 bytes hold several values, wide values included, in every flag combination")
        void wideBlocks(int size) {
            for (var flags = 0; flags < 8; flags++) {
                var random = new Random(size * 8L + flags);
                var layout = Layout.of(0, 1).blocks(size, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
                var wide = Math.min(8 * size - 20, 128);
                var components = List.of(Component.of(R, 10), new Component(G, wide, SIGNED, 0), Component.of(B, 10));
                var image = new Image(5, 3, components, layout, fill(random, 5, 3, layout, components));
                assertPlanes("flags " + flags, image, decode(image, UnciWriter.definition(components)));
            }
        }
    }

    @Nested
    @DisplayName("palettes")
    class Palettes {
        private static BigInteger[][] entries(Random random, int count, List<PaletteComponent> components) {
            var entries = new BigInteger[count][components.size()];
            for (var e = 0; e < count; e++) {
                for (var j = 0; j < components.size(); j++) {
                    var component = components.get(j);
                    entries[e][j] = UnciReference.random(random, component.format(), component.depth());
                }
            }
            return entries;
        }

        @ParameterizedTest(name = "{0}-bit indexes")
        @ValueSource(ints = {1, 2, 4, 8, 12, 16})
        @DisplayName("palette indexes are replaced by their entries, converted like components")
        void palette(int indexBits) {
            var random = new Random(indexBits);
            var count = Math.min(1 << indexBits, 300);
            var paletteComponents = List.of(new PaletteComponent(R, 8, UNSIGNED), new PaletteComponent(G, 12, UNSIGNED),
                    new PaletteComponent(B, 32, FLOAT), new PaletteComponent(A, 20, SIGNED));
            var entries = entries(random, count, paletteComponents);
            var components = List.of(Component.of(PALETTE, indexBits));
            var definition = UnciWriter.definition(components, R, G, B, A);
            var width = 9;
            var height = 7;
            var indexes = new BigInteger[width * height];
            for (var i = 0; i < indexes.length; i++) {
                indexes[i] = BigInteger.valueOf(random.nextInt(count));
            }
            var planes = UnciWriter.planes();
            planes.put(PALETTE, indexes);
            var image = new Image(width, height, components, Layout.of(0, 0), planes);
            var decoded = decode(image, definition, UnciWriter.cpal(paletteComponents, definition, entries));
            for (var j = 0; j < paletteComponents.size(); j++) {
                var component = paletteComponents.get(j);
                var plane = decoded.plane(component.type());
                var expected = new int[indexes.length];
                for (var i = 0; i < expected.length; i++) {
                    expected[i] = UnciReference.sample(component.format(), component.depth(),
                            entries[indexes[i].intValue()][j]);
                }
                assertEquals(UnciReference.outputDepth(component.format(), component.depth()), plane.bitDepth());
                assertArrayEquals(expected, unsigned(plane.samples()), "palette component " + j);
            }
            assertEquals(4, decoded.planes().size());
        }

        @Test
        @DisplayName("a palette of Y'CbCr entries makes a full-resolution 4:4:4 picture")
        void ycbcrPalette() {
            var paletteComponents = List.of(new PaletteComponent(Y, 8, UNSIGNED), new PaletteComponent(CB, 8, UNSIGNED),
                    new PaletteComponent(CR, 8, UNSIGNED));
            var entries = new BigInteger[][]{{BigInteger.valueOf(16), BigInteger.valueOf(128), BigInteger.valueOf(128)},
                    {BigInteger.valueOf(235), BigInteger.valueOf(90), BigInteger.valueOf(200)}};
            var components = List.of(Component.of(PALETTE, 1), Component.of(A, 8));
            var definition = UnciWriter.definition(components, Y, CB, CR);
            var planes = UnciWriter.planes();
            planes.put(PALETTE, new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE});
            planes.put(A, new BigInteger[]{BigInteger.valueOf(255), BigInteger.ZERO, BigInteger.valueOf(7)});
            var image = new Image(3, 1, components, Layout.of(0, 1), planes);
            var decoded = decode(image, definition, UnciWriter.cpal(paletteComponents, definition, entries));
            var picture = assertInstanceOf(YuvPicture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            assertArrayEquals(new byte[]{16, (byte) 235, (byte) 235}, picture.y());
            assertArrayEquals(new byte[]{(byte) 128, 90, 90}, picture.u());
            assertArrayEquals(new byte[]{(byte) 255, 0, 7}, picture.alpha());
        }

        @Test
        @DisplayName("an index past the palette and a palette item without cpal are malformed")
        void invalid() {
            var paletteComponents = List.of(new PaletteComponent(MONO, 8, UNSIGNED));
            var entries = new BigInteger[][]{{BigInteger.ONE}, {BigInteger.TWO}};
            var components = List.of(Component.of(PALETTE, 4));
            var definition = UnciWriter.definition(components, MONO);
            var planes = UnciWriter.planes();
            planes.put(PALETTE, new BigInteger[]{BigInteger.ONE, BigInteger.TWO});
            var image = new Image(2, 1, components, Layout.of(0, 0), planes);
            assertThrows(MediaException.Malformed.class, () -> decode(image, definition,
                    UnciWriter.cpal(paletteComponents, definition, entries)));
            assertThrows(MediaException.Malformed.class, () -> decode(image, definition));
        }
    }

    @Nested
    @DisplayName("filter arrays")
    class FilterArrays {
        @Test
        @DisplayName("a Bayer mosaic decodes to a linear monochrome picture of its samples")
        void bayer() {
            var random = new Random(11);
            var components = List.of(new Component(FILTER_ARRAY, 12, UNSIGNED, 2));
            var definition = UnciWriter.definition(components, R, G, B);
            var layout = Layout.of(0, 0).littleEndian(true);
            var image = new Image(6, 4, components, layout, fill(random, 6, 4, layout, components));
            var pattern = UnciWriter.cpat(2, 2, new int[]{R, G, G, B}, new float[]{1.5f, 1f, 1f, 2f}, definition);
            var decoded = decode(image, definition, pattern);
            assertPlanes("bayer", image, decoded);
            var picture = assertInstanceOf(Yuv16Picture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            assertEquals(12, picture.bitDepth());
            assertEquals(TransferCharacteristics.LINEAR, picture.color().transfer());
            assertEquals(ColorRange.FULL, picture.color().range());
            for (var i = 0; i < 24; i++) {
                assertEquals(image.planes().get(FILTER_ARRAY)[i].intValue(), picture.y()[i]);
            }
        }

        @Test
        @DisplayName("a filter array without cpat, or with a pattern naming no component, is malformed")
        void invalid() {
            var components = List.of(Component.of(FILTER_ARRAY, 8));
            var definition = UnciWriter.definition(components, R, G, B);
            var layout = Layout.of(0, 0);
            var image = new Image(2, 2, components, layout, fill(new Random(1), 2, 2, layout, components));
            assertThrows(MediaException.Malformed.class, () -> decode(image, definition));
            var pattern = UnciWriter.cpat(1, 1, new int[]{R}, new float[]{1}, definition);
            pattern[pattern.length - 5] = 9;
            assertThrows(MediaException.Malformed.class, () -> decode(image, definition, pattern));
            // a 65535x65535 pattern overflows an int count of its positions
            var huge = UnciWriter.cpat(1, 1, new int[]{R}, new float[]{1}, definition);
            Arrays.fill(huge, 12, 16, (byte) 0xFF);
            assertThrows(MediaException.Malformed.class, () -> decode(image, definition, huge));
        }
    }

    @Nested
    @DisplayName("CMYK")
    class Cmyk {
        private static int ink(int ink, int black, int max) {
            return (int) (((long) (max - ink) * (max - black) + max / 2) / max);
        }

        @Test
        @DisplayName("8-bit CMYK becomes RGB without color management")
        void cmyk8() {
            var random = new Random(5);
            var components = List.of(Component.of(CYAN, 8), Component.of(MAGENTA, 8), Component.of(YELLOW, 8),
                    Component.of(BLACK, 8));
            var layout = Layout.of(0, 1);
            var image = new Image(5, 4, components, layout, fill(random, 5, 4, layout, components));
            var decoded = decode(image, UnciWriter.definition(components));
            assertPlanes("cmyk", image, decoded);
            var picture = assertInstanceOf(YuvPicture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            for (var i = 0; i < 20; i++) {
                var k = image.planes().get(BLACK)[i].intValue();
                assertEquals(ink(image.planes().get(CYAN)[i].intValue(), k, 255), picture.v()[i] & 0xFF, "red " + i);
                assertEquals(ink(image.planes().get(MAGENTA)[i].intValue(), k, 255), picture.y()[i] & 0xFF, "green " + i);
                assertEquals(ink(image.planes().get(YELLOW)[i].intValue(), k, 255), picture.u()[i] & 0xFF, "blue " + i);
            }
        }

        @Test
        @DisplayName("16-bit CMY without black, with alpha, becomes 16-bit RGB with alpha")
        void cmy16() {
            var random = new Random(6);
            var components = List.of(Component.of(CYAN, 16), Component.of(MAGENTA, 16), Component.of(YELLOW, 12),
                    Component.of(A, 16));
            var layout = Layout.of(0, 0);
            var image = new Image(4, 3, components, layout, fill(random, 4, 3, layout, components));
            var decoded = decode(image, UnciWriter.definition(components));
            var picture = assertInstanceOf(Yuv16Picture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(16, picture.bitDepth());
            for (var i = 0; i < 12; i++) {
                var yellow = image.planes().get(YELLOW)[i].intValue();
                var scaled = (int) (((long) yellow * 65535 + 2047) / 4095);
                assertEquals(ink(image.planes().get(CYAN)[i].intValue(), 0, 65535), picture.v()[i] & 0xFFFF);
                assertEquals(ink(scaled, 0, 65535), picture.u()[i] & 0xFFFF);
                assertEquals(image.planes().get(A)[i].intValue(), picture.alpha()[i] & 0xFFFF);
            }
        }

        @Test
        @DisplayName("an ink without the other two is malformed")
        void partial() {
            var components = List.of(Component.of(CYAN, 8), Component.of(BLACK, 8));
            var layout = Layout.of(0, 1);
            var image = new Image(2, 2, components, layout, fill(new Random(2), 2, 2, layout, components));
            var decoded = decode(image, UnciWriter.definition(components));
            assertThrows(MediaException.Malformed.class,
                    () -> HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
        }
    }

    @Nested
    @DisplayName("generic compression")
    class Compression {
        private static byte[] resource(String name) {
            try (var in = HeifUncompressedFormatsTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/internal/compress/brotli/" + name)) {
                assertNotNull(in, name);
                return in.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static byte[] deflate(byte[] data, boolean raw) {
            var deflater = new Deflater(9, raw);
            deflater.setInput(data);
            deflater.finish();
            var out = new java.io.ByteArrayOutputStream();
            var buffer = new byte[4096];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }

        private static byte[] compress(String type, byte[] data) {
            return switch (type) {
                case "defl" -> deflate(data, true);
                case "zlib" -> deflate(data, false);
                default -> UnciWriter.storedBrotli(data);
            };
        }

        @Test
        @DisplayName("brot decompresses streams of the reference encoder, whole and as icef units")
        void brotli() {
            var binary = resource("binary.bin");
            var runs = resource("runs.bin");
            var whole = new byte[binary.length + runs.length];
            System.arraycopy(binary, 0, whole, 0, binary.length);
            System.arraycopy(runs, 0, whole, binary.length, runs.length);
            // 3000 + 13048 bytes of 8-bit gray, 59x272
            var components = List.of(Component.of(MONO, 8));
            var image = new Image(59, 272, components, Layout.of(0, 0), UnciWriter.planes());
            var definition = UnciWriter.definition(components);
            for (var quality : List.of("q0-w10", "q4-w22", "q11-w22")) {
                var first = resource("binary.bin." + quality + ".br");
                var second = resource("runs.bin." + quality + ".br");
                var payload = new byte[first.length + second.length + 5];
                System.arraycopy(first, 0, payload, 3, first.length);
                System.arraycopy(second, 0, payload, 3 + first.length + 2, second.length);
                var units = UnciWriter.icef(3, 2, new long[]{3, 3 + first.length + 2},
                        new long[]{first.length, second.length});
                var decoded = decodeData(image, payload, definition, UnciWriter.cmpC("brot", 0), units);
                assertArrayEquals(unsigned8(whole), unsigned(decoded.plane(MONO).samples()), quality);
            }
            var single = resource("words.txt.q11-w22.br");
            var words = resource("words.txt");
            // 20192 bytes of 8-bit gray, 32x631
            var gray = new Image(32, 631, components, Layout.of(0, 0), UnciWriter.planes());
            var decoded = decodeData(gray, single, definition, UnciWriter.cmpC("brot", 0));
            assertArrayEquals(unsigned8(words), unsigned(decoded.plane(MONO).samples()));
        }

        private static int[] unsigned8(byte[] data) {
            var out = new int[data.length];
            for (var i = 0; i < out.length; i++) {
                out[i] = data[i] & 0xFF;
            }
            return out;
        }

        @TestFactory
        @DisplayName("every compression type, whole and in units of rows, tiles or the image, with every icef code")
        Stream<DynamicTest> units() {
            var cases = new ArrayList<DynamicTest>();
            for (var type : List.of("defl", "zlib", "brot")) {
                for (var unitType = 0; unitType <= 4; unitType++) {
                    for (var codes = 0; codes < 3; codes++) {
                        var finalUnitType = unitType;
                        var finalCodes = codes;
                        cases.add(DynamicTest.dynamicTest(type + ", unit type " + unitType + ", codes " + codes, () -> {
                            var random = new Random(type.hashCode() + 10L * finalUnitType + finalCodes);
                            var components = List.of(Component.of(R, 8), Component.of(G, 10), Component.of(B, 8));
                            var layout = Layout.of(0, 1).tiles(2, 2, 0);
                            var image = new Image(8, 6, components, layout, fill(random, 8, 6, layout, components));
                            var data = UnciWriter.data(image);
                            // units of 1, 17 and the remaining bytes stand for rows, tiles or pixels: a decoder joins
                            // them in their order whatever they hold
                            var cuts = finalUnitType == 0 ? new int[]{0, data.length}
                                    : new int[]{0, 1, 18, data.length / 2, data.length};
                            var payload = new java.io.ByteArrayOutputStream();
                            var offsets = new long[cuts.length - 1];
                            var sizes = new long[cuts.length - 1];
                            for (var u = 0; u < offsets.length; u++) {
                                var unit = compress(type, Arrays.copyOfRange(data, cuts[u], cuts[u + 1]));
                                if (finalCodes != 0) {
                                    payload.write(0x5A);
                                }
                                offsets[u] = payload.size();
                                sizes[u] = unit.length;
                                payload.writeBytes(unit);
                            }
                            var offsetCode = finalCodes == 0 ? 0 : finalCodes == 1 ? 1 : 4;
                            var sizeCode = finalCodes == 0 ? 1 : finalCodes == 1 ? 2 : 4;
                            var decoded = decodeData(image, payload.toByteArray(), UnciWriter.definition(components),
                                    UnciWriter.cmpC(type, finalUnitType), UnciWriter.icef(offsetCode, sizeCode, offsets,
                                            sizes));
                            assertPlanes(type, image, decoded);
                            if (finalUnitType <= 1) {
                                var whole = decodeData(image, compress(type, data), UnciWriter.definition(components),
                                        UnciWriter.cmpC(type, finalUnitType));
                                assertPlanes(type + " whole", image, whole);
                            }
                        }));
                    }
                }
            }
            return cases.stream();
        }

        @Test
        @DisplayName("units outside the data, tile units without icef and reserved codes are malformed")
        void invalid() {
            var components = List.of(Component.of(MONO, 8));
            var image = new Image(4, 4, components, Layout.of(0, 0), fill(new Random(3), 4, 4, Layout.of(0, 0),
                    components));
            var definition = UnciWriter.definition(components);
            var stream = deflate(UnciWriter.data(image), false);
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, stream, definition,
                    UnciWriter.cmpC("zlib", 0), UnciWriter.icef(1, 1, new long[]{4}, new long[]{stream.length})));
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, stream, definition,
                    UnciWriter.cmpC("zlib", 2)));
            var reserved = UnciWriter.icef(1, 1, new long[]{0}, new long[]{stream.length});
            reserved[12] = (byte) (5 << 5);
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, stream, definition,
                    UnciWriter.cmpC("zlib", 0), reserved));
            assertThrows(MediaException.Unsupported.class, () -> decodeData(image, stream, definition,
                    UnciWriter.cmpC("zstd", 0)));
            var corrupt = UnciWriter.storedBrotli(UnciWriter.data(image));
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, Arrays.copyOf(corrupt, 9), definition,
                    UnciWriter.cmpC("brot", 0)));
        }

        @Test
        @DisplayName("data that inflates past the byte limit fails with LimitExceeded")
        void bombs() {
            var limits = MediaLimits.DEFAULT.toBuilder().maxBytes(100_000).build();
            var zeros = new byte[1 << 20];
            var components = List.of(Component.of(MONO, 8));
            var image = new Image(16, 16, components, Layout.of(0, 0), UnciWriter.planes());
            var definition = UnciWriter.definition(components);
            for (var type : List.of("defl", "zlib", "brot")) {
                var properties = new byte[][]{UnciWriter.uncC(components, image.layout(), definition),
                        UnciWriter.cmpd(definition), UnciWriter.cmpC(type, 0)};
                var file = UnciWriter.file(16, 16, type.equals("brot") ? resource("runs.bin.q11-w22.br")
                        : compress(type, zeros), properties);
                var tight = type.equals("brot") ? MediaLimits.DEFAULT.toBuilder().maxBytes(10_000).build() : limits;
                assertThrows(MediaException.LimitExceeded.class, () -> UnciReference.decode(file, tight), type);
            }
        }
    }

    @Nested
    @DisplayName("invalid components")
    class Invalid {
        @Test
        @DisplayName("floating-point and complex depths IEEE 754 lacks are malformed, reserved formats unsupported")
        void formats() {
            for (var component : List.of(new Component(MONO, 24, FLOAT, 0), new Component(MONO, 16, COMPLEX, 0),
                    new Component(MONO, 8, 4, 0), new Component(MONO, 8, 255, 0))) {
                var components = List.of(component);
                var image = new Image(1, 1, components, Layout.of(0, 0), UnciWriter.planes());
                var data = new byte[32];
                var expected = component.format() > 3 ? MediaException.Unsupported.class : MediaException.Malformed.class;
                assertThrows(expected, () -> decodeData(image, data, UnciWriter.definition(components)),
                        component.toString());
            }
        }

        @Test
        @DisplayName("a palette index of a signed or floating-point format is malformed")
        void paletteFormat() {
            var components = List.of(new Component(PALETTE, 8, SIGNED, 0));
            var image = new Image(1, 1, components, Layout.of(0, 0), UnciWriter.planes());
            var definition = UnciWriter.definition(components, MONO);
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, new byte[1], definition,
                    UnciWriter.cpal(List.of(new PaletteComponent(MONO, 8, UNSIGNED)), definition,
                            new BigInteger[][]{{BigInteger.ONE}})));
        }

        @Test
        @DisplayName("multi-Y 4:2:0 units without four luma values are malformed")
        void multiYLuma() {
            var components = List.of(Component.of(Y, 8), Component.of(Y, 8), Component.of(CB, 8), Component.of(CR, 8));
            var image = new Image(2, 2, components, Layout.of(2, 5), UnciWriter.planes());
            assertThrows(MediaException.Malformed.class, () -> decodeData(image, new byte[16],
                    UnciWriter.definition(components)));
        }
    }
}
