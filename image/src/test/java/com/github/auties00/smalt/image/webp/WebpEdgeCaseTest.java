package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the container and bitstream edge cases: minimal and maximal dimensions, odd sizes,
 * chunk padding, unknown and trailing data, truncation, canvas mismatches, frames outside the
 * canvas, reserved bits and resource limits, on files crafted chunk by chunk.
 */
class WebpEdgeCaseTest {
    static byte[] chunk(String tag, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(tag.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(le32(payload.length));
        out.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    static byte[] le32(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    static byte[] le24(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16)};
    }

    static byte[] riff(byte[]... chunks) {
        var body = new ByteArrayOutputStream();
        for (var chunk : chunks) {
            body.writeBytes(chunk);
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(le32(body.size() + 4));
        out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static byte[] vp8x(int flags, int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(le32(flags));
        out.writeBytes(le24(width - 1));
        out.writeBytes(le24(height - 1));
        return chunk("VP8X", out.toByteArray());
    }

    static byte[] anim(int background, int loops) {
        var payload = Arrays.copyOf(le32(background), 6);
        payload[4] = (byte) loops;
        payload[5] = (byte) (loops >> 8);
        return chunk("ANIM", payload);
    }

    static byte[] anmf(int x, int y, int width, int height, int duration, int flags, byte[] image) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(le24(x / 2));
        out.writeBytes(le24(y / 2));
        out.writeBytes(le24(width - 1));
        out.writeBytes(le24(height - 1));
        out.writeBytes(le24(duration));
        out.write(flags);
        out.writeBytes(image);
        return chunk("ANMF", out.toByteArray());
    }

    /** Returns the VP8L chunk (header included) of a lossless encode of solid or random pixels. */
    static byte[] vp8lChunk(int width, int height, boolean alpha, long seed) {
        var rgba = new byte[width * height * 4];
        new Random(seed).nextBytes(rgba);
        var file = WebpEncoder.encode(RgbaPicture.of(width, height, width * 4, rgba, alpha),
                WebpEncoderOptions.lossless().toBuilder().exact(true).build());
        return Arrays.copyOfRange(file, 12, file.length);
    }

    static RgbaPicture decode(byte[] file) {
        return (RgbaPicture) WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture();
    }

    @Nested
    @DisplayName("dimensions")
    class Dimensions {
        @ParameterizedTest(name = "alpha={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("decode 1x1 images in simple and extended containers")
        void onePixel(boolean alpha) {
            var image = vp8lChunk(1, 1, alpha, 1);
            var simple = riff(image);
            var extended = riff(vp8x(alpha ? 0x10 : 0, 1, 1), image);
            assertArrayEquals(WebpTestSupport.tightRgba(decode(simple)), WebpTestSupport.tightRgba(decode(extended)));
            var info = WebpDecoder.probe(MediaSource.of(extended));
            assertEquals(1, info.width());
            assertEquals(ImageFormat.WEBP, info.format());
        }

        @Test
        @DisplayName("probe a 16383x16383 canvas but refuse to decode it under the default pixel limit")
        void maximal() {
            var header = new byte[]{0x2F, (byte) 0xFE, (byte) 0xBF, (byte) 0xFF, 0x0F, 0, 0, 0, 0, 0};
            var file = riff(vp8x(0, 16383, 16383), chunk("VP8L", header));
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertEquals(16383, info.width());
            assertEquals(16383, info.height());
            assertThrows(MediaException.LimitExceeded.class, () -> WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("enforce caller limits before allocating")
        void callerLimits() {
            var file = riff(vp8lChunk(40, 30, false, 2));
            var limits = MediaLimits.builder().maxDimension(32).maxPixels(1L << 20).maxFrames(10).maxChannels(4)
                    .maxBytes(1L << 20).build();
            var options = ImageDecoderOptions.builder().limits(limits).build();
            assertThrows(MediaException.LimitExceeded.class, () -> WebpDecoder.decode(MediaSource.of(file), options));
            var pixels = MediaLimits.builder().maxDimension(1000).maxPixels(1000).maxFrames(10).maxChannels(4)
                    .maxBytes(1L << 20).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().limits(pixels)
                            .build()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"3x1", "1x3", "5x7", "129x2", "2x129"})
        @DisplayName("round-trip odd sizes")
        void odd(String size) {
            var parts = size.split("x");
            var width = Integer.parseInt(parts[0]);
            var height = Integer.parseInt(parts[1]);
            var image = vp8lChunk(width, height, true, width * 7L + height);
            var decoded = decode(riff(image));
            assertEquals(width, decoded.width());
            assertEquals(height, decoded.height());
        }

        @Test
        @DisplayName("reject a VP8X canvas that disagrees with the image")
        void canvasMismatch() {
            var file = riff(vp8x(0, 5, 4), vp8lChunk(4, 4, false, 3));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.probe(MediaSource.of(file)));
        }
    }

    @Nested
    @DisplayName("container")
    class Container {
        @Test
        @DisplayName("skip unknown chunks and odd-sized padded metadata before and after the image")
        void unknownAndPadding() {
            var image = vp8lChunk(6, 5, true, 4);
            var file = riff(vp8x(0x10 | 0x20 | 0x04, 6, 5), chunk("ICCP", new byte[]{1, 2, 3}), chunk("ZZZZ", new byte[5]),
                    image, chunk("XMP ", new byte[]{'<', 'x', '/', '>', ' '}), chunk("QQQQ", new byte[1]));
            var expected = WebpTestSupport.tightRgba(decode(riff(image)));
            var decoded = WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
            assertArrayEquals(expected, WebpTestSupport.tightRgba((RgbaPicture) decoded.picture()));
            assertArrayEquals(new byte[]{1, 2, 3}, decoded.iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("tolerate trailing bytes after the RIFF payload")
        void trailing() {
            var file = riff(vp8lChunk(6, 5, true, 5));
            var padded = Arrays.copyOf(file, file.length + 100);
            Arrays.fill(padded, file.length, padded.length, (byte) 0x55);
            assertArrayEquals(WebpTestSupport.tightRgba(decode(file)), WebpTestSupport.tightRgba(decode(padded)));
        }

        @Test
        @DisplayName("reject every truncation of a still file")
        void truncation() {
            var file = riff(vp8x(0x10, 16, 9), vp8lChunk(16, 9, true, 6));
            for (var length = 0; length < file.length; length++) {
                var truncated = Arrays.copyOf(file, length);
                assertThrows(MediaException.class, () -> WebpDecoder.decode(MediaSource.of(truncated), ImageDecoderOptions.defaults()),
                        "length " + length);
            }
        }

        @Test
        @DisplayName("reject a bitstream cut short inside a consistent container")
        void shortBitstream() {
            var image = vp8lChunk(32, 32, true, 7);
            var payload = Arrays.copyOfRange(image, 8, 8 + (image.length - 8) / 2);
            var file = riff(chunk("VP8L", payload));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("reject bad signatures, versions and first chunks")
        void headers() {
            var image = vp8lChunk(4, 4, false, 8);
            var badSignature = image.clone();
            badSignature[8] = 0x2E;
            assertThrows(MediaException.Malformed.class, () -> decode(riff(badSignature)));
            var badVersion = image.clone();
            badVersion[12] |= (byte) 0x20;
            assertThrows(MediaException.Malformed.class, () -> decode(riff(badVersion)));
            assertThrows(MediaException.Malformed.class, () -> decode(riff(chunk("ICCP", new byte[2]), image)));
            assertThrows(MediaException.Malformed.class, () -> decode(riff(chunk("VP8X", new byte[8]), image)));
            assertThrows(MediaException.Malformed.class, () -> decode(riff(vp8x(0, 4, 4))));
            assertThrows(MediaException.Malformed.class, () -> decode("RIFX\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1)));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.probe(MediaSource.of(new byte[0])));
        }
    }

    @Nested
    @DisplayName("animation")
    class Animation {
        @Test
        @DisplayName("composite frames at even offsets with blending and disposal")
        void offsets() {
            var red = solid(4, 2, 0xFF0000FF);
            var file = riff(vp8x(0x02 | 0x10, 10, 6), anim(0, 0),
                    anmf(0, 0, 10, 6, 40, 0x02, solid(10, 6, 0x000000FF)),
                    anmf(6, 4, 4, 2, 40, 0x01, red),
                    anmf(0, 0, 2, 2, 0, 0x02, solid(2, 2, 0x00FF0080)));
            var frames = WebpAnimationTest.decodeAll(file);
            assertEquals(3, frames.size());
            var second = frames.get(1);
            assertEquals((byte) 0xFF, second[(4 * 10 + 6) * 4], "red frame placed at (6, 4)");
            assertEquals((byte) 0x00, second[(4 * 10 + 5) * 4], "pixel left of the frame keeps the first frame");
            var third = frames.get(2);
            assertEquals(0, third[(4 * 10 + 6) * 4 + 3], "disposed frame area cleared to transparent");
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertTrue(info.animated());
            assertEquals(3, info.frameCount());
        }

        @Test
        @DisplayName("reject frames extending past the canvas")
        void outsideCanvas() {
            var file = riff(vp8x(0x02, 10, 6), anim(0, 0), anmf(8, 0, 4, 2, 40, 0, solid(4, 2, 0xFF0000FF)));
            assertThrows(MediaException.Malformed.class, () -> WebpAnimationTest.decodeAll(file));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.probe(MediaSource.of(file)));
        }

        @Test
        @DisplayName("reject animations without ANIM or without frames")
        void incomplete() {
            var noAnim = riff(vp8x(0x02, 4, 2), anmf(0, 0, 4, 2, 40, 0, solid(4, 2, 0xFF0000FF)));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.probe(MediaSource.of(noAnim)));
            var noFrames = riff(vp8x(0x02, 4, 2), anim(0, 0));
            assertThrows(MediaException.Malformed.class, () -> WebpDecoder.probe(MediaSource.of(noFrames)));
        }

        @Test
        @DisplayName("treat a one-frame animation as a still image in probe and decode it")
        void singleFrame() {
            var file = riff(vp8x(0x02, 4, 2), anim(0, 0), anmf(0, 0, 4, 2, 40, 0, solid(4, 2, 0xFF0000FF)));
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            var decoded = decode(file);
            assertEquals((byte) 0xFF, decoded.pixels()[0]);
        }

        @Test
        @DisplayName("enforce the frame-count limit")
        void frameLimit() {
            var frame = anmf(0, 0, 4, 2, 10, 0, solid(4, 2, 0xFF0000FF));
            var file = riff(vp8x(0x02, 4, 2), anim(0, 0), frame, frame, frame);
            var limits = MediaLimits.builder().maxDimension(16384).maxPixels(1L << 20).maxFrames(2).maxChannels(4)
                    .maxBytes(1L << 20).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.builder().limits(limits)
                            .build()).close());
        }
    }

    /** Returns the VP8L chunk of a solid-color image, RGBA given as {@code 0xRRGGBBAA}. */
    static byte[] solid(int width, int height, int rgba) {
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) (rgba >>> 24);
            pixels[i + 1] = (byte) (rgba >>> 16);
            pixels[i + 2] = (byte) (rgba >>> 8);
            pixels[i + 3] = (byte) rgba;
        }
        var file = WebpEncoder.encode(RgbaPicture.of(width, height, width * 4, pixels, true),
                WebpEncoderOptions.lossless().toBuilder().exact(true).build());
        return Arrays.copyOfRange(file, 12, file.length);
    }
}
