package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalRecords;

import java.util.List;

/**
 * Builds and takes apart the configuration records of the NAL-unit video codecs: the H.264
 * {@code AVCDecoderConfigurationRecord} ({@code avcC}, ISO/IEC 14496-15 clause 5.3.3), the HEVC
 * {@code HEVCDecoderConfigurationRecord} ({@code hvcC}, clause 8.3.3) and the VVC
 * {@code VvcDecoderConfigurationRecord} ({@code vvcC}, clause 11.2.4.2).
 *
 * <p>Demuxers of Annex B streams (MPEG transport streams) build a record from the parameter sets
 * they find in band, so that a track describes itself like an MP4 or Matroska track does; muxers
 * of Annex B streams take a record apart to repeat its parameter sets before key frames. Parameter
 * sets are always handled as whole NAL units, header included, with their emulation prevention
 * bytes. The parsers live in {@link NalRecords}; this class keeps the lenient entry points of the
 * containers.
 */
public final class NalConfigs {
    /**
     * The H.264 NAL unit type of a sequence parameter set.
     */
    public static final int AVC_SPS = 7;

    /**
     * The H.264 NAL unit type of a picture parameter set.
     */
    public static final int AVC_PPS = 8;

    /**
     * The HEVC NAL unit type of a video parameter set.
     */
    public static final int HEVC_VPS = 32;

    /**
     * The HEVC NAL unit type of a sequence parameter set.
     */
    public static final int HEVC_SPS = 33;

    /**
     * The HEVC NAL unit type of a picture parameter set.
     */
    public static final int HEVC_PPS = 34;

    /**
     * The largest number of parameter sets of one type a record built here holds.
     */
    private static final int MAX_SETS = 31;

    /**
     * Prevents instantiation.
     */
    private NalConfigs() {
        throw new AssertionError();
    }

    /**
     * Builds an {@code AVCDecoderConfigurationRecord} from in-band parameter sets.
     *
     * <p>The profile, compatibility and level bytes are copied from the first sequence parameter
     * set, and NAL unit lengths are declared as four bytes. For the High profiles (profile
     * {@code 100} and above) the record ends with the chroma format and bit depths of the first
     * sequence parameter set and no extended sets, as the record syntax requires.
     *
     * @param sps the sequence parameter sets, each a whole NAL unit; at least one, at most 31
     * @param pps the picture parameter sets, each a whole NAL unit; at most 255
     * @return the record, or {@code null} when a parameter set is too short or too long to be stored, or there is no
     * sequence parameter set
     */
    public static byte[] avcConfig(List<byte[]> sps, List<byte[]> pps) {
        if (sps.isEmpty() || sps.size() > MAX_SETS || pps.size() > 255) {
            return null;
        }
        var first = sps.getFirst();
        if (first.length < 4) {
            return null;
        }
        var size = 7;
        for (var unit : sps) {
            if (unit.length < 4 || unit.length > 0xFFFF) {
                return null;
            }
            size += 2 + unit.length;
        }
        for (var unit : pps) {
            if (unit.length < 2 || unit.length > 0xFFFF) {
                return null;
            }
            size += 2 + unit.length;
        }
        var profile = first[1] & 0xFF;
        var extension = profile == 100 || profile == 110 || profile == 122 || profile == 144;
        var format = extension ? avcFormat(first) : null;
        if (extension && format == null) {
            extension = false;
        }
        var out = new byte[size + (extension ? 4 : 0)];
        out[0] = 1;
        out[1] = first[1];
        out[2] = first[2];
        out[3] = first[3];
        out[4] = (byte) 0xFF;
        out[5] = (byte) (0xE0 | sps.size());
        var position = 6;
        for (var unit : sps) {
            position = putUnit(out, position, unit);
        }
        out[position++] = (byte) pps.size();
        for (var unit : pps) {
            position = putUnit(out, position, unit);
        }
        if (extension) {
            out[position++] = (byte) (0xFC | format[0]);
            out[position++] = (byte) (0xF8 | (format[1] - 8));
            out[position++] = (byte) (0xF8 | (format[2] - 8));
            out[position] = 0;
        }
        return out;
    }

    /**
     * Reads the chroma format and bit depths of an H.264 sequence parameter set of a High
     * profile.
     *
     * @param sps the sequence parameter set NAL unit
     * @return {@code chroma_format_idc}, the luma bit depth and the chroma bit depth, or {@code null} when the unit is
     * truncated
     */
    private static int[] avcFormat(byte[] sps) {
        try {
            var bits = new MsbBitReader(VideoConfigs.unescape(sps, 1));
            bits.skip(24);
            bits.readUe();
            var chroma = bits.readUe();
            if (chroma > 3) {
                return null;
            }
            if (chroma == 3) {
                bits.readFlag();
            }
            var luma = bits.readUe() + 8;
            var chromaDepth = bits.readUe() + 8;
            if (bits.overrun() || luma > 14 || chromaDepth > 14) {
                return null;
            }
            return new int[]{chroma, luma, chromaDepth};
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Builds an {@code HEVCDecoderConfigurationRecord} from in-band parameter sets.
     *
     * <p>The general profile, tier, compatibility, constraint and level fields are copied from the
     * profile-tier-level structure of the first sequence parameter set, which also gives the
     * chroma format, the bit depths, the number of temporal layers and the temporal nesting
     * flag. The spatial segmentation, parallelism and frame rate fields are declared unknown, NAL
     * unit lengths are declared as four bytes, and the arrays (video, sequence and picture
     * parameter sets, each present only when non-empty) are marked complete.
     *
     * @param vps the video parameter sets, each a whole NAL unit
     * @param sps the sequence parameter sets, each a whole NAL unit; at least one
     * @param pps the picture parameter sets, each a whole NAL unit
     * @return the record, or {@code null} when there is no sequence parameter set or the first one does not parse
     */
    public static byte[] hevcConfig(List<byte[]> vps, List<byte[]> sps, List<byte[]> pps) {
        if (sps.isEmpty() || vps.size() > 0xFFFF || sps.size() > 0xFFFF || pps.size() > 0xFFFF) {
            return null;
        }
        var fields = hevcFields(sps.getFirst());
        if (fields == null) {
            return null;
        }
        var size = 23;
        var arrays = 0;
        for (var list : List.of(vps, sps, pps)) {
            if (list.isEmpty()) {
                continue;
            }
            arrays++;
            size += 3;
            for (var unit : list) {
                if (unit.length < 3 || unit.length > 0xFFFF) {
                    return null;
                }
                size += 2 + unit.length;
            }
        }
        var out = new byte[size];
        out[0] = 1;
        System.arraycopy(fields.profileTierLevel, 0, out, 1, 12);
        out[13] = (byte) 0xF0;
        out[14] = 0;
        out[15] = (byte) 0xFC;
        out[16] = (byte) (0xFC | fields.chroma);
        out[17] = (byte) (0xF8 | (fields.lumaDepth - 8));
        out[18] = (byte) (0xF8 | (fields.chromaDepth - 8));
        out[19] = 0;
        out[20] = 0;
        out[21] = (byte) ((fields.temporalLayers << 3) | (fields.temporalNested ? 0x04 : 0) | 0x03);
        out[22] = (byte) arrays;
        var position = 23;
        var types = new int[]{HEVC_VPS, HEVC_SPS, HEVC_PPS};
        var lists = List.of(vps, sps, pps);
        for (var index = 0; index < 3; index++) {
            var list = lists.get(index);
            if (list.isEmpty()) {
                continue;
            }
            out[position] = (byte) (0x80 | types[index]);
            out[position + 1] = (byte) (list.size() >> 8);
            out[position + 2] = (byte) list.size();
            position += 3;
            for (var unit : list) {
                position = putUnit(out, position, unit);
            }
        }
        return out;
    }

    /**
     * Holds the fields of an HEVC sequence parameter set an {@code hvcC} record repeats.
     *
     * @param profileTierLevel the twelve bytes of the general profile, tier and level
     * @param chroma {@code chroma_format_idc}
     * @param lumaDepth the luma bit depth
     * @param chromaDepth the chroma bit depth
     * @param temporalLayers {@code sps_max_sub_layers_minus1 + 1}
     * @param temporalNested {@code sps_temporal_id_nesting_flag}
     */
    private record HevcFields(byte[] profileTierLevel, int chroma, int lumaDepth, int chromaDepth, int temporalLayers,
                              boolean temporalNested) {
    }

    /**
     * Parses the fields of an HEVC sequence parameter set an {@code hvcC} record repeats (ITU-T
     * H.265 clauses 7.3.2.2 and 7.3.3).
     *
     * @param sps the sequence parameter set NAL unit, two-byte header included
     * @return the fields, or {@code null} when the unit is truncated or invalid
     */
    private static HevcFields hevcFields(byte[] sps) {
        try {
            var rbsp = VideoConfigs.unescape(sps, 2);
            if (rbsp.length < 14) {
                return null;
            }
            var bits = new MsbBitReader(rbsp);
            bits.read(4);
            var maxSubLayersMinus1 = bits.read(3);
            var nested = bits.readFlag();
            var profileTierLevel = new byte[12];
            System.arraycopy(rbsp, 1, profileTierLevel, 0, 12);
            bits.skip(96);
            var profilePresent = new boolean[8];
            var levelPresent = new boolean[8];
            for (var index = 0; index < maxSubLayersMinus1; index++) {
                profilePresent[index] = bits.readFlag();
                levelPresent[index] = bits.readFlag();
            }
            if (maxSubLayersMinus1 > 0) {
                bits.skip(2L * (8 - maxSubLayersMinus1));
            }
            for (var index = 0; index < maxSubLayersMinus1; index++) {
                if (profilePresent[index]) {
                    bits.skip(88);
                }
                if (levelPresent[index]) {
                    bits.skip(8);
                }
            }
            bits.readUe();
            var chroma = bits.readUe();
            if (chroma > 3) {
                return null;
            }
            if (chroma == 3) {
                bits.readFlag();
            }
            bits.readUe();
            bits.readUe();
            if (bits.readFlag()) {
                bits.readUe();
                bits.readUe();
                bits.readUe();
                bits.readUe();
            }
            var luma = bits.readUe() + 8;
            var chromaDepth = bits.readUe() + 8;
            if (bits.overrun() || luma > 16 || chromaDepth > 16) {
                return null;
            }
            return new HevcFields(profileTierLevel, chroma, luma, chromaDepth, maxSubLayersMinus1 + 1, nested);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Takes an {@code AVCDecoderConfigurationRecord} apart, tolerating damage, as
     * {@link NalRecords#parseAvc(byte[])} does.
     *
     * @param config the record, or {@code null}
     * @return the length prefix size and the sequence then picture parameter sets, or {@code null} when the record is
     * {@code null}, truncated or not version 1
     */
    public static NalRecords.Parsed parseAvc(byte[] config) {
        return NalRecords.parseAvc(config);
    }

    /**
     * Takes an {@code HEVCDecoderConfigurationRecord} apart, tolerating damage, as
     * {@link NalRecords#parseHevc(byte[])} does.
     *
     * @param config the record, or {@code null}
     * @return the length prefix size and the parameter sets of every array, or {@code null} when the record is
     * {@code null} or truncated
     */
    public static NalRecords.Parsed parseHevc(byte[] config) {
        return NalRecords.parseHevc(config);
    }

    /**
     * Takes a {@code VvcDecoderConfigurationRecord} apart, tolerating damage, as
     * {@link NalRecords#parseVvc(byte[])} does.
     *
     * @param config the record, with or without the {@code vvcC} full box header, or {@code null}
     * @return the length prefix size and the parameter sets of every array, or {@code null} when the record is
     * {@code null} or truncated
     */
    public static NalRecords.Parsed parseVvc(byte[] config) {
        return NalRecords.parseVvc(config);
    }

    /**
     * Builds a {@code VvcDecoderConfigurationRecord} (ISO/IEC 14496-15 clause 11.2.4.2), without the version and
     * flags of the {@code vvcC} full box, from in-band parameter sets.
     *
     * <p>NAL unit lengths are declared as four bytes. When the first sequence parameter set carries a profile, tier
     * and level structure, the record holds it as its {@code VvcPTLRecord} with {@code ols_idx} 0, the number of
     * sublayers, chroma format and bit depth of that set, {@code constant_frame_rate} 1 when its timing information
     * fixes the picture rate (0 otherwise), its maximum picture size and an {@code avg_frame_rate} of 0
     * (unspecified); otherwise {@code ptl_present_flag} is zero. When the first video parameter set describes
     * several layers, the record describes the output layer set with the most layers (the lowest index among equals,
     * as {@code ols_idx}) instead: the profile, tier and level structure and number of sublayers the video parameter
     * set assigns to it, the chroma format, bit depth and picture size of its decoded picture buffer when it has
     * several layers (those of the sequence parameter set of its highest layer otherwise), and the timing of the
     * sequence parameter set of its highest layer. The arrays (video, sequence and picture parameter
     * sets, each present only when non-empty) are marked complete.
     *
     * @param vps the video parameter sets, each a whole NAL unit
     * @param sps the sequence parameter sets, each a whole NAL unit; at least one
     * @param pps the picture parameter sets, each a whole NAL unit
     * @return the record, or {@code null} when there is no sequence parameter set, the first one does not parse, or a
     * set is too short or too long to be stored
     */
    public static byte[] vvcConfig(List<byte[]> vps, List<byte[]> sps, List<byte[]> pps) {
        if (sps.isEmpty() || vps.size() > 0xFFFF || sps.size() > 0xFFFF || pps.size() > 0xFFFF) {
            return null;
        }
        var first = sps.getFirst();
        var fields = VvcSyntax.sps(first, 0, first.length);
        if (fields == null) {
            return null;
        }
        var layered = vps.isEmpty() ? null : VvcSyntax.vps(vps.getFirst(), 0, vps.getFirst().length);
        if (layered != null && layered.maxLayers() > 1) {
            var top = layered.layerIds()[layered.layerIds().length - 1];
            for (var unit : sps) {
                if (unit.length >= 2 && VvcSyntax.layer(unit, 0) == top) {
                    var parsed = VvcSyntax.sps(unit, 0, unit.length);
                    if (parsed != null) {
                        fields = parsed;
                    }
                    break;
                }
            }
        } else {
            layered = null;
        }
        var out = new java.io.ByteArrayOutputStream();
        var ptl = layered != null ? layered.profileTierLevel() : fields.profileTierLevel();
        out.write(0xF8 | (3 << 1) | (ptl != null ? 1 : 0));
        if (ptl != null) {
            var olsIndex = layered != null ? layered.olsIndex() : 0;
            var sublayers = layered != null ? layered.maxSubLayers() : fields.maxSubLayers();
            var constant = fields.constantRate() ? 1 : 0;
            var multiLayerSet = layered != null && layered.chromaFormatIdc() >= 0;
            var chroma = multiLayerSet ? layered.chromaFormatIdc() : fields.chromaFormatIdc();
            var bitDepth = multiLayerSet ? layered.bitDepth() : fields.bitDepth();
            var width = multiLayerSet ? layered.width() : fields.maxWidth();
            var height = multiLayerSet ? layered.height() : fields.maxHeight();
            var constraintBytes = layered != null ? layered.constraintBytes() : fields.constraintBytes();
            out.write(olsIndex >> 1);
            out.write(((olsIndex & 1) << 7) | (sublayers << 4) | (constant << 2) | chroma);
            out.write(((bitDepth - 8) << 5) | 0x1F);
            out.write(constraintBytes & 0x3F);
            out.writeBytes(ptl);
            out.write(width >> 8);
            out.write(width);
            out.write(height >> 8);
            out.write(height);
            out.write(0);
            out.write(0);
        }
        var types = new int[]{VvcSyntax.VPS, VvcSyntax.SPS, VvcSyntax.PPS};
        var lists = List.of(vps, sps, pps);
        var arrays = 0;
        for (var list : lists) {
            if (!list.isEmpty()) {
                arrays++;
            }
        }
        out.write(arrays);
        for (var index = 0; index < 3; index++) {
            var list = lists.get(index);
            if (list.isEmpty()) {
                continue;
            }
            out.write(0x80 | types[index]);
            out.write(list.size() >> 8);
            out.write(list.size());
            for (var unit : list) {
                if (unit.length < 3 || unit.length > 0xFFFF) {
                    return null;
                }
                out.write(unit.length >> 8);
                out.write(unit.length);
                out.writeBytes(unit);
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes one length-prefixed NAL unit of a record.
     *
     * @param out the record being built
     * @param position the index of the length field
     * @param unit the NAL unit
     * @return the index after the unit
     */
    private static int putUnit(byte[] out, int position, byte[] unit) {
        out[position] = (byte) (unit.length >> 8);
        out[position + 1] = (byte) unit.length;
        System.arraycopy(unit, 0, out, position + 2, unit.length);
        return position + 2 + unit.length;
    }
}
