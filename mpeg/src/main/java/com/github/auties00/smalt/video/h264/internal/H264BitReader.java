package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reads the raw byte sequence payload (RBSP) of one H.264 or HEVC NAL unit most-significant bit
 * first, with the descriptors of ITU-T H.264 7.2 and H.265 7.2.
 *
 * <p>The reader works on a buffer that holds at least {@link #PADDING} zero bytes after the
 * payload, so a refill is always one unaligned 64-bit load. Reads past the end of the payload
 * return zero bits and never fail; callers detect truncation with {@link #overrun()} at
 * syntax-structure boundaries. Because missing bits read as zero, every Exp-Golomb read is
 * bounded by the 32 leading zero limit, so no read loops forever.
 */
public final class H264BitReader {
    /**
     * The number of zero bytes that must follow the payload in the buffer.
     */
    public static final int PADDING = 16;

    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The buffer holding the payload followed by at least {@link #PADDING} zero bytes.
     */
    byte[] data;

    /**
     * The index one past the last payload byte.
     */
    private int end;

    /**
     * The index of the first payload byte.
     */
    private int start;

    /**
     * The index of the next byte not yet loaded into {@link #cache}.
     */
    int next;

    /**
     * The largest index at which a 64-bit load stays inside {@link #data}.
     */
    int loadLimit;

    /**
     * The upcoming bits, left-aligned: bit 63 is the next bit to read.
     */
    long cache;

    /**
     * The number of valid bits in {@link #cache}.
     */
    int count;

    /**
     * Constructs an unpositioned reader; {@link #init(byte[], int, int)} must be called before
     * reading.
     */
    public H264BitReader() {
        this.data = new byte[PADDING];
        this.loadLimit = PADDING - 8;
    }

    /**
     * Positions the reader at the start of a payload.
     *
     * @param data the buffer; the {@link #PADDING} bytes after {@code offset + length} must be
     * zero
     * @param offset the index of the first payload byte
     * @param length the payload length in bytes
     */
    public void init(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.next = offset;
        this.loadLimit = data.length - 8;
        this.cache = 0;
        this.count = 0;
    }

    /**
     * Tops the cache up to at least 57 valid bits.
     */
    private void refill() {
        var bytes = (63 - count) >>> 3;
        if (next <= loadLimit) {
            cache |= ((long) LONG_BE.get(data, next)) >>> count;
        }
        next += bytes;
        count += bytes << 3;
    }

    /**
     * Reads an unsigned field of up to 32 bits.
     *
     * @param bits the field width, from 0 to 32
     * @return the field value
     */
    public int readBits(int bits) {
        if (count < bits) {
            refill();
        }
        var value = (int) ((cache >>> 1) >>> (63 - bits));
        cache <<= bits;
        count -= bits;
        return value;
    }

    /**
     * Reads an unsigned field of up to 64 bits.
     *
     * @param bits the field width, from 0 to 64
     * @return the field value
     */
    public long readLong(int bits) {
        if (bits <= 32) {
            return readBits(bits) & 0xFFFFFFFFL;
        }
        var high = (long) readBits(bits - 32) & 0xFFFFFFFFL;
        return (high << 32) | (readBits(32) & 0xFFFFFFFFL);
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     */
    public int readBit() {
        if (count == 0) {
            refill();
        }
        var bit = (int) (cache >>> 63);
        cache <<= 1;
        count--;
        return bit;
    }

    /**
     * Reads one bit as a flag.
     *
     * @return {@code true} when the bit is set
     */
    public boolean readFlag() {
        return readBit() != 0;
    }

    /**
     * Skips up to 32 bits.
     *
     * @param bits the number of bits to skip, from 0 to 32
     */
    public void skipBits(int bits) {
        if (count < bits) {
            refill();
        }
        cache <<= bits;
        count -= bits;
    }

    /**
     * Skips any number of bits.
     *
     * @param bits the number of bits to skip, non-negative
     */
    public void skip(long bits) {
        while (bits > 32) {
            skipBits(32);
            bits -= 32;
        }
        skipBits((int) bits);
    }

    /**
     * Reads an unsigned Exp-Golomb code, {@code ue(v)}.
     *
     * @return the decoded value, from 0 to {@code 2^32 - 2} reduced to an {@code int} range
     * @throws MediaException.Malformed if the code has more than 31 leading zeros or its value
     * exceeds {@link Integer#MAX_VALUE}
     */
    public int readUe() {
        if (count < 32) {
            refill();
        }
        var zeros = Long.numberOfLeadingZeros(cache);
        if (zeros < 16) {
            var length = 2 * zeros + 1;
            var value = (int) (cache >>> (64 - length)) - 1;
            cache <<= length;
            count -= length;
            return value;
        }
        if (zeros > 31) {
            throw new MediaException.Malformed("invalid Exp-Golomb code");
        }
        skipBits(zeros + 1);
        var value = (1L << zeros) - 1 + (readBits(zeros) & 0xFFFFFFFFL);
        if (value > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("Exp-Golomb value " + value + " out of range");
        }
        return (int) value;
    }

    /**
     * Reads an unsigned Exp-Golomb code and checks it against an upper bound.
     *
     * @param max the largest valid value
     * @param name the syntax element, for the error message
     * @return the decoded value, from 0 to {@code max}
     * @throws MediaException.Malformed if the code is invalid or its value exceeds {@code max}
     */
    public int readUe(int max, String name) {
        var value = readUe();
        if (value > max) {
            throw new MediaException.Malformed(name + " out of range: " + value);
        }
        return value;
    }

    /**
     * Reads a signed Exp-Golomb code, {@code se(v)}.
     *
     * @return the decoded value
     * @throws MediaException.Malformed if the code is invalid
     */
    public int readSe() {
        var code = readUe();
        return (code & 1) != 0 ? (code >>> 1) + 1 : -(code >>> 1);
    }

    /**
     * Reads a signed Exp-Golomb code and checks it against a range.
     *
     * @param min the smallest valid value
     * @param max the largest valid value
     * @param name the syntax element, for the error message
     * @return the decoded value, from {@code min} to {@code max}
     * @throws MediaException.Malformed if the code is invalid or its value is outside the range
     */
    public int readSe(int min, int max, String name) {
        var value = readSe();
        if (value < min || value > max) {
            throw new MediaException.Malformed(name + " out of range: " + value);
        }
        return value;
    }

    /**
     * Reads a truncated Exp-Golomb code, {@code te(v)}, with the given range.
     *
     * @param max the largest possible value; positive
     * @return the decoded value
     * @throws MediaException.Malformed if the code is invalid
     */
    public int readTe(int max) {
        if (max == 1) {
            return readBit() ^ 1;
        }
        return readUe();
    }

    /**
     * Skips to the next byte boundary; does nothing when already aligned.
     */
    public void alignToByte() {
        var misaligned = count & 7;
        cache <<= misaligned;
        count -= misaligned;
    }

    /**
     * Returns the number of bits read so far.
     *
     * @return the bit offset from the start of the payload
     */
    public long bitPosition() {
        return (long) (next - start) * 8 - count;
    }

    /**
     * Returns the number of payload bits not yet read.
     *
     * @return the remaining bits, negative once reads ran past the end of the payload
     */
    public long bitsLeft() {
        return (long) (end - next) * 8 + count;
    }

    /**
     * Returns whether any read ran past the end of the payload.
     *
     * @return {@code true} once more bits were read than the payload holds
     */
    public boolean overrun() {
        return bitsLeft() < 0;
    }

    /**
     * Returns the buffer being read.
     *
     * @return the buffer, not a copy
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the index one past the last payload byte.
     *
     * @return the payload end index
     */
    public int end() {
        return end;
    }

    /**
     * Returns the index of the byte holding the next unread bit when the reader is byte
     * aligned.
     *
     * @return the byte index of the read position
     */
    public int bytePosition() {
        return next - (count >>> 3);
    }

    /**
     * Moves the read position to a byte index.
     *
     * @param position the index of the next byte to read, at least the payload start
     */
    public void seekToByte(int position) {
        this.next = position;
        this.cache = 0;
        this.count = 0;
    }

    /**
     * Returns whether the payload holds more syntax data before its {@code rbsp_trailing_bits},
     * the {@code more_rbsp_data()} function of ITU-T H.264 7.2.
     *
     * @param trailingBitPosition the bit position of the {@code rbsp_stop_one_bit}, as returned
     * by {@link com.github.auties00.smalt.internal.nal.NalUnits#stopBitPosition(byte[], int, int)
     * NalUnits.stopBitPosition}
     * @return {@code true} when the read position precedes the stop bit
     */
    public boolean moreRbspData(long trailingBitPosition) {
        return bitPosition() < trailingBitPosition;
    }
}
