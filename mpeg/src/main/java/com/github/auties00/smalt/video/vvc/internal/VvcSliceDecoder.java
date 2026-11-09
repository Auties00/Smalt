/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/CABACReader.cpp, source/Lib/CommonLib/UnitPartitioner.cpp,
 * source/Lib/CommonLib/ContextModelling.cpp, source/Lib/CommonLib/UnitTools.cpp). Copyright (c) 2018-2026,
 * Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Parses the slice data of a slice (ITU-T H.266 7.3.11) and hands every coding unit to the reconstruction as soon
 * as it is parsed.
 *
 * <p>Instances are not thread-safe; one instance decodes the slices of one picture after the other.
 */
final class VvcSliceDecoder {
    /**
     * The chroma intra mode of each {@code intra_chroma_pred_mode} below 4 (8.4.3): planar, vertical, horizontal
     * and DC.
     */
    private static final int[] CHROMA_MODES = {0, 50, 18, 1};
    /**
     * No split: the node is a coding unit.
     */
    static final int SPLIT_NONE = 0;
    /**
     * A quadtree split.
     */
    static final int SPLIT_QT = 1;
    /**
     * A horizontal binary split.
     */
    static final int SPLIT_BT_HOR = 2;
    /**
     * A vertical binary split.
     */
    static final int SPLIT_BT_VER = 3;
    /**
     * A horizontal ternary split.
     */
    static final int SPLIT_TT_HOR = 4;
    /**
     * A vertical ternary split.
     */
    static final int SPLIT_TT_VER = 5;
    /**
     * The split of a CTU root, before any coded split.
     */
    static final int SPLIT_CTU = 6;

    /**
     * {@code MODE_TYPE_ALL}: the coding units of a node may use any prediction mode.
     */
    static final int MODE_TYPE_ALL = 0;
    /**
     * {@code MODE_TYPE_INTER}: the coding units of a node are inter coded.
     */
    static final int MODE_TYPE_INTER = 1;
    /**
     * {@code MODE_TYPE_INTRA}: the coding units of a node are intra or IBC coded.
     */
    static final int MODE_TYPE_INTRA = 2;

    /**
     * The context offset of {@code split_cu_flag}, {@code split_qt_flag} and the multi-type tree flags by the depth of
     * the parent split kind.
     */
    private static final int[] SPLIT_CTX_OFFSET = {0, 0, 0, 3, 3, 6, 6};
    /**
     * The context offset of the last significant coefficient prefixes of luma by the base 2 logarithm of the block
     * side.
     */
    private static final int[] LAST_PREFIX_CTX = {0, 0, 0, 3, 6, 10, 15, 21};
    /**
     * The prefix of each last significant coefficient position ({@code GroupIdx}).
     */
    private static final int[] GROUP_IDX = {0, 1, 2, 3, 4, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 9,
            9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11,
            11, 11, 11, 11, 11, 11, 11, 11, 11};
    /**
     * The smallest position of each last significant coefficient prefix.
     */
    private static final int[] MIN_IN_GROUP = {0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96};
    /**
     * The Rice parameter of each sum of neighboring absolute levels ({@code cRiceParam} of Table 128).
     */
    private static final int[] RICE_TABLE = {0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 3, 3, 3, 3};
    /**
     * The transitions of the dependent quantization state machine, two bits per state and parity ({@code
     * QStateTransTable} of equation 1612).
     */
    private static final int QSTATE_TRANSITIONS = 32040;

    /**
     * The per picture maps of the picture being decoded.
     */
    private final VvcFrame f;
    /**
     * The arithmetic decoder of the slice.
     */
    private final VvcCabac cabac = new VvcCabac();
    /**
     * The context variables saved after the first CTU of a CTU row, which start the next row of wavefronts.
     */
    private final VvcCabac wppStore = new VvcCabac();
    /**
     * The reconstruction of the coding units.
     */
    private final VvcRecon recon;
    /**
     * The motion derivation of the coding units.
     */
    final VvcMotion motion;

    /**
     * The lane of the decoder: the pool its coding units come from in the picture maps.
     */
    final int lane;

    /**
     * The slice header the motion derivation and reconstruction of the decoder were last prepared for.
     */
    private VvcSliceHeader prepared;

    /**
     * The sequence parameter set of the slice.
     */
    private VvcSps sps;
    /**
     * The picture parameter set of the slice.
     */
    private VvcPps pps;
    /**
     * The picture header of the slice.
     */
    private VvcPictureHeader ph;
    /**
     * The header of the slice being decoded.
     */
    VvcSliceHeader sh;
    /**
     * The index of the slice in its picture.
     */
    private int sliceIndex;
    /**
     * Whether the intra slices of the sequence code luma and chroma in separate trees.
     */
    private boolean dualTreeIntra;
    /**
     * The horizontal chroma subsampling shift.
     */
    private int sx;
    /**
     * The vertical chroma subsampling shift.
     */
    private int sy;
    /**
     * {@code sps_chroma_format_idc}.
     */
    private int chromaFormat;
    /**
     * The column of the CTU being decoded, in CTUs.
     */
    private int ctuX;
    /**
     * The row of the CTU being decoded, in CTUs.
     */
    private int ctuY;
    /**
     * The address of the CTU being decoded in the picture's raster scan.
     */
    private int ctuAddr;

    /**
     * {@code qPY_PREV}: the QP of the last coding unit of the previous quantization group in decoding order.
     */
    private int prevQpY;
    /**
     * {@code qPY_PRED} of the current quantization group.
     */
    private int qgQpY;
    /**
     * The luma column of the current quantization group.
     */
    private int qgX;
    /**
     * The luma row of the current quantization group.
     */
    private int qgY;
    /**
     * {@code IsCuQpDeltaCoded}.
     */
    private boolean qpDeltaCoded;
    /**
     * {@code CuQpDeltaVal}.
     */
    private int cuQpDeltaVal;
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
     * {@code CuQpOffsetCbCr}.
     */
    private int cuQpOffsetCbCr;
    /**
     * Whether the next quantization group is the first of a slice or tile.
     */
    private boolean firstQgInSliceOrTile;
    /**
     * Whether the next quantization group is the first of a CTU row of wavefronts.
     */
    private boolean firstQgInCtbRow;

    /**
     * {@code LfnstDcOnly} of the coding unit being parsed.
     */
    private boolean lfnstDcOnly;
    /**
     * {@code LfnstZeroOutSigCoeffFlag} of the coding unit being parsed.
     */
    private boolean lfnstZeroOut;
    /**
     * {@code MtsDcOnly} of the coding unit being parsed.
     */
    private boolean mtsDcOnly;
    /**
     * {@code MtsZeroOutSigCoeffFlag} of the coding unit being parsed.
     */
    private boolean mtsZeroOut;
    /**
     * Whether {@code tu_y_coded_flag} of the next sub-partition is inferred.
     */
    private boolean inferTuCbfLuma;
    /**
     * {@code tu_y_coded_flag} of the previous sub-partition.
     */
    private boolean prevTuCbfY;

    /**
     * The transform units of the coding unit being parsed.
     */
    final VvcTus tus = new VvcTus();

    /**
     * The absolute levels of the coefficients of the block being parsed, after the first pass.
     */
    private final int[] absLevel = new int[64 * 64];
    /**
     * The significance of each coefficient of the block being parsed.
     */
    private final byte[] sigFlags = new byte[64 * 64];
    /**
     * The coefficient signs of the subblock being parsed.
     */
    private final int[] signBuf = new int[16];
    /**
     * {@code sb_coded_flag} of each subblock of the block being parsed.
     */
    private final int[] sbCoded = new int[16 * 16];

    /**
     * Constructs the slice decoder of a picture size.
     *
     * @param frame the per picture maps
     * @param recon the reconstruction
     * @param motion the motion derivation
     * @param lane the lane, from 0 to {@link VvcFrame#MAX_LANES} minus one
     */
    VvcSliceDecoder(VvcFrame frame, VvcRecon recon, VvcMotion motion, int lane) {
        this.f = frame;
        this.recon = recon;
        this.motion = motion;
        this.lane = lane;
    }

    /**
     * {@return the reconstruction of the decoder}
     */
    VvcRecon recon() {
        return recon;
    }

    /**
     * Decodes the slice data of a slice on the calling thread, one substream after the other.
     *
     * @param header the slice header
     * @param data the unescaped NAL unit payload
     * @param start the first byte of the slice data
     * @param end one past the last byte of the payload
     * @param slice the index of the slice in the picture
     * @throws MediaException.Malformed if the slice data is corrupt
     */
    void decode(VvcSliceHeader header, byte[] data, int start, int end, int slice) {
        prepare(header, slice, true);
        var starts = substreams(header);
        for (var k = 0; k < starts.length - 1; k++) {
            decodeSubstream(data, start, end, k, starts, null);
        }
    }

    /**
     * Prepares the decoder for the substreams of a slice: the motion derivation and the reconstruction once per
     * slice, and the slice's parameters.
     *
     * @param header the slice header
     * @param slice the index of the slice in the picture
     * @param primary whether the decoder also completes the header with the parameter sets it refers to, which one
     * decoder does for each slice before any substream is decoded
     * @throws MediaException.Malformed if the slice refers to a missing parameter set
     */
    void prepare(VvcSliceHeader header, int slice, boolean primary) {
        sh = header;
        sps = header.sps;
        pps = header.pps;
        ph = header.ph;
        sliceIndex = slice;
        chromaFormat = sps.chromaFormatIdc;
        sx = sps.chromaShiftX();
        sy = sps.chromaShiftY();
        dualTreeIntra = sh.isIntra() && sps.dualTree;
        if (prepared != header) {
            motion.beginSlice(header, slice);
            recon.beginSlice(header, primary);
            prepared = header;
        }
    }

    /**
     * Returns the substreams of a slice: the CTU indexes, into {@link VvcSliceHeader#ctus}, at which a tile or, with
     * wavefront parallel processing, a CTU row of a tile begins, and the CTU count last.
     *
     * @param header the slice header
     * @return the first CTU index of each substream, then the number of CTUs of the slice
     */
    static int[] substreams(VvcSliceHeader header) {
        var ctus = header.ctus;
        var pps = header.pps;
        var wpp = header.sps.entropyCodingSync;
        var w = pps.widthInCtus;
        var starts = new int[ctus.length + 1];
        var count = 0;
        for (var i = 0; i < ctus.length; i++) {
            var ctuX = ctus[i] % w;
            var ctuY = ctus[i] / w;
            var tileColStart = pps.tileColBd[pps.ctuToTileCol[ctuX]];
            var tileRowStart = pps.tileRowBd[pps.ctuToTileRow[ctuY]];
            if (i == 0 || (ctuX == tileColStart && (ctuY == tileRowStart || wpp))) {
                starts[count++] = i;
            }
        }
        starts[count++] = ctus.length;
        return Arrays.copyOf(starts, count);
    }

    /**
     * Decodes one substream of the slice the decoder is prepared for.
     *
     * <p>With a wavefront, the substream waits for the substream of the CTU row above it to decode the CTU above
     * each of its CTUs, starts from the CABAC contexts that substream stored, and publishes its own progress; without
     * one, the substreams of the slice are decoded in order on the calling thread.
     *
     * @param data the unescaped NAL unit payload
     * @param start the first byte of the slice data
     * @param end one past the last byte of the payload
     * @param k the substream
     * @param starts the substreams of the slice, from {@link #substreams(VvcSliceHeader)}
     * @param wavefront the coordination of concurrently decoded substreams, or {@code null}
     * @throws MediaException.Malformed if the substream is corrupt, or a substream it waits for failed
     */
    void decodeSubstream(byte[] data, int start, int end, int k, int[] starts, VvcWavefront wavefront) {
        var initType = sh.sliceType == VvcSliceHeader.I ? 2 : sh.sliceType == VvcSliceHeader.P
                ? (sh.cabacInit ? 0 : 1) : (sh.cabacInit ? 1 : 0);
        var ctus = sh.ctus;
        var w = pps.widthInCtus;
        var first = starts[k];
        var last = starts[k + 1];
        for (var i = first; i < last; i++) {
            ctuAddr = ctus[i];
            ctuX = ctuAddr % w;
            ctuY = ctuAddr / w;
            var tileColStart = pps.tileColBd[pps.ctuToTileCol[ctuX]];
            var tileRowStart = pps.tileRowBd[pps.ctuToTileRow[ctuY]];
            var newTile = i == 0 || (ctuX == tileColStart && ctuY == tileRowStart);
            var newRow = ctuX == tileColStart;
            if (wavefront != null) {
                wavefront.awaitAbove(k, i - first);
            }
            f.ctuSlice[ctuAddr] = sliceIndex;
            f.ctuHeader[ctuAddr] = sh;
            if (i == 0) {
                cabac.initContexts(initType, sh.sliceQp);
                cabac.start(data, start, end);
            } else if (i == first) {
                var from = start + (k - 1 < sh.entryPoints.length ? sh.entryPoints[k - 1] : end - start);
                if (from > end) {
                    throw new MediaException.Malformed("vvc entry point beyond the slice data");
                }
                if (!newTile && sps.entropyCodingSync) {
                    var above = ctuAddr - w;
                    if (ctuY > tileRowStart && f.ctuAvailable(above, ctuAddr)) {
                        cabac.copyContextsFrom(wavefront != null ? wavefront.store(k - 1) : wppStore);
                    } else {
                        cabac.initContexts(initType, sh.sliceQp);
                    }
                } else {
                    cabac.initContexts(initType, sh.sliceQp);
                }
                cabac.start(data, from, end);
            }
            if (i == 0 || newTile) {
                prevQpY = sh.sliceQp;
                firstQgInSliceOrTile = true;
            }
            firstQgInCtbRow = newRow;
            if (newRow) {
                motion.resetHistory();
            }
            decodeCtu();
            if (sps.entropyCodingSync && ctuX == tileColStart) {
                (wavefront != null ? wavefront.store(k) : wppStore).copyContextsFrom(cabac);
            }
            var lastInSlice = i == ctus.length - 1;
            var tileColEnd = pps.tileColBd[pps.ctuToTileCol[ctuX] + 1] - 1;
            var tileRowEnd = pps.tileRowBd[pps.ctuToTileRow[ctuY] + 1] - 1;
            if (lastInSlice) {
                if (cabac.decodeTerminate() != 1) {
                    throw new MediaException.Malformed("vvc missing end_of_slice_one_bit");
                }
            } else if (ctuX == tileColEnd && (ctuY == tileRowEnd || sps.entropyCodingSync)) {
                if (cabac.decodeTerminate() != 1) {
                    throw new MediaException.Malformed("vvc missing end_of_subset_one_bit");
                }
            }
            if (cabac.overrun()) {
                throw new MediaException.Malformed("vvc slice data truncated");
            }
            if (wavefront != null) {
                wavefront.advance(k, i - first + 1);
            }
        }
    }

    /**
     * Decodes the CTU at {@link #ctuX} and {@link #ctuY}: its SAO and ALF parameters, then its coding tree.
     */
    private void decodeCtu() {
        var x0 = ctuX << sps.log2CtuSize;
        var y0 = ctuY << sps.log2CtuSize;
        if (sh.saoLuma || sh.saoChroma) {
            parseSao();
        } else {
            for (var c = 0; c < 3; c++) {
                f.saoType[ctuAddr][c] = 0;
            }
        }
        parseAlf();
        if (dualTreeIntra) {
            dualTreeImplicitQtSplit(x0, y0, sps.ctuSize, 0, 0);
        } else {
            codingTree(x0, y0, sps.ctuSize, sps.ctuSize, true, true, 0, 0, 0, 0, 0, VvcCu.TREE_SINGLE,
                    MODE_TYPE_ALL, SPLIT_CTU, 0, 0);
        }
    }

    /**
     * Parses {@code sao( )} of the current CTU (7.3.11.3).
     */
    private void parseSao() {
        var sao = ctuAddr;
        for (var c = 0; c < 3; c++) {
            f.saoType[sao][c] = 0;
        }
        var chroma = sh.saoChroma && chromaFormat != 0;
        var merge = -1;
        if (ctuX > 0 && f.ctuAvailable(ctuAddr - 1, ctuAddr)) {
            if (cabac.decodeBin(VvcContexts.SAO_MERGE_FLAG) != 0) {
                merge = 0;
            }
        }
        if (merge < 0 && ctuY > 0 && f.ctuAvailable(ctuAddr - pps.widthInCtus, ctuAddr)) {
            if (cabac.decodeBin(VvcContexts.SAO_MERGE_FLAG) != 0) {
                merge = 1;
            }
        }
        if (merge >= 0) {
            var src = merge == 0 ? ctuAddr - 1 : ctuAddr - pps.widthInCtus;
            for (var c = 0; c < 3; c++) {
                var enabled = c == 0 ? sh.saoLuma : chroma;
                if (enabled) {
                    f.saoType[sao][c] = f.saoType[src][c];
                    f.saoBand[sao][c] = f.saoBand[src][c];
                    System.arraycopy(f.saoOffset[src][c], 0, f.saoOffset[sao][c], 0, 4);
                } else {
                    f.saoType[sao][c] = 0;
                }
            }
            return;
        }
        var offsetBits = Math.min(sps.bitDepth, 10) - 5;
        var maxOffset = (1 << offsetBits) - 1;
        for (var c = 0; c < 3; c++) {
            if (c == 0 ? !sh.saoLuma : !chroma) {
                continue;
            }
            if (c == 2) {
                f.saoType[sao][2] = f.saoType[sao][1];
            } else if (cabac.decodeBin(VvcContexts.SAO_TYPE_IDX) != 0) {
                f.saoType[sao][c] = cabac.decodeBypass() != 0 ? 2 : 1;
            } else {
                f.saoType[sao][c] = 0;
            }
            if (f.saoType[sao][c] == 0) {
                continue;
            }
            var offsets = f.saoOffset[sao][c];
            for (var k = 0; k < 4; k++) {
                offsets[k] = unaryMaxBypass(maxOffset);
            }
            var scale = sps.bitDepth - Math.min(sps.bitDepth, 10);
            if (f.saoType[sao][c] == 1) {
                for (var k = 0; k < 4; k++) {
                    if (offsets[k] != 0 && cabac.decodeBypass() != 0) {
                        offsets[k] = -offsets[k];
                    }
                }
                f.saoBand[sao][c] = cabac.decodeBypassBins(5);
            } else {
                offsets[2] = -offsets[2];
                offsets[3] = -offsets[3];
                if (c == 0) {
                    f.saoBand[sao][0] = cabac.decodeBypassBins(2);
                } else if (c == 1) {
                    f.saoBand[sao][1] = cabac.decodeBypassBins(2);
                } else {
                    f.saoBand[sao][2] = f.saoBand[sao][1];
                }
            }
            for (var k = 0; k < 4; k++) {
                offsets[k] = offsets[k] * (1 << scale);
            }
        }
    }

    /**
     * Parses the ALF and CC-ALF syntax of the current CTU ({@code alf_ctb_flag} and the following elements, 7.3.11.2).
     */
    private void parseAlf() {
        var ctu = ctuAddr;
        var leftAvail = ctuX > 0 && f.ctuAvailable(ctu - 1, ctu);
        var aboveAvail = ctuY > 0 && f.ctuAvailable(ctu - pps.widthInCtus, ctu);
        var left = ctu - 1;
        var above = ctu - pps.widthInCtus;
        for (var c = 0; c < 3; c++) {
            f.alfEnabled[ctu][c] = false;
        }
        f.ccAlfIdx[ctu][0] = 0;
        f.ccAlfIdx[ctu][1] = 0;
        if (sh.alfEnabled) {
            for (var c = 0; c < 3; c++) {
                var enabled = c == 0 || (c == 1 ? sh.alfCb : sh.alfCr);
                if (!enabled) {
                    continue;
                }
                var ctx = (leftAvail && f.alfEnabled[left][c] ? 1 : 0) + (aboveAvail && f.alfEnabled[above][c] ? 1 : 0);
                f.alfEnabled[ctu][c] = cabac.decodeBin(VvcContexts.CTB_ALF_FLAG + c * 3 + ctx) != 0;
                if (c == 0 && f.alfEnabled[ctu][0]) {
                    var numAps = sh.numAlfApsLuma;
                    int index;
                    if (numAps > 0 && cabac.decodeBin(VvcContexts.ALF_USE_TEMPORAL_FILT) != 0) {
                        index = numAps > 1 ? truncatedBinary(numAps) : 0;
                        if (index >= numAps) {
                            throw new MediaException.Malformed("vvc alf_luma_prev_filter_idx out of range");
                        }
                        index += 16;
                    } else {
                        index = truncatedBinary(16);
                    }
                    f.alfFilterSet[ctu] = index;
                }
                if (c > 0) {
                    f.alfChromaAlt[ctu][c - 1] = 0;
                    if (f.alfEnabled[ctu][c]) {
                        var aps = recon.alfAps(sh.alfApsChroma);
                        var alts = aps == null ? 1 : aps.alfChromaNumAlts;
                        var decoded = 0;
                        while (decoded < alts - 1
                                && cabac.decodeBin(VvcContexts.CTB_ALF_ALTERNATIVE + c - 1) != 0) {
                            decoded++;
                        }
                        f.alfChromaAlt[ctu][c - 1] = decoded;
                    }
                }
            }
        }
        for (var c = 0; c < 2 && chromaFormat != 0; c++) {
            var enabled = c == 0 ? sh.ccAlfCb : sh.ccAlfCr;
            if (!enabled) {
                continue;
            }
            var ctx = (leftAvail && f.ccAlfIdx[left][c] != 0 ? 1 : 0)
                    + (aboveAvail && f.ccAlfIdx[above][c] != 0 ? 1 : 0) + (c == 1 ? 3 : 0);
            var idc = cabac.decodeBin(VvcContexts.CC_ALF_FILTER_CONTROL_FLAG + ctx);
            if (idc != 0) {
                var aps = recon.alfAps(c == 0 ? sh.ccAlfCbAps : sh.ccAlfCrAps);
                var count = aps == null ? 1 : aps.ccAlfCount[c];
                while (idc != count && cabac.decodeBypass() != 0) {
                    idc++;
                }
            }
            f.ccAlfIdx[ctu][c] = idc;
        }
    }

    /**
     * Splits the CTU of a dual tree intra slice into 64x64 nodes, each holding a luma and a chroma coding tree
     * (7.3.11.4, {@code dual_tree_implicit_qt_split( )}).
     *
     * @param x0 the luma column of the node
     * @param y0 the luma row of the node
     * @param size the node size
     * @param cqtDepth the quadtree depth of the node
     * @param series the split series of the node
     */
    private void dualTreeImplicitQtSplit(int x0, int y0, int size, int cqtDepth, long series) {
        var cbSubdiv = 2 * cqtDepth;
        if (size > 64) {
            if (pps.cuQpDelta && cbSubdiv <= ph.cuQpDeltaSubdivIntra) {
                startQuantGroup(x0, y0);
            }
            if (sh.cuChromaQpOffsetEnabled && cbSubdiv <= ph.cuChromaQpOffsetSubdivIntra) {
                resetChromaQpOffset();
            }
            var half = size / 2;
            var child = series | ((long) SPLIT_QT << (3 * cqtDepth));
            dualTreeImplicitQtSplit(x0, y0, half, cqtDepth + 1, child);
            if (x0 + half < pps.width) {
                dualTreeImplicitQtSplit(x0 + half, y0, half, cqtDepth + 1, child);
            }
            if (y0 + half < pps.height) {
                dualTreeImplicitQtSplit(x0, y0 + half, half, cqtDepth + 1, child);
            }
            if (x0 + half < pps.width && y0 + half < pps.height) {
                dualTreeImplicitQtSplit(x0 + half, y0 + half, half, cqtDepth + 1, child);
            }
        } else {
            codingTree(x0, y0, size, size, true, false, cbSubdiv, cqtDepth, 0, 0, 0, VvcCu.TREE_LUMA,
                    MODE_TYPE_ALL, cqtDepth == 0 ? SPLIT_CTU : SPLIT_QT, series, cqtDepth);
            codingTree(x0, y0, size, size, false, true, cbSubdiv, cqtDepth, 0, 0, 0, VvcCu.TREE_CHROMA,
                    MODE_TYPE_ALL, cqtDepth == 0 ? SPLIT_CTU : SPLIT_QT, series, cqtDepth);
        }
    }

    /**
     * Starts a quantization group at a position: clears the QP delta and records its corner.
     *
     * @param x0 the luma column of the group
     * @param y0 the luma row of the group
     */
    private void startQuantGroup(int x0, int y0) {
        qpDeltaCoded = false;
        cuQpDeltaVal = 0;
        qgX = x0;
        qgY = y0;
        qgQpY = Integer.MIN_VALUE;
    }

    /**
     * Starts a chroma QP offset group: clears the offsets.
     */
    private void resetChromaQpOffset() {
        chromaQpOffsetCoded = false;
        cuQpOffsetCb = 0;
        cuQpOffsetCr = 0;
        cuQpOffsetCbCr = 0;
    }

    /**
     * Returns the index of the partition constraint set of a tree in the current slice: 0 for intra slices, 2 for the
     * chroma tree of a dual tree, 1 for inter slices.
     *
     * @param treeType the tree type
     * @return the index of the constraint set
     */
    private int constraintSet(int treeType) {
        if (!sh.isIntra()) {
            return 1;
        }
        return dualTreeIntra && treeType == VvcCu.TREE_CHROMA ? 2 : 0;
    }

    /**
     * Parses {@code coding_tree( )} (7.3.11.4) recursively: the split syntax, then the coding units of the leaves.
     *
     * @param x0 the luma column of the node
     * @param y0 the luma row of the node
     * @param w the node width
     * @param h the node height
     * @param qgOnY whether the node may start a luma quantization group
     * @param qgOnC whether the node may start a chroma QP offset group
     * @param cbSubdiv {@code cbSubdiv}
     * @param cqtDepth the quadtree depth
     * @param mttDepth the multi-type tree depth
     * @param depthOffset {@code depthOffset}
     * @param partIdx {@code partIdx}
     * @param treeType the tree type
     * @param modeType the mode type
     * @param parentSplit the split that produced the node
     * @param series the splits leading to the node, three bits per depth
     * @param depth the depth of the node
     */
    private void codingTree(int x0, int y0, int w, int h, boolean qgOnY, boolean qgOnC, int cbSubdiv, int cqtDepth,
                            int mttDepth, int depthOffset, int partIdx, int treeType, int modeType, int parentSplit,
                            long series, int depth) {
        var chroma = treeType == VvcCu.TREE_CHROMA;
        var localChroma = chroma && modeType == MODE_TYPE_INTRA && !dualTreeIntra;
        var set = constraintSet(treeType);
        var minQt = ph.minQtSize[set];
        var maxBt = ph.maxBtSize[set];
        var maxTt = ph.maxTtSize[set];
        var maxMtt = ph.maxMttDepth[set];
        var minCb = 1 << sps.log2MinCbSize;

        var canNo = true;
        var canQt = true;
        var canBh = true;
        var canBv = true;
        var canTh = true;
        var canTv = true;
        var canBtt = mttDepth < maxMtt + depthOffset;
        var trInPic = x0 + w <= pps.width;
        var blInPic = y0 + h <= pps.height;
        var dualChroma = chroma && !localChroma;
        var cw = w >> sx;
        var chh = h >> sy;
        if (localChroma) {
            canQt = canBh = canBv = canTh = canTv = false;
        } else {
            if (parentSplit != SPLIT_CTU && parentSplit != SPLIT_QT) {
                canQt = false;
            }
            if (w <= minQt) {
                canQt = false;
            }
            if (dualChroma && cw <= 4) {
                canQt = false;
            }
            if (!trInPic || !blInPic) {
                var btAllowed = w <= maxBt && h <= maxBt && w <= 64 && h <= 64 && canBtt;
                canNo = false;
                canTh = false;
                canTv = false;
                canQt |= !btAllowed;
                canBh = btAllowed && !blInPic && (trInPic || !canQt);
                canBv = btAllowed && blInPic && !trInPic;
                canBv &= !dualChroma || cw > 4;
                canQt |= !canBh && !canBv;
            } else {
                canBtt &= w > minCb || h > minCb;
                canBtt &= (w <= maxBt && h <= maxBt) || (w <= maxTt && h <= maxTt);
                if (!canBtt) {
                    canBh = canBv = canTh = canTv = false;
                } else {
                    var allowBt = modeType != MODE_TYPE_INTER || w * h != 32;
                    var allowTt = modeType != MODE_TYPE_INTER || w * h != 64;
                    if (w > maxBt || h > maxBt || !allowBt) {
                        canBh = canBv = false;
                    } else {
                        if ((parentSplit == SPLIT_TT_HOR || parentSplit == SPLIT_TT_VER) && partIdx == 1) {
                            canBh = parentSplit != SPLIT_TT_HOR;
                            canBv = parentSplit != SPLIT_TT_VER;
                        }
                        canBh &= h > minCb && h <= maxBt;
                        canBh &= w <= 64 || h > 64;
                        canBv &= w > minCb && w <= maxBt;
                        canBv &= w > 64 || h <= 64;
                    }
                    if (w > maxTt || h > maxTt || !allowTt || w > 64 || h > 64) {
                        canTh = canTv = false;
                    } else {
                        canTh &= h > 2 * minCb;
                        canTv &= w > 2 * minCb;
                    }
                    if (dualChroma) {
                        var area = cw * chh;
                        canBh &= area > 16;
                        canTh &= area > 32;
                        canBv &= area > 16 && cw > 4;
                        canTv &= area > 32 && cw > 8;
                    }
                }
            }
        }

        var split = SPLIT_NONE;
        var numHor = (canBh ? 1 : 0) + (canTh ? 1 : 0);
        var numVer = (canBv ? 1 : 0) + (canTv ? 1 : 0);
        var numSplit = (canQt ? 2 : 0) + numHor + numVer;
        var nbChroma = chroma;
        var left = f.available(x0 - 1, y0, x0, y0, nbChroma);
        var above = f.available(x0, y0 - 1, x0, y0, nbChroma);
        var isSplit = numSplit > 0;
        if (canNo && isSplit) {
            var ctx = (left != null && left.h < h ? 1 : 0) + (above != null && above.w < w ? 1 : 0)
                    + SPLIT_CTX_OFFSET[numSplit];
            isSplit = cabac.decodeBin(VvcContexts.SPLIT_FLAG + ctx) != 0;
        }
        if (!canNo && !isSplit) {
            throw new MediaException.Malformed("vvc coding tree cannot be split at the picture boundary");
        }
        if (pps.cuQpDelta && qgOnY && cbSubdiv <= cuQpDeltaSubdiv()) {
            startQuantGroup(x0, y0);
        }
        if (sh.cuChromaQpOffsetEnabled && qgOnC && cbSubdiv <= cuChromaQpOffsetSubdiv()) {
            resetChromaQpOffset();
        }
        if (isSplit) {
            var canBtt2 = numHor + numVer > 0;
            var isQt = canQt;
            if (isQt && canBtt2) {
                var ctx = (left != null && left.qtDepth > cqtDepth ? 1 : 0)
                        + (above != null && above.qtDepth > cqtDepth ? 1 : 0) + (cqtDepth < 2 ? 0 : 3);
                isQt = cabac.decodeBin(VvcContexts.SPLIT_QT_FLAG + ctx) != 0;
            }
            if (isQt) {
                split = SPLIT_QT;
            } else {
                var canHor = numHor > 0;
                var isVer = numVer > 0;
                if (isVer && canHor) {
                    int ctx;
                    if (numVer == numHor) {
                        ctx = 0;
                        if (left != null && above != null) {
                            var depAbove = w >> Integer.numberOfTrailingZeros(above.w);
                            var depLeft = h >> Integer.numberOfTrailingZeros(left.h);
                            ctx = depAbove == depLeft ? 0 : depAbove < depLeft ? 1 : 2;
                        }
                    } else {
                        ctx = numVer < numHor ? 3 : 4;
                    }
                    isVer = cabac.decodeBin(VvcContexts.SPLIT_HV_FLAG + ctx) != 0;
                }
                var can14 = isVer ? canTv : canTh;
                var is12 = isVer ? canBv : canBh;
                if (is12 && can14) {
                    var ctx = (mttDepth <= 1 ? 1 : 0) + (isVer ? 2 : 0);
                    is12 = cabac.decodeBin(VvcContexts.SPLIT12_FLAG + ctx) != 0;
                }
                split = isVer ? (is12 ? SPLIT_BT_VER : SPLIT_TT_VER) : (is12 ? SPLIT_BT_HOR : SPLIT_TT_HOR);
            }
            var childMode = modeConstraint(split, modeType, treeType, w, h, left, above);
            var childSeries = series | ((long) split << (3 * depth));
            var childTree = childMode == MODE_TYPE_INTRA && modeType == MODE_TYPE_ALL ? VvcCu.TREE_LUMA : treeType;
            switch (split) {
                case SPLIT_BT_VER -> {
                    var offset = depthOffset + (x0 + w > pps.width ? 1 : 0);
                    var x1 = x0 + w / 2;
                    codingTree(x0, y0, w / 2, h, qgOnY, qgOnC, cbSubdiv + 1, cqtDepth, mttDepth + 1, offset, 0,
                            childTree, childMode, split, childSeries, depth + 1);
                    if (x1 < pps.width) {
                        codingTree(x1, y0, w / 2, h, qgOnY, qgOnC, cbSubdiv + 1, cqtDepth, mttDepth + 1, offset, 1,
                                childTree, childMode, split, childSeries, depth + 1);
                    }
                }
                case SPLIT_BT_HOR -> {
                    var offset = depthOffset + (y0 + h > pps.height ? 1 : 0);
                    var y1 = y0 + h / 2;
                    codingTree(x0, y0, w, h / 2, qgOnY, qgOnC, cbSubdiv + 1, cqtDepth, mttDepth + 1, offset, 0,
                            childTree, childMode, split, childSeries, depth + 1);
                    if (y1 < pps.height) {
                        codingTree(x0, y1, w, h / 2, qgOnY, qgOnC, cbSubdiv + 1, cqtDepth, mttDepth + 1, offset, 1,
                                childTree, childMode, split, childSeries, depth + 1);
                    }
                }
                case SPLIT_TT_VER -> {
                    var nextY = qgOnY && cbSubdiv + 2 <= cuQpDeltaSubdiv();
                    var nextC = qgOnC && cbSubdiv + 2 <= cuChromaQpOffsetSubdiv();
                    codingTree(x0, y0, w / 4, h, nextY, nextC, cbSubdiv + 2, cqtDepth, mttDepth + 1, depthOffset, 0,
                            childTree, childMode, split, childSeries, depth + 1);
                    codingTree(x0 + w / 4, y0, w / 2, h, nextY, nextC, cbSubdiv + 1, cqtDepth, mttDepth + 1,
                            depthOffset, 1, childTree, childMode, split, childSeries, depth + 1);
                    codingTree(x0 + 3 * w / 4, y0, w / 4, h, nextY, nextC, cbSubdiv + 2, cqtDepth, mttDepth + 1,
                            depthOffset, 2, childTree, childMode, split, childSeries, depth + 1);
                }
                case SPLIT_TT_HOR -> {
                    var nextY = qgOnY && cbSubdiv + 2 <= cuQpDeltaSubdiv();
                    var nextC = qgOnC && cbSubdiv + 2 <= cuChromaQpOffsetSubdiv();
                    codingTree(x0, y0, w, h / 4, nextY, nextC, cbSubdiv + 2, cqtDepth, mttDepth + 1, depthOffset, 0,
                            childTree, childMode, split, childSeries, depth + 1);
                    codingTree(x0, y0 + h / 4, w, h / 2, nextY, nextC, cbSubdiv + 1, cqtDepth, mttDepth + 1,
                            depthOffset, 1, childTree, childMode, split, childSeries, depth + 1);
                    codingTree(x0, y0 + 3 * h / 4, w, h / 4, nextY, nextC, cbSubdiv + 2, cqtDepth, mttDepth + 1,
                            depthOffset, 2, childTree, childMode, split, childSeries, depth + 1);
                }
                default -> {
                    var x1 = x0 + w / 2;
                    var y1 = y0 + h / 2;
                    codingTree(x0, y0, w / 2, h / 2, qgOnY, qgOnC, cbSubdiv + 2, cqtDepth + 1, 0, 0, 0, childTree,
                            childMode, split, childSeries, depth + 1);
                    if (x1 < pps.width) {
                        codingTree(x1, y0, w / 2, h / 2, qgOnY, qgOnC, cbSubdiv + 2, cqtDepth + 1, 0, 0, 1,
                                childTree, childMode, split, childSeries, depth + 1);
                    }
                    if (y1 < pps.height) {
                        codingTree(x0, y1, w / 2, h / 2, qgOnY, qgOnC, cbSubdiv + 2, cqtDepth + 1, 0, 0, 2,
                                childTree, childMode, split, childSeries, depth + 1);
                    }
                    if (x1 < pps.width && y1 < pps.height) {
                        codingTree(x1, y1, w / 2, h / 2, qgOnY, qgOnC, cbSubdiv + 2, cqtDepth + 1, 0, 0, 3,
                                childTree, childMode, split, childSeries, depth + 1);
                    }
                }
            }
            if (modeType == MODE_TYPE_ALL && childMode == MODE_TYPE_INTRA) {
                codingTree(x0, y0, w, h, false, qgOnC, cbSubdiv, cqtDepth, mttDepth, 0, 0, VvcCu.TREE_CHROMA,
                        childMode, parentSplit, series, depth);
            }
        } else {
            codingUnit(x0, y0, w, h, cqtDepth, mttDepth, treeType, modeType, series, depth);
        }
    }

    /**
     * {@return {@code CuQpDeltaSubdiv} of the slice type}
     */
    private int cuQpDeltaSubdiv() {
        return sh.isIntra() ? ph.cuQpDeltaSubdivIntra : ph.cuQpDeltaSubdivInter;
    }

    /**
     * {@return {@code CuChromaQpOffsetSubdiv} of the slice type}
     */
    private int cuChromaQpOffsetSubdiv() {
        return sh.isIntra() ? ph.cuChromaQpOffsetSubdivIntra : ph.cuChromaQpOffsetSubdivInter;
    }

    /**
     * Derives {@code ModeTypeCondition} (7.4.12.4) and parses {@code non_inter_flag} when signalled.
     *
     * @param split the split of the node
     * @param modeType the mode type of the node
     * @param treeType the tree type of the node
     * @param w the node width
     * @param h the node height
     * @param left the coding unit left of the node, or {@code null}
     * @param above the coding unit above the node, or {@code null}
     * @return the mode type of the children: {@link #MODE_TYPE_ALL} unless {@code mode_constraint_flag} or the size
     * restricts them
     */
    private int modeConstraint(int split, int modeType, int treeType, int w, int h, VvcCu left, VvcCu above) {
        if (dualTreeIntra || modeType != MODE_TYPE_ALL || chromaFormat == 3 || chromaFormat == 0
                || treeType != VvcCu.TREE_SINGLE) {
            return modeType;
        }
        var minLumaArea = w * h;
        if (split == SPLIT_QT || split == SPLIT_TT_HOR || split == SPLIT_TT_VER) {
            minLumaArea >>= 2;
        } else {
            minLumaArea >>= 1;
        }
        var minChroma = minLumaArea >> (sx + sy);
        var cw = w >> sx;
        var is2xN = (cw == 4 && split == SPLIT_BT_VER) || (cw == 8 && split == SPLIT_TT_VER);
        if (minChroma >= 16 && !is2xN) {
            return modeType;
        }
        if (minLumaArea < 32 || sh.isIntra()) {
            return MODE_TYPE_INTRA;
        }
        var ctx = (above != null && above.predMode == VvcCu.MODE_INTRA)
                || (left != null && left.predMode == VvcCu.MODE_INTRA) ? 1 : 0;
        return cabac.decodeBin(VvcContexts.MODE_CONS_FLAG + ctx) != 0 ? MODE_TYPE_INTRA : MODE_TYPE_INTER;
    }

    /**
     * Parses {@code coding_unit( )} (7.3.11.5) and reconstructs the coding unit.
     *
     * @param x0 the luma column
     * @param y0 the luma row
     * @param w the width
     * @param h the height
     * @param cqtDepth the quadtree depth
     * @param mttDepth the multi-type tree depth
     * @param treeType the tree type
     * @param modeType the mode type
     * @param series the splits leading to the coding unit
     * @param depth the depth of the coding unit
     */
    private void codingUnit(int x0, int y0, int w, int h, int cqtDepth, int mttDepth, int treeType, int modeType,
                            long series, int depth) {
        if (sh.isIntra() && (w > 64 || h > 64)) {
            modeType = MODE_TYPE_INTRA;
        }
        var cu = f.newCu(lane, x0, y0, w, h, treeType, sliceIndex);
        cu.qtDepth = cqtDepth;
        cu.mttDepth = mttDepth;
        cu.splitSeries = series;
        cu.depth = depth;
        cu.modeIntraConstrained = modeType == MODE_TYPE_INTRA;
        cu.modeInterConstrained = modeType == MODE_TYPE_INTER;
        var chroma = treeType == VvcCu.TREE_CHROMA;
        var left = f.available(x0 - 1, y0, x0, y0, chroma);
        var above = f.available(x0, y0 - 1, x0, y0, chroma);
        deriveQp(cu);
        var is4x4 = w == 4 && h == 4;
        int inferredMode;
        if (is4x4 || modeType == MODE_TYPE_INTRA) {
            inferredMode = VvcCu.MODE_INTRA;
        } else if (modeType == MODE_TYPE_INTER) {
            inferredMode = VvcCu.MODE_INTER;
        } else {
            inferredMode = sh.isIntra() ? VvcCu.MODE_INTRA : VvcCu.MODE_INTER;
        }
        cu.predMode = inferredMode;
        if (!sh.isIntra() || sps.ibc) {
            if (treeType != VvcCu.TREE_CHROMA
                    && ((!is4x4 && modeType != MODE_TYPE_INTRA) || (sps.ibc && w <= 64 && h <= 64))) {
                var ctx = (left != null && left.skip ? 1 : 0) + (above != null && above.skip ? 1 : 0);
                cu.skip = cabac.decodeBin(VvcContexts.SKIP_FLAG + ctx) != 0;
            }
            if (!cu.skip && !sh.isIntra() && !is4x4 && modeType == MODE_TYPE_ALL) {
                var ctx = (above != null && above.predMode == VvcCu.MODE_INTRA)
                        || (left != null && left.predMode == VvcCu.MODE_INTRA) ? 1 : 0;
                cu.predMode = cabac.decodeBin(VvcContexts.PRED_MODE + ctx) != 0 ? VvcCu.MODE_INTRA
                        : VvcCu.MODE_INTER;
            }
            var ibcCondition = ((sh.isIntra() && !cu.skip) || (!sh.isIntra() && (cu.predMode != VvcCu.MODE_INTRA
                    || ((is4x4 || modeType == MODE_TYPE_INTRA) && !cu.skip))))
                    && w <= 64 && h <= 64 && modeType != MODE_TYPE_INTER && sps.ibc
                    && treeType != VvcCu.TREE_CHROMA;
            boolean ibc;
            if (ibcCondition) {
                var ctx = (left != null && left.predMode == VvcCu.MODE_IBC ? 1 : 0)
                        + (above != null && above.predMode == VvcCu.MODE_IBC ? 1 : 0);
                ibc = cabac.decodeBin(VvcContexts.IBCFLAG + ctx) != 0;
            } else if (cu.skip && (is4x4 || modeType == MODE_TYPE_INTRA)) {
                ibc = true;
            } else if (w == 128 || h == 128 || modeType == MODE_TYPE_INTER || treeType == VvcCu.TREE_CHROMA) {
                ibc = false;
            } else {
                ibc = sh.isIntra() && sps.ibc;
            }
            if (ibc) {
                cu.predMode = VvcCu.MODE_IBC;
            }
        }
        if (sps.palette) {
            throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE, "vvc palette mode",
                    "VVC palette mode needs the 4:4:4 profiles, which are not supported");
        }
        if (cu.predMode == VvcCu.MODE_INTRA) {
            if (treeType != VvcCu.TREE_CHROMA) {
                intraLumaSyntax(cu, left, above);
            }
            if (treeType != VvcCu.TREE_LUMA && chromaFormat != 0) {
                intraChromaSyntax(cu);
            }
        } else if (treeType != VvcCu.TREE_CHROMA) {
            interSyntax(cu, left, above);
        }
        var coded = true;
        if (cu.predMode != VvcCu.MODE_INTRA && !cu.merge) {
            coded = cabac.decodeBin(VvcContexts.QT_ROOT_CBF) != 0;
        }
        if (cu.skip) {
            coded = false;
        }
        tus.reset();
        lfnstDcOnly = true;
        lfnstZeroOut = true;
        mtsDcOnly = true;
        mtsZeroOut = true;
        if (coded) {
            if (cu.predMode == VvcCu.MODE_INTER && sps.sbt && !cu.ciip && w <= (1 << sps.log2MaxTbSize)
                    && h <= (1 << sps.log2MaxTbSize)) {
                sbtSyntax(cu);
            }
            transformTree(cu, x0, y0, w, h, treeType);
            lfnstMtsSyntax(cu, treeType);
        }
        cu.rootCbf = coded;
        tus.cuQpOffsetCb = cuQpOffsetCb;
        tus.cuQpOffsetCr = cuQpOffsetCr;
        tus.cuQpOffsetCbCr = cuQpOffsetCbCr;
        recon.reconstruct(cu, tus);
        if (treeType != VvcCu.TREE_CHROMA) {
            prevQpY = cu.qpY;
        }
    }

    /**
     * Derives the predicted QP of the quantization group at its first coding unit (8.7.1) and gives every coding
     * unit its {@code QpY}.
     *
     * @param cu the coding unit
     */
    private void deriveQp(VvcCu cu) {
        if (cu.treeType == VvcCu.TREE_CHROMA) {
            var center = f.cuAt(Math.min(cu.x + cu.w / 2, f.width - 1), Math.min(cu.y + cu.h / 2, f.height - 1),
                    false);
            cu.qpY = center != null ? center.qpY : prevQpY;
            cu.chromaQpAdj = 0;
            return;
        }
        if (!pps.cuQpDelta) {
            cu.qpY = sh.sliceQp;
            return;
        }
        if (qgQpY == Integer.MIN_VALUE) {
            var prev = firstQgInSliceOrTile ? sh.sliceQp : prevQpY;
            firstQgInSliceOrTile = false;
            var ctbMask = ~((1 << sps.log2CtuSize) - 1);
            var a = f.available(qgX - 1, qgY, cu.x, cu.y, false);
            var qpA = a != null && ((qgX - 1) & ctbMask) == (cu.x & ctbMask) && (qgY & ctbMask) == (cu.y & ctbMask)
                    ? a.qpY : prev;
            var b = f.available(qgX, qgY - 1, cu.x, cu.y, false);
            var qpB = b != null && (qgX & ctbMask) == (cu.x & ctbMask) && ((qgY - 1) & ctbMask) == (cu.y & ctbMask)
                    ? b.qpY : prev;
            var firstInRow = firstQgInCtbRow;
            firstQgInCtbRow = false;
            if (b != null && firstInRow) {
                qgQpY = b.qpY;
            } else {
                qgQpY = (qpA + qpB + 1) >> 1;
            }
        }
        cu.qpY = applyDelta(qgQpY, cuQpDeltaVal);
    }

    /**
     * {@return the QP a delta gives from a predicted QP, wrapped into the range of the bit depth (equation 1117)}
     *
     * @param pred the predicted QP
     * @param delta {@code CuQpDeltaVal}
     */
    private int applyDelta(int pred, int delta) {
        var bd = sps.qpBdOffset;
        return ((pred + delta + 64 + 2 * bd) % (64 + bd)) - bd;
    }

    /**
     * Parses the luma intra syntax of a coding unit: BDPCM, MIP, the reference line, ISP and the most probable mode
     * syntax, and derives its luma mode.
     *
     * @param cu the coding unit
     * @param left the coding unit to the left, or {@code null}
     * @param above the coding unit above, or {@code null}
     */
    private void intraLumaSyntax(VvcCu cu, VvcCu left, VvcCu above) {
        var w = cu.w;
        var h = cu.h;
        var maxTs = 1 << sps.log2MaxTsSize;
        if (sps.bdpcm && w <= maxTs && h <= maxTs && cabac.decodeBin(VvcContexts.BDPCMMODE) != 0) {
            cu.bdpcmLuma = 1 + cabac.decodeBin(VvcContexts.BDPCMMODE + 1);
            cu.intraLuma = cu.bdpcmLuma == 2 ? 50 : 18;
            return;
        }
        if (sps.mip) {
            int ctx;
            if (w > 2 * h || h > 2 * w) {
                ctx = 3;
            } else {
                ctx = (left != null && left.mip ? 1 : 0) + (above != null && above.mip ? 1 : 0);
            }
            cu.mip = cabac.decodeBin(VvcContexts.MIP_FLAG + ctx) != 0;
        }
        if (cu.mip) {
            cu.mipTranspose = cabac.decodeBypass() != 0;
            var modes = w == 4 && h == 4 ? 16 : (w == 4 || h == 4 || (w == 8 && h == 8)) ? 8 : 6;
            cu.mipMode = truncatedBinary(modes);
            if (cu.mipMode >= modes) {
                throw new MediaException.Malformed("vvc intra_mip_mode out of range");
            }
            cu.intraLuma = 0;
            return;
        }
        if (sps.mrl && (cu.y & (sps.ctuSize - 1)) != 0) {
            if (cabac.decodeBin(VvcContexts.MULTI_REF_LINE_IDX) != 0) {
                cu.multiRefIdx = cabac.decodeBin(VvcContexts.MULTI_REF_LINE_IDX + 1) != 0 ? 2 : 1;
            }
        }
        var maxTb = 1 << sps.log2MaxTbSize;
        if (sps.isp && cu.multiRefIdx == 0 && w <= maxTb && h <= maxTb && w * h > 16) {
            if (cabac.decodeBin(VvcContexts.ISPMODE) != 0) {
                cu.ispMode = 1 + cabac.decodeBin(VvcContexts.ISPMODE + 1);
            }
        }
        var mpm = mpmBuffer;
        deriveMpm(cu, mpm);
        var mpmFlag = cu.multiRefIdx != 0 || cabac.decodeBin(VvcContexts.IPRED_MODE_0) != 0;
        if (mpmFlag) {
            var idx = 1;
            if (cu.multiRefIdx == 0) {
                idx = cabac.decodeBin(VvcContexts.INTRA_LUMA_PLANAR_FLAG + (cu.ispMode == 0 ? 1 : 0));
            }
            if (idx != 0) {
                while (idx < 5 && cabac.decodeBypass() != 0) {
                    idx++;
                }
            }
            cu.intraLuma = mpm[idx];
        } else {
            var mode = truncatedBinary(61);
            Arrays.sort(mpm);
            for (var i = 0; i < 6; i++) {
                if (mode >= mpm[i]) {
                    mode++;
                }
            }
            cu.intraLuma = mode;
        }
    }

    /**
     * The most probable modes of the coding unit being parsed.
     */
    private final int[] mpmBuffer = new int[6];

    /**
     * Derives the six most probable modes (8.4.2).
     *
     * @param cu the coding unit
     * @param mpm receives the modes
     */
    private void deriveMpm(VvcCu cu, int[] mpm) {
        var leftMode = 0;
        var aboveMode = 0;
        var l = f.available(cu.x - 1, cu.y + cu.h - 1, cu.x, cu.y, false);
        if (l != null && l.predMode == VvcCu.MODE_INTRA && !l.mip) {
            leftMode = l.intraLuma;
        }
        var ay = cu.y - 1;
        var a = f.available(cu.x + cu.w - 1, ay, cu.x, cu.y, false);
        if (a != null && a.predMode == VvcCu.MODE_INTRA && !a.mip
                && (ay >> sps.log2CtuSize) == (cu.y >> sps.log2CtuSize)) {
            aboveMode = a.intraLuma;
        }
        var offset = 61;
        var mod = 64;
        mpm[0] = 0;
        mpm[1] = 1;
        mpm[2] = 50;
        mpm[3] = 18;
        mpm[4] = 46;
        mpm[5] = 54;
        if (leftMode == aboveMode) {
            if (leftMode > 1) {
                mpm[1] = leftMode;
                mpm[2] = ((leftMode + offset) % mod) + 2;
                mpm[3] = ((leftMode - 1) % mod) + 2;
                mpm[4] = ((leftMode + offset - 1) % mod) + 2;
                mpm[5] = (leftMode % mod) + 2;
            }
        } else if (leftMode > 1 && aboveMode > 1) {
            mpm[1] = leftMode;
            mpm[2] = aboveMode;
            var maxIdx = mpm[1] > mpm[2] ? 1 : 2;
            var minIdx = mpm[1] > mpm[2] ? 2 : 1;
            var diff = mpm[maxIdx] - mpm[minIdx];
            var mn = mpm[minIdx];
            var mx = mpm[maxIdx];
            if (diff == 1) {
                mpm[3] = ((mn + offset) % mod) + 2;
                mpm[4] = ((mx - 1) % mod) + 2;
                mpm[5] = ((mn + offset - 1) % mod) + 2;
            } else if (diff >= 62) {
                mpm[3] = ((mn - 1) % mod) + 2;
                mpm[4] = ((mx + offset) % mod) + 2;
                mpm[5] = (mn % mod) + 2;
            } else if (diff == 2) {
                mpm[3] = ((mn - 1) % mod) + 2;
                mpm[4] = ((mn + offset) % mod) + 2;
                mpm[5] = ((mx - 1) % mod) + 2;
            } else {
                mpm[3] = ((mn + offset) % mod) + 2;
                mpm[4] = ((mn - 1) % mod) + 2;
                mpm[5] = ((mx + offset) % mod) + 2;
            }
        } else if (leftMode + aboveMode >= 2) {
            var mx = Math.max(leftMode, aboveMode);
            mpm[1] = mx;
            mpm[2] = ((mx + offset) % mod) + 2;
            mpm[3] = ((mx - 1) % mod) + 2;
            mpm[4] = ((mx + offset - 1) % mod) + 2;
            mpm[5] = (mx % mod) + 2;
        }
    }

    /**
     * Parses the chroma intra syntax of a coding unit: BDPCM, the cross-component and the intra chroma mode syntax, and
     * derives its chroma mode.
     *
     * @param cu the coding unit
     */
    private void intraChromaSyntax(VvcCu cu) {
        var cw = cu.w >> sx;
        var ch = cu.h >> sy;
        var maxTs = 1 << sps.log2MaxTsSize;
        if (sps.bdpcm && cw <= maxTs && ch <= maxTs && cabac.decodeBin(VvcContexts.BDPCMMODE + 2) != 0) {
            cu.bdpcmChroma = 1 + cabac.decodeBin(VvcContexts.BDPCMMODE + 3);
            cu.intraChromaSyntax = 4;
            cu.intraChroma = cu.bdpcmChroma == 2 ? 50 : 18;
            return;
        }
        if (sps.cclm && cclmAllowed(cu) && cabac.decodeBin(VvcContexts.CCLM_MODE_FLAG) != 0) {
            var idx = cabac.decodeBin(VvcContexts.CCLM_MODE_IDX);
            if (idx != 0) {
                idx += cabac.decodeBypass();
            }
            cu.intraChromaSyntax = 5 + idx;
            cu.intraChroma = 81 + idx;
            return;
        }
        if (cabac.decodeBin(VvcContexts.IPRED_MODE_1) == 0) {
            cu.intraChromaSyntax = 4;
        } else {
            cu.intraChromaSyntax = cabac.decodeBypassBins(2);
        }
        var luma = colocatedLumaMode(cu);
        if (cu.intraChromaSyntax == 4) {
            cu.intraChroma = luma;
        } else {
            var mode = CHROMA_MODES[cu.intraChromaSyntax];
            cu.intraChroma = mode == luma ? 66 : mode;
        }
    }

    /**
     * {@return the luma intra mode at the center of a chroma coding unit: planar for MIP, DC for IBC (8.4.3)}
     *
     * @param cu the coding unit
     */
    private int colocatedLumaMode(VvcCu cu) {
        if (cu.treeType == VvcCu.TREE_SINGLE) {
            return cu.mip ? 0 : cu.intraLuma;
        }
        var luma = f.cuAt(Math.min(cu.x + cu.w / 2, f.width - 1), Math.min(cu.y + cu.h / 2, f.height - 1), false);
        if (luma == null || luma.mip) {
            return 0;
        }
        return luma.predMode == VvcCu.MODE_INTRA ? luma.intraLuma : 1;
    }

    /**
     * Derives {@code CclmEnabled} (7.4.12.5).
     *
     * @param cu the coding unit
     * @return whether cross-component prediction may be used
     */
    private boolean cclmAllowed(VvcCu cu) {
        if (!dualTreeIntra || sps.ctuSize <= 32) {
            return true;
        }
        var depth64 = sps.ctuSize == 128 ? 1 : 0;
        var s1 = splitAtDepth(cu, depth64);
        var s2 = splitAtDepth(cu, depth64 + 1);
        var allow = s1 == SPLIT_QT || s1 == SPLIT_NONE || (s1 == SPLIT_BT_HOR && (s2 == SPLIT_BT_VER
                || s2 == SPLIT_NONE));
        if (allow) {
            var luma = f.cuAt(cu.x, cu.y, false);
            if (luma != null) {
                if (luma.depth > depth64 && luma.qtDepth == depth64) {
                    allow = false;
                } else if (luma.depth == depth64 && luma.ispMode != 0) {
                    allow = false;
                }
            }
        }
        return allow;
    }

    /**
     * {@return the split a coding unit's tree applied at a depth above it, {@link #SPLIT_NONE} at or below its own
     * depth}
     *
     * @param cu the coding unit
     * @param depth the coding tree depth
     */
    private static int splitAtDepth(VvcCu cu, int depth) {
        if (depth >= cu.depth) {
            return SPLIT_NONE;
        }
        return (int) ((cu.splitSeries >>> (3 * depth)) & 7);
    }

    /**
     * Parses the inter syntax of a coding unit that is not merged (7.3.11.7): the prediction direction, affine flags,
     * reference indices, motion vector differences, predictor flags, AMVR and BCW.
     *
     * @param cu the coding unit
     * @param left the coding unit to the left, or {@code null}
     * @param above the coding unit above, or {@code null}
     */
    private void interSyntax(VvcCu cu, VvcCu left, VvcCu above) {
        var w = cu.w;
        var h = cu.h;
        cu.merge = cu.skip || cabac.decodeBin(VvcContexts.MERGE_FLAG) != 0;
        if (cu.merge) {
            mergeData(cu, left, above);
            return;
        }
        if (cu.predMode == VvcCu.MODE_IBC) {
            cu.interDir = 1;
            mvdCoding(cu, 0, 0);
            if (sps.maxNumIbcMergeCand > 1) {
                cu.mvpIdx[0] = cabac.decodeBin(VvcContexts.MVPIDX);
            }
            if (sps.amvr && (cu.mvx[0][0] != 0 || cu.mvy[0][0] != 0)) {
                cu.imv = 1 + cabac.decodeBin(VvcContexts.IMV_FLAG + 1);
            }
            return;
        }
        cu.interDir = 1;
        if (sh.isB()) {
            var bipredAllowed = !(w + h <= 12);
            if (bipredAllowed) {
                var ctx = 7 - ((Integer.numberOfTrailingZeros(w) + Integer.numberOfTrailingZeros(h) + 1) >> 1);
                if (cabac.decodeBin(VvcContexts.INTER_DIR + ctx) != 0) {
                    cu.interDir = 3;
                }
            }
            if (cu.interDir != 3) {
                cu.interDir = cabac.decodeBin(VvcContexts.INTER_DIR + 5) != 0 ? 2 : 1;
            }
        }
        if (sps.affine && w >= 16 && h >= 16) {
            var ctx = (left != null && left.affine ? 1 : 0) + (above != null && above.affine ? 1 : 0);
            cu.affine = cabac.decodeBin(VvcContexts.AFFINE_FLAG + ctx) != 0;
            if (cu.affine && sps.sixParamAffine) {
                cu.sixParam = cabac.decodeBin(VvcContexts.AFFINE_TYPE) != 0;
            }
        }
        if (sps.smvd && !ph.mvdL1Zero && cu.interDir == 3 && !cu.affine && motion.symRefIdx[0] > -1
                && motion.symRefIdx[1] > -1) {
            cu.smvd = cabac.decodeBin(VvcContexts.SMVD_FLAG) != 0;
        }
        var cps = cu.affine ? (cu.sixParam ? 3 : 2) : 1;
        var nonZero = false;
        if (cu.interDir != 2) {
            cu.refIdx[0] = cu.smvd ? motion.symRefIdx[0] : refIdx(0);
            for (var k = 0; k < cps; k++) {
                mvdCoding(cu, 0, k);
                nonZero |= cu.mvx[0][k] != 0 || cu.mvy[0][k] != 0;
            }
            cu.mvpIdx[0] = cabac.decodeBin(VvcContexts.MVPIDX);
        }
        if (cu.interDir != 1) {
            cu.refIdx[1] = cu.smvd ? motion.symRefIdx[1] : refIdx(1);
            if (ph.mvdL1Zero && cu.interDir == 3) {
                for (var k = 0; k < 3; k++) {
                    cu.mvx[1][k] = 0;
                    cu.mvy[1][k] = 0;
                }
            } else {
                if (cu.smvd) {
                    cu.mvx[1][0] = -cu.mvx[0][0];
                    cu.mvy[1][0] = -cu.mvy[0][0];
                } else {
                    mvdCoding(cu, 1, 0);
                }
                for (var k = 1; k < cps; k++) {
                    mvdCoding(cu, 1, k);
                }
                for (var k = 0; k < cps; k++) {
                    nonZero |= cu.mvx[1][k] != 0 || cu.mvy[1][k] != 0;
                }
            }
            cu.mvpIdx[1] = cabac.decodeBin(VvcContexts.MVPIDX);
        }
        if (nonZero && ((sps.amvr && !cu.affine) || (sps.affineAmvr && cu.affine))) {
            if (cu.affine) {
                if (cabac.decodeBin(VvcContexts.IMV_FLAG + 2) != 0) {
                    cu.imv = 1 + cabac.decodeBin(VvcContexts.IMV_FLAG + 3);
                }
            } else if (cabac.decodeBin(VvcContexts.IMV_FLAG) != 0) {
                if (cabac.decodeBin(VvcContexts.IMV_FLAG + 4) == 0) {
                    cu.imv = VvcCu.IMV_HALF;
                    cu.halfPel = true;
                } else {
                    cu.imv = 1 + cabac.decodeBin(VvcContexts.IMV_FLAG + 1);
                }
            }
        }
        if (sps.bcw && cu.interDir == 3 && w * h >= 256 && !motion.weighted(cu.refIdx[0], cu.refIdx[1])) {
            var idx = 0;
            if (cabac.decodeBin(VvcContexts.BCW_IDX) != 0) {
                idx = 1;
                var max = motion.noBackwardPred ? 4 : 2;
                while (idx < max && cabac.decodeBypass() != 0) {
                    idx++;
                }
            }
            cu.bcwIdx = idx;
        }
    }

    /**
     * Parses {@code ref_idx_l0} or {@code ref_idx_l1}.
     *
     * @param list the reference picture list, 0 or 1
     * @return the reference index
     */
    private int refIdx(int list) {
        var num = sh.numRefIdxActive[list];
        if (num <= 1 || cabac.decodeBin(VvcContexts.REF_PIC) == 0) {
            return 0;
        }
        if (num <= 2 || cabac.decodeBin(VvcContexts.REF_PIC + 1) == 0) {
            return 1;
        }
        for (var idx = 3; ; idx++) {
            if (num <= idx || cabac.decodeBypass() == 0) {
                return idx - 1;
            }
        }
    }

    /**
     * Parses {@code mvd_coding( )} (7.3.11.8) into the coding unit's vector difference.
     *
     * @param cu the coding unit
     * @param list the reference picture list, 0 or 1
     * @param cp the control point
     */
    private void mvdCoding(VvcCu cu, int list, int cp) {
        var hor = cabac.decodeBin(VvcContexts.MVD);
        var ver = cabac.decodeBin(VvcContexts.MVD);
        if (hor != 0) {
            hor += cabac.decodeBin(VvcContexts.MVD + 1);
        }
        if (ver != 0) {
            ver += cabac.decodeBin(VvcContexts.MVD + 1);
        }
        if (hor != 0) {
            if (hor > 1) {
                hor += cabac.decodeRemAbs(1, 0, 17);
            }
            if (cabac.decodeBypass() != 0) {
                hor = -hor;
            }
        }
        if (ver != 0) {
            if (ver > 1) {
                ver += cabac.decodeRemAbs(1, 0, 17);
            }
            if (cabac.decodeBypass() != 0) {
                ver = -ver;
            }
        }
        if (hor < -(1 << 15) || hor >= (1 << 15) || ver < -(1 << 15) || ver >= (1 << 15)) {
            throw new MediaException.Malformed("vvc motion vector difference out of range");
        }
        cu.mvx[list][cp] = hor;
        cu.mvy[list][cp] = ver;
    }

    /**
     * Parses {@code merge_data( )} (7.3.11.7): the subblock, regular, MMVD, CIIP and geometric merge syntax.
     *
     * @param cu the coding unit
     * @param left the coding unit to the left, or {@code null}
     * @param above the coding unit above, or {@code null}
     */
    private void mergeData(VvcCu cu, VvcCu left, VvcCu above) {
        var w = cu.w;
        var h = cu.h;
        if (cu.predMode == VvcCu.MODE_IBC) {
            cu.mergeIdx = mergeIdx(sps.maxNumIbcMergeCand, VvcContexts.MERGE_IDX);
            return;
        }
        if (motion.maxNumSubblockMergeCand > 0 && w >= 8 && h >= 8) {
            var ctx = (left != null && left.affine ? 1 : 0) + (above != null && above.affine ? 1 : 0);
            cu.subblockMerge = cabac.decodeBin(VvcContexts.SUBBLOCK_MERGE_FLAG + ctx) != 0;
            cu.affine = cu.subblockMerge;
        }
        if (cu.subblockMerge) {
            cu.mergeIdx = mergeIdx(motion.maxNumSubblockMergeCand, VvcContexts.AFF_MERGE_IDX);
            return;
        }
        var regular = true;
        var ciipAvailable = sps.ciip && !cu.skip && w < 128 && h < 128 && w * h >= 64;
        var gpmAvailable = sps.gpm && sh.isB() && w >= 8 && h >= 8 && w <= 64 && h <= 64 && w < 8 * h && h < 8 * w;
        if (gpmAvailable || ciipAvailable) {
            regular = cabac.decodeBin(VvcContexts.REGULAR_MERGE_FLAG + (cu.skip ? 0 : 1)) != 0;
        }
        if (regular) {
            if (sps.mmvd) {
                cu.mmvd = cabac.decodeBin(VvcContexts.MMVD_FLAG) != 0;
            }
        } else {
            if (gpmAvailable && ciipAvailable) {
                cu.ciip = cabac.decodeBin(VvcContexts.CIIP_FLAG) != 0;
            } else {
                cu.ciip = ciipAvailable;
            }
            cu.gpm = !cu.ciip;
        }
        if (cu.mmvd) {
            var base = 0;
            if (sps.maxNumMergeCand > 1 && cabac.decodeBin(VvcContexts.MMVD_MERGE_IDX) != 0) {
                base = 1;
            }
            var step = 0;
            if (cabac.decodeBin(VvcContexts.MMVD_STEP_MVP_IDX) != 0) {
                for (step = 1; step < 7 && cabac.decodeBypass() != 0; step++) {
                }
            }
            var dir = cabac.decodeBypass() << 1;
            dir |= cabac.decodeBypass();
            cu.mmvdIdx = base * 32 + step * 4 + dir;
        } else if (cu.gpm) {
            cu.gpmSplit = truncatedBinary(64);
            var max = sps.maxNumGpmMergeCand;
            var c0 = 0;
            var c1 = 0;
            if (cabac.decodeBin(VvcContexts.MERGE_IDX) != 0) {
                c0 = unaryMaxBypass(max - 2) + 1;
            }
            if (max - 2 > 0 && cabac.decodeBin(VvcContexts.MERGE_IDX) != 0) {
                c1 = unaryMaxBypass(max - 3) + 1;
            }
            if (c1 >= c0) {
                c1++;
            }
            cu.gpmIdx0 = c0;
            cu.gpmIdx1 = c1;
        } else {
            cu.mergeIdx = mergeIdx(sps.maxNumMergeCand, VvcContexts.MERGE_IDX);
        }
        if (cu.ciip) {
            cu.intraLuma = 0;
        }
    }

    /**
     * Parses a merge index of truncated rice binarization: its first bin with a context, the others bypass.
     *
     * @param numCand the number of candidates
     * @param ctx the context of the first bin
     * @return the index
     */
    private int mergeIdx(int numCand, int ctx) {
        var idx = 0;
        if (numCand > 1 && cabac.decodeBin(ctx) != 0) {
            for (idx = 1; idx < numCand - 1 && cabac.decodeBypass() != 0; idx++) {
            }
        }
        return idx;
    }

    /**
     * Parses the subblock transform syntax of a coding unit ({@code cu_sbt_flag} and the following flags).
     *
     * @param cu the coding unit
     */
    private void sbtSyntax(VvcCu cu) {
        var w = cu.w;
        var h = cu.h;
        var verH = w >= 8;
        var verQ = w >= 16;
        var horH = h >= 8;
        var horQ = h >= 16;
        if (!(verH || horH)) {
            return;
        }
        if (cabac.decodeBin(VvcContexts.SBT_FLAG + (w * h <= 256 ? 1 : 0)) == 0) {
            return;
        }
        var quad = (verH || horH) && (verQ || horQ) && cabac.decodeBin(VvcContexts.SBT_QUAD_FLAG) != 0;
        boolean hor;
        if ((quad && verQ && horQ) || (!quad && verH && horH)) {
            var ctx = w == h ? 0 : w < h ? 1 : 2;
            hor = cabac.decodeBin(VvcContexts.SBT_HOR_FLAG + ctx) != 0;
        } else {
            hor = (quad && horQ) || (!quad && horH);
        }
        cu.sbtIdx = 1;
        cu.sbtQuad = quad;
        cu.sbtHor = hor;
        cu.sbtPos = cabac.decodeBin(VvcContexts.SBT_POS_FLAG);
    }

    /**
     * Parses {@code transform_tree( )} (7.3.11.9) of a coding unit, with the sub-partitions of ISP and the two units of
     * a subblock transform.
     *
     * @param cu the coding unit
     * @param x0 the luma column
     * @param y0 the luma row
     * @param w the width
     * @param h the height
     * @param treeType the tree type
     */
    private void transformTree(VvcCu cu, int x0, int y0, int w, int h, int treeType) {
        inferTuCbfLuma = true;
        prevTuCbfY = false;
        var maxTb = 1 << sps.log2MaxTbSize;
        if (cu.ispMode == 0 && cu.sbtIdx == 0) {
            regularTree(cu, x0, y0, w, h, treeType, maxTb);
        } else if (cu.sbtIdx != 0) {
            var fourths = cu.sbtQuad ? (cu.sbtPos == 0 ? 1 : 3) : 2;
            if (!cu.sbtHor) {
                var tw = w * fourths / 4;
                transformUnit(cu, x0, y0, tw, h, treeType, 0);
                transformUnit(cu, x0 + tw, y0, w - tw, h, treeType, 1);
            } else {
                var th = h * fourths / 4;
                transformUnit(cu, x0, y0, w, th, treeType, 0);
                transformUnit(cu, x0, y0 + th, w, h - th, treeType, 1);
            }
        } else {
            var n = ispPartitions(cu);
            if (cu.ispMode == VvcCu.ISP_HOR) {
                var th = h / n;
                for (var i = 0; i < n; i++) {
                    transformUnit(cu, x0, y0 + th * i, w, th, treeType, i);
                }
            } else {
                var tw = w / n;
                for (var i = 0; i < n; i++) {
                    transformUnit(cu, x0 + tw * i, y0, tw, h, treeType, i);
                }
            }
        }
    }

    /**
     * {@return {@code NumIntraSubPartitions} of an ISP coding unit}
     *
     * @param cu the coding unit
     */
    static int ispPartitions(VvcCu cu) {
        if ((cu.w == 4 && cu.h == 8) || (cu.w == 8 && cu.h == 4)) {
            return 2;
        }
        return 4;
    }

    /**
     * Splits a transform tree at the maximum transform size and parses its transform units.
     *
     * @param cu the coding unit
     * @param x0 the luma column
     * @param y0 the luma row
     * @param w the width
     * @param h the height
     * @param treeType the tree type
     * @param maxTb the maximum transform size
     */
    private void regularTree(VvcCu cu, int x0, int y0, int w, int h, int treeType, int maxTb) {
        if (w > maxTb || h > maxTb) {
            var verFirst = w > maxTb && w > h;
            var tw = verFirst ? w / 2 : w;
            var th = verFirst ? h : h / 2;
            regularTree(cu, x0, y0, tw, th, treeType, maxTb);
            if (verFirst) {
                regularTree(cu, x0 + tw, y0, tw, th, treeType, maxTb);
            } else {
                regularTree(cu, x0, y0 + th, tw, th, treeType, maxTb);
            }
        } else {
            transformUnit(cu, x0, y0, w, h, treeType, 0);
        }
    }

    /**
     * Parses {@code transform_unit( )} (7.3.11.10): the coded block flags, the QP and chroma QP offset syntax, joint
     * chroma residuals and the residuals.
     *
     * @param cu the coding unit
     * @param x0 the luma column
     * @param y0 the luma row
     * @param tbW the luma width
     * @param tbH the luma height
     * @param treeType the tree type
     * @param subTu the index of the unit in the coding unit
     */
    private void transformUnit(VvcCu cu, int x0, int y0, int tbW, int tbH, int treeType, int subTu) {
        var isp = cu.ispMode != 0;
        var lastIsp = isp && subTu == ispPartitions(cu) - 1;
        int xC;
        int yC;
        int wC;
        int hC;
        if (isp && treeType == VvcCu.TREE_SINGLE && lastIsp) {
            xC = cu.x;
            yC = cu.y;
            wC = cu.w >> sx;
            hC = cu.h >> sy;
        } else {
            xC = x0;
            yC = y0;
            wC = tbW >> sx;
            hC = tbH >> sy;
        }
        var t = tus.add(x0, y0, tbW, tbH);
        tus.cx[t] = xC;
        tus.cy[t] = yC;
        tus.cw[t] = wC;
        tus.ch[t] = hC;
        var chromaAvailable = treeType != VvcCu.TREE_LUMA && chromaFormat != 0 && (!isp || lastIsp);
        tus.hasChroma[t] = chromaAvailable;
        tus.hasLuma[t] = treeType != VvcCu.TREE_CHROMA;
        var sbtNoResidual = cu.sbtIdx != 0 && ((subTu == 0 && cu.sbtPos == 1) || (subTu == 1 && cu.sbtPos == 0));
        var cb = false;
        var cr = false;
        if ((treeType == VvcCu.TREE_SINGLE || treeType == VvcCu.TREE_CHROMA) && chromaFormat != 0
                && ((!isp && !sbtNoResidual) || lastIsp)) {
            if (cu.bdpcmChroma != 0) {
                cb = cabac.decodeBin(VvcContexts.QT_CBF_1 + 1) != 0;
                cr = cabac.decodeBin(VvcContexts.QT_CBF_2 + 2) != 0;
            } else {
                cb = cabac.decodeBin(VvcContexts.QT_CBF_1) != 0;
                cr = cabac.decodeBin(VvcContexts.QT_CBF_2 + (cb ? 1 : 0)) != 0;
            }
        }
        var y = false;
        if (treeType == VvcCu.TREE_SINGLE || treeType == VvcCu.TREE_LUMA) {
            var parse = (!isp && !sbtNoResidual && ((cu.predMode == VvcCu.MODE_INTRA)
                    || (chromaAvailable && (cb || cr)) || cu.w > (1 << sps.log2MaxTbSize)
                    || cu.h > (1 << sps.log2MaxTbSize)))
                    || (isp && (subTu < ispPartitions(cu) - 1 || !inferTuCbfLuma));
            if (parse) {
                int ctx;
                if (cu.bdpcmLuma != 0) {
                    ctx = 1;
                } else if (!isp) {
                    ctx = 0;
                } else {
                    ctx = 2 + (prevTuCbfY ? 1 : 0);
                }
                y = cabac.decodeBin(VvcContexts.QT_CBF_0 + ctx) != 0;
            } else {
                y = !sbtNoResidual;
            }
            if (isp) {
                inferTuCbfLuma = inferTuCbfLuma && !y;
                prevTuCbfY = y;
            }
        }
        if (!chromaAvailable) {
            cb = false;
            cr = false;
        }
        tus.cbf[t] = (y ? 1 : 0) | (cb ? 2 : 0) | (cr ? 4 : 0);
        cu.cbfY |= y;
        cu.cbfCb |= cb;
        cu.cbfCr |= cr;
        if ((cu.w > 64 || cu.h > 64 || y || (chromaAvailable && (cb || cr))) && treeType != VvcCu.TREE_CHROMA
                && pps.cuQpDelta && !qpDeltaCoded) {
            var abs = 0;
            while (abs < 5 && cabac.decodeBin(VvcContexts.DELTA_QP + (abs == 0 ? 0 : 1)) != 0) {
                abs++;
            }
            if (abs >= 5) {
                abs += expGolombBypass(0);
            }
            var delta = abs;
            if (abs > 0 && cabac.decodeBypass() != 0) {
                delta = -abs;
            }
            var bd = sps.qpBdOffset;
            if (delta < -(32 + bd / 2) || delta > 31 + bd / 2) {
                throw new MediaException.Malformed("vvc CuQpDeltaVal out of range");
            }
            cuQpDeltaVal = delta;
            qpDeltaCoded = true;
            cu.qpY = applyDelta(qgQpY, cuQpDeltaVal);
        }
        if ((cu.w > 64 || cu.h > 64 || (chromaAvailable && (cb || cr))) && treeType != VvcCu.TREE_LUMA
                && sh.cuChromaQpOffsetEnabled && !chromaQpOffsetCoded) {
            var flag = cabac.decodeBin(VvcContexts.CHROMA_QP_ADJ_FLAG);
            var idx = 0;
            if (flag != 0 && pps.chromaQpOffsetListLen > 1) {
                while (idx < pps.chromaQpOffsetListLen - 1 && cabac.decodeBin(VvcContexts.CHROMA_QP_ADJ_IDC) != 0) {
                    idx++;
                }
            }
            if (flag != 0) {
                cuQpOffsetCb = pps.cbQpOffsetList[idx];
                cuQpOffsetCr = pps.crQpOffsetList[idx];
                cuQpOffsetCbCr = pps.jointCbCrQpOffsetList[idx];
            } else {
                cuQpOffsetCb = 0;
                cuQpOffsetCr = 0;
                cuQpOffsetCbCr = 0;
            }
            chromaQpOffsetCoded = true;
        }
        tus.qpOffsetCb[t] = cuQpOffsetCb;
        tus.qpOffsetCr[t] = cuQpOffsetCr;
        tus.qpOffsetCbCr[t] = cuQpOffsetCbCr;
        var joint = 0;
        if (sps.jointCbCr && chromaAvailable && ((cu.predMode == VvcCu.MODE_INTRA && (cb || cr)) || (cb && cr))) {
            var mask = (cb ? 2 : 0) + (cr ? 1 : 0);
            if (cabac.decodeBin(VvcContexts.JOINT_CB_CR_FLAG + mask - 1) != 0) {
                joint = mask;
            }
        }
        tus.jointCbCr[t] = joint;
        var maxTs = 1 << sps.log2MaxTsSize;
        tus.ts[t] = 0;
        if (y && treeType != VvcCu.TREE_CHROMA) {
            var ts = cu.bdpcmLuma != 0;
            if (sps.transformSkip && cu.bdpcmLuma == 0 && tbW <= maxTs && tbH <= maxTs && !isp && cu.sbtIdx == 0) {
                ts = cabac.decodeBin(VvcContexts.MTSINDEX + 4) != 0;
            }
            if (ts) {
                tus.ts[t] |= 1;
            }
            residual(cu, t, 0, tbW, tbH, ts);
        }
        if (cb && treeType != VvcCu.TREE_LUMA) {
            var ts = cu.bdpcmChroma != 0;
            if (sps.transformSkip && cu.bdpcmChroma == 0 && wC <= maxTs && hC <= maxTs && cu.sbtIdx == 0) {
                ts = cabac.decodeBin(VvcContexts.MTSINDEX + 5) != 0;
            }
            if (ts) {
                tus.ts[t] |= 2;
            }
            residual(cu, t, 1, wC, hC, ts);
        }
        if (cr && treeType != VvcCu.TREE_LUMA && !(cb && joint != 0)) {
            var ts = cu.bdpcmChroma != 0;
            if (sps.transformSkip && cu.bdpcmChroma == 0 && wC <= maxTs && hC <= maxTs && cu.sbtIdx == 0) {
                ts = cabac.decodeBin(VvcContexts.MTSINDEX + 5) != 0;
            }
            if (ts) {
                tus.ts[t] |= 4;
            }
            residual(cu, t, 2, wC, hC, ts);
        }
    }

    /**
     * Parses {@code lfnst_idx} and {@code mts_idx} of a coding unit, after its transform tree.
     *
     * @param cu the coding unit
     * @param treeType the tree type
     */
    private void lfnstMtsSyntax(VvcCu cu, int treeType) {
        var w = cu.w;
        var h = cu.h;
        int lw;
        int lh;
        if (treeType == VvcCu.TREE_CHROMA) {
            lw = w >> sx;
            lh = h >> sy;
        } else {
            var n = cu.ispMode != 0 ? ispPartitions(cu) : 1;
            lw = cu.ispMode == VvcCu.ISP_VER ? w / n : w;
            lh = cu.ispMode == VvcCu.ISP_HOR ? h / n : h;
        }
        var tsY = (tus.ts[0] & 1) != 0;
        var notTs = true;
        for (var t = 0; t < tus.count && notTs; t++) {
            var skipped = tus.cbf[t] & tus.ts[t];
            if (treeType == VvcCu.TREE_CHROMA || !tus.hasLuma[t]) {
                skipped &= ~1;
            }
            if (treeType == VvcCu.TREE_LUMA || !tus.hasChroma[t]) {
                skipped &= 1;
            }
            notTs = skipped == 0;
        }
        var maxTb = 1 << sps.log2MaxTbSize;
        if (Math.min(lw, lh) >= 4 && sps.lfnst && cu.predMode == VvcCu.MODE_INTRA && notTs
                && (treeType == VvcCu.TREE_CHROMA || !cu.mip || Math.min(lw, lh) >= 16)
                && Math.max(w, h) <= maxTb) {
            if ((cu.ispMode != 0 || !lfnstDcOnly) && lfnstZeroOut) {
                var ctx = treeType != VvcCu.TREE_SINGLE ? 1 : 0;
                var idx = cabac.decodeBin(VvcContexts.LFNSTIDX + ctx);
                if (idx != 0) {
                    idx += cabac.decodeBin(VvcContexts.LFNSTIDX + 2);
                }
                cu.lfnstIdx = idx;
            }
        }
        if (treeType != VvcCu.TREE_CHROMA && cu.lfnstIdx == 0 && !tsY && Math.max(w, h) <= 32 && cu.ispMode == 0
                && cu.sbtIdx == 0 && mtsZeroOut && !mtsDcOnly) {
            if ((cu.predMode == VvcCu.MODE_INTER && sps.explicitMtsInter)
                    || (cu.predMode == VvcCu.MODE_INTRA && sps.explicitMtsIntra)) {
                var idx = 0;
                if (cabac.decodeBin(VvcContexts.MTSINDEX) != 0) {
                    idx = 1;
                    for (var i = 0; i < 3; i++) {
                        if (cabac.decodeBin(VvcContexts.MTSINDEX + 1 + i) == 0) {
                            break;
                        }
                        idx++;
                    }
                }
                cu.mtsIdx = idx;
            }
        }
    }

    /**
     * Parses {@code residual_coding( )} or {@code residual_ts_coding( )} into the transform unit's coefficient
     * buffer.
     *
     * @param cu the coding unit
     * @param t the index of the transform unit
     * @param cIdx the component
     * @param w the block width
     * @param h the block height
     * @param ts whether the block is transform skipped
     */
    private void residual(VvcCu cu, int t, int cIdx, int w, int h, boolean ts) {
        var log2W = Integer.numberOfTrailingZeros(w);
        var log2H = Integer.numberOfTrailingZeros(h);
        var coeff = tus.allocate(t, cIdx, w * h);
        if (ts && !sh.tsResidualCodingDisabled) {
            residualTs(cu, coeff, tus.offset[cIdx][t], cIdx, log2W, log2H);
            return;
        }
        residualRegular(cu, coeff, tus.offset[cIdx][t], cIdx, log2W, log2H, ts);
    }

    /**
     * Parses {@code residual_coding( )} (7.3.11.11) of a block.
     *
     * @param cu the coding unit
     * @param out the coefficient buffer
     * @param base the index of the block in the buffer
     * @param cIdx the component
     * @param log2W the base 2 logarithm of the width
     * @param log2H the base 2 logarithm of the height
     * @param ts whether the block is transform skipped, which turns off sign data hiding
     */
    private void residualRegular(VvcCu cu, int[] out, int base, int cIdx, int log2W, int log2H, boolean ts) {
        var luma = cIdx == 0;
        var sbtMts = sps.mts && cu.sbtIdx != 0 && luma;
        var zoW = sbtMts && log2W == 5 && log2H < 6 ? 4 : Math.min(log2W, 5);
        var zoH = sbtMts && log2W < 6 && log2H == 5 ? 4 : Math.min(log2H, 5);
        var lastX = 0;
        var lastY = 0;
        var w = 1 << log2W;
        var h = 1 << log2H;
        var maxX = GROUP_IDX[(1 << zoW) - 1];
        var maxY = GROUP_IDX[(1 << zoH) - 1];
        int offX;
        int offY;
        int shX;
        int shY;
        int ctxSetX;
        int ctxSetY;
        if (luma) {
            offX = LAST_PREFIX_CTX[log2W];
            offY = LAST_PREFIX_CTX[log2H];
            shX = (log2W + 1) >> 2;
            shY = (log2H + 1) >> 2;
            ctxSetX = VvcContexts.LAST_X_0;
            ctxSetY = VvcContexts.LAST_Y_0;
        } else {
            offX = 0;
            offY = 0;
            shX = Math.clamp(w >> 3, 0, 2);
            shY = Math.clamp(h >> 3, 0, 2);
            ctxSetX = VvcContexts.LAST_X_1;
            ctxSetY = VvcContexts.LAST_Y_1;
        }
        if (log2W > 0) {
            while (lastX < maxX && cabac.decodeBin(ctxSetX + offX + (lastX >> shX)) != 0) {
                lastX++;
            }
        }
        if (log2H > 0) {
            while (lastY < maxY && cabac.decodeBin(ctxSetY + offY + (lastY >> shY)) != 0) {
                lastY++;
            }
        }
        if (lastX > 3) {
            var count = (lastX - 2) >> 1;
            lastX = MIN_IN_GROUP[lastX] + cabac.decodeBypassBins(count);
        }
        if (lastY > 3) {
            var count = (lastY - 2) >> 1;
            lastY = MIN_IN_GROUP[lastY] + cabac.decodeBypassBins(count);
        }
        if (lastX >= (1 << zoW) || lastY >= (1 << zoH)) {
            throw new MediaException.Malformed("vvc last significant coefficient out of range");
        }
        var log2SbW = Math.min(zoW, zoH) < 2 ? 1 : 2;
        var log2SbH = log2SbW;
        if (zoW + zoH > 3) {
            if (zoW < 2) {
                log2SbW = zoW;
                log2SbH = 4 - log2SbW;
            } else if (zoH < 2) {
                log2SbH = zoH;
                log2SbW = 4 - log2SbH;
            }
        }
        var numSbCoeff = 1 << (log2SbW + log2SbH);
        var sbScan = VvcScan.diagonal(zoW - log2SbW, zoH - log2SbH);
        var inScan = VvcScan.diagonal(log2SbW, log2SbH);
        var sbW = 1 << (zoW - log2SbW);
        var sbH = 1 << (zoH - log2SbH);
        var sbMaskW = (1 << log2SbW) - 1;
        // locate the last position in scan order
        var lastSubBlock = -1;
        var lastScanPos = -1;
        {
            var xs = lastX >> log2SbW;
            var ys = lastY >> log2SbH;
            var sbPos = ys * sbW + xs;
            for (var i = 0; i < sbScan.length; i++) {
                if (sbScan[i] == sbPos) {
                    lastSubBlock = i;
                    break;
                }
            }
            var inPos = ((lastY & ((1 << log2SbH) - 1)) << log2SbW) + (lastX & sbMaskW);
            for (var i = 0; i < numSbCoeff; i++) {
                if (inScan[i] == inPos) {
                    lastScanPos = i;
                    break;
                }
            }
        }
        if (lastSubBlock == 0 && zoW >= 2 && zoH >= 2 && !ts && lastScanPos > 0) {
            lfnstDcOnly = false;
        }
        if ((lastSubBlock > 0 && zoW >= 2 && zoH >= 2)
                || (lastScanPos > 7 && (zoW == 2 || zoW == 3) && zoW == zoH)) {
            lfnstZeroOut = false;
        }
        if ((lastSubBlock > 0 || lastScanPos > 0) && luma) {
            mtsDcOnly = false;
        }
        Arrays.fill(out, base, base + w * h, 0);
        var stride = w;
        Arrays.fill(absLevel, 0, w * h, 0);
        Arrays.fill(pass1, 0, w * h, 0);
        Arrays.fill(sigFlags, 0, w * h, (byte) 0);
        Arrays.fill(sbCoded, 0, sbW * sbH, 0);
        var depQuant = sh.depQuant;
        var qState = 0;
        var remBins = ((1 << (zoW + zoH)) * 7) >> 2;
        var sigBase = luma ? VvcContexts.SIG_FLAG_0 : VvcContexts.SIG_FLAG_1;
        var gt1Base = luma ? VvcContexts.GTX_FLAG_2 : VvcContexts.GTX_FLAG_3;
        var gt2Base = luma ? VvcContexts.GTX_FLAG_0 : VvcContexts.GTX_FLAG_1;
        var parBase = luma ? VvcContexts.PAR_FLAG_0 : VvcContexts.PAR_FLAG_1;
        var sigGroupBase = luma ? VvcContexts.SIG_COEFF_GROUP_0 : VvcContexts.SIG_COEFF_GROUP_1;
        var sigSetStride = VvcContexts.SIG_FLAG_2 - VvcContexts.SIG_FLAG_0;
        var zoWidth = 1 << zoW;
        var zoHeight = 1 << zoH;
        var sbPositions = scanPositions;
        for (var i = lastSubBlock; i >= 0; i--) {
            var startQState = qState;
            var sbPos = sbScan[i];
            var xS = sbPos % sbW;
            var yS = sbPos / sbW;
            var infer = false;
            var coded = true;
            if (i < lastSubBlock && i > 0) {
                var csbf = 0;
                if (xS < sbW - 1) {
                    csbf += sbCoded[sbPos + 1];
                }
                if (yS < sbH - 1) {
                    csbf += sbCoded[sbPos + sbW];
                }
                coded = cabac.decodeBin(sigGroupBase + Math.min(csbf, 1)) != 0;
                infer = true;
            }
            sbCoded[sbPos] = coded ? 1 : 0;
            if (coded && (xS > 3 || yS > 3) && luma) {
                mtsZeroOut = false;
            }
            var firstSig = numSbCoeff;
            var lastSig = -1;
            var firstPosMode0 = i == lastSubBlock ? lastScanPos : numSbCoeff - 1;
            var firstPosMode1 = firstPosMode0;
            var xBase = xS << log2SbW;
            var yBase = yS << log2SbH;
            for (var n = 0; n < numSbCoeff; n++) {
                var p = inScan[n];
                sbPositions[n] = (yBase + (p >> log2SbW)) * stride + xBase + (p & sbMaskW);
            }
            var gt2Count = 0;
            for (var n = firstPosMode0; n >= 0 && remBins >= 4; n--) {
                var pos = sbPositions[n];
                var xC = pos % stride;
                var yC = pos / stride;
                var isLast = i == lastSubBlock && n == lastScanPos;
                int sig;
                if (isLast) {
                    sig = 1;
                } else if (coded && (n > 0 || !infer)) {
                    var ctx = sigCtx(xC, yC, stride, zoWidth, zoHeight, luma, qState);
                    sig = cabac.decodeBin(sigBase + Math.max(0, qState - 1) * sigSetStride + ctx);
                    remBins--;
                    if (sig != 0) {
                        infer = false;
                    }
                } else {
                    sig = coded ? 1 : 0;
                }
                var pass1 = 0;
                if (sig != 0) {
                    sigFlags[pos] = 1;
                    int ctxOff;
                    if (isLast) {
                        ctxOff = 0;
                    } else {
                        ctxOff = gtxCtx(xC, yC, stride, zoWidth, zoHeight, luma);
                    }
                    var gt1 = cabac.decodeBin(gt1Base + ctxOff);
                    remBins--;
                    var par = 0;
                    var gt2 = 0;
                    if (gt1 != 0) {
                        par = cabac.decodeBin(parBase + ctxOff);
                        remBins--;
                        gt2 = cabac.decodeBin(gt2Base + ctxOff);
                        remBins--;
                    }
                    pass1 = 1 + par + gt1 + 2 * gt2;
                    if (lastSig == -1) {
                        lastSig = n;
                    }
                    firstSig = n;
                    if (gt2 != 0) {
                        gt2Count++;
                    }
                    absLevel[pos] = pass1;
                    this.pass1[pos] = pass1;
                    signBuf[n] = gt2;
                } else {
                    signBuf[n] = 0;
                }
                if (depQuant) {
                    qState = (QSTATE_TRANSITIONS >> ((qState << 2) + ((pass1 & 1) << 1))) & 3;
                }
                firstPosMode1 = n - 1;
            }
            for (var n = firstPosMode0; n > firstPosMode1; n--) {
                var pos = sbPositions[n];
                if (signBuf[n] != 0) {
                    var xC = pos % stride;
                    var yC = pos / stride;
                    var rice = riceParam(xC, yC, w, h, stride, 4);
                    var rem = cabac.decodeRemAbs(rice, 5, 15);
                    absLevel[pos] += 2 * rem;
                }
            }
            for (var n = firstPosMode1; n >= 0; n--) {
                var pos = sbPositions[n];
                if (coded) {
                    var xC = pos % stride;
                    var yC = pos / stride;
                    var rice = riceParam(xC, yC, w, h, stride, 0);
                    var zeroPos = (qState < 2 ? 1 : 2) << rice;
                    var dec = cabac.decodeRemAbs(rice, 5, 15);
                    var level = dec == zeroPos ? 0 : dec < zeroPos ? dec + 1 : dec;
                    absLevel[pos] = level;
                    if (level > 0) {
                        sigFlags[pos] = 1;
                        if (lastSig == -1) {
                            lastSig = n;
                        }
                        firstSig = n;
                    }
                    if (depQuant) {
                        qState = (QSTATE_TRANSITIONS >> ((qState << 2) + ((level & 1) << 1))) & 3;
                    }
                }
            }
            var hidden = sh.signDataHiding && lastSig - firstSig > 3;
            for (var n = numSbCoeff - 1; n >= 0; n--) {
                var pos = sbPositions[n];
                if (absLevel[pos] > 0 && (!hidden || n != firstSig)) {
                    signBuf[n] = cabac.decodeBypass();
                } else {
                    signBuf[n] = 0;
                }
            }
            if (depQuant) {
                qState = startQState;
                for (var n = numSbCoeff - 1; n >= 0; n--) {
                    var pos = sbPositions[n];
                    var level = absLevel[pos];
                    if (level > 0) {
                        var v = 2 * level - (qState > 1 ? 1 : 0);
                        out[base + pos] = signBuf[n] != 0 ? -v : v;
                    }
                    qState = (QSTATE_TRANSITIONS >> ((qState << 2) + ((level & 1) << 1))) & 3;
                }
            } else {
                var sum = 0;
                for (var n = numSbCoeff - 1; n >= 0; n--) {
                    var pos = sbPositions[n];
                    var level = absLevel[pos];
                    if (level > 0) {
                        var v = signBuf[n] != 0 ? -level : level;
                        if (hidden) {
                            sum += level;
                            if (n == firstSig && (sum & 1) == 1) {
                                v = -v;
                            }
                        }
                        out[base + pos] = v;
                    }
                }
            }
        }
    }

    /**
     * The positions of the coefficients of a subblock in scan order.
     */
    private final int[] scanPositions = new int[16];

    /**
     * Derives the context increment of {@code sig_coeff_flag} for a regular residual (9.3.4.2.8).
     *
     * @param xC the column of the coefficient
     * @param yC the row of the coefficient
     * @param stride the row stride of the significance and level maps
     * @param zoWidth the width of the coded area
     * @param zoHeight the height of the coded area
     * @param luma whether the block is a luma block
     * @param qState the dependent quantization state
     * @return the context index
     */
    private int sigCtx(int xC, int yC, int stride, int zoWidth, int zoHeight, boolean luma, int qState) {
        var pos = yC * stride + xC;
        var sum = 0;
        if (xC < zoWidth - 1) {
            sum += pass1[pos + 1];
            if (xC < zoWidth - 2) {
                sum += pass1[pos + 2];
            }
            if (yC < zoHeight - 1) {
                sum += pass1[pos + stride + 1];
            }
        }
        if (yC < zoHeight - 1) {
            sum += pass1[pos + stride];
            if (yC < zoHeight - 2) {
                sum += pass1[pos + 2 * stride];
            }
        }
        var d = xC + yC;
        var ctx = Math.min((sum + 1) >> 1, 3);
        if (luma) {
            ctx += d < 2 ? 8 : d < 5 ? 4 : 0;
        } else {
            ctx += d < 2 ? 4 : 0;
        }
        return ctx;
    }

    /**
     * The sum of the first pass levels of each coefficient of the block being parsed.
     */
    private final int[] pass1 = new int[64 * 64];

    /**
     * Derives the context offset of {@code abs_level_gtx_flag} and {@code par_level_flag} for a regular residual
     * (9.3.4.2.9), the last coefficient excluded.
     *
     * @param xC the column of the coefficient
     * @param yC the row of the coefficient
     * @param stride the row stride of the significance and level maps
     * @param zoWidth the width of the coded area
     * @param zoHeight the height of the coded area
     * @param luma whether the block is a luma block
     * @return the context offset
     */
    private int gtxCtx(int xC, int yC, int stride, int zoWidth, int zoHeight, boolean luma) {
        var pos = yC * stride + xC;
        var numSig = 0;
        var sum = 0;
        if (xC < zoWidth - 1) {
            numSig += sigFlags[pos + 1];
            sum += pass1[pos + 1];
            if (xC < zoWidth - 2) {
                numSig += sigFlags[pos + 2];
                sum += pass1[pos + 2];
            }
            if (yC < zoHeight - 1) {
                numSig += sigFlags[pos + stride + 1];
                sum += pass1[pos + stride + 1];
            }
        }
        if (yC < zoHeight - 1) {
            numSig += sigFlags[pos + stride];
            sum += pass1[pos + stride];
            if (yC < zoHeight - 2) {
                numSig += sigFlags[pos + 2 * stride];
                sum += pass1[pos + 2 * stride];
            }
        }
        var offset = Math.min(sum - numSig, 4);
        var d = xC + yC;
        if (luma) {
            return 1 + offset + (d == 0 ? 15 : d < 3 ? 10 : d < 10 ? 5 : 0);
        }
        return 1 + offset + (d == 0 ? 5 : 0);
    }

    /**
     * Derives the Rice parameter of {@code abs_remainder} ({@code baseLevel} 4) or {@code dec_abs_level}
     * ({@code baseLevel} 0) (9.3.3.2).
     *
     * @param xC the column of the coefficient
     * @param yC the row of the coefficient
     * @param w the width of the block
     * @param h the height of the block
     * @param stride the row stride of the level map
     * @param baseLevel the base level
     * @return the Rice parameter
     */
    private int riceParam(int xC, int yC, int w, int h, int stride, int baseLevel) {
        var pos = yC * stride + xC;
        var sum = 0;
        if (xC < w - 1) {
            sum += absLevel[pos + 1];
            if (xC < w - 2) {
                sum += absLevel[pos + 2];
            }
            if (yC < h - 1) {
                sum += absLevel[pos + stride + 1];
            }
        }
        if (yC < h - 1) {
            sum += absLevel[pos + stride];
            if (yC < h - 2) {
                sum += absLevel[pos + 2 * stride];
            }
        }
        return RICE_TABLE[Math.clamp(sum - baseLevel * 5, 0, 31)];
    }

    /**
     * Parses {@code residual_ts_coding( )} (7.3.11.12) of a transform skipped block.
     *
     * @param cu the coding unit
     * @param out the coefficient buffer
     * @param base the index of the block in the buffer
     * @param cIdx the component
     * @param log2W the base 2 logarithm of the width
     * @param log2H the base 2 logarithm of the height
     */
    private void residualTs(VvcCu cu, int[] out, int base, int cIdx, int log2W, int log2H) {
        var w = 1 << log2W;
        var h = 1 << log2H;
        var stride = w;
        var bdpcm = cIdx == 0 ? cu.bdpcmLuma != 0 : cu.bdpcmChroma != 0;
        var log2SbW = Math.min(log2W, log2H) < 2 ? 1 : 2;
        var log2SbH = log2SbW;
        if (log2W + log2H > 3) {
            if (log2W < 2) {
                log2SbW = log2W;
                log2SbH = 4 - log2SbW;
            } else if (log2H < 2) {
                log2SbH = log2H;
                log2SbW = 4 - log2SbH;
            }
        }
        var numSbCoeff = 1 << (log2SbW + log2SbH);
        var sbW = 1 << (log2W - log2SbW);
        var sbH = 1 << (log2H - log2SbH);
        var lastSubBlock = sbW * sbH - 1;
        var sbScan = VvcScan.diagonal(log2W - log2SbW, log2H - log2SbH);
        var inScan = VvcScan.diagonal(log2SbW, log2SbH);
        var sbMaskW = (1 << log2SbW) - 1;
        var inferSbCbf = true;
        var remBins = ((1 << (log2W + log2H)) * 7) >> 2;
        Arrays.fill(out, base, base + w * h, 0);
        Arrays.fill(absLevel, 0, w * h, 0);
        Arrays.fill(sigFlags, 0, w * h, (byte) 0);
        Arrays.fill(sbCoded, 0, sbW * sbH, 0);
        var signLevel = tsSign;
        Arrays.fill(signLevel, 0, w * h, (byte) 0);
        var positions = scanPositions;
        var gtx = tsGtx;
        var riceTs = 1;
        for (var i = 0; i <= lastSubBlock; i++) {
            var sbPos = sbScan[i];
            var xS = sbPos % sbW;
            var yS = sbPos / sbW;
            var coded = true;
            if (i != lastSubBlock || !inferSbCbf) {
                var ctx = 0;
                if (xS > 0) {
                    ctx += sbCoded[sbPos - 1];
                }
                if (yS > 0) {
                    ctx += sbCoded[sbPos - sbW];
                }
                coded = cabac.decodeBin(VvcContexts.TS_SIG_COEFF_GROUP + ctx) != 0;
            }
            sbCoded[sbPos] = coded ? 1 : 0;
            if (coded && i < lastSubBlock) {
                inferSbCbf = false;
            }
            var xBase = xS << log2SbW;
            var yBase = yS << log2SbH;
            for (var n = 0; n < numSbCoeff; n++) {
                var p = inScan[n];
                positions[n] = (yBase + (p >> log2SbW)) * stride + xBase + (p & sbMaskW);
            }
            var inferSig = true;
            var lastPass1 = -1;
            for (var n = 0; n <= numSbCoeff - 1 && remBins >= 4; n++) {
                var pos = positions[n];
                var xC = pos % stride;
                var yC = pos / stride;
                lastPass1 = n;
                var sig = 0;
                if (coded && (n != numSbCoeff - 1 || !inferSig)) {
                    var ctx = (xC > 0 ? sigFlags[pos - 1] : 0) + (yC > 0 ? sigFlags[pos - stride] : 0);
                    sig = cabac.decodeBin(VvcContexts.TS_SIG_FLAG + ctx);
                    remBins--;
                    if (sig != 0) {
                        inferSig = false;
                    }
                } else if (coded) {
                    sig = 1;
                }
                gtx[n] = 0;
                if (sig != 0) {
                    sigFlags[pos] = 1;
                    var left = xC > 0 ? signLevel[pos - 1] : 0;
                    var top = yC > 0 ? signLevel[pos - stride] : 0;
                    int sctx;
                    if ((left == 0 && top == 0) || left == -top) {
                        sctx = bdpcm ? 3 : 0;
                    } else if (left >= 0 && top >= 0) {
                        sctx = bdpcm ? 4 : 1;
                    } else {
                        sctx = bdpcm ? 5 : 2;
                    }
                    var sign = cabac.decodeBin(VvcContexts.TS_RESIDUAL_SIGN + sctx);
                    remBins--;
                    signLevel[pos] = (byte) (sign != 0 ? -1 : 1);
                    int gctx;
                    if (bdpcm) {
                        gctx = 3;
                    } else {
                        gctx = (xC > 0 ? sigFlags[pos - 1] : 0) + (yC > 0 ? sigFlags[pos - stride] : 0);
                    }
                    var g0 = cabac.decodeBin(VvcContexts.TS_LRG1_FLAG + gctx);
                    remBins--;
                    var par = 0;
                    if (g0 != 0) {
                        par = cabac.decodeBin(VvcContexts.TS_PAR_FLAG);
                        remBins--;
                    }
                    absLevel[pos] = 1 + g0 + par;
                    gtx[n] = g0;
                }
            }
            var lastPass2 = -1;
            for (var n = 0; n <= numSbCoeff - 1 && remBins >= 4; n++) {
                var pos = positions[n];
                if (n <= lastPass1) {
                    var prev = gtx[n];
                    for (var j = 1; j < 5; j++) {
                        if (prev != 0) {
                            var g = cabac.decodeBin(VvcContexts.TS_GTX_FLAG + j);
                            remBins--;
                            absLevel[pos] += 2 * g;
                            prev = g;
                        } else {
                            break;
                        }
                    }
                    tsGtxLast[n] = prev;
                }
                lastPass2 = n;
            }
            for (var n = 0; n <= numSbCoeff - 1; n++) {
                var pos = positions[n];
                var xC = pos % stride;
                var yC = pos / stride;
                var level = absLevel[pos];
                if (n <= lastPass2) {
                    if (level >= 10 && n <= lastPass1 && tsGtxLast[n] != 0) {
                        level += 2 * cabac.decodeRemAbs(riceTs, 5, 15);
                    }
                } else if (n <= lastPass1) {
                    if (level >= 2 && gtx[n] != 0) {
                        level += 2 * cabac.decodeRemAbs(riceTs, 5, 15);
                    }
                } else if (coded) {
                    level = cabac.decodeRemAbs(riceTs, 5, 15);
                    if (level != 0) {
                        sigFlags[pos] = 1;
                        signLevel[pos] = (byte) (cabac.decodeBypass() != 0 ? -1 : 1);
                    }
                }
                if (!bdpcm && n <= lastPass1 && level > 0) {
                    var absLeft = xC > 0 ? absLevel[pos - 1] : 0;
                    var absAbove = yC > 0 ? absLevel[pos - stride] : 0;
                    var pred = Math.max(absLeft, absAbove);
                    if (level == 1 && pred > 0) {
                        level = pred;
                    } else if (level > 0 && level <= pred) {
                        level--;
                    }
                }
                absLevel[pos] = level;
                if (level > 0) {
                    out[base + pos] = signLevel[pos] < 0 ? -level : level;
                }
            }
        }
    }

    /**
     * The coefficient signs of the transform skipped block being parsed.
     */
    private final byte[] tsSign = new byte[64 * 64];
    /**
     * The greater-than flags of the coefficients of the subblock being parsed.
     */
    private final int[] tsGtx = new int[16];
    /**
     * Whether each coefficient of the subblock being parsed took its last greater-than flag.
     */
    private final int[] tsGtxLast = new int[16];

    /**
     * Decodes a truncated binary value of bypass bins (9.3.3.4).
     *
     * @param max the number of values
     * @return the value
     */
    private int truncatedBinary(int max) {
        var k = 31 - Integer.numberOfLeadingZeros(max);
        var u = (1 << (k + 1)) - max;
        var value = cabac.decodeBypassBins(k);
        if (value >= u) {
            value = ((value << 1) | cabac.decodeBypass()) - u;
        }
        return value;
    }

    /**
     * Decodes a truncated unary value of bypass bins.
     *
     * @param max the largest value
     * @return the value
     */
    private int unaryMaxBypass(int max) {
        for (var k = 0; k < max; k++) {
            if (cabac.decodeBypass() == 0) {
                return k;
            }
        }
        return max;
    }

    /**
     * Decodes a k-th order Exp-Golomb value of bypass bins (9.3.3.12).
     *
     * @param k the order
     * @return the value
     */
    private int expGolombBypass(int k) {
        var value = 0;
        while (k < 32 && cabac.decodeBypass() != 0) {
            value += 1 << k;
            k++;
        }
        if (k > 0) {
            value += cabac.decodeBypassBins(k);
        }
        return value;
    }
}
