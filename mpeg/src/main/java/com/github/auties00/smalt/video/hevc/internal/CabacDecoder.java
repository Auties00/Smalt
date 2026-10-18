/* Ports HM (source/Lib/TLibDecoder/TDecBinCoderCABAC.cpp, TDecBinCABAC). Copyright (c) 2010-2026, ITU/ISO/IEC;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Decodes bins with the HEVC binary arithmetic decoding engine (ITU-T H.265 9.3.4.3) and holds
 * the context variables of the slice segment being decoded.
 *
 * <p>The offset register keeps 7 bits more than the specification's 9-bit {@code ivlOffset}, so
 * bytes are loaded whole: {@link #bitsNeeded} counts from -8 up to 0, when the next byte is
 * appended. Reads past the end of the slice segment data yield zero bytes; the decoder detects
 * the overrun with {@link #overrun()} at coding tree unit boundaries.
 *
 * @implNote This implementation follows the decoding engine of the HM reference software
 * ({@code TDecBinCABAC} in {@code source/Lib/TLibDecoder/TDecBinCoderCABAC.cpp}).
 */
public final class CabacDecoder {
    /**
     * The next context state and most probable symbol after a bin, indexed
     * {@code (((pStateIdx << 1) | valMps) << 1) | lps} where {@code lps} is 1 for the least probable
     * symbol.
     */
    private static final byte[] NEXT = new byte[256];

    static {
        for (var state = 0; state < 64; state++) {
            for (var mps = 0; mps < 2; mps++) {
                var combined = (state << 1) | mps;
                var nextMps = state == 62 || state == 63 ? state : state + 1;
                var nextLps = CabacTables.TRANS_LPS[state];
                var lpsMps = state == 0 ? 1 - mps : mps;
                NEXT[combined << 1] = (byte) ((nextMps << 1) | mps);
                NEXT[(combined << 1) | 1] = (byte) ((nextLps << 1) | lpsMps);
            }
        }
    }

    /**
     * The context variables, {@link CabacTables#COUNT} of them, as {@code (pStateIdx << 1) | valMps}.
     */
    public final byte[] states = new byte[CabacTables.COUNT];

    /**
     * The buffer holding the slice segment data followed by zero padding.
     */
    private byte[] data;

    /**
     * The index of the first byte of the current substream.
     */
    private int start;

    /**
     * The index one past the last byte of the slice segment data.
     */
    private int end;

    /**
     * The index of the next byte to append to {@link #value}.
     */
    private int position;

    /**
     * {@code ivlCurrRange}, from 256 to 510 between decisions.
     */
    private int range;

    /**
     * {@code ivlOffset} scaled by {@code 2^7}, plus the look-ahead bits below it.
     */
    private int value;

    /**
     * The number of bits until the next byte is appended, from -8 to -1.
     */
    private int bitsNeeded;

    /**
     * Constructs an engine; {@link #start(byte[], int, int)} must be called before decoding.
     */
    public CabacDecoder() {
        this.data = new byte[H264BitReader.PADDING];
    }

    /**
     * Initializes the arithmetic decoding engine at a byte position (ITU-T H.265 9.3.2.5).
     *
     * @param data     the buffer; at least {@link H264BitReader#PADDING} zero bytes must follow
     *                 {@code end}
     * @param position the index of the first byte of arithmetic-coded data
     * @param end      the index one past the last byte of the slice segment data
     */
    public void start(byte[] data, int position, int end) {
        this.data = data;
        this.start = position;
        this.end = end;
        this.position = position;
        restart();
    }

    /**
     * Reinitializes the arithmetic decoding engine at the current byte position, after PCM samples
     * or at the start of the next substream.
     */
    public void restart() {
        start = position;
        range = 510;
        bitsNeeded = -8;
        value = (readByte() << 8) | readByte();
    }

    /**
     * Reads the next byte of the slice segment data, zero past its end.
     *
     * @return the byte, 0 to 255
     */
    private int readByte() {
        var at = position++;
        return at < end ? data[at] & 0xFF : 0;
    }

    /**
     * Decodes one context-coded bin (ITU-T H.265 9.3.4.3.2).
     *
     * @implNote This implementation selects between the most and the least probable symbol path
     *           with a mask instead of a branch, and takes the renormalization shift from the
     *           leading zeros of the new range: which path a bin takes is close to random for
     *           the significance and level flags, so a branch on it mispredicts often.
     * @param ctx the index of the context variable in {@link #states}
     * @return the bin value, 0 or 1
     */
    public int decodeBin(int ctx) {
        var state = states[ctx] & 0xFF;
        var lps = CabacTables.LPS_RANGE[((state >> 1) << 2) + ((range >> 6) - 4)];
        var mps = range - lps;
        var scaledRange = mps << 7;
        var lpsMask = (scaledRange - 1 - value) >> 31;
        value -= scaledRange & lpsMask;
        var newRange = mps + ((lps - mps) & lpsMask);
        states[ctx] = NEXT[(state << 1) | (lpsMask & 1)];
        var shift = Integer.numberOfLeadingZeros(newRange) - 23;
        value <<= shift;
        range = newRange << shift;
        var needed = bitsNeeded + shift;
        if (needed >= 0) {
            value += readByte() << needed;
            needed -= 8;
        }
        bitsNeeded = needed;
        return (state ^ lpsMask) & 1;
    }

    /**
     * Decodes one bypass-coded bin (ITU-T H.265 9.3.4.3.4).
     *
     * @implNote This implementation selects the bin and the new offset without a branch: bypass
     *           bins are close to equiprobable, so a branch on their value mispredicts often.
     * @return the bin value, 0 or 1
     */
    public int decodeBypass() {
        value <<= 1;
        if (++bitsNeeded >= 0) {
            bitsNeeded = -8;
            value += readByte();
        }
        var scaledRange = range << 7;
        var difference = value - scaledRange;
        var below = difference >> 31;
        value = difference + (scaledRange & below);
        return below + 1;
    }

    /**
     * Decodes several bypass-coded bins, most significant first.
     *
     * <p>Decoding {@code n} bypass bins is a long division of the offset register, extended by the
     * next {@code n} bits of the bitstream, by the scaled range: the bins are the quotient and the
     * new offset is the remainder. The bins are decoded 8 at a time with one division each. The
     * quotient is masked to the number of bins, so that an offset left above the range by the
     * {@link #align()} of a corrupt stream still yields {@code count} bins.
     *
     * @implNote This implementation follows {@code TDecBinCABAC::decodeBinsEP} of the HM reference
     *           software, which loads a whole byte for each group of 8 bins, with its bin-by-bin
     *           comparisons replaced by the division they compute.
     * @param count the number of bins, from 0 to 32
     * @return the bins as an unsigned value
     */
    public int decodeBypassBins(int count) {
        if (count <= 1) {
            return count == 0 ? 0 : decodeBypass();
        }
        var scaledRange = range << 7;
        var bins = 0;
        while (count > 8) {
            value = (value << 8) + (readByte() << (8 + bitsNeeded));
            var quotient = value / scaledRange;
            value -= quotient * scaledRange;
            bins = (bins << 8) | (quotient & 0xFF);
            count -= 8;
        }
        bitsNeeded += count;
        value <<= count;
        if (bitsNeeded >= 0) {
            value += readByte() << bitsNeeded;
            bitsNeeded -= 8;
        }
        var quotient = value / scaledRange;
        value -= quotient * scaledRange;
        return (bins << count) | (quotient & ((1 << count) - 1));
    }

    /**
     * Decodes bypass-coded bins up to and including the first 0 bin, or until more than
     * {@code limit} 1 bins were decoded, and returns the number of 1 bins: the unary prefix of a
     * {@code coeff_abs_level_remaining} value.
     *
     * <p>When the result exceeds {@code limit} the engine has consumed an unspecified number of
     * further bins, so the caller must treat the data as corrupt.
     *
     * @implNote This implementation divides the offset register extended by the next byte to get
     *           the next 8 bins at once, counts their leading 1 bins, and consumes only the bins up to
     *           the first 0 bin: the first {@code k} bins of a long division depend on the first
     *           {@code k} bits of the dividend only, and the bits of the next byte below the
     *           register's 7 look-ahead bits never change a comparison with the scaled range.
     * @param limit the largest number of 1 bins that is valid
     * @return the number of 1 bins, greater than {@code limit} when the prefix is too long
     */
    public int decodeBypassOnes(int limit) {
        var scaledRange = range << 7;
        var ones = 0;
        while (true) {
            var at = position;
            var next = at < end ? data[at] & 0xFF : 0;
            var ahead = (value << 8) + (next << (8 + bitsNeeded));
            var quotient = ahead / scaledRange;
            var run = Integer.numberOfLeadingZeros(~quotient << 24);
            if (run < 8) {
                var consumed = run + 1;
                var shift = value << consumed;
                var needed = bitsNeeded + consumed;
                if (needed >= 0) {
                    shift += next << needed;
                    needed -= 8;
                    position = at + 1;
                }
                bitsNeeded = needed;
                value = shift - (quotient >> (7 - run)) * scaledRange;
                return ones + run;
            }
            value = ahead - quotient * scaledRange;
            position = at + 1;
            ones += 8;
            if (ones > limit) {
                return ones;
            }
        }
    }

    /**
     * Aligns the engine before aligned bypass decoding (ITU-T H.265 9.3.4.3.6): sets the range to
     * 256, so that the following bypass bins are the next bits of the bitstream.
     */
    public void align() {
        range = 256;
    }

    /**
     * Decodes a terminating bin (ITU-T H.265 9.3.4.3.5): {@code end_of_slice_segment_flag},
     * {@code end_of_subset_one_bit} or {@code pcm_flag}.
     *
     * <p>When the bin is 1 the engine stops, and {@link #bytePosition()} is the index of the first
     * byte after the coded data, where PCM samples or the next substream start.
     *
     * @return the bin value, 0 or 1
     */
    public int decodeTerminate() {
        range -= 2;
        var scaledRange = range << 7;
        if (value >= scaledRange) {
            return 1;
        }
        if (scaledRange < (256 << 7)) {
            range = scaledRange >> 6;
            value <<= 1;
            if (++bitsNeeded == 0) {
                bitsNeeded = -8;
                value += readByte();
            }
        }
        return 0;
    }

    /**
     * Returns the index of the first byte after the arithmetic-coded data, valid after
     * {@link #decodeTerminate()} returned 1.
     *
     * @return the byte index in the buffer
     */
    public int bytePosition() {
        return position;
    }

    /**
     * Moves the engine to a byte position, which must then be followed by {@link #restart()}.
     *
     * @param position the index of the next byte to read
     */
    public void seek(int position) {
        this.position = position;
    }

    /**
     * Returns the buffer the engine reads.
     *
     * @return the buffer
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the index one past the last byte of the slice segment data.
     *
     * @return the end index
     */
    public int end() {
        return end;
    }

    /**
     * Returns whether the engine read past the end of the slice segment data by more than the two
     * bytes the arithmetic decoder may legitimately look ahead.
     *
     * @return {@code true} when the slice segment data is truncated
     */
    public boolean overrun() {
        return position > end + 2;
    }
}
