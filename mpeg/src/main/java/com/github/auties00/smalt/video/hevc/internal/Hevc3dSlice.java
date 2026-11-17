package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds the 3D-HEVC part of a slice segment header (ITU-T H.265 I.7.3.6.1) of a layer decoded with
 * the process of Annex I, with the variables derived from it (I.7.4.7.1): the view and component of
 * the layer, the inter-component prediction flag, the tool enabling flags, the illumination
 * compensation flags and the camera parameters that convert depth values to disparities.
 *
 * <p>Instances are immutable once parsed and shared by the dependent slice segments of a slice.
 */
public final class Hevc3dSlice {
    /**
     * {@code DepthFlag}: whether the layer is a depth layer.
     */
    public final boolean depth;

    /**
     * {@code ViewIdx}, the view order index of the layer.
     */
    public final int viewIdx;

    /**
     * {@code in_comp_pred_flag}.
     */
    public boolean inCompPred;

    /**
     * {@code cpAvailableFlag}.
     */
    public boolean cpAvailable;

    /**
     * {@code IvDiMcEnabledFlag}.
     */
    public boolean ivDiMc;

    /**
     * {@code IvMvScalEnabledFlag}.
     */
    public boolean ivMvScal;

    /**
     * {@code IvResPredEnabledFlag}.
     */
    public boolean ivResPred;

    /**
     * {@code VspMcEnabledFlag}.
     */
    public boolean vspMc;

    /**
     * {@code DbbpEnabledFlag}.
     */
    public boolean dbbp;

    /**
     * {@code DepthRefEnabledFlag}.
     */
    public boolean depthRef;

    /**
     * {@code TexMcEnabledFlag}.
     */
    public boolean texMc;

    /**
     * {@code IntraContourEnabledFlag}.
     */
    public boolean intraContour;

    /**
     * {@code IntraDcOnlyWedgeEnabledFlag}.
     */
    public boolean intraDcOnlyWedge;

    /**
     * {@code CqtCuPartPredEnabledFlag}.
     */
    public boolean cqtCuPartPred;

    /**
     * {@code InterDcOnlyEnabledFlag}.
     */
    public boolean interDcOnly;

    /**
     * {@code SkipIntraEnabledFlag}.
     */
    public boolean skipIntra;

    /**
     * {@code DisparityDerivationFlag}.
     */
    public boolean disparityDerivation;

    /**
     * {@code NumExtraMergeCand}.
     */
    public int numExtraMergeCand;

    /**
     * {@code slice_ic_enabled_flag}.
     */
    public boolean icEnabled;

    /**
     * {@code slice_ic_disabled_merge_zero_idx_flag}.
     */
    public boolean icDisabledMergeZeroIdx;

    /**
     * {@code log2( subPbIvMcSize )}, the log2 of the size of the sub-block partitions of the inter-view
     * predicted merging candidate.
     */
    public int log2SubPbSize;

    /**
     * The views the camera parameters relate to, {@code cp_ref_voi[ ViewIdx ]}.
     */
    public int[] cpRefVoi = new int[0];

    /**
     * {@code cp_scale}, {@code cp_off}, {@code cp_inv_scale_plus_scale} and
     * {@code cp_inv_off_plus_off} of each view of {@link #cpRefVoi}, four values per view.
     */
    public int[] cp = new int[0];

    /**
     * {@code DepthToDisparityB} (equation I-40) of each view of {@link #cpRefVoi}, by depth value.
     */
    public int[][] depthToDisparityB = new int[0][];

    /**
     * {@code DepthToDisparityF} (equation I-43) of each view of {@link #cpRefVoi}, by depth value.
     */
    public int[][] depthToDisparityF = new int[0][];

    /**
     * {@code DltVal[ nuh_layer_id ]} of a depth layer whose {@code DltFlag} is 1, with
     * {@code NumValDlt} entries, or {@code null}.
     */
    public int[] dltVal;

    /**
     * {@code NumRefListLayers[ nuh_layer_id ]}.
     */
    public final int numRefListLayers;

    /**
     * Constructs the 3D part of a header.
     *
     * @param depth {@code DepthFlag}
     * @param viewIdx {@code ViewIdx}
     * @param numRefListLayers {@code NumRefListLayers[ nuh_layer_id ]}
     */
    Hevc3dSlice(boolean depth, int viewIdx, int numRefListLayers) {
        this.depth = depth;
        this.viewIdx = viewIdx;
        this.numRefListLayers = numRefListLayers;
    }

    /**
     * Selects the depth look-up table of a depth layer from the picture parameter set: the table
     * of its index among the depth layers in the order of the video parameter set (equation I-13).
     *
     * @param vps the active video parameter set
     * @param pps the active picture parameter set
     * @param layerId the {@code nuh_layer_id} of the slice segment
     */
    void selectLookUpTable(HevcVps vps, HevcPps pps, int layerId) {
        dltVal = null;
        if (!depth || !pps.dltsPresent) {
            return;
        }
        var index = 0;
        for (var lId : vps.layerIdInNuh) {
            if (lId == layerId) {
                if (index < pps.depthLookUpTables.length) {
                    dltVal = pps.depthLookUpTables[index];
                }
                return;
            }
            if (vps.depthLayer[lId]) {
                index++;
            }
        }
    }

    /**
     * Parses {@code in_comp_pred_flag}, when present, and derives the tool enabling flags
     * (equations I-17 to I-31 and I-35).
     *
     * @param reader the reader, positioned at {@code in_comp_pred_flag} or where it would be
     * @param vps the active video parameter set
     * @param sps the active sequence parameter set
     * @param layerId the {@code nuh_layer_id} of the slice segment
     * @param temporalId the temporal identifier of the slice segment
     * @param refPicLayerId {@code RefPicLayerId}
     * @param sliceType the slice type
     * @param nalType the NAL unit type
     */
    void parseTools(H264BitReader reader, HevcVps vps, HevcSps sps, int layerId, int temporalId,
                    int[] refPicLayerId, int sliceType, int nalType) {
        var curCmpLIds = depth ? new int[]{layerId} : refPicLayerId;
        cpAvailable = true;
        var allAvailable = true;
        var layerIdx = vps.layerIndex(layerId);
        for (var cmpLId : curCmpLIds) {
            var refViewIdx = vps.viewOrderIdx[cmpLId];
            if (vps.cpIndex(viewIdx, refViewIdx) < 0) {
                cpAvailable = false;
            }
            var available = false;
            var component = vps.viewCompLayerId(refViewIdx, !depth);
            if (component >= 0) {
                var j = vps.layerIndex(component);
                available = j >= 0 && layerIdx >= 0 && vps.directDependency[layerIdx][j]
                        && vps.subLayersVpsMaxMinus1[j] >= temporalId
                        && (temporalId == 0 || vps.maxTidIlRefPicsPlus1[j][layerIdx] > temporalId);
            }
            if (!available) {
                allAvailable = false;
            }
        }
        var availFlag = allAvailable && (depth
                ? sps.intraContourEnabled || sps.cqtCuPartPredEnabled || sps.texMcEnabled
                : sps.vspMcEnabled || sps.dbbpEnabled || sps.depthRefEnabled);
        inCompPred = availFlag && reader.readFlag();
        var d = depth ? 1 : 0;
        var hasRefLayers = numRefListLayers > 0;
        ivDiMc = hasRefLayers && sps.ivDiMcEnabled[d];
        ivMvScal = sps.ivMvScalEnabled[d] && viewIdx != 0;
        ivResPred = hasRefLayers && !depth && sps.ivResPredEnabled;
        vspMc = hasRefLayers && !depth && sps.vspMcEnabled && inCompPred && cpAvailable;
        dbbp = !depth && sps.dbbpEnabled && inCompPred;
        depthRef = !depth && sps.depthRefEnabled && inCompPred && cpAvailable;
        texMc = depth && sps.texMcEnabled && inCompPred;
        intraContour = depth && sps.intraContourEnabled && inCompPred;
        intraDcOnlyWedge = depth && sps.intraDcOnlyWedgeEnabled;
        cqtCuPartPred = depth && sps.cqtCuPartPredEnabled && inCompPred && sliceType != HevcSliceHeader.I
                && !HevcNal.isIrap(nalType);
        interDcOnly = depth && sps.interDcOnlyEnabled;
        skipIntra = depth && sps.skipIntraEnabled;
        disparityDerivation = ivDiMc || ivResPred || vspMc || dbbp;
        numExtraMergeCand = ivDiMc || texMc || vspMc ? 1 : 0;
        log2SubPbSize = depth ? sps.log2TexmcSubPbSize : sps.log2IvmcSubPbSize;
    }

    /**
     * Parses {@code slice_ic_enabled_flag} and {@code slice_ic_disabled_merge_zero_idx_flag}.
     *
     * @param reader the reader, positioned at the syntax elements
     */
    void parseIlluminationCompensation(H264BitReader reader) {
        icEnabled = reader.readFlag();
        if (icEnabled) {
            icDisabledMergeZeroIdx = reader.readFlag();
        }
    }

    /**
     * Parses the camera parameters of the slice segment header, or takes those of the video
     * parameter set, and derives {@code DepthToDisparityB} and {@code DepthToDisparityF}
     * (equations I-37 to I-43).
     *
     * @param reader the reader, positioned at {@code cp_scale[ j ]} or where it would be
     * @param vps the active video parameter set
     * @param bitDepth {@code BitDepthY}
     * @throws MediaException.Malformed if a parameter is out of range
     */
    void parseCameraParameters(H264BitReader reader, HevcVps vps, int bitDepth) {
        if (viewIdx >= 256) {
            return;
        }
        cpRefVoi = vps.cpRefVoi[viewIdx];
        if (vps.cpInSliceHeader[viewIdx]) {
            cp = new int[4 * cpRefVoi.length];
            for (var m = 0; m < cp.length; m++) {
                cp[m] = reader.readSe(-32768, 32767, "cp_scale");
            }
        } else {
            cp = vps.vpsCp[viewIdx];
        }
        var count = cpRefVoi.length;
        var log2Div = bitDepth - 1 + vps.cpPrecision;
        depthToDisparityB = new int[count][1 << bitDepth];
        depthToDisparityF = new int[count][1 << bitDepth];
        for (var m = 0; m < count; m++) {
            var scale = cp[4 * m];
            var off = cp[4 * m + 1];
            var invScale = cp[4 * m + 2] - scale;
            var invOff = cp[4 * m + 3] - off;
            var offset = ((long) off << bitDepth) + ((1L << log2Div) >> 1);
            var invOffset = ((long) invOff << bitDepth) + ((1L << log2Div) >> 1);
            for (var v = 0; v < 1 << bitDepth; v++) {
                depthToDisparityB[m][v] = (int) (((long) scale * v + offset) >> log2Div);
                depthToDisparityF[m][v] = (int) (((long) invScale * v + invOffset) >> log2Div);
            }
        }
    }

    /**
     * Returns {@code DepthToDisparityB[ refViewIdx ]}.
     *
     * @param refViewIdx the view order index of the other view
     * @return the table by depth value, or {@code null} when the slice has no camera parameters
     * for the view
     */
    public int[] depthToDisparityB(int refViewIdx) {
        for (var m = 0; m < cpRefVoi.length; m++) {
            if (cpRefVoi[m] == refViewIdx) {
                return m < depthToDisparityB.length ? depthToDisparityB[m] : null;
            }
        }
        return null;
    }
}
