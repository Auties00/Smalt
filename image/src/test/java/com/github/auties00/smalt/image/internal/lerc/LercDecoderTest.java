package com.github.auties00.smalt.image.internal.lerc;

import com.github.auties00.smalt.image.internal.CompressionSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link LercDecoder} against blobs written by Esri's LERC library and Lerc1 blobs written by
 * tools/testdata/lerc/make-vectors.py, pinned by the SHA-256 of the values and masks the library decodes; the
 * vectors cover every data type, Lerc2 versions 2 to 6, Lerc1 and every coding path.
 */
@DisplayName("LERC decoder")
class LercDecoderTest {
    static final Path DIRECTORY = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
            "internal", "lerc", "blobs");

    record Vector(String name, int type, int depth, int columns, int rows, int bands, String values, String masks,
                  byte[] blob) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> blobs() {
        try {
            return Files.readAllLines(DIRECTORY.resolve("blobs.tsv")).stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .map(p -> new Vector(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]), Integer.parseInt(p[5]), p[6], p[7], read(DIRECTORY.resolve(p[0]))));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @DisplayName("the blobs are the pinned ones")
    void pinned() {
        CompressionSamples.assertPinned(DIRECTORY.getParent().resolve("lerc.sha256"), DIRECTORY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blobs")
    @DisplayName("blobs decode to the values and masks of Esri's LERC library")
    void blob(Vector vector) throws DataFormatException {
        var decoder = new LercDecoder();
        var info = decoder.info(vector.blob(), 0, vector.blob().length);
        assertEquals(vector.type(), info.dataType());
        assertEquals(vector.depth(), info.depth());
        assertEquals(vector.columns(), info.columns());
        assertEquals(vector.rows(), info.rows());
        assertEquals(vector.bands(), info.bands());
        assertEquals(vector.blob().length, info.blobSize());
        var pixels = info.rows() * info.columns();
        var values = new double[info.bands() * pixels * info.depth()];
        var valid = new byte[info.bands() * pixels];
        decoder.decode(vector.blob(), 0, vector.blob().length, info, values, valid);
        var canonical = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < values.length; i++) {
            var pixel = i / info.depth();
            canonical.putDouble(valid[pixel] != 0 ? values[i] : 0.0);
        }
        assertEquals(vector.masks(), sha256(valid), "masks");
        assertEquals(vector.values(), sha256(canonical.array()), "values");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blobs")
    @DisplayName("truncated and mutated blobs fail cleanly")
    void damaged(Vector vector) {
        var random = new Random(vector.name().hashCode());
        var decoder = new LercDecoder();
        var blob = vector.blob();
        for (var round = 0; round < 60; round++) {
            byte[] data;
            if (round < 20) {
                data = java.util.Arrays.copyOf(blob, random.nextInt(blob.length));
            } else {
                data = blob.clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
            }
            try {
                var info = decoder.info(data, 0, data.length);
                var pixels = (long) info.rows() * info.columns();
                if (pixels * info.depth() * info.bands() > 1 << 22) {
                    continue;
                }
                decoder.decode(data, 0, data.length, info, new double[(int) (pixels * info.depth() * info.bands())],
                        new byte[(int) (pixels * info.bands())]);
            } catch (DataFormatException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }

    @Test
    @DisplayName("a Huffman table whose long codes grow the tree past its first allocation fails cleanly")
    void treeGrowth() throws IOException, DataFormatException {
        var data = java.nio.file.Files.readAllBytes(Path.of("src", "test", "resources", "fuzz-regressions",
                "ImageFuzzTest", "lerc", "huffman-tree-growth.lerc"));
        var decoder = new LercDecoder();
        var info = decoder.info(data, 0, data.length);
        var pixels = info.rows() * info.columns();
        try {
            decoder.decode(data, 0, data.length, info, new double[pixels * info.depth() * info.bands()],
                    new byte[pixels * info.bands()]);
        } catch (DataFormatException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    @DisplayName("data that is neither a Lerc2 nor a Lerc1 blob is rejected, and an invalid Lerc1 header is named")
    void rejected() {
        var decoder = new LercDecoder();
        assertThrows(DataFormatException.class, () -> decoder.info(new byte[40], 0, 40));
        var lerc1 = "CntZImage 000000000000000000000000000000000000000000000000000000000000000000".getBytes();
        var failure = assertThrows(DataFormatException.class, () -> decoder.info(lerc1, 0, lerc1.length));
        assertTrue(failure.getMessage().contains("Lerc1"));
    }

    @Test
    @DisplayName("a Lerc1 blob of three bands shares the count part of its first band")
    void lerc1Bands() throws DataFormatException {
        var vector = blobs().filter(v -> v.name().equals("lerc1-bands3.lerc")).findFirst().orElseThrow();
        var decoder = new LercDecoder();
        var info = decoder.info(vector.blob(), 0, vector.blob().length);
        assertEquals(0, info.version());
        assertEquals(3, info.bands());
        var pixels = info.rows() * info.columns();
        var valid = new byte[3 * pixels];
        decoder.decode(vector.blob(), 0, vector.blob().length, info, new double[3 * pixels], valid);
        var invalid = 0;
        for (var k = 0; k < pixels; k++) {
            assertEquals(valid[k], valid[pixels + k]);
            assertEquals(valid[k], valid[2 * pixels + k]);
            invalid += valid[k] == 0 ? 1 : 0;
        }
        assertTrue(invalid > 0, "the run-length mask leaves pixels out");
        // a later band without its count part is not a blob on its own
        var first = LercDecoderTest.lerc1BandSize(vector.blob());
        var rest = java.util.Arrays.copyOfRange(vector.blob(), first, vector.blob().length);
        assertThrows(DataFormatException.class, () -> {
            var lone = decoder.info(rest, 0, rest.length);
            var size = lone.bands() * lone.rows() * lone.columns();
            decoder.decode(rest, 0, rest.length, lone, new double[size], new byte[size]);
        });
    }

    /** Returns the size of the first band of a Lerc1 blob: its header and its count and value parts. */
    static int lerc1BandSize(byte[] blob) {
        var at = 34;
        for (var part = 0; part < 2; part++) {
            at += 16 + ByteBuffer.wrap(blob, at + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        return at;
    }

    @Test
    @DisplayName("the Fletcher-32 checksum matches its reference values")
    void fletcher() {
        // Lerc2's variant pairs bytes big-endian; "abcde" pairs as 0x6162, 0x6364 and the straggler 0x6500
        var data = "abcde".getBytes();
        long sum1 = 0xFFFF;
        long sum2 = 0xFFFF;
        for (var word : new int[]{0x6162, 0x6364, 0x6500}) {
            sum1 += word;
            sum2 += sum1;
        }
        sum1 = (sum1 & 0xFFFF) + (sum1 >>> 16);
        sum2 = (sum2 & 0xFFFF) + (sum2 >>> 16);
        assertEquals((int) (sum2 << 16 | sum1), LercDecoder.fletcher32(data, 0, data.length));
    }
}
