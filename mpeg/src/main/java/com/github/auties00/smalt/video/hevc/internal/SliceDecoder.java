/* Ports the 3D-HEVC coding unit syntax (source/Lib/TLibDecoder/TDecSbac.cpp, TDecEntropy.cpp, TDecCu.cpp), the
   depth-based block partitioning (source/Lib/TLibCommon/TComPrediction.cpp) and the view synthesis prediction
   sub-block motion (TComDataCU.cpp) of HTM-16.3. Copyright (c) 2010-2016, ITU/ISO/IEC; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.function.IntConsumer;

/**
 * Parses the slice segment data of one slice segment (ITU-T H.265 7.3.8) and reconstructs its
 * coding tree units into the current picture before in-loop filtering.
 *
 * <p>Each coding unit is reconstructed as soon as it is parsed: intra transform blocks are
 * predicted from the reconstructed neighbors and their residual added, inter prediction units are
 * motion compensated before their residual is added. The coding information later blocks and the
 * loop filters need is written to the {@link PictureState} and the motion field of the
 * {@link HevcFrame}.
 *
 * <p>Instances are reused across slice segments and are not thread-safe.
 */
public final class SliceDecoder implements NeighborAvailability {
    /**
     * {@code ctxIdxMap} of Table 9-50, the significance context of each position of a 4x4 block.
     */
    private static final int[] CTX_IDX_MAP = {0, 1, 4, 5, 2, 3, 4, 5, 6, 6, 8, 8, 7, 7, 8, 8};

    /**
     * {@code sigCtx} of 9.3.4.2.5 for every position of a 4x4 sub-block, indexed by
     * {@link #sigContextBase} plus {@code (yP << 2) + xP}: the context variable increment of
     * {@code sig_coeff_flag} before the chroma offset, for blocks that do not use the transform skip
     * context.
     */
    private static final byte[] SIG_CTX = new byte[2 * 4 * 3 * 4 * 2 * 16];

    /**
     * The contents of {@link #SIG_CTX} reordered by scan position of the sub-block, indexed by
     * {@link #sigContextBase} plus the scan position {@code n}.
     */
    private static final byte[] SIG_CTX_BY_SCAN = new byte[SIG_CTX.length];

    static {
        for (var chroma = 0; chroma < 2; chroma++) {
            for (var log2Size = 2; log2Size <= 5; log2Size++) {
                for (var scanIdx = 0; scanIdx < 3; scanIdx++) {
                    for (var prevCsbf = 0; prevCsbf < 4; prevCsbf++) {
                        for (var dcSub = 0; dcSub < 2; dcSub++) {
                            var base = sigContextBase(chroma == 0 ? 0 : 1, log2Size, scanIdx, prevCsbf, dcSub == 1);
                            for (var yP = 0; yP < 4; yP++) {
                                for (var xP = 0; xP < 4; xP++) {
                                    SIG_CTX[base + (yP << 2) + xP] = (byte) sigContext(chroma == 0 ? 0 : 1, log2Size,
                                            scanIdx, prevCsbf, dcSub == 1, xP, yP);
                                }
                            }
                            var order = ScanTables.BY_INDEX[scanIdx][2];
                            for (var n = 0; n < 16; n++) {
                                SIG_CTX_BY_SCAN[base + n] = SIG_CTX[base + ((order[n] >> 8) << 2) + (order[n] & 0xFF)];
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code QpC} of Table 8-10 for {@code qPi} 30 to 43.
     */
    private static final int[] QPC_TABLE = {29, 30, 31, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37};

    /**
     * {@code levelScale} of equation 8-309.
     */
    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    /**
     * {@code IntraPredModeC} of Table 8-3 for 4:2:2 video, indexed by {@code modeIdx}.
     */
    private static final int[] MODE_422 = {
            0, 1, 2, 2, 2, 2, 3, 5, 7, 8, 10, 12, 13, 15, 17, 18, 19, 20,
            21, 22, 23, 23, 24, 24, 25, 25, 26, 27, 27, 28, 28, 29, 29, 30, 31
    };

    /**
     * The partitioning mode {@code PART_2Nx2N}.
     */
    static final int PART_2Nx2N = 0;

    /**
     * The partitioning mode {@code PART_2NxN}.
     */
    static final int PART_2NxN = 1;

    /**
     * The partitioning mode {@code PART_Nx2N}.
     */
    static final int PART_Nx2N = 2;

    /**
     * The partitioning mode {@code PART_NxN}.
     */
    static final int PART_NxN = 3;

    /**
     * The partitioning mode {@code PART_2NxnU}.
     */
    static final int PART_2NxnU = 4;

    /**
     * The partitioning mode {@code PART_2NxnD}.
     */
    static final int PART_2NxnD = 5;

    /**
     * The partitioning mode {@code PART_nLx2N}.
     */
    static final int PART_nLx2N = 6;

    /**
     * The partitioning mode {@code PART_nRx2N}.
     */
    static final int PART_nRx2N = 7;

    /**
     * The arithmetic decoder and context variables.
     */
    private final CabacDecoder cabac = new CabacDecoder();

    /**
     * The intra predictor.
     */
    private final IntraPredictor intra = new IntraPredictor();

    /**
     * The inverse transform.
     */
    private final HevcTransform transform = new HevcTransform();

    /**
     * The motion vector predictor.
     */
    private final MotionPredictor motion = new MotionPredictor();

    /**
     * The motion compensated predictor.
     */
    private final InterPredictor inter = new InterPredictor();

    /**
     * The transform coefficient levels of the current transform block, raster order. Every entry
     * is 0 between transform blocks: a block clears the entries it may have written once it is
     * reconstructed, and {@link #prepare} clears all of them at the start of each slice segment.
     */
    private final int[] coefficients = new int[32 * 32];

    /**
     * The residual samples of the current transform block, raster order.
     */
    private final int[] residual = new int[32 * 32];

    /**
     * The intra boundary samples.
     */
    private final int[] boundary = new int[4 * 64 + 1];

    /**
     * The availability of each intra boundary sample.
     */
    private final boolean[] boundaryAvailable = new boolean[4 * 64 + 1];

    /**
     * The intra prediction of a block of a picture with 8-bit planes, before it is stored.
     */
    private final short[] intraBlock = new short[32 * 32];

    /**
     * The coded sub-block flags of the current transform block, indexed {@code yS * 8 + xS}.
     */
    private final boolean[] codedSubBlocks = new boolean[64];

    /**
     * The positions of the significant coefficients of the current sub-block, in decoding order.
     */
    private final int[] significant = new int[16];

    /**
     * The offsets of one component of a coding tree block being parsed.
     */
    private final int[] saoOffsets = new int[4];

    /**
     * The reader of PCM sample data.
     */
    private final H264BitReader pcmReader = new H264BitReader();

    /**
     * The {@code prev_intra_luma_pred_flag} values of a coding unit.
     */
    private final boolean[] intraFlags = new boolean[4];

    /**
     * The absolute levels of the significant coefficients of the current sub-block.
     */
    private final int[] levels = new int[16];

    /**
     * The motion of the prediction units of the coding unit being decoded, parsed before any is
     * derived because the coding unit extension of 3D-HEVC follows them.
     */
    private final MotionPredictor.PuMotion[] puMotions = {new MotionPredictor.PuMotion(),
            new MotionPredictor.PuMotion(), new MotionPredictor.PuMotion(), new MotionPredictor.PuMotion()};

    /**
     * The prediction unit whose syntax is being parsed.
     */
    private MotionPredictor.PuMotion puMotion = puMotions[0];

    /**
     * The luma x of each prediction unit of the coding unit.
     */
    private final int[] puX = new int[4];

    /**
     * The luma y of each prediction unit of the coding unit.
     */
    private final int[] puY = new int[4];

    /**
     * The width of each prediction unit of the coding unit.
     */
    private final int[] puWidth = new int[4];

    /**
     * The height of each prediction unit of the coding unit.
     */
    private final int[] puHeight = new int[4];

    /**
     * {@code IntraPredModeY} of {@code INTRA_WEDGE} (Table I.2).
     */
    static final int INTRA_WEDGE = 35;

    /**
     * {@code IntraPredModeY} of {@code INTRA_CONTOUR} (Table I.2).
     */
    static final int INTRA_CONTOUR = 36;

    /**
     * The shift of the coding block size in the coding unit shapes {@link HevcFrame#cuShape} holds.
     */
    private static final int TEXTURE_LOG2_SHIFT = 3;

    /**
     * The mask of the partitioning mode in the coding unit shapes {@link HevcFrame#cuShape} holds.
     */
    private static final int TEXTURE_PART_MASK = 7;

    /**
     * The depth intra predictor.
     */
    private final Hevc3dIntra depthIntra = new Hevc3dIntra();

    /**
     * {@code TexturePic} of a depth layer of 3D-HEVC, or {@code null}.
     */
    private HevcFrame texturePicture;

    /**
     * Whether the coding units of the slice record their shapes for the depth layer of the view
     * ({@link HevcSliceHeader#textureOfDepth}).
     */
    private boolean recordShapes;

    /**
     * {@code CqtCuPartPredEnabledFlag} of the slice.
     */
    private boolean cqtCuPartPred;

    /**
     * {@code dc_only_flag} of the current coding unit.
     */
    private boolean dcOnly;

    /**
     * {@code dbbp_flag} of the current coding unit.
     */
    private boolean dbbpFlag;

    /**
     * The depth intra mode of each prediction block of the current coding unit:
     * {@link #INTRA_WEDGE}, {@link #INTRA_CONTOUR}, or 0 when {@code DimFlag} is 0.
     */
    private final int[] dimMode = new int[4];

    /**
     * {@code wedge_full_tab_idx} of each prediction block of the current coding unit.
     */
    private final int[] wedgeIdx = new int[4];

    /**
     * {@code DcOffset} of each prediction block of the current coding unit, for its two segments.
     */
    private final int[][] dcOffsets = new int[4][2];

    /**
     * The segment mask of the coding unit with depth-based block partitioning, one value of 0 or 1
     * per luma sample in raster order; allocated with the first such coding unit.
     */
    private byte[] dbbpMask;

    /**
     * The prediction of the first segment of the coding unit with depth-based block partitioning,
     * by component.
     */
    private int[][] dbbpPrediction;

    /**
     * The combined prediction of a component of the coding unit with depth-based block
     * partitioning, before its boundary filtering.
     */
    private int[] dbbpCombined;

    /**
     * Whether the last block predicted with a depth intra mode has predicted values outside the
     * sample range, which its residual reconstruction adds to instead of the clipped samples.
     */
    private boolean dmmPending;

    /**
     * The luma x of the block of {@link #dmmPending}.
     */
    private int dmmX;

    /**
     * The luma y of the block of {@link #dmmPending}.
     */
    private int dmmY;

    /**
     * The size of the block of {@link #dmmPending}.
     */
    private int dmmSize;

    /**
     * The partition pattern of the block of {@link #dmmPending}.
     */
    private byte[] dmmPattern;

    /**
     * The 3D-HEVC part of the slice segment header, or {@code null}.
     */
    private Hevc3dSlice tools3d;

    /**
     * The variables of the 3D-HEVC tools the slice derived at its start, or {@code null}.
     */
    private InterViewContext views;

    /**
     * The frame being decoded.
     */
    private HevcFrame frame;

    /**
     * The coding information of the picture being decoded.
     */
    private PictureState pic;

    /**
     * The active sequence parameter set.
     */
    private HevcSps sps;

    /**
     * The active picture parameter set.
     */
    private HevcPps pps;

    /**
     * The slice segment header.
     */
    private HevcSliceHeader header;

    /**
     * The reference picture lists of the slice, indexed {@code [list][refIdx]}.
     */
    private HevcFrame[][] refList;

    /**
     * The index of the slice in the frame's slice tables.
     */
    private int sliceIndex;

    /**
     * {@code CtbAddrInTs} of the coding tree unit being decoded.
     */
    private int ctbAddrTs;

    /**
     * {@code CtbAddrInRs} of the coding tree unit being decoded.
     */
    private int ctbAddrRs;

    /**
     * The tile of the coding tree unit being decoded.
     */
    private int currentTile;

    /**
     * {@code Log2MinCuQpDeltaSize}.
     */
    private int log2MinCuQpDeltaSize;

    /**
     * {@code IsCuQpDeltaCoded}.
     */
    private boolean cuQpDeltaCoded;

    /**
     * {@code qPY_PRED} of the current quantization group.
     */
    private int qpYPred;

    /**
     * {@code QpY} of the last coding unit decoded.
     */
    private int lastQpY;

    /**
     * Whether the next quantization group is the first of a slice, tile or wavefront row, so its
     * {@code qPY_PREV} is {@code SliceQpY}.
     */
    private boolean firstQuantGroup;

    /**
     * {@code QpY} of the current coding unit.
     */
    private int qpY;

    /**
     * {@code cu_transquant_bypass_flag} of the current coding unit.
     */
    private boolean bypass;

    /**
     * Whether the current coding unit is coded in intra prediction mode.
     */
    private boolean cuIntra;

    /**
     * {@code IntraSplitFlag} of the current coding unit.
     */
    private boolean intraSplit;

    /**
     * {@code MaxTrafoDepth} of the current coding unit.
     */
    private int maxTrafoDepth;

    /**
     * {@code IntraPredModeY} of the four prediction blocks of the current coding unit.
     */
    private final int[] lumaModes = new int[4];

    /**
     * {@code IntraPredModeC} of the four prediction blocks of the current coding unit; all four are
     * equal unless the video is 4:4:4 and the coding unit is split into four prediction blocks.
     */
    private final int[] chromaModes = new int[4];

    /**
     * {@code intra_chroma_pred_mode} of the four prediction blocks of the current coding unit, 4
     * for the mode derived from luma.
     */
    private final int[] chromaSyntax = new int[4];

    /**
     * The luma x of the current coding unit.
     */
    private int cuX;

    /**
     * The luma y of the current coding unit.
     */
    private int cuY;

    /**
     * The size of the current coding unit.
     */
    private int cuSize;

    /**
     * The initialization type of the slice.
     */
    private int initType;

    /**
     * {@code StatCoeff}: the Rice parameter statistics of persistent Rice adaptation, indexed by
     * {@code sbType}.
     */
    private final int[] statCoeff = new int[4];

    /**
     * {@code Log2MinCuChromaQpOffsetSize}.
     */
    private int log2MinCuChromaQpOffsetSize;

    /**
     * {@code IsCuChromaQpOffsetCoded}.
     */
    private boolean chromaQpOffsetCoded;

    /**
     * {@code CuQpOffsetCb}.
     */
    private int cuQpOffsetCb;

    /**
     * {@code CuQpOffsetCr}.
     */
    private int cuQpOffsetCr;

    /**
     * {@code ResScaleVal} of the chroma component being decoded, 0 without cross-component
     * prediction.
     */
    private int resScaleVal;

    /**
     * The residual samples of the luma transform block of the current transform unit, kept for
     * cross-component prediction.
     */
    private final int[] lumaResidual = new int[32 * 32];

    /**
     * Whether {@link #lumaResidual} holds the residual of the current transform unit; it is
     * {@code false} when the luma block has no coefficients.
     */
    private boolean lumaResidualValid;

    /**
     * {@code transform_skip_flag} of the transform block being decoded.
     */
    private boolean transformSkip;

    /**
     * The residual DPCM direction of the transform block being decoded: -1 for none, 0 for
     * horizontal and 1 for vertical.
     */
    private int rdpcmDirection;

    /**
     * The palette mode decoder and the palette predictor of the slice segment.
     */
    private final PaletteCoding palette = new PaletteCoding();

    /**
     * The quantization parameters of the components of a palette coding unit.
     */
    private final int[] paletteQp = new int[3];

    /**
     * {@code tu_residual_act_flag} of the transform unit being decoded: its residuals are
     * collected in {@link #actResidual} and color transformed before they are added.
     */
    private boolean actTu;

    /**
     * The residual samples of the three components of a transform unit coded with the adaptive
     * color transform.
     */
    private final int[][] actResidual = {new int[32 * 32], new int[32 * 32], new int[32 * 32]};

    /**
     * Whether each component of {@link #actResidual} holds a residual of the current transform
     * unit; a component without one has a zero residual.
     */
    private final boolean[] actPresent = new boolean[3];

    /**
     * Constructs a decoder.
     */
    public SliceDecoder() {
    }

    /**
     * Decodes the slice segment data of a slice segment.
     *
     * @param frame the picture being decoded
     * @param pic the coding information of the picture
     * @param header the slice segment header
     * @param refList the reference picture lists of the slice
     * @param sliceIndex the index of the slice in the frame's slice tables
     * @param data the unescaped NAL unit payload, followed by {@link H264BitReader#PADDING}
     * zero bytes
     * @param end the index one past the last payload byte
     * @param ctbDone receives the raster address of every coding tree block once it is decoded
     * @throws MediaException.Malformed if the slice segment data is corrupt
     */
    public void decode(HevcFrame frame, PictureState pic, HevcSliceHeader header, HevcFrame[][] refList, int sliceIndex,
                       byte[] data, int end, IntConsumer ctbDone) {
        prepare(frame, pic, header, refList, sliceIndex);
        ctbAddrRs = header.segmentAddress;
        ctbAddrTs = pps.ctbAddrRsToTs[ctbAddrRs];
        cabac.start(data, header.dataOffset, end);
        var widthCtbs = sps.widthInCtbs;
        var first = true;
        while (true) {
            if (ctbAddrTs >= sps.sizeInCtbs) {
                throw new MediaException.Malformed("slice segment runs past the end of the picture");
            }
            ctbAddrRs = pps.ctbAddrTsToRs[ctbAddrTs];
            decodeCtu(first);
            first = false;
            ctbDone.accept(ctbAddrRs);
            var endOfSlice = cabac.decodeTerminate() == 1;
            ctbAddrTs++;
            if (endOfSlice) {
                storeDependentStates();
                break;
            }
            if (ctbAddrTs >= sps.sizeInCtbs) {
                throw new MediaException.Malformed("slice segment runs past the end of the picture");
            }
            var nextRs = pps.ctbAddrTsToRs[ctbAddrTs];
            var newTile = pps.tilesEnabled && pps.tileId[ctbAddrTs] != pps.tileId[ctbAddrTs - 1];
            var newRow = pps.entropyCodingSync && (nextRs % widthCtbs == 0
                    || pps.tileId[ctbAddrTs] != pps.tileId[pps.ctbAddrRsToTs[nextRs - 1]]);
            if (newTile || newRow) {
                if (cabac.decodeTerminate() != 1) {
                    throw new MediaException.Malformed("missing end_of_subset_one_bit");
                }
                cabac.restart();
            }
        }
    }

    /**
     * Decodes the coding tree blocks of one wavefront row of a slice segment from the substream of
     * the row, waiting before each coding tree block for the row above to be decoded past the
     * block above and to the right, as the context synchronization and the prediction from that
     * block require.
     *
     * <p>The slice segment must start at the beginning of a row of a picture without tiles. The
     * slice segment may end before the end of the row, in its last substream.
     *
     * @param frame the picture being decoded
     * @param pic the coding information of the picture
     * @param header the slice segment header
     * @param refList the reference picture lists of the slice
     * @param sliceIndex the index of the slice in the frame's slice tables
     * @param data the unescaped NAL unit payload, followed by {@link H264BitReader#PADDING}
     * zero bytes
     * @param start the index of the first byte of the row's substream
     * @param end the index one past the last byte of the row's substream
     * @param row the row of coding tree blocks
     * @param first whether the row is the first of the slice segment
     * @param ctbDone receives the raster address of every coding tree block once it is decoded
     * @param wavefront the progress of the rows decoded concurrently
     * @return whether the slice segment ended with the row
     * @throws MediaException.Malformed if the substream is corrupt
     */
    public boolean decodeRow(HevcFrame frame, PictureState pic, HevcSliceHeader header, HevcFrame[][] refList,
                             int sliceIndex, byte[] data, int start, int end, int row, boolean first,
                             IntConsumer ctbDone, Wavefront wavefront) {
        prepare(frame, pic, header, refList, sliceIndex);
        var widthCtbs = sps.widthInCtbs;
        cabac.start(data, start, end);
        for (var x = 0; x < widthCtbs; x++) {
            wavefront.awaitAbove(row, x);
            ctbAddrRs = row * widthCtbs + x;
            ctbAddrTs = pps.ctbAddrRsToTs[ctbAddrRs];
            decodeCtu(first && x == 0);
            ctbDone.accept(ctbAddrRs);
            wavefront.advance(row, x + 1);
            if (cabac.decodeTerminate() == 1) {
                storeDependentStates();
                return true;
            }
        }
        if (cabac.decodeTerminate() != 1) {
            throw new MediaException.Malformed("missing end_of_subset_one_bit");
        }
        return false;
    }

    /**
     * Decodes the coding tree blocks of one tile of a slice segment from the substream of the
     * tile, which does not depend on the other tiles of the picture.
     *
     * <p>The slice segment must start at the beginning of a tile of a picture without wavefronts.
     * The slice segment may end before the end of the tile, in its last substream.
     *
     * @param frame the picture being decoded
     * @param pic the coding information of the picture
     * @param header the slice segment header
     * @param refList the reference picture lists of the slice
     * @param sliceIndex the index of the slice in the frame's slice tables
     * @param data the unescaped NAL unit payload, followed by {@link H264BitReader#PADDING}
     * zero bytes
     * @param start the index of the first byte of the tile's substream
     * @param end the index one past the last byte of the tile's substream
     * @param tile the tile index, {@code TileId}
     * @param first whether the tile is the first of the slice segment
     * @param ctbDone receives the raster address of every coding tree block once it is decoded
     * @return whether the slice segment ended within the tile
     * @throws MediaException.Malformed if the substream is corrupt
     */
    public boolean decodeTile(HevcFrame frame, PictureState pic, HevcSliceHeader header, HevcFrame[][] refList,
                              int sliceIndex, byte[] data, int start, int end, int tile, boolean first,
                              IntConsumer ctbDone) {
        prepare(frame, pic, header, refList, sliceIndex);
        cabac.start(data, start, end);
        var firstTs = pps.tileStartTs[tile];
        var lastTs = pps.tileStartTs[tile + 1];
        for (var ts = firstTs; ts < lastTs; ts++) {
            ctbAddrTs = ts;
            ctbAddrRs = pps.ctbAddrTsToRs[ts];
            decodeCtu(first && ts == firstTs);
            ctbDone.accept(ctbAddrRs);
            if (cabac.decodeTerminate() == 1) {
                storeDependentStates();
                return true;
            }
        }
        if (cabac.decodeTerminate() != 1) {
            throw new MediaException.Malformed("missing end_of_subset_one_bit");
        }
        return false;
    }

    /**
     * Prepares the decoder for a slice segment.
     *
     * @param frame the picture being decoded
     * @param pic the coding information of the picture
     * @param header the slice segment header
     * @param refList the reference picture lists of the slice
     * @param sliceIndex the index of the slice in the frame's slice tables
     */
    private void prepare(HevcFrame frame, PictureState pic, HevcSliceHeader header, HevcFrame[][] refList,
                         int sliceIndex) {
        this.frame = frame;
        this.pic = pic;
        this.header = header;
        this.sps = header.sps;
        this.pps = header.pps;
        this.refList = refList;
        this.sliceIndex = sliceIndex;
        this.log2MinCuQpDeltaSize = sps.log2CtbSize - pps.diffCuQpDeltaDepth;
        this.log2MinCuChromaQpOffsetSize = sps.log2CtbSize - pps.diffCuChromaQpOffsetDepth;
        this.cuQpOffsetCb = 0;
        this.cuQpOffsetCr = 0;
        this.initType = header.sliceType == HevcSliceHeader.I ? 0
                : header.sliceType == HevcSliceHeader.P ? (header.cabacInit ? 2 : 1) : (header.cabacInit ? 1 : 2);
        java.util.Arrays.fill(coefficients, 0);
        this.tools3d = header.tools3d;
        this.views = header.interView;
        this.texturePicture = header.texturePicture;
        this.cqtCuPartPred = false;
        if (tools3d != null) {
            check3dTools(tools3d, sps);
            if (views != null) {
                pic.prepareViews();
            }
            if (tools3d.depth) {
                depthIntra.setLookUpTable(tools3d.dltVal, sps.bitDepthLuma);
                cqtCuPartPred = tools3d.cqtCuPartPred;
            }
        }
        recordShapes = header.textureOfDepth;
        if (recordShapes && (frame.cuShape == null || frame.cuShape.length != frame.width4 * frame.height4)) {
            frame.cuShape = new byte[frame.width4 * frame.height4];
        }
        motion.setSlice(frame, pic, header, refList, sliceIndex);
        inter.setSlice(header, refList, pic.dsp);
    }

    /**
     * Rejects the 3D-HEVC tools of a slice on sample formats Annex I does not specify them for,
     * which the 3D Main profile excludes: the depth coding tools on a depth layer with chroma
     * samples, and depth-based block partitioning on a 4:2:2 texture layer, whose chroma boundary
     * filtering (equation I-279) reads the partition pattern outside the coding block.
     *
     * @param tools the 3D-HEVC part of the slice segment header
     * @param sps the active sequence parameter set
     * @throws MediaException.Unsupported if the slice enables such a tool on such a layer
     */
    private static void check3dTools(Hevc3dSlice tools, HevcSps sps) {
        if (tools.depth && sps.chromaArrayType != 0 && (tools.intraContour || tools.intraDcOnlyWedge
                || tools.interDcOnly || tools.skipIntra)) {
            throw new MediaException.Unsupported("hevc 3D depth coding tools",
                    "depth intra modes or DC coding in a depth layer with chroma samples");
        }
        if (tools.dbbp && sps.chromaArrayType == 2) {
            throw new MediaException.Unsupported("hevc 3D depth-based block partitioning",
                    "depth-based block partitioning in a 4:2:2 texture layer");
        }
    }

    /**
     * Decodes the coding tree unit at {@link #ctbAddrRs} and {@link #ctbAddrTs}, with the context
     * initialization or synchronization that precedes it and the wavefront storage that follows
     * it.
     *
     * @param firstInSegment whether the coding tree unit is the first of the slice segment
     * @throws MediaException.Malformed if the coding tree unit is corrupt or was decoded already
     */
    private void decodeCtu(boolean firstInSegment) {
        if (pic.ctbSliceAddr[ctbAddrRs] >= 0) {
            throw new MediaException.Malformed("coding tree block decoded twice");
        }
        currentTile = pps.tileId[ctbAddrTs];
        initContexts(firstInSegment);
        pic.ctbSliceAddr[ctbAddrRs] = header.sliceAddressRs;
        pic.ctbHeader[ctbAddrRs] = header;
        frame.ctbSlice[ctbAddrRs] = sliceIndex;
        codingTreeUnit();
        if (pps.entropyCodingSync && isSecondCtbOfTileRow()) {
            var row = ctbAddrRs / sps.widthInCtbs;
            System.arraycopy(cabac.states, 0, pic.wppStates, row * CabacTables.COUNT, CabacTables.COUNT);
            System.arraycopy(statCoeff, 0, pic.wppStatCoeff, row * 4, 4);
            if (sps.paletteModeEnabled) {
                pic.wppPaletteSize[row] = palette.store(pic.wppPalette, row * pic.paletteStride,
                        sps.paletteMaxPredictorSize);
            }
        }
        saveBottomLine();
        if (cabac.overrun()) {
            throw new MediaException.Malformed("truncated slice segment data");
        }
    }

    /**
     * Copies the bottom sample line of the coding tree block just decoded, before any in-loop
     * filter changes it, for the intra prediction of the row below.
     */
    private void saveBottomLine() {
        var widthCtbs = sps.widthInCtbs;
        var row = ctbAddrRs / widthCtbs;
        if (row + 1 >= sps.heightInCtbs) {
            return;
        }
        var col = ctbAddrRs - row * widthCtbs;
        var shift = sps.log2CtbSize;
        var planes = sps.chromaArrayType == 0 ? 1 : 3;
        for (var c = 0; c < planes; c++) {
            var sx = c == 0 ? 0 : sps.chromaShiftX;
            var sy = c == 0 ? 0 : sps.chromaShiftY;
            var stride = c == 0 ? frame.width : frame.chromaWidth;
            var x0 = (col << shift) >> sx;
            var x1 = Math.min(((col + 1) << shift) >> sx, stride);
            var from = ((((row + 1) << shift) >> sy) - 1) * stride + x0;
            if (frame.edge8 != null) {
                var plane = c == 0 ? frame.y8 : c == 1 ? frame.cb8 : frame.cr8;
                System.arraycopy(plane, from, frame.edge8[c], row * stride + x0, x1 - x0);
            } else {
                var plane = c == 0 ? frame.y : c == 1 ? frame.cb : frame.cr;
                System.arraycopy(plane, from, frame.edge16[c], row * stride + x0, x1 - x0);
            }
        }
    }

    /**
     * Stores the context variables and the quantization parameter at the end of a slice segment
     * for a dependent slice segment that may follow it.
     */
    private void storeDependentStates() {
        if (pps.dependentSliceSegmentsEnabled) {
            System.arraycopy(cabac.states, 0, pic.dependentStates, 0, CabacTables.COUNT);
            System.arraycopy(statCoeff, 0, pic.dependentStatCoeff, 0, 4);
            pic.dependentQp = lastQpY;
            if (sps.paletteModeEnabled) {
                pic.dependentPaletteSize = palette.store(pic.dependentPalette, 0, sps.paletteMaxPredictorSize);
            }
        }
    }

    /**
     * Returns whether the current coding tree block is the second of a row of its tile, after
     * which the wavefront contexts are stored.
     *
     * @return {@code true} for the second coding tree block of a tile row
     */
    private boolean isSecondCtbOfTileRow() {
        var widthCtbs = sps.widthInCtbs;
        if (ctbAddrRs % widthCtbs == 1) {
            return true;
        }
        return ctbAddrRs > 1 && ctbAddrRs % widthCtbs >= 2
                && currentTile != pps.tileId[pps.ctbAddrRsToTs[ctbAddrRs - 2]];
    }

    /**
     * Initializes or synchronizes the context variables at the start of a coding tree unit when
     * the specification requires it (ITU-T H.265 9.3.1 and 9.3.2.1), and resets the quantization
     * parameter prediction at slice, tile and wavefront row starts.
     *
     * @param firstInSegment whether the coding tree unit is the first of the slice segment
     */
    private void initContexts(boolean firstInSegment) {
        var widthCtbs = sps.widthInCtbs;
        var firstInTile = ctbAddrTs == 0 || pps.tileId[ctbAddrTs] != pps.tileId[ctbAddrTs - 1];
        var rowStart = pps.entropyCodingSync && (ctbAddrRs % widthCtbs == 0
                || currentTile != pps.tileId[pps.ctbAddrRsToTs[ctbAddrRs - 1]]);
        if (firstInTile) {
            initializeContexts();
            firstQuantGroup = true;
        } else if (rowStart) {
            var ctbSize = sps.ctbSize;
            var x0 = (ctbAddrRs % widthCtbs) << sps.log2CtbSize;
            var y0 = (ctbAddrRs / widthCtbs) << sps.log2CtbSize;
            if (available(x0, y0, x0 + ctbSize, y0 - ctbSize)) {
                var above = ctbAddrRs / widthCtbs - 1;
                System.arraycopy(pic.wppStates, above * CabacTables.COUNT, cabac.states, 0, CabacTables.COUNT);
                System.arraycopy(pic.wppStatCoeff, above * 4, statCoeff, 0, 4);
                if (sps.paletteModeEnabled) {
                    palette.load(pic.wppPalette, above * pic.paletteStride, sps.paletteMaxPredictorSize,
                            pic.wppPaletteSize[above]);
                }
            } else {
                initializeContexts();
            }
            firstQuantGroup = true;
        } else if (firstInSegment) {
            if (header.dependentSliceSegment) {
                System.arraycopy(pic.dependentStates, 0, cabac.states, 0, CabacTables.COUNT);
                System.arraycopy(pic.dependentStatCoeff, 0, statCoeff, 0, 4);
                lastQpY = pic.dependentQp;
                if (sps.paletteModeEnabled) {
                    palette.load(pic.dependentPalette, 0, sps.paletteMaxPredictorSize, pic.dependentPaletteSize);
                }
                firstQuantGroup = false;
            } else {
                initializeContexts();
                firstQuantGroup = true;
            }
        }
        if (firstInSegment && !header.dependentSliceSegment) {
            firstQuantGroup = true;
        }
    }

    /**
     * Initializes the context variables for the slice and clears the Rice parameter statistics
     * (ITU-T H.265 9.3.2.2).
     */
    private void initializeContexts() {
        CabacTables.init(initType, header.sliceQp, cabac.states);
        java.util.Arrays.fill(statCoeff, 0);
        if (sps.paletteModeEnabled) {
            palette.initialize(sps, pps);
        }
    }

    /**
     * Returns whether a neighboring location is available for the current location, the z-scan
     * order availability of ITU-T H.265 6.4.1.
     *
     * @param xCurr the current luma x
     * @param yCurr the current luma y
     * @param xNb the neighboring luma x
     * @param yNb the neighboring luma y
     * @return {@code true} when the neighbor is decoded, in the same slice and in the same tile
     */
    @Override
    public boolean available(int xCurr, int yCurr, int xNb, int yNb) {
        if (xNb < 0 || yNb < 0 || xNb >= sps.width || yNb >= sps.height) {
            return false;
        }
        var log2 = sps.log2MinTbSize;
        var zs = pps.minTbAddrZs;
        var stride = pps.widthInMinTbs;
        if (zs[(yNb >> log2) * stride + (xNb >> log2)] > zs[(yCurr >> log2) * stride + (xCurr >> log2)]) {
            return false;
        }
        var ctb = (yNb >> sps.log2CtbSize) * sps.widthInCtbs + (xNb >> sps.log2CtbSize);
        return pic.ctbSliceAddr[ctb] == header.sliceAddressRs && pps.tileId[pps.ctbAddrRsToTs[ctb]] == currentTile;
    }

    /**
     * Parses and reconstructs one coding tree unit (7.3.8.2).
     */
    private void codingTreeUnit() {
        var widthCtbs = sps.widthInCtbs;
        var rx = ctbAddrRs % widthCtbs;
        var ry = ctbAddrRs / widthCtbs;
        if (header.saoLuma || header.saoChroma) {
            sao(rx, ry);
        }
        codingQuadtree(rx << sps.log2CtbSize, ry << sps.log2CtbSize, sps.log2CtbSize, 0);
    }

    /**
     * Parses the sample adaptive offset parameters of a coding tree block (7.3.8.3).
     *
     * @param rx the coding tree block column
     * @param ry the coding tree block row
     */
    private void sao(int rx, int ry) {
        var widthCtbs = sps.widthInCtbs;
        var size = sps.sizeInCtbs;
        if (rx > 0) {
            var leftInSlice = ctbAddrRs > header.sliceAddressRs;
            var leftInTile = currentTile == pps.tileId[pps.ctbAddrRsToTs[ctbAddrRs - 1]];
            if (leftInSlice && leftInTile && cabac.decodeBin(CabacTables.SAO_MERGE) == 1) {
                copySao(ctbAddrRs - 1);
                return;
            }
        }
        if (ry > 0) {
            var upInSlice = ctbAddrRs - widthCtbs >= header.sliceAddressRs;
            var upInTile = currentTile == pps.tileId[pps.ctbAddrRsToTs[ctbAddrRs - widthCtbs]];
            if (upInSlice && upInTile && cabac.decodeBin(CabacTables.SAO_MERGE) == 1) {
                copySao(ctbAddrRs - widthCtbs);
                return;
            }
        }
        var components = sps.chromaArrayType != 0 ? 3 : 1;
        for (var c = 0; c < 3; c++) {
            pic.saoType[c * size + ctbAddrRs] = 0;
        }
        for (var c = 0; c < components; c++) {
            if (!(c == 0 ? header.saoLuma : header.saoChroma)) {
                continue;
            }
            var index = c * size + ctbAddrRs;
            int type;
            if (c == 2) {
                type = pic.saoType[size + ctbAddrRs];
            } else {
                type = 0;
                if (cabac.decodeBin(CabacTables.SAO_TYPE) == 1) {
                    type = cabac.decodeBypass() == 0 ? 1 : 2;
                }
            }
            pic.saoType[index] = (byte) type;
            if (type == 0) {
                continue;
            }
            var bitDepth = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            var cMax = (1 << (Math.min(bitDepth, 10) - 5)) - 1;
            var offsets = saoOffsets;
            for (var i = 0; i < 4; i++) {
                var value = 0;
                while (value < cMax && cabac.decodeBypass() == 1) {
                    value++;
                }
                offsets[i] = value;
            }
            if (type == 1) {
                for (var i = 0; i < 4; i++) {
                    if (offsets[i] != 0 && cabac.decodeBypass() == 1) {
                        offsets[i] = -offsets[i];
                    }
                }
                pic.saoClass[index] = (byte) cabac.decodeBypassBins(5);
            } else {
                offsets[2] = -offsets[2];
                offsets[3] = -offsets[3];
                if (c == 0) {
                    pic.saoClass[index] = (byte) cabac.decodeBypassBins(2);
                } else if (c == 1) {
                    pic.saoClass[index] = (byte) cabac.decodeBypassBins(2);
                } else {
                    pic.saoClass[index] = pic.saoClass[size + ctbAddrRs];
                }
            }
            var scale = c == 0 ? pps.log2SaoOffsetScaleLuma : pps.log2SaoOffsetScaleChroma;
            for (var i = 0; i < 4; i++) {
                pic.saoOffset[index * 4 + i] = (short) (offsets[i] << scale);
            }
        }
    }

    /**
     * Copies the sample adaptive offset parameters of another coding tree block, for
     * {@code sao_merge_left_flag} and {@code sao_merge_up_flag}.
     *
     * @param source the raster address of the coding tree block to copy
     */
    private void copySao(int source) {
        var size = sps.sizeInCtbs;
        for (var c = 0; c < 3; c++) {
            var from = c * size + source;
            var to = c * size + ctbAddrRs;
            pic.saoType[to] = pic.saoType[from];
            pic.saoClass[to] = pic.saoClass[from];
            System.arraycopy(pic.saoOffset, from * 4, pic.saoOffset, to * 4, 4);
        }
    }

    /**
     * Parses a coding quadtree (7.3.8.4).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param log2Size the block size as a power of two
     * @param depth {@code cqtDepth}
     */
    private void codingQuadtree(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        boolean split;
        var inside = x0 + size <= sps.width && y0 + size <= sps.height;
        if (inside && log2Size > sps.log2MinCbSize && cqtCuPartPred
                && log2Size <= textureShape(x0, y0) >> TEXTURE_LOG2_SHIFT) {
            // predSplitCuFlag: the coding unit is not split below the size of the collocated texture coding unit
            split = false;
        } else if (inside && log2Size > sps.log2MinCbSize) {
            var ctx = 0;
            if (available(x0, y0, x0 - 1, y0) && pic.depth[blockIndex(x0 - 1, y0)] > depth) {
                ctx++;
            }
            if (available(x0, y0, x0, y0 - 1) && pic.depth[blockIndex(x0, y0 - 1)] > depth) {
                ctx++;
            }
            split = cabac.decodeBin(CabacTables.SPLIT_CU + ctx) == 1;
        } else {
            split = log2Size > sps.log2MinCbSize;
        }
        if (log2Size >= log2MinCuQpDeltaSize) {
            startQuantGroup(x0, y0);
        }
        if (header.cuChromaQpOffsetEnabled && log2Size >= log2MinCuChromaQpOffsetSize) {
            chromaQpOffsetCoded = false;
        }
        if (split) {
            var half = size >> 1;
            var x1 = x0 + half;
            var y1 = y0 + half;
            codingQuadtree(x0, y0, log2Size - 1, depth + 1);
            if (x1 < sps.width) {
                codingQuadtree(x1, y0, log2Size - 1, depth + 1);
            }
            if (y1 < sps.height) {
                codingQuadtree(x0, y1, log2Size - 1, depth + 1);
            }
            if (x1 < sps.width && y1 < sps.height) {
                codingQuadtree(x1, y1, log2Size - 1, depth + 1);
            }
        } else {
            codingUnit(x0, y0, log2Size, depth);
        }
    }

    /**
     * Starts a quantization group: resets {@code IsCuQpDeltaCoded} and {@code CuQpDeltaVal} and
     * derives {@code qPY_PRED} (8.6.1).
     *
     * @param xQg the luma x of the quantization group
     * @param yQg the luma y of the quantization group
     */
    private void startQuantGroup(int xQg, int yQg) {
        cuQpDeltaCoded = false;
        var previous = firstQuantGroup ? header.sliceQp : lastQpY;
        var qpA = previous;
        var qpB = previous;
        var ctbMask = ~((1 << sps.log2CtbSize) - 1);
        if (available(xQg, yQg, xQg - 1, yQg) && ((xQg - 1) & ctbMask) == (xQg & ctbMask)) {
            qpA = pic.qp[blockIndex(xQg - 1, yQg)];
        }
        if (available(xQg, yQg, xQg, yQg - 1) && ((yQg - 1) & ctbMask) == (yQg & ctbMask)) {
            qpB = pic.qp[blockIndex(xQg, yQg - 1)];
        }
        qpYPred = (qpA + qpB + 1) >> 1;
        qpY = qpYPred;
    }

    /**
     * Returns the index of the 4x4 block covering a luma location in the block arrays.
     *
     * @param x the luma x
     * @param y the luma y
     * @return the block index
     */
    private int blockIndex(int x, int y) {
        return (y >> 2) * pic.width4 + (x >> 2);
    }

    /**
     * Parses and reconstructs a coding unit (7.3.8.5 and I.7.3.8.5).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param depth {@code cqtDepth}
     */
    private void codingUnit(int x0, int y0, int log2Size, int depth) {
        var size = 1 << log2Size;
        cuX = x0;
        cuY = y0;
        cuSize = size;
        dcOnly = false;
        dbbpFlag = false;
        java.util.Arrays.fill(dimMode, 0);
        bypass = pps.transquantBypassEnabled && cabac.decodeBin(CabacTables.TRANSQUANT_BYPASS) == 1;
        var skip = false;
        if (header.sliceType != HevcSliceHeader.I) {
            var ctx = 0;
            if (available(x0, y0, x0 - 1, y0) && (pic.flags[blockIndex(x0 - 1, y0)] & PictureState.SKIP) != 0) {
                ctx++;
            }
            if (available(x0, y0, x0, y0 - 1) && (pic.flags[blockIndex(x0, y0 - 1)] & PictureState.SKIP) != 0) {
                ctx++;
            }
            skip = cabac.decodeBin(CabacTables.CU_SKIP + ctx) == 1;
        }
        var flags = (bypass ? PictureState.BYPASS : 0) | (skip ? PictureState.SKIP : 0);
        markCodingUnit(x0, y0, size, flags, depth);
        markEdges(x0, y0, size, size, PictureState.EDGE_TU_V | PictureState.EDGE_PU_V,
                PictureState.EDGE_TU_H | PictureState.EDGE_PU_H);
        var pcm = false;
        var partMode = PART_2Nx2N;
        if (skip) {
            cuIntra = false;
            puMotionPartitioned = false;
            recordShape(x0, y0, log2Size, PART_2Nx2N);
            parsePredictionUnit(0, x0, y0, size, size, x0, y0, true, PART_2Nx2N);
            finishInterUnit(x0, y0, log2Size, PART_2Nx2N, 1, true);
        } else if (tools3d != null && tools3d.skipIntra && cabac.decodeBin(CabacTables.SKIP_INTRA) == 1) {
            cuIntra = true;
            recordShape(x0, y0, log2Size, PART_2Nx2N);
            skipIntraUnit(x0, y0, log2Size);
        } else {
            cuIntra = header.sliceType == HevcSliceHeader.I || cabac.decodeBin(CabacTables.PRED_MODE) == 1;
            if (cuIntra && sps.paletteModeEnabled && log2Size <= sps.log2MaxTbSize
                    && cabac.decodeBin(CabacTables.PALETTE_MODE) == 1) {
                recordShape(x0, y0, log2Size, PART_2Nx2N);
                paletteCodingUnit(x0, y0, log2Size);
                finishCodingUnit(x0, y0, size);
                return;
            }
            var partPredIdc = 0;
            var predPartMode = false;
            if (cqtCuPartPred) {
                var shape = textureShape(x0, y0);
                var texturePart = shape & TEXTURE_PART_MASK;
                if (shape >> TEXTURE_LOG2_SHIFT == log2Size) {
                    predPartMode = texturePart == PART_2Nx2N;
                    if (texturePart == PART_2NxN || texturePart == PART_2NxnU || texturePart == PART_2NxnD) {
                        partPredIdc = 1;
                    } else if (texturePart != PART_2Nx2N && texturePart != PART_NxN) {
                        partPredIdc = 2;
                    }
                }
            }
            if ((!cuIntra || log2Size == sps.log2MinCbSize) && !predPartMode) {
                partMode = cuIntra || partPredIdc == 0 ? partMode(log2Size) : predictedPartMode(log2Size, partPredIdc);
            }
            intraSplit = cuIntra && partMode == PART_NxN;
            puMotionPartitioned = !cuIntra && partMode != PART_2Nx2N;
            recordShape(x0, y0, log2Size, partMode);
            if (cuIntra) {
                markIntra(x0, y0, size);
                if (partMode == PART_2Nx2N && sps.pcmEnabled && log2Size >= sps.log2MinPcmCbSize
                        && log2Size <= sps.log2MaxPcmCbSize) {
                    pcm = cabac.decodeTerminate() == 1;
                }
                if (pcm) {
                    pcmSample(x0, y0, log2Size);
                } else {
                    intraModes(x0, y0, log2Size, partMode);
                }
                if (tools3d != null && tools3d.intraDcOnlyWedge && partMode == PART_2Nx2N) {
                    dcOnly = cabac.decodeBin(CabacTables.DC_ONLY) == 1;
                }
                if (pcm && dcOnly) {
                    throw new MediaException.Malformed("dc_only_flag in a PCM coding unit");
                }
                if (!pcm) {
                    depthDcs(log2Size, partMode);
                }
            } else {
                interPredictionUnits(x0, y0, log2Size, partMode);
                if (dcOnly) {
                    depthDcs(log2Size, partMode);
                }
            }
            if (!pcm) {
                if (dcOnly) {
                    dcOnlyUnit(x0, y0, log2Size);
                } else {
                    var rootCbf = true;
                    if (!cuIntra && !(partMode == PART_2Nx2N && puMotions[0].merge)) {
                        rootCbf = cabac.decodeBin(CabacTables.RQT_ROOT_CBF) == 1;
                    }
                    if (rootCbf) {
                        maxTrafoDepth = cuIntra ? sps.maxTransformHierarchyDepthIntra + (intraSplit ? 1 : 0)
                                : sps.maxTransformHierarchyDepthInter;
                        transformTree(x0, y0, x0, y0, log2Size, 0, 0, 0, 0);
                    } else if (cuIntra) {
                        throw new MediaException.Malformed("intra coding unit without transform tree");
                    }
                }
            }
        }
        finishCodingUnit(x0, y0, size);
    }

    /**
     * Parses {@code part_mode} of an inter coding unit whose partitioning is predicted from the
     * collocated texture coding unit (I.9.3.3.6): up to two context coded bins choose between
     * 2Nx2N, the symmetric partitioning of the texture's direction and, with asymmetric motion
     * partitions, a bypass bin between the two asymmetric ones.
     *
     * @param log2Size the coding block size as a power of two
     * @param partPredIdc 1 for the horizontal partitionings, 2 for the vertical ones
     * @return the partitioning mode
     */
    private int predictedPartMode(int log2Size, int partPredIdc) {
        if (cabac.decodeBin(CabacTables.PART_MODE) == 1) {
            return PART_2Nx2N;
        }
        var horizontal = partPredIdc == 1;
        if (!sps.ampEnabled || log2Size == sps.log2MinCbSize || cabac.decodeBin(CabacTables.PART_MODE + 1) == 1) {
            return horizontal ? PART_2NxN : PART_Nx2N;
        }
        var second = cabac.decodeBypass() == 1;
        if (horizontal) {
            return second ? PART_2NxnD : PART_2NxnU;
        }
        return second ? PART_nRx2N : PART_nLx2N;
    }

    /**
     * Records the size and partitioning of a coding unit of a texture layer of 3D-HEVC, which the
     * depth layer of its view predicts its coding quadtree from (I.7.4.9.4 and I.7.4.9.5).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     */
    private void recordShape(int x0, int y0, int log2Size, int partMode) {
        if (!recordShapes) {
            return;
        }
        var shapes = frame.cuShape;
        var w4 = frame.width4;
        var blocks = 1 << (log2Size - 2);
        var start = blockIndex(x0, y0);
        var value = (byte) (log2Size << TEXTURE_LOG2_SHIFT | partMode);
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            java.util.Arrays.fill(shapes, row, row + blocks, value);
        }
    }

    /**
     * {@return the size and partitioning the collocated coding unit of the texture picture
     * recorded}, packed as {@link #recordShape} stores them
     *
     * @param x the luma x
     * @param y the luma y
     * @throws MediaException.Malformed if the texture picture of the view is missing
     */
    private int textureShape(int x, int y) {
        var texture = texturePicture;
        if (texture == null) {
            throw new MediaException.Malformed("depth coding unit predicted from a missing texture picture");
        }
        texture.awaitDecodedRows(y + 1);
        var shapes = texture.cuShape;
        if (shapes == null) {
            throw new MediaException.Malformed(
                    "depth coding unit predicted from a texture picture without coding units");
        }
        return shapes[(y >> 2) * texture.width4 + (x >> 2)];
    }

    /**
     * Parses and reconstructs a coding unit coded with {@code skip_intra_flag} (I.7.3.8.5.2 and
     * I.8.4.4.2.4): the vertical or horizontal intra mode, or the single sample of the left or
     * the above neighbor, over the whole coding block without a residual.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     */
    private void skipIntraUnit(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        markIntra(x0, y0, size);
        intraSplit = false;
        // the reference decoder records INTRA_DC as the mode the neighbors predict their modes from
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            java.util.Arrays.fill(pic.intraMode, start + j * w4, start + j * w4 + blocks, (byte) 1);
        }
        var mode = 0;
        if (cabac.decodeBin(CabacTables.SKIP_INTRA_MODE) == 1) {
            mode = 1;
            while (mode < 3 && cabac.decodeBypass() == 1) {
                mode++;
            }
        }
        if (mode < 2) {
            if (log2Size <= 5) {
                predictIntra(x0, y0, log2Size, 0, mode == 0 ? 26 : 10);
            } else {
                gatherBoundary(x0, y0, log2Size, 0, true);
                Hevc3dIntra.predictCopy(boundary, size, mode == 0, frame, x0, y0);
            }
            return;
        }
        gatherBoundary(x0, y0, log2Size, 0, false);
        var corner = 2 * size;
        var index = mode == 2 ? corner - 1 - (size >> 1) : corner + 1 + (size >> 1);
        var value = boundaryAvailable[index] ? boundary[index] : 1 << (sps.bitDepthLuma - 1);
        Hevc3dIntra.fill(frame, x0, y0, size, value);
    }

    /**
     * Parses a {@code depth_dcs( )} structure (I.7.3.8.5.3) and derives {@code DcOffset} of each
     * prediction block coded with a depth intra mode or of a coding unit coded with
     * {@code dc_only_flag} (equation I-51).
     *
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     */
    private void depthDcs(int log2Size, int partMode) {
        var count = cuIntra && partMode == PART_NxN ? 4 : 1;
        for (var k = 0; k < count; k++) {
            dcOffsets[k][0] = 0;
            dcOffsets[k][1] = 0;
            if (dimMode[k] == 0 && !dcOnly) {
                continue;
            }
            var present = !(cuIntra && dcOnly) || cabac.decodeBin(CabacTables.DEPTH_DC_PRESENT) == 1;
            if (!present) {
                continue;
            }
            var segments = dimMode[k] != 0 ? 2 : 1;
            for (var i = 0; i < segments; i++) {
                var abs = 0;
                while (abs < 3 && cabac.decodeBin(CabacTables.DEPTH_DC_ABS) == 1) {
                    abs++;
                }
                if (abs == 3) {
                    abs += expGolomb(0);
                }
                var magnitude = abs - segments + 2;
                if (magnitude > 0 && cabac.decodeBypass() == 1) {
                    magnitude = -magnitude;
                }
                dcOffsets[k][i] = magnitude;
            }
        }
    }

    /**
     * Reconstructs a coding unit coded with {@code dc_only_flag}: an intra coding unit is
     * predicted, from a partition pattern with the offsets of its segments or with its intra mode
     * and the offset of the whole block (I.8.4.4.1 and I.8.4.4.3), and an inter coding unit takes
     * the offset as its residual (I.8.5.4).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     */
    private void dcOnlyUnit(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        markCoded(x0, y0, size);
        if (!cuIntra) {
            Hevc3dIntra.addConstant(frame, x0, y0, size, dcOffsets[0][0], (1 << sps.bitDepthLuma) - 1);
            return;
        }
        if (dimMode[0] != 0) {
            gatherBoundary(x0, y0, log2Size, 0, true);
            depthIntra.predictSegments(boundary, size, partition(0, x0, y0, log2Size), dcOffsets[0][0],
                    dcOffsets[0][1], frame, x0, y0);
            return;
        }
        predictDcOnly(x0, y0, log2Size, pic.intraMode[blockIndex(x0, y0)]);
        depthIntra.addDcOffset(frame, x0, y0, size, dcOffsets[0][0]);
    }

    /**
     * Predicts the luma samples of an intra coding unit coded with {@code dc_only_flag} and an
     * intra mode of the base specification, in transform blocks of at most the largest transform
     * size, each predicted from the predicted samples of the blocks before it (I.8.4.4.1).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param log2Size the block size as a power of two
     * @param mode the intra prediction mode
     */
    private void predictDcOnly(int x0, int y0, int log2Size, int mode) {
        if (log2Size > sps.log2MaxTbSize) {
            var half = 1 << (log2Size - 1);
            predictDcOnly(x0, y0, log2Size - 1, mode);
            predictDcOnly(x0 + half, y0, log2Size - 1, mode);
            predictDcOnly(x0, y0 + half, log2Size - 1, mode);
            predictDcOnly(x0 + half, y0 + half, log2Size - 1, mode);
            return;
        }
        predictIntra(x0, y0, log2Size, 0, mode);
    }

    /**
     * Sets the {@link PictureState#CODED} flag of a block, as the reference decoder records a
     * coded luma block for the coding units coded with {@code dc_only_flag}.
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param size the block size
     */
    private void markCoded(int x0, int y0, int size) {
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            for (var i = 0; i < blocks; i++) {
                pic.flags[start + j * w4 + i] |= PictureState.CODED;
            }
        }
    }

    /**
     * Returns the partition pattern of a prediction block coded with a depth intra mode: the
     * wedgelet pattern of its {@code wedge_full_tab_idx}, or the contour pattern of the
     * collocated texture block.
     *
     * @param pb the prediction block within the coding unit, 0 to 3
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @param log2Size the block size as a power of two
     * @return the pattern, in raster order
     * @throws MediaException.Malformed if the texture picture of a contour block is missing
     */
    private byte[] partition(int pb, int x, int y, int log2Size) {
        if (dimMode[pb] == INTRA_WEDGE) {
            return Hevc3dIntra.wedge(log2Size, wedgeIdx[pb]);
        }
        if (texturePicture == null) {
            throw new MediaException.Malformed("INTRA_CONTOUR without the texture picture of the view");
        }
        return depthIntra.contour(texturePicture, x, y, 1 << log2Size);
    }

    /**
     * Parses and reconstructs a coding unit coded in palette mode (7.3.8.13 and 8.4.4.2.7), then
     * updates the palette predictor.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     */
    private void paletteCodingUnit(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        markIntra(x0, y0, size);
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            for (var i = 0; i < blocks; i++) {
                pic.flags[start + j * w4 + i] |= PictureState.PALETTE;
                pic.intraMode[start + j * w4 + i] = 1;
            }
        }
        var escape = palette.parseHeader(cabac, sps, log2Size);
        if (escape) {
            deltaQp();
            if (!bypass && header.cuChromaQpOffsetEnabled && !chromaQpOffsetCoded) {
                chromaQpOffset();
            }
        }
        palette.parseIndices(cabac);
        palette.parseEscapes(cabac, sps, bypass);
        var components = sps.chromaArrayType == 0 ? 1 : 3;
        for (var c = 0; c < components; c++) {
            paletteQp[c] = escape && !bypass ? quantizer(c) : 0;
        }
        palette.reconstruct(frame, sps, x0, y0, bypass, paletteQp);
        palette.updatePredictor(sps.paletteMaxPredictorSize);
    }

    /**
     * Parses a {@code delta_qp()} structure (7.3.8.14) when the quantization group has no
     * {@code CuQpDeltaVal} yet, and derives {@code QpY} (equation 8-283).
     *
     * @throws MediaException.Malformed if {@code CuQpDeltaVal} is out of range
     */
    private void deltaQp() {
        if (!pps.cuQpDeltaEnabled || cuQpDeltaCoded) {
            return;
        }
        var abs = 0;
        var ctx = CabacTables.CU_QP_DELTA;
        while (abs < 5 && cabac.decodeBin(ctx) == 1) {
            abs++;
            ctx = CabacTables.CU_QP_DELTA + 1;
        }
        if (abs == 5) {
            abs += expGolomb(0);
        }
        var value = abs;
        if (abs > 0 && cabac.decodeBypass() == 1) {
            value = -abs;
        }
        var qpBdOffset = 6 * (sps.bitDepthLuma - 8);
        if (value < -(26 + qpBdOffset / 2) || value > 25 + qpBdOffset / 2) {
            throw new MediaException.Malformed("CuQpDeltaVal out of range");
        }
        cuQpDeltaCoded = true;
        qpY = ((qpYPred + value + 52 + 2 * qpBdOffset) % (52 + qpBdOffset)) - qpBdOffset;
    }

    /**
     * Writes the flags and the coding tree depth of a coding unit to the block arrays.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     * @param flags the coding unit flags
     * @param depth the coding tree depth
     */
    private void markCodingUnit(int x0, int y0, int size, int flags, int depth) {
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            java.util.Arrays.fill(pic.flags, row, row + blocks, (byte) flags);
            java.util.Arrays.fill(pic.depth, row, row + blocks, (byte) depth);
        }
    }

    /**
     * Sets the {@link PictureState#INTRA} flag of a coding unit and clears its motion.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     */
    private void markIntra(int x0, int y0, int size) {
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            for (var i = 0; i < blocks; i++) {
                pic.flags[row + i] |= PictureState.INTRA;
                frame.ref0[row + i] = -1;
                frame.ref1[row + i] = -1;
            }
        }
    }

    /**
     * Records transform or prediction block boundaries on the left and top edges of a block.
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param width the block width
     * @param height the block height
     * @param vertical the flags to set on the left edge
     * @param horizontal the flags to set on the top edge
     */
    private void markEdges(int x0, int y0, int width, int height, int vertical, int horizontal) {
        var w4 = pic.width4;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < height >> 2; j++) {
            pic.edges[start + j * w4] |= (byte) vertical;
        }
        for (var i = 0; i < width >> 2; i++) {
            pic.edges[start + i] |= (byte) horizontal;
        }
    }

    /**
     * Stores the luma quantization parameter of a finished coding unit and remembers it as the last
     * {@code QpY}.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     */
    private void finishCodingUnit(int x0, int y0, int size) {
        var qp = qpY;
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            var row = start + j * w4;
            java.util.Arrays.fill(pic.qp, row, row + blocks, (byte) qp);
        }
        lastQpY = qp;
        firstQuantGroup = false;
    }

    /**
     * Parses {@code part_mode} (Table 9-45).
     *
     * @param log2Size the coding block size as a power of two
     * @return the partitioning mode
     */
    private int partMode(int log2Size) {
        if (cabac.decodeBin(CabacTables.PART_MODE) == 1) {
            return PART_2Nx2N;
        }
        if (cuIntra) {
            return PART_NxN;
        }
        if (log2Size == sps.log2MinCbSize) {
            if (cabac.decodeBin(CabacTables.PART_MODE + 1) == 1) {
                return PART_2NxN;
            }
            if (log2Size == 3) {
                return PART_Nx2N;
            }
            return cabac.decodeBin(CabacTables.PART_MODE + 2) == 1 ? PART_Nx2N : PART_NxN;
        }
        var horizontal = cabac.decodeBin(CabacTables.PART_MODE + 1) == 1;
        if (!sps.ampEnabled) {
            return horizontal ? PART_2NxN : PART_Nx2N;
        }
        if (cabac.decodeBin(CabacTables.PART_MODE + 3) == 1) {
            return horizontal ? PART_2NxN : PART_Nx2N;
        }
        var second = cabac.decodeBypass() == 1;
        if (horizontal) {
            return second ? PART_2NxnD : PART_2NxnU;
        }
        return second ? PART_nRx2N : PART_nLx2N;
    }

    /**
     * Parses and reconstructs the PCM samples of a coding unit (7.3.8.7 and 8.4.4.1).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     */
    private void pcmSample(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        var data = cabac.data();
        var position = cabac.bytePosition();
        var bits = pcmReader;
        bits.init(data, position, Math.max(0, cabac.end() - position));
        var shiftY = sps.bitDepthLuma - sps.pcmBitDepthLuma;
        var eightBit = frame.eightBit();
        for (var y = 0; y < size; y++) {
            var row = (y0 + y) * frame.width + x0;
            for (var x = 0; x < size; x++) {
                var sample = bits.readBits(sps.pcmBitDepthLuma) << shiftY;
                if (eightBit) {
                    frame.y8[row + x] = (byte) sample;
                } else {
                    frame.y[row + x] = (short) sample;
                }
            }
        }
        if (sps.chromaArrayType != 0) {
            var shiftC = sps.bitDepthChroma - sps.pcmBitDepthChroma;
            var w = size >> sps.chromaShiftX;
            var h = size >> sps.chromaShiftY;
            var cx = x0 >> sps.chromaShiftX;
            var cy = y0 >> sps.chromaShiftY;
            for (var plane = 0; plane < 2; plane++) {
                var samples = plane == 0 ? frame.cb : frame.cr;
                var samples8 = plane == 0 ? frame.cb8 : frame.cr8;
                for (var y = 0; y < h; y++) {
                    var row = (cy + y) * frame.chromaWidth + cx;
                    for (var x = 0; x < w; x++) {
                        var sample = bits.readBits(sps.pcmBitDepthChroma) << shiftC;
                        if (eightBit) {
                            samples8[row + x] = (byte) sample;
                        } else {
                            samples[row + x] = (short) sample;
                        }
                    }
                }
            }
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated PCM samples");
        }
        cabac.seek(bits.bytePosition());
        cabac.restart();
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(x0, y0);
        for (var j = 0; j < blocks; j++) {
            for (var i = 0; i < blocks; i++) {
                pic.flags[start + j * w4 + i] |= PictureState.PCM;
                pic.intraMode[start + j * w4 + i] = 1;
            }
        }
    }

    /**
     * Parses the luma and chroma intra prediction modes of a coding unit (7.3.8.5, 8.4.2 and
     * 8.4.3).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     */
    private void intraModes(int x0, int y0, int log2Size, int partMode) {
        var size = 1 << log2Size;
        var count = partMode == PART_NxN ? 4 : 1;
        var offset = partMode == PART_NxN ? size >> 1 : size;
        var prevFlags = intraFlags;
        var depthModes = tools3d != null && (tools3d.intraDcOnlyWedge || tools3d.intraContour);
        for (var i = 0; i < count; i++) {
            if (depthModes) {
                intraModeExtension(i, partMode == PART_NxN ? log2Size - 1 : log2Size);
            }
            prevFlags[i] = dimMode[i] == 0 && cabac.decodeBin(CabacTables.PREV_INTRA_LUMA) == 1;
        }
        for (var i = 0; i < count; i++) {
            var xPb = x0 + (i & 1) * offset;
            var yPb = y0 + (i >> 1) * offset;
            int mode;
            if (dimMode[i] != 0) {
                mode = dimMode[i];
                lumaModes[i] = mode;
                writeIntraMode(xPb, yPb, offset, mode);
                continue;
            }
            var candA = candidateMode(xPb, yPb, xPb - 1, yPb, false);
            var candB = candidateMode(xPb, yPb, xPb, yPb - 1, true);
            int c0;
            int c1;
            int c2;
            if (candA == candB) {
                if (candA < 2) {
                    c0 = 0;
                    c1 = 1;
                    c2 = 26;
                } else {
                    c0 = candA;
                    c1 = 2 + ((candA + 29) % 32);
                    c2 = 2 + ((candA - 2 + 1) % 32);
                }
            } else {
                c0 = candA;
                c1 = candB;
                if (c0 != 0 && c1 != 0) {
                    c2 = 0;
                } else if (c0 != 1 && c1 != 1) {
                    c2 = 1;
                } else {
                    c2 = 26;
                }
            }
            if (prevFlags[i]) {
                var mpmIdx = 0;
                while (mpmIdx < 2 && cabac.decodeBypass() == 1) {
                    mpmIdx++;
                }
                mode = mpmIdx == 0 ? c0 : mpmIdx == 1 ? c1 : c2;
            } else {
                mode = cabac.decodeBypassBins(5);
                if (c0 > c1) {
                    var t = c0;
                    c0 = c1;
                    c1 = t;
                }
                if (c0 > c2) {
                    var t = c0;
                    c0 = c2;
                    c2 = t;
                }
                if (c1 > c2) {
                    var t = c1;
                    c1 = c2;
                    c2 = t;
                }
                if (mode >= c0) {
                    mode++;
                }
                if (mode >= c1) {
                    mode++;
                }
                if (mode >= c2) {
                    mode++;
                }
            }
            lumaModes[i] = mode;
            writeIntraMode(xPb, yPb, offset, mode);
        }
        if (count == 1) {
            lumaModes[1] = lumaModes[2] = lumaModes[3] = lumaModes[0];
        }
        if (sps.chromaArrayType == 3) {
            for (var i = 0; i < count; i++) {
                chromaMode(i, lumaModes[i]);
            }
            if (count == 1) {
                chromaModes[1] = chromaModes[2] = chromaModes[3] = chromaModes[0];
                chromaSyntax[1] = chromaSyntax[2] = chromaSyntax[3] = chromaSyntax[0];
            }
        } else if (sps.chromaArrayType != 0) {
            chromaMode(0, lumaModes[0]);
            chromaModes[1] = chromaModes[2] = chromaModes[3] = chromaModes[0];
            chromaSyntax[1] = chromaSyntax[2] = chromaSyntax[3] = chromaSyntax[0];
        }
    }

    /**
     * Writes {@code IntraPredModeY} of a prediction block to the block arrays.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param size the prediction block size
     * @param mode the intra prediction mode
     */
    private void writeIntraMode(int xPb, int yPb, int size, int mode) {
        var w4 = pic.width4;
        var blocks = size >> 2;
        var start = blockIndex(xPb, yPb);
        for (var j = 0; j < blocks; j++) {
            java.util.Arrays.fill(pic.intraMode, start + j * w4, start + j * w4 + blocks, (byte) mode);
        }
    }

    /**
     * Parses an {@code intra_mode_ext( )} structure (I.7.3.8.5.1) into {@link #dimMode} and
     * {@link #wedgeIdx}.
     *
     * @param pb the prediction block within the coding unit, 0 to 3
     * @param log2PbSize the prediction block size as a power of two
     * @throws MediaException.Malformed if {@code wedge_full_tab_idx} is out of range
     */
    private void intraModeExtension(int pb, int log2PbSize) {
        dimMode[pb] = 0;
        if (log2PbSize >= 6 || cabac.decodeBin(CabacTables.NO_DIM) == 1) {
            return;
        }
        var wedge = tools3d.intraDcOnlyWedge;
        var contour = wedge && tools3d.intraContour ? cabac.decodeBin(CabacTables.DEPTH_INTRA_MODE) == 1
                : !wedge || tools3d.intraContour;
        if (contour) {
            dimMode[pb] = INTRA_CONTOUR;
            return;
        }
        var bits = Hevc3dIntra.wedgeIndexBits(log2PbSize);
        var index = 0;
        // the reference decoder reads the least significant bit first
        for (var b = 0; b < bits; b++) {
            index |= cabac.decodeBypass() << b;
        }
        if (index >= Hevc3dIntra.wedgeCount(log2PbSize)) {
            throw new MediaException.Malformed("wedge_full_tab_idx out of range");
        }
        wedgeIdx[pb] = index;
        dimMode[pb] = INTRA_WEDGE;
    }

    /**
     * Parses one {@code intra_chroma_pred_mode} and derives {@code IntraPredModeC} of a prediction
     * block (8.4.3, Tables 8-2 and 8-3).
     *
     * @param index the prediction block within the coding unit, 0 to 3
     * @param luma {@code IntraPredModeY} of the prediction block
     */
    private void chromaMode(int index, int luma) {
        int syntax;
        if (cabac.decodeBin(CabacTables.INTRA_CHROMA) == 0) {
            syntax = 4;
        } else {
            syntax = cabac.decodeBypassBins(2);
        }
        int mode;
        if (syntax == 4) {
            mode = luma;
        } else {
            var candidate = syntax == 0 ? 0 : syntax == 1 ? 26 : syntax == 2 ? 10 : 1;
            mode = candidate == luma ? 34 : candidate;
        }
        chromaSyntax[index] = syntax;
        chromaModes[index] = sps.chromaArrayType == 2 ? MODE_422[mode] : mode;
    }

    /**
     * Returns the index of the prediction block of the current coding unit that covers a luma
     * location, for the per prediction block chroma modes of 4:4:4 video.
     *
     * @param x the luma x
     * @param y the luma y
     * @return the prediction block index, 0 to 3
     */
    private int partitionAt(int x, int y) {
        var half = cuSize >> 1;
        return (x - cuX >= half ? 1 : 0) + (y - cuY >= half ? 2 : 0);
    }

    /**
     * Returns {@code candIntraPredModeX} of a neighbor (8.4.2 step 2).
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param xNb the luma x of the neighbor
     * @param yNb the luma y of the neighbor
     * @param above whether the neighbor is the one above
     * @return the candidate mode
     */
    private int candidateMode(int xPb, int yPb, int xNb, int yNb, boolean above) {
        if (!available(xPb, yPb, xNb, yNb)) {
            return 1;
        }
        var index = blockIndex(xNb, yNb);
        var flags = pic.flags[index];
        if ((flags & PictureState.INTRA) == 0 || (flags & PictureState.PCM) != 0) {
            return 1;
        }
        if (above && yNb < ((yPb >> sps.log2CtbSize) << sps.log2CtbSize)) {
            return 1;
        }
        var mode = pic.intraMode[index];
        return mode > 34 ? 1 : mode;
    }

    /**
     * Parses and predicts the prediction units of an inter coding unit.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     */
    private void interPredictionUnits(int x0, int y0, int log2Size, int partMode) {
        var n = 1 << log2Size;
        var h = n >> 1;
        var q = n >> 2;
        var count = 2;
        switch (partMode) {
            case PART_2Nx2N -> {
                parsePredictionUnit(0, x0, y0, n, n, x0, y0, false, partMode);
                count = 1;
            }
            case PART_2NxN -> {
                parsePredictionUnit(0, x0, y0, n, h, x0, y0, false, partMode);
                parsePredictionUnit(1, x0, y0 + h, n, h, x0, y0, false, partMode);
            }
            case PART_Nx2N -> {
                parsePredictionUnit(0, x0, y0, h, n, x0, y0, false, partMode);
                parsePredictionUnit(1, x0 + h, y0, h, n, x0, y0, false, partMode);
            }
            case PART_2NxnU -> {
                parsePredictionUnit(0, x0, y0, n, q, x0, y0, false, partMode);
                parsePredictionUnit(1, x0, y0 + q, n, n * 3 / 4, x0, y0, false, partMode);
            }
            case PART_2NxnD -> {
                parsePredictionUnit(0, x0, y0, n, n * 3 / 4, x0, y0, false, partMode);
                parsePredictionUnit(1, x0, y0 + n * 3 / 4, n, q, x0, y0, false, partMode);
            }
            case PART_nLx2N -> {
                parsePredictionUnit(0, x0, y0, q, n, x0, y0, false, partMode);
                parsePredictionUnit(1, x0 + q, y0, n * 3 / 4, n, x0, y0, false, partMode);
            }
            case PART_nRx2N -> {
                parsePredictionUnit(0, x0, y0, n * 3 / 4, n, x0, y0, false, partMode);
                parsePredictionUnit(1, x0 + n * 3 / 4, y0, q, n, x0, y0, false, partMode);
            }
            default -> {
                parsePredictionUnit(0, x0, y0, h, h, x0, y0, false, partMode);
                parsePredictionUnit(1, x0 + h, y0, h, h, x0, y0, false, partMode);
                parsePredictionUnit(2, x0, y0 + h, h, h, x0, y0, false, partMode);
                parsePredictionUnit(3, x0 + h, y0 + h, h, h, x0, y0, false, partMode);
                count = 4;
            }
        }
        finishInterUnit(x0, y0, log2Size, partMode, count, false);
    }

    /**
     * Parses the coding unit extension of an inter coding unit of 3D-HEVC (I.7.3.8.5.2), then
     * derives the motion of each prediction unit, stores it and predicts its samples, in order;
     * a coding unit with depth-based block partitioning is predicted once its two prediction
     * units are derived (I.8.5.3.5).
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param log2Size the coding block size as a power of two
     * @param partMode the partitioning mode
     * @param count the number of prediction units
     * @param skip whether the coding unit is skipped
     */
    private void finishInterUnit(int x0, int y0, int log2Size, int partMode, int count, boolean skip) {
        var weight = 0;
        var illumination = false;
        if (tools3d != null && views != null && !skip) {
            if (tools3d.dbbp && views.dispAvail && log2Size > 3 && (partMode == PART_2NxN || partMode == PART_Nx2N)) {
                dbbpFlag = cabac.decodeBin(CabacTables.DBBP) == 1;
            }
            if (tools3d.interDcOnly && partMode == PART_2Nx2N) {
                dcOnly = cabac.decodeBin(CabacTables.DC_ONLY) == 1;
            }
        }
        if (tools3d != null && views != null && partMode == PART_2Nx2N) {
            var m = puMotions[0];
            if (tools3d.ivResPred && views.rpRefPicAvail) {
                var ctx = available(x0, y0, x0 - 1, y0)
                        && (pic.flags[blockIndex(x0 - 1, y0)] & PictureState.RESIDUAL_PREDICTION) != 0 ? 1 : 0;
                if (cabac.decodeBin(CabacTables.RESIDUAL_WEIGHT + ctx) == 1) {
                    weight = 1 + cabac.decodeBin(CabacTables.RESIDUAL_WEIGHT + 2);
                }
            }
            if (tools3d.icEnabled && weight == 0 && illuminationCompensationEnabled(m)) {
                illumination = cabac.decodeBin(CabacTables.ILLU_COMP) == 1;
            }
            if (weight != 0) {
                var w4 = pic.width4;
                var blocks = 1 << (log2Size - 2);
                var start = blockIndex(x0, y0);
                for (var j = 0; j < blocks; j++) {
                    for (var i = 0; i < blocks; i++) {
                        pic.flags[start + j * w4 + i] |= (byte) PictureState.RESIDUAL_PREDICTION;
                    }
                }
            }
        }
        var disparity = 0;
        var disparityView = -1;
        if (tools3d != null && views != null && tools3d.disparityDerivation) {
            if (tools3d.depth) {
                motion.deriveDepthDisparity();
            } else {
                motion.deriveDisparity(x0, y0, 1 << log2Size, this);
            }
            disparity = motion.disparity();
            disparityView = motion.disparityView();
        }
        for (var k = 0; k < count; k++) {
            var m = puMotions[k];
            m.residualWeight = weight;
            m.illuminationCompensation = illumination;
            m.dbbp = dbbpFlag;
            m.dispVec = disparity;
            m.refViewIdx = disparityView;
            m.cuX = x0;
            m.cuY = y0;
            var xPb = puX[k];
            var yPb = puY[k];
            var width = puWidth[k];
            var height = puHeight[k];
            motion.derive(x0, y0, 1 << log2Size, xPb, yPb, width, height, k, m, this);
            if (m.vsp) {
                synthesizeViewMotion(xPb, yPb, width, height, m);
            }
            storeMotion(xPb, yPb, width, height, m);
            if (k > 0 || width != (1 << log2Size) || height != (1 << log2Size)) {
                markEdges(xPb, yPb, width, height, PictureState.EDGE_PU_V, PictureState.EDGE_PU_H);
            }
            if (illumination) {
                m.icAbove = available(xPb, yPb, xPb, yPb - 1);
                m.icLeft = available(xPb, yPb, xPb - 1, yPb);
            }
            if (!dbbpFlag) {
                inter.predict(frame, xPb, yPb, width, height, m);
            }
        }
        if (dbbpFlag) {
            predictDepthPartitions(x0, y0, 1 << log2Size, partMode);
        }
    }

    /**
     * Replaces the motion of a prediction unit predicted with view synthesis prediction by the
     * motion of its sub-block partitions (equations I-192 to I-194): 8x4 or 4x8 partitions, as
     * the corners of the depth block the disparity vector points to decide, each with the
     * disparity of the largest of the corner samples of its depth block in the list and towards
     * the reference picture of the candidate.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion of the prediction unit, receiving the sub-block partition motion
     * @throws MediaException.Malformed if the depth picture or the camera parameters of the views
     * are missing
     */
    private void synthesizeViewMotion(int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        var depthView = m.refViewIdx;
        var depth = depthView >= 0 ? views.depthPicture[depthView] : null;
        if (depth == null) {
            throw new MediaException.Malformed("view synthesis prediction without the depth picture of the view");
        }
        var xRef = xPb + (((short) m.dispVec + 2) >> 2);
        var yRef = yPb + (((m.dispVec >> 16) + 2) >> 2);
        boolean horizontal;
        if (height % 8 != 0) {
            horizontal = true;
        } else if (width % 8 != 0) {
            horizontal = false;
        } else {
            depth.awaitRows(Math.clamp(yRef + height, 1, depth.height));
            var topLeft = depthAt(depth, xRef, yRef);
            var bottomRight = depthAt(depth, xRef + width - 1, yRef + height - 1);
            var topRight = depthAt(depth, xRef + width - 1, yRef);
            var bottomLeft = depthAt(depth, xRef, yRef + height - 1);
            horizontal = (topLeft < bottomRight) == (topRight < bottomLeft);
        }
        var subWidth = horizontal ? 8 : 4;
        var subHeight = horizontal ? 4 : 8;
        m.subPbWidth = subWidth;
        m.subPbHeight = subHeight;
        var blocks = (width / subWidth) * (height / subHeight);
        for (var list = 0; list < 2; list++) {
            var mv = list == 0 ? m.subMv0 : m.subMv1;
            var ref = list == 0 ? m.subRef0 : m.subRef1;
            if (!m.predFlag[list]) {
                java.util.Arrays.fill(mv, 0, blocks, 0);
                java.util.Arrays.fill(ref, 0, blocks, (byte) -1);
                continue;
            }
            var refIdx = m.refIdx[list];
            var table = tools3d.depthToDisparityB(views.refViewIdx[list][refIdx]);
            if (table == null) {
                throw new MediaException.Malformed(
                        "view synthesis prediction without the camera parameters of the view");
            }
            var index = 0;
            for (var y = 0; y < height; y += subHeight) {
                for (var x = 0; x < width; x += subWidth) {
                    var maxDepth = MotionPredictor.maxCornerDepth(depth, xPb + x, yPb + y, subWidth, subHeight,
                            m.dispVec);
                    mv[index] = table[Math.min(maxDepth, table.length - 1)] & 0xFFFF;
                    ref[index] = (byte) refIdx;
                    index++;
                }
            }
        }
    }

    /**
     * Returns a luma sample of a depth picture, the coordinates clamped to the picture.
     *
     * @param depth the depth picture
     * @param x the luma x
     * @param y the luma y
     * @return the sample value
     */
    private static int depthAt(HevcFrame depth, int x, int y) {
        var index = Math.clamp(y, 0, depth.height - 1) * depth.width + Math.clamp(x, 0, depth.width - 1);
        return depth.y8 != null ? depth.y8[index] & 0xFF : depth.y[index] & 0xFFFF;
    }

    /**
     * Predicts a coding unit with depth-based block partitioning (I.8.5.3.5): each of the two
     * motions of its prediction units predicts the whole coding block, and the segment mask
     * derived from the depth block of the reference view picks, sample by sample, the prediction
     * of the segment, with the samples at the segment boundary filtered across the direction of
     * the partitioning.
     *
     * <p>As in the reference decoder, the depth block is read from the clamped position of its top
     * left sample, its rows and columns repeating the last ones inside the picture, and its
     * segments are split at the mean of four corner samples of the block inside the picture.
     *
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param size the coding block size
     * @param partMode {@link #PART_2NxN} or {@link #PART_Nx2N}
     * @throws MediaException.Malformed if the depth picture of the reference view is missing
     */
    private void predictDepthPartitions(int x0, int y0, int size, int partMode) {
        if (dbbpMask == null) {
            dbbpMask = new byte[64 * 64];
            dbbpPrediction = new int[][]{new int[64 * 64], new int[64 * 64], new int[64 * 64]};
            dbbpCombined = new int[64 * 64];
        }
        var first = puMotions[0];
        var view = first.refViewIdx;
        var depth = view >= 0 ? views.depthPicture[view] : null;
        if (depth == null) {
            throw new MediaException.Malformed("depth-based block partitioning without the depth picture of the view");
        }
        var dv = tools3d.depthRef ? motion.disparityRefined() : first.dispVec;
        var blkX = x0 + (((short) dv + 2) >> 2);
        var blkY = y0 + (((dv >> 16) + 2) >> 2);
        var width = depth.width;
        var height = depth.height;
        var baseX = Math.clamp(blkX, 0, width - 1);
        var baseY = Math.clamp(blkY, 0, height - 1);
        depth.awaitRows(Math.min(height, baseY + size + 1));
        var last = size - 1;
        var right = blkX > width - size ? width - blkX - 1 : last;
        var bottom = blkY > height - size ? height - blkY - 1 : last;
        var topLeft = depthAt(depth, baseX, baseY);
        var mean = (topLeft + depthAt(depth, baseX + right, baseY) + depthAt(depth, baseX, baseY + bottom)
                + depthAt(depth, baseX + right, baseY + bottom)) >> 2;
        var invert = topLeft > mean;
        var columnLimit = Math.max(0, width - blkX);
        var rowLimit = Math.max(0, height - blkY);
        var mask = dbbpMask;
        for (var y = 0; y < size; y++) {
            var row = baseY + Math.min(y, rowLimit);
            for (var x = 0; x < size; x++) {
                var value = depthAt(depth, baseX + Math.min(x, columnLimit), row);
                mask[y * size + x] = (byte) ((value > mean) != invert ? 1 : 0);
            }
        }
        // check3dTools admits 4:0:0, 4:2:0 and 4:4:4, whose chroma blocks are square
        var chroma = sps.chromaArrayType != 0;
        var shift = sps.chromaShiftX;
        var sizeC = size >> shift;
        var xC = x0 >> shift;
        var yC = y0 >> shift;
        inter.predict(frame, x0, y0, size, size, first);
        copyBlock(frame, 0, x0, y0, size, dbbpPrediction[0]);
        if (chroma) {
            copyBlock(frame, 1, xC, yC, sizeC, dbbpPrediction[1]);
            copyBlock(frame, 2, xC, yC, sizeC, dbbpPrediction[2]);
        }
        inter.predict(frame, x0, y0, size, size, puMotions[1]);
        var vertical = partMode == PART_Nx2N;
        combineSegments(0, x0, y0, size, 1, vertical);
        if (chroma) {
            combineSegments(1, xC, yC, sizeC, 1 << shift, vertical);
            combineSegments(2, xC, yC, sizeC, 1 << shift, vertical);
        }
    }

    /**
     * Combines the predictions of the two segments of a component of a coding unit with
     * depth-based block partitioning into the picture: the picture holds the prediction of the
     * second segment, {@link #dbbpPrediction} that of the first; a sample whose neighbors across
     * the partitioning direction lie in different segments is filtered with the weights 1, 2, 1.
     *
     * @param cIdx the component
     * @param x0 the x of the block in samples of the component
     * @param y0 the y of the block in samples of the component
     * @param size the block size in samples of the component
     * @param step the distance in luma samples of the mask between two samples of the component
     * @param vertical whether the partitioning is vertical, filtering horizontally
     */
    private void combineSegments(int cIdx, int x0, int y0, int size, int step, boolean vertical) {
        var mask = dbbpMask;
        var maskStride = size * step;
        var first = dbbpPrediction[cIdx];
        var combined = dbbpCombined;
        var plane = cIdx == 0 ? frame.y : cIdx == 1 ? frame.cb : frame.cr;
        var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        for (var y = 0; y < size; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < size; x++) {
                var second = plane8 != null ? plane8[row + x] & 0xFF : plane[row + x] & 0xFFFF;
                combined[y * size + x] = mask[y * step * maskStride + x * step] != 0 ? second : first[y * size + x];
            }
        }
        for (var y = 0; y < size; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < size; x++) {
                var centre = combined[y * size + x];
                int a;
                int b;
                int sideA;
                int sideB;
                if (vertical) {
                    var xa = x == 0 ? x : x - 1;
                    var xb = x == size - 1 ? x : x + 1;
                    sideA = mask[y * step * maskStride + xa * step];
                    sideB = mask[y * step * maskStride + xb * step];
                    a = combined[y * size + xa];
                    b = combined[y * size + xb];
                } else {
                    var ya = y == 0 ? y : y - 1;
                    var yb = y == size - 1 ? y : y + 1;
                    sideA = mask[ya * step * maskStride + x * step];
                    sideB = mask[yb * step * maskStride + x * step];
                    a = combined[ya * size + x];
                    b = combined[yb * size + x];
                }
                // a weighted mean of samples in range is in range, so equation I-279 needs no clipping
                var value = sideA != sideB ? (a + (centre << 1) + b) >> 2 : centre;
                if (plane8 != null) {
                    plane8[row + x] = (byte) value;
                } else {
                    plane[row + x] = (short) value;
                }
            }
        }
    }

    /**
     * Copies a square block of a component of the picture being decoded.
     *
     * @param frame the picture
     * @param cIdx the component
     * @param x0 the x of the block in samples of the component
     * @param y0 the y of the block in samples of the component
     * @param size the block size
     * @param target receives the samples, {@code size} per row
     */
    private static void copyBlock(HevcFrame frame, int cIdx, int x0, int y0, int size, int[] target) {
        var plane = cIdx == 0 ? frame.y : cIdx == 1 ? frame.cb : frame.cr;
        var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        for (var y = 0; y < size; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < size; x++) {
                target[y * size + x] = plane8 != null ? plane8[row + x] & 0xFF : plane[row + x] & 0xFFFF;
            }
        }
    }

    /**
     * Returns {@code icCuEnableFlag} of a 2Nx2N coding unit (equations I-48 and I-49): whether
     * {@code illu_comp_flag} is present.
     *
     * @param m the parsed syntax of the prediction unit
     * @return {@code true} when the flag is coded
     */
    private boolean illuminationCompensationEnabled(MotionPredictor.PuMotion m) {
        if (m.merge) {
            return m.mergeIdx != 0 || !tools3d.icDisabledMergeZeroIdx;
        }
        var view = tools3d.viewIdx;
        return m.predFlag[1] && views.refViewIdx[1][m.refIdx[1]] != view
                || m.predFlag[0] && views.refViewIdx[0][m.refIdx[0]] != view;
    }

    /**
     * Parses a prediction unit (7.3.8.6) into {@link #puMotions}; {@link #finishInterUnit} derives
     * its motion and predicts its samples.
     *
     * @param partIdx the partition index
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param xCb the luma x of the coding block
     * @param yCb the luma y of the coding block
     * @param skip whether the coding unit is skipped
     * @param partMode the partitioning mode of the coding unit
     */
    private void parsePredictionUnit(int partIdx, int xPb, int yPb, int width, int height, int xCb, int yCb,
                                     boolean skip, int partMode) {
        if (refList == null) {
            throw new MediaException.Malformed("inter prediction unit in a slice without reference pictures");
        }
        var m = puMotions[partIdx];
        puMotion = m;
        puX[partIdx] = xPb;
        puY[partIdx] = yPb;
        puWidth[partIdx] = width;
        puHeight[partIdx] = height;
        m.clear();
        m.partMode = partMode;
        if (skip) {
            m.merge = true;
        } else {
            m.merge = cabac.decodeBin(CabacTables.MERGE_FLAG) == 1;
        }
        if (m.merge) {
            m.mergeIdx = 0;
            if (header.maxNumMergeCand > 1) {
                if (cabac.decodeBin(CabacTables.MERGE_IDX) == 1) {
                    m.mergeIdx = 1;
                    while (m.mergeIdx < header.maxNumMergeCand - 1 && cabac.decodeBypass() == 1) {
                        m.mergeIdx++;
                    }
                }
            }
        } else {
            var predIdc = 0;
            if (header.sliceType == HevcSliceHeader.B) {
                if (width + height != 12) {
                    var ctx = pic.depth[blockIndex(xCb, yCb)];
                    if (cabac.decodeBin(CabacTables.INTER_PRED_IDC + ctx) == 1) {
                        predIdc = 2;
                    } else {
                        predIdc = cabac.decodeBin(CabacTables.INTER_PRED_IDC + 4);
                    }
                } else {
                    predIdc = cabac.decodeBin(CabacTables.INTER_PRED_IDC + 4);
                }
            }
            if (predIdc != 1) {
                m.refIdx[0] = refIdx(header.numRefIdx[0]);
                mvdCoding(0);
                m.mvpFlag[0] = cabac.decodeBin(CabacTables.MVP_FLAG);
                m.predFlag[0] = true;
            }
            if (predIdc != 0) {
                m.refIdx[1] = refIdx(header.numRefIdx[1]);
                if (header.mvdL1Zero && predIdc == 2) {
                    m.mvdX[1] = 0;
                    m.mvdY[1] = 0;
                } else {
                    mvdCoding(1);
                }
                m.mvpFlag[1] = cabac.decodeBin(CabacTables.MVP_FLAG);
                m.predFlag[1] = true;
            }
        }
    }

    /**
     * Parses a {@code ref_idx_lX} (truncated Rice, two context-coded bins).
     *
     * @param count {@code num_ref_idx_lX_active_minus1 + 1}
     * @return the reference index
     */
    private int refIdx(int count) {
        if (count <= 1) {
            return 0;
        }
        var max = count - 1;
        var value = 0;
        while (value < max) {
            var bin = value < 2 ? cabac.decodeBin(CabacTables.REF_IDX + value) : cabac.decodeBypass();
            if (bin == 0) {
                break;
            }
            value++;
        }
        return value;
    }

    /**
     * Parses an {@code mvd_coding(x0, y0, refList)} structure (7.3.8.9) into the prediction unit
     * motion.
     *
     * @param list the reference list
     */
    private void mvdCoding(int list) {
        var greater0X = cabac.decodeBin(CabacTables.MVD_GREATER0) == 1;
        var greater0Y = cabac.decodeBin(CabacTables.MVD_GREATER0) == 1;
        var greater1X = greater0X && cabac.decodeBin(CabacTables.MVD_GREATER1) == 1;
        var greater1Y = greater0Y && cabac.decodeBin(CabacTables.MVD_GREATER1) == 1;
        var x = 0;
        var y = 0;
        if (greater0X) {
            var abs = greater1X ? expGolomb(1) + 2 : 1;
            x = cabac.decodeBypass() == 1 ? -abs : abs;
        }
        if (greater0Y) {
            var abs = greater1Y ? expGolomb(1) + 2 : 1;
            y = cabac.decodeBypass() == 1 ? -abs : abs;
        }
        if (x < -32768 || x > 32767 || y < -32768 || y > 32767) {
            throw new MediaException.Malformed("motion vector difference out of range");
        }
        puMotion.mvdX[list] = x;
        puMotion.mvdY[list] = y;
    }

    /**
     * Decodes a k-th order Exp-Golomb bypass code (9.3.3.3).
     *
     * @param k the order
     * @return the value
     * @throws MediaException.Malformed if the prefix exceeds 32 bins
     */
    private int expGolomb(int k) {
        var ones = cabac.decodeBypassOnes(30 - k);
        if (ones > 30 - k) {
            throw new MediaException.Malformed("Exp-Golomb bypass code too long");
        }
        var value = ((1 << ones) - 1) << k;
        return value + cabac.decodeBypassBins(k + ones);
    }

    /**
     * Writes the motion of a prediction unit to the frame's motion field.
     *
     * @param xPb the luma x of the prediction block
     * @param yPb the luma y of the prediction block
     * @param width the prediction block width
     * @param height the prediction block height
     * @param m the motion
     */
    private void storeMotion(int xPb, int yPb, int width, int height, MotionPredictor.PuMotion m) {
        var w4 = frame.width4;
        var start = (yPb >> 2) * w4 + (xPb >> 2);
        if (pic.vsp != null) {
            for (var j = 0; j < height >> 2; j++) {
                var row = start + j * w4;
                java.util.Arrays.fill(pic.vsp, row, row + (width >> 2), (byte) (m.vsp ? 1 : 0));
            }
        }
        if (m.ivMc) {
            for (var j = 0; j < height >> 2; j++) {
                for (var i = 0; i < width >> 2; i++) {
                    pic.flags[start + j * w4 + i] |= (byte) PictureState.IV_MC;
                }
            }
        }
        if (m.subPbWidth != 0) {
            var index = 0;
            for (var y = 0; y < height; y += m.subPbHeight) {
                for (var x = 0; x < width; x += m.subPbWidth) {
                    var block = ((yPb + y) >> 2) * w4 + ((xPb + x) >> 2);
                    var mv0 = m.subRef0[index] >= 0 ? m.subMv0[index] : 0;
                    var mv1 = m.subRef1[index] >= 0 ? m.subMv1[index] : 0;
                    for (var j = 0; j < m.subPbHeight >> 2; j++) {
                        var row = block + j * w4;
                        var end = row + (m.subPbWidth >> 2);
                        java.util.Arrays.fill(frame.mv0, row, end, mv0);
                        java.util.Arrays.fill(frame.mv1, row, end, mv1);
                        java.util.Arrays.fill(frame.ref0, row, end, m.subRef0[index]);
                        java.util.Arrays.fill(frame.ref1, row, end, m.subRef1[index]);
                    }
                    index++;
                }
            }
            return;
        }
        var mv0 = (m.mvY[0] << 16) | (m.mvX[0] & 0xFFFF);
        var mv1 = (m.mvY[1] << 16) | (m.mvX[1] & 0xFFFF);
        var r0 = (byte) (m.predFlag[0] ? m.refIdx[0] : -1);
        var r1 = (byte) (m.predFlag[1] ? m.refIdx[1] : -1);
        var columns = width >> 2;
        for (var j = 0; j < height >> 2; j++) {
            var row = start + j * w4;
            java.util.Arrays.fill(frame.mv0, row, row + columns, mv0);
            java.util.Arrays.fill(frame.mv1, row, row + columns, mv1);
            java.util.Arrays.fill(frame.ref0, row, row + columns, r0);
            java.util.Arrays.fill(frame.ref1, row, row + columns, r1);
        }
    }

    /**
     * Parses a transform tree (7.3.8.8) and reconstructs its transform units.
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param xBase the luma x of the parent block
     * @param yBase the luma y of the parent block
     * @param log2Size the block size as a power of two
     * @param trafoDepth the transform tree depth
     * @param blkIdx the index of the block within its parent
     * @param parentCbfCb {@code cbf_cb} of the parent block: bit 0 for its first chroma block,
     * bit 1 for the second chroma block of 4:2:2 video
     * @param parentCbfCr {@code cbf_cr} of the parent block, as {@code parentCbfCb}
     */
    private void transformTree(int x0, int y0, int xBase, int yBase, int log2Size, int trafoDepth, int blkIdx,
                               int parentCbfCb, int parentCbfCr) {
        boolean split;
        if (log2Size <= sps.log2MaxTbSize && log2Size > sps.log2MinTbSize && trafoDepth < maxTrafoDepth
                && !(intraSplit && trafoDepth == 0)) {
            split = cabac.decodeBin(CabacTables.SPLIT_TRANSFORM + 5 - log2Size) == 1;
        } else {
            var interSplit = sps.maxTransformHierarchyDepthInter == 0 && !cuIntra && trafoDepth == 0
                    && puMotionPartitioned;
            split = log2Size > sps.log2MaxTbSize || (intraSplit && trafoDepth == 0) || interSplit;
        }
        var cbfCb = 0;
        var cbfCr = 0;
        var chroma = sps.chromaArrayType != 0;
        if ((log2Size > 2 && chroma) || sps.chromaArrayType == 3) {
            var second = sps.chromaArrayType == 2 && (!split || log2Size == 3);
            if (trafoDepth == 0 || parentCbfCb != 0) {
                cbfCb = cabac.decodeBin(CabacTables.CBF_CHROMA + trafoDepth);
                if (second) {
                    cbfCb |= cabac.decodeBin(CabacTables.CBF_CHROMA + trafoDepth) << 1;
                }
            }
            if (trafoDepth == 0 || parentCbfCr != 0) {
                cbfCr = cabac.decodeBin(CabacTables.CBF_CHROMA + trafoDepth);
                if (second) {
                    cbfCr |= cabac.decodeBin(CabacTables.CBF_CHROMA + trafoDepth) << 1;
                }
            }
        } else if (chroma && log2Size == 2) {
            cbfCb = parentCbfCb;
            cbfCr = parentCbfCr;
        }
        if (split) {
            var half = 1 << (log2Size - 1);
            transformTree(x0, y0, x0, y0, log2Size - 1, trafoDepth + 1, 0, cbfCb, cbfCr);
            transformTree(x0 + half, y0, x0, y0, log2Size - 1, trafoDepth + 1, 1, cbfCb, cbfCr);
            transformTree(x0, y0 + half, x0, y0, log2Size - 1, trafoDepth + 1, 2, cbfCb, cbfCr);
            transformTree(x0 + half, y0 + half, x0, y0, log2Size - 1, trafoDepth + 1, 3, cbfCb, cbfCr);
        } else {
            var cbfLuma = 1;
            if (cuIntra || trafoDepth != 0 || cbfCb != 0 || cbfCr != 0) {
                cbfLuma = cabac.decodeBin(CabacTables.CBF_LUMA + (trafoDepth == 0 ? 1 : 0));
            }
            if (trafoDepth > 0) {
                markEdges(x0, y0, 1 << log2Size, 1 << log2Size, PictureState.EDGE_TU_V, PictureState.EDGE_TU_H);
            }
            transformUnit(x0, y0, xBase, yBase, log2Size, trafoDepth, blkIdx, cbfLuma, cbfCb, cbfCr);
        }
    }

    /**
     * Whether the current inter coding unit has more than one prediction unit, which forces a
     * transform split when {@code max_transform_hierarchy_depth_inter} is 0 (equation 7-38).
     */
    private boolean puMotionPartitioned;

    /**
     * Parses and reconstructs a transform unit (7.3.8.10).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param xBase the luma x of the parent block
     * @param yBase the luma y of the parent block
     * @param log2Size the luma transform block size as a power of two
     * @param trafoDepth the transform tree depth
     * @param blkIdx the index of the block within its parent
     * @param cbfLuma {@code cbf_luma}
     * @param cbfCb {@code cbf_cb} (of the parent for 4x4 luma blocks of 4:2:0 and 4:2:2 video):
     * bit 0 for the first chroma block, bit 1 for the second chroma block of
     * 4:2:2 video
     * @param cbfCr {@code cbf_cr}, as {@code cbfCb}
     */
    private void transformUnit(int x0, int y0, int xBase, int yBase, int log2Size, int trafoDepth, int blkIdx,
                               int cbfLuma, int cbfCb, int cbfCr) {
        var chroma = sps.chromaArrayType != 0;
        var cbfChroma = chroma && (cbfCb != 0 || cbfCr != 0);
        actTu = false;
        if (cbfLuma != 0 || cbfChroma) {
            if (pps.actEnabled && (!cuIntra || (chromaSyntax[0] == 4 && chromaSyntax[1] == 4 && chromaSyntax[2] == 4
                    && chromaSyntax[3] == 4))) {
                actTu = cabac.decodeBin(CabacTables.ACT_FLAG) == 1;
            }
            deltaQp();
            if (cbfChroma && !bypass && header.cuChromaQpOffsetEnabled && !chromaQpOffsetCoded) {
                chromaQpOffset();
            }
        }
        if (actTu) {
            java.util.Arrays.fill(actPresent, false);
        }
        var size = 1 << log2Size;
        dmmPending = false;
        if (cuIntra) {
            predictIntra(x0, y0, log2Size, 0, pic.intraMode[blockIndex(x0, y0)]);
        }
        lumaResidualValid = false;
        if (cbfLuma != 0) {
            residualCoding(x0, y0, log2Size, 0);
            var w4 = pic.width4;
            var start = blockIndex(x0, y0);
            for (var j = 0; j < size >> 2; j++) {
                for (var i = 0; i < size >> 2; i++) {
                    pic.flags[start + j * w4 + i] |= PictureState.CODED;
                }
            }
        }
        if (!chroma) {
            return;
        }
        if (log2Size > 2 || sps.chromaArrayType == 3) {
            var log2SizeC = sps.chromaArrayType == 3 ? log2Size : log2Size - 1;
            var crossComponent = pps.crossComponentPrediction && cbfLuma != 0
                    && (!cuIntra || chromaSyntax[partitionAt(x0, y0)] == 4);
            resScaleVal = crossComponent ? crossComponentScale(0) : 0;
            chromaBlocks(x0, y0, log2SizeC, 1, cbfCb);
            resScaleVal = crossComponent ? crossComponentScale(1) : 0;
            chromaBlocks(x0, y0, log2SizeC, 2, cbfCr);
            resScaleVal = 0;
            if (actTu) {
                inverseColorTransform(x0, y0, log2Size);
            }
        } else if (blkIdx == 3) {
            chromaBlocks(xBase, yBase, 2, 1, cbfCb);
            chromaBlocks(xBase, yBase, 2, 2, cbfCr);
        }
    }

    /**
     * Parses a {@code chroma_qp_offset()} structure (7.3.8.15) and updates {@code CuQpOffsetCb}
     * and {@code CuQpOffsetCr}.
     */
    private void chromaQpOffset() {
        chromaQpOffsetCoded = true;
        if (cabac.decodeBin(CabacTables.CU_CHROMA_QP_OFFSET_FLAG) == 0) {
            cuQpOffsetCb = 0;
            cuQpOffsetCr = 0;
            return;
        }
        var index = 0;
        var max = pps.chromaQpOffsetListLength - 1;
        while (index < max && cabac.decodeBin(CabacTables.CU_CHROMA_QP_OFFSET_IDX) == 1) {
            index++;
        }
        cuQpOffsetCb = pps.cbQpOffsetList[index];
        cuQpOffsetCr = pps.crQpOffsetList[index];
    }

    /**
     * Parses a {@code cross_comp_pred(x0, y0, c)} structure (7.3.8.12) and returns
     * {@code ResScaleVal} (equation 7-80).
     *
     * @param c the chroma component, 0 for Cb and 1 for Cr
     * @return the scale factor, 0 or a signed power of two up to 8
     */
    private int crossComponentScale(int c) {
        var base = CabacTables.LOG2_RES_SCALE + 4 * c;
        var log2Plus1 = 0;
        while (log2Plus1 < 4 && cabac.decodeBin(base + log2Plus1) == 1) {
            log2Plus1++;
        }
        if (log2Plus1 == 0) {
            return 0;
        }
        var magnitude = 1 << (log2Plus1 - 1);
        return cabac.decodeBin(CabacTables.RES_SCALE_SIGN + c) == 1 ? -magnitude : magnitude;
    }

    /**
     * Predicts (for intra coding units) and reconstructs the chroma transform blocks of one
     * component of a transform unit: one block, or two stacked blocks for 4:2:2 video, the second
     * predicted from the reconstructed first.
     *
     * @param xLuma the luma x of the first block
     * @param yLuma the luma y of the first block
     * @param log2Size the chroma transform block size as a power of two
     * @param cIdx the component, 1 or 2
     * @param cbf the coded block flags of the blocks: bit 0 for the first, bit 1 for the
     * second
     */
    private void chromaBlocks(int xLuma, int yLuma, int log2Size, int cIdx, int cbf) {
        chromaBlock(xLuma, yLuma, log2Size, cIdx, cbf & 1);
        if (sps.chromaArrayType == 2) {
            chromaBlock(xLuma, yLuma + (1 << log2Size), log2Size, cIdx, (cbf >> 1) & 1);
        }
    }

    /**
     * Predicts (for intra coding units) and reconstructs one chroma transform block.
     *
     * @param xLuma the luma x of the block
     * @param yLuma the luma y of the block
     * @param log2Size the chroma transform block size as a power of two
     * @param cIdx the component, 1 or 2
     * @param cbf whether the block has coefficients
     */
    private void chromaBlock(int xLuma, int yLuma, int log2Size, int cIdx, int cbf) {
        if (cuIntra) {
            predictIntra(xLuma, yLuma, log2Size, cIdx, chromaModes[partitionAt(xLuma, yLuma)]);
        }
        if (cbf != 0) {
            residualCoding(xLuma, yLuma, log2Size, cIdx);
        } else if (resScaleVal != 0 && lumaResidualValid) {
            crossComponentOnly(xLuma, yLuma, log2Size, cIdx);
        }
    }

    /**
     * Predicts an intra transform block from its reconstructed neighbors (8.4.4.2).
     *
     * @param xLuma the luma x of the block
     * @param yLuma the luma y of the block
     * @param log2Size the transform block size of the component as a power of two
     * @param cIdx the component
     * @param mode the intra prediction mode
     */
    private void predictIntra(int xLuma, int yLuma, int log2Size, int cIdx, int mode) {
        if (mode > 34) {
            predictDepthIntra(xLuma, yLuma, log2Size, mode);
            return;
        }
        gatherBoundary(xLuma, yLuma, log2Size, cIdx, true);
        var size = 1 << log2Size;
        var shiftX = cIdx == 0 ? 0 : sps.chromaShiftX;
        var shiftY = cIdx == 0 ? 0 : sps.chromaShiftY;
        var plane = cIdx == 0 ? frame.y : cIdx == 1 ? frame.cb : frame.cr;
        var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        var xc = xLuma >> shiftX;
        var yc = yLuma >> shiftY;
        var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
        var filter = !sps.intraSmoothingDisabled && (cIdx == 0 || sps.chromaArrayType == 3);
        var dcFilter = !sps.intraBoundaryFilteringDisabled;
        var edgeFilters = cIdx == 0 && dcFilter && !(sps.implicitRdpcm && bypass);
        if (plane8 == null) {
            intra.predict(boundary, log2Size, mode, cIdx == 0, filter, sps.strongIntraSmoothing, edgeFilters, dcFilter,
                    bitDepth, plane, yc * stride + xc, stride);
            return;
        }
        var predicted = intraBlock;
        intra.predict(boundary, log2Size, mode, cIdx == 0, filter, sps.strongIntraSmoothing, edgeFilters, dcFilter,
                bitDepth, predicted, 0, size);
        for (var y = 0; y < size; y++) {
            var row = (yc + y) * stride + xc;
            var in = y * size;
            for (var x = 0; x < size; x++) {
                plane8[row + x] = (byte) predicted[in + x];
            }
        }
    }

    /**
     * Predicts a luma transform block coded with a depth intra mode from its partition pattern
     * and the {@code DcOffset} of its prediction block (I.8.4.4.2.2 and I.8.4.4.2.3); a predicted
     * value outside the sample range is kept for the residual of the block.
     *
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @param log2Size the transform block size as a power of two
     * @param mode {@link #INTRA_WEDGE} or {@link #INTRA_CONTOUR}
     * @throws MediaException.Malformed if the transform block is not the prediction block
     */
    private void predictDepthIntra(int x, int y, int log2Size, int mode) {
        var pb = intraSplit ? partitionAt(x, y) : 0;
        var pbSize = intraSplit ? cuSize >> 1 : cuSize;
        if (1 << log2Size != pbSize || dimMode[pb] != mode) {
            throw new MediaException.Malformed("transform split in a prediction block coded with a depth intra mode");
        }
        gatherBoundary(x, y, log2Size, 0, true);
        var pattern = partition(pb, x, y, log2Size);
        dmmPending = depthIntra.predictSegments(boundary, pbSize, pattern, dcOffsets[pb][0], dcOffsets[pb][1], frame,
                x, y);
        if (dmmPending) {
            dmmX = x;
            dmmY = y;
            dmmSize = pbSize;
            dmmPattern = pattern;
        }
    }

    /**
     * Gathers the neighboring samples of an intra transform block into {@link #boundary} and their
     * availability into {@link #boundaryAvailable} (8.4.4.2.1), substituting the samples that are
     * not available when asked (8.4.4.2.2).
     *
     * @param xLuma the luma x of the block
     * @param yLuma the luma y of the block
     * @param log2Size the transform block size of the component as a power of two
     * @param cIdx the component
     * @param substitute whether to substitute the samples that are not available
     */
    private void gatherBoundary(int xLuma, int yLuma, int log2Size, int cIdx, boolean substitute) {
        var size = 1 << log2Size;
        var shiftX = cIdx == 0 ? 0 : sps.chromaShiftX;
        var shiftY = cIdx == 0 ? 0 : sps.chromaShiftY;
        var plane = cIdx == 0 ? frame.y : cIdx == 1 ? frame.cb : frame.cr;
        var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        var xc = xLuma >> shiftX;
        var yc = yLuma >> shiftY;
        var count = 4 * size + 1;
        var corner = 2 * size;
        var constrained = pps.constrainedIntraPred;
        var all = true;
        var ctbMaskC = (1 << (sps.log2CtbSize - shiftY)) - 1;
        var edgeRow = (yc & ctbMaskC) == 0 && yc > 0 ? (yc >> (sps.log2CtbSize - shiftY)) - 1 : -1;
        var edge8 = edgeRow >= 0 ? frame.edge8 : null;
        var edge16 = edgeRow >= 0 ? frame.edge16 : null;
        // Availability is uniform over each 4x4 luma block, so it is derived once per block.
        var block = Integer.MIN_VALUE;
        var avail = false;
        for (var y = 2 * size - 1; y >= -1; y--) {
            var index = corner - 1 - y;
            var yNb = (yc + y) << shiftY;
            if (yNb >> 2 != block) {
                block = yNb >> 2;
                avail = sampleAvailable(xLuma, yLuma, (xc - 1) << shiftX, yNb, constrained);
            }
            boundaryAvailable[index] = avail;
            if (avail) {
                if (y == -1 && edgeRow >= 0) {
                    var at = edgeRow * stride + xc - 1;
                    boundary[index] = edge8 != null ? edge8[cIdx][at] & 0xFF : edge16[cIdx][at] & 0xFFFF;
                    continue;
                }
                var at = (yc + y) * stride + xc - 1;
                boundary[index] = plane8 != null ? plane8[at] & 0xFF : plane[at] & 0xFFFF;
            } else {
                all = false;
            }
        }
        block = Integer.MIN_VALUE;
        for (var x = 0; x < 2 * size; x++) {
            var index = corner + 1 + x;
            var xNb = (xc + x) << shiftX;
            if (xNb >> 2 != block) {
                block = xNb >> 2;
                avail = sampleAvailable(xLuma, yLuma, xNb, (yc - 1) << shiftY, constrained);
            }
            boundaryAvailable[index] = avail;
            if (avail) {
                if (edgeRow >= 0) {
                    var at = edgeRow * stride + xc + x;
                    boundary[index] = edge8 != null ? edge8[cIdx][at] & 0xFF : edge16[cIdx][at] & 0xFFFF;
                    continue;
                }
                var at = (yc - 1) * stride + xc + x;
                boundary[index] = plane8 != null ? plane8[at] & 0xFF : plane[at] & 0xFFFF;
            } else {
                all = false;
            }
        }
        if (!all && substitute) {
            var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            IntraPredictor.substitute(boundary, boundaryAvailable, count, bitDepth);
        }
    }

    /**
     * Returns whether a neighboring sample is available for intra prediction (8.4.4.2.1).
     *
     * @param xCurr the luma x of the current block
     * @param yCurr the luma y of the current block
     * @param xNb the luma x of the neighboring sample
     * @param yNb the luma y of the neighboring sample
     * @param constrained {@code constrained_intra_pred_flag}
     * @return {@code true} when the sample may be used
     */
    private boolean sampleAvailable(int xCurr, int yCurr, int xNb, int yNb, boolean constrained) {
        if (!available(xCurr, yCurr, xNb, yNb)) {
            return false;
        }
        return !constrained || (pic.flags[blockIndex(xNb, yNb)] & PictureState.INTRA) != 0;
    }

    /**
     * Parses a {@code residual_coding()} structure (7.3.8.11) and adds the decoded residual to the
     * reconstructed picture (8.6.2 and 8.6.5 to 8.6.7).
     *
     * @param x0 the luma x of the transform block
     * @param y0 the luma y of the transform block
     * @param log2Size the transform block size of the component as a power of two
     * @param cIdx the component
     */
    private void residualCoding(int x0, int y0, int log2Size, int cIdx) {
        var size = 1 << log2Size;
        var coeffs = coefficients;
        transformSkip = false;
        if (pps.transformSkipEnabled && !bypass && log2Size <= pps.log2MaxTransformSkipSize) {
            transformSkip = cabac.decodeBin(CabacTables.TRANSFORM_SKIP + (cIdx == 0 ? 0 : 1)) == 1;
        }
        var skipped = transformSkip || bypass;
        var predMode = cIdx == 0 ? pic.intraMode[blockIndex(x0, y0)] : chromaModes[partitionAt(x0, y0)];
        if (predMode > 34) {
            // the depth intra modes scan their coefficients as INTRA_DC does
            predMode = 1;
        }
        rdpcmDirection = -1;
        if (!cuIntra) {
            if (sps.explicitRdpcm && skipped) {
                var channel = cIdx == 0 ? 0 : 1;
                if (cabac.decodeBin(CabacTables.EXPLICIT_RDPCM + channel) == 1) {
                    rdpcmDirection = cabac.decodeBin(CabacTables.EXPLICIT_RDPCM_DIR + channel);
                }
            }
        } else if (sps.implicitRdpcm && skipped && (predMode == 10 || predMode == 26)) {
            rdpcmDirection = predMode == 26 ? 1 : 0;
        }
        int ctxOffset;
        int ctxShift;
        if (cIdx == 0) {
            ctxOffset = 3 * (log2Size - 2) + ((log2Size - 1) >> 2);
            ctxShift = (log2Size + 1) >> 2;
        } else {
            ctxOffset = 15;
            ctxShift = log2Size - 2;
        }
        var maxPrefix = (log2Size << 1) - 1;
        var prefixX = 0;
        while (prefixX < maxPrefix && cabac.decodeBin(CabacTables.LAST_X + ctxOffset + (prefixX >> ctxShift)) == 1) {
            prefixX++;
        }
        var prefixY = 0;
        while (prefixY < maxPrefix && cabac.decodeBin(CabacTables.LAST_Y + ctxOffset + (prefixY >> ctxShift)) == 1) {
            prefixY++;
        }
        var lastX = prefixX;
        if (prefixX > 3) {
            var bits = (prefixX >> 1) - 1;
            lastX = (1 << bits) * (2 + (prefixX & 1)) + cabac.decodeBypassBins(bits);
        }
        var lastY = prefixY;
        if (prefixY > 3) {
            var bits = (prefixY >> 1) - 1;
            lastY = (1 << bits) * (2 + (prefixY & 1)) + cabac.decodeBypassBins(bits);
        }
        var scanIdx = 0;
        if (cuIntra && (log2Size == 2 || (log2Size == 3 && (cIdx == 0 || sps.chromaArrayType == 3)))) {
            if (predMode >= 6 && predMode <= 14) {
                scanIdx = 2;
            } else if (predMode >= 22 && predMode <= 30) {
                scanIdx = 1;
            }
        }
        if (scanIdx == 2) {
            var t = lastX;
            lastX = lastY;
            lastY = t;
        }
        if (lastX >= size || lastY >= size) {
            throw new MediaException.Malformed("last significant coefficient outside the transform block");
        }
        var log2Sb = log2Size - 2;
        var sbScan = ScanTables.BY_INDEX[scanIdx][log2Sb];
        var posScan = ScanTables.BY_INDEX[scanIdx][2];
        var lastSubBlock = ScanTables.INDEX_OF[scanIdx][log2Sb][((lastY >> 2) << 3) | (lastX >> 2)];
        var lastScanPos = ScanTables.INDEX_OF[scanIdx][2][((lastY & 3) << 3) | (lastX & 3)];
        var sbWidth = 1 << log2Sb;
        java.util.Arrays.fill(codedSubBlocks, 0, 64, false);
        var greater1Ctx = 1;
        var signHidingEnabled = pps.signDataHiding && !bypass && rdpcmDirection < 0;
        var skipContext = sps.transformSkipContext && skipped;
        var persistentRice = sps.persistentRice;
        var sbType = (cIdx == 0 ? 2 : 0) + (skipped ? 1 : 0);
        var log2Range = cIdx == 0 ? sps.log2TransformRangeLuma : sps.log2TransformRangeChroma;
        var coeffMax = (1 << log2Range) - 1;
        var coeffMin = -(1 << log2Range);
        var maxX = 0;
        var maxY = 0;
        var sigBase = CabacTables.SIG_COEFF + (cIdx == 0 ? 0 : 27);
        var dequantize = !bypass;
        var qpScale = 0;
        var qpShift = 0;
        var qpAdd = 0L;
        var qpBdShift = 0;
        int[] factors = null;
        if (dequantize) {
            var qp = quantizer(cIdx);
            qpBdShift = (cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma) + log2Size + 10 - log2Range;
            qpAdd = 1L << (qpBdShift - 1);
            qpScale = LEVEL_SCALE[qp % 6];
            qpShift = qp / 6;
            if (sps.scalingListEnabled && !(transformSkip && size > 4)) {
                var list = pps.scalingList != null ? pps.scalingList : sps.scalingList;
                factors = list.factors(log2Size - 2, (cuIntra ? 0 : 3) + cIdx);
            }
        }
        for (var i = lastSubBlock; i >= 0; i--) {
            var sb = sbScan[i];
            var xS = sb & 0xFF;
            var yS = sb >> 8;
            var inferSbDc = false;
            boolean coded;
            if (i < lastSubBlock && i > 0) {
                var csbfCtx = 0;
                if (xS < sbWidth - 1 && codedSubBlocks[yS * 8 + xS + 1]) {
                    csbfCtx++;
                }
                if (yS < sbWidth - 1 && codedSubBlocks[(yS + 1) * 8 + xS]) {
                    csbfCtx++;
                }
                coded = cabac.decodeBin(CabacTables.CODED_SUB_BLOCK + (cIdx == 0 ? 0 : 2) + Math.min(csbfCtx, 1)) == 1;
                inferSbDc = true;
            } else {
                coded = true;
            }
            codedSubBlocks[yS * 8 + xS] = coded;
            var count = 0;
            var start = 15;
            if (i == lastSubBlock) {
                start = lastScanPos - 1;
                significant[count++] = lastScanPos;
            }
            if (coded) {
                var prevCsbf = 0;
                if (xS < sbWidth - 1 && codedSubBlocks[yS * 8 + xS + 1]) {
                    prevCsbf |= 1;
                }
                if (yS < sbWidth - 1 && codedSubBlocks[(yS + 1) * 8 + xS]) {
                    prevCsbf |= 2;
                }
                var contexts = sigContextBase(cIdx, log2Size, scanIdx, prevCsbf, i == 0);
                var skipCtx = sigBase + (cIdx == 0 ? 42 : 16);
                for (var n = start; n >= 0; n--) {
                    if (n > 0 || !inferSbDc) {
                        var ctx = skipContext ? skipCtx : sigBase + SIG_CTX_BY_SCAN[contexts + n];
                        var bin = cabac.decodeBin(ctx);
                        significant[count] = n;
                        count += bin;
                        inferSbDc &= bin == 0;
                    } else {
                        significant[count++] = 0;
                    }
                }
            }
            if (count == 0) {
                continue;
            }
            var ctxSet = (i == 0 || cIdx > 0) ? 0 : 2;
            if (greater1Ctx == 0) {
                ctxSet++;
            }
            greater1Ctx = 1;
            var gt1Base = CabacTables.GREATER1 + (cIdx == 0 ? 0 : 16) + ctxSet * 4;
            var firstGreater1 = -1;
            var numGreater1 = Math.min(count, 8);
            var escapeDataPresent = count > 8;
            for (var k = 0; k < count; k++) {
                levels[k] = 1;
            }
            for (var k = 0; k < numGreater1; k++) {
                var flag = cabac.decodeBin(gt1Base + greater1Ctx);
                if (flag == 1) {
                    levels[k] = 2;
                    greater1Ctx = 0;
                    if (firstGreater1 < 0) {
                        firstGreater1 = k;
                    } else {
                        escapeDataPresent = true;
                    }
                } else if (greater1Ctx > 0 && greater1Ctx < 3) {
                    greater1Ctx++;
                }
            }
            if (firstGreater1 >= 0) {
                if (cabac.decodeBin(CabacTables.GREATER2 + (cIdx == 0 ? 0 : 4) + ctxSet) == 1) {
                    levels[firstGreater1] = 3;
                    escapeDataPresent = true;
                }
            }
            if (escapeDataPresent && sps.bypassAlignment) {
                cabac.align();
            }
            var lastSigScanPos = significant[0];
            var firstSigScanPos = significant[count - 1];
            var signHidden = signHidingEnabled && lastSigScanPos - firstSigScanPos > 3;
            var signBits = count - (signHidden ? 1 : 0);
            var signs = cabac.decodeBypassBins(signBits) << (32 - signBits);
            if (signBits == 0) {
                signs = 0;
            }
            var rice = persistentRice ? statCoeff[sbType] >> 2 : 0;
            var firstRemaining = persistentRice;
            var sumAbs = 0;
            for (var k = 0; k < count; k++) {
                var base = levels[k];
                var threshold = k < 8 ? (k == firstGreater1 ? 3 : 2) : 1;
                var abs = base;
                if (base == threshold) {
                    var remaining = coeffAbsLevelRemaining(rice, log2Range);
                    if (firstRemaining) {
                        var initial = statCoeff[sbType] >> 2;
                        if (remaining >= (3L << initial)) {
                            statCoeff[sbType]++;
                        } else if (2L * remaining < (1L << initial) && statCoeff[sbType] > 0) {
                            statCoeff[sbType]--;
                        }
                        firstRemaining = false;
                    }
                    abs = base + remaining;
                    if (abs > (3L << rice)) {
                        rice = persistentRice ? rice + 1 : Math.min(rice + 1, 4);
                    }
                }
                var n = significant[k];
                var pos = posScan[n];
                var xC = (xS << 2) + (pos & 0xFF);
                var yC = (yS << 2) + (pos >> 8);
                int value;
                if (k == count - 1 && signHidden) {
                    sumAbs += abs;
                    value = (sumAbs & 1) != 0 ? -abs : abs;
                } else {
                    sumAbs += abs;
                    value = signs < 0 ? -abs : abs;
                    signs <<= 1;
                }
                var index = yC * size + xC;
                var level = Math.clamp(value, coeffMin, coeffMax);
                if (dequantize) {
                    var m = factors == null ? 16 : factors[index];
                    level = (int) Math.clamp((((long) level * m * qpScale << qpShift) + qpAdd) >> qpBdShift,
                            (long) coeffMin, (long) coeffMax);
                }
                coeffs[index] = level;
                if (xC > maxX) {
                    maxX = xC;
                }
                if (yC > maxY) {
                    maxY = yC;
                }
            }
        }
        reconstructResidual(x0, y0, log2Size, cIdx, maxX + 1, maxY + 1);
    }

    /**
     * Returns the offset in {@link #SIG_CTX} of the contexts of one sub-block.
     *
     * @param cIdx the component
     * @param log2Size the transform block size as a power of two
     * @param scanIdx the scan order
     * @param prevCsbf the coded sub-block flags of the sub-blocks to the right (bit 0) and below
     * (bit 1)
     * @param dcSub whether the sub-block is the first of the block
     * @return the offset
     */
    private static int sigContextBase(int cIdx, int log2Size, int scanIdx, int prevCsbf, boolean dcSub) {
        var variant = (((cIdx == 0 ? 0 : 1) * 4 + log2Size - 2) * 3 + scanIdx) * 4 + prevCsbf;
        return (variant * 2 + (dcSub ? 1 : 0)) * 16;
    }

    /**
     * Returns {@code sigCtx} of {@code sig_coeff_flag} (9.3.4.2.5) without the transform skip
     * context.
     *
     * @param cIdx the component
     * @param log2Size the transform block size as a power of two
     * @param scanIdx the scan order
     * @param prevCsbf the coded sub-block flags of the sub-blocks to the right (bit 0) and below
     * (bit 1)
     * @param dcSub whether the sub-block is the first of the block
     * @param xP the column within the sub-block
     * @param yP the row within the sub-block
     * @return {@code sigCtx}
     */
    private static int sigContext(int cIdx, int log2Size, int scanIdx, int prevCsbf, boolean dcSub, int xP, int yP) {
        if (log2Size == 2) {
            return CTX_IDX_MAP[(yP << 2) + xP];
        }
        if (dcSub && xP + yP == 0) {
            return 0;
        }
        var sigCtx = switch (prevCsbf) {
            case 0 -> xP + yP == 0 ? 2 : xP + yP < 3 ? 1 : 0;
            case 1 -> yP == 0 ? 2 : yP == 1 ? 1 : 0;
            case 2 -> xP == 0 ? 2 : xP == 1 ? 1 : 0;
            default -> 2;
        };
        if (cIdx == 0) {
            if (!dcSub) {
                sigCtx += 3;
            }
            return sigCtx + (log2Size == 3 ? (scanIdx == 0 ? 9 : 15) : 21);
        }
        return sigCtx + (log2Size == 3 ? 9 : 12);
    }

    /**
     * Decodes {@code coeff_abs_level_remaining} (9.3.3.11): a truncated Rice prefix followed by a
     * k-th order Exp-Golomb suffix, or by the limited Exp-Golomb suffix of 9.3.3.4 when extended
     * precision processing is enabled.
     *
     * @param rice the Rice parameter {@code cRiceParam}
     * @param log2Range {@code log2TransformRange} of the component
     * @return the value
     * @throws MediaException.Malformed if the prefix or the value is too long
     */
    private int coeffAbsLevelRemaining(int rice, int log2Range) {
        if (rice > 24) {
            throw new MediaException.Malformed("coeff_abs_level_remaining Rice parameter too large");
        }
        if (sps.extendedPrecision) {
            var maxExtension = 28 - log2Range;
            var limit = 4 + maxExtension;
            var prefix = 0;
            while (prefix < limit && cabac.decodeBypass() == 1) {
                prefix++;
            }
            if (prefix <= 3) {
                return (prefix << rice) + cabac.decodeBypassBins(rice);
            }
            var extension = prefix - 4;
            var escapeLength = extension == maxExtension ? log2Range : extension + rice + 1;
            return (4 << rice) + (((1 << extension) - 1) << (rice + 1)) + cabac.decodeBypassBins(escapeLength);
        }
        var prefix = cabac.decodeBypassOnes(32);
        if (prefix > 32) {
            throw new MediaException.Malformed("coeff_abs_level_remaining prefix too long");
        }
        if (prefix <= 3) {
            return (prefix << rice) + cabac.decodeBypassBins(rice);
        }
        var bits = prefix - 3 + rice;
        if (bits > 24) {
            throw new MediaException.Malformed("coeff_abs_level_remaining too large");
        }
        return (((1 << (prefix - 3)) + 2) << rice) + cabac.decodeBypassBins(bits);
    }

    /**
     * Scales and transforms the coefficients of a transform block, applies the residual
     * modifications of residual DPCM and cross-component prediction, and adds the residual to the
     * reconstructed picture (8.6.2 to 8.6.7).
     *
     * <p>The residual DPCM direction, the transform skip flag and the cross-component scale are
     * those parsed for the block. The residual of a luma block is kept for the cross-component
     * prediction of the chroma blocks of the same transform unit.
     *
     * @param x0 the luma x of the transform block
     * @param y0 the luma y of the transform block
     * @param log2Size the transform block size of the component as a power of two
     * @param cIdx the component
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param rows the number of leading rows that may hold non-zero coefficients
     */
    private void reconstructResidual(int x0, int y0, int log2Size, int cIdx, int columns, int rows) {
        var size = 1 << log2Size;
        var count = size * size;
        var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
        var coeffs = coefficients;
        var res = residual;
        var rotate = sps.transformSkipRotation && size == 4 && cuIntra;
        if (bypass) {
            if (rotate) {
                for (var i = 0; i < count; i++) {
                    res[i] = coeffs[count - 1 - i];
                }
            } else {
                System.arraycopy(coeffs, 0, res, 0, count);
            }
        } else {
            var log2Range = cIdx == 0 ? sps.log2TransformRangeLuma : sps.log2TransformRangeChroma;
            var coeffMax = (1L << log2Range) - 1;
            var coeffMin = -(1L << log2Range);
            var finalShift = Math.max(20 - bitDepth, sps.extendedPrecision ? 11 : 0);
            if (columns == 1 && rows == 1 && !transformSkip && log2Range <= 15 && rdpcmDirection < 0
                    && !pps.crossComponentPrediction && !actTu && frame.eightBit()
                    && !(cuIntra && cIdx == 0 && log2Size == 2)) {
                var column = Math.clamp((64 * coeffs[0] + 64) >> 7, -32768, 32767);
                coeffs[0] = 0;
                var dc = (64 * column + (1 << (finalShift - 1))) >> finalShift;
                if (dc != 0) {
                    addConstant(x0, y0, size, cIdx, dc);
                }
                return;
            }
            if (transformSkip) {
                var tsShift = 5 + log2Size;
                var round = 1L << (finalShift - 1);
                for (var i = 0; i < count; i++) {
                    var d = rotate ? coeffs[count - 1 - i] : coeffs[i];
                    res[i] = (int) ((((long) d << tsShift) + round) >> finalShift);
                }
            } else {
                var dst = cuIntra && cIdx == 0 && log2Size == 2;
                if (log2Range > 15) {
                    transform.inverseWide(coeffs, log2Size, dst, finalShift, (int) coeffMin, (int) coeffMax, res);
                } else {
                    transform.inverse(coeffs, log2Size, dst, finalShift, dst ? size : columns, dst ? size : rows, res,
                            pic.dsp);
                }
            }
        }
        if (rdpcmDirection >= 0) {
            accumulate(res, size, rdpcmDirection == 1);
        }
        if (cIdx == 0) {
            if (pps.crossComponentPrediction) {
                System.arraycopy(res, 0, lumaResidual, 0, count);
                lumaResidualValid = true;
            }
        } else if (resScaleVal != 0 && lumaResidualValid) {
            addCrossComponent(res, count);
        }
        if (actTu) {
            System.arraycopy(res, 0, actResidual[cIdx], 0, count);
            actPresent[cIdx] = true;
        } else {
            addResidual(x0, y0, size, cIdx, res);
        }
        for (var y = 0; y < rows; y++) {
            java.util.Arrays.fill(coeffs, y * size, y * size + columns, 0);
        }
    }

    /**
     * Applies the residual modification of blocks using a transform bypass (8.6.5): every sample
     * accumulates the sample before it, horizontally or vertically.
     *
     * @param res the residual samples, modified in place
     * @param size the block size
     * @param vertical whether the accumulation runs down the columns
     */
    private static void accumulate(int[] res, int size, boolean vertical) {
        if (vertical) {
            for (var i = size; i < size * size; i++) {
                res[i] += res[i - size];
            }
        } else {
            for (var y = 0; y < size; y++) {
                var row = y * size;
                for (var x = 1; x < size; x++) {
                    res[row + x] += res[row + x - 1];
                }
            }
        }
    }

    /**
     * Adds the cross-component prediction from the luma residual of the transform unit to a chroma
     * residual (8.6.6).
     *
     * @param res the chroma residual samples, modified in place
     * @param count the number of samples
     */
    private void addCrossComponent(int[] res, int count) {
        var luma = lumaResidual;
        var scale = resScaleVal;
        var bitDepthC = sps.bitDepthChroma;
        var bitDepthY = sps.bitDepthLuma;
        for (var i = 0; i < count; i++) {
            res[i] += (int) ((scale * (((long) luma[i] << bitDepthC) >> bitDepthY)) >> 3);
        }
    }

    /**
     * Reconstructs a chroma transform block without coefficients whose residual is only the
     * cross-component prediction from the luma residual (8.6.6 and 8.6.7).
     *
     * @param x0 the luma x of the transform block
     * @param y0 the luma y of the transform block
     * @param log2Size the transform block size as a power of two
     * @param cIdx the component, 1 or 2
     */
    private void crossComponentOnly(int x0, int y0, int log2Size, int cIdx) {
        var size = 1 << log2Size;
        var count = size * size;
        var res = residual;
        java.util.Arrays.fill(res, 0, count, 0);
        addCrossComponent(res, count);
        if (actTu) {
            System.arraycopy(res, 0, actResidual[cIdx], 0, count);
            actPresent[cIdx] = true;
        } else {
            addResidual(x0, y0, size, cIdx, res);
        }
    }

    /**
     * Applies the adaptive color transform to the residuals of the three components of a 4:4:4
     * transform unit (8.6.8.2) and adds them to the predicted samples.
     *
     * @param x0 the luma x of the transform unit
     * @param y0 the luma y of the transform unit
     * @param log2Size the transform block size as a power of two
     */
    private void inverseColorTransform(int x0, int y0, int log2Size) {
        var size = 1 << log2Size;
        var count = size * size;
        for (var c = 0; c < 3; c++) {
            if (!actPresent[c]) {
                java.util.Arrays.fill(actResidual[c], 0, count, 0);
            }
        }
        var rY = actResidual[0];
        var rCb = actResidual[1];
        var rCr = actResidual[2];
        var maxY = (1 << sps.log2TransformRangeLuma) - 1;
        var minY = -(1 << sps.log2TransformRangeLuma);
        var maxC = (1 << sps.log2TransformRangeChroma) - 1;
        var minC = -(1 << sps.log2TransformRangeChroma);
        var depth = Math.max(sps.bitDepthLuma, sps.bitDepthChroma);
        var deltaY = bypass ? 0 : depth - sps.bitDepthLuma;
        var deltaC = bypass ? 0 : depth - sps.bitDepthChroma;
        var chromaShift = bypass ? 0 : deltaC + 1;
        var offsetY = deltaY > 0 ? 1 << (deltaY - 1) : 0;
        var offsetC = deltaC > 0 ? 1 << (deltaC - 1) : 0;
        for (var i = 0; i < count; i++) {
            var y = Math.clamp(rY[i], minY, maxY) << deltaY;
            var cb = Math.clamp(rCb[i], minC, maxC) << chromaShift;
            var cr = Math.clamp(rCr[i], minC, maxC) << chromaShift;
            var t = y - (cb >> 1);
            y = t + cb;
            cb = t - (cr >> 1);
            cr += cb;
            rY[i] = (y + offsetY) >> deltaY;
            rCb[i] = (cb + offsetC) >> deltaC;
            rCr[i] = (cr + offsetC) >> deltaC;
        }
        actTu = false;
        addResidual(x0, y0, size, 0, rY);
        addResidual(x0, y0, size, 1, rCb);
        addResidual(x0, y0, size, 2, rCr);
    }

    /**
     * Adds one value to every predicted sample of a block of an 8-bit component and clips the
     * result to the sample range (8.6.7).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param size the block size in samples of the component
     * @param cIdx the component
     * @param value the value to add
     */
    private void addConstant(int x0, int y0, int size, int cIdx, int value) {
        if (cIdx == 0 && dmmPending && x0 == dmmX && y0 == dmmY && size == dmmSize) {
            reconstructDepthIntra(null, value);
            return;
        }
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        var xc = cIdx == 0 ? x0 : x0 >> sps.chromaShiftX;
        var yc = cIdx == 0 ? y0 : y0 >> sps.chromaShiftY;
        var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
        pic.dsp.addConstant(plane8, yc * stride + xc, stride, size, value);
    }

    /**
     * Adds a residual block to the predicted samples of a component and clips the result to the
     * sample range (8.6.7).
     *
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param size the block size in samples of the component
     * @param cIdx the component
     * @param res the residual samples, {@code size} per row
     */
    private void addResidual(int x0, int y0, int size, int cIdx, int[] res) {
        if (cIdx == 0 && dmmPending && x0 == dmmX && y0 == dmmY && size == dmmSize) {
            reconstructDepthIntra(res, 0);
            return;
        }
        var stride = cIdx == 0 ? frame.width : frame.chromaWidth;
        var xc = cIdx == 0 ? x0 : x0 >> sps.chromaShiftX;
        var yc = cIdx == 0 ? y0 : y0 >> sps.chromaShiftY;
        if (frame.eightBit()) {
            var plane8 = cIdx == 0 ? frame.y8 : cIdx == 1 ? frame.cb8 : frame.cr8;
            pic.dsp.addResidual(plane8, yc * stride + xc, stride, res, size);
            return;
        }
        var plane = cIdx == 0 ? frame.y : cIdx == 1 ? frame.cb : frame.cr;
        var max = (1 << (cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma)) - 1;
        pic.dsp.addResidual(plane, yc * stride + xc, stride, res, size, max);
    }

    /**
     * Reconstructs a luma block predicted with a depth intra mode whose predicted values lie
     * outside the sample range: each sample is its segment's predicted value plus the residual,
     * clipped (8.6.7).
     *
     * @param res the residual samples, {@link #dmmSize} per row, or {@code null} for a constant
     * @param constant the residual of every sample when {@code res} is {@code null}
     */
    private void reconstructDepthIntra(int[] res, int constant) {
        dmmPending = false;
        var size = dmmSize;
        var stride = frame.width;
        var max = (1 << sps.bitDepthLuma) - 1;
        var value0 = depthIntra.raw0();
        var value1 = depthIntra.raw1();
        for (var j = 0; j < size; j++) {
            var row = (dmmY + j) * stride + dmmX;
            for (var i = 0; i < size; i++) {
                var k = j * size + i;
                var residualValue = res != null ? res[k] : constant;
                var sample = Math.clamp((dmmPattern[k] == 0 ? value0 : value1) + residualValue, 0, max);
                if (frame.y8 != null) {
                    frame.y8[row + i] = (byte) sample;
                } else {
                    frame.y[row + i] = (short) sample;
                }
            }
        }
    }

    /**
     * Returns the quantization parameter {@code qP} of a component of the current coding unit
     * (8.6.1 and 8.6.2).
     *
     * @param cIdx the component
     * @return {@code qP}, including the bit depth offset
     */
    private int quantizer(int cIdx) {
        var qpBdOffsetY = 6 * (sps.bitDepthLuma - 8);
        if (cIdx == 0) {
            var act = actTu ? pps.actQpOffset[0] + header.actQpOffset[0] : 0;
            return Math.clamp(qpY + qpBdOffsetY + act, 0, 51 + qpBdOffsetY);
        }
        var qpBdOffsetC = 6 * (sps.bitDepthChroma - 8);
        var cuOffset = cIdx == 1 ? cuQpOffsetCb : cuQpOffsetCr;
        var offset = actTu ? pps.actQpOffset[cIdx] + header.actQpOffset[cIdx] + cuOffset
                : cIdx == 1 ? pps.cbQpOffset + header.cbQpOffset + cuOffset : pps.crQpOffset + header.crQpOffset + cuOffset;
        var qPi = Math.clamp(qpY + offset, -qpBdOffsetC, 57);
        int qPc;
        if (sps.chromaArrayType == 1) {
            qPc = qPi < 30 ? qPi : qPi > 43 ? qPi - 6 : QPC_TABLE[qPi - 30];
        } else {
            qPc = Math.min(qPi, 51);
        }
        return qPc + qpBdOffsetC;
    }
}
