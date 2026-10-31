package com.github.auties00.smalt.video.ffv1.internal;

import java.util.Arrays;

/**
 * Encodes the binary and scalar symbols of the FFV1 range coder, producing the bytes {@link Ffv1RangeDecoder}
 * decodes.
 *
 * <p>Instances are not thread-safe; the output buffer is reused across {@link #reset()} calls.
 */
public final class Ffv1RangeEncoder {
    private int[] one = Ffv1Tables.DEFAULT_ONE_STATE;

    private int[] zero = Ffv1Tables.DEFAULT_ZERO_STATE;

    private byte[] out = new byte[1024];

    private int size;

    /**
     * The low end of the interval in the 16-bit window, bit 16 holding a carry into the pending bytes.
     */
    private int low;

    private int range;

    /**
     * The first pending byte, which a carry may still increment.
     */
    private int cache;

    /**
     * The number of pending bytes: the cache and the 0xFF bytes after it.
     */
    private int pending;

    /**
     * Whether the next byte leaving the pending bytes is the leading zero that the decoder never reads.
     */
    private boolean leading;

    /**
     * The state of the sentinel symbol.
     */
    private final byte[] sentinelState = new byte[1];

    /**
     * The number of bytes shifted out of the low register since the string started, the first included.
     */
    private int shifts;

    /**
     * Starts a new range-coded byte string with the default state transition table.
     */
    public void reset() {
        size = 0;
        shifts = 0;
        low = 0;
        range = 0xFF00;
        cache = 0;
        pending = 1;
        leading = true;
        one = Ffv1Tables.DEFAULT_ONE_STATE;
        zero = Ffv1Tables.DEFAULT_ZERO_STATE;
    }

    /**
     * Switches to another state transition table.
     *
     * @param one the table for a coded 1
     * @param zero the table for a coded 0
     */
    public void transitions(int[] one, int[] zero) {
        this.one = one;
        this.zero = zero;
    }

    /**
     * Encodes one binary symbol and updates its state.
     *
     * @param states the adaptive context states, updated in place
     * @param index the index of the state
     * @param bit 0 or 1
     */
    public void bit(byte[] states, int index, int bit) {
        var state = states[index] & 0xFF;
        var split = range * state >>> 8;
        if (bit == 0) {
            range -= split;
            states[index] = (byte) zero[state];
        } else {
            low += range - split;
            range = split;
            states[index] = (byte) one[state];
        }
        if (range < 0x100) {
            range <<= 8;
            shift();
        }
    }

    /**
     * Encodes a scalar symbol (RFC 9043, figure 21).
     *
     * @param states the adaptive context states, updated in place
     * @param base the index of the symbol's first state
     * @param value the value; non-negative unless {@code signed}
     * @param signed whether the symbol has a sign
     */
    public void symbol(byte[] states, int base, int value, boolean signed) {
        if (value == 0) {
            bit(states, base, 1);
            return;
        }
        bit(states, base, 0);
        var magnitude = Math.abs(value);
        var e = 31 - Integer.numberOfLeadingZeros(magnitude);
        for (var i = 0; i < e; i++) {
            bit(states, base + 1 + Math.min(i, 9), 1);
        }
        bit(states, base + 1 + Math.min(e, 9), 0);
        for (var i = e - 1; i >= 0; i--) {
            bit(states, base + 22 + Math.min(i, 9), magnitude >>> i & 1);
        }
        if (signed) {
            bit(states, base + 11 + Math.min(e, 10), value < 0 ? 1 : 0);
        }
    }

    private void shift() {
        shifts++;
        if ((low & 0xFFFF) < 0xFF00 || low >= 0x10000) {
            var carry = low >>> 16;
            var temp = cache;
            do {
                write(temp + carry & 0xFF);
                temp = 0xFF;
            } while (--pending != 0);
            cache = low >>> 8 & 0xFF;
        }
        pending++;
        low = (low & 0xFF) << 8;
    }

    private void write(int value) {
        if (leading) {
            leading = false;
            return;
        }
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) value;
    }

    /**
     * Ends the string so that it decodes whatever bytes follow it.
     */
    public void finish() {
        for (var i = 0; i < 3; i++) {
            shift();
        }
    }

    /**
     * Ends the string for the switch to Golomb Rice data, so that the string decodes correctly with exactly one
     * byte read past it, the first byte of that data.
     *
     * <p>A version 3 slice header ends with the sentinel symbol, a 0 coded with state 129, and the data starts one
     * byte before the next byte a decoder reads after that symbol. A version 0 or 1 frame header codes no sentinel:
     * the data starts one byte before the next byte a decoder reads after the header's last symbol.
     *
     * @param following the first byte that will follow the string
     * @param sentinel whether to code the sentinel symbol
     */
    public void terminate(int following, boolean sentinel) {
        if (sentinel) {
            sentinel();
        }
        low += following - low & 0xFF;
        shift();
        shift();
        var temp = cache;
        do {
            write(temp);
            temp = 0xFF;
        } while (--pending != 0);
        size--;
    }

    /**
     * Codes the sentinel symbol, a 0 with state 129, that ends a version 3 slice header followed by Golomb Rice
     * data and a version 3 range-coded slice: a decoder that reads it has then read exactly one byte past the
     * string once {@link #terminate(int, boolean)} ends it.
     */
    public void sentinel() {
        sentinelState[0] = (byte) 129;
        bit(sentinelState, 0, 0);
    }

    /**
     * {@return the number of bytes the string will hold once {@link #terminate(int, boolean)} ends it without
     * another symbol}
     */
    public int terminatedSize() {
        return shifts + 1;
    }

    /**
     * {@return the number of bytes written}
     */
    public int size() {
        return size;
    }

    /**
     * {@return the buffer holding the written bytes from index 0, valid until the next write}
     */
    public byte[] buffer() {
        return out;
    }
}
