package com.github.auties00.smalt.internal.tags;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the gzip streams the tag editor recompresses movie fragments into: a stream padded to a requested length
 * through its header comment (RFC 1952) must decompress to the payload with the JDK's reader.
 */
@DisplayName("MP4 fragment recompression")
class Mp4FragmentGzipTest {
    private static byte[] payload(int seed) {
        var random = new Random(seed);
        var data = new byte[4000];
        for (var index = 0; index < data.length; index++) {
            data[index] = (byte) (random.nextInt(8) * 17);
        }
        return data;
    }

    private static byte[] gunzip(byte[] stream) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(stream))) {
            return input.readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0} bytes beyond the natural length")
    @ValueSource(ints = {0, 1, 2, 3, 100, 65536})
    @DisplayName("pads a stream to the requested length, which decompresses to the payload")
    void padded(int extra) throws IOException {
        var data = payload(extra);
        var natural = Mp4TagFile.gzip(data, 0).length;
        var stream = Mp4TagFile.gzip(data, natural + extra);
        assertEquals(natural + extra, stream.length);
        assertArrayEquals(data, gunzip(stream));
        assertEquals(extra > 0 ? 0x10 : 0, stream[3] & 0xFF, "the comment flag");
    }

    @ParameterizedTest(name = "{0} bytes short")
    @ValueSource(ints = {1, 50})
    @DisplayName("writes the natural stream when the requested length is shorter")
    void shorter(int missing) throws IOException {
        var data = payload(missing);
        var natural = Mp4TagFile.gzip(data, 0);
        var stream = Mp4TagFile.gzip(data, natural.length - missing);
        assertArrayEquals(natural, stream);
        assertArrayEquals(data, gunzip(stream));
        assertTrue(stream.length > natural.length - missing);
    }
}
