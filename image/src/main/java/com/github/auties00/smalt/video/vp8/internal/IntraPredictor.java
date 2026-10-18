/* Ports libvpx (vp8/common/reconintra.c, reconintra4x4.c, vpx_dsp/intrapred.c). Copyright (c) 2010 The WebM project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Forms the VP8 intra predictions (RFC 6386 section 12) directly in a frame plane.
 *
 * <p>Every predictor reads its edge samples from the plane around the destination block: the row
 * above ({@code off - stride}), the column to the left ({@code off - 1}), and the top-left corner.
 * The caller prepares those samples, including the 127 and 129 values used outside the frame and
 * the above-right samples of subblocks on the right column.
 *
 * @implNote This implementation writes rows as whole 32-bit or 64-bit words, so the inverse
 * transform that reads them back as words gets its data forwarded from the store buffer instead
 * of waiting for byte stores.
 */
final class IntraPredictor {
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
     * Prevents instantiation.
     */
    private IntraPredictor() {
        throw new AssertionError();
    }

    /**
     * Predicts a square block with a whole-block mode.
     *
     * @param mode      the mode: {@code DC_PRED}, {@code V_PRED}, {@code H_PRED}, or {@code TM_PRED}
     * @param p         the plane
     * @param off       the index of the block's top-left sample
     * @param stride    the row stride
     * @param size      the block size, 16 for luma or 8 for chroma
     * @param haveAbove whether the row above lies inside the frame (used only by DC prediction)
     * @param haveLeft  whether the column to the left lies inside the frame (used only by DC
     *                  prediction)
     */
    static void predictBlock(int mode, byte[] p, int off, int stride, int size, boolean haveAbove, boolean haveLeft) {
        switch (mode) {
            case Vp8FrameDecoder.DC_PRED -> {
                var shift = size == 16 ? 3 : 2;
                int dc;
                if (haveAbove && haveLeft) {
                    var sum = 0;
                    for (var i = 0; i < size; i++) {
                        sum += (p[off - stride + i] & 0xFF) + (p[off - 1 + i * stride] & 0xFF);
                    }
                    dc = (sum + size) >> (shift + 2);
                } else if (haveAbove) {
                    var sum = 0;
                    for (var i = 0; i < size; i++) {
                        sum += p[off - stride + i] & 0xFF;
                    }
                    dc = (sum + (size >> 1)) >> (shift + 1);
                } else if (haveLeft) {
                    var sum = 0;
                    for (var i = 0; i < size; i++) {
                        sum += p[off - 1 + i * stride] & 0xFF;
                    }
                    dc = (sum + (size >> 1)) >> (shift + 1);
                } else {
                    dc = 128;
                }
                var word = dc * SPLAT;
                for (var r = 0; r < size; r++) {
                    var row = off + r * stride;
                    LONG.set(p, row, word);
                    if (size == 16) {
                        LONG.set(p, row + 8, word);
                    }
                }
            }
            case Vp8FrameDecoder.V_PRED -> {
                var first = (long) LONG.get(p, off - stride);
                var second = size == 16 ? (long) LONG.get(p, off - stride + 8) : 0L;
                for (var r = 0; r < size; r++) {
                    var row = off + r * stride;
                    LONG.set(p, row, first);
                    if (size == 16) {
                        LONG.set(p, row + 8, second);
                    }
                }
            }
            case Vp8FrameDecoder.H_PRED -> {
                for (var r = 0; r < size; r++) {
                    var row = off + r * stride;
                    var word = (p[row - 1] & 0xFF) * SPLAT;
                    LONG.set(p, row, word);
                    if (size == 16) {
                        LONG.set(p, row + 8, word);
                    }
                }
            }
            default -> {
                var above = off - stride;
                var topLeft = p[above - 1] & 0xFF;
                for (var r = 0; r < size; r++) {
                    var row = off + r * stride;
                    var delta = (p[row - 1] & 0xFF) - topLeft;
                    for (var c = 0; c < size; c += 8) {
                        var word = 0L;
                        for (var n = 7; n >= 0; n--) {
                            word = word << 8 | clamp((p[above + c + n] & 0xFF) + delta);
                        }
                        LONG.set(p, row + c, word);
                    }
                }
            }
        }
    }

    /**
     * Returns the rounded average of three samples weighted 1, 2, 1.
     *
     * @param a the first sample
     * @param b the middle sample
     * @param c the last sample
     * @return {@code (a + 2b + c + 2) >> 2}
     */
    private static int avg3(int a, int b, int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    /**
     * Returns the rounded average of two samples.
     *
     * @param a the first sample
     * @param b the second sample
     * @return {@code (a + b + 1) >> 1}
     */
    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    /**
     * Predicts a 4x4 luma subblock with one of the ten subblock modes (RFC 6386 section 12.3).
     *
     * <p>The eight samples above the block (four above and four above-right) and the four samples
     * to its left, plus the top-left corner, must be valid.
     *
     * @param mode   the subblock mode, in libvpx order ({@code B_DC_PRED} to {@code B_HU_PRED})
     * @param p      the plane
     * @param off    the index of the subblock's top-left sample
     * @param stride the row stride
     */
    static void predictSubblock(int mode, byte[] p, int off, int stride) {
        var a = off - stride;
        var x = p[a - 1] & 0xFF;
        var a0 = p[a] & 0xFF;
        var a1 = p[a + 1] & 0xFF;
        var a2 = p[a + 2] & 0xFF;
        var a3 = p[a + 3] & 0xFF;
        var l0 = p[off - 1] & 0xFF;
        var l1 = p[off - 1 + stride] & 0xFF;
        var l2 = p[off - 1 + 2 * stride] & 0xFF;
        var l3 = p[off - 1 + 3 * stride] & 0xFF;
        var r0 = off;
        var r1 = off + stride;
        var r2 = off + 2 * stride;
        var r3 = off + 3 * stride;
        switch (mode) {
            case Vp8FrameDecoder.B_DC_PRED -> {
                var dc = ((a0 + a1 + a2 + a3 + l0 + l1 + l2 + l3 + 4) >> 3) * 0x01010101;
                INT.set(p, r0, dc);
                INT.set(p, r1, dc);
                INT.set(p, r2, dc);
                INT.set(p, r3, dc);
            }
            case Vp8FrameDecoder.B_TM_PRED -> {
                INT.set(p, r0, pack(clamp(a0 + l0 - x), clamp(a1 + l0 - x), clamp(a2 + l0 - x), clamp(a3 + l0 - x)));
                INT.set(p, r1, pack(clamp(a0 + l1 - x), clamp(a1 + l1 - x), clamp(a2 + l1 - x), clamp(a3 + l1 - x)));
                INT.set(p, r2, pack(clamp(a0 + l2 - x), clamp(a1 + l2 - x), clamp(a2 + l2 - x), clamp(a3 + l2 - x)));
                INT.set(p, r3, pack(clamp(a0 + l3 - x), clamp(a1 + l3 - x), clamp(a2 + l3 - x), clamp(a3 + l3 - x)));
            }
            case Vp8FrameDecoder.B_VE_PRED -> {
                var row = pack(avg3(x, a0, a1), avg3(a0, a1, a2), avg3(a1, a2, a3), avg3(a2, a3, p[a + 4] & 0xFF));
                INT.set(p, r0, row);
                INT.set(p, r1, row);
                INT.set(p, r2, row);
                INT.set(p, r3, row);
            }
            case Vp8FrameDecoder.B_HE_PRED -> {
                INT.set(p, r0, avg3(x, l0, l1) * 0x01010101);
                INT.set(p, r1, avg3(l0, l1, l2) * 0x01010101);
                INT.set(p, r2, avg3(l1, l2, l3) * 0x01010101);
                INT.set(p, r3, avg3(l2, l3, l3) * 0x01010101);
            }
            case Vp8FrameDecoder.B_LD_PRED -> {
                var a4 = p[a + 4] & 0xFF;
                var a5 = p[a + 5] & 0xFF;
                var a6 = p[a + 6] & 0xFF;
                var a7 = p[a + 7] & 0xFF;
                var d0 = avg3(a0, a1, a2);
                var d1 = avg3(a1, a2, a3);
                var d2 = avg3(a2, a3, a4);
                var d3 = avg3(a3, a4, a5);
                var d4 = avg3(a4, a5, a6);
                var d5 = avg3(a5, a6, a7);
                INT.set(p, r0, pack(d0, d1, d2, d3));
                INT.set(p, r1, pack(d1, d2, d3, d4));
                INT.set(p, r2, pack(d2, d3, d4, d5));
                INT.set(p, r3, pack(d3, d4, d5, avg3(a6, a7, a7)));
            }
            case Vp8FrameDecoder.B_RD_PRED -> {
                var e0 = avg3(l1, l2, l3);
                var e1 = avg3(l0, l1, l2);
                var e2 = avg3(x, l0, l1);
                var e3 = avg3(a0, x, l0);
                var e4 = avg3(a1, a0, x);
                var e5 = avg3(a2, a1, a0);
                INT.set(p, r0, pack(e3, e4, e5, avg3(a3, a2, a1)));
                INT.set(p, r1, pack(e2, e3, e4, e5));
                INT.set(p, r2, pack(e1, e2, e3, e4));
                INT.set(p, r3, pack(e0, e1, e2, e3));
            }
            case Vp8FrameDecoder.B_VR_PRED -> {
                var h0 = avg2(x, a0);
                var h1 = avg2(a0, a1);
                var h2 = avg2(a1, a2);
                var t0 = avg3(l0, x, a0);
                var t1 = avg3(x, a0, a1);
                var t2 = avg3(a0, a1, a2);
                INT.set(p, r0, pack(h0, h1, h2, avg2(a2, a3)));
                INT.set(p, r1, pack(t0, t1, t2, avg3(a1, a2, a3)));
                INT.set(p, r2, pack(avg3(l1, l0, x), h0, h1, h2));
                INT.set(p, r3, pack(avg3(l2, l1, l0), t0, t1, t2));
            }
            case Vp8FrameDecoder.B_VL_PRED -> {
                var a4 = p[a + 4] & 0xFF;
                var a5 = p[a + 5] & 0xFF;
                var a6 = p[a + 6] & 0xFF;
                var a7 = p[a + 7] & 0xFF;
                var h1 = avg2(a1, a2);
                var h2 = avg2(a2, a3);
                var h3 = avg2(a3, a4);
                var t1 = avg3(a1, a2, a3);
                var t2 = avg3(a2, a3, a4);
                var t3 = avg3(a3, a4, a5);
                INT.set(p, r0, pack(avg2(a0, a1), h1, h2, h3));
                INT.set(p, r1, pack(avg3(a0, a1, a2), t1, t2, t3));
                INT.set(p, r2, pack(h1, h2, h3, avg3(a4, a5, a6)));
                INT.set(p, r3, pack(t1, t2, t3, avg3(a5, a6, a7)));
            }
            case Vp8FrameDecoder.B_HD_PRED -> {
                var h0 = avg2(l0, x);
                var h1 = avg2(l1, l0);
                var h2 = avg2(l2, l1);
                var t0 = avg3(l0, x, a0);
                var t1 = avg3(l1, l0, x);
                var t2 = avg3(l2, l1, l0);
                INT.set(p, r0, pack(h0, t0, avg3(x, a0, a1), avg3(a0, a1, a2)));
                INT.set(p, r1, pack(h1, t1, h0, t0));
                INT.set(p, r2, pack(h2, t2, h1, t1));
                INT.set(p, r3, pack(avg2(l3, l2), avg3(l3, l2, l1), h2, t2));
            }
            default -> {
                var h1 = avg2(l1, l2);
                var h2 = avg2(l2, l3);
                var t1 = avg3(l1, l2, l3);
                var t2 = avg3(l2, l3, l3);
                INT.set(p, r0, pack(avg2(l0, l1), avg3(l0, l1, l2), h1, t1));
                INT.set(p, r1, pack(h1, t1, h2, t2));
                INT.set(p, r2, pack(h2, t2, l3, l3));
                INT.set(p, r3, l3 * 0x01010101);
            }
        }
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
     * Clamps a value to {@code [0, 255]}.
     *
     * @param v the value
     * @return the clamped value
     */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
