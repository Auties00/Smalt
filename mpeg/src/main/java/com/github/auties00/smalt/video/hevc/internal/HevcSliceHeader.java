package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed slice segment header (ITU-T H.265 7.3.6) with the variables derived from it.
 *
 * <p>A dependent slice segment copies every field of the slice header from the independent slice
 * segment that precedes it, except its address and entry points.
 */
public final class HevcSliceHeader {
    /**
     * The {@code slice_type} of a B slice.
     */
    public static final int B = 0;

    /**
     * The {@code slice_type} of a P slice.
     */
    public static final int P = 1;

    /**
     * The {@code slice_type} of an I slice.
     */
    public static final int I = 2;

    /**
     * The NAL unit type of the slice segment.
     */
    public int nalType;

    /**
     * The {@code nuh_temporal_id_plus1 - 1} of the slice segment.
     */
    public int temporalId;

    /**
     * {@code first_slice_segment_in_pic_flag}.
     */
    public boolean firstSliceSegmentInPic;

    /**
     * {@code no_output_of_prior_pics_flag}.
     */
    public boolean noOutputOfPriorPics;

    /**
     * The picture parameter set the segment refers to.
     */
    public HevcPps pps;

    /**
     * The sequence parameter set of {@link #pps}.
     */
    public HevcSps sps;

    /**
     * {@code dependent_slice_segment_flag}.
     */
    public boolean dependentSliceSegment;

    /**
     * {@code slice_segment_address}, a raster scan coding tree block address.
     */
    public int segmentAddress;

    /**
     * {@code SliceAddrRs}: the address of the first coding tree block of the slice the segment
     * belongs to.
     */
    public int sliceAddressRs;

    /**
     * {@code slice_type}.
     */
    public int sliceType;

    /**
     * {@code pic_output_flag}, 1 when absent.
     */
    public boolean picOutput = true;

    /**
     * {@code colour_plane_id}: the color plane of the slice with separate color planes, 0
     * otherwise.
     */
    public int colorPlaneId;

    /**
     * {@code slice_pic_order_cnt_lsb}, 0 for IDR pictures.
     */
    public int pocLsb;

    /**
     * The short-term reference picture set the picture uses: one of the SPS sets or the one coded
     * in the header; empty for IDR pictures.
     */
    public ShortTermRps shortTermRps = new ShortTermRps();

    /**
     * The number of long-term entries, {@code num_long_term_sps + num_long_term_pics}.
     */
    public int numLongTerm;

    /**
     * {@code num_long_term_sps}.
     */
    public int numLongTermSps;

    /**
     * {@code PocLsbLt} of each long-term entry.
     */
    public final int[] pocLsbLt = new int[32];

    /**
     * {@code UsedByCurrPicLt} of each long-term entry.
     */
    public final boolean[] usedByCurrPicLt = new boolean[32];

    /**
     * {@code delta_poc_msb_present_flag} of each long-term entry.
     */
    public final boolean[] deltaPocMsbPresent = new boolean[32];

    /**
     * {@code DeltaPocMsbCycleLt} of each long-term entry.
     */
    public final int[] deltaPocMsbCycleLt = new int[32];

    /**
     * {@code slice_temporal_mvp_enabled_flag}.
     */
    public boolean temporalMvpEnabled;

    /**
     * {@code slice_sao_luma_flag}.
     */
    public boolean saoLuma;

    /**
     * {@code slice_sao_chroma_flag}.
     */
    public boolean saoChroma;

    /**
     * {@code num_ref_idx_l0_active_minus1 + 1} and {@code num_ref_idx_l1_active_minus1 + 1}, 0 for
     * lists the slice type does not use.
     */
    public final int[] numRefIdx = new int[2];

    /**
     * {@code ref_pic_list_modification_flag_l0} and {@code ref_pic_list_modification_flag_l1}.
     */
    public final boolean[] listModification = new boolean[2];

    /**
     * {@code list_entry_l0} and {@code list_entry_l1}.
     */
    public final int[][] listEntry = new int[2][16];

    /**
     * Whether each entry of the reference picture lists is the current picture, indexed
     * {@code [list][refIdx]} (8.3.4, with {@code pps_curr_pic_ref_enabled_flag}).
     */
    public final boolean[][] currentPictureEntry = new boolean[2][16];

    /**
     * {@code use_integer_mv_flag}: whether the motion vectors of the slice that refer to other
     * pictures than the current one have integer resolution.
     */
    public boolean useIntegerMv;

    /**
     * {@code mvd_l1_zero_flag}.
     */
    public boolean mvdL1Zero;

    /**
     * {@code cabac_init_flag}.
     */
    public boolean cabacInit;

    /**
     * {@code collocated_from_l0_flag}, 1 when absent.
     */
    public boolean collocatedFromL0 = true;

    /**
     * {@code collocated_ref_idx}.
     */
    public int collocatedRefIdx;

    /**
     * Whether the slice carries a prediction weight table.
     */
    public boolean weighted;

    /**
     * {@code luma_log2_weight_denom}.
     */
    public int lumaLog2WeightDenom;

    /**
     * {@code ChromaLog2WeightDenom}.
     */
    public int chromaLog2WeightDenom;

    /**
     * The weights {@code LumaWeightLX} and {@code ChromaWeightLX}, indexed
     * {@code [list][refIdx][cIdx]}.
     */
    public final int[][][] weights = new int[2][16][3];

    /**
     * The offsets {@code luma_offset_lX} and {@code ChromaOffsetLX} (before the bit depth scaling),
     * indexed {@code [list][refIdx][cIdx]}.
     */
    public final int[][][] offsets = new int[2][16][3];

    /**
     * {@code MaxNumMergeCand}.
     */
    public int maxNumMergeCand;

    /**
     * {@code SliceQpY}.
     */
    public int sliceQp;

    /**
     * {@code slice_cb_qp_offset}.
     */
    public int cbQpOffset;

    /**
     * {@code slice_cr_qp_offset}.
     */
    public int crQpOffset;

    /**
     * {@code slice_act_y_qp_offset}, {@code slice_act_cb_qp_offset} and
     * {@code slice_act_cr_qp_offset}.
     */
    public final int[] actQpOffset = new int[3];

    /**
     * {@code cu_chroma_qp_offset_enabled_flag}.
     */
    public boolean cuChromaQpOffsetEnabled;

    /**
     * {@code slice_deblocking_filter_disabled_flag}.
     */
    public boolean deblockingDisabled;

    /**
     * {@code slice_beta_offset_div2}.
     */
    public int betaOffsetDiv2;

    /**
     * {@code slice_tc_offset_div2}.
     */
    public int tcOffsetDiv2;

    /**
     * {@code slice_loop_filter_across_slices_enabled_flag}.
     */
    public boolean loopFilterAcrossSlices;

    /**
     * The number of entry points, {@code num_entry_point_offsets}.
     */
    public int numEntryPoints;

    /**
     * {@code entry_point_offset_minus1 + 1} of each entry point, in bytes of the NAL unit
     * (emulation prevention bytes included).
     */
    public int[] entryPointOffsets = new int[0];

    /**
     * The index of the first byte of the slice segment data in the unescaped payload buffer.
     */
    public int dataOffset;

    /**
     * Constructs an empty header.
     */
    public HevcSliceHeader() {
    }

    /**
     * Returns whether the slice is an intra slice.
     *
     * @return {@code true} for an I slice
     */
    public boolean isIntra() {
        return sliceType == I;
    }

    /**
     * Returns the number of pictures the current picture may reference, {@code NumPicTotalCurr}.
     *
     * @return the count
     */
    public int numPicTotalCurr() {
        var total = 0;
        for (var i = 0; i < shortTermRps.numNegative; i++) {
            if (shortTermRps.usedS0[i]) {
                total++;
            }
        }
        for (var i = 0; i < shortTermRps.numPositive; i++) {
            if (shortTermRps.usedS1[i]) {
                total++;
            }
        }
        for (var i = 0; i < numLongTerm; i++) {
            if (usedByCurrPicLt[i]) {
                total++;
            }
        }
        if (pps.currPicRefEnabled) {
            total++;
        }
        return total;
    }

    /**
     * Returns the number of pictures of the reference picture set that the current picture may
     * reference, {@code NumPicTotalCurr} without the current picture itself.
     *
     * @return the count
     */
    public int numRpsPicturesCurr() {
        return numPicTotalCurr() - (pps.currPicRefEnabled ? 1 : 0);
    }

    /**
     * Parses a slice segment header that starts after the NAL unit header.
     *
     * @param reader the reader positioned after the NAL unit header
     * @param nalType the NAL unit type
     * @param temporalId the temporal identifier
     * @param ppsTable the picture parameter sets by identifier
     * @param spsTable the sequence parameter sets by identifier
     * @param independent the header of the last independent slice segment of the picture, which a
     * dependent slice segment copies; {@code null} when there is none
     * @return the header, positioned at the slice segment data ({@link #dataOffset})
     * @throws MediaException.Malformed if a field is out of range, a parameter set is missing or
     * the payload is truncated
     */
    public static HevcSliceHeader parse(H264BitReader reader, int nalType, int temporalId, HevcPps[] ppsTable,
                                        HevcSps[] spsTable, HevcSliceHeader independent) {
        var first = reader.readFlag();
        var noOutputOfPriorPics = false;
        if (HevcNal.isIrap(nalType)) {
            noOutputOfPriorPics = reader.readFlag();
        }
        var pps = ppsTable[reader.readUe(63, "slice_pic_parameter_set_id")];
        if (pps == null) {
            throw new MediaException.Malformed("slice refers to a missing picture parameter set");
        }
        var sps = spsTable[pps.spsId];
        if (sps == null) {
            throw new MediaException.Malformed("picture parameter set refers to a missing sequence parameter set");
        }
        var dependent = false;
        if (!first && pps.dependentSliceSegmentsEnabled) {
            dependent = reader.readFlag();
        }
        var address = 0;
        if (!first) {
            var bits = 32 - Integer.numberOfLeadingZeros(sps.sizeInCtbs - 1);
            address = reader.readBits(bits);
            if (address >= sps.sizeInCtbs) {
                throw new MediaException.Malformed("slice_segment_address out of range");
            }
        }
        HevcSliceHeader header;
        if (dependent) {
            if (independent == null || independent.pps != pps) {
                throw new MediaException.Malformed("dependent slice segment without its slice");
            }
            header = independent.copyForDependent();
        } else {
            header = new HevcSliceHeader();
            header.pps = pps;
            header.sps = sps;
            header.sliceAddressRs = address;
            header.parseIndependent(reader, nalType);
        }
        header.nalType = nalType;
        header.temporalId = temporalId;
        header.firstSliceSegmentInPic = first;
        header.noOutputOfPriorPics = noOutputOfPriorPics;
        header.dependentSliceSegment = dependent;
        header.segmentAddress = address;
        header.numEntryPoints = 0;
        header.entryPointOffsets = new int[0];
        if (pps.tilesEnabled || pps.entropyCodingSync) {
            var count = reader.readUe(sps.sizeInCtbs - 1, "num_entry_point_offsets");
            if (count > 0) {
                var length = reader.readUe(31, "offset_len_minus1") + 1;
                var offsets = new int[count];
                for (var i = 0; i < count; i++) {
                    var value = reader.readLong(length) + 1;
                    if (value > Integer.MAX_VALUE) {
                        throw new MediaException.Malformed("entry_point_offset_minus1 out of range");
                    }
                    offsets[i] = (int) value;
                }
                header.entryPointOffsets = offsets;
            }
            header.numEntryPoints = count;
        }
        if (pps.sliceHeaderExtensionPresent) {
            var length = reader.readUe(256, "slice_segment_header_extension_length");
            reader.skip(8L * length);
        }
        if (reader.readBit() != 1) {
            throw new MediaException.Malformed("missing alignment bit after the slice segment header");
        }
        reader.alignToByte();
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated slice segment header");
        }
        header.dataOffset = reader.bytePosition();
        return header;
    }

    /**
     * Returns a copy of this independent header for a dependent slice segment that follows it.
     *
     * @return the copy, sharing the immutable reference picture set
     */
    private HevcSliceHeader copyForDependent() {
        var copy = new HevcSliceHeader();
        copy.pps = pps;
        copy.sps = sps;
        copy.sliceAddressRs = sliceAddressRs;
        copy.sliceType = sliceType;
        copy.picOutput = picOutput;
        copy.colorPlaneId = colorPlaneId;
        copy.pocLsb = pocLsb;
        copy.shortTermRps = shortTermRps;
        copy.numLongTerm = numLongTerm;
        copy.numLongTermSps = numLongTermSps;
        System.arraycopy(pocLsbLt, 0, copy.pocLsbLt, 0, 32);
        System.arraycopy(usedByCurrPicLt, 0, copy.usedByCurrPicLt, 0, 32);
        System.arraycopy(deltaPocMsbPresent, 0, copy.deltaPocMsbPresent, 0, 32);
        System.arraycopy(deltaPocMsbCycleLt, 0, copy.deltaPocMsbCycleLt, 0, 32);
        copy.temporalMvpEnabled = temporalMvpEnabled;
        copy.saoLuma = saoLuma;
        copy.saoChroma = saoChroma;
        copy.numRefIdx[0] = numRefIdx[0];
        copy.numRefIdx[1] = numRefIdx[1];
        for (var list = 0; list < 2; list++) {
            copy.listModification[list] = listModification[list];
            System.arraycopy(listEntry[list], 0, copy.listEntry[list], 0, 16);
            System.arraycopy(currentPictureEntry[list], 0, copy.currentPictureEntry[list], 0, 16);
            for (var i = 0; i < 16; i++) {
                System.arraycopy(weights[list][i], 0, copy.weights[list][i], 0, 3);
                System.arraycopy(offsets[list][i], 0, copy.offsets[list][i], 0, 3);
            }
        }
        copy.useIntegerMv = useIntegerMv;
        System.arraycopy(actQpOffset, 0, copy.actQpOffset, 0, 3);
        copy.mvdL1Zero = mvdL1Zero;
        copy.cabacInit = cabacInit;
        copy.collocatedFromL0 = collocatedFromL0;
        copy.collocatedRefIdx = collocatedRefIdx;
        copy.weighted = weighted;
        copy.lumaLog2WeightDenom = lumaLog2WeightDenom;
        copy.chromaLog2WeightDenom = chromaLog2WeightDenom;
        copy.maxNumMergeCand = maxNumMergeCand;
        copy.sliceQp = sliceQp;
        copy.cbQpOffset = cbQpOffset;
        copy.crQpOffset = crQpOffset;
        copy.cuChromaQpOffsetEnabled = cuChromaQpOffsetEnabled;
        copy.deblockingDisabled = deblockingDisabled;
        copy.betaOffsetDiv2 = betaOffsetDiv2;
        copy.tcOffsetDiv2 = tcOffsetDiv2;
        copy.loopFilterAcrossSlices = loopFilterAcrossSlices;
        return copy;
    }

    /**
     * Parses the fields of an independent slice segment header, from {@code slice_reserved_flag}
     * to {@code slice_loop_filter_across_slices_enabled_flag}.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param nalType the NAL unit type
     * @throws MediaException.Malformed if a field is out of range
     */
    private void parseIndependent(H264BitReader reader, int nalType) {
        reader.readBits(pps.numExtraSliceHeaderBits);
        sliceType = reader.readUe(2, "slice_type");
        if (HevcNal.isIrap(nalType) && sliceType != I && !pps.currPicRefEnabled) {
            throw new MediaException.Malformed("intra random access point picture with a P or B slice");
        }
        if (pps.outputFlagPresent) {
            picOutput = reader.readFlag();
        }
        if (sps.separateColorPlane) {
            colorPlaneId = reader.readBits(2);
            if (colorPlaneId == 3) {
                throw new MediaException.Malformed("colour_plane_id is 3");
            }
        }
        if (!HevcNal.isIdr(nalType)) {
            pocLsb = reader.readBits(sps.log2MaxPocLsb);
            if (!reader.readFlag()) {
                shortTermRps = ShortTermRps.parse(reader, sps.shortTermRps.length, sps.shortTermRps,
                        sps.shortTermRps.length, ShortTermRps.MAX_PICTURES);
            } else {
                var count = sps.shortTermRps.length;
                if (count == 0) {
                    throw new MediaException.Malformed("short_term_ref_pic_set_sps_flag without SPS sets");
                }
                var bits = 32 - Integer.numberOfLeadingZeros(count - 1);
                var index = reader.readBits(bits);
                if (index >= count) {
                    throw new MediaException.Malformed("short_term_ref_pic_set_idx out of range");
                }
                shortTermRps = sps.shortTermRps[index];
            }
            if (sps.longTermRefsPresent) {
                parseLongTerm(reader);
            }
            if (sps.temporalMvpEnabled) {
                temporalMvpEnabled = reader.readFlag();
            }
        }
        if (sps.saoEnabled) {
            saoLuma = reader.readFlag();
            if (sps.chromaArrayType != 0) {
                saoChroma = reader.readFlag();
            }
        }
        if (sliceType != I) {
            numRefIdx[0] = pps.numRefIdxL0Default;
            numRefIdx[1] = sliceType == B ? pps.numRefIdxL1Default : 0;
            if (reader.readFlag()) {
                numRefIdx[0] = reader.readUe(14, "num_ref_idx_l0_active_minus1") + 1;
                if (sliceType == B) {
                    numRefIdx[1] = reader.readUe(14, "num_ref_idx_l1_active_minus1") + 1;
                }
            }
            var total = numPicTotalCurr();
            if (total == 0) {
                throw new MediaException.Malformed("inter slice without reference pictures");
            }
            if (pps.listsModificationPresent && total > 1) {
                var bits = 32 - Integer.numberOfLeadingZeros(total - 1);
                for (var list = 0; list < (sliceType == B ? 2 : 1); list++) {
                    listModification[list] = reader.readFlag();
                    if (listModification[list]) {
                        for (var i = 0; i < numRefIdx[list]; i++) {
                            var entry = reader.readBits(bits);
                            if (entry >= total) {
                                throw new MediaException.Malformed("list_entry out of range");
                            }
                            listEntry[list][i] = entry;
                        }
                    }
                }
            }
            markCurrentPictureEntries();
            if (sliceType == B) {
                mvdL1Zero = reader.readFlag();
            }
            if (pps.cabacInitPresent) {
                cabacInit = reader.readFlag();
            }
            if (temporalMvpEnabled) {
                if (sliceType == B) {
                    collocatedFromL0 = reader.readFlag();
                }
                var list = collocatedFromL0 ? 0 : 1;
                if (numRefIdx[list] > 1) {
                    collocatedRefIdx = reader.readUe(numRefIdx[list] - 1, "collocated_ref_idx");
                }
            }
            if ((pps.weightedPred && sliceType == P) || (pps.weightedBipred && sliceType == B)) {
                parsePredWeightTable(reader);
            }
            maxNumMergeCand = 5 - reader.readUe(4, "five_minus_max_num_merge_cand");
            useIntegerMv = sps.mvResolutionControl == 2 ? reader.readFlag() : sps.mvResolutionControl == 1;
        }
        var qpBdOffset = 6 * (sps.bitDepthLuma - 8);
        sliceQp = 26 + pps.initQpMinus26 + reader.readSe(-(26 + pps.initQpMinus26 + qpBdOffset),
                25 - pps.initQpMinus26, "slice_qp_delta");
        if (pps.sliceChromaQpOffsetsPresent) {
            cbQpOffset = reader.readSe(-12, 12, "slice_cb_qp_offset");
            crQpOffset = reader.readSe(-12, 12, "slice_cr_qp_offset");
        }
        if (pps.sliceActQpOffsetsPresent) {
            for (var c = 0; c < 3; c++) {
                actQpOffset[c] = reader.readSe(-12, 12, "slice_act_qp_offset");
                var total = pps.actQpOffset[c] + actQpOffset[c];
                if (total < -12 || total > 12) {
                    throw new MediaException.Malformed("adaptive color transform QP offset out of range");
                }
            }
        }
        if (pps.chromaQpOffsetList) {
            cuChromaQpOffsetEnabled = reader.readFlag();
        }
        deblockingDisabled = pps.deblockingDisabled;
        betaOffsetDiv2 = pps.betaOffsetDiv2;
        tcOffsetDiv2 = pps.tcOffsetDiv2;
        if (pps.deblockingOverrideEnabled && reader.readFlag()) {
            deblockingDisabled = reader.readFlag();
            if (!deblockingDisabled) {
                betaOffsetDiv2 = reader.readSe(-6, 6, "slice_beta_offset_div2");
                tcOffsetDiv2 = reader.readSe(-6, 6, "slice_tc_offset_div2");
            }
        }
        loopFilterAcrossSlices = pps.loopFilterAcrossSlices;
        if (pps.loopFilterAcrossSlices && (saoLuma || saoChroma || !deblockingDisabled)) {
            loopFilterAcrossSlices = reader.readFlag();
        }
    }

    /**
     * Derives which entries of the reference picture lists are the current picture, from the sizes
     * of the reference picture set lists, {@code num_ref_idx_lX_active_minus1} and the list
     * modification (equations 8-8 to 8-11).
     */
    private void markCurrentPictureEntries() {
        for (var list = 0; list < 2; list++) {
            java.util.Arrays.fill(currentPictureEntry[list], false);
        }
        if (!pps.currPicRefEnabled) {
            return;
        }
        var rps = numRpsPicturesCurr();
        var total = rps + 1;
        for (var list = 0; list < (sliceType == B ? 2 : 1); list++) {
            var count = Math.max(numRefIdx[list], total);
            for (var i = 0; i < numRefIdx[list]; i++) {
                var index = listModification[list] ? listEntry[list][i] : i;
                // RefPicListTemp repeats the reference picture set pictures followed by the current picture
                currentPictureEntry[list][i] = index < count && index % total == rps;
            }
            if (list == 0 && !listModification[0] && count > numRefIdx[0]) {
                currentPictureEntry[0][numRefIdx[0] - 1] = true;
            }
        }
    }

    /**
     * Parses the long-term reference picture entries of the header and derives
     * {@code DeltaPocMsbCycleLt} (equation 7-52).
     *
     * @param reader the reader, positioned at the syntax structure
     * @throws MediaException.Malformed if a count or index is out of range
     */
    private void parseLongTerm(H264BitReader reader) {
        numLongTermSps = 0;
        if (sps.numLongTermRefPicsSps > 0) {
            numLongTermSps = reader.readUe(sps.numLongTermRefPicsSps, "num_long_term_sps");
        }
        var numLongTermPics = reader.readUe(32 - numLongTermSps, "num_long_term_pics");
        numLongTerm = numLongTermSps + numLongTermPics;
        if (numLongTerm + shortTermRps.numDeltaPocs() > ShortTermRps.MAX_PICTURES) {
            throw new MediaException.Malformed("too many reference pictures");
        }
        var indexBits = sps.numLongTermRefPicsSps > 1 ? 32 - Integer.numberOfLeadingZeros(sps.numLongTermRefPicsSps - 1) : 0;
        for (var i = 0; i < numLongTerm; i++) {
            if (i < numLongTermSps) {
                var index = indexBits > 0 ? reader.readBits(indexBits) : 0;
                if (index >= sps.numLongTermRefPicsSps) {
                    throw new MediaException.Malformed("lt_idx_sps out of range");
                }
                pocLsbLt[i] = sps.ltRefPicPocLsbSps[index];
                usedByCurrPicLt[i] = sps.usedByCurrPicLtSps[index];
            } else {
                pocLsbLt[i] = reader.readBits(sps.log2MaxPocLsb);
                usedByCurrPicLt[i] = reader.readFlag();
            }
            deltaPocMsbPresent[i] = reader.readFlag();
            var cycle = deltaPocMsbPresent[i] ? reader.readUe() : 0;
            if (cycle < 0) {
                throw new MediaException.Malformed("delta_poc_msb_cycle_lt out of range");
            }
            deltaPocMsbCycleLt[i] = i == 0 || i == numLongTermSps ? cycle : cycle + deltaPocMsbCycleLt[i - 1];
        }
    }

    /**
     * Parses a {@code pred_weight_table()} structure (ITU-T H.265 7.3.6.3) and derives the weights
     * and offsets (7.4.7.3).
     *
     * @param reader the reader, positioned at the syntax structure
     * @throws MediaException.Malformed if a field is out of range
     */
    private void parsePredWeightTable(H264BitReader reader) {
        weighted = true;
        lumaLog2WeightDenom = reader.readUe(7, "luma_log2_weight_denom");
        var chroma = sps.chromaArrayType != 0;
        if (chroma) {
            chromaLog2WeightDenom = lumaLog2WeightDenom + reader.readSe(-lumaLog2WeightDenom, 7 - lumaLog2WeightDenom,
                    "delta_chroma_log2_weight_denom");
        }
        var halfRangeLuma = 1 << (sps.highPrecisionOffsets ? sps.bitDepthLuma - 1 : 7);
        var halfRange = 1 << (sps.highPrecisionOffsets ? sps.bitDepthChroma - 1 : 7);
        for (var list = 0; list < (sliceType == B ? 2 : 1); list++) {
            var count = numRefIdx[list];
            var lumaFlags = new boolean[count];
            var chromaFlags = new boolean[count];
            // the flags of the current picture are absent (7.3.6.3)
            for (var i = 0; i < count; i++) {
                lumaFlags[i] = !currentPictureEntry[list][i] && reader.readFlag();
            }
            if (chroma) {
                for (var i = 0; i < count; i++) {
                    chromaFlags[i] = !currentPictureEntry[list][i] && reader.readFlag();
                }
            }
            for (var i = 0; i < count; i++) {
                var weight = weights[list][i];
                var offset = offsets[list][i];
                weight[0] = 1 << lumaLog2WeightDenom;
                offset[0] = 0;
                if (lumaFlags[i]) {
                    weight[0] += reader.readSe(-128, 127, "delta_luma_weight");
                    offset[0] = reader.readSe(-halfRangeLuma, halfRangeLuma - 1, "luma_offset");
                }
                for (var c = 1; c < 3; c++) {
                    weight[c] = 1 << chromaLog2WeightDenom;
                    offset[c] = 0;
                }
                if (chromaFlags[i]) {
                    for (var c = 1; c < 3; c++) {
                        weight[c] += reader.readSe(-128, 127, "delta_chroma_weight");
                        var delta = reader.readSe(-4 * halfRange, 4 * halfRange - 1, "delta_chroma_offset");
                        var value = halfRange + delta - ((halfRange * weight[c]) >> chromaLog2WeightDenom);
                        offset[c] = Math.clamp(value, -halfRange, halfRange - 1);
                    }
                }
            }
        }
    }
}
