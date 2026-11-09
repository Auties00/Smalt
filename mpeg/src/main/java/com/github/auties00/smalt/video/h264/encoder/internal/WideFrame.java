package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds one frame of the extended engine in padded planes of 16-bit samples: a source frame, a
 * reconstruction being built, or a reference frame.
 *
 * <p>A reference frame also keeps each of its fields in planes of their own, padded with the
 * field's own edge rows, since a field picture or a field macroblock that predicts from a field
 * reads past the field's top and bottom rows as a decoder does (ITU-T H.264 8.4.2.2).
 *
 * <p>A frame of a stream whose color planes are coded separately also offers each plane as a
 * monochrome frame of its own ({@link #plane(int)}), which shares the plane's samples and keeps the
 * plane's co-located motion, since each plane is decoded as a monochrome picture (ITU-T H.264 8).
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
     * The motion of the frame's coded pictures that a B picture reads as co-located data (ITU-T H.264
     * 8.4.1.2.1), by structure (0 for the frame, 1 for the top field, 2 for the bottom field): the
     * list 0 reference index of each 8x8 quadrant at {@code 4 * mbAddr + quadrant}, or the list 1 index
     * when the quadrant does not use list 0, -1 for intra macroblocks; {@code null} for a structure
     * the frame was not coded as.
     */
    final byte[][] colRef = new byte[3][];

    /**
     * The horizontal vectors of the co-located data, laid out as {@link #colRef}.
     */
    final short[][] colMvX = new short[3][];

    /**
     * The vertical vectors of the co-located data, laid out as {@link #colRef}.
     */
    final short[][] colMvY = new short[3][];

    /**
     * Whether each macroblock address of a frame coded as macroblock pairs is a field macroblock.
     */
    boolean[] colField;

    /**
     * The monochrome views of the three color planes of a frame of a stream that codes them separately,
     * or {@code null}.
     */
    private final WideFrame[] planeViews;

    /**
     * Allocates the co-located data of a structure on first use.
     *
     * @param structure 0 for the frame, 1 for the top field, 2 for the bottom field
     * @param count the number of macroblocks of the structure
     */
    void allocateMotion(int structure, int count) {
        if (colRef[structure] == null) {
            colRef[structure] = new byte[count * 4];
            colMvX[structure] = new short[count * 4];
            colMvY[structure] = new short[count * 4];
        }
        if (structure == 0 && colField == null) {
            colField = new boolean[count];
        }
    }

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
        if (settings.separateColorPlanes()) {
            planeViews = new WideFrame[3];
            for (var c = 0; c < 3; c++) {
                planeViews[c] = new WideFrame(this, c);
            }
        } else {
            planeViews = null;
        }
    }

    /**
     * Creates a frame without samples, which holds the co-located motion of a picture until the frame the
     * picture belongs to takes it with {@link #swapMotion(WideFrame, int)}.
     */
    WideFrame() {
        planeViews = null;
    }

    /**
     * Creates the monochrome view of one color plane of a frame.
     *
     * @param frame the frame whose plane the view shares
     * @param c the color plane, 0 for Y, 1 for Cb, 2 for Cr
     */
    private WideFrame(WideFrame frame, int c) {
        planes[0] = frame.planes[c];
        topView[0] = frame.topView[c];
        bottomView[0] = frame.bottomView[c];
        planeViews = null;
    }

    /**
     * Returns the monochrome view of one color plane of a frame of a stream that codes the planes
     * separately: its plane 0 is the frame's plane {@code c}, and its co-located motion is the plane's own.
     *
     * @param c the color plane, 0 for Y, 1 for Cb, 2 for Cr
     * @return the view
     */
    WideFrame plane(int c) {
        return planeViews[c];
    }

    /**
     * Exchanges the co-located data of a structure with another frame, so that motion stored into a
     * holder becomes the frame's without copying.
     *
     * @param other the other frame, whose data of the structure is allocated
     * @param structure 0 for the frame, 1 for the top field, 2 for the bottom field
     */
    void swapMotion(WideFrame other, int structure) {
        var refs = colRef[structure];
        colRef[structure] = other.colRef[structure];
        other.colRef[structure] = refs;
        var xs = colMvX[structure];
        colMvX[structure] = other.colMvX[structure];
        other.colMvX[structure] = xs;
        var ys = colMvY[structure];
        colMvY[structure] = other.colMvY[structure];
        other.colMvY[structure] = ys;
        if (structure == 0) {
            var fields = colField;
            colField = other.colField;
            other.colField = fields;
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
