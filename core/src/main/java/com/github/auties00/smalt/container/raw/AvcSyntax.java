package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Rational;

import java.util.Map;

/**
 * Reads the fields of H.264 parameter sets and slice headers (ITU-T H.264 clauses 7.3.2.1, 7.3.2.2 and 7.3.3)
 * that order the pictures of an Annex B stream: the picture order count inputs, the field structure and the
 * memory management operations that reset the picture order count.
 *
 * <p>Every method returns {@code null} for a unit it cannot read.
 */
final class AvcSyntax {
    /**
     * The largest number of {@code offset_for_ref_frame} entries (H.264 clause 7.4.2.1.1).
     */
    private static final int MAX_POC_CYCLE = 255;

    /**
     * The largest number of slice groups.
     */
    private static final int MAX_SLICE_GROUPS = 8;

    /**
     * The largest number of reference indices of a list.
     */
    private static final int MAX_REF_INDICES = 32;

    /**
     * The largest number of list modification or memory management operations read from one slice header.
     */
    private static final int MAX_OPERATIONS = 100;

    private AvcSyntax() {
        throw new AssertionError();
    }

    /**
     * The fields of a sequence parameter set this package needs.
     *
     * @param id {@code seq_parameter_set_id}
     * @param chromaArrayType {@code ChromaArrayType}
     * @param separateColourPlane {@code separate_colour_plane_flag}
     * @param log2MaxFrameNum {@code log2_max_frame_num_minus4 + 4}
     * @param pocType {@code pic_order_cnt_type}
     * @param log2MaxPocLsb {@code log2_max_pic_order_cnt_lsb_minus4 + 4}
     * @param deltaPicOrderAlwaysZero {@code delta_pic_order_always_zero_flag}
     * @param offsetForNonRefPic {@code offset_for_non_ref_pic}
     * @param offsetForTopToBottomField {@code offset_for_top_to_bottom_field}
     * @param offsetForRefFrame {@code offset_for_ref_frame}
     * @param frameMbsOnly {@code frame_mbs_only_flag}
     * @param reorderFrames {@code max_num_reorder_frames}, or {@code -1} when the VUI does not signal it
     * @param frameRate the frame rate of the VUI timing information, or {@code null}
     */
    record Sps(int id, int chromaArrayType, boolean separateColourPlane, int log2MaxFrameNum, int pocType,
               int log2MaxPocLsb, boolean deltaPicOrderAlwaysZero, int offsetForNonRefPic,
               int offsetForTopToBottomField, int[] offsetForRefFrame, boolean frameMbsOnly, int reorderFrames,
               Rational frameRate) {
    }

    /**
     * The fields of a picture parameter set this package needs.
     *
     * @param id {@code pic_parameter_set_id}
     * @param spsId {@code seq_parameter_set_id}
     * @param bottomFieldPicOrderInFramePresent {@code bottom_field_pic_order_in_frame_present_flag}
     * @param refIdxL0 {@code num_ref_idx_l0_default_active_minus1 + 1}
     * @param refIdxL1 {@code num_ref_idx_l1_default_active_minus1 + 1}
     * @param weightedPred {@code weighted_pred_flag}
     * @param weightedBipredIdc {@code weighted_bipred_idc}
     * @param redundantPicCntPresent {@code redundant_pic_cnt_present_flag}
     */
    record Pps(int id, int spsId, boolean bottomFieldPicOrderInFramePresent, int refIdxL0, int refIdxL1,
               boolean weightedPred, int weightedBipredIdc, boolean redundantPicCntPresent) {
    }

    /**
     * The fields of a slice header this package needs.
     *
     * @param sps the active sequence parameter set
     * @param idr whether the slice belongs to an IDR picture
     * @param reference whether {@code nal_ref_idc} is not zero
     * @param intra whether {@code slice_type} is I or SI
     * @param frameNum {@code frame_num}
     * @param field {@code field_pic_flag}
     * @param bottom {@code bottom_field_flag}
     * @param pocLsb {@code pic_order_cnt_lsb}
     * @param deltaPocBottom {@code delta_pic_order_cnt_bottom}
     * @param deltaPoc0 {@code delta_pic_order_cnt[0]}
     * @param deltaPoc1 {@code delta_pic_order_cnt[1]}
     * @param mmco5 whether a {@code memory_management_control_operation} equal to 5 is present
     * @param ppsId {@code pic_parameter_set_id}
     * @param idrPicId {@code idr_pic_id}, zero for a non-IDR picture
     * @param redundantPicCnt {@code redundant_pic_cnt}, zero for a primary coded picture
     */
    record Slice(Sps sps, boolean idr, boolean reference, boolean intra, int frameNum, boolean field, boolean bottom,
                 int pocLsb, int deltaPocBottom, int deltaPoc0, int deltaPoc1, boolean mmco5, int ppsId,
                 int idrPicId, int redundantPicCnt) {
        /**
         * Returns whether a slice belongs to the same primary coded picture as this one, by the comparisons of ITU-T
         * H.264 clause 7.4.1.2.4.
         *
         * @param other the slice that follows this one
         * @return {@code true} when the slices code the same picture
         */
        boolean samePicture(Slice other) {
            return frameNum == other.frameNum && ppsId == other.ppsId && field == other.field
                    && bottom == other.bottom && reference == other.reference && idr == other.idr
                    && idrPicId == other.idrPicId && pocLsb == other.pocLsb && deltaPocBottom == other.deltaPocBottom
                    && deltaPoc0 == other.deltaPoc0 && deltaPoc1 == other.deltaPoc1;
        }
    }

    /**
     * Reads a sequence parameter set.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null}
     */
    static Sps sps(byte[] data, int offset, int length) {
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 1, length - 1));
            var profile = bits.read(8);
            bits.skip(16);
            var id = bits.readUe();
            if (id < 0 || id > 31) {
                return null;
            }
            var chromaFormat = 1;
            var separate = false;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                    || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139
                    || profile == 134 || profile == 135) {
                chromaFormat = bits.readUe();
                if (chromaFormat == 3) {
                    separate = bits.readFlag();
                }
                bits.readUe();
                bits.readUe();
                bits.skip(1);
                if (bits.readFlag()) {
                    for (var list = 0; list < (chromaFormat != 3 ? 8 : 12); list++) {
                        if (bits.readFlag()) {
                            skipScalingList(bits, list < 6 ? 16 : 64);
                        }
                    }
                }
            }
            var log2MaxFrameNum = bits.readUe() + 4;
            var pocType = bits.readUe();
            var log2MaxPocLsb = 0;
            var alwaysZero = false;
            var nonRef = 0;
            var topToBottom = 0;
            var cycle = new int[0];
            if (pocType == 0) {
                log2MaxPocLsb = bits.readUe() + 4;
            } else if (pocType == 1) {
                alwaysZero = bits.readFlag();
                nonRef = bits.readSe();
                topToBottom = bits.readSe();
                var count = bits.readUe();
                if (count < 0 || count > MAX_POC_CYCLE) {
                    return null;
                }
                cycle = new int[count];
                for (var i = 0; i < count; i++) {
                    cycle[i] = bits.readSe();
                }
            } else if (pocType != 2) {
                return null;
            }
            if (log2MaxFrameNum > 16 || (pocType == 0 && log2MaxPocLsb > 16)) {
                return null;
            }
            bits.readUe();
            bits.skip(1);
            bits.readUe();
            bits.readUe();
            var frameMbsOnly = bits.readFlag();
            if (!frameMbsOnly) {
                bits.skip(1);
            }
            bits.skip(1);
            if (bits.readFlag()) {
                bits.readUe();
                bits.readUe();
                bits.readUe();
                bits.readUe();
            }
            if (bits.overrun()) {
                return null;
            }
            var reorder = -1;
            Rational frameRate = null;
            if (bits.readFlag()) {
                if (bits.readFlag() && bits.read(8) == 255) {
                    bits.skip(32);
                }
                if (bits.readFlag()) {
                    bits.skip(1);
                }
                if (bits.readFlag()) {
                    bits.skip(4);
                    if (bits.readFlag()) {
                        bits.skip(24);
                    }
                }
                if (bits.readFlag()) {
                    bits.readUe();
                    bits.readUe();
                }
                if (bits.readFlag()) {
                    var units = bits.readLong(32);
                    var scale = bits.readLong(32);
                    bits.skip(1);
                    if (units > 0 && scale > 0 && scale <= Integer.MAX_VALUE && 2 * units <= Integer.MAX_VALUE) {
                        frameRate = new Rational((int) scale, (int) (2 * units)).reduce();
                    }
                }
                var nalHrd = bits.readFlag();
                if (nalHrd) {
                    skipHrd(bits);
                }
                var vclHrd = bits.readFlag();
                if (vclHrd) {
                    skipHrd(bits);
                }
                if (nalHrd || vclHrd) {
                    bits.skip(1);
                }
                bits.skip(1);
                if (bits.readFlag()) {
                    bits.skip(1);
                    bits.readUe();
                    bits.readUe();
                    bits.readUe();
                    bits.readUe();
                    reorder = bits.readUe();
                    bits.readUe();
                }
            }
            if (bits.overrun()) {
                // a truncated VUI tail: the fields that order pictures were read
                reorder = -1;
                frameRate = null;
            }
            return new Sps(id, separate ? 0 : chromaFormat, separate, log2MaxFrameNum, pocType, log2MaxPocLsb,
                    alwaysZero, nonRef, topToBottom, cycle, frameMbsOnly, reorder < 0 || reorder > 16 ? -1 : reorder,
                    frameRate);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads a picture parameter set.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null}
     */
    static Pps pps(byte[] data, int offset, int length) {
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 1, length - 1));
            var id = bits.readUe();
            var spsId = bits.readUe();
            if (id < 0 || id > 255 || spsId < 0 || spsId > 31) {
                return null;
            }
            bits.skip(1);
            var bottomPresent = bits.readFlag();
            var groups = bits.readUe() + 1;
            if (groups < 1 || groups > MAX_SLICE_GROUPS) {
                return null;
            }
            if (groups > 1) {
                var mapType = bits.readUe();
                switch (mapType) {
                    case 0 -> {
                        for (var i = 0; i < groups; i++) {
                            bits.readUe();
                        }
                    }
                    case 2 -> {
                        for (var i = 0; i < groups - 1; i++) {
                            bits.readUe();
                            bits.readUe();
                        }
                    }
                    case 3, 4, 5 -> {
                        bits.skip(1);
                        bits.readUe();
                    }
                    case 6 -> {
                        var units = bits.readUe() + 1;
                        if (units < 1 || units > 1 << 20) {
                            return null;
                        }
                        bits.skip((long) units * (32 - Integer.numberOfLeadingZeros(groups - 1)));
                    }
                    case 1 -> {
                    }
                    default -> {
                        return null;
                    }
                }
            }
            var l0 = bits.readUe() + 1;
            var l1 = bits.readUe() + 1;
            var weighted = bits.readFlag();
            var bipred = bits.read(2);
            bits.readSe();
            bits.readSe();
            bits.readSe();
            bits.skip(2);
            var redundant = bits.readFlag();
            if (bits.overrun() || l0 < 1 || l0 > MAX_REF_INDICES || l1 < 1 || l1 > MAX_REF_INDICES) {
                return null;
            }
            return new Pps(id, spsId, bottomPresent, l0, l1, weighted, bipred, redundant);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads the header of a coded slice (NAL unit type 1 or 5) or slice data partition A (type 2).
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @param sequences the sequence parameter sets met, by identifier
     * @param pictures the picture parameter sets met, by identifier
     * @return the fields, or {@code null} when the header cannot be read or names an unknown parameter set
     */
    static Slice slice(byte[] data, int offset, int length, Map<Integer, Sps> sequences, Map<Integer, Pps> pictures) {
        try {
            var header = data[offset] & 0xFF;
            var type = header & 0x1F;
            var reference = (header & 0x60) != 0;
            var idr = type == 5;
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 1, Math.min(length - 1, 4096)));
            bits.readUe();
            var sliceType = bits.readUe() % 5;
            var ppsId = bits.readUe();
            var pps = pictures.get(ppsId);
            if (pps == null) {
                return null;
            }
            var sps = sequences.get(pps.spsId());
            if (sps == null) {
                return null;
            }
            if (sps.separateColourPlane()) {
                bits.skip(2);
            }
            var frameNum = bits.read(sps.log2MaxFrameNum());
            var field = false;
            var bottom = false;
            if (!sps.frameMbsOnly()) {
                field = bits.readFlag();
                if (field) {
                    bottom = bits.readFlag();
                }
            }
            var idrPicId = idr ? bits.readUe() : 0;
            var lsb = 0;
            var deltaBottom = 0;
            var delta0 = 0;
            var delta1 = 0;
            if (sps.pocType() == 0) {
                lsb = bits.read(sps.log2MaxPocLsb());
                if (pps.bottomFieldPicOrderInFramePresent() && !field) {
                    deltaBottom = bits.readSe();
                }
            }
            if (sps.pocType() == 1 && !sps.deltaPicOrderAlwaysZero()) {
                delta0 = bits.readSe();
                if (pps.bottomFieldPicOrderInFramePresent() && !field) {
                    delta1 = bits.readSe();
                }
            }
            var intra = sliceType == 2 || sliceType == 4;
            var redundant = pps.redundantPicCntPresent() ? bits.readUe() : 0;
            var mmco5 = false;
            if (reference) {
                mmco5 = !idr && hasMmco5(bits, sps, pps, sliceType, type);
            }
            if (bits.overrun()) {
                return null;
            }
            return new Slice(sps, idr, reference, intra, frameNum, field, bottom, lsb, deltaBottom, delta0, delta1,
                    mmco5, ppsId, idrPicId, redundant);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads the rest of a reference slice header up to its decoded reference picture marking and returns whether it
     * holds a memory management control operation 5.
     *
     * @param bits the reader, positioned after {@code redundant_pic_cnt}
     * @param sps the active sequence parameter set
     * @param pps the active picture parameter set
     * @param sliceType {@code slice_type % 5}
     * @param nalType the NAL unit type
     * @return {@code true} when operation 5 is present
     */
    private static boolean hasMmco5(MsbBitReader bits, Sps sps, Pps pps, int sliceType, int nalType) {
        var b = sliceType == 1;
        var p = sliceType == 0 || sliceType == 3;
        if (nalType == 2) {
            // slice data partition A: slice_id follows the slice header
            return false;
        }
        if (b) {
            bits.skip(1);
        }
        var l0 = pps.refIdxL0();
        var l1 = pps.refIdxL1();
        if (p || b) {
            if (bits.readFlag()) {
                l0 = bits.readUe() + 1;
                if (b) {
                    l1 = bits.readUe() + 1;
                }
            }
        }
        if (l0 < 1 || l0 > MAX_REF_INDICES || l1 < 1 || l1 > MAX_REF_INDICES) {
            return false;
        }
        if (sliceType != 2 && sliceType != 4) {
            skipListModification(bits);
            if (b) {
                skipListModification(bits);
            }
        }
        if ((pps.weightedPred() && p) || (pps.weightedBipredIdc() == 1 && b)) {
            bits.readUe();
            var chroma = sps.chromaArrayType() != 0;
            if (chroma) {
                bits.readUe();
            }
            for (var list = 0; list < (b ? 2 : 1); list++) {
                var count = list == 0 ? l0 : l1;
                for (var i = 0; i < count; i++) {
                    if (bits.readFlag()) {
                        bits.readSe();
                        bits.readSe();
                    }
                    if (chroma && bits.readFlag()) {
                        bits.readSe();
                        bits.readSe();
                        bits.readSe();
                        bits.readSe();
                    }
                }
            }
        }
        if (!bits.readFlag()) {
            return false;
        }
        for (var i = 0; i < MAX_OPERATIONS && !bits.overrun(); i++) {
            var operation = bits.readUe();
            switch (operation) {
                case 0 -> {
                    return false;
                }
                case 1 -> bits.readUe();
                case 2 -> bits.readUe();
                case 3 -> {
                    bits.readUe();
                    bits.readUe();
                }
                case 4 -> bits.readUe();
                case 5 -> {
                    return true;
                }
                case 6 -> bits.readUe();
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Skips one {@code ref_pic_list_modification} loop.
     *
     * @param bits the reader
     */
    private static void skipListModification(MsbBitReader bits) {
        if (!bits.readFlag()) {
            return;
        }
        for (var i = 0; i < MAX_OPERATIONS && !bits.overrun(); i++) {
            var idc = bits.readUe();
            if (idc == 3 || idc < 0 || idc > 5) {
                return;
            }
            bits.readUe();
        }
    }

    /**
     * Skips the HRD parameters of a VUI (H.264 clause E.1.2).
     *
     * @param bits the reader
     */
    private static void skipHrd(MsbBitReader bits) {
        var count = bits.readUe() + 1;
        if (count < 1 || count > 32) {
            bits.skip(1L << 40);
            return;
        }
        bits.skip(8);
        for (var i = 0; i < count; i++) {
            bits.readUe();
            bits.readUe();
            bits.skip(1);
        }
        bits.skip(20);
    }

    /**
     * Skips one scaling list of a sequence parameter set.
     *
     * @param bits the reader
     * @param size the number of coefficients
     */
    private static void skipScalingList(MsbBitReader bits, int size) {
        var last = 8;
        var next = 8;
        for (var index = 0; index < size; index++) {
            if (next != 0) {
                next = (last + bits.readSe() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }
}
