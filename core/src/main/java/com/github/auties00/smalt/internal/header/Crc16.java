package com.github.auties00.smalt.internal.header;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Computes the CRC-16 of polynomial {@code 0x8005} and the CRC-8 of polynomial {@code 0x07}, both
 * most significant bit first and without a final inversion.
 *
 * <p>The CRC-16 ({@code x^16 + x^15 + x^2 + 1}) is the checksum of FLAC frames (initial value
 * zero), of MPEG audio frames with protection (initial value {@code 0xFFFF}) and of AC-3 sync
 * frames; the caller chooses the initial value. The CRC-8 ({@code x^8 + x^2 + x + 1}, initial
 * value zero) is the checksum of FLAC frame headers. This class is thread-safe.
 *
 * @implNote This implementation computes the CRC-16 with the slicing-by-16 technique: sixteen
 * tables hold the contribution of a byte followed by zero to fifteen zero bytes, so sixteen input
 * bytes are folded per iteration. Only the first two bytes of each block combine with the running
 * checksum, so the lookups of the other fourteen proceed independently of the previous block and
 * the loop-carried dependency is two lookups deep. The CRC-8 is only ever computed over a header
 * of at most sixteen bytes and uses a single table.
 */
public final class Crc16 {
    /**
     * The CRC-8 contribution of each byte value.
     */
    private static final byte[] CRC8 = crc8Table();

    /**
     * The CRC-16 contribution of a byte value followed by {@code k} zero bytes, for {@code k}
     * from 0 to 15, stored at {@code k * 256 + value}.
     */
    private static final char[] CRC16 = crc16Tables();

    /**
     * The big-endian {@code long} view used to load eight bytes at a time.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Prevents instantiation.
     */
    private Crc16() {
        throw new AssertionError();
    }

    /**
     * Computes the CRC-8 of a byte range from an initial value of zero.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the checksum, from 0 to 255
     */
    public static int crc8(byte[] data, int offset, int length) {
        var crc = 0;
        var end = offset + length;
        for (var i = offset; i < end; i++) {
            crc = CRC8[(crc ^ data[i]) & 0xFF] & 0xFF;
        }
        return crc;
    }

    /**
     * Continues a CRC-16 over a byte range.
     *
     * <p>{@code crc16(crc16(init, data, 0, n), data, n, m)} equals {@code crc16(init, data, 0, n + m)}.
     *
     * @param crc the checksum of the preceding bytes, or the initial value for the first range, from 0 to 65535
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the checksum of the preceding bytes followed by the range, from 0 to 65535
     */
    public static int crc16(int crc, byte[] data, int offset, int length) {
        var table = CRC16;
        var i = offset;
        var end = offset + length;
        for (; i + 16 <= end; i += 16) {
            var high = (long) LONG_BE.get(data, i);
            var low = (long) LONG_BE.get(data, i + 8);
            var rest = table[13 * 256 + ((int) (high >>> 40) & 0xFF)]
                    ^ table[12 * 256 + ((int) (high >>> 32) & 0xFF)]
                    ^ table[11 * 256 + ((int) (high >>> 24) & 0xFF)]
                    ^ table[10 * 256 + ((int) (high >>> 16) & 0xFF)]
                    ^ table[9 * 256 + ((int) (high >>> 8) & 0xFF)]
                    ^ table[8 * 256 + ((int) high & 0xFF)]
                    ^ table[7 * 256 + (int) (low >>> 56)]
                    ^ table[6 * 256 + ((int) (low >>> 48) & 0xFF)]
                    ^ table[5 * 256 + ((int) (low >>> 40) & 0xFF)]
                    ^ table[4 * 256 + ((int) (low >>> 32) & 0xFF)]
                    ^ table[3 * 256 + ((int) (low >>> 24) & 0xFF)]
                    ^ table[2 * 256 + ((int) (low >>> 16) & 0xFF)]
                    ^ table[256 + ((int) (low >>> 8) & 0xFF)]
                    ^ table[(int) low & 0xFF];
            var top = (int) (high >>> 48) ^ crc;
            crc = table[15 * 256 + (top >>> 8)] ^ table[14 * 256 + (top & 0xFF)] ^ rest;
        }
        for (; i < end; i++) {
            crc = ((crc << 8) & 0xFFFF) ^ table[((crc >>> 8) ^ data[i]) & 0xFF];
        }
        return crc;
    }

    /**
     * Builds the CRC-8 table.
     *
     * @return the contribution of each byte value
     */
    private static byte[] crc8Table() {
        var table = new byte[256];
        for (var value = 0; value < 256; value++) {
            var crc = value;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1;
            }
            table[value] = (byte) crc;
        }
        return table;
    }

    /**
     * Builds the sixteen CRC-16 slicing tables.
     *
     * @return the tables, concatenated
     */
    private static char[] crc16Tables() {
        var table = new char[16 * 256];
        for (var value = 0; value < 256; value++) {
            var crc = value << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x8005 : crc << 1;
            }
            table[value] = (char) (crc & 0xFFFF);
        }
        for (var slice = 1; slice < 16; slice++) {
            for (var value = 0; value < 256; value++) {
                int previous = table[(slice - 1) * 256 + value];
                table[slice * 256 + value] = (char) (((previous << 8) & 0xFFFF) ^ table[previous >>> 8]);
            }
        }
        return table;
    }
}
