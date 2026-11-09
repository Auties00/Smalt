/* Ports libvpx 1.17.0 (vp9/encoder/vp9_bitstream.c, vp9_segmentation.c). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_64X64;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARESTMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEWMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_HORZ;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_NONE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_SPLIT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.PARTITION_VERT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;

/**
 * Writes the coded frames of the real-time VP9 encoder: the uncompressed header, the compressed
 * header with its forward probability updates, and the tiles with the modes and tokens of every
 * block.
 *
 * <p>The compressed header adapts the frame context to the frame's symbol counts, writing only
 * the updates that save bits; the tiles are then coded with the updated probabilities. Each
 * tile but the last is preceded by its size in four big-endian bytes.
 *
 * @implNote This implementation ports libvpx's {@code vp9/encoder/vp9_bitstream.c}
 * ({@code vp9_pack_bitstream} and its helpers) and
 * {@code vp9_choose_segmap_coding_method} of {@code vp9_segmentation.c}, for
 * one tile row and single reference prediction.
 */
final class Vp9RtBitstream {
    /**
     * The intra mode token paths ({@code intra_mode_encodings}).
     */
    private static final int[] INTRA_MODE_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.INTRA_MODE, 10);

    /**
     * The inter mode token paths, by mode minus {@code NEARESTMV}.
     */
    private static final int[] INTER_MODE_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.INTER_MODE, 4);

    /**
     * The partition token paths.
     */
    private static final int[] PARTITION_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.PARTITION, 4);

    /**
     * The interpolation filter token paths.
     */
    private static final int[] INTERP_TOKENS = Vp9EncTables.treeTokens(Vp9Trees.SWITCHABLE_INTERP, 3);

    /**
     * The uncompressed header literal of each interpolation filter ({@code filter_to_literal}).
     */
    private static final int[] FILTER_TO_LITERAL = {1, 0, 2, 3};

    /**
     * The encoder.
     */
    private final Vp9RtEncoder enc;

    /**
     * The uncompressed header writer.
     */
    private final Vp9BitWriter header = new Vp9BitWriter();

    /**
     * The compressed header writer.
     */
    private final Vp9BoolEncoder compressed = new Vp9BoolEncoder(4096);

    /**
     * The tile writer.
     */
    private final Vp9BoolEncoder tileWriter = new Vp9BoolEncoder(1 << 16);

    /**
     * The booleans of the tile being written, recorded for {@link #tileWriter} to code on
     * another thread when the encoder has several.
     */
    private final Vp9SymbolRecorder recorder = new Vp9SymbolRecorder(1 << 16);

    /**
     * The number of booleans {@link #recorder} holds, published after every superblock, or
     * {@link Integer#MAX_VALUE} once the tile is recorded.
     */
    private final Vp9Wavefront recorded = new Vp9Wavefront(1, true);

    /**
     * The number of booleans of the recorded tile, valid once {@link #recorded} publishes
     * {@link Integer#MAX_VALUE}.
     */
    private volatile int recordedTotal;

    /**
     * The above partition contexts of the tile being written.
     */
    private byte[] abovePartition = new byte[0];

    /**
     * The worker writers that code the tiles of a frame in parallel, by tile column.
     */
    private Vp9RtBitstream[] tileWorkers = new Vp9RtBitstream[0];

    /**
     * Codes one tile column with its worker writer: the band of {@link #writeTilesInParallel},
     * created once so that coding a frame allocates no band.
     */
    private final ParallelBands.Band tileBand = this::writeWorkerTile;

    /**
     * Runs one stage of {@link #writeTilePipelined}, created once so that coding a frame
     * allocates no band.
     */
    private final ParallelBands.Band pipelineStage = this::runPipelineStage;

    /**
     * The boolean encoder of the tile {@link #writeTilePipelined} codes.
     */
    private Vp9BoolEncoder pipelineWriter;

    /**
     * The tile column {@link #writeTilePipelined} codes.
     */
    private int pipelineTile;

    /**
     * Scratch for the 4-byte size of a coded tile.
     */
    private final byte[] sizeBytes = new byte[4];

    /**
     * Scratch for the segment counts of the frame's segmentation map.
     */
    private final int[] noPredCounts = new int[8];

    /**
     * Scratch for the counts of the segments the temporal prediction misses.
     */
    private final int[] tUnpredCounts = new int[8];

    /**
     * Scratch for the counts of the temporal prediction flags, two per context.
     */
    private final int[] predictorCount = new int[6];

    /**
     * Scratch for the segmentation tree probabilities of spatial coding.
     */
    private final int[] noPredTree = new int[7];

    /**
     * Scratch for the segmentation tree probabilities of temporal coding.
     */
    private final int[] tPredTree = new int[7];

    /**
     * Scratch for the temporal prediction flag probabilities.
     */
    private final int[] tNopredProb = new int[3];

    /**
     * The left partition contexts of the superblock row being written.
     */
    private final byte[] leftPartition = new byte[8];

    /**
     * Scratch branch counts.
     */
    private final long[] branch = new long[64];

    /**
     * Scratch for probability searches.
     */
    private final int[] result = new int[1];

    /**
     * The coefficient probabilities modeled from the frame's counts, three per context.
     */
    private final int[] newCoefProbs = new int[2 * 2 * 6 * 6 * 3];

    /**
     * The coefficient branch counts of the frame, 11 nodes per context.
     */
    private final long[] coefBranch = new long[2 * 2 * 6 * 6 * 22];

    /**
     * The segmentation tree probabilities chosen for the frame.
     */
    final int[] segTreeProbs = new int[7];

    /**
     * The segment prediction probabilities chosen for the frame.
     */
    final int[] segPredProbs = new int[3];

    /**
     * Whether the frame's segmentation map is coded against the previous one.
     */
    boolean segTemporalUpdate;

    /**
     * The token index of the row being written.
     */
    private int tokenPos;

    /**
     * The tokens of the row being written.
     */
    private Vp9RtTokens tokens;

    /**
     * The output buffer.
     */
    private byte[] out = new byte[1 << 16];

    /**
     * The number of bytes in {@link #out}.
     */
    private int outSize;

    /**
     * Creates a writer.
     *
     * @param enc the encoder
     */
    Vp9RtBitstream(Vp9RtEncoder enc) {
        this.enc = enc;
    }

    /**
     * Returns the packet buffer, valid until the next frame.
     *
     * @return the buffer
     */
    byte[] buffer() {
        return out;
    }

    /**
     * Appends bytes to the packet.
     *
     * @param data the bytes
     * @param off the index of the first byte
     * @param len the number of bytes
     */
    private void append(byte[] data, int off, int len) {
        if (outSize + len > out.length) {
            out = Arrays.copyOf(out, Math.max(out.length * 2, outSize + len));
        }
        System.arraycopy(data, off, out, outSize, len);
        outSize += len;
    }

    /**
     * Writes the coded frame ({@code vp9_pack_bitstream}).
     *
     * @return the packet size in bytes
     */
    int pack() {
        outSize = 0;
        header.reset();
        writeUncompressedHeader();
        var sizePosition = header.bitPosition();
        header.writeLiteral(0, 16);
        var compressedSize = writeCompressedHeader();
        header.patch16(sizePosition, compressedSize);
        append(header.buffer(), 0, header.bytesWritten());
        append(compressed.buffer(), 0, compressedSize);
        writeTiles();
        return outSize;
    }

    /**
     * Writes the uncompressed header ({@code write_uncompressed_header}).
     */
    private void writeUncompressedHeader() {
        var w = header;
        var profile = enc.profile();
        w.writeLiteral(2, 2);
        w.writeBit(profile & 1);
        w.writeBit(profile >> 1);
        if (profile == 3) {
            w.writeBit(0);
        }
        w.writeBit(0);
        w.writeBit(enc.keyFrame ? 0 : 1);
        w.writeBit(enc.showFrame ? 1 : 0);
        w.writeBit(enc.errorResilient ? 1 : 0);
        if (enc.keyFrame) {
            w.writeLiteral(0x49, 8);
            w.writeLiteral(0x83, 8);
            w.writeLiteral(0x42, 8);
            writeColorConfig(w, profile);
            w.writeLiteral(enc.width - 1, 16);
            w.writeLiteral(enc.height - 1, 16);
            w.writeBit(0);
        } else {
            if (!enc.showFrame) {
                w.writeBit(0);
            }
            if (!enc.errorResilient) {
                w.writeLiteral(0, 2);
            }
            w.writeLiteral(enc.refreshMask, 8);
            for (var ref = LAST_FRAME; ref <= Vp9Constants.ALTREF_FRAME; ref++) {
                w.writeLiteral(enc.refSlot[ref - 1], 3);
                w.writeBit(enc.signBias[ref]);
            }
            var found = false;
            for (var ref = LAST_FRAME; ref <= Vp9Constants.ALTREF_FRAME; ref++) {
                if (enc.temporalLayers > 1) {
                    found = false;
                } else {
                    var picture = enc.slotPicture(enc.refSlot[ref - 1]);
                    found = picture != null && picture.width == enc.width && picture.height == enc.height;
                }
                w.writeBit(found ? 1 : 0);
                if (found) {
                    break;
                }
            }
            if (!found) {
                w.writeLiteral(enc.width - 1, 16);
                w.writeLiteral(enc.height - 1, 16);
            }
            w.writeBit(0);
            w.writeBit(enc.allowHp ? 1 : 0);
            fixInterpFilter();
            w.writeBit(enc.interpFilter == SWITCHABLE ? 1 : 0);
            if (enc.interpFilter != SWITCHABLE) {
                w.writeLiteral(FILTER_TO_LITERAL[enc.interpFilter], 2);
            }
        }
        if (!enc.errorResilient) {
            w.writeBit(enc.refreshFrameContext ? 1 : 0);
            w.writeBit(1);
        }
        w.writeLiteral(enc.frameContextIdx, 2);
        w.writeLiteral(enc.filterLevel, 6);
        w.writeLiteral(0, 3);
        writeLoopFilterDeltas(w);
        w.writeLiteral(enc.baseQindex, 8);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(0);
        writeSegmentation(w);
        var minLog2 = enc.minLog2TileCols;
        var maxLog2 = enc.maxLog2TileCols;
        for (var ones = enc.log2TileCols - minLog2; ones > 0; ones--) {
            w.writeBit(1);
        }
        if (enc.log2TileCols < maxLog2) {
            w.writeBit(0);
        }
        w.writeBit(0);
    }

    /**
     * Writes the bit depth, color space, color range and chroma subsampling of a key frame
     * ({@code write_bitdepth_colorspace_sampling}).
     *
     * @param w the header writer
     * @param profile the stream's profile
     */
    private void writeColorConfig(Vp9BitWriter w, int profile) {
        if (profile >= 2) {
            w.writeBit(enc.bitDepth == 10 ? 0 : 1);
        }
        w.writeLiteral(enc.colorSpace, 3);
        if (enc.colorSpace != Vp9Constants.CS_SRGB) {
            w.writeBit(enc.fullRange ? 1 : 0);
            if (profile == 1 || profile == 3) {
                w.writeBit(enc.ssX);
                w.writeBit(enc.ssY);
                w.writeBit(0);
            }
        } else {
            w.writeBit(0);
        }
    }

    /**
     * Writes the loop filter delta flags and the deltas that differ from those the decoder
     * holds ({@code encode_loopfilter} after the level and sharpness).
     *
     * @param w the header writer
     */
    private void writeLoopFilterDeltas(Vp9BitWriter w) {
        w.writeBit(1);
        var update = enc.lfDeltaUpdate;
        w.writeBit(update ? 1 : 0);
        if (update) {
            for (var i = 0; i < 4; i++) {
                var delta = enc.lfRefDeltas[i];
                var changed = delta != enc.lfLastRefDeltas[i];
                w.writeBit(changed ? 1 : 0);
                if (changed) {
                    enc.lfLastRefDeltas[i] = delta;
                    w.writeLiteral(Math.abs(delta) & 0x3F, 6);
                    w.writeBit(delta < 0 ? 1 : 0);
                }
            }
            for (var i = 0; i < 2; i++) {
                var delta = enc.lfModeDeltas[i];
                var changed = delta != enc.lfLastModeDeltas[i];
                w.writeBit(changed ? 1 : 0);
                if (changed) {
                    enc.lfLastModeDeltas[i] = delta;
                    w.writeLiteral(Math.abs(delta) & 0x3F, 6);
                    w.writeBit(delta < 0 ? 1 : 0);
                }
            }
        }
    }

    /**
     * Writes the segmentation parameters ({@code encode_segmentation}).
     *
     * @param w the header writer
     */
    private void writeSegmentation(Vp9BitWriter w) {
        w.writeBit(enc.segEnabled ? 1 : 0);
        if (!enc.segEnabled) {
            return;
        }
        w.writeBit(enc.segUpdateMap ? 1 : 0);
        if (enc.segUpdateMap) {
            chooseSegmapCodingMethod();
            for (var i = 0; i < 7; i++) {
                var update = segTreeProbs[i] != 255;
                w.writeBit(update ? 1 : 0);
                if (update) {
                    w.writeLiteral(segTreeProbs[i], 8);
                }
            }
            w.writeBit(segTemporalUpdate ? 1 : 0);
            if (segTemporalUpdate) {
                for (var i = 0; i < 3; i++) {
                    var update = segPredProbs[i] != 255;
                    w.writeBit(update ? 1 : 0);
                    if (update) {
                        w.writeLiteral(segPredProbs[i], 8);
                    }
                }
            }
        }
        w.writeBit(enc.segUpdateData ? 1 : 0);
        if (enc.segUpdateData) {
            w.writeBit(0);
            for (var i = 0; i < 8; i++) {
                for (var j = 0; j < 4; j++) {
                    var active = j == 0 && enc.segAltQEnabled[i];
                    w.writeBit(active ? 1 : 0);
                    if (active) {
                        var data = enc.segAltQ[i];
                        w.writeLiteral(Math.abs(data), 8);
                        w.writeBit(data < 0 ? 1 : 0);
                    }
                }
            }
        }
    }

    /**
     * Sets the frame filter to the only filter the frame's blocks used, when they used one
     * ({@code fix_interp_filter}).
     */
    private void fixInterpFilter() {
        if (enc.interpFilter != SWITCHABLE) {
            return;
        }
        var c = enc.frameCounts.counts;
        var used = 0;
        var only = 0;
        for (var i = 0; i < 3; i++) {
            var count = 0L;
            for (var j = 0; j < 4; j++) {
                count += c[Vp9EncCounts.SWITCHABLE_INTERP + j * 3 + i];
            }
            if (count > 0) {
                used++;
                only = i;
            }
        }
        if (used == 1) {
            enc.interpFilter = only;
        }
    }

    /**
     * Writes the compressed header ({@code write_compressed_header}).
     *
     * @return the size of the compressed header in bytes
     */
    private int writeCompressedHeader() {
        var w = compressed;
        w.start();
        var probs = enc.fc.probs;
        var counts = enc.frameCounts;
        var c = counts.counts;
        writeTxMode(w, probs, c);
        updateCoefProbs(w, probs, c);
        for (var k = 0; k < 3; k++) {
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.SKIP + k, c[Vp9EncCounts.SKIP + k * 2],
                    c[Vp9EncCounts.SKIP + k * 2 + 1], result);
        }
        if (!enc.keyFrame) {
            for (var i = 0; i < 7; i++) {
                interModeUpdate(w, probs, c, i);
            }
            if (enc.interpFilter == SWITCHABLE) {
                for (var j = 0; j < 4; j++) {
                    Vp9Probability.treeUpdate(w, Vp9Trees.SWITCHABLE_INTERP, probs,
                            Vp9FrameContext.SWITCHABLE_INTERP + j * 2, c, Vp9EncCounts.SWITCHABLE_INTERP + j * 3, 3,
                            branch, result);
                }
            }
            for (var i = 0; i < 4; i++) {
                Vp9Probability.condUpdate(w, probs, Vp9FrameContext.INTRA_INTER + i, c[Vp9EncCounts.INTRA_INTER + i * 2],
                        c[Vp9EncCounts.INTRA_INTER + i * 2 + 1], result);
            }
            if (enc.compoundReferenceAllowed()) {
                var compound = enc.referenceMode != Vp9RtEncoder.SINGLE_REFERENCE;
                w.write(compound ? 1 : 0, 128);
                if (compound) {
                    var select = enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT;
                    w.write(select ? 1 : 0, 128);
                    if (select) {
                        for (var i = 0; i < 5; i++) {
                            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.COMP_INTER + i,
                                    c[Vp9EncCounts.COMP_INTER + i * 2], c[Vp9EncCounts.COMP_INTER + i * 2 + 1], result);
                        }
                    }
                }
            }
            for (var i = 0; i < 5; i++) {
                Vp9Probability.condUpdate(w, probs, Vp9FrameContext.SINGLE_REF + i * 2,
                        c[Vp9EncCounts.SINGLE_REF + i * 4], c[Vp9EncCounts.SINGLE_REF + i * 4 + 1], result);
                Vp9Probability.condUpdate(w, probs, Vp9FrameContext.SINGLE_REF + i * 2 + 1,
                        c[Vp9EncCounts.SINGLE_REF + i * 4 + 2], c[Vp9EncCounts.SINGLE_REF + i * 4 + 3], result);
            }
            if (enc.referenceMode != Vp9RtEncoder.SINGLE_REFERENCE) {
                for (var i = 0; i < 5; i++) {
                    Vp9Probability.condUpdate(w, probs, Vp9FrameContext.COMP_REF + i, c[Vp9EncCounts.COMP_REF + i * 2],
                            c[Vp9EncCounts.COMP_REF + i * 2 + 1], result);
                }
            }
            for (var i = 0; i < 4; i++) {
                Vp9Probability.treeUpdate(w, Vp9Trees.INTRA_MODE, probs, Vp9FrameContext.Y_MODE + i * 9, c,
                        Vp9EncCounts.Y_MODE + i * 10, 10, branch, result);
            }
            for (var i = 0; i < 16; i++) {
                Vp9Probability.treeUpdate(w, Vp9Trees.PARTITION, probs, Vp9FrameContext.PARTITION + i * 3, c,
                        Vp9EncCounts.PARTITION + i * 4, 4, branch, result);
            }
            Vp9EncMv.writeProbUpdates(w, probs, counts, enc.allowHp);
        }
        w.stop();
        return w.size();
    }

    /**
     * Writes the conditional updates of one context of the inter mode probabilities, whose
     * counts are indexed by mode minus {@code NEARESTMV} while the tree's symbols follow
     * {@code INTER_OFFSET}.
     *
     * @param w the boolean encoder
     * @param probs the probabilities
     * @param c the counts
     * @param ctx the context
     */
    private void interModeUpdate(Vp9BoolEncoder w, int[] probs, int[] c, int ctx) {
        Vp9Probability.treeUpdate(w, Vp9Trees.INTER_MODE, probs, Vp9FrameContext.INTER_MODE + ctx * 3, c,
                Vp9EncCounts.INTER_MODE + ctx * 4, 4, branch, result);
    }

    /**
     * Writes the transform mode and its probability updates ({@code encode_txfm_probs}), or nothing
     * for a lossless frame, whose transform mode is implied.
     *
     * @param w the boolean encoder
     * @param probs the probabilities
     * @param c the counts
     */
    private void writeTxMode(Vp9BoolEncoder w, int[] probs, int[] c) {
        if (enc.lossless) {
            return;
        }
        var txMode = enc.txMode;
        w.writeLiteral(Math.min(txMode, 3), 2);
        if (txMode >= 3) {
            w.writeBit(txMode == TX_MODE_SELECT ? 1 : 0);
        }
        if (txMode != TX_MODE_SELECT) {
            return;
        }
        for (var i = 0; i < 2; i++) {
            var base = Vp9EncCounts.TX_8X8 + i * 2;
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_8X8 + i, c[base], c[base + 1], result);
        }
        for (var i = 0; i < 2; i++) {
            var base = Vp9EncCounts.TX_16X16 + i * 3;
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_16X16 + i * 2, c[base], c[base + 1] + (long) c[base + 2],
                    result);
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_16X16 + i * 2 + 1, c[base + 1], c[base + 2], result);
        }
        for (var i = 0; i < 2; i++) {
            var base = Vp9EncCounts.TX_32X32 + i * 4;
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_32X32 + i * 3, c[base],
                    (long) c[base + 1] + c[base + 2] + c[base + 3], result);
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_32X32 + i * 3 + 1, c[base + 1],
                    (long) c[base + 2] + c[base + 3], result);
            Vp9Probability.condUpdate(w, probs, Vp9FrameContext.TX_32X32 + i * 3 + 2, c[base + 2], c[base + 3],
                    result);
        }
    }

    /**
     * Writes the coefficient probability updates of every transform size the mode allows
     * ({@code update_coef_probs}).
     *
     * @param w the boolean encoder
     * @param probs the probabilities
     * @param c the counts
     */
    private void updateCoefProbs(Vp9BoolEncoder w, int[] probs, int[] c) {
        var maxTx = Vp9Constants.TX_MODE_TO_BIGGEST[enc.txMode];
        for (var tx = TX_4X4; tx <= maxTx; tx++) {
            if (c[Vp9EncCounts.TX_TOTALS + tx] <= 20 || (tx >= TX_16X16 && enc.txSizeSearchTx8x8)) {
                w.writeBit(0);
                continue;
            }
            buildTreeDistribution(tx, c);
            if (enc.sf.oneLoopReducedCoefUpdates) {
                updateCoefOneLoop(w, probs, tx);
            } else {
                updateCoefTwoLoop(w, probs, tx);
            }
        }
    }

    /**
     * Converts the token counts of a transform size into branch counts and model probabilities
     * ({@code build_tree_distribution}).
     *
     * @param tx the transform size
     * @param c the counts
     */
    private void buildTreeDistribution(int tx, int[] c) {
        var full = fullBranch;
        for (var unit = 0; unit < 2 * 2 * 6 * 6; unit++) {
            var band = (unit / 6) % 6;
            var ctx = unit % 6;
            if (band == 0 && ctx >= 3) {
                continue;
            }
            var set = tx * 2 * 2 * 6 * 6 + unit;
            Vp9Probability.treeBranchCounts(Vp9EncTables.COEF_TREE, c, Vp9EncCounts.COEF + set * 12, full);
            full[1] = c[Vp9EncCounts.EOB_BRANCH + set] - full[0];
            System.arraycopy(full, 0, coefBranch, unit * 22, 22);
            for (var m = 0; m < 3; m++) {
                newCoefProbs[unit * 3 + m] = Vp9EncTables.binaryProb(full[2 * m], full[2 * m + 1]);
            }
        }
    }

    /**
     * Returns the savings of updating one coefficient probability and leaves the new probability
     * in {@link #result}.
     *
     * @param unit the context index within the transform size
     * @param t the node, 0 to 2
     * @param oldP the old probability
     * @return the savings
     */
    private long coefSavings(int unit, int t, int oldP) {
        var newP = newCoefProbs[unit * 3 + t];
        if (t == 2) {
            Arrays.fill(branchModel, 0);
            System.arraycopy(coefBranch, unit * 22, branchModel, 0, 22);
            return Vp9Probability.savingsSearchModel(branchModel, oldP, newP, enc.sf.coeffProbApproxStep, result);
        }
        return Vp9Probability.savingsSearch(coefBranch[unit * 22 + 2 * t], coefBranch[unit * 22 + 2 * t + 1], oldP, newP,
                result);
    }

    /**
     * Scratch branch counts of the full token tree.
     */
    private final long[] fullBranch = new long[22];

    /**
     * The branch counts of the context being searched.
     */
    private final long[] branchModel = new long[22];

    /**
     * Writes the coefficient updates of a transform size after a dry run deciding whether any
     * update pays off ({@code TWO_LOOP}).
     *
     * @param w the boolean encoder
     * @param probs the probabilities
     * @param tx the transform size
     */
    private void updateCoefTwoLoop(Vp9BoolEncoder w, int[] probs, int tx) {
        var base = Vp9FrameContext.COEF + tx * 2 * 2 * 6 * 6 * 3;
        var updCost = Vp9EncTables.costZero(Vp9Probability.DIFF_UPDATE_PROB);
        long savings = 0;
        var updates = 0;
        for (var unit = 0; unit < 144; unit++) {
            if (skipUnit(unit)) {
                continue;
            }
            for (var t = 0; t < 3; t++) {
                var oldP = probs[base + unit * 3 + t];
                var s = coefSavings(unit, t, oldP);
                var u = s > 0 && result[0] != oldP;
                if (u) {
                    savings += s - updCost;
                    updates++;
                } else {
                    savings -= updCost;
                }
            }
        }
        if (updates == 0 || savings < 0) {
            w.writeBit(0);
            return;
        }
        w.writeBit(1);
        for (var unit = 0; unit < 144; unit++) {
            if (skipUnit(unit)) {
                continue;
            }
            for (var t = 0; t < 3; t++) {
                var index = base + unit * 3 + t;
                var oldP = probs[index];
                var s = coefSavings(unit, t, oldP);
                var u = s > 0 && result[0] != oldP;
                w.write(u ? 1 : 0, Vp9Probability.DIFF_UPDATE_PROB);
                if (u) {
                    Vp9Probability.writeUpdate(w, result[0], oldP);
                    probs[index] = result[0];
                }
            }
        }
    }

    /**
     * Writes the coefficient updates of a transform size in one pass, announcing the first
     * update lazily ({@code ONE_LOOP_REDUCED}).
     *
     * @param w the boolean encoder
     * @param probs the probabilities
     * @param tx the transform size
     */
    private void updateCoefOneLoop(Vp9BoolEncoder w, int[] probs, int tx) {
        var base = Vp9FrameContext.COEF + tx * 2 * 2 * 6 * 6 * 3;
        var updates = 0;
        var noUpdatesBeforeFirst = 0;
        for (var unit = 0; unit < 144; unit++) {
            if (skipUnit(unit)) {
                continue;
            }
            for (var t = 0; t < 3; t++) {
                var index = base + unit * 3 + t;
                var oldP = probs[index];
                var s = coefSavings(unit, t, oldP);
                var u = s > 0 && result[0] != oldP;
                if (u) {
                    updates++;
                }
                if (!u && updates == 0) {
                    noUpdatesBeforeFirst++;
                    continue;
                }
                if (u && updates == 1) {
                    w.writeBit(1);
                    for (var v = 0; v < noUpdatesBeforeFirst; v++) {
                        w.write(0, Vp9Probability.DIFF_UPDATE_PROB);
                    }
                }
                w.write(u ? 1 : 0, Vp9Probability.DIFF_UPDATE_PROB);
                if (u) {
                    Vp9Probability.writeUpdate(w, result[0], oldP);
                    probs[index] = result[0];
                }
            }
        }
        if (updates == 0) {
            w.writeBit(0);
        }
    }

    /**
     * Returns whether a context of a transform size does not exist: band 0 has three contexts.
     *
     * @param unit the context index within the transform size
     * @return whether to skip it
     */
    private static boolean skipUnit(int unit) {
        var band = (unit / 6) % 6;
        return band == 0 && unit % 6 >= 3;
    }

    /**
     * Writes every tile of the frame ({@code encode_tiles}).
     */
    private void writeTiles() {
        var tileCols = 1 << enc.log2TileCols;
        var aligned = (enc.miCols + 7) & ~7;
        if (abovePartition.length < aligned) {
            abovePartition = new byte[aligned];
        }
        Arrays.fill(abovePartition, (byte) 0);
        var pipelined = enc.threads > 1 && enc.sbRows > 1;
        if (pipelined && tileCols > 1) {
            writeTilesInParallel(tileCols);
            return;
        }
        for (var tile = 0; tile < tileCols; tile++) {
            var w = tileWriter;
            w.start();
            if (pipelined) {
                writeTilePipelined(w, tile);
            } else {
                writeTile(w, tile, false);
            }
            w.stop();
            var size = w.size();
            if (tile < tileCols - 1) {
                sizeBytes[0] = (byte) (size >>> 24);
                sizeBytes[1] = (byte) (size >>> 16);
                sizeBytes[2] = (byte) (size >>> 8);
                sizeBytes[3] = (byte) size;
                append(sizeBytes, 0, 4);
            }
            append(w.buffer(), 0, size);
        }
    }

    /**
     * Writes every tile of the frame, one tile per band of the module's pool: the tiles' boolean
     * coders are independent, so each band codes its tile with a worker writer that shares this
     * writer's segmentation settings and partition context, and the coded tiles are then appended
     * in tile order.
     *
     * @param tileCols the number of tile columns, above 1
     */
    private void writeTilesInParallel(int tileCols) {
        if (tileWorkers.length < tileCols) {
            var grown = Arrays.copyOf(tileWorkers, tileCols);
            for (var i = tileWorkers.length; i < tileCols; i++) {
                grown[i] = new Vp9RtBitstream(enc);
            }
            tileWorkers = grown;
        }
        var workers = tileWorkers;
        for (var tile = 0; tile < tileCols; tile++) {
            var worker = workers[tile];
            System.arraycopy(segTreeProbs, 0, worker.segTreeProbs, 0, segTreeProbs.length);
            System.arraycopy(segPredProbs, 0, worker.segPredProbs, 0, segPredProbs.length);
            worker.segTemporalUpdate = segTemporalUpdate;
            worker.abovePartition = abovePartition;
        }
        ParallelBands.run(tileCols, tileBand);
        for (var tile = 0; tile < tileCols; tile++) {
            var w = workers[tile].tileWriter;
            var size = w.size();
            if (tile < tileCols - 1) {
                sizeBytes[0] = (byte) (size >>> 24);
                sizeBytes[1] = (byte) (size >>> 16);
                sizeBytes[2] = (byte) (size >>> 8);
                sizeBytes[3] = (byte) size;
                append(sizeBytes, 0, 4);
            }
            append(w.buffer(), 0, size);
        }
    }

    /**
     * Codes one tile column with its worker writer of {@link #tileWorkers}.
     *
     * @param tile the tile column
     */
    private void writeWorkerTile(int tile) {
        var worker = tileWorkers[tile];
        var w = worker.tileWriter;
        w.start();
        worker.writeTile(w, tile, false);
        w.stop();
    }

    /**
     * Writes the blocks of one tile with two threads: the calling thread walks the blocks and
     * records the booleans of their modes in {@link #recorder}, with a mark where each block's
     * tokens and each superblock row start, and another thread of the module's pool codes the
     * recorded booleans with {@code w} as they arrive and writes the tokens at the marks. The
     * coded bytes are those of {@link #writeTile} with {@code w}.
     *
     * @param w the boolean encoder of the tile
     * @param tile the tile column
     */
    private void writeTilePipelined(Vp9BoolEncoder w, int tile) {
        recorder.clear();
        recorded.reset(1);
        recordedTotal = -1;
        pipelineWriter = w;
        pipelineTile = tile;
        ParallelBands.pipeline(2, pipelineStage);
    }

    /**
     * Runs one stage of {@link #writeTilePipelined} for {@link #pipelineTile}: stage 0 records
     * the booleans of the tile's blocks, stage 1 codes them with {@link #pipelineWriter}.
     *
     * @param stage the stage, 0 or 1
     */
    private void runPipelineStage(int stage) {
        var w = pipelineWriter;
        var tile = pipelineTile;
        if (stage == 0) {
            var done = false;
            try {
                writeTile(recorder, tile, true);
                recordedTotal = recorder.recorded();
                recorded.publish(0, Integer.MAX_VALUE);
                done = true;
            } finally {
                if (!done) {
                    recorded.abort();
                }
            }
            return;
        }
        var from = 0;
        var row = -1;
        Vp9RtTokens rowTokens = null;
        var position = 0;
        while (true) {
            var available = recorded.awaitProgress(0, from + 1);
            var to = available == Integer.MAX_VALUE ? recordedTotal : available;
            var symbols = recorder.symbols();
            while (from < to) {
                var mark = w.replay(symbols, from, to);
                if (mark == to) {
                    from = to;
                    break;
                }
                if (symbols[mark] == Vp9SymbolRecorder.TOKENS) {
                    position = packTokens(w, rowTokens, position);
                } else {
                    row++;
                    rowTokens = enc.rowTokens(tile, row);
                    position = 0;
                }
                from = mark + 1;
            }
            if (available == Integer.MAX_VALUE) {
                return;
            }
        }

    }

    /**
     * Writes the blocks of one tile ({@code write_modes}).
     *
     * @param w the boolean encoder
     * @param tile the tile column
     * @param publish whether to publish the number of booleans {@link #recorder} holds after
     * every superblock
     */
    private void writeTile(Vp9BoolEncoder w, int tile, boolean publish) {
        var colStart = enc.tileMiColStart(tile);
        var colEnd = enc.tileMiColStart(tile + 1);
        for (var sbRow = 0; sbRow < enc.sbRows; sbRow++) {
            tokens = enc.rowTokens(tile, sbRow);
            tokenPos = 0;
            if (publish) {
                recorder.mark(Vp9SymbolRecorder.ROW);
            }
            Arrays.fill(leftPartition, (byte) 0);
            for (var col = colStart; col < colEnd; col += 8) {
                writeModesSb(w, sbRow << 3, col, BLOCK_64X64, colStart);
                if (publish) {
                    recorded.publish(0, recorder.recorded());
                }
            }
        }
    }

    /**
     * Writes the partition and blocks of a square region ({@code write_modes_sb}).
     *
     * @param w the boolean encoder
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the region's size
     * @param colStart the tile's first 8x8 column
     */
    private void writeModesSb(Vp9BoolEncoder w, int row, int col, int bsize, int colStart) {
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        if (row >= miRows || col >= miCols) {
            return;
        }
        var bs = Vp9RtCoder.NUM_8X8_WIDE[bsize] >> 1;
        var m = enc.miGrid[row * miCols + col];
        var partition = Vp9RtCoder.partitionOf(bsize, m.sbType);
        writePartition(w, bs, row, col, partition, bsize);
        var subsize = Vp9Constants.SUBSIZE[partition * 13 + bsize];
        if (subsize < BLOCK_8X8) {
            writeModesB(w, row, col, colStart);
        } else {
            switch (partition) {
                case PARTITION_NONE -> writeModesB(w, row, col, colStart);
                case PARTITION_HORZ -> {
                    writeModesB(w, row, col, colStart);
                    if (row + bs < miRows) {
                        writeModesB(w, row + bs, col, colStart);
                    }
                }
                case PARTITION_VERT -> {
                    writeModesB(w, row, col, colStart);
                    if (col + bs < miCols) {
                        writeModesB(w, row, col + bs, colStart);
                    }
                }
                default -> {
                    writeModesSb(w, row, col, subsize, colStart);
                    writeModesSb(w, row, col + bs, subsize, colStart);
                    writeModesSb(w, row + bs, col, subsize, colStart);
                    writeModesSb(w, row + bs, col + bs, subsize, colStart);
                }
            }
        }
        if (bsize >= BLOCK_8X8 && (bsize == BLOCK_8X8 || partition != PARTITION_SPLIT)) {
            var n = Vp9RtCoder.NUM_8X8_WIDE[bsize];
            var above = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2];
            var left = (byte) Vp9Constants.PARTITION_CONTEXT[subsize * 2 + 1];
            for (var i = 0; i < n; i++) {
                abovePartition[col + i] = above;
                leftPartition[(row & 7) + i] = left;
            }
        }
    }

    /**
     * Writes a partition symbol ({@code write_partition}).
     *
     * @param w the boolean encoder
     * @param hbs half the region's width in 8x8 units
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param partition the partition type, from {@code PARTITION_NONE} to {@code PARTITION_SPLIT}
     * @param bsize the region's size
     */
    private void writePartition(Vp9BoolEncoder w, int hbs, int row, int col, int partition, int bsize) {
        var bsl = Vp9RtCoder.MI_WIDTH_LOG2[bsize];
        var above = (abovePartition[col] >> bsl) & 1;
        var left = (leftPartition[row & 7] >> bsl) & 1;
        var ctx = left * 2 + above + bsl * 4;
        int[] probs;
        int base;
        if (enc.keyFrame) {
            probs = Vp9ProbTables.KF_PARTITION_PROBS;
            base = ctx * 3;
        } else {
            probs = enc.fc.probs;
            base = Vp9FrameContext.PARTITION + ctx * 3;
        }
        var hasRows = row + hbs < enc.miRows;
        var hasCols = col + hbs < enc.miCols;
        if (hasRows && hasCols) {
            var token = PARTITION_TOKENS[partition];
            w.writeTree(Vp9Trees.PARTITION, probs, base, token >>> 5, token & 31);
        } else if (!hasRows && hasCols) {
            w.write(partition == PARTITION_SPLIT ? 1 : 0, probs[base + 1]);
        } else if (hasRows) {
            w.write(partition == PARTITION_SPLIT ? 1 : 0, probs[base + 2]);
        }
    }

    /**
     * Writes the modes and tokens of one block ({@code write_modes_b}).
     *
     * @param w the boolean encoder
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param colStart the tile's first 8x8 column
     */
    private void writeModesB(Vp9BoolEncoder w, int row, int col, int colStart) {
        var miCols = enc.miCols;
        var index = row * miCols + col;
        var m = enc.miGrid[index];
        var above = row > 0 ? enc.miGrid[index - miCols] : null;
        var left = col > colStart ? enc.miGrid[index - 1] : null;
        if (enc.keyFrame) {
            writeModesKf(w, m, above, left);
        } else {
            writeInterFrameModes(w, m, above, left, index);
        }
        if (w == recorder) {
            recorder.mark(Vp9SymbolRecorder.TOKENS);
        } else {
            tokenPos = packTokens(w, tokens, tokenPos);
        }
    }

    /**
     * Writes a segment identifier ({@code write_segment_id}).
     *
     * @param w the boolean encoder
     * @param segmentId the segment
     */
    private void writeSegmentId(Vp9BoolEncoder w, int segmentId) {
        if (enc.segEnabled && enc.segUpdateMap) {
            w.writeTree(Vp9Trees.SEGMENT, segTreeProbs, 0, segmentId, 3);
        }
    }

    /**
     * Writes the skip flag of a block.
     *
     * @param w the boolean encoder
     * @param m the block
     * @param above the block above, or {@code null}
     * @param left the block on the left, or {@code null}
     */
    private void writeSkip(Vp9BoolEncoder w, ModeInfo m, ModeInfo above, ModeInfo left) {
        var ctx = (above != null ? above.skip : 0) + (left != null ? left.skip : 0);
        w.write(m.skip, enc.fc.probs[Vp9FrameContext.SKIP + ctx]);
    }

    /**
     * Writes the transform size of a block ({@code write_selected_tx_size}).
     *
     * @param w the boolean encoder
     * @param m the block
     * @param above the block above, or {@code null}
     * @param left the block on the left, or {@code null}
     */
    private void writeTxSize(Vp9BoolEncoder w, ModeInfo m, ModeInfo above, ModeInfo left) {
        var maxTx = Vp9Constants.MAX_TX_SIZE[m.sbType];
        var ctx = Vp9PredContexts.txSize(above, left, maxTx);
        var probs = enc.fc.probs;
        int base;
        switch (maxTx) {
            case TX_8X8 -> base = Vp9FrameContext.TX_8X8 + ctx;
            case TX_16X16 -> base = Vp9FrameContext.TX_16X16 + ctx * 2;
            default -> base = Vp9FrameContext.TX_32X32 + ctx * 3;
        }
        var tx = m.txSize;
        w.write(tx != TX_4X4 ? 1 : 0, probs[base]);
        if (tx != TX_4X4 && maxTx >= TX_16X16) {
            w.write(tx != TX_8X8 ? 1 : 0, probs[base + 1]);
            if (tx != TX_8X8 && maxTx >= Vp9Constants.TX_32X32) {
                w.write(tx != TX_16X16 ? 1 : 0, probs[base + 2]);
            }
        }
    }

    /**
     * Writes an intra mode with a probability set.
     *
     * @param w the boolean encoder
     * @param mode the intra mode, from {@code DC_PRED} to {@code TM_PRED}
     * @param probs the probabilities
     * @param base the index of the first probability
     */
    private static void writeIntraMode(Vp9BoolEncoder w, int mode, int[] probs, int base) {
        var token = INTRA_MODE_TOKENS[mode];
        w.writeTree(Vp9Trees.INTRA_MODE, probs, base, token >>> 5, token & 31);
    }

    /**
     * Writes the modes of a key frame block ({@code write_mb_modes_kf}).
     *
     * @param w the boolean encoder
     * @param m the block
     * @param above the block above, or {@code null}
     * @param left the block on the left, or {@code null}
     */
    private void writeModesKf(Vp9BoolEncoder w, ModeInfo m, ModeInfo above, ModeInfo left) {
        if (enc.segUpdateMap) {
            writeSegmentId(w, m.segmentId);
        }
        writeSkip(w, m, above, left);
        if (m.sbType >= BLOCK_8X8 && enc.txMode == TX_MODE_SELECT) {
            writeTxSize(w, m, above, left);
        }
        if (m.sbType >= BLOCK_8X8) {
            var a = above == null || above.isInter() ? 0 : above.yMode(2);
            var l = left == null || left.isInter() ? 0 : left.yMode(1);
            writeIntraMode(w, m.mode, Vp9ProbTables.KF_Y_MODE_PROBS, (a * 10 + l) * 9);
        } else {
            var w4 = Vp9RtCoder.NUM_4X4_WIDE[m.sbType];
            var h4 = Vp9RtCoder.NUM_4X4_HIGH[m.sbType];
            for (var idy = 0; idy < 2; idy += h4) {
                for (var idx = 0; idx < 2; idx += w4) {
                    var block = idy * 2 + idx;
                    var a = aboveBlockMode(m, above, block);
                    var l = leftBlockMode(m, left, block);
                    writeIntraMode(w, m.subModes[block], Vp9ProbTables.KF_Y_MODE_PROBS, (a * 10 + l) * 9);
                }
            }
        }
        writeIntraMode(w, m.uvMode, Vp9ProbTables.KF_UV_MODE_PROBS, m.mode * 9);
    }

    /**
     * Returns the intra mode of the 4x4 block above a sub-block, the context of its mode in a key
     * frame ({@code vp9_above_block_mode}).
     *
     * @param m the block
     * @param above the block above, or {@code null}
     * @param block the sub-block, from 0 to 3
     * @return the mode, {@code DC_PRED} for a missing or inter block
     */
    static int aboveBlockMode(ModeInfo m, ModeInfo above, int block) {
        if (block == 0 || block == 1) {
            return above == null || above.isInter() ? Vp9Constants.DC_PRED : above.yMode(block + 2);
        }
        return m.subModes[block - 2];
    }

    /**
     * Returns the intra mode of the 4x4 block on the left of a sub-block
     * ({@code vp9_left_block_mode}).
     *
     * @param m the block
     * @param left the block on the left, or {@code null}
     * @param block the sub-block, from 0 to 3
     * @return the mode, {@code DC_PRED} for a missing or inter block
     */
    static int leftBlockMode(ModeInfo m, ModeInfo left, int block) {
        if (block == 0 || block == 2) {
            return left == null || left.isInter() ? Vp9Constants.DC_PRED : left.yMode(block + 1);
        }
        return m.subModes[block - 1];
    }

    /**
     * Writes the modes of an inter frame block ({@code pack_inter_mode_mvs}).
     *
     * @param w the boolean encoder
     * @param m the block
     * @param above the block above, or {@code null}
     * @param left the block on the left, or {@code null}
     * @param index the block's 8x8 index
     */
    private void writeInterFrameModes(Vp9BoolEncoder w, ModeInfo m, ModeInfo above, ModeInfo left, int index) {
        var probs = enc.fc.probs;
        if (enc.segEnabled && enc.segUpdateMap) {
            if (segTemporalUpdate) {
                var ctx = (above != null ? above.segIdPredicted : 0) + (left != null ? left.segIdPredicted : 0);
                w.write(m.segIdPredicted, segPredProbs[ctx]);
                if (m.segIdPredicted == 0) {
                    writeSegmentId(w, m.segmentId);
                }
            } else {
                writeSegmentId(w, m.segmentId);
            }
        }
        writeSkip(w, m, above, left);
        var inter = m.isInter();
        w.write(inter ? 1 : 0, probs[Vp9FrameContext.INTRA_INTER + Vp9PredContexts.intraInter(above, left)]);
        if (m.sbType >= BLOCK_8X8 && enc.txMode == TX_MODE_SELECT && !(inter && m.skip != 0)) {
            writeTxSize(w, m, above, left);
        }
        if (!inter) {
            if (m.sbType >= BLOCK_8X8) {
                writeIntraMode(w, m.mode, probs, Vp9FrameContext.Y_MODE + Vp9Constants.SIZE_GROUP[m.sbType] * 9);
            } else {
                var w4 = Vp9RtCoder.NUM_4X4_WIDE[m.sbType];
                var h4 = Vp9RtCoder.NUM_4X4_HIGH[m.sbType];
                for (var idy = 0; idy < 2; idy += h4) {
                    for (var idx = 0; idx < 2; idx += w4) {
                        writeIntraMode(w, m.subModes[idy * 2 + idx], probs, Vp9FrameContext.Y_MODE);
                    }
                }
            }
            writeIntraMode(w, m.uvMode, probs, Vp9FrameContext.UV_MODE + m.mode * 9);
            return;
        }
        var compound = m.hasSecondRef();
        if (enc.referenceMode == Vp9RtEncoder.REFERENCE_MODE_SELECT) {
            w.write(compound ? 1 : 0,
                    probs[Vp9FrameContext.COMP_INTER + Vp9PredContexts.referenceMode(above, left, enc.compFixedRef)]);
        }
        if (compound) {
            var fixedIdx = enc.signBias[enc.compFixedRef];
            var varRef = fixedIdx == 1 ? m.ref0 : m.ref1;
            w.write(varRef == enc.compVarRef1 ? 1 : 0, probs[Vp9FrameContext.COMP_REF + Vp9PredContexts.compRef(above,
                    left, fixedIdx, enc.compVarRef0, enc.compVarRef1, enc.compFixedRef)]);
        } else {
            var bit0 = m.ref0 != LAST_FRAME ? 1 : 0;
            w.write(bit0, probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP1(above, left) * 2]);
            if (bit0 != 0) {
                w.write(m.ref0 != GOLDEN_FRAME ? 1 : 0,
                        probs[Vp9FrameContext.SINGLE_REF + Vp9PredContexts.singleRefP2(above, left) * 2 + 1]);
            }
        }
        var modeCtx = enc.blockModeContext[index];
        if (m.sbType >= BLOCK_8X8) {
            var token = INTER_MODE_TOKENS[m.mode - NEARESTMV];
            w.writeTree(Vp9Trees.INTER_MODE, probs, Vp9FrameContext.INTER_MODE + modeCtx * 3, token >>> 5,
                    token & 31);
        }
        if (enc.interpFilter == SWITCHABLE) {
            var ctx = Vp9PredContexts.switchableInterp(above, left);
            var filterToken = INTERP_TOKENS[m.interpFilter];
            w.writeTree(Vp9Trees.SWITCHABLE_INTERP, probs, Vp9FrameContext.SWITCHABLE_INTERP + ctx * 2,
                    filterToken >>> 5, filterToken & 31);
        }
        var usehp = enc.allowHp;
        if (m.sbType < BLOCK_8X8) {
            var w4 = Vp9RtCoder.NUM_4X4_WIDE[m.sbType];
            var h4 = Vp9RtCoder.NUM_4X4_HIGH[m.sbType];
            for (var idy = 0; idy < 2; idy += h4) {
                for (var idx = 0; idx < 2; idx += w4) {
                    var j = idy * 2 + idx;
                    var bMode = m.subModes[j];
                    var token = INTER_MODE_TOKENS[bMode - NEARESTMV];
                    w.writeTree(Vp9Trees.INTER_MODE, probs, Vp9FrameContext.INTER_MODE + modeCtx * 3, token >>> 5,
                            token & 31);
                    if (bMode == NEWMV) {
                        Vp9EncMv.write(w, probs, m.subMv0[j], enc.blockRefMv[index], usehp);
                        if (compound) {
                            Vp9EncMv.write(w, probs, m.subMv1[j], enc.blockRefMv1[index], usehp);
                        }
                    }
                }
            }
        } else if (m.mode == NEWMV) {
            Vp9EncMv.write(w, probs, m.mv0, enc.blockRefMv[index], usehp);
            if (compound) {
                Vp9EncMv.write(w, probs, m.mv1, enc.blockRefMv1[index], usehp);
            }
        }
    }

    /**
     * Writes the tokens of one block, up to its end marker ({@code pack_mb_tokens}).
     *
     * @param w the boolean encoder
     * @param t the tokens of the block's superblock row
     * @param p the index of the block's first token
     * @return the index of the first token of the next block
     */
    private int packTokens(Vp9BoolEncoder w, Vp9RtTokens t, int p) {
        var probsIdx = t.probs;
        var words = t.words;
        var fc = enc.fc.probs;
        var pareto = Vp9ProbTables.PARETO;
        while (true) {
            var word = words[p];
            var token = word & 15;
            if (token == Vp9EncTables.EOSB_TOKEN) {
                p++;
                break;
            }
            var base = probsIdx[p];
            if (token == Vp9EncTables.EOB_TOKEN) {
                w.write(0, fc[base]);
                p++;
                continue;
            }
            w.write(1, fc[base]);
            while (token == Vp9EncTables.ZERO_TOKEN) {
                w.write(0, fc[base + 1]);
                p++;
                word = words[p];
                token = word & 15;
                if (token == Vp9EncTables.EOSB_TOKEN) {
                    return p + 1;
                }
                base = probsIdx[p];
            }
            var extra = word >>> 4;
            w.write(1, fc[base + 1]);
            if (token == Vp9EncTables.ONE_TOKEN) {
                w.write(0, fc[base + 2]);
                w.writeBit(extra & 1);
            } else {
                var enc2 = Vp9EncTables.COEF_ENCODINGS[token];
                w.write(1, fc[base + 2]);
                w.writeTree(Vp9EncTables.COEF_CON_TREE, pareto, (fc[base + 2] - 1) * 8, enc2 >>> 5, (enc2 & 31) - 3);
                if (token >= Vp9EncTables.CATEGORY1_TOKEN) {
                    var v = extra >> 1;
                    int n;
                    int pb;
                    int[] probs;
                    if (token == Vp9EncTables.CATEGORY6_TOKEN && enc.bitDepth > 8) {
                        n = Vp9EncTables.cat6Bits(enc.bitDepth);
                        probs = Vp9EncTables.CAT6_PROBS_HIGH12;
                        pb = probs.length - n;
                    } else {
                        n = Vp9EncTables.EXTRA_BITS[token];
                        probs = Vp9EncTables.EXTRA_PROBS;
                        pb = Vp9Constants.EXTRA_START[token];
                    }
                    do {
                        var bb = (v >> --n) & 1;
                        w.write(bb, probs[pb++]);
                    } while (n > 0);
                }
                w.writeBit(extra & 1);
            }
            p++;
        }
        return p;
    }

    /**
     * Chooses between spatial and temporal coding of the segmentation map and computes the
     * probabilities of the chosen method ({@code vp9_choose_segmap_coding_method}).
     */
    private void chooseSegmapCodingMethod() {
        Arrays.fill(noPredCounts, 0);
        Arrays.fill(tUnpredCounts, 0);
        Arrays.fill(predictorCount, 0);
        Arrays.fill(segTreeProbs, 255);
        Arrays.fill(segPredProbs, 255);
        var tileCols = 1 << enc.log2TileCols;
        for (var tile = 0; tile < tileCols; tile++) {
            var colStart = enc.tileMiColStart(tile);
            var colEnd = enc.tileMiColStart(tile + 1);
            for (var row = 0; row < enc.miRows; row += 8) {
                for (var col = colStart; col < colEnd; col += 8) {
                    countSegsSb(row, col, BLOCK_64X64, colStart, noPredCounts, predictorCount, tUnpredCounts);
                }
            }
        }
        calcSegtreeProbs(noPredCounts, noPredTree);
        var noPredCost = costSegmap(noPredCounts, noPredTree);
        var tPredCost = Integer.MAX_VALUE;
        if (!enc.keyFrame) {
            calcSegtreeProbs(tUnpredCounts, tPredTree);
            tPredCost = costSegmap(tUnpredCounts, tPredTree);
            for (var i = 0; i < 3; i++) {
                var count0 = predictorCount[i * 2];
                var count1 = predictorCount[i * 2 + 1];
                tNopredProb[i] = Vp9EncTables.binaryProb(count0, count1);
                tPredCost += count0 * Vp9EncTables.costZero(tNopredProb[i])
                        + count1 * Vp9EncTables.costOne(tNopredProb[i]);
            }
        }
        if (tPredCost < noPredCost) {
            segTemporalUpdate = true;
            System.arraycopy(tPredTree, 0, segTreeProbs, 0, 7);
            System.arraycopy(tNopredProb, 0, segPredProbs, 0, 3);
        } else {
            segTemporalUpdate = false;
            System.arraycopy(noPredTree, 0, segTreeProbs, 0, 7);
        }
    }

    /**
     * Counts the segments of the blocks of a region ({@code count_segs_sb}).
     *
     * @param row the region's 8x8 row
     * @param col the region's 8x8 column
     * @param bsize the region's size
     * @param colStart the tile's first 8x8 column
     * @param noPredCounts the segment counts
     * @param predictorCount the prediction flag counts per context
     * @param tUnpredCounts the counts of unpredicted segments
     */
    private void countSegsSb(int row, int col, int bsize, int colStart, int[] noPredCounts, int[] predictorCount,
                             int[] tUnpredCounts) {
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        if (row >= miRows || col >= miCols) {
            return;
        }
        var m = enc.miGrid[row * miCols + col];
        var bs = Vp9RtCoder.NUM_8X8_WIDE[bsize];
        var hbs = bs / 2;
        var bw = Vp9RtCoder.NUM_8X8_WIDE[m.sbType];
        var bh = Vp9RtCoder.NUM_8X8_HIGH[m.sbType];
        if (bw == bs && bh == bs) {
            countSegs(row, col, colStart, noPredCounts, predictorCount, tUnpredCounts);
        } else if (bw == bs && bh < bs) {
            countSegs(row, col, colStart, noPredCounts, predictorCount, tUnpredCounts);
            countSegs(row + hbs, col, colStart, noPredCounts, predictorCount, tUnpredCounts);
        } else if (bw < bs && bh == bs) {
            countSegs(row, col, colStart, noPredCounts, predictorCount, tUnpredCounts);
            countSegs(row, col + hbs, colStart, noPredCounts, predictorCount, tUnpredCounts);
        } else {
            var subsize = Vp9Constants.SUBSIZE[PARTITION_SPLIT * 13 + bsize];
            for (var n = 0; n < 4; n++) {
                countSegsSb(row + hbs * (n >> 1), col + hbs * (n & 1), subsize, colStart, noPredCounts,
                        predictorCount, tUnpredCounts);
            }
        }
    }

    /**
     * Counts the segment of one block and whether the previous map predicts it
     * ({@code count_segs}).
     *
     * @param row the block's 8x8 row
     * @param col the block's 8x8 column
     * @param colStart the tile's first 8x8 column
     * @param noPredCounts the segment counts
     * @param predictorCount the prediction flag counts per context
     * @param tUnpredCounts the counts of unpredicted segments
     */
    private void countSegs(int row, int col, int colStart, int[] noPredCounts, int[] predictorCount,
                           int[] tUnpredCounts) {
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        if (row >= miRows || col >= miCols) {
            return;
        }
        var index = row * miCols + col;
        var m = enc.miGrid[index];
        noPredCounts[m.segmentId]++;
        if (!enc.keyFrame) {
            var predicted = enc.segmentIdOf(enc.lastSegMap, m.sbType, row, col);
            var flag = predicted == m.segmentId ? 1 : 0;
            var above = row > 0 ? enc.miGrid[index - miCols] : null;
            var left = col > colStart ? enc.miGrid[index - 1] : null;
            var ctx = (above != null ? above.segIdPredicted : 0) + (left != null ? left.segIdPredicted : 0);
            m.segIdPredicted = flag;
            predictorCount[ctx * 2 + flag]++;
            if (flag == 0) {
                tUnpredCounts[m.segmentId]++;
            }
        }
    }

    /**
     * Computes the segment tree probabilities of segment counts ({@code calc_segtree_probs}).
     *
     * @param counts the number of blocks of each segment, 8 entries
     * @param probs receives the 7 probabilities
     */
    private static void calcSegtreeProbs(int[] counts, int[] probs) {
        var c01 = counts[0] + counts[1];
        var c23 = counts[2] + counts[3];
        var c45 = counts[4] + counts[5];
        var c67 = counts[6] + counts[7];
        probs[0] = Vp9EncTables.binaryProb(c01 + c23, c45 + c67);
        probs[1] = Vp9EncTables.binaryProb(c01, c23);
        probs[2] = Vp9EncTables.binaryProb(c45, c67);
        probs[3] = Vp9EncTables.binaryProb(counts[0], counts[1]);
        probs[4] = Vp9EncTables.binaryProb(counts[2], counts[3]);
        probs[5] = Vp9EncTables.binaryProb(counts[4], counts[5]);
        probs[6] = Vp9EncTables.binaryProb(counts[6], counts[7]);
    }

    /**
     * Returns the cost of coding segment counts with tree probabilities ({@code cost_segmap}).
     *
     * @param counts the number of blocks of each segment, 8 entries
     * @param probs the probabilities
     * @return the cost
     */
    private static int costSegmap(int[] counts, int[] probs) {
        var c01 = counts[0] + counts[1];
        var c23 = counts[2] + counts[3];
        var c45 = counts[4] + counts[5];
        var c67 = counts[6] + counts[7];
        var c0123 = c01 + c23;
        var c4567 = c45 + c67;
        var cost = c0123 * Vp9EncTables.costZero(probs[0]) + c4567 * Vp9EncTables.costOne(probs[0]);
        if (c0123 > 0) {
            cost += c01 * Vp9EncTables.costZero(probs[1]) + c23 * Vp9EncTables.costOne(probs[1]);
            if (c01 > 0) {
                cost += counts[0] * Vp9EncTables.costZero(probs[3]) + counts[1] * Vp9EncTables.costOne(probs[3]);
            }
            if (c23 > 0) {
                cost += counts[2] * Vp9EncTables.costZero(probs[4]) + counts[3] * Vp9EncTables.costOne(probs[4]);
            }
        }
        if (c4567 > 0) {
            cost += c45 * Vp9EncTables.costZero(probs[2]) + c67 * Vp9EncTables.costOne(probs[2]);
            if (c45 > 0) {
                cost += counts[4] * Vp9EncTables.costZero(probs[5]) + counts[5] * Vp9EncTables.costOne(probs[5]);
            }
            if (c67 > 0) {
                cost += counts[6] * Vp9EncTables.costZero(probs[6]) + counts[7] * Vp9EncTables.costOne(probs[6]);
            }
        }
        return cost;
    }
}
