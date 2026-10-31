/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp, source/Lib/CommonLib/Slice.cpp). Copyright (c)
 * 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD
 * License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed slice header (ITU-T H.266 7.3.7) with the values it inherits from its picture header.
 */
public final class VvcSliceHeader {
    /**
     * Constructs an empty header, which {@link #parse} fills.
     */
    public VvcSliceHeader() {
    }

    /**
     * The {@code sh_slice_type} of a B slice.
     */
    public static final int B = 0;
    /**
     * The {@code sh_slice_type} of a P slice.
     */
    public static final int P = 1;
    /**
     * The {@code sh_slice_type} of an I slice.
     */
    public static final int I = 2;

    /**
     * The picture header of the slice: its own or the picture header NAL unit's.
     */
    public VvcPictureHeader ph;
    /**
     * The picture parameter set of the slice.
     */
    public VvcPps pps;
    /**
     * The sequence parameter set of the slice.
     */
    public VvcSps sps;
    /**
     * The {@code nal_unit_type} of the slice.
     */
    public int nalType;
    /**
     * The {@code TemporalId} of the slice.
     */
    public int temporalId;
    /**
     * {@code sh_picture_header_in_slice_header_flag}.
     */
    public boolean pictureHeaderInSlice;
    /**
     * {@code sh_subpic_id}, or the identifier of the only subpicture.
     */
    public int subpicId;
    /**
     * The index of the subpicture whose identifier is {@link #subpicId}.
     */
    public int subpicIdx;
    /**
     * {@code sh_slice_address}.
     */
    public int sliceAddress;
    /**
     * The index of the slice in the picture parameter set's list of rectangular slices.
     */
    public int sliceIdx;
    /**
     * The CTU addresses of the slice in decoding order.
     */
    public int[] ctus;
    /**
     * {@code sh_slice_type}: {@link #B}, {@link #P} or {@link #I}.
     */
    public int sliceType;
    /**
     * {@code sh_no_output_of_prior_pics_flag}.
     */
    public boolean noOutputOfPriorPics;
    /**
     * {@code sh_alf_enabled_flag}, or the picture header's.
     */
    public boolean alfEnabled;
    /**
     * {@code sh_num_alf_aps_ids_luma}, or the picture header's.
     */
    public int numAlfApsLuma;
    /**
     * {@code sh_alf_aps_id_luma} of each luma filter set, or the picture header's.
     */
    public final int[] alfApsLuma = new int[8];
    /**
     * {@code sh_alf_cb_enabled_flag}, or the picture header's.
     */
    public boolean alfCb;
    /**
     * {@code sh_alf_cr_enabled_flag}, or the picture header's.
     */
    public boolean alfCr;
    /**
     * {@code sh_alf_aps_id_chroma}, or the picture header's.
     */
    public int alfApsChroma;
    /**
     * {@code sh_alf_cc_cb_enabled_flag}, or the picture header's.
     */
    public boolean ccAlfCb;
    /**
     * {@code sh_alf_cc_cb_aps_id}, or the picture header's.
     */
    public int ccAlfCbAps;
    /**
     * {@code sh_alf_cc_cr_enabled_flag}, or the picture header's.
     */
    public boolean ccAlfCr;
    /**
     * {@code sh_alf_cc_cr_aps_id}, or the picture header's.
     */
    public int ccAlfCrAps;
    /**
     * The adaptation parameter sets the slice's ALF syntax refers to, captured when the slice is decoded so that a
     * later parameter set with the same identifier does not change the filtering of this picture.
     */
    public final VvcAps[] alfLumaApsRefs = new VvcAps[8];
    /**
     * The adaptation parameter set of the chroma filters, captured when the slice header is parsed, or {@code null}.
     */
    public VvcAps alfChromaApsRef;
    /**
     * The adaptation parameter sets of the Cb and Cr cross-component filters, captured when the slice header is parsed.
     */
    public final VvcAps[] ccAlfApsRefs = new VvcAps[2];
    /**
     * {@code sh_lmcs_used_flag}.
     */
    public boolean lmcsUsed;
    /**
     * {@code sh_explicit_scaling_list_used_flag}.
     */
    public boolean scalingListUsed;
    /**
     * The reference picture lists of the slice.
     */
    public final VvcRefPicList[] rpl = {new VvcRefPicList(), new VvcRefPicList()};
    /**
     * {@code rpl_idx} of each list, or -1 when the list is coded in the header.
     */
    public final int[] rplIdx = {-1, -1};
    /**
     * {@code NumRefIdxActive} of each list.
     */
    public final int[] numRefIdxActive = new int[2];
    /**
     * {@code sh_cabac_init_flag}.
     */
    public boolean cabacInit;
    /**
     * {@code sh_collocated_from_l0_flag}, or the picture header's.
     */
    public boolean colFromL0 = true;
    /**
     * {@code sh_collocated_ref_idx}, or the picture header's.
     */
    public int colRefIdx;
    /**
     * The weighted prediction tables of the slice, or {@code null}.
     */
    public VvcPredWeights weights;
    /**
     * {@code SliceQpY}.
     */
    public int sliceQp;
    /**
     * {@code sh_cb_qp_offset} added to the picture parameter set's.
     */
    public int cbQpOffset;
    /**
     * {@code sh_cr_qp_offset} added to the picture parameter set's.
     */
    public int crQpOffset;
    /**
     * {@code sh_joint_cbcr_qp_offset} added to the picture parameter set's.
     */
    public int jointCbCrQpOffset;
    /**
     * {@code sh_cu_chroma_qp_offset_enabled_flag}.
     */
    public boolean cuChromaQpOffsetEnabled;
    /**
     * {@code sh_sao_luma_used_flag}.
     */
    public boolean saoLuma;
    /**
     * {@code sh_sao_chroma_used_flag}.
     */
    public boolean saoChroma;
    /**
     * {@code sh_deblocking_filter_disabled_flag}, or the inherited value.
     */
    public boolean deblockingDisabled;
    /**
     * {@code sh_luma_beta_offset_div2}, or the inherited value.
     */
    public int betaOffset;
    /**
     * {@code sh_luma_tc_offset_div2}, or the inherited value.
     */
    public int tcOffset;
    /**
     * {@code sh_cb_beta_offset_div2}, or the inherited value.
     */
    public int cbBetaOffset;
    /**
     * {@code sh_cb_tc_offset_div2}, or the inherited value.
     */
    public int cbTcOffset;
    /**
     * {@code sh_cr_beta_offset_div2}, or the inherited value.
     */
    public int crBetaOffset;
    /**
     * {@code sh_cr_tc_offset_div2}, or the inherited value.
     */
    public int crTcOffset;
    /**
     * {@code sh_dep_quant_used_flag}.
     */
    public boolean depQuant;
    /**
     * {@code sh_sign_data_hiding_used_flag}.
     */
    public boolean signDataHiding;
    /**
     * {@code sh_ts_residual_coding_disabled_flag}.
     */
    public boolean tsResidualCodingDisabled;
    /**
     * The byte offset in the unescaped NAL unit payload at which the slice data starts.
     */
    public int dataOffset;
    /**
     * The start of every entry point after the first, in bytes of the unescaped payload from {@link #dataOffset}.
     */
    public int[] entryPoints = new int[0];

    /**
     * The reference pictures of each list, set by the decoding process for reference picture lists construction.
     */
    public final VvcPicture[][] refPics = new VvcPicture[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * The picture order count of each active reference.
     */
    public final int[][] refPoc = new int[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * Whether each active reference is a long-term reference.
     */
    public final boolean[][] refLongTerm = new boolean[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * {@code RefPicScale[ i ][ j ][ 0 ]} and {@code [ 1 ]} of each active reference, {@code 1 << 14} when unscaled.
     */
    public final int[][] refScaleX = new int[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * The vertical scale factor of each active reference, {@code 1 << 14} when unscaled.
     */
    public final int[][] refScaleY = new int[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * {@code RprConstraintsActiveFlag} of each active reference.
     */
    public final boolean[][] rprActive = new boolean[2][VvcRefPicList.MAX_ENTRIES];
    /**
     * The picture order count of the picture the slice belongs to.
     */
    public int poc;

    /**
     * {@return whether the slice is intra only}
     */
    public boolean isIntra() {
        return sliceType == I;
    }

    /**
     * {@return whether the slice uses two reference picture lists}
     */
    public boolean isB() {
        return sliceType == B;
    }

    /**
     * Parses a slice header.
     *
     * @param r the reader, positioned after the NAL unit header
     * @param nalType the NAL unit type
     * @param ph the picture header in effect, or {@code null} when the slice must carry one
     * @param ppsTable the picture parameter sets received so far, indexed by id
     * @param removed the escaped offsets of the emulation prevention bytes of the NAL unit, relative to its first
     * payload byte after the two header bytes
     * @param removedCount the number of entries of {@code removed}
     * @return the header
     * @throws MediaException.Malformed if a syntax element is out of range or a parameter set is missing
     */
    public static VvcSliceHeader parse(H264BitReader r, int nalType, VvcPictureHeader ph, VvcPps[] ppsTable,
                                       int[] removed, int removedCount) {
        var s = new VvcSliceHeader();
        s.nalType = nalType;
        s.pictureHeaderInSlice = r.readFlag();
        if (s.pictureHeaderInSlice) {
            ph = VvcPictureHeader.parse(r, ppsTable);
        } else if (ph == null) {
            throw new MediaException.Malformed("vvc slice without a picture header");
        }
        s.ph = ph;
        var pps = ppsTable[ph.ppsId];
        if (pps == null) {
            throw new MediaException.Malformed("vvc slice refers to a missing picture parameter set");
        }
        var sps = pps.sps;
        s.pps = pps;
        s.sps = sps;
        if (sps.subpicInfoPresent) {
            s.subpicId = r.readBits(sps.subpicIdLen);
        }
        s.subpicIdx = pps.subpicIndex(sps.subpicInfoPresent ? s.subpicId : pps.subpicId[0]);
        var numTiles = pps.numTiles();
        if (!pps.rectSlice) {
            if (numTiles > 1) {
                s.sliceAddress = VvcSps.check(r.readBits(VvcSps.ceilLog2(numTiles)), 0, numTiles - 1,
                        "sh_slice_address");
            }
        } else {
            var inSubpic = pps.subpicNumSlices[s.subpicIdx];
            if (inSubpic > 1) {
                s.sliceAddress = VvcSps.check(r.readBits(VvcSps.ceilLog2(inSubpic)), 0, inSubpic - 1,
                        "sh_slice_address");
            }
        }
        for (var present : sps.extraShBits) {
            if (present) {
                r.readFlag();
            }
        }
        var tilesInSlice = 1;
        if (!pps.rectSlice && numTiles - s.sliceAddress > 1) {
            tilesInSlice = r.readUe(numTiles - 1, "sh_num_tiles_in_slice_minus1") + 1;
        }
        if (!pps.rectSlice) {
            if (s.sliceAddress + tilesInSlice > numTiles) {
                throw new MediaException.Malformed("vvc slice tiles exceed the picture");
            }
            var count = 0;
            for (var t = s.sliceAddress; t < s.sliceAddress + tilesInSlice; t++) {
                var tx = t % pps.numTileColumns;
                var ty = t / pps.numTileColumns;
                count += (pps.tileColBd[tx + 1] - pps.tileColBd[tx]) * (pps.tileRowBd[ty + 1] - pps.tileRowBd[ty]);
            }
            s.ctus = new int[count];
            var n = 0;
            for (var t = s.sliceAddress; t < s.sliceAddress + tilesInSlice; t++) {
                var tx = t % pps.numTileColumns;
                var ty = t / pps.numTileColumns;
                for (var y = pps.tileRowBd[ty]; y < pps.tileRowBd[ty + 1]; y++) {
                    for (var x = pps.tileColBd[tx]; x < pps.tileColBd[tx + 1]; x++) {
                        s.ctus[n++] = y * pps.widthInCtus + x;
                    }
                }
            }
            s.sliceIdx = s.sliceAddress;
        } else {
            var idx = s.sliceAddress;
            for (var i = 0; i < s.subpicIdx; i++) {
                idx += pps.subpicNumSlices[i];
            }
            if (idx >= pps.sliceCtus.length) {
                throw new MediaException.Malformed("vvc slice index out of range");
            }
            s.sliceIdx = idx;
            s.ctus = pps.sliceCtus[idx];
        }
        s.sliceType = ph.interAllowed ? r.readUe(2, "sh_slice_type") : I;
        if (!ph.intraAllowed && s.sliceType == I) {
            throw new MediaException.Malformed("vvc intra slice in a picture that disallows them");
        }
        if (!ph.interAllowed && s.sliceType != I) {
            throw new MediaException.Malformed("vvc inter slice in a picture that disallows them");
        }
        if (nalType == VvcNal.IDR_W_RADL || nalType == VvcNal.IDR_N_LP || nalType == VvcNal.CRA
                || nalType == VvcNal.GDR) {
            s.noOutputOfPriorPics = r.readFlag();
        }
        s.inherit(ph, pps);
        if (sps.alf && !pps.alfInfoInPh) {
            s.alfEnabled = r.readFlag();
            s.alfCb = false;
            s.alfCr = false;
            s.ccAlfCb = false;
            s.ccAlfCr = false;
            if (s.alfEnabled) {
                s.numAlfApsLuma = r.readBits(3);
                for (var i = 0; i < s.numAlfApsLuma; i++) {
                    s.alfApsLuma[i] = r.readBits(3);
                }
                if (sps.chromaFormatIdc != 0) {
                    s.alfCb = r.readFlag();
                    s.alfCr = r.readFlag();
                }
                if (s.alfCb || s.alfCr) {
                    s.alfApsChroma = r.readBits(3);
                }
                if (sps.ccalf) {
                    s.ccAlfCb = r.readFlag();
                    if (s.ccAlfCb) {
                        s.ccAlfCbAps = r.readBits(3);
                    }
                    s.ccAlfCr = r.readFlag();
                    if (s.ccAlfCr) {
                        s.ccAlfCrAps = r.readBits(3);
                    }
                }
            }
        }
        if (ph.lmcsEnabled && !s.pictureHeaderInSlice) {
            s.lmcsUsed = r.readFlag();
        }
        if (ph.explicitScalingList && !s.pictureHeaderInSlice) {
            s.scalingListUsed = r.readFlag();
        }
        if (pps.rplInfoInPh) {
            ph.rpl[0].copyInto(s.rpl[0]);
            ph.rpl[1].copyInto(s.rpl[1]);
            s.rplIdx[0] = ph.rplIdx[0];
            s.rplIdx[1] = ph.rplIdx[1];
        } else if ((nalType == VvcNal.IDR_W_RADL || nalType == VvcNal.IDR_N_LP) && !sps.idrRplPresent) {
            s.rpl[0].numEntries = 0;
            s.rpl[1].numEntries = 0;
        } else {
            VvcRefPicList.parseHeaderLists(r, sps, pps, s.rpl, s.rplIdx);
        }
        var override = true;
        var minus1 = new int[2];
        if ((s.sliceType != I && s.rpl[0].numEntries > 1) || (s.sliceType == B && s.rpl[1].numEntries > 1)) {
            override = r.readFlag();
            if (override) {
                for (var i = 0; i < (s.sliceType == B ? 2 : 1); i++) {
                    if (s.rpl[i].numEntries > 1) {
                        minus1[i] = r.readUe(14, "sh_num_ref_idx_active_minus1");
                    }
                }
            }
        }
        for (var i = 0; i < 2; i++) {
            if (s.sliceType == B || (s.sliceType == P && i == 0)) {
                if (override) {
                    s.numRefIdxActive[i] = minus1[i] + 1;
                } else {
                    s.numRefIdxActive[i] = Math.min(pps.numRefIdxDefault[i], s.rpl[i].numEntries);
                }
                if (s.numRefIdxActive[i] > s.rpl[i].numEntries) {
                    throw new MediaException.Malformed("vvc more active references than list entries");
                }
            } else {
                s.numRefIdxActive[i] = 0;
            }
        }
        if (s.sliceType != I) {
            if (pps.cabacInitPresent) {
                s.cabacInit = r.readFlag();
            }
            if (ph.temporalMvp && !pps.rplInfoInPh) {
                if (s.sliceType == B) {
                    s.colFromL0 = r.readFlag();
                }
                if ((s.colFromL0 && s.numRefIdxActive[0] > 1) || (!s.colFromL0 && s.numRefIdxActive[1] > 1)) {
                    s.colRefIdx = r.readUe(s.numRefIdxActive[s.colFromL0 ? 0 : 1] - 1, "sh_collocated_ref_idx");
                }
            }
            if (!pps.wpInfoInPh && ((pps.weightedPred && s.sliceType == P)
                    || (pps.weightedBipred && s.sliceType == B))) {
                s.weights = VvcPredWeights.parse(r, sps, pps, s.rpl, s.numRefIdxActive);
            } else if (pps.wpInfoInPh) {
                s.weights = ph.weights;
            }
        }
        if (!pps.qpDeltaInfoInPh) {
            s.sliceQp = 26 + (pps.initQp - 26) + r.readSe(-63 - 26 - 37, 63 + 37, "sh_qp_delta");
        }
        if (s.sliceQp < -sps.qpBdOffset || s.sliceQp > 63) {
            throw new MediaException.Malformed("vvc SliceQpY out of range: " + s.sliceQp);
        }
        if (pps.sliceChromaQpOffsets) {
            s.cbQpOffset = r.readSe(-12, 12, "sh_cb_qp_offset");
            s.crQpOffset = r.readSe(-12, 12, "sh_cr_qp_offset");
            if (sps.jointCbCr) {
                s.jointCbCrQpOffset = r.readSe(-12, 12, "sh_joint_cbcr_qp_offset");
            }
        }
        if (pps.cuChromaQpOffsetList) {
            s.cuChromaQpOffsetEnabled = r.readFlag();
        }
        if (sps.sao && !pps.saoInfoInPh) {
            s.saoLuma = r.readFlag();
            s.saoChroma = sps.chromaFormatIdc != 0 && r.readFlag();
        }
        var paramsPresent = pps.deblockingOverrideEnabled && !pps.dbfInfoInPh && r.readFlag();
        s.deblockingDisabled = (pps.deblockingDisabled && paramsPresent) ? false : ph.deblockingDisabled;
        if (paramsPresent) {
            if (!pps.deblockingDisabled) {
                s.deblockingDisabled = r.readFlag();
            }
            if (!s.deblockingDisabled) {
                s.betaOffset = r.readSe(-12, 12, "sh_luma_beta_offset_div2");
                s.tcOffset = r.readSe(-12, 12, "sh_luma_tc_offset_div2");
            }
        }
        if (paramsPresent && !s.deblockingDisabled && pps.chromaToolOffsets) {
            s.cbBetaOffset = r.readSe(-12, 12, "sh_cb_beta_offset_div2");
            s.cbTcOffset = r.readSe(-12, 12, "sh_cb_tc_offset_div2");
            s.crBetaOffset = r.readSe(-12, 12, "sh_cr_beta_offset_div2");
            s.crTcOffset = r.readSe(-12, 12, "sh_cr_tc_offset_div2");
        } else if (pps.chromaToolOffsets) {
            s.cbBetaOffset = ph.cbBetaOffset;
            s.cbTcOffset = ph.cbTcOffset;
            s.crBetaOffset = ph.crBetaOffset;
            s.crTcOffset = ph.crTcOffset;
        } else {
            s.cbBetaOffset = s.betaOffset;
            s.cbTcOffset = s.tcOffset;
            s.crBetaOffset = s.betaOffset;
            s.crTcOffset = s.tcOffset;
        }
        if (sps.depQuant) {
            s.depQuant = r.readFlag();
        }
        if (sps.signDataHiding && !s.depQuant) {
            s.signDataHiding = r.readFlag();
        }
        if (sps.transformSkip && !s.depQuant && !s.signDataHiding) {
            s.tsResidualCodingDisabled = r.readFlag();
        }
        if (sps.tsResidualCodingRiceInSh) {
            throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE, "vvc range extension",
                    "VVC range extension coding tools are not supported");
        }
        if (pps.sliceHeaderExtension) {
            var length = r.readUe(256, "sh_slice_header_extension_length");
            r.skip(8L * length);
        }
        var numEntryPoints = s.countEntryPoints();
        long[] offsets = null;
        if (numEntryPoints > 0) {
            var bits = r.readUe(31, "sh_entry_offset_len_minus1") + 1;
            offsets = new long[numEntryPoints];
            for (var i = 0; i < numEntryPoints; i++) {
                offsets[i] = r.readLong(bits) + 1;
            }
        }
        if (r.readBit() != 1) {
            throw new MediaException.Malformed("vvc slice header byte_alignment( ) without its one bit");
        }
        r.alignToByte();
        if (r.overrun()) {
            throw new MediaException.Malformed("truncated vvc slice header");
        }
        s.dataOffset = (int) (r.bitPosition() >> 3);
        if (offsets != null) {
            var headerEnd = s.dataOffset;
            for (var i = 0; i < removedCount; i++) {
                if (removed[i] < headerEnd) {
                    headerEnd++;
                }
            }
            s.entryPoints = new int[numEntryPoints];
            long prev = 0;
            long unescapedStart = 0;
            for (var i = 0; i < numEntryPoints; i++) {
                var curr = prev + offsets[i];
                var inRange = 0;
                for (var k = 0; k < removedCount; k++) {
                    if (removed[k] >= prev + headerEnd && removed[k] < curr + headerEnd) {
                        inRange++;
                    }
                }
                unescapedStart += offsets[i] - inRange;
                if (unescapedStart > Integer.MAX_VALUE) {
                    throw new MediaException.Malformed("vvc entry point out of range");
                }
                s.entryPoints[i] = (int) unescapedStart;
                prev = curr;
            }
        }
        return s;
    }

    /**
     * Copies the values the slice inherits from its picture header.
     *
     * @param ph the picture header
     * @param pps the picture parameter set
     */
    private void inherit(VvcPictureHeader ph, VvcPps pps) {
        colFromL0 = sliceType != B || ph.colFromL0;
        colRefIdx = pps.rplInfoInPh ? ph.colRefIdx : 0;
        if (pps.qpDeltaInfoInPh) {
            sliceQp = pps.initQp + ph.qpDelta;
        }
        deblockingDisabled = ph.deblockingDisabled;
        betaOffset = ph.betaOffset;
        tcOffset = ph.tcOffset;
        saoLuma = ph.saoLuma;
        saoChroma = ph.saoChroma;
        alfEnabled = ph.alfEnabled;
        alfCb = ph.alfCb;
        alfCr = ph.alfCr;
        numAlfApsLuma = ph.numAlfApsLuma;
        System.arraycopy(ph.alfApsLuma, 0, alfApsLuma, 0, 8);
        alfApsChroma = ph.alfApsChroma;
        ccAlfCb = ph.ccAlfCb;
        ccAlfCr = ph.ccAlfCr;
        ccAlfCbAps = ph.ccAlfCbAps;
        ccAlfCrAps = ph.ccAlfCrAps;
        lmcsUsed = pictureHeaderInSlice && ph.lmcsEnabled;
        scalingListUsed = pictureHeaderInSlice && ph.explicitScalingList;
    }

    /**
     * Counts the entry points of the slice: the CTUs that start a tile or, with wavefront parallel processing, a
     * CTU row.
     *
     * @return the number of entry points
     */
    private int countEntryPoints() {
        if (!sps.entryPointsPresent) {
            return 0;
        }
        var count = 0;
        var w = pps.widthInCtus;
        var prevX = ctus[0] % w;
        var prevY = ctus[0] / w;
        for (var i = 1; i < ctus.length; i++) {
            var x = ctus[i] % w;
            var y = ctus[i] / w;
            if (pps.ctuToTileRow[y] != pps.ctuToTileRow[prevY] || pps.ctuToTileCol[x] != pps.ctuToTileCol[prevX]
                    || (y != prevY && sps.entropyCodingSync)) {
                count++;
            }
            prevX = x;
            prevY = y;
        }
        return count;
    }
}
