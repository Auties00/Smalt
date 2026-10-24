package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Map;

/**
 * Reads the fields of HEVC parameter sets and slice segment headers (ITU-T H.265 clauses 7.3.2.2, 7.3.2.3 and
 * 7.3.6.1) that order the pictures of an Annex B stream.
 *
 * <p>Every method returns {@code null} for a unit it cannot read.
 */
final class HevcSyntax {
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
     */
    record Sps(int id, boolean separateColourPlane, int log2MaxPocLsb, int reorderPictures) {
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
     */
    record Slice(Sps sps, int pocLsb, boolean output) {
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
            var reorder = 0;
            for (var layer = orderingPresent ? 0 : subLayers; layer <= subLayers; layer++) {
                bits.readUe();
                reorder = bits.readUe();
                bits.readUe();
            }
            if (bits.overrun() || log2MaxPocLsb > 16 || reorder < 0 || reorder > 16) {
                return null;
            }
            return new Sps(id, separate, log2MaxPocLsb, reorder);
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
     * Reads the header of the first slice segment of a picture.
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
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 2, Math.min(length - 2, 64)));
            if (!bits.readFlag()) {
                return null;
            }
            if (type >= 16 && type <= 23) {
                bits.skip(1);
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
            var lsb = type == 19 || type == 20 ? 0 : bits.read(sps.log2MaxPocLsb());
            if (bits.overrun()) {
                return null;
            }
            return new Slice(sps, lsb, output);
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
