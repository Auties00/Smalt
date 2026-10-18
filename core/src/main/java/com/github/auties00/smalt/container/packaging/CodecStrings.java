package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Locale;

/**
 * Builds the {@code CODECS} values of HLS playlists and the {@code codecs} attributes of DASH
 * manifests (RFC 6381, ISO/IEC 14496-15 Annex E, and the VP9, AV1, Opus and AC-3 ISOBMFF
 * bindings).
 */
final class CodecStrings {
    private CodecStrings() {
        throw new AssertionError();
    }

    /**
     * Returns the codec string of a track.
     *
     * @param track the track to describe
     * @param keyFrame the first key frame of a VP9 or AV1 track without a configuration record, or
     * {@code null}
     * @return the codec string, or {@code null} when the codec has none
     */
    static String of(Track track, byte[] keyFrame) {
        var config = track.codecConfig().orElse(null);
        return switch (track.codec()) {
            case H264 -> config != null && config.length >= 4
                    ? String.format(Locale.ROOT, "avc1.%02x%02x%02x", config[1] & 0xFF, config[2] & 0xFF, config[3] & 0xFF)
                    : "avc1";
            case HEVC -> hevc(config);
            case AV1 -> av1(config != null ? config : keyFrame != null ? CodecConfigs.av1Config(keyFrame) : null);
            case VP9 -> vp9(config != null ? config : keyFrame != null && track instanceof VideoTrack video
                    ? CodecConfigs.vp9Config(keyFrame, video.width(), video.height(), video.color().orElse(null)) : null);
            case VP8 -> "vp8";
            case AAC -> "mp4a.40." + aacObjectType(config);
            case MP3 -> "mp4a.40.34";
            case MP2 -> "mp4a.40.33";
            case OPUS -> "opus";
            case FLAC -> "flac";
            case ALAC -> "alac";
            case AC3 -> "ac-3";
            case EAC3 -> "ec-3";
            default -> null;
        };
    }

    /**
     * Returns the codec string of an HEVC track from its {@code hvcC} record (ISO/IEC 14496-15
     * clause E.3): the sample entry, the profile space and profile, the compatibility flags in
     * reverse bit order, the tier and level, and the constraint bytes without trailing zeros.
     *
     * @param config the record, or {@code null}
     * @return the codec string
     */
    private static String hevc(byte[] config) {
        if (config == null || config.length < 13) {
            return "hvc1";
        }
        var space = (config[1] & 0xFF) >> 6;
        var tier = (config[1] >> 5) & 1;
        var profile = config[1] & 0x1F;
        var compatibility = ((config[2] & 0xFFL) << 24) | ((config[3] & 0xFFL) << 16) | ((config[4] & 0xFFL) << 8)
                | (config[5] & 0xFFL);
        var reversed = Long.reverse(compatibility) >>> 32;
        var builder = new StringBuilder("hvc1.");
        if (space > 0) {
            builder.append((char) ('A' + space - 1));
        }
        builder.append(profile).append('.').append(Long.toHexString(reversed).toUpperCase(Locale.ROOT))
                .append('.').append(tier == 0 ? 'L' : 'H').append(config[12] & 0xFF);
        var last = 11;
        while (last >= 6 && config[last] == 0) {
            last--;
        }
        for (var index = 6; index <= last; index++) {
            builder.append('.').append(String.format(Locale.ROOT, "%02X", config[index] & 0xFF));
        }
        return builder.toString();
    }

    /**
     * Returns the codec string of an AV1 track from its {@code av1C} record (AV1 ISOBMFF binding
     * clause 5): profile, level, tier and bit depth.
     *
     * @param config the record, or {@code null}
     * @return the codec string
     */
    private static String av1(byte[] config) {
        if (config == null || config.length < 4) {
            return "av01";
        }
        var profile = (config[1] & 0xFF) >> 5;
        var level = config[1] & 0x1F;
        var tier = (config[2] & 0x80) != 0 ? 'H' : 'M';
        var depth = (config[2] & 0x40) != 0 ? ((config[2] & 0x20) != 0 ? 12 : 10) : 8;
        return String.format(Locale.ROOT, "av01.%d.%02d%c.%02d", profile, level, tier, depth);
    }

    /**
     * Returns the codec string of a VP9 track from its {@code vpcC} record body (VP codec ISOBMFF
     * binding clause 4): profile, level and bit depth.
     *
     * @param config the record body, or {@code null}
     * @return the codec string
     */
    private static String vp9(byte[] config) {
        if (config == null || config.length < 3) {
            return "vp09";
        }
        return String.format(Locale.ROOT, "vp09.%02d.%02d.%02d", config[0] & 0xFF, config[1] & 0xFF, (config[2] & 0xFF) >> 4);
    }

    /**
     * Returns the audio object type an {@code AudioSpecificConfig} starts with.
     *
     * @param config the configuration, or {@code null}
     * @return the object type, {@code 2} (AAC-LC) when unknown
     */
    private static int aacObjectType(byte[] config) {
        if (config == null || config.length < 2) {
            return 2;
        }
        var type = (config[0] & 0xFF) >> 3;
        if (type == 31) {
            type = 32 + (((config[0] & 0x07) << 3) | ((config[1] & 0xFF) >> 5));
        }
        return type;
    }
}
