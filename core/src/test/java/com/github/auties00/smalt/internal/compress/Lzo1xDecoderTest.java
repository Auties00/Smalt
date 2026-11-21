package com.github.auties00.smalt.internal.compress;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks {@link Lzo1xDecoder} against the streams lzokay writes and the hand-written streams of the instruction
 * kinds it does not write (every kind occurs, as make-vectors.py checks), pinned by the SHA-256 of their content,
 * and on damaged and truncated streams.
 */
@DisplayName("LZO1X decoder")
class Lzo1xDecoderTest {
    static List<CompressVectors.Vector> vectors() {
        return CompressVectors.of("lzo");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes lzokay's streams to their content")
    void decodes(CompressVectors.Vector vector) {
        var decoded = Lzo1xDecoder.decode(vector.stream(), 0, vector.stream().length, MediaLimits.DEFAULT);
        assertEquals(vector.size(), decoded.length);
        assertEquals(vector.sha256(), CompressVectors.sha256(decoded));
    }

    @Test
    @DisplayName("decodes each instruction kind to the bytes it stands for")
    void instructions() {
        // first byte 18 (one literal), an M1 copy of two bytes at distance 1 with three trailing literals, the end
        var stream = new byte[]{18, 'q', 0x03, 0, 'r', 's', 't', 0x11, 0, 0};
        assertArrayEquals("qqqrst".getBytes(), Lzo1xDecoder.decode(stream, 0, stream.length, MediaLimits.DEFAULT));
        // a first literal run of five bytes, an M2 copy of three bytes at distance 5, the end
        var m2 = new byte[]{22, 'a', 'b', 'c', 'd', 'e', 0x50, 0, 0x11, 0, 0};
        assertArrayEquals("abcdeabc".getBytes(), Lzo1xDecoder.decode(m2, 0, m2.length, MediaLimits.DEFAULT));
        // an M3 copy of 10 bytes at distance 2, overlapping its own output
        var m3 = new byte[]{19, 'x', 'y', 0x28, 0x04, 0, 0x11, 0, 0};
        assertArrayEquals("xyxyxyxyxyxy".getBytes(), Lzo1xDecoder.decode(m3, 0, m3.length, MediaLimits.DEFAULT));
        assertArrayEquals(new byte[0], Lzo1xDecoder.decode(new byte[]{0x11, 0, 0}, 0, 3, MediaLimits.DEFAULT));
    }

    @Test
    @DisplayName("rejects a copy from before the start, a missing end and a malformed end")
    void malformed() {
        var before = new byte[]{18, 'q', 0x07, 0, 0x11, 0, 0};
        var failure = assertThrows(MediaException.Malformed.class,
                () -> Lzo1xDecoder.decode(before, 0, before.length, MediaLimits.DEFAULT));
        assertTrue(failure.getMessage().contains("before the start"), failure.getMessage());
        var unterminated = new byte[]{22, 'a', 'b', 'c', 'd', 'e'};
        assertThrows(MediaException.Malformed.class,
                () -> Lzo1xDecoder.decode(unterminated, 0, unterminated.length, MediaLimits.DEFAULT));
        var badEnd = new byte[]{22, 'a', 'b', 'c', 'd', 'e', 0x12, 0, 0};
        assertThrows(MediaException.Malformed.class, () -> Lzo1xDecoder.decode(badEnd, 0, badEnd.length, MediaLimits.DEFAULT));
        assertThrows(MediaException.Malformed.class, () -> Lzo1xDecoder.decode(new byte[]{0x11, 0}, 0, 2, MediaLimits.DEFAULT));
    }

    @Test
    @DisplayName("decodes a stream inside a larger array and ignores bytes after the end")
    void range() {
        var vector = CompressVectors.named("lzo", "text.lzo");
        var padded = new byte[vector.stream().length + 20];
        System.arraycopy(vector.stream(), 0, padded, 5, vector.stream().length);
        var decoded = Lzo1xDecoder.decode(padded, 5, vector.stream().length + 9, MediaLimits.DEFAULT);
        assertEquals(vector.sha256(), CompressVectors.sha256(decoded));
    }

    @Test
    @DisplayName("raises LimitExceeded when the content outgrows the allocation limit")
    void limit() {
        var vector = CompressVectors.named("lzo", "zeros.lzo");
        var limits = MediaLimits.builder().maxBytes(100_000).build();
        assertThrows(MediaException.LimitExceeded.class,
                () -> Lzo1xDecoder.decode(vector.stream(), 0, vector.stream().length, limits));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("rejects every truncation with Malformed")
    void truncated(CompressVectors.Vector vector) {
        var stream = vector.stream();
        var step = Math.max(1, stream.length / 97);
        for (var length = 0; length < stream.length; length += step) {
            var cut = length;
            assertThrows(MediaException.Malformed.class, () -> Lzo1xDecoder.decode(stream, 0, cut, MediaLimits.DEFAULT),
                    () -> vector.name() + " cut at " + cut);
        }
    }

    @Test
    @DisplayName("fails damaged and random input only with a MediaException")
    void damaged() {
        var random = new Random(5);
        var limits = MediaLimits.builder().maxBytes(4 << 20).build();
        for (var vector : vectors()) {
            if (vector.stream().length > 8000) {
                continue;
            }
            for (var trial = 0; trial < 40; trial++) {
                var stream = vector.stream().clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    stream[random.nextInt(stream.length)] ^= (byte) (1 << random.nextInt(8));
                }
                decodeOrReject(stream, limits, vector.name() + " trial " + trial);
            }
        }
        for (var trial = 0; trial < 2000; trial++) {
            var stream = new byte[3 + random.nextInt(64)];
            random.nextBytes(stream);
            decodeOrReject(stream, limits, "random trial " + trial);
        }
    }

    static void decodeOrReject(byte[] stream, MediaLimits limits, String what) {
        try {
            Lzo1xDecoder.decode(stream, 0, stream.length, limits);
        } catch (MediaException expected) {
            // rejected
        } catch (RuntimeException exception) {
            fail(what + " threw " + exception, exception);
        }
    }
}
