package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Arrays;

/**
 * Holds a parsed H.264 sequence parameter set (ITU-T H.264 7.3.2.1.1), including its VUI.
 *
 * <p>Instances are fully populated by {@link #parse(byte[], int, int)} and must not be modified
 * afterwards. Fields hold the syntax element values, with the derived variables the decoding
 * process uses most often precomputed.
 */
public final class H264Sps implements Cloneable {
    /**
     * The {@code profile_idc}.
     */
    public int profileIdc;

    /**
     * The byte holding {@code constraint_set0_flag} (bit 7) to {@code constraint_set5_flag}
     * (bit 2).
     */
    public int constraintFlags;

    /**
     * The {@code level_idc}.
     */
    public int levelIdc;

    /**
     * The {@code seq_parameter_set_id}, 0 to 31.
     */
    public int id;

    /**
     * The {@code chroma_format_idc}: 0 monochrome, 1 4:2:0, 2 4:2:2, 3 4:4:4.
     */
    public int chromaFormatIdc = 1;

    /**
     * The {@code separate_colour_plane_flag}.
     */
    public boolean separateColorPlane;

    /**
     * The luma sample bit depth, {@code 8 + bit_depth_luma_minus8}.
     */
    public int bitDepthLuma = 8;

    /**
     * The chroma sample bit depth, {@code 8 + bit_depth_chroma_minus8}.
     */
    public int bitDepthChroma = 8;

    /**
     * The {@code qpprime_y_zero_transform_bypass_flag}.
     */
    public boolean transformBypass;

    /**
     * The {@code seq_scaling_matrix_present_flag}.
     */
    public boolean scalingMatrixPresent;

    /**
     * The resolved 4x4 scaling lists in raster order.
     */
    public int[][] scaling4x4 = H264ScalingLists.flat4x4();

    /**
     * The resolved 8x8 scaling lists in raster order.
     */
    public int[][] scaling8x8 = H264ScalingLists.flat8x8();

    /**
     * The number of bits of {@code frame_num}, {@code log2_max_frame_num_minus4 + 4}.
     */
    public int log2MaxFrameNum;

    /**
     * The {@code pic_order_cnt_type}, 0 to 2.
     */
    public int pocType;

    /**
     * The number of bits of {@code pic_order_cnt_lsb}, {@code log2_max_pic_order_cnt_lsb_minus4 + 4}.
     */
    public int log2MaxPocLsb;

    /**
     * The {@code delta_pic_order_always_zero_flag}.
     */
    public boolean deltaPicOrderAlwaysZero;

    /**
     * The {@code offset_for_non_ref_pic}.
     */
    public int offsetForNonRefPic;

    /**
     * The {@code offset_for_top_to_bottom_field}.
     */
    public int offsetForTopToBottomField;

    /**
     * The {@code offset_for_ref_frame} values, one per frame of the POC cycle.
     */
    public int[] offsetForRefFrame = new int[0];

    /**
     * The {@code max_num_ref_frames}.
     */
    public int maxNumRefFrames;

    /**
     * The {@code gaps_in_frame_num_value_allowed_flag}.
     */
    public boolean gapsInFrameNumAllowed;

    /**
     * The picture width in macroblocks, {@code pic_width_in_mbs_minus1 + 1}.
     */
    public int widthInMbs;

    /**
     * The picture height in slice group map units, {@code pic_height_in_map_units_minus1 + 1}.
     */
    public int heightInMapUnits;

    /**
     * The frame height in macroblocks, {@code (2 - frame_mbs_only_flag) * heightInMapUnits}.
     */
    public int frameHeightInMbs;

    /**
     * The {@code frame_mbs_only_flag}.
     */
    public boolean frameMbsOnly;

    /**
     * The {@code mb_adaptive_frame_field_flag}.
     */
    public boolean mbAdaptiveFrameField;

    /**
     * The {@code direct_8x8_inference_flag}.
     */
    public boolean direct8x8Inference;

    /**
     * The number of luma samples cropped from the left edge.
     */
    public int cropLeft;

    /**
     * The number of luma samples cropped from the right edge.
     */
    public int cropRight;

    /**
     * The number of luma samples cropped from the top edge.
     */
    public int cropTop;

    /**
     * The number of luma samples cropped from the bottom edge.
     */
    public int cropBottom;

    /**
     * The {@code chroma_sample_loc_type_top_field} of the VUI, or {@code -1} when
     * {@code chroma_loc_info_present_flag} is 0.
     */
    public int chromaSampleLocType = -1;

    /**
     * Whether the VUI carried {@code colour_description_present_flag} equal to 1.
     */
    public boolean colorDescriptionPresent;

    /**
     * The {@code video_full_range_flag}, {@code false} when absent.
     */
    public boolean videoFullRange;

    /**
     * Whether the VUI carried a {@code video_signal_type_present_flag} equal to 1.
     */
    public boolean videoSignalTypePresent;

    /**
     * The {@code colour_primaries}, 2 (unspecified) when absent.
     */
    public int colorPrimaries = 2;

    /**
     * The {@code transfer_characteristics}, 2 (unspecified) when absent.
     */
    public int transferCharacteristics = 2;

    /**
     * The {@code matrix_coefficients}, 2 (unspecified) when absent.
     */
    public int matrixCoefficients = 2;

    /**
     * The sample aspect ratio width, 0 when unspecified.
     */
    public int sarWidth;

    /**
     * The sample aspect ratio height, 0 when unspecified.
     */
    public int sarHeight;

    /**
     * Whether the VUI carried timing information.
     */
    public boolean timingInfoPresent;

    /**
     * The {@code num_units_in_tick}.
     */
    public long numUnitsInTick;

    /**
     * The {@code time_scale}.
     */
    public long timeScale;

    /**
     * Whether the VUI carried NAL or VCL HRD parameters ({@code CpbDpbDelaysPresentFlag}), which
     * put the {@code cpb_removal_delay} and {@code dpb_output_delay} in picture timing SEI
     * messages.
     */
    public boolean cpbDpbDelaysPresent;

    /**
     * The length in bits of {@code cpb_removal_delay}, from 1 to 32, when
     * {@link #cpbDpbDelaysPresent} is set.
     */
    public int cpbRemovalDelayLength;

    /**
     * The length in bits of {@code dpb_output_delay}, from 1 to 32, when
     * {@link #cpbDpbDelaysPresent} is set.
     */
    public int dpbOutputDelayLength;

    /**
     * The VUI {@code pic_struct_present_flag}: whether picture timing SEI messages carry
     * {@code pic_struct}.
     */
    public boolean picStructPresent;

    /**
     * Whether the VUI carried a {@code bitstream_restriction_flag} equal to 1.
     */
    public boolean bitstreamRestriction;

    /**
     * The {@code max_num_reorder_frames}, meaningful when {@link #bitstreamRestriction} is set.
     */
    public int maxNumReorderFrames;

    /**
     * The {@code max_dec_frame_buffering}, meaningful when {@link #bitstreamRestriction} is set.
     */
    public int maxDecFrameBuffering;

    /**
     * The payload the set was parsed from, used to detect a changed set with the same id.
     */
    public byte[] raw;

    /**
     * The {@code chroma_format_idc} as coded, which decides the syntax of the picture parameter
     * sets that refer to this set even when {@link #chromaFormatIdc} is inferred to be 0, as for
     * auxiliary coded pictures.
     */
    public int codedChromaFormatIdc = 1;

    /**
     * Whether the set is a subset sequence parameter set, the sequence parameter set of the
     * scalable and multiview extensions.
     */
    public boolean subset;

    /**
     * The MVC extension of a subset set of a multiview profile, or {@code null}.
     */
    public H264MvcExtension mvc;

    /**
     * The SVC extension of a subset set of a scalable profile, or {@code null}.
     */
    public H264SvcExtension svc;

    /**
     * Constructs an empty set; used by {@link #parse(byte[], int, int)}.
     */
    private H264Sps() {
    }

    /**
     * Returns a copy of this set for the decoding of auxiliary coded pictures (ITU-T H.264
     * 7.4.2.1.2): monochrome, with the auxiliary bit depth as the luma bit depth.
     *
     * @param bitDepth the bit depth of the auxiliary pictures, 8 to 12
     * @return the copy
     */
    public H264Sps auxiliary(int bitDepth) {
        var copy = copy();
        copy.chromaFormatIdc = 0;
        copy.separateColorPlane = false;
        copy.bitDepthLuma = bitDepth;
        copy.bitDepthChroma = 8;
        return copy;
    }

    /**
     * Returns a shallow copy of this set: the arrays are shared, which is safe because sets are
     * never modified after parsing.
     *
     * @return the copy
     */
    public H264Sps copy() {
        try {
            return (H264Sps) clone();
        } catch (CloneNotSupportedException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Parses a sequence parameter set.
     *
     * @param rbsp the unescaped payload, starting after the NAL header byte and followed by
     * {@link H264BitReader#PADDING} zero bytes
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @return the parsed set
     * @throws MediaException.Malformed if a field is out of its range or the payload is
     * truncated
     */
    public static H264Sps parse(byte[] rbsp, int offset, int length) {
        return parse(rbsp, offset, length, false);
    }

    /**
     * Parses a subset sequence parameter set (ITU-T H.264 7.3.2.1.3): the sequence parameter set
     * data followed by the extension of its profile, the MVC extension for the multiview profiles,
     * the SVC extension for the scalable profiles.
     *
     * @param rbsp the unescaped payload, starting after the NAL header byte and followed by
     * {@link H264BitReader#PADDING} zero bytes
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @return the parsed set, with {@link #subset} set
     * @throws MediaException.Malformed if a field is out of its range or the payload is
     * truncated
     */
    public static H264Sps parseSubset(byte[] rbsp, int offset, int length) {
        return parse(rbsp, offset, length, true);
    }

    /**
     * Parses a sequence parameter set or a subset sequence parameter set.
     *
     * @param rbsp the unescaped payload, followed by {@link H264BitReader#PADDING} zero bytes
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @param subset whether the payload is a subset sequence parameter set
     * @return the parsed set
     * @throws MediaException.Malformed if a field is out of its range or the payload is
     * truncated
     */
    private static H264Sps parse(byte[] rbsp, int offset, int length, boolean subset) {
        var reader = new H264BitReader();
        reader.init(rbsp, offset, length);
        var sps = new H264Sps();
        sps.raw = Arrays.copyOfRange(rbsp, offset, offset + length);
        sps.profileIdc = reader.readBits(8);
        sps.constraintFlags = reader.readBits(8);
        sps.levelIdc = reader.readBits(8);
        sps.id = reader.readUe();
        if (sps.id > 31) {
            throw new MediaException.Malformed("seq_parameter_set_id " + sps.id + " out of range");
        }
        if (hasChromaInfo(sps.profileIdc)) {
            sps.chromaFormatIdc = reader.readUe();
            if (sps.chromaFormatIdc > 3) {
                throw new MediaException.Malformed("chroma_format_idc " + sps.chromaFormatIdc + " out of range");
            }
            sps.codedChromaFormatIdc = sps.chromaFormatIdc;
            if (sps.chromaFormatIdc == 3) {
                sps.separateColorPlane = reader.readFlag();
            }
            sps.bitDepthLuma = 8 + reader.readUe();
            sps.bitDepthChroma = 8 + reader.readUe();
            if (sps.bitDepthLuma < 8 || sps.bitDepthLuma > 14 || sps.bitDepthChroma < 8 || sps.bitDepthChroma > 14) {
                throw new MediaException.Malformed("bit depth out of range");
            }
            sps.transformBypass = reader.readFlag();
            sps.scalingMatrixPresent = reader.readFlag();
            if (sps.scalingMatrixPresent) {
                H264ScalingLists.parse(reader, sps.chromaFormatIdc != 3 ? 8 : 12, null, null,
                        sps.scaling4x4, sps.scaling8x8);
            }
        }
        sps.log2MaxFrameNum = reader.readUe() + 4;
        if (sps.log2MaxFrameNum > 16) {
            throw new MediaException.Malformed("log2_max_frame_num out of range");
        }
        sps.pocType = reader.readUe();
        if (sps.pocType == 0) {
            sps.log2MaxPocLsb = reader.readUe() + 4;
            if (sps.log2MaxPocLsb > 16) {
                throw new MediaException.Malformed("log2_max_pic_order_cnt_lsb out of range");
            }
        } else if (sps.pocType == 1) {
            sps.deltaPicOrderAlwaysZero = reader.readFlag();
            sps.offsetForNonRefPic = reader.readSe();
            sps.offsetForTopToBottomField = reader.readSe();
            var cycle = reader.readUe();
            if (cycle > 255) {
                throw new MediaException.Malformed("num_ref_frames_in_pic_order_cnt_cycle out of range");
            }
            sps.offsetForRefFrame = new int[cycle];
            for (var i = 0; i < cycle; i++) {
                sps.offsetForRefFrame[i] = reader.readSe();
            }
        } else if (sps.pocType != 2) {
            throw new MediaException.Malformed("pic_order_cnt_type " + sps.pocType + " out of range");
        }
        sps.maxNumRefFrames = reader.readUe();
        if (sps.maxNumRefFrames > 16) {
            throw new MediaException.Malformed("max_num_ref_frames " + sps.maxNumRefFrames + " out of range");
        }
        sps.gapsInFrameNumAllowed = reader.readFlag();
        var widthMinus1 = reader.readUe();
        var heightMinus1 = reader.readUe();
        if (widthMinus1 > 4095 || heightMinus1 > 4095) {
            throw new MediaException.Malformed("picture size out of range");
        }
        sps.widthInMbs = widthMinus1 + 1;
        sps.heightInMapUnits = heightMinus1 + 1;
        sps.frameMbsOnly = reader.readFlag();
        if (!sps.frameMbsOnly) {
            sps.mbAdaptiveFrameField = reader.readFlag();
        }
        sps.frameHeightInMbs = (sps.frameMbsOnly ? 1 : 2) * sps.heightInMapUnits;
        sps.direct8x8Inference = reader.readFlag();
        if (reader.readFlag()) {
            var left = reader.readUe();
            var right = reader.readUe();
            var top = reader.readUe();
            var bottom = reader.readUe();
            var chromaArrayType = sps.chromaArrayType();
            var unitX = chromaArrayType == 0 ? 1 : (chromaArrayType == 3 ? 1 : 2);
            var unitY = (chromaArrayType == 1 ? 2 : 1) * (sps.frameMbsOnly ? 1 : 2);
            var width = sps.widthInMbs * 16L;
            var height = sps.frameHeightInMbs * 16L;
            var cropX = ((long) left + right) * unitX;
            var cropY = ((long) top + bottom) * unitY;
            if (cropX < width && cropY < height) {
                sps.cropLeft = left * unitX;
                sps.cropRight = right * unitX;
                sps.cropTop = top * unitY;
                sps.cropBottom = bottom * unitY;
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set");
        }
        if (subset) {
            sps.subset = true;
            if (reader.readFlag()) {
                parseVui(reader, sps);
                sps.timingInfoPresent = false;
                sps.cpbDpbDelaysPresent = false;
                sps.picStructPresent = false;
            }
            switch (sps.profileIdc) {
                case 83, 86 -> sps.svc = H264SvcExtension.parse(reader, sps.chromaArrayType());
                case 118, 128, 134 -> {
                    reader.readFlag();
                    sps.mvc = H264MvcExtension.parse(reader, sps.profileIdc, sps.frameMbsOnly);
                }
                case 135, 138 -> {
                    reader.readFlag();
                    sps.mvc = H264MvcExtension.parseMvcd(reader);
                }
                default -> {
                }
            }
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated subset sequence parameter set");
            }
            return sps;
        }
        if (reader.readFlag()) {
            try {
                parseVui(reader, sps);
            } catch (MediaException.Malformed ignored) {
                sps.bitstreamRestriction = false;
                sps.timingInfoPresent = false;
                sps.cpbDpbDelaysPresent = false;
                sps.picStructPresent = false;
            }
            if (reader.overrun()) {
                sps.bitstreamRestriction = false;
                sps.cpbDpbDelaysPresent = false;
                sps.picStructPresent = false;
                sps.timingInfoPresent = sps.timingInfoPresent && sps.timeScale > 0 && sps.numUnitsInTick > 0;
            }
        }
        return sps;
    }

    /**
     * Returns whether the profile carries the chroma format, bit depth and scaling matrix
     * syntax.
     *
     * @param profileIdc the {@code profile_idc}
     * @return {@code true} for the High family and the scalable and multiview profiles
     */
    private static boolean hasChromaInfo(int profileIdc) {
        return switch (profileIdc) {
            case 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135 -> true;
            default -> false;
        };
    }

    /**
     * Parses the VUI parameters (ITU-T H.264 E.1.1).
     *
     * @param reader the reader positioned at the start of {@code vui_parameters()}
     * @param sps the set being filled
     */
    private static void parseVui(H264BitReader reader, H264Sps sps) {
        if (reader.readFlag()) {
            var aspectRatioIdc = reader.readBits(8);
            if (aspectRatioIdc == 255) {
                sps.sarWidth = reader.readBits(16);
                sps.sarHeight = reader.readBits(16);
            } else if (aspectRatioIdc > 0 && aspectRatioIdc < SAR.length / 2 + 1) {
                sps.sarWidth = SAR[(aspectRatioIdc - 1) * 2];
                sps.sarHeight = SAR[(aspectRatioIdc - 1) * 2 + 1];
            }
        }
        if (reader.readFlag()) {
            reader.readFlag();
        }
        if (reader.readFlag()) {
            sps.videoSignalTypePresent = true;
            reader.readBits(3);
            sps.videoFullRange = reader.readFlag();
            if (reader.readFlag()) {
                sps.colorDescriptionPresent = true;
                sps.colorPrimaries = reader.readBits(8);
                sps.transferCharacteristics = reader.readBits(8);
                sps.matrixCoefficients = reader.readBits(8);
            }
        }
        if (reader.readFlag()) {
            sps.chromaSampleLocType = reader.readUe();
            reader.readUe();
        }
        if (reader.readFlag()) {
            sps.numUnitsInTick = reader.readBits(32) & 0xFFFFFFFFL;
            sps.timeScale = reader.readBits(32) & 0xFFFFFFFFL;
            reader.readFlag();
            sps.timingInfoPresent = sps.numUnitsInTick > 0 && sps.timeScale > 0;
        }
        var nalHrd = reader.readFlag();
        if (nalHrd) {
            parseHrd(reader, sps);
        }
        var vclHrd = reader.readFlag();
        if (vclHrd) {
            parseHrd(reader, sps);
        }
        if (nalHrd || vclHrd) {
            sps.cpbDpbDelaysPresent = true;
            reader.readFlag();
        }
        sps.picStructPresent = reader.readFlag();
        if (reader.readFlag()) {
            reader.readFlag();
            reader.readUe();
            reader.readUe();
            reader.readUe();
            reader.readUe();
            var reorder = reader.readUe();
            var buffering = reader.readUe();
            if (!reader.overrun() && reorder <= 16 && buffering <= 16) {
                sps.bitstreamRestriction = true;
                sps.maxNumReorderFrames = reorder;
                sps.maxDecFrameBuffering = buffering;
            }
        }
    }

    /**
     * Parses an {@code hrd_parameters()} structure (ITU-T H.264 E.1.2), keeping the lengths of the
     * delays that picture timing SEI messages carry.
     *
     * @param reader the reader positioned at the start of the structure
     * @param sps the set being filled
     */
    private static void parseHrd(H264BitReader reader, H264Sps sps) {
        var count = reader.readUe() + 1;
        if (count > 32) {
            throw new MediaException.Malformed("cpb_cnt_minus1 out of range");
        }
        reader.readBits(4);
        reader.readBits(4);
        for (var i = 0; i < count; i++) {
            reader.readUe();
            reader.readUe();
            reader.readFlag();
        }
        reader.readBits(5);
        sps.cpbRemovalDelayLength = reader.readBits(5) + 1;
        sps.dpbOutputDelayLength = reader.readBits(5) + 1;
        reader.readBits(5);
    }

    /**
     * The sample aspect ratios of {@code aspect_ratio_idc} 1 to 16 as width, height pairs
     * (ITU-T H.264 Table E-1).
     */
    private static final int[] SAR = {
            1, 1, 12, 11, 10, 11, 16, 11, 40, 33, 24, 11, 20, 11, 32, 11, 80, 33, 18, 11, 15, 11, 64, 33,
            160, 99, 4, 3, 3, 2, 2, 1
    };

    /**
     * Returns the width term of the sample aspect ratio of an {@code aspect_ratio_idc} of H.264
     * Table E-1, which H.265 Table E.1 repeats.
     *
     * @param idc the {@code aspect_ratio_idc}
     * @return the width term, or 0 for 0 (unspecified), 255 ({@code Extended_SAR}) and the reserved values
     */
    public static int aspectWidthOf(int idc) {
        return idc > 0 && idc <= SAR.length / 2 ? SAR[(idc - 1) * 2] : 0;
    }

    /**
     * Returns the height term of the sample aspect ratio of an {@code aspect_ratio_idc}; see
     * {@link #aspectWidthOf(int)}.
     *
     * @param idc the {@code aspect_ratio_idc}
     * @return the height term, or 0 when {@link #aspectWidthOf(int)} is 0
     */
    public static int aspectHeightOf(int idc) {
        return idc > 0 && idc <= SAR.length / 2 ? SAR[(idc - 1) * 2 + 1] : 0;
    }

    /**
     * Returns {@code ChromaArrayType}: the chroma format, or 0 when color planes are coded
     * separately.
     *
     * @return 0 to 3
     */
    public int chromaArrayType() {
        return separateColorPlane ? 0 : chromaFormatIdc;
    }

    /**
     * Returns the coded frame width in luma samples.
     *
     * @return {@code widthInMbs * 16}
     */
    public int codedWidth() {
        return widthInMbs * 16;
    }

    /**
     * Returns the coded frame height in luma samples.
     *
     * @return {@code frameHeightInMbs * 16}
     */
    public int codedHeight() {
        return frameHeightInMbs * 16;
    }

    /**
     * Returns the display width after frame cropping.
     *
     * @return the cropped width in luma samples
     */
    public int croppedWidth() {
        return codedWidth() - cropLeft - cropRight;
    }

    /**
     * Returns the display height after frame cropping.
     *
     * @return the cropped height in luma samples
     */
    public int croppedHeight() {
        return codedHeight() - cropTop - cropBottom;
    }

    /**
     * Returns the number of frame buffers the decoded picture buffer needs: the
     * {@code max_dec_frame_buffering} of the VUI when present, otherwise {@code MaxDpbFrames} of
     * the level (ITU-T H.264 A.3.1), and never less than {@link #maxNumRefFrames}.
     *
     * @return the DPB size in frames, from 1 to 16
     */
    public int dpbFrames() {
        int size;
        if (bitstreamRestriction) {
            size = maxDecFrameBuffering;
        } else {
            var level = H264Levels.find(H264Levels.effectiveIdc(profileIdc, constraintFlags, levelIdc));
            var maxDpbMbs = (level != null ? level : H264Levels.HIGHEST).maxDpbMbs();
            size = maxDpbMbs / (widthInMbs * frameHeightInMbs);
        }
        return Math.clamp(Math.max(size, maxNumRefFrames), 1, 16);
    }

    /**
     * Returns the number of pictures that may precede a picture in decoding order and follow it
     * in output order: {@code max_num_reorder_frames} when signaled, 0 for
     * {@code pic_order_cnt_type} 2 (output order equals decoding order), and the DPB size
     * otherwise.
     *
     * @return the reorder depth
     */
    public int reorderDepth() {
        if (bitstreamRestriction) {
            return Math.min(maxNumReorderFrames, dpbFrames());
        }
        if (pocType == 2) {
            return 0;
        }
        return dpbFrames();
    }

    /**
     * Returns the color description of the pictures of this sequence.
     *
     * <p>Values signaled in the VUI are used as given. When the VUI leaves a component
     * unspecified, the default of the picture size applies: BT.709 for high-definition pictures
     * (wider than 1024 or taller than 576 luma samples) and BT.601 (SMPTE 170M) otherwise. The
     * range is full only when {@code video_full_range_flag} is set. The chroma siting is the
     * signaled {@code chroma_sample_loc_type_top_field}, or {@link ChromaSiting#UNSPECIFIED} when
     * the VUI does not carry it.
     *
     * @return the color description
     */
    public ColorInfo colorInfo() {
        var hd = croppedWidth() > 1024 || croppedHeight() > 576;
        var fallback = hd ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var matrix = fallback.matrix();
        var primaries = fallback.primaries();
        var transfer = fallback.transfer();
        if (colorDescriptionPresent) {
            var signaledMatrix = MatrixCoefficients.of(matrixCoefficients);
            if (signaledMatrix != MatrixCoefficients.UNSPECIFIED) {
                matrix = signaledMatrix;
            }
            var signaledPrimaries = ColorPrimaries.of(colorPrimaries);
            if (signaledPrimaries != ColorPrimaries.UNSPECIFIED) {
                primaries = signaledPrimaries;
            }
            var signaledTransfer = TransferCharacteristics.of(transferCharacteristics);
            if (signaledTransfer != TransferCharacteristics.UNSPECIFIED) {
                transfer = signaledTransfer;
            }
        }
        var info = new ColorInfo(matrix, videoFullRange ? ColorRange.FULL : ColorRange.LIMITED, primaries, transfer);
        return chromaSampleLocType < 0 ? info : info.withChromaSiting(ChromaSiting.of(chromaSampleLocType));
    }
}
