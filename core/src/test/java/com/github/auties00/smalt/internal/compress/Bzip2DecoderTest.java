package com.github.auties00.smalt.internal.compress;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks {@link Bzip2Decoder} against the streams libbzip2 writes at block sizes 1 and 9 (and a randomized block
 * stream libbzip2 decodes), pinned by the SHA-256 of their content, and on damaged and truncated streams.
 */
@DisplayName("bzip2 decoder")
class Bzip2DecoderTest {
    static List<CompressVectors.Vector> vectors() {
        return CompressVectors.of("bzip2");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes libbzip2's streams to their content")
    void decodes(CompressVectors.Vector vector) {
        var decoded = Bzip2Decoder.decode(vector.stream(), 0, vector.stream().length, MediaLimits.DEFAULT);
        assertEquals(vector.size(), decoded.length);
        assertEquals(vector.sha256(), CompressVectors.sha256(decoded));
    }

    @Test
    @DisplayName("keeps the committed streams the script wrote")
    void pinned() throws IOException {
        for (var line : Files.readAllLines(CompressVectors.DIRECTORY.resolve("compress.sha256"))) {
            var parts = line.split(" {2}");
            assertEquals(parts[0], CompressVectors.sha256(Files.readAllBytes(CompressVectors.DIRECTORY.resolve(parts[1]))),
                    parts[1]);
        }
    }

    @Test
    @DisplayName("covers several blocks, concatenated streams and the randomized block form")
    void coverage() {
        var multi = CompressVectors.named("bzip2", "multi-block-1.bz2");
        assertTrue(multi.size() > 200_000, "level 1 splits the input into three blocks");
        var randomized = CompressVectors.named("bzip2", "randomized.bz2");
        // the randomized bit follows the block magic and CRC: 4 + 6 + 4 bytes in
        assertEquals(0x80, randomized.stream()[14] & 0x80);
        var plain = CompressVectors.named("bzip2", "text-1.bz2");
        var runs = CompressVectors.named("bzip2", "runs-9.bz2");
        var concatenated = CompressVectors.named("bzip2", "concatenated.bz2");
        var joined = new byte[plain.size() + runs.size()];
        var first = Bzip2Decoder.decode(plain.stream(), 0, plain.stream().length, MediaLimits.DEFAULT);
        var second = Bzip2Decoder.decode(runs.stream(), 0, runs.stream().length, MediaLimits.DEFAULT);
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        assertArrayEquals(joined, Bzip2Decoder.decode(concatenated.stream(), 0, concatenated.stream().length,
                MediaLimits.DEFAULT));
    }

    @Test
    @DisplayName("decodes a stream inside a larger array and ignores bytes after it")
    void range() {
        var vector = CompressVectors.named("bzip2", "text-9.bz2");
        var padded = new byte[vector.stream().length + 20];
        System.arraycopy(vector.stream(), 0, padded, 7, vector.stream().length);
        padded[7 + vector.stream().length] = 'x';
        var decoded = Bzip2Decoder.decode(padded, 7, vector.stream().length + 13, MediaLimits.DEFAULT);
        assertEquals(vector.sha256(), CompressVectors.sha256(decoded));
    }

    @Test
    @DisplayName("rejects a stream whose block or stream CRC does not match")
    void crc() {
        var stream = CompressVectors.named("bzip2", "text-9.bz2").stream().clone();
        stream[10] ^= 1;
        var failure = assertThrows(MediaException.Malformed.class,
                () -> Bzip2Decoder.decode(stream, 0, stream.length, MediaLimits.DEFAULT));
        assertTrue(failure.getMessage().contains("block CRC"), failure.getMessage());
        var whole = CompressVectors.named("bzip2", "text-9.bz2").stream().clone();
        whole[whole.length - 2] ^= 0x40;
        var end = assertThrows(MediaException.Malformed.class,
                () -> Bzip2Decoder.decode(whole, 0, whole.length, MediaLimits.DEFAULT));
        assertTrue(end.getMessage().contains("stream CRC"), end.getMessage());
    }

    @Test
    @DisplayName("rejects data that is not a bzip2 stream")
    void header() {
        assertThrows(MediaException.Malformed.class, () -> Bzip2Decoder.decode(new byte[]{'B', 'Z', 'h', '0'}, 0, 4,
                MediaLimits.DEFAULT));
        assertThrows(MediaException.Malformed.class, () -> Bzip2Decoder.decode(new byte[0], 0, 0, MediaLimits.DEFAULT));
        assertFalse(Bzip2Decoder.isStream(new byte[]{'B', 'Z', 'h'}, 0, 3));
        assertTrue(Bzip2Decoder.isStream(new byte[]{'B', 'Z', 'h', '9'}, 0, 4));
    }

    @Test
    @DisplayName("raises LimitExceeded when the content outgrows the allocation limit")
    void limit() {
        var vector = CompressVectors.named("bzip2", "zeros-9.bz2");
        var limits = MediaLimits.builder().maxBytes(100_000).build();
        assertThrows(MediaException.LimitExceeded.class,
                () -> Bzip2Decoder.decode(vector.stream(), 0, vector.stream().length, limits));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("rejects every truncation with Malformed")
    void truncated(CompressVectors.Vector vector) {
        var stream = vector.stream();
        var step = Math.max(1, stream.length / 97);
        // a cut inside the header of the second of two streams leaves bytes that start no stream, which are ignored
        var ignored = vector.name().equals("concatenated.bz2")
                ? CompressVectors.named("bzip2", "text-1.bz2").stream().length : -10;
        for (var length = 0; length < stream.length - 1; length += step) {
            var cut = length;
            if (cut >= ignored && cut < ignored + 4) {
                continue;
            }
            assertThrows(MediaException.Malformed.class, () -> Bzip2Decoder.decode(stream, 0, cut, MediaLimits.DEFAULT),
                    () -> vector.name() + " cut at " + cut);
        }
    }

    @Test
    @DisplayName("fails damaged streams only with a MediaException")
    void damaged() {
        var random = new Random(3);
        var limits = MediaLimits.builder().maxBytes(4 << 20).build();
        for (var vector : vectors()) {
            if (vector.stream().length > 8000) {
                continue;
            }
            for (var trial = 0; trial < 40; trial++) {
                var stream = vector.stream().clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    var at = 4 + random.nextInt(Math.max(1, stream.length - 4));
                    if (at < stream.length) {
                        stream[at] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                try {
                    var decoded = Bzip2Decoder.decode(stream, 0, stream.length, limits);
                    if (!Arrays.equals(stream, vector.stream())) {
                        // a flip the CRCs cannot see changes padding bits only
                        assertEquals(vector.sha256(), CompressVectors.sha256(decoded), vector.name());
                    }
                } catch (MediaException expected) {
                    // rejected
                } catch (RuntimeException exception) {
                    fail(vector.name() + " trial " + trial + " threw " + exception, exception);
                }
            }
        }
    }
}
