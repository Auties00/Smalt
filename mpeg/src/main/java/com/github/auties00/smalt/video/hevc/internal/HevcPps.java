package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed picture parameter set (ITU-T H.265 7.3.2.3) and, once activated for a sequence
 * parameter set, the tile layout and scan conversion arrays of ITU-T H.265 6.5.1 and 6.5.2.
 */
public final class HevcPps {
    /**
     * {@code pps_pic_parameter_set_id}.
     */
    public int id;

    /**
     * {@code pps_seq_parameter_set_id}.
     */
    public int spsId;

    /**
     * {@code dependent_slice_segments_enabled_flag}.
     */
    public boolean dependentSliceSegmentsEnabled;

    /**
     * {@code output_flag_present_flag}.
     */
    public boolean outputFlagPresent;

    /**
     * {@code num_extra_slice_header_bits}.
     */
    public int numExtraSliceHeaderBits;

    /**
     * {@code sign_data_hiding_enabled_flag}.
     */
    public boolean signDataHiding;

    /**
     * {@code cabac_init_present_flag}.
     */
    public boolean cabacInitPresent;

    /**
     * {@code num_ref_idx_l0_default_active_minus1 + 1}.
     */
    public int numRefIdxL0Default;

    /**
     * {@code num_ref_idx_l1_default_active_minus1 + 1}.
     */
    public int numRefIdxL1Default;

    /**
     * {@code init_qp_minus26}.
     */
    public int initQpMinus26;

    /**
     * {@code constrained_intra_pred_flag}.
     */
    public boolean constrainedIntraPred;

    /**
     * {@code transform_skip_enabled_flag}.
     */
    public boolean transformSkipEnabled;

    /**
     * {@code cu_qp_delta_enabled_flag}.
     */
    public boolean cuQpDeltaEnabled;

    /**
     * {@code diff_cu_qp_delta_depth}.
     */
    public int diffCuQpDeltaDepth;

    /**
     * {@code pps_cb_qp_offset}.
     */
    public int cbQpOffset;

    /**
     * {@code pps_cr_qp_offset}.
     */
    public int crQpOffset;

    /**
     * {@code pps_slice_chroma_qp_offsets_present_flag}.
     */
    public boolean sliceChromaQpOffsetsPresent;

    /**
     * {@code weighted_pred_flag}.
     */
    public boolean weightedPred;

    /**
     * {@code weighted_bipred_flag}.
     */
    public boolean weightedBipred;

    /**
     * {@code transquant_bypass_enabled_flag}.
     */
    public boolean transquantBypassEnabled;

    /**
     * {@code tiles_enabled_flag}.
     */
    public boolean tilesEnabled;

    /**
     * {@code entropy_coding_sync_enabled_flag}.
     */
    public boolean entropyCodingSync;

    /**
     * {@code num_tile_columns_minus1 + 1}.
     */
    public int numTileColumns = 1;

    /**
     * {@code num_tile_rows_minus1 + 1}.
     */
    public int numTileRows = 1;

    /**
     * {@code uniform_spacing_flag}.
     */
    public boolean uniformSpacing = true;

    /**
     * {@code column_width_minus1 + 1} of every column but the last.
     */
    public int[] columnWidths = new int[0];

    /**
     * {@code row_height_minus1 + 1} of every row but the last.
     */
    public int[] rowHeights = new int[0];

    /**
     * {@code loop_filter_across_tiles_enabled_flag}, 1 when absent.
     */
    public boolean loopFilterAcrossTiles = true;

    /**
     * {@code pps_loop_filter_across_slices_enabled_flag}.
     */
    public boolean loopFilterAcrossSlices;

    /**
     * {@code deblocking_filter_override_enabled_flag}.
     */
    public boolean deblockingOverrideEnabled;

    /**
     * {@code pps_deblocking_filter_disabled_flag}.
     */
    public boolean deblockingDisabled;

    /**
     * {@code pps_beta_offset_div2}.
     */
    public int betaOffsetDiv2;

    /**
     * {@code pps_tc_offset_div2}.
     */
    public int tcOffsetDiv2;

    /**
     * The PPS scaling lists, or {@code null} when {@code pps_scaling_list_data_present_flag} is
     * clear.
     */
    public ScalingList scalingList;

    /**
     * {@code lists_modification_present_flag}.
     */
    public boolean listsModificationPresent;

    /**
     * {@code Log2ParMrgLevel}.
     */
    public int log2ParallelMergeLevel;

    /**
     * {@code slice_segment_header_extension_present_flag}.
     */
    public boolean sliceHeaderExtensionPresent;

    /**
     * {@code Log2MaxTransformSkipSize}: 2 unless the range extension raises it.
     */
    public int log2MaxTransformSkipSize = 2;

    /**
     * {@code cross_component_prediction_enabled_flag}.
     */
    public boolean crossComponentPrediction;

    /**
     * {@code chroma_qp_offset_list_enabled_flag}.
     */
    public boolean chromaQpOffsetList;

    /**
     * {@code diff_cu_chroma_qp_offset_depth}.
     */
    public int diffCuChromaQpOffsetDepth;

    /**
     * {@code chroma_qp_offset_list_len_minus1 + 1}, or 0 without chroma QP offset lists.
     */
    public int chromaQpOffsetListLength;

    /**
     * {@code cb_qp_offset_list}, {@link #chromaQpOffsetListLength} entries.
     */
    public final int[] cbQpOffsetList = new int[6];

    /**
     * {@code cr_qp_offset_list}, {@link #chromaQpOffsetListLength} entries.
     */
    public final int[] crQpOffsetList = new int[6];

    /**
     * {@code log2_sao_offset_scale_luma}.
     */
    public int log2SaoOffsetScaleLuma;

    /**
     * {@code log2_sao_offset_scale_chroma}.
     */
    public int log2SaoOffsetScaleChroma;

    /**
     * Whether the set carries a 3D or screen content coding extension.
     *
     * <p>A multilayer extension is ignored: it only affects the layers above the base layer, which
     * this decoder skips, so a version 1 decoder decodes the base layer of a scalable or multiview
     * stream.
     */
    public boolean otherExtensions;

    /**
     * The sequence parameter set the derived arrays below were computed for.
     */
    private HevcSps activeFor;

    /**
     * {@code CtbAddrRsToTs}.
     */
    public int[] ctbAddrRsToTs;

    /**
     * {@code CtbAddrTsToRs}.
     */
    public int[] ctbAddrTsToRs;

    /**
     * {@code TileId}, indexed by tile scan address.
     */
    public int[] tileId;

    /**
     * The tile scan address of the first coding tree block of each tile, followed by
     * {@code PicSizeInCtbsY}.
     */
    public int[] tileStartTs;

    /**
     * {@code MinTbAddrZs}, indexed {@code y * widthInMinTbs + x} in minimum transform blocks.
     */
    public int[] minTbAddrZs;

    /**
     * The picture width in minimum transform blocks, the stride of {@link #minTbAddrZs}.
     */
    public int widthInMinTbs;

    /**
     * Constructs an empty set; {@link #parse(H264BitReader)} fills it.
     */
    private HevcPps() {
    }

    /**
     * Parses a picture parameter set RBSP that starts after the NAL unit header.
     *
     * @param reader the reader positioned after the NAL unit header
     * @return the picture parameter set
     * @throws MediaException.Malformed if a field is out of range or the payload is truncated
     */
    public static HevcPps parse(H264BitReader reader) {
        var pps = new HevcPps();
        pps.id = reader.readUe(63, "pps_pic_parameter_set_id");
        pps.spsId = reader.readUe(15, "pps_seq_parameter_set_id");
        pps.dependentSliceSegmentsEnabled = reader.readFlag();
        pps.outputFlagPresent = reader.readFlag();
        pps.numExtraSliceHeaderBits = reader.readBits(3);
        pps.signDataHiding = reader.readFlag();
        pps.cabacInitPresent = reader.readFlag();
        pps.numRefIdxL0Default = reader.readUe(14, "num_ref_idx_l0_default_active_minus1") + 1;
        pps.numRefIdxL1Default = reader.readUe(14, "num_ref_idx_l1_default_active_minus1") + 1;
        pps.initQpMinus26 = reader.readSe(-(26 + 6 * 8), 25, "init_qp_minus26");
        pps.constrainedIntraPred = reader.readFlag();
        pps.transformSkipEnabled = reader.readFlag();
        pps.cuQpDeltaEnabled = reader.readFlag();
        if (pps.cuQpDeltaEnabled) {
            pps.diffCuQpDeltaDepth = reader.readUe(3, "diff_cu_qp_delta_depth");
        }
        pps.cbQpOffset = reader.readSe(-12, 12, "pps_cb_qp_offset");
        pps.crQpOffset = reader.readSe(-12, 12, "pps_cr_qp_offset");
        pps.sliceChromaQpOffsetsPresent = reader.readFlag();
        pps.weightedPred = reader.readFlag();
        pps.weightedBipred = reader.readFlag();
        pps.transquantBypassEnabled = reader.readFlag();
        pps.tilesEnabled = reader.readFlag();
        pps.entropyCodingSync = reader.readFlag();
        if (pps.tilesEnabled) {
            pps.numTileColumns = reader.readUe(1023, "num_tile_columns_minus1") + 1;
            pps.numTileRows = reader.readUe(1023, "num_tile_rows_minus1") + 1;
            pps.uniformSpacing = reader.readFlag();
            if (!pps.uniformSpacing) {
                pps.columnWidths = new int[pps.numTileColumns - 1];
                for (var i = 0; i < pps.columnWidths.length; i++) {
                    pps.columnWidths[i] = reader.readUe(4095, "column_width_minus1") + 1;
                }
                pps.rowHeights = new int[pps.numTileRows - 1];
                for (var i = 0; i < pps.rowHeights.length; i++) {
                    pps.rowHeights[i] = reader.readUe(4095, "row_height_minus1") + 1;
                }
            }
            pps.loopFilterAcrossTiles = reader.readFlag();
        }
        pps.loopFilterAcrossSlices = reader.readFlag();
        if (reader.readFlag()) {
            pps.deblockingOverrideEnabled = reader.readFlag();
            pps.deblockingDisabled = reader.readFlag();
            if (!pps.deblockingDisabled) {
                pps.betaOffsetDiv2 = reader.readSe(-6, 6, "pps_beta_offset_div2");
                pps.tcOffsetDiv2 = reader.readSe(-6, 6, "pps_tc_offset_div2");
            }
        }
        if (reader.readFlag()) {
            pps.scalingList = ScalingList.parse(reader);
        }
        pps.listsModificationPresent = reader.readFlag();
        pps.log2ParallelMergeLevel = reader.readUe(4, "log2_parallel_merge_level_minus2") + 2;
        pps.sliceHeaderExtensionPresent = reader.readFlag();
        if (reader.readFlag()) {
            var rangeExtension = reader.readFlag();
            var multilayerExtension = reader.readFlag();
            var extension3d = reader.readFlag();
            var sccExtension = reader.readFlag();
            reader.readBits(4);
            if (rangeExtension) {
                if (pps.transformSkipEnabled) {
                    pps.log2MaxTransformSkipSize = reader.readUe(3, "log2_max_transform_skip_block_size_minus2") + 2;
                }
                pps.crossComponentPrediction = reader.readFlag();
                pps.chromaQpOffsetList = reader.readFlag();
                if (pps.chromaQpOffsetList) {
                    pps.diffCuChromaQpOffsetDepth = reader.readUe(3, "diff_cu_chroma_qp_offset_depth");
                    pps.chromaQpOffsetListLength = reader.readUe(5, "chroma_qp_offset_list_len_minus1") + 1;
                    for (var i = 0; i < pps.chromaQpOffsetListLength; i++) {
                        pps.cbQpOffsetList[i] = reader.readSe(-12, 12, "cb_qp_offset_list");
                        pps.crQpOffsetList[i] = reader.readSe(-12, 12, "cr_qp_offset_list");
                    }
                }
                pps.log2SaoOffsetScaleLuma = reader.readUe(6, "log2_sao_offset_scale_luma");
                pps.log2SaoOffsetScaleChroma = reader.readUe(6, "log2_sao_offset_scale_chroma");
            }
            pps.otherExtensions = extension3d || sccExtension;
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated picture parameter set");
        }
        return pps;
    }

    /**
     * Returns the name of the first feature of this set that the decoder does not implement, or
     * {@code null} when it implements all of them.
     *
     * @return the feature name for {@link MediaException.Unsupported}, or {@code null}
     */
    public String unsupportedFeature() {
        if (otherExtensions) {
            return "hevc screen content coding";
        }
        return null;
    }

    /**
     * Returns whether activating the picture parameter set for a sequence parameter set leaves its
     * tables as they are: it was never activated, or it was activated for that one.
     *
     * @param sps the sequence parameter set
     * @return {@code true} when {@link #activate(HevcSps)} would not replace the tables
     */
    public boolean isActiveFor(HevcSps sps) {
        return activeFor == null || activeFor == sps;
    }

    /**
     * Computes the tile layout and the scan conversion arrays for a sequence parameter set, once
     * per sequence parameter set (ITU-T H.265 6.5.1 and 6.5.2).
     *
     * @param sps the active sequence parameter set
     * @throws MediaException.Malformed if the tile layout does not fit the picture
     */
    public void activate(HevcSps sps) {
        if (activeFor == sps) {
            return;
        }
        if (initQpMinus26 < -(26 + 6 * (sps.bitDepthLuma - 8))) {
            throw new MediaException.Malformed("init_qp_minus26 out of range");
        }
        if (log2MaxTransformSkipSize > sps.log2MaxTbSize
                || diffCuChromaQpOffsetDepth > sps.log2CtbSize - sps.log2MinCbSize
                || log2SaoOffsetScaleLuma > Math.max(0, sps.bitDepthLuma - 10)
                || log2SaoOffsetScaleChroma > Math.max(0, sps.bitDepthChroma - 10)
                || (crossComponentPrediction && sps.chromaArrayType != 3)
                || (chromaQpOffsetList && sps.chromaArrayType == 0)) {
            throw new MediaException.Malformed("picture parameter set range extension does not fit its sequence");
        }
        var widthCtbs = sps.widthInCtbs;
        var heightCtbs = sps.heightInCtbs;
        if (numTileColumns > widthCtbs || numTileRows > heightCtbs) {
            throw new MediaException.Malformed("more tiles than coding tree blocks");
        }
        var colWidth = new int[numTileColumns];
        var rowHeight = new int[numTileRows];
        if (uniformSpacing) {
            for (var i = 0; i < numTileColumns; i++) {
                colWidth[i] = ((i + 1) * widthCtbs) / numTileColumns - (i * widthCtbs) / numTileColumns;
            }
            for (var j = 0; j < numTileRows; j++) {
                rowHeight[j] = ((j + 1) * heightCtbs) / numTileRows - (j * heightCtbs) / numTileRows;
            }
        } else {
            var remaining = widthCtbs;
            for (var i = 0; i < numTileColumns - 1; i++) {
                colWidth[i] = columnWidths[i];
                remaining -= colWidth[i];
            }
            colWidth[numTileColumns - 1] = remaining;
            remaining = heightCtbs;
            for (var j = 0; j < numTileRows - 1; j++) {
                rowHeight[j] = rowHeights[j];
                remaining -= rowHeight[j];
            }
            rowHeight[numTileRows - 1] = remaining;
            if (colWidth[numTileColumns - 1] <= 0 || rowHeight[numTileRows - 1] <= 0) {
                throw new MediaException.Malformed("tile sizes exceed the picture");
            }
        }
        var colBd = new int[numTileColumns + 1];
        var rowBd = new int[numTileRows + 1];
        for (var i = 0; i < numTileColumns; i++) {
            colBd[i + 1] = colBd[i] + colWidth[i];
        }
        for (var j = 0; j < numTileRows; j++) {
            rowBd[j + 1] = rowBd[j] + rowHeight[j];
        }
        var size = sps.sizeInCtbs;
        var rsToTs = new int[size];
        var tsToRs = new int[size];
        var tiles = new int[size];
        var ts = 0;
        var tile = 0;
        var starts = new int[numTileColumns * numTileRows + 1];
        for (var j = 0; j < numTileRows; j++) {
            for (var i = 0; i < numTileColumns; i++) {
                starts[tile] = ts;
                for (var y = rowBd[j]; y < rowBd[j + 1]; y++) {
                    for (var x = colBd[i]; x < colBd[i + 1]; x++) {
                        var rs = y * widthCtbs + x;
                        rsToTs[rs] = ts;
                        tsToRs[ts] = rs;
                        tiles[ts] = tile;
                        ts++;
                    }
                }
                tile++;
            }
        }
        var log2Diff = sps.log2CtbSize - sps.log2MinTbSize;
        var minTbWidth = widthCtbs << log2Diff;
        var minTbHeight = heightCtbs << log2Diff;
        var zs = new int[minTbWidth * minTbHeight];
        for (var y = 0; y < minTbHeight; y++) {
            for (var x = 0; x < minTbWidth; x++) {
                var tbX = (x << sps.log2MinTbSize) >> sps.log2CtbSize;
                var tbY = (y << sps.log2MinTbSize) >> sps.log2CtbSize;
                var value = rsToTs[tbY * widthCtbs + tbX] << (log2Diff * 2);
                var m = 0;
                for (var i = 0; i < log2Diff; i++) {
                    m = 1 << i;
                    value += ((m & x) != 0 ? m * m : 0) + ((m & y) != 0 ? 2 * m * m : 0);
                }
                zs[y * minTbWidth + x] = value;
            }
        }
        starts[tile] = ts;
        tileStartTs = starts;
        ctbAddrRsToTs = rsToTs;
        ctbAddrTsToRs = tsToRs;
        tileId = tiles;
        minTbAddrZs = zs;
        widthInMinTbs = minTbWidth;
        activeFor = sps;
    }
}
