/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * tools/entropy/bitstrm.cpp and huffman.cpp: CInBitStream and CHuffmanDecoder). Copyright 1996-2000 Microsoft
 * Corporation and the contributors named in the sources; ISO/IEC free license for products conforming to ISO/IEC
 * 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * Reads bits most significant first from a byte array ({@code CInBitStream}). Bytes past the end read as
 * {@code 0xFF}, as the reference software reads them from a file at its end; reading far past the end throws
 * {@link CorruptStream}.
 */
final class InBits {
    /**
     * The most bits that may be read past the end before the data counts as corrupt.
     */
    private static final long OVERRUN_LIMIT = 1 << 16;

    private byte[] data = new byte[0];
    private int length;
    private long position;
    private long bookmark;

    void load(byte[] bytes, int offset, int count) {
        if (data.length < count) {
            data = new byte[Math.max(count, data.length * 2)];
        }
        System.arraycopy(bytes, offset, data, 0, count);
        length = count;
        position = 0;
    }

    private int byteAt(long index) {
        return index < length ? data[(int) index] & 0xFF : 0xFF;
    }

    /**
     * {@return the number of bits read ({@code getCounter})}
     */
    int counter() {
        return (int) position;
    }

    /**
     * {@return the number of bits left in the current byte, 0 at a byte boundary ({@code m_uNumOfBitsInBuffer})}
     */
    int bitsInBuffer() {
        var used = (int) (position & 7);
        return used == 0 ? 0 : 8 - used;
    }

    boolean eof() {
        return bitsInBuffer() == 0 && position >= (long) length * 8;
    }

    boolean exhausted() {
        return position > (long) length * 8;
    }

    int getBits(int count) {
        if (count == 0) {
            return 0;
        }
        var value = peekAt(position, count);
        position += count;
        if (position > (long) length * 8 + OVERRUN_LIMIT) {
            throw CorruptStream.INSTANCE;
        }
        return value;
    }

    int peekBits(int count) {
        return peekAt(position, count);
    }

    private int peekAt(long at, int count) {
        if (count == 0) {
            return 0;
        }
        long value = 0;
        var bit = at;
        var left = count;
        while (left > 0) {
            var index = bit >>> 3;
            var inByte = (int) (bit & 7);
            var available = 8 - inByte;
            var take = Math.min(available, left);
            var b = byteAt(index);
            var chunk = (b >>> (available - take)) & ((1 << take) - 1);
            value = (value << take) | chunk;
            left -= take;
            bit += take;
        }
        return (int) value;
    }

    /**
     * {@return whether the bit {@code count} bits ahead (1 being the next bit) is set ({@code peekOneBit})}
     */
    boolean peekOneBit(int count) {
        return peekAt(position + count - 1, 1) != 0;
    }

    /**
     * {@return the bits up to the next byte boundary, 8 bits at a boundary ({@code peekBitsTillByteAlign})}
     */
    int peekBitsTillByteAlign() {
        return peekBits(bitsTillByteAlign());
    }

    int bitsTillByteAlign() {
        var bits = bitsInBuffer();
        return bits == 0 ? 8 : bits;
    }

    /**
     * {@return the bits that start at the next byte boundary, after a whole stuffing byte when the position is
     * itself at a boundary ({@code peekBitsFromByteAlign})}
     */
    int peekBitsFromByteAlign(int count) {
        if (count == 0) {
            return 0;
        }
        var aligned = (position + 7) & ~7L;
        if (bitsInBuffer() == 0) {
            aligned = position + 8;
        }
        return peekAt(aligned, count);
    }

    /**
     * Moves to the next byte boundary, first reading {@code extra} bits when already at one ({@code flush}).
     */
    void flush(int extra) {
        if (bitsInBuffer() == 0) {
            getBits(extra);
        }
        position = (position + 7) & ~7L;
    }

    void flush() {
        flush(0);
    }

    void setBookmark() {
        bookmark = position;
    }

    void gotoBookmark() {
        position = bookmark;
    }

    long position() {
        return position;
    }

    void seek(long bit) {
        position = bit;
    }

    /**
     * {@return the length of the data, in bytes}
     */
    int length() {
        return length;
    }
}
