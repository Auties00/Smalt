package com.github.auties00.smalt.video.h264.internal;

/**
 * Holds one entry of the decoded picture buffer of a dependency representation of the scalable
 * decoding process (ITU-T H.264 F.8.2): a decoded frame, complementary field pair or non-paired
 * field, or a reference base frame, field pair or field, of one dependency representation. An
 * entry of the dependency representation with the highest {@code dependency_id} carries samples;
 * an entry of a lower dependency representation carries only its picture numbers, its marking
 * and, for {@code dependency_id} 0, the motion that direct prediction reads.
 *
 * <p>Each field of an entry has its own marking and picture order count; a frame marks and
 * numbers both fields alike.
 */
final class SvcPicture {
    /**
     * The marking "unused for reference".
     */
    static final int UNUSED = 0;

    /**
     * The marking "used for short-term reference".
     */
    static final int SHORT_TERM = 1;

    /**
     * The marking "used for long-term reference".
     */
    static final int LONG_TERM = 2;

    /**
     * The field selector of the top field, as {@link DecoderFrame#TOP}.
     */
    static final int TOP = DecoderFrame.TOP;

    /**
     * The field selector of the bottom field, as {@link DecoderFrame#BOTTOM}.
     */
    static final int BOTTOM = DecoderFrame.BOTTOM;

    /**
     * The field selector of both fields, as {@link DecoderFrame#FRAME}.
     */
    static final int FRAME = DecoderFrame.FRAME;

    /**
     * The identifier of the entry, unique among the entries of the decoder.
     */
    int id;

    /**
     * Whether the entry is a reference base picture.
     */
    boolean refBase;

    /**
     * The marking of the top field, one of {@link #UNUSED}, {@link #SHORT_TERM} and
     * {@link #LONG_TERM}.
     */
    int topMarking;

    /**
     * The marking of the bottom field.
     */
    int bottomMarking;

    /**
     * The fields the entry holds: {@link #TOP}, {@link #BOTTOM} or both ({@link #FRAME}).
     */
    int fields;

    /**
     * Whether the entry was coded as a frame rather than as fields.
     */
    boolean codedFrame;

    /**
     * Whether the frame was coded with {@code MbaffFrameFlag} 1.
     */
    boolean mbaff;

    /**
     * Whether the first field of the entry, in decoding order, is a reference picture, which
     * decides whether a second field can join it.
     */
    boolean firstFieldReference;

    /**
     * The {@code frame_num}, {@code FrameNum} of a short-term reference.
     */
    int frameNum;

    /**
     * The {@code FrameNumWrap}.
     */
    int frameNumWrap;

    /**
     * The {@code LongTermFrameIdx} of a long-term reference.
     */
    int longTermFrameIdx;

    /**
     * The {@code TopFieldOrderCnt} of the top field.
     */
    int topPoc;

    /**
     * The {@code BottomFieldOrderCnt} of the bottom field.
     */
    int bottomPoc;

    /**
     * The {@code temporal_id} of the access unit of the entry.
     */
    int temporalId;

    /**
     * Whether the entry stands for a frame lost to a gap in {@code frame_num}.
     */
    boolean nonExisting;

    /**
     * Whether a frame lost to a gap that the entry stands for may have been a picture the encoder
     * referred to: the sequence does not allow gaps, or a loss was reported before the gap.
     */
    boolean lostStandIn;

    /**
     * Whether the samples or the motion of the picture may differ from the encoder's: its access
     * unit was received incomplete or predicted from a damaged, lost or missing picture.
     */
    boolean damaged;

    /**
     * Whether the picture waits to be output.
     */
    boolean outputNeeded;

    /**
     * The samples of a picture of the target dependency representation, or {@code null}.
     */
    DecoderFrame frame;

    /**
     * The motion of a layer picture of dependency 0 coded as a frame, or of its top field.
     */
    final SvcMotionStore topMotion = new SvcMotionStore();

    /**
     * The motion of the bottom field of a layer picture of dependency 0 coded as fields.
     */
    final SvcMotionStore bottomMotion = new SvcMotionStore();

    /**
     * The {@code ScaledRefLayerLeftOffset} of the layer representation the picture was decoded
     * with.
     */
    int scaledLeft;

    /**
     * The {@code ScaledRefLayerTopOffset} of that layer representation.
     */
    int scaledTop;

    /**
     * The {@code ScaledRefLayerRightOffset} of that layer representation.
     */
    int scaledRight;

    /**
     * The {@code ScaledRefLayerBottomOffset} of that layer representation.
     */
    int scaledBottom;

    /**
     * Constructs an empty entry.
     */
    SvcPicture() {
    }

    /**
     * Returns the marking of a field.
     *
     * @param parity {@link #TOP} or {@link #BOTTOM}
     * @return the marking of that field
     */
    int marking(int parity) {
        return parity == TOP ? topMarking : bottomMarking;
    }

    /**
     * Sets the marking of some fields.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @param marking the new marking: {@link #UNUSED}, {@link #SHORT_TERM} or {@link #LONG_TERM}
     */
    void mark(int structure, int marking) {
        if ((structure & TOP) != 0) {
            topMarking = marking;
        }
        if ((structure & BOTTOM) != 0) {
            bottomMarking = marking;
        }
    }

    /**
     * Returns whether a field of the entry, or the entry when both fields are wanted, is marked
     * with a marking: for {@link #FRAME}, both fields must be.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @param marking the marking wanted: {@link #UNUSED}, {@link #SHORT_TERM} or {@link #LONG_TERM}
     * @return whether every selected field holds the marking
     */
    boolean marked(int structure, int marking) {
        if (structure == FRAME) {
            return topMarking == marking && bottomMarking == marking;
        }
        return marking(structure) == marking;
    }

    /**
     * Returns whether a field of the entry is a reference.
     *
     * @return {@code true} when either field is marked short-term or long-term
     */
    boolean isReference() {
        return topMarking != UNUSED || bottomMarking != UNUSED;
    }

    /**
     * Returns whether a field of the entry is a short-term reference.
     *
     * @return {@code true} when either field is marked short-term
     */
    boolean anyShortTerm() {
        return topMarking == SHORT_TERM || bottomMarking == SHORT_TERM;
    }

    /**
     * Returns whether a field of the entry is a long-term reference.
     *
     * @return {@code true} when either field is marked long-term
     */
    boolean anyLongTerm() {
        return topMarking == LONG_TERM || bottomMarking == LONG_TERM;
    }

    /**
     * Returns the picture order count of a field.
     *
     * @param parity {@link #TOP} or {@link #BOTTOM}
     * @return its order count
     */
    int fieldPoc(int parity) {
        return parity == TOP ? topPoc : bottomPoc;
    }

    /**
     * Returns the picture order count of the entry as a reference entry: the smaller order count
     * of its fields when it holds both, or that of its field.
     *
     * @return {@code PicOrderCnt} of the entry
     */
    int poc() {
        return switch (fields) {
            case TOP -> topPoc;
            case BOTTOM -> bottomPoc;
            default -> Math.min(topPoc, bottomPoc);
        };
    }

    /**
     * Returns the picture order count of a structure of the entry: a field, or the frame.
     *
     * @param structure {@link #TOP}, {@link #BOTTOM} or {@link #FRAME}
     * @return its order count
     */
    int structurePoc(int structure) {
        return structure == FRAME ? Math.min(topPoc, bottomPoc) : fieldPoc(structure);
    }

    /**
     * Returns the motion of a field or of the frame of the entry.
     *
     * @param parity {@link #TOP} or {@link #BOTTOM} for a field coded as a field picture,
     * {@link #FRAME} for a frame
     * @return the motion
     */
    SvcMotionStore motion(int parity) {
        return parity == BOTTOM ? bottomMotion : topMotion;
    }
}
