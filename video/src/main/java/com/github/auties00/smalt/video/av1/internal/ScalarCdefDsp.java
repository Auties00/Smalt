package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the CDEF kernels with plain scalar Java; the reference for every other
 * {@link CdefDsp}.
 *
 * @implNote This implementation ports dav1d's {@code cdef_tmpl.c} statement by statement: the
 *           padded 12x12 block copy marks missing samples with {@code Short.MIN_VALUE}, which the
 *           tap constraint turns into a zero contribution and which the clamping range ignores, as
 *           in dav1d's {@code fill} and {@code padding}. The 8-bit and high bit depth templates of
 *           dav1d compute identically here, so one implementation serves every bit depth.
 */
public final class ScalarCdefDsp extends CdefDsp {
    /**
     * The index, in the padded block copy, of the block's top-left sample: two rows and two
     * columns of padding precede it.
     */
    static final int TMP_ORIGIN = 2 * TMP_STRIDE + 2;

    /**
     * The value marking a missing sample in the padded block copy: a large negative number as a
     * signed value and a large positive one as an unsigned value.
     */
    private static final short MISSING = Short.MIN_VALUE;

    /**
     * The divisors of the partial diagonal sums of the direction search, {@code 840 / (n + 1)}.
     */
    private static final int[] DIV_TABLE = {840, 420, 280, 210, 168, 140, 120};

    /**
     * The offset, in the direction search scratch, of the horizontal and vertical partial sums
     * ({@code [2][8]}).
     */
    static final int HV = 0;

    /**
     * The offset, in the direction search scratch, of the diagonal partial sums
     * ({@code [2][15]}).
     */
    static final int DIAG = HV + 16;

    /**
     * The offset, in the direction search scratch, of the alternate partial sums
     * ({@code [4][11]}).
     */
    static final int ALT = DIAG + 30;

    /**
     * The offset, in the direction search scratch, of the eight direction costs.
     */
    private static final int COST = ALT + 44;

    /**
     * Creates the scalar implementation.
     */
    public ScalarCdefDsp() {
    }

    @Override
    public int dir(short[] src, int srcOff, int stride, int[] variance, int bitdepthMax) {
        var shift = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        for (var i = 1; i < 1 + COST; i++) {
            variance[i] = 0;
        }
        var s = 1;
        for (var y = 0; y < 8; y++) {
            var row = srcOff + y * stride;
            for (var x = 0; x < 8; x++) {
                var px = (src[row + x] >> shift) - 128;
                variance[s + DIAG + y + x] += px;
                variance[s + ALT + y + (x >> 1)] += px;
                variance[s + HV + y] += px;
                variance[s + ALT + 11 + 3 + y - (x >> 1)] += px;
                variance[s + DIAG + 15 + 7 + y - x] += px;
                variance[s + ALT + 22 + 3 - (y >> 1) + x] += px;
                variance[s + HV + 8 + x] += px;
                variance[s + ALT + 33 + (y >> 1) + x] += px;
            }
        }
        return finish(variance);
    }

    /**
     * Evaluates the eight direction costs from the partial sums of the direction search and
     * returns the best direction, storing its variance in element 0 ({@code cdef_find_dir} after
     * its accumulation).
     *
     * @param variance the scratch holding the partial sums from element 1 on; the costs are
     *                 written after them
     * @return the direction, 0 to 7
     */
    static int finish(int[] variance) {
        var s = 1;
        var cost = s + COST;
        for (var n = 0; n < 8; n++) {
            variance[cost + n] = 0;
        }
        for (var n = 0; n < 8; n++) {
            var a = variance[s + HV + n];
            var b = variance[s + HV + 8 + n];
            variance[cost + 2] += a * a;
            variance[cost + 6] += b * b;
        }
        variance[cost + 2] *= 105;
        variance[cost + 6] *= 105;
        for (var n = 0; n < 7; n++) {
            var d = DIV_TABLE[n];
            var a0 = variance[s + DIAG + n];
            var a1 = variance[s + DIAG + 14 - n];
            var b0 = variance[s + DIAG + 15 + n];
            var b1 = variance[s + DIAG + 15 + 14 - n];
            variance[cost] += (a0 * a0 + a1 * a1) * d;
            variance[cost + 4] += (b0 * b0 + b1 * b1) * d;
        }
        var c0 = variance[s + DIAG + 7];
        var c4 = variance[s + DIAG + 15 + 7];
        variance[cost] += c0 * c0 * 105;
        variance[cost + 4] += c4 * c4 * 105;
        for (var n = 0; n < 4; n++) {
            var alt = s + ALT + n * 11;
            var c = 0;
            for (var m = 0; m < 5; m++) {
                var p = variance[alt + 3 + m];
                c += p * p;
            }
            c *= 105;
            for (var m = 0; m < 3; m++) {
                var d = DIV_TABLE[2 * m + 1];
                var p0 = variance[alt + m];
                var p1 = variance[alt + 10 - m];
                c += (p0 * p0 + p1 * p1) * d;
            }
            variance[cost + n * 2 + 1] += c;
        }
        var bestDir = 0;
        var bestCost = variance[cost];
        for (var n = 1; n < 8; n++) {
            if (Integer.compareUnsigned(variance[cost + n], bestCost) > 0) {
                bestCost = variance[cost + n];
                bestDir = n;
            }
        }
        variance[0] = (bestCost - variance[cost + (bestDir ^ 4)]) >>> 10;
        return bestDir;
    }

    @Override
    public void filter(int layoutIndex, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                       short[] top, int topOff, short[] bottom, int bottomOff, int priStrength,
                       int secStrength, int dir, int damping, int edges, short[] scratch, int bitdepthMax) {
        var w = layoutIndex == LAYOUT_8X8 ? 8 : 4;
        var h = layoutIndex == LAYOUT_4X4 ? 4 : 8;
        padding(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, w, h, edges);
        if (priStrength != 0) {
            if (secStrength != 0) {
                filterPrimarySecondary(dst, dstOff, stride, scratch, priStrength, secStrength, dir, damping, w, h,
                        bitdepthMax);
            } else {
                filterPrimary(dst, dstOff, stride, scratch, priStrength, dir, damping, w, h, bitdepthMax);
            }
        } else {
            filterSecondary(dst, dstOff, stride, scratch, secStrength, dir, damping, w, h);
        }
    }

    /**
     * Applies both the primary and the secondary filter, clamping each output to the range of
     * the samples it was computed from.
     *
     * @param dst         the plane holding the block
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the row stride of {@code dst}
     * @param tmp         the padded block copy
     * @param priStrength the primary strength
     * @param secStrength the secondary strength
     * @param dir         the filter direction
     * @param damping     the damping, the base-2 logarithm of the strength roll-off
     * @param w           the block width
     * @param h           the block height
     * @param bitdepthMax the largest sample value
     */
    private static void filterPrimarySecondary(short[] dst, int dstOff, int stride, short[] tmp, int priStrength,
                                               int secStrength, int dir, int damping, int w, int h,
                                               int bitdepthMax) {
        var bitdepthMin8 = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        var priTap = 4 - ((priStrength >> bitdepthMin8) & 1);
        var priShift = Math.max(0, damping - ulog2(priStrength));
        var secShift = damping - ulog2(secStrength);
        var dirs = (dir + 2) * 2;
        var dirsPlus = (dir + 4) * 2;
        var dirsMinus = dir * 2;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = TMP_ORIGIN + y * TMP_STRIDE;
            for (var x = 0; x < w; x++) {
                int px = dst[row + x];
                var sum = 0;
                var max = px;
                var min = px;
                var priTapK = priTap;
                for (var k = 0; k < 2; k++) {
                    var off1 = DIRECTIONS[dirs + k];
                    int p0 = tmp[t + x + off1];
                    int p1 = tmp[t + x - off1];
                    sum += priTapK * constrain(p0 - px, priStrength, priShift);
                    sum += priTapK * constrain(p1 - px, priStrength, priShift);
                    priTapK = (priTapK & 3) | 2;
                    min = Math.min(p0 & 0xFFFF, min);
                    max = Math.max(p0, max);
                    min = Math.min(p1 & 0xFFFF, min);
                    max = Math.max(p1, max);
                    var off2 = DIRECTIONS[dirsPlus + k];
                    var off3 = DIRECTIONS[dirsMinus + k];
                    int s0 = tmp[t + x + off2];
                    int s1 = tmp[t + x - off2];
                    int s2 = tmp[t + x + off3];
                    int s3 = tmp[t + x - off3];
                    var secTap = 2 - k;
                    sum += secTap * constrain(s0 - px, secStrength, secShift);
                    sum += secTap * constrain(s1 - px, secStrength, secShift);
                    sum += secTap * constrain(s2 - px, secStrength, secShift);
                    sum += secTap * constrain(s3 - px, secStrength, secShift);
                    min = Math.min(s0 & 0xFFFF, min);
                    max = Math.max(s0, max);
                    min = Math.min(s1 & 0xFFFF, min);
                    max = Math.max(s1, max);
                    min = Math.min(s2 & 0xFFFF, min);
                    max = Math.max(s2, max);
                    min = Math.min(s3 & 0xFFFF, min);
                    max = Math.max(s3, max);
                }
                var value = px + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4);
                dst[row + x] = (short) Math.clamp(value, min, max);
            }
        }
    }

    /**
     * Applies the primary filter alone.
     *
     * @param dst         the plane holding the block
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the row stride of {@code dst}
     * @param tmp         the padded block copy
     * @param priStrength the primary strength
     * @param dir         the filter direction
     * @param damping     the damping, the base-2 logarithm of the strength roll-off
     * @param w           the block width
     * @param h           the block height
     * @param bitdepthMax the largest sample value
     */
    private static void filterPrimary(short[] dst, int dstOff, int stride, short[] tmp, int priStrength, int dir,
                                      int damping, int w, int h, int bitdepthMax) {
        var bitdepthMin8 = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        var priTap = 4 - ((priStrength >> bitdepthMin8) & 1);
        var priShift = Math.max(0, damping - ulog2(priStrength));
        var dirs = (dir + 2) * 2;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = TMP_ORIGIN + y * TMP_STRIDE;
            for (var x = 0; x < w; x++) {
                int px = dst[row + x];
                var sum = 0;
                var priTapK = priTap;
                for (var k = 0; k < 2; k++) {
                    var off = DIRECTIONS[dirs + k];
                    int p0 = tmp[t + x + off];
                    int p1 = tmp[t + x - off];
                    sum += priTapK * constrain(p0 - px, priStrength, priShift);
                    sum += priTapK * constrain(p1 - px, priStrength, priShift);
                    priTapK = (priTapK & 3) | 2;
                }
                dst[row + x] = (short) (px + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4));
            }
        }
    }

    /**
     * Applies the secondary filter alone.
     *
     * @param dst         the plane holding the block
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the row stride of {@code dst}
     * @param tmp         the padded block copy
     * @param secStrength the secondary strength
     * @param dir         the filter direction
     * @param damping     the damping, the base-2 logarithm of the strength roll-off
     * @param w           the block width
     * @param h           the block height
     */
    private static void filterSecondary(short[] dst, int dstOff, int stride, short[] tmp, int secStrength, int dir,
                                        int damping, int w, int h) {
        var secShift = damping - ulog2(secStrength);
        var dirsPlus = (dir + 4) * 2;
        var dirsMinus = dir * 2;
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = TMP_ORIGIN + y * TMP_STRIDE;
            for (var x = 0; x < w; x++) {
                int px = dst[row + x];
                var sum = 0;
                for (var k = 0; k < 2; k++) {
                    var off1 = DIRECTIONS[dirsPlus + k];
                    var off2 = DIRECTIONS[dirsMinus + k];
                    int s0 = tmp[t + x + off1];
                    int s1 = tmp[t + x - off1];
                    int s2 = tmp[t + x + off2];
                    int s3 = tmp[t + x - off2];
                    var secTap = 2 - k;
                    sum += secTap * constrain(s0 - px, secStrength, secShift);
                    sum += secTap * constrain(s1 - px, secStrength, secShift);
                    sum += secTap * constrain(s2 - px, secStrength, secShift);
                    sum += secTap * constrain(s3 - px, secStrength, secShift);
                }
                dst[row + x] = (short) (px + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4));
            }
        }
    }

    /**
     * Copies the block and its available surroundings into the padded block copy, marking the
     * samples beyond missing edges with {@link #MISSING}.
     *
     * @param tmp       the padded block copy, of at least {@link #SCRATCH_SHORTS} entries
     * @param src       the plane holding the block
     * @param srcOff    the index of the block's top-left sample
     * @param stride    the row stride of {@code src}, {@code top} and {@code bottom}
     * @param left      the left columns, two samples per row
     * @param leftOff   the index of the left samples of the block's first row
     * @param top       the two rows above the block
     * @param topOff    the index of the farther row's sample above the block's first column
     * @param bottom    the two rows below the block
     * @param bottomOff the index of the nearer row's sample below the block's first column
     * @param w         the block width
     * @param h         the block height
     * @param edges     the edge flags
     */
    static void padding(short[] tmp, short[] src, int srcOff, int stride, short[] left, int leftOff,
                                short[] top, int topOff, short[] bottom, int bottomOff, int w, int h, int edges) {
        var xStart = -2;
        var xEnd = w + 2;
        var yStart = -2;
        var yEnd = h + 2;
        if ((edges & HAVE_TOP) == 0) {
            fill(tmp, TMP_ORIGIN - 2 - 2 * TMP_STRIDE, w + 4, 2);
            yStart = 0;
        }
        if ((edges & HAVE_BOTTOM) == 0) {
            fill(tmp, TMP_ORIGIN + h * TMP_STRIDE - 2, w + 4, 2);
            yEnd -= 2;
        }
        if ((edges & HAVE_LEFT) == 0) {
            fill(tmp, TMP_ORIGIN + yStart * TMP_STRIDE - 2, 2, yEnd - yStart);
            xStart = 0;
        }
        if ((edges & HAVE_RIGHT) == 0) {
            fill(tmp, TMP_ORIGIN + yStart * TMP_STRIDE + w, 2, yEnd - yStart);
            xEnd -= 2;
        }
        var span = xEnd - xStart;
        var topRow = topOff + xStart;
        for (var y = yStart; y < 0; y++) {
            System.arraycopy(top, topRow, tmp, TMP_ORIGIN + y * TMP_STRIDE + xStart, span);
            topRow += stride;
        }
        if (xStart < 0) {
            for (var y = 0; y < h; y++) {
                var t = TMP_ORIGIN + y * TMP_STRIDE;
                var l = leftOff + 2 * y;
                tmp[t - 2] = left[l];
                tmp[t - 1] = left[l + 1];
            }
        }
        for (var y = 0; y < h; y++) {
            System.arraycopy(src, srcOff + y * stride, tmp, TMP_ORIGIN + y * TMP_STRIDE, xEnd);
        }
        var bottomRow = bottomOff + xStart;
        for (var y = h; y < yEnd; y++) {
            System.arraycopy(bottom, bottomRow, tmp, TMP_ORIGIN + y * TMP_STRIDE + xStart, span);
            bottomRow += stride;
        }
    }

    /**
     * Marks a rectangle of the padded block copy as missing.
     *
     * @param tmp   the padded block copy
     * @param off   the index of the rectangle's top-left entry
     * @param w     the rectangle width
     * @param h     the rectangle height
     */
    private static void fill(short[] tmp, int off, int w, int h) {
        for (var y = 0; y < h; y++) {
            var row = off + y * TMP_STRIDE;
            for (var x = 0; x < w; x++) {
                tmp[row + x] = MISSING;
            }
        }
    }

    /**
     * Constrains the difference between a tap and the filtered sample (AV1 specification
     * section 7.15.3, {@code constrain}).
     *
     * @param diff      the tap minus the filtered sample
     * @param threshold the filter strength
     * @param shift     the damping shift
     * @return the constrained difference, with the sign of {@code diff}
     */
    private static int constrain(int diff, int threshold, int shift) {
        var adiff = Math.abs(diff);
        var value = Math.min(adiff, Math.max(0, threshold - (adiff >> shift)));
        return diff < 0 ? -value : value;
    }

    /**
     * Returns the floor of the base-2 logarithm of a positive value.
     *
     * @param value the value, positive
     * @return the index of its highest set bit
     */
    static int ulog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }
}
