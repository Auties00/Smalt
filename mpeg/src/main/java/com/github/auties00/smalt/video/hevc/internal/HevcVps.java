package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.Arrays;

/**
 * Holds a parsed video parameter set (ITU-T H.265 7.3.2.1) with the variables of its multi-layer
 * extension (F.7.3.2.1.1 and F.7.4.3.1.1) that the decoding of the layers above the base layer
 * reads: the layers and their scalability identifiers, the dependencies between them, the layer
 * sets and output layer sets, the representation formats, the sizes of the sub-DPBs and the
 * dependency types.
 *
 * <p>Parsing stops before {@code vps_vui()}, which carries nothing decoding needs. A set without an
 * extension describes the base layer alone: one layer set and one output layer set holding layer 0.
 * Arrays indexed by layer identifier ({@code nuh_layer_id}) have 64 entries; arrays indexed by
 * layer index ({@code LayerIdxInVps}) have {@link #maxLayers} entries.
 */
public final class HevcVps {
    /**
     * The {@code AuxId} of an alpha plane layer ({@code AUX_ALPHA}).
     */
    public static final int AUX_ALPHA = 1;

    /**
     * The {@code AuxId} of a depth picture layer ({@code AUX_DEPTH}).
     */
    public static final int AUX_DEPTH = 2;

    /**
     * {@code vps_video_parameter_set_id}.
     */
    public int id;

    /**
     * The RBSP the set was parsed from, which tells a repeated set from a new one.
     */
    public byte[] raw = new byte[0];

    /**
     * {@code vps_base_layer_internal_flag}.
     */
    public boolean baseLayerInternal = true;

    /**
     * {@code vps_base_layer_available_flag}.
     */
    public boolean baseLayerAvailable = true;

    /**
     * {@code MaxLayersMinus1 + 1}, from 1 to 63.
     */
    public int maxLayers = 1;

    /**
     * {@code vps_max_sub_layers_minus1 + 1}.
     */
    public int maxSubLayers = 1;

    /**
     * Whether the set carries a {@code vps_extension()}.
     */
    public boolean extension;

    /**
     * {@code layer_id_in_nuh}, by layer index.
     */
    public int[] layerIdInNuh = {0};

    /**
     * {@code LayerIdxInVps}, by layer identifier: -1 for an identifier no layer of the set has.
     */
    public final int[] layerIdxInVps = new int[64];

    /**
     * {@code ScalabilityId}, indexed {@code [layerIdx][smIdx]}.
     */
    public int[][] scalabilityId = new int[1][16];

    /**
     * {@code ViewOrderIdx}, by layer identifier.
     */
    public final int[] viewOrderIdx = new int[64];

    /**
     * {@code DependencyId}, by layer identifier.
     */
    public final int[] dependencyId = new int[64];

    /**
     * {@code AuxId}, by layer identifier.
     */
    public final int[] auxId = new int[64];

    /**
     * {@code ViewId}, by layer identifier.
     */
    public final int[] viewId = new int[64];

    /**
     * {@code DepthLayerFlag}, by layer identifier.
     */
    public final boolean[] depthLayer = new boolean[64];

    /**
     * {@code NumViews}.
     */
    public int numViews = 1;

    /**
     * {@code direct_dependency_flag}, indexed {@code [layerIdx][layerIdx]}.
     */
    public boolean[][] directDependency = new boolean[1][1];

    /**
     * {@code DependencyFlag}, the transitive closure of {@link #directDependency}.
     */
    public boolean[][] dependency = new boolean[1][1];

    /**
     * {@code IdDirectRefLayer}, by layer identifier: the identifiers of the direct reference
     * layers in increasing order; the array length is {@code NumDirectRefLayers}.
     */
    public final int[][] idDirectRefLayer = emptyLists();

    /**
     * {@code IdRefLayer}, by layer identifier.
     */
    public final int[][] idRefLayer = emptyLists();

    /**
     * {@code IdPredictedLayer}, by layer identifier.
     */
    public final int[][] idPredictedLayer = emptyLists();

    /**
     * {@code sub_layers_vps_max_minus1}, by layer index.
     */
    public int[] subLayersVpsMaxMinus1 = {0};

    /**
     * {@code max_tid_il_ref_pics_plus1}, indexed {@code [refLayerIdx][layerIdx]}, 7 when absent.
     */
    public int[][] maxTidIlRefPicsPlus1 = {{7}};

    /**
     * {@code default_ref_layers_active_flag}.
     */
    public boolean defaultRefLayersActive;

    /**
     * The {@code general_profile_idc} of each {@code profile_tier_level()} structure, with the
     * profile of a structure without profile information inferred from the one before it.
     */
    public int[] ptlProfileIdc = {0};

    /**
     * {@code LayerSetLayerIdList}, by layer set index; {@code NumLayerSets} entries.
     */
    public int[][] layerSetLayerIdList = {{0}};

    /**
     * {@code vps_num_layer_sets_minus1 + 1}.
     */
    public int numBaseLayerSets = 1;

    /**
     * {@code OlsIdxToLsIdx}, by output layer set index; {@code NumOutputLayerSets} entries.
     */
    public int[] olsIdxToLsIdx = {0};

    /**
     * {@code OutputLayerFlag}, indexed {@code [olsIdx][index into the layer set]}.
     */
    public boolean[][] outputLayerFlag = {{true}};

    /**
     * {@code NecessaryLayerFlag}, indexed like {@link #outputLayerFlag}.
     */
    public boolean[][] necessaryLayerFlag = {{true}};

    /**
     * {@code profile_tier_level_idx}, indexed like {@link #outputLayerFlag}.
     */
    public int[][] ptlIdx = {{0}};

    /**
     * {@code alt_output_layer_flag}, by output layer set index.
     */
    public boolean[] altOutputLayer = {false};

    /**
     * The {@code rep_format()} structures.
     */
    public RepFormat[] repFormats = new RepFormat[0];

    /**
     * {@code vps_rep_format_idx}, by layer index.
     */
    public int[] repFormatIdx = {0};

    /**
     * {@code max_one_active_ref_layer_flag}.
     */
    public boolean maxOneActiveRefLayer;

    /**
     * {@code vps_poc_lsb_aligned_flag}.
     */
    public boolean pocLsbAligned;

    /**
     * {@code poc_lsb_not_present_flag}, by layer index.
     */
    public boolean[] pocLsbNotPresent = {false};

    /**
     * {@code max_vps_dec_pic_buffering_minus1 + 1}, indexed {@code [olsIdx][index into the layer
     * set][sub-layer]}; {@code null} for output layer set 0, which the active SPSs size.
     */
    public int[][][] maxDecPicBuffering = new int[1][][];

    /**
     * {@code max_vps_num_reorder_pics}, indexed {@code [olsIdx][sub-layer]}.
     */
    public int[][] maxNumReorder = new int[1][];

    /**
     * {@code max_vps_latency_increase_plus1}, indexed {@code [olsIdx][sub-layer]}.
     */
    public int[][] maxLatencyIncreasePlus1 = new int[1][];

    /**
     * {@code VpsInterLayerSamplePredictionEnabled}, indexed {@code [layerIdx][refLayerIdx]}.
     */
    public boolean[][] samplePrediction = new boolean[1][1];

    /**
     * {@code VpsInterLayerMotionPredictionEnabled}, indexed {@code [layerIdx][refLayerIdx]}.
     */
    public boolean[][] motionPrediction = new boolean[1][1];

    /**
     * A {@code rep_format()} structure (F.7.3.2.1.2), with the conformance window in luma samples.
     *
     * @param width {@code pic_width_vps_in_luma_samples}
     * @param height {@code pic_height_vps_in_luma_samples}
     * @param chromaFormatIdc {@code chroma_format_vps_idc}
     * @param separateColorPlane {@code separate_colour_plane_vps_flag}
     * @param bitDepthLuma {@code bit_depth_vps_luma_minus8 + 8}
     * @param bitDepthChroma {@code bit_depth_vps_chroma_minus8 + 8}
     * @param cropLeft the left conformance window offset, in luma samples
     * @param cropRight the right conformance window offset, in luma samples
     * @param cropTop the top conformance window offset, in luma samples
     * @param cropBottom the bottom conformance window offset, in luma samples
     */
    public record RepFormat(int width, int height, int chromaFormatIdc, boolean separateColorPlane, int bitDepthLuma,
                            int bitDepthChroma, int cropLeft, int cropRight, int cropTop, int cropBottom) {
    }

    /**
     * Constructs a set describing the base layer alone; {@link #parse(H264BitReader)} fills a
     * parsed one.
     */
    HevcVps() {
        Arrays.fill(layerIdxInVps, -1);
        layerIdxInVps[0] = 0;
    }

    /**
     * Returns a list of 64 empty arrays, one per layer identifier.
     *
     * @return the lists
     */
    private static int[][] emptyLists() {
        var lists = new int[64][];
        Arrays.fill(lists, new int[0]);
        return lists;
    }

    /**
     * {@return the number of output layer sets, {@code NumOutputLayerSets}}
     */
    public int numOutputLayerSets() {
        return olsIdxToLsIdx.length;
    }

    /**
     * Returns the index of a layer in the set, or -1 when the set has no such layer.
     *
     * @param layerId the {@code nuh_layer_id}, from 0 to 63
     * @return {@code LayerIdxInVps[layerId]}, or -1
     */
    public int layerIndex(int layerId) {
        return layerIdxInVps[layerId];
    }

    /**
     * Returns the representation format a layer refers to when its SPS does not name one.
     *
     * @param layerId the {@code nuh_layer_id} of the layer
     * @return the format, or {@code null} when the set carries none
     */
    public RepFormat repFormatOf(int layerId) {
        var index = layerIdxInVps[layerId];
        if (index < 0 || repFormats.length == 0) {
            return null;
        }
        return repFormats[Math.min(repFormatIdx[index], repFormats.length - 1)];
    }

    /**
     * Returns the {@code general_profile_idc} that applies to a layer of an output layer set.
     *
     * @param olsIdx the output layer set index
     * @param layerId the {@code nuh_layer_id} of the layer
     * @return the profile, or 0 when the set does not say
     */
    public int profileOf(int olsIdx, int layerId) {
        var list = layerSetLayerIdList[olsIdxToLsIdx[olsIdx]];
        for (var j = 0; j < list.length; j++) {
            if (list[j] == layerId) {
                var index = ptlIdx[olsIdx][j];
                return index < ptlProfileIdc.length ? ptlProfileIdc[index] : 0;
            }
        }
        return 0;
    }

    /**
     * Parses a video parameter set RBSP that starts after the NAL unit header.
     *
     * @param reader the reader positioned after the NAL unit header
     * @return the video parameter set
     * @throws MediaException.Malformed if a field is out of range or the payload is truncated
     */
    public static HevcVps parse(H264BitReader reader) {
        var vps = new HevcVps();
        vps.raw = Arrays.copyOfRange(reader.data(), reader.bytePosition(), reader.end());
        vps.id = reader.readBits(4);
        vps.baseLayerInternal = reader.readFlag();
        vps.baseLayerAvailable = reader.readFlag();
        var maxLayersMinus1 = reader.readBits(6);
        vps.maxLayers = Math.min(62, maxLayersMinus1) + 1;
        vps.maxSubLayers = reader.readBits(3) + 1;
        if (vps.maxSubLayers > 7) {
            throw new MediaException.Malformed("vps_max_sub_layers_minus1 out of range");
        }
        reader.readBit();
        reader.skipBits(16);
        var ptlProfile = new int[64];
        ptlProfile[0] = HevcSps.parseProfileTierLevel(reader, true, vps.maxSubLayers - 1) >> 8;
        var orderingInfo = reader.readFlag();
        for (var i = orderingInfo ? 0 : vps.maxSubLayers - 1; i < vps.maxSubLayers; i++) {
            reader.readUe();
            reader.readUe();
            reader.readUe();
        }
        var maxLayerId = reader.readBits(6);
        var numLayerSets = reader.readUe(1023, "vps_num_layer_sets_minus1") + 1;
        var included = new boolean[numLayerSets][64];
        for (var i = 1; i < numLayerSets; i++) {
            for (var j = 0; j <= maxLayerId; j++) {
                included[i][j] = reader.readFlag();
            }
        }
        if (reader.readFlag()) {
            reader.readBits(32);
            reader.readBits(32);
            if (reader.readFlag()) {
                reader.readUe();
            }
            var hrdCount = reader.readUe(numLayerSets, "vps_num_hrd_parameters");
            for (var i = 0; i < hrdCount; i++) {
                reader.readUe();
                var common = i == 0 || reader.readFlag();
                HevcSps.skipHrdParameters(reader, common, vps.maxSubLayers - 1);
            }
        }
        vps.numBaseLayerSets = numLayerSets;
        var baseSets = new int[numLayerSets][];
        baseSets[0] = new int[]{0};
        for (var i = 1; i < numLayerSets; i++) {
            var count = 0;
            for (var m = 0; m <= maxLayerId; m++) {
                if (included[i][m]) {
                    count++;
                }
            }
            var list = new int[count];
            var n = 0;
            for (var m = 0; m <= maxLayerId; m++) {
                if (included[i][m]) {
                    list[n++] = m;
                }
            }
            baseSets[i] = list;
        }
        vps.layerSetLayerIdList = baseSets;
        vps.olsIdxToLsIdx = new int[]{0};
        if (reader.readFlag()) {
            while (reader.bitPosition() % 8 != 0) {
                reader.readBit();
            }
            vps.parseExtension(reader, maxLayersMinus1, ptlProfile);
        } else if (vps.maxLayers > 1) {
            // a set that announces layers without describing them leaves the base layer usable
            vps.maxLayers = 1;
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated video parameter set");
        }
        return vps;
    }

    /**
     * Parses a {@code vps_extension()} structure and derives its variables.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param maxLayersMinus1 {@code vps_max_layers_minus1}
     * @param ptlProfile receives the profile of each {@code profile_tier_level()} structure, the
     * first one already filled
     * @throws MediaException.Malformed if a field is out of range
     */
    private void parseExtension(H264BitReader reader, int maxLayersMinus1, int[] ptlProfile) {
        extension = true;
        var layers = maxLayers;
        var subLayersMinus1 = maxSubLayers - 1;
        if (maxLayersMinus1 > 0 && baseLayerInternal) {
            ptlProfile[1] = profileOrInherit(HevcSps.parseProfileTierLevel(reader, false, subLayersMinus1) >> 8,
                    ptlProfile[0]);
        }
        var splitting = reader.readFlag();
        var mask = new boolean[16];
        var numTypes = 0;
        for (var i = 0; i < 16; i++) {
            mask[i] = reader.readFlag();
            if (mask[i]) {
                numTypes++;
            }
        }
        var dimLen = new int[numTypes];
        for (var j = 0; j < numTypes - (splitting ? 1 : 0); j++) {
            dimLen[j] = reader.readBits(3) + 1;
        }
        var dimBitOffset = new int[numTypes + 1];
        if (splitting && numTypes > 0) {
            for (var j = 1; j < numTypes; j++) {
                dimBitOffset[j] = dimBitOffset[j - 1] + dimLen[j - 1];
            }
            dimLen[numTypes - 1] = 6 - dimBitOffset[numTypes - 1];
            if (dimLen[numTypes - 1] <= 0) {
                throw new MediaException.Malformed("scalability dimensions longer than the layer identifier");
            }
            dimBitOffset[numTypes] = 6;
        }
        var nuhLayerIdPresent = reader.readFlag();
        layerIdInNuh = new int[layers];
        var dimensionId = new int[layers][numTypes];
        for (var i = 1; i < layers; i++) {
            layerIdInNuh[i] = nuhLayerIdPresent ? reader.readBits(6) : i;
            if (layerIdInNuh[i] <= layerIdInNuh[i - 1] || layerIdInNuh[i] > 62) {
                throw new MediaException.Malformed("layer_id_in_nuh out of order");
            }
            if (!splitting) {
                for (var j = 0; j < numTypes; j++) {
                    dimensionId[i][j] = reader.readBits(dimLen[j]);
                }
            }
        }
        if (splitting) {
            for (var i = 0; i < layers; i++) {
                for (var j = 0; j < numTypes; j++) {
                    dimensionId[i][j] = (layerIdInNuh[i] & ((1 << dimBitOffset[j + 1]) - 1)) >> dimBitOffset[j];
                }
            }
        }
        Arrays.fill(layerIdxInVps, -1);
        scalabilityId = new int[layers][16];
        numViews = 1;
        for (var i = 0; i < layers; i++) {
            var lId = layerIdInNuh[i];
            layerIdxInVps[lId] = i;
            for (int smIdx = 0, j = 0; smIdx < 16; smIdx++) {
                scalabilityId[i][smIdx] = mask[smIdx] ? dimensionId[i][j++] : 0;
            }
            depthLayer[lId] = scalabilityId[i][0] != 0;
            viewOrderIdx[lId] = scalabilityId[i][1];
            dependencyId[lId] = scalabilityId[i][2];
            auxId[lId] = scalabilityId[i][3];
            if (i > 0) {
                var newView = true;
                for (var j = 0; j < i; j++) {
                    if (viewOrderIdx[lId] == viewOrderIdx[layerIdInNuh[j]]) {
                        newView = false;
                        break;
                    }
                }
                if (newView) {
                    numViews++;
                }
            }
        }
        var viewIdLen = reader.readBits(4);
        var viewIdVal = new int[numViews];
        if (viewIdLen > 0) {
            for (var i = 0; i < numViews; i++) {
                viewIdVal[i] = reader.readBits(viewIdLen);
            }
        }
        for (var i = 0; i < layers; i++) {
            var lId = layerIdInNuh[i];
            var order = viewOrderIdx[lId];
            viewId[lId] = order < numViews ? viewIdVal[order] : 0;
        }
        directDependency = new boolean[layers][layers];
        for (var i = 1; i < layers; i++) {
            for (var j = 0; j < i; j++) {
                directDependency[i][j] = reader.readFlag();
            }
        }
        deriveDependencies();
        var independent = 0;
        var treeLists = new int[layers][];
        var inList = new boolean[64];
        for (var i = 0; i < layers; i++) {
            var lId = layerIdInNuh[i];
            if (idDirectRefLayer[lId].length == 0) {
                var predicted = idPredictedLayer[lId];
                var list = new int[predicted.length + 1];
                list[0] = lId;
                var h = 1;
                for (var predLId : predicted) {
                    if (!inList[predLId]) {
                        list[h++] = predLId;
                        inList[predLId] = true;
                    }
                }
                treeLists[independent++] = Arrays.copyOf(list, h);
            }
        }
        var numAddLayerSets = 0;
        if (independent > 1) {
            numAddLayerSets = reader.readUe(1023, "num_add_layer_sets");
        }
        var sets = Arrays.copyOf(layerSetLayerIdList, numBaseLayerSets + numAddLayerSets);
        for (var i = 0; i < numAddLayerSets; i++) {
            var list = new int[layers];
            var n = 0;
            for (var treeIdx = 1; treeIdx < independent; treeIdx++) {
                var tree = treeLists[treeIdx];
                var bits = ceilLog2(tree.length + 1);
                var highest = reader.readBits(bits);
                if (highest > tree.length) {
                    throw new MediaException.Malformed("highest_layer_idx_plus1 out of range");
                }
                for (var layerCnt = 0; layerCnt < highest; layerCnt++) {
                    list[n++] = tree[layerCnt];
                }
            }
            sets[numBaseLayerSets + i] = Arrays.copyOf(list, n);
        }
        layerSetLayerIdList = sets;
        var numLayerSets = sets.length;
        subLayersVpsMaxMinus1 = new int[layers];
        Arrays.fill(subLayersVpsMaxMinus1, subLayersMinus1);
        if (reader.readFlag()) {
            for (var i = 0; i < layers; i++) {
                subLayersVpsMaxMinus1[i] = Math.min(reader.readBits(3), subLayersMinus1);
            }
        }
        maxTidIlRefPicsPlus1 = new int[layers][layers];
        for (var row : maxTidIlRefPicsPlus1) {
            Arrays.fill(row, 7);
        }
        if (reader.readFlag()) {
            for (var i = 0; i < layers - 1; i++) {
                for (var j = i + 1; j < layers; j++) {
                    if (directDependency[j][i]) {
                        maxTidIlRefPicsPlus1[i][j] = reader.readBits(3);
                    }
                }
            }
        }
        defaultRefLayersActive = reader.readFlag();
        var numPtl = reader.readUe(63, "vps_num_profile_tier_level_minus1") + 1;
        for (var i = baseLayerInternal ? 2 : 1; i < numPtl; i++) {
            var present = reader.readFlag();
            ptlProfile[i] = profileOrInherit(HevcSps.parseProfileTierLevel(reader, present, subLayersMinus1) >> 8,
                    i > 0 ? ptlProfile[i - 1] : 0);
        }
        ptlProfileIdc = Arrays.copyOf(ptlProfile, numPtl);
        var numAddOlss = 0;
        var defaultOutputLayerIdc = 0;
        if (numLayerSets > 1) {
            numAddOlss = reader.readUe(1023, "num_add_olss");
            defaultOutputLayerIdc = Math.min(reader.readBits(2), 2);
        }
        var numOls = numAddOlss + numLayerSets;
        olsIdxToLsIdx = new int[numOls];
        outputLayerFlag = new boolean[numOls][];
        necessaryLayerFlag = new boolean[numOls][];
        ptlIdx = new int[numOls][];
        altOutputLayer = new boolean[numOls];
        outputLayerFlag[0] = new boolean[]{true};
        necessaryLayerFlag[0] = new boolean[]{true};
        ptlIdx[0] = new int[]{baseLayerInternal && maxLayersMinus1 > 0 ? 1 : 0};
        for (var i = 1; i < numOls; i++) {
            var setIdx = i;
            if (i >= numLayerSets) {
                var minus1 = numLayerSets > 2 ? reader.readBits(ceilLog2(numLayerSets - 1)) : 0;
                if (minus1 > numLayerSets - 2) {
                    throw new MediaException.Malformed("layer_set_idx_for_ols_minus1 out of range");
                }
                setIdx = minus1 + 1;
            }
            olsIdxToLsIdx[i] = setIdx;
            var list = sets[setIdx];
            var output = new boolean[list.length];
            if (i > numBaseLayerSets - 1 || defaultOutputLayerIdc == 2) {
                for (var j = 0; j < list.length; j++) {
                    output[j] = reader.readFlag();
                }
            } else {
                var highest = list.length == 0 ? -1 : list[list.length - 1];
                for (var j = 0; j < list.length; j++) {
                    output[j] = defaultOutputLayerIdc == 0 || list[j] == highest;
                }
            }
            outputLayerFlag[i] = output;
            var necessary = new boolean[list.length];
            for (var j = 0; j < list.length; j++) {
                if (output[j]) {
                    necessary[j] = true;
                    var current = layerIdxInVps[list[j]];
                    for (var r = 0; r < j; r++) {
                        var reference = layerIdxInVps[list[r]];
                        if (current >= 0 && reference >= 0 && dependency[current][reference]) {
                            necessary[r] = true;
                        }
                    }
                }
            }
            necessaryLayerFlag[i] = necessary;
            var indices = new int[list.length];
            for (var j = 0; j < list.length; j++) {
                if (necessary[j] && numPtl > 1) {
                    indices[j] = reader.readBits(ceilLog2(numPtl));
                }
            }
            ptlIdx[i] = indices;
            var outputs = 0;
            var highestOutput = -1;
            for (var j = 0; j < list.length; j++) {
                if (output[j]) {
                    outputs++;
                    highestOutput = list[j];
                }
            }
            if (outputs == 1 && idDirectRefLayer[highestOutput].length > 0) {
                altOutputLayer[i] = reader.readFlag();
            }
        }
        var numRepFormats = reader.readUe(255, "vps_num_rep_formats_minus1") + 1;
        repFormats = new RepFormat[numRepFormats];
        RepFormat previous = null;
        for (var i = 0; i < numRepFormats; i++) {
            previous = parseRepFormat(reader, previous);
            repFormats[i] = previous;
        }
        repFormatIdx = new int[layers];
        var idxPresent = numRepFormats > 1 && reader.readFlag();
        for (var i = 0; i < layers; i++) {
            repFormatIdx[i] = Math.min(i, numRepFormats - 1);
        }
        if (idxPresent) {
            for (var i = baseLayerInternal ? 1 : 0; i < layers; i++) {
                repFormatIdx[i] = reader.readBits(ceilLog2(numRepFormats));
                if (repFormatIdx[i] >= numRepFormats) {
                    throw new MediaException.Malformed("vps_rep_format_idx out of range");
                }
            }
        }
        maxOneActiveRefLayer = reader.readFlag();
        pocLsbAligned = reader.readFlag();
        pocLsbNotPresent = new boolean[layers];
        for (var i = 1; i < layers; i++) {
            if (idDirectRefLayer[layerIdInNuh[i]].length == 0) {
                pocLsbNotPresent[i] = reader.readFlag();
            }
        }
        parseDpbSize(reader);
        var typeBits = reader.readUe(30, "direct_dep_type_len_minus2") + 2;
        var types = new long[layers][layers];
        if (reader.readFlag()) {
            var all = reader.readLong(typeBits);
            for (var row : types) {
                Arrays.fill(row, all);
            }
            if (!baseLayerInternal) {
                for (var i = 1; i < layers; i++) {
                    types[i][0] = 0;
                }
            }
        } else {
            for (var i = baseLayerInternal ? 1 : 2; i < layers; i++) {
                for (var j = baseLayerInternal ? 0 : 1; j < i; j++) {
                    if (directDependency[i][j]) {
                        types[i][j] = reader.readLong(typeBits);
                    }
                }
            }
        }
        samplePrediction = new boolean[layers][layers];
        motionPrediction = new boolean[layers][layers];
        for (var i = 0; i < layers; i++) {
            for (var j = 0; j < layers; j++) {
                if (directDependency[i][j]) {
                    samplePrediction[i][j] = ((types[i][j] + 1) & 1) != 0;
                    motionPrediction[i][j] = ((types[i][j] + 1) & 2) != 0;
                }
            }
        }
    }

    /**
     * Derives {@code DependencyFlag}, {@code IdDirectRefLayer}, {@code IdRefLayer} and
     * {@code IdPredictedLayer} (equations F-4 and F-5).
     */
    private void deriveDependencies() {
        var layers = maxLayers;
        dependency = new boolean[layers][layers];
        for (var i = 0; i < layers; i++) {
            for (var j = 0; j < layers; j++) {
                dependency[i][j] = directDependency[i][j];
                for (var k = 0; k < i; k++) {
                    if (directDependency[i][k] && dependency[k][j]) {
                        dependency[i][j] = true;
                    }
                }
            }
        }
        for (var i = 0; i < layers; i++) {
            var direct = new int[layers];
            var all = new int[layers];
            var predicted = new int[layers];
            int d = 0, r = 0, p = 0;
            for (var j = 0; j < layers; j++) {
                if (directDependency[i][j]) {
                    direct[d++] = layerIdInNuh[j];
                }
                if (dependency[i][j]) {
                    all[r++] = layerIdInNuh[j];
                }
                if (dependency[j][i]) {
                    predicted[p++] = layerIdInNuh[j];
                }
            }
            var lId = layerIdInNuh[i];
            idDirectRefLayer[lId] = Arrays.copyOf(direct, d);
            idRefLayer[lId] = Arrays.copyOf(all, r);
            idPredictedLayer[lId] = Arrays.copyOf(predicted, p);
        }
    }

    /**
     * Parses a {@code dpb_size()} structure (F.7.3.2.1.3).
     *
     * @param reader the reader, positioned at the syntax structure
     */
    private void parseDpbSize(H264BitReader reader) {
        var numOls = olsIdxToLsIdx.length;
        maxDecPicBuffering = new int[numOls][][];
        maxNumReorder = new int[numOls][];
        maxLatencyIncreasePlus1 = new int[numOls][];
        for (var i = 1; i < numOls; i++) {
            var lsIdx = olsIdxToLsIdx[i];
            var list = layerSetLayerIdList[lsIdx];
            var maxSlMinus1 = 0;
            for (var lId : list) {
                var index = layerIdxInVps[lId];
                if (index >= 0) {
                    maxSlMinus1 = Math.max(maxSlMinus1, subLayersVpsMaxMinus1[index]);
                }
            }
            var buffering = new int[list.length][maxSlMinus1 + 1];
            var reorder = new int[maxSlMinus1 + 1];
            var latency = new int[maxSlMinus1 + 1];
            var flagsPresent = reader.readFlag();
            for (var j = 0; j <= maxSlMinus1; j++) {
                var present = j == 0 || flagsPresent && reader.readFlag();
                if (present) {
                    for (var k = 0; k < list.length; k++) {
                        if (necessaryLayerFlag[i][k] && (baseLayerInternal || list[k] != 0)) {
                            buffering[k][j] = reader.readUe(15, "max_vps_dec_pic_buffering_minus1") + 1;
                        } else {
                            buffering[k][j] = j > 0 ? buffering[k][j - 1] : 1;
                        }
                    }
                    reorder[j] = reader.readUe(15, "max_vps_num_reorder_pics");
                    latency[j] = reader.readUe();
                } else {
                    for (var k = 0; k < list.length; k++) {
                        buffering[k][j] = buffering[k][j - 1];
                    }
                    reorder[j] = reorder[j - 1];
                    latency[j] = latency[j - 1];
                }
            }
            maxDecPicBuffering[i] = buffering;
            maxNumReorder[i] = reorder;
            maxLatencyIncreasePlus1[i] = latency;
        }
    }

    /**
     * Parses a {@code rep_format()} structure.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param previous the structure before it, whose chroma format and bit depths it inherits when
     * it carries none, or {@code null} for the first
     * @return the structure
     * @throws MediaException.Malformed if a field is out of range
     */
    private static RepFormat parseRepFormat(H264BitReader reader, RepFormat previous) {
        var width = reader.readBits(16);
        var height = reader.readBits(16);
        int chroma;
        boolean separate;
        int lumaDepth;
        int chromaDepth;
        if (reader.readFlag()) {
            chroma = reader.readBits(2);
            separate = chroma == 3 && reader.readFlag();
            lumaDepth = reader.readBits(4) + 8;
            chromaDepth = reader.readBits(4) + 8;
        } else if (previous != null) {
            chroma = previous.chromaFormatIdc();
            separate = previous.separateColorPlane();
            lumaDepth = previous.bitDepthLuma();
            chromaDepth = previous.bitDepthChroma();
        } else {
            throw new MediaException.Malformed("first rep_format without chroma format and bit depths");
        }
        if (width == 0 || height == 0 || lumaDepth > 16 || chromaDepth > 16) {
            throw new MediaException.Malformed("rep_format out of range");
        }
        int left = 0, right = 0, top = 0, bottom = 0;
        if (reader.readFlag()) {
            var arrayType = separate ? 0 : chroma;
            var unitX = arrayType == 1 || arrayType == 2 ? 2 : 1;
            var unitY = arrayType == 1 ? 2 : 1;
            left = reader.readUe(65535, "conf_win_vps_left_offset") * unitX;
            right = reader.readUe(65535, "conf_win_vps_right_offset") * unitX;
            top = reader.readUe(65535, "conf_win_vps_top_offset") * unitY;
            bottom = reader.readUe(65535, "conf_win_vps_bottom_offset") * unitY;
            if (left + right >= width || top + bottom >= height) {
                throw new MediaException.Malformed("rep_format conformance window leaves no picture");
            }
        }
        return new RepFormat(width, height, chroma, separate, lumaDepth, chromaDepth, left, right, top, bottom);
    }

    /**
     * Returns a parsed profile, or the inherited one when the structure carried no profile.
     *
     * @param parsed the parsed {@code general_profile_idc}, or -1 when absent
     * @param inherited the profile of the structure before
     * @return the profile
     */
    private static int profileOrInherit(int parsed, int inherited) {
        return parsed >= 0 ? parsed : inherited;
    }

    /**
     * Returns {@code Ceil(Log2(value))}.
     *
     * @param value a positive value
     * @return the number of bits
     */
    static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
