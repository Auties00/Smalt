/* Ports HM-16.21, the ITU-T / ISO/IEC H.265 reference software (source/Lib/TLibEncoder/TEncBinCoderCABAC.cpp: the
   binary arithmetic encoder engine). Copyright (c) 2010-2017, ITU/ISO/IEC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.hevc.internal.CabacTables;

/**
 * Runs the HEVC arithmetic encoding engine (ITU-T H.265 9.3.4.3) and writes its output to a
 * {@link MsbBitWriter}.
 *
 * <p>The low end of the coding interval lives in a 32-bit register with extra precision and whole bytes
 * leave it; a run of {@code 0xFF} bytes is held back until the carry of later additions is known.
 * {@link #finish()} writes the bits that end an arithmetic-coded substream after a terminating bin of
 * value 1, as the decoder's initialization at the next substream expects.
 *
 * @implNote This implementation follows {@code TEncBinCABAC} of the HM reference encoder.
 */
final class CabacWriter extends BinCoder {
    /**
     * The renormalization shift after a least probable symbol, indexed by {@code rangeLps >> 3}.
     */
    private static final int[] RENORM = {
            6, 5, 4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
    };

    /**
     * The destination of the coded bits.
     */
    private MsbBitWriter out;

    /**
     * The low end of the coding interval, with extra precision.
     */
    private int low;

    /**
     * {@code ivlCurrRange}.
     */
    private int range;

    /**
     * The number of free bits in {@link #low} before a byte must leave it.
     */
    private int bitsLeft;

    /**
     * The number of held-back bytes: {@link #bufferedByte} followed by {@code bufferedCount - 1} bytes
     * of {@code 0xFF}.
     */
    private int bufferedCount;

    /**
     * The first held-back byte.
     */
    private int bufferedByte;

    /**
     * Constructs a writer.
     */
    CabacWriter() {
    }

    /**
     * Initializes the arithmetic encoding engine (9.3.2.5) to write to a byte-aligned destination.
     *
     * @param out the destination
     */
    void start(MsbBitWriter out) {
        this.out = out;
        low = 0;
        range = 510;
        bitsLeft = 23;
        bufferedCount = 0;
        bufferedByte = 0xFF;
    }

    @Override
    void bin(int ctx, int bin) {
        var s = states[ctx] & 0xFF;
        var lps = CabacTables.LPS_RANGE[((s >> 1) << 2) | ((range >> 6) & 3)];
        range -= lps;
        states[ctx] = NEXT_STATE[(s << 1) | bin];
        if (bin != (s & 1)) {
            var shift = RENORM[lps >> 3];
            low = (low + range) << shift;
            range = lps << shift;
            bitsLeft -= shift;
        } else {
            if (range >= 256) {
                return;
            }
            low <<= 1;
            range <<= 1;
            bitsLeft--;
        }
        if (bitsLeft < 12) {
            writeOut();
        }
    }

    @Override
    void bypass(int bin) {
        low <<= 1;
        if (bin != 0) {
            low += range;
        }
        if (--bitsLeft < 12) {
            writeOut();
        }
    }

    @Override
    void bypassBins(int value, int count) {
        while (count > 8) {
            count -= 8;
            var pattern = (value >>> count) & 0xFF;
            low = (low << 8) + range * pattern;
            bitsLeft -= 8;
            if (bitsLeft < 12) {
                writeOut();
            }
        }
        var pattern = count == 0 ? 0 : value & (int) ((1L << count) - 1);
        low = (low << count) + range * pattern;
        bitsLeft -= count;
        if (bitsLeft < 12) {
            writeOut();
        }
    }

    @Override
    void terminate(int bin) {
        range -= 2;
        if (bin != 0) {
            low += range;
            low <<= 7;
            range = 2 << 7;
            bitsLeft -= 7;
        } else if (range >= 256) {
            return;
        } else {
            low <<= 1;
            range <<= 1;
            bitsLeft--;
        }
        if (bitsLeft < 12) {
            writeOut();
        }
    }

    /**
     * Writes the remaining interval bits after a terminating bin of value 1 (the flushing of 9.3.4.3.5),
     * leaving the destination possibly unaligned; the caller then writes the stop bit and the alignment.
     */
    void finish() {
        if ((low >>> (32 - bitsLeft)) != 0) {
            out.write(bufferedByte + 1, 8);
            while (bufferedCount > 1) {
                out.write(0x00, 8);
                bufferedCount--;
            }
            low -= 1 << (32 - bitsLeft);
        } else {
            if (bufferedCount > 0) {
                out.write(bufferedByte, 8);
            }
            while (bufferedCount > 1) {
                out.write(0xFF, 8);
                bufferedCount--;
            }
        }
        out.write(low >>> 8, 24 - bitsLeft);
        bufferedCount = 0;
    }

    /**
     * Emits the top byte of {@link #low}, holding it back while a carry could still change it.
     */
    private void writeOut() {
        var leadByte = low >>> (24 - bitsLeft);
        bitsLeft += 8;
        low &= 0xFFFFFFFF >>> bitsLeft;
        if (leadByte == 0xFF) {
            bufferedCount++;
        } else if (bufferedCount > 0) {
            var carry = leadByte >>> 8;
            var value = bufferedByte + carry;
            bufferedByte = leadByte & 0xFF;
            out.write(value, 8);
            value = (0xFF + carry) & 0xFF;
            while (bufferedCount > 1) {
                out.write(value, 8);
                bufferedCount--;
            }
        } else {
            bufferedCount = 1;
            bufferedByte = leadByte;
        }
    }
}
