package com.github.auties00.smalt.video;

import java.util.Objects;

/**
 * Describes how a decoded frame packs the two views of a stereoscopic pair, as the frame packing
 * arrangement SEI message of ITU-T H.264 (D.2.26) and H.265 (D.3.16) signals it.
 *
 * <p>The two constituent frames are called frame 0 and frame 1. Except for
 * {@link Arrangement#FRAME_ALTERNATION}, frame 0 is the one holding the top-left sample of the
 * decoded frame. The grid positions place the luma sampling grid of a constituent frame relative
 * to the top-left corner of its area, in sixteenths of a luma sample; they are meaningful only when
 * {@code quincunxSampling} is {@code false} and the arrangement is neither
 * {@link Arrangement#FRAME_ALTERNATION} nor {@link Arrangement#UNPACKED}, are 0 otherwise, take
 * (0, 0) for the default alignment of the arrangement and (15, 15) for an unspecified one.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param arrangement how the views share the frame
 * @param interpretation which view frame 0 holds
 * @param quincunxSampling whether each view was sampled on a quincunx (checkerboard) grid
 * @param spatialFlipping whether one view is mirrored: horizontally for
 * {@link Arrangement#SIDE_BY_SIDE}, vertically for {@link Arrangement#TOP_BOTTOM}; always
 * {@code false} for the other arrangements
 * @param frame0Flipped whether the mirrored view is frame 0 rather than frame 1; {@code false} when
 * {@code spatialFlipping} is {@code false}
 * @param fieldViews whether the two views are the two fields of each frame, which only
 * {@link Arrangement#ROW_INTERLEAVED} allows
 * @param currentFrameIsFrame0 whether this frame is frame 0 of a {@link Arrangement#FRAME_ALTERNATION}
 * pair, whose frame 1 is the next frame in output order; {@code false} for the other arrangements
 * @param frame0SelfContained whether frame 0 is coded without inter prediction from frame 1
 * @param frame1SelfContained whether frame 1 is coded without inter prediction from frame 0
 * @param frame0GridX the horizontal grid position of frame 0, from 0 to 15
 * @param frame0GridY the vertical grid position of frame 0, from 0 to 15
 * @param frame1GridX the horizontal grid position of frame 1, from 0 to 15
 * @param frame1GridY the vertical grid position of frame 1, from 0 to 15
 * @param upsampledAspectRatio whether the sample aspect ratio applies to the views after they are
 * upsampled to the size of the frame (H.265 {@code upsampled_aspect_ratio_flag}); always
 * {@code false} for H.264
 */
public record FramePacking(Arrangement arrangement, Interpretation interpretation, boolean quincunxSampling,
                           boolean spatialFlipping, boolean frame0Flipped, boolean fieldViews,
                           boolean currentFrameIsFrame0, boolean frame0SelfContained, boolean frame1SelfContained,
                           int frame0GridX, int frame0GridY, int frame1GridX, int frame1GridY,
                           boolean upsampledAspectRatio) {
    /**
     * Validates the arrangement.
     *
     * @throws IllegalArgumentException if a grid position is outside 0 to 15
     */
    public FramePacking {
        Objects.requireNonNull(arrangement, "arrangement");
        Objects.requireNonNull(interpretation, "interpretation");
        if ((frame0GridX | frame0GridY | frame1GridX | frame1GridY) >>> 4 != 0) {
            throw new IllegalArgumentException("grid position outside 0 to 15: " + frame0GridX + ", " + frame0GridY
                    + ", " + frame1GridX + ", " + frame1GridY);
        }
    }

    /**
     * Constructs the arrangement of two views with the default alignment and no further flags.
     *
     * @param arrangement how the views share the frame
     * @param interpretation which view frame 0 holds
     */
    public FramePacking(Arrangement arrangement, Interpretation interpretation) {
        this(arrangement, interpretation, arrangement == Arrangement.CHECKERBOARD, false, false, false, false, false,
                false, 0, 0, 0, 0, false);
    }

    /**
     * Enumerates the ways two views share a frame, with the {@code frame_packing_arrangement_type}
     * of each.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Arrangement {
        /**
         * The samples of the views alternate in a checkerboard pattern (type 0).
         */
        CHECKERBOARD(0),

        /**
         * The columns of the views alternate (type 1).
         */
        COLUMN_INTERLEAVED(1),

        /**
         * The rows of the views alternate (type 2).
         */
        ROW_INTERLEAVED(2),

        /**
         * Frame 0 fills the left half, frame 1 the right half (type 3).
         */
        SIDE_BY_SIDE(3),

        /**
         * Frame 0 fills the top half, frame 1 the bottom half (type 4).
         */
        TOP_BOTTOM(4),

        /**
         * Consecutive frames in output order alternate between the views (type 5).
         */
        FRAME_ALTERNATION(5),

        /**
         * The frame holds a single view, with no packing (type 6).
         */
        UNPACKED(6),

        /**
         * Frame 0 fills the top-left two thirds of each dimension and frame 1 is tiled from the
         * three regions around it (type 7, the tile format for 720p views in 1080p frames).
         */
        TILE(7);

        /**
         * The {@code frame_packing_arrangement_type}.
         */
        private final int code;

        Arrangement(int code) {
            this.code = code;
        }

        /**
         * {@return the {@code frame_packing_arrangement_type} of this arrangement}
         */
        public int code() {
            return code;
        }

        /**
         * Returns the arrangement of a {@code frame_packing_arrangement_type}.
         *
         * @param code the type
         * @return the arrangement
         * @throws IllegalArgumentException if {@code code} is not from 0 to 7
         */
        public static Arrangement ofCode(int code) {
            if (code < 0 || code > 7) {
                throw new IllegalArgumentException("frame_packing_arrangement_type " + code + " is reserved");
            }
            return values()[code];
        }
    }

    /**
     * Enumerates the meanings of the two constituent frames, with the
     * {@code content_interpretation_type} of each.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Interpretation {
        /**
         * The relationship between the frames is unspecified (type 0), as it always is for
         * {@link Arrangement#UNPACKED}.
         */
        UNSPECIFIED(0),

        /**
         * Frame 0 is the left view and frame 1 the right view (type 1).
         */
        FRAME0_LEFT(1),

        /**
         * Frame 0 is the right view and frame 1 the left view (type 2).
         */
        FRAME0_RIGHT(2);

        /**
         * The {@code content_interpretation_type}.
         */
        private final int code;

        Interpretation(int code) {
            this.code = code;
        }

        /**
         * {@return the {@code content_interpretation_type} of this interpretation}
         */
        public int code() {
            return code;
        }

        /**
         * Returns the interpretation of a {@code content_interpretation_type}.
         *
         * @param code the type
         * @return the interpretation, {@link #UNSPECIFIED} for a reserved type
         */
        public static Interpretation ofCode(int code) {
            return code == 1 ? FRAME0_LEFT : code == 2 ? FRAME0_RIGHT : UNSPECIFIED;
        }
    }
}
