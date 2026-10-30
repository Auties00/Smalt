/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed picture header ({@code picture_header_structure( )}, ITU-T H.266 7.3.2.8).
 */
public final class VvcPictureHeader {
    /**
     * Constructs an empty header, which {@link #parse} fills.
     */
    public VvcPictureHeader() {
    }

    /**
     * {@code ph_gdr_or_irap_pic_flag}.
     */
    public boolean gdrOrIrap;
    /**
     * {@code ph_non_ref_pic_flag}.
     */
    public boolean nonRef;
    /**
     * {@code ph_gdr_pic_flag}.
     */
    public boolean gdr;
    /**
     * {@code ph_inter_slice_allowed_flag}.
     */
    public boolean interAllowed;
    /**
     * {@code ph_intra_slice_allowed_flag}.
     */
    public boolean intraAllowed = true;
    /**
     * {@code ph_pic_parameter_set_id}.
     */
    public int ppsId;
    /**
     * {@code ph_pic_order_cnt_lsb}.
     */
    public int pocLsb;
    /**
     * {@code ph_recovery_poc_cnt}, or -1 for a picture that is not a GDR picture.
     */
    public int recoveryPocCnt = -1;
    /**
     * {@code ph_poc_msb_cycle_present_flag}.
     */
    public boolean pocMsbPresent;
    /**
     * {@code ph_poc_msb_cycle_val}.
     */
    public int pocMsbVal;
    /**
     * {@code ph_alf_enabled_flag}.
     */
    public boolean alfEnabled;
    /**
     * {@code ph_num_alf_aps_ids_luma}.
     */
    public int numAlfApsLuma;
    /**
     * {@code ph_alf_aps_id_luma} of each luma filter set.
     */
    public final int[] alfApsLuma = new int[8];
    /**
     * {@code ph_alf_cb_enabled_flag}.
     */
    public boolean alfCb;
    /**
     * {@code ph_alf_cr_enabled_flag}.
     */
    public boolean alfCr;
    /**
     * {@code ph_alf_aps_id_chroma}.
     */
    public int alfApsChroma;
    /**
     * {@code ph_alf_cc_cb_enabled_flag}.
     */
    public boolean ccAlfCb;
    /**
     * {@code ph_alf_cc_cb_aps_id}.
     */
    public int ccAlfCbAps;
    /**
     * {@code ph_alf_cc_cr_enabled_flag}.
     */
    public boolean ccAlfCr;
    /**
     * {@code ph_alf_cc_cr_aps_id}.
     */
    public int ccAlfCrAps;
    /**
     * {@code ph_lmcs_enabled_flag}.
     */
    public boolean lmcsEnabled;
    /**
     * {@code ph_lmcs_aps_id}.
     */
    public int lmcsAps;
    /**
     * {@code ph_chroma_residual_scale_flag}.
     */
    public boolean chromaResidualScale;
    /**
     * {@code ph_explicit_scaling_list_enabled_flag}.
     */
    public boolean explicitScalingList;
    /**
     * {@code ph_scaling_list_aps_id}.
     */
    public int scalingListAps;
    /**
     * Whether virtual boundaries apply to the picture: the sequence parameter set's, or {@code
     * ph_virtual_boundaries_present_flag}.
     */
    public boolean virtualBoundariesPresent;
    /**
     * The number of vertical virtual boundaries, from the sequence parameter set or the header.
     */
    public int numVerVirtualBoundaries;
    /**
     * The number of horizontal virtual boundaries, from the sequence parameter set or the header.
     */
    public int numHorVirtualBoundaries;
    /**
     * The vertical virtual boundary positions, in luma samples.
     */
    public final int[] virtualBoundaryX = new int[3];
    /**
     * The horizontal virtual boundary positions, in luma samples.
     */
    public final int[] virtualBoundaryY = new int[3];
    /**
     * {@code ph_pic_output_flag}, {@code true} when absent.
     */
    public boolean picOutput = true;
    /**
     * The reference picture lists the header carries ({@code pps_rpl_info_in_ph_flag}).
     */
    public final VvcRefPicList[] rpl = {new VvcRefPicList(), new VvcRefPicList()};
    /**
     * {@code rpl_idx} of each list, or -1 when the list is coded in the header.
     */
    public final int[] rplIdx = {-1, -1};
    /**
     * The minimum quadtree leaf size of each slice kind, after the override.
     */
    public final int[] minQtSize = new int[3];
    /**
     * The maximum multi-type tree depth of each slice kind, after the override.
     */
    public final int[] maxMttDepth = new int[3];
    /**
     * The maximum binary tree root size of each slice kind, after the override.
     */
    public final int[] maxBtSize = new int[3];
    /**
     * The maximum ternary tree root size of each slice kind, after the override.
     */
    public final int[] maxTtSize = new int[3];
    /**
     * {@code ph_cu_qp_delta_subdiv_intra_slice}.
     */
    public int cuQpDeltaSubdivIntra;
    /**
     * {@code ph_cu_qp_delta_subdiv_inter_slice}.
     */
    public int cuQpDeltaSubdivInter;
    /**
     * {@code ph_cu_chroma_qp_offset_subdiv_intra_slice}.
     */
    public int cuChromaQpOffsetSubdivIntra;
    /**
     * {@code ph_cu_chroma_qp_offset_subdiv_inter_slice}.
     */
    public int cuChromaQpOffsetSubdivInter;
    /**
     * {@code ph_temporal_mvp_enabled_flag}.
     */
    public boolean temporalMvp;
    /**
     * {@code ph_collocated_from_l0_flag}.
     */
    public boolean colFromL0 = true;
    /**
     * {@code ph_collocated_ref_idx}.
     */
    public int colRefIdx;
    /**
     * {@code ph_mmvd_fullpel_only_flag}.
     */
    public boolean mmvdFullpel;
    /**
     * {@code ph_mvd_l1_zero_flag}.
     */
    public boolean mvdL1Zero;
    /**
     * {@code ph_bdof_disabled_flag}, or its inferred value.
     */
    public boolean bdofDisabled = true;
    /**
     * {@code ph_dmvr_disabled_flag}, or its inferred value.
     */
    public boolean dmvrDisabled = true;
    /**
     * {@code ph_prof_disabled_flag}, or its inferred value.
     */
    public boolean profDisabled = true;
    /**
     * The weighted prediction tables the header carries, or {@code null}.
     */
    public VvcPredWeights weights;
    /**
     * {@code ph_qp_delta}.
     */
    public int qpDelta;
    /**
     * {@code ph_joint_cbcr_sign_flag}.
     */
    public boolean jointCbCrSign;
    /**
     * {@code ph_sao_luma_enabled_flag}.
     */
    public boolean saoLuma;
    /**
     * {@code ph_sao_chroma_enabled_flag}.
     */
    public boolean saoChroma;
    /**
     * {@code ph_deblocking_params_present_flag}.
     */
    public boolean deblockingParamsPresent;
    /**
     * {@code ph_deblocking_filter_disabled_flag}, or the picture parameter set's.
     */
    public boolean deblockingDisabled;
    /**
     * {@code ph_luma_beta_offset_div2}, or the picture parameter set's.
     */
    public int betaOffset;
    /**
     * {@code ph_luma_tc_offset_div2}, or the picture parameter set's.
     */
    public int tcOffset;
    /**
     * {@code ph_cb_beta_offset_div2}, or the picture parameter set's.
     */
    public int cbBetaOffset;
    /**
     * {@code ph_cb_tc_offset_div2}, or the picture parameter set's.
     */
    public int cbTcOffset;
    /**
     * {@code ph_cr_beta_offset_div2}, or the picture parameter set's.
     */
    public int crBetaOffset;
    /**
     * {@code ph_cr_tc_offset_div2}, or the picture parameter set's.
     */
    public int crTcOffset;

    /**
     * Parses a picture header structure.
     *
     * @param r the reader
     * @param ppsTable the picture parameter sets received so far, indexed by id
     * @return the header
     * @throws MediaException.Malformed if a syntax element is out of range or a parameter set is missing
     */
    public static VvcPictureHeader parse(H264BitReader r, VvcPps[] ppsTable) {
        var h = new VvcPictureHeader();
        h.gdrOrIrap = r.readFlag();
        h.nonRef = r.readFlag();
        if (h.gdrOrIrap) {
            h.gdr = r.readFlag();
        }
        h.interAllowed = r.readFlag();
        if (h.interAllowed) {
            h.intraAllowed = r.readFlag();
        }
        h.ppsId = r.readUe(63, "ph_pic_parameter_set_id");
        var pps = ppsTable[h.ppsId];
        if (pps == null) {
            throw new MediaException.Malformed("vvc picture header refers to a missing picture parameter set");
        }
        var sps = pps.sps;
        var ctbLog2 = sps.log2CtuSize;
        var minCbLog2 = sps.log2MinCbSize;
        h.pocLsb = r.readBits(sps.log2MaxPocLsb);
        if (h.gdr) {
            h.recoveryPocCnt = r.readUe(1 << sps.log2MaxPocLsb, "ph_recovery_poc_cnt");
        }
        for (var present : sps.extraPhBits) {
            if (present) {
                r.readFlag();
            }
        }
        if (sps.pocMsbCycle) {
            h.pocMsbPresent = r.readFlag();
            if (h.pocMsbPresent) {
                h.pocMsbVal = r.readBits(sps.pocMsbCycleLen);
            }
        }
        if (sps.alf && pps.alfInfoInPh) {
            h.alfEnabled = r.readFlag();
            if (h.alfEnabled) {
                h.numAlfApsLuma = r.readBits(3);
                for (var i = 0; i < h.numAlfApsLuma; i++) {
                    h.alfApsLuma[i] = r.readBits(3);
                }
                if (sps.chromaFormatIdc != 0) {
                    h.alfCb = r.readFlag();
                    h.alfCr = r.readFlag();
                }
                if (h.alfCb || h.alfCr) {
                    h.alfApsChroma = r.readBits(3);
                }
                if (sps.ccalf) {
                    h.ccAlfCb = r.readFlag();
                    if (h.ccAlfCb) {
                        h.ccAlfCbAps = r.readBits(3);
                    }
                    h.ccAlfCr = r.readFlag();
                    if (h.ccAlfCr) {
                        h.ccAlfCrAps = r.readBits(3);
                    }
                }
            }
        }
        if (sps.lmcs) {
            h.lmcsEnabled = r.readFlag();
            if (h.lmcsEnabled) {
                h.lmcsAps = r.readBits(2);
                if (sps.chromaFormatIdc != 0) {
                    h.chromaResidualScale = r.readFlag();
                }
            }
        }
        if (sps.explicitScalingList) {
            h.explicitScalingList = r.readFlag();
            if (h.explicitScalingList) {
                h.scalingListAps = r.readBits(3);
            }
        }
        if (sps.virtualBoundariesEnabled && !sps.virtualBoundariesPresent) {
            h.virtualBoundariesPresent = r.readFlag();
            if (h.virtualBoundariesPresent) {
                h.numVerVirtualBoundaries = r.readUe(pps.width <= 8 ? 0 : 3, "ph_num_ver_virtual_boundaries");
                for (var i = 0; i < h.numVerVirtualBoundaries; i++) {
                    h.virtualBoundaryX[i] = (r.readUe((pps.width + 7) / 8 - 2, "ph_virtual_boundary_pos_x_minus1")
                            + 1) << 3;
                }
                h.numHorVirtualBoundaries = r.readUe(pps.height <= 8 ? 0 : 3, "ph_num_hor_virtual_boundaries");
                for (var i = 0; i < h.numHorVirtualBoundaries; i++) {
                    h.virtualBoundaryY[i] = (r.readUe((pps.height + 7) / 8 - 2, "ph_virtual_boundary_pos_y_minus1")
                            + 1) << 3;
                }
            }
        } else if (sps.virtualBoundariesPresent) {
            h.virtualBoundariesPresent = true;
            h.numVerVirtualBoundaries = sps.numVerVirtualBoundaries;
            h.numHorVirtualBoundaries = sps.numHorVirtualBoundaries;
            System.arraycopy(sps.virtualBoundaryX, 0, h.virtualBoundaryX, 0, 3);
            System.arraycopy(sps.virtualBoundaryY, 0, h.virtualBoundaryY, 0, 3);
        }
        if (pps.outputFlagPresent && !h.nonRef) {
            h.picOutput = r.readFlag();
        }
        if (pps.rplInfoInPh) {
            VvcRefPicList.parseHeaderLists(r, sps, pps, h.rpl, h.rplIdx);
        }
        var override = sps.partitionConstraintsOverride && r.readFlag();
        System.arraycopy(sps.minQtSize, 0, h.minQtSize, 0, 3);
        System.arraycopy(sps.maxMttDepth, 0, h.maxMttDepth, 0, 3);
        System.arraycopy(sps.maxBtSize, 0, h.maxBtSize, 0, 3);
        System.arraycopy(sps.maxTtSize, 0, h.maxTtSize, 0, 3);
        if (h.intraAllowed) {
            if (override) {
                var minQtY = r.readUe(Math.min(6, ctbLog2) - minCbLog2, "ph_log2_diff_min_qt_min_cb_intra_slice_luma")
                        + minCbLog2;
                h.minQtSize[0] = 1 << minQtY;
                h.maxMttDepth[0] = r.readUe(2 * (ctbLog2 - minCbLog2), "ph_max_mtt_hierarchy_depth_intra_slice_luma");
                h.maxBtSize[0] = h.minQtSize[0];
                h.maxTtSize[0] = h.minQtSize[0];
                if (h.maxMttDepth[0] != 0) {
                    h.maxBtSize[0] <<= r.readUe((sps.dualTree ? Math.min(6, ctbLog2) : ctbLog2) - minQtY,
                            "ph_log2_diff_max_bt_min_qt_intra_slice_luma");
                    h.maxTtSize[0] <<= r.readUe(Math.min(6, ctbLog2) - minQtY,
                            "ph_log2_diff_max_tt_min_qt_intra_slice_luma");
                }
                if (sps.dualTree) {
                    var minQtC = r.readUe(Math.min(6, ctbLog2) - minCbLog2,
                            "ph_log2_diff_min_qt_min_cb_intra_slice_chroma") + minCbLog2;
                    h.minQtSize[2] = 1 << minQtC;
                    h.maxMttDepth[2] = r.readUe(2 * (ctbLog2 - minCbLog2),
                            "ph_max_mtt_hierarchy_depth_intra_slice_chroma");
                    h.maxBtSize[2] = h.minQtSize[2];
                    h.maxTtSize[2] = h.minQtSize[2];
                    if (h.maxMttDepth[2] != 0) {
                        h.maxBtSize[2] <<= r.readUe(Math.min(6, ctbLog2) - minQtC,
                                "ph_log2_diff_max_bt_min_qt_intra_slice_chroma");
                        h.maxTtSize[2] <<= r.readUe(Math.min(6, ctbLog2) - minQtC,
                                "ph_log2_diff_max_tt_min_qt_intra_slice_chroma");
                    }
                }
            }
            var minQtLog2 = Integer.numberOfTrailingZeros(h.minQtSize[0]);
            if (pps.cuQpDelta) {
                h.cuQpDeltaSubdivIntra = r.readUe(2 * (ctbLog2 - minQtLog2 + h.maxMttDepth[0]),
                        "ph_cu_qp_delta_subdiv_intra_slice");
            }
            if (pps.cuChromaQpOffsetList) {
                h.cuChromaQpOffsetSubdivIntra = r.readUe(2 * (ctbLog2 - minQtLog2 + h.maxMttDepth[0]),
                        "ph_cu_chroma_qp_offset_subdiv_intra_slice");
            }
        }
        if (h.interAllowed) {
            if (override) {
                var minQt = r.readUe(Math.min(6, ctbLog2) - minCbLog2, "ph_log2_diff_min_qt_min_cb_inter_slice")
                        + minCbLog2;
                h.minQtSize[1] = 1 << minQt;
                h.maxMttDepth[1] = r.readUe(2 * (ctbLog2 - minCbLog2), "ph_max_mtt_hierarchy_depth_inter_slice");
                h.maxBtSize[1] = h.minQtSize[1];
                h.maxTtSize[1] = h.minQtSize[1];
                if (h.maxMttDepth[1] != 0) {
                    h.maxBtSize[1] <<= r.readUe(ctbLog2 - minQt, "ph_log2_diff_max_bt_min_qt_inter_slice");
                    h.maxTtSize[1] <<= r.readUe(Math.min(6, ctbLog2) - minQt, "ph_log2_diff_max_tt_min_qt_inter_slice");
                }
            }
            var minQtLog2 = override ? Integer.numberOfTrailingZeros(h.minQtSize[1]) : minCbLog2;
            if (pps.cuQpDelta) {
                h.cuQpDeltaSubdivInter = r.readUe(2 * (ctbLog2 - minQtLog2 + h.maxMttDepth[1]),
                        "ph_cu_qp_delta_subdiv_inter_slice");
            }
            if (pps.cuChromaQpOffsetList) {
                h.cuChromaQpOffsetSubdivInter = r.readUe(2 * (ctbLog2 - minQtLog2 + h.maxMttDepth[1]),
                        "ph_cu_chroma_qp_offset_subdiv_inter_slice");
            }
            if (sps.temporalMvp) {
                h.temporalMvp = r.readFlag();
                if (h.temporalMvp && pps.rplInfoInPh) {
                    h.colFromL0 = h.rpl[1].numEntries <= 0 || r.readFlag();
                    if ((h.colFromL0 && h.rpl[0].numEntries > 1) || (!h.colFromL0 && h.rpl[1].numEntries > 1)) {
                        h.colRefIdx = r.readUe(h.rpl[h.colFromL0 ? 0 : 1].numEntries - 1, "ph_collocated_ref_idx");
                    }
                }
            }
            if (sps.mmvdFullpelOnly) {
                h.mmvdFullpel = r.readFlag();
            }
            var presence = !pps.rplInfoInPh || h.rpl[1].numEntries > 0;
            h.bdofDisabled = sps.bdofControlInPh || !sps.bdof;
            h.dmvrDisabled = sps.dmvrControlInPh || !sps.dmvr;
            if (presence) {
                h.mvdL1Zero = r.readFlag();
                if (sps.bdofControlInPh) {
                    h.bdofDisabled = r.readFlag();
                }
                if (sps.dmvrControlInPh) {
                    h.dmvrDisabled = r.readFlag();
                }
            }
            h.profDisabled = sps.profControlInPh ? r.readFlag() : !sps.prof;
            if ((pps.weightedPred || pps.weightedBipred) && pps.wpInfoInPh) {
                h.weights = VvcPredWeights.parse(r, sps, pps, h.rpl, null);
            }
        }
        if (pps.qpDeltaInfoInPh) {
            h.qpDelta = r.readSe(-(26 + sps.qpBdOffset) - 37, 63 + 37, "ph_qp_delta");
        }
        if (sps.jointCbCr) {
            h.jointCbCrSign = r.readFlag();
        }
        if (sps.sao && pps.saoInfoInPh) {
            h.saoLuma = r.readFlag();
            if (sps.chromaFormatIdc != 0) {
                h.saoChroma = r.readFlag();
            }
        }
        if (pps.dbfInfoInPh) {
            h.deblockingParamsPresent = r.readFlag();
        }
        h.deblockingDisabled = !(pps.deblockingDisabled && h.deblockingParamsPresent) && pps.deblockingDisabled;
        h.betaOffset = pps.betaOffset;
        h.tcOffset = pps.tcOffset;
        if (h.deblockingParamsPresent) {
            if (!pps.deblockingDisabled) {
                h.deblockingDisabled = r.readFlag();
            }
            if (!h.deblockingDisabled) {
                h.betaOffset = r.readSe(-12, 12, "ph_luma_beta_offset_div2");
                h.tcOffset = r.readSe(-12, 12, "ph_luma_tc_offset_div2");
            }
        }
        if (h.deblockingParamsPresent && !h.deblockingDisabled && pps.chromaToolOffsets) {
            h.cbBetaOffset = r.readSe(-12, 12, "ph_cb_beta_offset_div2");
            h.cbTcOffset = r.readSe(-12, 12, "ph_cb_tc_offset_div2");
            h.crBetaOffset = r.readSe(-12, 12, "ph_cr_beta_offset_div2");
            h.crTcOffset = r.readSe(-12, 12, "ph_cr_tc_offset_div2");
        } else {
            h.cbBetaOffset = pps.chromaToolOffsets ? pps.cbBetaOffset : h.betaOffset;
            h.cbTcOffset = pps.chromaToolOffsets ? pps.cbTcOffset : h.tcOffset;
            h.crBetaOffset = pps.chromaToolOffsets ? pps.crBetaOffset : h.betaOffset;
            h.crTcOffset = pps.chromaToolOffsets ? pps.crTcOffset : h.tcOffset;
        }
        if (pps.pictureHeaderExtension) {
            var length = r.readUe(256, "ph_extension_length");
            r.skip(8L * length);
        }
        return h;
    }
}
