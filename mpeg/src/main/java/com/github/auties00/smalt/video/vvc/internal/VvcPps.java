/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp, source/Lib/CommonLib/Slice.cpp). Copyright (c)
 * 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD
 * License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.Arrays;

/**
 * Holds a parsed picture parameter set (ITU-T H.266 7.3.2.5) with its tile, slice and subpicture layout.
 */
public final class VvcPps {
    /**
     * Constructs an empty set, which {@link #parse} fills.
     */
    public VvcPps() {
    }

    /**
     * {@code pps_pic_parameter_set_id}.
     */
    public int id;
    /**
     * {@code pps_seq_parameter_set_id}.
     */
    public int spsId;
    /**
     * The sequence parameter set the layout was derived with.
     */
    public VvcSps sps;
    /**
     * {@code pps_mixed_nalu_types_in_pic_flag}.
     */
    public boolean mixedNaluTypes;
    /**
     * {@code pps_pic_width_in_luma_samples}.
     */
    public int width;
    /**
     * {@code pps_pic_height_in_luma_samples}.
     */
    public int height;
    /**
     * The left conformance window offset, in chroma samples: {@code pps_conf_win_left_offset}, or the sequence
     * parameter set's for a picture of the maximum size.
     */
    public int confLeft;
    /**
     * The right conformance window offset, in chroma samples.
     */
    public int confRight;
    /**
     * The top conformance window offset, in chroma samples.
     */
    public int confTop;
    /**
     * The bottom conformance window offset, in chroma samples.
     */
    public int confBottom;
    /**
     * {@code pps_scaling_window_explicit_signalling_flag}.
     */
    public boolean scalingWindowExplicit;
    /**
     * The left scaling window offset, in chroma samples: {@code pps_scaling_win_left_offset}, or the conformance
     * window's.
     */
    public int scalingLeft;
    /**
     * The right scaling window offset, in chroma samples.
     */
    public int scalingRight;
    /**
     * The top scaling window offset, in chroma samples.
     */
    public int scalingTop;
    /**
     * The bottom scaling window offset, in chroma samples.
     */
    public int scalingBottom;
    /**
     * {@code pps_output_flag_present_flag}.
     */
    public boolean outputFlagPresent;
    /**
     * {@code pps_no_pic_partition_flag}: one tile and one slice.
     */
    public boolean noPicPartition;
    /**
     * {@code pps_subpic_id_mapping_present_flag}.
     */
    public boolean subpicIdMappingPresent;
    /**
     * The number of subpictures, as the sequence parameter set declares them.
     */
    public int numSubpics = 1;
    /**
     * The identifier of each subpicture.
     */
    public int[] subpicId;
    /**
     * {@code PicWidthInCtbsY}.
     */
    public int widthInCtus;
    /**
     * {@code PicHeightInCtbsY}.
     */
    public int heightInCtus;
    /**
     * {@code NumTileColumns}.
     */
    public int numTileColumns;
    /**
     * {@code NumTileRows}.
     */
    public int numTileRows;
    /**
     * {@code ColBd}: the first CTU column of each tile column, and the picture width in CTUs last.
     */
    public int[] tileColBd;
    /**
     * {@code RowBd}: the first CTU row of each tile row, and the picture height in CTUs last.
     */
    public int[] tileRowBd;
    /**
     * The tile column of each CTU column, with one entry past the last column.
     */
    public int[] ctuToTileCol;
    /**
     * The tile row of each CTU row, with one entry past the last row.
     */
    public int[] ctuToTileRow;
    /**
     * {@code pps_loop_filter_across_tiles_enabled_flag}.
     */
    public boolean loopFilterAcrossTiles;
    /**
     * {@code pps_rect_slice_flag}.
     */
    public boolean rectSlice = true;
    /**
     * {@code pps_single_slice_per_subpic_flag}.
     */
    public boolean singleSlicePerSubpic;
    /**
     * {@code pps_num_slices_in_pic_minus1 + 1}, for rectangular slices.
     */
    public int numSlicesInPic = 1;
    /**
     * The CTU addresses (raster scan in the picture) of each rectangular slice, in decoding order.
     */
    public int[][] sliceCtus;
    /**
     * The number of rectangular slices in each subpicture.
     */
    public int[] subpicNumSlices;
    /**
     * {@code pps_loop_filter_across_slices_enabled_flag}.
     */
    public boolean loopFilterAcrossSlices;
    /**
     * {@code pps_cabac_init_present_flag}.
     */
    public boolean cabacInitPresent;
    /**
     * {@code pps_num_ref_idx_default_active_minus1 + 1} of each list.
     */
    public final int[] numRefIdxDefault = new int[2];
    /**
     * {@code pps_rpl1_idx_present_flag}.
     */
    public boolean rpl1IdxPresent;
    /**
     * {@code pps_weighted_pred_flag}.
     */
    public boolean weightedPred;
    /**
     * {@code pps_weighted_bipred_flag}.
     */
    public boolean weightedBipred;
    /**
     * {@code pps_ref_wraparound_enabled_flag}.
     */
    public boolean refWraparound;
    /**
     * {@code PpsRefWraparoundOffset} in luma samples: the picture width less {@code
     * pps_pic_width_minus_wraparound_offset} minimum coding blocks.
     */
    public int wraparoundOffset;
    /**
     * {@code pps_init_qp_minus26 + 26}.
     */
    public int initQp;
    /**
     * {@code pps_cu_qp_delta_enabled_flag}.
     */
    public boolean cuQpDelta;
    /**
     * {@code pps_chroma_tool_offsets_present_flag}.
     */
    public boolean chromaToolOffsets;
    /**
     * {@code pps_cb_qp_offset}.
     */
    public int cbQpOffset;
    /**
     * {@code pps_cr_qp_offset}.
     */
    public int crQpOffset;
    /**
     * {@code pps_joint_cbcr_qp_offset_value}.
     */
    public int jointCbCrQpOffset;
    /**
     * {@code pps_joint_cbcr_qp_offset_present_flag}.
     */
    public boolean jointCbCrQpOffsetPresent;
    /**
     * {@code pps_slice_chroma_qp_offsets_present_flag}.
     */
    public boolean sliceChromaQpOffsets;
    /**
     * {@code pps_cu_chroma_qp_offset_list_enabled_flag}.
     */
    public boolean cuChromaQpOffsetList;
    /**
     * {@code pps_chroma_qp_offset_list_len_minus1 + 1}.
     */
    public int chromaQpOffsetListLen;
    /**
     * {@code pps_cb_qp_offset_list} of each entry.
     */
    public final int[] cbQpOffsetList = new int[6];
    /**
     * {@code pps_cr_qp_offset_list} of each entry.
     */
    public final int[] crQpOffsetList = new int[6];
    /**
     * {@code pps_joint_cbcr_qp_offset_list} of each entry.
     */
    public final int[] jointCbCrQpOffsetList = new int[6];
    /**
     * {@code pps_deblocking_filter_control_present_flag}.
     */
    public boolean deblockingControl;
    /**
     * {@code pps_deblocking_filter_override_enabled_flag}.
     */
    public boolean deblockingOverrideEnabled;
    /**
     * {@code pps_deblocking_filter_disabled_flag}.
     */
    public boolean deblockingDisabled;
    /**
     * {@code pps_dbf_info_in_ph_flag}.
     */
    public boolean dbfInfoInPh;
    /**
     * {@code pps_luma_beta_offset_div2}.
     */
    public int betaOffset;
    /**
     * {@code pps_luma_tc_offset_div2}.
     */
    public int tcOffset;
    /**
     * {@code pps_cb_beta_offset_div2}, the luma offset when the chroma tool offsets are absent.
     */
    public int cbBetaOffset;
    /**
     * {@code pps_cb_tc_offset_div2}, the luma offset when the chroma tool offsets are absent.
     */
    public int cbTcOffset;
    /**
     * {@code pps_cr_beta_offset_div2}, the luma offset when the chroma tool offsets are absent.
     */
    public int crBetaOffset;
    /**
     * {@code pps_cr_tc_offset_div2}, the luma offset when the chroma tool offsets are absent.
     */
    public int crTcOffset;
    /**
     * {@code pps_rpl_info_in_ph_flag}.
     */
    public boolean rplInfoInPh;
    /**
     * {@code pps_sao_info_in_ph_flag}.
     */
    public boolean saoInfoInPh;
    /**
     * {@code pps_alf_info_in_ph_flag}.
     */
    public boolean alfInfoInPh;
    /**
     * {@code pps_wp_info_in_ph_flag}.
     */
    public boolean wpInfoInPh;
    /**
     * {@code pps_qp_delta_info_in_ph_flag}.
     */
    public boolean qpDeltaInfoInPh;
    /**
     * {@code pps_picture_header_extension_present_flag}.
     */
    public boolean pictureHeaderExtension;
    /**
     * {@code pps_slice_header_extension_present_flag}.
     */
    public boolean sliceHeaderExtension;

    /**
     * Parses a picture parameter set RBSP.
     *
     * @param r the reader, positioned after the NAL unit header
     * @param spsTable the sequence parameter sets received so far, indexed by id
     * @return the parsed set
     * @throws MediaException.Malformed if a syntax element is out of range or the sequence parameter set is missing
     */
    public static VvcPps parse(H264BitReader r, VvcSps[] spsTable) {
        var p = new VvcPps();
        p.id = r.readBits(6);
        p.spsId = r.readBits(4);
        var sps = spsTable[p.spsId];
        if (sps == null) {
            throw new MediaException.Malformed("vvc picture parameter set refers to a missing sequence parameter set");
        }
        p.sps = sps;
        var subW = 1 << sps.chromaShiftX();
        var subH = 1 << sps.chromaShiftY();
        p.mixedNaluTypes = r.readFlag();
        p.width = r.readUe(sps.maxWidth, "pps_pic_width_in_luma_samples");
        p.height = r.readUe(sps.maxHeight, "pps_pic_height_in_luma_samples");
        var minCb = 1 << sps.log2MinCbSize;
        var unit = Math.max(8, minCb);
        if (p.width == 0 || p.height == 0 || p.width % unit != 0 || p.height % unit != 0) {
            throw new MediaException.Malformed("vvc picture size not a multiple of the minimum coding block");
        }
        p.widthInCtus = (p.width + sps.ctuSize - 1) >> sps.log2CtuSize;
        p.heightInCtus = (p.height + sps.ctuSize - 1) >> sps.log2CtuSize;
        if (r.readFlag()) {
            p.confLeft = r.readUe(1 << 16, "pps_conf_win_left_offset");
            p.confRight = r.readUe(1 << 16, "pps_conf_win_right_offset");
            p.confTop = r.readUe(1 << 16, "pps_conf_win_top_offset");
            p.confBottom = r.readUe(1 << 16, "pps_conf_win_bottom_offset");
            if (subW * (p.confLeft + p.confRight) >= p.width || subH * (p.confTop + p.confBottom) >= p.height) {
                throw new MediaException.Malformed("vvc conformance window exceeds the picture");
            }
        }
        if (p.width == sps.maxWidth && p.height == sps.maxHeight) {
            p.confLeft = sps.confLeft;
            p.confRight = sps.confRight;
            p.confTop = sps.confTop;
            p.confBottom = sps.confBottom;
        }
        p.scalingWindowExplicit = r.readFlag();
        if (p.scalingWindowExplicit) {
            p.scalingLeft = r.readSe(-(1 << 16), 1 << 16, "pps_scaling_win_left_offset");
            p.scalingRight = r.readSe(-(1 << 16), 1 << 16, "pps_scaling_win_right_offset");
            p.scalingTop = r.readSe(-(1 << 16), 1 << 16, "pps_scaling_win_top_offset");
            p.scalingBottom = r.readSe(-(1 << 16), 1 << 16, "pps_scaling_win_bottom_offset");
            if ((long) subW * (p.scalingLeft + p.scalingRight) >= p.width
                    || (long) subH * (p.scalingTop + p.scalingBottom) >= p.height) {
                throw new MediaException.Malformed("vvc scaling window out of range");
            }
        } else {
            p.scalingLeft = p.confLeft;
            p.scalingRight = p.confRight;
            p.scalingTop = p.confTop;
            p.scalingBottom = p.confBottom;
        }
        p.outputFlagPresent = r.readFlag();
        p.noPicPartition = r.readFlag();
        p.subpicIdMappingPresent = r.readFlag();
        p.numSubpics = sps.numSubpics;
        p.subpicId = new int[sps.numSubpics];
        if (p.subpicIdMappingPresent) {
            var count = p.noPicPartition ? 1 : r.readUe(VvcSps.MAX_SUBPICS - 1, "pps_num_subpics_minus1") + 1;
            if (count != sps.numSubpics) {
                throw new MediaException.Malformed("vvc pps_num_subpics_minus1 differs from the sequence's");
            }
            var len = r.readUe(15, "pps_subpic_id_len_minus1") + 1;
            for (var i = 0; i < count; i++) {
                p.subpicId[i] = r.readBits(len);
            }
        } else {
            for (var i = 0; i < sps.numSubpics; i++) {
                p.subpicId[i] = sps.subpicIdMappingExplicit ? sps.subpicId[i] : i;
            }
        }
        int[] colWidths;
        int[] rowHeights;
        int[] sliceTileIdx = null;
        int[] sliceWidthInTiles = null;
        int[] sliceHeightInTiles = null;
        int[] sliceHeightInCtus = null;
        int[] numSlicesInTile = null;
        if (!p.noPicPartition) {
            if (r.readBits(2) + 5 != sps.log2CtuSize) {
                throw new MediaException.Malformed("vvc pps_log2_ctu_size_minus5 differs from the sequence's");
            }
            var expCols = r.readUe(p.widthInCtus - 1, "pps_num_exp_tile_columns_minus1") + 1;
            var expRows = r.readUe(p.heightInCtus - 1, "pps_num_exp_tile_rows_minus1") + 1;
            var expColWidths = new int[expCols];
            for (var i = 0; i < expCols; i++) {
                expColWidths[i] = r.readUe(p.widthInCtus - 1, "pps_tile_column_width_minus1") + 1;
            }
            var expRowHeights = new int[expRows];
            for (var i = 0; i < expRows; i++) {
                expRowHeights[i] = r.readUe(p.heightInCtus - 1, "pps_tile_row_height_minus1") + 1;
            }
            colWidths = uniformSizes(expColWidths, p.widthInCtus);
            rowHeights = uniformSizes(expRowHeights, p.heightInCtus);
            p.initTiles(colWidths, rowHeights);
            var numTiles = p.numTileColumns * p.numTileRows;
            if (numTiles > 1) {
                p.loopFilterAcrossTiles = r.readFlag();
                p.rectSlice = r.readFlag();
            }
            if (p.rectSlice) {
                p.singleSlicePerSubpic = r.readFlag();
            }
            if (p.rectSlice && !p.singleSlicePerSubpic) {
                p.numSlicesInPic = r.readUe(999, "pps_num_slices_in_pic_minus1") + 1;
                var tileIdxDeltaPresent = p.numSlicesInPic > 2 && r.readFlag();
                var n = p.numSlicesInPic;
                sliceTileIdx = new int[n];
                sliceWidthInTiles = new int[n];
                sliceHeightInTiles = new int[n];
                sliceHeightInCtus = new int[n];
                numSlicesInTile = new int[n];
                var tileIdx = 0;
                for (var i = 0; i < n - 1; i++) {
                    if (tileIdx < 0 || tileIdx >= numTiles) {
                        throw new MediaException.Malformed("vvc slice tile index out of range");
                    }
                    sliceTileIdx[i] = tileIdx;
                    var col = tileIdx % p.numTileColumns;
                    var row = tileIdx / p.numTileColumns;
                    sliceWidthInTiles[i] = col != p.numTileColumns - 1
                            ? r.readUe(p.numTileColumns - 1, "pps_slice_width_in_tiles_minus1") + 1 : 1;
                    if (row != p.numTileRows - 1 && (tileIdxDeltaPresent || col == 0)) {
                        sliceHeightInTiles[i] = r.readUe(p.numTileRows - 1, "pps_slice_height_in_tiles_minus1") + 1;
                    } else if (row == p.numTileRows - 1) {
                        sliceHeightInTiles[i] = 1;
                    } else {
                        sliceHeightInTiles[i] = i > 0 ? sliceHeightInTiles[i - 1] : 1;
                    }
                    numSlicesInTile[i] = 1;
                    if (sliceWidthInTiles[i] == 1 && sliceHeightInTiles[i] == 1) {
                        var tileHeight = rowHeights[row];
                        if (tileHeight > 1) {
                            var exp = r.readUe(tileHeight - 1, "pps_num_exp_slices_in_tile");
                            if (exp == 0) {
                                sliceHeightInCtus[i] = tileHeight;
                            } else {
                                var remaining = tileHeight;
                                var last = 0;
                                var j = 0;
                                var heights = new int[tileHeight];
                                for (; j < exp; j++) {
                                    last = r.readUe(tileHeight - 1, "pps_exp_slice_height_in_ctus_minus1") + 1;
                                    if (last > remaining) {
                                        throw new MediaException.Malformed("vvc slice heights exceed the tile");
                                    }
                                    heights[j] = last;
                                    remaining -= last;
                                }
                                while (remaining >= last && j < tileHeight) {
                                    heights[j++] = last;
                                    remaining -= last;
                                }
                                if (remaining > 0) {
                                    heights[j++] = remaining;
                                }
                                if (i + j > n) {
                                    throw new MediaException.Malformed("vvc slices in tile exceed the picture's");
                                }
                                for (var k = 0; k < j; k++) {
                                    numSlicesInTile[i + k] = j;
                                    sliceWidthInTiles[i + k] = 1;
                                    sliceHeightInTiles[i + k] = 1;
                                    sliceTileIdx[i + k] = tileIdx;
                                    sliceHeightInCtus[i + k] = heights[k];
                                }
                                i += j - 1;
                            }
                        } else {
                            sliceHeightInCtus[i] = tileHeight;
                        }
                    }
                    if (i < n - 1) {
                        if (tileIdxDeltaPresent) {
                            tileIdx += r.readSe(-numTiles + 1, numTiles - 1, "pps_tile_idx_delta_val");
                        } else {
                            tileIdx += sliceWidthInTiles[i];
                            if (tileIdx % p.numTileColumns == 0) {
                                tileIdx += (sliceHeightInTiles[i] - 1) * p.numTileColumns;
                            }
                        }
                    }
                }
                if (tileIdx < 0 || tileIdx >= numTiles) {
                    throw new MediaException.Malformed("vvc slice tile index out of range");
                }
                sliceTileIdx[n - 1] = tileIdx;
            }
            if (!p.rectSlice || p.singleSlicePerSubpic || p.numSlicesInPic > 1) {
                p.loopFilterAcrossSlices = r.readFlag();
            }
        } else {
            p.singleSlicePerSubpic = true;
            p.initTiles(new int[]{p.widthInCtus}, new int[]{p.heightInCtus});
        }
        p.cabacInitPresent = r.readFlag();
        p.numRefIdxDefault[0] = r.readUe(14, "pps_num_ref_idx_default_active_minus1") + 1;
        p.numRefIdxDefault[1] = r.readUe(14, "pps_num_ref_idx_default_active_minus1") + 1;
        p.rpl1IdxPresent = r.readFlag();
        p.weightedPred = r.readFlag();
        p.weightedBipred = r.readFlag();
        p.refWraparound = r.readFlag();
        if (p.refWraparound) {
            var minus = r.readUe(Math.max(0, p.width / minCb - sps.ctuSize / minCb - 2),
                    "pps_pic_width_minus_wraparound_offset");
            p.wraparoundOffset = minCb * (p.width / minCb - minus);
        }
        p.initQp = 26 + r.readSe(-(26 + sps.qpBdOffset), 37, "pps_init_qp_minus26");
        p.cuQpDelta = r.readFlag();
        p.chromaToolOffsets = r.readFlag();
        if (p.chromaToolOffsets) {
            p.cbQpOffset = r.readSe(-12, 12, "pps_cb_qp_offset");
            p.crQpOffset = r.readSe(-12, 12, "pps_cr_qp_offset");
            p.jointCbCrQpOffsetPresent = r.readFlag();
            if (p.jointCbCrQpOffsetPresent) {
                p.jointCbCrQpOffset = r.readSe(-12, 12, "pps_joint_cbcr_qp_offset_value");
            }
            p.sliceChromaQpOffsets = r.readFlag();
            p.cuChromaQpOffsetList = r.readFlag();
            if (p.cuChromaQpOffsetList) {
                p.chromaQpOffsetListLen = r.readUe(5, "pps_chroma_qp_offset_list_len_minus1") + 1;
                for (var i = 0; i < p.chromaQpOffsetListLen; i++) {
                    p.cbQpOffsetList[i] = r.readSe(-12, 12, "pps_cb_qp_offset_list");
                    p.crQpOffsetList[i] = r.readSe(-12, 12, "pps_cr_qp_offset_list");
                    if (p.jointCbCrQpOffsetPresent) {
                        p.jointCbCrQpOffsetList[i] = r.readSe(-12, 12, "pps_joint_cbcr_qp_offset_list");
                    }
                }
            }
        }
        p.deblockingControl = r.readFlag();
        if (p.deblockingControl) {
            p.deblockingOverrideEnabled = r.readFlag();
            p.deblockingDisabled = r.readFlag();
            if (!p.noPicPartition && p.deblockingOverrideEnabled) {
                p.dbfInfoInPh = r.readFlag();
            }
            if (!p.deblockingDisabled) {
                p.betaOffset = r.readSe(-12, 12, "pps_luma_beta_offset_div2");
                p.tcOffset = r.readSe(-12, 12, "pps_luma_tc_offset_div2");
                if (p.chromaToolOffsets) {
                    p.cbBetaOffset = r.readSe(-12, 12, "pps_cb_beta_offset_div2");
                    p.cbTcOffset = r.readSe(-12, 12, "pps_cb_tc_offset_div2");
                    p.crBetaOffset = r.readSe(-12, 12, "pps_cr_beta_offset_div2");
                    p.crTcOffset = r.readSe(-12, 12, "pps_cr_tc_offset_div2");
                } else {
                    p.cbBetaOffset = p.betaOffset;
                    p.cbTcOffset = p.tcOffset;
                    p.crBetaOffset = p.betaOffset;
                    p.crTcOffset = p.tcOffset;
                }
            }
        }
        if (!p.noPicPartition) {
            p.rplInfoInPh = r.readFlag();
            p.saoInfoInPh = r.readFlag();
            p.alfInfoInPh = r.readFlag();
            if ((p.weightedPred || p.weightedBipred) && p.rplInfoInPh) {
                p.wpInfoInPh = r.readFlag();
            }
            p.qpDeltaInfoInPh = r.readFlag();
        }
        p.pictureHeaderExtension = r.readFlag();
        p.sliceHeaderExtension = r.readFlag();
        if (r.overrun()) {
            throw new MediaException.Malformed("truncated vvc picture parameter set");
        }
        p.initSliceMaps(sliceTileIdx, sliceWidthInTiles, sliceHeightInTiles, sliceHeightInCtus, numSlicesInTile);
        return p;
    }

    /**
     * Completes explicit tile sizes with uniform ones (6.5.1).
     *
     * @param explicit the explicit sizes, at least one
     * @param total the picture size in CTUs
     * @return every size
     * @throws MediaException.Malformed if the explicit sizes exceed the picture
     */
    private static int[] uniformSizes(int[] explicit, int total) {
        var remaining = total;
        for (var size : explicit) {
            remaining -= size;
        }
        if (remaining < 0) {
            throw new MediaException.Malformed("vvc tile sizes exceed the picture");
        }
        var uniform = explicit[explicit.length - 1];
        var sizes = Arrays.copyOf(explicit, explicit.length + (remaining + uniform - 1) / uniform);
        var count = explicit.length;
        while (remaining > 0) {
            var size = Math.min(remaining, uniform);
            sizes[count++] = size;
            remaining -= size;
        }
        return Arrays.copyOf(sizes, count);
    }

    /**
     * Derives the tile boundaries and the CTU to tile maps.
     *
     * @param colWidths the width of each tile column, in CTUs
     * @param rowHeights the height of each tile row, in CTUs
     */
    private void initTiles(int[] colWidths, int[] rowHeights) {
        numTileColumns = colWidths.length;
        numTileRows = rowHeights.length;
        tileColBd = new int[numTileColumns + 1];
        for (var i = 0; i < numTileColumns; i++) {
            tileColBd[i + 1] = tileColBd[i] + colWidths[i];
        }
        tileRowBd = new int[numTileRows + 1];
        for (var i = 0; i < numTileRows; i++) {
            tileRowBd[i + 1] = tileRowBd[i] + rowHeights[i];
        }
        ctuToTileCol = new int[widthInCtus + 1];
        for (int x = 0, c = 0; x <= widthInCtus; x++) {
            if (c < numTileColumns && x == tileColBd[c + 1]) {
                c++;
            }
            ctuToTileCol[x] = c;
        }
        ctuToTileRow = new int[heightInCtus + 1];
        for (int y = 0, c = 0; y <= heightInCtus; y++) {
            if (c < numTileRows && y == tileRowBd[c + 1]) {
                c++;
            }
            ctuToTileRow[y] = c;
        }
    }

    /**
     * Appends the CTUs of a rectangle in raster order.
     *
     * @param list the CTU list being built
     * @param count the number of CTUs already in the list
     * @param x0 the first CTU column of the rectangle
     * @param x1 the CTU column after the rectangle
     * @param y0 the first CTU row of the rectangle
     * @param y1 the CTU row after the rectangle
     * @return the number of CTUs in the list
     * @throws MediaException.Malformed if the list overflows because slices overlap
     */
    private int addCtus(int[] list, int count, int x0, int x1, int y0, int y1) {
        for (var y = y0; y < y1; y++) {
            for (var x = x0; x < x1; x++) {
                if (count == list.length) {
                    throw new MediaException.Malformed("vvc slices overlap");
                }
                list[count++] = y * widthInCtus + x;
            }
        }
        return count;
    }

    /**
     * Builds the CTU list of every rectangular slice and counts the slices of every subpicture (6.5.1).
     *
     * @param tileIdx the first tile of each slice
     * @param widthInTiles the width in tiles of each slice
     * @param heightInTiles the height in tiles of each slice
     * @param sliceHeights the height in CTUs of each slice inside a tile
     * @param slicesInTile the number of slices of the tile of each slice
     * @throws MediaException.Malformed if a slice lies outside the picture or slices overlap
     */
    private void initSliceMaps(int[] tileIdx, int[] widthInTiles, int[] heightInTiles, int[] sliceHeights,
                               int[] slicesInTile) {
        var total = widthInCtus * heightInCtus;
        if (!rectSlice) {
            subpicNumSlices = new int[]{0};
            return;
        }
        if (singleSlicePerSubpic) {
            numSlicesInPic = sps.numSubpics;
            sliceCtus = new int[numSlicesInPic][];
            for (var i = 0; i < numSlicesInPic; i++) {
                var buffer = new int[total];
                var count = 0;
                var left = sps.subpicCtuX[i];
                var top = sps.subpicCtuY[i];
                var right = sps.numSubpics == 1 ? widthInCtus : left + sps.subpicWidth[i];
                var bottom = sps.numSubpics == 1 ? heightInCtus : top + sps.subpicHeight[i];
                if (right > widthInCtus || bottom > heightInCtus) {
                    throw new MediaException.Malformed("vvc subpicture outside the picture");
                }
                var tileX = ctuToTileCol[left];
                var tileY = ctuToTileRow[top];
                var tilesW = ctuToTileCol[right - 1] + 1 - tileX;
                var tilesH = ctuToTileRow[bottom - 1] + 1 - tileY;
                if (sps.numSubpics == 1) {
                    for (var ty = 0; ty < numTileRows; ty++) {
                        for (var tx = 0; tx < numTileColumns; tx++) {
                            count = addCtus(buffer, count, tileColBd[tx], tileColBd[tx + 1], tileRowBd[ty],
                                    tileRowBd[ty + 1]);
                        }
                    }
                } else if (tilesH == 1 && sps.subpicHeight[i] < tileRowBd[tileY + 1] - tileRowBd[tileY]) {
                    count = addCtus(buffer, count, left, right, top, bottom);
                } else {
                    for (var j = 0; j < tilesH; j++) {
                        for (var k = 0; k < tilesW; k++) {
                            count = addCtus(buffer, count, tileColBd[tileX + k], tileColBd[tileX + k + 1],
                                    tileRowBd[tileY + j], tileRowBd[tileY + j + 1]);
                        }
                    }
                }
                sliceCtus[i] = Arrays.copyOf(buffer, count);
            }
        } else if (tileIdx == null) {
            numSlicesInPic = 1;
            var buffer = new int[total];
            var count = 0;
            for (var ty = 0; ty < numTileRows; ty++) {
                for (var tx = 0; tx < numTileColumns; tx++) {
                    count = addCtus(buffer, count, tileColBd[tx], tileColBd[tx + 1], tileRowBd[ty], tileRowBd[ty + 1]);
                }
            }
            sliceCtus = new int[][]{Arrays.copyOf(buffer, count)};
        } else {
            sliceCtus = new int[numSlicesInPic][];
            for (var i = 0; i < numSlicesInPic; i++) {
                var tileX = tileIdx[i] % numTileColumns;
                var tileY = tileIdx[i] / numTileColumns;
                if (i == numSlicesInPic - 1) {
                    widthInTiles[i] = numTileColumns - tileX;
                    heightInTiles[i] = numTileRows - tileY;
                    slicesInTile[i] = 1;
                }
                var buffer = new int[total];
                var count = 0;
                if (widthInTiles[i] > 1 || heightInTiles[i] > 1) {
                    if (tileX + widthInTiles[i] > numTileColumns || tileY + heightInTiles[i] > numTileRows) {
                        throw new MediaException.Malformed("vvc slice outside the picture");
                    }
                    for (var j = 0; j < heightInTiles[i]; j++) {
                        for (var k = 0; k < widthInTiles[i]; k++) {
                            count = addCtus(buffer, count, tileColBd[tileX + k], tileColBd[tileX + k + 1],
                                    tileRowBd[tileY + j], tileRowBd[tileY + j + 1]);
                        }
                    }
                    sliceCtus[i] = Arrays.copyOf(buffer, count);
                } else {
                    var ctuY = tileRowBd[tileY];
                    for (var j = 0; j < slicesInTile[i] - 1; j++) {
                        count = addCtus(buffer, 0, tileColBd[tileX], tileColBd[tileX + 1], ctuY,
                                Math.min(ctuY + sliceHeights[i], tileRowBd[tileY + 1]));
                        sliceCtus[i] = Arrays.copyOf(buffer, count);
                        ctuY += sliceHeights[i];
                        i++;
                        if (i >= numSlicesInPic) {
                            throw new MediaException.Malformed("vvc slices in tile exceed the picture's");
                        }
                    }
                    count = addCtus(buffer, 0, tileColBd[tileX], tileColBd[tileX + 1], ctuY, tileRowBd[tileY + 1]);
                    sliceCtus[i] = Arrays.copyOf(buffer, count);
                }
            }
        }
        var seen = new boolean[total];
        for (var slice : sliceCtus) {
            for (var ctu : slice) {
                if (seen[ctu]) {
                    throw new MediaException.Malformed("vvc slices overlap");
                }
                seen[ctu] = true;
            }
        }
        subpicNumSlices = new int[sps.numSubpics];
        if (numSlicesInPic == 1) {
            Arrays.fill(subpicNumSlices, 1);
        } else {
            for (var slice : sliceCtus) {
                if (slice.length == 0) {
                    throw new MediaException.Malformed("vvc empty slice");
                }
                var x = slice[0] % widthInCtus;
                var y = slice[0] / widthInCtus;
                for (var s = 0; s < sps.numSubpics; s++) {
                    if (x >= sps.subpicCtuX[s] && x < sps.subpicCtuX[s] + sps.subpicWidth[s]
                            && y >= sps.subpicCtuY[s] && y < sps.subpicCtuY[s] + sps.subpicHeight[s]) {
                        subpicNumSlices[s]++;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns the subpicture index of a subpicture identifier.
     *
     * @param subpicIdValue {@code sh_subpic_id}
     * @return the index
     * @throws MediaException.Malformed if no subpicture has that identifier
     */
    public int subpicIndex(int subpicIdValue) {
        for (var i = 0; i < numSubpics; i++) {
            if (subpicId[i] == subpicIdValue) {
                return i;
            }
        }
        throw new MediaException.Malformed("vvc sh_subpic_id matches no subpicture");
    }

    /**
     * {@return the number of tiles}
     */
    public int numTiles() {
        return numTileColumns * numTileRows;
    }
}
