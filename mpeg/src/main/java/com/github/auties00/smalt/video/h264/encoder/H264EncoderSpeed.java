package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the trades between compression and encoding time of an {@link H264Encoder},
 * fastest first.
 *
 * <p>Every speed codes the same stream format; faster speeds search fewer references,
 * partitions, motion vectors and modes, so they spend more bits for the same quality.
 */
public enum H264EncoderSpeed {
    /**
     * One reference frame, 16x16 partitions, a small diamond search and SATD decisions, with a
     * rate-distortion check of skipping; sized for real-time encoding.
     */
    FASTEST,

    /**
     * One reference frame, 16x16 partitions, hexagon search with finer sub-sample refinement, and
     * rate-distortion choice between skipping, the best inter candidate and intra prediction.
     */
    FAST,

    /**
     * Two reference frames, 16x16, 16x8 and 8x16 partitions, a wider hexagon search with
     * chroma-aware costs, and rate-distortion choice among the best candidates.
     */
    BALANCED,

    /**
     * Three reference frames, partitions down to 4x4, an uneven multi-hexagon search, full
     * rate-distortion mode decision and rate-distortion optimized quantization.
     */
    SLOW
}
