package com.github.auties00.smalt.image.jxs.internal;

/**
 * The coding parameters each profile of ISO/IEC 21122-2 prescribes or defaults to, for the encoder.
 *
 * @param code the profile code {@code Ppih}
 * @param colorTransform the color transformation: -1 when the encoder chooses (0 or 1), 0 none, 3 Star-Tetrix
 * @param waveletPrecision {@code Bw}, or 0 for the sample precision (lossless profiles)
 * @param fractionalBits {@code Fq}
 * @param rawBits {@code Br}
 * @param maxVerticalLevels the largest {@code NLy}
 * @param longHeaders {@code Lh}
 * @param rawPerPacket {@code Rl}
 * @param uniform whether {@code Qpih} is uniform
 * @param suppressed {@code Sd}
 * @param inlineTetrix whether the Star-Tetrix extent is in-line ({@code Cf} 3) rather than full
 * @param maxColumnWidth the widest precinct in sampling grid points, or 0 for no limit
 * @param maxPrecision the largest component precision, or 16
 * @param maxComponents the largest number of components
 */
public record JxsProfiles(int code, int colorTransform, int waveletPrecision, int fractionalBits, int rawBits,
                          int maxVerticalLevels, boolean longHeaders, boolean rawPerPacket, boolean uniform,
                          int suppressed, boolean inlineTetrix, int maxColumnWidth, int maxPrecision,
                          int maxComponents) {
    private static final JxsProfiles[] TABLE = {
            new JxsProfiles(0x0000, -1, 20, 8, 4, 2, false, true, true, 0, false, 0, 16, 8),
            new JxsProfiles(0x1500, 0, 20, 8, 4, 1, false, false, false, 0, false, 0, 10, 3),
            new JxsProfiles(0x1A00, -1, 20, 8, 4, 1, false, false, false, 0, false, 0, 12, 3),
            new JxsProfiles(0x2500, 0, 20, 8, 4, 0, false, false, true, 0, false, 2048, 10, 3),
            new JxsProfiles(0x3240, 0, 20, 8, 4, 1, false, false, true, 0, false, 0, 12, 3),
            new JxsProfiles(0x3540, 0, 20, 8, 4, 1, false, false, true, 0, false, 0, 10, 3),
            new JxsProfiles(0x3A40, -1, 20, 8, 4, 1, false, false, true, 0, false, 0, 12, 3),
            new JxsProfiles(0x3E40, -1, 20, 8, 4, 1, false, false, true, 0, false, 0, 12, 4),
            new JxsProfiles(0x4240, 0, 20, 8, 4, 2, false, false, true, 0, false, 0, 12, 3),
            new JxsProfiles(0x4A40, -1, 20, 8, 4, 2, false, false, true, 0, false, 0, 12, 3),
            new JxsProfiles(0x4E40, -1, 20, 8, 4, 2, false, false, true, 0, false, 0, 12, 4),
            new JxsProfiles(0x4A44, -1, 20, 8, 4, 2, false, true, true, 0, false, 0, 12, 3),
            new JxsProfiles(0x6EC0, -1, 0, 0, 4, 2, false, true, true, 0, false, 0, 12, 4),
            new JxsProfiles(0x6ED0, -1, 0, 0, 5, 2, false, true, true, 0, false, 0, 16, 4),
            new JxsProfiles(0x9300, 3, 20, 8, 4, 0, true, false, true, 1, true, 0, 16, 4),
            new JxsProfiles(0xB340, 3, 20, 8, 4, 1, true, true, true, 1, false, 0, 16, 4),
            new JxsProfiles(0xC340, 3, 20, 8, 4, 2, true, true, true, 1, false, 0, 16, 4),
    };

    /**
     * Returns the parameters of a profile.
     *
     * @param code the profile code {@code Ppih}
     * @return the parameters, or {@code null} for a profile the encoder does not code
     */
    public static JxsProfiles of(int code) {
        for (var profile : TABLE) {
            if (profile.code == code) {
                return profile;
            }
        }
        return null;
    }

    /**
     * {@return the largest rate the full sublevel of a profile admits, in bits per sample of the picture (per mosaic
     * sample for the Bayer profiles), as the ISO/IEC 21122-2 profile table gives it; 128 for a profile without one}
     *
     * @param code the profile code {@code Ppih}
     */
    public static int fullBitsPerPixel(int code) {
        return switch (code) {
            case 0x1500, 0x2500, 0x3540 -> 20;
            case 0x1A00, 0x3A40, 0x4A40, 0x4A44, 0x4A45, 0x6A45 -> 36;
            case 0x3240, 0x4240 -> 18;
            case 0x3E40, 0x4E40, 0x6EC0, 0x6ED0 -> 48;
            case 0x9300, 0xB340, 0xC340 -> 64;
            default -> 128;
        };
    }

    /**
     * {@return whether the profile codes mathematically lossless codestreams}
     */
    public boolean lossless() {
        return fractionalBits == 0;
    }
}
