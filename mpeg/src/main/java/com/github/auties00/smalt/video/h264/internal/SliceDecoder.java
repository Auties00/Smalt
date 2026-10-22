package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes the slice data of H.264 pictures: macroblock parsing with CAVLC or CABAC, motion
 * vector and intra mode derivation, and the reconstruction of every macroblock into the frame
 * being decoded (ITU-T H.264 7.3.4, 7.3.5, 8.3 to 8.5 and 9).
 *
 * <p>A decoder instance works on one picture at a time and is not thread-safe. Neighboring
 * macroblock data is gathered into small caches laid out on a grid of stride 8, where
 * {@code (x, y)} in 4x4 block units (from -1 to 4 horizontally and -1 to 3 vertically) maps to
 * index {@code 9 + y * 8 + x}: row {@code y = -1} holds the bottom row of the macroblock above,
 * column {@code x = -1} the right column of the macroblock to the left, and column {@code x = 4}
 * is permanently unavailable.
 *
 * <p>An inter macroblock predicted as one 16x16 partition ({@code partShape} 0, which a B_Skip
 * or B_Direct_16x16 macroblock with uniform direct motion becomes) keeps its motion in the
 * top-left entry of the motion caches alone: nothing inside such a macroblock is predicted from
 * its other entries, so they are neither filled nor read, and its neighbor entries are filled
 * only where the 16x16 prediction reads them.
 */
final class SliceDecoder {
    /**
     * The cache index of the top-left 4x4 block of the current macroblock.
     */
    private static final int CACHE_ORIGIN = 9;

    /**
     * Writes little-endian {@code long}s into byte arrays.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);

    /**
     * The cache marker of an unavailable neighbor.
     */
    private static final int UNAVAILABLE = -2;

    /**
     * The cache index of each 4x4 block in raster order.
     */
    private static final byte[] CACHE_OF_RASTER = {9, 10, 11, 12, 17, 18, 19, 20, 25, 26, 27, 28, 33, 34, 35, 36};

    /**
     * Whether the top-right 4x4 neighbor of each luma 4x4 block, in {@code luma4x4BlkIdx} order,
     * lies inside the macroblock and is decoded before the block; blocks in the top row use the
     * macroblocks above instead.
     */
    private static final boolean[] TOP_RIGHT_INSIDE = {
            false, false, true, false, false, false, true, false, true, true, true, false, true, false, true, false
    };

    /**
     * The shape of each 8x8 sub-macroblock type: 0 8x8, 1 8x4, 2 4x8, 3 4x4, indexed by P
     * {@code sub_mb_type}.
     */
    private static final byte[] P_SUB_SHAPE = {0, 1, 2, 3};

    /**
     * The shape of each B {@code sub_mb_type}; direct (0) is marked with shape 0.
     */
    private static final byte[] B_SUB_SHAPE = {0, 0, 0, 0, 1, 2, 1, 2, 1, 2, 3, 3, 3};

    /**
     * The prediction flags (bit 0 list 0, bit 1 list 1) of each B {@code sub_mb_type}.
     */
    private static final byte[] B_SUB_PRED = {3, 1, 2, 3, 1, 1, 2, 2, 3, 3, 1, 2, 3};

    /**
     * The partition shape (0 16x16, 1 16x8, 2 8x16, 3 8x8) of each B {@code mb_type} 0 to 22.
     */
    private static final byte[] B_SHAPE = {
            0, 0, 0, 0, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 3
    };

    /**
     * The prediction flags of the first partition of each B {@code mb_type}.
     */
    private static final byte[] B_PRED0 = {
            3, 1, 2, 3, 1, 1, 2, 2, 1, 1, 2, 2, 1, 1, 2, 2, 3, 3, 3, 3, 3, 3, 0
    };

    /**
     * The prediction flags of the second partition of each B {@code mb_type}.
     */
    private static final byte[] B_PRED1 = {
            0, 0, 0, 0, 1, 1, 2, 2, 2, 2, 1, 1, 3, 3, 3, 3, 1, 1, 2, 2, 3, 3, 0
    };







    /**
     * The kernels of the picture being decoded, selected by {@link #startPicture}.
     */
    private H264Dsp dsp = H264Dsp.SCALAR;

    /**
     * The kernels of pictures deeper than 8 bits.
     */
    private final H264WideDsp wideDsp = H264WideDsp.INSTANCE;

    /**
     * The bit reader of CAVLC slice data and PCM samples.
     */
    private final H264BitReader bits = new H264BitReader();

    /**
     * The arithmetic decoding engine of CABAC slice data.
     */
    private final CabacReader cabac = new CabacReader();

    /**
     * The CAVLC residual decoder.
     */
    private final CavlcReader cavlc = new CavlcReader();

    /**
     * The intra predictor.
     */
    private final IntraPredictor intra = new IntraPredictor();

    /**
     * The inter predictor.
     */
    private final InterPredictor inter = new InterPredictor();

    /**
     * The intra predictor of pictures deeper than 8 bits.
     */
    private final WideIntraPredictor wideIntra = new WideIntraPredictor();

    /**
     * The inter predictor of pictures deeper than 8 bits.
     */
    private final WideInterPredictor wideInter = new WideInterPredictor();

    /**
     * Whether the frame being decoded holds 16-bit samples ({@link DecoderFrame#wide}).
     */
    private boolean wide;

    /**
     * The 16-bit luma plane of a {@link #wide} frame being decoded.
     */
    private short[] lumaPlane16;

    /**
     * The 16-bit Cb plane of a {@link #wide} frame being decoded, or {@code null} for
     * monochrome.
     */
    private short[] cbPlane16;

    /**
     * The 16-bit Cr plane of a {@link #wide} frame being decoded, or {@code null} for
     * monochrome.
     */
    private short[] crPlane16;

    /**
     * The largest luma sample value, {@code (1 << BitDepthY) - 1}.
     */
    private int lumaMax = 255;

    /**
     * The largest chroma sample value, {@code (1 << BitDepthC) - 1}.
     */
    private int chromaMax = 255;

    /**
     * The luma quantization parameter range offset {@code QpBdOffsetY}, {@code 6 * (BitDepthY - 8)}.
     */
    private int qpBdOffsetY;

    /**
     * The chroma quantization parameter range offset {@code QpBdOffsetC},
     * {@code 6 * (BitDepthC - 8)}.
     */
    private int qpBdOffsetC;

    /**
     * The frame being decoded.
     */
    DecoderFrame frame;

    /**
     * The per-macroblock state of the picture being decoded.
     */
    PictureState pic;

    /**
     * The picture width in macroblocks.
     */
    private int mbWidth;

    /**
     * The number of macroblocks in the picture.
     */
    private int mbCount;

    /**
     * The luma plane of the frame being decoded.
     */
    private byte[] lumaPlane;

    /**
     * The Cb plane of the frame being decoded, or {@code null} for monochrome.
     */
    private byte[] cbPlane;

    /**
     * The Cr plane of the frame being decoded, or {@code null} for monochrome.
     */
    private byte[] crPlane;

    /**
     * The luma row stride.
     */
    private int lumaStride;

    /**
     * The chroma row stride.
     */
    private int chromaStride;

    /**
     * Whether the picture has chroma.
     */
    private boolean hasChroma;

    /**
     * Whether the picture's chroma is 4:2:2 ({@code ChromaArrayType} 2) rather than 4:2:0.
     */
    private boolean chroma422;

    /**
     * Whether the picture's chroma is 4:4:4 ({@code ChromaArrayType} 3): Cb and Cr are predicted,
     * coded and reconstructed like luma.
     */
    private boolean chroma444;

    /**
     * The {@code colour_plane_id} of the color plane being decoded when the picture's planes are
     * coded separately, which selects the plane's scaling lists; 0 otherwise.
     */
    private int colorPlane;

    /**
     * Whether the sequence sets {@code qpprime_y_zero_transform_bypass_flag}.
     */
    private boolean transformBypass;

    /**
     * Whether the current macroblock is coded without transform ({@code TransformBypassModeFlag}:
     * {@link #transformBypass} with {@code QP'Y} equal to 0).
     */
    private boolean bypass;

    /**
     * The chroma width of a macroblock, {@code MbWidthC}: 16 for 4:4:4, 8 otherwise.
     */
    private int chromaColumns = 8;

    /**
     * The chroma height of a macroblock, {@code MbHeightC}: 16 for 4:2:2, 8 otherwise.
     */
    private int chromaRows = 8;

    /**
     * The slice being decoded.
     */
    private SliceTask task;

    /**
     * The header of the slice being decoded.
     */
    private H264SliceHeader header;

    /**
     * The picture parameter set of the slice.
     */
    private H264Pps pps;

    /**
     * The slice type, one of the {@link H264SliceHeader} constants.
     */
    private int sliceType;

    /**
     * Whether the slice is a B slice.
     */
    private boolean bSlice;

    /**
     * Whether the slice is an I slice.
     */
    private boolean iSlice;

    /**
     * Whether the slice uses CABAC.
     */
    private boolean cabacMode;

    /**
     * The number of the slice being decoded, unique across the decoder's lifetime.
     */
    private int sliceNum;

    /**
     * The macroblock to slice group map, or {@code null} with a single slice group.
     */
    private int[] sliceGroupMap;

    /**
     * The reference picture lists of the slice.
     */
    private DecoderFrame[][] refList;

    /**
     * The number of active entries of each list.
     */
    private final int[] refCount = new int[2];

    /**
     * The current luma quantization parameter {@code QPY}.
     */
    private int qp;

    /**
     * Whether the previous macroblock in decoding order had a non-zero {@code mb_qp_delta}.
     */
    private boolean lastQpDeltaNonZero;

    /**
     * The level scale factors of the six 4x4 scaling lists, indexed
     * {@code [list][qP % 6][raster]}.
     */
    private final int[][][] levelScale4 = new int[6][6][16];

    /**
     * The level scale factors of the six 8x8 scaling lists (intra and inter, for Y, Cb and Cr),
     * indexed {@code [list][qP % 6][raster]}.
     */
    private final int[][][] levelScale8 = new int[6][6][64];

    /**
     * The scale of the coefficient levels of transform-bypass blocks, which are their residual
     * samples (ITU-T H.264 8.5.12 and 8.5.13): 1 at every position.
     */
    private static final int[] UNIT_SCALE = unitScale();

    /**
     * The scaling lists the level scale factors were computed from.
     */
    private int[][] scaleSource4;

    /**
     * The 8x8 scaling lists the level scale factors were computed from.
     */
    private int[][] scaleSource8;

    /**
     * The weighted prediction mode: 0 default, 1 explicit, 2 implicit.
     */
    private int weightMode;


    /**
     * The co-located picture of temporal and spatial direct prediction, {@code RefPicList1[0]}.
     */
    private DecoderFrame colocated;

    /**
     * Whether {@code RefPicList1[0]} is a short-term reference picture.
     */
    private boolean colocatedShortTerm;

    /**
     * The current macroblock address.
     */
    private int mbAddr;

    /**
     * The current macroblock column.
     */
    private int mbX;

    /**
     * The current macroblock row.
     */
    private int mbY;

    /**
     * Whether the left macroblock is available.
     */
    private boolean availA;

    /**
     * Whether the upper macroblock is available.
     */
    private boolean availB;

    /**
     * Whether the upper-right macroblock is available.
     */
    private boolean availC;

    /**
     * Whether the upper-left macroblock is available.
     */
    private boolean availD;

    /**
     * The flags of the current macroblock ({@code DecoderFrame.MB_*}).
     */
    private int mbFlags;

    /**
     * The partition shape of an inter macroblock: 0 16x16, 1 16x8, 2 8x16, 3 8x8.
     */
    private int partShape;

    /**
     * The prediction flags (bit 0 list 0, bit 1 list 1) of the two partitions.
     */
    private final int[] partPred = new int[2];

    /**
     * The shape of each 8x8 partition: 0 8x8, 1 8x4, 2 4x8, 3 4x4.
     */
    private final int[] subShape = new int[4];

    /**
     * The prediction flags of each 8x8 partition.
     */
    private final int[] subPred = new int[4];

    /**
     * The 8x8 partitions predicted in direct mode, one bit each.
     */
    private int directMask;

    /**
     * The reference indices of each list per 8x8 partition.
     */
    private final int[][] refIdx = new int[2][4];

    /**
     * The motion vector differences of each list per 4x4 block in raster order, packed like
     * motion vectors.
     */
    private final int[][] mvd = new int[2][16];

    /**
     * The coded block pattern of the current macroblock.
     */
    private int cbp;

    /**
     * The Intra16x16PredMode of the current macroblock.
     */
    private int intra16Mode;

    /**
     * The intra_chroma_pred_mode of the current macroblock.
     */
    private int chromaPredMode;

    /**
     * The Intra4x4PredMode or Intra8x8PredMode of each 4x4 block in raster order.
     */
    private final int[] predModes = new int[16];

    /**
     * The reference index cache of each list.
     */
    private final int[][] refCache = new int[2][40];

    /**
     * The motion vector cache of each list.
     */
    private final int[][] mvCache = new int[2][40];

    /**
     * The absolute motion vector difference cache of each list, for CABAC contexts.
     */
    private final int[][] mvdCache = new int[2][40];

    /**
     * The CABAC {@code ref_idx} context condition of each list: 1 when the neighboring partition
     * has a reference index above 0 and was not predicted in direct mode.
     */
    private final int[][] refCtxCache = new int[2][40];

    /**
     * The luma coefficient count or coded block flag cache.
     */
    private final int[] nnzCache = new int[40];

    /**
     * The intra prediction mode cache; {@code -1} marks neighbors that force DC prediction.
     */
    private final int[] modeCache = new int[40];

    /**
     * The luma coefficients: sixteen 4x4 blocks in {@code luma4x4BlkIdx} order, or four 8x8
     * blocks, in raster order within each block.
     */
    private final int[] lumaCoeffs = new int[256];

    /**
     * The Cb coefficients: four (4:2:0) or eight (4:2:2) 4x4 blocks in {@code chroma4x4BlkIdx}
     * order, or for 4:4:4 the blocks of {@link #lumaCoeffs} in its layout.
     */
    private final int[] cbCoeffs = new int[256];

    /**
     * The Cr coefficients, laid out as {@link #cbCoeffs}.
     */
    private final int[] crCoeffs = new int[256];

    /**
     * The coefficients of each color component that is coded like luma, indexed by component:
     * luma, then the Cb and Cr of 4:4:4 macroblocks.
     */
    private final int[][] planeCoeffs = {lumaCoeffs, cbCoeffs, crCoeffs};

    /**
     * The Intra16x16 DC levels in raster order.
     */
    private final int[] lumaDc = new int[16];

    /**
     * The chroma DC levels in coefficient list order, Cb from index 0 and Cr from index 8.
     */
    private final int[] chromaDc = new int[16];

    /**
     * The Cb Intra16x16 DC levels of 4:4:4 macroblocks, laid out as {@link #lumaDc}.
     */
    private final int[] cbDc = new int[16];

    /**
     * The Cr Intra16x16 DC levels of 4:4:4 macroblocks, laid out as {@link #lumaDc}.
     */
    private final int[] crDc = new int[16];

    /**
     * The Intra16x16 DC levels of each color component that is coded like luma.
     */
    private final int[][] planeDc = {lumaDc, cbDc, crDc};

    /**
     * The blocks with coefficients of each color component coded like luma: bits by
     * {@code luma4x4BlkIdx}, or by 8x8 block with the 8x8 transform.
     */
    private final int[] coded = new int[3];

    /**
     * The blocks of each color component coded like luma whose only non-zero coefficient is the
     * DC one: 4x4 blocks by block index, or 8x8 blocks by quarter index in a macroblock with the
     * 8x8 transform. A block in both this set and {@link #coded} takes the full inverse transform.
     */
    private final int[] dcOnly = new int[3];

    /**
     * The coded DC blocks of the current CABAC macroblock as bits 6 (luma or Y), 7 (Cb) and 8 (Cr)
     * of {@link PictureState#cbp}.
     */
    private int dcFlags;

    /**
     * The residual of a transform-bypass region in raster order, 16 samples per row.
     */
    private final int[] bypassScratch = new int[256];

    /**
     * The chroma AC blocks with coefficients: bit {@code chroma4x4BlkIdx} for Cb, bit
     * {@code 8 + chroma4x4BlkIdx} for Cr.
     */
    private int chromaCoded;

    /**
     * The chroma components with DC coefficients: bit 0 Cb, bit 1 Cr.
     */
    private int chromaDcCoded;

    /**
     * Whether the current macroblock's chroma residual needs reconstruction.
     */
    private boolean chromaResidual;

    /**
     * The structure of the picture being decoded: {@link DecoderFrame#FRAME},
     * {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM}.
     */
    private int structure = DecoderFrame.FRAME;

    /**
     * Whether the picture being decoded is a field.
     */
    private boolean fieldPicture;

    /**
     * Whether the picture being decoded is an MBAFF frame.
     */
    private boolean mbaff;

    /**
     * The parity of the field picture being decoded (0 top, 1 bottom), 0 for a frame.
     */
    private int pictureParity;

    /**
     * The index distance from a macroblock to the one above it in a picture that is not an MBAFF
     * frame: the width in macroblocks for a frame, twice that for a field.
     */
    private int mbStride;

    /**
     * The address of the current macroblock in the picture's own numbering.
     */
    private int currAddr = -2;

    /**
     * Whether the current macroblock is a field macroblock.
     */
    private boolean fieldMb;

    /**
     * The parity of the field of the current field macroblock (0 top, 1 bottom), or -1 for a
     * frame macroblock.
     */
    private int mbParity = -1;

    /**
     * The reference view of the current macroblock: 0 for the macroblocks of frames and fields,
     * whose reference indices name list entries, and {@code 1 + parity} for the field
     * macroblocks of MBAFF frames, whose reference indices name fields of the list entries.
     */
    private int view;

    /**
     * The index of the left neighbor macroblock A of the current macroblock, valid when
     * {@link #availA}.
     */
    private int mbA;

    /**
     * The index of the upper neighbor macroblock B, valid when {@link #availB}.
     */
    private int mbB;

    /**
     * The index of the upper-right neighbor macroblock C, valid when {@link #availC}.
     */
    private int mbC;

    /**
     * The index of the upper-left neighbor macroblock D, valid when {@link #availD}.
     */
    private int mbD;

    /**
     * The index of the top-left luma sample of the current macroblock.
     */
    private int lumaBase;

    /**
     * The index of the top-left chroma sample of the current macroblock.
     */
    private int chromaBase;

    /**
     * The 4x4 coefficient scan of the current macroblock: zig-zag or field.
     */
    private byte[] scan4 = H264Tables.ZIGZAG_4X4;

    /**
     * The 8x8 coefficient scan of the current macroblock: zig-zag or field.
     */
    private byte[] scan8 = H264Tables.ZIGZAG_8X8;

    /**
     * The reference picture of each reference index per view and list, {@code (id << 2) | kind}
     * as stored in {@link DecoderFrame#refId}, indexed {@code [view][list][refIdx]}.
     */
    private final int[][][] viewRefId = new int[3][2][64];

    /**
     * The frame holding the reference of each reference index per view and list.
     */
    private final DecoderFrame[][][] viewFrame = new DecoderFrame[3][2][64];

    /**
     * The field each reference index reads per view and list (0 top, 1 bottom), or -1 for a
     * frame.
     */
    private final int[][][] viewParity = new int[3][2][64];

    /**
     * The picture order count of the picture each reference index names, per view and list.
     */
    private final int[][][] viewPoc = new int[3][2][64];

    /**
     * The reference marking of the picture each reference index names, per view and list.
     */
    private final int[][][] viewMarking = new int[3][2][64];

    /**
     * The picture order count of the current picture per view: of the picture, then of its top
     * and bottom fields.
     */
    private final int[] viewCurrentPoc = new int[3];

    /**
     * The implicit bi-predictive list 0 weights of each view, indexed
     * {@code refIdxL0 * 64 + refIdxL1} for the field views and {@code refIdxL0 * 32 + refIdxL1}
     * for view 0.
     */
    private final int[][] viewImplicit = {new int[32 * 32], new int[64 * 64], new int[64 * 64]};

    /**
     * The temporal direct scale factor of each list 0 reference index per view, or {@code 9999}
     * when the motion vector is copied unscaled.
     */
    private final int[][] viewDirectScale = new int[3][64];

    /**
     * Constructs a slice decoder, marking the motion cache column right of the macroblock
     * unavailable for good.
     */
    SliceDecoder() {
        for (var list = 0; list < 2; list++) {
            var rc = refCache[list];
            rc[13] = UNAVAILABLE;
            rc[21] = UNAVAILABLE;
            rc[29] = UNAVAILABLE;
            rc[37] = UNAVAILABLE;
        }
    }

    /**
     * Prepares decoding of a new picture.
     *
     * @param frame the frame to decode into
     * @param pic the per-macroblock state sized for the frame
     * @param dsp the kernels the picture is reconstructed with
     * @param structure the picture structure: {@link DecoderFrame#FRAME}, {@link DecoderFrame#TOP}
     * or {@link DecoderFrame#BOTTOM}
     * @param mbaff whether the picture is an MBAFF frame
     * @param colorPlane the {@code colour_plane_id} of the plane the picture is, for frames of
     * separately coded color planes, 0 otherwise
     */
    void startPicture(DecoderFrame frame, PictureState pic, H264Dsp dsp, int structure, boolean mbaff,
                      int colorPlane) {
        this.frame = frame;
        this.pic = pic;
        this.dsp = dsp;
        this.structure = structure;
        this.fieldPicture = structure != DecoderFrame.FRAME;
        this.mbaff = mbaff;
        this.pictureParity = structure == DecoderFrame.BOTTOM ? 1 : 0;
        this.mbWidth = frame.mbWidth;
        this.mbStride = fieldPicture ? 2 * frame.mbWidth : frame.mbWidth;
        this.mbCount = frame.mbWidth * (fieldPicture ? frame.mbHeight / 2 : frame.mbHeight);
        this.mbAddr = -2;
        this.currAddr = -2;
        this.lumaPlane = frame.luma;
        this.cbPlane = frame.cb;
        this.crPlane = frame.cr;
        this.wide = frame.wide;
        this.lumaPlane16 = frame.luma16;
        this.cbPlane16 = frame.cb16;
        this.crPlane16 = frame.cr16;
        this.lumaMax = (1 << frame.bitDepthLuma) - 1;
        this.chromaMax = (1 << frame.bitDepthChroma) - 1;
        this.qpBdOffsetY = 6 * (frame.bitDepthLuma - 8);
        this.qpBdOffsetC = 6 * (frame.bitDepthChroma - 8);
        this.lumaStride = fieldPicture ? 2 * frame.lumaStride : frame.lumaStride;
        this.chromaStride = fieldPicture ? 2 * frame.chromaStride : frame.chromaStride;
        this.hasChroma = frame.hasChroma;
        this.chroma422 = frame.chromaFormat == 2;
        this.chroma444 = frame.chromaFormat == 3;
        this.colorPlane = colorPlane;
        this.chromaRows = frame.chromaMbHeight;
        this.chromaColumns = frame.chromaMbWidth;
        this.fieldMb = fieldPicture;
        this.mbParity = fieldPicture ? pictureParity : -1;
        this.view = 0;
        this.scan4 = fieldPicture ? H264Tables.FIELD_4X4 : H264Tables.ZIGZAG_4X4;
        this.scan8 = fieldPicture ? H264Tables.FIELD_8X8 : H264Tables.ZIGZAG_8X8;
        if (wide) {
            wideIntra.setBitDepths(frame.bitDepthLuma, frame.bitDepthChroma);
            wideInter.startPicture(frame);
        } else {
            inter.startPicture(frame, dsp);
        }
    }


    /**
     * Decodes the data of one slice into the current picture.
     *
     * @param task the slice: header, payload, reference picture lists (entries up to the
     * active counts are non-null) and numbering
     * @param onMacroblock notified after each decoded macroblock with its address
     * @throws MediaException.Malformed if the slice data is corrupt
     */
    void decodeSlice(SliceTask task, MacroblockListener onMacroblock) {
        var header = task.header;
        var rbsp = task.rbsp;
        var offset = 0;
        var length = task.size;
        var lists = task.lists;
        var sliceNum = task.sliceNum;
        var sliceGroupMap = task.sliceGroupMap;
        this.task = task;
        this.header = header;
        this.pps = header.pps;
        this.sliceType = header.sliceType;
        this.bSlice = sliceType == H264SliceHeader.B;
        this.iSlice = header.isIntra();
        this.cabacMode = pps.cabac;
        this.sliceNum = sliceNum;
        this.sliceGroupMap = sliceGroupMap;
        this.refList = lists;
        this.refCount[0] = header.numRefIdxActive[0];
        this.refCount[1] = header.numRefIdxActive[1];
        this.qp = header.sliceQp;
        this.lastQpDeltaNonZero = false;
        this.transformBypass = header.sps.transformBypass;
        // Reconstruction expects zeroed coefficient buffers; a slice aborted by corrupt data
        // may have left parsed but unconsumed coefficients behind.
        Arrays.fill(lumaCoeffs, 0);
        Arrays.fill(cbCoeffs, 0);
        Arrays.fill(crCoeffs, 0);
        Arrays.fill(lumaDc, 0);
        Arrays.fill(chromaDc, 0);
        Arrays.fill(cbDc, 0);
        Arrays.fill(crDc, 0);
        prepareScaling();
        prepareViews();
        prepareWeights();
        prepareDirect();
        if (!bSlice) {
            clearList(1);
        }
        if (wide) {
            wideInter.startSlice(header, weightMode);
        } else {
            inter.startSlice(header, weightMode);
        }
        selectView(0);
        var filter = header.disableDeblockingFilterIdc | ((header.filterOffsetA + 12) << 2)
                | ((header.filterOffsetB + 12) << 8);
        var addr = (long) header.firstMbInSlice * (mbaff ? 2 : 1);
        if (addr >= mbCount) {
            throw new MediaException.Malformed("first_mb_in_slice " + header.firstMbInSlice + " out of range");
        }
        bits.init(rbsp, offset, length);
        if (cabacMode) {
            var aligned = (int) ((header.dataBitPosition + 7) >>> 3);
            cabac.init(rbsp, offset + aligned, offset + length);
            CabacTables.initContexts(cabac.states, iSlice ? 0 : header.cabacInitIdc + 1, header.sliceQp,
                    chroma444 ? CabacTables.CONTEXT_COUNT_444 : CabacTables.CONTEXT_COUNT);
            if (mbaff) {
                decodeCabacMbaffSlice((int) addr, filter, onMacroblock);
            } else {
                decodeCabacSlice((int) addr, filter, onMacroblock);
            }
        } else {
            skipTo(bits, header.dataBitPosition);
            var stopBit = NalUnits.stopBitPosition(rbsp, offset, length);
            if (mbaff) {
                decodeCavlcMbaffSlice((int) addr, filter, stopBit, onMacroblock);
            } else {
                decodeCavlcSlice((int) addr, filter, stopBit, onMacroblock);
            }
        }
    }

    /**
     * Derives, for every view the slice uses, the reference picture, field, picture order count
     * and marking of each reference index.
     *
     * <p>View 0 serves frames and fields: reference index {@code i} names list entry {@code i}.
     * Views 1 and 2 serve the top and bottom field macroblocks of MBAFF frames: reference index
     * {@code i} names the field of entry {@code i / 2} with the macroblock's parity when
     * {@code i} is even, and the other field when it is odd (ITU-T H.264 8.4.2.1).
     */
    private void prepareViews() {
        var views = mbaff && !iSlice ? 3 : 1;
        var lists = refList;
        for (var v = 0; v < views; v++) {
            var current = v == 0 ? task.currentPoc : task.currentFieldPoc[v - 1];
            viewCurrentPoc[v] = current;
            for (var list = 0; list < 2; list++) {
                var count = refCount[list] << (v == 0 ? 0 : 1);
                var ids = viewRefId[v][list];
                var frames = viewFrame[v][list];
                var parities = viewParity[v][list];
                var pocs = viewPoc[v][list];
                var marks = viewMarking[v][list];
                for (var r = 0; r < count; r++) {
                    if (v == 0) {
                        var entry = lists[list][r].plane(colorPlane);
                        var entryStructure = task.structures[list][r];
                        frames[r] = entry;
                        if (entryStructure == DecoderFrame.FRAME) {
                            parities[r] = -1;
                            ids[r] = entry.id << 2;
                        } else {
                            parities[r] = entryStructure - 1;
                            ids[r] = (entry.id << 2) | entryStructure;
                        }
                        pocs[r] = task.refPoc[list][r];
                        marks[r] = task.refMarking[list][r];
                    } else {
                        var entry = lists[list][r >> 1].plane(colorPlane);
                        var parity = (r & 1) == 0 ? v - 1 : 2 - v;
                        frames[r] = entry;
                        parities[r] = parity;
                        ids[r] = (entry.id << 2) | (parity + 1);
                        pocs[r] = task.refFieldPoc[list][(r & ~1) + parity];
                        marks[r] = task.refFieldMarking[list][(r & ~1) + parity];
                    }
                }
            }
        }
    }

    /**
     * Makes a view current: the reference indices of the macroblocks that follow name its
     * pictures (see {@link #prepareViews()}).
     *
     * @param v the view
     */
    private void selectView(int v) {
        view = v;
        var parity = v == 0 ? (fieldPicture ? pictureParity : -1) : v - 1;
        if (wide) {
            wideInter.selectReferences(viewFrame[v], viewParity[v], parity, v == 0 ? 0 : 1, viewImplicit[v],
                    v == 0 ? 32 : 64);
        } else {
            inter.selectReferences(viewFrame[v], viewParity[v], parity, v == 0 ? 0 : 1, viewImplicit[v],
                    v == 0 ? 32 : 64);
        }
    }

    /**
     * Sets the position of the macroblock the next inter prediction predicts, in the predictor
     * of the picture's sample size.
     *
     * @param x the horizontal luma position of the macroblock
     * @param y its vertical luma position in the frame or field it is predicted in
     */
    private void positionInter(int x, int y) {
        if (wide) {
            wideInter.position(lumaBase, lumaStride, chromaBase, chromaStride, x, y);
        } else {
            inter.position(lumaBase, lumaStride, chromaBase, chromaStride, x, y);
        }
    }

    /**
     * Forms the inter prediction of the current macroblock from its motion caches.
     */
    private void predictInter() {
        if (wide) {
            wideInter.predictMacroblock(this);
        } else {
            inter.predictMacroblock(this);
        }
    }

    /**
     * Returns the number of reference indices of a list available to the current macroblock.
     *
     * @param list the list index
     * @return the active entry count, doubled for the field macroblocks of MBAFF frames
     */
    private int activeRefs(int list) {
        return refCount[list] << (view == 0 ? 0 : 1);
    }

    /**
     * Advances a reader to an absolute bit position.
     *
     * @param reader the reader, positioned at the start of the payload
     * @param position the bit position
     */
    private static void skipTo(H264BitReader reader, long position) {
        var remaining = position;
        while (remaining > 0) {
            var step = (int) Math.min(remaining, 32);
            reader.skipBits(step);
            remaining -= step;
        }
    }

    /**
     * Receives a notification after each macroblock of a slice is reconstructed.
     */
    @FunctionalInterface
    interface MacroblockListener {
        /**
         * Handles a decoded macroblock.
         *
         * @param mbAddr the macroblock address
         */
        void decoded(int mbAddr);
    }

    /**
     * Returns the address of the macroblock that follows {@code addr} in the current slice
     * group (ITU-T H.264 8.2.2, {@code NextMbAddress}).
     *
     * @param addr the current address
     * @return the next address, or {@link #mbCount} when none remains
     */
    private int nextMbAddr(int addr) {
        var map = sliceGroupMap;
        if (map == null) {
            return addr + 1;
        }
        var group = map[addr];
        var next = addr + 1;
        while (next < mbCount && map[next] != group) {
            next++;
        }
        return next;
    }

    /**
     * Decodes the macroblocks of a CAVLC slice.
     *
     * @param firstMb the first macroblock address
     * @param filter the packed deblocking parameters of the slice
     * @param stopBit the bit position of the {@code rbsp_stop_one_bit}
     * @param onMacroblock the per-macroblock listener
     */
    private void decodeCavlcSlice(int firstMb, int filter, long stopBit, MacroblockListener onMacroblock) {
        var addr = firstMb;
        while (true) {
            if (!iSlice) {
                var run = bits.readUe();
                if (run > mbCount) {
                    throw new MediaException.Malformed("mb_skip_run " + run + " out of range");
                }
                for (var i = 0; i < run; i++) {
                    if (addr >= mbCount) {
                        throw new MediaException.Malformed("slice runs past the end of the picture");
                    }
                    beginMacroblock(addr, filter);
                    decodeSkip();
                    finishMacroblock(onMacroblock);
                    addr = nextMbAddr(addr);
                }
                if (run > 0 && !bits.moreRbspData(stopBit)) {
                    break;
                }
            }
            if (addr >= mbCount) {
                throw new MediaException.Malformed("slice runs past the end of the picture");
            }
            beginMacroblock(addr, filter);
            decodeMacroblock();
            if (bits.overrun()) {
                throw new MediaException.Malformed("truncated slice data");
            }
            finishMacroblock(onMacroblock);
            addr = nextMbAddr(addr);
            if (!bits.moreRbspData(stopBit)) {
                break;
            }
        }
    }

    /**
     * Decodes the macroblocks of a CABAC slice.
     *
     * @param firstMb the first macroblock address
     * @param filter the packed deblocking parameters of the slice
     * @param onMacroblock the per-macroblock listener
     */
    private void decodeCabacSlice(int firstMb, int filter, MacroblockListener onMacroblock) {
        var addr = firstMb;
        while (true) {
            if (addr >= mbCount) {
                throw new MediaException.Malformed("slice runs past the end of the picture");
            }
            beginMacroblock(addr, filter);
            if (!iSlice && decodeSkipFlag()) {
                decodeSkip();
            } else {
                decodeMacroblock();
            }
            if (cabac.overrun()) {
                throw new MediaException.Malformed("truncated slice data");
            }
            finishMacroblock(onMacroblock);
            addr = nextMbAddr(addr);
            if (cabac.terminate() != 0) {
                break;
            }
        }
    }

    /**
     * Sets up the addressing, geometry and neighbor availability of a macroblock of a frame or
     * field.
     *
     * @param addr the macroblock address in the picture's own numbering
     * @param filter the packed deblocking parameters of the slice
     */
    private void beginMacroblock(int addr, int filter) {
        if (addr == currAddr + 1 && mbX + 1 < mbWidth) {
            mbX++;
            mbAddr++;
        } else {
            mbX = addr % mbWidth;
            mbY = addr / mbWidth;
            mbAddr = fieldPicture ? (2 * mbY + pictureParity) * mbWidth + mbX : addr;
        }
        currAddr = addr;
        var index = mbAddr;
        var stride = mbStride;
        var slices = pic.sliceNum;
        var slice = sliceNum;
        mbA = index - 1;
        mbB = index - stride;
        mbC = mbB + 1;
        mbD = mbB - 1;
        availA = mbX > 0 && slices[mbA] == slice;
        availB = mbY > 0 && slices[mbB] == slice;
        availC = mbY > 0 && mbX + 1 < mbWidth && slices[mbC] == slice;
        availD = mbY > 0 && mbX > 0 && slices[mbD] == slice;
        lumaBase = frame.lumaOrigin + pictureParity * frame.lumaStride + mbY * 16 * lumaStride + mbX * 16;
        chromaBase = frame.chromaOrigin + pictureParity * frame.chromaStride + mbY * chromaRows * chromaStride
                + mbX * chromaColumns;
        positionInter(mbX * 16, mbY * 16);
        pic.filter[index] = filter;
        resetMacroblock();
    }

    /**
     * Resets the per-macroblock syntax state before a macroblock is parsed.
     */
    private void resetMacroblock() {
        mbFlags = 0;
        cbp = 0;
        directMask = 0;
        coded[0] = 0;
        coded[1] = 0;
        coded[2] = 0;
        dcOnly[0] = 0;
        dcOnly[1] = 0;
        dcOnly[2] = 0;
        chromaCoded = 0;
        chromaDcCoded = 0;
        chromaResidual = false;
    }

    /**
     * Records the current macroblock as decoded and notifies the listener.
     *
     * @param onMacroblock the per-macroblock listener
     */
    private void finishMacroblock(MacroblockListener onMacroblock) {
        pic.sliceNum[mbAddr] = sliceNum;
        frame.mbType[mbAddr] = fieldMb ? mbFlags | DecoderFrame.MB_FIELD : mbFlags;
        if (onMacroblock != null) {
            onMacroblock.decoded(mbAddr);
        }
    }

    /**
     * Decodes the macroblock pairs of a CAVLC slice of an MBAFF frame (ITU-T H.264 7.3.4).
     *
     * <p>A skipped top macroblock is reconstructed once the field decoding flag of its pair is
     * known: the flag the bottom macroblock carries when it is coded, or the inferred one.
     *
     * @param firstMb the first macroblock address, a top macroblock
     * @param filter the packed deblocking parameters of the slice
     * @param stopBit the bit position of the {@code rbsp_stop_one_bit}
     * @param onMacroblock the per-macroblock listener
     */
    private void decodeCavlcMbaffSlice(int firstMb, int filter, long stopBit, MacroblockListener onMacroblock) {
        var addr = firstMb;
        var pairIsField = false;
        var flagRead = false;
        var prevSkipped = false;
        while (true) {
            if (!iSlice) {
                var run = bits.readUe();
                if (run > mbCount) {
                    throw new MediaException.Malformed("mb_skip_run " + run + " out of range");
                }
                prevSkipped = run > 0;
                for (var i = 0; i < run; i++) {
                    if (addr >= mbCount) {
                        throw new MediaException.Malformed("slice runs past the end of the picture");
                    }
                    if ((addr & 1) == 0) {
                        pairIsField = inferPairField(addr);
                        if (i == run - 1 && bits.moreRbspData(stopBit)) {
                            pairIsField = bits.readFlag();
                            flagRead = true;
                        }
                    }
                    beginMbaffMacroblock(addr, filter, pairIsField);
                    decodeSkip();
                    finishMacroblock(onMacroblock);
                    addr = nextMbAddr(addr);
                }
                if (run > 0 && !bits.moreRbspData(stopBit)) {
                    break;
                }
            }
            if (addr >= mbCount) {
                throw new MediaException.Malformed("slice runs past the end of the picture");
            }
            if ((addr & 1) == 0) {
                pairIsField = bits.readFlag();
            } else if (prevSkipped && !flagRead) {
                pairIsField = bits.readFlag();
            }
            flagRead = false;
            beginMbaffMacroblock(addr, filter, pairIsField);
            decodeMacroblock();
            if (bits.overrun()) {
                throw new MediaException.Malformed("truncated slice data");
            }
            finishMacroblock(onMacroblock);
            addr = nextMbAddr(addr);
            if (!bits.moreRbspData(stopBit)) {
                break;
            }
        }
    }

    /**
     * Decodes the macroblock pairs of a CABAC slice of an MBAFF frame (ITU-T H.264 7.3.4).
     *
     * <p>A skipped top macroblock is reconstructed once the field decoding flag of its pair is
     * known: the flag the bottom macroblock carries when it is coded, or the inferred one.
     *
     * @param firstMb the first macroblock address, a top macroblock
     * @param filter the packed deblocking parameters of the slice
     * @param onMacroblock the per-macroblock listener
     */
    private void decodeCabacMbaffSlice(int firstMb, int filter, MacroblockListener onMacroblock) {
        var addr = firstMb;
        if ((addr & 1) != 0) {
            throw new MediaException.Malformed("MBAFF slice starts at a bottom macroblock");
        }
        while (true) {
            if (addr + 1 >= mbCount) {
                throw new MediaException.Malformed("slice runs past the end of the picture");
            }
            var top = addr;
            var bottom = addr + 1;
            var inferred = inferPairField(top);
            beginMbaffMacroblock(top, filter, inferred);
            if (!iSlice && decodeSkipFlag()) {
                var topIndex = mbAddr;
                frame.mbType[topIndex] = inferred ? DecoderFrame.MB_SKIP | DecoderFrame.MB_FIELD : DecoderFrame.MB_SKIP;
                pic.sliceNum[topIndex] = sliceNum;
                beginMbaffMacroblock(bottom, filter, inferred);
                var bottomSkipped = decodeSkipFlag();
                var field = bottomSkipped ? inferred : decodeFieldFlag(bottom);
                beginMbaffMacroblock(top, filter, field);
                decodeSkip();
                if (cabac.overrun()) {
                    throw new MediaException.Malformed("truncated slice data");
                }
                finishMacroblock(onMacroblock);
                beginMbaffMacroblock(bottom, filter, field);
                if (bottomSkipped) {
                    decodeSkip();
                } else {
                    decodeMacroblock();
                }
            } else {
                var field = decodeFieldFlag(top);
                if (field != inferred) {
                    setMbaffField(field);
                }
                decodeMacroblock();
                if (cabac.overrun()) {
                    throw new MediaException.Malformed("truncated slice data");
                }
                finishMacroblock(onMacroblock);
                beginMbaffMacroblock(bottom, filter, field);
                if (!iSlice && decodeSkipFlag()) {
                    decodeSkip();
                } else {
                    decodeMacroblock();
                }
            }
            if (cabac.overrun()) {
                throw new MediaException.Malformed("truncated slice data");
            }
            finishMacroblock(onMacroblock);
            addr = nextMbAddr(bottom);
            if (cabac.terminate() != 0) {
                break;
            }
        }
    }

    /**
     * Decodes {@code mb_field_decoding_flag} with CABAC (ITU-T H.264 9.3.3.1.1.2).
     *
     * @param addr the address of the macroblock carrying the flag
     * @return the flag
     */
    private boolean decodeFieldFlag(int addr) {
        var pair = addr >> 1;
        var x = pair % mbWidth;
        var y = pair / mbWidth;
        var top = 2 * y * mbWidth + x;
        var slices = pic.sliceNum;
        var types = frame.mbType;
        var inc = 0;
        if (x > 0 && slices[top - 1] == sliceNum && (types[top - 1] & DecoderFrame.MB_FIELD) != 0) {
            inc++;
        }
        if (y > 0 && slices[top - 2 * mbWidth] == sliceNum && (types[top - 2 * mbWidth] & DecoderFrame.MB_FIELD) != 0) {
            inc++;
        }
        return cabac.decision(70 + inc) != 0;
    }

    /**
     * Returns the field decoding flag inferred for a macroblock pair that carries none: the
     * flag of the pair to the left in the same slice, else of the pair above in the same slice,
     * else frame decoding (ITU-T H.264 7.4.4).
     *
     * @param addr the address of the top macroblock of the pair
     * @return whether the pair is inferred to be a field pair
     */
    private boolean inferPairField(int addr) {
        var pair = addr >> 1;
        var x = pair % mbWidth;
        var y = pair / mbWidth;
        var top = 2 * y * mbWidth + x;
        var slices = pic.sliceNum;
        if (x > 0 && slices[top - 1] == sliceNum) {
            return (frame.mbType[top - 1] & DecoderFrame.MB_FIELD) != 0;
        }
        if (y > 0 && slices[top - 2 * mbWidth] == sliceNum) {
            return (frame.mbType[top - 2 * mbWidth] & DecoderFrame.MB_FIELD) != 0;
        }
        return false;
    }

    /**
     * The index of the top macroblock of the current macroblock pair of an MBAFF frame.
     */
    private int pairTop;

    /**
     * The row of the current macroblock pair of an MBAFF frame.
     */
    private int pairY;

    /**
     * Whether the left macroblock pair of the current MBAFF macroblock is available.
     */
    private boolean pairAvailA;

    /**
     * The index of the macroblock each 4x4 luma block row of the current macroblock takes its
     * left neighbor from (ITU-T H.264 6.4.11.4), valid when {@link #availA}.
     */
    private final int[] leftMb = new int[4];

    /**
     * The 4x4 block row, within {@link #leftMb}, of the left neighbor of each 4x4 luma block row.
     */
    private final int[] leftRow = new int[4];

    /**
     * The index of the macroblock each 4x4 chroma block row of the current macroblock takes its
     * left neighbor from (ITU-T H.264 6.4.11.5), valid when {@link #availA}.
     */
    private final int[] leftChromaMb = new int[2];

    /**
     * The 4x4 chroma block row, within {@link #leftChromaMb}, of the left neighbor of each
     * chroma block row.
     */
    private final int[] leftChromaRow = new int[2];

    /**
     * The 4x4 block row of the upper-left neighbor block of the current macroblock in
     * {@link #mbD}.
     */
    private int rowD = 3;

    /**
     * The index of the macroblock holding the upper-left neighbor of the left 4x4 block of each
     * block row 1 to 3 of the current MBAFF macroblock (ITU-T H.264 6.4.11.7 with Table 6-4),
     * indexed by block row; where the left pair is coded in the other of frame and field
     * decoding, it is not the left neighbor of the block row above.
     */
    private final int[] leftDMb = new int[4];

    /**
     * The 4x4 block row, within {@link #leftDMb}, of each of those upper-left neighbors.
     */
    private final int[] leftDRow = new int[4];

    /**
     * The scaled reference index of each list of the upper-left neighbors of block rows 1 to 3
     * of the current MBAFF macroblock, {@code -2} when unavailable, indexed
     * {@code [list][row]}.
     */
    private final int[][] leftDRef = new int[2][4];

    /**
     * The scaled packed motion vectors of those upper-left neighbors, indexed like
     * {@link #leftDRef}.
     */
    private final int[][] leftDMv = new int[2][4];

    /**
     * Sets up the addressing of a macroblock of an MBAFF frame and its geometry and neighbors
     * for a field decoding flag of its pair.
     *
     * @param addr the macroblock address, {@code 2 * pair + bottom}
     * @param filter the packed deblocking parameters of the slice
     * @param field the field decoding flag of the pair, or the value inferred for it
     */
    private void beginMbaffMacroblock(int addr, int filter, boolean field) {
        var pair = addr >> 1;
        var bottom = addr & 1;
        mbX = pair % mbWidth;
        pairY = pair / mbWidth;
        mbY = 2 * pairY + bottom;
        mbAddr = mbY * mbWidth + mbX;
        pairTop = 2 * pairY * mbWidth + mbX;
        currAddr = addr;
        pic.filter[mbAddr] = filter;
        resetMacroblock();
        setMbaffField(field);
    }

    /**
     * Sets the current MBAFF macroblock's frame or field decoding: its sample geometry, scans,
     * reference view and neighbors (ITU-T H.264 6.4.10 and 6.4.12.2).
     *
     * @param field whether the macroblock is a field macroblock
     */
    private void setMbaffField(boolean field) {
        var bottom = mbY & 1;
        fieldMb = field;
        mbParity = field ? bottom : -1;
        scan4 = field ? H264Tables.FIELD_4X4 : H264Tables.ZIGZAG_4X4;
        scan8 = field ? H264Tables.FIELD_8X8 : H264Tables.ZIGZAG_8X8;
        var frameLuma = frame.lumaStride;
        var frameChroma = frame.chromaStride;
        if (field) {
            lumaStride = 2 * frameLuma;
            chromaStride = 2 * frameChroma;
            lumaBase = frame.lumaOrigin + (32 * pairY + bottom) * frameLuma + mbX * 16;
            chromaBase = frame.chromaOrigin + (2 * chromaRows * pairY + bottom) * frameChroma + mbX * chromaColumns;
            positionInter(mbX * 16, pairY * 16);
        } else {
            lumaStride = frameLuma;
            chromaStride = frameChroma;
            lumaBase = frame.lumaOrigin + mbY * 16 * frameLuma + mbX * 16;
            chromaBase = frame.chromaOrigin + mbY * chromaRows * frameChroma + mbX * chromaColumns;
            positionInter(mbX * 16, mbY * 16);
        }
        var wantedView = field ? 1 + bottom : 0;
        if (!iSlice && wantedView != view) {
            selectView(wantedView);
        }
        computeMbaffNeighbors(field, bottom);
    }

    /**
     * Derives the neighbors of the current MBAFF macroblock from Table 6-4 of ITU-T H.264: the
     * macroblock-level neighbors A, B, C and D, the macroblock and block row of the left neighbor
     * of every 4x4 luma and chroma block row, and the block row of the upper-left neighbor.
     *
     * @param field whether the current macroblock is a field macroblock
     * @param bottom 1 for the bottom macroblock of the pair, 0 for the top one
     */
    private void computeMbaffNeighbors(boolean field, int bottom) {
        var width = mbWidth;
        var top = pairTop;
        var slices = pic.sliceNum;
        var types = frame.mbType;
        var slice = sliceNum;
        var leftTop = top - 1;
        var leftBottom = top - 1 + width;
        var aboveTop = top - 2 * width;
        var aboveBottom = top - width;
        pairAvailA = mbX > 0 && slices[leftTop] == slice;
        var pairAvailB = pairY > 0 && slices[aboveTop] == slice;
        var pairAvailC = pairY > 0 && mbX + 1 < width && slices[aboveTop + 1] == slice;
        var pairAvailD = pairY > 0 && mbX > 0 && slices[aboveTop - 1] == slice;
        var leftField = pairAvailA && (types[leftTop] & DecoderFrame.MB_FIELD) != 0;
        availA = pairAvailA;
        if (!field) {
            if (bottom == 0) {
                mbA = leftTop;
                mbB = aboveBottom;
                availB = pairAvailB;
                mbC = aboveBottom + 1;
                availC = pairAvailC;
                mbD = aboveBottom - 1;
                availD = pairAvailD;
                rowD = 3;
                if (leftField) {
                    setLeftRows(leftTop, 0, leftTop, 0, leftTop, 1, leftTop, 1);
                    setLeftChroma(leftTop, 0, leftTop, 0);
                    setLeftD(leftBottom, 0, leftBottom, 0, leftBottom, 1);
                } else {
                    setLeftRows(leftTop, 0, leftTop, 1, leftTop, 2, leftTop, 3);
                    setLeftChroma(leftTop, 0, leftTop, 1);
                    setLeftD(leftTop, 0, leftTop, 1, leftTop, 2);
                }
            } else {
                mbA = leftField ? leftTop : leftBottom;
                mbB = top;
                availB = true;
                availC = false;
                mbD = leftField ? leftBottom : leftTop;
                availD = pairAvailA;
                rowD = leftField ? 1 : 3;
                if (leftField) {
                    setLeftRows(leftTop, 2, leftTop, 2, leftTop, 3, leftTop, 3);
                    setLeftChroma(leftTop, 1, leftTop, 1);
                    setLeftD(leftBottom, 2, leftBottom, 2, leftBottom, 3);
                } else {
                    setLeftRows(leftBottom, 0, leftBottom, 1, leftBottom, 2, leftBottom, 3);
                    setLeftChroma(leftBottom, 0, leftBottom, 1);
                    setLeftD(leftBottom, 0, leftBottom, 1, leftBottom, 2);
                }
            }
        } else {
            var aboveField = pairAvailB && (types[aboveTop] & DecoderFrame.MB_FIELD) != 0;
            var aboveRightField = pairAvailC && (types[aboveTop + 1] & DecoderFrame.MB_FIELD) != 0;
            var aboveLeftField = pairAvailD && (types[aboveTop - 1] & DecoderFrame.MB_FIELD) != 0;
            availB = pairAvailB;
            availC = pairAvailC;
            availD = pairAvailD;
            rowD = 3;
            if (bottom == 0) {
                mbB = aboveField ? aboveTop : aboveBottom;
                mbC = aboveRightField ? aboveTop + 1 : aboveBottom + 1;
                mbD = aboveLeftField ? aboveTop - 1 : aboveBottom - 1;
            } else {
                mbB = aboveBottom;
                mbC = aboveBottom + 1;
                mbD = aboveBottom - 1;
            }
            if (leftField) {
                var left = bottom == 0 ? leftTop : leftBottom;
                mbA = left;
                setLeftRows(left, 0, left, 1, left, 2, left, 3);
                setLeftChroma(left, 0, left, 1);
                setLeftD(left, 0, left, 1, left, 2);
            } else {
                mbA = leftTop;
                setLeftRows(leftTop, 0, leftTop, 2, leftBottom, 0, leftBottom, 2);
                setLeftChroma(leftTop, 0, leftBottom, 0);
                setLeftD(leftTop, 1, leftTop, 3, leftBottom, 1);
            }
        }
    }

    /**
     * Sets the left neighbors of the four 4x4 luma block rows.
     *
     * @param mb0 the macroblock of row 0
     * @param row0 the block row of row 0 in it
     * @param mb1 the macroblock of row 1
     * @param row1 the block row of row 1 in it
     * @param mb2 the macroblock of row 2
     * @param row2 the block row of row 2 in it
     * @param mb3 the macroblock of row 3
     * @param row3 the block row of row 3 in it
     */
    private void setLeftRows(int mb0, int row0, int mb1, int row1, int mb2, int row2, int mb3, int row3) {
        leftMb[0] = mb0;
        leftRow[0] = row0;
        leftMb[1] = mb1;
        leftRow[1] = row1;
        leftMb[2] = mb2;
        leftRow[2] = row2;
        leftMb[3] = mb3;
        leftRow[3] = row3;
    }

    /**
     * Sets the upper-left neighbors of the left 4x4 blocks of block rows 1 to 3.
     *
     * @param mb1 the macroblock of row 1
     * @param row1 the block row of row 1 in it
     * @param mb2 the macroblock of row 2
     * @param row2 the block row of row 2 in it
     * @param mb3 the macroblock of row 3
     * @param row3 the block row of row 3 in it
     */
    private void setLeftD(int mb1, int row1, int mb2, int row2, int mb3, int row3) {
        leftDMb[1] = mb1;
        leftDRow[1] = row1;
        leftDMb[2] = mb2;
        leftDRow[2] = row2;
        leftDMb[3] = mb3;
        leftDRow[3] = row3;
    }

    /**
     * Sets the left neighbors of the two 4x4 chroma block rows.
     *
     * @param mb0 the macroblock of row 0
     * @param row0 the block row of row 0 in it
     * @param mb1 the macroblock of row 1
     * @param row1 the block row of row 1 in it
     */
    private void setLeftChroma(int mb0, int row0, int mb1, int row1) {
        leftChromaMb[0] = mb0;
        leftChromaRow[0] = row0;
        leftChromaMb[1] = mb1;
        leftChromaRow[1] = row1;
    }

    /**
     * Prepares the level scale factors of the slice's scaling lists.
     */
    private void prepareScaling() {
        if (scaleSource4 == pps.scaling4x4 && scaleSource8 == pps.scaling8x8) {
            return;
        }
        scaleSource4 = pps.scaling4x4;
        scaleSource8 = pps.scaling8x8;
        for (var list = 0; list < 6; list++) {
            var weights = pps.scaling4x4[list];
            for (var m = 0; m < 6; m++) {
                var target = levelScale4[list][m];
                for (var i = 0; i < 16; i++) {
                    target[i] = weights[i] * H264Tables.NORM_ADJUST_4X4[m * 16 + i];
                }
            }
        }
        for (var list = 0; list < 6; list++) {
            var weights = pps.scaling8x8[list];
            for (var m = 0; m < 6; m++) {
                var target = levelScale8[list][m];
                for (var i = 0; i < 64; i++) {
                    target[i] = weights[i] * H264Tables.NORM_ADJUST_8X8[m * 64 + i];
                }
            }
        }
    }

    /**
     * Selects the weighted prediction mode of the slice and computes the implicit weights of
     * every view (ITU-T H.264 8.4.3).
     */
    private void prepareWeights() {
        weightMode = 0;
        if (header.hasWeightTable) {
            weightMode = 1;
            return;
        }
        if (!bSlice || pps.weightedBipredIdc != 2) {
            return;
        }
        weightMode = 2;
        var views = mbaff ? 3 : 1;
        for (var v = 0; v < views; v++) {
            var currentPoc = viewCurrentPoc[v];
            var pocs0 = viewPoc[v][0];
            var pocs1 = viewPoc[v][1];
            var marks0 = viewMarking[v][0];
            var marks1 = viewMarking[v][1];
            var weights = viewImplicit[v];
            var stride = v == 0 ? 32 : 64;
            var count0 = refCount[0] << (v == 0 ? 0 : 1);
            var count1 = refCount[1] << (v == 0 ? 0 : 1);
            for (var i = 0; i < count0; i++) {
                for (var j = 0; j < count1; j++) {
                    var weight1 = 32;
                    var td = Math.clamp(pocs1[j] - pocs0[i], -128, 127);
                    if (td != 0 && marks0[i] != DecoderFrame.LONG_TERM && marks1[j] != DecoderFrame.LONG_TERM) {
                        var tb = Math.clamp(currentPoc - pocs0[i], -128, 127);
                        var tx = (16384 + Math.abs(td / 2)) / td;
                        var scale = Math.clamp((tb * tx + 32) >> 6, -1024, 1023);
                        weight1 = scale >> 2;
                        if (weight1 < -64 || weight1 > 128) {
                            weight1 = 32;
                        }
                    }
                    weights[i * stride + j] = 64 - weight1;
                }
            }
        }
    }

    /**
     * Prepares the direct prediction state of a B slice: the co-located picture and the temporal
     * scale factors of every view (ITU-T H.264 8.4.1.2.3).
     */
    private void prepareDirect() {
        colocated = null;
        if (!bSlice) {
            return;
        }
        colocated = refList[1][0].plane(colorPlane);
        colocatedShortTerm = task.refMarking[1][0] == DecoderFrame.SHORT_TERM;
        if (header.directSpatialMvPred) {
            return;
        }
        var views = mbaff ? 3 : 1;
        for (var v = 0; v < views; v++) {
            var currentPoc = viewCurrentPoc[v];
            var poc1 = viewPoc[v][1][0];
            var scales = viewDirectScale[v];
            var count = refCount[0] << (v == 0 ? 0 : 1);
            for (var i = 0; i < count; i++) {
                var poc0 = viewPoc[v][0][i];
                var td = Math.clamp(poc1 - poc0, -128, 127);
                if (td == 0 || viewMarking[v][0][i] == DecoderFrame.LONG_TERM) {
                    scales[i] = 9999;
                } else {
                    var tb = Math.clamp(currentPoc - poc0, -128, 127);
                    var tx = (16384 + Math.abs(td / 2)) / td;
                    scales[i] = Math.clamp((tb * tx + 32) >> 6, -1024, 1023);
                }
            }
        }
    }

    /**
     * Decodes {@code mb_skip_flag} with CABAC (ITU-T H.264 9.3.3.1.1.1).
     *
     * @return whether the macroblock is skipped
     */
    private boolean decodeSkipFlag() {
        var ctx = 0;
        var types = frame.mbType;
        if (availA && (types[mbA] & DecoderFrame.MB_SKIP) == 0) {
            ctx++;
        }
        if (availB && (types[mbB] & DecoderFrame.MB_SKIP) == 0) {
            ctx++;
        }
        return cabac.decision((bSlice ? 24 : 11) + ctx) != 0;
    }

    /**
     * Decodes a P_Skip or B_Skip macroblock.
     */
    private void decodeSkip() {
        mbFlags = DecoderFrame.MB_SKIP;
        lastQpDeltaNonZero = false;
        if (bSlice) {
            mbFlags |= DecoderFrame.MB_DIRECT16 | (0xF << DecoderFrame.MB_DIRECT8_SHIFT);
            partShape = 3;
            directMask = 0xF;
            fillCornerMotionCache(0);
            fillCornerMotionCache(1);
            deriveDirect();
        } else {
            partShape = 0;
            fillCornerMotionCache(0);
            derivePSkip();
        }
        storeMotion();
        predictInter();
        storeSkipState();
    }

    /**
     * Stores the per-picture syntax state of a skipped macroblock.
     */
    private void storeSkipState() {
        var addr = mbAddr;
        pic.qp[addr] = (byte) qp;
        storeChromaQp();
        clearNnz(addr);
        pic.nonZero[addr] = 0;
        pic.cbp[addr] = 0;
        pic.chromaMode[addr] = 0;
    }

    /**
     * Clears the 48 coefficient counts of a macroblock with six 64-bit stores.
     *
     * @param addr the macroblock address
     */
    private void clearNnz(int addr) {
        var nnz = pic.nnz;
        var at = addr * PictureState.NNZ_STRIDE;
        LONG_LE.set(nnz, at, 0L);
        LONG_LE.set(nnz, at + 8, 0L);
        clearChromaNnz(at);
    }

    /**
     * Clears the chroma coefficient counts of the current macroblock.
     *
     * @param base the index of the macroblock in {@link PictureState#nnz}
     */
    private void clearChromaNnz(int base) {
        var nnz = pic.nnz;
        LONG_LE.set(nnz, base + 16, 0L);
        LONG_LE.set(nnz, base + 24, 0L);
        LONG_LE.set(nnz, base + 32, 0L);
        LONG_LE.set(nnz, base + 40, 0L);
    }

    /**
     * Stores the chroma quantization parameters of the current macroblock for the deblocking
     * filter.
     */
    private void storeChromaQp() {
        pic.qpCb[mbAddr] = (byte) chromaQp(qp, pps.chromaQpIndexOffset, qpBdOffsetC);
        pic.qpCr[mbAddr] = (byte) chromaQp(qp, pps.secondChromaQpIndexOffset, qpBdOffsetC);
    }

    /**
     * Returns {@code QPc} for a luma quantization parameter and chroma offset (ITU-T H.264
     * 8.5.8): {@code qPI = Clip3(-QpBdOffsetC, 51, QPY + offset)} mapped through Table 8-15, which
     * keeps every value below 30.
     *
     * @param lumaQp the luma quantization parameter {@code QPY}, from {@code -QpBdOffsetY} to 51
     * @param offset the chroma quantization parameter offset
     * @param qpBdOffsetC the chroma range offset {@code QpBdOffsetC}
     * @return the chroma quantization parameter {@code QPC}, from {@code -qpBdOffsetC} to 39
     */
    static int chromaQp(int lumaQp, int offset, int qpBdOffsetC) {
        var index = Math.clamp(lumaQp + offset, -qpBdOffsetC, 51);
        return index < 0 ? index : H264Tables.CHROMA_QP[index];
    }

    /**
     * Decodes one macroblock layer (ITU-T H.264 7.3.5).
     */
    private void decodeMacroblock() {
        var type = cabacMode ? decodeMbTypeCabac() : bits.readUe();
        int intraType;
        if (iSlice) {
            intraType = type;
        } else if (bSlice) {
            if (type > 48) {
                throw new MediaException.Malformed("mb_type " + type + " out of range");
            }
            intraType = type - 23;
        } else {
            if (type > 30) {
                throw new MediaException.Malformed("mb_type " + type + " out of range");
            }
            intraType = type - 5;
        }
        if (intraType >= 0) {
            if (intraType > 25) {
                throw new MediaException.Malformed("mb_type " + type + " out of range");
            }
            decodeIntraMacroblock(intraType);
            return;
        }
        decodeInterMacroblock(type);
    }

    /**
     * Resets the stored motion of a list for the current macroblock, for macroblocks that do not
     * use it.
     *
     * @param list the list index
     */
    private void clearList(int list) {
        var rc = refCache[list];
        var mc = mvCache[list];
        for (var i = 0; i < 16; i++) {
            var c = CACHE_OF_RASTER[i];
            rc[c] = -1;
            mc[c] = 0;
        }
    }

    /**
     * Decodes an intra macroblock.
     *
     * @param type the I slice {@code mb_type}, 0 to 25
     */
    private void decodeIntraMacroblock(int type) {
        var addr = mbAddr;
        if (type == 25) {
            decodePcm();
            return;
        }
        var transform8x8 = false;
        if (type == 0) {
            if (pps.transform8x8Mode) {
                transform8x8 = cabacMode ? decodeTransformFlag() : bits.readFlag();
            }
            mbFlags = DecoderFrame.MB_INTRA | (transform8x8 ? DecoderFrame.MB_I8X8 | DecoderFrame.MB_T8X8 : DecoderFrame.MB_I4X4);
            fillModeCache();
            if (transform8x8) {
                for (var b8 = 0; b8 < 4; b8++) {
                    var cache = CACHE_ORIGIN + (b8 & 1) * 2 + (b8 >> 1) * 16;
                    var predicted = predictedMode(cache);
                    var flag = cabacMode ? cabac.decision(68) != 0 : bits.readFlag();
                    int mode;
                    if (flag) {
                        mode = predicted;
                    } else {
                        var rem = cabacMode ? decodeRemMode() : bits.readBits(3);
                        mode = rem < predicted ? rem : rem + 1;
                    }
                    modeCache[cache] = mode;
                    modeCache[cache + 1] = mode;
                    modeCache[cache + 8] = mode;
                    modeCache[cache + 9] = mode;
                }
            } else {
                for (var blk = 0; blk < 16; blk++) {
                    var cache = CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[blk]];
                    var predicted = predictedMode(cache);
                    var flag = cabacMode ? cabac.decision(68) != 0 : bits.readFlag();
                    int mode;
                    if (flag) {
                        mode = predicted;
                    } else {
                        var rem = cabacMode ? decodeRemMode() : bits.readBits(3);
                        mode = rem < predicted ? rem : rem + 1;
                    }
                    modeCache[cache] = mode;
                }
            }
            for (var i = 0; i < 16; i++) {
                predModes[i] = modeCache[CACHE_OF_RASTER[i]];
            }
        } else {
            mbFlags = DecoderFrame.MB_INTRA | DecoderFrame.MB_I16X16;
            intra16Mode = (type - 1) & 3;
            var chromaCbp = chroma444 ? 0 : ((type - 1) >> 2) % 3;
            cbp = (type >= 13 ? 15 : 0) | (chromaCbp << 4);
        }
        if (hasChroma && !chroma444) {
            chromaPredMode = cabacMode ? decodeChromaPredModeCabac() : bits.readUe();
            if (chromaPredMode > 3) {
                throw new MediaException.Malformed("intra_chroma_pred_mode out of range");
            }
        } else {
            chromaPredMode = 0;
        }
        if (type == 0) {
            cbp = decodeCbp(true);
        }
        decodeQpDeltaAndResidual();
        storeIntraState();
        reconstructIntra();
    }

    /**
     * Stores the per-picture syntax state of an intra macroblock.
     */
    private void storeIntraState() {
        var addr = mbAddr;
        var modes = pic.intraModes;
        if ((mbFlags & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0) {
            for (var i = 0; i < 16; i++) {
                modes[addr * 16 + i] = (byte) predModes[i];
            }
        }
        pic.chromaMode[addr] = (byte) chromaPredMode;
        var refs0 = frame.ref[0];
        var refs1 = frame.ref[1];
        var ids0 = frame.refId[0];
        var ids1 = frame.refId[1];
        for (var i = 0; i < 4; i++) {
            refs0[addr * 4 + i] = -1;
            refs1[addr * 4 + i] = -1;
            ids0[addr * 4 + i] = -1;
            ids1[addr * 4 + i] = -1;
        }
        Arrays.fill(frame.mv[0], addr * 16, addr * 16 + 16, 0);
        Arrays.fill(frame.mv[1], addr * 16, addr * 16 + 16, 0);
    }

    /**
     * Fills the intra prediction mode cache from the neighboring macroblocks.
     */
    private void fillModeCache() {
        var cache = modeCache;
        var constrained = pps.constrainedIntraPred;
        var types = frame.mbType;
        var modes = pic.intraModes;
        if (mbaff) {
            for (var y = 0; y < 4; y++) {
                var n = leftMb[y];
                if (availA && (!constrained || (types[n] & DecoderFrame.MB_INTRA) != 0)) {
                    var coded = (types[n] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0;
                    cache[8 + y * 8] = coded ? modes[n * 16 + leftRow[y] * 4 + 3] : 2;
                } else {
                    cache[8 + y * 8] = -1;
                }
            }
        } else if (availA && (!constrained || (types[mbA] & DecoderFrame.MB_INTRA) != 0)) {
            var base = mbA * 16;
            var coded = (types[mbA] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = coded ? modes[base + y * 4 + 3] : 2;
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = -1;
            }
        }
        if (availB && (!constrained || (types[mbB] & DecoderFrame.MB_INTRA) != 0)) {
            var base = mbB * 16 + 12;
            var coded = (types[mbB] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0;
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = coded ? modes[base + x] : 2;
            }
        } else {
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = -1;
            }
        }
    }

    /**
     * Returns {@code predIntraNxNPredMode} of the block at a cache index (ITU-T H.264 8.3.1.1).
     *
     * @param cache the cache index of the block
     * @return the predicted mode
     */
    private int predictedMode(int cache) {
        var a = modeCache[cache - 1];
        var b = modeCache[cache - 8];
        if (a < 0 || b < 0) {
            return 2;
        }
        return Math.min(a, b);
    }

    /**
     * Decodes {@code rem_intra4x4_pred_mode} or {@code rem_intra8x8_pred_mode} with CABAC.
     *
     * @return the value, 0 to 7
     */
    private int decodeRemMode() {
        var value = cabac.decision(69);
        value |= cabac.decision(69) << 1;
        value |= cabac.decision(69) << 2;
        return value;
    }

    /**
     * Decodes an I_PCM macroblock (ITU-T H.264 7.3.5, {@code pcm_sample_luma} and
     * {@code pcm_sample_chroma}).
     */
    private void decodePcm() {
        mbFlags = DecoderFrame.MB_INTRA | DecoderFrame.MB_PCM;
        byte[] data;
        int position;
        if (cabacMode) {
            data = bits.data();
            position = cabac.alignedBytePosition();
        } else {
            bits.alignToByte();
            data = bits.data();
            position = bits.bytePosition();
        }
        if (wide) {
            position = readWidePcm(data, position);
        } else {
            position = readPcm(data, position);
        }
        if (cabacMode) {
            cabac.init(data, position, bits.end());
        } else {
            bits.seekToByte(position);
        }
        var addr = mbAddr;
        pic.qp[addr] = 0;
        pic.qpCb[addr] = (byte) chromaQp(0, pps.chromaQpIndexOffset, qpBdOffsetC);
        pic.qpCr[addr] = (byte) chromaQp(0, pps.secondChromaQpIndexOffset, qpBdOffsetC);
        Arrays.fill(pic.nnz, addr * PictureState.NNZ_STRIDE, (addr + 1) * PictureState.NNZ_STRIDE,
                (byte) (cabacMode ? 1 : 16));
        pic.nonZero[addr] = 0xFFFF;
        pic.cbp[addr] = 0x1FF | 0x2F;
        pic.chromaMode[addr] = 0;
        lastQpDeltaNonZero = false;
        chromaPredMode = 0;
        storeIntraState();
    }

    /**
     * Copies the 8-bit samples of an I_PCM macroblock into the picture.
     *
     * @param data the slice data
     * @param position the index of the first {@code pcm_sample_luma} byte
     * @return the index of the byte after the last sample
     * @throws MediaException.Malformed if the samples run past the slice data
     */
    private int readPcm(byte[] data, int position) {
        var rows = chromaRows;
        var columns = chromaColumns;
        var needed = 256 + (hasChroma ? 2 * columns * rows : 0);
        if (position + needed > bits.end()) {
            throw new MediaException.Malformed("truncated I_PCM macroblock");
        }
        var luma = lumaBase;
        for (var y = 0; y < 16; y++) {
            System.arraycopy(data, position + y * 16, lumaPlane, luma + y * lumaStride, 16);
        }
        position += 256;
        if (hasChroma) {
            var chroma = chromaBase;
            for (var y = 0; y < rows; y++) {
                System.arraycopy(data, position + y * columns, cbPlane, chroma + y * chromaStride, columns);
            }
            position += columns * rows;
            for (var y = 0; y < rows; y++) {
                System.arraycopy(data, position + y * columns, crPlane, chroma + y * chromaStride, columns);
            }
            position += columns * rows;
        }
        return position;
    }

    /**
     * Reads the samples of an I_PCM macroblock of a picture deeper than 8 bits into the picture:
     * {@code BitDepthY} bits per luma sample, then {@code BitDepthC} bits per chroma sample, most
     * significant bit first.
     *
     * @param data the slice data
     * @param position the index of the first {@code pcm_sample_luma} byte
     * @return the index of the byte after the last sample
     * @throws MediaException.Malformed if the samples run past the slice data
     */
    private int readWidePcm(byte[] data, int position) {
        var lumaDepth = frame.bitDepthLuma;
        var chromaDepth = frame.bitDepthChroma;
        var needed = 32 * lumaDepth + (hasChroma ? chromaColumns * chromaRows * chromaDepth / 4 : 0);
        if (position + needed > bits.end()) {
            throw new MediaException.Malformed("truncated I_PCM macroblock");
        }
        pcmPosition = position;
        pcmBits = 0;
        pcmCount = 0;
        readPcmBlock(data, lumaPlane16, lumaBase, lumaStride, 16, 16, lumaDepth);
        if (hasChroma) {
            readPcmBlock(data, cbPlane16, chromaBase, chromaStride, chromaColumns, chromaRows, chromaDepth);
            readPcmBlock(data, crPlane16, chromaBase, chromaStride, chromaColumns, chromaRows, chromaDepth);
        }
        return position + needed;
    }

    /**
     * The index of the next byte of the I_PCM samples being read.
     */
    private int pcmPosition;

    /**
     * The bits read ahead of the I_PCM sample being read, in the low {@link #pcmCount} bits.
     */
    private long pcmBits;

    /**
     * The number of bits read ahead.
     */
    private int pcmCount;

    /**
     * Reads one block of I_PCM samples of a picture deeper than 8 bits.
     *
     * @param data the slice data
     * @param plane the plane receiving the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param width the block width, 16 or 8
     * @param height the block height, 16 or 8
     * @param depth the bits per sample
     */
    private void readPcmBlock(byte[] data, short[] plane, int offset, int stride, int width, int height, int depth) {
        var mask = (1 << depth) - 1;
        var position = pcmPosition;
        var buffer = pcmBits;
        var count = pcmCount;
        for (var y = 0; y < height; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < width; x++) {
                while (count < depth) {
                    buffer = (buffer << 8) | (data[position++] & 0xFF);
                    count += 8;
                }
                count -= depth;
                plane[row + x] = (short) ((int) (buffer >>> count) & mask);
            }
        }
        pcmPosition = position;
        pcmBits = buffer;
        pcmCount = count;
    }

    /**
     * Decodes the coded block pattern.
     *
     * @param intraNxN whether the macroblock is Intra_4x4 or Intra_8x8
     * @return the coded block pattern
     */
    private int decodeCbp(boolean intraNxN) {
        if (cabacMode) {
            return decodeCbpCabac();
        }
        var code = bits.readUe();
        if (hasChroma && !chroma444) {
            if (code > 47) {
                throw new MediaException.Malformed("coded_block_pattern out of range");
            }
            return intraNxN ? H264Tables.CBP_INTRA[code] : H264Tables.CBP_INTER[code];
        }
        if (code > 15) {
            throw new MediaException.Malformed("coded_block_pattern out of range");
        }
        return intraNxN ? H264Tables.CBP_INTRA_MONO[code] : H264Tables.CBP_INTER_MONO[code];
    }

    /**
     * Decodes {@code mb_qp_delta} when present and the residual of the current macroblock.
     */
    private void decodeQpDeltaAndResidual() {
        var intra16 = (mbFlags & DecoderFrame.MB_I16X16) != 0;
        if (cbp == 0 && !intra16) {
            lastQpDeltaNonZero = false;
            bypass = transformBypass && qp == -qpBdOffsetY;
            pic.qp[mbAddr] = (byte) qp;
            storeChromaQp();
            clearResidualState();
            return;
        }
        var delta = cabacMode ? decodeQpDeltaCabac() : bits.readSe();
        var offset = qpBdOffsetY;
        if (delta < -(26 + offset / 2) || delta > 25 + offset / 2) {
            throw new MediaException.Malformed("mb_qp_delta " + delta + " out of range");
        }
        lastQpDeltaNonZero = delta != 0;
        qp = (qp + delta + 52 + 2 * offset) % (52 + offset) - offset;
        bypass = transformBypass && qp == -offset;
        pic.qp[mbAddr] = (byte) qp;
        storeChromaQp();
        if (cabacMode) {
            residualCabac();
        } else {
            residualCavlc();
        }
    }

    /**
     * Clears the coefficient state of a macroblock without residual.
     */
    private void clearResidualState() {
        var addr = mbAddr;
        clearNnz(addr);
        pic.nonZero[addr] = 0;
        pic.cbp[addr] = cbp;
    }

    /**
     * Returns the 4x4 scaling list index of a color component of the current macroblock:
     * {@code iYCbCr + (inter ? 3 : 0)} (ITU-T H.264 8.5.9).
     *
     * @param comp the component coded like luma: 0 luma, 1 Cb, 2 Cr
     * @return the index into {@link #levelScale4}
     */
    private int list4(int comp) {
        return ((mbFlags & DecoderFrame.MB_INTRA) != 0 ? 0 : 3) + comp + colorPlane;
    }

    /**
     * Returns the 8x8 scaling list index of a color component of the current macroblock:
     * {@code 2 * iYCbCr + (inter ? 1 : 0)} (ITU-T H.264 8.5.9).
     *
     * @param comp the component coded like luma: 0 luma, 1 Cb, 2 Cr
     * @return the index into {@link #levelScale8}
     */
    private int list8(int comp) {
        return 2 * (comp + colorPlane) + ((mbFlags & DecoderFrame.MB_INTRA) != 0 ? 0 : 1);
    }

    /**
     * Returns the quantization parameter of the residual of a color component of the current
     * macroblock: {@code QP'Y} for luma, {@code QP'C} for the Cb and Cr of 4:4:4.
     *
     * @param comp the component coded like luma: 0 luma, 1 Cb, 2 Cr
     * @return the quantization parameter, from 0
     */
    private int componentQp(int comp) {
        return switch (comp) {
            case 0 -> qp + qpBdOffsetY;
            case 1 -> pic.qpCb[mbAddr] + qpBdOffsetC;
            default -> pic.qpCr[mbAddr] + qpBdOffsetC;
        };
    }

    /**
     * Returns the shift that follows scaling for a quantization parameter: non-negative for a
     * left shift, negative for a rounding right shift.
     *
     * @param qpDiv6 {@code qP / 6}
     * @param base 4 for 4x4 blocks, 6 for 8x8 blocks
     * @return the shift
     */
    private static int scaleShift(int qpDiv6, int base) {
        return qpDiv6 - base;
    }

    /**
     * Returns the scale of transform-bypass coefficient levels.
     *
     * @return 64 ones
     */
    private static int[] unitScale() {
        var scale = new int[64];
        Arrays.fill(scale, 1);
        return scale;
    }

    /**
     * Parses the residual of the current macroblock with CAVLC (ITU-T H.264 7.3.5.3).
     */
    private void residualCavlc() {
        var addr = mbAddr;
        var base = addr * PictureState.NNZ_STRIDE;
        pic.nonZero[addr] = residualPlaneCavlc(0, base);
        if (chroma444) {
            residualPlaneCavlc(1, base);
            residualPlaneCavlc(2, base);
        } else if (hasChroma) {
            residualChromaCavlc(base);
        } else {
            clearChromaNnz(base);
        }
        pic.cbp[addr] = cbp;
    }

    /**
     * Parses the residual of one color component coded like luma with CAVLC
     * ({@code residual_luma}, ITU-T H.264 7.3.5.3.1): luma, or the Cb or Cr of a 4:4:4
     * macroblock.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param base the index of the macroblock in {@link PictureState#nnz}
     * @return the 4x4 blocks of the component with coefficients, one bit per raster index
     */
    private int residualPlaneCavlc(int comp, int base) {
        var nnz = pic.nnz;
        fillNnzCacheCavlc(comp);
        var intra16 = (mbFlags & DecoderFrame.MB_I16X16) != 0;
        var transform8x8 = (mbFlags & DecoderFrame.MB_T8X8) != 0;
        var qpPrime = componentQp(comp);
        var qpDiv = qpPrime / 6;
        var qpMod = qpPrime % 6;
        var lossless = bypass;
        var dequant4 = lossless ? UNIT_SCALE : levelScale4[list4(comp)][qpMod];
        var shift4 = lossless ? 0 : scaleShift(qpDiv, 4);
        var coeffs = planeCoeffs[comp];
        var cache = nnzCache;
        if (intra16) {
            var nC = lumaNc(CACHE_ORIGIN);
            var count = cavlc.residualBlock(bits, nC, 16, planeDc[comp], 0, scan4, 0, 1, 0, null, 0);
            if (count > 0) {
                transformPlaneDc(comp, dequant4[0], qpDiv);
            }
        }
        var nonZero = 0;
        var codedBlocks = 0;
        var dcBlocks = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbp & (1 << b8)) == 0) {
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[blk]]] = 0;
                }
                continue;
            }
            if (transform8x8) {
                var dequant8 = lossless ? UNIT_SCALE : levelScale8[list8(comp)][qpMod];
                var shift8 = lossless ? 0 : scaleShift(qpDiv, 6);
                var total = 0;
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    var c = CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[blk]];
                    var nC = lumaNc(c);
                    var count = cavlc.residualBlock(bits, nC, 16, coeffs, b8 * 64, scan8,
                            0, 4, i, dequant8, shift8);
                    cache[c] = count;
                    total += count;
                }
                if (total != 0) {
                    if (total == 1 && coeffs[b8 * 64] != 0) {
                        dcBlocks |= 1 << b8;
                    } else {
                        codedBlocks |= 1 << b8;
                    }
                    nonZero |= 0x33 << ((b8 & 1) * 2 + (b8 >> 1) * 8);
                }
            } else {
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    var raster = H264Tables.BLOCK_RASTER[blk];
                    var c = CACHE_OF_RASTER[raster];
                    var nC = lumaNc(c);
                    int count;
                    if (intra16) {
                        count = cavlc.residualBlock(bits, nC, 15, coeffs, blk * 16, scan4,
                                1, 1, 0, dequant4, shift4);
                    } else {
                        count = cavlc.residualBlock(bits, nC, 16, coeffs, blk * 16, scan4,
                                0, 1, 0, dequant4, shift4);
                    }
                    cache[c] = count;
                    if (count != 0) {
                        if (count == 1 && !intra16 && coeffs[blk * 16] != 0) {
                            dcBlocks |= 1 << blk;
                        } else {
                            codedBlocks |= 1 << blk;
                        }
                        nonZero |= 1 << raster;
                    }
                }
            }
        }
        coded[comp] |= codedBlocks;
        dcOnly[comp] |= dcBlocks;
        var at = base + 16 * comp;
        for (var i = 0; i < 16; i++) {
            nnz[at + i] = (byte) cache[CACHE_OF_RASTER[i]];
        }
        return nonZero;
    }

    /**
     * Parses the chroma residual with CAVLC.
     *
     * @param base the index of the macroblock in {@link PictureState#nnz}
     */
    private void residualChromaCavlc(int base) {
        var nnz = pic.nnz;
        var chroma = cbp >> 4;
        if (chroma == 0) {
            clearChromaNnz(base);
            return;
        }
        chromaResidual = true;
        var dcCount = chroma422 ? 8 : 4;
        var dcNc = chroma422 ? -2 : -1;
        for (var c = 0; c < 2; c++) {
            var count = cavlc.residualBlock(bits, dcNc, dcCount, chromaDc, c * 8, H264Tables.CHROMA_DC_SCAN, 0, 1, 0,
                    null, 0);
            if (count != 0) {
                chromaDcCoded |= 1 << c;
            }
        }
        if ((chroma & 2) != 0) {
            var intra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
            var blocks = chromaRows >> 1;
            for (var c = 0; c < 2; c++) {
                var list = (intra ? 1 : 4) + c;
                var chromaQpValue = (c == 0 ? pic.qpCb[mbAddr] : pic.qpCr[mbAddr]) + qpBdOffsetC;
                var dequant = bypass ? UNIT_SCALE : levelScale4[list][chromaQpValue % 6];
                var shift = bypass ? 0 : scaleShift(chromaQpValue / 6, 4);
                var coeffs = c == 0 ? cbCoeffs : crCoeffs;
                for (var blk = 0; blk < blocks; blk++) {
                    var nC = chromaNc(c, blk);
                    var count = cavlc.residualBlock(bits, nC, 15, coeffs, blk * 16, scan4,
                            1, 1, 0, dequant, shift);
                    nnz[base + 16 + c * 8 + blk] = (byte) count;
                    if (count != 0) {
                        chromaCoded |= 1 << (c * 8 + blk);
                    }
                }
            }
        } else {
            clearChromaNnz(base);
        }
    }


    /**
     * Fills the coefficient count cache of a color component coded like luma from the
     * neighboring macroblocks for CAVLC {@code nC} derivation; unavailable neighbors are marked
     * {@code -1}.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     */
    private void fillNnzCacheCavlc(int comp) {
        var cache = nnzCache;
        var nnz = pic.nnz;
        var offset = 16 * comp;
        if (availA && mbaff) {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[leftMb[y] * PictureState.NNZ_STRIDE + offset + leftRow[y] * 4 + 3];
            }
        } else if (availA) {
            var base = mbA * PictureState.NNZ_STRIDE + offset;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[base + y * 4 + 3];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = -1;
            }
        }
        if (availB) {
            var base = mbB * PictureState.NNZ_STRIDE + offset + 12;
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = nnz[base + x];
            }
        } else {
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = -1;
            }
        }
    }

    /**
     * Returns the CAVLC {@code nC} of the luma block at a cache index (ITU-T H.264 9.2.1).
     *
     * @param cache the cache index of the block
     * @return the {@code nC}
     */
    private int lumaNc(int cache) {
        var a = nnzCache[cache - 1];
        var b = nnzCache[cache - 8];
        if (a >= 0 && b >= 0) {
            return (a + b + 1) >> 1;
        }
        if (a >= 0) {
            return a;
        }
        return Math.max(b, 0);
    }

    /**
     * Returns the CAVLC {@code nC} of a chroma AC block.
     *
     * @param component 0 for Cb, 1 for Cr
     * @param blk the {@code chroma4x4BlkIdx}, 0 to 3 for 4:2:0 and 0 to 7 for 4:2:2
     * @return the {@code nC}
     */
    private int chromaNc(int component, int blk) {
        var nnz = pic.nnz;
        var base = mbAddr * PictureState.NNZ_STRIDE + 16 + component * 8;
        var x = blk & 1;
        var y = blk >> 1;
        var a = -1;
        var b = -1;
        if (x > 0) {
            a = nnz[base + blk - 1];
        } else if (availA) {
            a = nnz[leftChromaIndex(component, y)];
        }
        if (y > 0) {
            b = nnz[base + blk - 2];
        } else if (availB) {
            b = nnz[topChromaIndex(component, x)];
        }
        if (a >= 0 && b >= 0) {
            return (a + b + 1) >> 1;
        }
        if (a >= 0) {
            return a;
        }
        return Math.max(b, 0);
    }

    /**
     * Returns the index in {@link PictureState#nnz} of the left neighbor of a chroma 4x4 block
     * in the left column of the current macroblock.
     *
     * <p>A 4:2:2 macroblock has as many chroma as luma rows, so its chroma block rows take their
     * left neighbors from the macroblocks and block rows of the luma block rows (ITU-T H.264
     * 6.4.12).
     *
     * @param component 0 for Cb, 1 for Cr
     * @param y the block row, 0 or 1 for 4:2:0 and 0 to 3 for 4:2:2
     * @return the index of the neighbor's coefficient count or coded block flag
     */
    private int leftChromaIndex(int component, int y) {
        if (mbaff) {
            if (chroma422) {
                return leftMb[y] * PictureState.NNZ_STRIDE + 16 + component * 8 + leftRow[y] * 2 + 1;
            }
            return leftChromaMb[y] * PictureState.NNZ_STRIDE + 16 + component * 8 + leftChromaRow[y] * 2 + 1;
        }
        return mbA * PictureState.NNZ_STRIDE + 16 + component * 8 + y * 2 + 1;
    }

    /**
     * Returns the index in {@link PictureState#nnz} of the neighbor above a chroma 4x4 block in
     * the top row of the current macroblock: the block of the same column in the bottom block
     * row of {@link #mbB}.
     *
     * @param component 0 for Cb, 1 for Cr
     * @param x the block column, 0 or 1
     * @return the index of the neighbor's coefficient count or coded block flag
     */
    private int topChromaIndex(int component, int x) {
        return mbB * PictureState.NNZ_STRIDE + 16 + component * 8 + (chromaRows >> 1) - 2 + x;
    }

    /**
     * Applies the inverse Intra16x16 DC Hadamard transform and scaling of a color component
     * coded like luma, distributing the DC values to its sixteen 4x4 blocks (ITU-T H.264 8.5.10);
     * a transform-bypass macroblock takes the levels as the DC values.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param levelScale the level scale factor {@code LevelScale4x4(qP % 6, 0, 0)}
     * @param qpDiv {@code qP / 6}
     */
    private void transformPlaneDc(int comp, int levelScale, int qpDiv) {
        var c = planeDc[comp];
        var coeffs = planeCoeffs[comp];
        var raster = dcScratch;
        var scan = scan4;
        for (var i = 0; i < 16; i++) {
            raster[scan[i]] = c[i];
            c[i] = 0;
        }
        if (bypass) {
            var blocks = 0;
            for (var i = 0; i < 16; i++) {
                var dc = raster[i];
                if (dc != 0) {
                    var blk = blockIndexOf(i & 3, i >> 2);
                    coeffs[blk * 16] = dc;
                    blocks |= 1 << blk;
                }
            }
            dcOnly[comp] |= blocks;
            return;
        }
        for (var i = 0; i < 4; i++) {
            var r = i * 4;
            var s0 = raster[r] + raster[r + 1];
            var s1 = raster[r] - raster[r + 1];
            var s2 = raster[r + 2] + raster[r + 3];
            var s3 = raster[r + 2] - raster[r + 3];
            raster[r] = s0 + s2;
            raster[r + 1] = s0 - s2;
            raster[r + 2] = s1 - s3;
            raster[r + 3] = s1 + s3;
        }
        for (var j = 0; j < 4; j++) {
            var s0 = raster[j] + raster[j + 4];
            var s1 = raster[j] - raster[j + 4];
            var s2 = raster[j + 8] + raster[j + 12];
            var s3 = raster[j + 8] - raster[j + 12];
            raster[j] = s0 + s2;
            raster[j + 4] = s0 - s2;
            raster[j + 8] = s1 - s3;
            raster[j + 12] = s1 + s3;
        }
        var blocks = 0;
        for (var i = 0; i < 16; i++) {
            var f = raster[i];
            int dc;
            if (qpDiv >= 6) {
                dc = (f * levelScale) << (qpDiv - 6);
            } else {
                dc = (f * levelScale + (1 << (5 - qpDiv))) >> (6 - qpDiv);
            }
            if (dc != 0) {
                var x = i & 3;
                var y = i >> 2;
                var blk = blockIndexOf(x, y);
                coeffs[blk * 16] = dc;
                blocks |= 1 << blk;
            }
        }
        dcOnly[comp] |= blocks;
    }

    /**
     * The scratch buffer of the Intra16x16 DC transform.
     */
    private final int[] dcScratch = new int[16];

    /**
     * Returns the {@code luma4x4BlkIdx} of the 4x4 block at a position.
     *
     * @param x the column in 4x4 units
     * @param y the row in 4x4 units
     * @return the block index
     */
    private static int blockIndexOf(int x, int y) {
        return ((y >> 1) << 3) | ((x >> 1) << 2) | ((y & 1) << 1) | (x & 1);
    }

    /**
     * Applies the inverse chroma DC transform and scaling of one component (ITU-T H.264
     * 8.5.11.1 and 8.5.11.2).
     *
     * @param component 0 for Cb, 1 for Cr
     */
    private void transformChromaDc(int component) {
        if (chroma422) {
            transformChromaDc422(component);
            return;
        }
        var o = component * 8;
        var c0 = chromaDc[o];
        var c1 = chromaDc[o + 1];
        var c2 = chromaDc[o + 2];
        var c3 = chromaDc[o + 3];
        chromaDc[o] = 0;
        chromaDc[o + 1] = 0;
        chromaDc[o + 2] = 0;
        chromaDc[o + 3] = 0;
        var f0 = c0 + c1 + c2 + c3;
        var f1 = c0 - c1 + c2 - c3;
        var f2 = c0 + c1 - c2 - c3;
        var f3 = c0 - c1 - c2 + c3;
        var chromaQpValue = (component == 0 ? pic.qpCb[mbAddr] : pic.qpCr[mbAddr]) + qpBdOffsetC;
        var intra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
        var list = (intra ? 1 : 4) + component;
        var scale = levelScale4[list][chromaQpValue % 6][0];
        var shift = chromaQpValue / 6;
        var coeffs = component == 0 ? cbCoeffs : crCoeffs;
        coeffs[0] = ((f0 * scale) << shift) >> 5;
        coeffs[16] = ((f1 * scale) << shift) >> 5;
        coeffs[32] = ((f2 * scale) << shift) >> 5;
        coeffs[48] = ((f3 * scale) << shift) >> 5;
    }

    /**
     * Applies the inverse 2x4 chroma DC transform and scaling of one 4:2:2 component (ITU-T
     * H.264 8.5.11.1 and 8.5.11.2), distributing the scaled values to the DC coefficients of the
     * eight 4x4 blocks.
     *
     * <p>The levels, in list order {@code d0} to {@code d7}, form the 4x2 matrix
     * {@code c = {{d0, d2}, {d1, d5}, {d3, d6}, {d4, d7}}} (equation 8-305); its transform
     * {@code f} scales with {@code QP'c + 3} and row {@code i}, column {@code j} becomes the DC of
     * block {@code 2 * i + j}.
     *
     * @param component 0 for Cb, 1 for Cr
     */
    private void transformChromaDc422(int component) {
        var dc = chromaDc;
        var o = component * 8;
        var c00 = dc[o];
        var c10 = dc[o + 1];
        var c01 = dc[o + 2];
        var c20 = dc[o + 3];
        var c30 = dc[o + 4];
        var c11 = dc[o + 5];
        var c21 = dc[o + 6];
        var c31 = dc[o + 7];
        Arrays.fill(dc, o, o + 8, 0);
        var a0 = c00 + c10 + c20 + c30;
        var a1 = c00 + c10 - c20 - c30;
        var a2 = c00 - c10 - c20 + c30;
        var a3 = c00 - c10 + c20 - c30;
        var b0 = c01 + c11 + c21 + c31;
        var b1 = c01 + c11 - c21 - c31;
        var b2 = c01 - c11 - c21 + c31;
        var b3 = c01 - c11 + c21 - c31;
        var qpDc = (component == 0 ? pic.qpCb[mbAddr] : pic.qpCr[mbAddr]) + qpBdOffsetC + 3;
        var intra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
        var scale = levelScale4[(intra ? 1 : 4) + component][qpDc % 6][0];
        var qpDiv = qpDc / 6;
        var coeffs = component == 0 ? cbCoeffs : crCoeffs;
        coeffs[0] = scaleChromaDc422(a0 + b0, scale, qpDiv);
        coeffs[16] = scaleChromaDc422(a0 - b0, scale, qpDiv);
        coeffs[32] = scaleChromaDc422(a1 + b1, scale, qpDiv);
        coeffs[48] = scaleChromaDc422(a1 - b1, scale, qpDiv);
        coeffs[64] = scaleChromaDc422(a2 + b2, scale, qpDiv);
        coeffs[80] = scaleChromaDc422(a2 - b2, scale, qpDiv);
        coeffs[96] = scaleChromaDc422(a3 + b3, scale, qpDiv);
        coeffs[112] = scaleChromaDc422(a3 - b3, scale, qpDiv);
    }

    /**
     * Scales one 4:2:2 chroma DC value (ITU-T H.264 equation 8-328 and 8-329).
     *
     * @param f the transformed DC value
     * @param scale {@code LevelScale4x4(qPDC % 6, 0, 0)}
     * @param qpDiv {@code qPDC / 6}
     * @return the scaled DC coefficient
     */
    private static int scaleChromaDc422(int f, int scale, int qpDiv) {
        if (qpDiv >= 6) {
            return (f * scale) << (qpDiv - 6);
        }
        return (f * scale + (1 << (5 - qpDiv))) >> (6 - qpDiv);
    }

    /**
     * Decodes an inter macroblock of a P or B slice.
     *
     * @param type the P or B {@code mb_type}
     */
    private void decodeInterMacroblock(int type) {
        var whole = bSlice ? type <= 3 : type == 0;
        if (whole) {
            fillCornerMotionCache(0);
            if (bSlice) {
                fillCornerMotionCache(1);
            }
        } else {
            fillMotionCache(0);
            if (bSlice) {
                fillMotionCache(1);
            }
        }
        if (cabacMode) {
            fillCabacMotionCaches(whole);
        }
        if (bSlice) {
            if (type == 0) {
                mbFlags = DecoderFrame.MB_DIRECT16 | (0xF << DecoderFrame.MB_DIRECT8_SHIFT);
                partShape = 3;
                directMask = 0xF;
                deriveDirect();
                if (partShape == 0) {
                    mvdCache[0][CACHE_ORIGIN] = 0;
                    mvdCache[1][CACHE_ORIGIN] = 0;
                } else {
                    clearMvd(0);
                    clearMvd(1);
                }
            } else {
                partShape = B_SHAPE[type];
                partPred[0] = B_PRED0[type];
                partPred[1] = B_PRED1[type];
                if (partShape == 3) {
                    decodeSubMbPred(true);
                } else {
                    decodeMbPred();
                }
            }
        } else {
            switch (type) {
                case 0 -> {
                    partShape = 0;
                    partPred[0] = 1;
                }
                case 1 -> {
                    partShape = 1;
                    partPred[0] = 1;
                    partPred[1] = 1;
                }
                case 2 -> {
                    partShape = 2;
                    partPred[0] = 1;
                    partPred[1] = 1;
                }
                default -> partShape = 3;
            }
            if (partShape == 3) {
                decodeSubMbPred(type == 4);
            } else {
                decodeMbPred();
            }
        }
        cbp = decodeCbp(false);
        if ((cbp & 15) != 0 && pps.transform8x8Mode && transformAllowed()) {
            if (cabacMode ? decodeTransformFlag() : bits.readFlag()) {
                mbFlags |= DecoderFrame.MB_T8X8;
            }
        }
        storeMotion();
        decodeQpDeltaAndResidual();
        pic.chromaMode[mbAddr] = 0;
        predictInter();
        reconstructInterResidual();
    }

    /**
     * Returns whether {@code transform_size_8x8_flag} may follow the coded block pattern of the
     * current inter macroblock.
     *
     * @return {@code true} when no partition is smaller than 8x8
     */
    private boolean transformAllowed() {
        if ((mbFlags & DecoderFrame.MB_DIRECT16) != 0) {
            return header.sps.direct8x8Inference;
        }
        if (partShape != 3) {
            return true;
        }
        for (var i = 0; i < 4; i++) {
            if ((directMask & (1 << i)) != 0) {
                if (!header.sps.direct8x8Inference) {
                    return false;
                }
            } else if (subShape[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resets the motion vector differences of a list for the current macroblock.
     *
     * @param list the list index
     */
    private void clearMvd(int list) {
        Arrays.fill(mvd[list], 0);
        if (cabacMode) {
            var mc = mvdCache[list];
            for (var i = 0; i < 16; i++) {
                mc[CACHE_OF_RASTER[i]] = 0;
            }
        }
    }

    /**
     * Parses {@code mb_pred()} of an inter macroblock with one or two partitions and derives its
     * motion vectors.
     */
    private void decodeMbPred() {
        if (partShape == 0) {
            decodeMbPred16x16();
            return;
        }
        var parts = 2;
        for (var list = 0; list < 2; list++) {
            for (var p = 0; p < parts; p++) {
                var r = -1;
                if ((partPred[p] & (1 << list)) != 0) {
                    r = decodeRefIdx(list, partitionCache(p));
                }
                setPartitionRef(list, p, r);
            }
        }
        for (var list = 0; list < 2; list++) {
            var covering = 0;
            for (var p = 0; p < parts; p++) {
                covering += (partPred[p] >> list) & 1;
            }
            // Partitions that all use the list write every difference entry themselves, and P
            // slices never read the list 1 differences.
            if (covering < parts && (bSlice || list == 0)) {
                clearMvd(list);
            }
            for (var p = 0; p < parts; p++) {
                if ((partPred[p] & (1 << list)) != 0) {
                    var c = partitionCache(p);
                    var value = decodeMvd(list, c);
                    setPartitionMvd(list, p, value);
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var rc = refCache[list];
            var mc = mvCache[list];
            for (var i = 0; i < 16; i++) {
                rc[CACHE_OF_RASTER[i]] = UNAVAILABLE;
                mc[CACHE_OF_RASTER[i]] = 0;
            }
            for (var p = 0; p < parts; p++) {
                var r = partitionRef(list, p);
                var c = partitionCache(p);
                int mv;
                if (r < 0) {
                    mv = 0;
                } else {
                    mv = addMv(predictPartitionMv(list, p, r), mvd[list][rasterOfCache(c)]);
                }
                fillPartition(list, p, r, mv);
            }
        }
    }

    /**
     * Parses {@code mb_pred()} of an inter macroblock predicted as one 16x16 partition and
     * derives its motion.
     *
     * <p>Only the cache entries of the macroblock's top-left block are written: with one
     * partition nothing inside the macroblock is predicted from them, and readers of such a
     * macroblock's motion ({@link #storeMotion()} and the inter predictor) read that entry
     * alone.
     */
    private void decodeMbPred16x16() {
        var pred = partPred[0];
        var ref0 = (pred & 1) != 0 ? decodeRefIdx(0, CACHE_ORIGIN) : -1;
        var ref1 = (pred & 2) != 0 ? decodeRefIdx(1, CACHE_ORIGIN) : -1;
        var mvd0 = ref0 >= 0 ? decodeMvd(0, CACHE_ORIGIN) : 0;
        var mvd1 = ref1 >= 0 ? decodeMvd(1, CACHE_ORIGIN) : 0;
        setWholeMotion(0, ref0, mvd0);
        if (bSlice) {
            setWholeMotion(1, ref1, mvd1);
        }
    }

    /**
     * Derives and stores the motion of one list of a macroblock predicted as one 16x16
     * partition into the top-left cache entries.
     *
     * @param list the list index
     * @param r the reference index, or -1 when the list is unused
     * @param difference the packed motion vector difference, 0 when the list is unused
     */
    private void setWholeMotion(int list, int r, int difference) {
        refCache[list][CACHE_ORIGIN] = r;
        mvCache[list][CACHE_ORIGIN] = r < 0 ? 0 : addMv(predictMv(list, CACHE_ORIGIN, 4, r), difference);
        if (cabacMode) {
            var ax = Math.min(Math.abs((short) difference), 127);
            var ay = Math.min(Math.abs(difference >> 16), 127);
            mvdCache[list][CACHE_ORIGIN] = (ay << 8) | ax;
        }
    }

    /**
     * Returns the cache index of the top-left block of a macroblock partition.
     *
     * @param p the partition index
     * @return the cache index
     */
    private int partitionCache(int p) {
        return switch (partShape) {
            case 1 -> CACHE_ORIGIN + p * 16;
            case 2 -> CACHE_ORIGIN + p * 2;
            default -> CACHE_ORIGIN;
        };
    }

    /**
     * Returns the raster 4x4 index of a cache index inside the current macroblock.
     *
     * @param cache the cache index
     * @return the raster index
     */
    private static int rasterOfCache(int cache) {
        var offset = cache - CACHE_ORIGIN;
        return (offset >> 3) * 4 + (offset & 7);
    }

    /**
     * Records the reference index of a partition for all its 8x8 quadrants and, under CABAC,
     * the {@code ref_idx} context condition of its blocks.
     *
     * @param list the list index
     * @param p the partition index
     * @param r the reference index, {@code -1} when the list is unused
     */
    private void setPartitionRef(int list, int p, int r) {
        var refs = refIdx[list];
        switch (partShape) {
            case 0 -> {
                refs[0] = r;
                refs[1] = r;
                refs[2] = r;
                refs[3] = r;
            }
            case 1 -> {
                refs[p * 2] = r;
                refs[p * 2 + 1] = r;
            }
            default -> {
                refs[p] = r;
                refs[p + 2] = r;
            }
        }
        if (cabacMode) {
            var flag = r > 0 ? 1 : 0;
            var ctx = refCtxCache[list];
            var c = partitionCache(p);
            var w = partShape == 1 || partShape == 0 ? 4 : 2;
            var h = partShape == 2 || partShape == 0 ? 4 : 2;
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    ctx[c + y * 8 + x] = flag;
                }
            }
        }
    }

    /**
     * Returns the reference index of a partition.
     *
     * @param list the list index
     * @param p the partition index
     * @return the reference index
     */
    private int partitionRef(int list, int p) {
        return switch (partShape) {
            case 1 -> refIdx[list][p * 2];
            case 2 -> refIdx[list][p];
            default -> refIdx[list][0];
        };
    }

    /**
     * Records the motion vector difference of a partition for all its 4x4 blocks.
     *
     * @param list the list index
     * @param p the partition index
     * @param value the packed difference
     */
    private void setPartitionMvd(int list, int p, int value) {
        var c = partitionCache(p);
        var w = partShape == 1 || partShape == 0 ? 4 : 2;
        var h = partShape == 2 || partShape == 0 ? 4 : 2;
        fillMvd(list, c, w, h, value);
    }

    /**
     * Records a motion vector difference over a block rectangle and its CABAC context values.
     *
     * @param list the list index
     * @param c the cache index of the top-left block
     * @param w the width in 4x4 blocks
     * @param h the height in 4x4 blocks
     * @param value the packed difference
     */
    private void fillMvd(int list, int c, int w, int h, int value) {
        var raster = rasterOfCache(c);
        var target = mvd[list];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                target[raster + y * 4 + x] = value;
            }
        }
        if (cabacMode) {
            var ax = Math.min(Math.abs((short) value), 127);
            var ay = Math.min(Math.abs(value >> 16), 127);
            var packed = (ay << 8) | ax;
            var cache = mvdCache[list];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    cache[c + y * 8 + x] = packed;
                }
            }
        }
    }

    /**
     * Predicts the motion vector of a macroblock partition, applying the directional rules of
     * 16x8 and 8x16 partitions (ITU-T H.264 8.4.1.3).
     *
     * @param list the list index
     * @param p the partition index
     * @param r the reference index
     * @return the packed motion vector predictor
     */
    private int predictPartitionMv(int list, int p, int r) {
        var rc = refCache[list];
        var mc = mvCache[list];
        switch (partShape) {
            case 1 -> {
                if (p == 0) {
                    if (rc[CACHE_ORIGIN - 8] == r) {
                        return mc[CACHE_ORIGIN - 8];
                    }
                } else if (rc[CACHE_ORIGIN + 16 - 1] == r) {
                    return mc[CACHE_ORIGIN + 16 - 1];
                }
                return predictMv(list, partitionCache(p), 4, r);
            }
            case 2 -> {
                if (p == 0) {
                    if (rc[CACHE_ORIGIN - 1] == r) {
                        return mc[CACHE_ORIGIN - 1];
                    }
                } else {
                    var c = CACHE_ORIGIN + 2;
                    var cIdx = c - 8 + 2;
                    if (rc[cIdx] == UNAVAILABLE) {
                        cIdx = c - 9;
                    }
                    if (rc[cIdx] == r) {
                        return mc[cIdx];
                    }
                }
                return predictMv(list, partitionCache(p), 2, r);
            }
            default -> {
                return predictMv(list, CACHE_ORIGIN, 4, r);
            }
        }
    }

    /**
     * Returns the median motion vector predictor of a block (ITU-T H.264 8.4.1.3.1).
     *
     * @param list the list index
     * @param c the cache index of the block's top-left 4x4 block
     * @param width the block width in 4x4 units
     * @param r the reference index
     * @return the packed predictor
     */
    private int predictMv(int list, int c, int width, int r) {
        var rc = refCache[list];
        var mc = mvCache[list];
        var refA = rc[c - 1];
        var refB = rc[c - 8];
        var cIdx = c - 8 + width;
        var refC = rc[cIdx];
        int mvC;
        if (refC != UNAVAILABLE) {
            mvC = mc[cIdx];
        } else {
            cIdx = c - 9;
            if (mbaff && (cIdx & 7) == 0 && cIdx != 0) {
                refC = leftDRef[list][cIdx >> 3];
                mvC = leftDMv[list][cIdx >> 3];
            } else {
                refC = rc[cIdx];
                mvC = mc[cIdx];
            }
        }
        var mvA = mc[c - 1];
        if (refB == UNAVAILABLE && refC == UNAVAILABLE && refA != UNAVAILABLE) {
            return mvA;
        }
        var mvB = mc[c - 8];
        var match = (refA == r ? 1 : 0) | (refB == r ? 2 : 0) | (refC == r ? 4 : 0);
        return switch (match) {
            case 1 -> mvA;
            case 2 -> mvB;
            case 4 -> mvC;
            default -> median(mvA, mvB, mvC);
        };
    }

    /**
     * Returns the component-wise median of three packed motion vectors.
     *
     * @param a the first vector
     * @param b the second vector
     * @param c the third vector
     * @return the packed median
     */
    private static int median(int a, int b, int c) {
        int ax = (short) a;
        int bx = (short) b;
        int cx = (short) c;
        var ay = a >> 16;
        var by = b >> 16;
        var cy = c >> 16;
        var x = ax + bx + cx - Math.min(ax, Math.min(bx, cx)) - Math.max(ax, Math.max(bx, cx));
        var y = ay + by + cy - Math.min(ay, Math.min(by, cy)) - Math.max(ay, Math.max(by, cy));
        return (y << 16) | (x & 0xFFFF);
    }

    /**
     * Adds two packed motion vectors component-wise, wrapping each component to 16 bits.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the packed sum
     */
    private static int addMv(int a, int b) {
        var x = (short) a + (short) b;
        var y = (a >> 16) + (b >> 16);
        return (y << 16) | (x & 0xFFFF);
    }

    /**
     * Writes the reference index and motion vector of a macroblock partition into the caches.
     *
     * @param list the list index
     * @param p the partition index
     * @param r the reference index
     * @param mv the packed motion vector
     */
    private void fillPartition(int list, int p, int r, int mv) {
        var c = partitionCache(p);
        var w = partShape == 1 || partShape == 0 ? 4 : 2;
        var h = partShape == 2 || partShape == 0 ? 4 : 2;
        fillBlock(list, c, w, h, r, mv);
    }

    /**
     * Writes a reference index and motion vector over a block rectangle of the caches.
     *
     * @param list the list index
     * @param c the cache index of the top-left 4x4 block
     * @param w the width in 4x4 blocks
     * @param h the height in 4x4 blocks
     * @param r the reference index
     * @param mv the packed motion vector
     */
    private void fillBlock(int list, int c, int w, int h, int r, int mv) {
        var rc = refCache[list];
        var mc = mvCache[list];
        for (var y = 0; y < h; y++) {
            var row = c + y * 8;
            for (var x = 0; x < w; x++) {
                rc[row + x] = r;
                mc[row + x] = mv;
            }
        }
    }

    /**
     * Parses {@code sub_mb_pred()} of a P_8x8, P_8x8ref0 or B_8x8 macroblock and derives its
     * motion vectors.
     *
     * @param refZero for P slices, whether the macroblock is P_8x8ref0 (reference indices are
     * all zero); ignored for B slices
     */
    private void decodeSubMbPred(boolean refZero) {
        directMask = 0;
        for (var i = 0; i < 4; i++) {
            if (bSlice) {
                var type = cabacMode ? decodeBSubMbTypeCabac() : bits.readUe();
                if (type > 12) {
                    throw new MediaException.Malformed("sub_mb_type " + type + " out of range");
                }
                subShape[i] = B_SUB_SHAPE[type];
                subPred[i] = B_SUB_PRED[type];
                if (type == 0) {
                    directMask |= 1 << i;
                    subPred[i] = 0;
                }
            } else {
                var type = cabacMode ? decodePSubMbTypeCabac() : bits.readUe();
                if (type > 3) {
                    throw new MediaException.Malformed("sub_mb_type " + type + " out of range");
                }
                subShape[i] = P_SUB_SHAPE[type];
                subPred[i] = 1;
            }
        }
        mbFlags |= directMask << DecoderFrame.MB_DIRECT8_SHIFT;
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < 4; i++) {
                var r = -1;
                if ((subPred[i] & (1 << list)) != 0) {
                    if (!bSlice && refZero) {
                        r = 0;
                    } else {
                        r = decodeRefIdx(list, CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16);
                    }
                }
                refIdx[list][i] = r;
                if (cabacMode) {
                    var flag = r > 0 ? 1 : 0;
                    var c = CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16;
                    var ctx = refCtxCache[list];
                    ctx[c] = flag;
                    ctx[c + 1] = flag;
                    ctx[c + 8] = flag;
                    ctx[c + 9] = flag;
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var covering = 0;
            for (var i = 0; i < 4; i++) {
                covering += (subPred[i] >> list) & 1;
            }
            if (covering < 4 && (bSlice || list == 0)) {
                clearMvd(list);
            }
            for (var i = 0; i < 4; i++) {
                if ((subPred[i] & (1 << list)) == 0) {
                    continue;
                }
                var c8 = CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16;
                switch (subShape[i]) {
                    case 0 -> fillMvd(list, c8, 2, 2, decodeMvd(list, c8));
                    case 1 -> {
                        fillMvd(list, c8, 2, 1, decodeMvd(list, c8));
                        fillMvd(list, c8 + 8, 2, 1, decodeMvd(list, c8 + 8));
                    }
                    case 2 -> {
                        fillMvd(list, c8, 1, 2, decodeMvd(list, c8));
                        fillMvd(list, c8 + 1, 1, 2, decodeMvd(list, c8 + 1));
                    }
                    default -> {
                        fillMvd(list, c8, 1, 1, decodeMvd(list, c8));
                        fillMvd(list, c8 + 1, 1, 1, decodeMvd(list, c8 + 1));
                        fillMvd(list, c8 + 8, 1, 1, decodeMvd(list, c8 + 8));
                        fillMvd(list, c8 + 9, 1, 1, decodeMvd(list, c8 + 9));
                    }
                }
            }
        }
        deriveSubMbMotion();
    }

    /**
     * Derives the motion vectors of the four 8x8 partitions from the parsed reference indices
     * and differences, including direct-mode partitions of B_8x8.
     */
    private void deriveSubMbMotion() {
        var spatial = bSlice && header.directSpatialMvPred;
        if (directMask != 0) {
            prepareColocated();
        }
        int[] direct = null;
        if (directMask != 0 && spatial) {
            direct = spatialPredictors();
        }
        for (var list = 0; list < 2; list++) {
            var rc = refCache[list];
            var mc = mvCache[list];
            for (var i = 0; i < 16; i++) {
                rc[CACHE_OF_RASTER[i]] = UNAVAILABLE;
                mc[CACHE_OF_RASTER[i]] = 0;
            }
        }
        for (var i = 0; i < 4; i++) {
            var c8 = CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16;
            if ((directMask & (1 << i)) != 0) {
                if (spatial) {
                    spatialDirect8x8(i, direct);
                } else {
                    temporalDirect8x8(i);
                }
                continue;
            }
            for (var list = 0; list < 2; list++) {
                var r = refIdx[list][i];
                if (r < 0) {
                    fillBlock(list, c8, 2, 2, -1, 0);
                    continue;
                }
                var diffs = mvd[list];
                switch (subShape[i]) {
                    case 0 -> fillBlock(list, c8, 2, 2, r, addMv(predictMv(list, c8, 2, r), diffs[rasterOfCache(c8)]));
                    case 1 -> {
                        fillBlock(list, c8, 2, 1, r, addMv(predictMv(list, c8, 2, r), diffs[rasterOfCache(c8)]));
                        fillBlock(list, c8 + 8, 2, 1, r, addMv(predictMv(list, c8 + 8, 2, r), diffs[rasterOfCache(c8 + 8)]));
                    }
                    case 2 -> {
                        fillBlock(list, c8, 1, 2, r, addMv(predictMv(list, c8, 1, r), diffs[rasterOfCache(c8)]));
                        fillBlock(list, c8 + 1, 1, 2, r, addMv(predictMv(list, c8 + 1, 1, r), diffs[rasterOfCache(c8 + 1)]));
                    }
                    default -> {
                        fillBlock(list, c8, 1, 1, r, addMv(predictMv(list, c8, 1, r), diffs[rasterOfCache(c8)]));
                        fillBlock(list, c8 + 1, 1, 1, r, addMv(predictMv(list, c8 + 1, 1, r), diffs[rasterOfCache(c8 + 1)]));
                        fillBlock(list, c8 + 8, 1, 1, r, addMv(predictMv(list, c8 + 8, 1, r), diffs[rasterOfCache(c8 + 8)]));
                        fillBlock(list, c8 + 9, 1, 1, r, addMv(predictMv(list, c8 + 9, 1, r), diffs[rasterOfCache(c8 + 9)]));
                    }
                }
            }
        }
    }

    /**
     * Derives the motion of a P_Skip macroblock (ITU-T H.264 8.4.1.1).
     */
    private void derivePSkip() {
        var rc = refCache[0];
        var mc = mvCache[0];
        var refA = rc[CACHE_ORIGIN - 1];
        var refB = rc[CACHE_ORIGIN - 8];
        int mv;
        if (refA == UNAVAILABLE || refB == UNAVAILABLE
                || (refA == 0 && mc[CACHE_ORIGIN - 1] == 0)
                || (refB == 0 && mc[CACHE_ORIGIN - 8] == 0)) {
            mv = 0;
        } else {
            mv = predictMv(0, CACHE_ORIGIN, 4, 0);
        }
        rc[CACHE_ORIGIN] = 0;
        mc[CACHE_ORIGIN] = mv;
    }

    /**
     * Derives the motion of a B_Skip or B_Direct_16x16 macroblock.
     */
    private void deriveDirect() {
        prepareColocated();
        if (header.directSpatialMvPred) {
            var predictors = spatialPredictors();
            if (header.sps.direct8x8Inference && spatialDirectUniform(predictors)) {
                return;
            }
            for (var i = 0; i < 4; i++) {
                spatialDirect8x8(i, predictors);
            }
        } else {
            for (var i = 0; i < 4; i++) {
                temporalDirect8x8(i);
            }
        }
    }

    /**
     * The spatial direct predictors: reference index and packed motion vector of each list, plus
     * the direct-zero flag.
     */
    private final int[] spatialResult = new int[5];

    /**
     * Computes the macroblock-level spatial direct predictors (ITU-T H.264 8.4.1.2.2).
     *
     * @return {@code [refIdxL0, refIdxL1, mvpL0, mvpL1, directZeroPrediction]}
     */
    private int[] spatialPredictors() {
        var result = spatialResult;
        for (var list = 0; list < 2; list++) {
            var rc = refCache[list];
            var refA = Math.max(rc[CACHE_ORIGIN - 1], -1);
            var refB = Math.max(rc[CACHE_ORIGIN - 8], -1);
            var refC = rc[CACHE_ORIGIN - 8 + 4];
            if (refC == UNAVAILABLE) {
                refC = rc[CACHE_ORIGIN - 9];
            }
            refC = Math.max(refC, -1);
            result[list] = minPositive(refA, minPositive(refB, refC));
        }
        var directZero = result[0] < 0 && result[1] < 0;
        if (directZero) {
            result[0] = 0;
            result[1] = 0;
            result[2] = 0;
            result[3] = 0;
        } else {
            for (var list = 0; list < 2; list++) {
                if (result[list] >= 0) {
                    result[2 + list] = predictMv(list, CACHE_ORIGIN, 4, result[list]);
                } else {
                    result[2 + list] = 0;
                }
            }
        }
        result[4] = directZero ? 1 : 0;
        return result;
    }

    /**
     * Returns {@code MinPositive(x, y)} of ITU-T H.264 equation 8-184.
     *
     * @param x the first value
     * @param y the second value
     * @return the smaller of the non-negative values, or the larger when either is negative
     */
    private static int minPositive(int x, int y) {
        return x >= 0 && y >= 0 ? Math.min(x, y) : Math.max(x, y);
    }

    /**
     * Derives the spatial direct motion of a whole B_Skip or B_Direct_16x16 macroblock under
     * {@code direct_8x8_inference_flag} when its four 8x8 partitions turn out to share one
     * motion, and then marks the macroblock as a single 16x16 partition.
     *
     * <p>The partitions differ only through the co-located zero-motion test of their corner
     * blocks (ITU-T H.264 8.4.1.2.2), which matters only when a list predicts from reference
     * index 0, the prediction is not the direct-zero one, and the co-located macroblock is an
     * inter macroblock of a short-term reference; otherwise, or when the test gives the same
     * answer for all four corners, the motion is uniform. As for {@link #decodeMbPred16x16()},
     * only the top-left cache entries are written.
     *
     * @param predictors the macroblock-level predictors from {@link #spatialPredictors()}
     * @return {@code true} when the motion was derived; {@code false} when the partitions differ
     * and must be derived one by one
     */
    private boolean spatialDirectUniform(int[] predictors) {
        var ref0 = predictors[0];
        var ref1 = predictors[1];
        var directZero = predictors[4] != 0;
        var colZero = false;
        if (!directZero && (ref0 == 0 || ref1 == 0) && colocatedShortTerm) {
            var zeros = (colocatedZero(0) ? 1 : 0) + (colocatedZero(3) ? 1 : 0)
                    + (colocatedZero(12) ? 1 : 0) + (colocatedZero(15) ? 1 : 0);
            if (zeros != 0 && zeros != 4) {
                return false;
            }
            colZero = zeros == 4;
        }
        refCache[0][CACHE_ORIGIN] = ref0;
        mvCache[0][CACHE_ORIGIN] = ref0 < 0 || directZero || (ref0 == 0 && colZero) ? 0 : predictors[2];
        refCache[1][CACHE_ORIGIN] = ref1;
        mvCache[1][CACHE_ORIGIN] = ref1 < 0 || directZero || (ref1 == 0 && colZero) ? 0 : predictors[3];
        partShape = 0;
        return true;
    }

    /**
     * Derives the spatial direct motion of one 8x8 partition.
     *
     * @param i the partition index
     * @param predictors the macroblock-level predictors from {@link #spatialPredictors()}
     */
    private void spatialDirect8x8(int i, int[] predictors) {
        var c8 = CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16;
        var ref0 = predictors[0];
        var ref1 = predictors[1];
        var mv0 = predictors[2];
        var mv1 = predictors[3];
        var directZero = predictors[4] != 0;
        refIdx[0][i] = ref0;
        refIdx[1][i] = ref1;
        if (header.sps.direct8x8Inference) {
            var colZero = !directZero && colocatedShortTerm && colocatedZero(cornerRaster(i));
            fillBlock(0, c8, 2, 2, ref0, ref0 < 0 || directZero || (ref0 == 0 && colZero) ? 0 : mv0);
            fillBlock(1, c8, 2, 2, ref1, ref1 < 0 || directZero || (ref1 == 0 && colZero) ? 0 : mv1);
            return;
        }
        for (var j = 0; j < 4; j++) {
            var c = c8 + (j & 1) + (j >> 1) * 8;
            var raster = rasterOfCache(c);
            var colZero = !directZero && colocatedShortTerm && colocatedZero(raster);
            fillBlock(0, c, 1, 1, ref0, ref0 < 0 || directZero || (ref0 == 0 && colZero) ? 0 : mv0);
            fillBlock(1, c, 1, 1, ref1, ref1 < 0 || directZero || (ref1 == 0 && colZero) ? 0 : mv1);
        }
    }

    /**
     * Returns the raster index of the corner 4x4 block of an 8x8 partition used with
     * {@code direct_8x8_inference_flag}.
     *
     * @param i the partition index
     * @return the raster index
     */
    private static int cornerRaster(int i) {
        return switch (i) {
            case 0 -> 0;
            case 1 -> 3;
            case 2 -> 12;
            default -> 15;
        };
    }

    /**
     * The macroblock index in the co-located picture of the co-located blocks in the upper half
     * of the current macroblock (ITU-T H.264 Table 8-8).
     */
    private int colMbTop;

    /**
     * The macroblock index in the co-located picture of the co-located blocks in the lower half
     * of the current macroblock.
     */
    private int colMbBottom;

    /**
     * The vertical relation of the current and the co-located macroblock: 0
     * {@code One_To_One}, 1 {@code Frm_To_Fld}, 2 {@code Fld_To_Frm}.
     */
    private int colScale;

    /**
     * The first co-located row, in luma samples, of a {@code Fld_To_Frm} macroblock: 0 for a
     * top macroblock, 8 for a bottom one.
     */
    private int colRowOffset;

    /**
     * The co-located macroblock found by the last {@link #locateColocated}.
     */
    private int colMb;

    /**
     * The raster index of the co-located 4x4 block found by the last {@link #locateColocated}.
     */
    private int colBlock;

    /**
     * The 8x8 partition of the co-located block found by the last {@link #locateColocated}.
     */
    private int colPart;

    /**
     * Derives the co-located macroblocks of the current macroblock (ITU-T H.264 8.4.1.2.1,
     * Tables 8-6 and 8-8) and waits until their motion is final.
     */
    private void prepareColocated() {
        var col = colocated;
        var width = mbWidth;
        colScale = 0;
        colRowOffset = 0;
        if (col.codingStruct == DecoderFrame.CODED_MBAFF) {
            col.awaitDecodedRows(2 * ((mbAddr / width) >> 1) + 2);
        }
        if (fieldPicture) {
            switch (col.codingStruct) {
                case DecoderFrame.CODED_FIELDS -> {
                    colMbTop = (2 * mbY + task.structures[1][0] - 1) * width + mbX;
                    colMbBottom = colMbTop;
                }
                case DecoderFrame.CODED_FRAME -> {
                    colMbTop = 2 * mbY * width + mbX;
                    colMbBottom = colMbTop + width;
                    colScale = 1;
                }
                default -> {
                    var x = 2 * mbY * width + mbX;
                    if ((col.mbType[x] & DecoderFrame.MB_FIELD) == 0) {
                        colMbTop = x;
                        colMbBottom = x + width;
                        colScale = 1;
                    } else {
                        colMbTop = x + pictureParity * width;
                        colMbBottom = colMbTop;
                    }
                }
            }
        } else if (!mbaff) {
            if (col.codingStruct == DecoderFrame.CODED_FIELDS) {
                colMbTop = (2 * (mbY >> 1) + closerColocatedField()) * width + mbX;
                colMbBottom = colMbTop;
                colScale = 2;
                colRowOffset = 8 * (mbY & 1);
            } else {
                colMbTop = mbAddr;
                colMbBottom = mbAddr;
            }
        } else {
            var top = pairTop;
            if (col.codingStruct == DecoderFrame.CODED_FIELDS) {
                colMbTop = top + (fieldMb ? mbParity : closerColocatedField()) * width;
                colMbBottom = colMbTop;
                if (!fieldMb) {
                    colScale = 2;
                    colRowOffset = 8 * (mbY & 1);
                }
            } else {
                var colField = col.codingStruct == DecoderFrame.CODED_MBAFF
                        && (col.mbType[mbAddr] & DecoderFrame.MB_FIELD) != 0;
                if (fieldMb == colField) {
                    colMbTop = mbAddr;
                    colMbBottom = mbAddr;
                } else if (!fieldMb) {
                    colMbTop = top + closerColocatedField() * width;
                    colMbBottom = colMbTop;
                    colScale = 2;
                    colRowOffset = 8 * (mbY & 1);
                } else {
                    colMbTop = top;
                    colMbBottom = top + width;
                    colScale = 1;
                }
            }
        }
        if (col.codingStruct == DecoderFrame.CODED_FRAME) {
            col.awaitDecodedRows(Math.max(colMbTop, colMbBottom) / width + 1);
        } else if (col.codingStruct == DecoderFrame.CODED_FIELDS) {
            col.awaitDone(((colMbTop / width) & 1) + 1);
        }
    }

    /**
     * Returns the field of {@code RefPicList1[0]} closer in output order to the current frame,
     * the top field on a tie (ITU-T H.264 Table 8-6).
     *
     * @return 0 for the top field, 1 for the bottom field
     */
    private int closerColocatedField() {
        var current = viewCurrentPoc[0];
        var top = Math.abs(task.refFieldPoc[1][0] - current);
        var bottom = Math.abs(task.refFieldPoc[1][1] - current);
        return top < bottom ? 0 : 1;
    }

    /**
     * Locates the co-located block of the luma location {@code (xCol, yCol)} of the current
     * macroblock (ITU-T H.264 8.4.1.2.1) into {@link #colMb}, {@link #colBlock} and
     * {@link #colPart}.
     *
     * @param raster the raster 4x4 index of the block at {@code (xCol, yCol)}
     */
    private void locateColocated(int raster) {
        var xCol = (raster & 3) * 4;
        var yCol = (raster >> 2) * 4;
        int yM;
        switch (colScale) {
            case 0 -> {
                colMb = colMbTop;
                yM = yCol;
            }
            case 1 -> {
                colMb = yCol < 8 ? colMbTop : colMbBottom;
                yM = (2 * yCol) & 15;
            }
            default -> {
                colMb = colMbTop;
                yM = colRowOffset + 4 * (yCol >> 3);
            }
        }
        colBlock = (xCol >> 2) + 4 * (yM >> 2);
        colPart = (yM >> 3) * 2 + (xCol >> 3);
    }

    /**
     * Returns whether the co-located block of a block of the current macroblock is "not moving"
     * for spatial direct prediction: the co-located macroblock is not intra coded, its reference
     * index is 0 and both motion vector components lie in {@code [-1, 1]}, in the units of the
     * co-located macroblock.
     *
     * @param raster the raster 4x4 index of the block
     * @return the {@code colZeroFlag} without the short-term condition
     */
    private boolean colocatedZero(int raster) {
        locateColocated(raster);
        var col = colocated;
        var mb = colMb;
        if ((col.mbType[mb] & DecoderFrame.MB_INTRA) != 0) {
            return false;
        }
        var at = mb * 4 + colPart;
        var r0 = col.ref[0][at];
        int ref;
        int mv;
        if (r0 >= 0) {
            ref = r0;
            mv = col.mv[0][mb * 16 + colBlock];
        } else {
            ref = col.ref[1][at];
            mv = col.mv[1][mb * 16 + colBlock];
        }
        if (ref != 0) {
            return false;
        }
        int x = (short) mv;
        var y = mv >> 16;
        return x >= -1 && x <= 1 && y >= -1 && y <= 1;
    }

    /**
     * Derives the temporal direct motion of one 8x8 partition (ITU-T H.264 8.4.1.2.3).
     *
     * @param i the partition index
     */
    private void temporalDirect8x8(int i) {
        var c8 = CACHE_ORIGIN + (i & 1) * 2 + (i >> 1) * 16;
        var scales = viewDirectScale[view];
        if (header.sps.direct8x8Inference) {
            var refL0 = temporalReference(cornerRaster(i));
            refIdx[0][i] = refL0;
            refIdx[1][i] = 0;
            fillTemporal(c8, 2, refL0, scales[refL0], temporalMv);
            return;
        }
        for (var j = 0; j < 4; j++) {
            var c = c8 + (j & 1) + (j >> 1) * 8;
            var refL0 = temporalReference(rasterOfCache(c));
            refIdx[0][i] = refL0;
            refIdx[1][i] = 0;
            fillTemporal(c, 1, refL0, scales[refL0], temporalMv);
        }
    }

    /**
     * The co-located motion vector of the last {@link #temporalReference}, scaled vertically to
     * the units of the current macroblock.
     */
    private int temporalMv;

    /**
     * Derives the list 0 reference index of the temporal direct prediction of a block, and its
     * co-located motion vector into {@link #temporalMv} (ITU-T H.264 8.4.1.2.3).
     *
     * @param raster the raster 4x4 index of the block
     * @return {@code refIdxL0}
     */
    private int temporalReference(int raster) {
        locateColocated(raster);
        var col = colocated;
        var mb = colMb;
        var at = mb * 4 + colPart;
        if ((col.mbType[mb] & DecoderFrame.MB_INTRA) != 0) {
            temporalMv = 0;
            return 0;
        }
        var colList = col.ref[0][at] >= 0 ? 0 : 1;
        if (col.ref[colList][at] < 0) {
            temporalMv = 0;
            return 0;
        }
        var mv = col.mv[colList][mb * 16 + colBlock];
        var y = mv >> 16;
        if (colScale == 1) {
            y /= 2;
        } else if (colScale == 2) {
            y *= 2;
        }
        temporalMv = (y << 16) | (mv & 0xFFFF);
        return mapColocatedToList0(col.refId[colList][at]);
    }

    /**
     * Writes the scaled temporal direct motion of a square block into the caches.
     *
     * @param c the cache index of the top-left 4x4 block
     * @param size the block size in 4x4 units
     * @param refL0 the list 0 reference index
     * @param scale the {@code DistScaleFactor}, or {@code 9999} to copy {@code mvCol}
     * @param mvCol the packed co-located motion vector
     */
    private void fillTemporal(int c, int size, int refL0, int scale, int mvCol) {
        int mv0;
        int mv1;
        if (scale == 9999) {
            mv0 = mvCol;
            mv1 = 0;
        } else {
            int colX = (short) mvCol;
            var colY = mvCol >> 16;
            var x0 = (scale * colX + 128) >> 8;
            var y0 = (scale * colY + 128) >> 8;
            mv0 = (y0 << 16) | (x0 & 0xFFFF);
            mv1 = ((y0 - colY) << 16) | ((x0 - colX) & 0xFFFF);
        }
        fillBlock(0, c, size, size, refL0, mv0);
        fillBlock(1, c, size, size, 0, mv1);
    }

    /**
     * Returns the list 0 reference index of the current macroblock that refers to the picture a
     * co-located block referenced ({@code MapColToList0} of ITU-T H.264 8.4.1.2.3): the picture
     * itself, the field of a frame with the parity of the current field, or the frame holding a
     * field, as the vertical relation of the two macroblocks requires.
     *
     * @param reference the co-located block's reference, {@code (id << 2) | kind} as stored in
     * {@link DecoderFrame#refId}
     * @return the lowest such index, or 0 when list 0 holds none
     */
    private int mapColocatedToList0(int reference) {
        var frameId = reference >> 2;
        var kind = reference & 3;
        var ids = viewRefId[0][0];
        var count = refCount[0];
        int target;
        if (fieldMb && !fieldPicture) {
            var index = lowestFrameIndex(frameId, count, ids);
            if (colScale == 0 && kind != DecoderFrame.REF_FRAME) {
                return (index << 1) + (kind - 1 == mbParity ? 0 : 1);
            }
            return index << 1;
        }
        if (colScale == 1) {
            target = (frameId << 2) | (pictureParity + 1);
        } else if (colScale == 2) {
            return lowestFrameIndex(frameId, count, ids);
        } else {
            target = reference;
        }
        for (var i = 0; i < count; i++) {
            if (ids[i] == target) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Returns the lowest list 0 index of view 0 that refers to a frame, or to a field of it.
     *
     * @param frameId the {@link DecoderFrame#id} of the frame
     * @param count the number of active list 0 entries
     * @param ids the references of the list 0 entries of view 0
     * @return the index, or 0 when none refers to it
     */
    private static int lowestFrameIndex(int frameId, int count, int[] ids) {
        for (var i = 0; i < count; i++) {
            if (ids[i] >> 2 == frameId) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Fills the neighbor entries of the motion caches of a list from the neighboring
     * macroblocks.
     *
     * <p>The current macroblock's entries are left as they are: every prediction path resets or
     * fills them before reading them, and the column right of the macroblock stays unavailable
     * from construction.
     *
     * @param list the list index
     */
    private void fillMotionCache(int list) {
        if (mbaff) {
            fillMbaffMotionCache(list, 4);
            return;
        }
        var rc = refCache[list];
        var mc = mvCache[list];
        var refs = frame.ref[list];
        var mvs = frame.mv[list];
        if (availA) {
            var a = mbA;
            for (var y = 0; y < 4; y++) {
                rc[8 + y * 8] = refs[a * 4 + (y >> 1) * 2 + 1];
                mc[8 + y * 8] = mvs[a * 16 + y * 4 + 3];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                rc[8 + y * 8] = UNAVAILABLE;
                mc[8 + y * 8] = 0;
            }
        }
        if (availB) {
            var b = mbB;
            for (var x = 0; x < 4; x++) {
                rc[1 + x] = refs[b * 4 + 2 + (x >> 1)];
                mc[1 + x] = mvs[b * 16 + 12 + x];
            }
        } else {
            for (var x = 0; x < 4; x++) {
                rc[1 + x] = UNAVAILABLE;
                mc[1 + x] = 0;
            }
        }
        if (availC) {
            var c = mbC;
            rc[5] = refs[c * 4 + 2];
            mc[5] = mvs[c * 16 + 12];
        } else {
            rc[5] = UNAVAILABLE;
            mc[5] = 0;
        }
        if (availD) {
            var d = mbD;
            rc[0] = refs[d * 4 + 3];
            mc[0] = mvs[d * 16 + 15];
        } else {
            rc[0] = UNAVAILABLE;
            mc[0] = 0;
        }
    }

    /**
     * Fills the neighbor entries of the motion caches of a list for a macroblock of an MBAFF
     * frame, scaling the vectors and reference indices of neighbors coded in the other of frame
     * and field decoding to the current macroblock's units (ITU-T H.264 8.4.1.3.2).
     *
     * @param list the list index
     * @param width the number of left rows and upper columns filled: 4, or 1 for the entries a
     * 16x16 partition reads
     */
    private void fillMbaffMotionCache(int list, int width) {
        var rc = refCache[list];
        var mc = mvCache[list];
        var refs = frame.ref[list];
        var mvs = frame.mv[list];
        var types = frame.mbType;
        for (var y = 0; y < width; y++) {
            if (availA) {
                var n = leftMb[y];
                var row = leftRow[y];
                setScaledNeighbor(rc, mc, 8 + y * 8, refs[n * 4 + (row >> 1) * 2 + 1], mvs[n * 16 + row * 4 + 3],
                        types[n]);
            } else {
                rc[8 + y * 8] = UNAVAILABLE;
                mc[8 + y * 8] = 0;
            }
        }
        var dRefs = leftDRef[list];
        var dMvs = leftDMv[list];
        for (var y = 1; y < width; y++) {
            if (availA) {
                var n = leftDMb[y];
                var row = leftDRow[y];
                setScaledNeighbor(dRefs, dMvs, y, refs[n * 4 + (row >> 1) * 2 + 1], mvs[n * 16 + row * 4 + 3], types[n]);
            } else {
                dRefs[y] = UNAVAILABLE;
                dMvs[y] = 0;
            }
        }
        for (var x = 0; x < width; x++) {
            if (availB) {
                var n = mbB;
                setScaledNeighbor(rc, mc, 1 + x, refs[n * 4 + 2 + (x >> 1)], mvs[n * 16 + 12 + x], types[n]);
            } else {
                rc[1 + x] = UNAVAILABLE;
                mc[1 + x] = 0;
            }
        }
        if (availC) {
            var n = mbC;
            setScaledNeighbor(rc, mc, 5, refs[n * 4 + 2], mvs[n * 16 + 12], types[n]);
        } else {
            rc[5] = UNAVAILABLE;
            mc[5] = 0;
        }
        if (availD) {
            var n = mbD;
            var row = rowD;
            setScaledNeighbor(rc, mc, 0, refs[n * 4 + (row >> 1) * 2 + 1], mvs[n * 16 + row * 4 + 3], types[n]);
        } else {
            rc[0] = UNAVAILABLE;
            mc[0] = 0;
        }
    }

    /**
     * Stores the motion of a neighboring block of an MBAFF macroblock into the motion caches,
     * scaled to the current macroblock: a frame neighbor of a field macroblock halves its vertical
     * vector and doubles its reference index, a field neighbor of a frame macroblock doubles its
     * vertical vector and halves its reference index.
     *
     * @param rc the reference index cache
     * @param mc the motion vector cache
     * @param at the cache index
     * @param ref the neighbor's reference index, -1 when it does not use the list
     * @param mv the neighbor's packed motion vector
     * @param type the flags of the neighbor's macroblock
     */
    private void setScaledNeighbor(int[] rc, int[] mc, int at, int ref, int mv, int type) {
        if (ref >= 0) {
            var neighborField = (type & DecoderFrame.MB_FIELD) != 0;
            if (fieldMb && !neighborField) {
                mv = (((mv >> 16) / 2) << 16) | (mv & 0xFFFF);
                ref <<= 1;
            } else if (!fieldMb && neighborField) {
                mv = (((mv >> 16) * 2) << 16) | (mv & 0xFFFF);
                ref >>= 1;
            }
        }
        rc[at] = ref;
        mc[at] = mv;
    }

    /**
     * Fills the neighbor entries of the motion caches of a list that the prediction of a
     * macroblock as one 16x16 partition reads: the left neighbor of the top-left block, the
     * block above it, and the blocks above-right and above-left of the macroblock.
     *
     * @param list the list index
     */
    private void fillCornerMotionCache(int list) {
        if (mbaff) {
            fillMbaffMotionCache(list, 1);
            return;
        }
        var rc = refCache[list];
        var mc = mvCache[list];
        var refs = frame.ref[list];
        var mvs = frame.mv[list];
        if (availA) {
            var a = mbA;
            rc[8] = refs[a * 4 + 1];
            mc[8] = mvs[a * 16 + 3];
        } else {
            rc[8] = UNAVAILABLE;
            mc[8] = 0;
        }
        if (availB) {
            var b = mbB;
            rc[1] = refs[b * 4 + 2];
            mc[1] = mvs[b * 16 + 12];
        } else {
            rc[1] = UNAVAILABLE;
            mc[1] = 0;
        }
        if (availC) {
            var c = mbC;
            rc[5] = refs[c * 4 + 2];
            mc[5] = mvs[c * 16 + 12];
        } else {
            rc[5] = UNAVAILABLE;
            mc[5] = 0;
        }
        if (availD) {
            var d = mbD;
            rc[0] = refs[d * 4 + 3];
            mc[0] = mvs[d * 16 + 15];
        } else {
            rc[0] = UNAVAILABLE;
            mc[0] = 0;
        }
    }

    /**
     * Fills the neighbor entries of the CABAC motion vector difference and reference index
     * context caches of the lists the slice uses from the neighboring macroblocks.
     *
     * <p>The current macroblock's entries are written before they are read: reference contexts
     * by every partition's reference decoding, differences by the per-list clear that precedes
     * difference decoding.
     *
     * @param whole whether the macroblock is one 16x16 partition, whose decoding reads only the
     * entries left of and above its top-left block
     */
    private void fillCabacMotionCaches(boolean whole) {
        if (mbaff) {
            fillMbaffCabacCaches(whole ? 1 : 4);
            return;
        }
        if (whole) {
            fillCornerCabacCaches();
            return;
        }
        var lists = bSlice ? 2 : 1;
        for (var list = 0; list < lists; list++) {
            var md = mvdCache[list];
            var rf = refCtxCache[list];
            var stored = pic.mvd[list];
            var refs = frame.ref[list];
            var types = frame.mbType;
            if (availA) {
                var a = mbA;
                var type = types[a];
                var coded = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0;
                for (var y = 0; y < 4; y++) {
                    md[8 + y * 8] = coded ? stored[a * 16 + y * 4 + 3] : 0;
                    var part = (y >> 1) * 2 + 1;
                    rf[8 + y * 8] = refContext(type, part, refs[a * 4 + part]);
                }
            } else {
                for (var y = 0; y < 4; y++) {
                    md[8 + y * 8] = 0;
                    rf[8 + y * 8] = 0;
                }
            }
            if (availB) {
                var b = mbB;
                var type = types[b];
                var coded = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0;
                for (var x = 0; x < 4; x++) {
                    md[1 + x] = coded ? stored[b * 16 + 12 + x] : 0;
                    var part = 2 + (x >> 1);
                    rf[1 + x] = refContext(type, part, refs[b * 4 + part]);
                }
            } else {
                for (var x = 0; x < 4; x++) {
                    md[1 + x] = 0;
                    rf[1 + x] = 0;
                }
            }
        }
    }

    /**
     * Fills the entries of the CABAC motion vector difference and reference index context caches
     * left of and above the top-left block of the macroblock, for the lists the slice uses.
     */
    private void fillCornerCabacCaches() {
        var lists = bSlice ? 2 : 1;
        var types = frame.mbType;
        for (var list = 0; list < lists; list++) {
            var md = mvdCache[list];
            var rf = refCtxCache[list];
            var stored = pic.mvd[list];
            var refs = frame.ref[list];
            if (availA) {
                var a = mbA;
                var type = types[a];
                md[8] = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0 ? stored[a * 16 + 3] : 0;
                rf[8] = refContext(type, 1, refs[a * 4 + 1]);
            } else {
                md[8] = 0;
                rf[8] = 0;
            }
            if (availB) {
                var b = mbB;
                var type = types[b];
                md[1] = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0 ? stored[b * 16 + 12] : 0;
                rf[1] = refContext(type, 2, refs[b * 4 + 2]);
            } else {
                md[1] = 0;
                rf[1] = 0;
            }
        }
    }

    /**
     * Fills the neighbor entries of the CABAC motion vector difference and reference index
     * context caches of the lists the slice uses for a macroblock of an MBAFF frame, with the
     * vertical differences of neighbors coded in the other of frame and field decoding scaled to
     * the current macroblock (ITU-T H.264 9.3.3.1.1.6 and 9.3.3.1.1.7).
     *
     * @param width the number of left rows and upper columns filled: 4, or 1 for the entries a
     * 16x16 partition reads
     */
    private void fillMbaffCabacCaches(int width) {
        var lists = bSlice ? 2 : 1;
        var types = frame.mbType;
        for (var list = 0; list < lists; list++) {
            var md = mvdCache[list];
            var rf = refCtxCache[list];
            var stored = pic.mvd[list];
            var refs = frame.ref[list];
            for (var y = 0; y < width; y++) {
                if (availA) {
                    var n = leftMb[y];
                    var row = leftRow[y];
                    var type = types[n];
                    var coded = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0;
                    md[8 + y * 8] = coded ? scaledMvd(stored[n * 16 + row * 4 + 3], type) : 0;
                    var part = (row >> 1) * 2 + 1;
                    rf[8 + y * 8] = mbaffRefContext(type, part, refs[n * 4 + part]);
                } else {
                    md[8 + y * 8] = 0;
                    rf[8 + y * 8] = 0;
                }
            }
            for (var x = 0; x < width; x++) {
                if (availB) {
                    var n = mbB;
                    var type = types[n];
                    var coded = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0;
                    md[1 + x] = coded ? scaledMvd(stored[n * 16 + 12 + x], type) : 0;
                    var part = 2 + (x >> 1);
                    rf[1 + x] = mbaffRefContext(type, part, refs[n * 4 + part]);
                } else {
                    md[1 + x] = 0;
                    rf[1 + x] = 0;
                }
            }
        }
    }

    /**
     * Scales the packed absolute motion vector difference of a neighboring block of an MBAFF
     * macroblock to the current macroblock: the vertical component is doubled for a field
     * neighbor of a frame macroblock and halved for a frame neighbor of a field macroblock.
     *
     * @param packed the neighbor's packed absolute difference, {@code |y| << 8 | |x|}
     * @param type the flags of the neighbor's macroblock
     * @return the packed scaled absolute difference
     */
    private int scaledMvd(int packed, int type) {
        var neighborField = (type & DecoderFrame.MB_FIELD) != 0;
        if (neighborField == fieldMb) {
            return packed;
        }
        var y = packed >> 8;
        return ((fieldMb ? y >> 1 : y * 2) << 8) | (packed & 0xFF);
    }

    /**
     * Returns the CABAC {@code ref_idx} context condition of a neighboring partition of an MBAFF
     * macroblock: a field neighbor of a frame macroblock counts from reference index 2.
     *
     * @param type the flags of the neighboring macroblock
     * @param part the 8x8 partition of the neighboring block
     * @param ref the reference index of the neighboring partition
     * @return 1 when the scaled reference index is above 0 and the partition was not skipped or
     * predicted in direct mode
     */
    private int mbaffRefContext(int type, int part, int ref) {
        if ((type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) != 0) {
            return 0;
        }
        if ((type & (1 << (DecoderFrame.MB_DIRECT8_SHIFT + part))) != 0) {
            return 0;
        }
        var threshold = !fieldMb && (type & DecoderFrame.MB_FIELD) != 0 ? 1 : 0;
        return ref > threshold ? 1 : 0;
    }

    /**
     * Returns the CABAC {@code ref_idx} context condition of a neighboring partition.
     *
     * @param type the flags of the neighboring macroblock
     * @param part the 8x8 partition of the neighboring block
     * @param ref the reference index of the neighboring partition
     * @return 1 when the reference index is above 0 and the partition was not skipped or
     * predicted in direct mode
     */
    private static int refContext(int type, int part, int ref) {
        if ((type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) != 0) {
            return 0;
        }
        if ((type & (1 << (DecoderFrame.MB_DIRECT8_SHIFT + part))) != 0) {
            return 0;
        }
        return ref > 0 ? 1 : 0;
    }

    /**
     * Stores the motion of the current macroblock from the caches into the frame and, under
     * CABAC, the motion vector differences into the picture state, and flags macroblocks whose
     * motion is uniform over the whole macroblock or over each 8x8 quadrant.
     *
     * <p>Outside B slices only the list 0 motion is stored; the list 1 reference indices are
     * marked unused, which is all that readers of other slices and pictures consult.
     *
     * @implNote This implementation derives the uniformity flags from the partitioning where it
     * implies them: a 16x16 partition is uniform, 16x8 and 8x16 partitions and 8x8 partitions
     * without sub-partitions (or with 8x8-inferred direct prediction) are uniform per quadrant,
     * and then only the quadrant corners are compared. Other layouts compare every 4x4 block.
     * A 16x16 partition stores its single motion for every block from the top-left cache entry.
     */
    private void storeMotion() {
        var addr = mbAddr;
        var lists = bSlice ? 2 : 1;
        int uniformity;
        if (partShape == 0) {
            uniformity = DecoderFrame.MB_UNIFORM | DecoderFrame.MB_UNIFORM8;
        } else if (partShape != 3) {
            var other = partShape == 1 ? CACHE_ORIGIN + 16 : CACHE_ORIGIN + 2;
            uniformity = DecoderFrame.MB_UNIFORM8 | (sameMotion(CACHE_ORIGIN, other, lists) ? DecoderFrame.MB_UNIFORM : 0);
        } else if (quadrantsUniform()) {
            var whole = sameMotion(CACHE_ORIGIN, CACHE_ORIGIN + 2, lists) && sameMotion(CACHE_ORIGIN, CACHE_ORIGIN + 16, lists)
                    && sameMotion(CACHE_ORIGIN, CACHE_ORIGIN + 18, lists);
            uniformity = DecoderFrame.MB_UNIFORM8 | (whole ? DecoderFrame.MB_UNIFORM : 0);
        } else {
            uniformity = scanUniformity(lists);
        }
        if (partShape == 0) {
            storeUniformMotion(addr, lists);
        } else {
            storeMotionBlocks(addr, lists);
        }
        if (!bSlice) {
            var refs = frame.ref[1];
            var ids = frame.refId[1];
            for (var p = 0; p < 4; p++) {
                refs[addr * 4 + p] = -1;
                ids[addr * 4 + p] = -1;
            }
        }
        mbFlags |= uniformity;
    }

    /**
     * Stores the motion of a macroblock predicted as one 16x16 partition, whose cache entries
     * all hold the same reference index, motion vector and motion vector difference per list.
     *
     * @param addr the macroblock address
     * @param lists the number of lists to store
     * @throws MediaException.Malformed if a reference index exceeds the list size
     */
    private void storeUniformMotion(int addr, int lists) {
        for (var list = 0; list < lists; list++) {
            var r = refCache[list][CACHE_ORIGIN];
            var mv = mvCache[list][CACHE_ORIGIN];
            var mvs = frame.mv[list];
            var out = addr * 16;
            for (var k = 0; k < 16; k++) {
                mvs[out + k] = mv;
            }
            int id;
            if (r < 0) {
                r = -1;
                id = -1;
            } else if (r >= activeRefs(list)) {
                throw new MediaException.Malformed("reference index " + r + " out of range");
            } else {
                id = viewRefId[view][list][r];
            }
            var refs = frame.ref[list];
            var ids = frame.refId[list];
            var at = addr * 4;
            for (var p = 0; p < 4; p++) {
                refs[at + p] = (byte) r;
                ids[at + p] = id;
            }
            if (cabacMode && (mbFlags & DecoderFrame.MB_SKIP) == 0) {
                var stored = pic.mvd[list];
                var difference = (short) mvdCache[list][CACHE_ORIGIN];
                for (var k = 0; k < 16; k++) {
                    stored[out + k] = difference;
                }
            }
        }
    }

    /**
     * Stores the motion of a macroblock block by block from the caches.
     *
     * @param addr the macroblock address
     * @param lists the number of lists to store
     * @throws MediaException.Malformed if a reference index exceeds the list size
     */
    private void storeMotionBlocks(int addr, int lists) {
        for (var list = 0; list < lists; list++) {
            var rc = refCache[list];
            var mc = mvCache[list];
            var refs = frame.ref[list];
            var ids = frame.refId[list];
            var mvs = frame.mv[list];
            var out = addr * 16;
            for (var row = 0; row < 4; row++) {
                var c = CACHE_ORIGIN + row * 8;
                mvs[out] = mc[c];
                mvs[out + 1] = mc[c + 1];
                mvs[out + 2] = mc[c + 2];
                mvs[out + 3] = mc[c + 3];
                out += 4;
            }
            var entries = viewRefId[view][list];
            var active = activeRefs(list);
            for (var p = 0; p < 4; p++) {
                var r = rc[CACHE_ORIGIN + (p & 1) * 2 + (p >> 1) * 16];
                if (r < 0 || r >= active) {
                    if (r >= 0) {
                        throw new MediaException.Malformed("reference index " + r + " out of range");
                    }
                    refs[addr * 4 + p] = -1;
                    ids[addr * 4 + p] = -1;
                } else {
                    refs[addr * 4 + p] = (byte) r;
                    ids[addr * 4 + p] = entries[r];
                }
            }
            if (cabacMode && (mbFlags & DecoderFrame.MB_SKIP) == 0) {
                var stored = pic.mvd[list];
                var md = mvdCache[list];
                var at = addr * 16;
                for (var row = 0; row < 4; row++) {
                    var c = CACHE_ORIGIN + row * 8;
                    stored[at] = (short) md[c];
                    stored[at + 1] = (short) md[c + 1];
                    stored[at + 2] = (short) md[c + 2];
                    stored[at + 3] = (short) md[c + 3];
                    at += 4;
                }
            }
        }
    }

    /**
     * Returns whether every 8x8 quadrant of an 8x8-partitioned macroblock is predicted as one
     * block: without sub-partitions, or by direct prediction inferred per 8x8 block.
     *
     * @return whether each quadrant has uniform motion by construction
     */
    private boolean quadrantsUniform() {
        for (var i = 0; i < 4; i++) {
            if ((directMask & (1 << i)) != 0) {
                if (!header.sps.direct8x8Inference) {
                    return false;
                }
            } else if (subShape[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether two cache positions hold the same motion in every list: the same
     * reference index and, when that index is used, the same motion vector.
     *
     * @param first the first cache position
     * @param second the second cache position
     * @param lists the number of lists to compare
     * @return whether the motion matches
     */
    private boolean sameMotion(int first, int second, int lists) {
        for (var list = 0; list < lists; list++) {
            var rc = refCache[list];
            var ref = rc[first];
            if (rc[second] != ref || (ref >= 0 && mvCache[list][first] != mvCache[list][second])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares the motion of every 4x4 block to derive the uniformity flags of a macroblock
     * whose partitioning does not imply them.
     *
     * @param lists the number of lists to compare
     * @return {@link DecoderFrame#MB_UNIFORM} and {@link DecoderFrame#MB_UNIFORM8} as they hold
     */
    private int scanUniformity(int lists) {
        var uniform = true;
        var uniform8 = true;
        for (var list = 0; list < lists; list++) {
            var rc = refCache[list];
            var mc = mvCache[list];
            var firstRef = rc[CACHE_ORIGIN];
            var firstMv = mc[CACHE_ORIGIN];
            for (var i = 1; i < 16 && uniform; i++) {
                var c = CACHE_OF_RASTER[i];
                uniform = rc[c] == firstRef && (firstRef < 0 || mc[c] == firstMv);
            }
            if (!uniform) {
                for (var q = 0; q < 4 && uniform8; q++) {
                    var base = CACHE_ORIGIN + (q & 1) * 2 + (q >> 1) * 16;
                    var ref = rc[base];
                    var mv = mc[base];
                    uniform8 = rc[base + 1] == ref && rc[base + 8] == ref && rc[base + 9] == ref
                            && (ref < 0 || (mc[base + 1] == mv && mc[base + 8] == mv && mc[base + 9] == mv));
                }
            }
        }
        if (uniform) {
            return DecoderFrame.MB_UNIFORM | DecoderFrame.MB_UNIFORM8;
        }
        return uniform8 ? DecoderFrame.MB_UNIFORM8 : 0;
    }

    /**
     * Decodes a reference index.
     *
     * @param list the list index
     * @param c the cache index of the partition's top-left block
     * @return the reference index
     * @throws MediaException.Malformed if the index is not below the active count
     */
    private int decodeRefIdx(int list, int c) {
        var count = activeRefs(list);
        if (count <= 1) {
            return 0;
        }
        int value;
        if (cabacMode) {
            var ctx = refCtxCache[list];
            var inc = ctx[c - 1] + 2 * ctx[c - 8];
            value = 0;
            if (cabac.decision(54 + inc) != 0) {
                value = 1;
                if (cabac.decision(54 + 4) != 0) {
                    value = 2;
                    while (cabac.decision(54 + 5) != 0) {
                        value++;
                        if (value >= 32) {
                            throw new MediaException.Malformed("ref_idx out of range");
                        }
                    }
                }
            }
        } else {
            value = bits.readTe(count - 1);
        }
        if (value >= count) {
            throw new MediaException.Malformed("ref_idx " + value + " out of range");
        }
        return value;
    }

    /**
     * Decodes one motion vector difference, both components.
     *
     * @param list the list index
     * @param c the cache index of the block's top-left 4x4 block
     * @return the packed difference
     */
    private int decodeMvd(int list, int c) {
        int x;
        int y;
        if (cabacMode) {
            var md = mvdCache[list];
            var a = md[c - 1];
            var b = md[c - 8];
            x = decodeMvdComponent(40, (a & 0xFF) + (b & 0xFF));
            y = decodeMvdComponent(47, (a >> 8) + (b >> 8));
        } else {
            x = bits.readSe();
            y = bits.readSe();
        }
        return (y << 16) | (x & 0xFFFF);
    }

    /**
     * Decodes one CABAC motion vector difference component (ITU-T H.264 9.3.2.3 and
     * 9.3.3.1.1.7).
     *
     * @param base the context index offset: 40 for x, 47 for y
     * @param sum the sum of the neighboring absolute differences
     * @return the signed difference
     */
    private int decodeMvdComponent(int base, int sum) {
        var inc = sum < 3 ? 0 : (sum > 32 ? 2 : 1);
        if (cabac.decision(base + inc) == 0) {
            return 0;
        }
        var value = 1;
        var ctx = base + 3;
        while (value < 9 && cabac.decision(ctx) != 0) {
            value++;
            if (ctx < base + 6) {
                ctx++;
            }
        }
        if (value >= 9) {
            var k = 3;
            while (cabac.bypass() != 0) {
                value += 1 << k;
                k++;
                if (k > 24) {
                    throw new MediaException.Malformed("mvd out of range");
                }
            }
            while (k-- > 0) {
                value += cabac.bypass() << k;
            }
        }
        return cabac.bypass() != 0 ? -value : value;
    }

    /**
     * Decodes the {@code mb_type} of the slice with CABAC.
     *
     * @return the {@code mb_type} in the numbering of the slice type
     */
    private int decodeMbTypeCabac() {
        var types = frame.mbType;
        if (iSlice) {
            var inc = 0;
            if (availA && (types[mbA] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) == 0) {
                inc++;
            }
            if (availB && (types[mbB] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) == 0) {
                inc++;
            }
            return decodeIntraMbType(3 + inc, 3, true);
        }
        if (!bSlice) {
            if (cabac.decision(14) == 0) {
                if (cabac.decision(15) == 0) {
                    return cabac.decision(16) == 0 ? 0 : 3;
                }
                return cabac.decision(17) == 0 ? 2 : 1;
            }
            return 5 + decodeIntraMbType(17, 17, false);
        }
        var inc = 0;
        if (availA && (types[mbA] & (DecoderFrame.MB_SKIP | DecoderFrame.MB_DIRECT16)) == 0) {
            inc++;
        }
        if (availB && (types[mbB] & (DecoderFrame.MB_SKIP | DecoderFrame.MB_DIRECT16)) == 0) {
            inc++;
        }
        if (cabac.decision(27 + inc) == 0) {
            return 0;
        }
        if (cabac.decision(27 + 3) == 0) {
            return 1 + cabac.decision(27 + 5);
        }
        var value = cabac.decision(27 + 4) << 3;
        value |= cabac.decision(27 + 5) << 2;
        value |= cabac.decision(27 + 5) << 1;
        value |= cabac.decision(27 + 5);
        if (value < 8) {
            return value + 3;
        }
        if (value == 13) {
            return 23 + decodeIntraMbType(32, 32, false);
        }
        if (value == 14) {
            return 11;
        }
        if (value == 15) {
            return 22;
        }
        value = (value << 1) | cabac.decision(27 + 5);
        return value - 4;
    }

    /**
     * Decodes an intra {@code mb_type} (the I slice type or the intra suffix of P and B types).
     *
     * @param firstCtx the context of the first bin
     * @param base the context offset of the remaining bins: 3 for I slices, 17 or 32 for
     * suffixes
     * @param prefix whether the bins follow the I slice layout (offset 3) rather than the
     * suffix layout
     * @return the I slice {@code mb_type}, 0 to 25
     */
    private int decodeIntraMbType(int firstCtx, int base, boolean prefix) {
        if (cabac.decision(firstCtx) == 0) {
            return 0;
        }
        if (cabac.terminate() != 0) {
            return 25;
        }
        var luma = cabac.decision(base + (prefix ? 3 : 1));
        var chroma = cabac.decision(base + (prefix ? 4 : 2));
        if (chroma != 0) {
            chroma += cabac.decision(base + (prefix ? 5 : 2));
        }
        var mode = cabac.decision(base + (prefix ? 6 : 3)) << 1;
        mode |= cabac.decision(base + (prefix ? 7 : 3));
        return 1 + mode + 4 * chroma + 12 * luma;
    }

    /**
     * Decodes a P {@code sub_mb_type} with CABAC.
     *
     * @return the sub-macroblock type, 0 to 3
     */
    private int decodePSubMbTypeCabac() {
        if (cabac.decision(21) != 0) {
            return 0;
        }
        if (cabac.decision(22) == 0) {
            return 1;
        }
        return cabac.decision(23) != 0 ? 2 : 3;
    }

    /**
     * Decodes a B {@code sub_mb_type} with CABAC.
     *
     * @return the sub-macroblock type, 0 to 12
     */
    private int decodeBSubMbTypeCabac() {
        if (cabac.decision(36) == 0) {
            return 0;
        }
        if (cabac.decision(37) == 0) {
            return 1 + cabac.decision(39);
        }
        if (cabac.decision(38) != 0) {
            if (cabac.decision(39) != 0) {
                return 11 + cabac.decision(39);
            }
            var value = cabac.decision(39) << 1;
            value |= cabac.decision(39);
            return 7 + value;
        }
        var value = cabac.decision(39) << 1;
        value |= cabac.decision(39);
        return 3 + value;
    }

    /**
     * Decodes {@code transform_size_8x8_flag} with CABAC.
     *
     * @return the flag
     */
    private boolean decodeTransformFlag() {
        var inc = 0;
        var types = frame.mbType;
        if (availA && (types[mbA] & DecoderFrame.MB_T8X8) != 0) {
            inc++;
        }
        if (availB && (types[mbB] & DecoderFrame.MB_T8X8) != 0) {
            inc++;
        }
        return cabac.decision(399 + inc) != 0;
    }

    /**
     * Decodes {@code intra_chroma_pred_mode} with CABAC.
     *
     * @return the mode, 0 to 3
     */
    private int decodeChromaPredModeCabac() {
        var inc = 0;
        var types = frame.mbType;
        var modes = pic.chromaMode;
        if (availA) {
            var a = mbA;
            if ((types[a] & DecoderFrame.MB_INTRA) != 0 && (types[a] & DecoderFrame.MB_PCM) == 0 && modes[a] != 0) {
                inc++;
            }
        }
        if (availB) {
            var b = mbB;
            if ((types[b] & DecoderFrame.MB_INTRA) != 0 && (types[b] & DecoderFrame.MB_PCM) == 0 && modes[b] != 0) {
                inc++;
            }
        }
        if (cabac.decision(64 + inc) == 0) {
            return 0;
        }
        if (cabac.decision(67) == 0) {
            return 1;
        }
        return cabac.decision(67) == 0 ? 2 : 3;
    }

    /**
     * Decodes {@code mb_qp_delta} with CABAC.
     *
     * @return the signed delta
     */
    private int decodeQpDeltaCabac() {
        var ctx = 60 + (lastQpDeltaNonZero ? 1 : 0);
        if (cabac.decision(ctx) == 0) {
            return 0;
        }
        var value = 1;
        ctx = 62;
        while (cabac.decision(ctx) != 0) {
            value++;
            ctx = 63;
            if (value > 104) {
                throw new MediaException.Malformed("mb_qp_delta out of range");
            }
        }
        return (value & 1) != 0 ? (value + 1) >> 1 : -(value >> 1);
    }

    /**
     * Decodes {@code coded_block_pattern} with CABAC (ITU-T H.264 9.3.3.1.1.4).
     *
     * @return the coded block pattern
     */
    private int decodeCbpCabac() {
        var types = frame.mbType;
        var cbps = pic.cbp;
        var leftCbp = -1;
        var topCbp = -1;
        if (availA) {
            var a = mbA;
            leftCbp = (types[a] & DecoderFrame.MB_PCM) != 0 ? -2 : cbps[a];
        }
        if (availB) {
            var b = mbB;
            topCbp = (types[b] & DecoderFrame.MB_PCM) != 0 ? -2 : cbps[b];
        }
        var luma = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var x = b8 & 1;
            var y = b8 >> 1;
            int condA;
            if (x == 1) {
                condA = (luma & (1 << (b8 - 1))) == 0 ? 1 : 0;
            } else if (mbaff && availA) {
                var n = leftMb[2 * y];
                var neighborCbp = (types[n] & DecoderFrame.MB_PCM) != 0 ? -2 : cbps[n];
                condA = neighborLumaCbpCondition(neighborCbp, (leftRow[2 * y] >> 1) * 2 + 1);
            } else {
                condA = neighborLumaCbpCondition(leftCbp, b8 + 1);
            }
            int condB;
            if (y == 1) {
                condB = (luma & (1 << (b8 - 2))) == 0 ? 1 : 0;
            } else {
                condB = neighborLumaCbpCondition(topCbp, b8 + 2);
            }
            if (cabac.decision(73 + condA + 2 * condB) != 0) {
                luma |= 1 << b8;
            }
        }
        if (!hasChroma || chroma444) {
            return luma;
        }
        var condA = chromaCbpCondition(leftCbp, 0);
        var condB = chromaCbpCondition(topCbp, 0);
        if (cabac.decision(77 + condA + 2 * condB) == 0) {
            return luma;
        }
        condA = chromaCbpCondition(leftCbp, 1);
        condB = chromaCbpCondition(topCbp, 1);
        return luma | ((1 + cabac.decision(77 + 4 + condA + 2 * condB)) << 4);
    }

    /**
     * Returns the luma coded block pattern context condition of a neighboring 8x8 block.
     *
     * @param neighborCbp the neighbor's pattern, {@code -1} when unavailable, {@code -2} for
     * I_PCM
     * @param b8 the neighboring 8x8 block index within its macroblock
     * @return 1 when the neighbor exists and its block has no coded luma coefficients
     */
    private static int neighborLumaCbpCondition(int neighborCbp, int b8) {
        if (neighborCbp < 0) {
            return 0;
        }
        return (neighborCbp & (1 << b8)) == 0 ? 1 : 0;
    }

    /**
     * Returns a chroma coded block pattern context condition of a neighboring macroblock.
     *
     * @param neighborCbp the neighbor's pattern, {@code -1} when unavailable, {@code -2} for
     * I_PCM
     * @param bin 0 for the first bin (chroma coded), 1 for the second (AC coded)
     * @return the condition, 0 or 1
     */
    private static int chromaCbpCondition(int neighborCbp, int bin) {
        if (neighborCbp == -1) {
            return 0;
        }
        if (neighborCbp == -2) {
            return 1;
        }
        var chroma = (neighborCbp >> 4) & 3;
        return bin == 0 ? (chroma != 0 ? 1 : 0) : (chroma == 2 ? 1 : 0);
    }

    /**
     * The {@code ctxBlockCat} of the DC, AC, 4x4 and 8x8 blocks of each color component coded
     * like luma, indexed {@code [component][kind]} (ITU-T H.264 Table 9-42).
     */
    private static final byte[][] CATEGORIES = {{0, 1, 2, 5}, {6, 7, 8, 9}, {10, 11, 12, 13}};

    /**
     * Parses the residual of the current macroblock with CABAC (ITU-T H.264 7.3.5.3 and
     * 9.3.3.1.1.9).
     */
    private void residualCabac() {
        var addr = mbAddr;
        var nnz = pic.nnz;
        var base = addr * PictureState.NNZ_STRIDE;
        var isIntra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
        dcFlags = 0;
        pic.nonZero[addr] = residualPlaneCabac(0, base, isIntra);
        if (chroma444) {
            residualPlaneCabac(1, base, isIntra);
            residualPlaneCabac(2, base, isIntra);
            pic.cbp[addr] = cbp | dcFlags;
            return;
        }
        var cbpFlags = cbp | dcFlags;
        if (hasChroma && (cbp >> 4) != 0) {
            chromaResidual = true;
            var dcCategory = chroma422 ? CabacReader.CHROMA_DC_422 : 3;
            var dcCount = chroma422 ? 8 : 4;
            for (var c = 0; c < 2; c++) {
                var inc = dcCbfCondition(isIntra, 7 + c);
                if (cabac.decision(CabacTables.CBF_OFFSET[3] + inc) != 0) {
                    cbpFlags |= 1 << (7 + c);
                    residualBlockCabac(dcCategory, chromaDc, c * 8, H264Tables.CHROMA_DC_SCAN, 0, dcCount, null, 0,
                            true);
                    chromaDcCoded |= 1 << c;
                }
            }
            if ((cbp & 0x20) != 0) {
                var blocks = chromaRows >> 1;
                for (var c = 0; c < 2; c++) {
                    var chromaList = (isIntra ? 1 : 4) + c;
                    var chromaQpValue = (c == 0 ? pic.qpCb[addr] : pic.qpCr[addr]) + qpBdOffsetC;
                    var dequant = bypass ? UNIT_SCALE : levelScale4[chromaList][chromaQpValue % 6];
                    var shift = bypass ? 0 : scaleShift(chromaQpValue / 6, 4);
                    var coeffs = c == 0 ? cbCoeffs : crCoeffs;
                    for (var blk = 0; blk < blocks; blk++) {
                        var inc = chromaCbfCondition(isIntra, c, blk);
                        if (cabac.decision(CabacTables.CBF_OFFSET[4] + inc) == 0) {
                            nnz[base + 16 + c * 8 + blk] = 0;
                            continue;
                        }
                        nnz[base + 16 + c * 8 + blk] = 1;
                        residualBlockCabac(4, coeffs, blk * 16, scan4, 1, 15, dequant, shift, false);
                        chromaCoded |= 1 << (c * 8 + blk);
                    }
                }
            } else {
                clearChromaNnz(base);
            }
        } else {
            clearChromaNnz(base);
        }
        pic.cbp[addr] = cbpFlags;
    }

    /**
     * Parses the residual of one color component coded like luma with CABAC
     * ({@code residual_luma}, ITU-T H.264 7.3.5.3.1 and 7.3.5.3.3): luma, or the Cb or Cr of a
     * 4:4:4 macroblock, whose 8x8 blocks carry a {@code coded_block_flag} of their own.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param base the index of the macroblock in {@link PictureState#nnz}
     * @param isIntra whether the macroblock is intra coded
     * @return the 4x4 blocks of the component with coefficients, one bit per raster index
     */
    private int residualPlaneCabac(int comp, int base, boolean isIntra) {
        var nnz = pic.nnz;
        var intra16 = (mbFlags & DecoderFrame.MB_I16X16) != 0;
        var transform8x8 = (mbFlags & DecoderFrame.MB_T8X8) != 0;
        var categories = CATEGORIES[comp];
        fillCbfCache(isIntra, comp);
        var qpPrime = componentQp(comp);
        var qpDiv = qpPrime / 6;
        var qpMod = qpPrime % 6;
        var lossless = bypass;
        var dequant4 = lossless ? UNIT_SCALE : levelScale4[list4(comp)][qpMod];
        var shift4 = lossless ? 0 : scaleShift(qpDiv, 4);
        var coeffs = planeCoeffs[comp];
        var cache = nnzCache;
        if (intra16) {
            var inc = dcCbfCondition(isIntra, 6 + comp);
            if (cabac.decision(CabacTables.CBF_OFFSET[categories[0]] + inc) != 0) {
                dcFlags |= 1 << (6 + comp);
                residualBlockCabac(categories[0], planeDc[comp], 0, scan4, 0, 16, null, 0, true);
                transformPlaneDc(comp, dequant4[0], qpDiv);
            }
        }
        var nonZero = 0;
        var codedBlocks = 0;
        var dcBlocks = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbp & (1 << b8)) == 0) {
                for (var i = 0; i < 4; i++) {
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[b8 * 4 + i]]] = 0;
                }
                continue;
            }
            if (transform8x8) {
                int flag;
                if (chroma444) {
                    flag = cabac.decision(CabacTables.CBF_OFFSET[categories[3]] + cbf8x8Condition(b8));
                } else {
                    flag = 1;
                }
                if (flag != 0) {
                    var dequant8 = lossless ? UNIT_SCALE : levelScale8[list8(comp)][qpMod];
                    var shift8 = lossless ? 0 : scaleShift(qpDiv, 6);
                    var count = residualBlockCabac(categories[3], coeffs, b8 * 64, scan8, 0, 64, dequant8, shift8,
                            false);
                    if (count == 1 && coeffs[b8 * 64] != 0) {
                        dcBlocks |= 1 << b8;
                    } else {
                        codedBlocks |= 1 << b8;
                    }
                    nonZero |= 0x33 << ((b8 & 1) * 2 + (b8 >> 1) * 8);
                }
                for (var i = 0; i < 4; i++) {
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[b8 * 4 + i]]] = flag;
                }
                continue;
            }
            for (var i = 0; i < 4; i++) {
                var blk = b8 * 4 + i;
                var raster = H264Tables.BLOCK_RASTER[blk];
                var c = CACHE_OF_RASTER[raster];
                var category = categories[intra16 ? 1 : 2];
                var inc = cache[c - 1] + 2 * cache[c - 8];
                if (cabac.decision(CabacTables.CBF_OFFSET[category] + inc) == 0) {
                    cache[c] = 0;
                    continue;
                }
                cache[c] = 1;
                if (intra16) {
                    residualBlockCabac(category, coeffs, blk * 16, scan4, 1, 15, dequant4, shift4, false);
                    codedBlocks |= 1 << blk;
                } else {
                    var count = residualBlockCabac(category, coeffs, blk * 16, scan4, 0, 16, dequant4,
                            shift4, false);
                    if (count == 1 && coeffs[blk * 16] != 0) {
                        dcBlocks |= 1 << blk;
                    } else {
                        codedBlocks |= 1 << blk;
                    }
                }
                nonZero |= 1 << raster;
            }
        }
        coded[comp] |= codedBlocks;
        dcOnly[comp] |= dcBlocks;
        var at = base + 16 * comp;
        for (var i = 0; i < 16; i++) {
            nnz[at + i] = (byte) cache[CACHE_OF_RASTER[i]];
        }
        return nonZero;
    }

    /**
     * Returns the context increment of the {@code coded_block_flag} of an 8x8 block of a 4:4:4
     * macroblock ({@code ctxBlockCat} 5, 9 or 13, ITU-T H.264 9.3.3.1.1.9), from the coded block
     * flag cache of its component: a neighboring 8x8 block counts only in a macroblock coded with
     * the 8x8 transform, and an I_PCM neighbor counts as coded.
     *
     * @param b8 the index of the 8x8 block
     * @return the increment, 0 to 3
     */
    private int cbf8x8Condition(int b8) {
        var cache = nnzCache;
        var c = CACHE_ORIGIN + (b8 & 1) * 2 + (b8 >> 1) * 16;
        var types = frame.mbType;
        int a;
        if ((b8 & 1) != 0 || !availA) {
            a = cache[c - 1];
        } else {
            var n = mbaff ? leftMb[(b8 >> 1) * 2] : mbA;
            a = (types[n] & DecoderFrame.MB_PCM) != 0 ? 1 : (types[n] & DecoderFrame.MB_T8X8) != 0 ? cache[c - 1] : 0;
        }
        int b;
        if ((b8 >> 1) != 0 || !availB) {
            b = cache[c - 8];
        } else {
            b = (types[mbB] & DecoderFrame.MB_PCM) != 0 ? 1 : (types[mbB] & DecoderFrame.MB_T8X8) != 0 ? cache[c - 8] : 0;
        }
        return a + 2 * b;
    }

    /**
     * Fills the coded block flag cache of a color component coded like luma from the
     * neighboring macroblocks for CABAC (ITU-T H.264 9.3.3.1.1.9).
     *
     * @param isIntra whether the current macroblock is intra coded
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     */
    private void fillCbfCache(boolean isIntra, int comp) {
        var cache = nnzCache;
        var nnz = pic.nnz;
        var unavailable = isIntra ? 1 : 0;
        var offset = 16 * comp;
        if (availA && mbaff) {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[leftMb[y] * PictureState.NNZ_STRIDE + offset + leftRow[y] * 4 + 3];
            }
        } else if (availA) {
            var base = mbA * PictureState.NNZ_STRIDE + offset;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[base + y * 4 + 3];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = unavailable;
            }
        }
        if (availB) {
            var base = mbB * PictureState.NNZ_STRIDE + offset + 12;
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = nnz[base + x];
            }
        } else {
            for (var x = 0; x < 4; x++) {
                cache[1 + x] = unavailable;
            }
        }
    }

    /**
     * Returns the context increment of a DC {@code coded_block_flag}.
     *
     * @param isIntra whether the current macroblock is intra coded
     * @param bit the bit of the DC flag in {@link PictureState#cbp}: 6 luma, 7 Cb, 8 Cr (the
     * chroma DC blocks, or the Cb and Cr Intra16x16 DC blocks of 4:4:4)
     * @return the increment, 0 to 3
     */
    private int dcCbfCondition(boolean isIntra, int bit) {
        var cbps = pic.cbp;
        var unavailable = isIntra ? 1 : 0;
        var a = availA ? (cbps[mbA] >> bit) & 1 : unavailable;
        var b = availB ? (cbps[mbB] >> bit) & 1 : unavailable;
        return a + 2 * b;
    }

    /**
     * Returns the context increment of a chroma AC {@code coded_block_flag}.
     *
     * @param isIntra whether the current macroblock is intra coded
     * @param component 0 for Cb, 1 for Cr
     * @param blk the {@code chroma4x4BlkIdx}, 0 to 3 for 4:2:0 and 0 to 7 for 4:2:2
     * @return the increment, 0 to 3
     */
    private int chromaCbfCondition(boolean isIntra, int component, int blk) {
        var nnz = pic.nnz;
        var base = mbAddr * PictureState.NNZ_STRIDE + 16 + component * 8;
        var x = blk & 1;
        var y = blk >> 1;
        var unavailable = isIntra ? 1 : 0;
        int a;
        if (x > 0) {
            a = nnz[base + blk - 1];
        } else if (availA) {
            a = nnz[leftChromaIndex(component, y)];
        } else {
            a = unavailable;
        }
        int b;
        if (y > 0) {
            b = nnz[base + blk - 2];
        } else if (availB) {
            b = nnz[topChromaIndex(component, x)];
        } else {
            b = unavailable;
        }
        return a + 2 * b;
    }

    /**
     * Decodes the significance map and levels of one CABAC residual block (ITU-T H.264
     * 7.3.5.3.3) and stores its coefficients, dequantized when {@code dequant} is given.
     *
     * @param category the {@code ctxBlockCat}, 0 to 13, or {@link CabacReader#CHROMA_DC_422}
     * @param coeffs receives the coefficients
     * @param coeffOffset the index of the block in {@code coeffs}
     * @param scan maps scan index to position within the block
     * @param scanStart the scan index of the first coded coefficient
     * @param maxCoeff the {@code maxNumCoeff}
     * @param dequant the scale factor per block position, or {@code null} for raw levels
     * @param shift the post-scaling shift (see {@link CavlcReader#residualBlock})
     * @param dc whether the block is a DC block, stored in scan order
     * @return the number of non-zero coefficients
     */
    private int residualBlockCabac(int category, int[] coeffs, int coeffOffset, byte[] scan, int scanStart,
                                   int maxCoeff, int[] dequant, int shift, boolean dc) {
        var indices = significantIndices;
        var levels = significantLevels;
        var count = cabac.residualBlock(category, maxCoeff, fieldMb, indices, levels);
        if (dc) {
            for (var k = 0; k < count; k++) {
                coeffs[coeffOffset + scanStart + indices[k]] = levels[k];
            }
        } else if (dequant != null) {
            if (shift >= 0) {
                for (var k = 0; k < count; k++) {
                    var position = scan[scanStart + indices[k]];
                    coeffs[coeffOffset + position] = (levels[k] * dequant[position]) << shift;
                }
            } else {
                var right = -shift;
                var round = 1 << (right - 1);
                for (var k = 0; k < count; k++) {
                    var position = scan[scanStart + indices[k]];
                    coeffs[coeffOffset + position] = (levels[k] * dequant[position] + round) >> right;
                }
            }
        } else {
            for (var k = 0; k < count; k++) {
                coeffs[coeffOffset + scan[scanStart + indices[k]]] = levels[k];
            }
        }
        return count;
    }

    /**
     * The signed levels of the significant coefficients of the current CABAC block.
     */
    private final int[] significantLevels = new int[64];

    /**
     * The scan indices of the significant coefficients of the current CABAC block.
     */
    private final int[] significantIndices = new int[64];

    /**
     * Reconstructs an intra macroblock: prediction and residual of luma and chroma.
     */
    private void reconstructIntra() {
        var constrained = pps.constrainedIntraPred;
        var types = frame.mbType;
        int leftRows;
        int leftChromaRows;
        if (mbaff) {
            leftRows = mbaffLeftRows(constrained, false);
            leftChromaRows = chromaRows == 16 ? leftRows : mbaffLeftRows(constrained, true);
        } else {
            var available = availA && (!constrained || (types[mbA] & DecoderFrame.MB_INTRA) != 0);
            leftRows = available ? 0xFFFF : 0;
            leftChromaRows = available ? (1 << chromaRows) - 1 : 0;
        }
        var topOk = availB && (!constrained || (types[mbB] & DecoderFrame.MB_INTRA) != 0);
        var topRightOk = availC && (!constrained || (types[mbC] & DecoderFrame.MB_INTRA) != 0);
        var topLeftOk = availD && (!constrained || (types[mbD] & DecoderFrame.MB_INTRA) != 0);
        reconstructIntraPlane(0, leftRows, topOk, topRightOk, topLeftOk);
        if (chroma444) {
            var depths = wide && frame.bitDepthChroma != frame.bitDepthLuma;
            if (depths) {
                wideIntra.setBitDepths(frame.bitDepthChroma, frame.bitDepthChroma);
            }
            reconstructIntraPlane(1, leftRows, topOk, topRightOk, topLeftOk);
            reconstructIntraPlane(2, leftRows, topOk, topRightOk, topLeftOk);
            if (depths) {
                wideIntra.setBitDepths(frame.bitDepthLuma, frame.bitDepthChroma);
            }
            return;
        }
        if (hasChroma) {
            var chroma = chromaBase;
            var leftBlocks = 0;
            for (var row = 0; row < chromaRows >> 2; row++) {
                if (((leftChromaRows >> (4 * row)) & 0xF) == 0xF) {
                    leftBlocks |= 1 << row;
                }
            }
            if (wide) {
                wideIntra.predictChroma(chromaPredMode, cbPlane16, chroma, chromaStride, chromaRows, leftBlocks, topOk,
                        topLeftOk);
                wideIntra.predictChroma(chromaPredMode, crPlane16, chroma, chromaStride, chromaRows, leftBlocks, topOk,
                        topLeftOk);
            } else {
                intra.predictChroma(chromaPredMode, cbPlane, chroma, chromaStride, chromaRows, leftBlocks, topOk,
                        topLeftOk);
                intra.predictChroma(chromaPredMode, crPlane, chroma, chromaStride, chromaRows, leftBlocks, topOk,
                        topLeftOk);
            }
            addChromaResidual(chroma);
        }
    }

    /**
     * Predicts and reconstructs one color component of an intra macroblock that is coded like
     * luma: luma, or the Cb or Cr of a 4:4:4 macroblock, which takes the luma prediction modes
     * (ITU-T H.264 8.3.4.5).
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param leftRows the available left neighboring sample rows, one bit per row
     * @param topOk whether the samples above are available
     * @param topRightOk whether the samples above and to the right are available
     * @param topLeftOk whether the sample above and to the left is available
     */
    private void reconstructIntraPlane(int comp, int leftRows, boolean topOk, boolean topRightOk, boolean topLeftOk) {
        var base = comp == 0 ? lumaBase : chromaBase;
        var stride = comp == 0 ? lumaStride : chromaStride;
        var plane = plane8(comp);
        var plane16 = plane16(comp);
        var blocks = coded[comp] | dcOnly[comp];
        if ((mbFlags & DecoderFrame.MB_I4X4) != 0) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var offset = base + y * 4 * stride + x * 4;
                var hasLeft = x > 0 || ((leftRows >> (4 * y)) & 0xF) == 0xF;
                var hasTop = y > 0 || topOk;
                boolean hasTopLeft;
                if (x > 0 && y > 0) {
                    hasTopLeft = true;
                } else if (y > 0) {
                    hasTopLeft = ((leftRows >> (4 * y - 1)) & 1) != 0;
                } else if (x > 0) {
                    hasTopLeft = topOk;
                } else {
                    hasTopLeft = topLeftOk;
                }
                boolean hasTopRight;
                if (y == 0) {
                    hasTopRight = x < 3 ? topOk : topRightOk;
                } else {
                    hasTopRight = TOP_RIGHT_INSIDE[blk];
                }
                var mode = predModes[H264Tables.BLOCK_RASTER[blk]];
                if (wide) {
                    wideIntra.predict4x4(mode, plane16, offset, stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
                } else {
                    intra.predict4x4(mode, plane, offset, stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
                }
                if ((blocks & (1 << blk)) == 0) {
                    continue;
                }
                if (bypass) {
                    addBypass(comp, blk * 16, 4, offset, mode < 2 ? mode + 1 : 0);
                } else {
                    addPlane4x4(comp, blk, offset, (coded[comp] & (1 << blk)) != 0);
                }
            }
        } else if ((mbFlags & DecoderFrame.MB_I8X8) != 0) {
            for (var b8 = 0; b8 < 4; b8++) {
                var x = b8 & 1;
                var y = b8 >> 1;
                var offset = base + y * 8 * stride + x * 8;
                var hasLeft = x > 0 || ((leftRows >> (8 * y)) & 0xFF) == 0xFF;
                var hasTop = y > 0 || topOk;
                boolean hasTopLeft;
                if (x > 0 && y > 0) {
                    hasTopLeft = true;
                } else if (y > 0) {
                    hasTopLeft = ((leftRows >> 7) & 1) != 0;
                } else if (x > 0) {
                    hasTopLeft = topOk;
                } else {
                    hasTopLeft = topLeftOk;
                }
                boolean hasTopRight;
                if (b8 == 0) {
                    hasTopRight = topOk;
                } else if (b8 == 1) {
                    hasTopRight = topRightOk;
                } else {
                    hasTopRight = b8 == 2;
                }
                var mode = predModes[y * 8 + x * 2];
                if (wide) {
                    wideIntra.predict8x8(mode, plane16, offset, stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
                } else {
                    intra.predict8x8(mode, plane, offset, stride, hasLeft, hasTop, hasTopLeft, hasTopRight);
                }
                if ((blocks & (1 << b8)) == 0) {
                    continue;
                }
                if (bypass) {
                    addBypass(comp, b8 * 64, 8, offset, mode < 2 ? mode + 1 : 0);
                } else {
                    addPlane8x8(comp, b8, offset, (coded[comp] & (1 << b8)) != 0);
                }
            }
        } else {
            var leftOk = leftRows == 0xFFFF;
            if (wide) {
                wideIntra.predict16x16(intra16Mode, plane16, base, stride, leftOk, topOk, topLeftOk);
            } else {
                intra.predict16x16(intra16Mode, plane, base, stride, leftOk, topOk, topLeftOk);
            }
            if (bypass && intra16Mode < 2) {
                if (blocks != 0) {
                    addBypassRegion(comp, planeCoeffs[comp], 16, 16, true, base, intra16Mode + 1);
                }
            } else {
                addPlaneResidual4x4(comp, base);
            }
        }
    }

    /**
     * Returns the 8-bit plane of a color component.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @return the plane, or {@code null} for a frame of 16-bit samples
     */
    private byte[] plane8(int comp) {
        return comp == 0 ? lumaPlane : comp == 1 ? cbPlane : crPlane;
    }

    /**
     * Returns the 16-bit plane of a color component.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @return the plane, or {@code null} for a frame of 8-bit samples
     */
    private short[] plane16(int comp) {
        return comp == 0 ? lumaPlane16 : comp == 1 ? cbPlane16 : crPlane16;
    }

    /**
     * Returns which left neighboring sample rows of the current MBAFF macroblock are available
     * for intra prediction: rows of the left pair that are not inter coded under constrained
     * intra prediction (ITU-T H.264 8.3.1.2 with Table 6-4).
     *
     * @param constrained whether {@code constrained_intra_pred_flag} is set
     * @param chroma whether to describe the 8 chroma rows rather than the 16 luma rows
     * @return one bit per row, row 0 in bit 0
     */
    private int mbaffLeftRows(boolean constrained, boolean chroma) {
        var all = chroma ? 0xFF : 0xFFFF;
        if (!pairAvailA) {
            return 0;
        }
        if (!constrained) {
            return all;
        }
        var types = frame.mbType;
        var top = pairTop - 1;
        var bottom = top + mbWidth;
        var topOk = (types[top] & DecoderFrame.MB_INTRA) != 0;
        var bottomOk = (types[bottom] & DecoderFrame.MB_INTRA) != 0;
        var leftField = (types[top] & DecoderFrame.MB_FIELD) != 0;
        int topRows;
        int bottomRows;
        if (leftField == fieldMb) {
            return ((mbY & 1) == 0 ? topOk : bottomOk) ? all : 0;
        } else if (!fieldMb) {
            topRows = chroma ? 0x55 : 0x5555;
            bottomRows = chroma ? 0xAA : 0xAAAA;
        } else {
            topRows = chroma ? 0x0F : 0x00FF;
            bottomRows = chroma ? 0xF0 : 0xFF00;
        }
        return (topOk ? topRows : 0) | (bottomOk ? bottomRows : 0);
    }

    /**
     * Adds the 4x4-transformed residual of a color component coded like luma, including the
     * DC-only blocks of Intra16x16 macroblocks.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param base the index of the macroblock's top-left sample of the component
     */
    private void addPlaneResidual4x4(int comp, int base) {
        var full = coded[comp];
        var dc = dcOnly[comp] & ~full;
        if ((full | dc) == 0) {
            return;
        }
        var stride = comp == 0 ? lumaStride : chromaStride;
        for (var blk = 0; blk < 16; blk++) {
            var bit = 1 << blk;
            if (((full | dc) & bit) == 0) {
                continue;
            }
            var offset = base + H264Tables.BLOCK_Y[blk] * 4 * stride + H264Tables.BLOCK_X[blk] * 4;
            if (bypass) {
                addBypass(comp, blk * 16, 4, offset, 0);
            } else {
                addPlane4x4(comp, blk, offset, (full & bit) != 0);
            }
        }
    }

    /**
     * Adds the inverse-transformed residual of one 4x4 block of a color component coded like
     * luma to its prediction.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param blk the {@code luma4x4BlkIdx} of the block
     * @param offset the index of the block's top-left sample
     * @param full whether to apply the full inverse transform rather than the DC-only one
     */
    private void addPlane4x4(int comp, int blk, int offset, boolean full) {
        var coeffs = planeCoeffs[comp];
        var stride = comp == 0 ? lumaStride : chromaStride;
        if (wide) {
            var plane = plane16(comp);
            var max = comp == 0 ? lumaMax : chromaMax;
            if (full) {
                wideDsp.idct4x4Add(coeffs, blk * 16, plane, offset, stride, max);
            } else {
                wideDsp.idct4x4DcAdd(coeffs, blk * 16, plane, offset, stride, max);
            }
        } else if (full) {
            dsp.idct4x4Add(coeffs, blk * 16, plane8(comp), offset, stride);
        } else {
            dsp.idct4x4DcAdd(coeffs, blk * 16, plane8(comp), offset, stride);
        }
    }

    /**
     * Adds the inverse-transformed residual of one 8x8 block of a color component coded like
     * luma to its prediction.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param b8 the index of the 8x8 block
     * @param offset the index of the block's top-left sample
     * @param full whether to apply the full inverse transform rather than the DC-only one
     */
    private void addPlane8x8(int comp, int b8, int offset, boolean full) {
        var coeffs = planeCoeffs[comp];
        var stride = comp == 0 ? lumaStride : chromaStride;
        if (wide) {
            var plane = plane16(comp);
            var max = comp == 0 ? lumaMax : chromaMax;
            if (full) {
                wideDsp.idct8x8Add(coeffs, b8 * 64, plane, offset, stride, max);
            } else {
                wideDsp.idct8x8DcAdd(coeffs, b8 * 64, plane, offset, stride, max);
            }
        } else if (full) {
            dsp.idct8x8Add(coeffs, b8 * 64, plane8(comp), offset, stride);
        } else {
            dsp.idct8x8DcAdd(coeffs, b8 * 64, plane8(comp), offset, stride);
        }
    }

    /**
     * Adds the residual of one transform-bypass block of a color component coded like luma,
     * whose coefficient levels are its residual samples (ITU-T H.264 8.5.12 and 8.5.13), to its
     * prediction, after the intra residual transform-bypass accumulation (8.5.15) when
     * {@code dpcm} is not 0; the coefficients are cleared.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param coeffOffset the index of the block's levels in {@link #planeCoeffs}, in raster order
     * @param size the block size, 4 or 8
     * @param offset the index of the block's top-left sample
     * @param dpcm 0 for none, 1 to accumulate down the columns (vertical prediction), 2 along the
     * rows (horizontal prediction)
     */
    private void addBypass(int comp, int coeffOffset, int size, int offset, int dpcm) {
        var coeffs = planeCoeffs[comp];
        var residual = bypassScratch;
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                residual[y * 16 + x] = coeffs[coeffOffset + y * size + x];
            }
        }
        Arrays.fill(coeffs, coeffOffset, coeffOffset + size * size, 0);
        addResidualRegion(comp, size, size, offset, dpcm);
    }

    /**
     * Adds the transform-bypass residual of the 4x4 blocks of a whole macroblock area of one
     * component to its prediction, after the intra residual transform-bypass accumulation over the
     * whole area (ITU-T H.264 8.5.15) when {@code dpcm} is not 0; the coefficients are cleared.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param coeffs the levels of the area's 4x4 blocks, 16 per block in raster order
     * @param width the area width, 8 or 16
     * @param height the area height, 8 or 16
     * @param lumaOrder whether the blocks follow {@code luma4x4BlkIdx} rather than the raster
     * order of {@code chroma4x4BlkIdx}
     * @param offset the index of the area's top-left sample
     * @param dpcm 0 for none, 1 to accumulate down the columns, 2 along the rows
     */
    private void addBypassRegion(int comp, int[] coeffs, int width, int height, boolean lumaOrder, int offset,
                                 int dpcm) {
        var residual = bypassScratch;
        var blocks = (width >> 2) * (height >> 2);
        for (var blk = 0; blk < blocks; blk++) {
            var x0 = lumaOrder ? H264Tables.BLOCK_X[blk] * 4 : (blk & 1) * 4;
            var y0 = lumaOrder ? H264Tables.BLOCK_Y[blk] * 4 : (blk >> 1) * 4;
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    residual[(y0 + y) * 16 + x0 + x] = coeffs[blk * 16 + y * 4 + x];
                }
            }
        }
        Arrays.fill(coeffs, 0, blocks * 16, 0);
        addResidualRegion(comp, width, height, offset, dpcm);
    }

    /**
     * Adds the residual held in {@link #bypassScratch} to the prediction of an area of one
     * component, after accumulating it down the columns or along the rows (ITU-T H.264 8.5.15).
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param width the area width
     * @param height the area height
     * @param offset the index of the area's top-left sample
     * @param dpcm 0 for none, 1 to accumulate down the columns, 2 along the rows
     */
    private void addResidualRegion(int comp, int width, int height, int offset, int dpcm) {
        var residual = bypassScratch;
        if (dpcm == 1) {
            for (var y = 1; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    residual[y * 16 + x] += residual[(y - 1) * 16 + x];
                }
            }
        } else if (dpcm == 2) {
            for (var y = 0; y < height; y++) {
                for (var x = 1; x < width; x++) {
                    residual[y * 16 + x] += residual[y * 16 + x - 1];
                }
            }
        }
        var stride = comp == 0 ? lumaStride : chromaStride;
        var max = comp == 0 ? lumaMax : chromaMax;
        if (wide) {
            var plane = plane16(comp);
            for (var y = 0; y < height; y++) {
                var row = offset + y * stride;
                for (var x = 0; x < width; x++) {
                    plane[row + x] = (short) Math.clamp(plane[row + x] + residual[y * 16 + x], 0, max);
                }
            }
        } else {
            var plane = plane8(comp);
            for (var y = 0; y < height; y++) {
                var row = offset + y * stride;
                for (var x = 0; x < width; x++) {
                    plane[row + x] = (byte) Math.clamp((plane[row + x] & 0xFF) + residual[y * 16 + x], 0, 255);
                }
            }
        }
    }

    /**
     * Adds the chroma residual of the current macroblock.
     *
     * @param chroma the index of the macroblock's top-left chroma sample
     */
    private void addChromaResidual(int chroma) {
        if (!chromaResidual) {
            return;
        }
        if (bypass) {
            addBypassChroma(chroma);
            return;
        }
        for (var c = 0; c < 2; c++) {
            if ((chromaDcCoded & (1 << c)) != 0) {
                transformChromaDc(c);
            }
            var coeffs = c == 0 ? cbCoeffs : crCoeffs;
            if (wide) {
                addWideChroma(coeffs, c == 0 ? cbPlane16 : crPlane16, chroma, c);
                continue;
            }
            var plane = c == 0 ? cbPlane : crPlane;
            for (var half = 0; half < chromaRows >> 3; half++) {
                var first = half * 4;
                var origin = chroma + half * 8 * chromaStride;
                if (((chromaCoded >> (c * 8 + first)) & 0xF) == 0) {
                    dsp.chromaDcAdd(coeffs, first * 16, plane, origin, chromaStride);
                    continue;
                }
                for (var blk = first; blk < first + 4; blk++) {
                    var offset = chroma + (blk >> 1) * 4 * chromaStride + (blk & 1) * 4;
                    if ((chromaCoded & (1 << (c * 8 + blk))) != 0) {
                        dsp.idct4x4Add(coeffs, blk * 16, plane, offset, chromaStride);
                    } else if (coeffs[blk * 16] != 0) {
                        dsp.idct4x4DcAdd(coeffs, blk * 16, plane, offset, chromaStride);
                    }
                }
            }
        }
    }

    /**
     * Adds the transform-bypass chroma residual of the current 4:2:0 or 4:2:2 macroblock: the
     * DC levels become the DC coefficients of the 4x4 blocks they belong to ({@code dcC = c},
     * ITU-T H.264 8.5.11), and an intra macroblock with horizontal or vertical chroma prediction
     * accumulates the residual over the whole chroma block (8.5.4 and 8.5.15).
     *
     * @param chroma the index of the macroblock's top-left chroma sample
     */
    private void addBypassChroma(int chroma) {
        var intra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
        var dpcm = !intra ? 0 : chromaPredMode == 1 ? 2 : chromaPredMode == 2 ? 1 : 0;
        for (var c = 0; c < 2; c++) {
            var coeffs = c == 0 ? cbCoeffs : crCoeffs;
            var o = c * 8;
            if (chroma422) {
                // dcC is the 4x2 matrix {{d0, d2}, {d1, d5}, {d3, d6}, {d4, d7}} (equation 8-305)
                // and row i, column j is the DC of block 2 * i + j.
                coeffs[0] = chromaDc[o];
                coeffs[16] = chromaDc[o + 2];
                coeffs[32] = chromaDc[o + 1];
                coeffs[48] = chromaDc[o + 5];
                coeffs[64] = chromaDc[o + 3];
                coeffs[80] = chromaDc[o + 6];
                coeffs[96] = chromaDc[o + 4];
                coeffs[112] = chromaDc[o + 7];
            } else {
                coeffs[0] = chromaDc[o];
                coeffs[16] = chromaDc[o + 1];
                coeffs[32] = chromaDc[o + 2];
                coeffs[48] = chromaDc[o + 3];
            }
            Arrays.fill(chromaDc, o, o + 8, 0);
            addBypassRegion(c + 1, coeffs, 8, chromaRows, false, chroma, dpcm);
        }
    }

    /**
     * Adds the residual of one chroma component of the current macroblock to the prediction in
     * a 16-bit plane.
     *
     * @param coeffs the component's coefficients, DC transform already applied
     * @param plane the component's plane
     * @param chroma the index of the macroblock's top-left chroma sample
     * @param component 0 for Cb, 1 for Cr
     */
    private void addWideChroma(int[] coeffs, short[] plane, int chroma, int component) {
        for (var half = 0; half < chromaRows >> 3; half++) {
            var first = half * 4;
            var origin = chroma + half * 8 * chromaStride;
            if (((chromaCoded >> (component * 8 + first)) & 0xF) == 0) {
                wideDsp.chromaDcAdd(coeffs, first * 16, plane, origin, chromaStride, chromaMax);
                continue;
            }
            for (var blk = first; blk < first + 4; blk++) {
                var offset = chroma + (blk >> 1) * 4 * chromaStride + (blk & 1) * 4;
                if ((chromaCoded & (1 << (component * 8 + blk))) != 0) {
                    wideDsp.idct4x4Add(coeffs, blk * 16, plane, offset, chromaStride, chromaMax);
                } else if (coeffs[blk * 16] != 0) {
                    wideDsp.idct4x4DcAdd(coeffs, blk * 16, plane, offset, chromaStride, chromaMax);
                }
            }
        }
    }

    /**
     * Adds the residual of an inter macroblock after motion compensation.
     */
    private void reconstructInterResidual() {
        reconstructInterPlane(0, lumaBase);
        if (chroma444) {
            reconstructInterPlane(1, chromaBase);
            reconstructInterPlane(2, chromaBase);
        } else if (hasChroma) {
            addChromaResidual(chromaBase);
        }
    }

    /**
     * Adds the residual of one color component coded like luma of an inter macroblock after
     * motion compensation.
     *
     * @param comp the component: 0 luma, 1 Cb, 2 Cr
     * @param base the index of the macroblock's top-left sample of the component
     */
    private void reconstructInterPlane(int comp, int base) {
        if ((mbFlags & DecoderFrame.MB_T8X8) == 0) {
            addPlaneResidual4x4(comp, base);
            return;
        }
        var stride = comp == 0 ? lumaStride : chromaStride;
        var blocks = coded[comp] | dcOnly[comp];
        for (var b8 = 0; b8 < 4; b8++) {
            if ((blocks & (1 << b8)) == 0) {
                continue;
            }
            var offset = base + (b8 >> 1) * 8 * stride + (b8 & 1) * 8;
            if (bypass) {
                addBypass(comp, b8 * 64, 8, offset, 0);
            } else {
                addPlane8x8(comp, b8, offset, (coded[comp] & (1 << b8)) != 0);
            }
        }
    }

    /**
     * Returns the reference index cache of a list, for the inter predictor.
     *
     * @param list the list index
     * @return the cache
     */
    int[] refCache(int list) {
        return refCache[list];
    }

    /**
     * Returns the motion vector cache of a list, for the inter predictor.
     *
     * @param list the list index
     * @return the cache
     */
    int[] mvCache(int list) {
        return mvCache[list];
    }


    /**
     * Returns the partition shape of the current inter macroblock.
     *
     * @return 0 16x16, 1 16x8, 2 8x16, 3 8x8
     */
    int partShape() {
        return partShape;
    }

    /**
     * Returns the shape of an 8x8 partition, 0 for direct partitions.
     *
     * @param i the partition index
     * @return 0 8x8, 1 8x4, 2 4x8, 3 4x4
     */
    int subShape(int i) {
        return (directMask & (1 << i)) != 0 ? 0 : subShape[i];
    }

    /**
     * Returns whether the current macroblock's motion is uniform over its whole area, as flagged
     * by {@link #storeMotion()}, so a single 16x16 prediction produces its samples.
     *
     * @return {@code true} when every 4x4 block has the same references and motion vectors
     */
    boolean uniformMotion() {
        return (mbFlags & DecoderFrame.MB_UNIFORM) != 0;
    }

    /**
     * Returns whether direct 8x8 partitions must be compensated per 4x4 block.
     *
     * @param i the partition index
     * @return {@code true} for direct partitions without {@code direct_8x8_inference_flag}
     */
    boolean directPer4x4(int i) {
        return (directMask & (1 << i)) != 0 && !header.sps.direct8x8Inference;
    }
}
