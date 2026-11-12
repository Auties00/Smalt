package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Ogg CRC built on {@link java.util.zip.CRC32} and bit reversal, checked against the bitwise
 * definition, plus the scalar and vector bit reversers against each other.
 */
@DisplayName("Ogg CRC")
class OggCrcTest {
    static int bitwise(byte[] data, int offset, int length) {
        var crc = 0;
        for (var i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }

    static byte[] random(SplittableRandom random, int length) {
        var data = new byte[length];
        for (var i = 0; i < length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    @Test
    @DisplayName("matches the bitwise definition at every length and offset, reusing one calculator")
    void checksum() {
        var random = new SplittableRandom(11);
        var crc = new OggCrc();
        for (var length = 0; length <= OggPageReader.MAX_PAGE_SIZE; length = length < 600 ? length + 1 : length * 5 / 4) {
            var offset = random.nextInt(9);
            var data = random(random, offset + length + 3);
            assertEquals(bitwise(data, offset, length), crc.checksum(data, offset, length), "length " + length);
        }
        var largest = random(random, OggPageReader.MAX_PAGE_SIZE);
        assertEquals(bitwise(largest, 0, largest.length), crc.checksum(largest, 0, largest.length));
    }

    @Test
    @DisplayName("treats the checksum field of a page as zero without modifying the page")
    void page() {
        var random = new SplittableRandom(12);
        var crc = new OggCrc();
        for (var length : new int[]{27, 28, 100, 2400, 17_000, OggPageReader.MAX_PAGE_SIZE}) {
            var page = random(random, length + 5);
            var copy = page.clone();
            var zeroed = page.clone();
            for (var i = 22; i < 26; i++) {
                zeroed[5 + i] = 0;
            }
            assertEquals(bitwise(zeroed, 5, length), crc.page(page, 5, length), "length " + length);
            assertArrayEquals(copy, page);
        }
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {0, 1, 7, 8, 15, 16, 31, 32, 33, 63, 64, 65, 127, 1000, 4099, 65_307})
    @DisplayName("scalar and vector bit reversal agree with the per-bit definition")
    void reversers(int length) {
        var random = new SplittableRandom(length);
        var source = random(random, length + 6);
        var expected = new byte[length + 4];
        for (var i = 0; i < length; i++) {
            expected[2 + i] = (byte) (Integer.reverse(source[3 + i] & 0xFF) >>> 24);
        }
        var scalar = new byte[length + 4];
        new ScalarBitReverser().reverse(source, 3, scalar, 2, length);
        assertArrayEquals(expected, scalar, "scalar");
        Skip.unlessVector(Simd.ENABLED);
        var vector = new byte[length + 4];
        VectorBitReverser.create().reverse(source, 3, vector, 2, length);
        assertArrayEquals(expected, vector, "vector");
    }
}
