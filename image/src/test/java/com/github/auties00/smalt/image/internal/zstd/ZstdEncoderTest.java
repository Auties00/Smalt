package com.github.auties00.smalt.image.internal.zstd;

import com.github.auties00.smalt.image.internal.CompressionSamples;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link ZstdEncoder} by round trips through {@link ZstdDecoder} and, when zstd is on {@code PATH}, through
 * the zstd tool as an independent decoder.
 */
@DisplayName("Zstandard encoder")
class ZstdEncoderTest {
    static byte[] roundTrip(byte[] input, int level) throws DataFormatException {
        var frame = ZstdEncoder.compress(input, 0, input.length, level);
        var out = new byte[input.length + 16];
        var produced = new ZstdDecoder().decompress(frame, 0, frame.length, out, 0, out.length);
        assertEquals(input.length, produced, "decoded size at level " + level);
        return Arrays.copyOf(out, produced);
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    @DisplayName("mixed content round trips at every level")
    void levels(int level) throws DataFormatException {
        var random = new Random(level);
        for (var round = 0; round < 6; round++) {
            var input = CompressionSamples.sample(random, 1 + random.nextInt(400000));
            assertArrayEquals(input, roundTrip(input, level));
        }
    }

    @Test
    @DisplayName("edge sizes round trip: empty, one byte, block boundaries, runs")
    void sizes() throws DataFormatException {
        var random = new Random(7);
        for (var size : new int[]{0, 1, 2, 3, 4, 5, 31, 32, 255, 256, 257, 1023, 1024, 65791, 65792, 131071, 131072,
                131073, 262144}) {
            var input = CompressionSamples.sample(random, Math.max(size, 1));
            input = Arrays.copyOf(input, size);
            assertArrayEquals(input, roundTrip(input, 3), "size " + size);
            var run = new byte[size];
            Arrays.fill(run, (byte) 7);
            assertArrayEquals(run, roundTrip(run, 6), "run of " + size);
        }
    }

    @Test
    @DisplayName("an input larger than 8 MiB is written with a window and round trips")
    void largeWindow() throws DataFormatException {
        var random = new Random(11);
        var piece = CompressionSamples.sample(random, 1 << 20);
        var input = new byte[(9 << 20) + 12345];
        for (var i = 0; i < input.length; i += piece.length) {
            System.arraycopy(piece, 0, input, i, Math.min(piece.length, input.length - i));
        }
        input[input.length / 2] ^= 1;
        var frame = ZstdEncoder.compress(input, 0, input.length, 2);
        // not a single-segment frame: the descriptor has the window byte
        assertEquals(0, frame[4] & 0x20);
        assertTrue(frame.length < input.length / 4, "compressed to " + frame.length);
        var out = new byte[input.length];
        assertEquals(input.length, new ZstdDecoder().decompress(frame, 0, frame.length, out, 0, out.length));
        assertArrayEquals(input, out);
    }

    @Test
    @DisplayName("redundant content shrinks, and higher levels are not larger on text")
    void ratio() {
        var random = new Random(3);
        var text = new byte[200000];
        var words = List.of("strip ", "tile ", "sample ", "row ", "predictor ", "zstd ", "image ");
        var at = 0;
        while (at < text.length) {
            var word = words.get(random.nextInt(words.size())).getBytes();
            var count = Math.min(word.length, text.length - at);
            System.arraycopy(word, 0, text, at, count);
            at += count;
        }
        var fast = ZstdEncoder.compress(text, 0, text.length, 1).length;
        var best = ZstdEncoder.compress(text, 0, text.length, 9).length;
        assertTrue(fast < text.length / 3, "level 1 gives " + fast);
        assertTrue(best <= fast, "level 9 gives " + best + ", level 1 " + fast);
        var zeros = ZstdEncoder.compress(new byte[1 << 20], 0, 1 << 20, 3).length;
        assertTrue(zeros < 64, "a megabyte of zeros gives " + zeros);
        var noise = new byte[100000];
        random.nextBytes(noise);
        assertTrue(ZstdEncoder.compress(noise, 0, noise.length, 9).length < noise.length + 32);
    }

    @Test
    @DisplayName("the input range is honored and the output is deterministic")
    void range() throws DataFormatException {
        var input = CompressionSamples.sample(new Random(5), 50000);
        var frame = ZstdEncoder.compress(input, 1000, 20000, 5);
        assertArrayEquals(frame, ZstdEncoder.compress(input, 1000, 20000, 5));
        var out = new byte[20000];
        new ZstdDecoder().decompress(frame, 0, frame.length, out, 0, out.length);
        assertArrayEquals(Arrays.copyOfRange(input, 1000, 21000), out);
        assertThrows(IllegalArgumentException.class, () -> ZstdEncoder.compress(input, 0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> ZstdEncoder.compress(input, 0, 10, 10));
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {1, 4, 9})
    @DisplayName("the zstd tool decodes the frames exactly")
    void oracle(int level) {
        Oracle.assume("zstd");
        var random = new Random(100 + level);
        for (var round = 0; round < 8; round++) {
            var input = CompressionSamples.sample(random, 1 + random.nextInt(300000));
            var frame = ZstdEncoder.compress(input, 0, input.length, level);
            var decoded = Oracle.run("zstd", Duration.ofMinutes(1), frame, List.of("-q", "-d", "-c"));
            assertArrayEquals(input, decoded, "round " + round);
        }
    }
}
