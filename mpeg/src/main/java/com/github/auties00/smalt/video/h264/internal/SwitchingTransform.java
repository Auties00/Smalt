package com.github.auties00.smalt.video.h264.internal;

/**
 * Reconstructs the 4x4 blocks of the P macroblocks of SP slices and of the SI macroblocks of SI
 * slices, whose prediction is requantized in the transform domain before the residual levels are
 * added (ITU-T H.264 8.6).
 *
 * <p>Every method reads the prediction of a block from the picture, where the caller formed it,
 * and overwrites it with the reconstructed samples; the coefficient levels it reads are cleared.
 * Samples are 8 bits deep and chroma is 4:2:0, the only format SP and SI slices are defined for.
 * Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class SwitchingTransform {
    /**
     * The quantization scale {@code LevelScale2(m, i, j)} of 8-418 and 8-419, indexed
     * {@code [m][position class]} with the classes of {@link #CLASS}.
     */
    private static final int[][] LEVEL_SCALE2 = {
            {13107, 5243, 8066}, {11916, 4660, 7490}, {10082, 4194, 6554},
            {9362, 3647, 5825}, {8192, 3355, 5243}, {7282, 2893, 4559}
    };

    /**
     * The position class of each raster position of a 4x4 block: 0 when the row and column are
     * both even, 1 when both are odd, 2 otherwise.
     */
    private static final byte[] CLASS = {0, 2, 0, 2, 2, 1, 2, 1, 0, 2, 0, 2, 2, 1, 2, 1};

    /**
     * The factor {@code Aij} of 8-417 of each position class.
     */
    private static final int[] A = {16, 25, 20};

    /**
     * The forward transform of the prediction of each chroma 4x4 block of a component, four
     * blocks of sixteen coefficients in raster order.
     */
    private final int[] prediction = new int[64];

    /**
     * Constructs a transform with its own scratch storage.
     */
    SwitchingTransform() {
    }

    /**
     * Reconstructs one luma 4x4 block (ITU-T H.264 8.6.1.1 and 8.6.2.1).
     *
     * @param dsp the kernels whose inverse transform writes the block
     * @param plane the luma plane holding the block's prediction
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param levels the block's coefficient levels in raster order, cleared on return
     * @param at the index of the block's first level
     * @param scaleQp {@code LevelScale4x4(QPY % 6, i, j)} in raster order
     * @param qp the luma quantization parameter {@code QPY}, 0 to 51
     * @param scaleQs {@code LevelScale4x4(QSY % 6, i, j)} in raster order
     * @param qs the switching quantization parameter {@code QSY}, 0 to 51
     * @param switching whether the switching process of 8.6.2 applies rather than 8.6.1
     */
    void luma(H264Dsp dsp, byte[] plane, int offset, int stride, int[] levels, int at, int[] scaleQp, int qp,
              int[] scaleQs, int qs, boolean switching) {
        var p = prediction;
        forward(plane, offset, stride, p, 0);
        var qsDiv = qs / 6;
        var scale2 = LEVEL_SCALE2[qs % 6];
        for (var k = 0; k < 16; k++) {
            var cls = CLASS[k];
            var level = levels[at + k];
            long sum;
            if (switching) {
                sum = level + quantize(p[k], scale2[cls], 15 + qsDiv);
            } else {
                var combined = p[k] + ((((long) level * scaleQp[k] * A[cls]) << (qp / 6)) >> 10);
                sum = quantize(combined, scale2[cls], 15 + qsDiv);
            }
            levels[at + k] = scale(sum, scaleQs[k], qsDiv);
        }
        write(dsp, plane, offset, stride, levels, at);
    }

    /**
     * Reconstructs the four 4x4 blocks of one chroma component of a macroblock (ITU-T H.264
     * 8.6.1.2 and 8.6.2.2).
     *
     * @param dsp the kernels whose inverse transform writes the blocks
     * @param plane the chroma plane holding the component's prediction
     * @param base the index of the macroblock's top-left chroma sample
     * @param stride the row stride
     * @param levels the AC levels of the four blocks, sixteen per block in raster order with the
     * DC position unused, cleared on return
     * @param dcLevels the DC levels {@code ChromaDCLevel} in list order, cleared on return
     * @param dcAt the index of the component's first DC level
     * @param scaleQp {@code LevelScale4x4(QPC % 6, i, j)} in raster order
     * @param qp the chroma quantization parameter {@code QPC}, 0 to 39
     * @param scaleQs {@code LevelScale4x4(QSC % 6, i, j)} in raster order
     * @param qs the chroma switching quantization parameter {@code QSC}, 0 to 39
     * @param switching whether the switching process of 8.6.2 applies rather than 8.6.1
     */
    void chroma(H264Dsp dsp, byte[] plane, int base, int stride, int[] levels, int[] dcLevels, int dcAt,
                int[] scaleQp, int qp, int[] scaleQs, int qs, boolean switching) {
        var p = prediction;
        for (var blk = 0; blk < 4; blk++) {
            forward(plane, base + (blk >> 1) * 4 * stride + (blk & 1) * 4, stride, p, blk * 16);
        }
        var qsDiv = qs / 6;
        var scale2 = LEVEL_SCALE2[qs % 6];
        for (var blk = 0; blk < 4; blk++) {
            var at = blk * 16;
            for (var k = 1; k < 16; k++) {
                var cls = CLASS[k];
                var level = levels[at + k];
                long sum;
                if (switching) {
                    sum = level + quantize(p[at + k], scale2[cls], 15 + qsDiv);
                } else {
                    var combined = p[at + k] + ((((long) level * scaleQp[k] * A[cls]) << (qp / 6)) >> 10);
                    sum = quantize(combined, scale2[cls], 15 + qsDiv);
                }
                levels[at + k] = scale(sum, scaleQs[k], qsDiv);
            }
        }
        var p0 = p[0];
        var p1 = p[16];
        var p2 = p[32];
        var p3 = p[48];
        // the 2x2 transform of the DC coefficients of blocks [[0, 1], [2, 3]] (8-427); the
        // coefficient at row r, column c pairs with DC level 2 * r + c, as in 8.5.11
        var d0 = p0 + p1 + p2 + p3;
        var d1 = p0 - p1 + p2 - p3;
        var d2 = p0 + p1 - p2 - p3;
        var d3 = p0 - p1 - p2 + p3;
        var q0 = dc(d0, dcLevels[dcAt], scaleQp[0], qp, qsDiv, scale2[0], switching);
        var q1 = dc(d1, dcLevels[dcAt + 1], scaleQp[0], qp, qsDiv, scale2[0], switching);
        var q2 = dc(d2, dcLevels[dcAt + 2], scaleQp[0], qp, qsDiv, scale2[0], switching);
        var q3 = dc(d3, dcLevels[dcAt + 3], scaleQp[0], qp, qsDiv, scale2[0], switching);
        dcLevels[dcAt] = 0;
        dcLevels[dcAt + 1] = 0;
        dcLevels[dcAt + 2] = 0;
        dcLevels[dcAt + 3] = 0;
        var dcScale = scaleQs[0];
        levels[0] = (int) ((((q0 + q1 + q2 + q3) * dcScale) << qsDiv) >> 5);
        levels[16] = (int) ((((q0 - q1 + q2 - q3) * dcScale) << qsDiv) >> 5);
        levels[32] = (int) ((((q0 + q1 - q2 - q3) * dcScale) << qsDiv) >> 5);
        levels[48] = (int) ((((q0 - q1 - q2 + q3) * dcScale) << qsDiv) >> 5);
        for (var blk = 0; blk < 4; blk++) {
            write(dsp, plane, base + (blk >> 1) * 4 * stride + (blk & 1) * 4, stride, levels, blk * 16);
        }
    }

    /**
     * Returns the quantized sum of the DC transform coefficient of the prediction and the DC
     * level (8-428 and 8-429, or 8-439 and 8-440).
     *
     * @param prediction the transformed prediction DC coefficient {@code dcp}
     * @param level the DC level
     * @param scaleQp {@code LevelScale4x4(QPC % 6, 0, 0)}
     * @param qp {@code QPC}
     * @param qsDiv {@code QSC / 6}
     * @param scale2 {@code LevelScale2(QSC % 6, 0, 0)}
     * @param switching whether the switching process applies
     * @return the quantized DC value {@code dcr}
     */
    private static long dc(int prediction, int level, int scaleQp, int qp, int qsDiv, int scale2, boolean switching) {
        if (switching) {
            return quantize(prediction, scale2, 16 + qsDiv) + level;
        }
        var combined = prediction + ((((long) level * scaleQp * A[0]) << (qp / 6)) >> 9);
        return quantize(combined, scale2, 16 + qsDiv);
    }

    /**
     * Quantizes a transform coefficient, rounding half away from zero:
     * {@code Sign(c) * ((Abs(c) * scale + (1 << (shift - 1))) >> shift)}.
     *
     * @param value the coefficient
     * @param scale the quantization scale {@code LevelScale2}
     * @param shift the right shift, {@code 15 + QS / 6} or {@code 16 + QS / 6} for chroma DC
     * @return the quantized level
     */
    private static long quantize(long value, int scale, int shift) {
        var magnitude = (Math.abs(value) * scale + (1L << (shift - 1))) >> shift;
        return value < 0 ? -magnitude : magnitude;
    }

    /**
     * Scales a level with {@code QS} as the scaling process for residual 4x4 blocks does (8-336
     * and 8-337).
     *
     * @param level the quantized coefficient level
     * @param levelScale {@code LevelScale4x4(QS % 6, i, j)}
     * @param qsDiv {@code QS / 6}
     * @return the scaled coefficient
     */
    private static int scale(long level, int levelScale, int qsDiv) {
        if (qsDiv >= 4) {
            return (int) ((level * levelScale) << (qsDiv - 4));
        }
        return (int) ((level * levelScale + (1L << (3 - qsDiv))) >> (4 - qsDiv));
    }

    /**
     * Applies the forward core transform of 8-415 to a 4x4 block of samples.
     *
     * @param plane the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param out receives the coefficients in raster order
     * @param at the index of the first coefficient in {@code out}
     */
    private static void forward(byte[] plane, int offset, int stride, int[] out, int at) {
        for (var y = 0; y < 4; y++) {
            var row = offset + y * stride;
            var s0 = plane[row] & 0xFF;
            var s1 = plane[row + 1] & 0xFF;
            var s2 = plane[row + 2] & 0xFF;
            var s3 = plane[row + 3] & 0xFF;
            var a = s0 + s3;
            var b = s1 + s2;
            var c = s1 - s2;
            var d = s0 - s3;
            var o = at + y * 4;
            out[o] = a + b;
            out[o + 1] = 2 * d + c;
            out[o + 2] = a - b;
            out[o + 3] = d - 2 * c;
        }
        for (var x = 0; x < 4; x++) {
            var t0 = out[at + x];
            var t1 = out[at + 4 + x];
            var t2 = out[at + 8 + x];
            var t3 = out[at + 12 + x];
            var a = t0 + t3;
            var b = t1 + t2;
            var c = t1 - t2;
            var d = t0 - t3;
            out[at + x] = a + b;
            out[at + 4 + x] = 2 * d + c;
            out[at + 8 + x] = a - b;
            out[at + 12 + x] = d - 2 * c;
        }
    }

    /**
     * Writes the inverse transform of scaled coefficients, clipped, over a 4x4 block: the
     * transform adds to a zeroed block, so the result is {@code Clip1(r)} (8-421).
     *
     * @param dsp the kernels
     * @param plane the samples receiving the block
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param coefficients the scaled coefficients in raster order, cleared on return
     * @param at the index of the first coefficient
     */
    private static void write(H264Dsp dsp, byte[] plane, int offset, int stride, int[] coefficients, int at) {
        for (var y = 0; y < 4; y++) {
            var row = offset + y * stride;
            plane[row] = 0;
            plane[row + 1] = 0;
            plane[row + 2] = 0;
            plane[row + 3] = 0;
        }
        dsp.idct4x4Add(coefficients, at, plane, offset, stride);
    }
}
