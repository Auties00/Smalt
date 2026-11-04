package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a parsed picture parameter set (ITU-T H.265 7.3.2.3) and, once activated for a sequence
 * parameter set, the tile layout and scan conversion arrays of ITU-T H.265 6.5.1 and 6.5.2.
 */
public final class HevcPps implements Cloneable {
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
     * Whether the set carries a 3D extension, which this decoder does not parse.
     */
    public boolean extension3d;

    /**
     * {@code poc_reset_info_present_flag}.
     */
    public boolean pocResetInfoPresent;

    /**
     * {@code pps_scaling_list_ref_layer_id}, or -1 when {@code pps_infer_scaling_list_flag} is
     * clear.
     */
    public int scalingListRefLayerId = -1;

    /**
     * The reference layer location offsets of {@code pps_multilayer_extension()} by reference
     * layer identifier, or {@code null} for a layer without them.
     */
    public final RefLocation[] refLocations = new RefLocation[64];

    /**
     * The colour mapping table, or {@code null} when {@code colour_mapping_enabled_flag} is clear.
     */
    public ColourMapping colourMapping;

    /**
     * The reference layer location offsets of one reference layer (F.7.4.3.3.4), in the units of
     * the syntax elements.
     *
     * @param scaledLeft {@code scaled_ref_layer_left_offset}
     * @param scaledTop {@code scaled_ref_layer_top_offset}
     * @param scaledRight {@code scaled_ref_layer_right_offset}
     * @param scaledBottom {@code scaled_ref_layer_bottom_offset}
     * @param regionLeft {@code ref_region_left_offset}
     * @param regionTop {@code ref_region_top_offset}
     * @param regionRight {@code ref_region_right_offset}
     * @param regionBottom {@code ref_region_bottom_offset}
     * @param phaseHorLuma {@code phase_hor_luma}
     * @param phaseVerLuma {@code phase_ver_luma}
     * @param phaseHorChromaPlus8 {@code phase_hor_chroma_plus8}
     * @param phaseVerChromaPlus8 {@code phase_ver_chroma_plus8}, -1 when absent (inferred from the
     * scaling ratio)
     */
    public record RefLocation(int scaledLeft, int scaledTop, int scaledRight, int scaledBottom, int regionLeft,
                              int regionTop, int regionRight, int regionBottom, int phaseHorLuma, int phaseVerLuma,
                              int phaseHorChromaPlus8, int phaseVerChromaPlus8) {
        /**
         * The offsets of a reference layer the set gives none for.
         */
        public static final RefLocation NONE = new RefLocation(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, -1);
    }

    /**
     * A colour mapping table (F.7.3.2.3.5 and F.7.4.3.3.5) with its coefficients
     * {@code LutY}, {@code LutCb} and {@code LutCr}.
     *
     * @param refLayerIds {@code cm_ref_layer_id}
     * @param octantDepth {@code cm_octant_depth}
     * @param yPartNumLog2 {@code cm_y_part_num_log2}
     * @param inputDepthLuma {@code BitDepthCmInputY}
     * @param inputDepthChroma {@code BitDepthCmInputC}
     * @param outputDepthLuma {@code BitDepthCmOutputY}
     * @param outputDepthChroma {@code BitDepthCmOutputC}
     * @param threshU {@code CMThreshU}
     * @param threshV {@code CMThreshV}
     * @param lut the coefficients, indexed {@code [component][((idxY * OctantNumC + idxCb) *
     * OctantNumC + idxCr) * 4 + j]}
     */
    public record ColourMapping(int[] refLayerIds, int octantDepth, int yPartNumLog2, int inputDepthLuma,
                                int inputDepthChroma, int outputDepthLuma, int outputDepthChroma, int threshU,
                                int threshV, int[][] lut) {
        /**
         * Returns whether the table maps the pictures of a reference layer.
         *
         * @param layerId the {@code nuh_layer_id} of the reference layer
         * @return {@code true} when {@code cm_ref_layer_id} lists the layer
         */
        public boolean appliesTo(int layerId) {
            for (var id : refLayerIds) {
                if (id == layerId) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Whether the set carries a screen content coding extension ({@code pps_scc_extension()}).
     */
    public boolean sccExtension;

    /**
     * {@code pps_curr_pic_ref_enabled_flag}: whether the pictures of the set may reference
     * themselves (intra block copy).
     */
    public boolean currPicRefEnabled;

    /**
     * {@code residual_adaptive_colour_transform_enabled_flag}.
     */
    public boolean actEnabled;

    /**
     * {@code pps_slice_act_qp_offsets_present_flag}.
     */
    public boolean sliceActQpOffsetsPresent;

    /**
     * {@code PpsActQpOffsetY}, {@code PpsActQpOffsetCb} and {@code PpsActQpOffsetCr}.
     */
    public int[] actQpOffset = {-5, -5, -3};

    /**
     * {@code pps_palette_predictor_initializers_present_flag}.
     */
    public boolean paletteInitializersPresent;

    /**
     * {@code pps_palette_predictor_initializer}, indexed {@code [comp][i]}: one component with
     * {@code monochrome_palette_flag}, three otherwise, {@code pps_num_palette_predictor_initializers}
     * entries each (none when that is 0).
     */
    public int[][] paletteInitializers = new int[0][];

    /**
     * {@code pps_num_palette_predictor_initializers}.
     */
    public int numPaletteInitializers;

    /**
     * {@code luma_bit_depth_entry_minus8 + 8}, or 0 without initializers.
     */
    public int paletteLumaBitDepth;

    /**
     * {@code chroma_bit_depth_entry_minus8 + 8}, or 0 without chroma initializers.
     */
    public int paletteChromaBitDepth;

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
            if (multilayerExtension) {
                parseMultilayerExtension(reader, pps);
            }
            pps.extension3d = extension3d;
            if (sccExtension && !extension3d) {
                parseSccExtension(reader, pps);
                if (reader.overrun()) {
                    // the bit of a later version's extension data, set by an encoder of an earlier version
                    pps.clearScc();
                    return pps;
                }
                pps.sccExtension = true;
            }
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
        if (extension3d) {
            // TODO: the 3D-HEVC extension of a base layer picture parameter set (Annex I)
            return "hevc 3D extension";
        }
        return null;
    }

    /**
     * Parses a {@code pps_multilayer_extension()} structure (F.7.3.2.3.4).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param pps the picture parameter set being parsed
     * @throws MediaException.Malformed if a field is out of range
     */
    private static void parseMultilayerExtension(H264BitReader reader, HevcPps pps) {
        pps.pocResetInfoPresent = reader.readFlag();
        if (reader.readFlag()) {
            pps.scalingListRefLayerId = reader.readBits(6);
        }
        var count = reader.readUe(63, "num_ref_loc_offsets");
        for (var i = 0; i < count; i++) {
            var layer = reader.readBits(6);
            int sl = 0, st = 0, sr = 0, sb = 0;
            if (reader.readFlag()) {
                sl = readOffset(reader, "scaled_ref_layer_left_offset");
                st = readOffset(reader, "scaled_ref_layer_top_offset");
                sr = readOffset(reader, "scaled_ref_layer_right_offset");
                sb = readOffset(reader, "scaled_ref_layer_bottom_offset");
            }
            int rl = 0, rt = 0, rr = 0, rb = 0;
            if (reader.readFlag()) {
                rl = readOffset(reader, "ref_region_left_offset");
                rt = readOffset(reader, "ref_region_top_offset");
                rr = readOffset(reader, "ref_region_right_offset");
                rb = readOffset(reader, "ref_region_bottom_offset");
            }
            int hl = 0, vl = 0, hc = 8, vc = -1;
            if (reader.readFlag()) {
                hl = reader.readUe(31, "phase_hor_luma");
                vl = reader.readUe(31, "phase_ver_luma");
                hc = reader.readUe(63, "phase_hor_chroma_plus8");
                vc = reader.readUe(63, "phase_ver_chroma_plus8");
            }
            pps.refLocations[layer] = new RefLocation(sl, st, sr, sb, rl, rt, rr, rb, hl, vl, hc, vc);
        }
        if (reader.readFlag()) {
            pps.colourMapping = parseColourMapping(reader);
        }
    }

    /**
     * Reads a reference layer location offset, {@code se(v)} from {@code -2^14} to
     * {@code 2^14 - 1}.
     *
     * @param reader the reader positioned at the offset
     * @param name the syntax element name
     * @return the offset
     */
    private static int readOffset(H264BitReader reader, String name) {
        return reader.readSe(-(1 << 14), (1 << 14) - 1, name);
    }

    /**
     * Parses a {@code colour_mapping_table()} structure and derives its coefficients (equations
     * F-41 to F-51).
     *
     * @param reader the reader, positioned at the syntax structure
     * @return the table
     * @throws MediaException.Malformed if a field is out of range
     */
    private static ColourMapping parseColourMapping(H264BitReader reader) {
        var layers = reader.readUe(61, "num_cm_ref_layers_minus1") + 1;
        var ids = new int[layers];
        for (var i = 0; i < layers; i++) {
            ids[i] = reader.readBits(6);
        }
        var octantDepth = reader.readBits(2);
        var yPartNumLog2 = reader.readBits(2);
        if (octantDepth > 1 || octantDepth + yPartNumLog2 > 3) {
            throw new MediaException.Malformed("colour mapping octant depth out of range");
        }
        var inY = reader.readUe(8, "luma_bit_depth_cm_input_minus8") + 8;
        var inC = reader.readUe(8, "chroma_bit_depth_cm_input_minus8") + 8;
        var outY = reader.readUe(8, "luma_bit_depth_cm_output_minus8") + 8;
        var outC = reader.readUe(8, "chroma_bit_depth_cm_output_minus8") + 8;
        if (outY < inY || outC < inC) {
            throw new MediaException.Malformed("colour mapping output shallower than its input");
        }
        var quantBits = reader.readBits(2);
        var flcBits = reader.readBits(2) + 1;
        var threshU = 1 << (inC - 1);
        var threshV = 1 << (inC - 1);
        if (octantDepth == 1) {
            var half = 1 << (inC - 1);
            threshU += reader.readSe(-half, half - 1, "cm_adapt_threshold_u_delta");
            threshV += reader.readSe(-half, half - 1, "cm_adapt_threshold_v_delta");
        }
        var octantNumC = 1 << octantDepth;
        var octantNumY = 1 << (octantDepth + yPartNumLog2);
        var resLsBits = Math.max(0, 10 + inY - outY - quantBits - flcBits);
        var residuals = new int[3][octantNumY * octantNumC * octantNumC * 4];
        new OctantParser(reader, octantDepth, 1 << yPartNumLog2, octantNumC, resLsBits, quantBits, residuals)
                .parse(0, 0, 0, 0, octantNumC);
        var lut = new int[3][residuals[0].length];
        for (var idxY = 0; idxY < octantNumY; idxY++) {
            for (var idxCb = 0; idxCb < octantNumC; idxCb++) {
                for (var idxCr = 0; idxCr < octantNumC; idxCr++) {
                    var base = ((idxY * octantNumC + idxCb) * octantNumC + idxCr) * 4;
                    var previous = (((idxY - 1) * octantNumC + idxCb) * octantNumC + idxCr) * 4;
                    for (var j = 0; j < 4; j++) {
                        for (var c = 0; c < 3; c++) {
                            var prediction = idxY == 0 ? (j == c ? 1024 : 0) : lut[c][previous + j];
                            var value = residuals[c][base + j] + prediction;
                            if (value < -(1 << 11) || value >= (1 << 11)) {
                                throw new MediaException.Malformed("colour mapping coefficient out of range");
                            }
                            lut[c][base + j] = value;
                        }
                    }
                }
            }
        }
        return new ColourMapping(ids, octantDepth, yPartNumLog2, inY, inC, outY, outC, threshU, threshV, lut);
    }

    /**
     * Parses the {@code colour_mapping_octants()} structures of a colour mapping table into its
     * residuals {@code cmResCoeff} (equation F-50).
     */
    private static final class OctantParser {
        private final H264BitReader reader;

        private final int octantDepth;

        private final int partNumY;

        private final int octantNumC;

        private final int resLsBits;

        private final int quantBits;

        /**
         * The residuals, indexed like {@link ColourMapping#lut()}.
         */
        private final int[][] residuals;

        OctantParser(H264BitReader reader, int octantDepth, int partNumY, int octantNumC, int resLsBits,
                     int quantBits, int[][] residuals) {
            this.reader = reader;
            this.octantDepth = octantDepth;
            this.partNumY = partNumY;
            this.octantNumC = octantNumC;
            this.resLsBits = resLsBits;
            this.quantBits = quantBits;
            this.residuals = residuals;
        }

        /**
         * Parses one {@code colour_mapping_octants(inpDepth, idxY, idxCb, idxCr, inpLength)}; the
         * recursion is at most {@code cm_octant_depth + 1}, that is two, levels deep.
         *
         * @param depth {@code inpDepth}
         * @param idxY the luma octant index
         * @param idxCb the Cb octant index
         * @param idxCr the Cr octant index
         * @param length {@code inpLength}
         */
        void parse(int depth, int idxY, int idxCb, int idxCr, int length) {
            if (depth < octantDepth && reader.readFlag()) {
                var half = length / 2;
                for (var k = 0; k < 2; k++) {
                    for (var m = 0; m < 2; m++) {
                        for (var n = 0; n < 2; n++) {
                            parse(depth + 1, idxY + partNumY * k * half, idxCb + m * half, idxCr + n * half, half);
                        }
                    }
                }
                return;
            }
            for (var i = 0; i < partNumY; i++) {
                var idxShiftY = idxY + (i << (octantDepth - depth));
                var base = ((idxShiftY * octantNumC + idxCb) * octantNumC + idxCr) * 4;
                if (base + 4 > residuals[0].length) {
                    throw new MediaException.Malformed("colour mapping octant out of range");
                }
                for (var j = 0; j < 4; j++) {
                    if (!reader.readFlag()) {
                        continue;
                    }
                    for (var c = 0; c < 3; c++) {
                        var q = reader.readUe(1 << 12, "res_coeff_q");
                        var r = resLsBits > 0 ? reader.readBits(resLsBits) : 0;
                        var negative = (q != 0 || r != 0) && reader.readFlag();
                        var magnitude = ((q << resLsBits) + r) << quantBits;
                        residuals[c][base + j] = negative ? -magnitude : magnitude;
                    }
                }
            }
        }
    }

    /**
     * Parses a {@code pps_scc_extension()} structure (ITU-T H.265 7.3.2.3.3).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param pps the picture parameter set being parsed
     * @throws MediaException.Malformed if a field is out of range
     */
    private static void parseSccExtension(H264BitReader reader, HevcPps pps) {
        pps.currPicRefEnabled = reader.readFlag();
        pps.actEnabled = reader.readFlag();
        if (pps.actEnabled) {
            pps.sliceActQpOffsetsPresent = reader.readFlag();
            pps.actQpOffset[0] = reader.readSe(-7, 17, "pps_act_y_qp_offset_plus5") - 5;
            pps.actQpOffset[1] = reader.readSe(-7, 17, "pps_act_cb_qp_offset_plus5") - 5;
            pps.actQpOffset[2] = reader.readSe(-9, 15, "pps_act_cr_qp_offset_plus3") - 3;
        }
        pps.paletteInitializersPresent = reader.readFlag();
        if (pps.paletteInitializersPresent) {
            pps.numPaletteInitializers = reader.readUe(128, "pps_num_palette_predictor_initializers");
            if (pps.numPaletteInitializers > 0) {
                var monochrome = reader.readFlag();
                pps.paletteLumaBitDepth = reader.readUe(8, "luma_bit_depth_entry_minus8") + 8;
                if (!monochrome) {
                    pps.paletteChromaBitDepth = reader.readUe(8, "chroma_bit_depth_entry_minus8") + 8;
                }
                var components = monochrome ? 1 : 3;
                pps.paletteInitializers = new int[components][pps.numPaletteInitializers];
                for (var c = 0; c < components; c++) {
                    var bits = c == 0 ? pps.paletteLumaBitDepth : pps.paletteChromaBitDepth;
                    for (var i = 0; i < pps.numPaletteInitializers; i++) {
                        pps.paletteInitializers[c][i] = reader.readBits(bits);
                    }
                }
            }
        }
    }

    /**
     * Returns {@code TwoVersionsOfCurrDecPicFlag} (equation 7-40): whether the pictures of the set
     * reference themselves before in-loop filtering while in-loop filters may change their
     * samples.
     *
     * @param sps the active sequence parameter set
     * @return {@code true} when the current picture exists in two versions
     */
    public boolean twoVersions(HevcSps sps) {
        return currPicRefEnabled && (sps.saoEnabled || !deblockingDisabled || deblockingOverrideEnabled);
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
     * Resets the fields of the screen content coding extension to their values without one.
     */
    private void clearScc() {
        sccExtension = false;
        currPicRefEnabled = false;
        actEnabled = false;
        sliceActQpOffsetsPresent = false;
        actQpOffset[0] = -5;
        actQpOffset[1] = -5;
        actQpOffset[2] = -3;
        paletteInitializersPresent = false;
        paletteInitializers = new int[0][];
        numPaletteInitializers = 0;
        paletteLumaBitDepth = 0;
        paletteChromaBitDepth = 0;
    }

    /**
     * Returns the copy of this set whose tables serve a sequence parameter set, for a set that
     * pictures of several layers refer to with different sequence parameter sets: this set when it
     * was never activated or was activated for {@code sps}, otherwise a copy kept for {@code sps},
     * created the first time.
     *
     * @param sps the sequence parameter set
     * @return the set to activate for {@code sps}
     */
    public HevcPps forSps(HevcSps sps) {
        if (sccExtension && !sps.sccExtension) {
            // the screen content coding extension of a set used by a sequence without one is extension data
            if (withoutScc == null) {
                withoutScc = copyOf();
                withoutScc.clearScc();
            }
            return withoutScc.forSps(sps);
        }
        if (isActiveFor(sps)) {
            return this;
        }
        for (var variant : variants) {
            if (variant.activeFor == sps) {
                return variant;
            }
        }
        var copy = copyOf();
        if (variants.length >= MAX_VARIANTS) {
            variants[variants.length - 1] = copy;
        } else {
            variants = java.util.Arrays.copyOf(variants, variants.length + 1);
            variants[variants.length - 1] = copy;
        }
        return copy;
    }

    /**
     * Returns a copy of this set that was never activated, sharing its parsed arrays.
     *
     * @return the copy
     */
    private HevcPps copyOf() {
        try {
            var copy = (HevcPps) super.clone();
            copy.actQpOffset = actQpOffset.clone();
            copy.activeFor = null;
            copy.variants = new HevcPps[0];
            copy.withoutScc = null;
            copy.withScalingList = null;
            return copy;
        } catch (CloneNotSupportedException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * The copy of this set without its screen content coding extension, or {@code null}.
     */
    private HevcPps withoutScc;

    /**
     * The copy of this set with the scaling lists another layer's set gives it, or {@code null}.
     */
    private HevcPps withScalingList;

    /**
     * Returns this set with the scaling lists of the set active for the layer that
     * {@code pps_scaling_list_ref_layer_id} names (F.7.4.3.3.4), for a set that infers them.
     *
     * @param lists the scaling lists of that layer's set, or of its sequence parameter set when the
     * set carries none
     * @return a copy holding {@code lists}, the same one while they stay the same
     */
    public HevcPps inferScalingList(ScalingList lists) {
        if (withScalingList == null || withScalingList.scalingList != lists) {
            withScalingList = copyOf();
            withScalingList.scalingList = lists;
            withScalingList.scalingListRefLayerId = -1;
        }
        return withScalingList;
    }

    /**
     * The most copies {@link #forSps} keeps: one per layer of the largest layer sets in use.
     */
    private static final int MAX_VARIANTS = 8;

    /**
     * The copies of this set activated for other sequence parameter sets.
     */
    private HevcPps[] variants = new HevcPps[0];

    /**
     * Computes the tile layout and the scan conversion arrays for a sequence parameter set, once
     * per sequence parameter set (ITU-T H.265 6.5.1 and 6.5.2).
     *
     * @param sps the active sequence parameter set
     * @throws MediaException.Malformed if the tile layout does not fit the picture, or a range or
     *                                  screen content extension field does not fit the sequence
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
        if ((actEnabled && sps.chromaArrayType != 3)
                || (currPicRefEnabled && !sps.currPicRefEnabled)
                || (numPaletteInitializers > 0 && (!sps.paletteModeEnabled
                || numPaletteInitializers > sps.paletteMaxPredictorSize
                || paletteInitializers.length < (sps.chromaArrayType == 0 ? 1 : 3)
                || paletteLumaBitDepth != sps.bitDepthLuma
                || (sps.chromaArrayType != 0 && paletteChromaBitDepth != sps.bitDepthChroma)))) {
            throw new MediaException.Malformed("picture parameter set screen content extension does not fit its sequence");
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
