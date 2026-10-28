package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Arrays;
import java.util.Map;

/**
 * Reads the fields of HEVC parameter sets, slice segment headers and picture timing SEI messages (ITU-T H.265 clauses
 * 7.3.2.1, 7.3.2.2, 7.3.2.3, 7.3.6.1, 7.3.7, D.2.3 and E.2.1) that order and time the pictures of an Annex B stream.
 *
 * <p>Every method returns {@code null} for a unit it cannot read.
 */
final class HevcSyntax {
    /**
     * The largest number of short-term reference picture sets of a sequence parameter set.
     */
    private static final int MAX_SHORT_TERM_SETS = 64;

    /**
     * The largest number of pictures of one list of a short-term reference picture set.
     */
    private static final int MAX_SET_PICTURES = 16;

    /**
     * The largest number of long-term reference pictures of a sequence parameter set or slice header.
     */
    private static final int MAX_LONG_TERM = 32;

    /**
     * The most bytes of a slice segment header read.
     */
    private static final int SLICE_HEADER_BYTES = 512;

    private HevcSyntax() {
        throw new AssertionError();
    }

    /**
     * The fields of a sequence parameter set this package needs.
     *
     * @param id {@code sps_seq_parameter_set_id}
     * @param separateColourPlane {@code separate_colour_plane_flag}
     * @param log2MaxPocLsb {@code log2_max_pic_order_cnt_lsb_minus4 + 4}
     * @param reorderPictures {@code sps_max_num_reorder_pics} of the highest sub-layer
     * @param latencyIncreasePlus1 {@code sps_max_latency_increase_plus1} of the highest sub-layer
     * @param maxDecPicBuffering {@code sps_max_dec_pic_buffering_minus1 + 1} of the highest sub-layer
     * @param shortTermSets the picture order count differences of each short-term reference picture set, negative
     * ones first from the closest, then positive ones from the closest, or {@code null} when the sets cannot be read
     * @param longTermPresent {@code long_term_ref_pics_present_flag}
     * @param longTermLsbs {@code lt_ref_pic_poc_lsb_sps} of each long-term reference picture the set lists
     * @param fieldSeq {@code field_seq_flag}: every picture is a field
     * @param frameFieldInfo {@code frame_field_info_present_flag}: picture timing messages carry {@code pic_struct}
     * @param timingUnits {@code vui_num_units_in_tick}, or 0 without timing information in the VUI
     * @param timingScale {@code vui_time_scale}, or 0 without timing information in the VUI
     */
    record Sps(int id, boolean separateColourPlane, int log2MaxPocLsb, int reorderPictures, int latencyIncreasePlus1,
               int maxDecPicBuffering, int[][] shortTermSets, boolean longTermPresent, int[] longTermLsbs,
               boolean fieldSeq, boolean frameFieldInfo, long timingUnits, long timingScale) {
    }

    /**
     * The timing information of a video parameter set.
     *
     * @param id {@code vps_video_parameter_set_id}
     * @param timingUnits {@code vps_num_units_in_tick}, or 0 without timing information
     * @param timingScale {@code vps_time_scale}, or 0 without timing information
     */
    record Vps(int id, long timingUnits, long timingScale) {
    }

    /**
     * The fields of a picture parameter set this package needs.
     *
     * @param id {@code pps_pic_parameter_set_id}
     * @param spsId {@code pps_seq_parameter_set_id}
     * @param outputFlagPresent {@code output_flag_present_flag}
     * @param extraSliceHeaderBits {@code num_extra_slice_header_bits}
     */
    record Pps(int id, int spsId, boolean outputFlagPresent, int extraSliceHeaderBits) {
    }

    /**
     * The fields of the first slice segment header of a picture this package needs.
     *
     * @param sps the active sequence parameter set
     * @param pocLsb {@code slice_pic_order_cnt_lsb}, zero for an IDR picture
     * @param output {@code pic_output_flag}
     * @param noOutputOfPriorPics {@code no_output_of_prior_pics_flag} of an IRAP picture
     * @param shortTermDeltas the picture order count differences of the short-term reference pictures, empty for an
     * IDR picture, or {@code null} when the reference picture set is unknown
     * @param longTermLsbs the {@code PocLsbLt} of each long-term reference picture
     * @param longTermMsbCycles the {@code DeltaPocMsbCycleLt} of each long-term reference picture, or
     * {@code -1} when its {@code delta_poc_msb_present_flag} is zero
     */
    record Slice(Sps sps, int pocLsb, boolean output, boolean noOutputOfPriorPics, int[] shortTermDeltas,
                 int[] longTermLsbs, int[] longTermMsbCycles) {
        /**
         * Returns whether a picture stays a reference picture for this one: it is in the reference picture set.
         *
         * @param poc the picture's order count
         * @param currentPoc this picture's order count
         * @return {@code true} when the picture's order count is in the set
         */
        boolean references(long poc, long currentPoc) {
            if (shortTermDeltas == null) {
                return true;
            }
            for (var delta : shortTermDeltas) {
                if (currentPoc + delta == poc) {
                    return true;
                }
            }
            var maxLsb = 1L << sps.log2MaxPocLsb();
            for (var i = 0; i < longTermLsbs.length; i++) {
                if (longTermMsbCycles[i] < 0) {
                    if ((poc & (maxLsb - 1)) == longTermLsbs[i]) {
                        return true;
                    }
                } else if (poc == currentPoc - longTermMsbCycles[i] * maxLsb - (pocLsb - longTermLsbs[i])) {
                    return true;
                }
            }
            return false;
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
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, length - 2));
            bits.skip(4);
            var subLayers = bits.read(3);
            bits.skip(1);
            skipProfileTierLevel(bits, subLayers);
            var id = bits.readUe();
            if (id < 0 || id > 15) {
                return null;
            }
            var chroma = bits.readUe();
            var separate = chroma == 3 && bits.readFlag();
            bits.readUe();
            bits.readUe();
            if (bits.readFlag()) {
                bits.readUe();
                bits.readUe();
                bits.readUe();
                bits.readUe();
            }
            bits.readUe();
            bits.readUe();
            var log2MaxPocLsb = bits.readUe() + 4;
            var orderingPresent = bits.readFlag();
            var buffering = 0;
            var reorder = 0;
            var latency = 0;
            for (var layer = orderingPresent ? 0 : subLayers; layer <= subLayers; layer++) {
                buffering = bits.readUe() + 1;
                reorder = bits.readUe();
                latency = bits.readUe();
            }
            if (bits.overrun() || log2MaxPocLsb > 16 || reorder < 0 || reorder > 16 || buffering < 1
                    || buffering > 16) {
                return null;
            }
            var tail = readTail(bits, log2MaxPocLsb);
            var vui = tail == null ? null : tail.vui();
            return new Sps(id, separate, log2MaxPocLsb, reorder, Math.max(0, latency), buffering,
                    tail == null ? null : tail.sets(), tail != null && tail.longTerm(),
                    tail == null ? new int[0] : tail.longTermLsbs(), vui != null && vui[0] != 0,
                    vui != null && vui[1] != 0, vui == null ? 0 : vui[2], vui == null ? 0 : vui[3]);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * The fields of a sequence parameter set after its sub-layer ordering information.
     *
     * @param sets the picture order count differences of each short-term reference picture set
     * @param longTerm {@code long_term_ref_pics_present_flag}
     * @param longTermLsbs {@code lt_ref_pic_poc_lsb_sps} of each long-term reference picture the set lists
     * @param vui the VUI fields {@link #readVui} returns, or {@code null} without a VUI
     */
    private record Tail(int[][] sets, boolean longTerm, int[] longTermLsbs, long[] vui) {
    }

    /**
     * Reads the fields of a sequence parameter set after its sub-layer ordering information.
     *
     * @param bits the reader, positioned at {@code log2_min_luma_coding_block_size_minus3}
     * @param log2MaxPocLsb {@code log2_max_pic_order_cnt_lsb_minus4 + 4}
     * @return the fields, or {@code null} when they cannot be read
     */
    private static Tail readTail(MsbBitReader bits, int log2MaxPocLsb) {
        for (var index = 0; index < 6; index++) {
            bits.readUe();
        }
        if (bits.readFlag() && bits.readFlag()) {
            skipScalingLists(bits);
        }
        bits.skip(2);
        if (bits.readFlag()) {
            bits.skip(8);
            bits.readUe();
            bits.readUe();
            bits.skip(1);
        }
        var count = bits.readUe();
        if (count < 0 || count > MAX_SHORT_TERM_SETS) {
            return null;
        }
        var sets = new int[count][];
        for (var set = 0; set < count; set++) {
            sets[set] = readShortTermSet(bits, set, count, sets);
            if (sets[set] == null) {
                return null;
            }
        }
        var longTerm = bits.readFlag();
        var longTermLsbs = new int[0];
        if (longTerm) {
            var pictures = bits.readUe();
            if (pictures < 0 || pictures > MAX_LONG_TERM) {
                return null;
            }
            longTermLsbs = new int[pictures];
            for (var i = 0; i < pictures; i++) {
                longTermLsbs[i] = bits.read(log2MaxPocLsb);
                bits.skip(1);
            }
        }
        bits.skip(2);
        if (bits.overrun()) {
            return null;
        }
        var vui = bits.readFlag() ? readVui(bits) : null;
        return new Tail(sets, longTerm, longTermLsbs, vui);
    }

    /**
     * Skips the scaling list data of a sequence parameter set (H.265 clause 7.3.4).
     *
     * @param bits the reader
     */
    private static void skipScalingLists(MsbBitReader bits) {
        for (var size = 0; size < 4; size++) {
            for (var matrix = 0; matrix < 6; matrix += size == 3 ? 3 : 1) {
                if (!bits.readFlag()) {
                    bits.readUe();
                } else {
                    var coefficients = Math.min(64, 1 << (4 + (size << 1)));
                    if (size > 1) {
                        bits.readSe();
                    }
                    for (var index = 0; index < coefficients && !bits.overrun(); index++) {
                        bits.readSe();
                    }
                }
            }
        }
    }

    /**
     * Reads a short-term reference picture set (H.265 clauses 7.3.7 and 7.4.8) and derives its picture order count
     * differences.
     *
     * @param bits the reader
     * @param index the index of the set: below {@code count} in a sequence parameter set, {@code count} in a slice
     * segment header
     * @param count {@code num_short_term_ref_pic_sets}
     * @param sets the sets of the sequence parameter set read so far
     * @return the differences, negative ones first from the closest, then positive ones from the closest, or
     * {@code null} for an invalid set
     */
    private static int[] readShortTermSet(MsbBitReader bits, int index, int count, int[][] sets) {
        if (index != 0 && bits.readFlag()) {
            var reference = index - 1;
            if (index == count) {
                reference = index - (bits.readUe() + 1);
            }
            if (reference < 0 || reference >= index || sets[reference] == null) {
                return null;
            }
            var sign = bits.read(1);
            var deltaRps = (1 - 2 * sign) * (bits.readUe() + 1);
            var base = sets[reference];
            var derived = new int[base.length + 1];
            var size = 0;
            for (var j = 0; j <= base.length; j++) {
                var used = bits.readFlag();
                var useDelta = used || bits.readFlag();
                if (useDelta) {
                    var delta = j < base.length ? base[j] + deltaRps : deltaRps;
                    if (delta != 0) {
                        derived[size++] = delta;
                    }
                }
            }
            return order(Arrays.copyOf(derived, size));
        }
        var negative = bits.readUe();
        var positive = bits.readUe();
        if (negative < 0 || positive < 0 || negative > MAX_SET_PICTURES || positive > MAX_SET_PICTURES) {
            return null;
        }
        var deltas = new int[negative + positive];
        var poc = 0;
        for (var i = 0; i < negative; i++) {
            poc -= bits.readUe() + 1;
            bits.skip(1);
            deltas[i] = poc;
        }
        poc = 0;
        for (var i = 0; i < positive; i++) {
            poc += bits.readUe() + 1;
            bits.skip(1);
            deltas[negative + i] = poc;
        }
        return bits.overrun() ? null : deltas;
    }

    /**
     * Orders the differences of a derived set as the explicitly coded sets are: negative ones from the closest,
     * then positive ones from the closest.
     *
     * @param deltas the differences, distinct and not zero
     * @return the same array, reordered
     */
    private static int[] order(int[] deltas) {
        Arrays.sort(deltas);
        var negative = 0;
        while (negative < deltas.length && deltas[negative] < 0) {
            negative++;
        }
        for (var i = 0; i < negative / 2; i++) {
            var swap = deltas[i];
            deltas[i] = deltas[negative - 1 - i];
            deltas[negative - 1 - i] = swap;
        }
        return deltas;
    }

    /**
     * Reads a VUI up to its timing information.
     *
     * @param bits the reader, positioned after {@code vui_parameters_present_flag}
     * @return {@code field_seq_flag}, {@code frame_field_info_present_flag}, {@code vui_num_units_in_tick} and
     * {@code vui_time_scale} (zero without timing information), or {@code null} when it cannot be read
     */
    private static long[] readVui(MsbBitReader bits) {
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
        bits.skip(1);
        var fieldSeq = bits.readFlag();
        var frameFieldInfo = bits.readFlag();
        if (bits.readFlag()) {
            bits.readUe();
            bits.readUe();
            bits.readUe();
            bits.readUe();
        }
        long units = 0;
        long scale = 0;
        if (bits.readFlag()) {
            units = bits.readLong(32);
            scale = bits.readLong(32);
        }
        if (bits.overrun()) {
            return null;
        }
        if (units <= 0 || scale <= 0 || scale > Integer.MAX_VALUE || units > Integer.MAX_VALUE / 2) {
            units = 0;
            scale = 0;
        }
        return new long[]{fieldSeq ? 1 : 0, frameFieldInfo ? 1 : 0, units, scale};
    }

    /**
     * Reads the timing information of a video parameter set.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the fields, or {@code null}
     */
    static Vps vps(byte[] data, int offset, int length) {
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, length - 2));
            var id = bits.read(4);
            bits.skip(8);
            var subLayers = bits.read(3);
            bits.skip(17);
            skipProfileTierLevel(bits, subLayers);
            var orderingPresent = bits.readFlag();
            for (var layer = orderingPresent ? 0 : subLayers; layer <= subLayers; layer++) {
                bits.readUe();
                bits.readUe();
                bits.readUe();
            }
            var maxLayerId = bits.read(6);
            var layerSets = bits.readUe() + 1;
            if (layerSets < 1 || layerSets > 1024) {
                return null;
            }
            bits.skip((long) (layerSets - 1) * (maxLayerId + 1));
            long units = 0;
            long scale = 0;
            if (bits.readFlag()) {
                units = bits.readLong(32);
                scale = bits.readLong(32);
            }
            if (bits.overrun()) {
                return null;
            }
            if (units <= 0 || scale <= 0 || scale > Integer.MAX_VALUE || units > Integer.MAX_VALUE / 2) {
                units = 0;
                scale = 0;
            }
            return new Vps(id, units, scale);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Returns the {@code pic_struct} of an HEVC picture timing SEI message (ITU-T H.265 clause D.2.3).
     *
     * @param payload the message payload, without emulation prevention bytes
     * @param sps the active sequence parameter set
     * @return {@code pic_struct}, or {@code -1} when the set signals none or the payload is empty
     */
    static int picStruct(byte[] payload, Sps sps) {
        if (!sps.frameFieldInfo() || payload.length == 0) {
            return -1;
        }
        return (payload[0] & 0xFF) >>> 4;
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
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, Math.min(length - 2, 64)));
            var id = bits.readUe();
            var spsId = bits.readUe();
            if (id < 0 || id > 63 || spsId < 0 || spsId > 15) {
                return null;
            }
            bits.skip(1);
            var outputFlag = bits.readFlag();
            var extra = bits.read(3);
            if (bits.overrun()) {
                return null;
            }
            return new Pps(id, spsId, outputFlag, extra);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads the header of the first slice segment of a picture up to its reference picture set.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @param sequences the sequence parameter sets met, by identifier
     * @param pictures the picture parameter sets met, by identifier
     * @return the fields, or {@code null} when the header cannot be read, does not start a picture, or names an
     * unknown parameter set
     */
    static Slice slice(byte[] data, int offset, int length, Map<Integer, Sps> sequences, Map<Integer, Pps> pictures) {
        try {
            var type = (data[offset] >> 1) & 0x3F;
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2,
                    Math.min(length - 2, SLICE_HEADER_BYTES)));
            if (!bits.readFlag()) {
                return null;
            }
            var noOutput = false;
            if (type >= 16 && type <= 23) {
                noOutput = bits.readFlag();
            }
            var pps = pictures.get(bits.readUe());
            if (pps == null) {
                return null;
            }
            var sps = sequences.get(pps.spsId());
            if (sps == null) {
                return null;
            }
            bits.skip(pps.extraSliceHeaderBits());
            bits.readUe();
            var output = !pps.outputFlagPresent() || bits.readFlag();
            if (sps.separateColourPlane()) {
                bits.skip(2);
            }
            var idr = type == 19 || type == 20;
            var lsb = 0;
            var shortTerm = new int[0];
            var longTermLsbs = new int[0];
            var longTermCycles = new int[0];
            if (!idr) {
                lsb = bits.read(sps.log2MaxPocLsb());
                var sets = sps.shortTermSets();
                if (sets == null) {
                    return bits.overrun() ? null : new Slice(sps, lsb, output, noOutput, null, longTermLsbs,
                            longTermCycles);
                }
                if (!bits.readFlag()) {
                    shortTerm = readShortTermSet(bits, sets.length, sets.length, sets);
                } else if (sets.length > 0) {
                    var index = sets.length > 1 ? bits.read(32 - Integer.numberOfLeadingZeros(sets.length - 1)) : 0;
                    shortTerm = index < sets.length ? sets[index] : null;
                }
                if (shortTerm == null) {
                    return null;
                }
                if (sps.longTermPresent()) {
                    var fromSps = sps.longTermLsbs().length > 0 ? bits.readUe() : 0;
                    var count = fromSps + bits.readUe();
                    if (fromSps < 0 || count < fromSps || count > MAX_LONG_TERM) {
                        return null;
                    }
                    longTermLsbs = new int[count];
                    longTermCycles = new int[count];
                    var cycle = 0;
                    for (var i = 0; i < count; i++) {
                        if (i < fromSps) {
                            var spsCount = sps.longTermLsbs().length;
                            var at = spsCount > 1 ? bits.read(32 - Integer.numberOfLeadingZeros(spsCount - 1)) : 0;
                            longTermLsbs[i] = at < spsCount ? sps.longTermLsbs()[at] : 0;
                        } else {
                            longTermLsbs[i] = bits.read(sps.log2MaxPocLsb());
                            bits.skip(1);
                        }
                        if (bits.readFlag()) {
                            var value = bits.readUe();
                            cycle = i == 0 || i == fromSps ? value : cycle + value;
                            longTermCycles[i] = cycle;
                        } else {
                            longTermCycles[i] = -1;
                            if (i == 0 || i == fromSps) {
                                cycle = 0;
                            }
                        }
                    }
                }
            }
            if (bits.overrun()) {
                return null;
            }
            return new Slice(sps, lsb, output, noOutput, shortTerm, longTermLsbs, longTermCycles);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Skips a {@code profile_tier_level} structure with its general profile (H.265 clause 7.3.3).
     *
     * @param bits the reader
     * @param subLayers {@code sps_max_sub_layers_minus1}
     */
    private static void skipProfileTierLevel(MsbBitReader bits, int subLayers) {
        bits.skip(96);
        var profiles = new boolean[subLayers];
        var levels = new boolean[subLayers];
        for (var layer = 0; layer < subLayers; layer++) {
            profiles[layer] = bits.readFlag();
            levels[layer] = bits.readFlag();
        }
        if (subLayers > 0) {
            bits.skip(2L * (8 - subLayers));
        }
        for (var layer = 0; layer < subLayers; layer++) {
            bits.skip((profiles[layer] ? 88 : 0) + (levels[layer] ? 8 : 0));
        }
    }
}
