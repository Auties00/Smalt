package com.github.auties00.smalt.video.vp8.internal;

/**
 * Configures the VP8 key-frame encoder of {@link Vp8#encodeKeyFrame}.
 *
 * <p>The parameters mirror libwebp's {@code WebPConfig} and {@code cwebp} flags of the same names,
 * so a picture encoded with equal options has a size and quality comparable to {@code cwebp}'s
 * output: {@code quality} is {@code -q}, {@code method} is {@code -m}, {@code segments} is
 * {@code -segments}, {@code filterStrength} is {@code -f}, {@code sharpness} is {@code -sharpness},
 * {@code partitionCount} is {@code 2^-partitions}, and {@code snsStrength} is {@code -sns}.
 *
 * @param quality        the quality in {@code [0, 100]}; higher values use finer quantizers and
 *                       produce larger output
 * @param method         the speed/quality trade-off in {@code [0, 6]}; {@code 0} is fastest,
 *                       {@code 3} and above use rate-distortion optimized mode decisions,
 *                       {@code 5} and {@code 6} add trellis quantization
 * @param segments       the number of segments with their own quantizer and filter strength, in
 *                       {@code [1, 4]}
 * @param filterStrength the loop filter strength in {@code [0, 100]}; {@code 0} disables the
 *                       filter
 * @param sharpness      the loop filter sharpness in {@code [0, 7]}
 * @param partitionCount the number of token partitions: 1, 2, 4, or 8
 * @param snsStrength    the spatial noise shaping strength in {@code [0, 100]}: how much the
 *                       quantizer adapts to local complexity
 */
public record Vp8KeyFrameSettings(float quality, int method, int segments, int filterStrength, int sharpness,
                                int partitionCount, int snsStrength) {
    /**
     * Validates the options.
     *
     * @throws IllegalArgumentException if a parameter is outside its documented range
     */
    public Vp8KeyFrameSettings {
        if (!(quality >= 0 && quality <= 100)) {
            throw new IllegalArgumentException("quality must be in [0, 100]: " + quality);
        }
        if (method < 0 || method > 6) {
            throw new IllegalArgumentException("method must be in [0, 6]: " + method);
        }
        if (segments < 1 || segments > 4) {
            throw new IllegalArgumentException("segments must be in [1, 4]: " + segments);
        }
        if (filterStrength < 0 || filterStrength > 100) {
            throw new IllegalArgumentException("filter strength must be in [0, 100]: " + filterStrength);
        }
        if (sharpness < 0 || sharpness > 7) {
            throw new IllegalArgumentException("sharpness must be in [0, 7]: " + sharpness);
        }
        if (partitionCount != 1 && partitionCount != 2 && partitionCount != 4 && partitionCount != 8) {
            throw new IllegalArgumentException("partition count must be 1, 2, 4, or 8: " + partitionCount);
        }
        if (snsStrength < 0 || snsStrength > 100) {
            throw new IllegalArgumentException("sns strength must be in [0, 100]: " + snsStrength);
        }
    }

    /**
     * Returns the options {@code cwebp -q quality} uses: method 4, 4 segments, sns strength 50,
     * filter strength 60, sharpness 0, and one token partition.
     *
     * @param quality the quality in {@code [0, 100]}
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is outside {@code [0, 100]}
     */
    public static Vp8KeyFrameSettings quality(float quality) {
        return new Vp8KeyFrameSettings(quality, 4, 4, 60, 0, 1, 50);
    }
}
