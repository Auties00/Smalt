package com.github.auties00.smalt.internal.bits;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the MSB-first and LSB-first bit readers and writers: randomized round trips over every
 * field width, Exp-Golomb extremes, alignment, skipping, the writer's mark and rollback, and the
 * overrun contract.
 */
class BitStreamTest {
    private static final long SEED = 0x5EED_C0BAL;

    private static String bits(MsbBitWriter writer) {
        var bytes = writer.toByteArray();
        var text = new StringBuilder();
        for (var i = 0; i < writer.bitCount(); i++) {
            text.append((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        }
        return text.toString();
    }

    @Nested
    @DisplayName("MSB-first")
    class Msb {
        @ParameterizedTest(name = "seed offset {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("round-trips random fields of every width from 0 to 64 bits")
        void roundTrip(int seedOffset) {
            var random = new Random(SEED + seedOffset);
            var widths = new int[5000];
            var values = new long[5000];
            var writer = new MsbBitWriter(4);
            for (var i = 0; i < widths.length; i++) {
                widths[i] = random.nextInt(65);
                values[i] = widths[i] == 64 ? random.nextLong() : random.nextLong() & ((1L << widths[i]) - 1);
                writer.writeLong(values[i], widths[i]);
            }
            var reader = new MsbBitReader(writer.toByteArray());
            for (var i = 0; i < widths.length; i++) {
                assertEquals(values[i], reader.readLong(widths[i]), "field " + i + " of width " + widths[i]);
            }
            assertFalse(reader.overrun());
        }

        @Test
        @DisplayName("reads fields crossing every byte and cache boundary of a real array offset")
        void offsetRange() {
            var data = new byte[]{(byte) 0xFF, (byte) 0xA5, 0x3C, (byte) 0x81, 0x7E, 0x00, (byte) 0xFF, 0x12, 0x34, 0x56};
            var reader = new MsbBitReader(data, 1, 8);
            assertEquals(0xA, reader.read(4));
            assertEquals(0x53C8, reader.read(16));
            assertEquals(0x17E00FF1, reader.read(32));
            assertEquals(0x23, reader.read(8));
            assertEquals(4, reader.bitsLeft());
            assertEquals(0x4, reader.read(4));
            assertFalse(reader.overrun());
        }

        @Test
        @DisplayName("treats a zero-width read as a no-op returning zero")
        void zeroWidth() {
            var reader = new MsbBitReader(new byte[]{(byte) 0x80});
            assertEquals(0, reader.read(0));
            assertEquals(1, reader.readBit());
        }

        @ParameterizedTest(name = "value {0}")
        @ValueSource(longs = {0, 1, 2, 3, 7, 254, 255, 65534, 1_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE})
        @DisplayName("round-trips unsigned Exp-Golomb codes including the longest in range")
        void unsignedExpGolomb(long value) {
            var writer = new MsbBitWriter();
            writer.write(1, 3);
            writer.writeUe(value);
            writer.write(5, 3);
            var reader = new MsbBitReader(writer.toByteArray());
            assertEquals(1, reader.read(3));
            assertEquals(value, reader.readUe());
            assertEquals(5, reader.read(3));
        }

        @ParameterizedTest(name = "value {0}")
        @ValueSource(ints = {0, 1, -1, 2, -2, 1000, -1000, 1 << 29, -(1 << 29)})
        @DisplayName("round-trips signed Exp-Golomb codes")
        void signedExpGolomb(int value) {
            var writer = new MsbBitWriter();
            writer.writeSe(value);
            assertEquals(value, new MsbBitReader(writer.toByteArray()).readSe());
        }

        @Test
        @DisplayName("rejects an Exp-Golomb code with more than 31 leading zeros")
        void overlongExpGolomb() {
            var reader = new MsbBitReader(new byte[8]);
            assertThrows(MediaException.Malformed.class, reader::readUe);
        }

        @Test
        @DisplayName("aligns to the next byte and reports alignment")
        void alignment() {
            var reader = new MsbBitReader(new byte[]{(byte) 0b1010_0000, 0x42});
            reader.read(3);
            assertFalse(reader.isByteAligned());
            reader.alignToByte();
            assertTrue(reader.isByteAligned());
            assertEquals(8, reader.bitPosition());
            assertEquals(0x42, reader.read(8));
        }

        @Test
        @DisplayName("skips across the cache and lands on the right bit")
        void skipping() {
            var data = new byte[64];
            data[40] = (byte) 0b0001_0000;
            var reader = new MsbBitReader(data);
            reader.read(5);
            reader.skip(40 * 8 + 3 - 5);
            assertEquals(1, reader.readBit());
            assertEquals(40 * 8 + 4, reader.bitPosition());
        }

        @Test
        @DisplayName("reads zeros past the end and reports the overrun")
        void overrun() {
            var reader = new MsbBitReader(new byte[]{(byte) 0xFF});
            assertEquals(0xFF0, reader.read(12));
            assertTrue(reader.overrun());
            assertEquals(-4, reader.bitsLeft());
            assertThrows(MediaException.Malformed.class, reader::checkNotOverrun);
        }

        @Test
        @DisplayName("reports an overrun after skipping past the end")
        void skipPastEnd() {
            var reader = new MsbBitReader(new byte[4]);
            reader.skip(1_000);
            assertTrue(reader.overrun());
        }

        @Test
        @DisplayName("pads the writer's partial byte with zeros")
        void writerAlignment() {
            var zeros = new MsbBitWriter();
            zeros.write(0b101, 3);
            zeros.alignWithZeros();
            assertArrayEquals(new byte[]{(byte) 0b1010_0000}, zeros.toByteArray());
        }

        @Test
        @DisplayName("exposes the completed bytes in place, without the partial last byte")
        void backingArray() {
            var writer = new MsbBitWriter(1);
            for (var i = 0; i < 40; i++) {
                writer.write(i, 8);
            }
            writer.write(0b1, 3);
            assertEquals(40, writer.size());
            var array = writer.array();
            assertTrue(array.length >= 40);
            for (var i = 0; i < 40; i++) {
                assertEquals((byte) i, array[i]);
            }
            assertEquals(41, writer.toByteArray().length);
            writer.reset();
            assertEquals(0, writer.size());
        }

        @ParameterizedTest(name = "ue({0}) = {1}, se({0}) = {2}")
        @CsvSource({"0, 1, 1", "1, 010, 010", "2, 011, 00100", "3, 00100, 00110", "6, 00111, 0001100",
                "7, 0001000, 0001110", "14, 0001111, 000011100", "15, 000010000, 000011110"})
        @DisplayName("writes the Exp-Golomb bit patterns of the H.264 syntax")
        void expGolombPatterns(int value, String ue, String se) {
            var unsigned = new MsbBitWriter(1);
            unsigned.writeUe(value);
            assertEquals(ue, bits(unsigned));
            var signed = new MsbBitWriter(1);
            signed.writeSe(value);
            assertEquals(se, bits(signed));
            var negative = new MsbBitWriter(1);
            negative.writeSe(-value);
            assertEquals(value == 0 ? "1" : se.substring(0, se.length() - 1) + "1", bits(negative));
        }

        @Test
        @DisplayName("writes single bits, trailing bits and padding of ones")
        void bitsAndPadding() {
            var writer = new MsbBitWriter(1);
            writer.writeBit(1);
            writer.writeBit(2);
            writer.writeBit(3);
            writer.alignWithOnes();
            writer.alignWithOnes();
            writer.write(0, 2);
            writer.writeTrailingBits();
            writer.writeTrailingBits();
            assertEquals("101111110010000010000000", bits(writer));
            assertTrue(writer.isByteAligned());
            assertEquals(3, writer.size());
        }

        @ParameterizedTest(name = "{0} pending bits")
        @ValueSource(ints = {0, 8, 16, 24, 40})
        @DisplayName("appends whole bytes after any number of pending whole bytes and rejects an unaligned writer")
        void bytes(int pendingBits) {
            var writer = new MsbBitWriter(1);
            var expected = new byte[pendingBits / 8 + 3];
            for (var i = 0; i < pendingBits / 8; i++) {
                writer.write(0x80 | i, 8);
                expected[i] = (byte) (0x80 | i);
            }
            writer.writeBytes(new byte[]{9, 1, 2, 3, 9}, 1, 3);
            System.arraycopy(new byte[]{1, 2, 3}, 0, expected, pendingBits / 8, 3);
            assertArrayEquals(expected, writer.toByteArray());
            assertEquals(expected.length, writer.size());
            writer.write(1, 1);
            assertThrows(IllegalStateException.class, () -> writer.writeBytes(new byte[1], 0, 1));
        }

        @Test
        @DisplayName("rolls back to a mark, including across flushed words")
        void rollbackAcrossWords() {
            var writer = new MsbBitWriter(4);
            writer.write(0b101, 3);
            var mark = writer.mark();
            for (var i = 0; i < 100; i++) {
                writer.write(0x5A5A5, 20);
            }
            writer.rollback(mark);
            writer.write(0b11, 2);
            writer.alignWithZeros();
            assertEquals("10111000", bits(writer));
        }

        @ParameterizedTest(name = "seed offset {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
        @DisplayName("leaves after a rollback exactly the stream written up to the mark")
        void rollbackRandom(int seedOffset) {
            var random = new Random(SEED + seedOffset);
            for (var round = 0; round < 200; round++) {
                var writer = new MsbBitWriter(1);
                var reference = new MsbBitWriter(1);
                var prefix = random.nextInt(40);
                for (var i = 0; i < prefix; i++) {
                    var bits = random.nextInt(33);
                    var value = random.nextInt();
                    writer.write(value, bits);
                    reference.write(value, bits);
                }
                var mark = writer.mark();
                var discarded = random.nextInt(40);
                for (var i = 0; i < discarded; i++) {
                    writer.write(random.nextInt(), random.nextInt(33));
                }
                if (random.nextBoolean()) {
                    writer.size();
                }
                writer.rollback(mark);
                assertEquals(reference.bitCount(), writer.bitCount());
                var suffix = random.nextInt(40);
                for (var i = 0; i < suffix; i++) {
                    var bits = random.nextInt(33);
                    var value = random.nextInt();
                    writer.write(value, bits);
                    reference.write(value, bits);
                }
                assertArrayEquals(reference.toByteArray(), writer.toByteArray(), "round " + round);
            }
        }

        @Test
        @DisplayName("restarts on another range as a new reader over it would")
        void reset() {
            var first = new byte[]{(byte) 0xF0, 0x0F};
            var second = new byte[]{0x00, (byte) 0xAB, (byte) 0xCD, 0x00};
            var reader = new MsbBitReader(first);
            assertEquals(0xF0, reader.read(8));
            reader.read(12);
            assertTrue(reader.overrun());
            reader.reset(second, 1, 2);
            assertEquals(0, reader.bitPosition());
            assertEquals(16, reader.bitsLeft());
            assertEquals(0xABCD, reader.read(16));
            assertFalse(reader.overrun());
            assertThrows(IndexOutOfBoundsException.class, () -> reader.reset(second, 3, 2));
        }

        @ParameterizedTest(name = "position {0}")
        @ValueSource(longs = {0, 1, 7, 8, 63, 64, 65, 200, 511})
        @DisplayName("seeks backward and forward to the bit a sequential read reaches")
        void seek(long position) {
            var random = new Random(SEED + position);
            var data = new byte[64];
            random.nextBytes(data);
            var reader = new MsbBitReader(data, 0, data.length);
            reader.skip(300);
            reader.read(17);
            reader.seek(position);
            var sequential = new MsbBitReader(data);
            sequential.skip(position);
            assertEquals(sequential.bitPosition(), reader.bitPosition());
            assertEquals(sequential.read(1), reader.read(1));
            assertEquals(sequential.bitsLeft(), reader.bitsLeft());
        }

        @Test
        @DisplayName("leaves the reader overrun when it seeks past the end and rejects a negative position")
        void seekPastEnd() {
            var reader = new MsbBitReader(new byte[4], 0, 2);
            reader.seek(17);
            assertTrue(reader.overrun());
            assertThrows(IllegalArgumentException.class, () -> reader.seek(-1));
        }
    }

    @Nested
    @DisplayName("LSB-first")
    class Lsb {
        @ParameterizedTest(name = "seed offset {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("round-trips random fields of every width from 0 to 32 bits")
        void roundTrip(int seedOffset) {
            var random = new Random(SEED ^ seedOffset);
            var widths = new int[5000];
            var values = new int[5000];
            var writer = new LsbBitWriter(4);
            for (var i = 0; i < widths.length; i++) {
                widths[i] = random.nextInt(33);
                values[i] = widths[i] == 32 ? random.nextInt() : (int) (random.nextLong() & ((1L << widths[i]) - 1));
                writer.write(values[i], widths[i]);
            }
            var reader = new LsbBitReader(writer.toByteArray());
            for (var i = 0; i < widths.length; i++) {
                assertEquals(values[i], reader.read(widths[i]), "field " + i + " of width " + widths[i]);
            }
            assertFalse(reader.overrun());
        }

        @Test
        @DisplayName("reads fields least-significant bit first across byte boundaries")
        void bitOrder() {
            var reader = new LsbBitReader(new byte[]{(byte) 0b1100_0101, 0b0000_0011});
            assertEquals(0b101, reader.read(3));
            assertEquals(0b11000, reader.read(5));
            assertEquals(0b11, reader.read(2));
            assertEquals(6, reader.bitsLeft());
        }

        @Test
        @DisplayName("peeks and consumes cached bits for table-driven decoding")
        void peekAndConsume() {
            var reader = new LsbBitReader(new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
            reader.ensure(24);
            assertEquals(0xCDAB, reader.peekCached(16));
            reader.consume(4);
            assertEquals(0xFCDA, reader.peekCached(16));
            assertEquals(0xEFCDA, reader.read(20));
        }

        @Test
        @DisplayName("reads zeros past the end and reports the overrun")
        void overrun() {
            var reader = new LsbBitReader(new byte[]{(byte) 0xFF});
            assertEquals(0xFF, reader.read(12));
            assertTrue(reader.overrun());
            assertThrows(MediaException.Malformed.class, reader::checkNotOverrun);
        }

        @Test
        @DisplayName("skips across the cache and lands on the right bit")
        void skipping() {
            var data = new byte[64];
            data[33] = 0b0000_1000;
            var reader = new LsbBitReader(data);
            reader.read(7);
            reader.skip(33 * 8 + 3 - 7);
            assertEquals(1, reader.readBit());
        }

        @ParameterizedTest(name = "seed offset {0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("writes long fields of every width from 0 to 64 bits as two int writes would")
        void longWrites(int seedOffset) {
            var random = new Random(SEED * 31 + seedOffset);
            var longs = new LsbBitWriter(4);
            var ints = new LsbBitWriter(4);
            var widths = new int[2000];
            var values = new long[2000];
            for (var i = 0; i < widths.length; i++) {
                widths[i] = random.nextInt(65);
                values[i] = random.nextLong();
                longs.writeLong(values[i], widths[i]);
                var low = Math.min(32, widths[i]);
                ints.write((int) values[i], low);
                ints.write((int) (values[i] >>> 32), widths[i] - low);
            }
            assertArrayEquals(ints.toByteArray(), longs.toByteArray());
            var reader = new LsbBitReader(longs.toByteArray());
            for (var i = 0; i < widths.length; i++) {
                var low = Math.min(32, widths[i]);
                var expected = values[i] & (widths[i] == 64 ? -1L : (1L << widths[i]) - 1);
                var read = (reader.read(low) & 0xFFFFFFFFL) | ((long) reader.read(widths[i] - low) << 32);
                assertEquals(expected, read, "field " + i + " of width " + widths[i]);
            }
        }

        @Test
        @DisplayName("masks a 32-bit long value as the Ogg packer masks its unsigned fields")
        void unsignedLongField() {
            var writer = new LsbBitWriter();
            writer.writeLong(0xFFFF_FFFFL, 32);
            writer.writeLong(0x1_0000_0001L, 32);
            assertArrayEquals(new byte[]{-1, -1, -1, -1, 1, 0, 0, 0}, writer.toByteArray());
        }
    }
}
