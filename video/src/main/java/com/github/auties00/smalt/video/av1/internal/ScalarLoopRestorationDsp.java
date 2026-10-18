package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the loop restoration kernels with plain scalar Java; the reference for every other
 * {@link LoopRestorationDsp}.
 *
 * <p>Both filters stream the stripe row by row: each input row is filtered horizontally (or
 * box-summed) once into a ring of row buffers, and each output row combines the rows of the ring
 * around it. The ring is a set of row offsets into the scratch buffer, rotated as rows advance,
 * with rows repeated where the stripe has no neighboring rows.
 *
 * <p>The inner loops of the Wiener filter are the overridable row spans {@link #wienerHSpan} and
 * {@link #wienerVSpan}, which {@link VectorLoopRestorationDsp} replaces with Vector API loops.
 *
 * @implNote This implementation ports dav1d's {@code looprestoration_tmpl.c} ({@code wiener_c},
 *           {@code sgr_5x5_c}, {@code sgr_3x3_c}, {@code sgr_mix_c} and their row helpers) step by
 *           step, including the order in which ring rows are filled, repeated and rotated at the
 *           top and bottom of a stripe. Row offsets that dav1d keeps in pointer arrays live in a
 *           small slot area at the end of the scratch buffer. dav1d's 8-bit template stores box
 *           sums and filter outputs in 16-bit integers; their values always fit, so the
 *           {@code int} storage used here for every bit depth computes identically.
 */
public class ScalarLoopRestorationDsp extends LoopRestorationDsp {
    /**
     * The row stride of the Wiener ring rows ({@code REST_UNIT_STRIDE}): 256 * 1.5 + 3 + 3.
     */
    private static final int REST_UNIT_STRIDE = 390;

    /**
     * The row stride of the self-guided box sum and coefficient rows ({@code BUF_STRIDE}).
     */
    private static final int BUF_STRIDE = 384 + 16;

    /**
     * The row stride of the self-guided filter output rows ({@code FILTER_OUT_STRIDE}).
     */
    private static final int FILTER_OUT_STRIDE = 384;

    /**
     * The scratch offset of the six horizontally filtered Wiener ring rows.
     */
    private static final int WIENER_ROWS = 0;

    /**
     * The scratch offset of the Wiener row being filtered horizontally.
     */
    private static final int WIENER_TMP = 6 * REST_UNIT_STRIDE;

    /**
     * The scratch offset of the five 5x5 box sum of squares rows.
     */
    private static final int SUMSQ5 = 0;

    /**
     * The scratch offset of the five 5x5 box sum rows.
     */
    private static final int SUM5 = SUMSQ5 + 5 * BUF_STRIDE;

    /**
     * The scratch offset of the three 3x3 box sum of squares rows.
     */
    private static final int SUMSQ3 = SUM5 + 5 * BUF_STRIDE;

    /**
     * The scratch offset of the three 3x3 box sum rows.
     */
    private static final int SUM3 = SUMSQ3 + 3 * BUF_STRIDE;

    /**
     * The scratch offset of the two 5x5 {@code A} coefficient rows.
     */
    private static final int A5 = SUM3 + 3 * BUF_STRIDE;

    /**
     * The scratch offset of the two 5x5 {@code B} coefficient rows.
     */
    private static final int B5 = A5 + 2 * BUF_STRIDE;

    /**
     * The scratch offset of the four 3x3 {@code A} coefficient rows.
     */
    private static final int A3 = B5 + 2 * BUF_STRIDE;

    /**
     * The scratch offset of the four 3x3 {@code B} coefficient rows.
     */
    private static final int B3 = A3 + 4 * BUF_STRIDE;

    /**
     * The scratch offset of the two 5x5 filter output rows.
     */
    private static final int TMP5 = B3 + 4 * BUF_STRIDE;

    /**
     * The scratch offset of the two 3x3 filter output rows.
     */
    private static final int TMP3 = TMP5 + 2 * FILTER_OUT_STRIDE;

    /**
     * The scratch offset of the row offset slots that stand for dav1d's row pointer arrays.
     */
    private static final int SLOTS = TMP3 + 2 * FILTER_OUT_STRIDE;

    /**
     * The slot index of the seven Wiener ring row offsets.
     */
    private static final int P_WIENER = SLOTS;

    /**
     * The slot index of the five 5x5 sum of squares row offsets.
     */
    private static final int P_SUMSQ5 = SLOTS;

    /**
     * The slot index of the five 5x5 sum row offsets.
     */
    private static final int P_SUM5 = P_SUMSQ5 + 5;

    /**
     * The slot index of the three 3x3 sum of squares row offsets.
     */
    private static final int P_SUMSQ3 = P_SUM5 + 5;

    /**
     * The slot index of the three 3x3 sum row offsets.
     */
    private static final int P_SUM3 = P_SUMSQ3 + 3;

    /**
     * The slot index of the two 5x5 {@code A} row offsets.
     */
    private static final int P_A5 = P_SUM3 + 3;

    /**
     * The slot index of the two 5x5 {@code B} row offsets.
     */
    private static final int P_B5 = P_A5 + 2;

    /**
     * The slot index of the four 3x3 {@code A} row offsets.
     */
    private static final int P_A3 = P_B5 + 2;

    /**
     * The slot index of the four 3x3 {@code B} row offsets.
     */
    private static final int P_B3 = P_A3 + 4;

    /**
     * The scratch size the layout above needs, which {@link #SCRATCH_INTS} must cover.
     */
    private static final int REQUIRED_SCRATCH = P_B3 + 4;

    /**
     * The control-flow continuation of a self-guided stripe that ends after the regular rows,
     * outputting two final rows from the bottom neighbors.
     */
    private static final int TAIL_OUTPUT2 = 0;

    /**
     * The control-flow continuation of a self-guided stripe whose last processed row pair is
     * complete and whose remaining rows repeat the last stripe row.
     */
    private static final int TAIL_VERT2 = 1;

    /**
     * The control-flow continuation of a self-guided stripe with an odd number of rows left.
     */
    private static final int TAIL_ODD = 2;

    /**
     * The control-flow continuation of a self-guided stripe of a single row.
     */
    private static final int TAIL_VERT1 = 3;

    /**
     * The {@code 1 / x} lookup of the self-guided filter, {@code 256 / (x + 1)} rounded, indexed
     * by the scaled variance {@code z} (AV1 specification section 7.17.3, {@code x_by_xplus1}
     * folded with the final inversion).
     *
     * @implNote This implementation copies dav1d's {@code dav1d_sgr_x_by_x} ({@code tables.c}).
     */
    private static final int[] SGR_X_BY_X = {
            255, 128, 85, 64, 51, 43, 37, 32, 28, 26, 23, 21, 20, 18, 17,
            16, 15, 14, 13, 13, 12, 12, 11, 11, 10, 10, 9, 9, 9, 9,
            8, 8, 8, 8, 7, 7, 7, 7, 7, 6, 6, 6, 6, 6, 6,
            6, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            0
    };

    static {
        if (REQUIRED_SCRATCH > SCRATCH_INTS || WIENER_TMP + REST_UNIT_STRIDE > SLOTS) {
            throw new AssertionError("loop restoration scratch layout exceeds SCRATCH_INTS");
        }
    }

    /**
     * Creates the scalar implementation.
     */
    public ScalarLoopRestorationDsp() {
    }

    @Override
    public void wiener(int taps, short[] dst, int dstOff, int stride, short[] left, int leftOff, short[] lpf,
                       int lpfOff, int w, int h, LrParams params, int edges, int[] scratch, int bitdepthMax) {
        var sc = scratch;
        var filter = params.filter;
        var bitdepth = 32 - Integer.numberOfLeadingZeros(bitdepthMax);
        var lpfBottom = lpfOff + 6 * stride;
        var src = dstOff;
        var p = dstOff;
        var l = leftOff;
        var row0 = WIENER_ROWS;
        var row1 = WIENER_ROWS + REST_UNIT_STRIDE;
        var row2 = WIENER_ROWS + 2 * REST_UNIT_STRIDE;
        var row3 = WIENER_ROWS + 3 * REST_UNIT_STRIDE;
        var row4 = WIENER_ROWS + 4 * REST_UNIT_STRIDE;
        var tail = 0;
        flow:
        {
            if ((edges & HAVE_TOP) != 0) {
                setPtrs(sc, row0, row0, row1, row2, row2, row2);
                wienerH(sc, row0, null, 0, lpf, lpfOff, filter, w, edges, bitdepth);
                wienerH(sc, row1, null, 0, lpf, lpfOff + stride, filter, w, edges, bitdepth);
                wienerH(sc, row2, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 1;
                    break flow;
                }
                sc[P_WIENER + 4] = sc[P_WIENER + 5] = row3;
                wienerH(sc, row3, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 2;
                    break flow;
                }
                sc[P_WIENER + 5] = row4;
                wienerH(sc, row4, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 3;
                    break flow;
                }
            } else {
                setPtrs(sc, row0, row0, row0, row0, row0, row0);
                wienerH(sc, row0, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 1;
                    break flow;
                }
                sc[P_WIENER + 4] = sc[P_WIENER + 5] = row1;
                wienerH(sc, row1, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 2;
                    break flow;
                }
                sc[P_WIENER + 5] = row2;
                wienerH(sc, row2, left, l, dst, src, filter, w, edges, bitdepth);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = 3;
                    break flow;
                }
                sc[P_WIENER + 6] = row3;
                wienerHv(sc, dst, p, left, l, dst, src, filter, w, edges, bitdepth, bitdepthMax);
                l += 4;
                src += stride;
                p += stride;
                if (--h <= 0) {
                    tail = 3;
                    break flow;
                }
                sc[P_WIENER + 6] = row4;
                wienerHv(sc, dst, p, left, l, dst, src, filter, w, edges, bitdepth, bitdepthMax);
                l += 4;
                src += stride;
                p += stride;
                if (--h <= 0) {
                    tail = 3;
                    break flow;
                }
            }
            sc[P_WIENER + 6] = sc[P_WIENER + 5] + REST_UNIT_STRIDE;
            do {
                wienerHv(sc, dst, p, left, l, dst, src, filter, w, edges, bitdepth, bitdepthMax);
                l += 4;
                src += stride;
                p += stride;
            } while (--h > 0);
            if ((edges & HAVE_BOTTOM) == 0) {
                tail = 3;
                break flow;
            }
            wienerHv(sc, dst, p, null, 0, lpf, lpfBottom, filter, w, edges, bitdepth, bitdepthMax);
            p += stride;
            wienerHv(sc, dst, p, null, 0, lpf, lpfBottom + stride, filter, w, edges, bitdepth, bitdepthMax);
            p += stride;
            tail = 1;
        }
        if (tail == 3) {
            wienerV(sc, dst, p, filter, w, bitdepth, bitdepthMax);
            p += stride;
            tail = 2;
        }
        if (tail == 2) {
            wienerV(sc, dst, p, filter, w, bitdepth, bitdepthMax);
            p += stride;
        }
        wienerV(sc, dst, p, filter, w, bitdepth, bitdepthMax);
    }

    /**
     * Sets the first six Wiener ring slots.
     *
     * @param sc the scratch buffer
     * @param p0 the offset of ring row 0
     * @param p1 the offset of ring row 1
     * @param p2 the offset of ring row 2
     * @param p3 the offset of ring row 3
     * @param p4 the offset of ring row 4
     * @param p5 the offset of ring row 5
     */
    private static void setPtrs(int[] sc, int p0, int p1, int p2, int p3, int p4, int p5) {
        sc[P_WIENER] = p0;
        sc[P_WIENER + 1] = p1;
        sc[P_WIENER + 2] = p2;
        sc[P_WIENER + 3] = p3;
        sc[P_WIENER + 4] = p4;
        sc[P_WIENER + 5] = p5;
    }

    /**
     * Filters one row horizontally into a ring row ({@code wiener_filter_h}).
     *
     * @param sc       the scratch buffer
     * @param out      the scratch offset of the output row
     * @param left     the left columns, or {@code null} to read them from {@code src} when the
     *                 left edge exists
     * @param leftOff  the index of the row's four left samples
     * @param src      the plane holding the row
     * @param srcOff   the index of the row's first sample
     * @param filter   the filter taps, horizontal taps first
     * @param w        the row width
     * @param edges    the edge flags
     * @param bitdepth the bit depth
     */
    private void wienerH(int[] sc, int out, short[] left, int leftOff, short[] src, int srcOff, short[] filter,
                                int w, int edges, int bitdepth) {
        var roundBits = 3 + (bitdepth == 12 ? 2 : 0);
        var rounding = 1 << (roundBits - 1);
        var clipLimit = (1 << (bitdepth + 1 + 7 - roundBits)) - 1;
        var base = 1 << (bitdepth + 6);
        var haveLeft = (edges & HAVE_LEFT) != 0;
        var haveRight = (edges & HAVE_RIGHT) != 0;
        if (w < 6) {
            for (var x = 0; x < w; x++) {
                var sum = base;
                for (var i = 0; i < 7; i++) {
                    var idx = x + i - 3;
                    int px;
                    if (idx < 0) {
                        if (!haveLeft) {
                            px = src[srcOff];
                        } else if (left != null) {
                            px = left[leftOff + 4 + idx];
                        } else {
                            px = src[srcOff + idx];
                        }
                    } else if (idx >= w && !haveRight) {
                        px = src[srcOff + w - 1];
                    } else {
                        px = src[srcOff + idx];
                    }
                    sum += px * filter[i];
                }
                sc[out + x] = Math.clamp((sum + rounding) >> roundBits, 0, clipLimit);
            }
            return;
        }
        var start = 3;
        if (!haveLeft) {
            int first = src[srcOff];
            for (var x = 0; x < 3; x++) {
                var sum = base;
                for (var i = 0; i < 7; i++) {
                    var idx = x + i - 3;
                    sum += (idx < 0 ? first : src[srcOff + idx]) * filter[i];
                }
                sc[out + x] = Math.clamp((sum + rounding) >> roundBits, 0, clipLimit);
            }
        } else if (left != null) {
            for (var x = 0; x < 3; x++) {
                var sum = base;
                for (var i = 0; i < 7; i++) {
                    var idx = x + i - 3;
                    sum += (idx < 0 ? left[leftOff + 4 + idx] : src[srcOff + idx]) * filter[i];
                }
                sc[out + x] = Math.clamp((sum + rounding) >> roundBits, 0, clipLimit);
            }
        } else {
            start = 0;
        }
        var end = haveRight ? w : w - 3;
        wienerHSpan(sc, out, src, srcOff, start, end, filter, base + rounding, roundBits, clipLimit);
        int last = src[srcOff + w - 1];
        for (var x = end; x < w; x++) {
            var sum = base;
            for (var i = 0; i < 7; i++) {
                var idx = x + i - 3;
                sum += (idx >= w ? last : src[srcOff + idx]) * filter[i];
            }
            sc[out + x] = Math.clamp((sum + rounding) >> roundBits, 0, clipLimit);
        }
    }

    /**
     * Filters one output row vertically from the ring alone, the missing seventh row repeating
     * the sixth, and advances the ring ({@code wiener_filter_v}).
     *
     * @param sc          the scratch buffer
     * @param dst         the plane receiving the row
     * @param dstOff      the index of the output row's first sample
     * @param filter      the filter taps, vertical taps at index 8
     * @param w           the row width
     * @param bitdepth    the bit depth
     * @param bitdepthMax the largest sample value
     */
    private void wienerV(int[] sc, short[] dst, int dstOff, short[] filter, int w, int bitdepth,
                                int bitdepthMax) {
        var roundBits = 11 - (bitdepth == 12 ? 2 : 0);
        var rounding = 1 << (roundBits - 1);
        var roundOffset = 1 << (bitdepth + (roundBits - 1));
        wienerVSpan(sc, dst, dstOff, sc[P_WIENER], sc[P_WIENER + 1], sc[P_WIENER + 2], sc[P_WIENER + 3],
                sc[P_WIENER + 4], sc[P_WIENER + 5], sc[P_WIENER + 5], filter, w, rounding - roundOffset, roundBits,
                bitdepthMax);
        for (var i = 0; i < 5; i++) {
            sc[P_WIENER + i] = sc[P_WIENER + i + 1];
        }
    }

    /**
     * Filters one input row horizontally and one output row vertically from the ring and the new
     * row, then stores the new row in the ring and advances it ({@code wiener_filter_hv}).
     *
     * @param sc          the scratch buffer
     * @param dst         the plane receiving the row
     * @param dstOff      the index of the output row's first sample
     * @param left        the left columns, or {@code null} to read them from {@code src}
     * @param leftOff     the index of the input row's four left samples
     * @param src         the plane holding the input row
     * @param srcOff      the index of the input row's first sample
     * @param filter      the filter taps
     * @param w           the row width
     * @param edges       the edge flags
     * @param bitdepth    the bit depth
     * @param bitdepthMax the largest sample value
     */
    private void wienerHv(int[] sc, short[] dst, int dstOff, short[] left, int leftOff, short[] src,
                                 int srcOff, short[] filter, int w, int edges, int bitdepth, int bitdepthMax) {
        var roundBits = 11 - (bitdepth == 12 ? 2 : 0);
        var rounding = 1 << (roundBits - 1);
        var roundOffset = 1 << (bitdepth + (roundBits - 1));
        wienerH(sc, WIENER_TMP, left, leftOff, src, srcOff, filter, w, edges, bitdepth);
        wienerVSpan(sc, dst, dstOff, sc[P_WIENER], sc[P_WIENER + 1], sc[P_WIENER + 2], sc[P_WIENER + 3],
                sc[P_WIENER + 4], sc[P_WIENER + 5], WIENER_TMP, filter, w, rounding - roundOffset, roundBits,
                bitdepthMax);
        System.arraycopy(sc, WIENER_TMP, sc, sc[P_WIENER + 6], w);
        for (var i = 0; i < 6; i++) {
            sc[P_WIENER + i] = sc[P_WIENER + i + 1];
        }
        sc[P_WIENER + 6] = sc[P_WIENER];
    }

    /**
     * Filters the samples of a row whose seven taps all lie inside the row horizontally into a
     * ring row: {@code clamp((bias + sum(src[x - 3 + i] * filter[i])) >> shift, 0, clipLimit)} for
     * every {@code x} from {@code x0} to {@code x1}.
     *
     * @param sc        the scratch buffer
     * @param out       the scratch offset of the output row
     * @param src       the plane holding the row
     * @param srcOff    the index of the row's first sample
     * @param x0        the first output column
     * @param x1        the end of the output columns
     * @param filter    the filter taps, horizontal taps first
     * @param bias      the constant added to every sum, including the rounding offset
     * @param shift     the rounding shift
     * @param clipLimit the largest output value
     */
    void wienerHSpan(int[] sc, int out, short[] src, int srcOff, int x0, int x1, short[] filter, int bias, int shift,
                     int clipLimit) {
        int f0 = filter[0], f1 = filter[1], f2 = filter[2], f3 = filter[3], f4 = filter[4], f5 = filter[5],
                f6 = filter[6];
        for (var x = x0; x < x1; x++) {
            var s = srcOff + x - 3;
            var sum = bias + src[s] * f0 + src[s + 1] * f1 + src[s + 2] * f2 + src[s + 3] * f3
                    + src[s + 4] * f4 + src[s + 5] * f5 + src[s + 6] * f6;
            sc[out + x] = Math.clamp(sum >> shift, 0, clipLimit);
        }
    }

    /**
     * Filters one output row vertically from seven ring rows:
     * {@code clamp((bias + sum(row_i[x] * filter[8 + i])) >> shift, 0, bitdepthMax)}.
     *
     * @param sc          the scratch buffer holding the ring rows
     * @param dst         the plane receiving the row
     * @param dstOff      the index of the output row's first sample
     * @param r0          the scratch offset of the first row
     * @param r1          the scratch offset of the second row
     * @param r2          the scratch offset of the third row
     * @param r3          the scratch offset of the fourth row
     * @param r4          the scratch offset of the fifth row
     * @param r5          the scratch offset of the sixth row
     * @param r6          the scratch offset of the seventh row
     * @param filter      the filter taps, vertical taps at index 8
     * @param w           the row width
     * @param bias        the constant added to every sum, including the rounding offset
     * @param shift       the rounding shift
     * @param bitdepthMax the largest sample value
     */
    void wienerVSpan(int[] sc, short[] dst, int dstOff, int r0, int r1, int r2, int r3, int r4, int r5, int r6,
                     short[] filter, int w, int bias, int shift, int bitdepthMax) {
        int v0 = filter[8], v1 = filter[9], v2 = filter[10], v3 = filter[11], v4 = filter[12], v5 = filter[13],
                v6 = filter[14];
        for (var i = 0; i < w; i++) {
            var sum = bias + sc[r0 + i] * v0 + sc[r1 + i] * v1 + sc[r2 + i] * v2 + sc[r3 + i] * v3
                    + sc[r4 + i] * v4 + sc[r5 + i] * v5 + sc[r6 + i] * v6;
            dst[dstOff + i] = (short) Math.clamp(sum >> shift, 0, bitdepthMax);
        }
    }

    @Override
    public void sgr(int type, short[] dst, int dstOff, int stride, short[] left, int leftOff, short[] lpf, int lpfOff,
                    int w, int h, LrParams params, int edges, int[] scratch, int bitdepthMax) {
        switch (type) {
            case SGR_5X5 -> sgr5x5(dst, dstOff, stride, left, leftOff, lpf, lpfOff, w, h, params, edges, scratch,
                    bitdepthMax);
            case SGR_3X3 -> sgr3x3(dst, dstOff, stride, left, leftOff, lpf, lpfOff, w, h, params, edges, scratch,
                    bitdepthMax);
            default -> sgrMix(dst, dstOff, stride, left, leftOff, lpf, lpfOff, w, h, params, edges, scratch,
                    bitdepthMax);
        }
    }

    /**
     * Applies the 3x3 self-guided filter alone ({@code sgr_3x3_c}).
     *
     * @param dst         the plane, restored in place
     * @param dstOff      the index of the stripe's top-left sample
     * @param stride      the row stride of {@code dst} and {@code lpf}
     * @param left        the left columns
     * @param leftOff     the index of the left samples of the first row
     * @param lpf         the saved rows above and below the stripe
     * @param lpfOff      the index of {@code lpf} row 0 at the unit's first column
     * @param w           the stripe width
     * @param h           the stripe height
     * @param params      the filter parameters
     * @param edges       the edge flags
     * @param sc          the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void sgr3x3(short[] dst, int dstOff, int stride, short[] left, int leftOff, short[] lpf,
                               int lpfOff, int w, int h, LrParams params, int edges, int[] sc, int bitdepthMax) {
        var s1 = params.s1;
        var w1 = params.w1;
        for (var i = 0; i < 3; i++) {
            sc[P_A3 + i] = A3 + i * BUF_STRIDE;
            sc[P_B3 + i] = B3 + i * BUF_STRIDE;
        }
        var src = dstOff;
        var d = dstOff;
        var l = leftOff;
        var lpfBottom = lpfOff + 6 * stride;
        int tail;
        flow:
        {
            if ((edges & HAVE_TOP) != 0) {
                for (var i = 0; i < 3; i++) {
                    sc[P_SUMSQ3 + i] = SUMSQ3 + i * BUF_STRIDE;
                    sc[P_SUM3 + i] = SUM3 + i * BUF_STRIDE;
                }
                box3RowH(sc, SUMSQ3, SUM3, null, 0, lpf, lpfOff, w, edges);
                box3RowH(sc, SUMSQ3 + BUF_STRIDE, SUM3 + BUF_STRIDE, null, 0, lpf, lpfOff + stride, w, edges);
                box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], left, l, dst, src, w, s1, edges,
                        bitdepthMax);
                l += 4;
                src += stride;
                rotate(sc, P_A3, P_B3, 3);
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], left, l, dst, src, w, s1, edges,
                        bitdepthMax);
                l += 4;
                src += stride;
                rotate(sc, P_A3, P_B3, 3);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
            } else {
                for (var i = 0; i < 3; i++) {
                    sc[P_SUMSQ3 + i] = SUMSQ3;
                    sc[P_SUM3 + i] = SUM3;
                }
                box3RowH(sc, SUMSQ3, SUM3, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 3);
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                sc[P_SUMSQ3 + 2] = SUMSQ3 + BUF_STRIDE;
                sc[P_SUM3 + 2] = SUM3 + BUF_STRIDE;
                box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], left, l, dst, src, w, s1, edges,
                        bitdepthMax);
                l += 4;
                src += stride;
                rotate(sc, P_A3, P_B3, 3);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ3 + 2] = SUMSQ3 + 2 * BUF_STRIDE;
                sc[P_SUM3 + 2] = SUM3 + 2 * BUF_STRIDE;
            }
            do {
                box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], left, l, dst, src, w, s1, edges,
                        bitdepthMax);
                l += 4;
                src += stride;
                d = finish1(sc, dst, d, stride, P_A3, P_B3, w, w1, bitdepthMax);
            } while (--h > 0);
            if ((edges & HAVE_BOTTOM) == 0) {
                tail = TAIL_VERT2;
                break flow;
            }
            box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], null, 0, lpf, lpfBottom, w, s1, edges,
                    bitdepthMax);
            d = finish1(sc, dst, d, stride, P_A3, P_B3, w, w1, bitdepthMax);
            box3Hv(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], null, 0, lpf, lpfBottom + stride, w, s1,
                    edges, bitdepthMax);
            finish1(sc, dst, d, stride, P_A3, P_B3, w, w1, bitdepthMax);
            return;
        }
        if (tail == TAIL_VERT2) {
            sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
            sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
            box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], w, s1, bitdepthMax);
            d = finish1(sc, dst, d, stride, P_A3, P_B3, w, w1, bitdepthMax);
        } else {
            sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
            sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
            box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], w, s1, bitdepthMax);
            rotate(sc, P_A3, P_B3, 3);
        }
        sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
        sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
        box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 2], sc[P_B3 + 2], w, s1, bitdepthMax);
        finish1(sc, dst, d, stride, P_A3, P_B3, w, w1, bitdepthMax);
    }

    /**
     * Applies the 5x5 self-guided filter alone ({@code sgr_5x5_c}).
     *
     * @param dst         the plane, restored in place
     * @param dstOff      the index of the stripe's top-left sample
     * @param stride      the row stride of {@code dst} and {@code lpf}
     * @param left        the left columns
     * @param leftOff     the index of the left samples of the first row
     * @param lpf         the saved rows above and below the stripe
     * @param lpfOff      the index of {@code lpf} row 0 at the unit's first column
     * @param w           the stripe width
     * @param h           the stripe height
     * @param params      the filter parameters
     * @param edges       the edge flags
     * @param sc          the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void sgr5x5(short[] dst, int dstOff, int stride, short[] left, int leftOff, short[] lpf,
                               int lpfOff, int w, int h, LrParams params, int edges, int[] sc, int bitdepthMax) {
        var s0 = params.s0;
        var w0 = params.w0;
        for (var i = 0; i < 2; i++) {
            sc[P_A5 + i] = A5 + i * BUF_STRIDE;
            sc[P_B5 + i] = B5 + i * BUF_STRIDE;
        }
        var src = dstOff;
        var d = dstOff;
        var l = leftOff;
        var lpfBottom = lpfOff + 6 * stride;
        int tail;
        flow:
        {
            if ((edges & HAVE_TOP) != 0) {
                setSlots5(sc, SUMSQ5, SUM5, 0, 0, 1, 2, 3);
                box5RowH(sc, SUMSQ5, SUM5, null, 0, lpf, lpfOff, w, edges);
                box5RowH(sc, SUMSQ5 + BUF_STRIDE, SUM5 + BUF_STRIDE, null, 0, lpf, lpfOff + stride, w, edges);
                box5RowH(sc, SUMSQ5 + 2 * BUF_STRIDE, SUM5 + 2 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                box5RowH(sc, SUMSQ5 + 3 * BUF_STRIDE, SUM5 + 3 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 4 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 4 * BUF_STRIDE;
            } else {
                setSlots5(sc, SUMSQ5, SUM5, 0, 0, 0, 0, 0);
                box5RowH(sc, SUMSQ5, SUM5, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                sc[P_SUMSQ5 + 4] = SUMSQ5 + BUF_STRIDE;
                sc[P_SUM5 + 4] = SUM5 + BUF_STRIDE;
                box5RowH(sc, SUMSQ5 + BUF_STRIDE, SUM5 + BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 2 * BUF_STRIDE;
                sc[P_SUMSQ5 + 4] = SUMSQ5 + 3 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 2 * BUF_STRIDE;
                sc[P_SUM5 + 4] = SUM5 + 3 * BUF_STRIDE;
                box5RowH(sc, SUMSQ5 + 2 * BUF_STRIDE, SUM5 + 2 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = TAIL_ODD;
                    break flow;
                }
                box5RowH(sc, SUMSQ5 + 3 * BUF_STRIDE, SUM5 + 3 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                d = finish2(sc, dst, d, stride, P_A5, P_B5, w, 2, w0, bitdepthMax);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 4 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 4 * BUF_STRIDE;
            }
            do {
                box5RowH(sc, sc[P_SUMSQ5 + 3], sc[P_SUM5 + 3], left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                if (--h <= 0) {
                    tail = TAIL_ODD;
                    break flow;
                }
                box5RowH(sc, sc[P_SUMSQ5 + 4], sc[P_SUM5 + 4], left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                d = finish2(sc, dst, d, stride, P_A5, P_B5, w, 2, w0, bitdepthMax);
            } while (--h > 0);
            if ((edges & HAVE_BOTTOM) == 0) {
                tail = TAIL_VERT2;
                break flow;
            }
            box5RowH(sc, sc[P_SUMSQ5 + 3], sc[P_SUM5 + 3], null, 0, lpf, lpfBottom, w, edges);
            box5RowH(sc, sc[P_SUMSQ5 + 4], sc[P_SUM5 + 4], null, 0, lpf, lpfBottom + stride, w, edges);
            tail = TAIL_OUTPUT2;
        }
        switch (tail) {
            case TAIL_OUTPUT2, TAIL_VERT2 -> {
                if (tail == TAIL_VERT2) {
                    sc[P_SUMSQ5 + 3] = sc[P_SUMSQ5 + 2];
                    sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 2];
                    sc[P_SUM5 + 3] = sc[P_SUM5 + 2];
                    sc[P_SUM5 + 4] = sc[P_SUM5 + 2];
                }
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                finish2(sc, dst, d, stride, P_A5, P_B5, w, 2, w0, bitdepthMax);
                return;
            }
            case TAIL_ODD -> {
                sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 3];
                sc[P_SUM5 + 4] = sc[P_SUM5 + 3];
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                d = finish2(sc, dst, d, stride, P_A5, P_B5, w, 2, w0, bitdepthMax);
            }
            default -> {
                sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 3];
                sc[P_SUM5 + 4] = sc[P_SUM5 + 3];
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
            }
        }
        sc[P_SUMSQ5 + 3] = sc[P_SUMSQ5 + 2];
        sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 2];
        sc[P_SUM5 + 3] = sc[P_SUM5 + 2];
        sc[P_SUM5 + 4] = sc[P_SUM5 + 2];
        box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
        finish2(sc, dst, d, stride, P_A5, P_B5, w, 1, w0, bitdepthMax);
    }

    /**
     * Applies both self-guided passes and combines them ({@code sgr_mix_c}).
     *
     * @param dst         the plane, restored in place
     * @param dstOff      the index of the stripe's top-left sample
     * @param stride      the row stride of {@code dst} and {@code lpf}
     * @param left        the left columns
     * @param leftOff     the index of the left samples of the first row
     * @param lpf         the saved rows above and below the stripe
     * @param lpfOff      the index of {@code lpf} row 0 at the unit's first column
     * @param w           the stripe width
     * @param h           the stripe height
     * @param params      the filter parameters
     * @param edges       the edge flags
     * @param sc          the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private static void sgrMix(short[] dst, int dstOff, int stride, short[] left, int leftOff, short[] lpf,
                               int lpfOff, int w, int h, LrParams params, int edges, int[] sc, int bitdepthMax) {
        var s0 = params.s0;
        var s1 = params.s1;
        var w0 = params.w0;
        var w1 = params.w1;
        for (var i = 0; i < 2; i++) {
            sc[P_A5 + i] = A5 + i * BUF_STRIDE;
            sc[P_B5 + i] = B5 + i * BUF_STRIDE;
        }
        for (var i = 0; i < 4; i++) {
            sc[P_A3 + i] = A3 + i * BUF_STRIDE;
            sc[P_B3 + i] = B3 + i * BUF_STRIDE;
        }
        var src = dstOff;
        var d = dstOff;
        var l = leftOff;
        var lpfBottom = lpfOff + 6 * stride;
        int tail;
        flow:
        {
            if ((edges & HAVE_TOP) != 0) {
                setSlots5(sc, SUMSQ5, SUM5, 0, 0, 1, 2, 3);
                for (var i = 0; i < 3; i++) {
                    sc[P_SUMSQ3 + i] = SUMSQ3 + i * BUF_STRIDE;
                    sc[P_SUM3 + i] = SUM3 + i * BUF_STRIDE;
                }
                box35RowH(sc, SUMSQ3, SUM3, SUMSQ5, SUM5, null, 0, lpf, lpfOff, w, edges);
                box35RowH(sc, SUMSQ3 + BUF_STRIDE, SUM3 + BUF_STRIDE, SUMSQ5 + BUF_STRIDE, SUM5 + BUF_STRIDE,
                        null, 0, lpf, lpfOff + stride, w, edges);
                box35RowH(sc, SUMSQ3 + 2 * BUF_STRIDE, SUM3 + 2 * BUF_STRIDE, SUMSQ5 + 2 * BUF_STRIDE,
                        SUM5 + 2 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], SUMSQ5 + 3 * BUF_STRIDE, SUM5 + 3 * BUF_STRIDE,
                        left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 4 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 4 * BUF_STRIDE;
            } else {
                setSlots5(sc, SUMSQ5, SUM5, 0, 0, 0, 0, 0);
                for (var i = 0; i < 3; i++) {
                    sc[P_SUMSQ3 + i] = SUMSQ3;
                    sc[P_SUM3 + i] = SUM3;
                }
                box35RowH(sc, SUMSQ3, SUM3, SUMSQ5, SUM5, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_VERT1;
                    break flow;
                }
                sc[P_SUMSQ5 + 4] = SUMSQ5 + BUF_STRIDE;
                sc[P_SUM5 + 4] = SUM5 + BUF_STRIDE;
                sc[P_SUMSQ3 + 2] = SUMSQ3 + BUF_STRIDE;
                sc[P_SUM3 + 2] = SUM3 + BUF_STRIDE;
                box35RowH(sc, SUMSQ3 + BUF_STRIDE, SUM3 + BUF_STRIDE, SUMSQ5 + BUF_STRIDE, SUM5 + BUF_STRIDE,
                        left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 2 * BUF_STRIDE;
                sc[P_SUMSQ5 + 4] = SUMSQ5 + 3 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 2 * BUF_STRIDE;
                sc[P_SUM5 + 4] = SUM5 + 3 * BUF_STRIDE;
                sc[P_SUMSQ3 + 2] = SUMSQ3 + 2 * BUF_STRIDE;
                sc[P_SUM3 + 2] = SUM3 + 2 * BUF_STRIDE;
                box35RowH(sc, SUMSQ3 + 2 * BUF_STRIDE, SUM3 + 2 * BUF_STRIDE, SUMSQ5 + 2 * BUF_STRIDE,
                        SUM5 + 2 * BUF_STRIDE, left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_ODD;
                    break flow;
                }
                box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], SUMSQ5 + 3 * BUF_STRIDE, SUM5 + 3 * BUF_STRIDE,
                        left, l, dst, src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                d = finishMix(sc, dst, d, stride, w, 2, w0, w1, bitdepthMax);
                if (--h <= 0) {
                    tail = TAIL_VERT2;
                    break flow;
                }
                sc[P_SUMSQ5 + 3] = SUMSQ5 + 4 * BUF_STRIDE;
                sc[P_SUM5 + 3] = SUM5 + 4 * BUF_STRIDE;
            }
            do {
                box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], sc[P_SUMSQ5 + 3], sc[P_SUM5 + 3], left, l, dst,
                        src, w, edges);
                l += 4;
                src += stride;
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
                if (--h <= 0) {
                    tail = TAIL_ODD;
                    break flow;
                }
                box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], sc[P_SUMSQ5 + 4], sc[P_SUM5 + 4], left, l, dst,
                        src, w, edges);
                l += 4;
                src += stride;
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                d = finishMix(sc, dst, d, stride, w, 2, w0, w1, bitdepthMax);
            } while (--h > 0);
            if ((edges & HAVE_BOTTOM) == 0) {
                tail = TAIL_VERT2;
                break flow;
            }
            box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], sc[P_SUMSQ5 + 3], sc[P_SUM5 + 3], null, 0, lpf,
                    lpfBottom, w, edges);
            box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
            rotate(sc, P_A3, P_B3, 4);
            box35RowH(sc, sc[P_SUMSQ3 + 2], sc[P_SUM3 + 2], sc[P_SUMSQ5 + 4], sc[P_SUM5 + 4], null, 0, lpf,
                    lpfBottom + stride, w, edges);
            tail = TAIL_OUTPUT2;
        }
        switch (tail) {
            case TAIL_OUTPUT2, TAIL_VERT2 -> {
                if (tail == TAIL_VERT2) {
                    sc[P_SUMSQ5 + 3] = sc[P_SUMSQ5 + 2];
                    sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 2];
                    sc[P_SUM5 + 3] = sc[P_SUM5 + 2];
                    sc[P_SUM5 + 4] = sc[P_SUM5 + 2];
                    sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
                    sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
                    box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                    rotate(sc, P_A3, P_B3, 4);
                    sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
                    sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
                }
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                finishMix(sc, dst, d, stride, w, 2, w0, w1, bitdepthMax);
                return;
            }
            case TAIL_ODD -> {
                sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 3];
                sc[P_SUM5 + 4] = sc[P_SUM5 + 3];
                sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
                sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                d = finishMix(sc, dst, d, stride, w, 2, w0, w1, bitdepthMax);
            }
            default -> {
                sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 3];
                sc[P_SUM5 + 4] = sc[P_SUM5 + 3];
                sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
                sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
                box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
                rotate(sc, P_A5, P_B5, 2);
                box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
                rotate(sc, P_A3, P_B3, 4);
            }
        }
        sc[P_SUMSQ5 + 3] = sc[P_SUMSQ5 + 2];
        sc[P_SUMSQ5 + 4] = sc[P_SUMSQ5 + 2];
        sc[P_SUM5 + 3] = sc[P_SUM5 + 2];
        sc[P_SUM5 + 4] = sc[P_SUM5 + 2];
        sc[P_SUMSQ3 + 2] = sc[P_SUMSQ3 + 1];
        sc[P_SUM3 + 2] = sc[P_SUM3 + 1];
        box5Vert(sc, P_SUMSQ5, P_SUM5, sc[P_A5 + 1], sc[P_B5 + 1], w, s0, bitdepthMax);
        box3Vert(sc, P_SUMSQ3, P_SUM3, sc[P_A3 + 3], sc[P_B3 + 3], w, s1, bitdepthMax);
        rotate(sc, P_A3, P_B3, 4);
        finishMix(sc, dst, d, stride, w, 1, w0, w1, bitdepthMax);
    }

    /**
     * Sets the five 5x5 box sum slots to rows of the 5x5 buffers.
     *
     * @param sc       the scratch buffer
     * @param sumsqBuf the scratch offset of the sum of squares rows
     * @param sumBuf   the scratch offset of the sum rows
     * @param r0       the row held by slot 0
     * @param r1       the row held by slot 1
     * @param r2       the row held by slot 2
     * @param r3       the row held by slot 3
     * @param r4       the row held by slot 4
     */
    private static void setSlots5(int[] sc, int sumsqBuf, int sumBuf, int r0, int r1, int r2, int r3, int r4) {
        sc[P_SUMSQ5] = sumsqBuf + r0 * BUF_STRIDE;
        sc[P_SUMSQ5 + 1] = sumsqBuf + r1 * BUF_STRIDE;
        sc[P_SUMSQ5 + 2] = sumsqBuf + r2 * BUF_STRIDE;
        sc[P_SUMSQ5 + 3] = sumsqBuf + r3 * BUF_STRIDE;
        sc[P_SUMSQ5 + 4] = sumsqBuf + r4 * BUF_STRIDE;
        sc[P_SUM5] = sumBuf + r0 * BUF_STRIDE;
        sc[P_SUM5 + 1] = sumBuf + r1 * BUF_STRIDE;
        sc[P_SUM5 + 2] = sumBuf + r2 * BUF_STRIDE;
        sc[P_SUM5 + 3] = sumBuf + r3 * BUF_STRIDE;
        sc[P_SUM5 + 4] = sumBuf + r4 * BUF_STRIDE;
    }

    /**
     * Rotates two parallel slot arrays left by one entry, the first entry moving to the end
     * ({@code rotate}).
     *
     * @param sc the scratch buffer
     * @param pa the slot index of the first array
     * @param pb the slot index of the second array
     * @param n  the number of slots of each array
     */
    private static void rotate(int[] sc, int pa, int pb, int n) {
        var a = sc[pa];
        var b = sc[pb];
        for (var i = 0; i < n - 1; i++) {
            sc[pa + i] = sc[pa + i + 1];
            sc[pb + i] = sc[pb + i + 1];
        }
        sc[pa + n - 1] = a;
        sc[pb + n - 1] = b;
    }

    /**
     * Rotates the five 5x5 box sum slots left by two entries, the first two moving to the end
     * ({@code rotate5_x2}).
     *
     * @param sc    the scratch buffer
     * @param pSq   the slot index of the sum of squares rows
     * @param pSum  the slot index of the sum rows
     */
    private static void rotate5x2(int[] sc, int pSq, int pSum) {
        var sq0 = sc[pSq];
        var sq1 = sc[pSq + 1];
        var s0 = sc[pSum];
        var s1 = sc[pSum + 1];
        sc[pSq] = sc[pSq + 2];
        sc[pSq + 1] = sc[pSq + 3];
        sc[pSq + 2] = sc[pSq + 4];
        sc[pSum] = sc[pSum + 2];
        sc[pSum + 1] = sc[pSum + 3];
        sc[pSum + 2] = sc[pSum + 4];
        sc[pSq + 3] = sq0;
        sc[pSq + 4] = sq1;
        sc[pSum + 3] = s0;
        sc[pSum + 4] = s1;
    }

    /**
     * Computes the horizontal 3-sample box sums and sums of squares of one row
     * ({@code sgr_box3_row_h}), for the {@code w + 2} positions from {@code -1} to {@code w},
     * position {@code -1} landing at index 0 of the output rows.
     *
     * @param sc      the scratch buffer
     * @param sumsq   the scratch offset of the sum of squares row
     * @param sum     the scratch offset of the sum row
     * @param left    the left columns, or {@code null} to read them from {@code src}
     * @param leftOff the index of the row's four left samples
     * @param src     the plane holding the row
     * @param srcOff  the index of the row's first sample
     * @param w       the row width
     * @param edges   the edge flags
     */
    private static void box3RowH(int[] sc, int sumsq, int sum, short[] left, int leftOff, short[] src, int srcOff,
                                 int w, int edges) {
        var haveLeft = (edges & HAVE_LEFT) != 0;
        var haveRight = (edges & HAVE_RIGHT) != 0;
        int a = haveLeft ? (left != null ? left[leftOff + 2] : src[srcOff - 2]) : src[srcOff];
        int b = haveLeft ? (left != null ? left[leftOff + 3] : src[srcOff - 1]) : src[srcOff];
        int last = src[srcOff + w - 1];
        for (var x = -1; x < w + 1; x++) {
            int c = (x + 1 < w || haveRight) ? src[srcOff + x + 1] : last;
            sc[sum + 1 + x] = a + b + c;
            sc[sumsq + 1 + x] = a * a + b * b + c * c;
            a = b;
            b = c;
        }
    }

    /**
     * Computes the horizontal 5-sample box sums and sums of squares of one row
     * ({@code sgr_box5_row_h}), for the {@code w + 2} positions from {@code -1} to {@code w},
     * position {@code -1} landing at index 0 of the output rows.
     *
     * @param sc      the scratch buffer
     * @param sumsq   the scratch offset of the sum of squares row
     * @param sum     the scratch offset of the sum row
     * @param left    the left columns, or {@code null} to read them from {@code src}
     * @param leftOff the index of the row's four left samples
     * @param src     the plane holding the row
     * @param srcOff  the index of the row's first sample
     * @param w       the row width
     * @param edges   the edge flags
     */
    private static void box5RowH(int[] sc, int sumsq, int sum, short[] left, int leftOff, short[] src, int srcOff,
                                 int w, int edges) {
        var haveLeft = (edges & HAVE_LEFT) != 0;
        var haveRight = (edges & HAVE_RIGHT) != 0;
        int a = haveLeft ? (left != null ? left[leftOff + 1] : src[srcOff - 3]) : src[srcOff];
        int b = haveLeft ? (left != null ? left[leftOff + 2] : src[srcOff - 2]) : src[srcOff];
        int c = haveLeft ? (left != null ? left[leftOff + 3] : src[srcOff - 1]) : src[srcOff];
        int d = src[srcOff];
        int last = src[srcOff + w - 1];
        for (var x = -1; x < w + 1; x++) {
            int e = (x + 2 < w || haveRight) ? src[srcOff + x + 2] : last;
            sc[sum + 1 + x] = a + b + c + d + e;
            sc[sumsq + 1 + x] = a * a + b * b + c * c + d * d + e * e;
            a = b;
            b = c;
            c = d;
            d = e;
        }
    }

    /**
     * Computes both the 3-sample and the 5-sample horizontal box sums of one row
     * ({@code sgr_box35_row_h}).
     *
     * @param sc      the scratch buffer
     * @param sumsq3  the scratch offset of the 3-sample sum of squares row
     * @param sum3    the scratch offset of the 3-sample sum row
     * @param sumsq5  the scratch offset of the 5-sample sum of squares row
     * @param sum5    the scratch offset of the 5-sample sum row
     * @param left    the left columns, or {@code null} to read them from {@code src}
     * @param leftOff the index of the row's four left samples
     * @param src     the plane holding the row
     * @param srcOff  the index of the row's first sample
     * @param w       the row width
     * @param edges   the edge flags
     */
    private static void box35RowH(int[] sc, int sumsq3, int sum3, int sumsq5, int sum5, short[] left, int leftOff,
                                  short[] src, int srcOff, int w, int edges) {
        box3RowH(sc, sumsq3, sum3, left, leftOff, src, srcOff, w, edges);
        box5RowH(sc, sumsq5, sum5, left, leftOff, src, srcOff, w, edges);
    }

    /**
     * Sums three ring rows of box sums vertically, derives the {@code A} and {@code B}
     * coefficients of the 3x3 pass, and rotates the ring ({@code sgr_box3_vert}).
     *
     * @param sc          the scratch buffer
     * @param pSq         the slot index of the three sum of squares rows
     * @param pSum        the slot index of the three sum rows
     * @param aOut        the scratch offset of the output {@code A} row
     * @param bOut        the scratch offset of the output {@code B} row
     * @param w           the row width
     * @param s           the 3x3 scale
     * @param bitdepthMax the largest sample value
     */
    private static void box3Vert(int[] sc, int pSq, int pSum, int aOut, int bOut, int w, int s, int bitdepthMax) {
        int q0 = sc[pSq], q1 = sc[pSq + 1], q2 = sc[pSq + 2];
        int u0 = sc[pSum], u1 = sc[pSum + 1], u2 = sc[pSum + 2];
        for (var x = 0; x < w + 2; x++) {
            sc[aOut + x] = sc[q0 + x] + sc[q1 + x] + sc[q2 + x];
            sc[bOut + x] = sc[u0 + x] + sc[u1 + x] + sc[u2 + x];
        }
        calcRowAb(sc, aOut, bOut, w, s, bitdepthMax, 9, 455);
        rotate(sc, pSq, pSum, 3);
    }

    /**
     * Sums five ring rows of box sums vertically, derives the {@code A} and {@code B}
     * coefficients of the 5x5 pass, and rotates the ring by two rows ({@code sgr_box5_vert}).
     *
     * @param sc          the scratch buffer
     * @param pSq         the slot index of the five sum of squares rows
     * @param pSum        the slot index of the five sum rows
     * @param aOut        the scratch offset of the output {@code A} row
     * @param bOut        the scratch offset of the output {@code B} row
     * @param w           the row width
     * @param s           the 5x5 scale
     * @param bitdepthMax the largest sample value
     */
    private static void box5Vert(int[] sc, int pSq, int pSum, int aOut, int bOut, int w, int s, int bitdepthMax) {
        int q0 = sc[pSq], q1 = sc[pSq + 1], q2 = sc[pSq + 2], q3 = sc[pSq + 3], q4 = sc[pSq + 4];
        int u0 = sc[pSum], u1 = sc[pSum + 1], u2 = sc[pSum + 2], u3 = sc[pSum + 3], u4 = sc[pSum + 4];
        for (var x = 0; x < w + 2; x++) {
            sc[aOut + x] = sc[q0 + x] + sc[q1 + x] + sc[q2 + x] + sc[q3 + x] + sc[q4 + x];
            sc[bOut + x] = sc[u0 + x] + sc[u1 + x] + sc[u2 + x] + sc[u3 + x] + sc[u4 + x];
        }
        calcRowAb(sc, aOut, bOut, w, s, bitdepthMax, 25, 164);
        rotate5x2(sc, pSq, pSum);
    }

    /**
     * Turns one row of box sums of squares and box sums into the self-guided {@code A} and
     * {@code B} coefficients, in place ({@code sgr_calc_row_ab}): the sums of squares row
     * receives {@code A} and the sums row receives {@code B}.
     *
     * @param sc          the scratch buffer
     * @param aa          the scratch offset of the sums of squares row
     * @param bb          the scratch offset of the sums row
     * @param w           the row width
     * @param s           the scale
     * @param bitdepthMax the largest sample value
     * @param n           the number of samples in the box, 9 or 25
     * @param oneByX      the box size reciprocal in 1/4096, 455 or 164
     */
    private static void calcRowAb(int[] sc, int aa, int bb, int w, int s, int bitdepthMax, int n, int oneByX) {
        var bitdepthMin8 = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        var aRound = (1 << (2 * bitdepthMin8)) >> 1;
        var bRound = (1 << bitdepthMin8) >> 1;
        for (var i = 0; i < w + 2; i++) {
            var sumB = sc[bb + i];
            var a = (sc[aa + i] + aRound) >> (2 * bitdepthMin8);
            var b = (sumB + bRound) >> bitdepthMin8;
            var p = Math.max(a * n - b * b, 0);
            var z = (p * s + (1 << 19)) >>> 20;
            var x = SGR_X_BY_X[Math.min(z, 255)];
            sc[aa + i] = (x * sumB * oneByX + (1 << 11)) >>> 12;
            sc[bb + i] = x;
        }
    }

    /**
     * Filters one row with the 3x3 coefficients of three ring rows ({@code sgr_finish_filter_row1}).
     *
     * @param sc     the scratch buffer
     * @param tmp    the scratch offset of the output row
     * @param src    the plane holding the unfiltered row
     * @param srcOff the index of the row's first sample
     * @param pA     the slot index of the three {@code A} rows
     * @param pB     the slot index of the three {@code B} rows
     * @param w      the row width
     */
    private static void finishFilterRow1(int[] sc, int tmp, short[] src, int srcOff, int pA, int pB, int w) {
        int a0 = sc[pA], a1 = sc[pA + 1], a2 = sc[pA + 2];
        int b0 = sc[pB], b1 = sc[pB + 1], b2 = sc[pB + 2];
        for (var i = 0; i < w; i++) {
            var j = i + 1;
            var a = (sc[b1 + j] + sc[b1 + j - 1] + sc[b1 + j + 1] + sc[b0 + j] + sc[b2 + j]) * 4
                    + (sc[b0 + j - 1] + sc[b2 + j - 1] + sc[b0 + j + 1] + sc[b2 + j + 1]) * 3;
            var b = (sc[a1 + j] + sc[a1 + j - 1] + sc[a1 + j + 1] + sc[a0 + j] + sc[a2 + j]) * 4
                    + (sc[a0 + j - 1] + sc[a2 + j - 1] + sc[a0 + j + 1] + sc[a2 + j + 1]) * 3;
            sc[tmp + i] = (b - a * src[srcOff + i] + (1 << 8)) >> 9;
        }
    }

    /**
     * Filters up to two rows with the 5x5 coefficients of two ring rows
     * ({@code sgr_finish_filter2}): the first row from both coefficient rows, the second from the
     * second coefficient row alone.
     *
     * @param sc        the scratch buffer
     * @param tmp       the scratch offset of the two output rows
     * @param src       the plane holding the unfiltered rows
     * @param srcOff    the index of the first row's first sample
     * @param srcStride the row stride of {@code src}
     * @param pA        the slot index of the two {@code A} rows
     * @param pB        the slot index of the two {@code B} rows
     * @param w         the row width
     * @param h         the number of rows to filter, 1 or 2
     */
    private static void finishFilter2(int[] sc, int tmp, short[] src, int srcOff, int srcStride, int pA, int pB,
                                      int w, int h) {
        int a0 = sc[pA], a1 = sc[pA + 1];
        int b0 = sc[pB], b1 = sc[pB + 1];
        for (var i = 0; i < w; i++) {
            var j = i + 1;
            var a = (sc[b0 + j] + sc[b1 + j]) * 6
                    + (sc[b0 + j - 1] + sc[b1 + j - 1] + sc[b0 + j + 1] + sc[b1 + j + 1]) * 5;
            var b = (sc[a0 + j] + sc[a1 + j]) * 6
                    + (sc[a0 + j - 1] + sc[a1 + j - 1] + sc[a0 + j + 1] + sc[a1 + j + 1]) * 5;
            sc[tmp + i] = (b - a * src[srcOff + i] + (1 << 8)) >> 9;
        }
        if (h <= 1) {
            return;
        }
        var out = tmp + FILTER_OUT_STRIDE;
        var row = srcOff + srcStride;
        for (var i = 0; i < w; i++) {
            var j = i + 1;
            var a = sc[b1 + j] * 6 + (sc[b1 + j - 1] + sc[b1 + j + 1]) * 5;
            var b = sc[a1 + j] * 6 + (sc[a1 + j - 1] + sc[a1 + j + 1]) * 5;
            sc[out + i] = (b - a * src[row + i] + (1 << 7)) >> 8;
        }
    }

    /**
     * Adds one weighted filter output row to a plane row ({@code sgr_weighted_row1}).
     *
     * @param dst         the plane
     * @param dstOff      the index of the row's first sample
     * @param sc          the scratch buffer
     * @param t1          the scratch offset of the filter output row
     * @param w           the row width
     * @param w1          the weight
     * @param bitdepthMax the largest sample value
     */
    private static void weightedRow1(short[] dst, int dstOff, int[] sc, int t1, int w, int w1, int bitdepthMax) {
        for (var i = 0; i < w; i++) {
            var v = w1 * sc[t1 + i];
            dst[dstOff + i] = (short) Math.clamp(dst[dstOff + i] + ((v + (1 << 10)) >> 11), 0, bitdepthMax);
        }
    }

    /**
     * Adds two weighted filter outputs to up to two plane rows ({@code sgr_weighted2}).
     *
     * @param dst         the plane
     * @param dstOff      the index of the first row's first sample
     * @param stride      the row stride of {@code dst}
     * @param sc          the scratch buffer
     * @param t1          the scratch offset of the first filter output rows
     * @param t2          the scratch offset of the second filter output rows
     * @param w           the row width
     * @param h           the number of rows
     * @param w0          the weight of the first output
     * @param w1          the weight of the second output
     * @param bitdepthMax the largest sample value
     */
    private static void weighted2(short[] dst, int dstOff, int stride, int[] sc, int t1, int t2, int w, int h, int w0,
                                  int w1, int bitdepthMax) {
        for (var j = 0; j < h; j++) {
            var row = dstOff + j * stride;
            var r1 = t1 + j * FILTER_OUT_STRIDE;
            var r2 = t2 + j * FILTER_OUT_STRIDE;
            for (var i = 0; i < w; i++) {
                var v = w0 * sc[r1 + i] + w1 * sc[r2 + i];
                dst[row + i] = (short) Math.clamp(dst[row + i] + ((v + (1 << 10)) >> 11), 0, bitdepthMax);
            }
        }
    }

    /**
     * Outputs one row of the 3x3 pass and rotates the coefficient ring ({@code sgr_finish1}).
     *
     * @param sc          the scratch buffer
     * @param dst         the plane
     * @param dstOff      the index of the output row's first sample
     * @param stride      the row stride of {@code dst}
     * @param pA          the slot index of the three {@code A} rows
     * @param pB          the slot index of the three {@code B} rows
     * @param w           the row width
     * @param w1          the 3x3 weight
     * @param bitdepthMax the largest sample value
     * @return the index of the next output row
     */
    private static int finish1(int[] sc, short[] dst, int dstOff, int stride, int pA, int pB, int w, int w1,
                               int bitdepthMax) {
        finishFilterRow1(sc, TMP3, dst, dstOff, pA, pB, w);
        weightedRow1(dst, dstOff, sc, TMP3, w, w1, bitdepthMax);
        rotate(sc, pA, pB, 3);
        return dstOff + stride;
    }

    /**
     * Outputs up to two rows of the 5x5 pass and rotates the coefficient ring
     * ({@code sgr_finish2}).
     *
     * @param sc          the scratch buffer
     * @param dst         the plane
     * @param dstOff      the index of the first output row's first sample
     * @param stride      the row stride of {@code dst}
     * @param pA          the slot index of the two {@code A} rows
     * @param pB          the slot index of the two {@code B} rows
     * @param w           the row width
     * @param h           the number of rows to output, 1 or 2
     * @param w0          the 5x5 weight
     * @param bitdepthMax the largest sample value
     * @return the index of the next output row
     */
    private static int finish2(int[] sc, short[] dst, int dstOff, int stride, int pA, int pB, int w, int h, int w0,
                               int bitdepthMax) {
        finishFilter2(sc, TMP5, dst, dstOff, stride, pA, pB, w, h);
        weightedRow1(dst, dstOff, sc, TMP5, w, w0, bitdepthMax);
        var next = dstOff + stride;
        if (h > 1) {
            weightedRow1(dst, next, sc, TMP5 + FILTER_OUT_STRIDE, w, w0, bitdepthMax);
            next += stride;
        }
        rotate(sc, pA, pB, 2);
        return next;
    }

    /**
     * Outputs up to two rows combining both passes and rotates both coefficient rings
     * ({@code sgr_finish_mix}).
     *
     * @param sc          the scratch buffer
     * @param dst         the plane
     * @param dstOff      the index of the first output row's first sample
     * @param stride      the row stride of {@code dst}
     * @param w           the row width
     * @param h           the number of rows to output, 1 or 2
     * @param w0          the 5x5 weight
     * @param w1          the 3x3 weight
     * @param bitdepthMax the largest sample value
     * @return the index of the next output row
     */
    private static int finishMix(int[] sc, short[] dst, int dstOff, int stride, int w, int h, int w0, int w1,
                                 int bitdepthMax) {
        finishFilter2(sc, TMP5, dst, dstOff, stride, P_A5, P_B5, w, h);
        finishFilterRow1(sc, TMP3, dst, dstOff, P_A3, P_B3, w);
        if (h > 1) {
            finishFilterRow1(sc, TMP3 + FILTER_OUT_STRIDE, dst, dstOff + stride, P_A3 + 1, P_B3 + 1, w);
        }
        weighted2(dst, dstOff, stride, sc, TMP5, TMP3, w, h, w0, w1, bitdepthMax);
        rotate(sc, P_A5, P_B5, 2);
        rotate(sc, P_A3, P_B3, 4);
        return dstOff + h * stride;
    }

    /**
     * Computes the 3-sample box sums of one row into the next ring row and derives one row of 3x3
     * coefficients ({@code sgr_box3_hv}).
     *
     * @param sc          the scratch buffer
     * @param pSq         the slot index of the three sum of squares rows
     * @param pSum        the slot index of the three sum rows
     * @param aOut        the scratch offset of the output {@code A} row
     * @param bOut        the scratch offset of the output {@code B} row
     * @param left        the left columns, or {@code null} to read them from {@code src}
     * @param leftOff     the index of the row's four left samples
     * @param src         the plane holding the row
     * @param srcOff      the index of the row's first sample
     * @param w           the row width
     * @param s           the 3x3 scale
     * @param edges       the edge flags
     * @param bitdepthMax the largest sample value
     */
    private static void box3Hv(int[] sc, int pSq, int pSum, int aOut, int bOut, short[] left, int leftOff,
                               short[] src, int srcOff, int w, int s, int edges, int bitdepthMax) {
        box3RowH(sc, sc[pSq + 2], sc[pSum + 2], left, leftOff, src, srcOff, w, edges);
        box3Vert(sc, pSq, pSum, aOut, bOut, w, s, bitdepthMax);
    }
}
