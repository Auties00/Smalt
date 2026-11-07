package com.github.auties00.smalt.image.tga;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TGA codec: hand-built files of every image type, depth, origin and color map layout, run-length packets
 * crossing scanlines, the extension area's attributes type, Smalt's encoder in every mode, ffmpeg reading Smalt's
 * files and Smalt reading ffmpeg's, the recognition heuristic and rejection of malformed files.
 */
@DisplayName("TGA codec")
class TgaCodecTest {
    static byte[] header(int idLength, int mapType, int type, int mapFirst, int mapLength, int mapBits, int width,
                         int height, int depth, int descriptor) {
        return new byte[]{(byte) idLength, (byte) mapType, (byte) type, (byte) mapFirst, (byte) (mapFirst >>> 8),
                (byte) mapLength, (byte) (mapLength >>> 8), (byte) mapBits, 0, 0, 0, 0, (byte) width,
                (byte) (width >>> 8), (byte) height, (byte) (height >>> 8), (byte) depth, (byte) descriptor};
    }

    static byte[] concat(byte[]... parts) {
        return RasterTesting.concat(parts);
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @Test
        @DisplayName("reads a bottom-up 24-bit image upright")
        void bottomUp() {
            var data = concat(header(0, 0, 2, 0, 0, 0, 2, 2, 24, 0), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
            var rgba = RasterTesting.tight(TgaDecoder.decode(MediaSource.of(data)).picture());
            assertArrayEquals(new byte[]{9, 8, 7, -1, 12, 11, 10, -1, 3, 2, 1, -1, 6, 5, 4, -1}, rgba);
        }

        @Test
        @DisplayName("honors the right-to-left and top-to-bottom origin bits and skips the image ID")
        void origins() {
            var pixels = new byte[]{1, 2, 3, 4, 5, 6};
            var topRight = concat(header(3, 0, 2, 0, 0, 0, 2, 1, 24, 0x30), new byte[]{9, 9, 9}, pixels);
            assertArrayEquals(new byte[]{6, 5, 4, -1, 3, 2, 1, -1},
                    RasterTesting.tight(TgaDecoder.decode(MediaSource.of(topRight)).picture()));
        }

        @Test
        @DisplayName("reads color maps of every entry size, offset by the first entry index, and 16-bit indices")
        void colorMaps() {
            var map24 = concat(header(0, 1, 1, 2, 2, 24, 3, 1, 8, 0x20), new byte[]{10, 20, 30, 40, 50, 60},
                    new byte[]{2, 3, 7});
            assertArrayEquals(new byte[]{30, 20, 10, -1, 60, 50, 40, -1, 0, 0, 0, -1},
                    RasterTesting.tight(TgaDecoder.decode(MediaSource.of(map24)).picture()));
            var map32 = concat(header(0, 1, 1, 0, 1, 32, 1, 1, 16, 0x28), new byte[]{1, 2, 3, 4}, new byte[]{0, 0});
            assertArrayEquals(new byte[]{3, 2, 1, 4}, RasterTesting.tight(TgaDecoder.decode(MediaSource.of(map32))
                    .picture()));
            var map15 = concat(header(0, 1, 1, 0, 1, 15, 1, 1, 8, 0x20), new byte[]{(byte) 0xE0, 0x7F}, new byte[]{0});
            assertArrayEquals(new byte[]{-1, -1, 0, -1}, RasterTesting.tight(TgaDecoder.decode(MediaSource.of(map15))
                    .picture()));
        }

        @Test
        @DisplayName("expands 15 and 16-bit pixels by bit replication, the top bit giving alpha")
        void sixteenBit() {
            // red 31, green 16, blue 1, attribute bit clear then set
            var value = 31 << 10 | 16 << 5 | 1;
            var data = concat(header(0, 0, 2, 0, 0, 0, 2, 1, 16, 0x21), new byte[]{(byte) value, (byte) (value >>> 8),
                    (byte) value, (byte) ((value >>> 8) | 0x80)});
            var rgba = RasterTesting.tight(TgaDecoder.decode(MediaSource.of(data)).picture());
            assertArrayEquals(new byte[]{-1, (byte) 132, 8, 0, -1, (byte) 132, 8, -1}, rgba);
            var opaque = concat(header(0, 0, 2, 0, 0, 0, 1, 1, 16, 0x20), new byte[]{(byte) value,
                    (byte) (value >>> 8)});
            assertFalse(TgaDecoder.decode(MediaSource.of(opaque)).picture().hasAlpha());
        }

        @Test
        @DisplayName("reads gray and gray with attribute images")
        void gray() {
            var data = concat(header(0, 0, 3, 0, 0, 0, 2, 1, 16, 0x28), new byte[]{7, 100, 9, (byte) 200});
            assertArrayEquals(new byte[]{7, 7, 7, 100, 9, 9, 9, (byte) 200},
                    RasterTesting.tight(TgaDecoder.decode(MediaSource.of(data)).picture()));
        }

        @Test
        @DisplayName("reads run-length packets crossing scanlines")
        void runLength() {
            var data = concat(header(0, 0, 11, 0, 0, 0, 3, 2, 8, 0x20), new byte[]{(byte) 0x83, 5, 1, 6, 7});
            var rgba = RasterTesting.tight(TgaDecoder.decode(MediaSource.of(data)).picture());
            int[] expected = {5, 5, 5, 5, 6, 7};
            for (var index = 0; index < 6; index++) {
                assertEquals(expected[index], rgba[4 * index]);
            }
        }

        @ParameterizedTest
        @CsvSource({"0,false", "2,false", "3,true", "4,true"})
        @DisplayName("follows the extension area's attributes type")
        void attributesType(int type, boolean alpha) {
            var pixel = new byte[]{(byte) 200, 100, 50, (byte) 128};
            var body = concat(header(0, 0, 2, 0, 0, 0, 1, 1, 32, 0x28), pixel);
            var extension = new byte[495];
            extension[0] = (byte) 239;
            extension[1] = 1;
            extension[494] = (byte) type;
            var footer = new ByteArrayOutputStream();
            var offset = body.length;
            footer.writeBytes(new byte[]{(byte) offset, (byte) (offset >>> 8), 0, 0, 0, 0, 0, 0});
            footer.writeBytes("TRUEVISION-XFILE.\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var data = concat(body, extension, footer.toByteArray());
            var image = TgaDecoder.decode(MediaSource.of(data));
            assertEquals(alpha, image.picture().hasAlpha());
            var rgba = RasterTesting.tight(image.picture());
            if (type == 4) {
                assertArrayEquals(new byte[]{(byte) 100, (byte) 199, (byte) 255, (byte) 128}, rgba);
            } else {
                assertArrayEquals(new byte[]{50, 100, (byte) 200, alpha ? (byte) 128 : -1}, rgba);
            }
        }

        @Test
        @DisplayName("leaves pixels missing from a truncated stream transparent")
        void truncated() {
            var data = concat(header(0, 0, 2, 0, 0, 0, 2, 2, 24, 0x20), new byte[]{1, 2, 3, 4});
            var picture = TgaDecoder.decode(MediaSource.of(data)).picture();
            assertTrue(picture.hasAlpha());
            assertArrayEquals(new byte[]{3, 2, 1, -1, 0, 0, 0, 0}, Arrays.copyOf(RasterTesting.tight(picture), 8));
        }

        @Test
        @DisplayName("rejects malformed headers and unsupported types")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> TgaDecoder.decode(MediaSource.of(new byte[10])));
            assertThrows(MediaException.Malformed.class, () -> TgaDecoder.decode(MediaSource.of(
                    header(0, 0, 2, 0, 0, 0, 0, 1, 24, 0))));
            assertThrows(MediaException.Malformed.class, () -> TgaDecoder.decode(MediaSource.of(
                    header(0, 0, 1, 0, 0, 0, 1, 1, 8, 0))));
            assertThrows(MediaException.Malformed.class, () -> TgaDecoder.decode(MediaSource.of(
                    header(0, 0, 2, 0, 0, 0, 1, 1, 12, 0))));
            assertThrows(MediaException.Unsupported.class, () -> TgaDecoder.decode(MediaSource.of(
                    header(0, 0, 32, 0, 0, 0, 1, 1, 8, 0))));
            assertThrows(MediaException.Malformed.class, () -> TgaDecoder.decode(MediaSource.of(
                    header(0, 1, 1, 0, 200, 24, 1, 1, 8, 0))));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(TgaEncoder.encode(RasterFixtures.rgba(12, 9, 1, true)),
                    TgaEncoder.encode(RasterFixtures.rgba(6, 5, 2, false), TgaEncoderOptions.builder().runLength(false)
                            .build()),
                    concat(header(0, 1, 9, 0, 2, 16, 4, 2, 8, 0), new byte[]{0, 0, (byte) 0xFF, 0x7F},
                            new byte[]{(byte) 0x83, 1, 3, 0, 1, 0, 1}));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 5,
                    source -> TgaDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest
        @CsvSource({"true,false,false", "true,true,true", "false,false,true", "false,true,false"})
        @DisplayName("round trips in every coding and row order")
        void roundTrip(boolean runLength, boolean topDown, boolean alpha) {
            var picture = RasterFixtures.rgba(37, 14, 3, alpha);
            var file = TgaEncoder.encode(picture, TgaEncoderOptions.builder().runLength(runLength).topDown(topDown)
                    .build());
            assertEquals(ImageFormat.TGA, ImageFormat.detect(file, file.length).orElseThrow());
            var decoded = TgaDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(alpha, decoded.hasAlpha());
            assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(decoded));
        }

        @Test
        @DisplayName("writes gray pictures as 8-bit gray and compresses runs")
        void grayAndRuns() {
            var gray = Channel8Picture.allocate(300, 4, PixelLayout.GRAY);
            Arrays.fill(gray.pixels(), (byte) 77);
            var file = TgaEncoder.encode(gray);
            assertEquals(11, file[2]);
            assertEquals(8, file[16]);
            assertTrue(file.length < 18 + 300 * 4, "runs compress");
            var rgba = RasterTesting.tight(TgaDecoder.decode(MediaSource.of(file)).picture());
            for (var index = 0; index < rgba.length; index += 4) {
                assertEquals(77, rgba[index]);
            }
        }

        @Test
        @DisplayName("rejects pictures wider than the format holds and encodes through Images")
        void limits() {
            assertThrows(MediaException.LimitExceeded.class, () -> TgaEncoder.encode(RgbaPicture.allocate(70000, 1,
                    false)));
            var picture = RasterFixtures.rgba(10, 10, 4, true);
            var file = Images.encode(picture, ImageFormat.TGA, ImageEncoderOptions.lossless());
            assertEquals(ImageFormat.TGA, Images.detect(MediaSource.of(file)).orElseThrow());
            assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(Images.decode(MediaSource.of(file))
                    .picture()));
        }
    }

    @Nested
    @DisplayName("ffmpeg")
    class Ffmpeg {
        @ParameterizedTest
        @CsvSource({"true,false", "false,false", "true,true", "false,true"})
        @DisplayName("ffmpeg reads Smalt's files to the same pixels")
        void readsSmalt(boolean runLength, boolean alpha) {
            var picture = RasterFixtures.rgba(29, 13, 7, alpha);
            var file = TgaEncoder.encode(picture, TgaEncoderOptions.builder().runLength(runLength).build());
            var theirs = RasterFixtures.ffmpegDecode(file, "tga", "rgba");
            Tolerance.exact().assertWithin("ffmpeg decode", theirs, RasterTesting.tight(picture));
        }

        @ParameterizedTest
        @CsvSource({"bgr24,1", "bgra,1", "rgb555le,1", "gray,1", "pal8,1", "bgr24,0", "bgra,0", "rgb555le,0",
                "gray,0", "pal8,0"})
        @DisplayName("Smalt reads ffmpeg's files to the same pixels")
        void readsFfmpeg(String pixelFormat, int runLength) {
            var picture = RasterFixtures.rgba(31, 17, 9, true);
            var file = RasterFixtures.ffmpegEncode(RasterFixtures.raw(picture), "rgba", 31, 17, "tga",
                    List.of("-c:v", "targa", "-rle", Integer.toString(runLength), "-pix_fmt", pixelFormat));
            var ours = RasterTesting.tight(TgaDecoder.decode(MediaSource.of(file)).picture());
            var theirs = RasterFixtures.ffmpegDecode(file, "tga", "rgba");
            Tolerance.exact().assertWithin(pixelFormat, theirs, ours);
        }
    }
}
