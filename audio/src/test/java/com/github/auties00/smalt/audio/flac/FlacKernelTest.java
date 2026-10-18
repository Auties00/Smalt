package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the data-parallel kernels (scalar against Vector API, bit for bit), the order-specialized
 * LPC restore against a naive reference, and the bit reader's edge cases.
 */
class FlacKernelTest {
    static int[] randomInts(SplittableRandom random, int length, int bits) {
        var result = new int[length];
        for (var i = 0; i < length; i++) {
            result[i] = bits == 32 ? random.nextInt() : random.nextInt(-(1 << (bits - 1)), 1 << (bits - 1));
        }
        return result;
    }

    @Nested
    @DisplayName("SIMD equivalence")
    class Equivalence {
        static FlacKernel vector() {
            Assumptions.assumeTrue(Simd.ENABLED, "Vector API disabled");
            return VectorFlacKernel.create();
        }

        @ParameterizedTest(name = "length {0}")
        @ValueSource(ints = {0, 1, 7, 8, 15, 16, 17, 63, 1000, 4608})
        @DisplayName("vector and scalar kernels agree bit for bit, tails included")
        void kernels(int length) {
            var vector = vector();
            var scalar = new ScalarFlacKernel();
            var random = new SplittableRandom(length);
            for (var bits : new int[]{8, 16, 24, 31, 32}) {
                var a = randomInts(random, length, Math.min(bits, 31));
                var b = randomInts(random, length, Math.min(bits, 31));
                var scale = Math.scalb(1f, 1 - bits);
                var expectedFloat = new float[length];
                var actualFloat = new float[length];
                scalar.toFloat(a, expectedFloat, length, scale);
                vector.toFloat(a, actualFloat, length, scale);
                assertArrayEquals(expectedFloat, actualFloat);

                var l1 = a.clone();
                var s1 = b.clone();
                var l2 = a.clone();
                var s2 = b.clone();
                scalar.leftSide(l1, s1, length);
                vector.leftSide(l2, s2, length);
                assertArrayEquals(s1, s2);

                s1 = a.clone();
                var r1 = b.clone();
                s2 = a.clone();
                var r2 = b.clone();
                scalar.sideRight(s1, r1, length);
                vector.sideRight(s2, r2, length);
                assertArrayEquals(s1, s2);

                var m1 = a.clone();
                var d1 = b.clone();
                var m2 = a.clone();
                var d2 = b.clone();
                scalar.midSide(m1, d1, length);
                vector.midSide(m2, d2, length);
                assertArrayEquals(m1, m2);
                assertArrayEquals(d1, d2);
            }
        }

        @Test
        @DisplayName("the scalar mid/side kernel inverts the encoder transform")
        void midSideInverse() {
            var random = new SplittableRandom(3);
            var scalar = new ScalarFlacKernel();
            var left = randomInts(random, 1000, 24);
            var right = randomInts(random, 1000, 24);
            var mid = new int[1000];
            var side = new int[1000];
            for (var i = 0; i < 1000; i++) {
                mid[i] = (left[i] + right[i]) >> 1;
                side[i] = left[i] - right[i];
            }
            scalar.midSide(mid, side, 1000);
            assertArrayEquals(left, mid);
            assertArrayEquals(right, side);
        }
    }

    @Nested
    @DisplayName("linear prediction")
    class Lpc {
        @Test
        @DisplayName("every specialized order matches the naive 64-bit restore")
        void orders() {
            var random = new SplittableRandom(11);
            for (var order = 1; order <= 32; order++) {
                for (var trial = 0; trial < 4; trial++) {
                    var precision = 1 + random.nextInt(15);
                    var shift = random.nextInt(16);
                    var coefficients = new int[32];
                    for (var j = 0; j < order; j++) {
                        coefficients[j] = random.nextInt(-(1 << (precision - 1)), 1 << (precision - 1));
                    }
                    var blockSize = order + random.nextInt(600);
                    var input = randomInts(random, blockSize, 20);
                    var expected = input.clone();
                    for (var i = order; i < blockSize; i++) {
                        var sum = 0L;
                        for (var j = 0; j < order; j++) {
                            sum += (long) coefficients[j] * expected[i - 1 - j];
                        }
                        expected[i] += (int) (sum >> shift);
                    }
                    var actual = input.clone();
                    FlacLpc.restore(actual, blockSize, coefficients, order, shift);
                    assertArrayEquals(expected, actual, "order " + order);
                }
            }
        }
    }

    @Nested
    @DisplayName("bit reader")
    class BitReader {
        @Test
        @DisplayName("reads signed values up to 33 bits and unary codes of any length")
        void reads() {
            var writer = new MsbBitWriter();
            writer.writeLong(-(1L << 32), 33);
            writer.writeLong((1L << 32) - 1, 33);
            writer.write(0, 31);
            writer.write(0, 31);
            writer.write(1, 1);
            writer.write(0x5, 3);
            var bytes = writer.toByteArray();
            var reader = new FlacBitReader();
            reader.reset(bytes, 0, bytes.length);
            assertEquals(-(1L << 32), reader.readSignedLong(33));
            assertEquals((1L << 32) - 1, reader.readSignedLong(33));
            assertEquals(62, reader.readUnary());
            assertEquals(-3, reader.readSigned(3));
            assertFalse(reader.overrun());
        }

        @Test
        @DisplayName("fails on a unary code or Rice block that runs past the end instead of spinning")
        void unaryPastEnd() {
            var zeros = new byte[40];
            var reader = new FlacBitReader();
            reader.reset(zeros, 0, zeros.length);
            assertThrows(MediaException.Malformed.class, reader::readUnary);
            reader.reset(zeros, 3, 20);
            var out = new int[8];
            assertThrows(MediaException.Malformed.class, () -> reader.readRice(out, 0, 8, 3));
        }

        @Test
        @DisplayName("Rice decoding matches a bitwise encoder for random parameters and offsets")
        void rice() {
            var random = new SplittableRandom(99);
            for (var trial = 0; trial < 200; trial++) {
                var parameter = random.nextInt(31);
                var count = 1 + random.nextInt(300);
                var values = new int[count];
                var writer = new MsbBitWriter();
                var prefix = random.nextInt(8);
                writer.write(0, prefix);
                for (var i = 0; i < count; i++) {
                    var magnitude = random.nextInt(8) == 0 ? (1L << Math.min(30, parameter + 5)) : (1L << parameter);
                    values[i] = (int) random.nextLong(-magnitude, magnitude);
                    var folded = values[i] >= 0 ? (long) values[i] << 1 : ((-(long) values[i]) << 1) - 1;
                    var quotient = folded >>> parameter;
                    while (quotient >= 31) {
                        writer.write(0, 31);
                        quotient -= 31;
                    }
                    writer.write(1, (int) quotient + 1);
                    if (parameter > 0) {
                        writer.writeLong(folded & ((1L << parameter) - 1), parameter);
                    }
                }
                writer.write(1, 1);
                var bytes = writer.toByteArray();
                var reader = new FlacBitReader();
                reader.reset(bytes, 0, bytes.length);
                reader.read(prefix);
                var out = new int[count + 2];
                reader.readRice(out, 1, count + 1, parameter);
                assertArrayEquals(values, Arrays.copyOfRange(out, 1, count + 1), "trial " + trial);
                assertEquals(1, reader.read(1));
                assertFalse(reader.overrun());
            }
        }
    }
}
