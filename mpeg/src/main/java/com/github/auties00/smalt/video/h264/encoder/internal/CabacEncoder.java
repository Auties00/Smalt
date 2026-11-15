/* Ports HM, the ITU-T / ISO/IEC H.265 reference software (source/Lib/TLibEncoder/TEncBinCoderCABAC.cpp: the binary
   arithmetic encoder engine). Copyright (c) 2010-2026, ITU/ISO/IEC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Runs the CABAC arithmetic encoding engine (clause 9.3.4) and writes its output to a
 * {@link MsbBitWriter}.
 *
 * <p>The engine keeps the coding interval's low end in a 32-bit register with extra precision
 * and emits whole bytes; a run of {@code 0xFF} bytes is held back until the carry out of later
 * additions is known. The emitted bits are identical to the bit-serial {@code PutBit} procedure of
 * the specification, including the suppressed first bit, and {@link #flush()} produces the
 * terminating bits of {@code EncodeFlush}, whose last bit is the {@code rbsp_stop_one_bit} of a
 * slice.
 *
 * @implNote This implementation follows the byte-oriented engine of the HEVC HM reference
 * encoder ({@code TEncBinCABAC}), whose arithmetic is the same as H.264's.
 */
final class CabacEncoder extends CabacCoder {
    /**
     * The number of renormalization shifts after an LPS, indexed by {@code rangeLPS >> 3}.
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
     * The low end of the coding interval, with extra precision above the 10 bits of the
     * specification.
     */
    private int low;

    /**
     * The width of the coding interval, {@code codIRange}.
     */
    private int range;

    /**
     * The number of free bits in {@link #low} before a byte must be emitted.
     */
    private int bitsLeft;

    /**
     * The number of held-back bytes: the byte in {@link #bufferedByte} followed by
     * {@code bufferedCount - 1} bytes of {@code 0xFF}.
     */
    private int bufferedCount;

    /**
     * The first held-back byte.
     */
    private int bufferedByte;

    /**
     * The number of bins coded since the last {@link #resetBinCount()}.
     */
    private long bins;

    /**
     * Constructs an encoder.
     */
    CabacEncoder() {
    }

    /**
     * Constructs an encoder of a number of contexts.
     *
     * @param count the number of contexts
     */
    CabacEncoder(int count) {
        super(count);
    }

    /**
     * Initializes the arithmetic encoding engine (clause 9.3.1.2) to write to a destination.
     *
     * @param out the destination, byte-aligned
     */
    void start(MsbBitWriter out) {
        this.out = out;
        this.low = 0;
        this.range = 510;
        this.bitsLeft = 23;
        this.bufferedCount = 0;
        this.bufferedByte = 0xFF;
    }

    @Override
    void decision(int ctx, int bin) {
        bins++;
        var s = states[ctx] & 0xFF;
        var lps = com.github.auties00.smalt.video.h264.internal.CabacTables.LPS_RANGE[((s >> 1) << 2) | ((range >> 6) & 3)];
        range -= lps;
        states[ctx] = CabacTables.NEXT_STATE[(s << 1) | bin];
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
        bins++;
        low <<= 1;
        if (bin != 0) {
            low += range;
        }
        if (--bitsLeft < 12) {
            writeOut();
        }
    }

    @Override
    void bypassBits(int value, int count) {
        bins += count;
        while (count > 8) {
            count -= 8;
            var pattern = (value >>> count) & 0xFF;
            low = (low << 8) + range * pattern;
            bitsLeft -= 8;
            if (bitsLeft < 12) {
                writeOut();
            }
        }
        var pattern = value & ((1 << count) - 1);
        low = (low << count) + range * pattern;
        bitsLeft -= count;
        if (bitsLeft < 12) {
            writeOut();
        }
    }

    @Override
    void terminate(int bin) {
        bins++;
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
     * Completes {@code EncodeFlush} after a terminating bin of value 1: writes the remaining
     * interval bits and the final one bit, leaving the writer possibly unaligned.
     */
    void flush() {
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
        out.writeBit(1);
        bufferedCount = 0;
    }

    /**
     * Returns the number of bins coded since the last {@link #resetBinCount()}.
     *
     * @return the bin count
     */
    long binCount() {
        return bins;
    }

    /**
     * Resets the bin counter.
     */
    void resetBinCount() {
        bins = 0;
    }

    /**
     * Returns the number of bits the engine has produced or committed to so far, an estimate
     * accurate to a few bits used to bound macroblock sizes.
     *
     * @return the bit position including held-back bytes and register contents
     */
    long bitPosition() {
        return out.bitCount() + 8L * bufferedCount + (23 - bitsLeft) + 8;
    }

    /**
     * Saves the complete encoder state, including the output position and the context states,
     * so that {@link #restore(long[], byte[])} can undo everything coded afterwards.
     *
     * @param registers receives the engine registers and the output mark; at least 7 entries
     * @param contexts receives the context states; at least {@link CabacTables#CONTEXTS} entries
     */
    void save(long[] registers, byte[] contexts) {
        registers[0] = out.mark();
        registers[1] = low;
        registers[2] = range;
        registers[3] = bitsLeft;
        registers[4] = bufferedCount;
        registers[5] = bufferedByte;
        registers[6] = bins;
        System.arraycopy(states, 0, contexts, 0, states.length);
    }

    /**
     * Restores a state saved by {@link #save(long[], byte[])}, discarding everything coded since.
     *
     * @param registers the saved engine registers and output mark
     * @param contexts the saved context states
     */
    void restore(long[] registers, byte[] contexts) {
        out.rollback(registers[0]);
        low = (int) registers[1];
        range = (int) registers[2];
        bitsLeft = (int) registers[3];
        bufferedCount = (int) registers[4];
        bufferedByte = (int) registers[5];
        bins = registers[6];
        System.arraycopy(contexts, 0, states, 0, states.length);
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
