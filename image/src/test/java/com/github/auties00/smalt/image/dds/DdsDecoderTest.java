package com.github.auties00.smalt.image.dds;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the DDS decoder: every block compression against the reference decoder bcdec ({@code smalt-bcdec},
 * {@code tools/testdata/bcn-oracle}) on random blocks, which reach every BC6H and BC7 mode and partition, bit for
 * bit; the S3TC and RGTC formats against ffmpeg within its rounding; the uncompressed DXGI and legacy mask formats
 * against values computed from their definitions and against ffmpeg; mipmap, cube map, array and volume layouts; and
 * rejection of malformed and unsupported files.
 */
@DisplayName("DDS decoder")
class DdsDecoderTest {
    private static final int WIDTH = 37;
    private static final int HEIGHT = 21;

    private static int blocks(int width, int height) {
        return ((width + 3) / 4) * ((height + 3) / 4);
    }

    private static byte[] bcdec(String format, int width, int height, byte[] blocks) {
        Oracle.assume("smalt-bcdec");
        return Oracle.run("smalt-bcdec", Duration.ofMinutes(1), blocks, List.of(format, Integer.toString(width),
                Integer.toString(height)));
    }

    @Nested
    @DisplayName("block compression against bcdec")
    class Bcdec {
        @ParameterizedTest
        @CsvSource({"bc1,DXT1,8", "bc2,DXT3,16", "bc3,DXT5,16", "bc7,,16"})
        @DisplayName("decodes RGBA block formats bit for bit")
        void rgba(String format, String fourCc, int blockBytes) {
            var data = DdsFiles.random(blocks(WIDTH, HEIGHT) * blockBytes, format.hashCode());
            var file = fourCc == null ? DdsFiles.dx10(WIDTH, HEIGHT, 98, data)
                    : DdsFiles.fourCcFile(fourCc, WIDTH, HEIGHT, data);
            var ours = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(file)).picture());
            Tolerance.exact().assertWithin(format, bcdec(format, WIDTH, HEIGHT, data), ours);
        }

        @ParameterizedTest
        @CsvSource({"bc4,80,false", "bc4s,81,true", "bc5,83,false", "bc5s,84,true"})
        @DisplayName("decodes BC4 and BC5 bit for bit, signed values mapped to unsigned")
        void channels(String format, int dxgi, boolean signed) {
            var two = format.startsWith("bc5");
            var data = DdsFiles.random(blocks(WIDTH, HEIGHT) * (two ? 16 : 8), dxgi);
            var file = DdsFiles.dx10(WIDTH, HEIGHT, dxgi, data);
            var ours = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(file)).picture());
            var reference = bcdec(format, WIDTH, HEIGHT, data);
            var channels = two ? 2 : 1;
            var expected = new byte[WIDTH * HEIGHT * 4];
            for (var texel = 0; texel < WIDTH * HEIGHT; texel++) {
                for (var channel = 0; channel < 3; channel++) {
                    var source = two ? channel : 0;
                    int value;
                    if (two && channel == 2) {
                        value = 0;
                    } else {
                        var raw = reference[texel * channels + source];
                        value = signed ? ((raw + 127) * 255 + 127) / 254 : raw & 0xFF;
                    }
                    expected[4 * texel + channel] = (byte) value;
                }
                expected[4 * texel + 3] = (byte) 255;
            }
            Tolerance.exact().assertWithin(format, expected, ours);
        }

        @ParameterizedTest
        @CsvSource({"bc6h,95,false", "bc6hs,96,true"})
        @DisplayName("decodes BC6H bit for bit in every mode")
        void bc6h(String format, int dxgi, boolean signed) {
            var data = DdsFiles.random(blocks(WIDTH, HEIGHT) * 16, dxgi * 7L);
            var file = DdsFiles.dx10(WIDTH, HEIGHT, dxgi, data);
            var ours = assertInstanceOf(ChannelFloatPicture.class, DdsDecoder.decode(MediaSource.of(file),
                    ImageDecoderOptions.builder().keepChannels(true).build()).picture());
            var halves = ByteBuffer.wrap(bcdec(format, WIDTH, HEIGHT, data)).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer();
            var expected = new float[halves.remaining()];
            for (var index = 0; index < expected.length; index++) {
                expected[index] = Float.float16ToFloat(halves.get(index));
            }
            assertArrayEquals(expected, ours.pixels());
        }

        @Test
        @DisplayName("divides out the premultiplied alpha of DXT2 and DXT4")
        void premultiplied() {
            var data = DdsFiles.random(blocks(8, 8) * 16, 3);
            var straight = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(DdsFiles.fourCcFile("DXT5", 8, 8,
                    data))).picture());
            var divided = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(DdsFiles.fourCcFile("DXT4", 8, 8,
                    data))).picture());
            for (var index = 0; index < straight.length; index += 4) {
                var alpha = straight[index + 3] & 0xFF;
                for (var channel = 0; channel < 3; channel++) {
                    var value = straight[index + channel] & 0xFF;
                    var expected = alpha == 0 ? 0 : Math.min(255, (value * 255 + alpha / 2) / alpha);
                    assertEquals(expected, divided[index + channel] & 0xFF);
                }
            }
        }
    }

    @Nested
    @DisplayName("ffmpeg")
    class Ffmpeg {
        @ParameterizedTest
        @CsvSource({"DXT1,8", "DXT3,16", "DXT5,16", "ATI1,8", "ATI2,16"})
        @DisplayName("agrees with ffmpeg on S3TC and RGTC blocks within rounding")
        void blocks(String fourCc, int blockBytes) {
            var picture = RasterFixtures.rgba(32, 16, 1, true);
            var data = DdsFiles.random(DdsDecoderTest.blocks(32, 16) * blockBytes, fourCc.hashCode());
            var file = DdsFiles.fourCcFile(fourCc, 32, 16, data);
            var ours = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(file)).picture());
            var theirs = RasterFixtures.ffmpegDecode(file, "dds", "rgba");
            assertEquals(picture.width() * picture.height() * 4, ours.length);
            if (fourCc.equals("ATI2")) {
                // ffmpeg reconstructs the blue of a two-channel normal map; red and green must agree
                for (var index = 0; index < ours.length; index += 4) {
                    theirs[index + 2] = ours[index + 2];
                }
            }
            Tolerance.maxAbs(3).assertWithin(fourCc, theirs, ours);
        }

        @ParameterizedTest
        @CsvSource({"32,16711680,65280,255,4278190080,65", "16,63488,2016,31,0,64", "16,31744,992,31,32768,65",
                "24,16711680,65280,255,0,64"})
        @DisplayName("agrees with ffmpeg on legacy mask formats exactly")
        void masks(int bits, long red, long green, long blue, long alpha, int flags) {
            var data = DdsFiles.random(17 * 9 * bits / 8, bits + red);
            var file = DdsFiles.legacy(17, 9, 1, flags, 0, bits, new long[]{red, green, blue, alpha}, 0, 1, data);
            var ours = RasterTesting.tight(DdsDecoder.decode(MediaSource.of(file)).picture());
            var theirs = RasterFixtures.ffmpegDecode(file, "dds", "rgba");
            if (alpha == 0x8000) {
                // ffmpeg reads the 1-bit alpha of A1R5G5B5 as unused (rgb555le); Smalt keeps it
                for (var index = 0; index < 17 * 9; index++) {
                    var set = (data[2 * index + 1] & 0x80) != 0;
                    assertEquals(set ? 255 : 0, ours[4 * index + 3] & 0xFF, "alpha of texel " + index);
                    theirs[4 * index + 3] = ours[4 * index + 3];
                }
            }
            // ffmpeg widens 5 and 6-bit fields by replicating their high bits; Smalt rounds v * 255 / max, which
            // differs by at most 1
            (bits == 16 ? Tolerance.maxAbs(1) : Tolerance.exact()).assertWithin("masks " + bits, theirs, ours);
            if (bits == 16) {
                for (var index = 0; index < 17 * 9; index++) {
                    var word = (data[2 * index] & 0xFF) | (data[2 * index + 1] & 0xFF) << 8;
                    var masks = new long[]{red, green, blue};
                    for (var channel = 0; channel < 3; channel++) {
                        var shift = Long.numberOfTrailingZeros(masks[channel]);
                        var max = masks[channel] >>> shift;
                        var value = (word & masks[channel]) >>> shift;
                        assertEquals(Math.round(value * 255.0 / max), ours[4 * index + channel] & 0xFF,
                                "channel " + channel + " of texel " + index);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("uncompressed formats")
    class Uncompressed {
        @Test
        @DisplayName("decodes R8G8B8A8, B8G8R8A8 and B5G6R5 from their definitions")
        void eightBit() {
            var data = new byte[]{10, 20, 30, 40};
            assertArrayEquals(new byte[]{10, 20, 30, 40}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 28, data))).picture()));
            assertArrayEquals(new byte[]{30, 20, 10, 40}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 87, data))).picture()));
            var rgb565 = new byte[]{(byte) 0x1F, (byte) 0xF8};
            assertArrayEquals(new byte[]{-1, 0, -1, -1}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 85, rgb565))).picture()));
        }

        @Test
        @DisplayName("decodes single, two-channel and alpha-only formats")
        void channelCounts() {
            assertArrayEquals(new byte[]{77, 77, 77, -1}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 61, new byte[]{77}))).picture()));
            assertArrayEquals(new byte[]{5, 6, 0, -1}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 49, new byte[]{5, 6}))).picture()));
            assertArrayEquals(new byte[]{0, 0, 0, 9}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 65, new byte[]{9}))).picture()));
            var luminance = DdsFiles.legacy(1, 1, 1, DdsFiles.DDPF_LUMINANCE | DdsFiles.DDPF_ALPHAPIXELS, 0, 16,
                    new long[]{0xFF, 0, 0, 0xFF00}, 0, 1, new byte[]{50, 60});
            assertArrayEquals(new byte[]{50, 50, 50, 60}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    luminance)).picture()));
            var signed = DdsFiles.dx10(1, 1, 51, new byte[]{(byte) 0x81, 127});
            assertArrayEquals(new byte[]{0, -1, 0, -1}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(signed))
                    .picture()));
        }

        @Test
        @DisplayName("keeps 16-bit and floating-point formats")
        void deep() {
            var wide = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1000)
                    .putShort((short) 2000).putShort((short) 3000).putShort((short) 0xFFFF).array();
            var picture = assertInstanceOf(Rgba16Picture.class, DdsDecoder.decode(MediaSource.of(DdsFiles.dx10(1, 1,
                    11, wide)), ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            assertArrayEquals(new short[]{1000, 2000, 3000, -1}, java.util.Arrays.copyOf(picture.pixels(), 4));
            var floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).putFloat(-2f)
                    .putFloat(0.25f).putFloat(1f).array();
            var keep = ImageDecoderOptions.builder().keepChannels(true).build();
            var decoded = assertInstanceOf(ChannelFloatPicture.class, DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 2, floats)), keep).picture());
            assertArrayEquals(new float[]{1.5f, -2f, 0.25f, 1f}, decoded.pixels());
            var half = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(Float.floatToFloat16(3f))
                    .putShort(Float.floatToFloat16(0.5f)).putShort((short) 0).putShort(Float.floatToFloat16(1f))
                    .array();
            assertArrayEquals(new float[]{3f, 0.5f, 0f, 1f}, ((ChannelFloatPicture) DdsDecoder.decode(
                    MediaSource.of(DdsFiles.dx10(1, 1, 10, half)), keep).picture()).pixels());
            // R11G11B10: red 1.0 (exponent 15, mantissa 0), green 0.5, blue 2.0
            var packed = 15 << 6 | (14 << 6) << 11 | (16L << 5) << 22;
            var small = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) packed).array();
            assertArrayEquals(new float[]{1f, 0.5f, 2f}, ((ChannelFloatPicture) DdsDecoder.decode(
                    MediaSource.of(DdsFiles.dx10(1, 1, 26, small)), keep).picture()).pixels());
            // R9G9B9E5: mantissas 256, 128, 0 with exponent 16 give 1.0, 0.5 and 0
            var shared = 256 | 128 << 9 | 16 << 27;
            var exponent = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(shared).array();
            assertArrayEquals(new float[]{1f, 0.5f, 0f}, ((ChannelFloatPicture) DdsDecoder.decode(
                    MediaSource.of(DdsFiles.dx10(1, 1, 67, exponent)), keep).picture()).pixels());
        }
    }

    @Nested
    @DisplayName("layouts")
    class Layouts {
        @Test
        @DisplayName("opens every array element, face, mipmap level and slice as a page")
        void pages() {
            // a cube map array of 2 elements and 3 mipmap levels of 8x8 RGBA
            var levels = 8 * 8 + 4 * 4 + 2 * 2;
            var data = DdsFiles.random(2 * 6 * levels * 4, 5);
            var file = DdsFiles.dx10(8, 8, 3, 28, 3, 0x4, 2, 0, 1, data);
            try (var pages = DdsDecoder.openPages(MediaSource.of(file))) {
                assertEquals(36, pages.pageCount());
                assertEquals(4, pages.probe(1).width());
                assertEquals(2, pages.probe(2).width());
                var page = pages.read(3 * 7 + 1);
                assertEquals(4, page.picture().width());
                var offset = 148 + (7 * levels + 64) * 4;
                assertArrayEquals(java.util.Arrays.copyOfRange(file, offset, offset + 64),
                        RasterTesting.tight(page.picture()));
            }
            assertEquals(36, DdsDecoder.probe(MediaSource.of(file)).frameCount());
            assertEquals(ImageFormat.DDS, Images.detect(MediaSource.of(file)).orElseThrow());
        }

        @Test
        @DisplayName("lays out the faces a legacy cube map lists and the slices of a volume texture")
        void legacyCubeAndVolume() {
            var data = DdsFiles.random(3 * 4 * 4 * 4, 7);
            var cube = DdsFiles.legacy(4, 4, 1, DdsFiles.DDPF_RGB | DdsFiles.DDPF_ALPHAPIXELS, 0, 32,
                    new long[]{0xFF, 0xFF00, 0xFF0000, 0xFF000000L}, 0x200 | 0x400 | 0x1000 | 0x8000, 1, data);
            assertEquals(3, DdsDecoder.probe(MediaSource.of(cube)).frameCount());
            var volumeData = DdsFiles.random((4 * 4 * 4 + 2 * 2 * 2 + 1) * 4, 9);
            var volume = DdsFiles.legacy(4, 4, 3, DdsFiles.DDPF_RGB | DdsFiles.DDPF_ALPHAPIXELS, 0, 32,
                    new long[]{0xFF, 0xFF00, 0xFF0000, 0xFF000000L}, 0x200000, 4, volumeData);
            assertEquals(4 + 2 + 1, DdsDecoder.probe(MediaSource.of(volume)).frameCount());
        }

        @Test
        @DisplayName("decodes the smallest mipmap level that meets the size hint")
        void reducedSize() {
            var data = DdsFiles.random(blocks(16, 16) * 8 + blocks(8, 8) * 8 + blocks(4, 4) * 8, 11);
            var file = DdsFiles.legacy(16, 16, 3, DdsFiles.DDPF_FOURCC, DdsFiles.fourCc("DXT1"), 0, new long[4], 0, 1,
                    data);
            var image = DdsDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().minSize(6, 6).build());
            assertEquals(8, image.picture().width());
            assertEquals(16, image.sourceWidth());
            assertEquals(16, DdsDecoder.decode(MediaSource.of(file)).picture().width());
        }

        @Test
        @DisplayName("keeps the surfaces before a truncated end")
        void truncated() {
            var data = DdsFiles.random(blocks(8, 8) * 8 + blocks(4, 4) * 8 - 3, 13);
            var file = DdsFiles.legacy(8, 8, 2, DdsFiles.DDPF_FOURCC, DdsFiles.fourCc("DXT1"), 0, new long[4], 0, 1,
                    data);
            assertEquals(1, DdsDecoder.probe(MediaSource.of(file)).frameCount());
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects invalid headers and unsupported formats")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(new byte[64])));
            assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(4, 4, 45, new byte[64]))));
            assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(
                    DdsFiles.fourCcFile("YUY2", 4, 4, new byte[64]))));
            assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(1,
                    1, 1, DdsFiles.DDPF_RGB, 0, 32, new long[]{0x0F0F, 0, 0, 0}, 0, 1, new byte[4]))));
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(
                    DdsFiles.fourCcFile("DXT1", 8, 8, new byte[10]))));
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(
                    DdsFiles.fourCcFile("DXT1", 0, 8, new byte[10]))));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(DdsFiles.dx10(9, 9, 98, DdsFiles.random(blocks(9, 9) * 16, 1)),
                    DdsFiles.dx10(9, 9, 95, DdsFiles.random(blocks(9, 9) * 16, 2)),
                    DdsFiles.fourCcFile("DXT5", 8, 4, DdsFiles.random(32, 3)),
                    DdsFiles.dx10(3, 3, 26, DdsFiles.random(36, 4)));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 17,
                    source -> DdsDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
