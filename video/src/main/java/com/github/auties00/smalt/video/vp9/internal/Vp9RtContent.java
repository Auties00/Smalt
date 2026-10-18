package com.github.auties00.smalt.video.vp9.internal;

/**
 * Detects skin colored blocks in the source of the real-time VP9 encoder, so that faces keep
 * their quality: skin superblocks split more finely and skin blocks join the cyclic refresh.
 *
 * <p>A block is skin when the color of its center sample lies within a Gaussian model of skin
 * chroma and its luma is neither too dark nor too bright; static blocks need a closer match.
 * The per-superblock map is then cleaned of isolated skin and isolated non-skin blocks.
 *
 * @implNote This implementation ports libvpx's {@code vpx_dsp/skin_detection.c} and
 *           {@code vp9/encoder/vp9_skin_detection.c}, BSD-3, including the source offset of
 *           {@code vp9_compute_skin_sb}, which does not advance past the skipped first block
 *           column.
 */
final class Vp9RtContent {
    /**
     * The skin chroma means of the five models, as Cb and Cr in Q6.
     */
    private static final int[][] SKIN_MEAN = {{7463, 9614}, {6400, 10240}, {7040, 10240}, {8320, 9280}, {6800, 9614}};

    /**
     * The inverse covariance of the skin model in Q16.
     */
    private static final int[] SKIN_INV_COV = {4107, 1663, 1663, 2157};

    /**
     * The distance thresholds of the models in Q18.
     */
    private static final int[] SKIN_THRESHOLD = {1570636, 1400000, 800000, 800000, 800000, 800000};

    /**
     * The encoder.
     */
    private final Vp9RtEncoder enc;

    /**
     * Whether each 8x8 block (at 16x16 granularity) is skin.
     */
    byte[] skinMap;

    /**
     * Creates a skin detector.
     *
     * @param enc the encoder
     */
    Vp9RtContent(Vp9RtEncoder enc) {
        this.enc = enc;
    }

    /**
     * Resizes the skin map for a frame size.
     *
     * @param miRows the 8x8 rows
     * @param miCols the 8x8 columns
     */
    void resize(int miRows, int miCols) {
        skinMap = new byte[miRows * miCols];
    }

    /**
     * Returns the Mahalanobis distance of a chroma pair to a skin model
     * ({@code vpx_evaluate_skin_color_difference}).
     *
     * @param cb  the blue difference
     * @param cr  the red difference
     * @param idx the model
     * @return the distance in Q18
     */
    private static int colorDifference(int cb, int cr, int idx) {
        var cbQ6 = cb << 6;
        var crQ6 = cr << 6;
        var cbDiff = (cbQ6 - SKIN_MEAN[idx][0]) * (cbQ6 - SKIN_MEAN[idx][0]);
        var cbcrDiff = (cbQ6 - SKIN_MEAN[idx][0]) * (crQ6 - SKIN_MEAN[idx][1]);
        var crDiff = (crQ6 - SKIN_MEAN[idx][1]) * (crQ6 - SKIN_MEAN[idx][1]);
        var cbQ2 = (cbDiff + (1 << 9)) >> 10;
        var cbcrQ2 = (cbcrDiff + (1 << 9)) >> 10;
        var crQ2 = (crDiff + (1 << 9)) >> 10;
        return SKIN_INV_COV[0] * cbQ2 + SKIN_INV_COV[1] * cbcrQ2 + SKIN_INV_COV[2] * cbcrQ2 + SKIN_INV_COV[3] * crQ2;
    }

    /**
     * Returns whether a sample's color is skin ({@code vpx_skin_pixel}).
     *
     * @param y      the luma
     * @param cb     the blue difference
     * @param cr     the red difference
     * @param motion whether the block moves
     * @return whether the color is skin
     */
    static boolean skinPixel(int y, int cb, int cr, boolean motion) {
        if (y < 40 || y > 220) {
            return false;
        }
        if (cb == 128 && cr == 128) {
            return false;
        }
        if (cb > 150 && cr < 110) {
            return false;
        }
        for (var i = 0; i < 5; i++) {
            var diff = colorDifference(cb, cr, i);
            if (diff < SKIN_THRESHOLD[i + 1]) {
                if (y < 60 && diff > 3 * (SKIN_THRESHOLD[i + 1] >> 2)) {
                    return false;
                }
                return motion || diff <= (SKIN_THRESHOLD[i + 1] >> 1);
            }
            if (diff > (SKIN_THRESHOLD[i + 1] << 3)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns whether a block is skin from its center sample ({@code vp9_compute_skin_block}).
     *
     * @param yOff         the index of the block in the luma plane
     * @param uvOff        the index of the block in the chroma planes
     * @param bsize        the block size
     * @param consecZeromv the frames the block has kept a zero motion vector
     * @return whether the block is skin
     */
    private boolean skinBlock(int yOff, int uvOff, int bsize, int consecZeromv) {
        if (consecZeromv > 60) {
            return false;
        }
        var src = enc.source;
        var yw = (4 << Vp9RtCoder.B_WIDTH_LOG2[bsize]) >> 1;
        var yh = (4 << Vp9RtCoder.B_HEIGHT_LOG2[bsize]) >> 1;
        var y = src.planes[0][yOff + yh * src.strides[0] + yw] & 0xFF;
        var uvIndex = uvOff + (yh >> 1) * src.strides[1] + (yw >> 1);
        var u = src.planes[1][uvIndex] & 0xFF;
        var v = src.planes[2][uvIndex] & 0xFF;
        return skinPixel(y, u, v, consecZeromv <= 25);
    }

    /**
     * Returns whether the block at given source offsets is skin, ignoring its motion history.
     *
     * @param srcOff the indices of the block in the three source planes
     * @param bsize  the block size
     * @return whether the block is skin
     */
    boolean isSkinBlock(int[] srcOff, int bsize) {
        return skinBlock(srcOff[0], srcOff[1], bsize, 0);
    }

    /**
     * Returns whether a static 16x16 block is skin, a block static for long being skin only with
     * a closer color match.
     *
     * @param srcOff       the indices of the block in the three source planes
     * @param consecZeromv the frames the block has kept a zero motion vector
     * @return whether the block is skin
     */
    boolean skinBlockWithMotion(int[] srcOff, int consecZeromv) {
        return skinBlock(srcOff[0], srcOff[1], Vp9Constants.BLOCK_16X16, consecZeromv);
    }

    /**
     * Computes the skin map of a superblock in 16x16 units and cleans isolated blocks
     * ({@code vp9_compute_skin_sb} with {@code BLOCK_16X16}).
     *
     * @param miRow the superblock's 8x8 row
     * @param miCol the superblock's 8x8 column
     */
    void computeSkinSuperblock(int miRow, int miCol) {
        var src = enc.source;
        var miCols = enc.miCols;
        var ys = src.strides[0];
        var uvs = src.strides[1];
        var yOff = src.offset(0, miCol << 3, miRow << 3);
        var uvOff = src.offset(1, miCol << 2, miRow << 2);
        var rowLimit = Math.min(miRow + 8, enc.miRows - 2);
        var colLimit = Math.min(miCol + 8, miCols - 2);
        var czm = enc.consecZeroMv;
        var map = skinMap;
        for (var i = miRow; i < rowLimit; i += 2) {
            var num = 0;
            for (var j = miCol; j < colLimit; j += 2) {
                if (i == 0 || j == 0) {
                    continue;
                }
                var index = i * miCols + j;
                var consec = Math.min(czm[index] & 0xFF, Math.min(czm[index + 1] & 0xFF,
                        Math.min(czm[index + miCols] & 0xFF, czm[index + miCols + 1] & 0xFF)));
                map[index] = (byte) (skinBlock(yOff, uvOff, Vp9Constants.BLOCK_16X16, consec) ? 1 : 0);
                num++;
                yOff += 16;
                uvOff += 8;
            }
            yOff += (ys << 4) - (num << 4);
            uvOff += (uvs << 3) - (num << 3);
        }
        for (var i = miRow; i < rowLimit; i += 2) {
            for (var j = miCol; j < colLimit; j += 2) {
                var index = i * miCols + j;
                if ((i == miRow && (j == miCol || j == colLimit - 2))
                        || (i == rowLimit - 2 && (j == miCol || j == colLimit - 2))) {
                    continue;
                }
                var nonSkinThreshold = i == miRow || i == rowLimit - 2 || j == miCol || j == colLimit - 2 ? 5 : 8;
                var neighbors = 0;
                for (var mi = -2; mi <= 2; mi += 2) {
                    for (var mj = -2; mj <= 2; mj += 2) {
                        if (i + mi >= miRow && i + mi < rowLimit && j + mj >= miCol && j + mj < colLimit
                                && map[(i + mi) * miCols + j + mj] != 0) {
                            neighbors++;
                        }
                    }
                }
                if (map[index] != 0 && neighbors < 2) {
                    map[index] = 0;
                }
                if (map[index] == 0 && neighbors == nonSkinThreshold) {
                    map[index] = 1;
                }
            }
        }
    }

    /**
     * Returns whether most of a superblock is skin, in which case it splits at least to 32x32
     * ({@code skin_sb_split}).
     *
     * @param miRow the superblock's 8x8 row
     * @param miCol the superblock's 8x8 column
     * @return whether the superblock is skin
     */
    boolean skinSuperblockSplit(int miRow, int miCol) {
        var miCols = enc.miCols;
        var miRows = enc.miRows;
        var lowRes = enc.width <= 352 && enc.height <= 288;
        if (lowRes || !(miCol >= 8 && miCol + 8 < miCols && miRow >= 8 && miRow + 8 < miRows)) {
            return false;
        }
        var skin = 0;
        var nonSkin = 0;
        var xmis = Math.min(miCols - miCol, 8);
        var ymis = Math.min(miRows - miRow, 8);
        var blockIndex = miRow * miCols + miCol;
        outer:
        for (var i = 0; i < ymis; i += 2) {
            for (var j = 0; j < xmis; j += 2) {
                var isSkin = skinMap[blockIndex + i * miCols + j];
                skin += isSkin;
                nonSkin += 1 - isSkin;
                if (nonSkin > 3) {
                    break outer;
                }
            }
        }
        return skin > 12;
    }
}
