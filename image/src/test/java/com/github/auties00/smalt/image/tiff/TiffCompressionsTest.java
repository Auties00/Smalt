package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.image.internal.CompressionSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the Zstandard, LZMA, LERC, JBIG, PixarLog and NeXT compressions of {@link TiffDecoder} against the vectors of
 * tools/testdata/tiff-compressions/make-vectors.py, each compared sample for sample with libtiff's decode of it (the
 * {@code .ref.tif} next to it), and the Zstandard compression of {@link TiffEncoder} by round trips and, when tiffcp
 * is on {@code PATH}, libtiff's decode.
 */
@DisplayName("TIFF Zstandard, LZMA, LERC, JBIG, PixarLog and NeXT")
class TiffCompressionsTest {
    private static final String VECTORS = "/com/github/auties00/smalt/image/tiff/compressions/";

    static Stream<String> vectors() {
        var manifest = new String(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/compressions.sha256"),
                StandardCharsets.US_ASCII);
        return manifest.lines().map(line -> line.substring(line.indexOf("  ") + 2))
                .filter(name -> !name.endsWith(".ref.tif")).map(name -> name.substring(0, name.length() - 4));
    }

    static byte[] vector(String name) {
        return TiffJpeg12Test.resource(VECTORS + name);
    }

    /** Compares samples exactly, NaN equal to NaN. */
    static void assertSame(double[] expected, double[] actual, String what) {
        assertEquals(expected.length, actual.length, what + ": sample count");
        for (var i = 0; i < expected.length; i++) {
            if (Double.isNaN(expected[i]) ? !Double.isNaN(actual[i]) : expected[i] != actual[i]) {
                fail(what + ": sample " + i + " is " + actual[i] + ", libtiff decodes " + expected[i]);
            }
        }
    }

    static double[] samples(byte[] tiff) {
        return TiffCodecCompressionTest.samples(TiffCodecCompressionTest.decode(tiff));
    }

    @Test
    @DisplayName("the vectors are the pinned ones")
    void pinned() {
        var directory = java.nio.file.Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
                "tiff");
        CompressionSamples.assertPinned(directory.resolve("compressions.sha256"), directory.resolve("compressions"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("vectors decode to libtiff's samples")
    void libtiffSamples(String name) {
        var expected = samples(vector(name + ".ref.tif"));
        var actual = samples(vector(name + ".tif"));
        assertSame(expected, actual, name);
        var prefix = name.startsWith("tifffile-") ? name.substring(9) : name;
        var compression = TiffCompression.valueOf(prefix.substring(0, prefix.indexOf('-')).toUpperCase());
        try (var pages = TiffDecoder.openPages(MediaSource.of(vector(name + ".tif")))) {
            assertEquals(compression, pages.page(0).compression());
        }
    }

    @Test
    @DisplayName("the LERC mask becomes alpha and NaN, which the vectors exercise")
    void lercMasks() {
        var alpha = samples(vector("lerc-rgba8-mask.tif"));
        var transparent = 0;
        for (var i = 3; i < alpha.length; i += 4) {
            transparent += alpha[i] == 0 ? 1 : 0;
            assertTrue(alpha[i] == 0 || alpha[i] == 255);
        }
        assertTrue(transparent > 0, "the mask leaves pixels transparent");
        var nan = 0;
        for (var value : samples(vector("lerc-float32-nan.tif"))) {
            nan += Double.isNaN(value) ? 1 : 0;
        }
        assertTrue(nan > 100, "the mask leaves NaN samples: " + nan);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("truncated and mutated vectors decode or fail with a MediaException")
    void damaged(String name) {
        var data = vector(name + ".tif");
        var random = new Random(name.hashCode());
        for (var round = 0; round < 30; round++) {
            byte[] damaged;
            if (round < 10) {
                damaged = Arrays.copyOf(data, data.length - 1 - random.nextInt(Math.max(1, data.length / 2)));
            } else {
                damaged = data.clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    // past the header and directory, where the coded data lies
                    var at = Math.min(data.length - 1, 8 + random.nextInt(data.length - 8));
                    damaged[at] ^= (byte) (1 + random.nextInt(255));
                }
            }
            try {
                TiffCodecCompressionTest.decode(damaged);
            } catch (MediaException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }

    @Nested
    @DisplayName("damaged and unsupported pages")
    class Pages {
        @Test
        @DisplayName("a Zstandard or LZMA strip that ends early keeps its decoded rows")
        void partial() {
            for (var compression : new int[]{50000, 34925}) {
                // three Zstandard blocks, so that a cut leaves complete blocks
                var width = 512;
                var height = compression == 50000 ? 600 : 40;
                var rows = new byte[width * height];
                new Random(compression).nextBytes(rows);
                for (var i = 0; i < rows.length; i += 3) {
                    rows[i] = 0;
                }
                byte[] coded;
                if (compression == 50000) {
                    coded = com.github.auties00.smalt.image.internal.zstd.ZstdEncoder.compress(rows, 0, rows.length, 3);
                } else {
                    coded = oracleXz(rows);
                    if (coded == null) {
                        continue;
                    }
                }
                var full = TiffFiles.of(width, height, 1, 8).tag(259, compression).chunks(coded).bytes();
                var expected = samples(TiffFiles.of(width, height, 1, 8).data(rows).bytes());
                assertSame(expected, samples(full), "complete strip");
                var cut = TiffFiles.of(width, height, 1, 8).tag(259, compression)
                        .chunks(Arrays.copyOf(coded, coded.length * 2 / 3)).bytes();
                var partial = samples(cut);
                var same = 0;
                while (same < partial.length && partial[same] == expected[same]) {
                    same++;
                }
                assertTrue(same >= width * 4, compression + ": only " + same + " samples survive");
                for (var i = same; i < partial.length; i++) {
                    if (partial[i] != expected[i] && partial[i] != 0) {
                        fail(compression + ": sample " + i + " is neither decoded nor zero");
                    }
                }
            }
        }

        static byte[] oracleXz(byte[] rows) {
            if (Oracle.find("xz").isEmpty()) {
                return null;
            }
            return Oracle.run("xz", Duration.ofMinutes(1), rows, List.of("-q", "-c", "--check=crc64"));
        }

        @Test
        @DisplayName("invalid data that produces nothing is malformed")
        void malformed() {
            for (var compression : new int[]{50000, 34925, 34887, 32909, 34661}) {
                var file = TiffFiles.of(8, 8, 1, compression == 34661 ? 1 : 8).tag(259, compression)
                        .chunks(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21})
                        .bytes();
                assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(file)),
                        "compression " + compression);
            }
        }

        @Test
        @DisplayName("layouts a scheme cannot hold are rejected naming the feature")
        void rejected() {
            var pixarLog12 = TiffFiles.of(4, 4, 1, 12).tag(259, 32909).chunks(new byte[8]).bytes();
            assertEquals("tiff pixarlog 12-bit", assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(pixarLog12))).format());
            var lerc64 = TiffFiles.of(4, 4, 1, 64).format(1).tag(259, 34887).chunks(new byte[8]).bytes();
            assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(lerc64)));
            var jbig8 = TiffFiles.of(4, 4, 1, 8).tag(259, 34661).chunks(new byte[8]).bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(jbig8)));
            var next4 = TiffFiles.of(4, 4, 1, 4).tag(259, 32766).chunks(new byte[8]).bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(next4)));
        }

        @Test
        @DisplayName("a JBIG entity of two bit-planes gives its most significant plane, as libtiff reads it")
        void jbigPlanes() {
            // pbmtojbg -b -q (JBIG-KIT 2.1) of a 16x8 PGM of maximum 3 whose high bit is set in the left half and
            // whose low bit is set in the top half; tiffcp -c none of this page holds the left half set
            var entity = java.util.HexFormat.of().parseHex("000002000000001000000008000000020800031cf9a480ff02f780ff0280ff"
                    + "0280ff02ff02d3ff02ff02c0ff02");
            // FillOrder 1 JBIG pages hold their bytes least significant bit first
            for (var i = 0; i < entity.length; i++) {
                entity[i] = (byte) (Integer.reverse(entity[i] & 0xFF) >>> 24);
            }
            var rows = new byte[16];
            for (var y = 0; y < 8; y++) {
                rows[2 * y] = (byte) 0xFF;
            }
            var page = TiffFiles.of(16, 8, 1, 1).tag(259, 34661).chunks(entity).bytes();
            assertSame(samples(TiffFiles.of(16, 8, 1, 1).data(rows).bytes()), samples(page), "two-plane JBIG");
        }

        @Test
        @DisplayName("a LERC blob whose size or type differs from the strip is malformed")
        void lercShape() {
            var data = vector("lerc-gray16-be.tif");
            try (var pages = TiffDecoder.openPages(MediaSource.of(data))) {
                assertEquals(TiffCompression.LERC, pages.page(0).compression());
            }
            // a 32-bit page: the blob holds 16-bit values
            var retyped = TiffFiles.of(64, 48, 1, 32).tag(259, 34887).chunks(firstStrip(data)).bytes();
            assertThrows(MediaException.Malformed.class, () -> TiffDecoder.decode(MediaSource.of(retyped)));
        }

        static byte[] firstStrip(byte[] tiff) {
            var little = tiff[0] == 'I';
            var ifd = readInt(tiff, 4, little);
            var count = readShort(tiff, ifd, little);
            long offset = -1;
            long size = -1;
            for (var i = 0; i < count; i++) {
                var at = ifd + 2 + 12 * i;
                var tag = readShort(tiff, at, little);
                var type = readShort(tiff, at + 2, little);
                var value = type == 3 ? readShort(tiff, at + 8, little) : readInt(tiff, at + 8, little);
                if (tag == 273) {
                    offset = value;
                } else if (tag == 279) {
                    size = value;
                }
            }
            return Arrays.copyOfRange(tiff, (int) offset, (int) (offset + size));
        }

        static int readShort(byte[] data, int at, boolean little) {
            return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
        }

        static int readInt(byte[] data, int at, boolean little) {
            return little ? readShort(data, at, true) | readShort(data, at + 2, true) << 16
                    : readShort(data, at, false) << 16 | readShort(data, at + 2, false);
        }

        @Test
        @DisplayName("a LERC page without LercParameters recognizes the wrapping from the data")
        void lercWithoutParameters() {
            for (var name : List.of("lerc-gray16-deflate", "lerc-float32-lossy-zstd", "lerc-rgb8")) {
                var data = vector(name + ".tif").clone();
                var little = data[0] == 'I';
                var ifd = readInt(data, 4, little);
                var count = readShort(data, ifd, little);
                var found = false;
                for (var i = 0; i < count; i++) {
                    var at = ifd + 2 + 12 * i;
                    if (readShort(data, at, little) == 50674) {
                        // renumber the tag to a private one, keeping the directory sorted
                        data[at + (little ? 0 : 1)] = (byte) 0xF3;
                        found = true;
                    }
                }
                assertTrue(found, name + " has LercParameters");
                assertSame(samples(vector(name + ".ref.tif")), samples(data), name);
            }
        }
    }

    @Nested
    @DisplayName("Zstandard encoding")
    class Encoding {
        static Stream<TiffEncoderOptions> options() {
            var list = new ArrayList<TiffEncoderOptions>();
            for (var level = 1; level <= 9; level += 4) {
                for (var predictor : TiffPredictor.values()) {
                    if (predictor == TiffPredictor.FLOATING_POINT) {
                        continue;
                    }
                    list.add(TiffEncoderOptions.zstd().toBuilder().compressionLevel(level).predictor(predictor).build());
                }
            }
            list.add(TiffEncoderOptions.zstd().toBuilder().tiles(32, 16).build());
            list.add(TiffEncoderOptions.zstd().toBuilder().depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            list.add(TiffEncoderOptions.zstd().toBuilder().layout(TiffEncoderOptions.Layout.GRAY).bigTiff(true).build());
            list.add(TiffEncoderOptions.zstd().toBuilder().strips(3).bigEndian(true).build());
            return list.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("options")
        @DisplayName("pages round trip to the samples an uncompressed page holds")
        void roundTrip(TiffEncoderOptions options) {
            var picture = TiffEncoderTest.picture(70, 45, true, false, 31);
            var zstd = TiffEncoder.encode(picture, options);
            var plain = TiffEncoder.encode(picture, options.toBuilder().compression(TiffCompression.NONE).build());
            assertSame(samples(plain), samples(zstd), options.toString());
            try (var pages = TiffDecoder.openPages(MediaSource.of(zstd))) {
                assertEquals(TiffCompression.ZSTD, pages.page(0).compression());
            }
        }

        @Test
        @DisplayName("Zstandard pages are smaller than uncompressed ones and levels are honored")
        void sizes() {
            var picture = TiffEncoderTest.picture(256, 128, false, false, 7);
            var plain = TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed()).length;
            var fast = TiffEncoder.encode(picture, TiffEncoderOptions.zstd().toBuilder().compressionLevel(1).build());
            var best = TiffEncoder.encode(picture, TiffEncoderOptions.zstd().toBuilder().compressionLevel(9).build());
            assertTrue(fast.length < plain, "level 1: " + fast.length + " of " + plain);
            assertTrue(best.length <= fast.length, "level 9: " + best.length + ", level 1: " + fast.length);
            assertNotEquals(TiffEncoderOptions.zstd(), TiffEncoderOptions.deflate());
            assertThrows(IllegalArgumentException.class,
                    () -> TiffEncoderOptions.zstd().toBuilder().compressionLevel(10).build());
        }

        @Test
        @DisplayName("libtiff decodes Smalt's Zstandard pages to the same samples")
        void libtiff() throws IOException {
            Oracle.assume(Oracle.Tool.TIFFCP);
            var directory = Files.createTempDirectory("smalt-zstd");
            try {
                for (var options : options().toList()) {
                    var picture = TiffEncoderTest.picture(70, 45, true, false, 5);
                    var zstd = TiffEncoder.encode(picture, options);
                    var input = directory.resolve("in.tif");
                    var output = directory.resolve("out.tif");
                    Files.write(input, zstd);
                    Files.deleteIfExists(output);
                    Oracle.run(Oracle.Tool.TIFFCP, Duration.ofMinutes(1), List.of("-c", "none", input.toString(),
                            output.toString()));
                    assertSame(samples(Files.readAllBytes(output)), samples(zstd), options.toString());
                }
            } finally {
                try (var files = Files.list(directory)) {
                    for (var file : files.toList()) {
                        Files.delete(file);
                    }
                }
                Files.delete(directory);
            }
        }
    }

    @Test
    @DisplayName("PixarLog codes cover the whole table and match libtiff for 8-bit, 16-bit and float samples")
    void pixarLogTable() {
        for (var name : List.of("pixarlog-gray8", "pixarlog-gray16", "pixarlog-float32")) {
            var values = samples(vector(name + ".tif"));
            var distinct = Arrays.stream(values).distinct().count();
            assertTrue(distinct > 200, name + " has " + distinct + " distinct samples");
            assertArrayEquals(samples(vector(name + ".ref.tif")), values, name);
        }
    }
}
