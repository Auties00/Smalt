package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the FLAC checksums, the frame header parser and the STREAMINFO parser against the
 * catalog check values of the CRC definitions and hand-assembled headers.
 */
class FlacInternalsTest {
    /** Bitwise reference CRC-16/UMTS (poly 0x8005, init 0, MSB first). */
    static int referenceCrc16(byte[] data, int offset, int length) {
        var crc = 0;
        for (var i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    /** Assembles a frame header from its fields and appends the CRC-8. */
    public static byte[] header(boolean variable, int blockCode, int rateCode, int assignment, int sizeCode, long number,
                                int blockExtra, int rateExtra) {
        var out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(variable ? 0xF9 : 0xF8);
        out.write(blockCode << 4 | rateCode);
        out.write(assignment << 4 | sizeCode << 1);
        writeNumber(out, number);
        if (blockCode == 6) {
            out.write(blockExtra);
        } else if (blockCode == 7) {
            out.write(blockExtra >>> 8);
            out.write(blockExtra & 0xFF);
        }
        if (rateCode == 12) {
            out.write(rateExtra);
        } else if (rateCode == 13 || rateCode == 14) {
            out.write(rateExtra >>> 8);
            out.write(rateExtra & 0xFF);
        }
        var bytes = out.toByteArray();
        var result = Arrays.copyOf(bytes, bytes.length + 1);
        result[bytes.length] = (byte) Crc16.crc8(bytes, 0, bytes.length);
        return result;
    }

    static void writeNumber(ByteArrayOutputStream out, long value) {
        if (value < 0x80) {
            out.write((int) value);
            return;
        }
        var extra = value < 1L << 11 ? 1 : value < 1L << 16 ? 2 : value < 1L << 21 ? 3 : value < 1L << 26 ? 4 : value < 1L << 31 ? 5 : 6;
        var lead = extra == 6 ? 0xFE : (0xFF << (7 - extra)) & 0xFF;
        out.write(lead | (int) (value >>> (6 * extra)));
        for (var i = extra - 1; i >= 0; i--) {
            out.write(0x80 | (int) ((value >>> (6 * i)) & 0x3F));
        }
    }

    @Nested
    @DisplayName("checksums")
    class Checksums {
        @Test
        @DisplayName("CRC-8 and CRC-16 match the catalog check values of 123456789")
        void checkValues() {
            var check = "123456789".getBytes(StandardCharsets.US_ASCII);
            assertEquals(0xF4, Crc16.crc8(check, 0, check.length));
            assertEquals(0xFEE8, Crc16.crc16(0, check, 0, check.length));
        }

        @Test
        @DisplayName("sliced CRC-16 equals the bitwise definition for every length, offset and split point")
        void slicedCrc16() {
            var random = new SplittableRandom(42);
            var data = new byte[300];
            for (var i = 0; i < data.length; i++) {
                data[i] = (byte) random.nextInt(256);
            }
            for (var offset = 0; offset < 20; offset++) {
                for (var length = 0; offset + length <= data.length; length += 7) {
                    var expected = referenceCrc16(data, offset, length);
                    assertEquals(expected, Crc16.crc16(0, data, offset, length));
                    var split = length / 3;
                    var partial = Crc16.crc16(0, data, offset, split);
                    assertEquals(expected, Crc16.crc16(partial, data, offset + split, length - split));
                }
            }
        }
    }

    @Nested
    @DisplayName("frame header")
    class FrameHeader {
        @ParameterizedTest(name = "block code {0} -> {1}")
        @CsvSource({"1, 192", "2, 576", "3, 1152", "4, 2304", "5, 4608", "8, 256", "9, 512", "10, 1024", "11, 2048",
                "12, 4096", "13, 8192", "14, 16384", "15, 32768"})
        @DisplayName("decodes every table block size code")
        void blockSizes(int code, int expected) {
            var bytes = header(false, code, 9, 1, 4, 0, 0, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(expected, parsed.blockSize());
            assertEquals(bytes.length, parsed.size());
        }

        @ParameterizedTest(name = "uncommon block size {0}")
        @ValueSource(ints = {1, 16, 255, 256, 257, 4097, 65535})
        @DisplayName("decodes 8- and 16-bit uncommon block sizes")
        void uncommonBlockSizes(int size) {
            var bytes = size <= 256 ? header(false, 6, 9, 1, 4, 5, size - 1, 0) : header(false, 7, 9, 1, 4, 5, size - 1, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(size, parsed.blockSize());
            assertEquals(5, parsed.number());
        }

        @ParameterizedTest(name = "rate code {0} -> {1}")
        @CsvSource({"0, 0", "1, 88200", "2, 176400", "3, 192000", "4, 8000", "5, 16000", "6, 22050", "7, 24000",
                "8, 32000", "9, 44100", "10, 48000", "11, 96000"})
        @DisplayName("decodes every table sample rate code, zero deferring to STREAMINFO")
        void sampleRates(int code, int expected) {
            var bytes = header(false, 12, code, 0, 4, 0, 0, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(expected, parsed.sampleRate());
        }

        @ParameterizedTest(name = "rate code {0} value {1} -> {2}")
        @CsvSource({"12, 37, 37000", "13, 50001, 50001", "14, 1234, 12340"})
        @DisplayName("decodes the kHz, Hz and tens-of-Hz uncommon sample rates")
        void uncommonRates(int code, int value, int expected) {
            var bytes = header(false, 12, code, 0, 4, 0, 0, value);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(expected, parsed.sampleRate());
        }

        @ParameterizedTest(name = "size code {0} -> {1}")
        @CsvSource({"0, 0", "1, 8", "2, 12", "4, 16", "5, 20", "6, 24", "7, 32"})
        @DisplayName("decodes every sample size code")
        void sampleSizes(int code, int expected) {
            var bytes = header(false, 12, 9, 1, code, 0, 0, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(expected, parsed.bitsPerSample());
        }

        @ParameterizedTest(name = "number {0}")
        @ValueSource(longs = {0, 127, 128, 2047, 2048, 65535, 65536, (1L << 21) - 1, 1L << 21, (1L << 31) - 1, 1L << 31,
                (1L << 36) - 1})
        @DisplayName("decodes every length of the extended UTF-8 sample number")
        void numbers(long number) {
            var bytes = header(true, 12, 9, 1, 4, number, 0, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(number, parsed.number());
            assertTrue(parsed.variableBlocking());
        }

        @ParameterizedTest(name = "assignment {0} -> {1} channels")
        @CsvSource({"0, 1", "1, 2", "5, 6", "7, 8", "8, 2", "9, 2", "10, 2"})
        @DisplayName("maps channel assignments to channel counts")
        void channels(int assignment, int channels) {
            var bytes = header(false, 12, 9, assignment, 4, 0, 0, 0);
            var parsed = new FlacFrameHeader();
            assertTrue(parsed.parse(bytes, 0, bytes.length));
            assertEquals(channels, parsed.channels());
            assertEquals(assignment, parsed.channelAssignment());
        }

        @Test
        @DisplayName("rejects reserved codes, a 65536 block size, bad numbers, a bad CRC-8 and truncation")
        void rejects() {
            var parsed = new FlacFrameHeader();
            var good = header(false, 12, 9, 1, 4, 3, 0, 0);
            assertTrue(parsed.parse(good, 0, good.length));
            for (var bad : new byte[][]{
                    header(false, 0, 9, 1, 4, 3, 0, 0),
                    header(false, 12, 15, 1, 4, 3, 0, 0),
                    header(false, 12, 9, 11, 4, 3, 0, 0),
                    header(false, 12, 9, 15, 4, 3, 0, 0),
                    header(false, 12, 9, 1, 3, 3, 0, 0),
                    header(false, 7, 9, 1, 4, 3, 65535, 0),
                    header(false, 12, 12, 1, 4, 3, 0, 0),
                    header(false, 12, 9, 1, 4, 1L << 31, 0, 0)}) {
                assertFalse(parsed.parse(bad, 0, bad.length), () -> HexFormat.of().formatHex(bad));
            }
            var reservedBit = good.clone();
            reservedBit[3] |= 1;
            reservedBit[reservedBit.length - 1] = (byte) Crc16.crc8(reservedBit, 0, reservedBit.length - 1);
            assertFalse(parsed.parse(reservedBit, 0, reservedBit.length));
            var badCrc = good.clone();
            badCrc[badCrc.length - 1] ^= 1;
            assertFalse(parsed.parse(badCrc, 0, badCrc.length));
            var badContinuation = header(false, 12, 9, 1, 4, 300, 0, 0);
            badContinuation[5] &= 0x3F;
            badContinuation[badContinuation.length - 1] = (byte) Crc16.crc8(badContinuation, 0, badContinuation.length - 1);
            assertFalse(parsed.parse(badContinuation, 0, badContinuation.length));
            for (var length = 0; length < good.length; length++) {
                assertFalse(parsed.parse(good, 0, length), "truncated to " + length);
            }
            assertFalse(parsed.parse(new byte[]{(byte) 0xFF, (byte) 0xFA, 0, 0, 0, 0}, 0, 6));
        }
    }

    @Nested
    @DisplayName("STREAMINFO")
    class StreamInfo {
        static byte[] body(int minBlock, int maxBlock, int rate, int channels, int bits, long total) {
            var data = new byte[34];
            data[0] = (byte) (minBlock >>> 8);
            data[1] = (byte) minBlock;
            data[2] = (byte) (maxBlock >>> 8);
            data[3] = (byte) maxBlock;
            var packed = (long) rate << 44 | (long) (channels - 1) << 41 | (long) (bits - 1) << 36 | total;
            for (var i = 0; i < 8; i++) {
                data[10 + i] = (byte) (packed >>> (56 - 8 * i));
            }
            data[33] = 7;
            return data;
        }

        @Test
        @DisplayName("parses every field, including the 36-bit sample count")
        void parses() {
            var info = FlacStreamInfo.parse(body(16, 65535, 655350, 8, 32, (1L << 36) - 1), 0);
            assertEquals(16, info.minBlockSize());
            assertEquals(65535, info.maxBlockSize());
            assertEquals(655350, info.sampleRate());
            assertEquals(8, info.channels());
            assertEquals(32, info.bitsPerSample());
            assertEquals((1L << 36) - 1, info.totalSamples());
            assertTrue(info.hasMd5());
        }

        @Test
        @DisplayName("rejects a zero sample rate, fewer than four bits and a maximum block size below 16")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> FlacStreamInfo.parse(body(4096, 4096, 0, 2, 16, 0), 0));
            assertThrows(MediaException.Malformed.class, () -> FlacStreamInfo.parse(body(4096, 4096, 44100, 2, 3, 0), 0));
            assertThrows(MediaException.Malformed.class, () -> FlacStreamInfo.parse(body(1, 1, 44100, 2, 16, 0), 0));
        }
    }
}
