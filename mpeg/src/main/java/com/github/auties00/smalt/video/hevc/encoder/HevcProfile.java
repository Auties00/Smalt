package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.image.ChromaFormat;

import java.util.Objects;

/**
 * Enumerates the profiles of ITU-T H.265 Annex A an {@link HevcEncoder} codes, the sets of coding tools and
 * sample formats a stream announces to decoders.
 *
 * <p>Every profile codes the same coding tools; they differ in the bit depths and {@linkplain
 * #supportsChromaFormat(ChromaFormat) chroma formats} they allow and in whether the stream may hold more than one
 * picture. The version 1 profiles code 4:2:0 only; the format range extensions profiles
 * ({@code general_profile_idc} 4) also code 4:2:2 or 4:4:4.
 */
public enum HevcProfile {
    /**
     * Main ({@code general_profile_idc} 1): 8-bit 4:2:0 video.
     */
    MAIN(1, 8, false, ChromaFormat.YUV420),

    /**
     * Main 10 ({@code general_profile_idc} 2): 4:2:0 video of 8 to 10 bits.
     */
    MAIN_10(2, 10, false, ChromaFormat.YUV420),

    /**
     * Main Still Picture ({@code general_profile_idc} 3): a single 8-bit 4:2:0 picture, coded intra, as
     * HEIF images store it.
     */
    MAIN_STILL_PICTURE(3, 8, true, ChromaFormat.YUV420),

    /**
     * Main 10 Still Picture ({@code general_profile_idc} 2 with
     * {@code general_one_picture_only_constraint_flag}): a single 4:2:0 picture of 8 to 10 bits, coded
     * intra.
     */
    MAIN_10_STILL_PICTURE(2, 10, true, ChromaFormat.YUV420),

    /**
     * Main 4:2:2 10 ({@code general_profile_idc} 4, format range extensions): 4:2:0 or 4:2:2 video of 8 to 10
     * bits.
     */
    MAIN_422_10(4, 10, false, ChromaFormat.YUV422),

    /**
     * Main 4:4:4 ({@code general_profile_idc} 4, format range extensions): 8-bit 4:2:0, 4:2:2 or 4:4:4 video.
     */
    MAIN_444(4, 8, false, ChromaFormat.YUV444),

    /**
     * Main 4:4:4 10 ({@code general_profile_idc} 4, format range extensions): 4:2:0, 4:2:2 or 4:4:4 video of 8
     * to 10 bits.
     */
    MAIN_444_10(4, 10, false, ChromaFormat.YUV444),

    /**
     * Main 4:4:4 Still Picture ({@code general_profile_idc} 4, format range extensions): a single 8-bit 4:2:0,
     * 4:2:2 or 4:4:4 picture, coded intra.
     */
    MAIN_444_STILL_PICTURE(4, 8, true, ChromaFormat.YUV444);

    private final int profileIdc;

    private final int maxBitDepth;

    private final boolean stillPicture;

    /**
     * The densest chroma format the profile allows.
     */
    private final ChromaFormat maxChromaFormat;

    HevcProfile(int profileIdc, int maxBitDepth, boolean stillPicture, ChromaFormat maxChromaFormat) {
        this.profileIdc = profileIdc;
        this.maxBitDepth = maxBitDepth;
        this.stillPicture = stillPicture;
        this.maxChromaFormat = maxChromaFormat;
    }

    /**
     * {@return the {@code general_profile_idc} the stream signals, 1 to 4}
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

    /**
     * Returns whether the encoder codes pictures of a chroma format in the profile.
     *
     * <p>Every profile codes {@link ChromaFormat#YUV420}; {@link #MAIN_422_10} also codes
     * {@link ChromaFormat#YUV422}, and the 4:4:4 profiles code both and {@link ChromaFormat#YUV444}.
     * {@link ChromaFormat#MONOCHROME}, which the format range extensions profiles allow, is not coded by the encoder,
     * and {@link ChromaFormat#YUV440} has no HEVC chroma format.
     *
     * @param format the chroma format
     * @return {@code true} when the encoder codes the format in the profile
     */
    public boolean supportsChromaFormat(ChromaFormat format) {
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case YUV420 -> true;
            case YUV422 -> maxChromaFormat != ChromaFormat.YUV420;
            case YUV444 -> maxChromaFormat == ChromaFormat.YUV444;
            case MONOCHROME, YUV440 -> false;
        };
    }
}
