package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Holds the coding decisions of one frame that the rate control and the reference structure make
 * before the frame is encoded.
 *
 * <p>The fields are set by the caller for each frame; the frame encoder only reads them.
 */
final class FrameParams {
    /**
     * Whether the frame is a key frame.
     */
    boolean key;

    /**
     * Whether an inter frame is coded in error resilient mode, independent of earlier CDFs.
     */
    boolean errorResilient;

    /**
     * The frame's order hint, reduced modulo the order hint range when written.
     */
    int orderHint;

    /**
     * The reference whose CDFs an inter frame starts from, 0 to 6.
     */
    int primaryRefFrame;

    /**
     * The reference slots the frame refreshes, one bit per slot.
     */
    int refreshFlags;

    /**
     * The slot of each of the seven references, {@code LAST} first.
     */
    final int[] refIdx = new int[7];

    /**
     * Whether motion vectors have eighth-sample precision.
     */
    boolean allowHighPrecisionMv;

    /**
     * The interpolation filter of every block: 0 regular, 1 smooth, 2 sharp, 3 bilinear.
     */
    int interpFilter;

    /**
     * The base-2 logarithm of the number of tile columns requested.
     */
    int tileColsLog2;

    /**
     * The base quantizer index, 1 to 255.
     */
    int qindex;

    /**
     * Whether the frame codes a segmentation map.
     */
    boolean segmentation;

    /**
     * The quantizer delta of each segment.
     */
    final int[] segDeltaQ = new int[8];

    /**
     * The vertical and horizontal luma loop filter levels.
     */
    final int[] lfLevelY = new int[2];

    /**
     * The U loop filter level.
     */
    int lfLevelU;

    /**
     * The V loop filter level.
     */
    int lfLevelV;

    /**
     * The CDEF damping, 3 to 6.
     */
    int cdefDamping = 3;

    /**
     * The CDEF luma strength: the primary strength times four plus the secondary strength code.
     */
    int cdefYStrength;

    /**
     * The CDEF chroma strength: the primary strength times four plus the secondary strength code.
     */
    int cdefUvStrength;

    /**
     * Whether blocks choose their transform size.
     */
    boolean txSwitchable = true;

    /**
     * The temporal layer of the frame.
     */
    int temporalId;

    /**
     * The number of temporal layers of the stream; above one, frames carry the OBU extension.
     */
    int temporalLayers = 1;

    /**
     * The number of threads the frame may use: 1 for the caller only, 0 for the shared pool.
     */
    int threads;

    /**
     * The decoder reference indices the mode decision may predict from, one bit each:
     * bit 0 {@code LAST}, bit 3 {@code GOLDEN}, bit 6 {@code ALTREF}.
     */
    int refSearch = 1;

    /**
     * Whether the frame refreshes the golden reference, which raises its Lagrangian multiplier.
     */
    boolean goldenUpdate;

    /**
     * The real-time speed level, 7 to 10.
     */
    int speed = 9;

    /**
     * Whether the frame is shown once decoded; a hidden frame is shown later through a
     * {@code show_existing_frame} header.
     */
    boolean showFrame = true;

    /**
     * Whether the frame's temporal unit starts with this frame, so its OBUs start with a temporal
     * delimiter; the frames coded after a hidden frame of the same temporal unit have none.
     */
    boolean temporalDelimiter = true;

    /**
     * Whether blocks may predict from two references ({@code reference_select}).
     */
    boolean referenceSelect;

    /**
     * Whether blocks may be coded in skip mode, when the frame's references allow it
     * ({@code skip_mode_present}); used with {@link #referenceSelect}.
     */
    boolean skipMode;

    /**
     * Whether blocks may use a local warp, signaled with a switchable motion mode; needs a
     * sequence header that enables warped motion.
     */
    boolean warpedMotion;

    /**
     * Whether the frame projects the motion of its references for its vector predictions; needs a
     * sequence header that enables reference frame motion vectors.
     */
    boolean refFrameMvs;

    /**
     * The good-quality speed level, 2 (slowest) to 6, or 0 for the real-time features of
     * {@link #speed}.
     */
    int goodSpeed;

    /**
     * The scale of the frame's Lagrangian multiplier, in 1/128 units.
     */
    int rdmultScale = 128;

    /**
     * Whether the deblocking levels are searched on the reconstruction instead of taken from the
     * quantizer model; used with {@link #goodSpeed}.
     */
    boolean loopFilterSearch;

    /**
     * The CDEF search level, 0 for the quantizer model, 1 to 6 for the searches of
     * {@link CdefSearch}; used with {@link #goodSpeed}.
     */
    int cdefSearch;

    /**
     * Creates parameters with every field at its default.
     */
    FrameParams() {
    }
}
