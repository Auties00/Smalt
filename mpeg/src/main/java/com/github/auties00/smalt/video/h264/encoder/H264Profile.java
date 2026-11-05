package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the H.264 profiles an {@link H264Encoder} produces.
 *
 * <p>The profile selects the entropy coder (CABAC above {@link #CONSTRAINED_BASELINE}, unless
 * {@link H264EncoderOptions#cavlc()} asks for CAVLC) and the coding tools, and bounds the chroma format, the
 * bit depth and lossless coding of the stream ({@link H264EncoderOptions#chromaFormat()},
 * {@link H264EncoderOptions#bitDepth()}, {@link H264EncoderOptions#lossless()}):
 * <table class="striped">
 * <caption>Formats each profile codes</caption>
 * <thead>
 * <tr><th scope="col">Profile</th><th scope="col">Chroma formats</th><th scope="col">Bit depths</th>
 * <th scope="col">Lossless</th><th scope="col">Interlaced</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>{@link #CONSTRAINED_BASELINE}</td><td>4:2:0</td><td>8</td><td>no</td><td>no</td></tr>
 * <tr><td>{@link #MAIN}</td><td>4:2:0</td><td>8</td><td>no</td><td>yes</td></tr>
 * <tr><td>{@link #HIGH}</td><td>4:0:0, 4:2:0</td><td>8</td><td>no</td><td>yes</td></tr>
 * <tr><td>{@link #HIGH_10}</td><td>4:0:0, 4:2:0</td><td>8 to 10</td><td>no</td><td>yes</td></tr>
 * <tr><td>{@link #HIGH_422}</td><td>4:0:0, 4:2:0, 4:2:2</td><td>8 to 10</td><td>no</td><td>yes</td></tr>
 * <tr><td>{@link #HIGH_444_PREDICTIVE}</td><td>4:0:0, 4:2:0, 4:2:2, 4:4:4</td><td>8 to 14</td><td>yes</td>
 * <td>yes</td></tr>
 * </tbody>
 * </table>
 * {@link #CONSTRAINED_BASELINE} codes I and P pictures only; the other profiles also code B pictures when
 * the options ask for them ({@link H264EncoderOptions#bFrames()}) and the stream is 8-bit, 4:2:0,
 * progressive and not lossless.
 */
public enum H264Profile {
    /**
     * Constrained Baseline ({@code profile_idc} 66 with {@code constraint_set0_flag} and
     * {@code constraint_set1_flag}): CAVLC, I and P slices; decodable by every H.264 decoder.
     */
    CONSTRAINED_BASELINE(66),

    /**
     * Main ({@code profile_idc} 77 with {@code constraint_set1_flag}): CABAC or CAVLC, I, P and B slices,
     * frame and field coding.
     */
    MAIN(77),

    /**
     * High ({@code profile_idc} 100): Main plus the 8x8 transform, 8x8 intra prediction and
     * monochrome (4:0:0) streams.
     */
    HIGH(100),

    /**
     * High 10 ({@code profile_idc} 110): High with samples of up to 10 bits.
     */
    HIGH_10(110),

    /**
     * High 4:2:2 ({@code profile_idc} 122): High 10 plus 4:2:2 chroma.
     */
    HIGH_422(122),

    /**
     * High 4:4:4 Predictive ({@code profile_idc} 244): High 4:2:2 plus 4:4:4 chroma, samples of up to 14
     * bits and lossless coding.
     */
    HIGH_444_PREDICTIVE(244);

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

    /**
     * {@return whether the profile has the 8x8 transform, 8x8 intra prediction and the
     * {@code profile_idc} 100 and later syntax of the sequence parameter set}
     */
    boolean high() {
        return profileIdc >= 100;
    }

    /**
     * {@return the largest sample bit depth of the profile}
     */
    int maxBitDepth() {
        return switch (this) {
            case CONSTRAINED_BASELINE, MAIN, HIGH -> 8;
            case HIGH_10, HIGH_422 -> 10;
            case HIGH_444_PREDICTIVE -> 14;
        };
    }
}
