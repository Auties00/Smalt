package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.SampleAspects;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;

import java.io.ByteArrayOutputStream;

/**
 * Writes the raw byte sequence payloads of the video, sequence and picture parameter sets (ITU-T H.265
 * 7.3.2.1 to 7.3.2.3), of the SEI messages the encoder emits (Annex D) and the
 * {@code HEVCDecoderConfigurationRecord} of ISO/IEC 14496-15 8.3.3.
 *
 * <p>The streams have one layer and one temporal sub-layer, 64x64 coding tree blocks, coding blocks from
 * 8x8 and transform blocks from 4x4 to 32x32, wavefront parallel processing, and every short-term
 * reference picture set in the slice headers.
 */
final class ParameterSets {
    /**
     * {@code log2_max_pic_order_cnt_lsb_minus4 + 4}.
     */
    static final int LOG2_MAX_POC_LSB = 8;

    /**
     * The {@code payloadType} of the decoded picture hash.
     */
    private static final int SEI_PICTURE_HASH = 132;

    /**
     * The {@code payloadType} of the mastering display colour volume.
     */
    private static final int SEI_MASTERING_DISPLAY = 137;

    /**
     * The {@code payloadType} of the content light level information.
     */
    private static final int SEI_CONTENT_LIGHT_LEVEL = 144;

    /**
     * The sample aspect ratios of {@code aspect_ratio_idc} 1 to 16 (ITU-T H.265 Table E.1), as numerator and
     * denominator.
     */
    private static final int[][] ASPECT_RATIOS = {
            {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11}, {20, 11}, {32, 11}, {80, 33},
            {18, 11}, {15, 11}, {64, 33}, {160, 99}, {4, 3}, {3, 2}, {2, 1}
    };

    /**
     * The {@code aspect_ratio_idc} of an explicit ratio.
     */
    private static final int EXTENDED_SAR = 255;

    /**
     * Prevents instantiation.
     */
    private ParameterSets() {
        throw new AssertionError();
    }

    /**
     * Writes the video parameter set.
     *
     * @param s the settings
     * @return the raw byte sequence payload
     */
    static byte[] vps(EncoderSettings s) {
        var w = new MsbBitWriter(64);
        w.write(0, 4);
        w.writeBit(1);
        w.writeBit(1);
        w.write(0, 6);
        w.write(0, 3);
        w.writeBit(1);
        w.write(0xFFFF, 16);
        profileTierLevel(w, s);
        w.writeBit(1);
        w.writeUe(s.maxDecPicBuffering - 1);
        w.writeUe(s.maxNumReorder);
        w.writeUe(0);
        w.write(0, 6);
        w.writeUe(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeTrailingBits();
        return w.toByteArray();
    }

    /**
     * Writes the sequence parameter set.
     *
     * @param s the settings
     * @param sao whether sample adaptive offset is enabled
     * @return the raw byte sequence payload
     */
    static byte[] sps(EncoderSettings s, boolean sao) {
        var w = new MsbBitWriter(128);
        w.write(0, 4);
        w.write(0, 3);
        w.writeBit(1);
        profileTierLevel(w, s);
        w.writeUe(0);
        w.writeUe(s.chromaFormatIdc);
        if (s.chromaFormatIdc == 3) {
            w.writeBit(0);
        }
        w.writeUe(s.codedWidth);
        w.writeUe(s.codedHeight);
        var cropRight = (s.codedWidth - s.width) >> s.chromaShiftX;
        var cropBottom = (s.codedHeight - s.height) >> s.chromaShiftY;
        if (cropRight != 0 || cropBottom != 0) {
            w.writeBit(1);
            w.writeUe(0);
            w.writeUe(cropRight);
            w.writeUe(0);
            w.writeUe(cropBottom);
        } else {
            w.writeBit(0);
        }
        w.writeUe(s.bitDepth - 8);
        w.writeUe(s.bitDepth - 8);
        w.writeUe(LOG2_MAX_POC_LSB - 4);
        w.writeBit(1);
        w.writeUe(s.maxDecPicBuffering - 1);
        w.writeUe(s.maxNumReorder);
        w.writeUe(0);
        w.writeUe(0);
        w.writeUe(s.log2CtbSize - 3);
        w.writeUe(0);
        w.writeUe(3);
        w.writeUe(s.maxTransformDepthInter);
        w.writeUe(s.maxTransformDepthIntra);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(sao ? 1 : 0);
        w.writeBit(0);
        w.writeUe(0);
        w.writeBit(0);
        w.writeBit(s.stillPicture ? 0 : 1);
        w.writeBit(1);
        w.writeBit(1);
        vui(w, s);
        w.writeBit(0);
        w.writeTrailingBits();
        return w.toByteArray();
    }

    /**
     * Writes the picture parameter set.
     *
     * <p>A lossless stream sets {@code transquant_bypass_enabled_flag} and {@code pps_deblocking_filter_disabled_flag}.
     *
     * @param s the settings
     * @param signHiding {@code sign_data_hiding_enabled_flag}
     * @param cuQpDelta {@code cu_qp_delta_enabled_flag}, with {@code diff_cu_qp_delta_depth} 1
     * @return the raw byte sequence payload
     */
    static byte[] pps(EncoderSettings s, boolean signHiding, boolean cuQpDelta) {
        var w = new MsbBitWriter(32);
        w.writeUe(0);
        w.writeUe(0);
        w.writeBit(0);
        w.writeBit(0);
        w.write(0, 3);
        w.writeBit(signHiding ? 1 : 0);
        w.writeBit(0);
        w.writeUe(0);
        w.writeUe(0);
        w.writeSe(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(cuQpDelta ? 1 : 0);
        if (cuQpDelta) {
            w.writeUe(1);
        }
        w.writeSe(0);
        w.writeSe(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(s.lossless ? 1 : 0);
        w.writeBit(0);
        w.writeBit(1);
        w.writeBit(0);
        // deblocking_filter_control_present_flag, then deblocking_filter_override_enabled_flag 0 and
        // pps_deblocking_filter_disabled_flag 1 for a lossless stream
        w.writeBit(s.lossless ? 1 : 0);
        if (s.lossless) {
            w.writeBit(0);
            w.writeBit(1);
        }
        w.writeBit(0);
        w.writeBit(0);
        w.writeUe(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeTrailingBits();
        return w.toByteArray();
    }

    /**
     * Writes {@code profile_tier_level(1, 0)} (7.3.3).
     *
     * @param w the destination
     * @param s the settings
     */
    private static void profileTierLevel(MsbBitWriter w, EncoderSettings s) {
        var profile = s.profile();
        w.write(0, 2);
        w.writeBit(s.highTier ? 1 : 0);
        w.write(profile.profileIdc(), 5);
        var compatibility = compatibility(profile);
        for (var j = 0; j < 32; j++) {
            w.writeBit((compatibility >>> j) & 1);
        }
        w.writeLong(constraints(profile), 48);
        w.write(s.levelIdc, 8);
    }

    /**
     * Returns the {@code general_profile_compatibility_flag} values of a profile, flag {@code j} at bit {@code j}.
     *
     * @param profile the profile the stream signals
     * @return the flags
     */
    private static int compatibility(HevcProfile profile) {
        return switch (profile) {
            case MAIN -> (1 << 1) | (1 << 2);
            case MAIN_10, MAIN_10_STILL_PICTURE -> 1 << 2;
            case MAIN_STILL_PICTURE -> (1 << 1) | (1 << 2) | (1 << 3);
            case MAIN_422_10, MAIN_444, MAIN_444_10, MAIN_444_STILL_PICTURE -> 1 << 4;
        };
    }

    /**
     * Returns the 48 bits of {@code profile_tier_level} from {@code general_progressive_source_flag} to
     * {@code general_inbld_flag}, the first in the most significant bit: a progressive, frame-only source and the
     * constraint flags of the profile (Table A.2 for the format range extensions profiles).
     *
     * @param profile the profile the stream signals
     * @return the bits
     */
    static long constraints(HevcProfile profile) {
        // general_max_12bit, max_10bit, max_8bit, max_422chroma, max_420chroma, max_monochrome, intra,
        // one_picture_only and lower_bit_rate constraint flags, in that order from bit 8 down to bit 0
        var flags = switch (profile) {
            case MAIN, MAIN_10, MAIN_STILL_PICTURE -> 0;
            case MAIN_10_STILL_PICTURE -> 0b000000010;
            case MAIN_422_10 -> 0b110100001;
            case MAIN_444 -> 0b111000001;
            case MAIN_444_10 -> 0b110000001;
            case MAIN_444_STILL_PICTURE -> 0b111000111;
        };
        return (0b1001L << 44) | ((long) flags << 35);
    }

    /**
     * Writes {@code vui_parameters()} (E.2.1): the sample aspect ratio, the color description, the chroma
     * siting and the timing.
     *
     * @param w the destination
     * @param s the settings
     */
    private static void vui(MsbBitWriter w, EncoderSettings s) {
        var sar = s.options.sampleAspectRatio().reduce();
        var square = sar.numerator() == sar.denominator();
        w.writeBit(square ? 0 : 1);
        if (!square) {
            var index = SampleAspects.indexOf(sar, ASPECT_RATIOS);
            w.write(index >= 0 ? index + 1 : EXTENDED_SAR, 8);
            if (index < 0) {
                w.write((int) sar.numerator(), 16);
                w.write((int) sar.denominator(), 16);
            }
        }
        w.writeBit(0);
        ColorInfo color = s.color;
        w.writeBit(1);
        w.write(5, 3);
        w.writeBit(color.range() == ColorRange.FULL ? 1 : 0);
        w.writeBit(1);
        w.write(color.primaries().code(), 8);
        w.write(color.transfer().code(), 8);
        w.write(color.matrix().code(), 8);
        var siting = color.chromaSiting().chromaSampleLocType().orElse(-1);
        w.writeBit(siting >= 0 ? 1 : 0);
        if (siting >= 0) {
            w.writeUe(siting);
            w.writeUe(siting);
        }
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(0);
        w.writeBit(0);
        var timing = !s.stillPicture;
        w.writeBit(timing ? 1 : 0);
        if (timing) {
            Rational rate = s.frameRate();
            w.writeLong(rate.denominator(), 32);
            w.writeLong(rate.numerator(), 32);
            w.writeBit(0);
            w.writeBit(0);
        }
        w.writeBit(0);
    }

    /**
     * Returns the payload of a prefix SEI NAL unit carrying the static high-dynamic-range metadata of the
     * color description: the mastering display colour volume (D.2.28) and the content light level
     * information (D.2.35).
     *
     * @param color the color description
     * @return the raw byte sequence payload, or {@code null} when the description carries neither
     */
    static byte[] staticSei(ColorInfo color) {
        if (color.masteringDisplay().isEmpty() && color.contentLightLevel().isEmpty()) {
            return null;
        }
        var writer = new MsbBitWriter(64);
        color.masteringDisplay().ifPresent(display -> {
            var payload = new MsbBitWriter(24);
            // D.3.28 orders the primaries green, blue, red, in increments of 0.00002, and the luminances in
            // increments of 0.0001 candelas per square meter.
            for (var point : new Chromaticity[]{display.green(), display.blue(), display.red(), display.whitePoint()}) {
                payload.write(chromaticityCode(point.x()), 16);
                payload.write(chromaticityCode(point.y()), 16);
            }
            payload.writeLong(luminanceCode(display.maxLuminance()), 32);
            payload.writeLong(luminanceCode(display.minLuminance()), 32);
            message(writer, SEI_MASTERING_DISPLAY, payload);
        });
        color.contentLightLevel().ifPresent(level -> {
            var payload = new MsbBitWriter(4);
            payload.write(level.maxContentLightLevel(), 16);
            payload.write(level.maxFrameAverageLightLevel(), 16);
            message(writer, SEI_CONTENT_LIGHT_LEVEL, payload);
        });
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Returns the payload of a suffix SEI NAL unit carrying a decoded picture hash (D.2.20).
     *
     * @param type the {@code hash_type}
     * @param hashes the hash of each color component, as {@code PictureHash.compute} returns them
     * @return the raw byte sequence payload
     */
    static byte[] pictureHashSei(int type, byte[][] hashes) {
        var payload = new MsbBitWriter(64);
        payload.write(type, 8);
        for (var hash : hashes) {
            payload.writeBytes(hash, 0, hash.length);
        }
        var writer = new MsbBitWriter(72);
        message(writer, SEI_PICTURE_HASH, payload);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /**
     * Appends one {@code sei_message()} (7.3.5).
     *
     * @param writer the SEI payload being written
     * @param type the {@code payloadType}
     * @param payload the byte-aligned message payload
     */
    private static void message(MsbBitWriter writer, int type, MsbBitWriter payload) {
        var bytes = payload.toByteArray();
        for (var t = type; ; t -= 255) {
            if (t < 255) {
                writer.write(t, 8);
                break;
            }
            writer.write(255, 8);
        }
        for (var size = bytes.length; ; size -= 255) {
            if (size < 255) {
                writer.write(size, 8);
                break;
            }
            writer.write(255, 8);
        }
        writer.writeBytes(bytes, 0, bytes.length);
    }

    /**
     * Returns the 16-bit code of a chromaticity coordinate in increments of 0.00002.
     *
     * @param value the coordinate
     * @return the code, clipped to 50000 as D.3.28 requires
     */
    private static int chromaticityCode(double value) {
        return (int) Math.clamp(Math.round(value / 0.00002), 0, 50_000);
    }

    /**
     * Returns the 32-bit code of a luminance in increments of 0.0001 candelas per square meter.
     *
     * @param value the luminance
     * @return the code, clipped to the 32-bit range
     */
    private static long luminanceCode(double value) {
        return Math.clamp(Math.round(value / 0.0001), 0, 0xFFFF_FFFFL);
    }

    /**
     * Returns the {@code HEVCDecoderConfigurationRecord} of a stream (ISO/IEC 14496-15 8.3.3.1).
     *
     * @param s the settings
     * @param vps the video parameter set payload
     * @param sps the sequence parameter set payload
     * @param pps the picture parameter set payload
     * @param sei the payload of the prefix SEI message every IDR access unit carries, or {@code null}
     * @return the record
     */
    static byte[] configurationRecord(EncoderSettings s, byte[] vps, byte[] sps, byte[] pps, byte[] sei) {
        var out = new ByteArrayOutputStream(64 + vps.length + sps.length + pps.length);
        var profile = s.profile();
        out.write(1);
        out.write((s.highTier ? 0x20 : 0) | profile.profileIdc());
        var flags = Integer.reverse(compatibility(profile));
        out.write(flags >>> 24);
        out.write(flags >>> 16);
        out.write(flags >>> 8);
        out.write(flags);
        var constraints = constraints(profile);
        for (var shift = 40; shift >= 0; shift -= 8) {
            out.write((int) (constraints >>> shift) & 0xFF);
        }
        out.write(s.levelIdc);
        out.write(0xF0);
        out.write(0x00);
        out.write(0xFC);
        out.write(0xFC | s.chromaFormatIdc);
        out.write(0xF8 | (s.bitDepth - 8));
        out.write(0xF8 | (s.bitDepth - 8));
        out.write(0);
        out.write(0);
        // constantFrameRate 0, numTemporalLayers 1, temporalIdNested 1, lengthSizeMinusOne 3
        out.write((1 << 3) | (1 << 2) | 3);
        var arrays = sei == null ? 3 : 4;
        out.write(arrays);
        array(out, HevcNal.VPS, vps);
        array(out, HevcNal.SPS, sps);
        array(out, HevcNal.PPS, pps);
        if (sei != null) {
            array(out, HevcNal.PREFIX_SEI, sei);
        }
        return out.toByteArray();
    }

    /**
     * Appends an array of one NAL unit to a configuration record.
     *
     * @param out the record
     * @param type the NAL unit type
     * @param payload the raw byte sequence payload
     */
    private static void array(ByteArrayOutputStream out, int type, byte[] payload) {
        var unit = NalWriter.unit(type, payload);
        out.write(0x80 | type);
        out.write(0);
        out.write(1);
        out.write(unit.length >>> 8);
        out.write(unit.length);
        out.write(unit, 0, unit.length);
    }
}
