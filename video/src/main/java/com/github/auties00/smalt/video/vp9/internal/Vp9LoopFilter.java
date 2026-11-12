/* Ports libvpx (vp9/common/vp9_loopfilter.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_16X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_32X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_8X8;

/**
 * Applies the VP9 loop filter to the superblocks of a decoded frame.
 *
 * <p>For each superblock the filter builds bit masks of the 8x8 block edges to filter and the
 * filter length of each, from the mode information of the blocks the superblock holds, then
 * filters every plane: first the vertical edges of the whole superblock, then its horizontal
 * edges. Superblocks must be filtered in raster order, or in an order where superblock
 * {@code (row, col)} follows {@code (row, col - 1)} and {@code (row - 1, col + 1)}, the
 * dependencies of overlapping filters.
 *
 * <p>An instance holds the masks of one superblock and belongs to one thread at a time.
 *
 * @implNote This implementation follows libvpx's {@code vp9/common/vp9_loopfilter.c}
 * ({@code vp9_setup_mask}, {@code vp9_adjust_mask}, {@code vp9_filter_block_plane_ss00},
 * {@code vp9_filter_block_plane_ss11}, {@code vp9_filter_block_plane_non420}).
 */
final class Vp9LoopFilter {
    /**
     * The left edge masks of each transform size over a 64x64 area, one bit per 8x8 block in
     * raster order ({@code left_64x64_txform_mask}).
     */
    private static final long[] LEFT_64X64_TXFORM_MASK = {
            0xffffffffffffffffL, 0xffffffffffffffffL, 0x5555555555555555L, 0x1111111111111111L
    };

    /**
     * The above edge masks of each transform size over a 64x64 area
     * ({@code above_64x64_txform_mask}).
     */
    private static final long[] ABOVE_64X64_TXFORM_MASK = {
            0xffffffffffffffffL, 0xffffffffffffffffL, 0x00ff00ff00ff00ffL, 0x000000ff000000ffL
    };

    /**
     * The left edge mask of each block size, at the top-left 8x8 position
     * ({@code left_prediction_mask}).
     */
    private static final long[] LEFT_PREDICTION_MASK = {
            0x1L, 0x1L, 0x1L, 0x1L, 0x101L, 0x1L, 0x101L, 0x1010101L, 0x101L, 0x1010101L,
            0x0101010101010101L, 0x1010101L, 0x0101010101010101L
    };

    /**
     * The above edge mask of each block size, at the top-left 8x8 position
     * ({@code above_prediction_mask}).
     */
    private static final long[] ABOVE_PREDICTION_MASK = {
            0x1L, 0x1L, 0x1L, 0x1L, 0x1L, 0x3L, 0x3L, 0x3L, 0xfL, 0xfL, 0xfL, 0xffL, 0xffL
    };

    /**
     * The mask of the 8x8 blocks each block size covers, at the top-left position
     * ({@code size_mask}).
     */
    private static final long[] SIZE_MASK = {
            0x1L, 0x1L, 0x1L, 0x1L, 0x101L, 0x3L, 0x303L, 0x3030303L, 0xf0fL, 0xf0f0f0fL,
            0x0f0f0f0f0f0f0f0fL, 0xffffffffL, 0xffffffffffffffffL
    };

    /**
     * The 8x8 blocks of a superblock whose left edge is a 32x32 boundary.
     */
    private static final long LEFT_BORDER = 0x1111111111111111L;

    /**
     * The 8x8 blocks of a superblock whose top edge is a 32x32 boundary.
     */
    private static final long ABOVE_BORDER = 0x000000ff000000ffL;

    /**
     * The left edge masks of each chroma transform size over a 4:2:0 superblock, one bit per
     * chroma 8x8 block ({@code left_64x64_txform_mask_uv}).
     */
    private static final int[] LEFT_64X64_TXFORM_MASK_UV = {0xffff, 0xffff, 0x5555, 0x1111};

    /**
     * The above edge masks of each chroma transform size ({@code above_64x64_txform_mask_uv}).
     */
    private static final int[] ABOVE_64X64_TXFORM_MASK_UV = {0xffff, 0xffff, 0x0f0f, 0x000f};

    /**
     * The chroma left edge mask of each block size ({@code left_prediction_mask_uv}).
     */
    private static final int[] LEFT_PREDICTION_MASK_UV = {
            0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x11, 0x1, 0x11, 0x1111, 0x11, 0x1111
    };

    /**
     * The chroma above edge mask of each block size ({@code above_prediction_mask_uv}).
     */
    private static final int[] ABOVE_PREDICTION_MASK_UV = {
            0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x3, 0x3, 0x3, 0xf, 0xf
    };

    /**
     * The chroma blocks each block size covers ({@code size_mask_uv}).
     */
    private static final int[] SIZE_MASK_UV = {
            0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x11, 0x3, 0x33, 0x3333, 0xff, 0xffff
    };

    /**
     * The chroma blocks whose left edge is a 32x32 boundary.
     */
    private static final int LEFT_BORDER_UV = 0x1111;

    /**
     * The chroma blocks whose top edge is a 32x32 boundary.
     */
    private static final int ABOVE_BORDER_UV = 0x000f;

    /**
     * Maps a prediction mode to its mode delta index: 0 for intra modes and the zero motion
     * vector, 1 for the other inter modes ({@code mode_lf_lut}).
     */
    private static final int[] MODE_LF_LUT = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1};

    /**
     * The filter levels, thresholds and mode information of the frame being filtered.
     */
    private final Vp9LoopFilterParams lf;

    /**
     * The 8-bit kernels.
     */
    private final Vp9Dsp dsp = Vp9Dsp.INSTANCE;

    /**
     * The scratch memory of the 8-bit kernels.
     */
    private final byte[] scratch = new byte[Vp9Dsp.LOOP_FILTER_SCRATCH];

    /**
     * The luma left edge masks per filter length (4, 8, 16, unused).
     */
    private final long[] leftY = new long[4];

    /**
     * The luma above edge masks per filter length.
     */
    private final long[] aboveY = new long[4];

    /**
     * The luma inner 4x4 edge mask.
     */
    private long int4x4Y;

    /**
     * The chroma left edge masks per filter length.
     */
    private final int[] leftUv = new int[4];

    /**
     * The chroma above edge masks per filter length.
     */
    private final int[] aboveUv = new int[4];

    /**
     * The chroma inner 4x4 edge mask.
     */
    private int int4x4Uv;

    /**
     * The filter level of each luma 8x8 block of the superblock.
     */
    private final byte[] lflY = new byte[64];

    /**
     * The filter levels of the chroma blocks, or of the blocks of a plane on the generic path.
     */
    private final byte[] lfl = new byte[64];

    /**
     * The per-row masks of the generic path: 16-wide edges, 8-wide edges, 4-wide edges and
     * inner 4x4 edges, 8 rows each.
     */
    private final int[] rowMasks = new int[4 * 8];

    /**
     * The 8-bit samples of the plane being filtered, or {@code null}.
     */
    private byte[] s8;

    /**
     * The high bit depth samples of the plane being filtered, or {@code null}.
     */
    private short[] s16;

    /**
     * The row stride of the plane being filtered.
     */
    private int pitch;

    /**
     * The bit depth of the frame being filtered.
     */
    private int bitDepth;

    /**
     * Whether only the luma plane is filtered, as the encoder's search of the filter level does.
     */
    boolean lumaOnly;

    /**
     * Constructs a filter of the frames whose parameters a decoder or an encoder keeps.
     *
     * @param params the parameters, which the owner updates before each frame
     */
    Vp9LoopFilter(Vp9LoopFilterParams params) {
        this.lf = params;
    }

    /**
     * Filters one row of superblocks, optionally as one row of a wavefront.
     *
     * <p>In a wavefront, superblock {@code c} is filtered only once the row above has finished
     * superblock {@code c + 1}: the last superblock of that row whose filtering changes samples
     * this superblock reads (its left edge moves the 8 columns before it), and whose samples this
     * superblock's top edge then changes. Each finished superblock is published, so the result is
     * the same as filtering the rows in order.
     *
     * @param frame the frame being filtered
     * @param miRow the superblock row's first unit row
     * @param wavefront the wavefront the row belongs to, or {@code null} to filter it alone
     * @throws java.util.concurrent.CancellationException if the wavefront was aborted
     */
    void filterRow(Vp9Frame frame, int miRow, Vp9Wavefront wavefront) {
        var sbRow = miRow >> 3;
        var sbCols = (lf.miCols + 7) >> 3;
        var above = 0;
        for (var sb = 0; sb < sbCols; sb++) {
            if (wavefront != null && sbRow > 0) {
                var needed = Math.min(sb + 2, sbCols);
                if (above < needed) {
                    above = wavefront.awaitProgress(sbRow - 1, needed);
                }
            }
            filterSuperblock(frame, miRow, sb << 3);
            if (wavefront != null) {
                wavefront.publish(sbRow, sb + 1);
            }
        }
    }

    /**
     * Filters one superblock, every plane.
     *
     * @param frame the frame being filtered
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    void filterSuperblock(Vp9Frame frame, int miRow, int miCol) {
        setupMask(miRow, miCol);
        adjustMask(miRow, miCol);
        bitDepth = frame.bitDepth;
        selectPlane(frame, 0);
        filterPlaneSs00(miRow, miCol);
        if (lumaOnly) {
            return;
        }
        var ssX = frame.ssX;
        var ssY = frame.ssY;
        for (var plane = 1; plane < 3; plane++) {
            selectPlane(frame, plane);
            if (ssX == 1 && ssY == 1) {
                filterPlaneSs11(miRow, miCol);
            } else if (ssX == 0 && ssY == 0) {
                filterPlaneSs00(miRow, miCol);
            } else {
                filterPlaneGeneric(frame, miRow, miCol);
            }
        }
    }

    /**
     * Makes a plane the target of the edge filters.
     *
     * @param frame the frame being filtered
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    private void selectPlane(Vp9Frame frame, int plane) {
        s8 = frame.planes8[plane];
        s16 = frame.planes16[plane];
        pitch = frame.strides[plane];
    }

    /**
     * Returns the filter level of a block ({@code get_filter_level}).
     *
     * @param mi the block
     * @return the level, from 0 to 63
     */
    private int filterLevel(ModeInfo mi) {
        return lf.levels[(mi.segmentId * 4 + mi.ref0) * 2 + MODE_LF_LUT[mi.mode]];
    }

    /**
     * Adds a block's edges to the luma and chroma masks ({@code build_masks}).
     *
     * @param mi the block
     * @param shiftY the bit of the block's top-left 8x8 block in the luma masks
     * @param shiftUv the bit of the block's top-left chroma block in the chroma masks
     */
    private void buildMasks(ModeInfo mi, int shiftY, int shiftUv) {
        var blockSize = mi.sbType;
        var txSizeY = mi.txSize;
        var txSizeUv = Vp9Constants.UV_TX_SIZE[(blockSize * 4 + txSizeY) * 4 + 3];
        var level = filterLevel(mi);
        if (level == 0) {
            return;
        }
        fillLevels(blockSize, shiftY, level);
        aboveY[txSizeY] |= ABOVE_PREDICTION_MASK[blockSize] << shiftY;
        aboveUv[txSizeUv] |= ABOVE_PREDICTION_MASK_UV[blockSize] << shiftUv;
        leftY[txSizeY] |= LEFT_PREDICTION_MASK[blockSize] << shiftY;
        leftUv[txSizeUv] |= LEFT_PREDICTION_MASK_UV[blockSize] << shiftUv;
        if (mi.skip != 0 && mi.isInter()) {
            return;
        }
        aboveY[txSizeY] |= (SIZE_MASK[blockSize] & ABOVE_64X64_TXFORM_MASK[txSizeY]) << shiftY;
        aboveUv[txSizeUv] |= (SIZE_MASK_UV[blockSize] & ABOVE_64X64_TXFORM_MASK_UV[txSizeUv]) << shiftUv;
        leftY[txSizeY] |= (SIZE_MASK[blockSize] & LEFT_64X64_TXFORM_MASK[txSizeY]) << shiftY;
        leftUv[txSizeUv] |= (SIZE_MASK_UV[blockSize] & LEFT_64X64_TXFORM_MASK_UV[txSizeUv]) << shiftUv;
        if (txSizeY == TX_4X4) {
            int4x4Y |= SIZE_MASK[blockSize] << shiftY;
        }
        if (txSizeUv == TX_4X4) {
            int4x4Uv |= (SIZE_MASK_UV[blockSize] & 0xffff) << shiftUv;
        }
    }

    /**
     * Adds a block's edges to the luma masks only ({@code build_y_mask}).
     *
     * @param mi the block
     * @param shiftY the bit of the block's top-left 8x8 block in the luma masks
     */
    private void buildYMask(ModeInfo mi, int shiftY) {
        var blockSize = mi.sbType;
        var txSizeY = mi.txSize;
        var level = filterLevel(mi);
        if (level == 0) {
            return;
        }
        fillLevels(blockSize, shiftY, level);
        aboveY[txSizeY] |= ABOVE_PREDICTION_MASK[blockSize] << shiftY;
        leftY[txSizeY] |= LEFT_PREDICTION_MASK[blockSize] << shiftY;
        if (mi.skip != 0 && mi.isInter()) {
            return;
        }
        aboveY[txSizeY] |= (SIZE_MASK[blockSize] & ABOVE_64X64_TXFORM_MASK[txSizeY]) << shiftY;
        leftY[txSizeY] |= (SIZE_MASK[blockSize] & LEFT_64X64_TXFORM_MASK[txSizeY]) << shiftY;
        if (txSizeY == TX_4X4) {
            int4x4Y |= SIZE_MASK[blockSize] << shiftY;
        }
    }

    /**
     * Records the filter level of every 8x8 block a block covers.
     *
     * @param blockSize the block size
     * @param shiftY the index of the block's top-left 8x8 block
     * @param level the filter level of the block, from 0 to 63
     */
    private void fillLevels(int blockSize, int shiftY, int level) {
        var w = Vp9Constants.NUM_8X8_WIDE[blockSize];
        var h = Vp9Constants.NUM_8X8_HIGH[blockSize];
        var index = shiftY;
        var value = (byte) level;
        for (var i = 0; i < h; i++) {
            for (var j = 0; j < w; j++) {
                lflY[index + j] = value;
            }
            index += 8;
        }
    }

    /**
     * Builds the masks of a superblock from the mode information of its blocks
     * ({@code vp9_setup_mask}).
     *
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    private void setupMask(int miRow, int miCol) {
        Arrays.fill(leftY, 0);
        Arrays.fill(aboveY, 0);
        int4x4Y = 0;
        Arrays.fill(leftUv, 0);
        Arrays.fill(aboveUv, 0);
        int4x4Uv = 0;
        Arrays.fill(lflY, (byte) 0);
        var grid = lf.miGrid;
        var stride = lf.miCols;
        var base = miRow * stride + miCol;
        var maxRows = Math.min(lf.miRows - miRow, 8);
        var maxCols = Math.min(lf.miCols - miCol, 8);
        var first = grid[base];
        switch (first.sbType) {
            case BLOCK_64X64 -> buildMasks(first, 0, 0);
            case BLOCK_64X32 -> {
                buildMasks(first, 0, 0);
                if (4 < maxRows) {
                    buildMasks(grid[base + 4 * stride], 32, 8);
                }
            }
            case BLOCK_32X64 -> {
                buildMasks(first, 0, 0);
                if (4 < maxCols) {
                    buildMasks(grid[base + 4], 4, 2);
                }
            }
            default -> {
                for (var idx32 = 0; idx32 < 4; idx32++) {
                    var col32 = (idx32 & 1) << 2;
                    var row32 = (idx32 >> 1) << 2;
                    if (col32 >= maxCols || row32 >= maxRows) {
                        continue;
                    }
                    var shiftY32 = row32 * 8 + col32;
                    var shiftUv32 = (row32 >> 1) * 4 + (col32 >> 1);
                    var mi32 = grid[base + row32 * stride + col32];
                    switch (mi32.sbType) {
                        case BLOCK_32X32 -> buildMasks(mi32, shiftY32, shiftUv32);
                        case BLOCK_32X16 -> {
                            buildMasks(mi32, shiftY32, shiftUv32);
                            if (row32 + 2 < maxRows) {
                                buildMasks(grid[base + (row32 + 2) * stride + col32], shiftY32 + 16, shiftUv32 + 4);
                            }
                        }
                        case BLOCK_16X32 -> {
                            buildMasks(mi32, shiftY32, shiftUv32);
                            if (col32 + 2 < maxCols) {
                                buildMasks(grid[base + row32 * stride + col32 + 2], shiftY32 + 2, shiftUv32 + 1);
                            }
                        }
                        default -> setupMask16(grid, base, stride, row32, col32, shiftY32, shiftUv32, maxRows,
                                maxCols);
                    }
                }
            }
        }
    }

    /**
     * Builds the masks of the four 16x16 areas of a 32x32 area split further.
     *
     * @param grid the mode information grid
     * @param base the grid index of the superblock's first unit
     * @param stride the grid stride
     * @param row32 the 32x32 area's unit row within the superblock
     * @param col32 the 32x32 area's unit column within the superblock
     * @param shiftY32 the luma mask bit of the area
     * @param shiftUv32 the chroma mask bit of the area
     * @param maxRows the superblock's unit rows inside the frame
     * @param maxCols the superblock's unit columns inside the frame
     */
    private void setupMask16(ModeInfo[] grid, int base, int stride, int row32, int col32, int shiftY32, int shiftUv32,
                             int maxRows, int maxCols) {
        for (var idx16 = 0; idx16 < 4; idx16++) {
            var col16 = col32 + ((idx16 & 1) << 1);
            var row16 = row32 + ((idx16 >> 1) << 1);
            if (col16 >= maxCols || row16 >= maxRows) {
                continue;
            }
            var shiftY16 = shiftY32 + ((idx16 >> 1) << 4) + ((idx16 & 1) << 1);
            var shiftUv16 = shiftUv32 + ((idx16 >> 1) << 2) + (idx16 & 1);
            var mi16 = grid[base + row16 * stride + col16];
            switch (mi16.sbType) {
                case BLOCK_16X16 -> buildMasks(mi16, shiftY16, shiftUv16);
                case BLOCK_16X8 -> {
                    buildMasks(mi16, shiftY16, shiftUv16);
                    if (row16 + 1 < maxRows) {
                        buildYMask(grid[base + (row16 + 1) * stride + col16], shiftY16 + 8);
                    }
                }
                case BLOCK_8X16 -> {
                    buildMasks(mi16, shiftY16, shiftUv16);
                    if (col16 + 1 < maxCols) {
                        buildYMask(grid[base + row16 * stride + col16 + 1], shiftY16 + 1);
                    }
                }
                default -> {
                    buildMasks(mi16, shiftY16, shiftUv16);
                    for (var idx8 = 1; idx8 < 4; idx8++) {
                        var col8 = col16 + (idx8 & 1);
                        var row8 = row16 + (idx8 >> 1);
                        if (col8 >= maxCols || row8 >= maxRows) {
                            continue;
                        }
                        buildYMask(grid[base + row8 * stride + col8], shiftY16 + ((idx8 >> 1) << 3) + (idx8 & 1));
                    }
                }
            }
        }
    }

    /**
     * Adjusts the masks of a superblock: the 32x32 transforms use the 16-wide filter, 4x4
     * transform edges on 32x32 boundaries use the 8-wide filter, edges outside the frame and on
     * its left border are dropped, and wide chroma filters on the last rows and columns are
     * narrowed ({@code vp9_adjust_mask}).
     *
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    private void adjustMask(int miRow, int miCol) {
        leftY[TX_16X16] |= leftY[TX_32X32];
        aboveY[TX_16X16] |= aboveY[TX_32X32];
        leftUv[TX_16X16] |= leftUv[TX_32X32];
        aboveUv[TX_16X16] |= aboveUv[TX_32X32];
        leftY[TX_8X8] |= leftY[TX_4X4] & LEFT_BORDER;
        leftY[TX_4X4] &= ~LEFT_BORDER;
        aboveY[TX_8X8] |= aboveY[TX_4X4] & ABOVE_BORDER;
        aboveY[TX_4X4] &= ~ABOVE_BORDER;
        leftUv[TX_8X8] |= leftUv[TX_4X4] & LEFT_BORDER_UV;
        leftUv[TX_4X4] &= ~LEFT_BORDER_UV;
        aboveUv[TX_8X8] |= aboveUv[TX_4X4] & ABOVE_BORDER_UV;
        aboveUv[TX_4X4] &= ~ABOVE_BORDER_UV;
        if (miRow + 8 > lf.miRows) {
            var rows = lf.miRows - miRow;
            var maskY = (1L << (rows << 3)) - 1;
            var maskUv = ((1 << (((rows + 1) >> 1) << 2)) - 1) & 0xffff;
            for (var i = 0; i < TX_32X32; i++) {
                leftY[i] &= maskY;
                aboveY[i] &= maskY;
                leftUv[i] &= maskUv;
                aboveUv[i] &= maskUv;
            }
            int4x4Y &= maskY;
            int4x4Uv &= maskUv;
            if (rows == 1) {
                aboveUv[TX_8X8] |= aboveUv[TX_16X16];
                aboveUv[TX_16X16] = 0;
            }
            if (rows == 5) {
                aboveUv[TX_8X8] |= aboveUv[TX_16X16] & 0xff00;
                aboveUv[TX_16X16] &= ~(aboveUv[TX_16X16] & 0xff00);
            }
        }
        if (miCol + 8 > lf.miCols) {
            var columns = lf.miCols - miCol;
            var maskY = ((1L << columns) - 1) * 0x0101010101010101L;
            var maskUv = (((1 << ((columns + 1) >> 1)) - 1) * 0x1111) & 0xffff;
            var maskUvInt = (((1 << (columns >> 1)) - 1) * 0x1111) & 0xffff;
            for (var i = 0; i < TX_32X32; i++) {
                leftY[i] &= maskY;
                aboveY[i] &= maskY;
                leftUv[i] &= maskUv;
                aboveUv[i] &= maskUv;
            }
            int4x4Y &= maskY;
            int4x4Uv &= maskUvInt;
            if (columns == 1) {
                leftUv[TX_8X8] |= leftUv[TX_16X16];
                leftUv[TX_16X16] = 0;
            }
            if (columns == 5) {
                leftUv[TX_8X8] |= leftUv[TX_16X16] & 0xcccc;
                leftUv[TX_16X16] &= ~(leftUv[TX_16X16] & 0xcccc);
            }
        }
        if (miCol == 0) {
            for (var i = 0; i < TX_32X32; i++) {
                leftY[i] &= 0xfefefefefefefefeL;
                leftUv[i] &= 0xeeee;
            }
        }
    }

    /**
     * Filters a plane without chroma subsampling with the luma masks
     * ({@code vp9_filter_block_plane_ss00}).
     *
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    private void filterPlaneSs00(int miRow, int miCol) {
        var dst0 = (miRow * 8) * pitch + miCol * 8;
        var mask16 = leftY[TX_16X16];
        var mask8 = leftY[TX_8X8];
        var mask4 = leftY[TX_4X4];
        var mask4Int = int4x4Y;
        var dst = dst0;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r += 2) {
            filterVerticalRow2(false, dst, (int) mask16 & 0xffff, (int) mask8 & 0xffff, (int) mask4 & 0xffff,
                    (int) mask4Int & 0xffff, lflY, r << 3);
            dst += 16 * pitch;
            mask16 >>>= 16;
            mask8 >>>= 16;
            mask4 >>>= 16;
            mask4Int >>>= 16;
        }
        dst = dst0;
        mask16 = aboveY[TX_16X16];
        mask8 = aboveY[TX_8X8];
        mask4 = aboveY[TX_4X4];
        mask4Int = int4x4Y;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r++) {
            int m16;
            int m8;
            int m4;
            if (miRow + r == 0) {
                m16 = 0;
                m8 = 0;
                m4 = 0;
            } else {
                m16 = (int) mask16 & 0xff;
                m8 = (int) mask8 & 0xff;
                m4 = (int) mask4 & 0xff;
            }
            filterHorizontal(dst, m16, m8, m4, (int) mask4Int & 0xff, lflY, r << 3);
            dst += 8 * pitch;
            mask16 >>>= 8;
            mask8 >>>= 8;
            mask4 >>>= 8;
            mask4Int >>>= 8;
        }
    }

    /**
     * Filters a 4:2:0 chroma plane with the chroma masks ({@code vp9_filter_block_plane_ss11}).
     *
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    private void filterPlaneSs11(int miRow, int miCol) {
        var dst0 = (miRow * 4) * pitch + miCol * 4;
        Arrays.fill(lfl, 0, 16, (byte) 0);
        var mask16 = leftUv[TX_16X16];
        var mask8 = leftUv[TX_8X8];
        var mask4 = leftUv[TX_4X4];
        var mask4Int = int4x4Uv;
        var dst = dst0;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r += 4) {
            for (var c = 0; c < 4; c++) {
                lfl[(r << 1) + c] = lflY[(r << 3) + (c << 1)];
                lfl[((r + 2) << 1) + c] = lflY[((r + 2) << 3) + (c << 1)];
            }
            filterVerticalRow2(true, dst, mask16 & 0xff, mask8 & 0xff, mask4 & 0xff, mask4Int & 0xff, lfl, r << 1);
            dst += 16 * pitch;
            mask16 >>>= 8;
            mask8 >>>= 8;
            mask4 >>>= 8;
            mask4Int >>>= 8;
        }
        dst = dst0;
        mask16 = aboveUv[TX_16X16];
        mask8 = aboveUv[TX_8X8];
        mask4 = aboveUv[TX_4X4];
        mask4Int = int4x4Uv;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r += 2) {
            var skipBorder4x4 = miRow + r == lf.miRows - 1;
            var m4Int = skipBorder4x4 ? 0 : mask4Int & 0xf;
            int m16;
            int m8;
            int m4;
            if (miRow + r == 0) {
                m16 = 0;
                m8 = 0;
                m4 = 0;
            } else {
                m16 = mask16 & 0xf;
                m8 = mask8 & 0xf;
                m4 = mask4 & 0xf;
            }
            filterHorizontal(dst, m16, m8, m4, m4Int, lfl, r << 1);
            dst += 8 * pitch;
            mask16 >>>= 4;
            mask8 >>>= 4;
            mask4 >>>= 4;
            mask4Int >>>= 4;
        }
    }

    /**
     * Filters a chroma plane with 4:2:2 or 4:4:0 subsampling, building its masks from the mode
     * information directly ({@code vp9_filter_block_plane_non420}).
     *
     * @param frame the frame being filtered
     * @param miRow the superblock's first unit row
     * @param miCol the superblock's first unit column
     */
    private void filterPlaneGeneric(Vp9Frame frame, int miRow, int miCol) {
        var ssX = frame.ssX;
        var ssY = frame.ssY;
        var rowStep = 1 << ssY;
        var colStep = 1 << ssX;
        var masks = rowMasks;
        Arrays.fill(masks, 0);
        Arrays.fill(lfl, (byte) 0);
        var grid = lf.miGrid;
        var miCols = lf.miCols;
        var dst0 = ((miRow * 8) >> ssY) * pitch + ((miCol * 8) >> ssX);
        var dst = dst0;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r += rowStep) {
            var mask16c = 0;
            var mask8c = 0;
            var mask4c = 0;
            for (var c = 0; c < 8 && miCol + c < miCols; c += colStep) {
                var mi = grid[(miRow + r) * miCols + miCol + c];
                var sbType = mi.sbType;
                var skipThis = mi.skip != 0 && mi.isInter();
                var blockEdgeLeft = Vp9Constants.NUM_4X4_WIDE[sbType] > 1
                        ? (c & (Vp9Constants.NUM_8X8_WIDE[sbType] - 1)) == 0 : true;
                var skipThisC = skipThis && !blockEdgeLeft;
                var blockEdgeAbove = Vp9Constants.NUM_4X4_HIGH[sbType] > 1
                        ? (r & (Vp9Constants.NUM_8X8_HIGH[sbType] - 1)) == 0 : true;
                var skipThisR = skipThis && !blockEdgeAbove;
                var txSize = Vp9Constants.UV_TX_SIZE[((sbType * 4 + mi.txSize) * 2 + ssX) * 2 + ssY];
                var skipBorder4x4C = ssX != 0 && miCol + c == miCols - 1;
                var skipBorder4x4R = ssY != 0 && miRow + r == lf.miRows - 1;
                var level = filterLevel(mi);
                lfl[(r << 3) + (c >> ssX)] = (byte) level;
                if (level == 0) {
                    continue;
                }
                var bit = 1 << (c >> ssX);
                if (txSize == TX_32X32) {
                    if (!skipThisC && ((c >> ssX) & 3) == 0) {
                        if (!skipBorder4x4C) {
                            mask16c |= bit;
                        } else {
                            mask8c |= bit;
                        }
                    }
                    if (!skipThisR && ((r >> ssY) & 3) == 0) {
                        if (!skipBorder4x4R) {
                            masks[r] |= bit;
                        } else {
                            masks[8 + r] |= bit;
                        }
                    }
                } else if (txSize == TX_16X16) {
                    if (!skipThisC && ((c >> ssX) & 1) == 0) {
                        if (!skipBorder4x4C) {
                            mask16c |= bit;
                        } else {
                            mask8c |= bit;
                        }
                    }
                    if (!skipThisR && ((r >> ssY) & 1) == 0) {
                        if (!skipBorder4x4R) {
                            masks[r] |= bit;
                        } else {
                            masks[8 + r] |= bit;
                        }
                    }
                } else {
                    if (!skipThisC) {
                        if (txSize == TX_8X8 || ((c >> ssX) & 3) == 0) {
                            mask8c |= bit;
                        } else {
                            mask4c |= bit;
                        }
                    }
                    if (!skipThisR) {
                        if (txSize == TX_8X8 || ((r >> ssY) & 3) == 0) {
                            masks[8 + r] |= bit;
                        } else {
                            masks[16 + r] |= bit;
                        }
                    }
                    if (!skipThis && txSize < TX_8X8 && !skipBorder4x4C) {
                        masks[24 + r] |= bit;
                    }
                }
            }
            var borderMask = miCol == 0 ? ~1 : ~0;
            filterVertical(dst, mask16c & borderMask, mask8c & borderMask, mask4c & borderMask, masks[24 + r], lfl,
                    r << 3);
            dst += 8 * pitch;
        }
        dst = dst0;
        for (var r = 0; r < 8 && miRow + r < lf.miRows; r += rowStep) {
            var skipBorder4x4R = ssY != 0 && miRow + r == lf.miRows - 1;
            var mask4IntR = skipBorder4x4R ? 0 : masks[24 + r];
            int m16;
            int m8;
            int m4;
            if (miRow + r == 0) {
                m16 = 0;
                m8 = 0;
                m4 = 0;
            } else {
                m16 = masks[r];
                m8 = masks[8 + r];
                m4 = masks[16 + r];
            }
            filterHorizontal(dst, m16, m8, m4, mask4IntR, lfl, r << 3);
            dst += 8 * pitch;
        }
    }

    /**
     * Filters the vertical edges of two rows of 8x8 blocks ({@code filter_selectively_vert_row2}).
     *
     * @param subsampled whether the plane is 4:2:0 chroma, with 4 blocks per mask row instead of 8
     * @param s the index of the first sample of the two rows
     * @param mask16 the blocks whose left edge gets the 16-wide filter, two mask rows
     * @param mask8 the blocks whose left edge gets the 8-wide filter
     * @param mask4 the blocks whose left edge gets the 4-wide filter
     * @param mask4Int the blocks whose inner vertical 4x4 edge gets the 4-wide filter
     * @param levels the filter levels
     * @param levelBase the index of the first row's first block level
     */
    private void filterVerticalRow2(boolean subsampled, int s, int mask16, int mask8, int mask4, int mask4Int,
                                    byte[] levels, int levelBase) {
        var dualMaskCutoff = subsampled ? 0xff : 0xffff;
        var forward = subsampled ? 4 : 8;
        var dualOne = 1 | (1 << forward);
        var lflIndex = levelBase;
        var ss0 = s;
        for (var mask = (mask16 | mask8 | mask4 | mask4Int) & dualMaskCutoff; mask != 0;
             mask = (mask & ~dualOne) >>> 1) {
            if ((mask & dualOne) != 0) {
                var level0 = levels[lflIndex] & 0xFF;
                var level1 = levels[lflIndex + forward] & 0xFF;
                var ss1 = ss0 + 8 * pitch;
                if ((mask16 & dualOne) != 0) {
                    if ((mask16 & dualOne) == dualOne) {
                        vertical16(ss0, level0, 16);
                    } else {
                        var second = (mask16 & 1) == 0;
                        vertical16(second ? ss1 : ss0, second ? level1 : level0, 8);
                    }
                }
                if ((mask8 & dualOne) != 0) {
                    if ((mask8 & dualOne) == dualOne) {
                        vertical8Dual(ss0, level0, level1);
                    } else {
                        var second = (mask8 & 1) == 0;
                        vertical8(second ? ss1 : ss0, second ? level1 : level0);
                    }
                }
                if ((mask4 & dualOne) != 0) {
                    if ((mask4 & dualOne) == dualOne) {
                        vertical4Dual(ss0, level0, level1);
                    } else {
                        var second = (mask4 & 1) == 0;
                        vertical4(second ? ss1 : ss0, second ? level1 : level0);
                    }
                }
                if ((mask4Int & dualOne) != 0) {
                    if ((mask4Int & dualOne) == dualOne) {
                        vertical4Dual(ss0 + 4, level0, level1);
                    } else {
                        var second = (mask4Int & 1) == 0;
                        vertical4((second ? ss1 : ss0) + 4, second ? level1 : level0);
                    }
                }
            }
            ss0 += 8;
            lflIndex++;
            mask16 >>>= 1;
            mask8 >>>= 1;
            mask4 >>>= 1;
            mask4Int >>>= 1;
        }
    }

    /**
     * Filters the vertical edges of one row of 8x8 blocks ({@code filter_selectively_vert}).
     *
     * @param s the index of the row's first sample
     * @param mask16 the blocks whose left edge gets the 16-wide filter
     * @param mask8 the blocks whose left edge gets the 8-wide filter
     * @param mask4 the blocks whose left edge gets the 4-wide filter
     * @param mask4Int the blocks whose inner vertical 4x4 edge gets the 4-wide filter
     * @param levels the filter levels
     * @param levelBase the index of the row's first block level
     */
    private void filterVertical(int s, int mask16, int mask8, int mask4, int mask4Int, byte[] levels, int levelBase) {
        var lflIndex = levelBase;
        for (var mask = mask16 | mask8 | mask4 | mask4Int; mask != 0; mask >>>= 1) {
            var level = levels[lflIndex] & 0xFF;
            if ((mask & 1) != 0) {
                if ((mask16 & 1) != 0) {
                    vertical16(s, level, 8);
                } else if ((mask8 & 1) != 0) {
                    vertical8(s, level);
                } else if ((mask4 & 1) != 0) {
                    vertical4(s, level);
                }
            }
            if ((mask4Int & 1) != 0) {
                vertical4(s + 4, level);
            }
            s += 8;
            lflIndex++;
            mask16 >>>= 1;
            mask8 >>>= 1;
            mask4 >>>= 1;
            mask4Int >>>= 1;
        }
    }

    /**
     * Filters the horizontal edges of one row of 8x8 blocks ({@code filter_selectively_horiz}).
     *
     * @param s the index of the row's first sample
     * @param mask16 the blocks whose top edge gets the 16-wide filter
     * @param mask8 the blocks whose top edge gets the 8-wide filter
     * @param mask4 the blocks whose top edge gets the 4-wide filter
     * @param mask4Int the blocks whose inner horizontal 4x4 edge gets the 4-wide filter
     * @param levels the filter levels
     * @param levelBase the index of the row's first block level
     */
    private void filterHorizontal(int s, int mask16, int mask8, int mask4, int mask4Int, byte[] levels,
                                  int levelBase) {
        var lflIndex = levelBase;
        var count = 1;
        for (var mask = mask16 | mask8 | mask4 | mask4Int; mask != 0; mask >>>= count) {
            count = 1;
            if ((mask & 1) != 0) {
                var level = levels[lflIndex] & 0xFF;
                if ((mask16 & 1) != 0) {
                    if ((mask16 & 3) == 3) {
                        horizontal16(s, level, 2);
                        count = 2;
                    } else {
                        horizontal16(s, level, 1);
                    }
                } else if ((mask8 & 1) != 0) {
                    if ((mask8 & 3) == 3) {
                        var next = levels[lflIndex + 1] & 0xFF;
                        horizontal8Dual(s, level, next);
                        if ((mask4Int & 3) == 3) {
                            horizontal4Dual(s + 4 * pitch, level, next);
                        } else if ((mask4Int & 1) != 0) {
                            horizontal4(s + 4 * pitch, level);
                        } else if ((mask4Int & 2) != 0) {
                            horizontal4(s + 8 + 4 * pitch, next);
                        }
                        count = 2;
                    } else {
                        horizontal8(s, level);
                        if ((mask4Int & 1) != 0) {
                            horizontal4(s + 4 * pitch, level);
                        }
                    }
                } else if ((mask4 & 1) != 0) {
                    if ((mask4 & 3) == 3) {
                        var next = levels[lflIndex + 1] & 0xFF;
                        horizontal4Dual(s, level, next);
                        if ((mask4Int & 3) == 3) {
                            horizontal4Dual(s + 4 * pitch, level, next);
                        } else if ((mask4Int & 1) != 0) {
                            horizontal4(s + 4 * pitch, level);
                        } else if ((mask4Int & 2) != 0) {
                            horizontal4(s + 8 + 4 * pitch, next);
                        }
                        count = 2;
                    } else {
                        horizontal4(s, level);
                        if ((mask4Int & 1) != 0) {
                            horizontal4(s + 4 * pitch, level);
                        }
                    }
                } else {
                    horizontal4(s + 4 * pitch, level);
                }
            }
            s += 8 * count;
            lflIndex += count;
            mask16 >>>= count;
            mask8 >>>= count;
            mask4 >>>= count;
            mask4Int >>>= count;
        }
    }

    /**
     * Applies the 16-wide filter across a vertical edge.
     *
     * @param s the index of the first sample right of the edge
     * @param level the filter level
     * @param rows the number of rows, 8 or 16
     */
    private void vertical16(int s, int level, int rows) {
        if (s16 != null) {
            Vp9HighDsp.lpf16(s16, s, 1, pitch, rows, lf.mblim[level], lf.limit[level], lf.hevThr[level], bitDepth);
        } else {
            dsp.lpfVertical16(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], rows, scratch);
        }
    }

    /**
     * Applies the 8-wide filter across a vertical edge of 8 rows.
     *
     * @param s the index of the first sample right of the edge
     * @param level the filter level
     */
    private void vertical8(int s, int level) {
        if (s16 != null) {
            Vp9HighDsp.lpf8(s16, s, 1, pitch, 8, lf.mblim[level], lf.limit[level], lf.hevThr[level], bitDepth);
        } else {
            dsp.lpfVertical8(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], scratch);
        }
    }

    /**
     * Applies the 8-wide filter across a vertical edge of two 8-row units.
     *
     * @param s the index of the first sample right of the edge
     * @param level0 the filter level of the first unit
     * @param level1 the filter level of the second unit
     */
    private void vertical8Dual(int s, int level0, int level1) {
        if (s16 != null) {
            vertical8(s, level0);
            vertical8(s + 8 * pitch, level1);
        } else {
            dsp.lpfVertical8Dual(s8, s, pitch, lf.mblim[level0], lf.limit[level0], lf.hevThr[level0],
                    lf.mblim[level1], lf.limit[level1], lf.hevThr[level1], scratch);
        }
    }

    /**
     * Applies the 4-wide filter across a vertical edge of 8 rows.
     *
     * @param s the index of the first sample right of the edge
     * @param level the filter level
     */
    private void vertical4(int s, int level) {
        if (s16 != null) {
            Vp9HighDsp.lpf4(s16, s, 1, pitch, 8, lf.mblim[level], lf.limit[level], lf.hevThr[level], bitDepth);
        } else {
            dsp.lpfVertical4(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], scratch);
        }
    }

    /**
     * Applies the 4-wide filter across a vertical edge of two 8-row units.
     *
     * @param s the index of the first sample right of the edge
     * @param level0 the filter level of the first unit
     * @param level1 the filter level of the second unit
     */
    private void vertical4Dual(int s, int level0, int level1) {
        if (s16 != null) {
            vertical4(s, level0);
            vertical4(s + 8 * pitch, level1);
        } else {
            dsp.lpfVertical4Dual(s8, s, pitch, lf.mblim[level0], lf.limit[level0], lf.hevThr[level0],
                    lf.mblim[level1], lf.limit[level1], lf.hevThr[level1], scratch);
        }
    }

    /**
     * Applies the 16-wide filter across a horizontal edge.
     *
     * @param s the index of the first sample below the edge
     * @param level the filter level
     * @param count the number of 8-column units, 1 or 2
     */
    private void horizontal16(int s, int level, int count) {
        if (s16 != null) {
            Vp9HighDsp.lpf16(s16, s, pitch, 1, 8 * count, lf.mblim[level], lf.limit[level], lf.hevThr[level],
                    bitDepth);
        } else {
            dsp.lpfHorizontal16(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], count, scratch);
        }
    }

    /**
     * Applies the 8-wide filter across a horizontal edge of 8 columns.
     *
     * @param s the index of the first sample below the edge
     * @param level the filter level
     */
    private void horizontal8(int s, int level) {
        if (s16 != null) {
            Vp9HighDsp.lpf8(s16, s, pitch, 1, 8, lf.mblim[level], lf.limit[level], lf.hevThr[level], bitDepth);
        } else {
            dsp.lpfHorizontal8(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], scratch);
        }
    }

    /**
     * Applies the 8-wide filter across a horizontal edge of two 8-column units.
     *
     * @param s the index of the first sample below the edge
     * @param level0 the filter level of the first unit
     * @param level1 the filter level of the second unit
     */
    private void horizontal8Dual(int s, int level0, int level1) {
        if (s16 != null) {
            horizontal8(s, level0);
            horizontal8(s + 8, level1);
        } else {
            dsp.lpfHorizontal8Dual(s8, s, pitch, lf.mblim[level0], lf.limit[level0], lf.hevThr[level0],
                    lf.mblim[level1], lf.limit[level1], lf.hevThr[level1], scratch);
        }
    }

    /**
     * Applies the 4-wide filter across a horizontal edge of 8 columns.
     *
     * @param s the index of the first sample below the edge
     * @param level the filter level
     */
    private void horizontal4(int s, int level) {
        if (s16 != null) {
            Vp9HighDsp.lpf4(s16, s, pitch, 1, 8, lf.mblim[level], lf.limit[level], lf.hevThr[level], bitDepth);
        } else {
            dsp.lpfHorizontal4(s8, s, pitch, lf.mblim[level], lf.limit[level], lf.hevThr[level], scratch);
        }
    }

    /**
     * Applies the 4-wide filter across a horizontal edge of two 8-column units.
     *
     * @param s the index of the first sample below the edge
     * @param level0 the filter level of the first unit
     * @param level1 the filter level of the second unit
     */
    private void horizontal4Dual(int s, int level0, int level1) {
        if (s16 != null) {
            horizontal4(s, level0);
            horizontal4(s + 8, level1);
        } else {
            dsp.lpfHorizontal4Dual(s8, s, pitch, lf.mblim[level0], lf.limit[level0], lf.hevThr[level0],
                    lf.mblim[level1], lf.limit[level1], lf.hevThr[level1], scratch);
        }
    }
}
