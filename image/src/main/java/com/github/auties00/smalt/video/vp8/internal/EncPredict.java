/* Ports libwebp 1.6.0 (src/dsp/enc.c: Intra16Preds_C, IntraChromaPreds_C, Intra4Preds_C). Copyright (c) 2010, Google
   Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Forms every candidate intra prediction of the key-frame encoder in its prediction buffer.
 *
 * <p>The prediction buffer has rows of {@link Vp8EncDsp#BPS} bytes and holds, at fixed offsets,
 * the four 16x16 luma predictions, the four chroma predictions (U and V side by side), and the
 * ten 4x4 subblock predictions of the current subblock. Missing edges (outside the picture) are
 * signaled by a {@code null} array and replaced by the values the decoder uses.
 *
 * @implNote This implementation writes rows as whole 32-bit or 64-bit words, so the transforms
 * that read them back as words get their data forwarded from the store buffer instead of waiting
 * for byte stores.
 */
final class EncPredict {
    /**
     * The row stride of the prediction buffer.
     */
    private static final int BPS = Vp8EncDsp.BPS;

    /**
     * Reads and writes four bytes of a byte array as a little-endian {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Replicates a byte into every byte of a {@code long}.
     */
    private static final long SPLAT = 0x0101010101010101L;

    /**
     * The offset of the 16x16 DC prediction.
     */
    static final int I16DC16 = 0;

    /**
     * The offset of the 16x16 TrueMotion prediction.
     */
    static final int I16TM16 = 16;

    /**
     * The offset of the 16x16 vertical prediction.
     */
    static final int I16VE16 = 16 * BPS;

    /**
     * The offset of the 16x16 horizontal prediction.
     */
    static final int I16HE16 = 16 * BPS + 16;

    /**
     * The offset of the chroma DC prediction.
     */
    static final int C8DC8 = 2 * 16 * BPS;

    /**
     * The offset of the chroma TrueMotion prediction.
     */
    static final int C8TM8 = C8DC8 + 16;

    /**
     * The offset of the chroma vertical prediction.
     */
    static final int C8VE8 = 2 * 16 * BPS + 8 * BPS;

    /**
     * The offset of the chroma horizontal prediction.
     */
    static final int C8HE8 = C8VE8 + 16;

    /**
     * The offset of the first 4x4 prediction.
     */
    static final int I4DC4 = 3 * 16 * BPS;

    /**
     * The offset of the 4x4 scratch block used during subblock mode decisions.
     */
    static final int I4TMP = 3 * 16 * BPS + 4 * BPS + 8;

    /**
     * The size of the prediction buffer.
     */
    static final int PRED_SIZE = 32 * BPS + 16 * BPS + 8 * BPS;

    /**
     * The offsets of the 16x16 predictions, in libwebp mode order (DC, TM, VE, HE).
     */
    static final int[] I16_MODE_OFFSETS = {I16DC16, I16TM16, I16VE16, I16HE16};

    /**
     * The offsets of the chroma predictions, in libwebp mode order.
     */
    static final int[] UV_MODE_OFFSETS = {C8DC8, C8TM8, C8VE8, C8HE8};

    /**
     * The offsets of the 4x4 predictions, in libwebp mode order (DC, TM, VE, HE, RD, VR, LD, VL,
     * HD, HU).
     */
    static final int[] I4_MODE_OFFSETS = {
            I4DC4, I4DC4 + 4, I4DC4 + 8, I4DC4 + 12, I4DC4 + 16, I4DC4 + 20, I4DC4 + 24, I4DC4 + 28,
            I4DC4 + 4 * BPS, I4DC4 + 4 * BPS + 4
    };

    /**
     * Prevents instantiation.
     */
    private EncPredict() {
        throw new AssertionError();
    }

    /**
     * Fills a square with one value.
     *
     * @param dst the prediction buffer
     * @param off the index of the square
     * @param value the sample value, of which the low 8 bits are stored
     * @param size the square size, 4, 8 or 16
     */
    private static void fill(byte[] dst, int off, int value, int size) {
        for (var j = 0; j < size; j++) {
            fillRow(dst, off + j * BPS, value, size);
        }
    }

    /**
     * Fills one row of a square with one value.
     *
     * @param dst the prediction buffer
     * @param off the index of the row's first sample
     * @param value the sample value, of which the low 8 bits are stored
     * @param size the row length, 4, 8 or 16
     */
    private static void fillRow(byte[] dst, int off, int value, int size) {
        var word = (value & 0xFF) * SPLAT;
        if (size == 4) {
            INT.set(dst, off, (int) word);
        } else {
            LONG.set(dst, off, word);
            if (size == 16) {
                LONG.set(dst, off + 8, word);
            }
        }
    }

    /**
     * Forms a vertical prediction.
     *
     * @param dst    the prediction buffer
     * @param off    the index of the square
     * @param top    the samples above, or {@code null}
     * @param topOff the index of the first sample above
     * @param size   the square size, 8 or 16
     */
    private static void verticalPred(byte[] dst, int off, byte[] top, int topOff, int size) {
        if (top != null) {
            var first = (long) LONG.get(top, topOff);
            var second = size == 16 ? (long) LONG.get(top, topOff + 8) : 0L;
            for (var j = 0; j < size; j++) {
                LONG.set(dst, off + j * BPS, first);
                if (size == 16) {
                    LONG.set(dst, off + j * BPS + 8, second);
                }
            }
        } else {
            fill(dst, off, 127, size);
        }
    }

    /**
     * Forms a horizontal prediction.
     *
     * @param dst     the prediction buffer
     * @param off     the index of the square
     * @param left    the samples to the left (index {@code leftOff - 1} is the corner), or
     *                {@code null}
     * @param leftOff the index of the first sample to the left
     * @param size    the square size, 8 or 16
     */
    private static void horizontalPred(byte[] dst, int off, byte[] left, int leftOff, int size) {
        if (left != null) {
            for (var j = 0; j < size; j++) {
                fillRow(dst, off + j * BPS, left[leftOff + j], size);
            }
        } else {
            fill(dst, off, 129, size);
        }
    }

    /**
     * Forms a TrueMotion prediction.
     *
     * @param dst     the prediction buffer
     * @param off     the index of the square
     * @param left    the samples to the left, or {@code null}
     * @param leftOff the index of the first sample to the left
     * @param top     the samples above, or {@code null}
     * @param topOff  the index of the first sample above
     * @param size    the square size, 8 or 16
     */
    private static void trueMotion(byte[] dst, int off, byte[] left, int leftOff, byte[] top, int topOff, int size) {
        if (left != null) {
            if (top != null) {
                var corner = left[leftOff - 1] & 0xFF;
                for (var y = 0; y < size; y++) {
                    var delta = (left[leftOff + y] & 0xFF) - corner;
                    var row = off + y * BPS;
                    for (var x = 0; x < size; x += 8) {
                        var word = 0L;
                        for (var n = 7; n >= 0; n--) {
                            word = word << 8 | clip((top[topOff + x + n] & 0xFF) + delta);
                        }
                        LONG.set(dst, row + x, word);
                    }
                }
            } else {
                horizontalPred(dst, off, left, leftOff, size);
            }
        } else if (top != null) {
            verticalPred(dst, off, top, topOff, size);
        } else {
            fill(dst, off, 129, size);
        }
    }

    /**
     * Clamps a value to {@code [0, 255]}.
     *
     * @param v the value
     * @return the clamped value
     */
    private static int clip(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * Packs four samples into a little-endian word, the first sample in the lowest byte.
     *
     * @param b0 the first sample
     * @param b1 the second sample
     * @param b2 the third sample
     * @param b3 the fourth sample
     * @return the word
     */
    private static int pack(int b0, int b1, int b2, int b3) {
        return b0 | b1 << 8 | b2 << 16 | b3 << 24;
    }

    /**
     * Forms a DC prediction.
     *
     * @param dst     the prediction buffer
     * @param off     the index of the square
     * @param left    the samples to the left, or {@code null}
     * @param leftOff the index of the first sample to the left
     * @param top     the samples above, or {@code null}
     * @param topOff  the index of the first sample above
     * @param size    the square size
     * @param round   the rounding constant
     * @param shift   the normalization shift
     */
    private static void dcMode(byte[] dst, int off, byte[] left, int leftOff, byte[] top, int topOff,
                               int size, int round, int shift) {
        var dc = 0;
        if (top != null) {
            for (var j = 0; j < size; j++) {
                dc += top[topOff + j] & 0xFF;
            }
            if (left != null) {
                for (var j = 0; j < size; j++) {
                    dc += left[leftOff + j] & 0xFF;
                }
            } else {
                dc += dc;
            }
            dc = (dc + round) >> shift;
        } else if (left != null) {
            for (var j = 0; j < size; j++) {
                dc += left[leftOff + j] & 0xFF;
            }
            dc += dc;
            dc = (dc + round) >> shift;
        } else {
            dc = 0x80;
        }
        fill(dst, off, dc, size);
    }

    /**
     * Forms the four 16x16 luma predictions.
     *
     * @param dst     the prediction buffer
     * @param left    the 16 samples to the left preceded by the corner, or {@code null}
     * @param leftOff the index of the first sample to the left
     * @param top     the 16 samples above, or {@code null}
     * @param topOff  the index of the first sample above
     */
    static void luma16(byte[] dst, byte[] left, int leftOff, byte[] top, int topOff) {
        dcMode(dst, I16DC16, left, leftOff, top, topOff, 16, 16, 5);
        verticalPred(dst, I16VE16, top, topOff, 16);
        horizontalPred(dst, I16HE16, left, leftOff, 16);
        trueMotion(dst, I16TM16, left, leftOff, top, topOff, 16);
    }

    /**
     * Forms the four chroma predictions of both planes.
     *
     * @param dst     the prediction buffer
     * @param uLeft   the 8 U samples to the left preceded by the corner, or {@code null}
     * @param vLeft   the 8 V samples to the left preceded by the corner, or {@code null}
     * @param leftOff the index of the first sample to the left in both arrays
     * @param top     the 8 U then 8 V samples above, or {@code null}
     * @param topOff  the index of the first U sample above
     */
    static void chroma8(byte[] dst, byte[] uLeft, byte[] vLeft, int leftOff, byte[] top, int topOff) {
        dcMode(dst, C8DC8, uLeft, leftOff, top, topOff, 8, 8, 4);
        verticalPred(dst, C8VE8, top, topOff, 8);
        horizontalPred(dst, C8HE8, uLeft, leftOff, 8);
        trueMotion(dst, C8TM8, uLeft, leftOff, top, topOff, 8);
        dcMode(dst, C8DC8 + 8, vLeft, leftOff, top, topOff + 8, 8, 8, 4);
        verticalPred(dst, C8VE8 + 8, top, topOff + 8, 8);
        horizontalPred(dst, C8HE8 + 8, vLeft, leftOff, 8);
        trueMotion(dst, C8TM8 + 8, vLeft, leftOff, top, topOff + 8, 8);
    }

    /**
     * Returns the rounded average of three samples weighted 1, 2, 1.
     *
     * @param a the first sample
     * @param b the middle sample
     * @param c the last sample
     * @return the average
     */
    private static int avg3(int a, int b, int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    /**
     * Returns the rounded average of two samples.
     *
     * @param a the first sample
     * @param b the second sample
     * @return the average
     */
    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    /**
     * Forms the ten 4x4 subblock predictions.
     *
     * <p>{@code top[t]} is the first sample above the subblock: {@code top[t + 0..7]} are the
     * samples above and above-right, {@code top[t - 1]} the corner, and {@code top[t - 2..-5]}
     * the samples to the left from top to bottom.
     *
     * @param dst the prediction buffer
     * @param top the boundary samples
     * @param t   the index of the first sample above
     */
    static void luma4(byte[] dst, byte[] top, int t) {
        int x = top[t - 1] & 0xFF, i = top[t - 2] & 0xFF, j = top[t - 3] & 0xFF, k = top[t - 4] & 0xFF,
                l = top[t - 5] & 0xFF;
        int a = top[t] & 0xFF, b = top[t + 1] & 0xFF, c = top[t + 2] & 0xFF, d = top[t + 3] & 0xFF,
                e = top[t + 4] & 0xFF, f = top[t + 5] & 0xFF, g = top[t + 6] & 0xFF, h = top[t + 7] & 0xFF;
        fill(dst, I4DC4, (a + b + c + d + i + j + k + l + 4) >> 3, 4);

        var tm = I4DC4 + 4;
        var left = pack(i, j, k, l);
        for (var y = 0; y < 4; y++) {
            var delta = (left >>> (8 * y) & 0xFF) - x;
            INT.set(dst, tm + y * BPS, pack(clip(a + delta), clip(b + delta), clip(c + delta), clip(d + delta)));
        }

        var ve = pack(avg3(x, a, b), avg3(a, b, c), avg3(b, c, d), avg3(c, d, e));
        for (var y = 0; y < 4; y++) {
            INT.set(dst, I4DC4 + 8 + y * BPS, ve);
        }

        var he = I4DC4 + 12;
        INT.set(dst, he, avg3(x, i, j) * 0x01010101);
        INT.set(dst, he + BPS, avg3(i, j, k) * 0x01010101);
        INT.set(dst, he + 2 * BPS, avg3(j, k, l) * 0x01010101);
        INT.set(dst, he + 3 * BPS, avg3(k, l, l) * 0x01010101);

        var axi = avg3(a, x, i);
        var bax = avg3(b, a, x);
        var cba = avg3(c, b, a);
        var xij = avg3(x, i, j);
        var ijk = avg3(i, j, k);
        var rd = I4DC4 + 16;
        INT.set(dst, rd, pack(axi, bax, cba, avg3(d, c, b)));
        INT.set(dst, rd + BPS, pack(xij, axi, bax, cba));
        INT.set(dst, rd + 2 * BPS, pack(ijk, xij, axi, bax));
        INT.set(dst, rd + 3 * BPS, pack(avg3(j, k, l), ijk, xij, axi));

        var xa = avg2(x, a);
        var ab = avg2(a, b);
        var bc = avg2(b, c);
        var cd = avg2(c, d);
        var de = avg2(d, e);
        var ixa = avg3(i, x, a);
        var xab = avg3(x, a, b);
        var abc = avg3(a, b, c);
        var bcd = avg3(b, c, d);
        var vr = I4DC4 + 20;
        INT.set(dst, vr, pack(xa, ab, bc, cd));
        INT.set(dst, vr + BPS, pack(ixa, xab, abc, bcd));
        INT.set(dst, vr + 2 * BPS, pack(avg3(j, i, x), xa, ab, bc));
        INT.set(dst, vr + 3 * BPS, pack(avg3(k, j, i), ixa, xab, abc));

        var cde = avg3(c, d, e);
        var def = avg3(d, e, f);
        var efg = avg3(e, f, g);
        var fgh = avg3(f, g, h);
        var ld = I4DC4 + 24;
        INT.set(dst, ld, pack(abc, bcd, cde, def));
        INT.set(dst, ld + BPS, pack(bcd, cde, def, efg));
        INT.set(dst, ld + 2 * BPS, pack(cde, def, efg, fgh));
        INT.set(dst, ld + 3 * BPS, pack(def, efg, fgh, avg3(g, h, h)));

        var vl = I4DC4 + 28;
        INT.set(dst, vl, pack(ab, bc, cd, de));
        INT.set(dst, vl + BPS, pack(abc, bcd, cde, def));
        INT.set(dst, vl + 2 * BPS, pack(bc, cd, de, efg));
        INT.set(dst, vl + 3 * BPS, pack(bcd, cde, def, fgh));

        var ix = avg2(i, x);
        var ji = avg2(j, i);
        var kj = avg2(k, j);
        var jix = avg3(j, i, x);
        var kji = avg3(k, j, i);
        var hd = I4DC4 + 4 * BPS;
        INT.set(dst, hd, pack(ix, ixa, xab, abc));
        INT.set(dst, hd + BPS, pack(ji, jix, ix, ixa));
        INT.set(dst, hd + 2 * BPS, pack(kj, kji, ji, jix));
        INT.set(dst, hd + 3 * BPS, pack(avg2(l, k), avg3(l, k, j), kj, kji));

        var jk = avg2(j, k);
        var kl = avg2(k, l);
        var jkl = avg3(j, k, l);
        var kll = avg3(k, l, l);
        var hu = I4DC4 + 4 * BPS + 4;
        INT.set(dst, hu, pack(avg2(i, j), ijk, jk, jkl));
        INT.set(dst, hu + BPS, pack(jk, jkl, kl, kll));
        INT.set(dst, hu + 2 * BPS, pack(kl, kll, l, l));
        INT.set(dst, hu + 3 * BPS, l * 0x01010101);
    }
}
