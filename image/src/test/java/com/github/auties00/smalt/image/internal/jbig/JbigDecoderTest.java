package com.github.auties00.smalt.image.internal.jbig;

import com.github.auties00.smalt.image.internal.CompressionSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link JbigDecoder} against ITU-T T.82: the arithmetic decoder test sequence of clause 7.1, the
 * artificial image of clause 7.2 coded with the sequential and progressive test parameters (entities whose byte
 * counts match T.82 Tables 29 and 32), and entities of tools/testdata/jbig/make-vectors.py covering every option,
 * pinned by the SHA-256 of their source raster.
 */
@DisplayName("JBIG decoder")
class JbigDecoderTest {
    static final Path DIRECTORY = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
            "internal", "jbig", "entities");

    record Vector(String name, int size, String sha256, byte[] data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> entities() {
        try {
            return Files.readAllLines(DIRECTORY.resolve("entities.tsv")).stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .map(p -> new Vector(p[0], Integer.parseInt(p[1]), p[2], read(DIRECTORY.resolve(p[0]))));
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

    /**
     * Returns the artificial test image of T.82 Figure 38 as packed rows, most significant bit first.
     */
    static byte[] artificial() {
        var width = 1960;
        var height = 1951;
        var rowBytes = (width + 7) / 8;
        var image = new byte[rowBytes * height];
        var prsg = 1;
        var repeat = new int[8];
        for (var j = 0; j < height; j++) {
            for (var i = 0; i < width; i++) {
                int bit;
                if (j < 192) {
                    bit = 0;
                } else if (j < 1023 || ((i >> 3) & 3) == 0) {
                    var sum = (prsg & 1) + ((prsg >> 2) & 1) + ((prsg >> 11) & 1) + ((prsg >> 15) & 1);
                    prsg = (prsg << 1) + (sum & 1);
                    bit = (prsg & 3) == 0 ? 1 : 0;
                    repeat[i & 7] = bit;
                } else {
                    bit = repeat[i & 7];
                }
                if (bit != 0) {
                    image[j * rowBytes + (i >> 3)] |= (byte) (0x80 >> (i & 7));
                }
            }
        }
        return image;
    }

    @Test
    @DisplayName("the entities are the pinned ones")
    void pinned() {
        CompressionSamples.assertPinned(DIRECTORY.getParent().resolve("jbig.sha256"), DIRECTORY);
    }

    @Test
    @DisplayName("the arithmetic decoder reproduces the test sequence of T.82 clause 7.1")
    void arithmetic() {
        var pix = HexFormat.of().parseHex("05e000008b0001c4170000347fff1a3f951b05d81d17e7700000000006560e6a");
        var cx = HexFormat.of().parseHex("0fe000000f0000f0ff00" + "0000".repeat(11));
        var scd = HexFormat.of().parseHex("6989995c32eafaa0d5ff527fffffffc00000003fff2d208291");
        var coder = new JbigArithmeticDecoder();
        coder.tables(new byte[2], new byte[2]);
        coder.start(scd, 0, scd.length);
        for (var i = 0; i < 256; i++) {
            var context = (cx[i >> 3] >> (7 - (i & 7))) & 1;
            var expected = (pix[i >> 3] >> (7 - (i & 7))) & 1;
            assertEquals(expected, coder.decode(context), "pixel " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"t82-sequential-3.jbg", "t82-progressive.jbg"})
    @DisplayName("the T.82 clause 7.2 test entities decode to the artificial image")
    void conformance(String name) throws DataFormatException {
        var data = read(DIRECTORY.resolve(name));
        var image = new JbigDecoder().decode(data, 0, data.length, Long.MAX_VALUE);
        assertEquals(1960, image.width());
        assertEquals(1951, image.height());
        assertEquals(1, image.planes().length);
        assertArrayEquals(artificial(), image.planes()[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    @DisplayName("entities of every option decode to their source raster")
    void entity(Vector vector) throws DataFormatException {
        var image = new JbigDecoder().decode(vector.data(), 0, vector.data().length, Long.MAX_VALUE);
        assertEquals(vector.sha256(), sha256(raster(image)));
        var top = new JbigDecoder().decode(vector.data(), 0, vector.data().length, Long.MAX_VALUE, true);
        assertEquals(1, top.planes().length);
        assertArrayEquals(image.planes()[0], top.planes()[0], "the most significant plane alone");
    }

    /**
     * Returns the raster of a decoded image as the vectors pin it: the rows of a single plane, or one byte per
     * pixel holding the planes as a binary number.
     */
    static byte[] raster(JbigDecoder.Image image) {
        if (image.planes().length == 1) {
            return image.planes()[0];
        }
        var rowBytes = (image.width() + 7) / 8;
        var samples = new byte[image.width() * image.height()];
        for (var y = 0; y < image.height(); y++) {
            for (var x = 0; x < image.width(); x++) {
                var value = 0;
                for (var plane : image.planes()) {
                    value = value << 1 | (plane[y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                }
                samples[y * image.width() + x] = (byte) value;
            }
        }
        return samples;
    }

    @Test
    @DisplayName("a private prediction table is read from the header and used")
    void privateTable() throws DataFormatException {
        var original = entities().filter(v -> v.name().equals("prog-default.jbg")).findFirst().orElseThrow();
        var data = original.data();
        assertTrue((data[19] & 0x04) != 0, "the entity uses deterministic prediction");
        var withTable = withPrivateTable(data, JbigDecoder.defaultTable());
        var image = new JbigDecoder().decode(withTable, 0, withTable.length, Long.MAX_VALUE);
        assertEquals(original.sha256(), sha256(raster(image)));
        // a table that never predicts must change the decoded image, so the table is not ignored
        var none = new byte[1728];
        Arrays.fill(none, (byte) 0xAA);
        var withNone = withPrivateTable(data, none);
        var other = new JbigDecoder().decode(withNone, 0, withNone.length, Long.MAX_VALUE);
        assertFalse(original.sha256().equals(sha256(raster(other))));
    }

    static byte[] withPrivateTable(byte[] data, byte[] table) {
        var result = new byte[data.length + 1728];
        System.arraycopy(data, 0, result, 0, 20);
        result[19] |= 0x02;
        System.arraycopy(table, 0, result, 20, 1728);
        System.arraycopy(data, 20, result, 20 + 1728, data.length - 20);
        return result;
    }

    @Test
    @DisplayName("entities continuing another one, invalid headers and oversized images are rejected")
    void rejected() {
        var data = entities().filter(v -> v.name().equals("prog-default.jbg")).findFirst().orElseThrow().data();
        var continued = data.clone();
        continued[0] = 1;
        assertThrows(DataFormatException.class, () -> new JbigDecoder().decode(continued, 0, continued.length, Long.MAX_VALUE));
        var lastTable = data.clone();
        lastTable[19] |= 0x03;
        assertThrows(DataFormatException.class, () -> new JbigDecoder().decode(lastTable, 0, lastTable.length, Long.MAX_VALUE));
        var order = data.clone();
        order[18] = 0x01;
        assertThrows(DataFormatException.class, () -> new JbigDecoder().decode(order, 0, order.length, Long.MAX_VALUE));
        assertThrows(DataFormatException.class, () -> new JbigDecoder().decode(data, 0, data.length, 100));
        assertThrows(DataFormatException.class, () -> new JbigDecoder().decode(data, 0, 19, Long.MAX_VALUE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    @DisplayName("truncated and mutated entities fail cleanly or decode")
    void damaged(Vector vector) {
        var random = new Random(vector.name().hashCode());
        var decoder = new JbigDecoder();
        for (var round = 0; round < 40; round++) {
            byte[] data;
            if (round < 15) {
                data = Arrays.copyOf(vector.data(), random.nextInt(vector.data().length));
            } else {
                data = vector.data().clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
            }
            try {
                var image = decoder.decode(data, 0, data.length, 1 << 20);
                assertTrue(image.width() > 0 && image.height() > 0);
            } catch (DataFormatException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }

    @Test
    @DisplayName("an entity cut after some stripes keeps those stripes")
    void partial() throws DataFormatException {
        var full = entities().filter(v -> v.name().equals("seq-tpbon-stripes.jbg")).findFirst().orElseThrow();
        var complete = new JbigDecoder().decode(full.data(), 0, full.data().length, Long.MAX_VALUE);
        var cut = Arrays.copyOf(full.data(), full.data().length * 2 / 3);
        var partial = new JbigDecoder().decode(cut, 0, cut.length, Long.MAX_VALUE);
        var rowBytes = (complete.width() + 7) / 8;
        var rows = 0;
        while (rows < complete.height() && Arrays.equals(complete.planes()[0], rows * rowBytes, (rows + 1) * rowBytes,
                partial.planes()[0], rows * rowBytes, (rows + 1) * rowBytes)) {
            rows++;
        }
        assertTrue(rows >= 16, "only " + rows + " rows survive");
        for (var i = rows * rowBytes; i < partial.planes()[0].length; i++) {
            assertEquals(0, partial.planes()[0][i], "rows after the last complete stripe are background");
        }
    }
}
