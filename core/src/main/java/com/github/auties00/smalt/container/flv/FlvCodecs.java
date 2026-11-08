package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.track.Codec;

/**
 * Maps the codec identifiers of FLV tags to {@link Codec} constants and back: the legacy {@code CodecID} and
 * {@code SoundFormat} fields (FLV specification E.4.2.1 and E.4.3.1) and the FourCCs of the enhanced headers
 * (Enhanced RTMP v2).
 */
final class FlvCodecs {
    /**
     * The legacy video codec identifier of Sorenson H.263.
     */
    static final int VIDEO_SORENSON = 2;

    /**
     * The legacy video codec identifier of H.264.
     */
    static final int VIDEO_AVC = 7;

    /**
     * The legacy sound format of MP3.
     */
    static final int AUDIO_MP3 = 2;

    /**
     * The legacy sound format of little-endian linear PCM.
     */
    static final int AUDIO_PCM_LE = 3;

    /**
     * The legacy sound format of G.711 A-law.
     */
    static final int AUDIO_ALAW = 7;

    /**
     * The legacy sound format of G.711 mu-law.
     */
    static final int AUDIO_MULAW = 8;

    /**
     * The sound format that announces an enhanced audio header.
     */
    static final int AUDIO_EX_HEADER = 9;

    /**
     * The legacy sound format of AAC.
     */
    static final int AUDIO_AAC = 10;

    /**
     * The legacy sound format of Speex.
     */
    static final int AUDIO_SPEEX = 11;

    /**
     * The legacy sound format of MP3 at 8 kHz.
     */
    static final int AUDIO_MP3_8K = 14;

    private FlvCodecs() {
    }

    /**
     * Returns the codec of a legacy video codec identifier.
     *
     * @param id the {@code CodecID}
     * @return the codec, {@link Codec#UNKNOWN_VIDEO} for Screen video, VP6 and unassigned identifiers
     */
    static Codec ofLegacyVideo(int id) {
        return switch (id) {
            case VIDEO_SORENSON -> Codec.FLV1;
            case VIDEO_AVC -> Codec.H264;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * Returns the codec of an enhanced video FourCC.
     *
     * @param fourCc the FourCC as a big-endian integer
     * @return the codec, {@link Codec#UNKNOWN_VIDEO} for VVC and unassigned FourCCs
     */
    static Codec ofVideoFourCc(int fourCc) {
        return switch (fourCc) {
            case 0x61766331 -> Codec.H264;
            case 0x68766331 -> Codec.HEVC;
            case 0x61763031 -> Codec.AV1;
            case 0x76703039 -> Codec.VP9;
            case 0x76703038 -> Codec.VP8;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * Returns the enhanced video FourCC of a codec.
     *
     * @param codec the codec whose FourCC is wanted
     * @return the FourCC, or {@code null} when no enhanced FourCC names it
     */
    static String videoFourCc(Codec codec) {
        return switch (codec) {
            case H264 -> "avc1";
            case HEVC -> "hvc1";
            case AV1 -> "av01";
            case VP9 -> "vp09";
            case VP8 -> "vp08";
            default -> null;
        };
    }

    /**
     * Returns the codec of an enhanced audio FourCC.
     *
     * @param fourCc the FourCC as a big-endian integer
     * @return the codec, {@link Codec#UNKNOWN_AUDIO} for unassigned FourCCs
     */
    static Codec ofAudioFourCc(int fourCc) {
        return switch (fourCc) {
            case 0x6D703461 -> Codec.AAC;
            case 0x4F707573 -> Codec.OPUS;
            case 0x664C6143 -> Codec.FLAC;
            case 0x2E6D7033 -> Codec.MP3;
            case 0x61632D33 -> Codec.AC3;
            case 0x65632D33 -> Codec.EAC3;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Returns the enhanced audio FourCC of a codec.
     *
     * @param codec the codec whose FourCC is wanted
     * @return the FourCC, or {@code null} when no enhanced FourCC names it
     */
    static String audioFourCc(Codec codec) {
        return switch (codec) {
            case AAC -> "mp4a";
            case OPUS -> "Opus";
            case FLAC -> "fLaC";
            case MP3 -> ".mp3";
            case AC3 -> "ac-3";
            case EAC3 -> "ec-3";
            default -> null;
        };
    }

    /**
     * Returns whether the coded frames of an enhanced video codec carry a composition time offset.
     *
     * @param fourCc the FourCC as a big-endian integer
     * @return {@code true} for H.264, HEVC and VVC
     */
    static boolean hasCompositionTime(int fourCc) {
        return fourCc == 0x61766331 || fourCc == 0x68766331 || fourCc == 0x76766331;
    }

    /**
     * Returns the integer value of a FourCC.
     *
     * @param code the four ASCII characters
     * @return the big-endian value
     */
    static int fourCc(String code) {
        return (code.charAt(0) << 24) | (code.charAt(1) << 16) | (code.charAt(2) << 8) | code.charAt(3);
    }
}
