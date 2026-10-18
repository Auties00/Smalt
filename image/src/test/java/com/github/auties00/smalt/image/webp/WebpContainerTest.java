package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link WebpContainer}: bounded chunk parsing, EXIF surgery on simple lossy and lossless,
 * extended and animated files (validated structurally, by webpinfo and webpmux, and by decoding
 * the result with dwebp and this decoder), EXIF extraction, the animation flag and the first-frame
 * length.
 */
class WebpContainerTest {
    /** A minimal big-endian TIFF/EXIF payload with one Orientation (0x0112) entry. */
    static byte[] exifWithOrientation(int orientation) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8});
        out.writeBytes(new byte[]{0, 1});
        out.writeBytes(new byte[]{0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0});
        out.writeBytes(new byte[]{0, 0, 0, 0});
        return out.toByteArray();
    }

    static byte[] losslessStill(boolean alpha) {
        var rgba = new byte[23 * 17 * 4];
        new Random(alpha ? 1 : 2).nextBytes(rgba);
        return WebpEncoder.encode(RgbaPicture.of(23, 17, 23 * 4, rgba, alpha), WebpEncoderOptions.lossless()
                .toBuilder().exact(true).build());
    }

    static Path lossyStillPath() {
        Oracle.assume(Oracle.Tool.CWEBP);
        var png = Fixtures.testImage("png", 50, 30, List.of("-pix_fmt", "rgb24"));
        var out = WebpTestSupport.scratchPath("container-lossy.webp");
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-q", "70", png.toString(), "-o", out.toString()));
        return out;
    }

    static byte[] animation() {
        var encoder = new WebpAnimationEncoder(40, 30, WebpAnimationEncoderOptions.lossless());
        var random = new Random(4);
        for (var i = 0; i < 3; i++) {
            var rgba = new byte[40 * 30 * 4];
            random.nextBytes(rgba);
            encoder.addFrame(RgbaPicture.of(40, 30, 160, rgba, true), 50 + i);
        }
        return encoder.finish();
    }

    static List<String> tags(byte[] file) {
        return WebpContainer.parse(file).stream().map(WebpContainer.Chunk::fourCc).toList();
    }

    static void assertWebpinfoClean(byte[] file, String name) {
        var path = WebpTestSupport.scratch(name, file);
        var info = WebpTestSupport.runTool("webpinfo", List.of(path.toString()));
        assertTrue(info.contains("No error detected"), info);
    }

    @Nested
    @DisplayName("parse")
    class Parse {
        @Test
        @DisplayName("lists the chunks of a simple file")
        void simple() {
            var file = losslessStill(true);
            var chunks = WebpContainer.parse(file);
            assertEquals(1, chunks.size());
            var chunk = chunks.getFirst();
            assertEquals("VP8L", chunk.fourCc());
            assertEquals(12, chunk.offset());
            assertEquals(20, chunk.payloadOffset());
            assertEquals(file.length, chunk.end());
            assertEquals(file.length - 20, chunk.size() + (chunk.size() & 1));
        }

        @Test
        @DisplayName("ignores bytes after the declared RIFF end")
        void trailing() {
            var file = losslessStill(false);
            var extended = Arrays.copyOf(file, file.length + 7);
            assertEquals(WebpContainer.parse(file), WebpContainer.parse(extended));
        }

        @Test
        @DisplayName("tolerates a missing final padding byte")
        void missingPad() {
            var chunk = new byte[]{'V', 'P', '8', 'L', 5, 0, 0, 0, 0x2F, 0, 0, 0, 0};
            var file = riff(chunk);
            assertEquals(List.of("VP8L"), tags(file));
        }

        @Test
        @DisplayName("rejects truncated files, bad headers and oversized chunks")
        void malformed() {
            var file = losslessStill(true);
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(Arrays.copyOf(file, file.length - 1)));
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(new byte[11]));
            var notRiff = file.clone();
            notRiff[0] = 'X';
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(notRiff));
            var notWebp = file.clone();
            notWebp[11] = 'Q';
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(notWebp));
            var hugeChunk = file.clone();
            hugeChunk[16] = (byte) 0xFF;
            hugeChunk[17] = (byte) 0xFF;
            hugeChunk[18] = (byte) 0xFF;
            hugeChunk[19] = (byte) 0x7F;
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(hugeChunk));
            var shortHeader = riff(new byte[]{'V', 'P', '8', 'L', 0, 0, 0, 0, 'E', 'X'});
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.parse(shortHeader));
            assertThrows(NullPointerException.class, () -> WebpContainer.parse(null));
        }
    }

    static byte[] riff(byte[] body) {
        var out = new ByteArrayOutputStream();
        var size = body.length + 4;
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("withExif")
    class WithExif {
        @ParameterizedTest(name = "alpha={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("converts a simple lossless file to VP8X with the alpha flag from the bitstream")
        void lossless(boolean alpha) {
            var file = losslessStill(alpha);
            var exif = exifWithOrientation(6);
            var tagged = WebpContainer.withExif(file, exif);
            WebpStructure.validate(tagged);
            assertEquals(List.of("VP8X", "VP8L", "EXIF"), tags(tagged));
            assertEquals((alpha ? 0x10 : 0) | 0x08, tagged[20] & 0xFF, "VP8X flags");
            assertEquals(22, WebpStructure.le24(tagged, 24), "canvas width - 1");
            assertEquals(16, WebpStructure.le24(tagged, 27), "canvas height - 1");
            assertArrayEquals(exif, WebpContainer.exif(tagged).orElseThrow());
            assertArrayEquals(WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)),
                    WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(tagged)));
            assertWebpinfoClean(tagged, "exif-ll-" + alpha + ".webp");
        }

        @Test
        @DisplayName("converts a simple lossy file, which dwebp still decodes identically")
        void lossy() {
            var path = lossyStillPath();
            var file = WebpTestSupport.readFile(path);
            var tagged = WebpContainer.withExif(file, exifWithOrientation(3));
            WebpStructure.validate(tagged);
            assertEquals(List.of("VP8X", "VP8 ", "EXIF"), tags(tagged));
            assertEquals(0x08, tagged[20] & 0xFF, "VP8X flags");
            assertEquals(49, WebpStructure.le24(tagged, 24));
            assertEquals(29, WebpStructure.le24(tagged, 27));
            var taggedPath = WebpTestSupport.scratch("container-lossy-exif.webp", tagged);
            assertArrayEquals(WebpTestSupport.dwebpRgba(path), WebpTestSupport.dwebpRgba(taggedPath));
            assertWebpinfoClean(tagged, "container-lossy-exif-info.webp");
        }

        @Test
        @DisplayName("keeps ICC and XMP in specification order and replaces an existing EXIF chunk")
        void extended() {
            var file = losslessStill(true);
            var source = WebpTestSupport.scratch("container-ext-src.webp", file);
            var icc = WebpTestSupport.scratch("container.icc", "fake icc profile".getBytes(StandardCharsets.US_ASCII));
            var xmp = WebpTestSupport.scratch("container.xmp", "<x:xmpmeta/>".getBytes(StandardCharsets.US_ASCII));
            var old = WebpTestSupport.scratch("container-old.exif", exifWithOrientation(8));
            var withIcc = WebpTestSupport.scratchPath("container-icc.webp");
            var withXmp = WebpTestSupport.scratchPath("container-icc-xmp.webp");
            var withAll = WebpTestSupport.scratchPath("container-all.webp");
            WebpTestSupport.runTool("webpmux", List.of("-set", "icc", icc.toString(), source.toString(), "-o", withIcc.toString()));
            WebpTestSupport.runTool("webpmux", List.of("-set", "xmp", xmp.toString(), withIcc.toString(), "-o", withXmp.toString()));
            WebpTestSupport.runTool("webpmux", List.of("-set", "exif", old.toString(), withXmp.toString(), "-o", withAll.toString()));
            var original = WebpTestSupport.readFile(withAll);
            assertArrayEquals(exifWithOrientation(8), WebpContainer.exif(original).orElseThrow());
            var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0};
            var tagged = WebpContainer.withExif(original, exif);
            WebpStructure.validate(tagged);
            assertEquals(List.of("VP8X", "ICCP", "VP8L", "EXIF", "XMP "), tags(tagged));
            assertEquals(0x20 | 0x10 | 0x08 | 0x04, tagged[20] & 0xFF, "VP8X flags");
            assertArrayEquals(exif, WebpContainer.exif(tagged).orElseThrow());
            var taggedPath = WebpTestSupport.scratch("container-all-new.webp", tagged);
            var dumped = WebpTestSupport.scratchPath("container-all-new.exif");
            WebpTestSupport.runTool("webpmux", List.of("-get", "exif", taggedPath.toString(), "-o", dumped.toString()));
            assertArrayEquals(exif, WebpTestSupport.readFile(dumped));
            assertWebpinfoClean(tagged, "container-all-info.webp");
            var decoded = WebpDecoder.decode(MediaSource.of(tagged), ImageDecoderOptions.defaults());
            assertArrayEquals("fake icc profile".getBytes(StandardCharsets.US_ASCII), decoded.iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("removes the EXIF chunk and clears the flag for a null payload")
        void remove() {
            var tagged = WebpContainer.withExif(losslessStill(true), exifWithOrientation(6));
            var cleared = WebpContainer.removeExif(tagged);
            WebpStructure.validate(cleared);
            assertEquals(List.of("VP8X", "VP8L"), tags(cleared));
            assertEquals(0x10, cleared[20] & 0xFF);
            assertTrue(WebpContainer.exif(cleared).isEmpty());
        }

        @Test
        @DisplayName("places EXIF after the frames of an animation and keeps it animated")
        void animated() {
            var file = animation();
            var tagged = WebpContainer.withExif(file, exifWithOrientation(1));
            WebpStructure.validate(tagged);
            var tags = tags(tagged);
            assertEquals(List.of("VP8X", "ANIM", "ANMF", "ANMF", "ANMF", "EXIF"), tags);
            assertTrue(WebpContainer.isAnimated(tagged));
            assertEquals(WebpAnimationTest.decodeAll(file).size(), WebpAnimationTest.decodeAll(tagged).size());
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(WebpAnimationTest.decodeAll(file).get(i), WebpAnimationTest.decodeAll(tagged).get(i));
            }
            var path = WebpTestSupport.scratch("container-anim-exif.webp", tagged);
            var info = WebpTestSupport.runTool("webpmux", List.of("-info", path.toString()));
            assertTrue(info.contains("Number of frames: 3"), info);
            assertTrue(info.contains("EXIF"), info);
            assertWebpinfoClean(tagged, "container-anim-info.webp");
        }

        @Test
        @DisplayName("pads odd payloads and moves unknown chunks to the end")
        void oddAndUnknown() {
            var file = losslessStill(false);
            var body = Arrays.copyOfRange(file, 12, file.length);
            var unknown = new byte[]{'Z', 'Z', 'Z', 'Z', 1, 0, 0, 0, 7, 0};
            var withUnknown = riff(concat(body, unknown));
            var tagged = WebpContainer.withExif(withUnknown, new byte[]{1, 2, 3});
            assertEquals(List.of("VP8X", "VP8L", "EXIF", "ZZZZ"), tags(tagged));
            var exif = WebpContainer.parse(tagged).get(2);
            assertEquals(3, exif.size());
            assertEquals(0, tagged[exif.payloadOffset() + 3], "padding byte");
            assertArrayEquals(new byte[]{1, 2, 3}, WebpContainer.exif(tagged).orElseThrow());
        }

        @Test
        @DisplayName("feeds the EXIF orientation to probe and decode")
        void orientation() {
            var tagged = WebpContainer.withExif(losslessStill(false), exifWithOrientation(6));
            var info = WebpDecoder.probe(MediaSource.of(tagged));
            assertEquals(Orientation.ROTATE_90, info.orientation());
            assertEquals(17, info.displayWidth());
            var decoded = WebpDecoder.decode(MediaSource.of(tagged), ImageDecoderOptions.defaults());
            assertEquals(Orientation.ROTATE_90, decoded.orientation());
            assertArrayEquals(exifWithOrientation(6), decoded.exif().orElseThrow());
        }

        @Test
        @DisplayName("rejects files without image data or with a second VP8X")
        void invalid() {
            var noImage = riff(new byte[]{'V', 'P', '8', 'X', 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.withExif(noImage, new byte[4]));
            var unknownFirst = riff(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 0});
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.withExif(unknownFirst, new byte[4]));
            var tagged = WebpContainer.withExif(losslessStill(false), new byte[2]);
            var vp8x = Arrays.copyOfRange(tagged, 12, 30);
            var doubled = riff(concat(vp8x, Arrays.copyOfRange(tagged, 12, tagged.length)));
            assertThrows(MediaException.Malformed.class, () -> WebpContainer.withExif(doubled, new byte[4]));
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        var out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Nested
    @DisplayName("queries")
    class Queries {
        @Test
        @DisplayName("exif() returns null without an EXIF chunk")
        void noExif() {
            assertTrue(WebpContainer.exif(losslessStill(true)).isEmpty());
        }

        @Test
        @DisplayName("isAnimated() reads the VP8X animation flag and rejects anything else")
        void animatedFlag() {
            assertTrue(WebpContainer.isAnimated(animation()));
            assertFalse(WebpContainer.isAnimated(losslessStill(true)));
            assertFalse(WebpContainer.isAnimated(WebpContainer.withExif(losslessStill(true), new byte[2])));
            assertFalse(WebpContainer.isAnimated(new byte[3]));
            assertFalse(WebpContainer.isAnimated(new byte[64]));
        }

        @Test
        @DisplayName("parse() locates the end of the first ANMF chunk, which an EXIF edit keeps")
        void firstFrameEnd() {
            var file = animation();
            var chunks = WebpStructure.validate(file);
            var firstAnmf = chunks.stream().filter(chunk -> chunk.tag().equals("ANMF")).findFirst().orElseThrow();
            var expected = firstAnmf.offset() + 8 + firstAnmf.size() + (firstAnmf.size() & 1);
            assertEquals(expected, firstFrameEnd(file));
            assertEquals(-1, firstFrameEnd(losslessStill(true)));
            assertEquals(firstFrameEnd(file), firstFrameEnd(WebpContainer.withExif(file, new byte[5])));
        }

        private static int firstFrameEnd(byte[] file) {
            return WebpContainer.parse(file).stream()
                    .filter(chunk -> chunk.fourCc().equals("ANMF"))
                    .mapToInt(WebpContainer.Chunk::end)
                    .findFirst()
                    .orElse(-1);
        }
    }

    @Nested
    @DisplayName("Optional accessors and remove editors")
    class OptionalAndRemove {
        @Test
        @DisplayName("exif, xmp and iccProfile return the payloads, or empty without them")
        void find() {
            var file = losslessStill(false);
            assertTrue(WebpContainer.exif(file).isEmpty());
            assertTrue(WebpContainer.xmp(file).isEmpty());
            assertTrue(WebpContainer.iccProfile(file).isEmpty());
            var exif = exifWithOrientation(6);
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
            var icc = new byte[]{1, 2, 3, 4};
            var tagged = WebpContainer.withIccProfile(WebpContainer.withXmp(WebpContainer.withExif(file, exif), xmp), icc);
            assertArrayEquals(exif, WebpContainer.exif(tagged).orElseThrow());
            assertArrayEquals(xmp, WebpContainer.xmp(tagged).orElseThrow());
            assertArrayEquals(icc, WebpContainer.iccProfile(tagged).orElseThrow());
        }

        @Test
        @DisplayName("removeExif, removeXmp and removeIccProfile remove one chunk each; the editors reject null")
        void remove() {
            var file = losslessStill(true);
            var tagged = WebpContainer.withIccProfile(WebpContainer.withXmp(WebpContainer.withExif(file,
                    exifWithOrientation(3)), new byte[]{'x'}), new byte[]{9, 9});
            var noExif = WebpContainer.removeExif(tagged);
            assertTrue(WebpContainer.exif(noExif).isEmpty());
            assertTrue(WebpContainer.xmp(noExif).isPresent());
            assertTrue(WebpContainer.iccProfile(noExif).isPresent());
            assertThrows(NullPointerException.class, () -> WebpContainer.withExif(tagged, null));
            assertThrows(NullPointerException.class, () -> WebpContainer.withXmp(tagged, null));
            assertThrows(NullPointerException.class, () -> WebpContainer.withIccProfile(tagged, null));
            var bare = WebpContainer.removeIccProfile(WebpContainer.removeXmp(WebpContainer.removeExif(tagged)));
            assertTrue(WebpContainer.exif(bare).isEmpty());
            assertTrue(WebpContainer.xmp(bare).isEmpty());
            assertTrue(WebpContainer.iccProfile(bare).isEmpty());
        }
    }
}
