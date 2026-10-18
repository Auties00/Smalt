package com.github.auties00.smalt.video.av1.internal.encoder;

import java.util.Arrays;

/**
 * Prunes the directional intra modes of a block from the histogram of the source's gradient
 * orientations: a linear model scores each of the eight directional modes from the histogram and
 * the modes scoring at most a threshold are skipped.
 *
 * @implNote This implementation is libaom's {@code prune_intra_mode_with_hog}
 * ({@code av1/encoder/intra_mode_search_utils.h}): Sobel gradients over the block's
 * interior, 32 orientation bins weighted by {@code |dx| + |dy|} and normalized by their
 * total, and the trained single-layer model {@code av1_intra_hog_model}, whose weights
 * and bias are copied unchanged.
 */
final class IntraHog {
    /**
     * The number of orientation bins.
     */
    private static final int BINS = 32;

    /**
     * The number of directional modes.
     */
    private static final int MODES = 8;

    /**
     * The upper bounds of the orientation bins, as {@code dy / dx} in 16-bit fixed point.
     */
    private static final int[] THRESHOLDS = {
            -1334015, -441798, -261605, -183158, -138560, -109331, -88359, -72303,
            -59392, -48579, -39272, -30982, -23445, -16400, -9715, -3194,
            3227, 9748, 16433, 23478, 31015, 39305, 48611, 59425,
            72336, 88392, 109364, 138593, 183191, 261638, 441831, Integer.MAX_VALUE
    };

    /**
     * The model bias of each directional mode, from vertical to D67.
     */
    private static final float[] BIAS = {
            0.450578f, 0.695518f, -0.717944f, -0.639894f,
            -0.602019f, -0.453454f, 0.055857f, -0.465480f,
    };

    /**
     * The model weights, 32 per directional mode.
     */
    private static final float[] WEIGHTS = {
            -3.076402f, -3.757063f, -3.275266f, -3.180665f, -3.452105f, -3.216593f,
            -2.871212f, -3.134296f, -1.822324f, -2.401411f, -1.541016f, -1.195322f,
            -0.434156f, 0.322868f, 2.260546f, 3.368715f, 3.989290f, 3.308487f,
            2.277893f, 0.923793f, 0.026412f, -0.385174f, -0.718622f, -1.408867f,
            -1.050558f, -2.323941f, -2.225827f, -2.585453f, -3.054283f, -2.875087f,
            -2.985709f, -3.447155f, 3.758139f, 3.204353f, 2.170998f, 0.826587f,
            -0.269665f, -0.702068f, -1.085776f, -2.175249f, -1.623180f, -2.975142f,
            -2.779629f, -3.190799f, -3.521900f, -3.375480f, -3.319355f, -3.897389f,
            -3.172334f, -3.594528f, -2.879132f, -2.547777f, -2.921023f, -2.281844f,
            -1.818988f, -2.041771f, -0.618268f, -1.396458f, -0.567153f, -0.285868f,
            -0.088058f, 0.753494f, 2.092413f, 3.215266f, -3.300277f, -2.748658f,
            -2.315784f, -2.423671f, -2.257283f, -2.269583f, -2.196660f, -2.301076f,
            -2.646516f, -2.271319f, -2.254366f, -2.300102f, -2.217960f, -2.473300f,
            -2.116866f, -2.528246f, -3.314712f, -1.701010f, -0.589040f, -0.088077f,
            0.813112f, 1.702213f, 2.653045f, 3.351749f, 3.243554f, 3.199409f,
            2.437856f, 1.468854f, 0.533039f, -0.099065f, -0.622643f, -2.200732f,
            -4.228861f, -2.875263f, -1.273956f, -0.433280f, 0.803771f, 1.975043f,
            3.179528f, 3.939064f, 3.454379f, 3.689386f, 3.116411f, 1.970991f,
            0.798406f, -0.628514f, -1.252546f, -2.825176f, -4.090178f, -3.777448f,
            -3.227314f, -3.479403f, -3.320569f, -3.159372f, -2.729202f, -2.722341f,
            -3.054913f, -2.742923f, -2.612703f, -2.662632f, -2.907314f, -3.117794f,
            -3.102660f, -3.970972f, -4.891357f, -3.935582f, -3.347758f, -2.721924f,
            -2.219011f, -1.702391f, -0.866529f, -0.153743f, 0.107733f, 1.416882f,
            2.572884f, 3.607755f, 3.974820f, 3.997783f, 2.970459f, 0.791687f,
            -1.478921f, -1.228154f, -1.216955f, -1.765932f, -1.951003f, -1.985301f,
            -1.975881f, -1.985593f, -2.422371f, -2.419978f, -2.531288f, -2.951853f,
            -3.071380f, -3.277027f, -3.373539f, -4.462010f, -0.967888f, 0.805524f,
            2.794130f, 3.685984f, 3.745195f, 3.252444f, 2.316108f, 1.399146f,
            -0.136519f, -0.162811f, -1.004357f, -1.667911f, -1.964662f, -2.937579f,
            -3.019533f, -3.942766f, -5.102767f, -3.882073f, -3.532027f, -3.451956f,
            -2.944015f, -2.643064f, -2.529872f, -2.077290f, -2.809965f, -1.803734f,
            -1.783593f, -1.662585f, -1.415484f, -1.392673f, -0.788794f, -1.204819f,
            -1.998864f, -1.182102f, -0.892110f, -1.317415f, -1.359112f, -1.522867f,
            -1.468552f, -1.779072f, -2.332959f, -2.160346f, -2.329387f, -2.631259f,
            -2.744936f, -3.052494f, -2.787363f, -3.442548f, -4.245075f, -3.032172f,
            -2.061609f, -1.768116f, -1.286072f, -0.706587f, -0.192413f, 0.386938f,
            0.716997f, 1.481393f, 2.216702f, 2.737986f, 3.109809f, 3.226084f,
            2.490098f, -0.095827f, -3.864816f, -3.507248f, -3.128925f, -2.908251f,
            -2.883836f, -2.881411f, -2.524377f, -2.624478f, -2.399573f, -2.367718f,
            -1.918255f, -1.926277f, -1.694584f, -1.723790f, -0.966491f, -1.183115f,
            -1.430687f, 0.872896f, 2.766550f, 3.610080f, 3.578041f, 3.334928f,
            2.586680f, 1.895721f, 1.122195f, 0.488519f, -0.140689f, -0.799076f,
            -1.222860f, -1.502437f, -1.900969f, -3.206816f,
    };

    /**
     * The side of the square regions whose gradients are cached.
     */
    private static final int REGION = 64;

    /**
     * The histogram scratch.
     */
    private final float[] hist = new float[BINS];

    /**
     * The orientation bin of each sample of the cached region: -1 for a vertical gradient split
     * between the first and the last bin, -2 for no gradient.
     */
    private final byte[] bins = new byte[REGION * REGION];

    /**
     * The gradient magnitude {@code |dx| + |dy|} of each sample of the cached region.
     */
    private final int[] magnitudes = new int[REGION * REGION];

    /**
     * The plane of the cached region, or {@code null}.
     */
    private short[] cachedPlane;

    /**
     * The left column of the cached region.
     */
    private int cachedX = -1;

    /**
     * The top row of the cached region.
     */
    private int cachedY = -1;

    /**
     * Creates a pruner.
     */
    IntraHog() {
    }

    /**
     * Returns the directional modes the gradient histogram of a block rules out.
     *
     * <p>The gradients of the 64 by 64 region holding the block are computed once and reused by
     * every block of the region, as long as the plane's samples do not change.
     *
     * @param src the source plane
     * @param stride the source stride
     * @param width the plane width
     * @param height the plane height
     * @param x the block's left column
     * @param y the block's top row
     * @param rows the visible rows of the block
     * @param cols the visible columns of the block
     * @param scale the factor applied to the normalized histogram: 1 for luma, the number of
     * luma samples per chroma sample for chroma
     * @param threshold the score at or below which a mode is ruled out
     * @return a mask with bit {@code m} set for each ruled-out directional mode {@code m}, 1 to 8
     */
    int skipMask(short[] src, int stride, int width, int height, int x, int y, int rows, int cols, float scale,
                 float threshold) {
        var rx = x & ~(REGION - 1);
        var ry = y & ~(REGION - 1);
        if (src != cachedPlane || rx != cachedX || ry != cachedY) {
            gradients(src, stride, width, height, rx, ry);
            cachedPlane = src;
            cachedX = rx;
            cachedY = ry;
        }
        var h = hist;
        Arrays.fill(h, 0);
        var total = 0.1f;
        for (var r = 1; r < rows - 1; r++) {
            var row = (y - ry + r) * REGION + (x - rx);
            for (var c = 1; c < cols - 1; c++) {
                var bin = bins[row + c];
                if (bin == -2) {
                    continue;
                }
                var temp = magnitudes[row + c];
                total += temp;
                if (bin == -1) {
                    h[0] += temp / 2;
                    h[BINS - 1] += temp / 2;
                } else {
                    h[bin] += temp;
                }
            }
        }
        var mask = 0;
        for (var m = 0; m < MODES; m++) {
            var score = BIAS[m];
            var w = m * BINS;
            for (var i = 0; i < BINS; i++) {
                score += WEIGHTS[w + i] * (h[i] / total * scale);
            }
            if (score <= threshold) {
                mask |= 1 << (m + 1);
            }
        }
        return mask;
    }

    /**
     * Forgets the cached gradients, for a plane whose samples changed.
     */
    void reset() {
        cachedPlane = null;
    }

    /**
     * Computes the Sobel gradients of a region, leaving samples on the plane's border without one.
     *
     * @param src the plane
     * @param stride the plane stride
     * @param width the plane width
     * @param height the plane height
     * @param rx the region's left column
     * @param ry the region's top row
     */
    private void gradients(short[] src, int stride, int width, int height, int rx, int ry) {
        for (var r = 0; r < REGION; r++) {
            var py = ry + r;
            for (var c = 0; c < REGION; c++) {
                var px = rx + c;
                var i = r * REGION + c;
                if (py < 1 || px < 1 || py >= height - 1 || px >= width - 1) {
                    bins[i] = -2;
                    continue;
                }
                var p = py * stride + px;
                var dx = (src[p + 1 - stride] + 2 * src[p + 1] + src[p + 1 + stride])
                        - (src[p - 1 - stride] + 2 * src[p - 1] + src[p - 1 + stride]);
                var dy = (src[p + stride - 1] + 2 * src[p + stride] + src[p + stride + 1])
                        - (src[p - stride - 1] + 2 * src[p - stride] + src[p - stride + 1]);
                if (dx == 0 && dy == 0) {
                    bins[i] = -2;
                    continue;
                }
                magnitudes[i] = Math.abs(dx) + Math.abs(dy);
                bins[i] = (byte) (dx == 0 ? -1 : bin(dx, dy));
            }
        }
    }

    /**
     * Returns the orientation bin of a gradient.
     *
     * @param dx the horizontal gradient, not zero
     * @param dy the vertical gradient
     * @return the bin, 0 to 31
     */
    private static int bin(int dx, int dy) {
        var ratio = (int) (((long) dy << 16) / dx);
        int lo;
        if (ratio <= THRESHOLDS[7]) {
            lo = 0;
        } else if (ratio <= THRESHOLDS[15]) {
            lo = 8;
        } else if (ratio <= THRESHOLDS[23]) {
            lo = 16;
        } else {
            lo = 24;
        }
        for (var i = lo; i < lo + 7; i++) {
            if (ratio <= THRESHOLDS[i]) {
                return i;
            }
        }
        return lo + 7;
    }
}
