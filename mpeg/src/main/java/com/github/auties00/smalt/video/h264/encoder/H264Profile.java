package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the H.264 profiles an {@link H264Encoder} produces.
 *
 * <p>The profile selects the entropy coder and the coding tools; {@link #CONSTRAINED_BASELINE}
 * codes I and P pictures only, {@link #MAIN} and {@link #HIGH} also code B pictures when the
 * options ask for them ({@link H264EncoderOptions#bFrames()}).
 */
public enum H264Profile {
    /**
     * Constrained Baseline ({@code profile_idc} 66 with {@code constraint_set0_flag} and
     * {@code constraint_set1_flag}): CAVLC, I and P slices; decodable by every H.264 decoder.
     */
    CONSTRAINED_BASELINE(66),

    /**
     * Main ({@code profile_idc} 77 with {@code constraint_set1_flag}): CABAC, I, P and B slices.
     */
    MAIN(77),

    /**
     * High ({@code profile_idc} 100): Main plus the 8x8 transform and 8x8 intra prediction.
     */
    HIGH(100);

    private final int profileIdc;

    H264Profile(int profileIdc) {
        this.profileIdc = profileIdc;
    }

    /**
     * {@return the {@code profile_idc} written to the sequence parameter set}
     */
    public int profileIdc() {
        return profileIdc;
    }
}
