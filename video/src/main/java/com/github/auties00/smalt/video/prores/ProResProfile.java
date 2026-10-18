package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.image.ChromaFormat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Enumerates the six Apple ProRes flavors, each with its QuickTime sample entry type, chroma
 * layout, quantization matrices and target data rate.
 *
 * <p>The 422 flavors code 4:2:2 Y'CbCr at 10 bits; the 4444 flavors code 4:4:4 Y'CbCr at 12
 * bits and may carry an alpha channel. The target data rates are Apple's published rates for
 * 1920x1080 at 29.97 frames per second, without alpha; {@link #bitsPerMacroblock()} scales them to
 * other frame sizes and rates.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum ProResProfile {
    /**
     * Apple ProRes 422 Proxy ({@code apco}), about 45 Mb/s at 1080p29.97.
     */
    PROXY("apco", ChromaFormat.YUV422, 45, new int[]{
            4, 7, 9, 11, 13, 14, 15, 63,
            7, 7, 11, 12, 14, 15, 63, 63,
            9, 11, 13, 14, 15, 63, 63, 63,
            11, 11, 13, 14, 63, 63, 63, 63,
            11, 13, 14, 63, 63, 63, 63, 63,
            13, 14, 63, 63, 63, 63, 63, 63,
            13, 63, 63, 63, 63, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63
    }, new int[]{
            4, 7, 9, 11, 13, 14, 63, 63,
            7, 7, 11, 12, 14, 63, 63, 63,
            9, 11, 13, 14, 63, 63, 63, 63,
            11, 11, 13, 14, 63, 63, 63, 63,
            11, 13, 14, 63, 63, 63, 63, 63,
            13, 14, 63, 63, 63, 63, 63, 63,
            13, 63, 63, 63, 63, 63, 63, 63,
            63, 63, 63, 63, 63, 63, 63, 63
    }),

    /**
     * Apple ProRes 422 LT ({@code apcs}), about 102 Mb/s at 1080p29.97.
     */
    LT("apcs", ChromaFormat.YUV422, 102, Matrices.LT, Matrices.LT),

    /**
     * Apple ProRes 422 ({@code apcn}), about 147 Mb/s at 1080p29.97.
     */
    STANDARD("apcn", ChromaFormat.YUV422, 147, Matrices.STANDARD, Matrices.STANDARD),

    /**
     * Apple ProRes 422 HQ ({@code apch}), about 220 Mb/s at 1080p29.97.
     */
    HQ("apch", ChromaFormat.YUV422, 220, Matrices.HIGH, Matrices.HIGH),

    /**
     * Apple ProRes 4444 ({@code ap4h}), about 330 Mb/s at 1080p29.97 without alpha.
     */
    PRORES_4444("ap4h", ChromaFormat.YUV444, 330, Matrices.HIGH, Matrices.HIGH),

    /**
     * Apple ProRes 4444 XQ ({@code ap4x}), about 500 Mb/s at 1080p29.97 without alpha.
     */
    PRORES_4444_XQ("ap4x", ChromaFormat.YUV444, 500, Matrices.HIGH, Matrices.HIGH);

    /**
     * The number of macroblocks of a 1920x1080 frame.
     */
    private static final int REFERENCE_MACROBLOCKS = 120 * 68;

    /**
     * The frame rate of the published target data rates.
     */
    private static final double REFERENCE_FRAME_RATE = 30000.0 / 1001.0;

    /**
     * The QuickTime sample entry type.
     */
    private final String fourCc;

    /**
     * The chroma layout.
     */
    private final ChromaFormat chroma;

    /**
     * The target data rate at 1920x1080 and 29.97 frames per second, in megabits per second.
     */
    private final int referenceMegabits;

    /**
     * The luma quantization matrix, raster order.
     */
    private final int[] lumaMatrix;

    /**
     * The chroma quantization matrix, raster order.
     */
    private final int[] chromaMatrix;

    ProResProfile(String fourCc, ChromaFormat chroma, int referenceMegabits, int[] lumaMatrix, int[] chromaMatrix) {
        this.fourCc = fourCc;
        this.chroma = chroma;
        this.referenceMegabits = referenceMegabits;
        this.lumaMatrix = lumaMatrix;
        this.chromaMatrix = chromaMatrix;
    }

    /**
     * {@return the QuickTime sample entry type, for example {@code "apch"}, which is also the codec
     * configuration of a ProRes track}
     */
    public String fourCc() {
        return fourCc;
    }

    /**
     * {@return the chroma format the flavor codes, {@link ChromaFormat#YUV422} or
     * {@link ChromaFormat#YUV444}}
     */
    public ChromaFormat chromaFormat() {
        return chroma;
    }

    /**
     * {@return whether the flavor may carry an alpha channel: {@code true} for the 4444 flavors}
     */
    public boolean supportsAlpha() {
        return chroma == ChromaFormat.YUV444;
    }

    /**
     * {@return the bit depth the flavor is mastered at: 10 for the 422 flavors, 12 for the 4444
     * flavors}
     */
    public int bitDepth() {
        return chroma == ChromaFormat.YUV444 ? 12 : 10;
    }

    /**
     * {@return Apple's target data rate at 1920x1080 and 29.97 frames per second without alpha, in
     * bits per second}
     */
    public long referenceBitrate() {
        return referenceMegabits * 1_000_000L;
    }

    /**
     * {@return the target number of bits of one 16x16 macroblock: {@link #referenceBitrate()} divided
     * by the macroblocks of 1920x1080 frames at 29.97 frames per second}
     */
    public double bitsPerMacroblock() {
        return referenceMegabits * 1e6 / (REFERENCE_MACROBLOCKS * REFERENCE_FRAME_RATE);
    }

    /**
     * {@return a copy of the luma quantization matrix the flavor signals: 64 weights in raster
     * order}
     */
    public int[] lumaMatrix() {
        return lumaMatrix.clone();
    }

    /**
     * {@return a copy of the chroma quantization matrix the flavor signals: 64 weights in raster
     * order}
     */
    public int[] chromaMatrix() {
        return chromaMatrix.clone();
    }

    /**
     * Returns the flavor of a QuickTime sample entry type or Matroska codec private data.
     *
     * @param fourCc the four-character code, for example {@code "apcn"}; case-sensitive
     * @return the flavor, or empty for an unknown code
     */
    public static Optional<ProResProfile> ofFourCc(String fourCc) {
        for (var profile : values()) {
            if (profile.fourCc.equals(fourCc)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the flavor named by a track's codec configuration, the sample entry type in ASCII.
     *
     * @param config the configuration bytes, or {@code null} for none
     * @return the flavor, or empty when the configuration is not exactly the four bytes of a known
     * sample entry type
     */
    public static Optional<ProResProfile> ofConfig(byte[] config) {
        if (config == null || config.length != 4) {
            return Optional.empty();
        }
        return ofFourCc(new String(config, StandardCharsets.US_ASCII));
    }

    /**
     * Holds the quantization matrices the flavors share.
     */
    private static final class Matrices {
        /**
         * The matrix of 422 LT.
         */
        private static final int[] LT = {
                4, 5, 6, 7, 9, 11, 13, 15,
                5, 5, 7, 8, 11, 13, 15, 17,
                6, 7, 9, 11, 13, 15, 15, 17,
                7, 7, 9, 11, 13, 15, 17, 19,
                7, 9, 11, 13, 14, 16, 19, 23,
                9, 11, 13, 14, 16, 19, 23, 29,
                9, 11, 13, 15, 17, 21, 28, 35,
                11, 13, 16, 17, 21, 28, 35, 41
        };

        /**
         * The matrix of 422.
         */
        private static final int[] STANDARD = {
                4, 4, 5, 5, 6, 7, 7, 9,
                4, 4, 5, 6, 7, 7, 9, 9,
                5, 5, 6, 7, 7, 9, 9, 10,
                5, 5, 6, 7, 7, 9, 9, 10,
                5, 6, 7, 7, 8, 9, 10, 12,
                6, 7, 7, 8, 9, 10, 12, 15,
                6, 7, 7, 9, 10, 11, 14, 17,
                7, 7, 9, 10, 11, 14, 17, 21
        };

        /**
         * The matrix of 422 HQ and the 4444 flavors.
         */
        private static final int[] HIGH = {
                4, 4, 4, 4, 4, 4, 4, 4,
                4, 4, 4, 4, 4, 4, 4, 4,
                4, 4, 4, 4, 4, 4, 4, 4,
                4, 4, 4, 4, 4, 4, 4, 5,
                4, 4, 4, 4, 4, 4, 5, 5,
                4, 4, 4, 4, 4, 5, 5, 6,
                4, 4, 4, 4, 5, 5, 6, 7,
                4, 4, 4, 4, 5, 6, 7, 7
        };

        /**
         * Prevents instantiation.
         */
        private Matrices() {
            throw new AssertionError();
        }
    }
}
