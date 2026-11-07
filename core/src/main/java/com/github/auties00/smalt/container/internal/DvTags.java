package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.header.DifProfile;

import java.util.Locale;

/**
 * Maps the identifiers containers give DV video: the QuickTime sample entry types and the FourCCs of AVI and of
 * Matroska's {@code V_MS/VFW/FOURCC} bitmap headers.
 *
 * <p>This class is thread-safe.
 */
public final class DvTags {
    private DvTags() {
        throw new AssertionError();
    }

    /**
     * Returns whether a QuickTime sample entry type is DV video.
     *
     * @param type the four characters, case sensitive
     * @return {@code true} for {@code dvc }, {@code dvcp}, {@code dvpp}, {@code dvpn}, {@code dv5n}, {@code dv5p},
     * the DVCPRO HD types {@code dvh2}, {@code dvh3}, {@code dvh5}, {@code dvh6}, {@code dvhp}, {@code dvhq}, and the
     * Avid types {@code AVdv} and {@code AVd1}
     */
    public static boolean quickTime(String type) {
        return switch (type) {
            case "dvc ", "dvcp", "dvpp", "dvpn", "dv5n", "dv5p", "dvh2", "dvh3", "dvh5", "dvh6", "dvhp", "dvhq",
                 "AVdv", "AVd1" -> true;
            default -> false;
        };
    }

    /**
     * Returns whether a video FourCC of a bitmap header is DV video.
     *
     * @param fourCc the FourCC, any case
     * @return {@code true} for the DV FourCCs of AVI files, such as {@code dvsd}, {@code dv25}, {@code dv50} and
     * {@code dvh1}
     */
    public static boolean riff(String fourCc) {
        return switch (fourCc.toUpperCase(Locale.ROOT)) {
            case "DVSD", "DVHD", "DVSL", "DV25", "DV50", "CDVC", "CDVH", "CDV5", "DVC ", "DVCS", "DVH1", "DVIS",
                 "PDVC", "SL25", "SLDV", "AVD1" -> true;
            default -> false;
        };
    }

    /**
     * Returns the QuickTime sample entry type of a DV format.
     *
     * @param profile the format
     * @return the four characters
     */
    public static String quickTimeType(DifProfile profile) {
        return switch (profile) {
            case DV_525, DVCPRO_525 -> "dvc ";
            case DV_625 -> "dvcp";
            case DVCPRO_625 -> "dvpp";
            case DVCPRO50_525 -> "dv5n";
            case DVCPRO50_625 -> "dv5p";
            case DVCPRO_HD_1080I60 -> "dvh6";
            case DVCPRO_HD_1080I50 -> "dvh5";
            case DVCPRO_HD_720P60 -> "dvhp";
            case DVCPRO_HD_720P50 -> "dvhq";
        };
    }

    /**
     * Returns the compressor name QuickTime gives a DV format.
     *
     * @param profile the format
     * @return the name, ASCII, at most 31 characters
     */
    public static String compressorName(DifProfile profile) {
        return switch (profile) {
            case DV_525, DVCPRO_525 -> "DV/DVCPRO - NTSC";
            case DV_625 -> "DV - PAL";
            case DVCPRO_625 -> "DVCPRO - PAL";
            case DVCPRO50_525 -> "DVCPRO50 - NTSC";
            case DVCPRO50_625 -> "DVCPRO50 - PAL";
            case DVCPRO_HD_1080I60 -> "DVCPRO HD 1080i60";
            case DVCPRO_HD_1080I50 -> "DVCPRO HD 1080i50";
            case DVCPRO_HD_720P60 -> "DVCPRO HD 720p60";
            case DVCPRO_HD_720P50 -> "DVCPRO HD 720p50";
        };
    }

    /**
     * Returns the bitmap header FourCC of DV video of a picture size: {@code dvsd} for the 25 and 50 Mb/s formats,
     * whose frames name their format themselves, and {@code dvh1} for DVCPRO HD.
     *
     * @param width the coded width
     * @return the FourCC
     */
    public static String riffFourCc(int width) {
        return width > 720 ? "dvh1" : "dvsd";
    }
}
