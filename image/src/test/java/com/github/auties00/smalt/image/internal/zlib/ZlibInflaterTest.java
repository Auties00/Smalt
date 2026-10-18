package com.github.auties00.smalt.image.internal.zlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compresses generated data with the JDK's {@link Deflater} and checks that {@link ZlibInflater}
 * restores it whatever the sizes of the input and output pieces, and that it accepts and rejects
 * damaged streams like {@link Inflater}.
 */
@DisplayName("ZlibInflater matches java.util.zip.Inflater")
class ZlibInflaterTest {
    static byte[] data(int kind, int size, long seed) {
        var random = new Random(seed);
        var data = new byte[size];
        switch (kind) {
            case 0 -> random.nextBytes(data);
            case 1 -> {
            }
            case 2 -> {
                for (var i = 0; i < size; i++) {
                    data[i] = (byte) (i % 251);
                }
            }
            case 3 -> {
                var value = 128;
                for (var i = 0; i < size; i++) {
                    value = Math.clamp(value + random.nextInt(7) - 3, 0, 255);
                    data[i] = (byte) value;
                }
            }
            case 4 -> {
                var i = 0;
                while (i < size) {
                    var run = 1 + random.nextInt(300);
                    var value = (byte) random.nextInt(4);
                    for (var j = 0; j < run && i < size; j++) {
                        data[i++] = value;
                    }
                }
            }
            default -> {
                var text = "the quick brown fox jumps over the lazy dog 0123456789".getBytes();
                for (var i = 0; i < size; i++) {
                    data[i] = random.nextInt(10) == 0 ? (byte) random.nextInt(256) : text[(i * 7 + i / 53) % text.length];
                }
            }
        }
        return data;
    }

    static byte[] deflate(byte[] data, int level, int strategy, boolean sync) {
        var deflater = new Deflater(level);
        deflater.setStrategy(strategy);
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        var half = data.length / 2;
        if (sync) {
            deflater.setInput(data, 0, half);
            int n;
            do {
                n = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
                out.write(buffer, 0, n);
            } while (!deflater.needsInput() || n == buffer.length);
            deflater.setInput(data, half, data.length - half);
        } else {
            deflater.setInput(data);
        }
        deflater.finish();
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] compressed, int inputPiece, int outputPiece) throws DataFormatException {
        var inflater = new ZlibInflater();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[outputPiece];
        var fed = 0;
        var stalls = 0;
        while (!inflater.finished()) {
            var count = inflater.inflate(buffer, 0, buffer.length);
            out.write(buffer, 0, count);
            if (count > 0) {
                stalls = 0;
                continue;
            }
            if (inflater.finished()) {
                break;
            }
            if (inflater.needsInput()) {
                if (fed >= compressed.length) {
                    fail("stream ends early after " + out.size() + " bytes");
                }
                var piece = Math.min(inputPiece, compressed.length - fed);
                inflater.setInput(compressed, fed, piece);
                fed += piece;
                stalls = 0;
                continue;
            }
            if (++stalls > 3) {
                fail("no progress");
            }
        }
        return out.toByteArray();
    }

    static Stream<Arguments> streams() {
        var cases = new ArrayList<Arguments>();
        var sizes = new int[]{0, 1, 7, 100, 5000, 70000, 600000};
        var seed = 1L;
        for (var kind = 0; kind < 6; kind++) {
            for (var size : sizes) {
                for (var level : new int[]{0, 1, 6, 9}) {
                    cases.add(Arguments.of(kind, size, level, seed++));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "kind {0} size {1} level {2}")
    @MethodSource("streams")
    @DisplayName("restores deflated data in pieces of every size")
    void restores(int kind, int size, int level, long seed) throws DataFormatException {
        var original = data(kind, size, seed);
        for (var strategy : new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY}) {
            for (var sync : new boolean[]{false, true}) {
                var zlib = deflate(original, level, strategy, sync);
                assertArrayEquals(original, inflate(zlib, Integer.MAX_VALUE, 1 << 20), "whole");
                assertArrayEquals(original, inflate(zlib, 1, 777), "byte by byte");
                assertArrayEquals(original, inflate(zlib, 13, 64), "13-byte input, 64-byte output");
            }
        }
    }

    @Test
    @DisplayName("rejects and accepts damaged streams like the JDK, and never fails otherwise")
    void damaged() throws DataFormatException {
        var random = new Random(99);
        for (var trial = 0; trial < 6000; trial++) {
            var original = data(random.nextInt(6), 1 + random.nextInt(4000), trial);
            var compressed = deflate(original, random.nextInt(10), Deflater.DEFAULT_STRATEGY, random.nextBoolean());
            var damaged = compressed.clone();
            var flips = 1 + random.nextInt(3);
            for (var k = 0; k < flips; k++) {
                damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            }
            if (random.nextInt(4) == 0) {
                damaged = java.util.Arrays.copyOf(damaged, random.nextInt(damaged.length));
            }
            byte[] expected = null;
            var jdk = new Inflater();
            jdk.setInput(damaged);
            var buffer = new byte[original.length + 100];
            var produced = 0;
            try {
                while (!jdk.finished()) {
                    var n = jdk.inflate(buffer, produced, buffer.length - produced);
                    produced += n;
                    if (n == 0 && (jdk.needsInput() || jdk.needsDictionary() || produced == buffer.length)) {
                        break;
                    }
                }
                if (jdk.finished()) {
                    expected = java.util.Arrays.copyOf(buffer, produced);
                }
            } catch (DataFormatException ignored) {
                expected = null;
            }
            jdk.end();
            byte[] actual = null;
            var inflater = new ZlibInflater();
            inflater.setInput(damaged, 0, damaged.length);
            var out = new byte[original.length + 100];
            var got = 0;
            try {
                while (!inflater.finished()) {
                    var n = inflater.inflate(out, got, out.length - got);
                    got += n;
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary() || got == out.length)) {
                        break;
                    }
                }
                if (inflater.finished()) {
                    actual = java.util.Arrays.copyOf(out, got);
                }
            } catch (DataFormatException ignored) {
                actual = null;
            }
            if (expected == null) {
                assertEquals(null, actual, "trial " + trial + " is rejected by the JDK");
            } else {
                assertTrue(actual != null, "trial " + trial + " is accepted by the JDK");
                assertArrayEquals(expected, actual, "trial " + trial);
            }
        }
    }
}
