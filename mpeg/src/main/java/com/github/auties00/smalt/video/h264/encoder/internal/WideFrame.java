package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds one frame of the extended engine in padded planes of 16-bit samples: a source frame, a
 * reconstruction being built, or a reference frame.
 *
 * <p>A reference frame also keeps each of its fields in planes of their own, padded with the
 * field's own edge rows, since a field picture or a field macroblock that predicts from a field
 * reads past the field's top and bottom rows as a decoder does (ITU-T H.264 8.4.2.2).
 */
final class WideFrame {
    /**
     * The luma padding on each side, in samples.
     */
    static final int PAD = 32;

    /**
     * The luma, Cb and Cr planes; the chroma planes are {@code null} for monochrome frames.
     */
    final WidePlane[] planes = new WidePlane[3];

    /**
     * The views of the top field rows of {@link #planes}.
     */
    final WidePlane[] topView = new WidePlane[3];

    /**
     * The views of the bottom field rows of {@link #planes}.
     */
    final WidePlane[] bottomView = new WidePlane[3];

    /**
     * The separately padded top field planes of a reference frame, or {@code null} until
     * {@link #finishReference(boolean)} built them.
     */
    WidePlane[] topField;

    /**
     * The separately padded bottom field planes of a reference frame, or {@code null} until
     * {@link #finishReference(boolean)} built them.
     */
    WidePlane[] bottomField;

    /**
     * The {@code frame_num} the frame was coded with.
     */
    int frameNum;

    /**
     * The motion compensation view of the frame, or {@code null} until a frame macroblock first
     * predicts from it.
     */
    WideReference frameRef;

    /**
     * The motion compensation view of the top field, or {@code null} until a field first predicts
     * from it.
     */
    WideReference topRef;

    /**
     * The motion compensation view of the bottom field, or {@code null} until a field first predicts
     * from it.
     */
    WideReference bottomRef;

    /**
     * The picture order count of the top field, which is the frame's for progressive frames.
     */
    int topPoc;

    /**
     * The picture order count of the bottom field.
     */
    int bottomPoc;

    /**
     * Allocates a frame.
     *
     * @param settings the stream settings, which give the coded size and the chroma format
     */
    WideFrame(WideSettings settings) {
        var width = settings.codedWidth();
        var height = settings.codedHeight();
        planes[0] = new WidePlane(width, height, PAD, PAD);
        if (settings.hasChroma()) {
            var cw = width / settings.subWidth();
            var ch = height / settings.subHeight();
            var padX = PAD / settings.subWidth();
            var padY = PAD / settings.subHeight();
            planes[1] = new WidePlane(cw, ch, padX, padY);
            planes[2] = new WidePlane(cw, ch, padX, padY);
        }
        for (var c = 0; c < 3; c++) {
            if (planes[c] != null) {
                topView[c] = new WidePlane(planes[c], false);
                bottomView[c] = new WidePlane(planes[c], true);
            }
        }
    }

    /**
     * Returns the planes of the frame or of one of its fields as coded rows: the frame planes, or
     * the field views.
     *
     * @param structure 0 for the frame, 1 for the top field, 2 for the bottom field
     * @return the planes, indexed by color component
     */
    WidePlane[] coded(int structure) {
        return structure == 0 ? planes : structure == 1 ? topView : bottomView;
    }

    /**
     * Returns the padded planes motion compensation reads for the frame or one of its fields.
     *
     * @param bottom for a field, whether it is the bottom one
     * @param field whether a field is read
     * @return the planes, indexed by color component
     */
    WidePlane[] reference(boolean field, boolean bottom) {
        return !field ? planes : bottom ? bottomField : topField;
    }

    /**
     * Pads the frame planes and, when field prediction may read the frame, builds its padded field
     * planes, once the frame's final samples are in place.
     *
     * @param fields whether to build the field planes
     */
    void finishReference(boolean fields) {
        for (var plane : planes) {
            if (plane != null) {
                plane.pad();
            }
        }
        if (!fields) {
            return;
        }
        if (topField == null) {
            topField = new WidePlane[3];
            bottomField = new WidePlane[3];
            for (var c = 0; c < 3; c++) {
                var plane = planes[c];
                if (plane != null) {
                    topField[c] = new WidePlane(plane.width, plane.height / 2, plane.padX, plane.padY);
                    bottomField[c] = new WidePlane(plane.width, plane.height / 2, plane.padX, plane.padY);
                }
            }
        }
        for (var c = 0; c < 3; c++) {
            if (planes[c] != null) {
                topField[c].copyFrom(topView[c]);
                topField[c].pad();
                bottomField[c].copyFrom(bottomView[c]);
                bottomField[c].pad();
            }
        }
    }
}
