package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes uncompressed image items (ISO/IEC 23001-17) of every interleaving, packing and padding:
 * layouts written byte by byte here, and the raw pixel formats ffmpeg writes for the predefined
 * configurations (NV12, YUYV, UYVY, v210, RGB565 and others), compared with ffmpeg's planar
 * conversion of the same bytes.
 */
@DisplayName("uncompressed HEIF items")
class HeifUncompressedTest {
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    private static final int MONO = 0;
    private static final int Y = 1;
    private static final int CB = 2;
    private static final int CR = 3;
    private static final int R = 4;
    private static final int G = 5;
    private static final int B = 6;
    private static final int A = 7;

    /** One uncC component: the cmpd index, the bit depth and the alignment in bytes. */
    record Comp(int index, int depth, int align) {
        static Comp of(int index, int depth) {
            return new Comp(index, depth, 0);
        }
    }

    /** The fields of a version 0 uncC box after its components. */
    record Layout(int sampling, int interleave, int blockSize, boolean componentsLe, boolean padLsb, boolean blockLe,
                  boolean reversed, long pixelSize, long rowAlign, long tileAlign, int columns, int rows) {
        static Layout of(int sampling, int interleave) {
            return new Layout(sampling, interleave, 0, false, false, false, false, 0, 0, 0, 1, 1);
        }

        Layout blocks(int size, boolean padLsb, boolean littleEndian, boolean reversed) {
            return new Layout(sampling, interleave, size, componentsLe, padLsb, littleEndian, reversed, pixelSize, rowAlign,
                    tileAlign, columns, rows);
        }

        Layout littleEndian() {
            return new Layout(sampling, interleave, blockSize, true, padLsb, blockLe, reversed, pixelSize, rowAlign, tileAlign,
                    columns, rows);
        }

        Layout pixelSize(long size) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, size, rowAlign,
                    tileAlign, columns, rows);
        }

        Layout rowAlign(long size) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, pixelSize, size,
                    tileAlign, columns, rows);
        }

        Layout tiles(int columns, int rows, long tileAlign) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, pixelSize, rowAlign,
                    tileAlign, columns, rows);
        }
    }

    static byte[] uncC(Layout layout, Comp... comps) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, comps.length);
        for (var comp : comps) {
            HeifBuilder.u16(out, comp.index());
            out.write(comp.depth() - 1);
            out.write(0);
            out.write(comp.align());
        }
        out.write(layout.sampling());
        out.write(layout.interleave());
        out.write(layout.blockSize());
        out.write((layout.componentsLe() ? 0x80 : 0) | (layout.padLsb() ? 0x40 : 0) | (layout.blockLe() ? 0x20 : 0)
                | (layout.reversed() ? 0x10 : 0));
        HeifBuilder.u32(out, layout.pixelSize());
        HeifBuilder.u32(out, layout.rowAlign());
        HeifBuilder.u32(out, layout.tileAlign());
        HeifBuilder.u32(out, layout.columns() - 1);
        HeifBuilder.u32(out, layout.rows() - 1);
        return HeifBuilder.fullBox("uncC", 0, 0, out.toByteArray());
    }

    static byte[] profile(String fourCc) {
        return HeifBuilder.fullBox("uncC", 1, 0, fourCc.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    static byte[] cmpd(int... types) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, types.length);
        for (var type : types) {
            HeifBuilder.u16(out, type);
        }
        return HeifBuilder.box("cmpd", out.toByteArray());
    }

    static byte[] cmpC(String type) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(0);
        return HeifBuilder.fullBox("cmpC", 0, 0, out.toByteArray());
    }

    static HeifComponents decode(int width, int height, byte[] data, byte[]... properties) {
        var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
        var all = new HeifBuilder.Property[properties.length + 1];
        all[0] = plain(HeifBuilder.ispe(width, height));
        for (var i = 0; i < properties.length; i++) {
            all[i + 1] = plain(properties[i]);
        }
        builder.primary(builder.item("unci", data, all));
        var source = MediaSource.of(builder.build());
        var file = HeifFile.parse(source, LIMITS);
        var item = file.primaryItem();
        return HeifUncompressed.decode("heic", file.readItem(source, item, LIMITS), item, width, height, LIMITS);
    }

    static int[] plane(HeifComponents components, int type) {
        var plane = components.plane(type);
        assertNotNull(plane, "plane of type " + type);
        var out = new int[plane.samples().length];
        for (var i = 0; i < out.length; i++) {
            out[i] = plane.samples()[i] & 0xFFFF;
        }
        return out;
    }

    static int[] random(Random random, int count, int depth) {
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = random.nextInt(1 << depth);
        }
        return out;
    }

    @Nested
    @DisplayName("layouts written byte by byte")
    class Layouts {
        @Test
        @DisplayName("version 1 profiles: rgb3, rgba and abgr")
        void profiles() {
            var random = new Random(1);
            var r = random(random, 15, 8);
            var g = random(random, 15, 8);
            var b = random(random, 15, 8);
            var a = random(random, 15, 8);
            var rgb = new byte[45];
            var rgba = new byte[60];
            var abgr = new byte[60];
            for (var i = 0; i < 15; i++) {
                rgb[3 * i] = (byte) r[i];
                rgb[3 * i + 1] = (byte) g[i];
                rgb[3 * i + 2] = (byte) b[i];
                rgba[4 * i] = (byte) r[i];
                rgba[4 * i + 1] = (byte) g[i];
                rgba[4 * i + 2] = (byte) b[i];
                rgba[4 * i + 3] = (byte) a[i];
                abgr[4 * i] = (byte) a[i];
                abgr[4 * i + 1] = (byte) b[i];
                abgr[4 * i + 2] = (byte) g[i];
                abgr[4 * i + 3] = (byte) r[i];
            }
            var three = decode(5, 3, rgb, profile("rgb3"));
            assertArrayEquals(r, plane(three, R));
            assertArrayEquals(g, plane(three, G));
            assertArrayEquals(b, plane(three, B));
            for (var data : List.of(rgba, abgr)) {
                var four = decode(5, 3, data, profile(data == rgba ? "rgba" : "abgr"));
                assertArrayEquals(r, plane(four, R));
                assertArrayEquals(g, plane(four, G));
                assertArrayEquals(b, plane(four, B));
                assertArrayEquals(a, plane(four, A));
            }
        }

        @Test
        @DisplayName("planar 4:2:0 with padded rows")
        void planar() {
            var random = new Random(2);
            var y = random(random, 7 * 5, 8);
            var cb = random(random, 4 * 3, 8);
            var cr = random(random, 4 * 3, 8);
            var out = new ByteArrayOutputStream();
            writeRows(out, y, 7, 8);
            writeRows(out, cb, 4, 8);
            writeRows(out, cr, 4, 8);
            var decoded = decode(7, 5, out.toByteArray(), uncC(Layout.of(2, 0).rowAlign(8), Comp.of(0, 8), Comp.of(1, 8),
                    Comp.of(2, 8)), cmpd(Y, CB, CR));
            assertArrayEquals(y, plane(decoded, Y));
            assertArrayEquals(cb, plane(decoded, CB));
            assertArrayEquals(cr, plane(decoded, CR));
            assertEquals(2, decoded.plane(CB).subsamplingX());
            assertEquals(2, decoded.plane(CB).subsamplingY());
        }

        @Test
        @DisplayName("row interleaving of 16-bit little-endian components")
        void rows() {
            var random = new Random(3);
            var r = random(random, 12, 16);
            var g = random(random, 12, 16);
            var b = random(random, 12, 16);
            var out = new ByteArrayOutputStream();
            for (var row = 0; row < 3; row++) {
                for (var plane : List.of(r, g, b)) {
                    for (var x = 0; x < 4; x++) {
                        var v = plane[row * 4 + x];
                        out.write(v);
                        out.write(v >> 8);
                    }
                }
            }
            var decoded = decode(4, 3, out.toByteArray(), uncC(Layout.of(0, 3).littleEndian(), Comp.of(0, 16),
                    Comp.of(1, 16), Comp.of(2, 16)), cmpd(R, G, B));
            assertArrayEquals(r, plane(decoded, R));
            assertArrayEquals(g, plane(decoded, G));
            assertArrayEquals(b, plane(decoded, B));
        }

        @Test
        @DisplayName("10-bit values aligned to two bytes, in either byte order")
        void aligned() {
            var random = new Random(4);
            var values = random(random, 6 * 2, 10);
            for (var little : new boolean[]{false, true}) {
                var data = new byte[values.length * 2];
                for (var i = 0; i < values.length; i++) {
                    var hi = (byte) (values[i] >> 8);
                    var lo = (byte) values[i];
                    data[2 * i] = little ? lo : hi;
                    data[2 * i + 1] = little ? hi : lo;
                }
                var layout = little ? Layout.of(0, 0).littleEndian() : Layout.of(0, 0);
                var decoded = decode(6, 2, data, uncC(layout, new Comp(0, 10, 2)), cmpd(MONO));
                assertArrayEquals(values, plane(decoded, MONO));
            }
        }

        @Test
        @DisplayName("values aligned to more than eight bytes, in either byte order")
        void wideAlignment() {
            var random = new Random(14);
            var values = random(random, 4 * 3, 12);
            for (var little : new boolean[]{false, true}) {
                var data = new byte[values.length * 11];
                for (var i = 0; i < values.length; i++) {
                    // the value sits in the two least significant bytes of an 11-byte word; the other bytes are set
                    // to show that they are padding
                    Arrays.fill(data, 11 * i, 11 * i + 11, (byte) 0xF0);
                    var hi = (byte) (values[i] >> 8);
                    var lo = (byte) values[i];
                    data[little ? 11 * i : 11 * i + 10] = lo;
                    data[little ? 11 * i + 1 : 11 * i + 9] = (byte) (hi & 0x0F);
                }
                var layout = little ? Layout.of(0, 0).littleEndian() : Layout.of(0, 0);
                var decoded = decode(4, 3, data, uncC(layout, new Comp(0, 12, 11)), cmpd(MONO));
                assertArrayEquals(values, plane(decoded, MONO), little ? "little-endian" : "big-endian");
            }
        }

        @Test
        @DisplayName("1-bit monochrome packed most significant bit first, each row on a new byte")
        void bilevel() {
            var random = new Random(5);
            var values = random(random, 11 * 3, 1);
            var data = new byte[2 * 3];
            for (var row = 0; row < 3; row++) {
                for (var x = 0; x < 11; x++) {
                    data[row * 2 + x / 8] |= (byte) (values[row * 11 + x] << (7 - x % 8));
                }
            }
            var decoded = decode(11, 3, data, uncC(Layout.of(0, 0), Comp.of(0, 1)), cmpd(MONO));
            assertArrayEquals(values, plane(decoded, MONO));
        }

        @Test
        @DisplayName("12-bit pixels packed bit after bit and padded to their pixel size")
        void pixelSize() {
            var random = new Random(6);
            var y = random(random, 8, 12);
            var a = random(random, 8, 12);
            var data = new byte[8 * 4];
            for (var i = 0; i < 8; i++) {
                var bits = ((long) y[i] << 12) | a[i];
                data[4 * i] = (byte) (bits >> 16);
                data[4 * i + 1] = (byte) (bits >> 8);
                data[4 * i + 2] = (byte) bits;
                data[4 * i + 3] = (byte) 0xEE;
            }
            var decoded = decode(4, 2, data, uncC(Layout.of(0, 1).pixelSize(4), Comp.of(0, 12), Comp.of(1, 12)),
                    cmpd(Y, A));
            assertArrayEquals(y, plane(decoded, Y));
            assertArrayEquals(a, plane(decoded, A));
        }

        @Test
        @DisplayName("blocks in every combination of byte order, padding end and value order")
        void blocks() {
            var random = new Random(7);
            var r = random(random, 6, 10);
            var g = random(random, 6, 10);
            var b = random(random, 6, 10);
            for (var flags = 0; flags < 8; flags++) {
                var padLsb = (flags & 1) != 0;
                var little = (flags & 2) != 0;
                var reversed = (flags & 4) != 0;
                var data = new byte[6 * 4];
                for (var i = 0; i < 6; i++) {
                    long word = reversed ? r[i] | ((long) g[i] << 10) | ((long) b[i] << 20)
                            : ((long) r[i] << 20) | ((long) g[i] << 10) | b[i];
                    if (padLsb) {
                        word <<= 2;
                    }
                    for (var k = 0; k < 4; k++) {
                        data[4 * i + k] = (byte) (little ? word >> (8 * k) : word >> (24 - 8 * k));
                    }
                }
                var decoded = decode(3, 2, data, uncC(Layout.of(0, 1).blocks(4, padLsb, little, reversed),
                        Comp.of(0, 10), Comp.of(1, 10), Comp.of(2, 10)), cmpd(R, G, B));
                assertArrayEquals(r, plane(decoded, R), "flags " + flags);
                assertArrayEquals(g, plane(decoded, G), "flags " + flags);
                assertArrayEquals(b, plane(decoded, B), "flags " + flags);
            }
        }

        @Test
        @DisplayName("component planes of every tile, with and without tile-component interleaving")
        void tiles() {
            var random = new Random(8);
            var width = 8;
            var height = 6;
            var y = random(random, width * height, 8);
            // 4:2:2: the chroma planes are 4x6 and their tiles 2x3
            var cb = random(random, 4 * 6, 8);
            var cr = random(random, 4 * 6, 8);
            for (var interleave : new int[]{0, 4}) {
                var out = new ByteArrayOutputStream();
                var planes = List.of(y, cb, cr);
                if (interleave == 0) {
                    for (var t = 0; t < 4; t++) {
                        var start = out.size();
                        for (var c = 0; c < 3; c++) {
                            writeTile(out, planes.get(c), c == 0 ? width : 4, t, c == 0 ? 4 : 2, 3);
                        }
                        pad(out, start, 16);
                    }
                } else {
                    for (var c = 0; c < 3; c++) {
                        for (var t = 0; t < 4; t++) {
                            var start = out.size();
                            writeTile(out, planes.get(c), c == 0 ? width : 4, t, c == 0 ? 4 : 2, 3);
                            pad(out, start, 16);
                        }
                    }
                }
                var decoded = decode(width, height, out.toByteArray(), uncC(Layout.of(1, interleave).tiles(2, 2, 16),
                        Comp.of(0, 8), Comp.of(1, 8), Comp.of(2, 8)), cmpd(Y, CB, CR));
                assertArrayEquals(y, plane(decoded, Y), "interleave " + interleave);
                assertArrayEquals(cb, plane(decoded, CB), "interleave " + interleave);
                assertArrayEquals(cr, plane(decoded, CR), "interleave " + interleave);
            }
        }

        @Test
        @DisplayName("generic compression of the whole item with deflate and zlib")
        void compressed() {
            var random = new Random(9);
            var values = random(random, 32 * 16, 8);
            var raw = new byte[values.length];
            for (var i = 0; i < raw.length; i++) {
                raw[i] = (byte) values[i];
            }
            for (var type : List.of("defl", "zlib")) {
                var deflater = new Deflater(9, type.equals("defl"));
                deflater.setInput(raw);
                deflater.finish();
                var compressed = new byte[4096];
                var size = deflater.deflate(compressed);
                deflater.end();
                var decoded = decode(32, 16, Arrays.copyOf(compressed, size), uncC(Layout.of(0, 0), Comp.of(0, 8)),
                        cmpd(MONO), cmpC(type));
                assertArrayEquals(values, plane(decoded, MONO), type);
            }
        }

        @Test
        @DisplayName("padding components are skipped")
        void padded() {
            var data = new byte[]{1, 99, 2, 98, 3, 97, 4, 96};
            var decoded = decode(2, 2, data, uncC(Layout.of(0, 1), Comp.of(0, 8), Comp.of(1, 8)), cmpd(MONO, 12));
            assertArrayEquals(new int[]{1, 2, 3, 4}, plane(decoded, MONO));
            assertEquals(1, decoded.planes().size());
        }
    }

    @Nested
    @DisplayName("ffmpeg raw pixel formats")
    class Ffmpeg {
        private static final int WIDTH = 64;
        private static final int HEIGHT = 16;

        private static byte[] source(String pixelFormat, List<String> codec) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var arguments = new java.util.ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=1", "-frames:v", "1", "-pix_fmt", pixelFormat));
            arguments.addAll(codec);
            arguments.addAll(List.of("-f", "rawvideo", "-"));
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
        }

        private static byte[] convert(byte[] raw, String from, String to) {
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), raw, List.of("-v", "error", "-f", "rawvideo",
                    "-pix_fmt", from, "-s", WIDTH + "x" + HEIGHT, "-i", "pipe:0", "-f", "rawvideo", "-pix_fmt", to, "-"));
        }

        private static int[] bytes(byte[] raw, int offset, int count) {
            var out = new int[count];
            for (var i = 0; i < count; i++) {
                out[i] = raw[offset + i] & 0xFF;
            }
            return out;
        }

        private static int[] shortsLe(byte[] raw, int offset, int count) {
            var out = new int[count];
            for (var i = 0; i < count; i++) {
                out[i] = (raw[offset + 2 * i] & 0xFF) | ((raw[offset + 2 * i + 1] & 0xFF) << 8);
            }
            return out;
        }

        private static void assertPlanar(HeifComponents decoded, byte[] planar, int chromaWidth, int chromaHeight) {
            var luma = WIDTH * HEIGHT;
            var chroma = chromaWidth * chromaHeight;
            assertArrayEquals(bytes(planar, 0, luma), plane(decoded, Y), "Y");
            assertArrayEquals(bytes(planar, luma, chroma), plane(decoded, CB), "Cb");
            assertArrayEquals(bytes(planar, luma + chroma, chroma), plane(decoded, CR), "Cr");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"nv12", "nv21"})
        @DisplayName("NV12 and NV21 as mixed interleaving")
        void semiPlanar(String format) {
            var raw = source(format, List.of());
            var crFirst = format.equals("nv21");
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(2, 2), Comp.of(0, 8),
                    Comp.of(crFirst ? 2 : 1, 8), Comp.of(crFirst ? 1 : 2, 8)), cmpd(Y, CB, CR));
            assertPlanar(decoded, convert(raw, format, "yuv420p"), WIDTH / 2, HEIGHT / 2);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuyv422", "uyvy422", "yvyu422"})
        @DisplayName("YUYV, UYVY and YVYU as multi-Y interleaving")
        void packed422(String format) {
            var raw = source(format, List.of());
            var order = switch (format) {
                case "yuyv422" -> new int[]{Y, CB, Y, CR};
                case "uyvy422" -> new int[]{CB, Y, CR, Y};
                default -> new int[]{Y, CR, Y, CB};
            };
            var comps = new Comp[4];
            for (var i = 0; i < 4; i++) {
                comps[i] = Comp.of(order[i] - 1, 8);
            }
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(1, 5), comps), cmpd(Y, CB, CR));
            assertPlanar(decoded, convert(raw, format, "yuv422p"), WIDTH / 2, HEIGHT);
        }

        @Test
        @DisplayName("UYYVYY 4:1:1 as multi-Y interleaving")
        void packed411() {
            // swscale neither reads nor writes UYYVYY, so ffmpeg's 4:1:1 planes are packed here (U Y Y V Y Y)
            var planar = source("yuv411p", List.of());
            var luma = WIDTH * HEIGHT;
            var chroma = WIDTH / 4 * HEIGHT;
            var raw = new byte[luma + 2 * chroma];
            for (var unit = 0; unit < chroma; unit++) {
                var y = unit * 4;
                raw[6 * unit] = planar[luma + unit];
                raw[6 * unit + 1] = planar[y];
                raw[6 * unit + 2] = planar[y + 1];
                raw[6 * unit + 3] = planar[luma + chroma + unit];
                raw[6 * unit + 4] = planar[y + 2];
                raw[6 * unit + 5] = planar[y + 3];
            }
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(3, 5), Comp.of(1, 8), Comp.of(0, 8), Comp.of(0, 8),
                    Comp.of(2, 8), Comp.of(0, 8), Comp.of(0, 8)), cmpd(Y, CB, CR));
            assertPlanar(decoded, planar, WIDTH / 4, HEIGHT);
        }

        @Test
        @DisplayName("v210: 10-bit 4:2:2 in reversed little-endian blocks, rows padded to 128 bytes")
        void v210() {
            var raw = source("yuv422p10le", List.of("-c:v", "v210"));
            var planar = source("yuv422p10le", List.of());
            var layout = Layout.of(1, 5).blocks(4, false, true, true).rowAlign(128);
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(layout, Comp.of(1, 10), Comp.of(0, 10), Comp.of(2, 10),
                    Comp.of(0, 10)), cmpd(Y, CB, CR));
            var luma = WIDTH * HEIGHT;
            var chroma = WIDTH / 2 * HEIGHT;
            assertArrayEquals(shortsLe(planar, 0, luma), plane(decoded, Y));
            assertArrayEquals(shortsLe(planar, 2 * luma, chroma), plane(decoded, CB));
            assertArrayEquals(shortsLe(planar, 2 * (luma + chroma), chroma), plane(decoded, CR));
        }

        @Test
        @DisplayName("Y210 and P010: 10-bit values at the top of little-endian two-byte blocks")
        void msbAligned() {
            var block = Layout.of(1, 5).blocks(2, true, true, false);
            var y210 = source("y210le", List.of());
            var decoded = decode(WIDTH, HEIGHT, y210, uncC(block, Comp.of(0, 10), Comp.of(1, 10), Comp.of(0, 10),
                    Comp.of(2, 10)), cmpd(Y, CB, CR));
            var planar = convert(y210, "y210le", "yuv422p10le");
            var luma = WIDTH * HEIGHT;
            var chroma = WIDTH / 2 * HEIGHT;
            assertArrayEquals(shortsLe(planar, 0, luma), plane(decoded, Y), "Y210 Y");
            assertArrayEquals(shortsLe(planar, 2 * luma, chroma), plane(decoded, CB), "Y210 Cb");
            assertArrayEquals(shortsLe(planar, 2 * (luma + chroma), chroma), plane(decoded, CR), "Y210 Cr");
            var p010 = source("p010le", List.of());
            decoded = decode(WIDTH, HEIGHT, p010, uncC(Layout.of(2, 2).blocks(2, true, true, false), Comp.of(0, 10),
                    Comp.of(1, 10), Comp.of(2, 10)), cmpd(Y, CB, CR));
            planar = convert(p010, "p010le", "yuv420p10le");
            chroma = WIDTH / 2 * HEIGHT / 2;
            assertArrayEquals(shortsLe(planar, 0, luma), plane(decoded, Y), "P010 Y");
            assertArrayEquals(shortsLe(planar, 2 * luma, chroma), plane(decoded, CB), "P010 Cb");
            assertArrayEquals(shortsLe(planar, 2 * (luma + chroma), chroma), plane(decoded, CR), "P010 Cr");
        }

        @Test
        @DisplayName("XV30: 10-bit 4:4:4 pixels in reversed little-endian four-byte blocks padded at the top")
        void xv30() {
            var raw = source("xv30le", List.of());
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(0, 1).blocks(4, false, true, true), Comp.of(1, 10),
                    Comp.of(0, 10), Comp.of(2, 10)), cmpd(Y, CB, CR));
            var planar = convert(raw, "xv30le", "yuv444p10le");
            var luma = WIDTH * HEIGHT;
            assertArrayEquals(shortsLe(planar, 0, luma), plane(decoded, Y), "Y");
            assertArrayEquals(shortsLe(planar, 2 * luma, luma), plane(decoded, CB), "Cb");
            assertArrayEquals(shortsLe(planar, 4 * luma, luma), plane(decoded, CR), "Cr");
        }

        @Test
        @DisplayName("RGB565 big-endian as pixel interleaving packed bit after bit")
        void rgb565() {
            var raw = source("rgb565be", List.of());
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(0, 1), Comp.of(0, 5), Comp.of(1, 6), Comp.of(2, 5)),
                    cmpd(R, G, B));
            var rgb = convert(raw, "rgb565be", "rgb24");
            var r = new int[WIDTH * HEIGHT];
            var g = new int[WIDTH * HEIGHT];
            var b = new int[WIDTH * HEIGHT];
            for (var i = 0; i < r.length; i++) {
                r[i] = (rgb[3 * i] & 0xFF) >> 3;
                g[i] = (rgb[3 * i + 1] & 0xFF) >> 2;
                b[i] = (rgb[3 * i + 2] & 0xFF) >> 3;
            }
            assertArrayEquals(r, plane(decoded, R));
            assertArrayEquals(g, plane(decoded, G));
            assertArrayEquals(b, plane(decoded, B));
        }

        @Test
        @DisplayName("10-bit gray in little-endian two-byte words and 16-bit big-endian gray")
        void gray() {
            var ten = source("gray10le", List.of());
            var decoded = decode(WIDTH, HEIGHT, ten, uncC(Layout.of(0, 0).littleEndian(), new Comp(0, 10, 2)),
                    cmpd(MONO));
            assertArrayEquals(shortsLe(ten, 0, WIDTH * HEIGHT), plane(decoded, MONO));
            var sixteen = source("gray16be", List.of());
            var big = decode(WIDTH, HEIGHT, sixteen, uncC(Layout.of(0, 0), Comp.of(0, 16)), cmpd(MONO));
            assertArrayEquals(shortsLe(convert(sixteen, "gray16be", "gray16le"), 0, WIDTH * HEIGHT), plane(big, MONO));
        }

        @Test
        @DisplayName("I420 as component interleaving, assembled into a 4:2:0 picture")
        void i420() {
            var raw = source("yuv420p", List.of());
            var decoded = decode(WIDTH, HEIGHT, raw, uncC(Layout.of(2, 0), Comp.of(0, 8), Comp.of(1, 8), Comp.of(2, 8)),
                    cmpd(Y, CB, CR));
            assertPlanar(decoded, raw, WIDTH / 2, HEIGHT / 2);
            var picture = assertInstanceOf(YuvPicture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            assertEquals(raw[0], picture.y()[0]);
        }
    }

    @Nested
    @DisplayName("component pictures")
    class Pictures {
        @Test
        @DisplayName("RGB becomes identity-matrix 4:4:4 planes and depths are rescaled to the deepest component")
        void rgb() {
            var data = new byte[]{(byte) 0b11111_000, 0b000_11111, 0, 0};
            var decoded = decode(2, 1, data, uncC(Layout.of(0, 1), Comp.of(0, 5), Comp.of(1, 6), Comp.of(2, 5)),
                    cmpd(R, G, B));
            var picture = assertInstanceOf(YuvPicture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            // green 0 in luma, blue 31 in u, red 31 in v, all scaled to 255
            assertEquals(0, picture.y()[0]);
            assertEquals((byte) 255, picture.u()[0]);
            assertEquals((byte) 255, picture.v()[0]);
            assertEquals(0, picture.v()[1]);
        }

        @Test
        @DisplayName("12-bit luma with alpha makes a 12-bit picture with alpha")
        void deep() {
            var data = new byte[]{(byte) 0xAB, (byte) 0xCF, (byte) 0xFF};
            var decoded = decode(1, 1, data, uncC(Layout.of(0, 1), Comp.of(0, 12), Comp.of(1, 12)), cmpd(Y, A));
            var picture = assertInstanceOf(Yuv16Picture.class,
                    HeifComponentPictures.toPicture("heic", decoded, ColorInfo.JPEG, LIMITS));
            assertEquals(12, picture.bitDepth());
            assertEquals(0xABC, picture.y()[0]);
            assertEquals(0xFFF, picture.alpha()[0]);
            assertTrue(picture.hasAlpha());
        }
    }

    @Nested
    @DisplayName("codec properties")
    class Properties {
        private static HeifItem item(byte[]... properties) {
            var builder = new HeifBuilder().brands("mif1", "mif1");
            var all = new HeifBuilder.Property[properties.length];
            for (var i = 0; i < properties.length; i++) {
                all[i] = plain(properties[i]);
            }
            builder.primary(builder.item("j2k1", new byte[4], all));
            return HeifFile.parse(MediaSource.of(builder.build()), LIMITS).primaryItem();
        }

        private static byte[] cdef(int type) {
            return HeifBuilder.box("cdef", new byte[]{0, 2, 0, 0, 0, 0, 0, 1, 0, 1, 0, (byte) type, 0, 0});
        }

        @Test
        @DisplayName("j2kH holds its child boxes, as a plain box or with a full box header")
        void jpeg2000Header() {
            for (var opacity : new int[]{1, 2}) {
                var plainBox = item(HeifBuilder.box("j2kH", cdef(opacity))).property(HeifProperty.Jpeg2000Header.class);
                var fullBox = item(HeifBuilder.fullBox("j2kH", 0, 0, cdef(opacity)))
                        .property(HeifProperty.Jpeg2000Header.class);
                assertEquals(1, plainBox.boxes().size());
                assertArrayEquals(plainBox.boxes().getFirst(), fullBox.boxes().getFirst());
                assertEquals(opacity, plainBox.opacity());
            }
            assertEquals(HeifProperty.Jpeg2000Header.NO_OPACITY,
                    item(HeifBuilder.box("j2kH", new byte[0])).property(HeifProperty.Jpeg2000Header.class).opacity());
        }

        @Test
        @DisplayName("avcC, jpgC, cmpC and cloc keep their fields")
        void others() {
            var parsed = item(HeifBuilder.box("avcC", new byte[]{1, 2, 3}), HeifBuilder.box("jpgC", new byte[]{9, 8}),
                    HeifBuilder.fullBox("cmpC", 0, 0, new byte[]{'d', 'e', 'f', 'l', (byte) 0x80}),
                    HeifBuilder.fullBox("cloc", 0, 0, new byte[]{2}));
            assertArrayEquals(new byte[]{1, 2, 3}, parsed.property(HeifProperty.AvcConfiguration.class).record());
            assertArrayEquals(new byte[]{9, 8}, parsed.property(HeifProperty.JpegConfiguration.class).prefix());
            var compression = parsed.property(HeifProperty.CompressionConfiguration.class);
            assertEquals("defl", compression.compressionType());
            assertTrue(compression.individualEntities());
            assertEquals(0, compression.entityType());
            assertEquals(2, parsed.property(HeifProperty.ChromaLocation.class).location());
        }
    }

    @Nested
    @DisplayName("invalid and unsupported items")
    class Invalid {
        @Test
        @DisplayName("data shorter than the layout is malformed")
        void truncated() {
            assertThrows(MediaException.Malformed.class, () -> decode(4, 4, new byte[15],
                    uncC(Layout.of(0, 0), Comp.of(0, 8)), cmpd(MONO)));
        }

        @Test
        @DisplayName("tiles that do not divide the image are malformed")
        void tiles() {
            assertThrows(MediaException.Malformed.class, () -> decode(5, 4, new byte[20],
                    uncC(Layout.of(0, 0).tiles(2, 1, 0), Comp.of(0, 8)), cmpd(MONO)));
        }

        @Test
        @DisplayName("a component index past the component definition is malformed")
        void index() {
            assertThrows(MediaException.Malformed.class, () -> decode(2, 2, new byte[4],
                    uncC(Layout.of(0, 0), Comp.of(3, 8)), cmpd(MONO)));
        }

        @Test
        @DisplayName("an uncC of a later version or an unknown version 1 profile keeps the file readable and is unsupported")
        void unknownVersion() {
            var later = HeifBuilder.fullBox("uncC", 2, 0, new byte[]{'r', 'g', 'b', '3', 0, 0, 0, 0});
            var unknownProfile = profile("yuv2");
            for (var uncC : List.of(later, unknownProfile)) {
                var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
                builder.primary(builder.item("unci", new byte[12], plain(HeifBuilder.ispe(2, 2)), plain(uncC)));
                var source = MediaSource.of(builder.build());
                var file = HeifFile.parse(source, LIMITS);
                var item = file.primaryItem();
                assertEquals(null, item.property(HeifProperty.UncompressedConfiguration.class));
                assertThrows(MediaException.Unsupported.class, () -> HeifUncompressed.decode("heic",
                        file.readItem(source, item, LIMITS), item, 2, 2, LIMITS));
            }
        }

        @Test
        @DisplayName("floating-point components, palettes and Bayer filter arrays are unsupported")
        void unsupported() {
            var floats = new ByteArrayOutputStream();
            HeifBuilder.u32(floats, 0);
            HeifBuilder.u32(floats, 1);
            HeifBuilder.u16(floats, 0);
            floats.write(31);
            floats.write(1);
            floats.write(0);
            floats.writeBytes(new byte[24]);
            assertThrows(MediaException.Unsupported.class, () -> decode(1, 1, new byte[4],
                    HeifBuilder.fullBox("uncC", 0, 0, floats.toByteArray()), cmpd(MONO)));
            assertThrows(MediaException.Unsupported.class, () -> decode(1, 1, new byte[1],
                    uncC(Layout.of(0, 0), Comp.of(0, 8)), cmpd(10)));
            assertThrows(MediaException.Unsupported.class, () -> decode(1, 1, new byte[1],
                    uncC(Layout.of(0, 0), Comp.of(0, 8)), cmpd(11)));
            assertThrows(MediaException.Unsupported.class, () -> decode(1, 1, new byte[1],
                    uncC(Layout.of(0, 0), Comp.of(0, 8)), cmpd(MONO), cmpC("brot")));
        }
    }

    private static void writeRows(ByteArrayOutputStream out, int[] plane, int width, int align) {
        for (var row = 0; row < plane.length / width; row++) {
            var start = out.size();
            for (var x = 0; x < width; x++) {
                out.write(plane[row * width + x]);
            }
            pad(out, start, align);
        }
    }

    private static void writeTile(ByteArrayOutputStream out, int[] plane, int planeWidth, int tile, int tileWidth,
                                  int tileHeight) {
        var x0 = (tile % 2) * tileWidth;
        var y0 = (tile / 2) * tileHeight;
        for (var row = 0; row < tileHeight; row++) {
            for (var x = 0; x < tileWidth; x++) {
                out.write(plane[(y0 + row) * planeWidth + x0 + x]);
            }
        }
    }

    private static void pad(ByteArrayOutputStream out, int start, int align) {
        while ((out.size() - start) % align != 0) {
            out.write(0x5A);
        }
    }
}
