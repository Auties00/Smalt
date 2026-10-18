package com.github.auties00.smalt.image.jxl.internal.brotli;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decompresses streams the reference brotli CLI wrote at qualities 0 to 11 and windows of 10 and
 * 22 bits (text that exercises the static dictionary, random bytes, long runs and an empty
 * input), and checks that corrupt streams fail cleanly.
 */
class BrotliDecoderTest {
    static byte[] resource(String name) {
        try (var in = BrotliDecoderTest.class.getResourceAsStream("/com/github/auties00/smalt/image/jxl/brotli/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<String> streams() {
        var names = new ArrayList<String>();
        for (var source : List.of("words.txt", "binary.bin", "runs.bin", "empty.bin")) {
            for (var quality : List.of(0, 4, 9, 11)) {
                for (var window : List.of(10, 22)) {
                    names.add(source + ".q" + quality + "-w" + window + ".br");
                }
            }
        }
        return names;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("matches the original bytes")
    void decompress(String name) {
        var compressed = resource(name);
        var original = resource(name.substring(0, name.indexOf(".q")));
        assertArrayEquals(original, BrotliDecoder.decompress(compressed, 0, compressed.length, 1 << 20));
    }

    @Test
    @DisplayName("rejects output beyond the bound")
    void bound() {
        var compressed = resource("runs.bin.q9-w22.br");
        assertThrows(MediaException.LimitExceeded.class,
                () -> BrotliDecoder.decompress(compressed, 0, compressed.length, 100));
    }

    @Test
    @DisplayName("fails corrupt and truncated streams with a MediaException")
    void corrupt() {
        var compressed = resource("words.txt.q11-w22.br");
        var random = new Random(5);
        for (var i = 0; i < 400; i++) {
            var data = compressed.clone();
            if (i % 2 == 0) {
                data = Arrays.copyOf(data, random.nextInt(data.length));
            } else {
                data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
            }
            var input = data;
            try {
                BrotliDecoder.decompress(input, 0, input.length, 1 << 20);
            } catch (MediaException expected) {
                // A corrupt stream is rejected.
            }
        }
    }
}
