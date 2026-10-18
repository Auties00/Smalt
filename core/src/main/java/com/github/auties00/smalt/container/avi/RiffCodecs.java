package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.track.Codec;

import java.util.Locale;

/**
 * Maps the video FourCCs of AVI streams to {@link Codec}s and back: the compression FourCC of a
 * {@code BITMAPINFOHEADER}, or the handler FourCC of the stream header.
 */
final class RiffCodecs {
    private RiffCodecs() {
        throw new AssertionError();
    }

    /**
     * Maps a video FourCC.
     *
     * @param fourCc the FourCC, any case
     * @return the codec, {@link Codec#UNKNOWN_VIDEO} when unrecognized
     */
    static Codec video(String fourCc) {
        return switch (fourCc.toUpperCase(Locale.ROOT)) {
            case "H264", "X264", "AVC1", "DAVC", "SMV2", "VSSH" -> Codec.H264;
            case "HEVC", "H265", "HVC1", "HEV1", "X265" -> Codec.HEVC;
            case "XVID", "DIVX", "DX50", "FMP4", "MP4V", "MP4S", "M4S2", "3IV2", "RMP4", "SMP4", "UMP4", "XVIX", "BLZ0",
                 "DXGM", "EM4A", "EPHV", "FVFW", "GEOV", "HDX4", "SEDG", "WV1F", "DIGI", "INMC", "PVMM", "M4CC",
                 "SN40", "UNDF", "WAWV", "ZMP4", "DMK2", "DM4V", "QMP4" -> Codec.MPEG4_PART2;
            case "MJPG", "AVRN", "LJPG", "JPGL", "AVDJ", "DMB1", "MJLS", "ACDV", "QIVG", "SLMJ", "CJPG", "IJPG" ->
                    Codec.MJPEG;
            case "H263", "S263", "U263", "X263", "M263", "L263", "VX1K", "ZYGO", "VIV1", "T263", "LSVX" -> Codec.H263;
            case "VP80" -> Codec.VP8;
            case "VP90" -> Codec.VP9;
            case "AV01" -> Codec.AV1;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * Returns the FourCC AVI stores for a video codec.
     *
     * @param codec the video codec
     * @return the FourCC, or {@code null} when AVI cannot carry it
     */
    static String videoFourCc(Codec codec) {
        return switch (codec) {
            case MJPEG -> "MJPG";
            case MPEG4_PART2 -> "FMP4";
            case H264 -> "H264";
            case HEVC -> "HEVC";
            case H263 -> "H263";
            case VP8 -> "VP80";
            case VP9 -> "VP90";
            default -> null;
        };
    }
}
