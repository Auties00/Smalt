/* Ports the studio inverse DCT of the MoMuSys MPEG-4 Visual reference software of ISO/IEC 14496-5:2001/Amd.1:2002
 * (StudioProfile-MoMuSys, vm_common/src/text_util.c: BlockIDCT_DCText_Studio). Copyright 1996-2002 the ACTS-MoMuSys
 * partners and Sony Corporation; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for
 * Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.studio;

/**
 * The separable 8x8 inverse DCT of the Studio reference decoder, rows first, in double precision.
 *
 * <p>Its input holds the dequantised coefficients in raster order, scaled by the coefficient precision divisor; its
 * output samples are rounded to the nearest integer. Instances hold scratch state and are not thread-safe.
 */
final class StudioIdct {
    private static final double C0 = 0.7071068;
    private static final double C1 = 0.4903926;
    private static final double C2 = 0.4619398;
    private static final double C3 = 0.4157348;
    private static final double C4 = 0.3535534;
    private static final double C5 = 0.2777851;
    private static final double C6 = 0.1913417;
    private static final double C7 = 0.0975452;

    private final double[] block = new double[64];
    private final double[] tmp = new double[8];
    private final double[] tmp1 = new double[8];

    /**
     * Transforms one block.
     *
     * @param coefficients the coefficients in raster order
     * @param divisor the scale of the coefficients: 8 for the extended precision of Studio layers, 1 for layers
     * converted from ISO/IEC 13818-2
     * @param out the 64 rounded samples in raster order
     */
    void apply(int[] coefficients, double divisor, int[] out) {
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                tmp[j] = coefficients[i * 8 + j] / divisor;
            }
            butterfly();
            for (var j = 0; j < 4; j++) {
                block[i * 8 + j] = tmp[j] + tmp[7 - j];
                block[i * 8 + 7 - j] = tmp[j] - tmp[7 - j];
            }
        }
        for (var i = 0; i < 8; i++) {
            for (var j = 0; j < 8; j++) {
                tmp[j] = block[j * 8 + i];
            }
            butterfly();
            for (var j = 0; j < 4; j++) {
                out[j * 8 + i] = (int) Math.floor(tmp[j] + tmp[7 - j] + 0.5);
                out[(7 - j) * 8 + i] = (int) Math.floor(tmp[j] - tmp[7 - j] + 0.5);
            }
        }
    }

    /**
     * Runs the one-dimensional transform on {@link #tmp}, leaving the even and odd halves to combine.
     */
    private void butterfly() {
        var e = tmp[1] * C7 - tmp[7] * C1;
        var h = tmp[7] * C7 + tmp[1] * C1;
        var f = tmp[5] * C3 - tmp[3] * C5;
        var g = tmp[3] * C3 + tmp[5] * C5;
        tmp1[0] = (tmp[0] + tmp[4]) * C4;
        tmp1[1] = (tmp[0] - tmp[4]) * C4;
        tmp1[2] = tmp[2] * C6 - tmp[6] * C2;
        tmp1[3] = tmp[6] * C6 + tmp[2] * C2;
        tmp[4] = e + f;
        tmp1[5] = e - f;
        tmp1[6] = h - g;
        tmp[7] = h + g;
        tmp[5] = (tmp1[6] - tmp1[5]) * C0;
        tmp[6] = (tmp1[6] + tmp1[5]) * C0;
        tmp[0] = tmp1[0] + tmp1[3];
        tmp[1] = tmp1[1] + tmp1[2];
        tmp[2] = tmp1[1] - tmp1[2];
        tmp[3] = tmp1[0] - tmp1[3];
    }
}
