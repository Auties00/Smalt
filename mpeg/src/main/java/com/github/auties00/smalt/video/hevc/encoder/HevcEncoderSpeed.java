package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the trades between compression and encoding time of an {@link HevcEncoder}, fastest first.
 *
 * <p>Every speed codes the same stream format; faster speeds evaluate fewer coding unit sizes, prediction
 * modes, partitions, motion vectors and reference pictures, so they spend more bits for the same quality.
 */
public enum HevcEncoderSpeed {
    /**
     * Coding units from 32x32 to 8x8 with early termination, a reduced set of intra modes, one reference
     * picture, a small diamond search and merge candidates checked by their prediction error; sized for
     * real-time encoding.
     */
    FASTEST,

    /**
     * Coding units from 64x64 to 8x8, the intra modes refined around a coarse search, one reference picture,
     * a hexagon search with quarter-sample refinement and rate-distortion choice among the best candidates.
     */
    FAST,

    /**
     * As {@link #FAST} with every intra mode, 4x4 intra blocks in inter pictures, two reference pictures, the
     * rectangular partitions (2NxN and Nx2N) of 8x8 to 64x64 inter coding units and the chroma intra modes.
     */
    BALANCED,

    /**
     * As {@link #BALANCED} with three reference pictures, a wider search, intra coding units also where skip
     * is chosen, splits below skipped coding units and the transform tree split of 16x16 and 32x32 inter coding
     * units.
     */
    SLOW
}
