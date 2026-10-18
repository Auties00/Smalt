package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * The coding steps of the good-quality frame structure ({@code FRAME_UPDATE_TYPE}), each with the
 * rate control class whose size correction the frames it codes share.
 */
enum FrameUpdateType {
    /**
     * A key frame ({@code KF_UPDATE}).
     */
    KEY(0),

    /**
     * A hidden alternate reference at the end of a group ({@code ARF_UPDATE}).
     */
    ARF(1),

    /**
     * A hidden internal alternate reference ({@code INTNL_ARF_UPDATE}).
     */
    INTERNAL_ARF(2),

    /**
     * A shown frame coded in display order ({@code LF_UPDATE}).
     */
    LEAF(3),

    /**
     * The step that shows the alternate reference of a group, through {@code show_existing_frame}
     * or as a coded overlay ({@code OVERLAY_UPDATE}).
     */
    OVERLAY(3),

    /**
     * The step that shows an internal alternate reference ({@code INTNL_OVERLAY_UPDATE}).
     */
    INTERNAL_OVERLAY(3);

    /**
     * The number of rate control classes.
     */
    static final int RATE_CLASSES = 4;

    /**
     * The rate control class, from 0 to {@link #RATE_CLASSES} minus one; leaves and coded overlays
     * share one.
     */
    final int rateClass;

    FrameUpdateType(int rateClass) {
        this.rateClass = rateClass;
    }
}
