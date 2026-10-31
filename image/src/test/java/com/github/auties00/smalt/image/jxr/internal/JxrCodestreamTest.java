package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the samples of every JPEG XR vector of tools/testdata/behavior/make-jxr-vectors.py with those the
 * reference decoder, jxrlib 1.1, decodes from it (the SHA-256 digests of jxr.tsv), sample for sample: every pixel
 * format, overlap mode, chroma subsampling, bitstream order, tiling with soft and hard boundaries, dropped bands,
 * trimmed flexbits, per-tile and per-macroblock quantizers, the transform operators of codec subversions 0, 1 and 9,
 * inscribed windows and planar and interleaved alpha.
 */
@DisplayName("JPEG XR codestreams against jxrlib")
class JxrCodestreamTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/jxr/";

    static byte[] resource(String name) {
        try (var input = JxrCodestreamTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** {@return the rows of jxr.tsv: name, file digest, sample digest, width, height, channels} */
    static List<String[]> rows() {
        return new String(resource("jxr.tsv"), StandardCharsets.US_ASCII).strip().lines()
                .map(String::strip).map(line -> line.split("\t")).toList();
    }

    static Stream<Arguments> vectors() {
        return rows().stream().map(row -> Arguments.of(row[0], row));
    }

    /** {@return the samples of a file's image codestream with its planar alpha codestream appended} */
    static JxrCodestream.Samples samples(byte[] data) {
        var container = JxrContainer.read(data);
        var image = JxrCodestream.decode(data, container.imageOffset(), container.imageEnd(), MediaLimits.DEFAULT);
        if (container.alphaOffset() < 0 || image.alpha()) {
            return image;
        }
        var alpha = JxrCodestream.decode(data, container.alphaOffset(), container.alphaEnd(), MediaLimits.DEFAULT);
        var channels = Arrays.copyOf(image.channels(), image.channels().length + 1);
        channels[image.channels().length] = alpha.channels()[0];
        return new JxrCodestream.Samples(image.header(), image.format(), image.width(), image.height(), channels,
                true);
    }

    /** {@return the SHA-256 of samples: every pixel row-major, every channel, as an int32 little-endian} */
    static String digest(int[][] channels, int pixels) throws NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        var buffer = new byte[4 * channels.length];
        for (var index = 0; index < pixels; index++) {
            for (var c = 0; c < channels.length; c++) {
                var value = channels[c][index];
                buffer[4 * c] = (byte) value;
                buffer[4 * c + 1] = (byte) (value >> 8);
                buffer[4 * c + 2] = (byte) (value >> 16);
                buffer[4 * c + 3] = (byte) (value >> 24);
            }
            digest.update(buffer);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes the samples jxrlib decodes")
    void againstReference(String name, String[] row) throws NoSuchAlgorithmException {
        var data = resource(row[0] + ".jxr");
        assertEquals(row[1], sha256(data), "the vector is the file pinned in jxr.tsv");
        var samples = samples(data);
        assertEquals(Integer.parseInt(row[3]), samples.width(), "width");
        assertEquals(Integer.parseInt(row[4]), samples.height(), "height");
        assertEquals(Integer.parseInt(row[5]), samples.channels().length, "channels");
        assertEquals(row[2], digest(samples.channels(), samples.width() * samples.height()), "samples");
    }

    @Test
    @DisplayName("the vectors cover every output bit depth and internal color format")
    void coverage() {
        var depths = new java.util.TreeSet<Integer>();
        var formats = new java.util.TreeSet<Integer>();
        var subversions = new java.util.TreeSet<Integer>();
        for (var row : rows()) {
            var data = resource(row[0] + ".jxr");
            var container = JxrContainer.read(data);
            var header = JxrHeader.parse(data, container.imageOffset(), container.imageEnd());
            depths.add(header.outputBitDepth());
            formats.add(header.internalColorFormat());
            subversions.add(header.subversion);
        }
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 6, 7, 8, 9, 10), depths);
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 6), formats);
        assertEquals(java.util.Set.of(0, 1, 9), subversions);
    }

    @Nested
    @DisplayName("malformed codestreams")
    class Malformed {
        private static byte[] codestream() {
            var data = resource("rgb8-q40l2.jxr");
            var container = JxrContainer.read(data);
            return Arrays.copyOfRange(data, container.imageOffset(), container.imageEnd());
        }

        private static void decode(byte[] codestream) {
            JxrCodestream.decode(codestream, 0, codestream.length, MediaLimits.DEFAULT);
        }

        @Test
        @DisplayName("decodes a bare codestream as its container does")
        void bare() throws NoSuchAlgorithmException {
            var data = resource("rgb8-q40l2.jxr");
            var stream = codestream();
            var bare = JxrCodestream.decode(stream, 0, stream.length, MediaLimits.DEFAULT);
            var contained = samples(data);
            assertEquals(digest(contained.channels(), contained.width() * contained.height()),
                    digest(bare.channels(), bare.width() * bare.height()));
            assertEquals(0, JxrContainer.read(stream).imageOffset());
        }

        @Test
        @DisplayName("rejects a missing signature, another codec version or subversion and overlap mode 3")
        void header() {
            var stream = codestream();
            var signature = stream.clone();
            signature[0] = 'X';
            assertThrows(MediaException.Malformed.class, () -> decode(signature));
            var version = stream.clone();
            version[8] = 0x21;
            assertThrows(MediaException.Unsupported.class, () -> decode(version));
            var subversion = stream.clone();
            subversion[8] = 0x15;
            assertThrows(MediaException.Unsupported.class, () -> decode(subversion));
            var overlap = stream.clone();
            overlap[9] |= 0x03;
            assertThrows(MediaException.Malformed.class, () -> decode(overlap));
            var colorFormat = stream.clone();
            colorFormat[11] = (byte) (0x50 | colorFormat[11] & 0xF);
            assertThrows(MediaException.Malformed.class, () -> decode(colorFormat));
            assertThrows(MediaException.Malformed.class, () -> decode(Arrays.copyOf(stream, 12)));
        }

        @Test
        @DisplayName("rejects containers without a codestream or with entries past the file")
        void container() {
            var data = resource("rgb8-q40l2.jxr");
            var magic = data.clone();
            magic[2] = 0;
            assertThrows(MediaException.Malformed.class, () -> JxrContainer.read(magic));
            var version = data.clone();
            version[3] = 2;
            assertThrows(MediaException.Unsupported.class, () -> JxrContainer.read(version));
            var directory = data.clone();
            directory[4] = (byte) 0xF0;
            directory[5] = (byte) 0xFF;
            assertThrows(MediaException.Malformed.class, () -> JxrContainer.read(directory));
            assertThrows(MediaException.Malformed.class, () -> JxrContainer.read(Arrays.copyOf(data, 6)));
        }

        @Test
        @DisplayName("decodes or rejects every truncation of a codestream without another exception")
        void truncations() {
            var stream = codestream();
            for (var length = 16; length < stream.length; length += 7) {
                var cut = Arrays.copyOf(stream, length);
                try {
                    decode(cut);
                } catch (MediaException expected) {
                    // a truncated packet is malformed, or decodes from zero bits
                }
            }
        }
    }
}
