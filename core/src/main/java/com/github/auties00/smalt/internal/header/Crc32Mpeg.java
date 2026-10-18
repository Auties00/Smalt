package com.github.auties00.smalt.internal.header;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Computes the CRC-32 of polynomial {@code 0x04C11DB7}, most significant bit first and without a
 * final inversion (CRC-32/MPEG-2 when started from {@code 0xFFFFFFFF}).
 *
 * <p>The caller chooses the initial value: MPEG-2 transport stream sections start from
 * {@code 0xFFFFFFFF} (ISO/IEC 13818-1 Annex A), Ogg pages from zero (RFC 3533 section 6). Over a
 * whole transport stream section, its trailing {@code CRC_32} field included, the result is zero
 * when the section is intact. This class is thread-safe.
 *
 * @implNote This implementation folds eight bytes per iteration with eight slicing tables; the
 * tail is folded a byte at a time with the first table.
 */
public final class Crc32Mpeg {
    /**
     * The initial value of the MPEG-2 checksums.
     */
    public static final int MPEG_INIT = 0xFFFFFFFF;

    /**
     * The contribution of a byte value followed by {@code k} zero bytes, for {@code k} from 0 to
     * 7, stored at {@code k * 256 + value}.
     */
    private static final int[] TABLES = tables();

    /**
     * The big-endian {@code int} view used to load four bytes at a time.
     */
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Prevents instantiation.
     */
    private Crc32Mpeg() {
        throw new AssertionError();
    }

    /**
     * Continues the checksum over a byte range.
     *
     * <p>{@code update(update(init, data, 0, n), data, n, m)} equals
     * {@code update(init, data, 0, n + m)}.
     *
     * @param crc the checksum of the preceding bytes, or the initial value for the first range
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the checksum of the preceding bytes followed by the range
     * @throws IndexOutOfBoundsException if the range is out of bounds
     */
    public static int update(int crc, byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        var table = TABLES;
        var i = offset;
        var end = offset + length;
        for (; i + 8 <= end; i += 8) {
            var high = (int) INT_BE.get(data, i) ^ crc;
            var low = (int) INT_BE.get(data, i + 4);
            crc = table[7 * 256 + (high >>> 24)]
                    ^ table[6 * 256 + ((high >>> 16) & 0xFF)]
                    ^ table[5 * 256 + ((high >>> 8) & 0xFF)]
                    ^ table[4 * 256 + (high & 0xFF)]
                    ^ table[3 * 256 + (low >>> 24)]
                    ^ table[2 * 256 + ((low >>> 16) & 0xFF)]
                    ^ table[256 + ((low >>> 8) & 0xFF)]
                    ^ table[low & 0xFF];
        }
        for (; i < end; i++) {
            crc = (crc << 8) ^ table[((crc >>> 24) ^ data[i]) & 0xFF];
        }
        return crc;
    }

    /**
     * Builds the eight slicing tables.
     *
     * @return the tables, concatenated
     */
    private static int[] tables() {
        var table = new int[8 * 256];
        for (var value = 0; value < 256; value++) {
            var crc = value << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
            table[value] = crc;
        }
        for (var slice = 1; slice < 8; slice++) {
            for (var value = 0; value < 256; value++) {
                var previous = table[(slice - 1) * 256 + value];
                table[slice * 256 + value] = (previous << 8) ^ table[previous >>> 24];
            }
        }
        return table;
    }
}
