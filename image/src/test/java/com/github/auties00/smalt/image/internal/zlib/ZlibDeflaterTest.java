package com.github.auties00.smalt.image.internal.zlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compresses generated data with {@link ZlibDeflater} at every level and checks that the JDK's
 * {@link Inflater} and {@link ZlibInflater} restore it, that segments primed with a dictionary
 * join into one stream, and that the output is deterministic.
 */
@DisplayName("ZlibDeflater produces standard streams")
class ZlibDeflaterTest {
    static Stream<Arguments> cases() {
        var list = Stream.<Arguments>builder();
        for (var level = 0; level <= 9; level++) {
            for (var kind = 0; kind < 6; kind++) {
                for (var size : new int[]{0, 1, 2, 3, 4, 5, 100, 4097, 70000, 300000, 1500000}) {
                    list.add(Arguments.of(level, kind, size));
                }
            }
        }
        return list.build();
    }

    static byte[] inflate(byte[] stream, boolean nowrap, int expected) throws Exception {
        var inflater = new Inflater(nowrap);
        inflater.setInput(stream);
        var out = new byte[expected + 1];
        var n = 0;
        while (!inflater.finished()) {
            var k = inflater.inflate(out, n, out.length - n);
            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                break;
            }
            n += k;
        }
        assertTrue(inflater.finished(), "stream ends");
        return Arrays.copyOf(out, n);
    }

    @ParameterizedTest(name = "level {0}, data kind {1}, {2} bytes")
    @MethodSource("cases")
    @DisplayName("round trips through the JDK Inflater and ZlibInflater")
    void roundTrip(int level, int kind, int size) throws Exception {
        var data = ZlibInflaterTest.data(kind, size, 17L * level + kind);
        var stream = ZlibDeflater.zlib(data, 0, data.length, level);
        assertArrayEquals(data, inflate(stream, false, size));
        assertArrayEquals(stream, ZlibDeflater.zlib(data, 0, data.length, level), "deterministic");
        var inflater = new ZlibInflater();
        inflater.setInput(stream, 0, stream.length);
        var out = new byte[size + 1];
        var n = 0;
        while (!inflater.finished()) {
            var k = inflater.inflate(out, n, out.length - n);
            if (k == 0 && inflater.needsInput()) {
                break;
            }
            n += k;
        }
        assertEquals(size, n);
        assertArrayEquals(data, Arrays.copyOf(out, n));
    }

    @ParameterizedTest(name = "level {0}, filtered {1}")
    @MethodSource("segmentCases")
    @DisplayName("joins dictionary-primed segments into one raw deflate stream")
    void segments(int level, boolean filtered) throws Exception {
        var data = ZlibInflaterTest.data(5, 400000, 99L + level);
        var parts = new int[]{0, 70000, 70001, 200000, 400000};
        var stream = new ByteArrayOutputStream();
        for (var i = 0; i + 1 < parts.length; i++) {
            var from = parts[i];
            var to = parts[i + 1];
            var dictionary = Math.min(32768, from);
            var buffer = new byte[dictionary + (to - from) + 16];
            System.arraycopy(data, from - dictionary, buffer, 0, dictionary + (to - from));
            stream.writeBytes(ZlibDeflater.segment(buffer, dictionary, to - from, level, filtered, i + 2 == parts.length));
        }
        assertArrayEquals(data, inflate(stream.toByteArray(), true, data.length));
    }

    static Stream<Arguments> segmentCases() {
        var list = Stream.<Arguments>builder();
        for (var level = 0; level <= 9; level++) {
            list.add(Arguments.of(level, false));
            list.add(Arguments.of(level, true));
        }
        return list.build();
    }

    @ParameterizedTest(name = "level {0}, filtered {1}")
    @MethodSource("segmentCases")
    @DisplayName("round trips data whose statistics change from block to block")
    void splitBlocks(int level, boolean filtered) throws Exception {
        var data = new byte[600000];
        var random = new java.util.Random(7L + level);
        for (var i = 0; i < data.length; i++) {
            var phase = i / 50000 % 4;
            data[i] = switch (phase) {
                case 0 -> (byte) "the quick brown fox jumps over the lazy dog ".charAt(i % 44);
                case 1 -> (byte) random.nextInt();
                case 2 -> (byte) (i / 300);
                default -> (byte) (random.nextInt(4) * 85);
            };
        }
        var buffer = Arrays.copyOf(data, data.length + 16);
        var stream = ZlibDeflater.segment(buffer, 0, data.length, level, filtered, true);
        assertArrayEquals(data, inflate(stream, true, data.length));
    }
}
