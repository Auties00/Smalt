package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the JPEG XL raw data of the DNG encoder (DNG 1.7 compression 52546): the tiles are lossless 16-bit JPEG
 * XL images of one sample (color filter array and monochrome images) or three (linear raw images) that assemble
 * into the stored image, its edges repeated past the stored area, the file declares DNG 1.7 as its backward
 * version, libjxl's djxl decodes the tiles to the same samples, and images of two samples per photosite are
 * refused. The round trips of every image kind run in {@code DngEncoderTest} over every compression.
 */
class DngJpegXlTest {
    private static final int TILE = 32;

    static List<byte[]> tiles(byte[] dng) {
        var raw = DngStructure.of(dng).raw();
        var offsets = raw.get(324).longs(DngEncoderTest.LE);
        var counts = raw.get(325).longs(DngEncoderTest.LE);
        return IntStream.range(0, offsets.length).mapToObj(i -> Arrays.copyOfRange(dng, (int) offsets[i],
                (int) (offsets[i] + counts[i]))).toList();
    }

    /** The MD5 digest of the MD5 digests of the tiles' bytes, which the DNG SDK computes for lossy raw data. */
    static byte[] codedDigest(List<byte[]> tiles) {
        try {
            var md5 = java.security.MessageDigest.getInstance("MD5");
            var all = java.security.MessageDigest.getInstance("MD5");
            for (var tile : tiles) {
                all.update(md5.digest(tile));
            }
            return all.digest();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /** The samples of one tile as libjxl would output them: the nominal values scaled to 16 bits. */
    static int[] decodeTile(byte[] tile, int channels) {
        var image = JxlDecoder.decodeFloat(MediaSource.of(tile));
        assertEquals(TILE, image.width());
        assertEquals(TILE, image.height());
        assertEquals(channels, image.colorChannels());
        assertTrue(image.extraChannelTypes().isEmpty());
        var out = new int[TILE * TILE * channels];
        for (var i = 0; i < TILE * TILE; i++) {
            for (var c = 0; c < channels; c++) {
                var value = image.plane(c)[i] * 65535;
                // the decoder's nominal values carry float rounding, well within 0.01 of a 16-bit code
                assertEquals(Math.round(value), value, 0.01, "an integer sample");
                out[i * channels + c] = Math.round(value);
            }
        }
        return out;
    }

    @Test
    @DisplayName("tiles are lossless 16-bit JPEG XL images of the stored image, edges repeated")
    void tilesHoldSamples() {
        var images = List.of(DngEncoderTest.bayer(75, 50, CfaPattern.bayer("RGGB"), 3),
                RawImage.ofLinear(45, 37, DngEncoderTest.scene(45, 37, 3, null, 4), DngEncoderTest.NEUTRAL,
                        DngEncoderTest.SRGB_D50),
                RawImage.ofMonochrome(40, 33, DngEncoderTest.scene(40, 33, 1, null, 5)));
        for (var image : images) {
            var spp = image.channels();
            var dng = DngEncoder.encode(image, DngEncoderTest.options(DngCompression.JPEG_XL, TILE));
            var structure = DngStructure.of(dng);
            var raw = structure.raw();
            assertEquals(52546, raw.get(259).longs(DngEncoderTest.LE)[0]);
            assertArrayEquals(new long[]{1, 7, 0, 0}, structure.first().get(50707).longs(DngEncoderTest.LE),
                    "DNGBackwardVersion");
            var width = (int) raw.get(256).longs(DngEncoderTest.LE)[0];
            var height = (int) raw.get(257).longs(DngEncoderTest.LE)[0];
            var across = Math.ceilDiv(width, TILE);
            var tiles = tiles(dng);
            assertEquals(across * Math.ceilDiv(height, TILE), tiles.size());
            assertArrayEquals(codedDigest(tiles), structure.first().get(51111).bytes(),
                    "NewRawImageDigest over the coded tiles, as for lossy compressed raw data");
            var stored = new int[width * height * spp];
            for (var t = 0; t < tiles.size(); t++) {
                var samples = decodeTile(tiles.get(t), spp);
                var left = (t % across) * TILE;
                var top = (t / across) * TILE;
                for (var y = 0; y < TILE; y++) {
                    for (var x = 0; x < TILE; x++) {
                        var sx = Math.min(left + x, width - 1);
                        var sy = Math.min(top + y, height - 1);
                        for (var c = 0; c < spp; c++) {
                            var value = samples[(y * TILE + x) * spp + c];
                            if (left + x < width && top + y < height) {
                                stored[(sy * width + sx) * spp + c] = value;
                            } else {
                                // the repeated edge equals the stored sample it copies, decoded from its own tile
                                var inside = samples[(Math.min(y, sy - top) * TILE + Math.min(x, sx - left)) * spp + c];
                                assertEquals(inside, value, "edge of tile " + t + " at " + x + "," + y);
                            }
                        }
                    }
                }
            }
            var originX = 0;
            var originY = 0;
            if (raw.has(50719)) {
                var origin = raw.get(50719).doubles(DngEncoderTest.LE);
                originX = (int) origin[0];
                originY = (int) origin[1];
            }
            for (var y = 0; y < image.height(); y++) {
                for (var x = 0; x < image.width(); x++) {
                    for (var c = 0; c < spp; c++) {
                        assertEquals(image.samples()[(y * image.width() + x) * spp + c] & 0xFFFF,
                                stored[((y + originY) * width + x + originX) * spp + c], "sample " + x + "," + y);
                    }
                }
            }
            assertTrue(dng.length < DngEncoder.encode(image, DngEncoderTest.options(DngCompression.UNCOMPRESSED,
                    TILE)).length, "JPEG XL tiles are smaller than uncompressed ones");
        }
    }

    @Test
    @DisplayName("djxl decodes each tile to the samples Smalt's decoder reads")
    void djxl() throws IOException {
        Oracle.assume(Oracle.Tool.DJXL);
        var directory = Scratch.directory("dng-jxl");
        var cases = List.of(DngEncoderTest.bayer(70, 45, CfaPattern.bayer("GBRG"), 8),
                RawImage.ofLinear(40, 30, DngEncoderTest.scene(40, 30, 3, null, 9), DngEncoderTest.NEUTRAL,
                        DngEncoderTest.SRGB_D50));
        for (var index = 0; index < cases.size(); index++) {
            var image = cases.get(index);
            var spp = image.channels();
            var tiles = tiles(DngEncoder.encode(image, DngEncoderTest.options(DngCompression.JPEG_XL, TILE)));
            for (var t = 0; t < tiles.size(); t++) {
                var input = directory.resolve("tile-" + index + "-" + t + ".jxl");
                Files.write(input, tiles.get(t));
                var output = directory.resolve("tile-" + index + "-" + t + (spp == 1 ? ".pgm" : ".ppm"));
                Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(input.toString(), output.toString(),
                        "--quiet"));
                assertArrayEquals(decodeTile(tiles.get(t), spp), netpbm16(Files.readAllBytes(output), spp),
                        "case " + index + " tile " + t);
            }
        }
    }

    /** Reads the samples of a 16-bit binary PGM or PPM file of one tile. */
    static int[] netpbm16(byte[] data, int channels) {
        var header = new String(data, 0, Math.min(64, data.length), StandardCharsets.US_ASCII);
        var tokens = header.split("\\s+", 5);
        assertEquals(channels == 1 ? "P5" : "P6", tokens[0]);
        assertEquals(TILE, Integer.parseInt(tokens[1]));
        assertEquals(TILE, Integer.parseInt(tokens[2]));
        assertEquals(65535, Integer.parseInt(tokens[3]));
        var start = data.length - TILE * TILE * channels * 2;
        var out = new int[TILE * TILE * channels];
        for (var i = 0; i < out.length; i++) {
            out[i] = (data[start + 2 * i] & 0xFF) << 8 | (data[start + 2 * i + 1] & 0xFF);
        }
        return out;
    }

    @Test
    @DisplayName("images of two samples per photosite are refused, naming the feature")
    void twoSamples() {
        var cfa = CfaPattern.bayer("RGGB");
        var samples = DngEncoderTest.scene(20, 16, 2, null, 10);
        var image = new RawImage(20, 16, 2, samples, cfa, DngEncoderTest.NEUTRAL, DngEncoderTest.SRGB_D50,
                CfaLayout.RECTANGULAR, List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
        var failure = assertThrows(MediaException.Unsupported.class,
                () -> DngEncoder.encode(image, DngEncoderTest.options(DngCompression.JPEG_XL, 16)));
        assertTrue(failure.getMessage().contains("two"), failure.getMessage());
        assertEquals(2, DngEncoderTest.readBack(DngEncoder.encode(image,
                DngEncoderTest.options(DngCompression.LOSSLESS_JPEG, 16))).channels());
    }

    @Test
    @DisplayName("only JPEG XL raw data raises the backward version to DNG 1.7 and records its lossless coding")
    void backwardVersion() {
        var image = DngEncoderTest.bayer(40, 30, CfaPattern.bayer("RGGB"), 11);
        for (var compression : DngCompression.values()) {
            var dng = DngEncoder.encode(image, DngEncoderTest.options(compression, TILE));
            var structure = DngStructure.of(dng);
            var version = structure.first().get(50707).longs(DngEncoderTest.LE);
            assertEquals(compression == DngCompression.JPEG_XL ? 7 : 1, version[1], compression.toString());
            assertEquals(compression.code(), structure.raw().get(259).longs(DngEncoderTest.LE)[0]);
            var jxl = compression == DngCompression.JPEG_XL;
            assertEquals(jxl, structure.raw().has(52553), compression + " JXLDistance");
            assertEquals(jxl, structure.raw().has(52554), compression + " JXLEffort");
            if (jxl) {
                var distance = structure.raw().get(52553);
                assertEquals(11, distance.type(), "JXLDistance is a FLOAT");
                assertEquals(0.0, distance.doubles(DngEncoderTest.LE)[0], "lossless distance");
                assertEquals(4, structure.raw().get(52554).type(), "JXLEffort is a LONG");
                assertEquals(7, structure.raw().get(52554).longs(DngEncoderTest.LE)[0], "default effort");
            }
        }
    }
}
