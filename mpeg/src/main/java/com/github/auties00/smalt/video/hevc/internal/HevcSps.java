package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.h264.internal.H264Sps;

import java.util.Arrays;

/**
 * Holds a parsed sequence parameter set (ITU-T H.265 7.3.2.2) with the variables derived from it.
 *
 * <p>Parsing accepts every syntax element of the specification. The features this decoder does not
 * implement are reported by {@link #unsupportedFeature()} rather than rejected here, so that a
 * sequence parameter set that is never activated does not stop the stream.
 */
public final class HevcSps implements Cloneable {
    /**
     * The largest default display window offset kept, in chroma sample units, the bound of the
     * conformance window offsets.
     */
    private static final int MAX_WINDOW_OFFSET = 65535;

    /**
     * {@code MaxDpbSize} of the highest levels (A.4.2), the sub-layer ordering a set with
     * {@code MultiLayerExtSpsFlag} has until it is resolved.
     */
    private static final int MAX_DPB_SIZE = 16;

    /**
     * {@code sps_seq_parameter_set_id}.
     */
    public int id;

    /**
     * {@code sps_video_parameter_set_id}.
     */
    public int vpsId;

    /**
     * The {@code nuh_layer_id} of the NAL unit that carried the set.
     */
    public int nuhLayerId;

    /**
     * {@code MultiLayerExtSpsFlag}: the set leaves the picture format, the sub-layer ordering and
     * the profile to the video parameter set.
     */
    public boolean multiLayerExt;

    /**
     * {@code sps_rep_format_idx}, or -1 when {@code update_rep_format_flag} is clear.
     */
    public int spsRepFormatIdx = -1;

    /**
     * {@code sps_scaling_list_ref_layer_id}, or -1 when {@code sps_infer_scaling_list_flag} is
     * clear.
     */
    public int scalingListRefLayerId = -1;

    /**
     * {@code inter_view_mv_vert_constraint_flag}.
     */
    public boolean interViewMvVertConstraint;

    /**
     * The layer this set was resolved for by {@link #forLayer}, or -1 for a set as parsed.
     */
    public int resolvedLayer = -1;

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
    public int[] maxDecPicBuffering = new int[8];

    /**
     * {@code sps_max_num_reorder_pics} of each sub-layer.
     */
    public int[] maxNumReorder = new int[8];

    /**
     * {@code sps_max_latency_increase_plus1} of each sub-layer.
     */
    public int[] maxLatencyIncreasePlus1 = new int[8];

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
     * The VUI default display window offsets as coded, in chroma sample units (left, right, top,
     * bottom), all 0 when absent or when an offset exceeds 65535.
     */
    private final int[] displayOffsets = new int[4];

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
     * Whether the set carries a screen content coding extension ({@code sps_scc_extension()}).
     */
    public boolean sccExtension;

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
        return parse(reader, 0, null);
    }

    /**
     * Parses a sequence parameter set RBSP of a NAL unit of any layer (F.7.3.2.2.1).
     *
     * <p>A set with {@code MultiLayerExtSpsFlag} leaves its picture format to the video parameter
     * set: it is complete only once {@link #forLayer} resolves it for the layer that refers to it.
     *
     * @param reader the reader positioned after the NAL unit header
     * @param nuhLayerId the {@code nuh_layer_id} of the NAL unit
     * @param vpsTable the video parameter sets by identifier, which give a set with
     * {@code MultiLayerExtSpsFlag} the bit depths of its palette predictor initializers, or
     * {@code null}
     * @return the sequence parameter set
     * @throws MediaException.Malformed if a field is out of range or the payload is truncated
     */
    public static HevcSps parse(H264BitReader reader, int nuhLayerId, HevcVps[] vpsTable) {
        var sps = new HevcSps();
        sps.nuhLayerId = nuhLayerId;
        sps.vpsId = reader.readBits(4);
        var maxSubLayersMinus1 = reader.readBits(3);
        sps.multiLayerExt = nuhLayerId != 0 && maxSubLayersMinus1 == 7;
        var vps = vpsTable != null ? vpsTable[sps.vpsId] : null;
        if (sps.multiLayerExt) {
            sps.maxSubLayers = vps != null ? vps.maxSubLayers : 7;
        } else {
            sps.maxSubLayers = maxSubLayersMinus1 + 1;
            if (sps.maxSubLayers > 7) {
                throw new MediaException.Malformed("sps_max_sub_layers_minus1 out of range");
            }
            reader.readBit();
            var profile = parseProfileTierLevel(reader, true, sps.maxSubLayers - 1);
            sps.profileIdc = profile >> 8;
            sps.levelIdc = profile & 0xFF;
        }
        sps.id = reader.readUe(15, "sps_seq_parameter_set_id");
        if (sps.multiLayerExt) {
            if (reader.readFlag()) {
                sps.spsRepFormatIdx = reader.readBits(8);
            }
            var format = vps == null ? null : sps.spsRepFormatIdx >= 0
                    ? sps.spsRepFormatIdx < vps.repFormats.length ? vps.repFormats[sps.spsRepFormatIdx] : null
                    : vps.repFormatOf(nuhLayerId);
            if (format != null) {
                sps.applyRepFormat(format);
            } else {
                sps.chromaFormatIdc = 1;
                sps.chromaShiftX = 1;
                sps.chromaShiftY = 1;
                sps.chromaArrayType = 1;
                sps.bitDepthLuma = 8;
                sps.bitDepthChroma = 8;
            }
        } else {
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
        }
        sps.log2MaxPocLsb = reader.readUe(12, "log2_max_pic_order_cnt_lsb_minus4") + 4;
        if (!sps.multiLayerExt) {
            var orderingInfo = reader.readFlag();
            for (var i = orderingInfo ? 0 : sps.maxSubLayers - 1; i < sps.maxSubLayers; i++) {
                sps.maxDecPicBuffering[i] = reader.readUe(15, "sps_max_dec_pic_buffering_minus1") + 1;
                sps.maxNumReorder[i] = reader.readUe(15, "sps_max_num_reorder_pics");
                if (sps.maxNumReorder[i] >= sps.maxDecPicBuffering[i]) {
                    // a reordering the buffer size cannot hold: the buffer holds the reordered pictures
                    sps.maxDecPicBuffering[i] = sps.maxNumReorder[i] + 1;
                }
                sps.maxLatencyIncreasePlus1[i] = reader.readUe();
            }
            if (!orderingInfo) {
                for (var i = 0; i < sps.maxSubLayers - 1; i++) {
                    sps.maxDecPicBuffering[i] = sps.maxDecPicBuffering[sps.maxSubLayers - 1];
                    sps.maxNumReorder[i] = sps.maxNumReorder[sps.maxSubLayers - 1];
                    sps.maxLatencyIncreasePlus1[i] = sps.maxLatencyIncreasePlus1[sps.maxSubLayers - 1];
                }
            }
        } else {
            Arrays.fill(sps.maxDecPicBuffering, MAX_DPB_SIZE);
            Arrays.fill(sps.maxNumReorder, MAX_DPB_SIZE - 1);
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
            if (sps.multiLayerExt && reader.readFlag()) {
                sps.scalingListRefLayerId = reader.readBits(6);
                // the active SPS of the reference layer supplies the lists when the set is resolved
                sps.scalingList = ScalingList.defaults();
            } else {
                sps.scalingList = reader.readFlag() ? ScalingList.parse(reader) : ScalingList.defaults();
            }
        }
        sps.ampEnabled = reader.readFlag();
        sps.saoEnabled = reader.readFlag();
        sps.pcmEnabled = reader.readFlag();
        if (sps.pcmEnabled) {
            sps.pcmBitDepthLuma = reader.readBits(4) + 1;
            sps.pcmBitDepthChroma = reader.readBits(4) + 1;
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
                sps.interViewMvVertConstraint = reader.readFlag();
            }
            sps.extension3d = extension3d;
            // the multiview, scalable and 3D profiles have no screen content coding extension: their sets may
            // carry the bit of a later version's extension data there, which is ignored
            var multiLayerProfile = sps.multiLayerExt || sps.profileIdc == 6 || sps.profileIdc == 7
                    || sps.profileIdc == 8 || sps.profileIdc == 10;
            if (sccExtension && !extension3d && !multiLayerProfile) {
                sps.sccExtension = true;
                parseSccExtension(reader, sps);
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set");
        }
        if (sps.width > 0) {
            sps.derive();
        }
        return sps;
    }

    /**
     * Takes the picture format of a representation format: the chroma format, the picture size,
     * the bit depths and the conformance window.
     *
     * @param format the representation format
     */
    private void applyRepFormat(HevcVps.RepFormat format) {
        chromaFormatIdc = format.chromaFormatIdc();
        separateColorPlane = format.separateColorPlane();
        chromaArrayType = separateColorPlane ? 0 : chromaFormatIdc;
        chromaShiftX = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 1 : 0;
        chromaShiftY = chromaFormatIdc == 1 ? 1 : 0;
        width = format.width();
        height = format.height();
        bitDepthLuma = format.bitDepthLuma();
        bitDepthChroma = format.bitDepthChroma();
        cropLeft = format.cropLeft();
        cropRight = format.cropRight();
        cropTop = format.cropTop();
        cropBottom = format.cropBottom();
    }

    /**
     * Returns this set as it applies to a layer of a multi-layer stream (F.7.4.3.2.1): for a layer
     * above the base layer that has reference layers, the picture format of the representation
     * format the video parameter set assigns to the layer (or the one {@code sps_rep_format_idx}
     * names), and, for a set with {@code MultiLayerExtSpsFlag}, the sub-layer ordering of the
     * target output layer set and the scaling lists of the reference layer the set names.
     *
     * @param vps the active video parameter set
     * @param layerId the {@code nuh_layer_id} of the layer referring to the set
     * @param olsIdx the target output layer set index
     * @param activeSps the active sequence parameter set of each layer, by layer identifier
     * @return this set when it applies unchanged, or a resolved copy, the same one for the same
     * arguments
     * @throws MediaException.Malformed if the representation format is missing or does not fit
     * the coding block sizes
     */
    public HevcSps forLayer(HevcVps vps, int layerId, int olsIdx, HevcSps[] activeSps) {
        if (layerId == 0 || vps == null || !vps.extension || vps.layerIndex(layerId) < 0) {
            if (multiLayerExt || width == 0) {
                throw new MediaException.Malformed("multi-layer sequence parameter set without its video parameter set");
            }
            return this;
        }
        var independent = vps.idDirectRefLayer[layerId].length == 0;
        if (independent && !multiLayerExt) {
            return this;
        }
        var cached = resolved != null ? resolved[layerId] : null;
        HevcSps reference = scalingListRefLayerId >= 0 ? activeSps[scalingListRefLayerId] : null;
        if (cached != null && cached.resolvedVps == vps && cached.resolvedOls == olsIdx
                && cached.resolvedScalingSource == reference) {
            return cached;
        }
        HevcVps.RepFormat format;
        if (spsRepFormatIdx >= 0) {
            if (spsRepFormatIdx >= vps.repFormats.length) {
                throw new MediaException.Malformed("sps_rep_format_idx out of range");
            }
            format = vps.repFormats[spsRepFormatIdx];
        } else {
            format = vps.repFormatOf(layerId);
        }
        if (format == null) {
            throw new MediaException.Malformed("layer without a representation format");
        }
        var copy = copy();
        copy.applyRepFormat(format);
        copy.resolvedLayer = layerId;
        copy.resolvedVps = vps;
        copy.resolvedOls = olsIdx;
        copy.resolvedScalingSource = reference;
        if (multiLayerExt) {
            copy.maxSubLayers = vps.maxSubLayers;
            var lsIdx = vps.olsIdxToLsIdx[Math.min(olsIdx, vps.numOutputLayerSets() - 1)];
            var list = vps.layerSetLayerIdList[lsIdx];
            var buffering = vps.maxDecPicBuffering[Math.min(olsIdx, vps.numOutputLayerSets() - 1)];
            for (var k = 0; k < list.length; k++) {
                if (list[k] == layerId && buffering != null) {
                    for (var j = 0; j < 8; j++) {
                        var source = Math.min(j, buffering[k].length - 1);
                        copy.maxDecPicBuffering[j] = buffering[k][source];
                    }
                }
            }
            if (reference != null && reference.scalingList != null) {
                copy.scalingList = reference.scalingList;
            }
        }
        copy.derive();
        if (resolved == null) {
            resolved = new HevcSps[64];
        }
        resolved[layerId] = copy;
        return copy;
    }

    /**
     * Returns a set describing the pictures of a base layer coded outside the stream: the format
     * of its representation format, intra pictures of 16x16 coding tree blocks with no other tool.
     *
     * @param format the representation format of the base layer
     * @return the set
     * @throws MediaException.Malformed if the picture size is not a multiple of 8
     */
    public static HevcSps external(HevcVps.RepFormat format) {
        var sps = new HevcSps();
        sps.applyRepFormat(format);
        sps.maxSubLayers = 1;
        sps.maxDecPicBuffering[0] = 1;
        sps.log2MaxPocLsb = 16;
        sps.log2MinCbSize = 3;
        sps.log2CtbSize = 4;
        sps.log2MinTbSize = 2;
        sps.log2MaxTbSize = 4;
        sps.derive();
        return sps;
    }

    /**
     * The copies {@link #forLayer} resolved, by layer identifier, or {@code null}.
     */
    private HevcSps[] resolved;

    /**
     * The video parameter set this copy was resolved with.
     */
    private HevcVps resolvedVps;

    /**
     * The output layer set this copy was resolved for.
     */
    private int resolvedOls;

    /**
     * The sequence parameter set whose scaling lists this copy took, or {@code null}.
     */
    private HevcSps resolvedScalingSource;

    /**
     * Returns a copy of this set that shares its immutable parts (the reference picture sets, the
     * scaling lists and the palette predictor initializers).
     *
     * @return the copy
     */
    private HevcSps copy() {
        try {
            var copy = (HevcSps) super.clone();
            copy.resolved = null;
            copy.maxDecPicBuffering = maxDecPicBuffering.clone();
            copy.maxNumReorder = maxNumReorder.clone();
            copy.maxLatencyIncreasePlus1 = maxLatencyIncreasePlus1.clone();
            return copy;
        } catch (CloneNotSupportedException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Derives the picture size variables in coding tree and minimum coding blocks.
     *
     * @throws MediaException.Malformed if the picture size is not a multiple of the minimum coding
     * block size
     */
    private void derive() {
        if (pcmEnabled && (pcmBitDepthLuma > bitDepthLuma || pcmBitDepthChroma > bitDepthChroma)) {
            throw new MediaException.Malformed("PCM bit depth above the sample bit depth");
        }
        var unitX = chromaArrayType == 0 ? 1 : 1 << chromaShiftX;
        var unitY = chromaArrayType == 0 ? 1 : 1 << chromaShiftY;
        displayLeft = displayOffsets[0] * unitX;
        displayRight = displayOffsets[1] * unitX;
        displayTop = displayOffsets[2] * unitY;
        displayBottom = displayOffsets[3] * unitY;
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
     * Parses a {@code profile_tier_level(profilePresentFlag, maxNumSubLayersMinus1)} structure
     * (ITU-T H.265 7.3.3).
     *
     * @param reader the reader, positioned at the syntax structure
     * @param profilePresent {@code profilePresentFlag}: whether the general profile and tier are
     * present
     * @param maxNumSubLayersMinus1 the number of sub-layers minus 1
     * @return {@code (general_profile_idc << 8) | general_level_idc}, the profile -1 when absent
     */
    static int parseProfileTierLevel(H264BitReader reader, boolean profilePresent, int maxNumSubLayersMinus1) {
        var profileIdc = -1;
        if (profilePresent) {
            reader.readBits(3);
            profileIdc = reader.readBits(5);
            reader.skip(32 + 48);
        }
        var levelIdc = reader.readBits(8);
        var subProfilePresent = new boolean[8];
        var subLevelPresent = new boolean[8];
        for (var i = 0; i < maxNumSubLayersMinus1; i++) {
            subProfilePresent[i] = reader.readFlag();
            subLevelPresent[i] = reader.readFlag();
        }
        if (maxNumSubLayersMinus1 > 0) {
            for (var i = maxNumSubLayersMinus1; i < 8; i++) {
                reader.readBits(2);
            }
        }
        for (var i = 0; i < maxNumSubLayersMinus1; i++) {
            if (subProfilePresent[i]) {
                reader.skip(88);
            }
            if (subLevelPresent[i]) {
                reader.readBits(8);
            }
        }
        return (profileIdc << 8) | levelIdc;
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
                sps.displayOffsets[0] = left;
                sps.displayOffsets[1] = right;
                sps.displayOffsets[2] = top;
                sps.displayOffsets[3] = bottom;
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
