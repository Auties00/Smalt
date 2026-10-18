package com.github.auties00.smalt.image.internal.zlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Zlib one-call helpers")
class ZlibTest {
    static byte[] data(int size) {
        var random = new Random(size);
        var data = new byte[size];
        for (var i = 0; i < size; i++) {
            data[i] = (byte) (random.nextInt(8) + i % 7);
        }
        return data;
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 1000, 1024, 4096, 5000, 200_000})
    @DisplayName("a deflated payload inflates back, bounded or of known size")
    void roundTrip(int size) {
        var data = data(size);
        for (var level : new int[]{Deflater.BEST_SPEED, Deflater.BEST_COMPRESSION}) {
            var stream = Zlib.deflate(data, level);
            assertArrayEquals(data, Zlib.inflate(stream, 0, stream.length, size + 1));
            assertArrayEquals(data, Zlib.inflate(stream, 0, stream.length, size));
            if (size > 0) {
                assertArrayEquals(data, Zlib.inflate(stream, size));
            }
        }
    }

    @Test
    @DisplayName("an output past the bound, a wrong known size, and damaged streams give null")
    void rejects() {
        var data = data(5000);
        var stream = Zlib.deflate(data, Deflater.BEST_SPEED);
        assertNull(Zlib.inflate(stream, 0, stream.length, 4999));
        assertNull(Zlib.inflate(stream, 4999));
        assertNull(Zlib.inflate(stream, 5001));
        assertNull(Zlib.inflate(stream, 0, stream.length - 9, 10_000));
        var corrupt = stream.clone();
        corrupt[0] = 0;
        assertNull(Zlib.inflate(corrupt, 0, corrupt.length, 10_000));
    }

    @Test
    @DisplayName("matches the bytes of java.util.zip.Deflater")
    void deflaterBytes() {
        var data = data(70_000);
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        var buffer = new byte[100_000];
        var length = deflater.deflate(buffer);
        deflater.end();
        assertArrayEquals(Arrays.copyOf(buffer, length), Zlib.deflate(data, Deflater.BEST_COMPRESSION));
    }
}
