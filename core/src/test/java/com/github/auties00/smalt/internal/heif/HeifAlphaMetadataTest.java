package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the shared HEIF pieces of alpha resampling, mixed-depth canvases and XMP items: the alpha
 * resampler against an independently built monochrome reference resampled by {@link ImageOps},
 * the canvas shifts of shallower planes, and the XMP lookup with every content encoding, built
 * box by box with {@link HeifBuilder}.
 */
@DisplayName("HEIF alpha, depth and XMP")
class HeifAlphaMetadataTest {
    private static final byte[] AV1C = {(byte) 0x81, 0x00, 0x0C, 0x00};

    private static final byte[] PACKET = ("<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?><x:xmpmeta xmlns:x='adobe:ns:meta/'>"
            + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about=''"
            + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:format>image/heif</dc:format></rdf:Description>"
            + "</rdf:RDF></x:xmpmeta><?xpacket end='w'?>").repeat(3).getBytes(StandardCharsets.UTF_8);

    @Nested
    @DisplayName("alpha resampling")
    class Resampling {
        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {8, 10, 12, 16})
        @DisplayName("resamples the luma of a strided 4:2:0 alpha picture like ImageOps does a tight monochrome copy")
        void luma(int depth) {
            var random = new SplittableRandom(depth);
            var width = 7;
            var height = 5;
            var stride = 11;
            var max = (1 << depth) - 1;
            var luma = new short[stride * height];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = (short) random.nextInt(max + 1);
            }
            var tight = new short[width * height];
            for (var y = 0; y < height; y++) {
                System.arraycopy(luma, y * stride, tight, y * width, width);
            }
            var color = ColorInfo.JPEG.withRange(ColorRange.LIMITED);
            com.github.auties00.smalt.image.Picture alpha;
            com.github.auties00.smalt.image.Picture reference;
            if (depth == 8) {
                var bytes = new byte[luma.length];
                var tightBytes = new byte[tight.length];
                for (var i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) luma[i];
                }
                for (var i = 0; i < tightBytes.length; i++) {
                    tightBytes[i] = (byte) tight[i];
                }
                alpha = YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(color)
                        .luma(bytes, stride).chroma(new byte[4 * 3], new byte[4 * 3], 4).build();
                reference = YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).color(color)
                        .luma(tightBytes, width).build();
            } else {
                alpha = Yuv16Picture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).bitDepth(depth)
                        .color(color).luma(luma, stride).chroma(new short[4 * 3], new short[4 * 3], 4).build();
                reference = Yuv16Picture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME)
                        .bitDepth(depth).color(color).luma(tight, width).build();
            }
            for (var size : new int[][]{{16, 9}, {3, 2}, {7, 12}}) {
                var resampled = HeifPictures.resampleAlpha(alpha, size[0], size[1]);
                var expected = ImageOps.resize(reference, size[0], size[1], ResizeFilter.BILINEAR);
                assertEquals(size[0], resampled.width());
                assertEquals(size[1], resampled.height());
                switch (resampled) {
                    case YuvPicture yuv -> {
                        assertEquals(ChromaFormat.MONOCHROME, yuv.chromaFormat());
                        assertEquals(color, yuv.color());
                        assertArrayEquals(lumaOf((YuvPicture) expected), lumaOf(yuv));
                    }
                    case Yuv16Picture yuv -> {
                        assertEquals(ChromaFormat.MONOCHROME, yuv.chromaFormat());
                        assertEquals(depth, yuv.bitDepth());
                        assertArrayEquals(lumaOf((Yuv16Picture) expected), lumaOf(yuv));
                    }
                    default -> throw new AssertionError(resampled);
                }
            }
            assertSame(alpha, HeifPictures.resampleAlpha(alpha, width, height));
        }

        @Test
        @DisplayName("keeps a constant plane constant and the triangle filter inside the source range")
        void bounded() {
            var luma = new byte[16];
            Arrays.fill(luma, (byte) 200);
            var flat = YuvPicture.builder().size(4, 4).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG).luma(luma, 4).build();
            var up = (YuvPicture) HeifPictures.resampleAlpha(flat, 13, 9);
            for (var value : lumaOf(up)) {
                assertEquals(200, value);
            }
            var edge = new short[64];
            for (var i = 0; i < edge.length; i++) {
                edge[i] = (short) ((i % 8) < 4 ? 0 : 1023);
            }
            var step = Yuv16Picture.builder().size(8, 8).chromaFormat(ChromaFormat.MONOCHROME).bitDepth(10)
                    .color(ColorInfo.JPEG).luma(edge, 8).build();
            for (var value : lumaOf((Yuv16Picture) HeifPictures.resampleAlpha(step, 29, 3))) {
                assertEquals(true, value >= 0 && value <= 1023, "sample " + value);
            }
        }

        @Test
        @DisplayName("rejects a packed alpha picture")
        void packed() {
            assertThrows(MediaException.Malformed.class,
                    () -> HeifPictures.resampleAlpha(RgbaPicture.allocate(2, 2, true), 4, 4));
        }

        @Test
        @DisplayName("copies the visible luma of a decoded plane at its depth")
        void lumaPicture() {
            var plane = new short[6 * 4];
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (short) (i * 3);
            }
            var narrow = assertInstanceOf(YuvPicture.class, HeifPictures.lumaPicture(plane, 7, 6, 3, 2, 8));
            assertArrayEquals(new int[]{21, 24, 27, 39, 42, 45}, lumaOf(narrow));
            var deep = assertInstanceOf(Yuv16Picture.class, HeifPictures.lumaPicture(plane, 1, 6, 2, 3, 10));
            assertEquals(10, deep.bitDepth());
            assertArrayEquals(new int[]{3, 6, 21, 24, 39, 42}, lumaOf(deep));
            var bytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            assertArrayEquals(new int[]{6, 7}, lumaOf(HeifPictures.lumaPicture(bytes, 5, 4, 2, 1)));
        }
    }

    @Nested
    @DisplayName("canvas")
    class Canvas {
        /** A tile of 16-bit planes with per-plane depths. */
        private record Tile(short[][] planes, int[] depths, int[] strides) implements DecodedTile {
            @Override
            public boolean eightBit() {
                return false;
            }

            @Override
            public byte[] plane8(int plane) {
                throw new UnsupportedOperationException();
            }

            @Override
            public short[] plane16(int plane) {
                return planes[plane];
            }

            @Override
            public int offset(int plane) {
                return 0;
            }

            @Override
            public int stride(int plane) {
                return strides[plane];
            }

            @Override
            public int depth(int plane) {
                return depths[plane];
            }
        }

        @ParameterizedTest(name = "luma {0} bits")
        @ValueSource(ints = {8, 10, 12})
        @DisplayName("shifts the planes of a shallower component up to the canvas depth, and narrows each at its own depth")
        void mixedDepths(int lumaDepth) {
            var chromaDepth = lumaDepth == 12 ? 8 : 12;
            var depth = Math.max(lumaDepth, chromaDepth);
            var random = new SplittableRandom(lumaDepth);
            var luma = new short[4 * 4];
            var cb = new short[2 * 2];
            var cr = new short[2 * 2];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = (short) random.nextInt(1 << lumaDepth);
            }
            for (var i = 0; i < cb.length; i++) {
                cb[i] = (short) random.nextInt(1 << chromaDepth);
                cr[i] = (short) random.nextInt(1 << chromaDepth);
            }
            var tile = new Tile(new short[][]{luma, cb, cr}, new int[]{lumaDepth, chromaDepth, chromaDepth},
                    new int[]{4, 2, 2});
            var wide = new HeifCanvas("heic", MediaLimits.DEFAULT, 4, 4, ChromaFormat.YUV420, depth, true, false);
            wide.writeColor(tile, 0, 0, 4, 4, false);
            var picture = (Yuv16Picture) wide.toPicture(ColorInfo.JPEG);
            assertEquals(depth, picture.bitDepth());
            for (var i = 0; i < luma.length; i++) {
                assertEquals(luma[i] << (depth - lumaDepth), picture.y()[i], "luma " + i);
            }
            for (var i = 0; i < cb.length; i++) {
                assertEquals(cb[i] << (depth - chromaDepth), picture.u()[i], "cb " + i);
                assertEquals(cr[i] << (depth - chromaDepth), picture.v()[i], "cr " + i);
            }
            var narrow = new HeifCanvas("heic", MediaLimits.DEFAULT, 4, 4, ChromaFormat.YUV420, depth, false, false);
            narrow.writeColor(tile, 0, 0, 4, 4, false);
            var eight = (YuvPicture) narrow.toPicture(ColorInfo.JPEG);
            for (var i = 0; i < luma.length; i++) {
                assertEquals(round(luma[i], lumaDepth), eight.y()[i] & 0xFF, "narrow luma " + i);
            }
            for (var i = 0; i < cb.length; i++) {
                assertEquals(round(cb[i], chromaDepth), eight.u()[i] & 0xFF, "narrow cb " + i);
            }
        }

        private static int round(int value, int depth) {
            var shift = depth - 8;
            return shift == 0 ? value : Math.min(255, (value + (1 << (shift - 1))) >> shift);
        }

        @Test
        @DisplayName("writes the top-left region of an alpha picture of another depth into a region")
        void alphaRegion() {
            var canvas = new HeifCanvas("heic", MediaLimits.DEFAULT, 4, 2, ChromaFormat.MONOCHROME, 10, true, true);
            var alpha = YuvPicture.builder().size(3, 1).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                    .luma(new byte[]{0, (byte) 128, (byte) 255}, 3).build();
            canvas.writeAlpha(alpha, 1, 1, 2, 1, false);
            var picture = (Yuv16Picture) canvas.toPicture(ColorInfo.JPEG);
            assertArrayEquals(new short[]{0, 0, 0, 0, 0, 0, (short) ((128 * 1023 + 127) / 255), 0},
                    Arrays.copyOf(picture.alpha(), 8));
        }
    }

    @Nested
    @DisplayName("XMP items")
    class Xmp {
        private static HeifFile parse(byte[] data) {
            return HeifFile.parse(MediaSource.of(data), MediaLimits.DEFAULT);
        }

        private static byte[] file(String contentType, String encoding, byte[] data) {
            var builder = new HeifBuilder();
            var image = builder.item("av01", new byte[]{1, 2, 3}, plain(HeifBuilder.ispe(4, 4)),
                    essential(HeifBuilder.av1C(AV1C)));
            var xmp = encoding == null ? builder.mimeItem(contentType, data)
                    : builder.mimeItem(contentType, encoding, data);
            builder.reference("cdsc", xmp, image).primary(image);
            return builder.build();
        }

        private static byte[] xmpOf(byte[] data, MediaLimits limits) {
            var heif = HeifFile.parse(MediaSource.of(data), limits);
            return heif.xmpOf(MediaSource.of(data), heif.primaryItem(), limits);
        }

        private static byte[] deflate(byte[] data, boolean raw) {
            var deflater = new Deflater(9, raw);
            deflater.setInput(data);
            deflater.finish();
            var out = new ByteArrayOutputStream();
            var buffer = new byte[256];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }

        /** Writes a gzip member with the FEXTRA, FNAME, FCOMMENT and FHCRC header fields. */
        private static byte[] gzipWithFields(byte[] data) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{0x1F, (byte) 0x8B, 8, 4 | 8 | 16 | 2, 0, 0, 0, 0, 0, 3});
            out.writeBytes(new byte[]{3, 0, 'a', 'b', 'c'});
            out.writeBytes("packet.xmp\0".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes("a comment\0".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(new byte[]{0x12, 0x34});
            out.writeBytes(deflate(data, true));
            var crc = new CRC32();
            crc.update(data);
            for (var shift = 0; shift < 32; shift += 8) {
                out.write((int) (crc.getValue() >>> shift));
            }
            for (var shift = 0; shift < 32; shift += 8) {
                out.write(data.length >>> shift);
            }
            return out.toByteArray();
        }

        private static byte[] gzip(byte[] data) {
            var out = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(out)) {
                gzip.write(data);
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads the content encoding of mime items and leaves it empty when absent")
        void contentEncoding() {
            var encoded = parse(file("application/rdf+xml", "deflate", deflate(PACKET, false)));
            var mime = encoded.descriptionsOf(encoded.primaryItem().id()).getFirst();
            assertEquals("deflate", mime.contentEncoding());
            assertEquals("application/rdf+xml", mime.contentType());
            var plainFile = parse(file("application/rdf+xml", null, PACKET));
            assertEquals("", plainFile.descriptionsOf(plainFile.primaryItem().id()).getFirst().contentEncoding());
            assertEquals("", plainFile.primaryItem().contentEncoding());
        }

        @Test
        @DisplayName("returns the packet of an unencoded, zlib, raw deflate and gzip item, with any content type parameters")
        void encodings() {
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml", null, PACKET), MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml; charset=utf-8", "", PACKET), MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("Application/RDF+XML", "identity", PACKET), MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml", "deflate", deflate(PACKET, false)),
                    MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml", "deflate", deflate(PACKET, true)),
                    MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml", "gzip", gzip(PACKET)), MediaLimits.DEFAULT));
            assertArrayEquals(PACKET, xmpOf(file("application/rdf+xml", "gzip", gzipWithFields(PACKET)),
                    MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("leaves out other content types, unknown encodings, corrupt and oversized packets")
        void leftOut() {
            assertNull(xmpOf(file("text/plain", null, PACKET), MediaLimits.DEFAULT));
            assertNull(xmpOf(file("application/rdf+xml", "br", PACKET), MediaLimits.DEFAULT));
            var compressed = deflate(PACKET, false);
            assertNull(xmpOf(file("application/rdf+xml", "deflate", Arrays.copyOf(compressed, compressed.length / 2)),
                    MediaLimits.DEFAULT));
            assertNull(xmpOf(file("application/rdf+xml", "gzip", new byte[]{0x1F, (byte) 0x8B, 8, 8, 0}),
                    MediaLimits.DEFAULT));
            var bomb = deflate(new byte[4 << 20], false);
            var small = MediaLimits.builder().maxBytes(1 << 20).build();
            assertNull(xmpOf(file("application/rdf+xml", "deflate", bomb), small));
            assertEquals(4 << 20, xmpOf(file("application/rdf+xml", "deflate", bomb), MediaLimits.DEFAULT).length);
            // half-random data inflates to less than four times its compressed size, the first buffer, so the
            // bound must apply to that buffer too
            var noise = new byte[400];
            var random = new byte[200];
            new SplittableRandom(5).nextBytes(random);
            System.arraycopy(random, 0, noise, 0, random.length);
            for (var encoding : new String[]{"deflate", "gzip"}) {
                var encoded = file("application/rdf+xml", encoding, encoding.equals("gzip") ? gzip(noise)
                        : deflate(noise, false));
                var heif = parse(encoded);
                var exact = MediaLimits.builder().maxBytes(noise.length).build();
                assertArrayEquals(noise, heif.xmpOf(MediaSource.of(encoded), heif.primaryItem(), exact), encoding);
                var under = MediaLimits.builder().maxBytes(noise.length - 1).build();
                assertNull(heif.xmpOf(MediaSource.of(encoded), heif.primaryItem(), under), encoding);
            }
        }

        @Test
        @DisplayName("takes the packet of the first input of a derived item that has none")
        void lineage() {
            var builder = new HeifBuilder();
            var tile = builder.item("av01", new byte[]{1, 2, 3}, plain(HeifBuilder.ispe(4, 4)),
                    essential(HeifBuilder.av1C(AV1C)));
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 1, 4, 4), plain(HeifBuilder.ispe(4, 4)));
            builder.reference("dimg", grid, tile);
            var xmp = builder.mimeItem("application/rdf+xml", PACKET);
            builder.reference("cdsc", xmp, tile).primary(grid);
            assertArrayEquals(PACKET, xmpOf(builder.build(), MediaLimits.DEFAULT));

            var own = new HeifBuilder();
            var ownTile = own.item("av01", new byte[]{1, 2, 3}, plain(HeifBuilder.ispe(4, 4)),
                    essential(HeifBuilder.av1C(AV1C)));
            var ownGrid = own.idatItem("grid", HeifBuilder.grid(1, 1, 4, 4), plain(HeifBuilder.ispe(4, 4)));
            own.reference("dimg", ownGrid, ownTile);
            var tileXmp = own.mimeItem("application/rdf+xml", new byte[]{'t'});
            var gridXmp = own.mimeItem("application/rdf+xml", PACKET);
            own.reference("cdsc", tileXmp, ownTile).reference("cdsc", gridXmp, ownGrid).primary(ownGrid);
            assertArrayEquals(PACKET, xmpOf(own.build(), MediaLimits.DEFAULT));
        }
    }

    private static int[] lumaOf(YuvPicture picture) {
        var out = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out[y * picture.width() + x] = picture.y()[y * picture.yStride() + x] & 0xFF;
            }
        }
        return out;
    }

    private static int[] lumaOf(Yuv16Picture picture) {
        var out = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out[y * picture.width() + x] = picture.y()[y * picture.yStride() + x] & 0xFFFF;
            }
        }
        return out;
    }
}
