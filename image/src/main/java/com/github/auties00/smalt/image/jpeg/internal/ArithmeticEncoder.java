/* Ports libjpeg-turbo (jcarith.c, jaricom.c). Copyright (C) 1997-2009, Guido Vollbeding; Copyright (C)
   2015-2022, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

/**
 * Codes binary decisions with the adaptive arithmetic coder of T.81 Annex D (the QM coder) and the
 * statistical models of the DCT processes (F.1.4, G.1.3) and of the lossless process (H.1.2.3).
 *
 * <p>Each statistics bin is one byte: the more probable symbol in bit 7 and the index of the probability
 * estimation state of T.81 Table D.2 below it. The output carries the byte stuffing of T.81 D.1.6 and is the
 * shortest code that decodes to the decisions: {@link #finish()} drops trailing zero bytes as T.81 D.1.8
 * permits. Instances are not thread-safe.
 *
 * @implNote This implementation follows libjpeg-turbo's encoder, including its compact state table, its
 * zero-byte and {@code 0xFF}-byte stacking and its termination, so its bytes equal {@code cjpeg -arithmetic}'s.
 */
final class ArithmeticEncoder {
    /**
     * The probability estimation state machine of T.81 Table D.2 as quadruples of {@code Qe}, next
     * state after an LPS, next state after an MPS and the MPS switch flag, plus a final state with a
     * fixed estimate of 0.5.
     */
    private static final int[] STATES = ArithmeticScanDecoder.STATE_TABLE;

    /**
     * The index of the fixed-probability state.
     */
    private static final int FIXED_STATE = 113;

    /**
     * The number of DC statistics bins of a table.
     */
    static final int DC_BINS = 64;

    /**
     * The number of AC statistics bins of a table.
     */
    static final int AC_BINS = 256;

    /**
     * The number of statistics bins of a lossless table, which uses the DC model with contexts up to 96
     * and magnitude bins from 100 to 157.
     */
    static final int LOSSLESS_BINS = 158;

    /**
     * The output.
     */
    private final JpegBitWriter out;

    /**
     * The code register {@code C}, laid out as in T.81 D.1.3.
     */
    private long c;

    /**
     * The interval register {@code A}.
     */
    private long a;

    /**
     * The number of stacked {@code 0xFF} bytes that a carry may still change.
     */
    private long sc;

    /**
     * The number of pending zero bytes that the end of the scan may drop.
     */
    private long zc;

    /**
     * The number of shifts before the next byte is ready.
     */
    private int ct;

    /**
     * The last byte that is not {@code 0xFF}, or -1 when none is buffered.
     */
    private int buffer;

    /**
     * The single statistics bin of the fixed-probability decisions.
     */
    private final byte[] fixedBin = new byte[1];

    /**
     * Constructs an encoder writing to an output.
     *
     * @param out the output, byte-aligned
     */
    ArithmeticEncoder(JpegBitWriter out) {
        this.out = out;
        reset();
    }

    /**
     * Resets the registers, as at the start of a scan or restart interval.
     */
    void reset() {
        c = 0;
        a = 0x10000L;
        sc = 0;
        zc = 0;
        ct = 11;
        buffer = -1;
        fixedBin[0] = (byte) FIXED_STATE;
    }

    /**
     * Writes the code of the remaining decisions (T.81 D.1.8), without trailing zero bytes; the output
     * is then byte-aligned, ready for a marker. The registers must be {@link #reset()} before the next
     * decision.
     */
    void finish() {
        var temp = (a - 1 + c) & 0xFFFF0000L;
        if (temp < c) {
            c = temp + 0x8000L;
        } else {
            c = temp;
        }
        c <<= ct;
        if ((c & 0xF8000000L) != 0) {
            if (buffer >= 0) {
                emitZeros();
                emit(buffer + 1);
                if (buffer + 1 == 0xFF) {
                    emit(0x00);
                }
            }
            zc += sc;
            sc = 0;
        } else {
            if (buffer == 0) {
                ++zc;
            } else if (buffer >= 0) {
                emitZeros();
                emit(buffer);
            }
            if (sc != 0) {
                emitZeros();
                do {
                    emit(0xFF);
                    emit(0x00);
                } while (--sc != 0);
            }
        }
        if ((c & 0x7FFF800L) != 0) {
            emitZeros();
            emit((int) ((c >> 19) & 0xFF));
            if (((c >> 19) & 0xFF) == 0xFF) {
                emit(0x00);
            }
            if ((c & 0x7F800L) != 0) {
                emit((int) ((c >> 11) & 0xFF));
                if (((c >> 11) & 0xFF) == 0xFF) {
                    emit(0x00);
                }
            }
        }
    }

    /**
     * Codes one decision in a statistics bin and updates the bin's estimate (T.81 D.1.4, D.1.5, D.1.6).
     *
     * @param stats the statistics bins
     * @param index the bin
     * @param value the decision, 0 or 1
     */
    void encode(byte[] stats, int index, int value) {
        var sv = stats[index] & 0xFF;
        var state = (sv & 0x7F) << 2;
        long qe = STATES[state];
        var nextLps = STATES[state + 1];
        var nextMps = STATES[state + 2];
        var switchMps = STATES[state + 3];
        a -= qe;
        if (value != (sv >> 7)) {
            if (a >= qe) {
                c += a;
                a = qe;
            }
            stats[index] = (byte) ((sv & 0x80) ^ (switchMps << 7) | nextLps);
        } else {
            if (a >= 0x8000L) {
                return;
            }
            if (a < qe) {
                c += a;
                a = qe;
            }
            stats[index] = (byte) ((sv & 0x80) | nextMps);
        }
        do {
            a <<= 1;
            c <<= 1;
            if (--ct == 0) {
                var temp = c >> 19;
                if (temp > 0xFF) {
                    if (buffer >= 0) {
                        emitZeros();
                        emit(buffer + 1);
                        if (buffer + 1 == 0xFF) {
                            emit(0x00);
                        }
                    }
                    zc += sc;
                    sc = 0;
                    buffer = (int) (temp & 0xFF);
                } else if (temp == 0xFF) {
                    ++sc;
                } else {
                    if (buffer == 0) {
                        ++zc;
                    } else if (buffer >= 0) {
                        emitZeros();
                        emit(buffer);
                    }
                    if (sc != 0) {
                        emitZeros();
                        do {
                            emit(0xFF);
                            emit(0x00);
                        } while (--sc != 0);
                    }
                    buffer = (int) (temp & 0xFF);
                }
                c &= 0x7FFFFL;
                ct += 8;
            }
        } while (a < 0x8000L);
    }

    /**
     * Codes one decision with the fixed probability estimate of 0.5.
     *
     * @param value the decision, 0 or 1
     */
    void encodeFixed(int value) {
        encode(fixedBin, 0, value);
    }

    /**
     * Writes the pending zero bytes.
     */
    private void emitZeros() {
        while (zc > 0) {
            out.writeByte(0);
            zc--;
        }
    }

    /**
     * Writes one byte.
     *
     * @param value the byte
     */
    private void emit(int value) {
        out.writeByte(value);
    }

    /**
     * Codes a nonzero difference or value magnitude with the DC decision tree of T.81 Figures F.6 to
     * F.9, starting at the sign decision.
     *
     * @param stats the statistics bins
     * @param s0 the bin of the zero decision just coded as 1
     * @param x1 the bin of the first magnitude category decision
     * @param value the nonzero value
     * @return the magnitude category {@code m}: 0 for {@code |value| == 1}, otherwise the largest power of
     * two not above {@code |value| - 1}
     */
    int encodeDcMagnitude(byte[] stats, int s0, int x1, int value) {
        int st;
        var v = value;
        if (v > 0) {
            encode(stats, s0 + 1, 0);
            st = s0 + 2;
        } else {
            v = -v;
            encode(stats, s0 + 1, 1);
            st = s0 + 3;
        }
        var m = 0;
        if ((v -= 1) != 0) {
            encode(stats, st, 1);
            m = 1;
            var v2 = v;
            st = x1;
            while ((v2 >>= 1) != 0) {
                encode(stats, st, 1);
                m <<= 1;
                st++;
            }
        }
        encode(stats, st, 0);
        var category = m;
        st += 14;
        while ((m >>= 1) != 0) {
            encode(stats, st, (m & v) != 0 ? 1 : 0);
        }
        return category;
    }

    /**
     * Codes the AC magnitude of one nonzero coefficient, after its sign (T.81 Figures F.8 and F.9).
     *
     * @param stats the AC statistics bins
     * @param st the bin of the first magnitude decision, {@code 3 * (k - 1) + 2}
     * @param k the zigzag index of the coefficient
     * @param kx the conditioning bound {@code Kx}
     * @param magnitude the magnitude, at least 1
     */
    void encodeAcMagnitude(byte[] stats, int st, int k, int kx, int magnitude) {
        var v = magnitude;
        var m = 0;
        if ((v -= 1) != 0) {
            encode(stats, st, 1);
            m = 1;
            var v2 = v;
            if ((v2 >>= 1) != 0) {
                encode(stats, st, 1);
                m <<= 1;
                st = k <= kx ? 189 : 217;
                while ((v2 >>= 1) != 0) {
                    encode(stats, st, 1);
                    m <<= 1;
                    st++;
                }
            }
        }
        encode(stats, st, 0);
        st += 14;
        while ((m >>= 1) != 0) {
            encode(stats, st, (m & v) != 0 ? 1 : 0);
        }
    }

    /**
     * Clears statistics bins to the initial state of T.81 D.1.1.
     *
     * @param stats the bins
     */
    static void clear(byte[] stats) {
        Arrays.fill(stats, (byte) 0);
    }
}
