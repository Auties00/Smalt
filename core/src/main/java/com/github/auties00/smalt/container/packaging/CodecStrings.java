package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds the {@code CODECS} values of HLS playlists and the {@code codecs} attributes of DASH
 * manifests (RFC 6381, ISO/IEC 14496-15 Annex E, the VP codec, AV1, Opus and AC-3 ISOBMFF
 * bindings, the sample entry types of the other codecs a CMAF segment carries, the ISO/IEC 14496-30 text
 * tracks with the IMSC profile of a TTML track, and the Dolby Vision {@code dva1}, {@code dvh1} and {@code dav1}
 * strings with the two-digit profile and level of a track whose base layer is not backward compatible).
 */
final class CodecStrings {
    private CodecStrings() {
        throw new AssertionError();
    }

    /**
     * Returns the codec string of a track.
     *
     * @param track the track to describe
     * @param keyFrame the first key frame of a video track, which describes a VP8, VP9, AV1 or MPEG-4
     * Part 2 track without a configuration and a ProRes or DNxHD track, or the first document of a TTML track,
     * which names its IMSC profile, or {@code null}
     * @return the codec string, or {@code null} when the codec has none
     */
    static String of(Track track, byte[] keyFrame) {
        var config = track.codecConfig().orElse(null);
        // TODO: the HLS SUPPLEMENTAL-CODECS of a backward compatible Dolby Vision track (dvh1.08.04/db4h)
        if (track instanceof VideoTrack video && video.dolbyVision().isPresent()
                && !video.dolbyVision().get().isBackwardCompatible()) {
            var prefix = switch (track.codec()) {
                case H264 -> "dva1";
                case HEVC -> "dvh1";
                case AV1 -> "dav1";
                default -> null;
            };
            if (prefix != null) {
                var configuration = video.dolbyVision().get();
                return String.format(Locale.ROOT, "%s.%02d.%02d", prefix, configuration.profile(),
                        configuration.level());
            }
        }
        return switch (track.codec()) {
            case H264 -> config != null && config.length >= 4
                    ? String.format(Locale.ROOT, "avc1.%02x%02x%02x", config[1] & 0xFF, config[2] & 0xFF, config[3] & 0xFF)
                    : "avc1";
            case HEVC -> hevc(config);
            case AV1 -> av1(config != null ? config : keyFrame != null ? CodecConfigs.av1Config(keyFrame) : null);
            case VP9 -> vp("vp09", config != null ? config : keyFrame != null && track instanceof VideoTrack video
                    ? CodecConfigs.vp9Config(keyFrame, video.width(), video.height(), video.color().orElse(null)) : null);
            case VP8 -> vp("vp08", config != null ? config : keyFrame != null && track instanceof VideoTrack video
                    ? CodecConfigs.vp8Config(keyFrame, video.width(), video.height(), video.color().orElse(null)) : null);
            case MPEG4_PART2 -> mpeg4(config != null && config.length > 0 ? config
                    : keyFrame != null ? CodecConfigs.mpeg4Config(keyFrame) : null);
            case H263 -> "s263";
            case MJPEG -> "mp4v.6C";
            case JPEG2000 -> "mjp2";
            case PRORES, DNXHD -> fourCc(CodecConfigs.quickTimeVisualType(track.codec() == Codec.PRORES, config, keyFrame));
            case AAC -> "mp4a.40." + aacObjectType(config);
            case MP3 -> "mp4a.40.34";
            case MP2 -> "mp4a.40.33";
            case OPUS -> "opus";
            case FLAC -> "flac";
            case ALAC -> "alac";
            case AC3 -> "ac-3";
            case EAC3 -> "ec-3";
            case AMR_NB -> "samr";
            case AMR_WB -> "sawb";
            case PCM_S16LE, PCM_S16BE, PCM_S24LE, PCM_S24BE, PCM_S32LE, PCM_S32BE -> "ipcm";
            case PCM_F32LE, PCM_F32BE, PCM_F64LE, PCM_F64BE -> "fpcm";
            case WEBVTT -> "wvtt";
            case TTML -> ttml(config, keyFrame);
            default -> null;
        };
    }

    /**
     * Returns the codec string of a TTML track: {@code stpp.ttml.} and the code of the IMSC profile its
     * configuration's auxiliary MIME types or its first document declare ({@code im1t} and {@code im1i} for the
     * IMSC 1 text and image profiles, {@code im2t} and {@code im2i} for IMSC 1.1), or {@code stpp}.
     *
     * @param config the {@code stpp} configuration, or {@code null}
     * @param document the first document, or {@code null}
     * @return the codec string
     */
    private static String ttml(byte[] config, byte[] document) {
        for (var text : new byte[][]{config, document}) {
            if (text == null) {
                continue;
            }
            var value = new String(text, StandardCharsets.UTF_8);
            for (var code : new String[]{"im1t", "im1i", "im2t", "im2i"}) {
                if (value.contains("codecs=" + code)) {
                    return "stpp.ttml." + code;
                }
            }
            if (value.contains("http://www.w3.org/ns/ttml/profile/imsc1.1/text")) {
                return "stpp.ttml.im2t";
            }
            if (value.contains("http://www.w3.org/ns/ttml/profile/imsc1.1/image")) {
                return "stpp.ttml.im2i";
            }
            if (value.contains("http://www.w3.org/ns/ttml/profile/imsc1/text")) {
                return "stpp.ttml.im1t";
            }
            if (value.contains("http://www.w3.org/ns/ttml/profile/imsc1/image")) {
                return "stpp.ttml.im1i";
            }
        }
        return "stpp";
    }

    /**
     * Returns the codec string of an MPEG-4 Part 2 track (RFC 6381 clause 3.3): the object type
     * {@code 20} and the decimal {@code profile_and_level_indication} of the visual object
     * sequence header, when the configuration holds one.
     *
     * @param config the decoder configuration, or {@code null}
     * @return the codec string
     */
    private static String mpeg4(byte[] config) {
        if (config != null) {
            for (var index = 0; index + 4 < config.length; index++) {
                if (config[index] == 0 && config[index + 1] == 0 && config[index + 2] == 1
                        && (config[index + 3] & 0xFF) == 0xB0) {
                    return "mp4v.20." + (config[index + 4] & 0xFF);
                }
            }
        }
        return "mp4v.20";
    }

    /**
     * {@return a four-character code as a string}
     *
     * @param code the code, four ASCII characters packed big-endian
     */
    private static String fourCc(int code) {
        return new String(new char[]{(char) (code >>> 24), (char) ((code >>> 16) & 0xFF), (char) ((code >>> 8) & 0xFF),
                (char) (code & 0xFF)});
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
     * Returns the codec string of a VP8 or VP9 track from its {@code vpcC} record body (VP codec
     * ISOBMFF binding clause 4): the sample entry type, profile, level and bit depth.
     *
     * @param entry the sample entry type, {@code vp08} or {@code vp09}
     * @param config the record body, or {@code null}
     * @return the codec string
     */
    private static String vp(String entry, byte[] config) {
        if (config == null || config.length < 3) {
            return entry;
        }
        return String.format(Locale.ROOT, "%s.%02d.%02d.%02d", entry, config[0] & 0xFF, config[1] & 0xFF,
                (config[2] & 0xFF) >> 4);
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
