package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.h264.internal.H264Sps;

/**
 * Holds a parsed sequence parameter set (ITU-T H.265 7.3.2.2) with the variables derived from it.
 *
 * <p>Parsing accepts every syntax element of the specification. The features this decoder does not
 * implement are reported by {@link #unsupportedFeature()} rather than rejected here, so that a
 * sequence parameter set that is never activated does not stop the stream.
 */
public final class HevcSps {
    /**
     * The largest default display window offset kept, in chroma sample units, the bound of the
     * conformance window offsets.
     */
    private static final int MAX_WINDOW_OFFSET = 65535;

    /**
     * {@code sps_seq_parameter_set_id}.
     */
    public int id;

    /**
     * {@code sps_max_sub_layers_minus1 + 1}.
     */
    public int maxSubLayers;

    /**
     * {@code general_profile_idc}.
     */
    public int profileIdc;

    /**
     * {@code general_level_idc}.
     */
    public int levelIdc;

    /**
     * {@code chroma_format_idc}: 0 monochrome, 1 4:2:0, 2 4:2:2, 3 4:4:4.
     */
    public int chromaFormatIdc;

    /**
     * {@code separate_colour_plane_flag}.
     */
    public boolean separateColorPlane;

    /**
     * {@code ChromaArrayType}: {@link #chromaFormatIdc}, or 0 with separate color planes.
     */
    public int chromaArrayType;

    /**
     * {@code SubWidthC} as a shift: 1 for 4:2:0 and 4:2:2, 0 otherwise.
     */
    public int chromaShiftX;

    /**
     * {@code SubHeightC} as a shift: 1 for 4:2:0, 0 otherwise.
     */
    public int chromaShiftY;

    /**
     * {@code pic_width_in_luma_samples}.
     */
    public int width;

    /**
     * {@code pic_height_in_luma_samples}.
     */
    public int height;

    /**
     * The left offset of the conformance window, in luma samples.
     */
    public int cropLeft;

    /**
     * The right offset of the conformance window, in luma samples.
     */
    public int cropRight;

    /**
     * The top offset of the conformance window, in luma samples.
     */
    public int cropTop;

    /**
     * The bottom offset of the conformance window, in luma samples.
     */
    public int cropBottom;

    /**
     * {@code BitDepthY}.
     */
    public int bitDepthLuma;

    /**
     * {@code BitDepthC}; {@link #bitDepthLuma} with separate color planes, whose planes are all
     * decoded as monochrome pictures.
     */
    public int bitDepthChroma;

    /**
     * {@code log2_max_pic_order_cnt_lsb_minus4 + 4}.
     */
    public int log2MaxPocLsb;

    /**
     * {@code sps_max_dec_pic_buffering_minus1 + 1} of each sub-layer.
     */
    public final int[] maxDecPicBuffering = new int[8];

    /**
     * {@code sps_max_num_reorder_pics} of each sub-layer.
     */
    public final int[] maxNumReorder = new int[8];

    /**
     * {@code sps_max_latency_increase_plus1} of each sub-layer.
     */
    public final int[] maxLatencyIncreasePlus1 = new int[8];

    /**
     * {@code MinCbLog2SizeY}.
     */
    public int log2MinCbSize;

    /**
     * {@code CtbLog2SizeY}.
     */
    public int log2CtbSize;

    /**
     * {@code MinTbLog2SizeY}.
     */
    public int log2MinTbSize;

    /**
     * {@code MaxTbLog2SizeY}.
     */
    public int log2MaxTbSize;

    /**
     * {@code max_transform_hierarchy_depth_inter}.
     */
    public int maxTransformHierarchyDepthInter;

    /**
     * {@code max_transform_hierarchy_depth_intra}.
     */
    public int maxTransformHierarchyDepthIntra;

    /**
     * {@code scaling_list_enabled_flag}.
     */
    public boolean scalingListEnabled;

    /**
     * The SPS scaling lists: the parsed lists, or the default lists when the flag is set without
     * data; {@code null} when scaling lists are disabled.
     */
    public ScalingList scalingList;

    /**
     * {@code amp_enabled_flag}.
     */
    public boolean ampEnabled;

    /**
     * {@code sample_adaptive_offset_enabled_flag}.
     */
    public boolean saoEnabled;

    /**
     * {@code pcm_enabled_flag}.
     */
    public boolean pcmEnabled;

    /**
     * {@code PcmBitDepthY}.
     */
    public int pcmBitDepthLuma;

    /**
     * {@code PcmBitDepthC}.
     */
    public int pcmBitDepthChroma;

    /**
     * {@code Log2MinIpcmCbSizeY}.
     */
    public int log2MinPcmCbSize;

    /**
     * {@code Log2MaxIpcmCbSizeY}.
     */
    public int log2MaxPcmCbSize;

    /**
     * {@code pcm_loop_filter_disabled_flag}.
     */
    public boolean pcmLoopFilterDisabled;

    /**
     * The short-term reference picture sets, {@code num_short_term_ref_pic_sets} of them.
     */
    public ShortTermRps[] shortTermRps = new ShortTermRps[0];

    /**
     * {@code long_term_ref_pics_present_flag}.
     */
    public boolean longTermRefsPresent;

    /**
     * {@code num_long_term_ref_pics_sps}.
     */
    public int numLongTermRefPicsSps;

    /**
     * {@code lt_ref_pic_poc_lsb_sps}.
     */
    public final int[] ltRefPicPocLsbSps = new int[32];

    /**
     * {@code used_by_curr_pic_lt_sps_flag}.
     */
    public final boolean[] usedByCurrPicLtSps = new boolean[32];

    /**
     * {@code sps_temporal_mvp_enabled_flag}.
     */
    public boolean temporalMvpEnabled;

    /**
     * {@code strong_intra_smoothing_enabled_flag}.
     */
    public boolean strongIntraSmoothing;

    /**
     * Whether the VUI carried a video signal type.
     */
    public boolean videoSignalTypePresent;

    /**
     * {@code video_full_range_flag}.
     */
    public boolean fullRange;

    /**
     * {@code colour_primaries}, 2 (unspecified) when absent.
     */
    public int colorPrimaries = 2;

    /**
     * {@code transfer_characteristics}, 2 (unspecified) when absent.
     */
    public int transferCharacteristics = 2;

    /**
     * {@code matrix_coeffs}, 2 (unspecified) when absent.
     */
    public int matrixCoefficients = 2;

    /**
     * {@code chroma_sample_loc_type_top_field}, or -1 when absent.
     */
    public int chromaSampleLocType = -1;

    /**
     * {@code vui_timing_info_present_flag}.
     */
    public boolean timingInfoPresent;

    /**
     * {@code vui_num_units_in_tick}.
     */
    public long numUnitsInTick;

    /**
     * {@code vui_time_scale}.
     */
    public long timeScale;

    /**
     * {@code transform_skip_rotation_enabled_flag}.
     */
    public boolean transformSkipRotation;

    /**
     * {@code transform_skip_context_enabled_flag}.
     */
    public boolean transformSkipContext;

    /**
     * {@code implicit_rdpcm_enabled_flag}.
     */
    public boolean implicitRdpcm;

    /**
     * {@code explicit_rdpcm_enabled_flag}.
     */
    public boolean explicitRdpcm;

    /**
     * The sample aspect ratio width of the VUI, 0 when unspecified.
     */
    public int sarWidth;

    /**
     * The sample aspect ratio height of the VUI, 0 when unspecified.
     */
    public int sarHeight;

    /**
     * {@code field_seq_flag}: whether every picture is a field.
     */
    public boolean fieldSeq;

    /**
     * {@code frame_field_info_present_flag}: whether picture timing SEI messages start with
     * {@code pic_struct}.
     */
    public boolean frameFieldInfoPresent;

    /**
     * The left offset of the VUI default display window inside the conformance window, in luma
     * samples, 0 when absent or when an offset exceeds 65535 chroma samples (the four offsets are
     * then all 0).
     */
    public int displayLeft;

    /**
     * The right offset of the default display window, in luma samples.
     */
    public int displayRight;

    /**
     * The top offset of the default display window, in luma samples.
     */
    public int displayTop;

    /**
     * The bottom offset of the default display window, in luma samples.
     */
    public int displayBottom;

    /**
     * {@code extended_precision_processing_flag}.
     */
    public boolean extendedPrecision;

    /**
     * {@code intra_smoothing_disabled_flag}.
     */
    public boolean intraSmoothingDisabled;

    /**
     * {@code high_precision_offsets_enabled_flag}.
     */
    public boolean highPrecisionOffsets;

    /**
     * {@code persistent_rice_adaptation_enabled_flag}.
     */
    public boolean persistentRice;

    /**
     * {@code cabac_bypass_alignment_enabled_flag}.
     */
    public boolean bypassAlignment;

    /**
     * {@code log2TransformRange} of luma (equation 8-300): 15, or {@code Max(15, BitDepthY + 6)}
     * with extended precision processing.
     */
    public int log2TransformRangeLuma = 15;

    /**
     * {@code log2TransformRange} of chroma (equation 8-304): 15, or {@code Max(15, BitDepthC + 6)}
     * with extended precision processing.
     */
    public int log2TransformRangeChroma = 15;

    /**
     * Whether the set carries a 3D extension, which this decoder does not parse.
     */
    public boolean extension3d;

    /**
     * {@code sps_curr_pic_ref_enabled_flag}.
     */
    public boolean currPicRefEnabled;

    /**
     * {@code palette_mode_enabled_flag}.
     */
    public boolean paletteModeEnabled;

    /**
     * {@code palette_max_size}.
     */
    public int paletteMaxSize;

    /**
     * {@code PaletteMaxPredictorSize}: {@code palette_max_size + delta_palette_max_predictor_size}.
     */
    public int paletteMaxPredictorSize;

    /**
     * {@code sps_palette_predictor_initializer}, indexed {@code [comp][i]}, or {@code null} when
     * {@code sps_palette_predictor_initializers_present_flag} is clear.
     */
    public int[][] paletteInitializers;

    /**
     * {@code motion_vector_resolution_control_idc}: 0 for quarter-sample motion vectors, 1 for
     * integer ones, 2 for a choice per slice.
     */
    public int mvResolutionControl;

    /**
     * {@code intra_boundary_filtering_disabled_flag}.
     */
    public boolean intraBoundaryFilteringDisabled;

    /**
     * {@code CtbSizeY}.
     */
    public int ctbSize;

    /**
     * {@code PicWidthInCtbsY}.
     */
    public int widthInCtbs;

    /**
     * {@code PicHeightInCtbsY}.
     */
    public int heightInCtbs;

    /**
     * {@code PicSizeInCtbsY}.
     */
    public int sizeInCtbs;

    /**
     * Constructs an empty set; {@link #parse(H264BitReader)} fills it.
     */
    private HevcSps() {
    }

    /**
     * {@return the bit depth of the sequence's pictures: {@link #bitDepthLuma} for monochrome
     * sequences, whose chroma bit depth describes no samples, and the larger of the two depths
     * otherwise}
     */
    public int pictureBitDepth() {
        return chromaFormatIdc == 0 ? bitDepthLuma : Math.max(bitDepthLuma, bitDepthChroma);
    }

    /**
     * {@return whether the luma and chroma samples of the sequence's pictures have different bit
     * depths; never for a monochrome sequence}
     */
    public boolean mixedBitDepth() {
        return chromaFormatIdc != 0 && bitDepthLuma != bitDepthChroma;
    }

    /**
     * Parses a sequence parameter set RBSP that starts after the NAL unit header.
     *
     * @param reader the reader positioned after the NAL unit header
     * @return the sequence parameter set
     * @throws MediaException.Malformed if a field is out of range or the payload is truncated
     */
    public static HevcSps parse(H264BitReader reader) {
        var sps = new HevcSps();
        reader.skipBits(4);
        sps.maxSubLayers = reader.readBits(3) + 1;
        if (sps.maxSubLayers > 7) {
            throw new MediaException.Malformed("sps_max_sub_layers_minus1 out of range");
        }
        reader.readBit();
        parseProfileTierLevel(reader, sps.maxSubLayers - 1, sps);
        sps.id = reader.readUe(15, "sps_seq_parameter_set_id");
        sps.chromaFormatIdc = reader.readUe(3, "chroma_format_idc");
        if (sps.chromaFormatIdc == 3) {
            sps.separateColorPlane = reader.readFlag();
        }
        sps.chromaArrayType = sps.separateColorPlane ? 0 : sps.chromaFormatIdc;
        sps.chromaShiftX = sps.chromaFormatIdc == 1 || sps.chromaFormatIdc == 2 ? 1 : 0;
        sps.chromaShiftY = sps.chromaFormatIdc == 1 ? 1 : 0;
        sps.width = reader.readUe(65535, "pic_width_in_luma_samples");
        sps.height = reader.readUe(65535, "pic_height_in_luma_samples");
        if (sps.width == 0 || sps.height == 0) {
            throw new MediaException.Malformed("zero picture size");
        }
        if (reader.readFlag()) {
            var unitX = sps.chromaArrayType == 0 ? 1 : 1 << sps.chromaShiftX;
            var unitY = sps.chromaArrayType == 0 ? 1 : 1 << sps.chromaShiftY;
            sps.cropLeft = reader.readUe(65535, "conf_win_left_offset") * unitX;
            sps.cropRight = reader.readUe(65535, "conf_win_right_offset") * unitX;
            sps.cropTop = reader.readUe(65535, "conf_win_top_offset") * unitY;
            sps.cropBottom = reader.readUe(65535, "conf_win_bottom_offset") * unitY;
            if (sps.cropLeft + sps.cropRight >= sps.width || sps.cropTop + sps.cropBottom >= sps.height) {
                throw new MediaException.Malformed("conformance window leaves no picture");
            }
        }
        sps.bitDepthLuma = reader.readUe(8, "bit_depth_luma_minus8") + 8;
        sps.bitDepthChroma = reader.readUe(8, "bit_depth_chroma_minus8") + 8;
        sps.log2MaxPocLsb = reader.readUe(12, "log2_max_pic_order_cnt_lsb_minus4") + 4;
        var orderingInfo = reader.readFlag();
        for (var i = orderingInfo ? 0 : sps.maxSubLayers - 1; i < sps.maxSubLayers; i++) {
            sps.maxDecPicBuffering[i] = reader.readUe(15, "sps_max_dec_pic_buffering_minus1") + 1;
            sps.maxNumReorder[i] = reader.readUe(sps.maxDecPicBuffering[i] - 1, "sps_max_num_reorder_pics");
            sps.maxLatencyIncreasePlus1[i] = reader.readUe();
        }
        if (!orderingInfo) {
            for (var i = 0; i < sps.maxSubLayers - 1; i++) {
                sps.maxDecPicBuffering[i] = sps.maxDecPicBuffering[sps.maxSubLayers - 1];
                sps.maxNumReorder[i] = sps.maxNumReorder[sps.maxSubLayers - 1];
                sps.maxLatencyIncreasePlus1[i] = sps.maxLatencyIncreasePlus1[sps.maxSubLayers - 1];
            }
        }
        sps.log2MinCbSize = reader.readUe(3, "log2_min_luma_coding_block_size_minus3") + 3;
        sps.log2CtbSize = sps.log2MinCbSize + reader.readUe(6 - sps.log2MinCbSize, "log2_diff_max_min_luma_coding_block_size");
        if (sps.log2CtbSize < 4) {
            throw new MediaException.Malformed("coding tree block smaller than 16x16");
        }
        sps.log2MinTbSize = reader.readUe(sps.log2MinCbSize - 3, "log2_min_luma_transform_block_size_minus2") + 2;
        sps.log2MaxTbSize = sps.log2MinTbSize + reader.readUe(Math.min(5, sps.log2CtbSize) - sps.log2MinTbSize,
                "log2_diff_max_min_luma_transform_block_size");
        sps.maxTransformHierarchyDepthInter = reader.readUe(sps.log2CtbSize - sps.log2MinTbSize, "max_transform_hierarchy_depth_inter");
        sps.maxTransformHierarchyDepthIntra = reader.readUe(sps.log2CtbSize - sps.log2MinTbSize, "max_transform_hierarchy_depth_intra");
        sps.scalingListEnabled = reader.readFlag();
        if (sps.scalingListEnabled) {
            sps.scalingList = reader.readFlag() ? ScalingList.parse(reader) : ScalingList.defaults();
        }
        sps.ampEnabled = reader.readFlag();
        sps.saoEnabled = reader.readFlag();
        sps.pcmEnabled = reader.readFlag();
        if (sps.pcmEnabled) {
            sps.pcmBitDepthLuma = reader.readBits(4) + 1;
            sps.pcmBitDepthChroma = reader.readBits(4) + 1;
            if (sps.pcmBitDepthLuma > sps.bitDepthLuma || sps.pcmBitDepthChroma > sps.bitDepthChroma) {
                throw new MediaException.Malformed("PCM bit depth above the sample bit depth");
            }
            sps.log2MinPcmCbSize = reader.readUe(2, "log2_min_pcm_luma_coding_block_size_minus3") + 3;
            sps.log2MaxPcmCbSize = sps.log2MinPcmCbSize + reader.readUe(5 - sps.log2MinPcmCbSize,
                    "log2_diff_max_min_pcm_luma_coding_block_size");
            sps.pcmLoopFilterDisabled = reader.readFlag();
        }
        var numSets = reader.readUe(64, "num_short_term_ref_pic_sets");
        sps.shortTermRps = new ShortTermRps[numSets];
        for (var i = 0; i < numSets; i++) {
            sps.shortTermRps[i] = ShortTermRps.parse(reader, i, sps.shortTermRps, numSets, ShortTermRps.MAX_PICTURES);
        }
        sps.longTermRefsPresent = reader.readFlag();
        if (sps.longTermRefsPresent) {
            sps.numLongTermRefPicsSps = reader.readUe(32, "num_long_term_ref_pics_sps");
            for (var i = 0; i < sps.numLongTermRefPicsSps; i++) {
                sps.ltRefPicPocLsbSps[i] = reader.readBits(sps.log2MaxPocLsb);
                sps.usedByCurrPicLtSps[i] = reader.readFlag();
            }
        }
        sps.temporalMvpEnabled = reader.readFlag();
        sps.strongIntraSmoothing = reader.readFlag();
        if (reader.readFlag()) {
            parseVui(reader, sps);
        }
        if (reader.readFlag()) {
            var rangeExtension = reader.readFlag();
            var multilayerExtension = reader.readFlag();
            var extension3d = reader.readFlag();
            var sccExtension = reader.readFlag();
            reader.readBits(4);
            if (rangeExtension) {
                sps.transformSkipRotation = reader.readFlag();
                sps.transformSkipContext = reader.readFlag();
                sps.implicitRdpcm = reader.readFlag();
                sps.explicitRdpcm = reader.readFlag();
                sps.extendedPrecision = reader.readFlag();
                sps.intraSmoothingDisabled = reader.readFlag();
                sps.highPrecisionOffsets = reader.readFlag();
                sps.persistentRice = reader.readFlag();
                sps.bypassAlignment = reader.readFlag();
            }
            if (multilayerExtension) {
                reader.readBit();
            }
            sps.extension3d = extension3d;
            if (sccExtension && !extension3d) {
                parseSccExtension(reader, sps);
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set");
        }
        sps.derive();
        return sps;
    }

    /**
     * Derives the picture size variables in coding tree and minimum coding blocks.
     *
     * @throws MediaException.Malformed if the picture size is not a multiple of the minimum coding
     * block size
     */
    private void derive() {
        ctbSize = 1 << log2CtbSize;
        var minCb = 1 << log2MinCbSize;
        if (width % minCb != 0 || height % minCb != 0) {
            throw new MediaException.Malformed("picture size not a multiple of the minimum coding block size");
        }
        widthInCtbs = (width + ctbSize - 1) >> log2CtbSize;
        heightInCtbs = (height + ctbSize - 1) >> log2CtbSize;
        sizeInCtbs = widthInCtbs * heightInCtbs;
        if (separateColorPlane) {
            // every plane is decoded as a monochrome picture, whose processes use BitDepthY only
            bitDepthChroma = bitDepthLuma;
        }
        log2TransformRangeLuma = extendedPrecision ? Math.max(15, bitDepthLuma + 6) : 15;
        log2TransformRangeChroma = extendedPrecision ? Math.max(15, bitDepthChroma + 6) : 15;
    }

    /**
     * Parses a {@code sps_scc_extension()} structure (ITU-T H.265 7.3.2.2.3).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param sps the sequence parameter set being parsed, with its chroma format and bit depths known
     * @throws MediaException.Malformed if a field is out of range
     */
    private static void parseSccExtension(H264BitReader reader, HevcSps sps) {
        sps.currPicRefEnabled = reader.readFlag();
        sps.paletteModeEnabled = reader.readFlag();
        if (sps.paletteModeEnabled) {
            sps.paletteMaxSize = reader.readUe(64, "palette_max_size");
            sps.paletteMaxPredictorSize = sps.paletteMaxSize
                    + reader.readUe(128 - sps.paletteMaxSize, "delta_palette_max_predictor_size");
            if (reader.readFlag()) {
                var count = reader.readUe(Math.max(0, sps.paletteMaxPredictorSize - 1),
                        "sps_num_palette_predictor_initializers_minus1") + 1;
                if (count > sps.paletteMaxPredictorSize) {
                    throw new MediaException.Malformed("more palette predictor initializers than predictor entries");
                }
                var components = sps.chromaFormatIdc == 0 ? 1 : 3;
                sps.paletteInitializers = new int[components][count];
                for (var c = 0; c < components; c++) {
                    var bits = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
                    for (var i = 0; i < count; i++) {
                        sps.paletteInitializers[c][i] = reader.readBits(bits);
                    }
                }
            }
        }
        sps.mvResolutionControl = reader.readBits(2);
        if (sps.mvResolutionControl == 3) {
            throw new MediaException.Malformed("motion_vector_resolution_control_idc is 3");
        }
        sps.intraBoundaryFilteringDisabled = reader.readFlag();
    }

    /**
     * Returns whether another sequence parameter set describes pictures of the same size, format
     * and block structure, so that pictures of one can reference pictures of the other.
     *
     * @param other the other sequence parameter set
     * @return {@code true} when the layouts match
     */
    public boolean sameLayout(HevcSps other) {
        return width == other.width && height == other.height && chromaFormatIdc == other.chromaFormatIdc
                && separateColorPlane == other.separateColorPlane
                && bitDepthLuma == other.bitDepthLuma && bitDepthChroma == other.bitDepthChroma
                && log2CtbSize == other.log2CtbSize && log2MinCbSize == other.log2MinCbSize
                && log2MinTbSize == other.log2MinTbSize;
    }

    /**
     * Returns the name of the first feature of this sequence that the decoder does not implement,
     * or {@code null} when it implements all of them.
     *
     * @return the feature name for {@link MediaException.Unsupported}, or {@code null}
     */
    public String unsupportedFeature() {
        if (extension3d) {
            // TODO: the 3D-HEVC extension of a base layer sequence parameter set (Annex I)
            return "hevc 3D extension";
        }
        return null;
    }

    /**
     * Parses a {@code profile_tier_level(1, maxNumSubLayersMinus1)} structure and keeps the
     * general profile and level.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param maxNumSubLayersMinus1 the number of sub-layers minus 1
     * @param sps receives the general profile and level, or {@code null}
     */
    static void parseProfileTierLevel(H264BitReader reader, int maxNumSubLayersMinus1, HevcSps sps) {
        reader.readBits(3);
        var profileIdc = reader.readBits(5);
        reader.skip(32 + 48);
        var levelIdc = reader.readBits(8);
        if (sps != null) {
            sps.profileIdc = profileIdc;
            sps.levelIdc = levelIdc;
        }
        var profilePresent = new boolean[8];
        var levelPresent = new boolean[8];
        for (var i = 0; i < maxNumSubLayersMinus1; i++) {
            profilePresent[i] = reader.readFlag();
            levelPresent[i] = reader.readFlag();
        }
        if (maxNumSubLayersMinus1 > 0) {
            for (var i = maxNumSubLayersMinus1; i < 8; i++) {
                reader.readBits(2);
            }
        }
        for (var i = 0; i < maxNumSubLayersMinus1; i++) {
            if (profilePresent[i]) {
                reader.skip(88);
            }
            if (levelPresent[i]) {
                reader.readBits(8);
            }
        }
    }

    /**
     * Parses a {@code vui_parameters()} structure (ITU-T H.265 E.2.1), keeping the sample aspect
     * ratio, the color description, chroma location, {@code field_seq_flag},
     * {@code frame_field_info_present_flag}, the default display window and timing.
     *
     * @param reader the reader, positioned at the syntax structure
     * @param sps the sequence parameter set being parsed, with its chroma format known
     */
    private static void parseVui(H264BitReader reader, HevcSps sps) {
        if (reader.readFlag()) {
            var idc = reader.readBits(8);
            if (idc == 255) {
                sps.sarWidth = reader.readBits(16);
                sps.sarHeight = reader.readBits(16);
            } else {
                sps.sarWidth = H264Sps.aspectWidthOf(idc);
                sps.sarHeight = H264Sps.aspectHeightOf(idc);
            }
            if (sps.sarWidth == 0 || sps.sarHeight == 0) {
                sps.sarWidth = 0;
                sps.sarHeight = 0;
            }
        }
        if (reader.readFlag()) {
            reader.readBit();
        }
        sps.videoSignalTypePresent = reader.readFlag();
        if (sps.videoSignalTypePresent) {
            reader.readBits(3);
            sps.fullRange = reader.readFlag();
            if (reader.readFlag()) {
                sps.colorPrimaries = reader.readBits(8);
                sps.transferCharacteristics = reader.readBits(8);
                sps.matrixCoefficients = reader.readBits(8);
            }
        }
        if (reader.readFlag()) {
            sps.chromaSampleLocType = reader.readUe(5, "chroma_sample_loc_type_top_field");
            reader.readUe(5, "chroma_sample_loc_type_bottom_field");
        }
        reader.readBit();
        sps.fieldSeq = reader.readFlag();
        sps.frameFieldInfoPresent = reader.readFlag();
        if (reader.readFlag()) {
            var left = reader.readUe();
            var right = reader.readUe();
            var top = reader.readUe();
            var bottom = reader.readUe();
            // the window is only a display hint: one no picture could hold is dropped, not rejected
            if (left <= MAX_WINDOW_OFFSET && right <= MAX_WINDOW_OFFSET && top <= MAX_WINDOW_OFFSET
                    && bottom <= MAX_WINDOW_OFFSET) {
                var unitX = sps.chromaArrayType == 0 ? 1 : 1 << sps.chromaShiftX;
                var unitY = sps.chromaArrayType == 0 ? 1 : 1 << sps.chromaShiftY;
                sps.displayLeft = left * unitX;
                sps.displayRight = right * unitX;
                sps.displayTop = top * unitY;
                sps.displayBottom = bottom * unitY;
            }
        }
        sps.timingInfoPresent = reader.readFlag();
        if (sps.timingInfoPresent) {
            sps.numUnitsInTick = reader.readBits(32) & 0xFFFFFFFFL;
            sps.timeScale = reader.readBits(32) & 0xFFFFFFFFL;
            if (reader.readFlag()) {
                reader.readUe();
            }
            if (reader.readFlag()) {
                skipHrdParameters(reader, true, sps.maxSubLayers - 1);
            }
        }
        if (reader.readFlag()) {
            reader.readBits(3);
            reader.readUe();
            reader.readUe();
            reader.readUe();
            reader.readUe();
            reader.readUe();
        }
    }

    /**
     * Skips an {@code hrd_parameters(commonInfPresentFlag, maxNumSubLayersMinus1)} structure
     * (ITU-T H.265 E.2.2).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param commonInfPresent {@code commonInfPresentFlag}
     * @param maxNumSubLayersMinus1 the number of sub-layers minus 1
     * @throws MediaException.Malformed if a count is out of range
     */
    static void skipHrdParameters(H264BitReader reader, boolean commonInfPresent, int maxNumSubLayersMinus1) {
        var nal = false;
        var vcl = false;
        var subPic = false;
        if (commonInfPresent) {
            nal = reader.readFlag();
            vcl = reader.readFlag();
            if (nal || vcl) {
                subPic = reader.readFlag();
                if (subPic) {
                    reader.readBits(8);
                    reader.readBits(5);
                    reader.readBit();
                    reader.readBits(5);
                }
                reader.readBits(8);
                if (subPic) {
                    reader.readBits(4);
                }
                reader.readBits(15);
            }
        }
        for (var i = 0; i <= maxNumSubLayersMinus1; i++) {
            var fixedGeneral = reader.readFlag();
            var fixedWithinCvs = fixedGeneral || reader.readFlag();
            var lowDelay = false;
            if (fixedWithinCvs) {
                reader.readUe();
            } else {
                lowDelay = reader.readFlag();
            }
            var cpbCnt = 1;
            if (!lowDelay) {
                cpbCnt = reader.readUe(31, "cpb_cnt_minus1") + 1;
            }
            for (var k = 0; k < 2; k++) {
                if ((k == 0 && nal) || (k == 1 && vcl)) {
                    for (var j = 0; j < cpbCnt; j++) {
                        reader.readUe();
                        reader.readUe();
                        if (subPic) {
                            reader.readUe();
                            reader.readUe();
                        }
                        reader.readBit();
                    }
                }
            }
        }
    }
}
