package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Arrays;

/**
 * Holds a parsed H.264 picture parameter set (ITU-T H.264 7.3.2.2).
 *
 * <p>The syntax of a picture parameter set depends on its sequence parameter set (the number of
 * scaling lists and their fall-back rules), so a set is parsed against the sequence parameter
 * set it refers to; {@link #sps} records which one.
 */
public final class H264Pps {
    /**
     * The {@code pic_parameter_set_id}, 0 to 255.
     */
    public int id;

    /**
     * The {@code seq_parameter_set_id} this set refers to.
     */
    public int spsId;

    /**
     * The sequence parameter set this set was parsed against.
     */
    public H264Sps sps;

    /**
     * The {@code entropy_coding_mode_flag}: {@code true} for CABAC.
     */
    public boolean cabac;

    /**
     * The {@code bottom_field_pic_order_in_frame_present_flag}.
     */
    public boolean bottomFieldPicOrderInFramePresent;

    /**
     * The number of slice groups, {@code num_slice_groups_minus1 + 1}.
     */
    public int numSliceGroups;

    /**
     * The {@code slice_group_map_type}.
     */
    public int sliceGroupMapType;

    /**
     * The {@code run_length_minus1} values plus one, for map type 0.
     */
    public int[] runLength;

    /**
     * The {@code top_left} values, for map type 2.
     */
    public int[] topLeft;

    /**
     * The {@code bottom_right} values, for map type 2.
     */
    public int[] bottomRight;

    /**
     * The {@code slice_group_change_direction_flag}, for map types 3 to 5.
     */
    public boolean sliceGroupChangeDirection;

    /**
     * The {@code SliceGroupChangeRate}, {@code slice_group_change_rate_minus1 + 1}.
     */
    public int sliceGroupChangeRate;

    /**
     * The {@code slice_group_id} of every map unit, for map type 6.
     */
    public int[] sliceGroupId;

    /**
     * The default number of active list 0 references, {@code num_ref_idx_l0_default_active_minus1 + 1}.
     */
    public int numRefIdxL0Default;

    /**
     * The default number of active list 1 references.
     */
    public int numRefIdxL1Default;

    /**
     * The {@code weighted_pred_flag}.
     */
    public boolean weightedPred;

    /**
     * The {@code weighted_bipred_idc}, 0 to 2.
     */
    public int weightedBipredIdc;

    /**
     * The initial luma quantization parameter, {@code 26 + pic_init_qp_minus26}.
     */
    public int initQp;

    /**
     * The {@code chroma_qp_index_offset} applied to Cb.
     */
    public int chromaQpIndexOffset;

    /**
     * The {@code second_chroma_qp_index_offset} applied to Cr; equal to
     * {@link #chromaQpIndexOffset} when absent.
     */
    public int secondChromaQpIndexOffset;

    /**
     * The {@code deblocking_filter_control_present_flag}.
     */
    public boolean deblockingFilterControlPresent;

    /**
     * The {@code constrained_intra_pred_flag}.
     */
    public boolean constrainedIntraPred;

    /**
     * The {@code redundant_pic_cnt_present_flag}.
     */
    public boolean redundantPicCntPresent;

    /**
     * The {@code transform_8x8_mode_flag}.
     */
    public boolean transform8x8Mode;

    /**
     * The resolved 4x4 scaling lists in raster order.
     */
    public int[][] scaling4x4;

    /**
     * The resolved 8x8 scaling lists in raster order.
     */
    public int[][] scaling8x8;

    /**
     * The payload the set was parsed from.
     */
    public byte[] raw;

    /**
     * Constructs an empty set; used by {@link #parse(byte[], int, int, H264Sps)}.
     */
    private H264Pps() {
    }

    /**
     * Reads the {@code pic_parameter_set_id} and {@code seq_parameter_set_id} of a picture
     * parameter set payload.
     *
     * @param rbsp the unescaped payload after the NAL header, followed by padding
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @return {@code ppsId << 8 | spsId}
     * @throws MediaException.Malformed if an id is out of range
     */
    public static int peekIds(byte[] rbsp, int offset, int length) {
        var reader = new H264BitReader();
        reader.init(rbsp, offset, length);
        var ppsId = reader.readUe();
        var spsId = reader.readUe();
        if (ppsId > 255 || spsId > 31 || reader.overrun()) {
            throw new MediaException.Malformed("picture parameter set id out of range");
        }
        return ppsId << 8 | spsId;
    }

    /**
     * Parses a picture parameter set against its sequence parameter set.
     *
     * @param rbsp the unescaped payload after the NAL header, followed by
     * {@link H264BitReader#PADDING} zero bytes
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @param sps the sequence parameter set the payload refers to
     * @return the parsed set
     * @throws MediaException.Malformed if a field is out of range or the payload is truncated
     */
    public static H264Pps parse(byte[] rbsp, int offset, int length, H264Sps sps) {
        var reader = new H264BitReader();
        reader.init(rbsp, offset, length);
        var stopBit = NalUnits.stopBitPosition(rbsp, offset, length);
        var pps = new H264Pps();
        pps.raw = Arrays.copyOfRange(rbsp, offset, offset + length);
        pps.sps = sps;
        pps.id = reader.readUe();
        pps.spsId = reader.readUe();
        if (pps.id > 255 || pps.spsId > 31) {
            throw new MediaException.Malformed("picture parameter set id out of range");
        }
        pps.cabac = reader.readFlag();
        pps.bottomFieldPicOrderInFramePresent = reader.readFlag();
        pps.numSliceGroups = reader.readUe() + 1;
        if (pps.numSliceGroups > 8) {
            throw new MediaException.Malformed("num_slice_groups_minus1 out of range");
        }
        if (pps.numSliceGroups > 1) {
            pps.sliceGroupMapType = reader.readUe();
            switch (pps.sliceGroupMapType) {
                case 0 -> {
                    pps.runLength = new int[pps.numSliceGroups];
                    for (var i = 0; i < pps.numSliceGroups; i++) {
                        pps.runLength[i] = reader.readUe() + 1;
                    }
                }
                case 2 -> {
                    pps.topLeft = new int[pps.numSliceGroups];
                    pps.bottomRight = new int[pps.numSliceGroups];
                    for (var i = 0; i < pps.numSliceGroups - 1; i++) {
                        pps.topLeft[i] = reader.readUe();
                        pps.bottomRight[i] = reader.readUe();
                    }
                }
                case 3, 4, 5 -> {
                    pps.sliceGroupChangeDirection = reader.readFlag();
                    pps.sliceGroupChangeRate = reader.readUe() + 1;
                }
                case 6 -> {
                    var units = reader.readUe() + 1;
                    if (units > sps.widthInMbs * sps.heightInMapUnits) {
                        throw new MediaException.Malformed("pic_size_in_map_units_minus1 out of range");
                    }
                    var bits = 32 - Integer.numberOfLeadingZeros(pps.numSliceGroups - 1);
                    pps.sliceGroupId = new int[units];
                    for (var i = 0; i < units; i++) {
                        pps.sliceGroupId[i] = reader.readBits(bits);
                        if (pps.sliceGroupId[i] >= pps.numSliceGroups) {
                            throw new MediaException.Malformed("slice_group_id out of range");
                        }
                    }
                }
                case 1 -> {
                }
                default -> throw new MediaException.Malformed("slice_group_map_type " + pps.sliceGroupMapType + " out of range");
            }
        }
        pps.numRefIdxL0Default = reader.readUe() + 1;
        pps.numRefIdxL1Default = reader.readUe() + 1;
        if (pps.numRefIdxL0Default > 32 || pps.numRefIdxL1Default > 32) {
            throw new MediaException.Malformed("num_ref_idx_default_active_minus1 out of range");
        }
        pps.weightedPred = reader.readFlag();
        pps.weightedBipredIdc = reader.readBits(2);
        if (pps.weightedBipredIdc == 3) {
            throw new MediaException.Malformed("weighted_bipred_idc 3 is reserved");
        }
        pps.initQp = 26 + reader.readSe();
        reader.readSe();
        pps.chromaQpIndexOffset = reader.readSe();
        if (pps.initQp < -6 * (sps.bitDepthLuma - 8) || pps.initQp > 51
                || pps.chromaQpIndexOffset < -12 || pps.chromaQpIndexOffset > 12) {
            throw new MediaException.Malformed("picture parameter set quantizer out of range");
        }
        pps.secondChromaQpIndexOffset = pps.chromaQpIndexOffset;
        pps.deblockingFilterControlPresent = reader.readFlag();
        pps.constrainedIntraPred = reader.readFlag();
        pps.redundantPicCntPresent = reader.readFlag();
        pps.scaling4x4 = sps.scaling4x4;
        pps.scaling8x8 = sps.scaling8x8;
        if (reader.moreRbspData(stopBit)) {
            pps.transform8x8Mode = reader.readFlag();
            if (reader.readFlag()) {
                var scaling4x4 = new int[6][16];
                var scaling8x8 = new int[6][64];
                var count = 6 + (pps.transform8x8Mode ? (sps.chromaFormatIdc != 3 ? 2 : 6) : 0);
                if (sps.scalingMatrixPresent) {
                    H264ScalingLists.parse(reader, count, sps.scaling4x4, sps.scaling8x8, scaling4x4, scaling8x8);
                } else {
                    H264ScalingLists.parse(reader, count, null, null, scaling4x4, scaling8x8);
                }
                pps.scaling4x4 = scaling4x4;
                pps.scaling8x8 = scaling8x8;
            }
            pps.secondChromaQpIndexOffset = reader.readSe();
            if (pps.secondChromaQpIndexOffset < -12 || pps.secondChromaQpIndexOffset > 12) {
                throw new MediaException.Malformed("second_chroma_qp_index_offset out of range");
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated picture parameter set");
        }
        return pps;
    }
}
