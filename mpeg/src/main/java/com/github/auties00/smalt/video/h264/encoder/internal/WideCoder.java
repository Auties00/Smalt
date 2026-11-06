package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.internal.H264Tables;
import com.github.auties00.smalt.video.h264.internal.WideIntraPredictor;

import java.util.Arrays;

/**
 * Decides, codes and reconstructs the macroblocks of the extended engine, one at a time in
 * decoding order: the prediction (Intra_4x4, Intra_8x8, Intra_16x16, the chroma intra mode, or
 * one motion vector for the whole macroblock), the residual levels and the reconstruction that
 * later macroblocks and the decoder see.
 *
 * <p>Decisions compare prediction errors measured with the 4x4 Hadamard transform plus the
 * estimated side information weighted by a Lagrange multiplier. The reconstruction follows ITU-T
 * H.264 exactly, so that intra prediction inside a picture reads what a decoder reads; references
 * between pictures come from the caller. Instances are not thread-safe.
 */
final class WideCoder {
    /**
     * The scan position to raster index map of the 4:2:2 chroma DC levels (ITU-T H.264 8.5.11.1).
     */
    static final byte[] CHROMA_DC_422_SCAN = {0, 2, 1, 4, 6, 3, 5, 7};

    /**
     * The scan position to raster index map of the 4:2:0 chroma DC levels.
     */
    static final byte[] CHROMA_DC_420_SCAN = {0, 1, 2, 3};

    /**
     * Whether the samples above-right of each 4x4 luma block, by {@code luma4x4BlkIdx}, lie in the
     * same macroblock and precede the block in decoding order.
     */
    private static final boolean[] TOP_RIGHT_INSIDE = new boolean[16];

    static {
        for (var blk = 0; blk < 16; blk++) {
            var x = H264Tables.BLOCK_X[blk];
            var y = H264Tables.BLOCK_Y[blk];
            TOP_RIGHT_INSIDE[blk] = y > 0 && x < 3
                    && H264Tables.BLOCK_RASTER[(y - 1) * 4 + x + 1] < H264Tables.BLOCK_RASTER[y * 4 + x];
        }
    }

    private final WideSettings settings;

    private final WideTransform transform = new WideTransform();

    private final WideIntraPredictor intra = new WideIntraPredictor();

    /**
     * The largest sample value.
     */
    private final int max;

    /**
     * The number of components coded like luma: 1, or 3 for 4:4:4.
     */
    private final int lumaComponents;

    /**
     * Whether the stream has subsampled chroma (4:2:0 or 4:2:2).
     */
    private final boolean subsampled;

    /**
     * The number of chroma 4x4 blocks per component of 4:2:0 and 4:2:2: 4 or 8.
     */
    private final int chromaBlocks;

    /**
     * Whether the 8x8 transform is enabled.
     */
    private final boolean transform8x8Mode;

    /**
     * The macroblock tables.
     */
    final WideMbTable table;

    /**
     * The source frame of the picture.
     */
    private WideFrame source;

    /**
     * The reconstruction frame of the picture.
     */
    private WideFrame recon;

    /**
     * The coded structure: 0 for a frame, 1 for a top field, 2 for a bottom field.
     */
    private int structure;

    /**
     * Whether the picture is a frame of macroblock pairs.
     */
    private boolean mbaff;

    /**
     * Whether the picture is a P picture.
     */
    private boolean pSlice;

    /**
     * The references of frame macroblocks, or of every macroblock of a field picture, by index.
     */
    private WideReference[] frameRefs = new WideReference[0];

    /**
     * The references of field macroblocks of a frame of macroblock pairs, by the macroblock's
     * parity and index.
     */
    private final WideReference[][] pairFieldRefs = new WideReference[2][];

    /**
     * The intra refresh band of a P picture: its first column and the column after it, both 0
     * without intra refresh.
     */
    private int refreshStart;

    private int refreshEnd;

    /**
     * The decision of the current macroblock.
     */
    final WideMb mb = new WideMb();

    /**
     * A saved candidate.
     */
    private final WideMb saved = new WideMb();

    /**
     * The sample planes of the current macroblock's source.
     */
    private WidePlane[] src;

    /**
     * The sample planes of the current macroblock's reconstruction.
     */
    private WidePlane[] rec;

    /**
     * The references of the current macroblock.
     */
    private WideReference[] refs;

    /**
     * The luma position of the current macroblock in its planes.
     */
    private int x0;

    private int y0;

    /**
     * The index of the current macroblock's top-left sample of each component in {@link #src}
     * and {@link #rec}.
     */
    private final int[] at = new int[3];

    /**
     * The parity of the current macroblock's field, 0 or 1, or -1 for frame macroblocks.
     */
    private int parity;

    /**
     * Whether the current macroblock reads its references as fields.
     */
    private boolean fieldContext;

    /**
     * The availability of the neighbors A, B, C and D of the current macroblock.
     */
    private boolean availA;

    private boolean availB;

    private boolean availC;

    private boolean availD;

    /**
     * The quantizer {@code QPY} of the current macroblock.
     */
    private int qp;

    /**
     * The Lagrange multiplier of prediction errors measured with SATD, times 256.
     */
    private int lambda;

    /**
     * Scratch residual of one 16x16 component.
     */
    private final int[] residual = new int[256];

    /**
     * Scratch coefficients.
     */
    private final int[] coefficients = new int[256];

    /**
     * Scratch DC coefficients.
     */
    private final int[] dcScratch = new int[16];

    /**
     * Scratch space of the Hadamard transform.
     */
    private final int[] hadamard = new int[16];

    /**
     * The horizontal steps to the eight neighbors of a vector.
     */
    private static final int[] SQUARE_X = {-1, -1, -1, 0, 0, 1, 1, 1};

    /**
     * The vertical steps to the eight neighbors of a vector.
     */
    private static final int[] SQUARE_Y = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * The inter prediction of each component of the current macroblock, 16 samples per row.
     */
    private final short[][] prediction = {new short[256], new short[256], new short[256]};

    /**
     * A saved reconstruction of the current macroblock, 256 samples per component.
     */
    private final short[][] savedRecon = {new short[256], new short[256], new short[256]};

    /**
     * Whether the samples above-right of the current macroblock must not be predicted from because
     * intra refresh has not refreshed them.
     */
    private boolean avoidTopRight;

    /**
     * The largest horizontal motion vector intra refresh allows the current macroblock, or
     * {@link Integer#MAX_VALUE}.
     */
    private int refreshLimitX = Integer.MAX_VALUE;

    /**
     * The search range of the integer motion search, in samples.
     */
    private final int searchRange;

    /**
     * Whether Intra_4x4 and Intra_8x8 are tried in P pictures.
     */
    private final boolean intraNxNInter;

    /**
     * The largest magnitude of a level: the CAVLC escape limit of the profiles below High, which
     * allow no {@code level_prefix} above 15, and unlimited otherwise.
     */
    private final int maxLevel;

    /**
     * Constructs a coder.
     *
     * @param settings the stream settings
     */
    WideCoder(WideSettings settings) {
        this.settings = settings;
        this.max = settings.maxSample();
        this.lumaComponents = settings.chroma444() ? 3 : 1;
        this.subsampled = settings.hasChroma() && !settings.chroma444();
        this.chromaBlocks = settings.mbHeightC() == 16 ? 8 : 4;
        this.transform8x8Mode = settings.transform8x8();
        this.table = new WideMbTable(settings.mbWidth(), settings.mbHeight());
        intra.setBitDepths(settings.bitDepth(), settings.bitDepth());
        var speed = settings.config().passRecorder() != null ? H264EncoderSpeed.FASTEST : settings.config().speed();
        this.searchRange = switch (speed) {
            case FASTEST -> 8;
            case FAST -> 12;
            case BALANCED -> 16;
            case SLOW -> 24;
        };
        this.intraNxNInter = speed != H264EncoderSpeed.FASTEST;
        this.maxLevel = !settings.cabac() && settings.profileIdc() < 100 ? CavlcWriter.MAX_LEVEL : Integer.MAX_VALUE;
    }

    /**
     * Prepares the coding of a picture.
     *
     * @param source the source frame
     * @param recon the frame receiving the reconstruction
     * @param structure 0 for a frame, 1 for a top field, 2 for a bottom field
     * @param pairs whether the frame is coded in macroblock pairs
     * @param predicted whether the picture is a P picture
     * @param references the references of frame macroblocks or of the field picture, by index
     * @param topFieldRefs the references of top field macroblocks of a frame of pairs, or {@code null}
     * @param bottomFieldRefs the references of bottom field macroblocks of a frame of pairs, or
     * {@code null}
     * @param refreshStart the first column of the intra refresh band, 0 for none
     * @param refreshEnd the column after the intra refresh band, 0 for none
     */
    void startPicture(WideFrame source, WideFrame recon, int structure, boolean pairs, boolean predicted,
                      WideReference[] references, WideReference[] topFieldRefs, WideReference[] bottomFieldRefs,
                      int refreshStart, int refreshEnd) {
        this.source = source;
        this.recon = recon;
        this.structure = structure;
        this.mbaff = pairs;
        this.pSlice = predicted;
        this.frameRefs = references;
        this.pairFieldRefs[0] = topFieldRefs;
        this.pairFieldRefs[1] = bottomFieldRefs;
        this.refreshStart = refreshStart;
        this.refreshEnd = refreshEnd;
        table.startPicture(structure == 0 ? settings.mbHeight() : settings.mbHeight() / 2, pairs);
    }

    /**
     * Returns the number of reference indices macroblocks of a kind may use.
     *
     * @param field whether the macroblock is a field macroblock of a frame of pairs
     * @return the number of indices
     */
    int refCount(boolean field) {
        return field ? pairFieldRefs[0].length : frameRefs.length;
    }

    /**
     * Returns the field flag a pair of macroblocks gets when neither of them carries one: that of
     * the pair to the left in the same slice, otherwise that of the pair above in the same slice,
     * otherwise frame (ITU-T H.264 7.4.4).
     *
     * @param address the decoding-order address of the pair's top macroblock
     * @param sliceStart the address of the first macroblock of the slice
     * @return whether the pair is inferred to be a field pair
     */
    boolean inferredField(int address, int sliceStart) {
        var pair = address >> 1;
        var width = settings.mbWidth();
        var x = pair % width;
        var y = pair / width;
        if (x > 0 && 2 * (pair - 1) >= sliceStart) {
            return table.field[2 * y * width + x - 1];
        }
        if (y > 0 && 2 * (pair - width) >= sliceStart) {
            return table.field[2 * (y - 1) * width + x];
        }
        return false;
    }

    /**
     * Decides whether a pair of macroblocks is coded as field macroblocks, from the vertical
     * activity of its source rows: a pair whose rows differ more from their neighbors than from the
     * rows two below codes better as fields.
     *
     * @param pair the index of the pair in raster order of pairs
     * @return whether to code the pair as field macroblocks
     */
    boolean decideField(int pair) {
        var width = settings.mbWidth();
        var plane = source.planes[0];
        var x = (pair % width) * 16;
        var y = (pair / width) * 32;
        long frameCost = 0;
        long fieldCost = 0;
        for (var row = 0; row < 30; row++) {
            var a = plane.origin + (y + row) * plane.stride + x;
            for (var col = 0; col < 16; col++) {
                var v = plane.data[a + col];
                frameCost += Math.abs(v - plane.data[a + plane.stride + col]);
                fieldCost += Math.abs(v - plane.data[a + 2 * plane.stride + col]);
            }
        }
        return 2 * fieldCost < frameCost;
    }

    /**
     * Decides, codes and reconstructs one macroblock, and records it in the tables.
     *
     * @param address the decoding-order address
     * @param field whether the macroblock is a field macroblock of a frame of pairs
     * @param mbQp the quantizer {@code QPY} of the macroblock
     * @param sliceStart the address of the first macroblock of the slice
     * @param skipAllowed whether {@code P_Skip} may be chosen
     * @return the decided macroblock, valid until the next call
     */
    WideMb code(int address, boolean field, int mbQp, int sliceStart, boolean skipAllowed) {
        position(address, field, sliceStart);
        qp = mbQp;
        var depthScale = 1 << (settings.bitDepth() - 8);
        lambda = (int) Math.round(256 * 0.92 * StrictMath.pow(2, (Math.max(qp, 0) - 12) / 6.0)) * depthScale;
        var mbX = table.mbX;
        var refreshing = pSlice && mbX >= refreshStart && mbX < refreshEnd;
        avoidTopRight = refreshing && mbX == refreshEnd - 1 && availC;
        refreshLimitX = pSlice && mbX < refreshStart ? 64 * (refreshStart - mbX) - 85 : Integer.MAX_VALUE;
        if (pSlice && !refreshing) {
            decideInter(skipAllowed);
        } else {
            decideIntra(Long.MAX_VALUE);
        }
        commit();
        return mb;
    }

    /**
     * Moves to a macroblock: its planes, sample positions, references and neighbor availability.
     *
     * @param address the decoding-order address
     * @param field whether it is a field macroblock of a frame of pairs
     * @param sliceStart the address of the first macroblock of the slice
     */
    private void position(int address, boolean field, int sliceStart) {
        table.moveTo(address, field, sliceStart);
        var mbX = table.mbX;
        if (mbaff) {
            var pairY = table.mbY >> 1;
            var bottom = table.bottom;
            if (field) {
                src = source.coded(bottom ? 2 : 1);
                rec = recon.coded(bottom ? 2 : 1);
                y0 = pairY * 16;
                parity = bottom ? 1 : 0;
                fieldContext = true;
                refs = pairFieldRefs[parity];
            } else {
                src = source.planes;
                rec = recon.planes;
                y0 = table.mbY * 16;
                parity = -1;
                fieldContext = false;
                refs = frameRefs;
            }
        } else {
            src = source.coded(structure);
            rec = recon.coded(structure);
            y0 = table.mbY * 16;
            parity = structure - 1;
            fieldContext = structure != 0;
            refs = frameRefs;
        }
        x0 = mbX * 16;
        at[0] = src[0].origin + y0 * src[0].stride + x0;
        if (settings.hasChroma()) {
            var cx = mbX * settings.mbWidthC();
            var cy = y0 * settings.mbHeightC() / 16;
            at[1] = src[1].origin + cy * src[1].stride + cx;
            at[2] = at[1];
        }
        table.locate(-1, 0, 16, 16);
        availA = table.found >= 0;
        table.locate(0, -1, 16, 16);
        availB = table.found >= 0;
        table.locate(16, -1, 16, 16);
        availC = table.found >= 0;
        table.locate(-1, -1, 16, 16);
        availD = table.found >= 0;
    }

    /**
     * {@return whether the current macroblock reads its references as fields}
     */
    boolean fieldContext() {
        return fieldContext;
    }

    /**
     * Decides and codes an intra macroblock.
     *
     * @param interCost the cost of the best inter candidate, which intra prediction must beat to be
     * tried in full, or {@link Long#MAX_VALUE} in intra pictures
     * @return the cost of the chosen intra candidate
     */
    private long decideIntra(long interCost) {
        var lossless = settings.lossless();
        var best16 = -1;
        var cost16 = Long.MAX_VALUE;
        if (!lossless) {
            for (var mode = 0; mode < 4; mode++) {
                if (!available16(mode)) {
                    continue;
                }
                var cost = 0L;
                for (var c = 0; c < lumaComponents; c++) {
                    intra.predict16x16(mode, rec[c].data, at[c], rec[c].stride, availA, availB, availD);
                    cost += satd(src[c].data, at[c], src[c].stride, rec[c].data, at[c], rec[c].stride, 16, 16);
                }
                cost += (lambda * 6L) >> 8;
                if (cost < cost16) {
                    cost16 = cost;
                    best16 = mode;
                }
            }
        }
        var chromaMode = subsampled ? decideChromaMode() : 0;
        var tryNxN = lossless || interCost == Long.MAX_VALUE || intraNxNInter;
        long costNxN = Long.MAX_VALUE;
        var bestNxN = -1;
        if (tryNxN && Math.min(cost16, interCost) > 0) {
            codeIntraNxN(false, chromaMode);
            var cost4 = nxnCost;
            costNxN = cost4;
            bestNxN = WideMb.I4X4;
            if (transform8x8Mode && !avoidTopRight) {
                saved.copyFrom(mb);
                saveRecon();
                codeIntraNxN(true, chromaMode);
                if (nxnCost < cost4) {
                    costNxN = nxnCost;
                    bestNxN = WideMb.I8X8;
                } else {
                    mb.copyFrom(saved);
                    restoreRecon();
                }
            }
        }
        var bestIntra = Math.min(cost16, costNxN);
        if (interCost != Long.MAX_VALUE && interCost <= bestIntra) {
            return bestIntra;
        }
        if (cost16 < costNxN) {
            codeIntra16(best16, chromaMode);
            return cost16;
        }
        if (bestNxN < 0) {
            codeIntraNxN(false, chromaMode);
        }
        return costNxN;
    }

    /**
     * Returns whether the reference samples an Intra_16x16 mode needs are available.
     *
     * @param mode the Intra_16x16 prediction mode, 0 to 3
     * @return whether the mode can be used
     */
    private boolean available16(int mode) {
        return switch (mode) {
            case 0 -> availB;
            case 1 -> availA;
            case 2 -> true;
            default -> availA && availB && availD;
        };
    }

    /**
     * Chooses the chroma intra prediction mode of a 4:2:0 or 4:2:2 macroblock by SATD.
     *
     * @return the mode
     */
    private int decideChromaMode() {
        var best = 0;
        var bestCost = Long.MAX_VALUE;
        var rows = settings.mbHeightC();
        var left = availA ? (1 << (rows >> 2)) - 1 : 0;
        for (var mode = 0; mode < 4; mode++) {
            if (mode == 1 && !availA || mode == 2 && !availB || mode == 3 && !(availA && availB && availD)) {
                continue;
            }
            var cost = 0L;
            for (var c = 1; c < 3; c++) {
                intra.predictChroma(mode, rec[c].data, at[c], rec[c].stride, rows, left, availB, availD);
                cost += satd(src[c].data, at[c], src[c].stride, rec[c].data, at[c], rec[c].stride, 8, rows);
            }
            cost += (lambda * (mode == 0 ? 1L : 3L)) >> 8;
            if (cost < bestCost) {
                bestCost = cost;
                best = mode;
            }
        }
        return best;
    }

    /**
     * The cost of the last {@link #codeIntraNxN} call.
     */
    private long nxnCost;

    /**
     * Codes the current macroblock as Intra_4x4 or Intra_8x8, choosing each block's mode by SATD
     * and reconstructing it before the next block, and codes its chroma with a chroma mode.
     *
     * @param eight whether to use 8x8 blocks
     * @param chromaMode the chroma prediction mode of 4:2:0 and 4:2:2
     */
    private void codeIntraNxN(boolean eight, int chromaMode) {
        clearResidual();
        mb.type = eight ? WideMb.I8X8 : WideMb.I4X4;
        mb.transform8x8 = eight;
        mb.qp = qp;
        mb.chromaMode = chromaMode;
        var cost = (lambda * (pSlice ? 9L : 1L)) >> 8;
        var blocks = eight ? 4 : 16;
        var size = eight ? 8 : 4;
        var cbp = 0;
        for (var blk = 0; blk < blocks; blk++) {
            int bx;
            int by;
            if (eight) {
                bx = (blk & 1) * 2;
                by = (blk >> 1) * 2;
            } else {
                bx = H264Tables.BLOCK_X[blk];
                by = H264Tables.BLOCK_Y[blk];
            }
            var hasTop = by > 0 || availB;
            var hasLeft = bx > 0 || availA;
            boolean hasTopLeft;
            if (bx > 0 && by > 0) {
                hasTopLeft = true;
            } else if (by > 0) {
                hasTopLeft = availA;
            } else if (bx > 0) {
                hasTopLeft = availB;
            } else {
                hasTopLeft = availD;
            }
            boolean hasTopRight;
            if (eight) {
                hasTopRight = switch (blk) {
                    case 0 -> availB;
                    case 1 -> availC;
                    case 2 -> true;
                    default -> false;
                };
            } else if (by == 0) {
                hasTopRight = bx < 3 ? availB : availC;
            } else {
                hasTopRight = TOP_RIGHT_INSIDE[blk];
            }
            var predicted = predictedMode(bx, by);
            mb.predictedModes[blk] = predicted;
            var bestMode = -1;
            var bestCost = Long.MAX_VALUE;
            for (var mode = 0; mode < 9; mode++) {
                if (!availableNxN(mode, hasTop, hasLeft, hasTopLeft)
                        || avoidTopRight && !eight && blk == 5 && (mode == 3 || mode == 7)) {
                    continue;
                }
                var modeCost = 0L;
                for (var c = 0; c < lumaComponents; c++) {
                    var offset = at[c] + by * 4 * rec[c].stride + bx * 4;
                    predictNxN(eight, mode, c, offset, hasLeft, hasTop, hasTopLeft, hasTopRight);
                    modeCost += satd(src[c].data, offset, src[c].stride, rec[c].data, offset, rec[c].stride, size,
                            size);
                }
                modeCost += (lambda * (mode == predicted ? 1L : 4L)) >> 8;
                if (modeCost < bestCost) {
                    bestCost = modeCost;
                    bestMode = mode;
                }
            }
            mb.modes[blk] = bestMode;
            cost += bestCost;
            for (var c = 0; c < lumaComponents; c++) {
                var offset = at[c] + by * 4 * rec[c].stride + bx * 4;
                predictNxN(eight, bestMode, c, offset, hasLeft, hasTop, hasTopLeft, hasTopRight);
                var dpcm = bestMode == 0 ? 1 : bestMode == 1 ? 2 : 0;
                if (eight ? codeBlock8x8(c, blk, offset, true, dpcm) : codeBlock4x4(c, blk, offset, true, dpcm)) {
                    cbp |= 1 << (eight ? blk : blk >> 2);
                }
            }
        }
        mb.cbp = cbp;
        codeChromaIntra(chromaMode);
        nxnCost = cost;
    }

    /**
     * Predicts one Intra_4x4 or Intra_8x8 block of a component into the reconstruction.
     *
     * @param eight whether the block is 8x8
     * @param mode the prediction mode
     * @param c the component
     * @param offset the index of the block's top-left sample
     * @param hasLeft whether the samples on the left are available
     * @param hasTop whether the samples above are available
     * @param hasTopLeft whether the sample above-left is available
     * @param hasTopRight whether the samples above-right are available
     */
    private void predictNxN(boolean eight, int mode, int c, int offset, boolean hasLeft, boolean hasTop,
                            boolean hasTopLeft, boolean hasTopRight) {
        if (eight) {
            intra.predict8x8(mode, rec[c].data, offset, rec[c].stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
        } else {
            intra.predict4x4(mode, rec[c].data, offset, rec[c].stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
        }
    }

    /**
     * Returns whether the reference samples an Intra_4x4 or Intra_8x8 mode needs are available.
     *
     * @param mode the Intra_4x4 or Intra_8x8 prediction mode, 0 to 8
     * @param top whether the samples above are available
     * @param left whether the samples on the left are available
     * @param topLeft whether the sample above-left is available
     * @return whether the mode can be used
     */
    private static boolean availableNxN(int mode, boolean top, boolean left, boolean topLeft) {
        return switch (mode) {
            case 0, 3, 7 -> top;
            case 1, 8 -> left;
            case 2 -> true;
            default -> top && left && topLeft;
        };
    }

    /**
     * Returns the predicted Intra_4x4 or Intra_8x8 mode of a block (ITU-T H.264 8.3.1.1).
     *
     * @param bx the block's column of 4x4 blocks
     * @param by the block's row of 4x4 blocks
     * @return the predicted mode
     */
    private int predictedMode(int bx, int by) {
        var a = neighborMode(bx - 1, by, bx > 0);
        var b = neighborMode(bx, by - 1, by > 0);
        if (a < 0 || b < 0) {
            return 2;
        }
        return Math.min(a, b);
    }

    /**
     * Returns the intra mode of the 4x4 block at a position next to or inside the current macroblock.
     *
     * @param bx the column of 4x4 blocks, -1 for the left neighbor
     * @param by the row of 4x4 blocks, -1 for the neighbor above
     * @param inside whether the block lies in the current macroblock
     * @return the mode, 2 for a neighbor that is not coded Intra_4x4 or Intra_8x8, or -1 when the
     * neighbor is not available
     */
    private int neighborMode(int bx, int by, boolean inside) {
        if (inside) {
            return mb.type == WideMb.I8X8 ? mb.modes[(by >> 1) * 2 + (bx >> 1)]
                    : mb.modes[H264Tables.BLOCK_RASTER[by * 4 + bx]];
        }
        table.locate(bx < 0 ? -1 : bx * 4, by < 0 ? -1 : by * 4, 16, 16);
        var n = table.found;
        if (n < 0) {
            return -1;
        }
        var type = table.type[n];
        if (type != WideMb.I4X4 && type != WideMb.I8X8) {
            return 2;
        }
        return table.intraModes[n * 16 + (table.foundY >> 2) * 4 + (table.foundX >> 2)];
    }

    /**
     * Codes the current macroblock as Intra_16x16 with a prediction mode.
     *
     * @param mode the Intra_16x16 mode
     * @param chromaMode the chroma prediction mode of 4:2:0 and 4:2:2
     */
    private void codeIntra16(int mode, int chromaMode) {
        clearResidual();
        mb.type = WideMb.I16X16;
        mb.transform8x8 = false;
        mb.i16Mode = mode;
        mb.qp = qp;
        mb.chromaMode = chromaMode;
        var ac = false;
        for (var c = 0; c < lumaComponents; c++) {
            intra.predict16x16(mode, rec[c].data, at[c], rec[c].stride, availA, availB, availD);
            ac |= codeDcPlane(c, at[c], true);
        }
        mb.cbp = ac ? 15 : 0;
        if (!ac) {
            for (var c = 0; c < lumaComponents; c++) {
                mb.coded[c] = 0;
            }
        }
        codeChromaIntra(chromaMode);
    }

    /**
     * Predicts and codes the chroma of a 4:2:0 or 4:2:2 intra macroblock.
     *
     * @param mode the chroma prediction mode
     */
    private void codeChromaIntra(int mode) {
        if (!subsampled) {
            return;
        }
        var rows = settings.mbHeightC();
        var left = availA ? (1 << (rows >> 2)) - 1 : 0;
        for (var c = 1; c < 3; c++) {
            intra.predictChroma(mode, rec[c].data, at[c], rec[c].stride, rows, left, availB, availD);
        }
        codeChroma(true, mode == 1 ? 2 : mode == 2 ? 1 : 0);
    }

    /**
     * Codes the residual of a 16x16 component whose prediction is in the reconstruction as 16 4x4
     * blocks with a separate DC transform (Intra_16x16), reconstructing it.
     *
     * @param c the component
     * @param offset the index of the macroblock's top-left sample of the component
     * @param intraMb whether the macroblock is intra
     * @return whether any AC level is non-zero
     */
    private boolean codeDcPlane(int c, int offset, boolean intraMb) {
        var stride = rec[c].stride;
        var qpPrime = componentQp(c);
        var levels = mb.levels[c];
        var dc = dcScratch;
        var scan = scan4();
        var anyAc = false;
        for (var blk = 0; blk < 16; blk++) {
            var bx = H264Tables.BLOCK_X[blk];
            var by = H264Tables.BLOCK_Y[blk];
            var o = offset + by * 4 * stride + bx * 4;
            loadResidual(c, o, 4, 4, coefficients, blk * 16);
            WideTransform.forward4x4(coefficients, blk * 16);
            dc[by * 4 + bx] = coefficients[blk * 16];
            var count = WideTransform.quant4x4(coefficients, blk * 16, qpPrime, intraMb, 1, maxLevel);
            for (var k = 1; k < 16; k++) {
                levels[blk * 16 + k] = coefficients[blk * 16 + scan[k]];
            }
            anyAc |= count != 0;
        }
        WideTransform.forwardLumaDc(dc);
        var dcCount = WideTransform.quantDc(dc, 16, qpPrime, intraMb, maxLevel);
        for (var k = 0; k < 16; k++) {
            mb.dc[c][k] = dc[scan[k]];
        }
        mb.codedDc[c] = dcCount != 0;
        if (!anyAc) {
            for (var blk = 0; blk < 16; blk++) {
                Arrays.fill(levels, blk * 16, blk * 16 + 16, 0);
            }
        }
        var coded = 0;
        if (anyAc) {
            for (var blk = 0; blk < 16; blk++) {
                for (var k = 1; k < 16; k++) {
                    if (levels[blk * 16 + k] != 0) {
                        coded |= 1 << blk;
                        break;
                    }
                }
            }
        }
        mb.coded[c] = coded;
        WideTransform.inverseLumaDc(dc, qpPrime);
        for (var blk = 0; blk < 16; blk++) {
            var bx = H264Tables.BLOCK_X[blk];
            var by = H264Tables.BLOCK_Y[blk];
            var block = coefficients;
            Arrays.fill(block, blk * 16, blk * 16 + 16, 0);
            for (var k = 1; k < 16; k++) {
                block[blk * 16 + scan[k]] = levels[blk * 16 + k];
            }
            WideTransform.dequant4x4(block, blk * 16, qpPrime, 1);
            block[blk * 16] = dc[by * 4 + bx];
            transform.inverse4x4Add(block, blk * 16, rec[c].data, offset + by * 4 * stride + bx * 4, stride, max);
        }
        return anyAc;
    }

    /**
     * Codes one 4x4 block of a component coded like luma whose prediction is in the
     * reconstruction, reconstructing it.
     *
     * @param c the component
     * @param blk the {@code luma4x4BlkIdx}
     * @param offset the index of the block's top-left sample
     * @param intraMb whether the macroblock is intra
     * @param dpcm for lossless intra blocks, 1 for vertical and 2 for horizontal prediction, else 0
     * @return whether a level is non-zero
     */
    private boolean codeBlock4x4(int c, int blk, int offset, boolean intraMb, int dpcm) {
        var stride = rec[c].stride;
        var levels = mb.levels[c];
        var scan = scan4();
        var block = coefficients;
        loadResidual(c, offset, 4, 4, block, 0);
        if (settings.lossless()) {
            difference(block, 0, 4, 4, 4, dpcm);
            var nonZero = false;
            for (var k = 0; k < 16; k++) {
                levels[blk * 16 + k] = block[scan[k]];
                nonZero |= block[scan[k]] != 0;
            }
            addLossless(c, offset, 4, 4, dpcm, blk * 16, levels, scan);
            if (nonZero) {
                mb.coded[c] |= 1 << blk;
            }
            return nonZero;
        }
        WideTransform.forward4x4(block, 0);
        var count = WideTransform.quant4x4(block, 0, componentQp(c), intraMb, 0, maxLevel);
        for (var k = 0; k < 16; k++) {
            levels[blk * 16 + k] = block[scan[k]];
        }
        if (count == 0) {
            return false;
        }
        mb.coded[c] |= 1 << blk;
        WideTransform.dequant4x4(block, 0, componentQp(c), 0);
        transform.inverse4x4Add(block, 0, rec[c].data, offset, stride, max);
        return true;
    }

    /**
     * Codes one 8x8 block of a component coded like luma whose prediction is in the
     * reconstruction, reconstructing it.
     *
     * @param c the component
     * @param b8 the {@code luma8x8BlkIdx}
     * @param offset the index of the block's top-left sample
     * @param intraMb whether the macroblock is intra
     * @param dpcm for lossless intra blocks, 1 for vertical and 2 for horizontal prediction, else 0
     * @return whether a level is non-zero
     */
    private boolean codeBlock8x8(int c, int b8, int offset, boolean intraMb, int dpcm) {
        var stride = rec[c].stride;
        var levels = mb.levels[c];
        var scan = scan8();
        var block = coefficients;
        loadResidual(c, offset, 8, 8, block, 0);
        if (settings.lossless()) {
            difference(block, 0, 8, 8, 8, dpcm);
            var nonZero = false;
            for (var k = 0; k < 64; k++) {
                levels[b8 * 64 + k] = block[scan[k]];
                nonZero |= block[scan[k]] != 0;
            }
            addLossless(c, offset, 8, 8, dpcm, b8 * 64, levels, scan);
            if (nonZero) {
                mb.coded[c] |= 0xF << (b8 * 4);
            }
            return nonZero;
        }
        WideTransform.forward8x8(block, 0);
        var count = WideTransform.quant8x8(block, 0, componentQp(c), intraMb, maxLevel);
        for (var k = 0; k < 64; k++) {
            levels[b8 * 64 + k] = block[scan[k]];
        }
        if (count == 0) {
            return false;
        }
        mb.coded[c] |= 0xF << (b8 * 4);
        WideTransform.dequant8x8(block, 0, componentQp(c));
        transform.inverse8x8Add(block, 0, rec[c].data, offset, stride, max);
        return true;
    }

    /**
     * Loads the residual of a block, the source minus the prediction in the reconstruction.
     *
     * @param c the component
     * @param offset the index of the block's top-left sample
     * @param w the block width
     * @param h the block height
     * @param out the destination, {@code w} values per row
     * @param outOffset the index of the first destination value
     */
    private void loadResidual(int c, int offset, int w, int h, int[] out, int outOffset) {
        var s = src[c];
        var r = rec[c];
        for (var y = 0; y < h; y++) {
            var a = offset + y * s.stride;
            var b = offset + y * r.stride;
            for (var x = 0; x < w; x++) {
                out[outOffset + y * w + x] = s.data[a + x] - r.data[b + x];
            }
        }
    }

    /**
     * Replaces a residual by its differences along the columns or the rows, the inverse of the
     * accumulation of lossless intra blocks (ITU-T H.264 8.5.15).
     *
     * @param block the residual
     * @param offset the index of the first value
     * @param w the width
     * @param h the height
     * @param stride the distance between two rows
     * @param dpcm 1 to difference down the columns, 2 along the rows, 0 for nothing
     */
    private static void difference(int[] block, int offset, int w, int h, int stride, int dpcm) {
        if (dpcm == 1) {
            for (var y = h - 1; y > 0; y--) {
                for (var x = 0; x < w; x++) {
                    block[offset + y * stride + x] -= block[offset + (y - 1) * stride + x];
                }
            }
        } else if (dpcm == 2) {
            for (var y = 0; y < h; y++) {
                for (var x = w - 1; x > 0; x--) {
                    block[offset + y * stride + x] -= block[offset + y * stride + x - 1];
                }
            }
        }
    }

    /**
     * Reconstructs a lossless block from its levels as a decoder does: the levels are the residual,
     * accumulated for vertical and horizontal intra prediction.
     *
     * @param c the component
     * @param offset the index of the block's top-left sample
     * @param w the block width
     * @param h the block height
     * @param dpcm 1 to accumulate down the columns, 2 along the rows, 0 for nothing
     * @param levelOffset the index of the block's first level in {@code levels}
     * @param levels the block's levels in scan order
     * @param scan the scan mapping scan positions to raster indices
     */
    private void addLossless(int c, int offset, int w, int h, int dpcm, int levelOffset, int[] levels, byte[] scan) {
        var block = residual;
        for (var k = 0; k < w * h; k++) {
            block[scan[k]] = levels[levelOffset + k];
        }
        accumulate(block, w, h, w, dpcm);
        var r = rec[c];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var i = offset + y * r.stride + x;
                r.data[i] = (short) Math.clamp(r.data[i] + block[y * w + x], 0, max);
            }
        }
    }

    /**
     * Accumulates a residual down the columns or along the rows (ITU-T H.264 8.5.15).
     *
     * @param block the residual
     * @param w the width
     * @param h the height
     * @param stride the distance between two rows
     * @param dpcm 1 down the columns, 2 along the rows, 0 for nothing
     */
    private static void accumulate(int[] block, int w, int h, int stride, int dpcm) {
        if (dpcm == 1) {
            for (var y = 1; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    block[y * stride + x] += block[(y - 1) * stride + x];
                }
            }
        } else if (dpcm == 2) {
            for (var y = 0; y < h; y++) {
                for (var x = 1; x < w; x++) {
                    block[y * stride + x] += block[y * stride + x - 1];
                }
            }
        }
    }

    /**
     * Codes the chroma residual of a 4:2:0 or 4:2:2 macroblock whose prediction is in the
     * reconstruction, reconstructing it and setting the chroma bits of the coded block pattern.
     *
     * @param intraMb whether the macroblock is intra
     * @param dpcm for lossless intra macroblocks, 1 for vertical and 2 for horizontal chroma
     * prediction, else 0
     */
    private void codeChroma(boolean intraMb, int dpcm) {
        var rows = settings.mbHeightC();
        var count = chromaBlocks;
        var dcScan = count == 8 ? CHROMA_DC_422_SCAN : CHROMA_DC_420_SCAN;
        var scan = scan4();
        var anyDc = false;
        var anyAc = false;
        for (var c = 1; c < 3; c++) {
            var stride = rec[c].stride;
            var offset = at[c];
            var dc = mb.chromaDc[c - 1];
            var ac = mb.chromaAc[c - 1];
            var blocks = coefficients;
            if (settings.lossless()) {
                var whole = residual;
                loadResidual(c, offset, 8, rows, whole, 0);
                difference(whole, 0, 8, rows, 8, dpcm);
                for (var blk = 0; blk < count; blk++) {
                    var bx = (blk & 1) * 4;
                    var by = (blk >> 1) * 4;
                    for (var y = 0; y < 4; y++) {
                        for (var x = 0; x < 4; x++) {
                            blocks[blk * 16 + y * 4 + x] = whole[(by + y) * 8 + bx + x];
                        }
                    }
                }
                for (var k = 0; k < count; k++) {
                    dc[k] = blocks[dcScan[k] * 16];
                    anyDc |= dc[k] != 0;
                }
                for (var blk = 0; blk < count; blk++) {
                    for (var k = 1; k < 16; k++) {
                        ac[blk * 16 + k] = blocks[blk * 16 + scan[k]];
                        if (ac[blk * 16 + k] != 0) {
                            anyAc = true;
                            mb.codedChroma[c - 1] |= 1 << blk;
                        }
                    }
                }
                continue;
            }
            var qpPrime = componentQp(c);
            var dcValues = dcScratch;
            for (var blk = 0; blk < count; blk++) {
                var bx = (blk & 1) * 4;
                var by = (blk >> 1) * 4;
                loadResidual(c, offset + by * stride + bx, 4, 4, blocks, blk * 16);
                WideTransform.forward4x4(blocks, blk * 16);
                dcValues[blk] = blocks[blk * 16];
                WideTransform.quant4x4(blocks, blk * 16, qpPrime, intraMb, 1, maxLevel);
                for (var k = 1; k < 16; k++) {
                    ac[blk * 16 + k] = blocks[blk * 16 + scan[k]];
                    if (ac[blk * 16 + k] != 0) {
                        anyAc = true;
                        mb.codedChroma[c - 1] |= 1 << blk;
                    }
                }
            }
            transform.forwardChromaDc(dcValues, count);
            WideTransform.quantDc(dcValues, count, count == 8 ? qpPrime + 3 : qpPrime, intraMb, maxLevel);
            for (var k = 0; k < count; k++) {
                dc[k] = dcValues[dcScan[k]];
                anyDc |= dc[k] != 0;
            }
        }
        var chroma = anyAc ? 2 : anyDc ? 1 : 0;
        if (chroma < 2) {
            for (var c = 0; c < 2; c++) {
                Arrays.fill(mb.chromaAc[c], 0, 128, 0);
                mb.codedChroma[c] = 0;
            }
        }
        if (chroma == 0) {
            for (var c = 0; c < 2; c++) {
                Arrays.fill(mb.chromaDc[c], 0, 8, 0);
            }
        }
        for (var c = 0; c < 2; c++) {
            var any = false;
            for (var k = 0; k < count; k++) {
                any |= mb.chromaDc[c][k] != 0;
            }
            mb.codedChromaDc[c] = any;
        }
        mb.cbp = (mb.cbp & 15) | (chroma << 4);
        if (chroma != 0) {
            reconstructChroma(dpcm);
        }
    }

    /**
     * Adds the coded chroma residual of the current 4:2:0 or 4:2:2 macroblock to its prediction.
     *
     * @param dpcm for lossless intra macroblocks, 1 for vertical and 2 for horizontal chroma
     * prediction, else 0
     */
    private void reconstructChroma(int dpcm) {
        var rows = settings.mbHeightC();
        var count = chromaBlocks;
        var dcScan = count == 8 ? CHROMA_DC_422_SCAN : CHROMA_DC_420_SCAN;
        var scan = scan4();
        for (var c = 1; c < 3; c++) {
            var stride = rec[c].stride;
            var offset = at[c];
            var dc = mb.chromaDc[c - 1];
            var ac = mb.chromaAc[c - 1];
            var blocks = coefficients;
            Arrays.fill(blocks, 0, count * 16, 0);
            if (settings.lossless()) {
                for (var k = 0; k < count; k++) {
                    blocks[dcScan[k] * 16] = dc[k];
                }
                for (var blk = 0; blk < count; blk++) {
                    for (var k = 1; k < 16; k++) {
                        blocks[blk * 16 + scan[k]] = ac[blk * 16 + k];
                    }
                }
                var whole = residual;
                for (var blk = 0; blk < count; blk++) {
                    var bx = (blk & 1) * 4;
                    var by = (blk >> 1) * 4;
                    for (var y = 0; y < 4; y++) {
                        for (var x = 0; x < 4; x++) {
                            whole[(by + y) * 8 + bx + x] = blocks[blk * 16 + y * 4 + x];
                        }
                    }
                }
                accumulate(whole, 8, rows, 8, dpcm);
                var r = rec[c];
                for (var y = 0; y < rows; y++) {
                    for (var x = 0; x < 8; x++) {
                        var i = offset + y * stride + x;
                        r.data[i] = (short) Math.clamp(r.data[i] + whole[y * 8 + x], 0, max);
                    }
                }
                continue;
            }
            var qpPrime = componentQp(c);
            var dcValues = dcScratch;
            for (var k = 0; k < count; k++) {
                dcValues[dcScan[k]] = dc[k];
            }
            transform.inverseChromaDc(dcValues, count, qpPrime);
            for (var blk = 0; blk < count; blk++) {
                for (var k = 1; k < 16; k++) {
                    blocks[blk * 16 + scan[k]] = ac[blk * 16 + k];
                }
                WideTransform.dequant4x4(blocks, blk * 16, qpPrime, 1);
                blocks[blk * 16] = dcValues[blk];
                var bx = (blk & 1) * 4;
                var by = (blk >> 1) * 4;
                transform.inverse4x4Add(blocks, blk * 16, rec[c].data, offset + by * stride + bx, stride, max);
            }
        }
    }

    /**
     * Returns the quantizer {@code qP} of a component of the current macroblock: {@code QP'Y} for
     * luma, {@code QP'C} for chroma (ITU-T H.264 8.5.8).
     *
     * @param c the component
     * @return the quantizer with the bit depth offset
     */
    private int componentQp(int c) {
        var offset = settings.qpBdOffset();
        if (c == 0) {
            return qp + offset;
        }
        var qpi = Math.clamp(qp + settings.chromaQpOffset(), -offset, 51);
        return chromaQp(qpi) + offset;
    }

    /**
     * Returns {@code QPC} for a {@code qPI} (ITU-T H.264 Table 8-15).
     *
     * @param qpi the index, from {@code -QpBdOffsetC} to 51
     * @return the chroma quantizer
     */
    static int chromaQp(int qpi) {
        if (qpi < 30) {
            return qpi;
        }
        return H264Tables.CHROMA_QP[qpi];
    }

    /**
     * {@return the 4x4 scan of the current macroblock: field scan for field macroblocks and pictures}
     */
    byte[] scan4() {
        return fieldContext ? H264Tables.FIELD_4X4 : H264Tables.ZIGZAG_4X4;
    }

    /**
     * {@return the 8x8 scan of the current macroblock}
     */
    byte[] scan8() {
        return fieldContext ? H264Tables.FIELD_8X8 : H264Tables.ZIGZAG_8X8;
    }

    /**
     * Clears the residual levels of the current macroblock.
     */
    private void clearResidual() {
        for (var c = 0; c < 3; c++) {
            Arrays.fill(mb.levels[c], 0);
            Arrays.fill(mb.dc[c], 0);
            mb.coded[c] = 0;
            mb.codedDc[c] = false;
        }
        for (var c = 0; c < 2; c++) {
            Arrays.fill(mb.chromaDc[c], 0);
            Arrays.fill(mb.chromaAc[c], 0);
            mb.codedChroma[c] = 0;
            mb.codedChromaDc[c] = false;
        }
        mb.cbp = 0;
        mb.ref = -1;
        mb.mvX = 0;
        mb.mvY = 0;
        mb.mvdX = 0;
        mb.mvdY = 0;
    }

    /**
     * Saves the reconstruction of the current macroblock.
     */
    private void saveRecon() {
        copyRecon(true);
    }

    /**
     * Restores the reconstruction saved by {@link #saveRecon()}.
     */
    private void restoreRecon() {
        copyRecon(false);
    }

    /**
     * Copies the reconstruction of the current macroblock to or from {@link #savedRecon}.
     *
     * @param save whether to save rather than restore
     */
    private void copyRecon(boolean save) {
        for (var c = 0; c < 3; c++) {
            if (rec[c] == null) {
                continue;
            }
            var w = c == 0 ? 16 : settings.mbWidthC();
            var h = c == 0 ? 16 : settings.mbHeightC();
            var plane = rec[c];
            for (var y = 0; y < h; y++) {
                var i = at[c] + y * plane.stride;
                if (save) {
                    System.arraycopy(plane.data, i, savedRecon[c], y * 16, w);
                } else {
                    System.arraycopy(savedRecon[c], y * 16, plane.data, i, w);
                }
            }
        }
    }

    /**
     * Decides and codes the current macroblock of a P picture: skip, one motion vector, or intra.
     *
     * @param skipAllowed whether {@code P_Skip} may be chosen
     */
    private void decideInter(boolean skipAllowed) {
        var refCount = refs.length;
        var skipMv = skipVector();
        var skipX = MotionPredictor.x(skipMv);
        var skipY = MotionPredictor.y(skipMv);
        var bestCost = Long.MAX_VALUE;
        var bestRef = 0;
        var bestX = 0;
        var bestY = 0;
        if (skipAllowed && insideWindow(skipX, skipY)) {
            var skipCost = interSatd(refs[0], skipX, skipY);
            if (skipCost * 256 < 6L * lambda) {
                codeInter(0, skipX, skipY, skipMv);
                if ((mb.cbp & 0x3F) == 0) {
                    mb.type = WideMb.PSKIP;
                    return;
                }
            }
        }
        for (var r = 0; r < refCount; r++) {
            var mvp = predictVector(r);
            var found = search(refs[r], MotionPredictor.x(mvp), MotionPredictor.y(mvp), r == 0 ? skipMv : mvp);
            var cost = searchCost + ((lambda * (long) refBits(r, refCount)) >> 8);
            if (cost < bestCost) {
                bestCost = cost;
                bestRef = r;
                bestX = MotionPredictor.x(found);
                bestY = MotionPredictor.y(found);
            }
        }
        var intraCost = Long.MAX_VALUE;
        if (bestCost > 0) {
            intraCost = decideIntra(bestCost);
            if (intraCost < bestCost) {
                return;
            }
        }
        codeInter(bestRef, bestX, bestY, predictVector(bestRef));
        if (skipAllowed && bestRef == 0 && bestX == skipX && bestY == skipY && (mb.cbp & 0x3F) == 0) {
            mb.type = WideMb.PSKIP;
        }
    }

    /**
     * Returns the number of bits of a reference index.
     *
     * @param ref the index
     * @param count the number of indices
     * @return the bits
     */
    private static int refBits(int ref, int count) {
        if (count <= 1) {
            return 0;
        }
        return count == 2 ? 1 : 2 * (31 - Integer.numberOfLeadingZeros(ref + 1)) + 1;
    }

    /**
     * The SATD cost, vector cost included, of the vector the last {@link #search} returned.
     */
    private long searchCost;

    /**
     * Searches the motion vector of the current macroblock in a reference: an integer diamond
     * search around the predictor and the start candidates, then half- and quarter-sample
     * refinement.
     *
     * @param ref the reference
     * @param mvpX the horizontal predictor in quarter samples
     * @param mvpY the vertical predictor in quarter samples
     * @param candidate another packed start candidate
     * @return the packed vector found
     */
    private int search(WideReference ref, int mvpX, int mvpY, int candidate) {
        var plane = ref.planes[0];
        var bestX = 0;
        var bestY = 0;
        var bestCost = Long.MAX_VALUE;
        for (var start = 0; start < 3; start++) {
            var sx = clampX(start == 0 ? mvpX & ~3 : start == 1 ? 0 : MotionPredictor.x(candidate) & ~3) & ~3;
            if (clampX(sx) != sx) {
                sx += 4;
            }
            var sy = clampY(start == 0 ? mvpY & ~3 : start == 1 ? 0 : MotionPredictor.y(candidate) & ~3, ref) & ~3;
            if (clampY(sy, ref) != sy) {
                sy += 4;
            }
            var cost = sadCost(plane, sx, sy, mvpX, mvpY);
            if (cost < bestCost) {
                bestCost = cost;
                bestX = sx;
                bestY = sy;
            }
        }
        var centerX = bestX;
        var centerY = bestY;
        for (var step = 0; step < searchRange; step++) {
            var moved = false;
            var cx = bestX;
            var cy = bestY;
            for (var d = 0; d < 4; d++) {
                var nx = cx + (d == 0 ? 4 : d == 1 ? -4 : 0);
                var ny = cy + (d == 2 ? 4 : d == 3 ? -4 : 0);
                if (Math.abs(nx - centerX) > 4 * searchRange || Math.abs(ny - centerY) > 4 * searchRange
                        || clampX(nx) != nx || clampY(ny, ref) != ny) {
                    continue;
                }
                var cost = sadCost(plane, nx, ny, mvpX, mvpY);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = nx;
                    bestY = ny;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        var satdBest = interSatd(ref, bestX, bestY) + mvCost(bestX, bestY, mvpX, mvpY);
        for (var stepSize = 2; stepSize >= 1; stepSize--) {
            var cx = bestX;
            var cy = bestY;
            for (var d = 0; d < 8; d++) {
                var nx = cx + stepSize * SQUARE_X[d];
                var ny = cy + stepSize * SQUARE_Y[d];
                if (clampX(nx) != nx || clampY(ny, ref) != ny) {
                    continue;
                }
                var cost = interSatd(ref, nx, ny) + mvCost(nx, ny, mvpX, mvpY);
                if (cost < satdBest) {
                    satdBest = cost;
                    bestX = nx;
                    bestY = ny;
                }
            }
        }
        searchCost = satdBest;
        return MotionPredictor.pack(bestX, bestY);
    }

    /**
     * Clamps a horizontal vector to the reference padding, the level's range and the intra refresh
     * limit.
     *
     * @param mvx the vector in quarter samples
     * @return the clamped vector
     */
    private int clampX(int mvx) {
        var low = (-WideFrame.PAD + 4 - x0) * 4;
        var high = (settings.codedWidth() + WideFrame.PAD - 16 - 5 - x0) * 4;
        return Math.clamp(mvx, low, Math.max(low, Math.min(high, refreshLimitX)));
    }

    /**
     * Clamps a vertical vector to the reference padding and the level's range.
     *
     * @param mvy the vector in quarter samples
     * @param ref the reference
     * @return the clamped vector
     */
    private int clampY(int mvy, WideReference ref) {
        var height = ref.planes[0].height;
        var limit = fieldContext ? settings.maxMvY() / 2 : settings.maxMvY();
        var low = Math.max(-limit, (-WideFrame.PAD + 4 - y0) * 4);
        var high = Math.min(limit, (height + WideFrame.PAD - 16 - 5 - y0) * 4);
        return Math.clamp(mvy, low, high);
    }

    /**
     * Returns whether a vector stays inside the area the motion search allows.
     *
     * @param mvx the horizontal vector
     * @param mvy the vertical vector
     * @return whether it does
     */
    private boolean insideWindow(int mvx, int mvy) {
        return refs.length > 0 && clampX(mvx) == mvx && clampY(mvy, refs[0]) == mvy;
    }

    /**
     * Returns the weighted bit cost of coding a vector against a predictor.
     *
     * @param mvx the horizontal vector
     * @param mvy the vertical vector
     * @param mvpX the horizontal predictor
     * @param mvpY the vertical predictor
     * @return the cost in SATD units
     */
    private long mvCost(int mvx, int mvy, int mvpX, int mvpY) {
        return ((long) lambda * (seBits(mvx - mvpX) + seBits(mvy - mvpY))) >> 8;
    }

    /**
     * Returns the length of the signed Exp-Golomb code of a value.
     *
     * @param value the signed value to code
     * @return the bits
     */
    static int seBits(int value) {
        var mapped = value > 0 ? 2L * value - 1 : -2L * value;
        return 2 * (63 - Long.numberOfLeadingZeros(mapped + 1)) + 1;
    }

    /**
     * Returns the SAD cost, vector cost included, of an integer vector.
     *
     * @param plane the reference luma plane
     * @param mvx the horizontal vector, a multiple of 4
     * @param mvy the vertical vector, a multiple of 4
     * @param mvpX the horizontal predictor
     * @param mvpY the vertical predictor
     * @return the cost
     */
    private long sadCost(WidePlane plane, int mvx, int mvy, int mvpX, int mvpY) {
        var s = src[0];
        var r = plane.origin + (y0 + (mvy >> 2)) * plane.stride + x0 + (mvx >> 2);
        long sad = 0;
        for (var y = 0; y < 16; y++) {
            var a = at[0] + y * s.stride;
            var b = r + y * plane.stride;
            for (var x = 0; x < 16; x++) {
                sad += Math.abs(s.data[a + x] - plane.data[b + x]);
            }
        }
        return sad + mvCost(mvx, mvy, mvpX, mvpY);
    }

    /**
     * Returns the SATD of the luma prediction at a vector, every component coded like luma
     * included.
     *
     * @param ref the reference
     * @param mvx the horizontal vector in quarter samples
     * @param mvy the vertical vector in quarter samples
     * @return the SATD
     */
    private long interSatd(WideReference ref, int mvx, int mvy) {
        var total = 0L;
        for (var c = 0; c < lumaComponents; c++) {
            ref.predictLuma(c, x0, y0, mvx, mvy, 16, 16, prediction[c], 0, 16);
            total += satd(src[c].data, at[c], src[c].stride, prediction[c], 0, 16, 16, 16);
        }
        return total;
    }

    /**
     * Codes the current macroblock as {@code P_L0_16x16}: motion compensation into the
     * reconstruction, then the residual.
     *
     * @param ref the reference index
     * @param mvx the horizontal vector
     * @param mvy the vertical vector
     * @param mvp the packed vector predictor
     */
    private void codeInter(int ref, int mvx, int mvy, int mvp) {
        clearResidual();
        mb.type = WideMb.P16X16;
        mb.transform8x8 = false;
        mb.qp = qp;
        mb.ref = ref;
        mb.mvX = mvx;
        mb.mvY = mvy;
        mb.mvdX = mvx - MotionPredictor.x(mvp);
        mb.mvdY = mvy - MotionPredictor.y(mvp);
        var reference = refs[ref];
        for (var c = 0; c < lumaComponents; c++) {
            reference.predictLuma(c, x0, y0, mvx, mvy, 16, 16, prediction[c], 0, 16);
            copyPrediction(c, 16, 16);
        }
        if (subsampled) {
            var cw = settings.mbWidthC();
            var ch = settings.mbHeightC();
            var cx = x0 / settings.subWidth();
            var cy = y0 * ch / 16;
            var cmvy = ch == 16 ? mvy << 1 : chromaOffset(mvy, reference.parity);
            for (var c = 1; c < 3; c++) {
                reference.predictChroma(c, cx, cy, mvx, cmvy, cw, ch, prediction[c], 0, 16);
                copyPrediction(c, cw, ch);
            }
        }
        mb.cbp = codeInterLuma(false);
        if (transform8x8Mode && !settings.lossless() && mb.cbp != 0) {
            var cost4 = interLumaCost();
            saved.copyFrom(mb);
            saveRecon();
            for (var c = 0; c < lumaComponents; c++) {
                Arrays.fill(mb.levels[c], 0);
                mb.coded[c] = 0;
                copyPrediction(c, 16, 16);
            }
            mb.cbp = codeInterLuma(true);
            mb.transform8x8 = mb.cbp != 0;
            if (mb.cbp == 0 || interLumaCost() >= cost4) {
                mb.copyFrom(saved);
                restoreRecon();
            }
        }
        if (subsampled) {
            codeChroma(false, 0);
        }
    }

    /**
     * Codes the residual of the components coded like luma of an inter macroblock whose prediction is in
     * the reconstruction, with the 4x4 or the 8x8 transform.
     *
     * @param eight whether to use the 8x8 transform
     * @return the luma bits of the coded block pattern
     */
    private int codeInterLuma(boolean eight) {
        var cbp = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var any = false;
            for (var c = 0; c < lumaComponents; c++) {
                if (eight) {
                    var offset = at[c] + (b8 >> 1) * 8 * rec[c].stride + (b8 & 1) * 8;
                    any |= codeBlock8x8(c, b8, offset, false, 0);
                    continue;
                }
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    var offset = at[c] + H264Tables.BLOCK_Y[blk] * 4 * rec[c].stride + H264Tables.BLOCK_X[blk] * 4;
                    any |= codeBlock4x4(c, blk, offset, false, 0);
                }
            }
            if (any) {
                cbp |= 1 << b8;
            }
        }
        return cbp;
    }

    /**
     * Returns the rate-distortion cost of the coded components like luma of the current macroblock: the
     * squared error of the reconstruction plus the estimated bits of the levels, weighted by the
     * squared-error Lagrange multiplier.
     *
     * @return the cost, in squared error units times 256
     */
    private long interLumaCost() {
        long ssd = 0;
        long bits = 0;
        for (var c = 0; c < lumaComponents; c++) {
            var s = src[c];
            var r = rec[c];
            for (var y = 0; y < 16; y++) {
                var a = at[c] + y * s.stride;
                var b = at[c] + y * r.stride;
                for (var x = 0; x < 16; x++) {
                    var d = s.data[a + x] - r.data[b + x];
                    ssd += (long) d * d;
                }
            }
            for (var level : mb.levels[c]) {
                if (level != 0) {
                    bits += 3 + 2L * (31 - Integer.numberOfLeadingZeros(Math.abs(level)));
                }
            }
            bits += 2L * Integer.bitCount(mb.coded[c]);
        }
        var depthScale = 1L << (2 * (settings.bitDepth() - 8));
        var lambdaSsd = (long) (256 * 0.85 * StrictMath.pow(2, (Math.max(qp, 0) - 12) / 3.0)) * depthScale;
        return ssd * 256 + lambdaSsd * bits;
    }

    /**
     * Returns the vertical chroma vector of 4:2:0 for a luma vector, shifted by a quarter chroma
     * sample when a field predicts from the field of the other parity (ITU-T H.264 Table 8-9).
     *
     * @param mvy the vertical luma vector
     * @param refParity the parity of the reference field, or -1 for a frame
     * @return the vertical chroma vector in eighth samples
     */
    private int chromaOffset(int mvy, int refParity) {
        if (parity < 0 || refParity < 0 || parity == refParity) {
            return mvy;
        }
        return parity == 1 ? mvy + 2 : mvy - 2;
    }

    /**
     * Copies a component's inter prediction into the reconstruction.
     *
     * @param c the component
     * @param w the width
     * @param h the height
     */
    private void copyPrediction(int c, int w, int h) {
        var plane = rec[c];
        for (var y = 0; y < h; y++) {
            System.arraycopy(prediction[c], y * 16, plane.data, at[c] + y * plane.stride, w);
        }
    }

    /**
     * Returns the motion vector of {@code P_Skip} (ITU-T H.264 8.4.1.1).
     *
     * @return the packed vector
     */
    private int skipVector() {
        if (!availA || !availB) {
            return 0;
        }
        table.locate(-1, 0, 16, 16);
        var a = table.found;
        if (scaledRef(a) == 0 && scaledMv(a) == 0) {
            return 0;
        }
        table.locate(0, -1, 16, 16);
        var b = table.found;
        if (scaledRef(b) == 0 && scaledMv(b) == 0) {
            return 0;
        }
        return predictVector(0);
    }

    /**
     * Returns the motion vector predictor of the 16x16 partition for a reference index (ITU-T
     * H.264 8.4.1.3).
     *
     * @param ref the reference index
     * @return the packed predictor
     */
    int predictVector(int ref) {
        table.locate(-1, 0, 16, 16);
        var a = table.found;
        table.locate(0, -1, 16, 16);
        var b = table.found;
        table.locate(16, -1, 16, 16);
        var c = table.found;
        if (c < 0) {
            table.locate(-1, -1, 16, 16);
            c = table.found;
        }
        var refA = scaledRef(a);
        var refB = scaledRef(b);
        var refC = scaledRef(c);
        var mvA = scaledMv(a);
        var mvB = scaledMv(b);
        var mvC = scaledMv(c);
        if (b < 0 && c < 0 && a >= 0) {
            mvB = mvA;
            mvC = mvA;
            refB = refA;
            refC = refA;
        }
        var matches = (refA == ref ? 1 : 0) + (refB == ref ? 1 : 0) + (refC == ref ? 1 : 0);
        if (matches == 1) {
            return refA == ref ? mvA : refB == ref ? mvB : mvC;
        }
        return MotionPredictor.pack(median(MotionPredictor.x(mvA), MotionPredictor.x(mvB), MotionPredictor.x(mvC)),
                median(MotionPredictor.y(mvA), MotionPredictor.y(mvB), MotionPredictor.y(mvC)));
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    private static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Returns a neighbor's reference index in the current macroblock's units.
     *
     * @param position the neighbor's grid position, or -1
     * @return the index, or -1 for an unavailable or intra neighbor
     */
    private int scaledRef(int position) {
        if (position < 0) {
            return -1;
        }
        var type = table.type[position];
        if (type <= WideMb.IPCM) {
            return -1;
        }
        var ref = table.ref[position];
        return switch (table.fieldScaling(position)) {
            case 1 -> ref >> 1;
            case -1 -> ref << 1;
            default -> ref;
        };
    }

    /**
     * Returns a neighbor's motion vector in the current macroblock's units.
     *
     * @param position the neighbor's grid position, or -1
     * @return the packed vector, zero for an unavailable or intra neighbor
     */
    private int scaledMv(int position) {
        if (position < 0 || table.type[position] <= WideMb.IPCM) {
            return 0;
        }
        int y = table.mvY[position];
        switch (table.fieldScaling(position)) {
            case 1 -> y *= 2;
            case -1 -> y /= 2;
            default -> {
            }
        }
        return MotionPredictor.pack(table.mvX[position], y);
    }

    /**
     * Codes the current macroblock as {@code I_PCM}: the reconstruction becomes the source samples.
     */
    void makePcm() {
        clearResidual();
        mb.type = WideMb.IPCM;
        mb.transform8x8 = false;
        mb.qp = qp;
        mb.cbp = 0x2F;
        for (var c = 0; c < 3; c++) {
            if (rec[c] == null) {
                continue;
            }
            var w = c == 0 ? 16 : settings.mbWidthC();
            var h = c == 0 ? 16 : settings.mbHeightC();
            for (var y = 0; y < h; y++) {
                System.arraycopy(src[c].data, at[c] + y * src[c].stride, rec[c].data, at[c] + y * rec[c].stride, w);
            }
        }
    }

    /**
     * Rewrites the current macroblock's table entries after it was replaced by {@link #makePcm()}.
     */
    void recommit() {
        commit();
    }

    /**
     * Copies the source samples of the current {@code I_PCM} macroblock, luma then Cb then Cr.
     *
     * @param out receives the samples
     * @return the number of samples
     */
    int pcmSamples(int[] out) {
        var n = 0;
        for (var c = 0; c < 3; c++) {
            if (src[c] == null) {
                continue;
            }
            var w = c == 0 ? 16 : settings.mbWidthC();
            var h = c == 0 ? 16 : settings.mbHeightC();
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    out[n++] = src[c].data[at[c] + y * src[c].stride + x];
                }
            }
        }
        return n;
    }

    /**
     * Records the current macroblock in the tables.
     */
    private void commit() {
        var t = table;
        var p = t.index;
        t.type[p] = (byte) mb.type;
        t.field[p] = fieldContext && mbaff;
        t.qp[p] = (byte) mb.qp;
        t.cbp[p] = (byte) mb.cbp;
        t.chromaMode[p] = (byte) (mb.intra() ? mb.chromaMode : 0);
        t.transform8x8[p] = mb.transform8x8;
        var inter = !mb.intra();
        t.ref[p] = (byte) (inter ? (mb.type == WideMb.PSKIP ? 0 : mb.ref) : -1);
        t.mvX[p] = (short) (inter ? mb.mvX : 0);
        t.mvY[p] = (short) (inter ? mb.mvY : 0);
        t.mvdX[p] = (short) (mb.type == WideMb.P16X16 ? Math.abs(mb.mvdX) : 0);
        t.mvdY[p] = (short) (mb.type == WideMb.P16X16 ? Math.abs(mb.mvdY) : 0);
        for (var i = 0; i < 16; i++) {
            int mode;
            if (mb.type == WideMb.I4X4) {
                mode = mb.modes[H264Tables.BLOCK_RASTER[i]];
            } else if (mb.type == WideMb.I8X8) {
                mode = mb.modes[((i >> 3) << 1) + ((i & 3) >> 1)];
            } else {
                mode = 2;
            }
            t.intraModes[p * 16 + i] = (byte) mode;
        }
        for (var c = 0; c < 3; c++) {
            var base = p * 48 + c * 16;
            var cbfBits = 0;
            for (var raster = 0; raster < 16; raster++) {
                t.totalCoeff[base + raster] = 0;
            }
            if (mb.type == WideMb.IPCM) {
                Arrays.fill(t.totalCoeff, base, base + 16, (byte) 16);
                t.cbf[p * 3 + c] = 0x1FFFF;
                continue;
            }
            if (c < lumaComponents) {
                for (var blk = 0; blk < 16; blk++) {
                    var raster = H264Tables.BLOCK_RASTER[blk];
                    var count = totalCoeff(c, blk);
                    t.totalCoeff[base + raster] = (byte) count;
                    if ((mb.coded[c] & (1 << blk)) != 0) {
                        cbfBits |= 1 << raster;
                    }
                }
                if (mb.type == WideMb.I16X16 && mb.codedDc[c]) {
                    cbfBits |= 1 << 16;
                }
            } else if (subsampled) {
                for (var blk = 0; blk < chromaBlocks; blk++) {
                    var count = 0;
                    for (var k = 1; k < 16; k++) {
                        if (mb.chromaAc[c - 1][blk * 16 + k] != 0) {
                            count++;
                        }
                    }
                    t.totalCoeff[base + blk] = (byte) count;
                    if (count != 0) {
                        cbfBits |= 1 << blk;
                    }
                }
                if (mb.codedChromaDc[c - 1]) {
                    cbfBits |= 1 << 16;
                }
            }
            t.cbf[p * 3 + c] = cbfBits;
        }
    }

    /**
     * Returns the {@code TotalCoeff} a 4x4 block of a component coded like luma reports to the
     * blocks after it: its non-zero levels (AC levels for Intra_16x16), or for the 8x8 transform the
     * levels of the 4x4 coefficient list CAVLC codes for it.
     *
     * @param c the component
     * @param blk the {@code luma4x4BlkIdx}
     * @return the count
     */
    private int totalCoeff(int c, int blk) {
        if ((mb.cbp & (1 << (blk >> 2))) == 0) {
            return 0;
        }
        var levels = mb.levels[c];
        var count = 0;
        if (mb.transform8x8) {
            var b8 = blk >> 2;
            var list = blk & 3;
            for (var k = 0; k < 16; k++) {
                if (levels[b8 * 64 + 4 * k + list] != 0) {
                    count++;
                }
            }
            return count;
        }
        for (var k = mb.type == WideMb.I16X16 ? 1 : 0; k < 16; k++) {
            if (levels[blk * 16 + k] != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the sum of absolute 4x4 Hadamard-transformed differences of two blocks, halved.
     *
     * @param a the first samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first row stride
     * @param b the second samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second row stride
     * @param w the width, a multiple of 4
     * @param h the height, a multiple of 4
     * @return the SATD
     */
    private long satd(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, int w, int h) {
        long total = 0;
        var d = hadamard;
        for (var by = 0; by < h; by += 4) {
            for (var bx = 0; bx < w; bx += 4) {
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        d[y * 4 + x] = a[aOffset + (by + y) * aStride + bx + x]
                                - b[bOffset + (by + y) * bStride + bx + x];
                    }
                }
                for (var i = 0; i < 4; i++) {
                    var s0 = d[i * 4] + d[i * 4 + 1];
                    var s1 = d[i * 4 + 2] + d[i * 4 + 3];
                    var d0 = d[i * 4] - d[i * 4 + 1];
                    var d1 = d[i * 4 + 2] - d[i * 4 + 3];
                    d[i * 4] = s0 + s1;
                    d[i * 4 + 1] = s0 - s1;
                    d[i * 4 + 2] = d0 - d1;
                    d[i * 4 + 3] = d0 + d1;
                }
                for (var j = 0; j < 4; j++) {
                    var s0 = d[j] + d[j + 4];
                    var s1 = d[j + 8] + d[j + 12];
                    var d0 = d[j] - d[j + 4];
                    var d1 = d[j + 8] - d[j + 12];
                    total += Math.abs(s0 + s1) + Math.abs(s0 - s1) + Math.abs(d0 - d1) + Math.abs(d0 + d1);
                }
            }
        }
        return total >> 1;
    }
}
