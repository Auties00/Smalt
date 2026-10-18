/* Ports libvpx (vp9/decoder/vp9_decodeframe.c, vp9_detokenize.c, vp9/common/vp9_reconintra.c). Copyright (c) 2010,
   The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_INVALID;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DCT_DCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_HORZ;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_NONE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_VERT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;

/**
 * Decodes the tiles of one tile column of a VP9 frame: the partition trees, the mode
 * information and coefficient tokens of every block, and the reconstruction of the block's
 * samples by prediction and inverse transform.
 *
 * <p>A tile decoder belongs to one thread at a time. The tile columns of a frame are
 * independent, so decoders of different columns of the same frame run concurrently: each
 * writes only the mode information, contexts and samples of its own columns and reads only
 * those plus the frame-wide state its {@link Vp9FrameDecoder} prepared before the tiles. The
 * symbol counts of the column accumulate in {@link #counts}.
 *
 * <p>A decoder with a {@link #queue} only parses: it reads the mode information and the
 * coefficient tokens and hands each superblock row to {@link #recon}, another instance that
 * reconstructs the rows on its own thread while this one parses ahead.
 *
 * @implNote This implementation follows libvpx's {@code vp9/decoder/vp9_decodeframe.c}
 * ({@code decode_partition}, {@code decode_block}, {@code dec_build_inter_predictors}),
 * {@code vp9_detokenize.c} and {@code vp9/common/vp9_reconintra.c}.
 */
final class Vp9TileDecoder {
    /**
     * The width and height, in samples, of the border extension buffer of motion compensation.
     */
    private static final int MC_BUFFER_SIZE = 160;

    /**
     * The coefficient token tree below {@code ONE_TOKEN} ({@code vp9_coef_con_tree}): leaf
     * {@code -t} is token {@code t}, from 2 ({@code TWO_TOKEN}) to 10 ({@code CATEGORY6_TOKEN}).
     */
    private static final int[] TOKEN_TREE = {2, 6, -2, 4, -3, -4, 8, 10, -5, -6, 12, 14, -7, -8, -9, -10};


    /**
     * The extra bit probabilities of the category tokens, one table after another: categories 1 to
     * 5 ({@code vp9_cat1_prob} to {@code vp9_cat5_prob}), then category 6 of 12-bit video
     * ({@code vp9_cat6_prob_high12}), of which 10-bit and 8-bit video skip the first 2 and 4; indexed
     * by {@link Vp9Constants#EXTRA_START}.
     */
    private static final int[] EXTRA_PROBS = {159, 165, 145, 173, 148, 140, 176, 155, 140, 135, 180, 157, 141, 134,
            130, 255, 255, 255, 255, 254, 254, 254, 252, 249, 243, 230, 196, 177, 153, 140, 133, 130, 129};


    /**
     * The index in {@link #EXTRA_PROBS} one past the last extra bit of each token.
     */
    private static final int[] EXTRA_END = {0, 0, 0, 0, 0, 1, 3, 6, 10, 15, 33};

    /**
     * The frame decoder whose frame the tiles belong to.
     */
    private final Vp9FrameDecoder fd;

    /**
     * The pixel kernels.
     */
    private final Vp9Dsp dsp = Vp9Dsp.INSTANCE;

    /**
     * The storage order of the coefficients of the 8-bit kernels for each transform size and
     * type, {@code [txSize * 4 + txType]}: entry {@code i} is where the coefficient at scan
     * position {@code i} goes in the layout of {@link Vp9Dsp#coefficientLayout}.
     */
    private static final short[][] STORAGE_ORDERS = storageOrders(Vp9Dsp.INSTANCE);

    /**
     * The arithmetic decoder of the current tile.
     */
    final Vp9BoolDecoder reader = new Vp9BoolDecoder();

    /**
     * The symbol counts of the tiles this decoder decoded since they were last cleared.
     */
    final Vp9FrameCounts counts = new Vp9FrameCounts();

    /**
     * The wavefront to which the decoder publishes the number of superblock rows of each tile
     * column it has decoded, for loop filter workers running alongside, or {@code null}.
     */
    Vp9Wavefront progress;

    /**
     * The queue that hands the parsed rows to {@link #recon}, or {@code null} when this decoder
     * parses and reconstructs every block itself.
     */
    Vp9ReconQueue queue;

    /**
     * The queue this decoder uses whenever it parses ahead of {@link #recon}, kept between frames.
     */
    Vp9ReconQueue spareQueue;

    /**
     * The decoder that reconstructs the rows this decoder parses into {@link #queue}.
     */
    Vp9TileDecoder recon;

    /**
     * The array that {@link #decodeCoefs} appends the position and value of each coefficient to,
     * or {@code null} to write the coefficients to {@link #coef}.
     */
    private int[] sinkArray;

    /**
     * The index in {@link #sinkArray} of the next position and value.
     */
    private int sinkLength;

    /**
     * The tile column of the current tile.
     */
    private int tileCol;

    /**
     * The reader of the blocks' mode information.
     */
    private final Vp9ModeReader modes;

    /**
     * The first 8x8 unit row of the current tile.
     */
    int miRowStart;

    /**
     * The 8x8 unit row after the current tile.
     */
    int miRowEnd;

    /**
     * The first 8x8 unit column of the current tile.
     */
    int miColStart;

    /**
     * The 8x8 unit column after the current tile.
     */
    int miColEnd;

    /**
     * The nonzero coefficient contexts left of the current superblock, per plane, in 4x4 units.
     */
    final byte[][] leftContext = {new byte[16], new byte[16], new byte[16]};

    /**
     * The partition contexts left of the current superblock, in 8x8 units.
     */
    final byte[] leftSegContext = new byte[8];

    /**
     * The mode information of the current block.
     */
    ModeInfo mi;

    /**
     * The block above the current one, or {@code null} at the top of the frame.
     */
    ModeInfo aboveMi;

    /**
     * The block left of the current one, or {@code null} at the left of the tile.
     */
    ModeInfo leftMi;

    /**
     * The distance from the block's left edge to the frame's left edge, in eighths of a sample,
     * zero or negative.
     */
    int mbToLeftEdge;

    /**
     * The distance from the block's right edge to the frame's right edge, in eighths of a
     * sample, negative when the block overhangs the frame.
     */
    int mbToRightEdge;

    /**
     * The distance from the block's top edge to the frame's top edge, in eighths of a sample.
     */
    int mbToTopEdge;

    /**
     * The distance from the block's bottom edge to the frame's bottom edge, in eighths of a
     * sample.
     */
    int mbToBottomEdge;

    /**
     * The base-2 logarithm of the width, in 4x4 units, of the sub-blocks of an 8x8 block.
     */
    int bmodeBlocksWl;

    /**
     * The base-2 logarithm of the height, in 4x4 units, of the sub-blocks of an 8x8 block.
     */
    int bmodeBlocksHl;

    /**
     * The width of the current block per plane, in 4x4 units.
     */
    private final int[] n4w = new int[3];

    /**
     * The height of the current block per plane, in 4x4 units.
     */
    private final int[] n4h = new int[3];

    /**
     * The base-2 logarithm of {@link #n4w}.
     */
    private final int[] n4wl = new int[3];

    /**
     * The index of the current block's first above context per plane.
     */
    private final int[] aboveCtxOff = new int[3];

    /**
     * The index of the current block's first left context per plane.
     */
    private final int[] leftCtxOff = new int[3];

    /**
     * The index of the current block's top-left sample per plane.
     */
    private final int[] dstOff = new int[3];

    /**
     * The number of 4x4 columns of the current transform plane inside the frame, or 0 when the
     * block does not overhang the right edge.
     */
    private int maxBlocksWide;

    /**
     * The number of 4x4 rows of the current transform plane inside the frame, or 0 when the
     * block does not overhang the bottom edge.
     */
    private int maxBlocksHigh;

    /**
     * The dequantized coefficients of the transform block being decoded, zero between blocks.
     */
    private final int[] coef = new int[32 * 32];

    /**
     * The token energies of the coefficients decoded so far, by raster position.
     */
    private final byte[] tokenCache = new byte[32 * 32];

    /**
     * The scratch of the inverse transforms.
     */
    private final int[] transformScratch = new int[Vp9Dsp.TRANSFORM_SCRATCH];

    /**
     * The scratch of motion compensation.
     */
    private final byte[] convolveScratch = new byte[Vp9Dsp.CONVOLVE_SCRATCH];

    /**
     * The reference block extended past the frame edge, for motion compensation.
     */
    private final byte[] mcBuffer = new byte[MC_BUFFER_SIZE * MC_BUFFER_SIZE + 64];

    /**
     * The predictor of 8-bit intra blocks.
     */
    private final Vp9IntraPredictor intraPredictor = new Vp9IntraPredictor();

    /**
     * The high bit depth reconstruction, created for the first high bit depth frame.
     */
    private Vp9HighRecon high;

    /**
     * Creates a tile decoder for the frames of a frame decoder.
     *
     * @param fd the frame decoder
     */
    Vp9TileDecoder(Vp9FrameDecoder fd) {
        this.fd = fd;
        this.modes = new Vp9ModeReader(fd, this);
    }

    /**
     * Decodes one tile.
     *
     * @param data the packet
     * @param offset the index of the tile's first byte
     * @param size the tile's size in bytes
     * @param tileRow the tile row
     * @param tileCol the tile column
     * @throws MediaException.Malformed if the tile is corrupt
     */
    void decodeTile(byte[] data, int offset, int size, int tileRow, int tileCol) {
        startTile(data, offset, size, tileRow, tileCol);
        var rows = queue;
        for (var miRow = miRowStart; miRow < miRowEnd; miRow += 8) {
            if (rows != null) {
                rows.beginRow();
            }
            decodeRow(miRow);
            if (rows != null) {
                rows.endRow();
            }
        }
    }

    /**
     * Prepares this decoder to reconstruct the rows of a tile that another decoder parses.
     *
     * @param tileRow the tile row
     * @param tileCol the tile column
     */
    void startReconstruction(int tileRow, int tileCol) {
        this.tileCol = tileCol;
        miRowStart = fd.tileOffset(tileRow, fd.miRows, fd.log2TileRows);
        miRowEnd = fd.tileOffset(tileRow + 1, fd.miRows, fd.log2TileRows);
        miColStart = fd.tileOffset(tileCol, fd.miCols, fd.log2TileCols);
        miColEnd = fd.tileOffset(tileCol + 1, fd.miCols, fd.log2TileCols);
        if (fd.curFrame.highBitDepth() && high == null) {
            high = new Vp9HighRecon();
        }
    }

    /**
     * Reconstructs the tile rows that a queue hands over, one superblock row at a time, publishing
     * each to the progress wavefront if the decoder has one.
     *
     * @param rows the queue the parsing decoder fills
     */
    void reconstructTile(Vp9ReconQueue rows) {
        for (var miRow = miRowStart; miRow < miRowEnd; miRow += 8) {
            var slab = rows.awaitRow();
            reconstructRow(rows.blocks(slab), rows.blockLength(slab), rows.coefficients(slab));
            rows.endReconstruction();
            if (progress != null) {
                progress.publish(tileCol, (miRow >> 3) + 1);
            }
        }
    }

    /**
     * Starts decoding a tile: sets its bounds and initializes the arithmetic decoder on its data.
     *
     * @param data the packet
     * @param offset the index of the tile's first byte
     * @param size the tile size in bytes
     * @param tileRow the tile row
     * @param tileCol the tile column
     * @throws MediaException.Malformed if the tile is empty or its marker bit is set
     */
    void startTile(byte[] data, int offset, int size, int tileRow, int tileCol) {
        this.tileCol = tileCol;
        miRowStart = fd.tileOffset(tileRow, fd.miRows, fd.log2TileRows);
        miRowEnd = fd.tileOffset(tileRow + 1, fd.miRows, fd.log2TileRows);
        miColStart = fd.tileOffset(tileCol, fd.miCols, fd.log2TileCols);
        miColEnd = fd.tileOffset(tileCol + 1, fd.miCols, fd.log2TileCols);
        if (size <= 0) {
            throw new MediaException.Malformed("truncated packet or corrupt vp9 tile length");
        }
        if (reader.init(data, offset, size)) {
            throw new MediaException.Malformed("corrupt vp9 tile marker bit");
        }
        if (fd.curFrame.highBitDepth() && high == null) {
            high = new Vp9HighRecon();
        }
    }

    /**
     * Decodes one superblock row of the current tile, publishing it to the progress wavefront if
     * the decoder has one.
     *
     * @param miRow the row's first unit row
     * @throws MediaException.Malformed if the tile data is corrupt
     */
    void decodeRow(int miRow) {
        for (var plane = 0; plane < 3; plane++) {
            Arrays.fill(leftContext[plane], (byte) 0);
        }
        Arrays.fill(leftSegContext, (byte) 0);
        for (var miCol = miColStart; miCol < miColEnd; miCol += 8) {
            decodePartition(miRow, miCol, BLOCK_64X64, 4);
        }
        if (reader.hasError()) {
            throw new MediaException.Malformed("corrupt vp9 tile data");
        }
        if (progress != null) {
            progress.publish(tileCol, (miRow >> 3) + 1);
        }
    }

    /**
     * Decodes the partition tree of a square block ({@code decode_partition}).
     *
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param bsize the block size
     * @param n4x4l2 the base-2 logarithm of the block's width in 4x4 units
     */
    private void decodePartition(int miRow, int miCol, int bsize, int n4x4l2) {
        var n8x8l2 = n4x4l2 - 1;
        var num8x8 = 1 << n8x8l2;
        var hbs = num8x8 >> 1;
        if (miRow >= fd.miRows || miCol >= fd.miCols) {
            return;
        }
        var hasRows = miRow + hbs < fd.miRows;
        var hasCols = miCol + hbs < fd.miCols;
        var partition = readPartition(miRow, miCol, hasRows, hasCols, n8x8l2);
        var subsize = Vp9Constants.SUBSIZE[partition * 13 + bsize];
        if (hbs == 0) {
            bmodeBlocksWl = (partition & PARTITION_VERT) != 0 ? 0 : 1;
            bmodeBlocksHl = (partition & PARTITION_HORZ) != 0 ? 0 : 1;
            decodeBlock(miRow, miCol, subsize, 1, 1);
        } else {
            switch (partition) {
                case PARTITION_NONE -> decodeBlock(miRow, miCol, subsize, n4x4l2, n4x4l2);
                case PARTITION_HORZ -> {
                    decodeBlock(miRow, miCol, subsize, n4x4l2, n8x8l2);
                    if (hasRows) {
                        decodeBlock(miRow + hbs, miCol, subsize, n4x4l2, n8x8l2);
                    }
                }
                case PARTITION_VERT -> {
                    decodeBlock(miRow, miCol, subsize, n8x8l2, n4x4l2);
                    if (hasCols) {
                        decodeBlock(miRow, miCol + hbs, subsize, n8x8l2, n4x4l2);
                    }
                }
                default -> {
                    decodePartition(miRow, miCol, subsize, n8x8l2);
                    decodePartition(miRow, miCol + hbs, subsize, n8x8l2);
                    decodePartition(miRow + hbs, miCol, subsize, n8x8l2);
                    decodePartition(miRow + hbs, miCol + hbs, subsize, n8x8l2);
                }
            }
        }
        if (bsize >= BLOCK_8X8 && (bsize == BLOCK_8X8 || partition != PARTITION_SPLIT)) {
            var above = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2];
            var left = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2 + 1];
            var aboveContext = fd.aboveSegContext;
            var leftStart = miRow & 7;
            for (var i = 0; i < num8x8; i++) {
                aboveContext[miCol + i] = above;
                leftSegContext[leftStart + i] = left;
            }
        }
    }

    /**
     * Reads the partition type of a block ({@code read_partition}).
     *
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param hasRows whether the bottom half of the block starts inside the frame
     * @param hasCols whether the right half of the block starts inside the frame
     * @param bsl the base-2 logarithm of the block's width in 8x8 units
     * @return the partition type
     */
    private int readPartition(int miRow, int miCol, boolean hasRows, boolean hasCols, int bsl) {
        var above = (fd.aboveSegContext[miCol] >> bsl) & 1;
        var left = (leftSegContext[miRow & 7] >> bsl) & 1;
        var ctx = left * 2 + above + bsl * 4;
        int[] probs;
        int base;
        if (fd.intraOnlyFrame()) {
            probs = Vp9ProbTables.KF_PARTITION_PROBS;
            base = ctx * 3;
        } else {
            probs = fd.fc.probs;
            base = Vp9FrameContext.PARTITION + ctx * 3;
        }
        int partition;
        if (hasRows && hasCols) {
            partition = reader.readTree(Vp9Trees.PARTITION, probs, base);
        } else if (!hasRows && hasCols) {
            partition = reader.readBool(probs[base + 1]) != 0 ? PARTITION_SPLIT : PARTITION_HORZ;
        } else if (hasRows) {
            partition = reader.readBool(probs[base + 2]) != 0 ? PARTITION_SPLIT : PARTITION_VERT;
        } else {
            partition = PARTITION_SPLIT;
        }
        counts.counts[Vp9FrameCounts.PARTITION + ctx * 4 + partition]++;
        return partition;
    }

    /**
     * Points the decoder at a block: records it as the current block and derives its per-plane
     * sizes, context offsets, sample offsets, distances to the frame edges and neighbors.
     *
     * @param block the block's mode information
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param bwl the base-2 logarithm of the block's width in 4x4 units, at least 1
     * @param bhl the base-2 logarithm of the block's height in 4x4 units, at least 1
     */
    private void bindBlock(ModeInfo block, int miRow, int miCol, int bwl, int bhl) {
        var bw = 1 << (bwl - 1);
        var bh = 1 << (bhl - 1);
        var miCols = fd.miCols;
        var offset = miRow * miCols + miCol;
        var grid = fd.miGrid;
        mi = block;
        var ssX = fd.ssX;
        var ssY = fd.ssY;
        var frame = fd.curFrame;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : ssX;
            var sy = plane == 0 ? 0 : ssY;
            n4w[plane] = (bw << 1) >> sx;
            n4h[plane] = (bh << 1) >> sy;
            n4wl[plane] = bwl - sx;
            aboveCtxOff[plane] = (miCol * 2) >> sx;
            leftCtxOff[plane] = ((miRow * 2) & 15) >> sy;
            dstOff[plane] = ((miRow * 8) >> sy) * frame.strides[plane] + ((miCol * 8) >> sx);
        }
        mbToTopEdge = -((miRow * 8) * 8);
        mbToBottomEdge = ((fd.miRows - bh - miRow) * 8) * 8;
        mbToLeftEdge = -((miCol * 8) * 8);
        mbToRightEdge = ((miCols - bw - miCol) * 8) * 8;
        aboveMi = miRow != 0 ? grid[offset - miCols] : null;
        leftMi = miCol > miColStart ? grid[offset - 1] : null;
    }

    /**
     * Decodes one block: its mode information, its prediction and its residual
     * ({@code decode_block}).
     *
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param bsize the block size
     * @param bwl the base-2 logarithm of the block's width in 4x4 units, at least 1
     * @param bhl the base-2 logarithm of the block's height in 4x4 units, at least 1
     * @throws MediaException.Malformed if the block is invalid
     */
    private void decodeBlock(int miRow, int miCol, int bsize, int bwl, int bhl) {
        var less8x8 = bsize < BLOCK_8X8;
        var bw = 1 << (bwl - 1);
        var bh = 1 << (bhl - 1);
        var miCols = fd.miCols;
        var xMis = Math.min(bw, miCols - miCol);
        var yMis = Math.min(bh, fd.miRows - miRow);
        var offset = miRow * miCols + miCol;
        var block = fd.miPool[offset];
        block.sbType = bsize;
        var grid = fd.miGrid;
        for (var y = 0; y < yMis; y++) {
            var row = offset + y * miCols;
            for (var x = 0; x < xMis; x++) {
                grid[row + x] = block;
            }
        }
        bindBlock(block, miRow, miCol, bwl, bhl);
        if (bsize >= BLOCK_8X8 && (fd.ssX != 0 || fd.ssY != 0)
                && Vp9Constants.SS_SIZE[bsize * 4 + fd.ssX * 2 + fd.ssY] == BLOCK_INVALID) {
            throw new MediaException.Malformed("invalid vp9 block size for the chroma subsampling");
        }
        modes.read(miRow, miCol, xMis, yMis);
        if (block.skip != 0) {
            resetSkipContext();
        }
        if (queue != null) {
            parseResidual(block, miRow, miCol, bsize, bwl, bhl);
            return;
        }
        if (!block.isInter()) {
            for (var plane = 0; plane < 3; plane++) {
                var txSize = plane == 0 ? block.txSize : uvTxSize(block);
                var step = 1 << txSize;
                var wide = visibleWidth(plane);
                var highRows = visibleHeight(plane);
                for (var row = 0; row < highRows; row += step) {
                    for (var col = 0; col < wide; col += step) {
                        predictAndReconstructIntra(plane, row, col, txSize);
                    }
                }
            }
        } else {
            buildInterPredictors(miRow, miCol);
            if (block.skip == 0) {
                var eobTotal = 0;
                for (var plane = 0; plane < 3; plane++) {
                    var txSize = plane == 0 ? block.txSize : uvTxSize(block);
                    var step = 1 << txSize;
                    var wide = visibleWidth(plane);
                    var highRows = visibleHeight(plane);
                    for (var row = 0; row < highRows; row += step) {
                        for (var col = 0; col < wide; col += step) {
                            eobTotal += reconstructInter(plane, row, col, txSize);
                        }
                    }
                }
                if (!less8x8 && eobTotal == 0) {
                    block.skip = 1;
                }
            }
        }
    }

    /**
     * Returns the intra prediction mode of a transform block.
     *
     * @param block the block being decoded
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param row the transform block's row within the block, in 4x4 units
     * @param col the transform block's column within the block, in 4x4 units
     * @return the mode
     */
    private static int intraMode(ModeInfo block, int plane, int row, int col) {
        if (block.sbType < BLOCK_8X8 && plane == 0) {
            return block.subModes[(row << 1) + col];
        }
        return plane == 0 ? block.mode : block.uvMode;
    }

    /**
     * Decodes the residual tokens of the current block into the queue, in the order
     * {@link #reconstructRow} replays them, and applies the rule that an inter block of at least
     * 8x8 samples without coded coefficients counts as skipped.
     *
     * @param block the block being decoded
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param bsize the block size
     * @param bwl the base-2 logarithm of the block's width in 4x4 units
     * @param bhl the base-2 logarithm of the block's height in 4x4 units
     */
    private void parseResidual(ModeInfo block, int miRow, int miCol, int bsize, int bwl, int bhl) {
        var rows = queue;
        var coded = block.skip == 0;
        rows.block((miRow << 16) | miCol, bsize | (bwl << 8) | (bhl << 16) | (coded ? 1 << 24 : 0));
        if (!coded) {
            return;
        }
        var inter = block.isInter();
        var lossless = fd.lossless;
        var eobTotal = 0;
        for (var plane = 0; plane < 3; plane++) {
            var txSize = plane == 0 ? block.txSize : uvTxSize(block);
            var step = 1 << txSize;
            var wide = visibleWidth(plane);
            var highRows = visibleHeight(plane);
            for (var row = 0; row < highRows; row += step) {
                for (var col = 0; col < wide; col += step) {
                    var txType = inter || plane != 0 || lossless ? DCT_DCT
                            : Vp9Constants.MODE_TO_TX_TYPE[intraMode(block, plane, row, col)];
                    var cursor = rows.reserve(2 + 2 * (16 << (txSize << 1)));
                    sinkArray = rows.coefficients();
                    sinkLength = cursor + 2;
                    var eob = decodeBlockTokens(plane, txSize, txType, col, row, block.segmentId);
                    eobTotal += eob;
                    rows.commit(cursor, eob, (sinkLength - cursor - 2) >> 1);
                }
            }
        }
        sinkArray = null;
        if (inter && bsize >= BLOCK_8X8 && eobTotal == 0) {
            block.skip = 1;
        }
    }

    /**
     * Reconstructs the blocks of one superblock row that {@link #parseResidual} queued: predicts
     * each, then adds the residual of each transform block.
     *
     * @param records the block records of the row
     * @param length the number of ints in use in {@code records}
     * @param coefficients the coefficients of the row
     */
    private void reconstructRow(int[] records, int length, int[] coefficients) {
        var cursor = 0;
        var frame = fd.curFrame;
        for (var at = 0; at < length; at += 2) {
            var position = records[at];
            var shape = records[at + 1];
            var miRow = position >>> 16;
            var miCol = position & 0xFFFF;
            var bsize = shape & 0xFF;
            var bwl = (shape >> 8) & 0xFF;
            var bhl = (shape >> 16) & 0xFF;
            var coded = (shape >>> 24) != 0;
            var block = fd.miPool[miRow * fd.miCols + miCol];
            bindBlock(block, miRow, miCol, bwl, bhl);
            var inter = block.isInter();
            if (inter) {
                buildInterPredictors(miRow, miCol);
            }
            for (var plane = 0; plane < 3 && (!inter || coded); plane++) {
                var txSize = plane == 0 ? block.txSize : uvTxSize(block);
                var step = 1 << txSize;
                var wide = visibleWidth(plane);
                var highRows = visibleHeight(plane);
                for (var row = 0; row < highRows; row += step) {
                    for (var col = 0; col < wide; col += step) {
                        var txType = DCT_DCT;
                        if (!inter) {
                            var mode = intraMode(block, plane, row, col);
                            predictIntraBlock(plane, row, col, txSize, mode);
                            if (plane == 0 && !fd.lossless) {
                                txType = Vp9Constants.MODE_TO_TX_TYPE[mode];
                            }
                        }
                        if (!coded) {
                            continue;
                        }
                        var eob = coefficients[cursor++];
                        if (eob > 0) {
                            var count = coefficients[cursor++];
                            for (var k = 0; k < count; k++) {
                                coef[coefficients[cursor]] = coefficients[cursor + 1];
                                cursor += 2;
                            }
                            var dst = dstOff[plane] + 4 * row * frame.strides[plane] + 4 * col;
                            inverseTransform(frame, plane, dst, txSize, txType, eob);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the number of 4x4 columns of the current block inside the frame in a plane, and
     * records it in {@link #maxBlocksWide} when the block overhangs the frame
     * ({@code max_blocks_wide}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @return the number of columns
     */
    private int visibleWidth(int plane) {
        var sx = plane == 0 ? 0 : fd.ssX;
        var wide = n4w[plane] + (mbToRightEdge >= 0 ? 0 : mbToRightEdge >> (5 + sx));
        maxBlocksWide = mbToRightEdge >= 0 ? 0 : wide;
        return wide;
    }

    /**
     * Returns the number of 4x4 rows of the current block inside the frame in a plane, and
     * records it in {@link #maxBlocksHigh} when the block overhangs the frame
     * ({@code max_blocks_high}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @return the number of rows
     */
    private int visibleHeight(int plane) {
        var sy = plane == 0 ? 0 : fd.ssY;
        var high = n4h[plane] + (mbToBottomEdge >= 0 ? 0 : mbToBottomEdge >> (5 + sy));
        maxBlocksHigh = mbToBottomEdge >= 0 ? 0 : high;
        return high;
    }

    /**
     * Returns a value clamped to a range, testing the lower bound first like libvpx's
     * {@code clamp}.
     *
     * @param value the value to clamp
     * @param low the lower bound
     * @param high the upper bound
     * @return the clamped value
     */
    static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    /**
     * Returns the chroma transform size of a block ({@code get_uv_tx_size}).
     *
     * @param block the block being decoded
     * @return the transform size
     */
    private int uvTxSize(ModeInfo block) {
        return Vp9Constants.UV_TX_SIZE[((block.sbType * 4 + block.txSize) * 2 + fd.ssX) * 2 + fd.ssY];
    }

    /**
     * Clears the nonzero contexts the current block covers, for a block without residual
     * ({@code dec_reset_skip_context}).
     */
    private void resetSkipContext() {
        for (var plane = 0; plane < 3; plane++) {
            var above = fd.aboveContext[plane];
            var aboveStart = aboveCtxOff[plane];
            for (int i = 0, n = n4w[plane]; i < n; i++) {
                above[aboveStart + i] = 0;
            }
            var left = leftContext[plane];
            var leftStart = leftCtxOff[plane];
            for (int i = 0, n = n4h[plane]; i < n; i++) {
                left[leftStart + i] = 0;
            }
        }
    }

    /**
     * Predicts one transform block of an intra block and adds its residual
     * ({@code predict_and_reconstruct_intra_block}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param row the transform block's row within the block, in 4x4 units
     * @param col the transform block's column within the block, in 4x4 units
     * @param txSize the transform size
     */
    private void predictAndReconstructIntra(int plane, int row, int col, int txSize) {
        var block = mi;
        var mode = intraMode(block, plane, row, col);
        predictIntraBlock(plane, row, col, txSize, mode);
        if (block.skip == 0) {
            var frame = fd.curFrame;
            var dst = dstOff[plane] + 4 * row * frame.strides[plane] + 4 * col;
            var lossless = fd.lossless;
            var txType = plane != 0 || lossless ? DCT_DCT : Vp9Constants.MODE_TO_TX_TYPE[mode];
            var eob = decodeBlockTokens(plane, txSize, txType, col, row, block.segmentId);
            if (eob > 0) {
                inverseTransform(frame, plane, dst, txSize, txType, eob);
            }
        }
    }

    /**
     * Predicts one transform block of an intra block from its decoded neighbors.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param row the transform block's row within the block, in 4x4 units
     * @param col the transform block's column within the block, in 4x4 units
     * @param txSize the transform size
     * @param mode the intra mode of the transform block
     */
    private void predictIntraBlock(int plane, int row, int col, int txSize, int mode) {
        var frame = fd.curFrame;
        var stride = frame.strides[plane];
        var dst = dstOff[plane] + 4 * row * stride + 4 * col;
        var bw = 1 << n4wl[plane];
        var txw = 1 << txSize;
        var haveTop = row != 0 || aboveMi != null;
        var haveLeft = col != 0 || leftMi != null;
        var haveRight = col + txw < bw;
        if (frame.highBitDepth()) {
            high.predictIntra(this, frame, plane, dst, mode, txSize, haveTop, haveLeft, haveRight, col * 4, row * 4);
        } else {
            predictIntra(frame, plane, dst, mode, txSize, haveTop, haveLeft, haveRight, col * 4, row * 4);
        }
    }

    /**
     * Decodes the residual of one transform block of an inter block and adds it to the
     * prediction ({@code reconstruct_inter_block}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param row the transform block's row within the block, in 4x4 units
     * @param col the transform block's column within the block, in 4x4 units
     * @param txSize the transform size
     * @return the number of coded coefficients
     */
    private int reconstructInter(int plane, int row, int col, int txSize) {
        var eob = decodeBlockTokens(plane, txSize, DCT_DCT, col, row, mi.segmentId);
        if (eob > 0) {
            var frame = fd.curFrame;
            var dst = dstOff[plane] + 4 * row * frame.strides[plane] + 4 * col;
            inverseTransform(frame, plane, dst, txSize, DCT_DCT, eob);
        }
        return eob;
    }

    /**
     * Adds the inverse transform of the decoded coefficients to the prediction and clears the
     * coefficients.
     *
     * @param frame the frame being decoded
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param dst the index of the transform block's top-left sample
     * @param txSize the transform size
     * @param txType the transform type
     * @param eob the number of coded coefficients
     */
    private void inverseTransform(Vp9Frame frame, int plane, int dst, int txSize, int txType, int eob) {
        var stride = frame.strides[plane];
        if (frame.highBitDepth()) {
            var samples = frame.planes16[plane];
            if (fd.lossless) {
                Vp9HighDsp.inverseWhtAdd(eob, coef, 0, samples, dst, stride, frame.bitDepth, transformScratch);
            } else {
                Vp9HighDsp.inverseTransformAdd(txSize, txType, eob, coef, 0, samples, dst, stride, frame.bitDepth,
                        transformScratch);
            }
        } else {
            var samples = frame.planes8[plane];
            if (fd.lossless) {
                dsp.inverseWhtAdd(eob, coef, 0, samples, dst, stride, transformScratch);
            } else {
                dsp.inverseTransformAdd(txSize, txType, eob, coef, 0, samples, dst, stride, transformScratch);
            }
        }
        var order = storageOrder(txSize, txType);
        for (var c = 0; c < eob; c++) {
            coef[order[c]] = 0;
        }
    }

    /**
     * Returns the scan order of a transform size and type.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @return the scan
     */
    private static short[] scanOrder(int txSize, int txType) {
        return switch (txSize) {
            case TX_4X4 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_4X4
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_4X4 : Vp9ScanTables.DEFAULT_SCAN_4X4;
            case 1 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_8X8
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_8X8 : Vp9ScanTables.DEFAULT_SCAN_8X8;
            case 2 -> txType == 1 ? Vp9ScanTables.ROW_SCAN_16X16
                    : txType == 2 ? Vp9ScanTables.COL_SCAN_16X16 : Vp9ScanTables.DEFAULT_SCAN_16X16;
            default -> Vp9ScanTables.DEFAULT_SCAN_32X32;
        };
    }

    /**
     * Returns where the coefficients of a transform block go, scan position by scan position: the
     * layout of the 8-bit kernels, or raster order for high bit depth and lossless frames, whose
     * kernels read raster coefficients.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @return the storage order
     */
    private short[] storageOrder(int txSize, int txType) {
        if (fd.lossless || fd.curFrame.highBitDepth()) {
            return scanOrder(txSize, txType);
        }
        return STORAGE_ORDERS[txSize * 4 + txType];
    }

    /**
     * Builds the storage orders of every transform size and type for a set of 8-bit kernels.
     *
     * @param dsp the kernels
     * @return the orders, {@code [txSize * 4 + txType]}
     */
    private static short[][] storageOrders(Vp9Dsp dsp) {
        var orders = new short[16][];
        for (var txSize = 0; txSize < 4; txSize++) {
            var layout = dsp.coefficientLayout(txSize);
            for (var txType = 0; txType < 4; txType++) {
                var scan = scanOrder(txSize, txType);
                var order = new short[scan.length];
                for (var i = 0; i < scan.length; i++) {
                    order[i] = layout[scan[i]];
                }
                orders[txSize * 4 + txType] = order;
            }
        }
        return orders;
    }

    /**
     * Returns the context neighbors of a transform size and type's scan order.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @return the neighbors
     */
    private static short[] neighbors(int txSize, int txType) {
        return switch (txSize) {
            case TX_4X4 -> txType == 1 ? Vp9NeighborTables.ROW_4X4
                    : txType == 2 ? Vp9NeighborTables.COL_4X4 : Vp9NeighborTables.DEFAULT_4X4;
            case 1 -> txType == 1 ? Vp9NeighborTables.ROW_8X8
                    : txType == 2 ? Vp9NeighborTables.COL_8X8 : Vp9NeighborTables.DEFAULT_8X8;
            case 2 -> txType == 1 ? Vp9NeighborTables.ROW_16X16
                    : txType == 2 ? Vp9NeighborTables.COL_16X16 : Vp9NeighborTables.DEFAULT_16X16;
            default -> Vp9NeighborTables.DEFAULT_32X32;
        };
    }

    /**
     * Decodes the coefficient tokens of one transform block and updates the nonzero contexts
     * ({@code vp9_decode_block_tokens}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param txSize the transform size
     * @param txType the transform type, which selects the scan order
     * @param x the transform block's column within the block, in 4x4 units
     * @param y the transform block's row within the block, in 4x4 units
     * @param segmentId the block's segment
     * @return the number of coded coefficients
     */
    private int decodeBlockTokens(int plane, int txSize, int txType, int x, int y, int segmentId) {
        var above = fd.aboveContext[plane];
        var left = leftContext[plane];
        var aOff = aboveCtxOff[plane] + x;
        var lOff = leftCtxOff[plane] + y;
        var size = 1 << txSize;
        var aboveNonzero = 0;
        var leftNonzero = 0;
        for (var i = 0; i < size; i++) {
            aboveNonzero |= above[aOff + i];
            leftNonzero |= left[lOff + i];
        }
        var ctx = (aboveNonzero != 0 ? 1 : 0) + (leftNonzero != 0 ? 1 : 0);
        var dequant = plane == 0 ? fd.yDequant : fd.uvDequant;
        var eob = decodeCoefs(plane == 0 ? 0 : 1, txSize, ctx, scanOrder(txSize, txType), storageOrder(txSize, txType),
                neighbors(txSize, txType), dequant[segmentId * 2], dequant[segmentId * 2 + 1]);
        var nonzero = (byte) (eob > 0 ? 1 : 0);
        var aboveCount = size;
        var leftCount = size;
        if (txSize != TX_4X4) {
            if (maxBlocksWide != 0 && size + x > maxBlocksWide) {
                aboveCount = maxBlocksWide - x;
            }
            if (maxBlocksHigh != 0 && size + y > maxBlocksHigh) {
                leftCount = maxBlocksHigh - y;
            }
        }
        for (var i = 0; i < size; i++) {
            above[aOff + i] = i < aboveCount ? nonzero : 0;
            left[lOff + i] = i < leftCount ? nonzero : 0;
        }
        return eob;
    }

    /**
     * Decodes the tokens of one transform block into dequantized coefficients
     * ({@code decode_coefs}).
     *
     * @implNote This implementation keeps the arithmetic decoder's window, range and bit count in
     * local variables for the whole block, refilling through the decoder only when the
     * window runs low, so the bits of a block are decoded without a round trip through
     * memory per bit; the decoder's fields are updated before returning. The tokens above
     * {@code ONE_TOKEN} are read with one walk of {@link #TOKEN_TREE} and their extra
     * bits with one loop over {@link #EXTRA_PROBS}.
     * @param type the plane type, 0 for luma and 1 for chroma
     * @param txSize the transform size
     * @param ctx the context of the first token
     * @param scan the scan order
     * @param store where the coefficient of each scan position is stored
     * @param neighbors the scan's context neighbors
     * @param dcQuant the DC quantizer step
     * @param acQuant the AC quantizer step
     * @return the number of coded coefficients
     */
    private int decodeCoefs(int type, int txSize, int ctx, short[] scan, short[] store, short[] neighbors,
                            int dcQuant, int acQuant) {
        var r = reader;
        var probs = fd.fc.probs;
        var c = counts.counts;
        var cache = tokenCache;
        var out = coef;
        var sink = sinkArray;
        var sinkAt = sinkLength;
        var ref = mi.isInter() ? 1 : 0;
        var set = (txSize * 2 + type) * 2 + ref;
        var probBase = Vp9FrameContext.COEF + set * 6 * 6 * 3;
        var countBase = Vp9FrameCounts.COEF + set * 6 * 6 * 4;
        var eobBase = Vp9FrameCounts.EOB_BRANCH + set * 6 * 6;
        var bands = txSize == TX_4X4 ? Vp9ProbTables.BAND_4X4 : Vp9ProbTables.BAND_8X8_PLUS;
        var maxEob = 16 << (txSize << 1);
        var dqShift = txSize == TX_32X32 ? 1 : 0;
        var bitDepth = fd.curFrame.bitDepth;
        var cat6Skip = bitDepth == 12 ? 0 : bitDepth == 10 ? 2 : 4;
        var dqv = dcQuant;
        var pareto = Vp9ProbTables.PARETO;
        var value = r.value;
        var range = r.range;
        var count = r.count;
        var i = 0;
        while (i < maxEob) {
            var unit = bands[i] * 6 + ctx;
            var p = probBase + unit * 3;
            c[eobBase + unit]++;
            var probability = probs[p];
            var split = (range * probability + (256 - probability)) >> 8;
            if (count < 0) {
                r.value = value;
                r.count = count;
                r.fill();
                value = r.value;
                count = r.count;
            }
            if ((int) (value >>> 56) >= split) {
                range -= split;
                value -= (long) split << 56;
            } else {
                range = split;
                var shift = Integer.numberOfLeadingZeros(range) - 24;
                range <<= shift;
                value <<= shift;
                count -= shift;
                c[countBase + unit * 4 + 3]++;
                break;
            }
            var shift = Integer.numberOfLeadingZeros(range) - 24;
            range <<= shift;
            value <<= shift;
            count -= shift;
            while (true) {
                probability = probs[p + 1];
                split = (range * probability + (256 - probability)) >> 8;
                if (count < 0) {
                    r.value = value;
                    r.count = count;
                    r.fill();
                    value = r.value;
                    count = r.count;
                }
                var bit = (int) (value >>> 56) >= split;
                if (bit) {
                    range -= split;
                    value -= (long) split << 56;
                } else {
                    range = split;
                }
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range <<= shift;
                value <<= shift;
                count -= shift;
                if (bit) {
                    break;
                }
                c[countBase + unit * 4]++;
                dqv = acQuant;
                cache[scan[i]] = 0;
                i++;
                if (i >= maxEob) {
                    r.value = value;
                    r.range = range;
                    r.count = count;
                    sinkLength = sinkAt;
                    return i;
                }
                ctx = (1 + cache[neighbors[2 * i]] + cache[neighbors[2 * i + 1]]) >> 1;
                unit = bands[i] * 6 + ctx;
                p = probBase + unit * 3;
            }
            probability = probs[p + 2];
            split = (range * probability + (256 - probability)) >> 8;
            if (count < 0) {
                r.value = value;
                r.count = count;
                r.fill();
                value = r.value;
                count = r.count;
            }
            int token;
            if ((int) (value >>> 56) >= split) {
                range -= split;
                value -= (long) split << 56;
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range <<= shift;
                value <<= shift;
                count -= shift;
                c[countBase + unit * 4 + 2]++;
                var model = (probability - 1) * 8;
                var node = 0;
                do {
                    probability = pareto[model + (node >> 1)];
                    split = (range * probability + (256 - probability)) >> 8;
                    if (count < 0) {
                        r.value = value;
                        r.count = count;
                        r.fill();
                        value = r.value;
                        count = r.count;
                    }
                    var bit = 0;
                    if ((int) (value >>> 56) >= split) {
                        range -= split;
                        value -= (long) split << 56;
                        bit = 1;
                    } else {
                        range = split;
                    }
                    shift = Integer.numberOfLeadingZeros(range) - 24;
                    range <<= shift;
                    value <<= shift;
                    count -= shift;
                    node = TOKEN_TREE[node + bit];
                } while (node > 0);
                token = -node;
            } else {
                range = split;
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range <<= shift;
                value <<= shift;
                count -= shift;
                c[countBase + unit * 4 + 1]++;
                token = 1;
            }
            cache[scan[i]] = Vp9Constants.ENERGY_CLASS[token];
            var magnitude = Vp9Constants.TOKEN_BASE[token];
            if (token >= 5) {
                var start = Vp9Constants.EXTRA_START[token];
                var end = EXTRA_END[token];
                if (token == 10) {
                    start += cat6Skip;
                }
                var extra = 0;
                for (var b = start; b < end; b++) {
                    probability = EXTRA_PROBS[b];
                    split = (range * probability + (256 - probability)) >> 8;
                    if (count < 0) {
                        r.value = value;
                        r.count = count;
                        r.fill();
                        value = r.value;
                        count = r.count;
                    }
                    var bit = 0;
                    if ((int) (value >>> 56) >= split) {
                        range -= split;
                        value -= (long) split << 56;
                        bit = 1;
                    } else {
                        range = split;
                    }
                    shift = Integer.numberOfLeadingZeros(range) - 24;
                    range <<= shift;
                    value <<= shift;
                    count -= shift;
                    extra = (extra << 1) | bit;
                }
                magnitude += extra;
            }
            var dequantized = (int) (((long) magnitude * dqv) >> dqShift);
            split = (range + 1) >> 1;
            if (count < 0) {
                r.value = value;
                r.count = count;
                r.fill();
                value = r.value;
                count = r.count;
            }
            if ((int) (value >>> 56) >= split) {
                range -= split;
                value -= (long) split << 56;
                dequantized = -dequantized;
            } else {
                range = split;
            }
            shift = Integer.numberOfLeadingZeros(range) - 24;
            range <<= shift;
            value <<= shift;
            count -= shift;
            if (sink != null) {
                sink[sinkAt] = store[i];
                sink[sinkAt + 1] = dequantized;
                sinkAt += 2;
            } else {
                out[store[i]] = dequantized;
            }
            i++;
            ctx = (1 + cache[neighbors[2 * i]] + cache[neighbors[2 * i + 1]]) >> 1;
            dqv = acQuant;
        }
        r.value = value;
        r.range = range;
        r.count = count;
        sinkLength = sinkAt;
        return i;
    }

    /**
     * Predicts one 8-bit transform block from its decoded neighbors, in place.
     *
     * @param frame the frame being decoded
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param dst the index of the transform block's top-left sample
     * @param mode the intra mode
     * @param txSize the transform size
     * @param haveTop whether the row above is available
     * @param haveLeft whether the column to the left is available
     * @param haveRight whether the samples above and to the right belong to the same block
     * @param x the transform block's horizontal offset within the block, in samples
     * @param y the transform block's vertical offset within the block, in samples
     */
    private void predictIntra(Vp9Frame frame, int plane, int dst, int mode, int txSize, boolean haveTop,
                              boolean haveLeft, boolean haveRight, int x, int y) {
        var ref = frame.planes8[plane];
        var stride = frame.strides[plane];
        var sx = plane == 0 ? 0 : fd.ssX;
        var sy = plane == 0 ? 0 : fd.ssY;
        intraPredictor.predict(ref, stride, ref, dst, stride, dst, mode, txSize, haveTop, haveLeft, haveRight,
                (-mbToLeftEdge >> (3 + sx)) + x, (-mbToTopEdge >> (3 + sy)) + y, frame.planeWidths[plane],
                frame.planeHeights[plane], mbToRightEdge < 0, mbToBottomEdge < 0);
    }

    /**
     * Predicts the current inter block from its references, every plane
     * ({@code dec_build_inter_predictors_sb}).
     *
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @throws MediaException.Malformed if a reference has a size it cannot be predicted from
     */
    private void buildInterPredictors(int miRow, int miCol) {
        var block = mi;
        var miX = miCol * 8;
        var miY = miRow * 8;
        var kernelBase = block.interpFilter * 16 * 8;
        var compound = block.hasSecondRef();
        var frame = fd.curFrame;
        for (var ref = 0; ref < (compound ? 2 : 1); ref++) {
            var refFrameIndex = (ref == 0 ? block.ref0 : block.ref1) - 1;
            var refFrame = fd.activeRefs[refFrameIndex];
            var xScale = fd.refXScale[refFrameIndex];
            var yScale = fd.refYScale[refFrameIndex];
            if (xScale == Vp9Constants.REF_INVALID_SCALE || yScale == Vp9Constants.REF_INVALID_SCALE) {
                throw new MediaException.Malformed("vp9 reference frame has invalid dimensions");
            }
            var scaled = xScale != Vp9Constants.REF_NO_SCALE || yScale != Vp9Constants.REF_NO_SCALE;
            if (block.sbType < BLOCK_8X8) {
                for (var plane = 0; plane < 3; plane++) {
                    var num4w = n4w[plane];
                    var num4h = n4h[plane];
                    var i = 0;
                    for (var y = 0; y < num4h; y++) {
                        for (var x = 0; x < num4w; x++) {
                            var mv = averageSplitMvs(plane, ref, i++);
                            predictInter(frame, refFrame, refFrameIndex, plane, 4 * num4w, 4 * num4h, 4 * x, 4 * y, 4, 4,
                                    miX, miY, kernelBase, mv, scaled, ref);
                        }
                    }
                }
            } else {
                var mv = ref == 0 ? block.mv0 : block.mv1;
                for (var plane = 0; plane < 3; plane++) {
                    var bw = 4 * n4w[plane];
                    var bh = 4 * n4h[plane];
                    predictInter(frame, refFrame, refFrameIndex, plane, bw, bh, 0, 0, bw, bh, miX, miY, kernelBase, mv,
                            scaled, ref);
                }
            }
        }
    }

    /**
     * Returns the motion vector of a sub-8x8 block for a plane, averaging the luma sub-block
     * vectors that a subsampled chroma block covers ({@code average_split_mvs}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param ref the reference, 0 or 1
     * @param block the index of the plane's 4x4 block within the 8x8 block
     * @return the packed motion vector
     */
    private int averageSplitMvs(int plane, int ref, int block) {
        var mvs = ref == 0 ? mi.subMv0 : mi.subMv1;
        var sx = plane == 0 ? 0 : fd.ssX;
        var sy = plane == 0 ? 0 : fd.ssY;
        var index = (sx > 0 ? 2 : 0) | (sy > 0 ? 1 : 0);
        return switch (index) {
            case 0 -> mvs[block];
            case 1 -> averageQ2(mvs[block], mvs[block + 2]);
            case 2 -> averageQ2(mvs[block], mvs[block + 1]);
            default -> {
                var row = (mvs[0] >> 16) + (mvs[1] >> 16) + (mvs[2] >> 16) + (mvs[3] >> 16);
                var col = (short) mvs[0] + (short) mvs[1] + (short) mvs[2] + (short) mvs[3];
                yield ModeInfo.packMv((row < 0 ? row - 2 : row + 2) / 4, (col < 0 ? col - 2 : col + 2) / 4);
            }
        };
    }

    /**
     * Returns the rounded average of two packed motion vectors ({@code mi_mv_pred_q2}).
     *
     * @param a the first vector
     * @param b the second vector
     * @return the packed average
     */
    private static int averageQ2(int a, int b) {
        var row = (a >> 16) + (b >> 16);
        var col = (short) a + (short) b;
        return ModeInfo.packMv((row < 0 ? row - 1 : row + 1) / 2, (col < 0 ? col - 1 : col + 1) / 2);
    }

    /**
     * Returns a reference coordinate scaled to the current frame ({@code scaled_x},
     * {@code scaled_y}).
     *
     * @param value the coordinate
     * @param scale the fixed-point scale factor
     * @return the scaled coordinate
     */
    private static int scaleValue(int value, int scale) {
        return (int) (((long) value * scale) >> 14);
    }

    /**
     * Predicts one block of one plane from one reference, extending the reference edge when the
     * block reads outside it ({@code dec_build_inter_predictors}).
     *
     * @param frame the frame being decoded
     * @param refFrame the reference frame
     * @param refFrameIndex the reference's index among the frame's three references
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bw the width of the whole block in the plane
     * @param bh the height of the whole block in the plane
     * @param x the predicted area's horizontal offset within the block
     * @param y the predicted area's vertical offset within the block
     * @param w the predicted area's width
     * @param h the predicted area's height
     * @param miX the block's luma column
     * @param miY the block's luma row
     * @param kernelBase the index of the block's filter in {@link Vp9Constants#FILTER_KERNELS}
     * @param mv the packed motion vector, in eighths of a luma sample
     * @param scaled whether the reference has a different size
     * @param ref 0 for the first reference, 1 for the second, which averages
     */
    private void predictInter(Vp9Frame frame, Vp9Frame refFrame, int refFrameIndex, int plane, int bw, int bh, int x,
                              int y, int w, int h, int miX, int miY, int kernelBase, int mv, boolean scaled, int ref) {
        var sx = plane == 0 ? 0 : fd.ssX;
        var sy = plane == 0 ? 0 : fd.ssY;
        var frameWidth = refFrame.cropWidths[plane];
        var frameHeight = refFrame.cropHeights[plane];
        var mvRow = mv >> 16;
        var mvCol = (short) mv;
        int x0;
        int y0;
        int x016;
        int y016;
        int scaledRow;
        int scaledCol;
        int xs;
        int ys;
        if (scaled) {
            var xScale = fd.refXScale[refFrameIndex];
            var yScale = fd.refYScale[refFrameIndex];
            var spelLeft = (4 + bw) << 4;
            var spelRight = spelLeft - 16;
            var spelTop = (4 + bh) << 4;
            var spelBottom = spelTop - 16;
            var clampedRow = (short) (mvRow * (1 << (1 - sy)));
            var clampedCol = (short) (mvCol * (1 << (1 - sx)));
            clampedCol = (short) clamp(clampedCol, mbToLeftEdge * (1 << (1 - sx)) - spelLeft,
                    mbToRightEdge * (1 << (1 - sx)) + spelRight);
            clampedRow = (short) clamp(clampedRow, mbToTopEdge * (1 << (1 - sy)) - spelTop,
                    mbToBottomEdge * (1 << (1 - sy)) + spelBottom);
            var xStart = -mbToLeftEdge >> (3 + sx);
            var yStart = -mbToTopEdge >> (3 + sy);
            x016 = scaleValue((xStart + x) << 4, xScale);
            y016 = scaleValue((yStart + y) << 4, yScale);
            x0 = scaleValue(xStart + x, xScale);
            y0 = scaleValue(yStart + y, yScale);
            var xOff = scaleValue((miX + x) << 4, xScale) & 15;
            var yOff = scaleValue((miY + y) << 4, yScale) & 15;
            scaledRow = scaleValue(clampedRow, yScale) + yOff;
            scaledCol = scaleValue(clampedCol, xScale) + xOff;
            xs = fd.refXStep[refFrameIndex];
            ys = fd.refYStep[refFrameIndex];
        } else {
            x0 = (-mbToLeftEdge >> (3 + sx)) + x;
            y0 = (-mbToTopEdge >> (3 + sy)) + y;
            x016 = x0 << 4;
            y016 = y0 << 4;
            scaledRow = mvRow * (1 << (1 - sy));
            scaledCol = mvCol * (1 << (1 - sx));
            xs = 16;
            ys = 16;
        }
        var subX = scaledCol & 15;
        var subY = scaledRow & 15;
        x0 += scaledCol >> 4;
        y0 += scaledRow >> 4;
        x016 += scaledCol;
        y016 += scaledRow;
        var dstStride = frame.strides[plane];
        var dst = dstOff[plane] + y * dstStride + x;
        var refStride = refFrame.strides[plane];
        var checkBorder = scaled || scaledCol != 0 || scaledRow != 0 || (frameWidth & 7) != 0 || (frameHeight & 7) != 0;
        if (frame.highBitDepth()) {
            high.predictInter(this, refFrame.planes16[plane], refStride, frameWidth, frameHeight,
                    frame.planes16[plane], dst, dstStride, x0, y0, x016, y016, subX, subY, xs, ys, w, h, checkBorder,
                    kernelBase, ref != 0, frame.bitDepth);
            return;
        }
        var src = refFrame.planes8[plane];
        var srcOffset = y0 * refStride + x0;
        if (checkBorder) {
            var y1 = ((y016 + (h - 1) * ys) >> 4) + 1;
            var x1 = ((x016 + (w - 1) * xs) >> 4) + 1;
            var xPad = 0;
            var yPad = 0;
            if (subX != 0 || xs != 16) {
                x0 -= 3;
                x1 += 4;
                xPad = 1;
            }
            if (subY != 0 || ys != 16) {
                y0 -= 3;
                y1 += 4;
                yPad = 1;
            }
            if (x0 < 0 || x0 > frameWidth - 1 || x1 < 0 || x1 > frameWidth - 1 || y0 < 0 || y0 > frameHeight - 1
                    || y1 < 0 || y1 > frameHeight - 1) {
                var bW = x1 - x0 + 1;
                var bH = y1 - y0 + 1;
                if (bW > MC_BUFFER_SIZE || bH > MC_BUFFER_SIZE) {
                    throw new MediaException.Malformed("vp9 motion compensation area too large");
                }
                buildMcBorder(src, refStride, mcBuffer, bW, x0, y0, bW, bH, frameWidth, frameHeight);
                var borderOffset = yPad * 3 * bW + xPad * 3;
                dsp.convolve(mcBuffer, borderOffset, bW, frame.planes8[plane], dst, dstStride,
                        Vp9Constants.FILTER_KERNELS, kernelBase, subX, xs, subY, ys, w, h, ref != 0, convolveScratch);
                return;
            }
        }
        dsp.convolve(src, srcOffset, refStride, frame.planes8[plane], dst, dstStride,
                Vp9Constants.FILTER_KERNELS, kernelBase, subX, xs, subY, ys, w, h, ref != 0, convolveScratch);
    }

    /**
     * Copies a reference area into a buffer, replicating the frame's edge samples where the area
     * lies outside the frame ({@code build_mc_border}).
     *
     * @param src the reference plane
     * @param srcStride the reference row stride
     * @param dst the buffer
     * @param dstStride the buffer row stride
     * @param x the area's left column in the reference, possibly negative
     * @param y the area's top row in the reference, possibly negative
     * @param bW the area width
     * @param bH the area height
     * @param w the frame width
     * @param h the frame height
     */
    private static void buildMcBorder(byte[] src, int srcStride, byte[] dst, int dstStride, int x, int y, int bW, int bH,
                                      int w, int h) {
        var refRow = y >= h ? (h - 1) * srcStride : (y > 0 ? y * srcStride : 0);
        var left = Math.min(x < 0 ? -x : 0, bW);
        var right = Math.min(x + bW > w ? x + bW - w : 0, bW);
        var copy = bW - left - right;
        var out = 0;
        for (var row = 0; row < bH; row++) {
            if (left > 0) {
                var edge = src[refRow];
                for (var i = 0; i < left; i++) {
                    dst[out + i] = edge;
                }
            }
            if (copy > 0) {
                System.arraycopy(src, refRow + x + left, dst, out + left, copy);
            }
            if (right > 0) {
                var edge = src[refRow + w - 1];
                var start = out + left + copy;
                for (var i = 0; i < right; i++) {
                    dst[start + i] = edge;
                }
            }
            out += dstStride;
            y++;
            if (y > 0 && y < h) {
                refRow += srcStride;
            }
        }
    }
}
