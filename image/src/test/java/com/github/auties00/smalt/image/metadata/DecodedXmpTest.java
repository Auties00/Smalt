package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the XMP packet the image decoders return in {@code DecodedImage.xmp()}: the exact bytes
 * of packets inserted by hand into JPEG, PNG, WebP, TIFF and JPEG 2000 files, Extended XMP merged
 * as {@link ImageMetadata} merges it, and the packets {@link ImageMetadataEditor} writes.
 */
@DisplayName("XMP of decoded images")
class DecodedXmpTest {
    private static final byte[] PACKET = ("<?xpacket begin='" + (char) 0xFEFF + "' id='W5M0MpCehiHzreSzNTczkc9d'?>"
            + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
            + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
            + "<dc:format>image/test</dc:format></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='w'?>")
            .getBytes(StandardCharsets.UTF_8);

    private static XmpPacket packet(String title) {
        return XmpPacket.empty().with(XmpPacket.DC, "title", title).with(XmpPacket.XMP, "CreatorTool", "Smalt");
    }

    @Nested
    @DisplayName("JPEG")
    class Jpeg {
        private static byte[] withApp1(byte[] jpeg, byte[] id, byte[] payload) {
            var out = new ByteArrayOutputStream();
            out.write(0xFF);
            out.write(0xD8);
            var length = 2 + id.length + payload.length;
            out.write(0xFF);
            out.write(0xE1);
            out.write(length >> 8);
            out.write(length);
            out.writeBytes(id);
            out.writeBytes(payload);
            out.write(jpeg, 2, jpeg.length - 2);
            return out.toByteArray();
        }

        @Test
        @DisplayName("return the first XMP APP1 segment's packet exactly, through every decode path")
        void exact() {
            var id = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
            var jpeg = withApp1(withApp1(MetadataSupport.jpeg(), id, "<second/>".getBytes(StandardCharsets.UTF_8)), id,
                    PACKET);
            assertArrayEquals(PACKET, JpegDecoder.decode(MediaSource.of(jpeg)).xmp().orElseThrow());
            assertArrayEquals(PACKET, JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.ofMinimumSize(8, 8))
                    .xmp().orElseThrow());
            assertArrayEquals(PACKET, JpegDecoder.decode(MediaSource.of(jpeg),
                    ImageDecoderOptions.builder().convertToSrgb(true).build()).xmp().orElseThrow());
            assertArrayEquals(PACKET, Images.decode(MediaSource.of(jpeg)).xmp().orElseThrow());
            assertFalse(JpegDecoder.decode(MediaSource.of(MetadataSupport.jpeg())).xmp().isPresent());
        }

        @Test
        @DisplayName("merge an Extended XMP packet as ImageMetadata does, and keep the main packet when it is incomplete")
        void extended() {
            var large = packet("Extended").with(XmpPacket.DC, "description", "x".repeat(150_000))
                    .with(XmpPacket.PHOTOSHOP, "City", "Roma");
            var edited = ImageMetadataEditor.apply(MetadataSupport.jpeg(), ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(large).build());
            var decoded = JpegDecoder.decode(MediaSource.of(edited)).xmp().orElseThrow();
            var merged = XmpPacket.parse(decoded);
            assertEquals(ImageMetadata.read(edited).xmp().orElseThrow(), merged);
            assertEquals(java.util.Set.copyOf(large.properties()), java.util.Set.copyOf(merged.properties()));
            assertEquals("Roma", merged.text(XmpPacket.PHOTOSHOP, "City").orElseThrow());

            var truncated = dropLastExtendedSegment(edited);
            var main = XmpPacket.parse(JpegDecoder.decode(MediaSource.of(truncated)).xmp().orElseThrow());
            assertTrue(main.text(XmpPacket.XMP_NOTE, "HasExtendedXMP").isPresent());
            assertNotEquals(large, main);

            // an extension that claims a length past the allocation limit leaves the main packet as stored
            var guid = "0123456789ABCDEF0123456789ABCDEF";
            var mainBytes = packet("Main").with(XmpPacket.XMP_NOTE, "HasExtendedXMP", guid).toBytes();
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(guid.getBytes(StandardCharsets.ISO_8859_1));
            chunk.writeBytes(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0});
            chunk.writeBytes("<x/>".getBytes(StandardCharsets.UTF_8));
            var extensionId = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.ISO_8859_1);
            var mainId = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
            var hostile = withApp1(withApp1(MetadataSupport.jpeg(), extensionId, chunk.toByteArray()), mainId, mainBytes);
            var limited = ImageDecoderOptions.builder().limits(com.github.auties00.smalt.MediaLimits.builder()
                    .maxBytes(16L << 20).build()).build();
            assertArrayEquals(mainBytes, JpegDecoder.decode(MediaSource.of(hostile), limited).xmp().orElseThrow());
        }

        private static byte[] dropLastExtendedSegment(byte[] jpeg) {
            var id = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.ISO_8859_1);
            var last = -1;
            var lastLength = 0;
            var position = 2;
            while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF && (jpeg[position + 1] & 0xFF) != 0xDA) {
                var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
                if ((jpeg[position + 1] & 0xFF) == 0xE1 && Arrays.equals(jpeg, position + 4, position + 4 + id.length,
                        id, 0, id.length)) {
                    last = position;
                    lastLength = length + 2;
                }
                position += length + 2;
            }
            assertTrue(last > 0, "an Extended XMP segment");
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, last);
            out.write(jpeg, last + lastLength, jpeg.length - last - lastLength);
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("PNG")
    class Png {
        private static byte[] chunk(String type, byte[] data) {
            var out = new ByteArrayOutputStream();
            out.write(data.length >>> 24);
            out.write(data.length >>> 16);
            out.write(data.length >>> 8);
            out.write(data.length);
            var typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(typeBytes);
            out.writeBytes(data);
            var crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            var value = crc.getValue();
            out.write((int) (value >>> 24));
            out.write((int) (value >>> 16));
            out.write((int) (value >>> 8));
            out.write((int) value);
            return out.toByteArray();
        }

        private static byte[] itxt(String keyword, byte[] text, boolean compressed) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(keyword.getBytes(StandardCharsets.ISO_8859_1));
            out.write(0);
            out.write(compressed ? 1 : 0);
            out.write(0);
            out.writeBytes("en".getBytes(StandardCharsets.ISO_8859_1));
            out.write(0);
            out.write(0);
            if (compressed) {
                var deflater = new Deflater();
                deflater.setInput(text);
                deflater.finish();
                var buffer = new byte[text.length + 64];
                var size = deflater.deflate(buffer);
                deflater.end();
                out.write(buffer, 0, size);
            } else {
                out.writeBytes(text);
            }
            return chunk("iTXt", out.toByteArray());
        }

        /** Inserts a chunk after IHDR (early) or before IEND (late). */
        private static byte[] insert(byte[] png, byte[] chunk, boolean early) {
            var at = early ? 8 + 25 : png.length - 12;
            var out = new ByteArrayOutputStream();
            out.write(png, 0, at);
            out.writeBytes(chunk);
            out.write(png, at, png.length - at);
            return out.toByteArray();
        }

        @Test
        @DisplayName("return the packet of the first XMP iTXt chunk, plain or compressed, before or after the image data")
        void chunks() {
            var png = MetadataSupport.png();
            var other = itxt("Comment", "<other/>".getBytes(StandardCharsets.UTF_8), false);
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(insert(insert(png, itxt("XML:com.adobe.xmp", PACKET,
                    false), true), other, true))).xmp().orElseThrow());
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(insert(png, itxt("XML:com.adobe.xmp", PACKET,
                    true), true))).xmp().orElseThrow());
            var late = insert(png, itxt("XML:com.adobe.xmp", PACKET, true), false);
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(late)).xmp().orElseThrow());
            var first = insert(insert(png, itxt("XML:com.adobe.xmp", PACKET, false), true),
                    itxt("XML:com.adobe.xmp", "<late/>".getBytes(StandardCharsets.UTF_8), false), false);
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(first)).xmp().orElseThrow());
            assertFalse(PngDecoder.decode(MediaSource.of(png)).xmp().isPresent());
        }

        @Test
        @DisplayName("tolerate a corrupt or truncated chunk after the image data, and leave a bad-CRC packet out")
        void damaged() {
            var png = MetadataSupport.png();
            var late = insert(png, itxt("XML:com.adobe.xmp", PACKET, false), false);
            var truncated = Arrays.copyOf(late, late.length - 40);
            assertFalse(PngDecoder.decode(MediaSource.of(truncated)).xmp().isPresent());
            var badCrc = insert(png, itxt("XML:com.adobe.xmp", PACKET, false), true);
            badCrc[8 + 25 + 8 + 20] ^= 1;
            assertFalse(PngDecoder.decode(MediaSource.of(badCrc)).xmp().isPresent());
            // a compressed packet whose zlib stream is corrupt, before the image data, then a valid one after it
            var corrupt = new ByteArrayOutputStream();
            corrupt.writeBytes("XML:com.adobe.xmp\0".getBytes(StandardCharsets.ISO_8859_1));
            corrupt.writeBytes(new byte[]{1, 0, 0, 0, 0x78, (byte) 0x9C, (byte) 0xFF, (byte) 0xFF, 7, 1});
            var corruptChunk = chunk("iTXt", corrupt.toByteArray());
            var decoded = PngDecoder.decode(MediaSource.of(insert(png, corruptChunk, true)));
            assertTrue(decoded.picture().width() > 0);
            assertFalse(decoded.xmp().isPresent());
            var recovered = insert(insert(png, corruptChunk, true), itxt("XML:com.adobe.xmp", PACKET, true), false);
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(recovered)).xmp().orElseThrow());
            // a compression method other than zlib is not a packet
            var method = itxt("XML:com.adobe.xmp", PACKET, false);
            method[8 + "XML:com.adobe.xmp".length() + 2] = 1;
            var methodData = Arrays.copyOfRange(method, 8, method.length - 4);
            assertFalse(PngDecoder.decode(MediaSource.of(insert(png, chunk("iTXt", methodData), true))).xmp().isPresent());
        }

        @Test
        @DisplayName("read the packet the metadata editor writes, and only before the frames of an animation")
        void editorAndAnimation() {
            var edited = ImageMetadataEditor.apply(MetadataSupport.png(), ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(packet("Png")).build());
            assertEquals(packet("Png"), XmpPacket.parse(PngDecoder.decode(MediaSource.of(edited)).xmp().orElseThrow()));

            var sink = new ByteArrayOutputStream();
            try (var writer = Images.openAnimationWriter(ImageFormat.PNG, 8, 8, MediaSink.of(sink))) {
                for (var i = 0; i < 2; i++) {
                    var frame = RgbaPicture.allocate(8, 8, false);
                    Arrays.fill(frame.pixels(), (byte) (i * 100));
                    writer.write(frame, 100_000);
                }
                writer.finish();
            }
            var apng = sink.toByteArray();
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(insert(apng, itxt("XML:com.adobe.xmp", PACKET,
                    false), true))).xmp().orElseThrow());
            assertFalse(PngDecoder.decode(MediaSource.of(insert(apng, itxt("XML:com.adobe.xmp", PACKET, false), false)))
                    .xmp().isPresent());
            var still = PngEncoder.encode(RgbaPicture.allocate(4, 4, true));
            assertArrayEquals(PACKET, PngDecoder.decode(MediaSource.of(insert(still, itxt("XML:com.adobe.xmp", PACKET,
                    false), false)), ImageDecoderOptions.builder().highBitDepth(true).build()).xmp().orElseThrow());
        }
    }

    @Nested
    @DisplayName("WebP, TIFF and JPEG 2000")
    class Others {
        @Test
        @DisplayName("return the XMP chunk of a lossy, lossless and edited WebP file exactly")
        void webp() {
            for (var webp : List.of(MetadataSupport.webp(), MetadataSupport.webpLossless())) {
                var withXmp = WebpContainer.withXmp(webp, PACKET);
                assertArrayEquals(PACKET, WebpDecoder.decode(MediaSource.of(withXmp)).xmp().orElseThrow());
                assertFalse(WebpDecoder.decode(MediaSource.of(webp)).xmp().isPresent());
            }
            var edited = ImageMetadataEditor.apply(MetadataSupport.webp(), ImageMetadataEdit.keepAll().toBuilder()
                    .xmp(packet("Webp")).build());
            assertEquals(packet("Webp"), XmpPacket.parse(WebpDecoder.decode(MediaSource.of(edited)).xmp().orElseThrow()));
        }

        /** Writes the fields of a TIFF file in one byte order. */
        private static final class TiffBytes {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final boolean bigEndian;

            TiffBytes(boolean bigEndian) {
                this.bigEndian = bigEndian;
            }

            void u16(int value) {
                if (bigEndian) {
                    bytes.write(value >>> 8);
                    bytes.write(value);
                } else {
                    bytes.write(value);
                    bytes.write(value >>> 8);
                }
            }

            void u32(long value) {
                if (bigEndian) {
                    u16((int) (value >>> 16));
                    u16((int) value);
                } else {
                    u16((int) value);
                    u16((int) (value >>> 16));
                }
            }

            void header() {
                bytes.writeBytes(bigEndian ? new byte[]{'M', 'M'} : new byte[]{'I', 'I'});
                u16(42);
                u32(8);
            }

            /** Writes an entry whose single SHORT or LONG value is inline, or whose values are at an offset. */
            void entry(int tag, int type, int count, long value) {
                u16(tag);
                u16(type);
                u32(count);
                if (type == 3 && count == 1) {
                    u16((int) value);
                    u16(0);
                } else {
                    u32(value);
                }
            }
        }

        /** Builds an uncompressed 2x2 RGB TIFF whose IFD0 carries an XMP packet, or none. */
        private static byte[] tiff(byte[] xmp, boolean bigEndian) {
            var entries = xmp == null ? 9 : 10;
            var bits = 8 + 2 + entries * 12 + 4;
            var pixels = bits + 6;
            var packet = pixels + 12;
            var out = new TiffBytes(bigEndian);
            out.header();
            out.u16(entries);
            out.entry(256, 3, 1, 2);
            out.entry(257, 3, 1, 2);
            out.entry(258, 3, 3, bits);
            out.entry(259, 3, 1, 1);
            out.entry(262, 3, 1, 2);
            out.entry(273, 4, 1, pixels);
            out.entry(277, 3, 1, 3);
            out.entry(278, 3, 1, 2);
            out.entry(279, 4, 1, 12);
            if (xmp != null) {
                out.entry(700, 1, xmp.length, packet);
            }
            out.u32(0);
            out.u16(8);
            out.u16(8);
            out.u16(8);
            for (var i = 0; i < 12; i++) {
                out.bytes.write(i * 20);
            }
            if (xmp != null) {
                out.bytes.writeBytes(xmp);
            }
            return out.bytes.toByteArray();
        }

        @Test
        @DisplayName("return the XMLPacket tag of a TIFF page exactly, in either byte order")
        void tiff() {
            for (var bigEndian : new boolean[]{false, true}) {
                var decoded = TiffDecoder.decode(MediaSource.of(tiff(PACKET, bigEndian)));
                assertArrayEquals(PACKET, decoded.xmp().orElseThrow());
                assertEquals(2, decoded.picture().width());
                assertFalse(TiffDecoder.decode(MediaSource.of(tiff(null, bigEndian))).xmp().isPresent());
            }
        }

        @Test
        @DisplayName("return the XMP box of a JPEG 2000 file")
        void jp2() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var jp2 = Fixtures.read(Fixtures.testImage("jp2", 32, 32, List.of()));
            var box = new ByteArrayOutputStream();
            var length = 8 + PACKET.length;
            box.write(length >>> 24);
            box.write(length >>> 16);
            box.write(length >>> 8);
            box.write(length);
            box.writeBytes("xml ".getBytes(StandardCharsets.ISO_8859_1));
            box.writeBytes(PACKET);
            var withXmp = Arrays.copyOf(jp2, jp2.length + box.size());
            System.arraycopy(box.toByteArray(), 0, withXmp, jp2.length, box.size());
            assertArrayEquals(PACKET, Jp2Decoder.decode(MediaSource.of(withXmp)).xmp().orElseThrow());
            assertFalse(Jp2Decoder.decode(MediaSource.of(jp2)).xmp().isPresent());
            // the uuid box of XMP part 3, BE7ACFCB-97A9-42E8-9C71-999491E3AFAC, as Adobe applications write it
            var uuid = HexFormat.of().parseHex("BE7ACFCB97A942E89C71999491E3AFAC");
            var uuidBox = new ByteArrayOutputStream();
            var uuidLength = 8 + uuid.length + PACKET.length;
            uuidBox.write(uuidLength >>> 24);
            uuidBox.write(uuidLength >>> 16);
            uuidBox.write(uuidLength >>> 8);
            uuidBox.write(uuidLength);
            uuidBox.writeBytes("uuid".getBytes(StandardCharsets.ISO_8859_1));
            uuidBox.writeBytes(uuid);
            uuidBox.writeBytes(PACKET);
            var withUuid = Arrays.copyOf(jp2, jp2.length + uuidBox.size());
            System.arraycopy(uuidBox.toByteArray(), 0, withUuid, jp2.length, uuidBox.size());
            assertArrayEquals(PACKET, Jp2Decoder.decode(MediaSource.of(withUuid)).xmp().orElseThrow());
        }
    }
}
