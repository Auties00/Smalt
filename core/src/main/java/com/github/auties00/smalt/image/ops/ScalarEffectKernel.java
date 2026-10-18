package com.github.auties00.smalt.image.ops;

/**
 * Implements {@link EffectKernel} with scalar code; the reference for the Vector API
 * implementation, which also uses it for the ends of rows.
 */
final class ScalarEffectKernel implements EffectKernel {
    /**
     * Creates the kernel.
     */
    ScalarEffectKernel() {
    }

    @Override
    public void boxAccumulate(byte[] src, int offset, int weight, int[] acc, int length) {
        boxAccumulateRange(src, offset, weight, acc, 0, length);
    }

    /**
     * Accumulates the samples {@code [from, to)} of a run.
     *
     * @param src    the 8-bit samples
     * @param offset the index of the run's first sample
     * @param weight the multiplier
     * @param acc    the accumulators
     * @param from   the first index within the run
     * @param to     the index after the last one
     */
    void boxAccumulateRange(byte[] src, int offset, int weight, int[] acc, int from, int to) {
        for (var i = from; i < to; i++) {
            acc[i] += weight * (src[offset + i] & 0xFF);
        }
    }

    @Override
    public void boxRow(byte[] src, int add, int subtract, int left, int right, int[] acc, int ww, int fw, byte[] dst,
                       int dstOffset, int length) {
        boxRowRange(src, add, subtract, left, right, acc, ww, fw, dst, dstOffset, 0, length);
    }

    /**
     * Filters the samples {@code [from, to)} of a row with the extended box filter.
     *
     * @param src       the source samples
     * @param add       the index of the entering row
     * @param subtract  the index of the leaving row
     * @param left      the index of the far start row
     * @param right     the index of the far end row
     * @param acc       the sliding sums
     * @param ww        the whole-sample weight
     * @param fw        the fractional end weight
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination sample
     * @param from      the first index within the row
     * @param to        the index after the last one
     */
    void boxRowRange(byte[] src, int add, int subtract, int left, int right, int[] acc, int ww, int fw, byte[] dst,
                     int dstOffset, int from, int to) {
        for (var i = from; i < to; i++) {
            var sum = acc[i] + (src[add + i] & 0xFF) - (src[subtract + i] & 0xFF);
            acc[i] = sum;
            var bulk = sum * ww + ((src[left + i] & 0xFF) + (src[right + i] & 0xFF)) * fw;
            dst[dstOffset + i] = (byte) ((bulk + (1 << 23)) >>> 24);
        }
    }

    @Override
    public void mixRow(byte[] src, int srcOffset, int width, int[] matrix, byte[] dst, int dstOffset) {
        mixRowRange(src, srcOffset, matrix, dst, dstOffset, 0, width);
    }

    /**
     * Mixes the pixels {@code [from, to)} of a row.
     *
     * @param src       the RGBA source
     * @param srcOffset the index of the row's first source byte
     * @param matrix    the coefficients
     * @param dst       the RGBA destination
     * @param dstOffset the index of the row's first destination byte
     * @param from      the first pixel
     * @param to        the pixel after the last one
     */
    void mixRowRange(byte[] src, int srcOffset, int[] matrix, byte[] dst, int dstOffset, int from, int to) {
        for (var x = from; x < to; x++) {
            var s = srcOffset + 4 * x;
            var d = dstOffset + 4 * x;
            var r = src[s] & 0xFF;
            var g = src[s + 1] & 0xFF;
            var b = src[s + 2] & 0xFF;
            var a = src[s + 3];
            dst[d] = (byte) Math.clamp((matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[3]) >> 16, 0, 255);
            dst[d + 1] = (byte) Math.clamp((matrix[4] * r + matrix[5] * g + matrix[6] * b + matrix[7]) >> 16, 0, 255);
            dst[d + 2] = (byte) Math.clamp((matrix[8] * r + matrix[9] * g + matrix[10] * b + matrix[11]) >> 16, 0, 255);
            dst[d + 3] = a;
        }
    }

    @Override
    public void unsharpRow(byte[] original, int originalOffset, byte[] blurred, int blurredOffset, int length,
                           int percent, int threshold, byte[] dst, int dstOffset) {
        unsharpRange(original, originalOffset, blurred, blurredOffset, percent, threshold, dst, dstOffset, 0, length);
    }

    /**
     * Sharpens the samples {@code [from, to)} of a run.
     *
     * @param original       the original samples
     * @param originalOffset the index of the run's first original sample
     * @param blurred        the blurred samples
     * @param blurredOffset  the index of the run's first blurred sample
     * @param percent        the strength in percent
     * @param threshold      the smallest difference that is sharpened, exclusive
     * @param dst            the destination samples
     * @param dstOffset      the index of the run's first destination sample
     * @param from           the first index within the run
     * @param to             the index after the last one
     */
    void unsharpRange(byte[] original, int originalOffset, byte[] blurred, int blurredOffset, int percent,
                      int threshold, byte[] dst, int dstOffset, int from, int to) {
        for (var i = from; i < to; i++) {
            var value = original[originalOffset + i] & 0xFF;
            var diff = value - (blurred[blurredOffset + i] & 0xFF);
            if (Math.abs(diff) > threshold) {
                dst[dstOffset + i] = (byte) Math.clamp(value + diff * percent / 100, 0, 255);
            } else {
                dst[dstOffset + i] = (byte) value;
            }
        }
    }

    @Override
    public void median9Row(byte[] above, byte[] row, byte[] below, int offset, int length, byte[] dst, int dstOffset) {
        median9Range(above, row, below, offset, dst, dstOffset, 0, length);
    }

    /**
     * Computes the 3x3 medians of the samples {@code [from, to)} of a row with the min/max
     * network of {@link #median9Row}'s vector twin.
     *
     * @param above     the row above, padded
     * @param row       the row itself, padded
     * @param below     the row below, padded
     * @param offset    the index of the first sample in each padded row
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination sample
     * @param from      the first sample
     * @param to        the sample after the last one
     */
    void median9Range(byte[] above, byte[] row, byte[] below, int offset, byte[] dst, int dstOffset, int from, int to) {
        for (var i = from; i < to; i++) {
            var s = offset + i;
            int p0 = above[s - 4] & 0xFF, p1 = above[s] & 0xFF, p2 = above[s + 4] & 0xFF;
            int p3 = row[s - 4] & 0xFF, p4 = row[s] & 0xFF, p5 = row[s + 4] & 0xFF;
            int p6 = below[s - 4] & 0xFF, p7 = below[s] & 0xFF, p8 = below[s + 4] & 0xFF;
            int t;
            t = Math.min(p1, p2); p2 = Math.max(p1, p2); p1 = t;
            t = Math.min(p4, p5); p5 = Math.max(p4, p5); p4 = t;
            t = Math.min(p7, p8); p8 = Math.max(p7, p8); p7 = t;
            t = Math.min(p0, p1); p1 = Math.max(p0, p1); p0 = t;
            t = Math.min(p3, p4); p4 = Math.max(p3, p4); p3 = t;
            t = Math.min(p6, p7); p7 = Math.max(p6, p7); p6 = t;
            t = Math.min(p1, p2); p2 = Math.max(p1, p2); p1 = t;
            t = Math.min(p4, p5); p5 = Math.max(p4, p5); p4 = t;
            t = Math.min(p7, p8); p8 = Math.max(p7, p8); p7 = t;
            p3 = Math.max(p0, p3);
            p5 = Math.min(p5, p8);
            t = Math.min(p4, p7); p7 = Math.max(p4, p7); p4 = t;
            p6 = Math.max(p3, p6);
            p4 = Math.max(p1, p4);
            p2 = Math.min(p2, p5);
            p4 = Math.min(p4, p7);
            t = Math.min(p4, p2); p2 = Math.max(p4, p2); p4 = t;
            p4 = Math.max(p6, p4);
            dst[dstOffset + i] = (byte) Math.min(p4, p2);
        }
    }

    @Override
    public void blend(BlendMode mode, float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg,
                      float[] sb, float[] sa, float opacity, int length) {
        blendRange(mode, br, bg, bb, ba, sr, sg, sb, sa, opacity, 0, length);
    }

    /**
     * Composites the pixels {@code [from, to)} of a row.
     *
     * @param mode    the blend mode
     * @param br      the backdrop red values, replaced by the result
     * @param bg      the backdrop green values, replaced by the result
     * @param bb      the backdrop blue values, replaced by the result
     * @param ba      the backdrop alpha values, replaced by the result
     * @param sr      the source red values
     * @param sg      the source green values
     * @param sb      the source blue values
     * @param sa      the source alpha values
     * @param opacity the factor applied to the source alpha
     * @param from    the first pixel
     * @param to      the pixel after the last one
     */
    void blendRange(BlendMode mode, float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg, float[] sb,
                    float[] sa, float opacity, int from, int to) {
        if (mode != BlendMode.NORMAL) {
            for (var i = from; i < to; i++) {
                var ab = ba[i];
                sr[i] = (1 - ab) * sr[i] + ab * blend(mode, br[i], sr[i]);
                sg[i] = (1 - ab) * sg[i] + ab * blend(mode, bg[i], sg[i]);
                sb[i] = (1 - ab) * sb[i] + ab * blend(mode, bb[i], sb[i]);
            }
        }
        compositeRange(br, bg, bb, ba, sr, sg, sb, sa, opacity, from, to);
    }

    /**
     * Composites the mixed source colors of the pixels {@code [from, to)} over the backdrop,
     * source-over.
     *
     * @param br      the backdrop red values, replaced by the result
     * @param bg      the backdrop green values, replaced by the result
     * @param bb      the backdrop blue values, replaced by the result
     * @param ba      the backdrop alpha values, replaced by the result
     * @param sr      the mixed source red values
     * @param sg      the mixed source green values
     * @param sb      the mixed source blue values
     * @param sa      the source alpha values
     * @param opacity the factor applied to the source alpha
     * @param from    the first pixel
     * @param to      the pixel after the last one
     */
    void compositeRange(float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg, float[] sb,
                        float[] sa, float opacity, int from, int to) {
        for (var i = from; i < to; i++) {
            var as = sa[i] * opacity;
            var backWeight = ba[i] * (1 - as);
            var ao = as + backWeight;
            if (ao > 0) {
                br[i] = (as * sr[i] + backWeight * br[i]) / ao;
                bg[i] = (as * sg[i] + backWeight * bg[i]) / ao;
                bb[i] = (as * sb[i] + backWeight * bb[i]) / ao;
            } else {
                br[i] = 0;
                bg[i] = 0;
                bb[i] = 0;
            }
            ba[i] = ao;
        }
    }

    /**
     * Evaluates a blend function.
     *
     * @param mode the blend mode
     * @param cb   the backdrop color
     * @param cs   the source color
     * @return the blended color
     */
    static float blend(BlendMode mode, float cb, float cs) {
        return switch (mode) {
            case NORMAL -> cs;
            case MULTIPLY -> cb * cs;
            case SCREEN -> (cb + cs) - cb * cs;
            case OVERLAY -> {
                if (cb <= 0.5f) {
                    yield cs * (2 * cb);
                }
                var t = 2 * cb - 1;
                yield (cs + t) - cs * t;
            }
            case DARKEN -> Math.min(cb, cs);
            case LIGHTEN -> Math.max(cb, cs);
            case COLOR_DODGE -> {
                if (cb <= 0) {
                    yield 0;
                }
                if (cs >= 1) {
                    yield 1;
                }
                yield Math.min(1, cb / (1 - cs));
            }
            case COLOR_BURN -> {
                if (cb >= 1) {
                    yield 1;
                }
                if (cs <= 0) {
                    yield 0;
                }
                yield 1 - Math.min(1, (1 - cb) / cs);
            }
            case HARD_LIGHT -> {
                if (cs <= 0.5f) {
                    yield cb * (2 * cs);
                }
                var t = 2 * cs - 1;
                yield (cb + t) - cb * t;
            }
            case SOFT_LIGHT -> {
                if (cs <= 0.5f) {
                    yield cb - ((1 - 2 * cs) * cb) * (1 - cb);
                }
                var d = cb <= 0.25f ? ((16 * cb - 12) * cb + 4) * cb : (float) Math.sqrt(cb);
                yield cb + (2 * cs - 1) * (d - cb);
            }
            case DIFFERENCE -> Math.abs(cb - cs);
            case EXCLUSION -> (cb + cs) - 2 * (cb * cs);
            case ADD -> Math.min(1, cb + cs);
            case SUBTRACT -> Math.max(0, cb - cs);
        };
    }
}
