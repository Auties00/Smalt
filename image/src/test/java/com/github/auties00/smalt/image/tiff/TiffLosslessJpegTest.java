package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegLsEncoder;
import com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers JPEG-compressed TIFF images (compression 7) whose strips and tiles hold lossless codestreams, as DICOM
 * allows where it allows lossless JPEG: JPEG-LS and lossless JPEG at 8, 12 and 16 bits, gray and RGB, in strips
 * and tiles, each decoding to exactly the samples coded.
 */
@DisplayName("TIFF lossless JPEG and JPEG-LS strips")
class TiffLosslessJpegTest {
    private static int sample(int x, int y, int c, int bits) {
        return ((x * 37 + y * 11 + c * 101 + (x * y) % 13) * 3) & ((1 << bits) - 1);
    }

    private static YuvPicture gray8(int width, int height, int x0, int y0) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) sample(x0 + x, y0 + y, 0, 8);
            }
        }
        return picture;
    }

    private static Yuv16Picture gray16(int width, int height, int x0, int y0, int bits) {
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.MONOCHROME, bits, false, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (short) sample(x0 + x, y0 + y, 0, bits);
            }
        }
        return picture;
    }

    private static RgbaPicture rgb(int width, int height, int x0, int y0) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 3; c++) {
                    picture.pixels()[y * picture.stride() + 4 * x + c] = (byte) sample(x0 + x, y0 + y, c, 8);
                }
                picture.pixels()[y * picture.stride() + 4 * x + 3] = (byte) 0xFF;
            }
        }
        return picture;
    }

    private interface ChunkCoder {
        Picture picture(int width, int height, int x0, int y0);
    }

    private static List<byte[]> strips(int width, int height, int rows, ChunkCoder coder,
                                       Function<Picture, byte[]> encoder) {
        var chunks = new ArrayList<byte[]>();
        for (var y0 = 0; y0 < height; y0 += rows) {
            chunks.add(encoder.apply(coder.picture(width, Math.min(rows, height - y0), 0, y0)));
        }
        return chunks;
    }

    private static List<byte[]> tiles(int width, int height, int tile, ChunkCoder coder,
                                      Function<Picture, byte[]> encoder) {
        var chunks = new ArrayList<byte[]>();
        for (var y0 = 0; y0 < height; y0 += tile) {
            for (var x0 = 0; x0 < width; x0 += tile) {
                chunks.add(encoder.apply(coder.picture(tile, tile, x0, y0)));
            }
        }
        return chunks;
    }

    private static Picture decode(byte[] tiff) {
        return TiffDecoder.decode(MediaSource.of(tiff), ImageDecoderOptions.builder().highBitDepth(true).build())
                .picture();
    }

    @ParameterizedTest(name = "NEAR {0}")
    @ValueSource(ints = {0, 3})
    @DisplayName("8-bit gray JPEG-LS strips decode to the coded samples")
    void grayStrips(int near) {
        var chunks = strips(45, 37, 16, TiffLosslessJpegTest::gray8,
                picture -> JpegLsEncoder.encode(picture, JpegLsEncoderOptions.ofNearLossless(near)));
        var tiff = CodedTiff.of(45, 37, 1, 8, 7).strips(16).chunks(chunks).bytes();
        var picture = assertInstanceOf(RgbaPicture.class, decode(tiff));
        for (var y = 0; y < 37; y++) {
            for (var x = 0; x < 45; x++) {
                assertEquals(sample(x, y, 0, 8), picture.pixels()[y * picture.stride() + 4 * x] & 0xFF, near,
                        x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("16-bit gray JPEG-LS tiles decode to the coded samples")
    void gray16Tiles() {
        var chunks = tiles(40, 24, 16, (w, h, x0, y0) -> gray16(w, h, x0, y0, 16), JpegLsEncoder::encode);
        var tiff = CodedTiff.of(40, 24, 1, 16, 7).tiles(16, 16).chunks(chunks).bytes();
        var picture = assertInstanceOf(Rgba16Picture.class, decode(tiff));
        for (var y = 0; y < 24; y++) {
            for (var x = 0; x < 40; x++) {
                assertEquals(sample(x, y, 0, 16), picture.pixels()[y * picture.stride() + 4 * x] & 0xFFFF,
                        x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("RGB JPEG-LS strips, line and sample interleaved, decode to the coded samples")
    void rgbStrips() {
        for (var interleave : new JpegLsEncoderOptions.Interleave[]{JpegLsEncoderOptions.Interleave.LINE,
                JpegLsEncoderOptions.Interleave.SAMPLE}) {
            var chunks = strips(33, 21, 8, TiffLosslessJpegTest::rgb, picture -> JpegLsEncoder.encode(picture,
                    JpegLsEncoderOptions.builder().interleave(interleave).build()));
            var tiff = CodedTiff.of(33, 21, 3, 8, 7).strips(8).chunks(chunks).bytes();
            var picture = assertInstanceOf(RgbaPicture.class, decode(tiff));
            for (var y = 0; y < 21; y++) {
                for (var x = 0; x < 33; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(sample(x, y, c, 8), picture.pixels()[y * picture.stride() + 4 * x + c] & 0xFF);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("12-bit lossless JPEG strips are unpacked most significant bit first")
    void losslessTwelveBit() {
        var options = JpegEncoderOptions.builder().lossless(true).precision(12).chromaFormat(ChromaFormat.MONOCHROME)
                .build();
        var chunks = strips(29, 19, 10, (w, h, x0, y0) -> gray16(w, h, x0, y0, 12),
                picture -> JpegEncoder.encode(picture, options));
        var tiff = CodedTiff.of(29, 19, 1, 12, 7).strips(10).chunks(chunks).bytes();
        var picture = assertInstanceOf(Rgba16Picture.class, decode(tiff));
        for (var y = 0; y < 19; y++) {
            for (var x = 0; x < 29; x++) {
                var value = picture.pixels()[y * picture.stride() + 4 * x] & 0xFFFF;
                assertEquals((sample(x, y, 0, 12) * 65535 * 2 + 4095) / 8190, value, x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("names a DCT strip in a 16-bit image as unsupported")
    void dctSixteenBit() {
        var options = JpegEncoderOptions.builder().chromaFormat(ChromaFormat.MONOCHROME).build();
        var chunks = strips(16, 16, 16, TiffLosslessJpegTest::gray8, picture -> JpegEncoder.encode(picture, options));
        var tiff = CodedTiff.of(16, 16, 1, 16, 7).strips(16).chunks(chunks).bytes();
        assertEquals("tiff jpeg 16-bit",
                assertThrows(MediaException.Unsupported.class, () -> decode(tiff)).format());
    }
}
