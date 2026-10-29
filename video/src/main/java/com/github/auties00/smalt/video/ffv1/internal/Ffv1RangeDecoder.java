package com.github.auties00.smalt.video.ffv1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the binary and scalar symbols of the FFV1 range coder (RFC 9043, section 3.8.1).
 *
 * <p>States are unsigned bytes held in {@code byte} arrays. Bytes past the end of the coded range read as zero,
 * so a corrupt stream decodes to unspecified symbols without reading outside its range. Instances are not
 * thread-safe.
 */
public final class Ffv1RangeDecoder {
    private int[] one = Ffv1Tables.DEFAULT_ONE_STATE;

    private int[] zero = Ffv1Tables.DEFAULT_ZERO_STATE;

    private final byte[] sentinel = new byte[1];

    private byte[] data;

    /**
     * The number of bytes consumed from the start of the range, those past its end included.
     */
    private int consumed;

    private int start;

    private int end;

    private int range;

    private int low;

    /**
     * Starts decoding a range of bytes with the default state transition table.
     *
     * @param data the bytes
     * @param offset the first byte of the range
     * @param end the end of the range, exclusive
     */
    public void reset(byte[] data, int offset, int end) {
        this.data = data;
        this.start = offset;
        this.end = end;
        this.consumed = 0;
        this.one = Ffv1Tables.DEFAULT_ONE_STATE;
        this.zero = Ffv1Tables.DEFAULT_ZERO_STATE;
        range = 0xFF00;
        low = next() << 8 | next();
        if (low >= range) {
            low = range;
        }
    }

    /**
     * Moves the end of the coded range, for a range that was started before its end was known.
     *
     * @param end the new end, exclusive
     */
    public void limit(int end) {
        this.end = end;
    }

    /**
     * Switches to another state transition table.
     *
     * @param one the table for a decoded 1
     * @param zero the table for a decoded 0
     */
    public void transitions(int[] one, int[] zero) {
        this.one = one;
        this.zero = zero;
    }

    private int next() {
        var index = start + consumed++;
        return index < end ? data[index] & 0xFF : 0;
    }

    /**
     * Decodes one binary symbol and updates its state.
     *
     * @param states the adaptive context states, updated in place
     * @param index the index of the state
     * @return 0 or 1
     */
    public int bit(byte[] states, int index) {
        var state = states[index] & 0xFF;
        var split = range * state >>> 8;
        range -= split;
        int bit;
        if (low < range) {
            states[index] = (byte) zero[state];
            bit = 0;
        } else {
            low -= range;
            states[index] = (byte) one[state];
            range = split;
            bit = 1;
        }
        if (range < 0x100) {
            range <<= 8;
            low = low << 8 | next();
        }
        return bit;
    }

    /**
     * Decodes a scalar symbol (RFC 9043, figure 21).
     *
     * @param states the adaptive context states, updated in place
     * @param base the index of the symbol's first state; the symbol uses 32 states
     * @param signed whether the symbol has a sign
     * @return the value
     * @throws MediaException.Malformed if the exponent exceeds 31 bits
     */
    public int symbol(byte[] states, int base, boolean signed) {
        if (bit(states, base) != 0) {
            return 0;
        }
        var e = 0;
        while (bit(states, base + 1 + Math.min(e, 9)) != 0) {
            if (++e > 31) {
                throw new MediaException.Malformed("FFV1 symbol exponent exceeds 31 bits");
            }
        }
        var a = 1;
        for (var i = e - 1; i >= 0; i--) {
            a = a << 1 | bit(states, base + 22 + Math.min(i, 9));
        }
        return signed && bit(states, base + 11 + Math.min(e, 10)) != 0 ? -a : a;
    }

    /**
     * Decodes an unsigned scalar symbol that must lie in a range.
     *
     * @param states the adaptive context states, updated in place
     * @param name the name of the field, for the failure
     * @param max the largest valid value
     * @return the value
     * @throws MediaException.Malformed if the value is negative or exceeds {@code max}
     */
    public int unsigned(byte[] states, String name, int max) {
        var value = symbol(states, 0, false);
        if (value < 0 || value > max) {
            throw new MediaException.Malformed("FFV1 " + name + " " + Integer.toUnsignedString(value));
        }
        return value;
    }

    /**
     * Ends a range-coded header followed by Golomb Rice data, and returns where that data starts.
     *
     * <p>A version 3 slice header ends with the sentinel symbol (state 129), which this method decodes and
     * discards; the data starts one byte before the next byte the decoder reads after it. A version 0 or 1 frame
     * header has no sentinel: the data starts one byte before the next byte the decoder reads after the header's
     * last symbol.
     *
     * @param sentinel whether the header ends with the sentinel symbol
     * @return the index of the first byte after the range-coded bytes
     */
    public int terminate(boolean sentinel) {
        if (sentinel) {
            this.sentinel[0] = (byte) 129;
            bit(this.sentinel, 0);
        }
        return start + consumed - 1;
    }

    /**
     * {@return the index of the next byte the decoder would read}
     */
    public int position() {
        return start + consumed;
    }
}
