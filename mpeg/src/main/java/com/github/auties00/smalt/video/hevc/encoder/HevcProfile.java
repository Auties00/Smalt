package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the profiles of ITU-T H.265 Annex A an {@link HevcEncoder} codes, the sets of coding tools and
 * sample formats a stream announces to decoders.
 *
 * <p>Every profile codes 4:2:0 pictures with the same coding tools; they differ in the bit depths they allow
 * and in whether the stream may hold more than one picture.
 */
public enum HevcProfile {
    /**
     * Main ({@code general_profile_idc} 1): 8-bit 4:2:0 video.
     */
    MAIN(1, 8, false),

    /**
     * Main 10 ({@code general_profile_idc} 2): 4:2:0 video of 8 to 10 bits.
     */
    MAIN_10(2, 10, false),

    /**
     * Main Still Picture ({@code general_profile_idc} 3): a single 8-bit 4:2:0 picture, coded intra, as
     * HEIF images store it.
     */
    MAIN_STILL_PICTURE(3, 8, true),

    /**
     * Main 10 Still Picture ({@code general_profile_idc} 2 with
     * {@code general_one_picture_only_constraint_flag}): a single 4:2:0 picture of 8 to 10 bits, coded
     * intra.
     */
    MAIN_10_STILL_PICTURE(2, 10, true);

    private final int profileIdc;

    private final int maxBitDepth;

    private final boolean stillPicture;

    HevcProfile(int profileIdc, int maxBitDepth, boolean stillPicture) {
        this.profileIdc = profileIdc;
        this.maxBitDepth = maxBitDepth;
        this.stillPicture = stillPicture;
    }

    /**
     * {@return the {@code general_profile_idc} the stream signals, 1, 2 or 3}
     */
    public int profileIdc() {
        return profileIdc;
    }

    /**
     * {@return the deepest samples the profile allows, in bits, 8 or 10}
     */
    public int maxBitDepth() {
        return maxBitDepth;
    }

    /**
     * {@return whether a stream of the profile holds exactly one picture}
     */
    public boolean stillPicture() {
        return stillPicture;
    }
}
