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
     * The level scale factors of the intra and inter luma 8x8 scaling lists, indexed
     * {@code [list][qP % 6][raster]}.
     */
    private final int[][][] levelScale8 = new int[2][6][64];

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
     * The implicit bi-predictive list 0 weights, indexed {@code refIdxL0 * 32 + refIdxL1}.
     */
    private final int[] implicitWeight = new int[32 * 32];

    /**
     * The temporal direct scale factor of each list 0 reference, or {@code 9999} when the motion
     * vector is copied unscaled.
     */
    private final int[] directScale = new int[32];

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
     * The Cb coefficients: four 4x4 blocks.
     */
    private final int[] cbCoeffs = new int[64];

    /**
     * The Cr coefficients: four 4x4 blocks.
     */
    private final int[] crCoeffs = new int[64];

    /**
     * The Intra16x16 DC levels in raster order.
     */
    private final int[] lumaDc = new int[16];

    /**
     * The chroma DC levels of Cb then Cr.
     */
    private final int[] chromaDc = new int[8];

    /**
     * The luma blocks with coefficients: bits by {@code luma4x4BlkIdx}, or by 8x8 block with the
     * 8x8 transform.
     */
    private int lumaCoded;

    /**
     * The luma blocks whose only non-zero coefficient is the DC one: 4x4 blocks by block index,
     * or 8x8 blocks by quarter index in a macroblock with the 8x8 transform. A block in both this
     * set and {@link #lumaCoded} takes the full inverse transform.
     */
    private int lumaDcOnly;

    /**
     * The chroma AC blocks with coefficients: bits 0 to 3 Cb, 4 to 7 Cr.
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
     */
    void startPicture(DecoderFrame frame, PictureState pic, H264Dsp dsp) {
        this.frame = frame;
        this.pic = pic;
        this.dsp = dsp;
        this.mbWidth = frame.mbWidth;
        this.mbCount = frame.mbWidth * frame.mbHeight;
        this.mbAddr = -2;
        this.lumaPlane = frame.luma;
        this.cbPlane = frame.cb;
        this.crPlane = frame.cr;
        this.lumaStride = frame.lumaStride;
        this.chromaStride = frame.chromaStride;
        this.hasChroma = frame.hasChroma;
        inter.startPicture(frame, dsp);
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
        // Reconstruction expects zeroed coefficient buffers; a slice aborted by corrupt data
        // may have left parsed but unconsumed coefficients behind.
        Arrays.fill(lumaCoeffs, 0);
        Arrays.fill(cbCoeffs, 0);
        Arrays.fill(crCoeffs, 0);
        Arrays.fill(lumaDc, 0);
        Arrays.fill(chromaDc, 0);
        prepareScaling();
        prepareWeights();
        prepareDirect();
        if (!bSlice) {
            clearList(1);
        }
        inter.startSlice(header, lists, weightMode, implicitWeight);
        var filter = header.disableDeblockingFilterIdc | ((header.filterOffsetA + 12) << 2)
                | ((header.filterOffsetB + 12) << 8);
        var addr = header.firstMbInSlice;
        if (addr >= mbCount) {
            throw new MediaException.Malformed("first_mb_in_slice " + addr + " out of range");
        }
        bits.init(rbsp, offset, length);
        if (cabacMode) {
            var aligned = (int) ((header.dataBitPosition + 7) >>> 3);
            cabac.init(rbsp, offset + aligned, offset + length);
            CabacTables.initContexts(cabac.states, iSlice ? 0 : header.cabacInitIdc + 1, header.sliceQp);
            decodeCabacSlice(addr, filter, onMacroblock);
        } else {
            skipTo(bits, header.dataBitPosition);
            var stopBit = NalUnits.stopBitPosition(rbsp, offset, length);
            decodeCavlcSlice(addr, filter, stopBit, onMacroblock);
        }
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
     * Sets up the addressing and neighbor availability of a macroblock.
     *
     * @param addr the macroblock address
     * @param filter the packed deblocking parameters of the slice
     */
    private void beginMacroblock(int addr, int filter) {
        if (addr == mbAddr + 1 && mbX + 1 < mbWidth) {
            mbX++;
        } else {
            mbX = addr % mbWidth;
            mbY = addr / mbWidth;
        }
        mbAddr = addr;
        var slices = pic.sliceNum;
        var slice = sliceNum;
        availA = mbX > 0 && slices[addr - 1] == slice;
        availB = mbY > 0 && slices[addr - mbWidth] == slice;
        availC = mbY > 0 && mbX + 1 < mbWidth && slices[addr - mbWidth + 1] == slice;
        availD = mbY > 0 && mbX > 0 && slices[addr - mbWidth - 1] == slice;
        pic.filter[addr] = filter;
        mbFlags = 0;
        cbp = 0;
        directMask = 0;
        lumaCoded = 0;
        lumaDcOnly = 0;
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
        frame.mbType[mbAddr] = mbFlags;
        frame.decodedMbs++;
        if (onMacroblock != null) {
            onMacroblock.decoded(mbAddr);
        }
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
        for (var list = 0; list < 2; list++) {
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
     * Selects the weighted prediction mode of the slice and computes implicit weights.
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
        var currentPoc = task.currentPoc;
        var pocs0 = task.refPoc[0];
        var pocs1 = task.refPoc[1];
        var marks0 = task.refMarking[0];
        var marks1 = task.refMarking[1];
        for (var i = 0; i < refCount[0]; i++) {
            for (var j = 0; j < refCount[1]; j++) {
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
                implicitWeight[i * 32 + j] = 64 - weight1;
            }
        }
    }

    /**
     * Prepares the direct prediction state of a B slice: the co-located picture and the temporal
     * scale factors.
     */
    private void prepareDirect() {
        colocated = null;
        if (!bSlice) {
            return;
        }
        colocated = refList[1][0];
        colocatedShortTerm = task.refMarking[1][0] == DecoderFrame.SHORT_TERM;
        if (header.directSpatialMvPred) {
            return;
        }
        var currentPoc = task.currentPoc;
        var poc1 = task.refPoc[1][0];
        for (var i = 0; i < refCount[0]; i++) {
            var poc0 = task.refPoc[0][i];
            var td = Math.clamp(poc1 - poc0, -128, 127);
            if (td == 0 || task.refMarking[0][i] == DecoderFrame.LONG_TERM) {
                directScale[i] = 9999;
            } else {
                var tb = Math.clamp(currentPoc - poc0, -128, 127);
                var tx = (16384 + Math.abs(td / 2)) / td;
                directScale[i] = Math.clamp((tb * tx + 32) >> 6, -1024, 1023);
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
        if (availA && (types[mbAddr - 1] & DecoderFrame.MB_SKIP) == 0) {
            ctx++;
        }
        if (availB && (types[mbAddr - mbWidth] & DecoderFrame.MB_SKIP) == 0) {
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
        inter.predictMacroblock(this);
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
     * Clears the 24 coefficient counts of a macroblock with three 64-bit stores.
     *
     * @param addr the macroblock address
     */
    private void clearNnz(int addr) {
        var nnz = pic.nnz;
        var at = addr * PictureState.NNZ_STRIDE;
        LONG_LE.set(nnz, at, 0L);
        LONG_LE.set(nnz, at + 8, 0L);
        LONG_LE.set(nnz, at + 16, 0L);
    }

    /**
     * Stores the chroma quantization parameters of the current macroblock for the deblocking
     * filter.
     */
    private void storeChromaQp() {
        pic.qpCb[mbAddr] = (byte) chromaQp(qp, pps.chromaQpIndexOffset);
        pic.qpCr[mbAddr] = (byte) chromaQp(qp, pps.secondChromaQpIndexOffset);
    }

    /**
     * Returns {@code QPc} for a luma quantization parameter and chroma offset (ITU-T H.264
     * 8.5.8, 8-bit video).
     *
     * @param lumaQp the luma quantization parameter
     * @param offset the chroma quantization parameter offset
     * @return the chroma quantization parameter
     */
    static int chromaQp(int lumaQp, int offset) {
        return H264Tables.CHROMA_QP[Math.clamp(lumaQp + offset, 0, 51)];
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
            var chromaCbp = ((type - 1) >> 2) % 3;
            cbp = (type >= 13 ? 15 : 0) | (chromaCbp << 4);
        }
        if (hasChroma) {
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
        if (availA && (!constrained || (types[mbAddr - 1] & DecoderFrame.MB_INTRA) != 0)) {
            var base = (mbAddr - 1) * 16;
            var coded = (types[mbAddr - 1] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = coded ? modes[base + y * 4 + 3] : 2;
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = -1;
            }
        }
        if (availB && (!constrained || (types[mbAddr - mbWidth] & DecoderFrame.MB_INTRA) != 0)) {
            var base = (mbAddr - mbWidth) * 16 + 12;
            var coded = (types[mbAddr - mbWidth] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) != 0;
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
        var needed = 256 + (hasChroma ? 128 : 0);
        if (position + needed > bits.end()) {
            throw new MediaException.Malformed("truncated I_PCM macroblock");
        }
        var luma = frame.lumaOrigin + mbY * 16 * lumaStride + mbX * 16;
        for (var y = 0; y < 16; y++) {
            System.arraycopy(data, position + y * 16, lumaPlane, luma + y * lumaStride, 16);
        }
        position += 256;
        if (hasChroma) {
            var chroma = frame.chromaOrigin + mbY * 8 * chromaStride + mbX * 8;
            for (var y = 0; y < 8; y++) {
                System.arraycopy(data, position + y * 8, cbPlane, chroma + y * chromaStride, 8);
            }
            position += 64;
            for (var y = 0; y < 8; y++) {
                System.arraycopy(data, position + y * 8, crPlane, chroma + y * chromaStride, 8);
            }
            position += 64;
        }
        if (cabacMode) {
            cabac.init(data, position, bits.end());
        } else {
            bits.seekToByte(position);
        }
        var addr = mbAddr;
        pic.qp[addr] = 0;
        pic.qpCb[addr] = (byte) chromaQp(0, pps.chromaQpIndexOffset);
        pic.qpCr[addr] = (byte) chromaQp(0, pps.secondChromaQpIndexOffset);
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
        if (hasChroma) {
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
            pic.qp[mbAddr] = (byte) qp;
            storeChromaQp();
            clearResidualState();
            return;
        }
        var delta = cabacMode ? decodeQpDeltaCabac() : bits.readSe();
        if (delta < -26 || delta > 25) {
            throw new MediaException.Malformed("mb_qp_delta " + delta + " out of range");
        }
        lastQpDeltaNonZero = delta != 0;
        qp = (qp + delta + 52) % 52;
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
     * Returns the luma list index (0 intra, 3 inter) of the current macroblock's scaling lists.
     *
     * @return the 4x4 scaling list index of luma
     */
    private int lumaList() {
        return (mbFlags & DecoderFrame.MB_INTRA) != 0 ? 0 : 3;
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
     * Parses the residual of the current macroblock with CAVLC (ITU-T H.264 7.3.5.3).
     */
    private void residualCavlc() {
        var addr = mbAddr;
        var nnz = pic.nnz;
        var base = addr * PictureState.NNZ_STRIDE;
        fillNnzCacheCavlc();
        var intra16 = (mbFlags & DecoderFrame.MB_I16X16) != 0;
        var transform8x8 = (mbFlags & DecoderFrame.MB_T8X8) != 0;
        var qpDiv = qp / 6;
        var qpMod = qp % 6;
        var list = lumaList();
        var dequant4 = levelScale4[list][qpMod];
        var shift4 = scaleShift(qpDiv, 4);
        var cache = nnzCache;
        if (intra16) {
            var nC = lumaNc(CACHE_ORIGIN);
            var count = cavlc.residualBlock(bits, nC, 16, lumaDc, 0, H264Tables.ZIGZAG_4X4, 0, 1, 0, null, 0);
            if (count > 0) {
                transformLumaDc(dequant4[0], qpDiv);
            }
        }
        var nonZero = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbp & (1 << b8)) == 0) {
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[blk]]] = 0;
                }
                continue;
            }
            if (transform8x8) {
                var dequant8 = levelScale8[list == 0 ? 0 : 1][qpMod];
                var shift8 = scaleShift(qpDiv, 6);
                var total = 0;
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    var c = CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[blk]];
                    var nC = lumaNc(c);
                    var count = cavlc.residualBlock(bits, nC, 16, lumaCoeffs, b8 * 64, H264Tables.ZIGZAG_8X8,
                            0, 4, i, dequant8, shift8);
                    cache[c] = count;
                    total += count;
                }
                if (total != 0) {
                    if (total == 1 && lumaCoeffs[b8 * 64] != 0) {
                        lumaDcOnly |= 1 << b8;
                    } else {
                        lumaCoded |= 1 << b8;
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
                        count = cavlc.residualBlock(bits, nC, 15, lumaCoeffs, blk * 16, H264Tables.ZIGZAG_4X4,
                                1, 1, 0, dequant4, shift4);
                    } else {
                        count = cavlc.residualBlock(bits, nC, 16, lumaCoeffs, blk * 16, H264Tables.ZIGZAG_4X4,
                                0, 1, 0, dequant4, shift4);
                    }
                    cache[c] = count;
                    if (count != 0) {
                        if (count == 1 && !intra16 && lumaCoeffs[blk * 16] != 0) {
                            lumaDcOnly |= 1 << blk;
                        } else {
                            lumaCoded |= 1 << blk;
                        }
                        nonZero |= 1 << raster;
                    }
                }
            }
        }
        for (var i = 0; i < 16; i++) {
            nnz[base + i] = (byte) cache[CACHE_OF_RASTER[i]];
        }
        pic.nonZero[addr] = nonZero;
        if (hasChroma) {
            residualChromaCavlc(base);
        } else {
            for (var i = 16; i < 24; i++) {
                nnz[base + i] = 0;
            }
        }
        pic.cbp[addr] = cbp;
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
            for (var i = 16; i < 24; i++) {
                nnz[base + i] = 0;
            }
            return;
        }
        chromaResidual = true;
        for (var c = 0; c < 2; c++) {
            var count = cavlc.residualBlock(bits, -1, 4, chromaDc, c * 4, H264Tables.CHROMA_DC_SCAN, 0, 1, 0, null, 0);
            if (count != 0) {
                chromaDcCoded |= 1 << c;
            }
        }
        if ((chroma & 2) != 0) {
            var intra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
            for (var c = 0; c < 2; c++) {
                var list = (intra ? 1 : 4) + c;
                var chromaQpValue = c == 0 ? pic.qpCb[mbAddr] : pic.qpCr[mbAddr];
                var dequant = levelScale4[list][chromaQpValue % 6];
                var shift = scaleShift(chromaQpValue / 6, 4);
                var coeffs = c == 0 ? cbCoeffs : crCoeffs;
                for (var blk = 0; blk < 4; blk++) {
                    var nC = chromaNc(c, blk);
                    var count = cavlc.residualBlock(bits, nC, 15, coeffs, blk * 16, H264Tables.ZIGZAG_4X4,
                            1, 1, 0, dequant, shift);
                    nnz[base + 16 + c * 4 + blk] = (byte) count;
                    if (count != 0) {
                        chromaCoded |= 1 << (c * 4 + blk);
                    }
                }
            }
        } else {
            for (var i = 16; i < 24; i++) {
                nnz[base + i] = 0;
            }
        }
    }


    /**
     * Fills the luma coefficient count cache from the neighboring macroblocks for CAVLC
     * {@code nC} derivation; unavailable neighbors are marked {@code -1}.
     */
    private void fillNnzCacheCavlc() {
        var cache = nnzCache;
        var nnz = pic.nnz;
        if (availA) {
            var base = (mbAddr - 1) * PictureState.NNZ_STRIDE;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[base + y * 4 + 3];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = -1;
            }
        }
        if (availB) {
            var base = (mbAddr - mbWidth) * PictureState.NNZ_STRIDE + 12;
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
     * @param blk the chroma 4x4 block index, 0 to 3
     * @return the {@code nC}
     */
    private int chromaNc(int component, int blk) {
        var nnz = pic.nnz;
        var base = mbAddr * PictureState.NNZ_STRIDE + 16 + component * 4;
        var x = blk & 1;
        var y = blk >> 1;
        var a = -1;
        var b = -1;
        if (x > 0) {
            a = nnz[base + blk - 1];
        } else if (availA) {
            a = nnz[base - PictureState.NNZ_STRIDE + y * 2 + 1];
        }
        if (y > 0) {
            b = nnz[base + blk - 2];
        } else if (availB) {
            b = nnz[base - mbWidth * PictureState.NNZ_STRIDE + 2 + x];
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
     * Applies the inverse Intra16x16 DC Hadamard transform and scaling, distributing the DC
     * values to the sixteen 4x4 blocks (ITU-T H.264 8.5.10).
     *
     * @param levelScale the level scale factor {@code LevelScale4x4(qP % 6, 0, 0)}
     * @param qpDiv {@code qP / 6}
     */
    private void transformLumaDc(int levelScale, int qpDiv) {
        var c = lumaDc;
        var raster = dcScratch;
        for (var i = 0; i < 16; i++) {
            raster[H264Tables.ZIGZAG_4X4[i]] = c[i];
            c[i] = 0;
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
                lumaCoeffs[blk * 16] = dc;
                lumaDcOnly |= 1 << blk;
            }
        }
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
        var o = component * 4;
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
        var chromaQpValue = component == 0 ? pic.qpCb[mbAddr] : pic.qpCr[mbAddr];
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
        inter.predictMacroblock(this);
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
        if (refC == UNAVAILABLE) {
            cIdx = c - 9;
            refC = rc[cIdx];
        }
        var mvA = mc[c - 1];
        if (refB == UNAVAILABLE && refC == UNAVAILABLE && refA != UNAVAILABLE) {
            return mvA;
        }
        var mvB = mc[c - 8];
        var mvC = mc[cIdx];
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
            colocated.awaitDecodedRows(mbY + 1);
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
        colocated.awaitDecodedRows(mbY + 1);
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
        if (!directZero && (ref0 == 0 || ref1 == 0) && colocatedShortTerm
                && (colocated.mbType[mbAddr] & DecoderFrame.MB_INTRA) == 0) {
            var zeros = (colocatedZero(0, 0) ? 1 : 0) + (colocatedZero(1, 3) ? 1 : 0)
                    + (colocatedZero(2, 12) ? 1 : 0) + (colocatedZero(3, 15) ? 1 : 0);
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
        var col = colocated;
        var colIntra = (col.mbType[mbAddr] & DecoderFrame.MB_INTRA) != 0;
        if (header.sps.direct8x8Inference) {
            var colZero = !directZero && !colIntra && colocatedShortTerm && colocatedZero(i, cornerRaster(i));
            fillBlock(0, c8, 2, 2, ref0, ref0 < 0 || directZero || (ref0 == 0 && colZero) ? 0 : mv0);
            fillBlock(1, c8, 2, 2, ref1, ref1 < 0 || directZero || (ref1 == 0 && colZero) ? 0 : mv1);
            return;
        }
        for (var j = 0; j < 4; j++) {
            var c = c8 + (j & 1) + (j >> 1) * 8;
            var raster = rasterOfCache(c);
            var colZero = !directZero && !colIntra && colocatedShortTerm && colocatedZero(i, raster);
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
     * Returns whether the co-located block is "not moving" for spatial direct prediction: its
     * reference index is 0 and both motion vector components lie in {@code [-1, 1]}.
     *
     * @param partition the 8x8 partition of the co-located block
     * @param raster the raster 4x4 index of the co-located block
     * @return the {@code colZeroFlag} without the short-term condition
     */
    private boolean colocatedZero(int partition, int raster) {
        var col = colocated;
        var r0 = col.ref[0][mbAddr * 4 + partition];
        int ref;
        int mv;
        if (r0 >= 0) {
            ref = r0;
            mv = col.mv[0][mbAddr * 16 + raster];
        } else {
            ref = col.ref[1][mbAddr * 4 + partition];
            mv = col.mv[1][mbAddr * 16 + raster];
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
        var col = colocated;
        var colIntra = (col.mbType[mbAddr] & DecoderFrame.MB_INTRA) != 0;
        var r0 = col.ref[0][mbAddr * 4 + i];
        var colList = r0 >= 0 ? 0 : 1;
        var colRef = colIntra ? -1 : col.ref[colList][mbAddr * 4 + i];
        var refL0 = colRef < 0 ? 0 : mapColocatedToList0(col.refId[colList][mbAddr * 4 + i]);
        refIdx[0][i] = refL0;
        refIdx[1][i] = 0;
        var scale = directScale[refL0];
        if (header.sps.direct8x8Inference) {
            var mvCol = colRef < 0 ? 0 : col.mv[colList][mbAddr * 16 + cornerRaster(i)];
            fillTemporal(c8, 2, refL0, scale, mvCol);
            return;
        }
        for (var j = 0; j < 4; j++) {
            var c = c8 + (j & 1) + (j >> 1) * 8;
            var mvCol = colRef < 0 ? 0 : col.mv[colList][mbAddr * 16 + rasterOfCache(c)];
            fillTemporal(c, 1, refL0, scale, mvCol);
        }
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
     * Returns the lowest list 0 index that refers to a picture ({@code MapColToList0}).
     *
     * @param pictureId the identity of the picture
     * @return the index, or 0 when the picture is not in list 0
     */
    private int mapColocatedToList0(int pictureId) {
        var list = refList[0];
        for (var i = 0; i < refCount[0]; i++) {
            if (list[i].id == pictureId) {
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
        var rc = refCache[list];
        var mc = mvCache[list];
        var refs = frame.ref[list];
        var mvs = frame.mv[list];
        if (availA) {
            var a = mbAddr - 1;
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
            var b = mbAddr - mbWidth;
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
            var c = mbAddr - mbWidth + 1;
            rc[5] = refs[c * 4 + 2];
            mc[5] = mvs[c * 16 + 12];
        } else {
            rc[5] = UNAVAILABLE;
            mc[5] = 0;
        }
        if (availD) {
            var d = mbAddr - mbWidth - 1;
            rc[0] = refs[d * 4 + 3];
            mc[0] = mvs[d * 16 + 15];
        } else {
            rc[0] = UNAVAILABLE;
            mc[0] = 0;
        }
    }

    /**
     * Fills the neighbor entries of the motion caches of a list that the prediction of a
     * macroblock as one 16x16 partition reads: the left neighbor of the top-left block, the
     * block above it, and the blocks above-right and above-left of the macroblock.
     *
     * @param list the list index
     */
    private void fillCornerMotionCache(int list) {
        var rc = refCache[list];
        var mc = mvCache[list];
        var refs = frame.ref[list];
        var mvs = frame.mv[list];
        if (availA) {
            var a = mbAddr - 1;
            rc[8] = refs[a * 4 + 1];
            mc[8] = mvs[a * 16 + 3];
        } else {
            rc[8] = UNAVAILABLE;
            mc[8] = 0;
        }
        if (availB) {
            var b = mbAddr - mbWidth;
            rc[1] = refs[b * 4 + 2];
            mc[1] = mvs[b * 16 + 12];
        } else {
            rc[1] = UNAVAILABLE;
            mc[1] = 0;
        }
        if (availC) {
            var c = mbAddr - mbWidth + 1;
            rc[5] = refs[c * 4 + 2];
            mc[5] = mvs[c * 16 + 12];
        } else {
            rc[5] = UNAVAILABLE;
            mc[5] = 0;
        }
        if (availD) {
            var d = mbAddr - mbWidth - 1;
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
                var a = mbAddr - 1;
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
                var b = mbAddr - mbWidth;
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
                var a = mbAddr - 1;
                var type = types[a];
                md[8] = (type & (DecoderFrame.MB_SKIP | DecoderFrame.MB_INTRA)) == 0 ? stored[a * 16 + 3] : 0;
                rf[8] = refContext(type, 1, refs[a * 4 + 1]);
            } else {
                md[8] = 0;
                rf[8] = 0;
            }
            if (availB) {
                var b = mbAddr - mbWidth;
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
            } else if (r >= refCount[list]) {
                throw new MediaException.Malformed("reference index " + r + " out of range");
            } else {
                id = refList[list][r].id;
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
            var entries = refList[list];
            for (var p = 0; p < 4; p++) {
                var r = rc[CACHE_ORIGIN + (p & 1) * 2 + (p >> 1) * 16];
                if (r < 0 || r >= refCount[list]) {
                    if (r >= 0) {
                        throw new MediaException.Malformed("reference index " + r + " out of range");
                    }
                    refs[addr * 4 + p] = -1;
                    ids[addr * 4 + p] = -1;
                } else {
                    refs[addr * 4 + p] = (byte) r;
                    ids[addr * 4 + p] = entries[r].id;
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
        var count = refCount[list];
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
            if (availA && (types[mbAddr - 1] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) == 0) {
                inc++;
            }
            if (availB && (types[mbAddr - mbWidth] & (DecoderFrame.MB_I4X4 | DecoderFrame.MB_I8X8)) == 0) {
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
        if (availA && (types[mbAddr - 1] & (DecoderFrame.MB_SKIP | DecoderFrame.MB_DIRECT16)) == 0) {
            inc++;
        }
        if (availB && (types[mbAddr - mbWidth] & (DecoderFrame.MB_SKIP | DecoderFrame.MB_DIRECT16)) == 0) {
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
        if (availA && (types[mbAddr - 1] & DecoderFrame.MB_T8X8) != 0) {
            inc++;
        }
        if (availB && (types[mbAddr - mbWidth] & DecoderFrame.MB_T8X8) != 0) {
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
            var a = mbAddr - 1;
            if ((types[a] & DecoderFrame.MB_INTRA) != 0 && (types[a] & DecoderFrame.MB_PCM) == 0 && modes[a] != 0) {
                inc++;
            }
        }
        if (availB) {
            var b = mbAddr - mbWidth;
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
            var a = mbAddr - 1;
            leftCbp = (types[a] & DecoderFrame.MB_PCM) != 0 ? -2 : cbps[a];
        }
        if (availB) {
            var b = mbAddr - mbWidth;
            topCbp = (types[b] & DecoderFrame.MB_PCM) != 0 ? -2 : cbps[b];
        }
        var luma = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var x = b8 & 1;
            var y = b8 >> 1;
            int condA;
            if (x == 1) {
                condA = (luma & (1 << (b8 - 1))) == 0 ? 1 : 0;
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
        if (!hasChroma) {
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
     * Parses the residual of the current macroblock with CABAC (ITU-T H.264 7.3.5.3 and
     * 9.3.3.1.1.9).
     */
    private void residualCabac() {
        var addr = mbAddr;
        var nnz = pic.nnz;
        var base = addr * PictureState.NNZ_STRIDE;
        var intra16 = (mbFlags & DecoderFrame.MB_I16X16) != 0;
        var transform8x8 = (mbFlags & DecoderFrame.MB_T8X8) != 0;
        var isIntra = (mbFlags & DecoderFrame.MB_INTRA) != 0;
        fillCbfCache(isIntra);
        var qpDiv = qp / 6;
        var qpMod = qp % 6;
        var list = lumaList();
        var dequant4 = levelScale4[list][qpMod];
        var shift4 = scaleShift(qpDiv, 4);
        var cache = nnzCache;
        var cbpFlags = cbp;
        if (intra16) {
            var inc = dcCbfCondition(isIntra, 6);
            if (cabac.decision(CabacTables.CBF_OFFSET[0] + inc) != 0) {
                cbpFlags |= 1 << 6;
                residualBlockCabac(0, lumaDc, 0, H264Tables.ZIGZAG_4X4, 0, 16, null, 0, true);
                transformLumaDc(dequant4[0], qpDiv);
            }
        }
        var nonZero = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbp & (1 << b8)) == 0) {
                for (var i = 0; i < 4; i++) {
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[b8 * 4 + i]]] = 0;
                }
                continue;
            }
            if (transform8x8) {
                var dequant8 = levelScale8[list == 0 ? 0 : 1][qpMod];
                var shift8 = scaleShift(qpDiv, 6);
                var count = residualBlockCabac(5, lumaCoeffs, b8 * 64, H264Tables.ZIGZAG_8X8, 0, 64, dequant8, shift8,
                        false);
                if (count == 1 && lumaCoeffs[b8 * 64] != 0) {
                    lumaDcOnly |= 1 << b8;
                } else {
                    lumaCoded |= 1 << b8;
                }
                nonZero |= 0x33 << ((b8 & 1) * 2 + (b8 >> 1) * 8);
                for (var i = 0; i < 4; i++) {
                    cache[CACHE_OF_RASTER[H264Tables.BLOCK_RASTER[b8 * 4 + i]]] = 1;
                }
                continue;
            }
            for (var i = 0; i < 4; i++) {
                var blk = b8 * 4 + i;
                var raster = H264Tables.BLOCK_RASTER[blk];
                var c = CACHE_OF_RASTER[raster];
                var category = intra16 ? 1 : 2;
                var inc = cache[c - 1] + 2 * cache[c - 8];
                if (cabac.decision(CabacTables.CBF_OFFSET[category] + inc) == 0) {
                    cache[c] = 0;
                    continue;
                }
                cache[c] = 1;
                if (intra16) {
                    residualBlockCabac(1, lumaCoeffs, blk * 16, H264Tables.ZIGZAG_4X4, 1, 15, dequant4, shift4, false);
                    lumaCoded |= 1 << blk;
                } else {
                    var count = residualBlockCabac(2, lumaCoeffs, blk * 16, H264Tables.ZIGZAG_4X4, 0, 16, dequant4,
                            shift4, false);
                    if (count == 1 && lumaCoeffs[blk * 16] != 0) {
                        lumaDcOnly |= 1 << blk;
                    } else {
                        lumaCoded |= 1 << blk;
                    }
                }
                nonZero |= 1 << raster;
            }
        }
        for (var i = 0; i < 16; i++) {
            nnz[base + i] = (byte) cache[CACHE_OF_RASTER[i]];
        }
        pic.nonZero[addr] = nonZero;
        if (hasChroma && (cbp >> 4) != 0) {
            chromaResidual = true;
            for (var c = 0; c < 2; c++) {
                var inc = dcCbfCondition(isIntra, 7 + c);
                if (cabac.decision(CabacTables.CBF_OFFSET[3] + inc) != 0) {
                    cbpFlags |= 1 << (7 + c);
                    residualBlockCabac(3, chromaDc, c * 4, H264Tables.CHROMA_DC_SCAN, 0, 4, null, 0, true);
                    chromaDcCoded |= 1 << c;
                }
            }
            if ((cbp & 0x20) != 0) {
                for (var c = 0; c < 2; c++) {
                    var chromaList = (isIntra ? 1 : 4) + c;
                    var chromaQpValue = c == 0 ? pic.qpCb[addr] : pic.qpCr[addr];
                    var dequant = levelScale4[chromaList][chromaQpValue % 6];
                    var shift = scaleShift(chromaQpValue / 6, 4);
                    var coeffs = c == 0 ? cbCoeffs : crCoeffs;
                    for (var blk = 0; blk < 4; blk++) {
                        var inc = chromaCbfCondition(isIntra, c, blk);
                        if (cabac.decision(CabacTables.CBF_OFFSET[4] + inc) == 0) {
                            nnz[base + 16 + c * 4 + blk] = 0;
                            continue;
                        }
                        nnz[base + 16 + c * 4 + blk] = 1;
                        residualBlockCabac(4, coeffs, blk * 16, H264Tables.ZIGZAG_4X4, 1, 15, dequant, shift, false);
                        chromaCoded |= 1 << (c * 4 + blk);
                    }
                }
            } else {
                for (var i = 16; i < 24; i++) {
                    nnz[base + i] = 0;
                }
            }
        } else {
            for (var i = 16; i < 24; i++) {
                nnz[base + i] = 0;
            }
        }
        pic.cbp[addr] = cbpFlags;
    }

    /**
     * Fills the luma coded block flag cache from the neighboring macroblocks for CABAC
     * (ITU-T H.264 9.3.3.1.1.9).
     *
     * @param isIntra whether the current macroblock is intra coded
     */
    private void fillCbfCache(boolean isIntra) {
        var cache = nnzCache;
        var nnz = pic.nnz;
        var unavailable = isIntra ? 1 : 0;
        if (availA) {
            var base = (mbAddr - 1) * PictureState.NNZ_STRIDE;
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = nnz[base + y * 4 + 3];
            }
        } else {
            for (var y = 0; y < 4; y++) {
                cache[8 + y * 8] = unavailable;
            }
        }
        if (availB) {
            var base = (mbAddr - mbWidth) * PictureState.NNZ_STRIDE + 12;
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
     * @param bit the bit of the DC flag in {@link PictureState#cbp}: 6 luma, 7 Cb, 8 Cr
     * @return the increment, 0 to 3
     */
    private int dcCbfCondition(boolean isIntra, int bit) {
        var cbps = pic.cbp;
        var unavailable = isIntra ? 1 : 0;
        var a = availA ? (cbps[mbAddr - 1] >> bit) & 1 : unavailable;
        var b = availB ? (cbps[mbAddr - mbWidth] >> bit) & 1 : unavailable;
        return a + 2 * b;
    }

    /**
     * Returns the context increment of a chroma AC {@code coded_block_flag}.
     *
     * @param isIntra whether the current macroblock is intra coded
     * @param component 0 for Cb, 1 for Cr
     * @param blk the chroma block index, 0 to 3
     * @return the increment, 0 to 3
     */
    private int chromaCbfCondition(boolean isIntra, int component, int blk) {
        var nnz = pic.nnz;
        var base = mbAddr * PictureState.NNZ_STRIDE + 16 + component * 4;
        var x = blk & 1;
        var y = blk >> 1;
        var unavailable = isIntra ? 1 : 0;
        int a;
        if (x > 0) {
            a = nnz[base + blk - 1];
        } else if (availA) {
            a = nnz[base - PictureState.NNZ_STRIDE + y * 2 + 1];
        } else {
            a = unavailable;
        }
        int b;
        if (y > 0) {
            b = nnz[base + blk - 2];
        } else if (availB) {
            b = nnz[base - mbWidth * PictureState.NNZ_STRIDE + 2 + x];
        } else {
            b = unavailable;
        }
        return a + 2 * b;
    }

    /**
     * Decodes the significance map and levels of one CABAC residual block (ITU-T H.264
     * 7.3.5.3.3) and stores its coefficients, dequantized when {@code dequant} is given.
     *
     * @param category the {@code ctxBlockCat}, 0 to 5
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
        var count = cabac.residualBlock(category, maxCoeff, indices, levels);
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
        var luma = frame.lumaOrigin + mbY * 16 * lumaStride + mbX * 16;
        var constrained = pps.constrainedIntraPred;
        var types = frame.mbType;
        var leftOk = availA && (!constrained || (types[mbAddr - 1] & DecoderFrame.MB_INTRA) != 0);
        var topOk = availB && (!constrained || (types[mbAddr - mbWidth] & DecoderFrame.MB_INTRA) != 0);
        var topRightOk = availC && (!constrained || (types[mbAddr - mbWidth + 1] & DecoderFrame.MB_INTRA) != 0);
        var topLeftOk = availD && (!constrained || (types[mbAddr - mbWidth - 1] & DecoderFrame.MB_INTRA) != 0);
        if ((mbFlags & DecoderFrame.MB_I4X4) != 0) {
            for (var blk = 0; blk < 16; blk++) {
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var offset = luma + y * 4 * lumaStride + x * 4;
                var hasLeft = x > 0 || leftOk;
                var hasTop = y > 0 || topOk;
                boolean hasTopLeft;
                if (x > 0 && y > 0) {
                    hasTopLeft = true;
                } else if (y > 0) {
                    hasTopLeft = leftOk;
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
                intra.predict4x4(predModes[H264Tables.BLOCK_RASTER[blk]], lumaPlane, offset, lumaStride,
                        hasLeft, hasTop, hasTopLeft, hasTopRight);
                if ((lumaCoded & (1 << blk)) != 0) {
                    dsp.idct4x4Add(lumaCoeffs, blk * 16, lumaPlane, offset, lumaStride);
                } else if ((lumaDcOnly & (1 << blk)) != 0) {
                    dsp.idct4x4DcAdd(lumaCoeffs, blk * 16, lumaPlane, offset, lumaStride);
                }
            }
        } else if ((mbFlags & DecoderFrame.MB_I8X8) != 0) {
            for (var b8 = 0; b8 < 4; b8++) {
                var x = b8 & 1;
                var y = b8 >> 1;
                var offset = luma + y * 8 * lumaStride + x * 8;
                var hasLeft = x > 0 || leftOk;
                var hasTop = y > 0 || topOk;
                boolean hasTopLeft;
                if (x > 0 && y > 0) {
                    hasTopLeft = true;
                } else if (y > 0) {
                    hasTopLeft = leftOk;
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
                intra.predict8x8(predModes[y * 8 + x * 2], lumaPlane, offset, lumaStride,
                        hasLeft, hasTop, hasTopLeft, hasTopRight);
                if ((lumaCoded & (1 << b8)) != 0) {
                    dsp.idct8x8Add(lumaCoeffs, b8 * 64, lumaPlane, offset, lumaStride);
                } else if ((lumaDcOnly & (1 << b8)) != 0) {
                    dsp.idct8x8DcAdd(lumaCoeffs, b8 * 64, lumaPlane, offset, lumaStride);
                }
            }
        } else {
            intra.predict16x16(intra16Mode, lumaPlane, luma, lumaStride, leftOk, topOk, topLeftOk);
            addLumaResidual4x4(luma);
        }
        if (hasChroma) {
            var chroma = frame.chromaOrigin + mbY * 8 * chromaStride + mbX * 8;
            intra.predictChroma(chromaPredMode, cbPlane, chroma, chromaStride, leftOk, topOk, topLeftOk);
            intra.predictChroma(chromaPredMode, crPlane, chroma, chromaStride, leftOk, topOk, topLeftOk);
            addChromaResidual(chroma);
        }
    }

    /**
     * Adds the 4x4-transformed luma residual of the current macroblock, including DC-only blocks
     * of Intra16x16 macroblocks.
     *
     * @param luma the index of the macroblock's top-left luma sample
     */
    private void addLumaResidual4x4(int luma) {
        var coded = lumaCoded;
        var dcOnly = lumaDcOnly & ~coded;
        if ((coded | dcOnly) == 0) {
            return;
        }
        for (var blk = 0; blk < 16; blk++) {
            var bit = 1 << blk;
            if (((coded | dcOnly) & bit) == 0) {
                continue;
            }
            var offset = luma + H264Tables.BLOCK_Y[blk] * 4 * lumaStride + H264Tables.BLOCK_X[blk] * 4;
            if ((coded & bit) != 0) {
                dsp.idct4x4Add(lumaCoeffs, blk * 16, lumaPlane, offset, lumaStride);
            } else {
                dsp.idct4x4DcAdd(lumaCoeffs, blk * 16, lumaPlane, offset, lumaStride);
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
        for (var c = 0; c < 2; c++) {
            if ((chromaDcCoded & (1 << c)) != 0) {
                transformChromaDc(c);
            }
            var coeffs = c == 0 ? cbCoeffs : crCoeffs;
            var plane = c == 0 ? cbPlane : crPlane;
            if (((chromaCoded >> (c * 4)) & 0xF) == 0) {
                dsp.chromaDcAdd(coeffs, plane, chroma, chromaStride);
                continue;
            }
            for (var blk = 0; blk < 4; blk++) {
                var offset = chroma + (blk >> 1) * 4 * chromaStride + (blk & 1) * 4;
                if ((chromaCoded & (1 << (c * 4 + blk))) != 0) {
                    dsp.idct4x4Add(coeffs, blk * 16, plane, offset, chromaStride);
                } else if (coeffs[blk * 16] != 0) {
                    dsp.idct4x4DcAdd(coeffs, blk * 16, plane, offset, chromaStride);
                }
            }
        }
    }

    /**
     * Adds the residual of an inter macroblock after motion compensation.
     */
    private void reconstructInterResidual() {
        var luma = frame.lumaOrigin + mbY * 16 * lumaStride + mbX * 16;
        if ((mbFlags & DecoderFrame.MB_T8X8) != 0) {
            for (var b8 = 0; b8 < 4; b8++) {
                var offset = luma + (b8 >> 1) * 8 * lumaStride + (b8 & 1) * 8;
                if ((lumaCoded & (1 << b8)) != 0) {
                    dsp.idct8x8Add(lumaCoeffs, b8 * 64, lumaPlane, offset, lumaStride);
                } else if ((lumaDcOnly & (1 << b8)) != 0) {
                    dsp.idct8x8DcAdd(lumaCoeffs, b8 * 64, lumaPlane, offset, lumaStride);
                }
            }
        } else {
            addLumaResidual4x4(luma);
        }
        if (hasChroma) {
            addChromaResidual(frame.chromaOrigin + mbY * 8 * chromaStride + mbX * 8);
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
     * Returns the current macroblock column.
     *
     * @return the column
     */
    int mbX() {
        return mbX;
    }

    /**
     * Returns the current macroblock row.
     *
     * @return the row
     */
    int mbY() {
        return mbY;
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
