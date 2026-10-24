/* Ports libvpx 1.15.1 (vp8/encoder/vp8_quantize.c: vp8_regular_quantize_b_c, invert_quant, vp8cx_init_quantizer,
   the zero-bin extras of vp8cx_mb_init_quantizer). Copyright (c) 2010 The WebM project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds libvpx's regular quantizer of one block type of one segment and quantizes blocks with it.
 *
 * <p>Unlike the fast quantizer of {@link QuantMatrix}, a coefficient is zero unless its magnitude
 * reaches a zero bin of about 0.65 or 0.63 of a step, widened after each run of zero levels and
 * by a per-macroblock extra, and the quotient is computed exactly through a multiply and a
 * shift, libvpx's {@code improved_quant}.
 */
final class RegularQuantizer {
    /**
     * The widening of the zero bin after each length of a run of zero levels, in 1/128 of the
     * AC step, libvpx's {@code zbin_boost}.
     */
    private static final int[] ZBIN_BOOST = {0, 0, 8, 10, 12, 14, 16, 20, 24, 28, 32, 36, 40, 44, 44, 44};

    /**
     * The rounding offset, in 1/128 of a step, libvpx's {@code qrounding_factors}.
     */
    private static final int ROUNDING = 48;

    /**
     * The quantizer index from which the zero bin narrows from 84 to 80 in 1/128 of a step,
     * libvpx's {@code qzbin_factors}.
     */
    private static final int NARROW_ZBIN_QINDEX = 48;

    /**
     * The quantizer steps, in raster order.
     */
    final int[] q = new int[16];

    /**
     * The zero bins, in raster order.
     */
    private final int[] zbin = new int[16];

    /**
     * The rounding offsets, in raster order.
     */
    private final int[] round = new int[16];

    /**
     * The fixed-point reciprocals of the steps, minus one in 16-bit fixed point.
     */
    private final int[] quant = new int[16];

    /**
     * The final multipliers of the reciprocals.
     */
    private final int[] shift = new int[16];

    /**
     * The widening of the zero bin after each run length of zero levels.
     */
    private final int[] zrunBoost = new int[16];

    /**
     * Creates a quantizer of zeros.
     */
    RegularQuantizer() {
    }

    /**
     * Sets the quantizer of a pair of steps, libvpx's {@code vp8cx_init_quantizer} with
     * {@code improved_quant}.
     *
     * @param dc the DC step
     * @param ac the AC step
     * @param qindex the quantizer index of the segment
     */
    void set(int dc, int ac, int qindex) {
        var zbinFactor = qindex < NARROW_ZBIN_QINDEX ? 84 : 80;
        for (var i = 0; i < 16; i++) {
            var d = i == 0 ? dc : ac;
            q[i] = d;
            var l = 31 - Integer.numberOfLeadingZeros(d);
            var m = 1 + (1 << (16 + l)) / d;
            quant[i] = (short) (m - (1 << 16));
            shift[i] = 1 << (16 - l);
            zbin[i] = (zbinFactor * d + 64) >> 7;
            round[i] = (ROUNDING * d) >> 7;
            zrunBoost[i] = (d * ZBIN_BOOST[i]) >> 7;
        }
    }

    /**
     * Returns the zero-bin extra of a macroblock, libvpx's {@code ZBIN_EXTRA_Y} and
     * {@code ZBIN_EXTRA_UV}, or {@code ZBIN_EXTRA_Y2} when the over-quantization is halved.
     *
     * @param boost the zero-bin over-quantization (halved for the Y2 block) plus the mode boost,
     *              in 1/128 of the AC step
     * @return the extra
     */
    int zbinExtra(int boost) {
        return (q[1] * boost) >> 7;
    }

    /**
     * Quantizes one block of coefficients, libvpx's {@code vp8_regular_quantize_b_c}: replaces
     * each coefficient with its dequantized value and stores the levels in zigzag order.
     *
     * <p>The positions before {@code first} are quantized for the zero-run widening of the zero
     * bin, as libvpx quantizes the DC of a luma block whose DC the Y2 block codes, but their
     * levels and coefficients are set to zero.
     *
     * @param coef the coefficients
     * @param off the index of raster position 0
     * @param stride the distance between consecutive raster positions
     * @param first the first coded zigzag position
     * @param zbinExtra the zero-bin extra of the macroblock, from {@link #zbinExtra}
     * @param levels receives the levels
     * @param levelsOff the index of the level of zigzag position 0
     * @param levelsStride the distance between consecutive zigzag positions
     * @return one past the last nonzero coded position, {@code 0} without one
     */
    int quantize(int[] coef, int off, int stride, int first, int zbinExtra, short[] levels, int levelsOff,
                 int levelsStride) {
        var eob = 0;
        var run = 0;
        for (var n = 0; n < 16; n++) {
            var rc = RtDsp.ZIGZAG[n];
            var at = off + rc * stride;
            var level = level(coef[at], rc, zbinExtra + zrunBoost[run]);
            run++;
            if (level != 0) {
                run = 0;
            }
            if (n < first) {
                level = 0;
            }
            coef[at] = level * q[rc];
            levels[levelsOff + n * levelsStride] = (short) level;
            if (level != 0) {
                eob = n + 1;
            }
        }
        return eob;
    }

    /**
     * Quantizes one block of 16-bit coefficients like {@link #quantize(int[], int, int, int, int,
     * short[], int, int)}.
     *
     * @param coef the coefficients
     * @param off the index of raster position 0
     * @param stride the distance between consecutive raster positions
     * @param first the first coded zigzag position
     * @param zbinExtra the zero-bin extra of the macroblock
     * @param levels receives the levels
     * @param levelsOff the index of the level of zigzag position 0
     * @param levelsStride the distance between consecutive zigzag positions
     * @return one past the last nonzero coded position, {@code 0} without one
     */
    int quantize(short[] coef, int off, int stride, int first, int zbinExtra, short[] levels, int levelsOff,
                 int levelsStride) {
        var eob = 0;
        var run = 0;
        for (var n = 0; n < 16; n++) {
            var rc = RtDsp.ZIGZAG[n];
            var at = off + rc * stride;
            var level = level(coef[at], rc, zbinExtra + zrunBoost[run]);
            run++;
            if (level != 0) {
                run = 0;
            }
            if (n < first) {
                level = 0;
            }
            coef[at] = (short) (level * q[rc]);
            levels[levelsOff + n * levelsStride] = (short) level;
            if (level != 0) {
                eob = n + 1;
            }
        }
        return eob;
    }

    /**
     * Returns the level of one coefficient.
     *
     * @param z the coefficient
     * @param rc its raster position
     * @param widening the widening of its zero bin
     * @return the signed level, at most {@link RtDsp#MAX_LEVEL} in magnitude
     */
    private int level(int z, int rc, int widening) {
        var x = Math.abs(z);
        if (x < zbin[rc] + widening) {
            return 0;
        }
        x += round[rc];
        var y = Math.min(((((x * quant[rc]) >> 16) + x) * shift[rc]) >> 16, RtDsp.MAX_LEVEL);
        return z < 0 ? -y : y;
    }
}
