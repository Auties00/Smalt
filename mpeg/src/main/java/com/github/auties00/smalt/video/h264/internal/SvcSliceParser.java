package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Arrays;

/**
 * Parses the macroblocks of one slice of a scalable access unit, base layer slices
 * ({@code slice_data()}, ITU-T H.264 7.3.4) and slices in scalable extension
 * ({@code slice_data_in_scalable_extension()}, F.7.3.4.1) alike, with CAVLC or CABAC and the
 * alternative parsing of F.9.
 *
 * <p>Each call to {@link #next()} parses one macroblock, skipped ones included, and leaves its
 * syntax elements in the public fields of the parser for the decoding process; the parser itself
 * records in the {@link SvcLayer} only the values the parsing of the following macroblocks reads.
 * Only frames without macroblock-adaptive frame/field coding are parsed.
 */
final class SvcSliceParser {
    /**
     * The coded block pattern of each {@code codeNum} for Intra_4x4 and Intra_8x8 macroblocks.
     */
    private static final byte[] CBP_INTRA = H264Tables.CBP_INTRA;

    /**
     * The coded block pattern of each {@code codeNum} for inter macroblocks.
     */
    private static final byte[] CBP_INTER = H264Tables.CBP_INTER;

    /**
     * The contexts initialization values {@code (m, n)} of {@code ctxIdx} 1024 to 1030 (ITU-T H.264
     * Tables F-12 and F-13), indexed by the CABAC model: 0 for EI slices, {@code cabac_init_idc + 1}
     * otherwise.
     */
    private static final int[][] SVC_CONTEXTS = {
            {-14, 138, -22, 140, -11, 99, -6, 67, -6, 67, -23, 104, -35, 106},
            {0, 75, 2, 65, 2, 59, -6, 67, -6, 67, -23, 104, -35, 106},
            {0, 75, 2, 65, 2, 59, -6, 67, -6, 67, -23, 104, -35, 106},
            {0, 75, 2, 65, 2, 59, -6, 67, -6, 67, -23, 104, -35, 106}
    };

    /**
     * The macroblock address of the current macroblock.
     */
    int mbAddr;

    /**
     * Whether the macroblock is skipped: its {@code mb_type} is inferred to P_Skip or B_Skip.
     */
    boolean skipped;

    /**
     * The {@code base_mode_flag}.
     */
    boolean baseMode;

    /**
     * The macroblock type the syntax codes, an {@link SvcMb} type; {@link SvcMb#NONE} with
     * {@link #baseMode}.
     */
    int type;

    /**
     * The {@code Intra16x16PredMode} of an Intra_16x16 macroblock.
     */
    int i16Mode;

    /**
     * The {@code transform_size_8x8_flag}.
     */
    boolean transform8x8;

    /**
     * The {@code prev_intra4x4_pred_mode_flag} or {@code prev_intra8x8_pred_mode_flag} of each
     * block.
     */
    final boolean[] prevIntraMode = new boolean[16];

    /**
     * The {@code rem_intra4x4_pred_mode} or {@code rem_intra8x8_pred_mode} of each block.
     */
    final int[] remIntraMode = new int[16];

    /**
     * The {@code intra_chroma_pred_mode}.
     */
    int chromaPredMode;

    /**
     * The sub-macroblock types of a macroblock with four partitions, {@link SvcMb} sub types.
     */
    final int[] subType = new int[4];

    /**
     * The {@code motion_prediction_flag_l0} of each partition.
     */
    final boolean[] motionPred0 = new boolean[4];

    /**
     * The {@code motion_prediction_flag_l1} of each partition.
     */
    final boolean[] motionPred1 = new boolean[4];

    /**
     * The {@code ref_idx_l0} of each partition, 0 when absent.
     */
    final int[] refIdx0 = new int[4];

    /**
     * The {@code ref_idx_l1} of each partition, 0 when absent.
     */
    final int[] refIdx1 = new int[4];

    /**
     * The {@code mvd_l0} of each partition and sub-partition, indexed
     * {@code (mbPartIdx * 4 + subMbPartIdx) * 2 + compIdx}.
     */
    final int[] mvd0 = new int[32];

    /**
     * The {@code mvd_l1}, laid out as {@link #mvd0}.
     */
    final int[] mvd1 = new int[32];

    /**
     * The {@code residual_prediction_flag}.
     */
    boolean residualPred;

    /**
     * The {@code CodedBlockPatternLuma}.
     */
    int cbpLuma;

    /**
     * The {@code CodedBlockPatternChroma}.
     */
    int cbpChroma;

    /**
     * The luma quantization parameter {@code QPY} of the macroblock.
     */
    int qp;

    /**
     * The {@code LumaLevel4x4} lists at {@code luma4x4BlkIdx * 16}, or the {@code LumaLevel8x8}
     * lists at {@code luma8x8BlkIdx * 64} when {@link #transform8x8} is set.
     */
    final int[] lumaLevels = new int[256];

    /**
     * The {@code Intra16x16DCLevel} list.
     */
    final int[] dcLevels = new int[16];

    /**
     * The {@code Intra16x16ACLevel} lists at {@code luma4x4BlkIdx * 16}, 15 entries each.
     */
    final int[] acLevels = new int[256];

    /**
     * The {@code ChromaDCLevel} lists at {@code iCbCr * 4}.
     */
    final int[] chromaDcLevels = new int[8];

    /**
     * The {@code ChromaACLevel} lists at {@code (iCbCr * 4 + chroma4x4BlkIdx) * 16}, 15 entries
     * each.
     */
    final int[] chromaAcLevels = new int[128];

    /**
     * The PCM samples: 256 luma samples in raster order followed by the Cb and the Cr samples.
     */
    final int[] pcm = new int[384];

    /**
     * The luma blocks of the macroblock coding non-zero levels, bit {@code luma4x4BlkIdx}.
     */
    int codedLuma;

    /**
     * The reader of the slice data.
     */
    private final H264BitReader bits = new H264BitReader();

    /**
     * The decoder of the CAVLC residual blocks.
     */
    private final CavlcReader cavlc = new CavlcReader();

    /**
     * The CABAC decoding engine and its context states.
     */
    private final CabacReader cabac = new CabacReader();

    /**
     * The scan positions of the significant coefficients of the CABAC residual block being decoded.
     */
    private final int[] indices = new int[64];

    /**
     * The coefficient levels of the residual block being decoded.
     */
    private final int[] values = new int[64];

    /**
     * The slice being parsed.
     */
    private SvcSlice slice;

    /**
     * The header of the slice being parsed.
     */
    private H264SliceHeader header;

    /**
     * The picture parameter set of the slice being parsed.
     */
    private H264Pps pps;

    /**
     * The sequence parameter set of the slice being parsed.
     */
    private H264Sps sps;

    /**
     * The arrays that receive the syntax of the parsed macroblocks.
     */
    private SvcLayer layer;

    /**
     * The slice group of each macroblock, or {@code null} for a single slice group.
     */
    private int[] groupMap;

    /**
     * The {@code sliceIdc} value the macroblocks of the slice receive: the slice's
     * {@code first_mb_in_slice} times 128 plus its {@code DQId}.
     */
    private int sliceIdc;

    /**
     * The slice type, from 0 to 4.
     */
    private int sliceType;

    /**
     * Whether the slice is coded with CABAC.
     */
    private boolean cabacMode;

    /**
     * The position of the RBSP stop bit of the slice data, which ends the CAVLC slice data.
     */
    private long stopBit;

    /**
     * The macroblocks still to infer of a slice with {@code slice_skip_flag} set, or -1 for a slice
     * whose macroblocks are coded.
     */
    private int remaining;

    /**
     * The skipped macroblocks left in the current {@code mb_skip_run} of a CAVLC slice.
     */
    private int pendingSkips;

    /**
     * Whether the macroblock after a completed {@code mb_skip_run} is coded, so that no run
     * precedes it.
     */
    private boolean codedNext;

    /**
     * Whether the slice data has ended.
     */
    private boolean ended;

    /**
     * Whether the previous macroblock in decoding order coded a non-zero {@code mb_qp_delta}, for
     * the CABAC context of the next one.
     */
    private boolean prevQpDeltaNonZero;

    /**
     * The luma quantization parameter of the previous macroblock in decoding order, which
     * {@code mb_qp_delta} updates.
     */
    private int qpPred;

    /**
     * The {@code scan_idx_start} of the slice.
     */
    private int scanStart;

    /**
     * The {@code scan_idx_end} of the slice.
     */
    private int scanEnd;

    /**
     * The number of scan positions the residual blocks code minus one when it is below 15, for the
     * codeword remapping of Table F-10, or -1 when every position is coded.
     */
    private int remapRange;

    /**
     * Whether the CAVLC {@code coded_block_pattern} code numbers are shifted by one (ITU-T H.264
     * F.9), for slices that predict from a reference layer and code part of the scan.
     */
    private boolean cbpShift;

    /**
     * The column of the current macroblock in macroblocks.
     */
    private int mbX;

    /**
     * The row of the current macroblock in macroblocks.
     */
    private int mbY;

    /**
     * The address of the neighboring macroblock A of the current macroblock in the slice, or -1
     * when it is unavailable.
     */
    private int mbA;

    /**
     * The address of the neighboring macroblock B of the current macroblock in the slice, or -1
     * when it is unavailable.
     */
    private int mbB;

    /**
     * The address of the neighboring macroblock C of the current macroblock in the slice, or -1
     * when it is unavailable.
     */
    private int mbC;

    /**
     * The address of the neighboring macroblock D of the current macroblock in the slice, or -1
     * when it is unavailable.
     */
    private int mbD;

    /**
     * The left bound of the scaled reference layer window in luma samples.
     */
    private int cropLeft;

    /**
     * The top bound of the scaled reference layer window in luma samples.
     */
    private int cropTop;

    /**
     * The right bound of the scaled reference layer window in luma samples, exclusive.
     */
    private int cropRight;

    /**
     * The bottom bound of the scaled reference layer window in luma samples, exclusive.
     */
    private int cropBottom;

    /**
     * Constructs a parser.
     */
    SvcSliceParser() {
    }

    /**
     * Positions the parser at the first macroblock of a slice.
     *
     * @param slice the slice, whose header is parsed and whose fields of the scalable extension
     * are set
     * @param layer the arrays of the layer representation the slice belongs to
     * @param groupMap the {@code MbToSliceGroupMap} of the layer representation, or {@code null}
     * for a single slice group
     * @param crop the scaled reference layer window in luma samples ({@code left, top, right,
     * bottom}, exclusive right and bottom), or {@code null} when the slice has no reference layer
     */
    void begin(SvcSlice slice, SvcLayer layer, int[] groupMap, int[] crop) {
        this.slice = slice;
        this.header = slice.header;
        this.pps = header.pps;
        this.sps = header.sps;
        this.layer = layer;
        this.groupMap = groupMap;
        this.sliceIdc = (header.firstMbInSlice << 7) + slice.dqId();
        this.sliceType = header.sliceType % 5;
        this.cabacMode = pps.cabac;
        this.mbAddr = -1;
        this.pendingSkips = 0;
        this.codedNext = false;
        this.ended = false;
        this.prevQpDeltaNonZero = false;
        this.qpPred = header.sliceQp;
        this.scanStart = slice.scanIdxStart;
        this.scanEnd = slice.scanIdxEnd;
        this.remapRange = scanEnd - scanStart < 15 ? scanEnd - scanStart : -1;
        this.cbpShift = slice.refLayerDqId >= 0 && scanEnd - scanStart < 15;
        if (crop != null) {
            cropLeft = crop[0];
            cropTop = crop[1];
            cropRight = crop[2];
            cropBottom = crop[3];
        } else {
            cropLeft = 0;
            cropTop = 0;
            cropRight = -1;
            cropBottom = -1;
        }
        if (slice.sliceSkip) {
            remaining = slice.skippedMbs;
            return;
        }
        remaining = -1;
        bits.init(slice.rbsp, 0, slice.size);
        bits.skip(header.dataBitPosition);
        if (cabacMode) {
            bits.alignToByte();
            cabac.init(slice.rbsp, bits.bytePosition(), slice.size);
            var model = sliceType == H264SliceHeader.I ? 0 : header.cabacInitIdc + 1;
            CabacTables.initContexts(cabac.states, model, header.sliceQp, CabacTables.CONTEXT_COUNT);
            var table = SVC_CONTEXTS[model];
            var qp = Math.clamp(header.sliceQp, 0, 51);
            for (var i = 0; i < 7; i++) {
                var pre = Math.clamp(((table[2 * i] * qp) >> 4) + table[2 * i + 1], 1, 126);
                cabac.states[1024 + i] = (byte) (pre <= 63 ? (63 - pre) << 1 : ((pre - 64) << 1) | 1);
            }
        } else {
            stopBit = NalUnits.stopBitPosition(slice.rbsp, 0, slice.size);
        }
    }

    /**
     * Parses the next macroblock of the slice.
     *
     * @return {@code false} when the slice has no more macroblocks
     * @throws MediaException.Malformed if the slice data is invalid
     */
    boolean next() {
        if (ended) {
            return false;
        }
        if (remaining >= 0) {
            if (remaining == 0) {
                ended = true;
                return false;
            }
            remaining--;
            advance();
            inferSkippedSlice();
            return true;
        }
        if (cabacMode) {
            return nextCabac();
        }
        return nextCavlc();
    }

    /**
     * Parses the next macroblock of a CAVLC slice.
     *
     * @return {@code false} at the end of the slice
     */
    private boolean nextCavlc() {
        if (pendingSkips > 0) {
            pendingSkips--;
            advance();
            parseSkipped();
            endSkipRun();
            return true;
        }
        if (!codedNext && sliceType != H264SliceHeader.I) {
            var run = bits.readUe();
            if (run > layer.mbCount) {
                throw new MediaException.Malformed("mb_skip_run out of range");
            }
            if (run > 0) {
                pendingSkips = run - 1;
                advance();
                parseSkipped();
                endSkipRun();
                return true;
            }
        }
        codedNext = false;
        advance();
        parseMacroblock();
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated slice data");
        }
        if (!bits.moreRbspData(stopBit)) {
            ended = true;
        }
        return true;
    }

    /**
     * Ends the slice after the last macroblock of a skip run when no more slice data follows,
     * and otherwise marks the next macroblock as coded.
     */
    private void endSkipRun() {
        if (pendingSkips > 0) {
            return;
        }
        if (bits.moreRbspData(stopBit)) {
            codedNext = true;
        } else {
            ended = true;
        }
    }

    /**
     * Parses the next macroblock of a CABAC slice.
     *
     * @return {@code false} at the end of the slice
     */
    private boolean nextCabac() {
        advance();
        if (sliceType != H264SliceHeader.I) {
            var inc = 0;
            if (mbA >= 0 && (layer.syntax[mbA] & 0xF) != SvcLayer.SYNTAX_SKIP) {
                inc++;
            }
            if (mbB >= 0 && (layer.syntax[mbB] & 0xF) != SvcLayer.SYNTAX_SKIP) {
                inc++;
            }
            var skip = cabac.decision((sliceType == H264SliceHeader.B ? 24 : 11) + inc) != 0;
            if (skip) {
                parseSkipped();
            } else {
                parseMacroblock();
            }
        } else {
            parseMacroblock();
        }
        if (cabac.terminate() != 0) {
            ended = true;
        }
        if (cabac.overrun()) {
            throw new MediaException.Malformed("truncated slice data");
        }
        return true;
    }

    /**
     * Moves to the next macroblock address of the slice and derives its neighbors.
     */
    private void advance() {
        if (mbAddr < 0) {
            mbAddr = header.firstMbInSlice;
        } else if (groupMap == null) {
            mbAddr++;
        } else {
            var group = groupMap[mbAddr];
            var next = mbAddr + 1;
            while (next < layer.mbCount && groupMap[next] != group) {
                next++;
            }
            mbAddr = next;
        }
        if (mbAddr >= layer.mbCount) {
            throw new MediaException.Malformed("slice runs past the last macroblock");
        }
        var w = layer.widthMbs;
        mbX = mbAddr % w;
        mbY = mbAddr / w;
        var ids = layer.sliceIdc;
        mbA = mbX > 0 && ids[mbAddr - 1] == sliceIdc ? mbAddr - 1 : -1;
        mbB = mbY > 0 && ids[mbAddr - w] == sliceIdc ? mbAddr - w : -1;
        mbC = mbY > 0 && mbX < w - 1 && ids[mbAddr - w + 1] == sliceIdc ? mbAddr - w + 1 : -1;
        mbD = mbY > 0 && mbX > 0 && ids[mbAddr - w - 1] == sliceIdc ? mbAddr - w - 1 : -1;
        ids[mbAddr] = sliceIdc;
    }

    /**
     * Returns the left neighbor of the current macroblock in the slice.
     *
     * @return its address, or -1 when not available
     */
    int neighborA() {
        return mbA;
    }

    /**
     * Returns the upper neighbor of the current macroblock in the slice.
     *
     * @return its address, or -1 when not available
     */
    int neighborB() {
        return mbB;
    }

    /**
     * Returns the upper right neighbor of the current macroblock in the slice.
     *
     * @return its address, or -1 when not available
     */
    int neighborC() {
        return mbC;
    }

    /**
     * Returns the upper left neighbor of the current macroblock in the slice.
     *
     * @return its address, or -1 when not available
     */
    int neighborD() {
        return mbD;
    }

    /**
     * Returns the {@code sliceIdc} of the slice.
     *
     * @return {@code (first_mb_in_slice << 7) + DQId}
     */
    int sliceIdc() {
        return sliceIdc;
    }

    /**
     * Returns whether a macroblock lies inside the window of the scaled reference layer, the
     * {@code InCropWindow()} function of ITU-T H.264 F.7.4.6.
     *
     * @param addr the macroblock address
     * @return {@code true} when inter-layer prediction applies to the macroblock
     */
    boolean inCropWindow(int addr) {
        if (slice.noInterLayerPred) {
            return false;
        }
        var x = addr % layer.widthMbs;
        var y = addr / layer.widthMbs;
        return x >= (cropLeft + 15) / 16 && x < cropRight / 16 && y >= (cropTop + 15) / 16 && y < cropBottom / 16;
    }

    /**
     * Resets the syntax elements of the current macroblock to their inferred values.
     */
    private void clearMacroblock() {
        skipped = false;
        baseMode = false;
        type = SvcMb.NONE;
        i16Mode = 0;
        transform8x8 = false;
        chromaPredMode = 0;
        residualPred = false;
        cbpLuma = 0;
        cbpChroma = 0;
        codedLuma = 0;
        Arrays.fill(subType, 0);
        Arrays.fill(motionPred0, false);
        Arrays.fill(motionPred1, false);
        Arrays.fill(refIdx0, 0);
        Arrays.fill(refIdx1, 0);
        Arrays.fill(mvd0, 0);
        Arrays.fill(mvd1, 0);
        Arrays.fill(lumaLevels, 0);
        Arrays.fill(dcLevels, 0);
        Arrays.fill(acLevels, 0);
        Arrays.fill(chromaDcLevels, 0);
        Arrays.fill(chromaAcLevels, 0);
    }

    /**
     * Sets the syntax elements of a macroblock of a slice with {@code slice_skip_flag} 1
     * (ITU-T H.264 F.7.4.3.4).
     */
    private void inferSkippedSlice() {
        clearMacroblock();
        baseMode = true;
        residualPred = true;
        qp = header.sliceQp;
        qpPred = qp;
        storeSyntax(SvcLayer.SYNTAX_INTER | SvcLayer.SYNTAX_BASE);
        Arrays.fill(layer.totalCoeff, mbAddr * 24, mbAddr * 24 + 24, (byte) 0);
        layer.dcFlags[mbAddr] = 0;
        clearMotionSyntax();
    }

    /**
     * Sets the syntax elements of a skipped macroblock.
     */
    private void parseSkipped() {
        clearMacroblock();
        skipped = true;
        type = sliceType == H264SliceHeader.B ? SvcMb.B_SKIP : SvcMb.P_SKIP;
        residualPred = slice.extension && inCropWindow(mbAddr) && slice.defaultResidualPrediction;
        qp = qpPred;
        prevQpDeltaNonZero = false;
        storeSyntax(SvcLayer.SYNTAX_SKIP | (type == SvcMb.B_SKIP ? SvcLayer.SYNTAX_DIRECT : 0));
        Arrays.fill(layer.totalCoeff, mbAddr * 24, mbAddr * 24 + 24, (byte) 0);
        layer.dcFlags[mbAddr] = 0;
        clearMotionSyntax();
    }

    /**
     * Records the parsing category and coded block pattern of the current macroblock.
     *
     * @param syntax the category with its flags
     */
    private void storeSyntax(int syntax) {
        layer.syntax[mbAddr] = (byte) syntax;
        layer.cbp[mbAddr] = (byte) (cbpLuma | (cbpChroma << 4));
        layer.codedLuma[mbAddr] = (short) codedLuma;
    }

    /**
     * Clears the CABAC motion contexts of the current macroblock.
     */
    private void clearMotionSyntax() {
        Arrays.fill(layer.mvd0, mbAddr * 32, mbAddr * 32 + 32, (byte) 0);
        Arrays.fill(layer.mvd1, mbAddr * 32, mbAddr * 32 + 32, (byte) 0);
        Arrays.fill(layer.refContext0, mbAddr * 4, mbAddr * 4 + 4, false);
        Arrays.fill(layer.refContext1, mbAddr * 4, mbAddr * 4 + 4, false);
    }

    /**
     * Parses a {@code macroblock_layer()} or {@code macroblock_layer_in_scalable_extension()}.
     */
    private void parseMacroblock() {
        clearMacroblock();
        var crop = inCropWindow(mbAddr);
        if (crop && slice.adaptiveBaseMode) {
            baseMode = cabacMode ? decodeBaseModeFlag() : bits.readFlag();
        } else {
            baseMode = crop && slice.defaultBaseMode;
        }
        if (baseMode) {
            clearMotionSyntax();
        }
        var syntax = SvcLayer.SYNTAX_INTER;
        var intraNxN = false;
        var noSubSmallerThan8x8 = true;
        if (!baseMode) {
            var raw = cabacMode ? decodeMbTypeCabac() : bits.readUe();
            decodeType(raw);
            if (type == SvcMb.IPCM) {
                parsePcm();
                return;
            }
            intraNxN = type == SvcMb.I4X4;
            if (intraNxN) {
                syntax = SvcLayer.SYNTAX_INXN;
            } else if (type == SvcMb.I16X16) {
                syntax = SvcLayer.SYNTAX_I16;
            } else if (type == SvcMb.B_DIRECT) {
                syntax |= SvcLayer.SYNTAX_DIRECT;
            }
            if (SvcMb.eightByEight(type)) {
                parseSubMbPred(crop);
                for (var p = 0; p < 4; p++) {
                    if (!SvcMb.subDirect(subType[p])) {
                        if (SvcMb.parts(SvcMb.subShape(subType[p])) > 1) {
                            noSubSmallerThan8x8 = false;
                        }
                    } else if (!sps.direct8x8Inference) {
                        noSubSmallerThan8x8 = false;
                    }
                }
            } else {
                if (pps.transform8x8Mode && intraNxN) {
                    transform8x8 = cabacMode ? decodeTransformFlag() : bits.readFlag();
                    if (transform8x8) {
                        type = SvcMb.I8X8;
                    }
                }
                parseMbPred(crop);
            }
        }
        var intra = SvcMb.intra(type) && !baseMode;
        if (slice.adaptiveResidualPrediction && sliceType != H264SliceHeader.I && crop && (baseMode || !intra)) {
            residualPred = cabacMode ? cabac.decision(1029 + (baseMode ? 0 : 1)) != 0 : bits.readFlag();
        } else {
            residualPred = slice.extension && sliceType != H264SliceHeader.I && crop && (baseMode || !intra)
                    && slice.defaultResidualPrediction;
        }
        var i16 = type == SvcMb.I16X16 && !baseMode;
        var qpDelta = 0;
        if (scanEnd >= scanStart) {
            if (baseMode || !i16) {
                var cbp = cabacMode ? decodeCbpCabac() : decodeCbpCavlc(intra && !baseMode);
                cbpLuma = cbp & 15;
                cbpChroma = cbp >> 4;
                if (cbpLuma > 0 && pps.transform8x8Mode && (baseMode || (!intraNxN && noSubSmallerThan8x8
                        && (type != SvcMb.B_DIRECT || sps.direct8x8Inference)))) {
                    transform8x8 = cabacMode ? decodeTransformFlag() : bits.readFlag();
                }
            }
            if (cbpLuma > 0 || cbpChroma > 0 || i16) {
                qpDelta = cabacMode ? decodeQpDeltaCabac() : bits.readSe();
                if (qpDelta < -26 || qpDelta > 25) {
                    throw new MediaException.Malformed("mb_qp_delta out of range");
                }
            }
        } else if (baseMode || !i16) {
            cbpLuma = 0;
            cbpChroma = 0;
        }
        qp = ((qpPred + qpDelta + 52) % 52 + 52) % 52;
        qpPred = qp;
        prevQpDeltaNonZero = qpDelta != 0;
        if (baseMode) {
            syntax = SvcLayer.SYNTAX_INTER | SvcLayer.SYNTAX_BASE;
        }
        if (transform8x8) {
            syntax |= SvcLayer.SYNTAX_T8X8;
        }
        storeSyntax(syntax);
        if (scanEnd >= scanStart && (cbpLuma > 0 || cbpChroma > 0 || i16)) {
            if (cabacMode) {
                residualCabac(intra, i16);
            } else {
                residualCavlc(i16);
            }
        } else {
            Arrays.fill(layer.totalCoeff, mbAddr * 24, mbAddr * 24 + 24, (byte) 0);
            layer.dcFlags[mbAddr] = 0;
        }
        layer.codedLuma[mbAddr] = (short) codedLuma;
        if (intra) {
            layer.ipredChroma[mbAddr] = (byte) chromaPredMode;
        }
    }

    /**
     * Sets {@link #type} and {@link #i16Mode} from a coded {@code mb_type}.
     *
     * @param raw the {@code mb_type} in the numbering of the slice type
     */
    private void decodeType(int raw) {
        var intraType = raw;
        if (sliceType == H264SliceHeader.P) {
            if (raw < 5) {
                type = SvcMb.P_BASE + raw;
                return;
            }
            intraType = raw - 5;
        } else if (sliceType == H264SliceHeader.B) {
            if (raw < 23) {
                type = SvcMb.B_BASE + raw;
                return;
            }
            intraType = raw - 23;
        }
        if (intraType > 25) {
            throw new MediaException.Malformed("mb_type " + raw + " out of range");
        }
        if (intraType == 0) {
            type = SvcMb.I4X4;
        } else if (intraType == 25) {
            type = SvcMb.IPCM;
        } else {
            type = SvcMb.I16X16;
            i16Mode = (intraType - 1) % 4;
            cbpChroma = ((intraType - 1) / 4) % 3;
            cbpLuma = intraType >= 13 ? 15 : 0;
        }
    }

    /**
     * Parses the samples of an I_PCM macroblock.
     */
    private void parsePcm() {
        if (cabacMode) {
            var position = cabac.alignedBytePosition();
            var data = slice.rbsp;
            if (position + 384 > slice.size) {
                throw new MediaException.Malformed("truncated PCM samples");
            }
            for (var i = 0; i < 384; i++) {
                pcm[i] = data[position + i] & 0xFF;
            }
            cabac.init(data, position + 384, slice.size);
        } else {
            bits.alignToByte();
            for (var i = 0; i < 384; i++) {
                pcm[i] = bits.readBits(8);
            }
        }
        qp = qpPred;
        prevQpDeltaNonZero = false;
        cbpLuma = 15;
        cbpChroma = 2;
        codedLuma = 0xFFFF;
        storeSyntax(SvcLayer.SYNTAX_PCM);
        Arrays.fill(layer.totalCoeff, mbAddr * 24, mbAddr * 24 + 24, (byte) 16);
        layer.dcFlags[mbAddr] = 7;
        clearMotionSyntax();
    }

    /**
     * Parses an {@code mb_pred()} or {@code mb_pred_in_scalable_extension()}.
     *
     * @param crop whether the macroblock lies inside the scaled reference layer window
     */
    private void parseMbPred(boolean crop) {
        clearMotionSyntax();
        if (SvcMb.intra(type)) {
            if (type == SvcMb.I4X4 || type == SvcMb.I8X8) {
                var count = type == SvcMb.I4X4 ? 16 : 4;
                for (var i = 0; i < count; i++) {
                    if (cabacMode) {
                        prevIntraMode[i] = cabac.decision(68) != 0;
                        if (!prevIntraMode[i]) {
                            var mode = cabac.decision(69);
                            mode |= cabac.decision(69) << 1;
                            mode |= cabac.decision(69) << 2;
                            remIntraMode[i] = mode;
                        }
                    } else {
                        prevIntraMode[i] = bits.readFlag();
                        if (!prevIntraMode[i]) {
                            remIntraMode[i] = bits.readBits(3);
                        }
                    }
                }
            }
            chromaPredMode = cabacMode ? decodeChromaPredModeCabac() : bits.readUe();
            if (chromaPredMode > 3) {
                throw new MediaException.Malformed("intra_chroma_pred_mode out of range");
            }
            return;
        }
        if (type == SvcMb.B_DIRECT) {
            return;
        }
        var shape = SvcMb.shape(type);
        var parts = SvcMb.parts(shape);
        if (crop && slice.adaptiveMotionPrediction) {
            for (var p = 0; p < parts; p++) {
                if (SvcMb.mode(type, p) != 2) {
                    motionPred0[p] = cabacMode ? cabac.decision(1027) != 0 : bits.readFlag();
                }
            }
            for (var p = 0; p < parts; p++) {
                if (SvcMb.mode(type, p) != 1) {
                    motionPred1[p] = cabacMode ? cabac.decision(1028) != 0 : bits.readFlag();
                }
            }
        } else if (crop && slice.defaultMotionPrediction) {
            Arrays.fill(motionPred0, 0, parts, true);
            Arrays.fill(motionPred1, 0, parts, true);
        }
        for (var list = 0; list < 2; list++) {
            var refs = list == 0 ? refIdx0 : refIdx1;
            var predicted = list == 0 ? motionPred0 : motionPred1;
            var count = header.numRefIdxActive[list];
            for (var p = 0; p < parts; p++) {
                if ((SvcMb.mode(type, p) & (1 << list)) != 0 && count > 1 && !predicted[p]) {
                    refs[p] = decodeRefIdx(list, partX(shape, p), partY(shape, p), count);
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var mvds = list == 0 ? mvd0 : mvd1;
            for (var p = 0; p < parts; p++) {
                if ((SvcMb.mode(type, p) & (1 << list)) != 0) {
                    decodeMvd(list, p * 4, partX(shape, p), partY(shape, p), partW(shape), partH(shape), mvds);
                }
            }
        }
        storeRefContexts(shape);
    }

    /**
     * Parses a {@code sub_mb_pred()} or {@code sub_mb_pred_in_scalable_extension()}.
     *
     * @param crop whether the macroblock lies inside the scaled reference layer window
     */
    private void parseSubMbPred(boolean crop) {
        clearMotionSyntax();
        var b = sliceType == H264SliceHeader.B;
        for (var p = 0; p < 4; p++) {
            int raw;
            if (cabacMode) {
                raw = b ? decodeBSubMbTypeCabac() : decodePSubMbTypeCabac();
            } else {
                raw = bits.readUe();
            }
            if (raw > (b ? 12 : 3)) {
                throw new MediaException.Malformed("sub_mb_type " + raw + " out of range");
            }
            subType[p] = b ? SvcMb.SUB_B_BASE + raw : raw;
        }
        if (crop && slice.adaptiveMotionPrediction) {
            for (var p = 0; p < 4; p++) {
                if (!SvcMb.subDirect(subType[p]) && SvcMb.subMode(subType[p]) != 2) {
                    motionPred0[p] = cabacMode ? cabac.decision(1027) != 0 : bits.readFlag();
                }
            }
            for (var p = 0; p < 4; p++) {
                if (!SvcMb.subDirect(subType[p]) && SvcMb.subMode(subType[p]) != 1) {
                    motionPred1[p] = cabacMode ? cabac.decision(1028) != 0 : bits.readFlag();
                }
            }
        } else if (crop && slice.defaultMotionPrediction) {
            for (var p = 0; p < 4; p++) {
                if (!SvcMb.subDirect(subType[p])) {
                    motionPred0[p] = true;
                    motionPred1[p] = true;
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var refs = list == 0 ? refIdx0 : refIdx1;
            var predicted = list == 0 ? motionPred0 : motionPred1;
            var count = header.numRefIdxActive[list];
            for (var p = 0; p < 4; p++) {
                var sub = subType[p];
                if (count > 1 && type != SvcMb.P_8X8_REF0 && !SvcMb.subDirect(sub)
                        && (SvcMb.subMode(sub) & (1 << list)) != 0 && !predicted[p]) {
                    refs[p] = decodeRefIdx(list, (p & 1) * 2, (p >> 1) * 2, count);
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var mvds = list == 0 ? mvd0 : mvd1;
            for (var p = 0; p < 4; p++) {
                var sub = subType[p];
                if (SvcMb.subDirect(sub) || (SvcMb.subMode(sub) & (1 << list)) == 0) {
                    continue;
                }
                var shape = SvcMb.subShape(sub);
                var w = shape == SvcMb.SHAPE_16X16 || shape == SvcMb.SHAPE_16X8 ? 2 : 1;
                var h = shape == SvcMb.SHAPE_16X16 || shape == SvcMb.SHAPE_8X16 ? 2 : 1;
                var count = SvcMb.parts(shape);
                for (var s = 0; s < count; s++) {
                    var x = (p & 1) * 2 + (w == 2 ? 0 : s & 1);
                    var y = (p >> 1) * 2 + (h == 2 ? 0 : (w == 2 ? s : s >> 1));
                    decodeMvd(list, p * 4 + s, x, y, w, h, mvds);
                }
            }
        }
        for (var list = 0; list < 2; list++) {
            var contexts = list == 0 ? layer.refContext0 : layer.refContext1;
            var refs = list == 0 ? refIdx0 : refIdx1;
            for (var p = 0; p < 4; p++) {
                var sub = subType[p];
                contexts[mbAddr * 4 + p] = !SvcMb.subDirect(sub) && (SvcMb.subMode(sub) & (1 << list)) != 0
                        && refs[p] > 0;
            }
        }
    }

    /**
     * Records the CABAC reference index contexts of a macroblock without sub-macroblock
     * partitions.
     *
     * @param shape the partition shape
     */
    private void storeRefContexts(int shape) {
        for (var list = 0; list < 2; list++) {
            var contexts = list == 0 ? layer.refContext0 : layer.refContext1;
            var refs = list == 0 ? refIdx0 : refIdx1;
            for (var q = 0; q < 4; q++) {
                var p = partOfQuadrant(shape, q);
                contexts[mbAddr * 4 + q] = (SvcMb.mode(type, p) & (1 << list)) != 0 && refs[p] > 0;
            }
        }
    }

    /**
     * Returns the partition of a shape that covers an 8x8 quadrant.
     *
     * @param shape the partition shape
     * @param quadrant the quadrant, 0 to 3
     * @return the partition index
     */
    static int partOfQuadrant(int shape, int quadrant) {
        return switch (shape) {
            case SvcMb.SHAPE_16X16 -> 0;
            case SvcMb.SHAPE_16X8 -> quadrant >> 1;
            case SvcMb.SHAPE_8X16 -> quadrant & 1;
            default -> quadrant;
        };
    }

    /**
     * Returns the column of a partition in 4x4 blocks.
     *
     * @param shape the partition shape
     * @param part the partition index
     * @return the column
     */
    static int partX(int shape, int part) {
        return shape == SvcMb.SHAPE_8X16 || shape == SvcMb.SHAPE_8X8 ? (part & 1) * 2 : 0;
    }

    /**
     * Returns the row of a partition in 4x4 blocks.
     *
     * @param shape the partition shape
     * @param part the partition index
     * @return the row
     */
    static int partY(int shape, int part) {
        return switch (shape) {
            case SvcMb.SHAPE_16X8 -> part * 2;
            case SvcMb.SHAPE_8X8 -> (part >> 1) * 2;
            default -> 0;
        };
    }

    /**
     * Returns the width of the partitions of a shape in 4x4 blocks.
     *
     * @param shape the partition shape
     * @return the width
     */
    static int partW(int shape) {
        return shape == SvcMb.SHAPE_16X16 || shape == SvcMb.SHAPE_16X8 ? 4 : 2;
    }

    /**
     * Returns the height of the partitions of a shape in 4x4 blocks.
     *
     * @param shape the partition shape
     * @return the height
     */
    static int partH(int shape) {
        return shape == SvcMb.SHAPE_16X16 || shape == SvcMb.SHAPE_8X16 ? 4 : 2;
    }

    /**
     * Decodes a reference index.
     *
     * @param list the reference picture list index, 0 or 1
     * @param x the column of the partition in 4x4 blocks
     * @param y the row of the partition in 4x4 blocks
     * @param count the number of active references of the list
     * @return the index
     */
    private int decodeRefIdx(int list, int x, int y, int count) {
        int value;
        if (cabacMode) {
            var contexts = list == 0 ? layer.refContext0 : layer.refContext1;
            var inc = 0;
            if (x > 0 ? currentRefContext(list, x - 1, y) : mbA >= 0 && contexts[mbA * 4 + (y >> 1) * 2 + 1]) {
                inc++;
            }
            if (y > 0 ? currentRefContext(list, x, y - 1) : mbB >= 0 && contexts[mbB * 4 + 2 + (x >> 1)]) {
                inc += 2;
            }

            value = 0;
            if (cabac.decision(54 + inc) != 0) {
                value = 1;
                if (cabac.decision(58) != 0) {
                    value = 2;
                    while (cabac.decision(59) != 0) {
                        if (++value >= 32) {
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
     * Returns whether the 4x4 block of the current macroblock at a position, already parsed,
     * contributes 1 to the CABAC reference index context of a neighbor.
     *
     * @param list the reference picture list index, 0 or 1
     * @param x the block column
     * @param y the block row
     * @return the context condition
     */
    private boolean currentRefContext(int list, int x, int y) {
        var quadrant = (y >> 1) * 2 + (x >> 1);
        var refs = list == 0 ? refIdx0 : refIdx1;
        if (SvcMb.eightByEight(type)) {
            var sub = subType[quadrant];
            return !SvcMb.subDirect(sub) && (SvcMb.subMode(sub) & (1 << list)) != 0 && refs[quadrant] > 0;
        }
        var p = partOfQuadrant(SvcMb.shape(type), quadrant);
        return (SvcMb.mode(type, p) & (1 << list)) != 0 && refs[p] > 0;
    }

    /**
     * Decodes the motion vector difference of one partition and records it for the CABAC
     * contexts of the following partitions.
     *
     * @param list the reference picture list index, 0 or 1
     * @param index the partition and sub-partition index, {@code mbPartIdx * 4 + subMbPartIdx}
     * @param x the column of the partition in 4x4 blocks
     * @param y the row of the partition in 4x4 blocks
     * @param w the width of the partition in 4x4 blocks
     * @param h the height of the partition in 4x4 blocks
     * @param mvds receives the difference
     */
    private void decodeMvd(int list, int index, int x, int y, int w, int h, int[] mvds) {
        int dx;
        int dy;
        var stored = list == 0 ? layer.mvd0 : layer.mvd1;
        if (cabacMode) {
            var left = x > 0 ? mbAddr * 32 + (y * 4 + x - 1) * 2 : mbA >= 0 ? mbA * 32 + (y * 4 + 3) * 2 : -1;
            var top = y > 0 ? mbAddr * 32 + ((y - 1) * 4 + x) * 2 : mbB >= 0 ? mbB * 32 + (12 + x) * 2 : -1;
            var ax = left >= 0 ? stored[left] & 0xFF : 0;
            var ay = left >= 0 ? stored[left + 1] & 0xFF : 0;
            var bx = top >= 0 ? stored[top] & 0xFF : 0;
            var by = top >= 0 ? stored[top + 1] & 0xFF : 0;
            dx = decodeMvdComponent(40, ax + bx);
            dy = decodeMvdComponent(47, ay + by);
        } else {
            dx = bits.readSe();
            dy = bits.readSe();
        }
        mvds[index * 2] = dx;
        mvds[index * 2 + 1] = dy;
        var absX = (byte) Math.min(Math.abs(dx), 127);
        var absY = (byte) Math.min(Math.abs(dy), 127);
        for (var j = y; j < y + h; j++) {
            for (var i = x; i < x + w; i++) {
                var at = mbAddr * 32 + (j * 4 + i) * 2;
                stored[at] = absX;
                stored[at + 1] = absY;
            }
        }
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
                if (++k > 24) {
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
     * Decodes {@code base_mode_flag} with CABAC (ITU-T H.264 F.9.3.3.2.1).
     *
     * @return the flag
     */
    private boolean decodeBaseModeFlag() {
        var inc = 0;
        if (mbA < 0 || (layer.syntax[mbA] & SvcLayer.SYNTAX_BASE) == 0) {
            inc++;
        }
        if (mbB < 0 || (layer.syntax[mbB] & SvcLayer.SYNTAX_BASE) == 0) {
            inc++;
        }
        return cabac.decision(1024 + inc) != 0;
    }

    /**
     * Decodes the {@code mb_type} with CABAC.
     *
     * @return the {@code mb_type} in the numbering of the slice type
     */
    private int decodeMbTypeCabac() {
        if (sliceType == H264SliceHeader.I) {
            var inc = 0;
            if (mbA >= 0 && (layer.syntax[mbA] & 0xF) != SvcLayer.SYNTAX_INXN) {
                inc++;
            }
            if (mbB >= 0 && (layer.syntax[mbB] & 0xF) != SvcLayer.SYNTAX_INXN) {
                inc++;
            }
            return decodeIntraMbType(3 + inc, 3, true);
        }
        if (sliceType == H264SliceHeader.P) {
            if (cabac.decision(14) == 0) {
                if (cabac.decision(15) == 0) {
                    return cabac.decision(16) == 0 ? 0 : 3;
                }
                return cabac.decision(17) == 0 ? 2 : 1;
            }
            return 5 + decodeIntraMbType(17, 17, false);
        }
        var inc = 0;
        if (mbA >= 0 && (layer.syntax[mbA] & SvcLayer.SYNTAX_DIRECT) == 0) {
            inc++;
        }
        if (mbB >= 0 && (layer.syntax[mbB] & SvcLayer.SYNTAX_DIRECT) == 0) {
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
     * @param base the context offset of the remaining bins
     * @param prefix whether the bins follow the I slice layout rather than the suffix layout
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
        if (mbA >= 0 && (layer.syntax[mbA] & SvcLayer.SYNTAX_T8X8) != 0) {
            inc++;
        }
        if (mbB >= 0 && (layer.syntax[mbB] & SvcLayer.SYNTAX_T8X8) != 0) {
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
        if (mbA >= 0 && intraSyntax(mbA) && layer.ipredChroma[mbA] != 0) {
            inc++;
        }
        if (mbB >= 0 && intraSyntax(mbB) && layer.ipredChroma[mbB] != 0) {
            inc++;
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
     * Returns whether a macroblock was coded with an intra {@code mb_type} other than I_PCM.
     *
     * @param addr the macroblock address
     * @return {@code true} for I_NxN and Intra_16x16 macroblocks
     */
    private boolean intraSyntax(int addr) {
        var category = layer.syntax[addr] & 0xF;
        return category == SvcLayer.SYNTAX_INXN || category == SvcLayer.SYNTAX_I16;
    }

    /**
     * Decodes {@code mb_qp_delta} with CABAC.
     *
     * @return the signed delta
     */
    private int decodeQpDeltaCabac() {
        if (cabac.decision(60 + (prevQpDeltaNonZero ? 1 : 0)) == 0) {
            return 0;
        }
        var value = 1;
        var ctx = 62;
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
     * Decodes {@code coded_block_pattern} with CAVLC (ITU-T H.264 9.1.2 and F.9.1).
     *
     * @param intra whether the macroblock uses the Intra_4x4 or Intra_8x8 mapping
     * @return the coded block pattern
     */
    private int decodeCbpCavlc(boolean intra) {
        var code = bits.readUe();
        if (cbpShift) {
            code--;
        }
        if (code == -1) {
            if (mbA >= 0) {
                return neighborCbp(mbA);
            }
            if (mbB >= 0) {
                return neighborCbp(mbB);
            }
            return 0;
        }
        if (code > 47) {
            throw new MediaException.Malformed("coded_block_pattern out of range");
        }
        return (intra ? CBP_INTRA : CBP_INTER)[code];
    }

    /**
     * Returns the coded block pattern a neighbor contributes to an inferred coded block pattern
     * (ITU-T H.264 F.9.1).
     *
     * @param addr the neighbor address
     * @return its pattern, 0 for skipped and I_PCM macroblocks
     */
    private int neighborCbp(int addr) {
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_SKIP || category == SvcLayer.SYNTAX_PCM) {
            return 0;
        }
        return layer.cbp[addr] & 0x3F;
    }

    /**
     * Decodes {@code coded_block_pattern} with CABAC (ITU-T H.264 9.3.3.1.1.4).
     *
     * @return the coded block pattern
     */
    private int decodeCbpCabac() {
        var luma = 0;
        for (var b8 = 0; b8 < 4; b8++) {
            var x = b8 & 1;
            var y = b8 >> 1;
            int condA;
            if (x == 1) {
                condA = (luma & (1 << (b8 - 1))) == 0 ? 1 : 0;
            } else {
                condA = lumaCbpCondition(mbA, b8 + 1);
            }
            int condB;
            if (y == 1) {
                condB = (luma & (1 << (b8 - 2))) == 0 ? 1 : 0;
            } else {
                condB = lumaCbpCondition(mbB, b8 + 2);
            }
            if (cabac.decision(73 + condA + 2 * condB) != 0) {
                luma |= 1 << b8;
            }
        }
        var condA = chromaCbpCondition(mbA, 0);
        var condB = chromaCbpCondition(mbB, 0);
        if (cabac.decision(77 + condA + 2 * condB) == 0) {
            return luma;
        }
        condA = chromaCbpCondition(mbA, 1);
        condB = chromaCbpCondition(mbB, 1);
        return luma | ((1 + cabac.decision(81 + condA + 2 * condB)) << 4);
    }

    /**
     * Returns the luma coded block pattern context condition of a neighboring 8x8 block.
     *
     * @param addr the neighbor address, or -1
     * @param b8 the neighboring 8x8 block within that macroblock
     * @return 1 when the neighbor block exists and codes no luma coefficients
     */
    private int lumaCbpCondition(int addr, int b8) {
        if (addr < 0) {
            return 0;
        }
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_PCM) {
            return 0;
        }
        if (category == SvcLayer.SYNTAX_SKIP) {
            return 1;
        }
        return (layer.cbp[addr] & (1 << b8)) == 0 ? 1 : 0;
    }

    /**
     * Returns a chroma coded block pattern context condition of a neighboring macroblock.
     *
     * @param addr the neighbor address, or -1
     * @param bin 0 for the first bin, 1 for the second
     * @return the condition, 0 or 1
     */
    private int chromaCbpCondition(int addr, int bin) {
        if (addr < 0) {
            return 0;
        }
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_PCM) {
            return 1;
        }
        if (category == SvcLayer.SYNTAX_SKIP) {
            return 0;
        }
        var chroma = (layer.cbp[addr] >> 4) & 3;
        return bin == 0 ? (chroma != 0 ? 1 : 0) : (chroma == 2 ? 1 : 0);
    }

    /**
     * Returns the stored {@code TotalCoeff} of the luma 4x4 block left of a block of the current
     * macroblock, and whether it is available.
     *
     * @param x the block column
     * @param y the block row
     * @return the count, or -1 when the neighbor is not available
     */
    private int leftLumaCount(int x, int y) {
        if (x > 0) {
            return layer.totalCoeff[mbAddr * 24 + y * 4 + x - 1];
        }
        return mbA >= 0 ? layer.totalCoeff[mbA * 24 + y * 4 + 3] : -1;
    }

    /**
     * Returns the stored {@code TotalCoeff} of the luma 4x4 block above a block of the current
     * macroblock, and whether it is available.
     *
     * @param x the block column
     * @param y the block row
     * @return the count, or -1 when the neighbor is not available
     */
    private int topLumaCount(int x, int y) {
        if (y > 0) {
            return layer.totalCoeff[mbAddr * 24 + (y - 1) * 4 + x];
        }
        return mbB >= 0 ? layer.totalCoeff[mbB * 24 + 12 + x] : -1;
    }

    /**
     * Returns the stored count of the chroma 4x4 block left of a block of the current macroblock.
     *
     * @param component 0 for Cb, 1 for Cr
     * @param x the block column, 0 or 1
     * @param y the block row, 0 or 1
     * @return the count, or -1 when the neighbor is not available
     */
    private int leftChromaCount(int component, int x, int y) {
        var base = 16 + component * 4;
        if (x > 0) {
            return layer.totalCoeff[mbAddr * 24 + base + y * 2];
        }
        return mbA >= 0 ? layer.totalCoeff[mbA * 24 + base + y * 2 + 1] : -1;
    }

    /**
     * Returns the stored count of the chroma 4x4 block above a block of the current macroblock.
     *
     * @param component 0 for Cb, 1 for Cr
     * @param x the block column, 0 or 1
     * @param y the block row, 0 or 1
     * @return the count, or -1 when the neighbor is not available
     */
    private int topChromaCount(int component, int x, int y) {
        var base = 16 + component * 4;
        if (y > 0) {
            return layer.totalCoeff[mbAddr * 24 + base + x];
        }
        return mbB >= 0 ? layer.totalCoeff[mbB * 24 + base + 2 + x] : -1;
    }

    /**
     * Combines two neighboring counts into {@code nC} (ITU-T H.264 9.2.1).
     *
     * @param a the left count, or -1
     * @param b the upper count, or -1
     * @return {@code nC}
     */
    private static int nC(int a, int b) {
        if (a >= 0 && b >= 0) {
            return (a + b + 1) >> 1;
        }
        if (a >= 0) {
            return a;
        }
        return Math.max(b, 0);
    }

    /**
     * Parses the residual of the current macroblock with CAVLC.
     *
     * @param i16 whether the macroblock is an Intra_16x16 macroblock with {@code base_mode_flag}
     * 0
     */
    private void residualCavlc(boolean i16) {
        var counts = layer.totalCoeff;
        var base = mbAddr * 24;
        Arrays.fill(counts, base, base + 24, (byte) 0);
        var start = scanStart;
        var end = scanEnd;
        var lumaMax = end - start + 1;
        var acMax = end - Math.max(1, start) + 1;
        if (start == 0 && i16) {
            var count = cavlc.scalableBlock(bits, nC(leftLumaCount(0, 0), topLumaCount(0, 0)), 0, 15, 16, -1,
                    dcLevels, 0);
            if (count > 0) {
                codedLuma = 0xFFFF;
            }
        }
        for (var b8 = 0; b8 < 4; b8++) {
            for (var b4 = 0; b4 < 4; b4++) {
                var blk = b8 * 4 + b4;
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                if ((cbpLuma & (1 << b8)) == 0) {
                    continue;
                }
                var context = nC(leftLumaCount(x, y), topLumaCount(x, y));
                int count;
                if (i16) {
                    count = cavlc.scalableBlock(bits, context, Math.max(0, start - 1), end - 1, acMax, remapRange,
                            acLevels, blk * 16);
                } else if (transform8x8) {
                    count = cavlc.scalableBlock(bits, context, start, end, lumaMax, remapRange, values, 0);
                    for (var i = start; i <= end; i++) {
                        lumaLevels[b8 * 64 + 4 * i + b4] = values[i];
                    }
                } else {
                    count = cavlc.scalableBlock(bits, context, start, end, lumaMax, remapRange, lumaLevels, blk * 16);
                }
                counts[base + y * 4 + x] = (byte) count;
                if (count > 0) {
                    codedLuma |= transform8x8 ? 0xF << (b8 * 4) : 1 << blk;
                }
            }
        }
        if ((cbpChroma & 3) != 0 && start == 0) {
            for (var c = 0; c < 2; c++) {
                cavlc.scalableBlock(bits, -1, 0, 3, 4, -1, chromaDcLevels, c * 4);
            }
        }
        if ((cbpChroma & 2) != 0) {
            for (var c = 0; c < 2; c++) {
                for (var b = 0; b < 4; b++) {
                    var x = b & 1;
                    var y = b >> 1;
                    var context = nC(leftChromaCount(c, x, y), topChromaCount(c, x, y));
                    var count = cavlc.scalableBlock(bits, context, Math.max(0, start - 1), end - 1, acMax, remapRange,
                            chromaAcLevels, (c * 4 + b) * 16);
                    counts[base + 16 + c * 4 + b] = (byte) count;
                }
            }
        }
    }

    /**
     * Parses the residual of the current macroblock with CABAC.
     *
     * @param intra whether the macroblock has an intra prediction mode
     * @param i16 whether the macroblock is an Intra_16x16 macroblock with {@code base_mode_flag}
     * 0
     */
    private void residualCabac(boolean intra, boolean i16) {
        var counts = layer.totalCoeff;
        var base = mbAddr * 24;
        Arrays.fill(counts, base, base + 24, (byte) 0);
        var dcFlags = 0;
        var start = scanStart;
        var end = scanEnd;
        if (start == 0 && i16) {
            var inc = dcCondition(mbA, 0, intra) + 2 * dcCondition(mbB, 0, intra);
            if (cabac.decision(CabacTables.CBF_OFFSET[0] + inc) != 0) {
                dcFlags |= 1;
                codedLuma = 0xFFFF;
                readCabacBlock(0, 0, 15, dcLevels, 0);
            }
        }
        for (var b8 = 0; b8 < 4; b8++) {
            if ((cbpLuma & (1 << b8)) == 0) {
                continue;
            }
            if (transform8x8) {
                readCabacBlock(5, 4 * start, 4 * end + 3, lumaLevels, b8 * 64);
                var x = (b8 & 1) * 2;
                var y = (b8 >> 1) * 2;
                counts[base + y * 4 + x] = 1;
                counts[base + y * 4 + x + 1] = 1;
                counts[base + y * 4 + x + 4] = 1;
                counts[base + y * 4 + x + 5] = 1;
                codedLuma |= 0xF << (b8 * 4);
                continue;
            }
            for (var b4 = 0; b4 < 4; b4++) {
                var blk = b8 * 4 + b4;
                var x = H264Tables.BLOCK_X[blk];
                var y = H264Tables.BLOCK_Y[blk];
                var category = i16 ? 1 : 2;
                var inc = lumaBlockCondition(x, y, true, intra) + 2 * lumaBlockCondition(x, y, false, intra);
                if (cabac.decision(CabacTables.CBF_OFFSET[category] + inc) != 0) {
                    counts[base + y * 4 + x] = 1;
                    codedLuma |= 1 << blk;
                    if (i16) {
                        readCabacBlock(1, Math.max(0, start - 1), end - 1, acLevels, blk * 16);
                    } else {
                        readCabacBlock(2, start, end, lumaLevels, blk * 16);
                    }
                }
            }
        }
        if ((cbpChroma & 3) != 0 && start == 0) {
            for (var c = 0; c < 2; c++) {
                var inc = dcCondition(mbA, 1 + c, intra) + 2 * dcCondition(mbB, 1 + c, intra);
                if (cabac.decision(CabacTables.CBF_OFFSET[3] + inc) != 0) {
                    dcFlags |= 2 << c;
                    readCabacBlock(3, 0, 3, chromaDcLevels, c * 4);
                }
            }
        }
        if ((cbpChroma & 2) != 0) {
            for (var c = 0; c < 2; c++) {
                for (var b = 0; b < 4; b++) {
                    var x = b & 1;
                    var y = b >> 1;
                    var inc = chromaBlockCondition(c, x, y, true, intra) + 2 * chromaBlockCondition(c, x, y, false, intra);
                    if (cabac.decision(CabacTables.CBF_OFFSET[4] + inc) != 0) {
                        counts[base + 16 + c * 4 + b] = 1;
                        readCabacBlock(4, Math.max(0, start - 1), end - 1, chromaAcLevels, (c * 4 + b) * 16);
                    }
                }
            }
        }
        layer.dcFlags[mbAddr] = (byte) dcFlags;
    }

    /**
     * Decodes one CABAC residual block with {@code coded_block_flag} 1 into a coefficient list.
     *
     * @param category the {@code ctxBlockCat}
     * @param start the first coded position
     * @param end the last coded position
     * @param target receives the levels at their list positions
     * @param offset the index of list position 0 in {@code target}
     */
    private void readCabacBlock(int category, int start, int end, int[] target, int offset) {
        var count = cabac.residualBlock(category, start, end, indices, values);
        for (var k = 0; k < count; k++) {
            target[offset + indices[k]] = values[k];
        }
    }

    /**
     * Returns the {@code coded_block_flag} context condition of a neighboring DC block.
     *
     * @param addr the neighbor address, or -1
     * @param bit the DC flag: 0 luma, 1 Cb, 2 Cr
     * @param intra whether the current macroblock has an intra prediction mode
     * @return the condition
     */
    private int dcCondition(int addr, int bit, boolean intra) {
        if (addr < 0) {
            return intra ? 1 : 0;
        }
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_PCM) {
            return 1;
        }
        if (bit == 0) {
            return category == SvcLayer.SYNTAX_I16 && (layer.dcFlags[addr] & 1) != 0 ? 1 : 0;
        }
        if (category == SvcLayer.SYNTAX_SKIP || ((layer.cbp[addr] >> 4) & 3) == 0) {
            return 0;
        }
        return (layer.dcFlags[addr] & (1 << bit)) != 0 ? 1 : 0;
    }

    /**
     * Returns the {@code coded_block_flag} context condition of the luma block left of or above a
     * block of the current macroblock.
     *
     * @param x the block column
     * @param y the block row
     * @param left whether the left neighbor is wanted rather than the upper one
     * @param intra whether the current macroblock has an intra prediction mode
     * @return the condition
     */
    private int lumaBlockCondition(int x, int y, boolean left, boolean intra) {
        int addr;
        int raster;
        if (left) {
            addr = x > 0 ? mbAddr : mbA;
            raster = x > 0 ? y * 4 + x - 1 : y * 4 + 3;
        } else {
            addr = y > 0 ? mbAddr : mbB;
            raster = y > 0 ? (y - 1) * 4 + x : 12 + x;
        }
        if (addr < 0) {
            return intra ? 1 : 0;
        }
        if (addr == mbAddr) {
            return layer.totalCoeff[addr * 24 + raster] != 0 ? 1 : 0;
        }
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_PCM) {
            return 1;
        }
        if (category == SvcLayer.SYNTAX_SKIP) {
            return 0;
        }
        var b8 = (raster >> 3) * 2 + ((raster & 3) >> 1);
        if ((layer.cbp[addr] & (1 << b8)) == 0) {
            return 0;
        }
        return layer.totalCoeff[addr * 24 + raster] != 0 ? 1 : 0;
    }

    /**
     * Returns the {@code coded_block_flag} context condition of the chroma AC block left of or
     * above a block of the current macroblock.
     *
     * @param component 0 for Cb, 1 for Cr
     * @param x the block column, 0 or 1
     * @param y the block row, 0 or 1
     * @param left whether the left neighbor is wanted rather than the upper one
     * @param intra whether the current macroblock has an intra prediction mode
     * @return the condition
     */
    private int chromaBlockCondition(int component, int x, int y, boolean left, boolean intra) {
        int addr;
        int index;
        if (left) {
            addr = x > 0 ? mbAddr : mbA;
            index = x > 0 ? y * 2 : y * 2 + 1;
        } else {
            addr = y > 0 ? mbAddr : mbB;
            index = y > 0 ? x : 2 + x;
        }
        if (addr < 0) {
            return intra ? 1 : 0;
        }
        var stored = layer.totalCoeff[addr * 24 + 16 + component * 4 + index];
        if (addr == mbAddr) {
            return stored != 0 ? 1 : 0;
        }
        var category = layer.syntax[addr] & 0xF;
        if (category == SvcLayer.SYNTAX_PCM) {
            return 1;
        }
        if (category == SvcLayer.SYNTAX_SKIP || ((layer.cbp[addr] >> 4) & 3) != 2) {
            return 0;
        }
        return stored != 0 ? 1 : 0;
    }
}
