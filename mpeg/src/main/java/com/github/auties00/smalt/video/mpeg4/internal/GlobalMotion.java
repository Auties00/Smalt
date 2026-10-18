package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Computes the global motion compensated prediction of S(GMC)-VOPs: the sprite reference points
 * of ISO/IEC 14496-2 7.8.4, the warping functions of 7.8.5 for 0 to 3 warping points, the sample
 * reconstruction of 7.8.6 with the GMC rounding, and the averaged motion vector of a GMC
 * macroblock of 7.8.7.3.
 *
 * <p>{@link #setup(VideoObjectLayer, Vop)} derives the warping parameters of a VOP; afterwards the
 * prediction of any macroblock can be computed independently, from any thread. Warped positions
 * are evaluated with 64-bit arithmetic, which the clause recommends for affine transforms.
 *
 * <p>An instance belongs to one decoder.
 */
public final class GlobalMotion {
    /**
     * The warping accuracy {@code s}: samples are positioned in units of {@code 1/s} sample.
     */
    private int s;

    /**
     * The base-2 logarithm of {@link #s}.
     */
    private int sBits;

    /**
     * The number of warping points, from 0 to 3.
     */
    private int points;

    /**
     * The luminance warp: {@code F = (fx0 + fxi * i + fxj * j + round) >> shift} and the same for
     * {@code G} with the {@code g*} coefficients; the shift is 0 for 0 and 1 points.
     */
    private long fx0;

    /**
     * The coefficient of the column in the luminance horizontal warp.
     */
    private long fxi;

    /**
     * The coefficient of the row in the luminance horizontal warp.
     */
    private long fxj;

    /**
     * The constant of the luminance vertical warp.
     */
    private long gy0;

    /**
     * The coefficient of the column in the luminance vertical warp.
     */
    private long gyi;

    /**
     * The coefficient of the row in the luminance vertical warp.
     */
    private long gyj;

    /**
     * The shift of the luminance warp.
     */
    private int shift;

    /**
     * The chrominance warp constant: {@code Fc = (cfx0 + cfxi * Ic + cfxj * Jc + round) >> cshift},
     * with {@code Ic = 4 ic + 1} and {@code Jc = 4 jc + 1} for 2 and 3 points.
     */
    private long cfx0;

    /**
     * The coefficient of {@code Ic} in the chrominance horizontal warp.
     */
    private long cfxi;

    /**
     * The coefficient of {@code Jc} in the chrominance horizontal warp.
     */
    private long cfxj;

    /**
     * The chrominance vertical warp constant.
     */
    private long cgy0;

    /**
     * The coefficient of {@code Ic} in the chrominance vertical warp.
     */
    private long cgyi;

    /**
     * The coefficient of {@code Jc} in the chrominance vertical warp.
     */
    private long cgyj;

    /**
     * The shift of the chrominance warp.
     */
    private int cshift;

    /**
     * The sprite reference point {@code i0'} of the first warping point, in {@code 1/s} samples.
     */
    private long i0;

    /**
     * The sprite reference point {@code j0'} of the first warping point, in {@code 1/s} samples.
     */
    private long j0;

    /**
     * The rounding control of the VOP.
     */
    private int rounding;

    /**
     * Creates an instance.
     */
    public GlobalMotion() {
    }

    /**
     * Derives the warping parameters of an S(GMC)-VOP.
     *
     * @param layer the video object layer of the VOP
     * @param vop the VOP header with its sprite trajectory
     */
    public void setup(VideoObjectLayer layer, Vop vop) {
        s = 2 << layer.warpingAccuracy;
        sBits = 1 + layer.warpingAccuracy;
        points = layer.warpingPoints;
        rounding = vop.roundingType;
        long w = layer.width;
        long h = layer.height;
        var du = vop.du;
        var dv = vop.dv;
        long half = s / 2;
        i0 = half * du[0];
        j0 = half * dv[0];
        long i1 = half * (2 * w + du[1] + du[0]);
        long j1 = half * (dv[1] + dv[0]);
        long i2 = half * (du[2] + du[0]);
        long j2 = half * (2 * h + dv[2] + dv[0]);
        long r = 16 / s;
        var alpha = log2Ceil(w);
        var beta = log2Ceil(h);
        long wp = 1L << alpha;
        long hp = 1L << beta;
        var rho = 4 - sBits;
        // Virtual sprite points of 7.8.4 in 1/16 sample units, with i0 = j0 = 0 for rectangular VOPs.
        var i1v = 16 * wp + divRound((w - wp) * (r * i0) + wp * (r * i1 - 16 * w), w);
        var j1v = divRound((w - wp) * (r * j0) + wp * (r * j1), w);
        var i2v = divRound((h - hp) * (r * i0) + hp * (r * i2), h);
        var j2v = 16 * hp + divRound((h - hp) * (r * j0) + hp * (r * j2 - 16 * h), h);
        switch (points) {
            case 0 -> {
                fx0 = 0;
                fxi = s;
                fxj = 0;
                gy0 = 0;
                gyi = 0;
                gyj = s;
                shift = 0;
                cfx0 = 0;
                cfxi = s;
                cfxj = 0;
                cgy0 = 0;
                cgyi = 0;
                cgyj = s;
                cshift = -1;
            }
            case 1 -> {
                fx0 = i0;
                fxi = s;
                fxj = 0;
                gy0 = j0;
                gyi = 0;
                gyj = s;
                shift = 0;
                cfx0 = (i0 >> 1) | (i0 & 1);
                cfxi = s;
                cfxj = 0;
                cgy0 = (j0 >> 1) | (j0 & 1);
                cgyi = 0;
                cgyj = s;
                cshift = -1;
            }
            case 2 -> {
                var a = -r * i0 + i1v;
                var b = r * j0 - j1v;
                var c = -r * j0 + j1v;
                shift = alpha + rho;
                fx0 = i0 << shift;
                fxi = a;
                fxj = b;
                gy0 = j0 << shift;
                gyi = c;
                gyj = a;
                cshift = alpha + rho + 2;
                cfx0 = 2 * wp * r * i0 - 16 * wp;
                cfxi = a;
                cfxj = b;
                cgy0 = 2 * wp * r * j0 - 16 * wp;
                cgyi = c;
                cgyj = a;
            }
            default -> {
                var ai = -r * i0 + i1v;
                var bi = -r * i0 + i2v;
                var aj = -r * j0 + j1v;
                var bj = -r * j0 + j2v;
                // Divide numerator and denominator by the smaller of W' and H' (7.8.5), so the
                // division by W' H' r becomes a shift by max(alpha, beta) + rho.
                long scaleI;
                long scaleJ;
                int big;
                if (alpha >= beta) {
                    scaleI = 1;
                    scaleJ = 1L << (alpha - beta);
                    big = alpha;
                } else {
                    scaleI = 1L << (beta - alpha);
                    scaleJ = 1;
                    big = beta;
                }
                shift = big + rho;
                fx0 = i0 << shift;
                fxi = ai * scaleI;
                fxj = bi * scaleJ;
                gy0 = j0 << shift;
                gyi = aj * scaleI;
                gyj = bj * scaleJ;
                cshift = big + rho + 2;
                var big2 = 1L << big;
                cfx0 = 2 * big2 * r * i0 - 16 * big2;
                cfxi = ai * scaleI;
                cfxj = bi * scaleJ;
                cgy0 = 2 * big2 * r * j0 - 16 * big2;
                cgyi = aj * scaleI;
                cgyj = bj * scaleJ;
            }
        }
    }

    /**
     * Returns the horizontal warped position of a luminance sample, in {@code 1/s} samples.
     *
     * @param i the column
     * @param j the row
     * @return {@code F(i, j)}
     */
    long lumaX(int i, int j) {
        if (shift == 0) {
            return fx0 + fxi * i + fxj * j;
        }
        return (fx0 + fxi * i + fxj * j + (1L << (shift - 1))) >> shift;
    }

    /**
     * Returns the vertical warped position of a luminance sample, in {@code 1/s} samples.
     *
     * @param i the column
     * @param j the row
     * @return {@code G(i, j)}
     */
    long lumaY(int i, int j) {
        if (shift == 0) {
            return gy0 + gyi * i + gyj * j;
        }
        return (gy0 + gyi * i + gyj * j + (1L << (shift - 1))) >> shift;
    }

    /**
     * Returns the horizontal warped position of a chrominance sample, in {@code 1/s} samples.
     *
     * @param i the column
     * @param j the row
     * @return {@code Fc(i, j)}
     */
    long chromaX(int i, int j) {
        if (cshift < 0) {
            return cfx0 + cfxi * i + cfxj * j;
        }
        return (cfx0 + cfxi * (4L * i + 1) + cfxj * (4L * j + 1) + (1L << (cshift - 1))) >> cshift;
    }

    /**
     * Returns the vertical warped position of a chrominance sample, in {@code 1/s} samples.
     *
     * @param i the column
     * @param j the row
     * @return {@code Gc(i, j)}
     */
    long chromaY(int i, int j) {
        if (cshift < 0) {
            return cgy0 + cgyi * i + cgyj * j;
        }
        return (cgy0 + cgyi * (4L * i + 1) + cgyj * (4L * j + 1) + (1L << (cshift - 1))) >> cshift;
    }

    /**
     * Predicts the luminance of one macroblock by warping the reference.
     *
     * @param ref the reference luminance plane, edges replicated
     * @param refOrigin the index of the reference's top-left displayed sample
     * @param refStride the reference row stride
     * @param width the displayed reference width
     * @param height the displayed reference height
     * @param dst the destination plane
     * @param dstOffset the index of the macroblock's top-left sample
     * @param dstStride the destination row stride
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     */
    public void predictLuma(byte[] ref, int refOrigin, int refStride, int width, int height, byte[] dst,
                            int dstOffset, int dstStride, int x, int y) {
        warp(ref, refOrigin, refStride, width, height, dst, dstOffset, dstStride, x, y, 16, false);
    }

    /**
     * Predicts one chrominance block of a macroblock by warping the reference.
     *
     * @param ref the reference chrominance plane, edges replicated
     * @param refOrigin the index of the reference's top-left displayed sample
     * @param refStride the reference row stride
     * @param width the displayed reference chrominance width
     * @param height the displayed reference chrominance height
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param x the block's left column
     * @param y the block's top row
     */
    public void predictChroma(byte[] ref, int refOrigin, int refStride, int width, int height, byte[] dst,
                              int dstOffset, int dstStride, int x, int y) {
        warp(ref, refOrigin, refStride, width, height, dst, dstOffset, dstStride, x, y, 8, true);
    }

    /**
     * Warps a square block with the bilinear sample reconstruction of 7.8.6, reading samples
     * outside the reference from its edges.
     *
     * @param ref the reference plane
     * @param refOrigin the index of the reference's top-left displayed sample
     * @param refStride the reference row stride
     * @param width the displayed reference width
     * @param height the displayed reference height
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size
     * @param chroma whether the block is a chrominance block
     */
    private void warp(byte[] ref, int refOrigin, int refStride, int width, int height, byte[] dst, int dstOffset,
                      int dstStride, int x, int y, int size, boolean chroma) {
        var mask = s - 1;
        var square = s * s;
        var bias = square / 2 - rounding;
        var squareBits = 2 * sBits;
        for (var row = 0; row < size; row++) {
            var d = dstOffset + row * dstStride;
            for (var col = 0; col < size; col++) {
                var px = chroma ? chromaX(x + col, y + row) : lumaX(x + col, y + row);
                var py = chroma ? chromaY(x + col, y + row) : lumaY(x + col, y + row);
                var ix = px >> sBits;
                var iy = py >> sBits;
                var ri = (int) (px & mask);
                var rj = (int) (py & mask);
                var x0 = clamp(ix, width);
                var x1 = clamp(ix + 1, width);
                var y0 = clamp(iy, height);
                var y1 = clamp(iy + 1, height);
                var r0 = refOrigin + y0 * refStride;
                var r1 = refOrigin + y1 * refStride;
                var p00 = ref[r0 + x0] & 0xFF;
                var p01 = ref[r0 + x1] & 0xFF;
                var p10 = ref[r1 + x0] & 0xFF;
                var p11 = ref[r1 + x1] & 0xFF;
                var value = ((s - rj) * ((s - ri) * p00 + ri * p01) + rj * ((s - ri) * p10 + ri * p11) + bias)
                        >> squareBits;
                dst[d + col] = (byte) value;
            }
        }
    }

    /**
     * Clamps a coordinate to a dimension.
     *
     * @param value the coordinate
     * @param size the dimension
     * @return the coordinate within {@code 0..size - 1}
     */
    private static int clamp(long value, int size) {
        return (int) (value < 0 ? 0 : value >= size ? size - 1 : value);
    }

    /**
     * Returns the averaged motion vector of the macroblock at a position (7.8.7.3), in half
     * samples or quarter samples, clipped to the range of the forward f_code.
     *
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     * @param quarter whether the layer uses quarter sample vectors
     * @param fcode the forward f_code
     * @param vertical whether the vertical component is returned
     * @return the averaged vector component
     */
    public int averageVector(int x, int y, boolean quarter, int fcode, boolean vertical) {
        long sum = 0;
        for (var row = 0; row < 16; row++) {
            for (var col = 0; col < 16; col++) {
                if (vertical) {
                    sum += lumaY(x + col, y + row) - (long) s * (y + row);
                } else {
                    sum += lumaX(x + col, y + row) - (long) s * (x + col);
                }
            }
        }
        var unit = quarter ? 4 : 2;
        var value = (int) divRound(sum * unit, 256L * s);
        var high = (32 << (fcode - 1)) - 1;
        var low = -(32 << (fcode - 1));
        return value < low ? low : value > high ? high : value;
    }

    /**
     * Divides with rounding to nearest, halves away from zero (the {@code //} operator).
     *
     * @param numerator the dividend
     * @param denominator the denominator, positive
     * @return the quotient
     */
    static long divRound(long numerator, long denominator) {
        return numerator >= 0 ? (numerator + denominator / 2) / denominator
                : -((-numerator + denominator / 2) / denominator);
    }

    /**
     * Returns the smallest {@code a > 0} with {@code 2^a >= value}.
     *
     * @param value the value, positive
     * @return the exponent
     */
    private static int log2Ceil(long value) {
        var a = 1;
        while ((1L << a) < value) {
            a++;
        }
        return a;
    }
}
