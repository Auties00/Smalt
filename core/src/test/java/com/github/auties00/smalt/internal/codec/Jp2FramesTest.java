package com.github.auties00.smalt.internal.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the frame forms of Motion JPEG 2000 that {@link Jp2Frames} reads and writes, over synthetic main headers
 * built from ISO/IEC 15444-1 annex A (only the headers matter to it) and boxes built from annex I.
 */
@DisplayName("Jp2Frames")
class Jp2FramesTest {
    // SOC, SIZ (Rsiz 0, image 0..width x 0..height, one tile, components), COD (LRCP, 1 layer, mct), then SOT and EOC
    static byte[] codestream(int width, int height, int[] depths, int[] dx, int[] dy, boolean mct) {
        var out = new ByteArrayOutputStream();
        u16(out, 0xFF4F);
        u16(out, 0xFF51);
        u16(out, 38 + 3 * depths.length);
        u16(out, 0);
        u32(out, width);
        u32(out, height);
        u32(out, 0);
        u32(out, 0);
        u32(out, width);
        u32(out, height);
        u32(out, 0);
        u32(out, 0);
        u16(out, depths.length);
        for (var c = 0; c < depths.length; c++) {
            out.write(depths[c] - 1);
            out.write(dx[c]);
            out.write(dy[c]);
        }
        u16(out, 0xFF52);
        u16(out, 12);
        out.write(0);
        out.write(0);
        u16(out, 1);
        out.write(mct ? 1 : 0);
        out.write(5);
        out.write(4);
        out.write(4);
        out.write(0);
        out.write(1);
        u16(out, 0xFF90);
        u16(out, 10);
        u16(out, 0);
        u32(out, 14);
        out.write(0);
        out.write(1);
        u16(out, 0xFF93);
        u16(out, 0xFFD9);
        return out.toByteArray();
    }

    static byte[] box(String type, byte[]... contents) {
        var out = new ByteArrayOutputStream();
        var length = 8;
        for (var part : contents) {
            length += part.length;
        }
        u32(out, length);
        out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (var part : contents) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static final byte[] SIGNATURE = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 13, 10, (byte) 0x87, 10};

    static byte[] yuv420() {
        return codestream(33, 17, new int[]{8, 8, 8}, new int[]{1, 2, 2}, new int[]{1, 2, 2}, false);
    }

    static byte[] rgb() {
        return codestream(16, 8, new int[]{8, 8, 8}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, true);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    @Nested
    @DisplayName("classifies and locates")
    class Locate {
        @Test
        @DisplayName("a raw codestream as one codestream covering the frame")
        void codestream() {
            var frame = yuv420();
            var bounds = new int[4];
            assertEquals(Jp2Frames.CODESTREAM, Jp2Frames.form(frame, 0, frame.length));
            assertEquals(1, Jp2Frames.codestreams(frame, 0, frame.length, bounds));
            assertEquals(0, bounds[0]);
            assertEquals(frame.length, bounds[1]);
        }

        @Test
        @DisplayName("a JP2 file by its signature and its codestream inside the jp2c box")
        void file() {
            var stream = yuv420();
            var header = Jp2Frames.synthesizeHeader(Jp2Frames.siz(stream, 0, stream.length));
            var file = Jp2Frames.toFile(header, stream, 0, stream.length);
            var bounds = new int[4];
            assertEquals(Jp2Frames.FILE, Jp2Frames.form(file, 0, file.length));
            assertEquals(1, Jp2Frames.codestreams(file, 0, file.length, bounds));
            assertArrayEquals(stream, Arrays.copyOfRange(file, bounds[0], bounds[1]));
            assertArrayEquals(header, Jp2Frames.header(file, 0, file.length));
        }

        @Test
        @DisplayName("the two field codestreams of a Motion JPEG 2000 sample, also past other boxes and at an offset")
        void fields() {
            var top = yuv420();
            var bottom = rgb();
            var sample = concat(new byte[3], box("free", new byte[5]), box("jp2c", top), box("jp2c", bottom));
            var bounds = new int[4];
            assertEquals(Jp2Frames.BOXES, Jp2Frames.form(sample, 3, sample.length - 3));
            assertEquals(2, Jp2Frames.codestreams(sample, 3, sample.length - 3, bounds));
            assertArrayEquals(top, Arrays.copyOfRange(sample, bounds[0], bounds[1]));
            assertArrayEquals(bottom, Arrays.copyOfRange(sample, bounds[2], bounds[3]));
        }

        @Test
        @DisplayName("a jp2c box of length 0 as running to the end of the frame, and of a 64-bit length")
        void lengths() {
            var stream = yuv420();
            var open = concat(new byte[]{0, 0, 0, 0, 'j', 'p', '2', 'c'}, stream);
            var bounds = new int[4];
            assertEquals(1, Jp2Frames.codestreams(open, 0, open.length, bounds));
            assertEquals(8, bounds[0]);
            assertEquals(open.length, bounds[1]);
            var large = concat(new byte[]{0, 0, 0, 1, 'j', 'p', '2', 'c', 0, 0, 0, 0, 0, 0, 0,
                    (byte) (16 + stream.length)}, stream);
            assertEquals(1, Jp2Frames.codestreams(large, 0, large.length, bounds));
            assertEquals(16, bounds[0]);
            assertEquals(large.length, bounds[1]);
        }

        @Test
        @DisplayName("nothing in bytes of no known form, and stops at a box that does not fit")
        void unknown() {
            var bounds = new int[4];
            var garbage = "not a frame at all".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertEquals(Jp2Frames.UNKNOWN, Jp2Frames.form(garbage, 0, garbage.length));
            assertEquals(0, Jp2Frames.codestreams(garbage, 0, garbage.length, bounds));
            var truncated = box("jp2c", yuv420());
            truncated = Arrays.copyOf(truncated, truncated.length - 1);
            assertEquals(0, Jp2Frames.codestreams(truncated, 0, truncated.length, bounds));
            assertEquals(Jp2Frames.UNKNOWN, Jp2Frames.form(new byte[0], 0, 0));
        }

        @Test
        @DisplayName("never throws on random or truncated input")
        void robust() {
            var random = new Random(7);
            var bounds = new int[4];
            var sample = concat(box("jp2c", yuv420()), box("jp2c", rgb()));
            var file = Jp2Frames.toFile(Jp2Frames.synthesizeHeader(Jp2Frames.siz(rgb(), 0, rgb().length)), rgb(), 0,
                    rgb().length);
            for (var seed : new byte[][]{sample, file, yuv420()}) {
                for (var cut = 0; cut <= seed.length; cut++) {
                    var part = Arrays.copyOf(seed, cut);
                    Jp2Frames.form(part, 0, part.length);
                    Jp2Frames.codestreams(part, 0, part.length, bounds);
                    Jp2Frames.header(part, 0, part.length);
                    Jp2Frames.firstSiz(part);
                    Jp2Frames.toSample(part);
                    Jp2Frames.selfContained(part, Jp2Frames.header(file, 0, file.length));
                }
                for (var round = 0; round < 500; round++) {
                    var mutated = seed.clone();
                    for (var flips = 0; flips < 4; flips++) {
                        mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                    }
                    Jp2Frames.form(mutated, 0, mutated.length);
                    Jp2Frames.codestreams(mutated, 0, mutated.length, bounds);
                    Jp2Frames.header(mutated, 0, mutated.length);
                    Jp2Frames.firstSiz(mutated);
                    Jp2Frames.toSample(mutated);
                    Jp2Frames.headerColorSpace(mutated);
                    Jp2Frames.headerDepth(mutated);
                }
            }
        }
    }

    @Nested
    @DisplayName("parses and builds headers")
    class Headers {
        @Test
        @DisplayName("reads the SIZ fields and the component transform flag")
        void siz() {
            var yuv = Jp2Frames.siz(yuv420(), 0, yuv420().length);
            assertNotNull(yuv);
            assertEquals(33, yuv.width());
            assertEquals(17, yuv.height());
            assertEquals(3, yuv.components());
            assertEquals(2, yuv.subsamplingX()[1]);
            assertFalse(yuv.componentTransform());
            assertTrue(yuv.subsampledChroma());
            var rgb = Jp2Frames.siz(rgb(), 0, rgb().length);
            assertTrue(rgb.componentTransform());
            assertFalse(rgb.subsampledChroma());
            var wide = codestream(4, 4, new int[]{12, 10}, new int[]{1, 1}, new int[]{1, 1}, false);
            assertEquals(12, Jp2Frames.siz(wide, 0, wide.length).maxDepth());
        }

        @ParameterizedTest(name = "{0} leading bytes")
        @ValueSource(ints = {0, 20, 60})
        @DisplayName("reads the first codestream's SIZ from the leading bytes of a sample whose box runs past them")
        void truncated(int extra) {
            var sample = box("jp2c", yuv420());
            var lead = Arrays.copyOf(sample, 8 + 4 + 2 + 38 + 9 + extra);
            var siz = Jp2Frames.firstSiz(lead);
            assertNotNull(siz);
            assertEquals(33, siz.width());
        }

        @Test
        @DisplayName("synthesizes sYCC for subsampled chroma, sRGB for RGB and greyscale for one component")
        void synthesized() {
            var yuv = Jp2Frames.synthesizeHeader(Jp2Frames.siz(yuv420(), 0, yuv420().length));
            assertTrue(Jp2Frames.isHeader(yuv));
            assertEquals(18, Jp2Frames.headerColorSpace(yuv));
            assertEquals(8, Jp2Frames.headerDepth(yuv));
            assertArrayEquals(new long[]{33, 17}, Jp2Frames.headerSize(yuv));
            var rgb = Jp2Frames.synthesizeHeader(Jp2Frames.siz(rgb(), 0, rgb().length));
            assertEquals(16, Jp2Frames.headerColorSpace(rgb));
            var gray = codestream(4, 4, new int[]{8}, new int[]{1}, new int[]{1}, false);
            assertEquals(17, Jp2Frames.headerColorSpace(Jp2Frames.synthesizeHeader(Jp2Frames.siz(gray, 0,
                    gray.length))));
        }

        @Test
        @DisplayName("writes a Bits Per Component box when the components differ, and reads the largest depth")
        void bitsPerComponent() {
            var header = Jp2Frames.buildHeader(10, 20, new int[]{12, 8, 8}, new boolean[]{false, false, false}, 18);
            assertTrue(Jp2Frames.isHeader(header));
            assertEquals(0xFF, header[26] & 0xFF);
            assertEquals(12, Jp2Frames.headerDepth(header));
            assertEquals(18, Jp2Frames.headerColorSpace(header));
            assertArrayEquals(new long[]{10, 20}, Jp2Frames.headerSize(header));
        }

        @Test
        @DisplayName("rejects boxes that are not a JP2 Header box")
        void notHeaders() {
            assertFalse(Jp2Frames.isHeader(null));
            assertFalse(Jp2Frames.isHeader(new byte[0]));
            assertFalse(Jp2Frames.isHeader(box("jp2h", new byte[22])));
            assertFalse(Jp2Frames.isHeader(box("free", box("ihdr", new byte[14]))));
            assertTrue(Jp2Frames.isHeader(box("jp2h", box("ihdr", new byte[14]))));
            assertEquals(-1, Jp2Frames.headerColorSpace(box("jp2h", box("ihdr", new byte[14]))));
            assertNull(Jp2Frames.headerSize(new byte[3]));
        }
    }

    @Nested
    @DisplayName("rewrites")
    class Rewrites {
        @Test
        @DisplayName("any form into the jp2c boxes of a Motion JPEG 2000 sample, keeping a sample that already is one")
        void toSample() {
            var stream = yuv420();
            var expected = box("jp2c", stream);
            assertArrayEquals(expected, Jp2Frames.toSample(stream));
            var header = Jp2Frames.synthesizeHeader(Jp2Frames.siz(stream, 0, stream.length));
            assertArrayEquals(expected, Jp2Frames.toSample(Jp2Frames.toFile(header, stream, 0, stream.length)));
            var fields = concat(box("jp2c", stream), box("jp2c", rgb()));
            assertSame(fields, Jp2Frames.toSample(fields));
            assertArrayEquals(fields, Jp2Frames.toSample(concat(box("free", new byte[2]), fields)));
            assertNull(Jp2Frames.toSample(new byte[]{1, 2, 3}));
        }

        @Test
        @DisplayName("a frame relying on the track's header into a JP2 file, or field boxes after the header box,"
                + " leaving files and frames with a header alone")
        void selfContained() {
            var stream = yuv420();
            var header = Jp2Frames.synthesizeHeader(Jp2Frames.siz(stream, 0, stream.length));
            var expected = concat(SIGNATURE, box("ftyp", "jp2 ".getBytes(), new byte[4], "jp2 ".getBytes()), header,
                    box("jp2c", stream));
            assertArrayEquals(expected, Jp2Frames.selfContained(stream, header));
            assertArrayEquals(expected, Jp2Frames.selfContained(box("jp2c", stream), header));
            assertSame(stream, Jp2Frames.selfContained(stream, null));
            assertSame(expected, Jp2Frames.selfContained(expected, header));
            var fields = concat(box("jp2c", stream), box("jp2c", stream));
            var framed = concat(header, fields);
            assertArrayEquals(framed, Jp2Frames.selfContained(fields, header));
            assertArrayEquals(framed, Jp2Frames.selfContained(concat(box("free", new byte[3]), fields), header));
            assertSame(fields, Jp2Frames.selfContained(fields, null));
            assertSame(framed, Jp2Frames.selfContained(framed, header));
            var own = concat(fields, header);
            assertSame(own, Jp2Frames.selfContained(own, Jp2Frames.synthesizeHeader(Jp2Frames.siz(rgb(), 0,
                    rgb().length))));
            assertArrayEquals(header, Jp2Frames.header(framed, 0, framed.length));
            var at = new int[4];
            assertTrue(Jp2Frames.findHeader(own, 0, own.length, at));
            assertEquals(fields.length, at[0]);
            assertEquals(own.length, at[1]);
            assertTrue(Jp2Frames.isHeader(own, at[0], at[1]));
            assertFalse(Jp2Frames.isHeader(own, at[0], at[1] - 1));
            assertFalse(Jp2Frames.findHeader(fields, 0, fields.length, at));
            assertArrayEquals(fields, Jp2Frames.toSample(framed), "a sample drops the frame's header box");
        }

        @Test
        @DisplayName("a high-throughput codestream into a file of the JPH brand")
        void jphBrand() {
            var stream = yuv420();
            // Rsiz bit 14 declares ISO/IEC 15444-15
            stream[6] = 0x40;
            var header = Jp2Frames.synthesizeHeader(Jp2Frames.siz(stream, 0, stream.length));
            var expected = concat(SIGNATURE, box("ftyp", "jph ".getBytes(), new byte[4], "jph ".getBytes()), header,
                    box("jp2c", stream));
            assertArrayEquals(expected, Jp2Frames.toFile(header, stream, 0, stream.length));
        }
    }
}
