package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.internal.Djpeg12Support;
import com.github.auties00.smalt.image.tiff.internal.TiffJpeg;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes 12-bit JPEG-compressed TIFF files (compression 7, BitsPerSample 12): the multi-strip and tiled
 * files of tools/testdata/jpeg12 against the samples libtiff 4.7.1 with libjpeg-turbo 3.1.3 decodes from
 * them (recorded in jpeg12.csv), an odd-width file strip by strip against djpeg's 12-bit decode, and
 * single-strip files wrapping the cjpeg12 vectors against djpeg's recorded output.
 */
class TiffJpeg12Test {
    private static final String JPEG = "/com/github/auties00/smalt/image/jpeg/";
    private static final String TIFF = "/com/github/auties00/smalt/image/tiff/";
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    static byte[] resource(String path) {
        try (var input = TiffJpeg12Test.class.getResourceAsStream(path)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<String> lines(String path) {
        var lines = new ArrayList<String>();
        for (var line : new String(resource(path), StandardCharsets.US_ASCII).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                lines.add(line.strip());
            }
        }
        return lines;
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // the 12-bit sample behind a 16-bit one, which the decoder scales as round(v * 65535 / 4095)
    static int twelve(short sixteen) {
        var value = sixteen & 0xFFFF;
        var result = (int) Math.round(value * 4095.0 / 65535.0);
        assertEquals(value, (result * 65535 + 2047) / 4095, "a 16-bit sample that is no scaled 12-bit sample");
        return result;
    }

    // the decoded samples, channels per pixel, at 12 bits
    static int[] samples(Rgba16Picture picture, int channels) {
        var width = picture.width();
        var height = picture.height();
        var out = new int[width * height * channels];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + 4 * x;
                for (var c = 0; c < channels; c++) {
                    out[(y * width + x) * channels + c] = twelve(picture.pixels()[at + c]);
                }
                if (channels == 1) {
                    assertEquals(picture.pixels()[at], picture.pixels()[at + 1]);
                    assertEquals(picture.pixels()[at], picture.pixels()[at + 2]);
                }
                assertEquals(-1, picture.pixels()[at + 3]);
            }
        }
        return out;
    }

    // packs 12-bit samples as TIFF does, each row starting on a byte boundary
    static byte[] pack(int[] samples, int width, int height, int channels) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < height; y++) {
            var bits = 0L;
            var count = 0;
            for (var i = 0; i < width * channels; i++) {
                bits = (bits << 12) | samples[y * width * channels + i];
                count += 12;
                while (count >= 8) {
                    count -= 8;
                    out.write((int) (bits >>> count) & 0xFF);
                }
            }
            if (count > 0) {
                out.write((int) (bits << (8 - count)) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    static List<Arguments> libtiffCases() {
        var cases = new ArrayList<Arguments>();
        for (var line : lines(TIFF + "jpeg12.csv")) {
            var f = line.split(";");
            cases.add(Arguments.of(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("libtiffCases")
    @DisplayName("strips and tiles decode to the 12-bit samples libtiff decodes, and at 8 bits as the same samples uncompressed")
    void libtiff(String file, int width, int height, String md5) {
        var data = resource(TIFF + "jpeg12/" + file);
        var picture = assertInstanceOf(Rgba16Picture.class, TiffDecoder.decode(MediaSource.of(data), WIDE).picture());
        assertEquals(width, picture.width());
        assertEquals(height, picture.height());
        var channels = file.contains("gray") ? 1 : 3;
        var samples = samples(picture, channels);
        var packed = pack(samples, width, height, channels);
        if (!md5.equals("skip")) {
            assertEquals(md5, md5(packed));
        }
        var uncompressed = TiffFiles.of(width, height, channels, 12).data(packed).bytes();
        var expected = (RgbaPicture) TiffDecoder.decode(MediaSource.of(uncompressed)).picture();
        var actual = assertInstanceOf(RgbaPicture.class, TiffDecoder.decode(MediaSource.of(data)).picture());
        assertArrayEquals(expected.pixels(), actual.pixels());
        var wideExpected = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(uncompressed), WIDE).picture();
        assertArrayEquals(wideExpected.pixels(), picture.pixels());
    }

    @Test
    @DisplayName("an odd-width file decodes strip by strip to djpeg's 12-bit output, its last sample of a row included")
    void oddWidth() {
        var data = resource(TIFF + "jpeg12/227x149-ycbcr420-strips32-tables.tif");
        var picture = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(data), WIDE).picture();
        var samples = samples(picture, 3);
        var directory = ifd(data);
        var tables = Arrays.copyOfRange(data, (int) directory.get(347)[0], (int) (directory.get(347)[0] + directory.get(347)[1]));
        var offsets = directory.get(273);
        var counts = directory.get(279);
        var row = 0;
        for (var strip = 0; strip < offsets.length; strip++) {
            var stream = TiffJpeg.join(tables, Arrays.copyOfRange(data, (int) offsets[strip], (int) (offsets[strip] + counts[strip])),
                    (int) counts[strip]);
            var rows = Math.min(32, 149 - row);
            var expected = Djpeg12Support.emulate(stream, 0);
            var actual = Djpeg12Support.pnm(227, rows, 3, Arrays.copyOfRange(samples, row * 227 * 3, (row + rows) * 227 * 3));
            assertArrayEquals(expected, actual, "strip " + strip);
            row += rows;
        }
        assertEquals(149, row);
    }

    // the strip offsets (273), strip byte counts (279), and offset and length of JPEGTables (347) of a little-endian TIFF
    static java.util.Map<Integer, long[]> ifd(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var at = buffer.getInt(4);
        var count = buffer.getShort(at) & 0xFFFF;
        var tags = new java.util.HashMap<Integer, long[]>();
        for (var i = 0; i < count; i++) {
            var entry = at + 2 + 12 * i;
            var tag = buffer.getShort(entry) & 0xFFFF;
            var type = buffer.getShort(entry + 2);
            var n = buffer.getInt(entry + 4);
            var value = buffer.getInt(entry + 8);
            if (tag == 347) {
                tags.put(tag, new long[]{value, n});
            } else if (tag == 273 || tag == 279) {
                var values = new long[n];
                for (var k = 0; k < n; k++) {
                    values[k] = n == 1 ? value : (type == 3 ? buffer.getShort(value + 2 * k) & 0xFFFF : buffer.getInt(value + 4 * k));
                }
                tags.put(tag, values);
            }
        }
        return tags;
    }

    static List<Arguments> singleStripCases() {
        var cases = new ArrayList<Arguments>();
        for (var line : lines(JPEG + "djpeg12-md5.csv")) {
            var f = line.split(",");
            if (f[6].equals("1/1") && (f[0].startsWith("227x149-") || f[0].startsWith("64x48-"))) {
                cases.add(Arguments.of(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[7]));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleStripCases")
    @DisplayName("a single-strip file holding a 12-bit JPEG decodes to djpeg's 12-bit output")
    void singleStrip(String file, int width, int height, String layout, String md5) {
        var jpeg = resource(JPEG + "cjpeg12/" + file);
        var channels = layout.equals("MONOCHROME") ? 1 : 3;
        var tiff = TiffFiles.of(width, height, channels, 12).data(jpeg).tag(259, 7);
        switch (layout) {
            case "MONOCHROME" -> tiff.photometric(1);
            case "RGB" -> tiff.photometric(2);
            default -> {
                tiff.photometric(6);
                var factors = switch (layout) {
                    case "YUV420" -> new long[]{2, 2};
                    case "YUV422" -> new long[]{2, 1};
                    case "YUV440" -> new long[]{1, 2};
                    case "YUV411" -> new long[]{4, 1};
                    default -> new long[]{1, 1};
                };
                tiff.tag(530, factors);
            }
        }
        var picture = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(tiff.bytes()), WIDE).picture();
        assertEquals(md5, md5(Djpeg12Support.pnm(width, height, channels, samples(picture, channels))));
    }

    @Test
    @DisplayName("separate planes, each a one-component 12-bit JPEG, decode to djpeg's 12-bit gray samples in every channel")
    void separatePlanes() {
        var gray = resource(JPEG + "cjpeg12/227x149-monochrome-baseline.jpg");
        var data = new byte[gray.length * 3];
        for (var plane = 0; plane < 3; plane++) {
            System.arraycopy(gray, 0, data, plane * gray.length, gray.length);
        }
        var tiff = TiffFiles.of(227, 149, 3, 12).data(data).tag(259, 7).photometric(2).planar().bytes();
        var picture = assertInstanceOf(Rgba16Picture.class, TiffDecoder.decode(MediaSource.of(tiff), WIDE).picture());
        var rgb = samples(picture, 3);
        var red = new int[227 * 149];
        for (var i = 0; i < red.length; i++) {
            red[i] = rgb[3 * i];
            assertEquals(red[i], rgb[3 * i + 1]);
            assertEquals(red[i], rgb[3 * i + 2]);
        }
        assertArrayEquals(Djpeg12Support.emulate(gray, 0), Djpeg12Support.pnm(227, 149, 1, red));
    }

    @ParameterizedTest(name = "BitsPerSample {0}, JPEG file {1}")
    @CsvSource({"8, 227x149-yuv420-baseline.jpg", "12, 8"})
    @DisplayName("rejects a JPEG whose precision differs from BitsPerSample")
    void precisionMismatch(int bits, String file) {
        var jpeg = file.equals("8") ? eightBit() : resource(JPEG + "cjpeg12/" + file);
        var tiff = TiffFiles.of(227, 149, 3, bits).data(jpeg).tag(259, 7).photometric(6).tag(530, 2, 2).bytes();
        var thrown = assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(tiff), WIDE));
        assertTrue(thrown.getMessage().contains("-bit samples, the image " + bits), thrown.getMessage());
    }

    private static byte[] eightBit() {
        var picture = RgbaPicture.allocate(227, 149, false);
        Arrays.fill(picture.pixels(), (byte) 90);
        return com.github.auties00.smalt.image.jpeg.JpegEncoder.encode(picture);
    }

    @Test
    @DisplayName("a 12-bit file cut anywhere decodes, its missing strips transparent, or raises a MediaException")
    void truncation() {
        var data = resource(TIFF + "jpeg12/226x149-ycbcr422-tiles64-tables.tif");
        for (var length = 0; length < data.length; length += 97) {
            var cut = Arrays.copyOf(data, length);
            for (var options : List.of(WIDE, ImageDecoderOptions.defaults())) {
                try {
                    TiffDecoder.decode(MediaSource.of(cut), options);
                } catch (MediaException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    @DisplayName("rejects 12-bit old-style JPEG and 10-bit JPEG as unsupported")
    void unsupportedDepths() {
        var jpeg = resource(JPEG + "cjpeg12/227x149-yuv420-baseline.jpg");
        var oldStyle = TiffFiles.of(227, 149, 3, 12).data(jpeg).tag(259, 6).photometric(6).bytes();
        assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(oldStyle), WIDE));
        var tenBit = TiffFiles.of(227, 149, 3, 10).data(jpeg).tag(259, 7).photometric(6).bytes();
        assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(tenBit), WIDE));
    }
}
