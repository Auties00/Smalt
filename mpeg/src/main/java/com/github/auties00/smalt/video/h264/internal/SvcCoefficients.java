package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Implements the transform coefficient processes of the scalable decoding process: the scaling
 * and refinement of transform coefficients (ITU-T H.264 F.8.5.1), the scaling of transform
 * coefficient levels prior to refinement (F.8.5.2) and the construction of residual samples from
 * scaled transform coefficients (F.8.5.3.1 and F.8.5.3.2), for 4:2:0 macroblocks of 8-bit
 * samples.
 *
 * <p>Coefficients use the {@code tCoeffIdx} layout of {@link SvcLayer}.
 */
final class SvcCoefficients {
    /**
     * The scale values {@code cS} of ITU-T H.264 Table F-6, indexed by
     * {@code ( refQP - cQP + 54 ) % 6}.
     */
    private static final int[] LEVEL_SCALE = {8, 9, 10, 11, 13, 14};

    /**
     * The working 4x4 block.
     */
    private final int[] block = new int[16];

    /**
     * The working 8x8 block.
     */
    private final int[] block8 = new int[64];

    /**
     * The working DC values of an Intra_16x16 macroblock.
     */
    private final int[] dc = new int[16];

    /**
     * Constructs the processes with their working buffers.
     */
    SvcCoefficients() {
    }

    /**
     * Returns the chroma quantization parameter of a luma quantization parameter (ITU-T H.264
     * 8.5.8 for 8-bit samples).
     *
     * @param qpY the luma quantization parameter
     * @param offset the chroma quantization parameter offset
     * @return the chroma quantization parameter
     */
    static int chromaQp(int qpY, int offset) {
        return H264Tables.CHROMA_QP[Math.clamp(qpY + offset, 0, 51)];
    }

    /**
     * Scales and refines the transform coefficients of a macroblock (ITU-T H.264 F.8.5.1).
     *
     * @param layer the layer arrays holding {@code sTCoeff} and {@code tCoeffLevel}
     * @param mbAddr the macroblock address
     * @param refinement the {@code refinementFlag}: whether the coefficients refine the existing
     * ones rather than replace them
     * @param type the {@code mbType}
     * @param cTrafo the {@code cTrafo}
     * @param syntax the parser holding the transform coefficient levels of the macroblock, all of
     * which are taken as 0 when {@code null}
     * @param baseMode the {@code base_mode_flag} to interpret the levels with
     * @param tcoeffPrediction the {@code tcoeff_level_prediction_flag} to refine with
     * @param pps the picture parameter set holding the scaling lists
     */
    void refine(SvcLayer layer, int mbAddr, boolean refinement, int type, int cTrafo, SvcSliceParser syntax,
                boolean baseMode, boolean tcoeffPrediction, H264Pps pps) {
        var base = mbAddr * SvcLayer.COEFFS;
        var tc = layer.tCoeff;
        var sc = layer.sTCoeff;
        if (!refinement) {
            Arrays.fill(tc, base, base + SvcLayer.COEFFS, 0);
            Arrays.fill(sc, base, base + SvcLayer.COEFFS, 0);
        }
        var intra = SvcMb.intra(type);
        var qp = layer.qpY[mbAddr];
        var lists = pps.scaling4x4;
        switch (cTrafo) {
            case SvcLayer.T_PCM -> {
                if (!baseMode && syntax != null) {
                    for (var k = 0; k < 256; k++) {
                        tc[base + k] = 0;
                        sc[base + k] = syntax.pcm[k];
                    }
                }
            }
            case SvcLayer.T_4X4 -> {
                Arrays.fill(tcoeffPrediction ? sc : tc, base, base + 256, 0);
                var scale = lists[intra ? 0 : 3];
                for (var blk = 0; blk < 16; blk++) {
                    var at = base + blk * 16;
                    for (var k = 0; k < 16; k++) {
                        var raster = H264Tables.ZIGZAG_4X4[k];
                        if (syntax != null) {
                            tc[at + raster] += syntax.lumaLevels[blk * 16 + k];
                        }
                    }
                    for (var r = 0; r < 16; r++) {
                        sc[at + r] += scale4x4(tc[at + r], qp, scale, r);
                    }
                }
            }
            case SvcLayer.T_8X8 -> {
                Arrays.fill(tcoeffPrediction ? sc : tc, base, base + 256, 0);
                var scale = pps.scaling8x8[intra ? 0 : 1];
                for (var b8 = 0; b8 < 4; b8++) {
                    var at = base + b8 * 64;
                    if (syntax != null) {
                        for (var k = 0; k < 64; k++) {
                            tc[at + H264Tables.ZIGZAG_8X8[k]] += syntax.lumaLevels[b8 * 64 + k];
                        }
                    }
                    for (var r = 0; r < 64; r++) {
                        sc[at + r] += scale8x8(tc[at + r], qp, scale, r);
                    }
                }
            }
            case SvcLayer.T_16X16 -> refineIntra16x16(tc, sc, base, qp, lists[0], syntax, baseMode);
            default -> {
            }
        }
        for (var c = 0; c < 2; c++) {
            var qpC = c == 0 ? layer.qpCb[mbAddr] : layer.qpCr[mbAddr];
            var at = base + 256 + c * 64;
            if (cTrafo == SvcLayer.T_PCM) {
                if (!baseMode && syntax != null) {
                    for (var k = 0; k < 64; k++) {
                        tc[at + k] = 0;
                        sc[at + k] = syntax.pcm[256 + c * 64 + k];
                    }
                }
                continue;
            }
            Arrays.fill(tcoeffPrediction ? sc : tc, at, at + 64, 0);
            var scale = lists[(intra ? 1 : 4) + c];
            var dcScale = scale[0] * H264Tables.NORM_ADJUST_4X4[(qpC % 6) * 16] << (qpC / 6);
            for (var i = 0; i < 4; i++) {
                var index = at + 16 * i;
                if (syntax != null) {
                    tc[index] += syntax.chromaDcLevels[c * 4 + i];
                }
                sc[index] += tc[index] * dcScale;
            }
            for (var blk = 0; blk < 4; blk++) {
                var blockAt = at + blk * 16;
                if (syntax != null) {
                    for (var k = 1; k < 16; k++) {
                        tc[blockAt + H264Tables.ZIGZAG_4X4[k]] += syntax.chromaAcLevels[(c * 4 + blk) * 16 + k - 1];
                    }
                }
                for (var r = 1; r < 16; r++) {
                    sc[blockAt + r] += scale4x4(tc[blockAt + r], qpC, scale, r);
                }
            }
        }
    }

    /**
     * Refines the luma coefficients of an Intra_16x16 macroblock (ITU-T H.264 F.8.5.1.1.4).
     *
     * @param tc the {@code tCoeffLevel} array
     * @param sc the {@code sTCoeff} array
     * @param base the index of the macroblock's coefficients
     * @param qp the luma quantization parameter
     * @param scale the intra luma 4x4 scaling list
     * @param syntax the parser holding the levels, or {@code null} for levels equal to 0
     * @param baseMode whether the levels are coded as 4x4 blocks, as for {@code base_mode_flag}
     * 1
     */
    private void refineIntra16x16(int[] tc, int[] sc, int base, int qp, int[] scale, SvcSliceParser syntax,
                                  boolean baseMode) {
        for (var blk = 0; blk < 16; blk++) {
            var x = H264Tables.BLOCK_X[blk];
            var y = H264Tables.BLOCK_Y[blk];
            var level = 0;
            if (syntax != null) {
                level = baseMode ? syntax.lumaLevels[blk * 16] : syntax.dcLevels[zigzagIndex(y * 4 + x)];
            }
            tc[base + blk * 16] += level;
            dc[y * 4 + x] = tc[base + blk * 16];
        }
        hadamard4x4(dc);
        var levelScale = scale[0] * H264Tables.NORM_ADJUST_4X4[(qp % 6) * 16];
        for (var blk = 0; blk < 16; blk++) {
            var x = H264Tables.BLOCK_X[blk];
            var y = H264Tables.BLOCK_Y[blk];
            var f = dc[y * 4 + x];
            sc[base + blk * 16] = qp >= 36 ? (f * levelScale) << (qp / 6 - 6)
                    : (f * levelScale + (1 << (5 - qp / 6))) >> (6 - qp / 6);
        }
        for (var blk = 0; blk < 16; blk++) {
            var at = base + blk * 16;
            if (syntax != null) {
                for (var k = 1; k < 16; k++) {
                    var level = baseMode ? syntax.lumaLevels[blk * 16 + k] : syntax.acLevels[blk * 16 + k - 1];
                    tc[at + H264Tables.ZIGZAG_4X4[k]] += level;
                }
            }
            for (var r = 1; r < 16; r++) {
                sc[at + r] = scale4x4(tc[at + r], qp, scale, r);
            }
        }
    }

    /**
     * Returns the zig-zag scan index of a raster position of a 4x4 block.
     *
     * @param raster the raster position
     * @return the scan index
     */
    private static int zigzagIndex(int raster) {
        for (var k = 0; k < 16; k++) {
            if (H264Tables.ZIGZAG_4X4[k] == raster) {
                return k;
            }
        }
        throw new IllegalArgumentException();
    }

    /**
     * Applies the 4x4 Hadamard transform of the Intra_16x16 DC coefficients in place (ITU-T H.264
     * equation 8-320).
     *
     * @param c the coefficients in raster order
     */
    private static void hadamard4x4(int[] c) {
        for (var i = 0; i < 4; i++) {
            var a = c[i * 4];
            var b = c[i * 4 + 1];
            var d = c[i * 4 + 2];
            var e = c[i * 4 + 3];
            c[i * 4] = a + b + d + e;
            c[i * 4 + 1] = a + b - d - e;
            c[i * 4 + 2] = a - b - d + e;
            c[i * 4 + 3] = a - b + d - e;
        }
        for (var j = 0; j < 4; j++) {
            var a = c[j];
            var b = c[4 + j];
            var d = c[8 + j];
            var e = c[12 + j];
            c[j] = a + b + d + e;
            c[4 + j] = a + b - d - e;
            c[8 + j] = a - b - d + e;
            c[12 + j] = a - b + d - e;
        }
    }

    /**
     * Scales one coefficient of a 4x4 block (ITU-T H.264 8.5.12.1).
     *
     * @param c the coefficient level
     * @param qp the quantization parameter
     * @param scale the scaling list in raster order
     * @param raster the raster position
     * @return the scaled coefficient
     */
    private static int scale4x4(int c, int qp, int[] scale, int raster) {
        if (c == 0) {
            return 0;
        }
        var levelScale = scale[raster] * H264Tables.NORM_ADJUST_4X4[(qp % 6) * 16 + raster];
        return qp >= 24 ? (c * levelScale) << (qp / 6 - 4) : (c * levelScale + (1 << (3 - qp / 6))) >> (4 - qp / 6);
    }

    /**
     * Scales one coefficient of an 8x8 block (ITU-T H.264 8.5.13.1).
     *
     * @param c the coefficient level
     * @param qp the quantization parameter
     * @param scale the scaling list in raster order
     * @param raster the raster position
     * @return the scaled coefficient
     */
    private static int scale8x8(int c, int qp, int[] scale, int raster) {
        if (c == 0) {
            return 0;
        }
        var levelScale = scale[raster] * H264Tables.NORM_ADJUST_8X8[(qp % 6) * 64 + raster];
        return qp >= 36 ? (c * levelScale) << (qp / 6 - 6) : (c * levelScale + (1 << (5 - qp / 6))) >> (6 - qp / 6);
    }

    /**
     * Scales the transform coefficient levels of a macroblock to a new quantization parameter
     * before their refinement (ITU-T H.264 F.8.5.2).
     *
     * @param layer the layer arrays
     * @param mbAddr the macroblock address
     * @param cQpY the luma quantization parameter of the macroblock
     * @param cQpCb the Cb quantization parameter of the macroblock
     * @param cQpCr the Cr quantization parameter of the macroblock
     * @param refQpY the luma quantization parameter of the reference layer macroblock
     * @param refQpCb the Cb quantization parameter of the reference layer macroblock
     * @param refQpCr the Cr quantization parameter of the reference layer macroblock
     */
    static void scaleLevels(SvcLayer layer, int mbAddr, int cQpY, int cQpCb, int cQpCr, int refQpY, int refQpCb,
                            int refQpCr) {
        var base = mbAddr * SvcLayer.COEFFS;
        scaleLevels(layer.tCoeff, base, 256, cQpY, refQpY);
        scaleLevels(layer.tCoeff, base + 256, 64, cQpCb, refQpCb);
        scaleLevels(layer.tCoeff, base + 320, 64, cQpCr, refQpCr);
    }

    /**
     * Scales a run of transform coefficient levels (ITU-T H.264 equation F-172).
     *
     * @param levels the coefficient levels
     * @param offset the first level
     * @param count the number of levels
     * @param cQp the quantization parameter of the current layer
     * @param refQp the quantization parameter of the reference layer
     */
    private static void scaleLevels(int[] levels, int offset, int count, int cQp, int refQp) {
        var scale = LEVEL_SCALE[(refQp - cQp + 54) % 6];
        var shift = (refQp - cQp + 54) / 6;
        for (var i = offset; i < offset + count; i++) {
            levels[i] = (int) (((long) (scale * levels[i]) << shift) >> 12);
        }
    }

    /**
     * Constructs the residual samples of a macroblock from its scaled coefficients (ITU-T H.264
     * F.8.5.3.1 and F.8.5.3.2).
     *
     * @param sc the {@code sTCoeff} array
     * @param base the index of the macroblock's coefficients
     * @param cTrafo the transform type
     * @param luma receives the 16x16 luma residual in raster order
     * @param cb receives the 8x8 Cb residual in raster order
     * @param cr receives the 8x8 Cr residual in raster order
     */
    void residual(int[] sc, int base, int cTrafo, int[] luma, int[] cb, int[] cr) {
        switch (cTrafo) {
            case SvcLayer.T_PCM -> {
                for (var k = 0; k < 256; k++) {
                    luma[k] = sc[base + k];
                }
                for (var k = 0; k < 64; k++) {
                    cb[k] = sc[base + 256 + k];
                    cr[k] = sc[base + 320 + k];
                }
                return;
            }
            case SvcLayer.T_8X8 -> {
                for (var b8 = 0; b8 < 4; b8++) {
                    System.arraycopy(sc, base + b8 * 64, block8, 0, 64);
                    inverse8x8(block8);
                    var origin = (b8 >> 1) * 128 + (b8 & 1) * 8;
                    for (var y = 0; y < 8; y++) {
                        System.arraycopy(block8, y * 8, luma, origin + y * 16, 8);
                    }
                }
            }
            case SvcLayer.T_4X4, SvcLayer.T_16X16 -> {
                for (var blk = 0; blk < 16; blk++) {
                    System.arraycopy(sc, base + blk * 16, block, 0, 16);
                    inverse4x4(block);
                    var origin = H264Tables.BLOCK_Y[blk] * 64 + H264Tables.BLOCK_X[blk] * 4;
                    for (var y = 0; y < 4; y++) {
                        System.arraycopy(block, y * 4, luma, origin + y * 16, 4);
                    }
                }
            }
            default -> Arrays.fill(luma, 0, 256, 0);
        }
        chromaResidual(sc, base + 256, cb);
        chromaResidual(sc, base + 320, cr);
    }

    /**
     * Constructs the residual samples of one 4:2:0 chroma component (ITU-T H.264 F.8.5.3.2.2).
     *
     * @param sc the {@code sTCoeff} array
     * @param base the index of the component's coefficients
     * @param out receives the 8x8 residual in raster order
     */
    private void chromaResidual(int[] sc, int base, int[] out) {
        var c0 = sc[base];
        var c1 = sc[base + 16];
        var c2 = sc[base + 32];
        var c3 = sc[base + 48];
        var f0 = c0 + c1 + c2 + c3;
        var f1 = c0 - c1 + c2 - c3;
        var f2 = c0 + c1 - c2 - c3;
        var f3 = c0 - c1 - c2 + c3;
        for (var blk = 0; blk < 4; blk++) {
            System.arraycopy(sc, base + blk * 16, block, 0, 16);
            block[0] = (blk == 0 ? f0 : blk == 1 ? f1 : blk == 2 ? f2 : f3) >> 5;
            inverse4x4(block);
            var origin = (blk >> 1) * 32 + (blk & 1) * 4;
            for (var y = 0; y < 4; y++) {
                System.arraycopy(block, y * 4, out, origin + y * 8, 4);
            }
        }
    }

    /**
     * Applies the inverse 4x4 transform with its final rounding in place (ITU-T H.264 8.5.12.2).
     *
     * @param d the scaled coefficients in raster order; receive the residual samples
     */
    static void inverse4x4(int[] d) {
        for (var i = 0; i < 4; i++) {
            var o = i * 4;
            var e = d[o] + d[o + 2];
            var f = d[o] - d[o + 2];
            var g = (d[o + 1] >> 1) - d[o + 3];
            var h = d[o + 1] + (d[o + 3] >> 1);
            d[o] = e + h;
            d[o + 1] = f + g;
            d[o + 2] = f - g;
            d[o + 3] = e - h;
        }
        for (var j = 0; j < 4; j++) {
            var e = d[j] + d[8 + j];
            var f = d[j] - d[8 + j];
            var g = (d[4 + j] >> 1) - d[12 + j];
            var h = d[4 + j] + (d[12 + j] >> 1);
            d[j] = (e + h + 32) >> 6;
            d[4 + j] = (f + g + 32) >> 6;
            d[8 + j] = (f - g + 32) >> 6;
            d[12 + j] = (e - h + 32) >> 6;
        }
    }

    /**
     * Applies the inverse 8x8 transform with its final rounding in place (ITU-T H.264 8.5.13.2).
     *
     * @param d the scaled coefficients in raster order; receive the residual samples
     */
    static void inverse8x8(int[] d) {
        for (var i = 0; i < 8; i++) {
            transform8(d, i * 8, 1);
        }
        for (var j = 0; j < 8; j++) {
            transform8(d, j, 8);
        }
        for (var k = 0; k < 64; k++) {
            d[k] = (d[k] + 32) >> 6;
        }
    }

    /**
     * Applies the one-dimensional inverse 8-point transform of ITU-T H.264 8.5.13.2 in place.
     *
     * @param d the values
     * @param offset the first value
     * @param step the distance between successive values
     */
    private static void transform8(int[] d, int offset, int step) {
        var d0 = d[offset];
        var d1 = d[offset + step];
        var d2 = d[offset + 2 * step];
        var d3 = d[offset + 3 * step];
        var d4 = d[offset + 4 * step];
        var d5 = d[offset + 5 * step];
        var d6 = d[offset + 6 * step];
        var d7 = d[offset + 7 * step];
        var e0 = d0 + d4;
        var e1 = -d3 + d5 - d7 - (d7 >> 1);
        var e2 = d0 - d4;
        var e3 = d1 + d7 - d3 - (d3 >> 1);
        var e4 = (d2 >> 1) - d6;
        var e5 = -d1 + d7 + d5 + (d5 >> 1);
        var e6 = d2 + (d6 >> 1);
        var e7 = d3 + d5 + d1 + (d1 >> 1);
        var f0 = e0 + e6;
        var f1 = e1 + (e7 >> 2);
        var f2 = e2 + e4;
        var f3 = e3 + (e5 >> 2);
        var f4 = e2 - e4;
        var f5 = (e3 >> 2) - e5;
        var f6 = e0 - e6;
        var f7 = e7 - (e1 >> 2);
        d[offset] = f0 + f7;
        d[offset + step] = f2 + f5;
        d[offset + 2 * step] = f4 + f3;
        d[offset + 3 * step] = f6 + f1;
        d[offset + 4 * step] = f6 - f1;
        d[offset + 5 * step] = f4 - f3;
        d[offset + 6 * step] = f2 - f5;
        d[offset + 7 * step] = f0 - f7;
    }
}
