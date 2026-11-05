/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/BinDecoder.cpp and source/Lib/CommonLib/ContextModelling.cpp: the
 * arithmetic decoding engine and the probability estimation). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur
 * Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Decodes the bins of a CABAC substream (ITU-T H.266 9.3.4.3) and holds its context variables.
 *
 * <p>Every context keeps the two probability estimates {@code pStateIdx0} (10 bits) and {@code pStateIdx1} (14 bits)
 * with their adaptation shifts. Reads past the end of the substream supply zero bits. Instances are not
 * thread-safe.
 */
final class VvcCabac {
    /**
     * Constructs a decoder, initialized for each slice by {@link #initContexts} and {@link #start}.
     */
    VvcCabac() {
    }

    /**
     * The first probability estimate {@code pStateIdx0} of each context.
     */
    private final short[] state0 = new short[VvcContexts.COUNT];
    /**
     * The second probability estimate {@code pStateIdx1} of each context.
     */
    private final short[] state1 = new short[VvcContexts.COUNT];
    /**
     * The first adaptation rate {@code shiftIdx0} of each context.
     */
    private final byte[] shift0 = new byte[VvcContexts.COUNT];
    /**
     * The second adaptation rate {@code shiftIdx1} of each context.
     */
    private final byte[] shift1 = new byte[VvcContexts.COUNT];

    /**
     * The bytes of the substream.
     */
    private byte[] data;
    /**
     * The index of the next byte of the substream.
     */
    private int position;
    /**
     * The index after the last byte of the substream.
     */
    private int end;
    /**
     * {@code ivlCurrRange}.
     */
    private int range;
    /**
     * {@code ivlOffset}, scaled by the bits read ahead.
     */
    private int value;
    /**
     * The number of bits to read before the next byte, negative.
     */
    private int bitsNeeded;
    /**
     * The number of zero bytes read past the end of the substream.
     */
    private int padding;

    /**
     * Initializes every context variable (9.3.2.2).
     *
     * @param initType the initialization type, 0 to 2
     * @param sliceQp {@code SliceQpY}
     */
    void initContexts(int initType, int sliceQp) {
        var init = VvcContexts.INIT[initType];
        var shifts = VvcContexts.INIT[3];
        var qp = Math.clamp(sliceQp, 0, 63);
        for (var i = 0; i < VvcContexts.COUNT; i++) {
            var initValue = init[i];
            var slope = (initValue >> 3) - 4;
            var offset = (initValue & 7) * 18 + 1;
            var pre = Math.clamp(((slope * (qp - 16)) >> 1) + offset, 1, 127);
            state0[i] = (short) (pre << 3);
            state1[i] = (short) (pre << 7);
            var shiftIdx = shifts[i];
            var s0 = (shiftIdx >> 2) + 2;
            shift0[i] = (byte) s0;
            shift1[i] = (byte) ((shiftIdx & 3) + 3 + s0);
        }
    }

    /**
     * Copies the context variables of another decoder (the storage and synchronization processes of 9.3.2.3 and
     * 9.3.2.4).
     *
     * @param other the source
     */
    void copyContextsFrom(VvcCabac other) {
        System.arraycopy(other.state0, 0, state0, 0, VvcContexts.COUNT);
        System.arraycopy(other.state1, 0, state1, 0, VvcContexts.COUNT);
        System.arraycopy(other.shift0, 0, shift0, 0, VvcContexts.COUNT);
        System.arraycopy(other.shift1, 0, shift1, 0, VvcContexts.COUNT);
    }

    /**
     * Starts the arithmetic decoder on a byte-aligned substream (9.3.2.5).
     *
     * @param data the buffer
     * @param offset the first byte of the substream
     * @param end one past its last byte
     */
    void start(byte[] data, int offset, int end) {
        this.data = data;
        this.position = offset;
        this.end = end;
        padding = 0;
        range = 510;
        value = (readByte() << 8) | readByte();
        bitsNeeded = -8;
    }

    /**
     * Reads the next byte of the substream, or zero past its end.
     *
     * @return the byte
     */
    private int readByte() {
        if (position < end) {
            return data[position++] & 0xFF;
        }
        padding++;
        return 0;
    }

    /**
     * {@return whether the decoder has consumed bytes past the end of its substream}
     */
    boolean overrun() {
        return padding > 2;
    }

    /**
     * Decodes a context-coded bin.
     *
     * @param ctx the context index
     * @return the bin
     */
    int decodeBin(int ctx) {
        int s0 = state0[ctx];
        int s1 = state1[ctx];
        var pState = s1 + (s0 << 4);
        var mps = pState >> 14;
        var lps = (((range >> 5) * ((mps != 0 ? 32767 - pState : pState) >> 9)) >> 1) + 4;
        range -= lps;
        var scaled = range << 7;
        int bin;
        if (value < scaled) {
            bin = mps;
            if (range < 256) {
                range <<= 1;
                value <<= 1;
                if (++bitsNeeded == 0) {
                    value |= readByte();
                    bitsNeeded = -8;
                }
            }
        } else {
            bin = mps ^ 1;
            var bits = Integer.numberOfLeadingZeros(lps) - 23;
            value = (value - scaled) << bits;
            range = lps << bits;
            bitsNeeded += bits;
            if (bitsNeeded >= 0) {
                value |= readByte() << bitsNeeded;
                bitsNeeded -= 8;
            }
        }
        int sh0 = shift0[ctx];
        int sh1 = shift1[ctx];
        state0[ctx] = (short) (s0 - (s0 >> sh0) + ((1023 * bin) >> sh0));
        state1[ctx] = (short) (s1 - (s1 >> sh1) + ((16383 * bin) >> sh1));
        return bin;
    }

    /**
     * Decodes a bypass bin.
     *
     * @return the bin
     */
    int decodeBypass() {
        value <<= 1;
        if (++bitsNeeded >= 0) {
            value |= readByte();
            bitsNeeded = -8;
        }
        var scaled = range << 7;
        if (value >= scaled) {
            value -= scaled;
            return 1;
        }
        return 0;
    }

    /**
     * Decodes bypass bins, most significant first.
     *
     * @param count the number of bins, from 0 to 32
     * @return the bins
     */
    int decodeBypassBins(int count) {
        var bins = 0;
        for (var i = 0; i < count; i++) {
            bins = (bins << 1) | decodeBypass();
        }
        return bins;
    }

    /**
     * Decodes a bin before termination ({@code end_of_slice_segment_flag} and similar).
     *
     * @return the bin
     */
    int decodeTerminate() {
        range -= 2;
        var scaled = range << 7;
        if (value >= scaled) {
            return 1;
        }
        if (range < 256) {
            range <<= 1;
            value <<= 1;
            if (++bitsNeeded == 0) {
                value |= readByte();
                bitsNeeded = -8;
            }
        }
        return 0;
    }

    /**
     * Decodes the bypass bins of {@code abs_remainder} or {@code dec_abs_level} (9.3.3.11, 9.3.3.12): a Rice code
     * whose prefix continues as a limited Exp-Golomb code past {@code cutoff}.
     *
     * @param rice the Rice parameter
     * @param cutoff the prefix length coded in Rice form
     * @param maxLog2Range {@code log2TransformRange}, normally 15
     * @return the value
     */
    int decodeRemAbs(int rice, int cutoff, int maxLog2Range) {
        var maxPrefix = 32 - maxLog2Range;
        var prefix = 0;
        while (prefix < maxPrefix && decodeBypass() != 0) {
            prefix++;
        }
        int length;
        int offset;
        if (prefix < cutoff) {
            offset = prefix << rice;
            length = rice;
        } else {
            offset = ((1 << (prefix - cutoff)) + cutoff - 1) << rice;
            length = rice + (prefix == maxPrefix ? maxLog2Range - rice : prefix - cutoff);
        }
        return offset + decodeBypassBins(length);
    }

    /**
     * {@return the index one past the last byte the decoder has read}
     */
    int position() {
        return position;
    }
}
