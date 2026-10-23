package com.github.auties00.smalt.video.h264.internal;

/**
 * Holds one entry of the decoded picture buffer of a dependency representation of the scalable
 * decoding process (ITU-T H.264 F.8.2): a decoded picture or a reference base picture of the
 * dependency representation with the highest {@code dependency_id}, which carries samples, or a
 * layer picture of a lower dependency representation, which carries only its picture numbers,
 * its marking and, for {@code dependency_id} 0, the motion that direct prediction reads.
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
     * Whether the entry is a reference base picture.
     */
    boolean refBase;

    /**
     * The reference marking, one of {@link #UNUSED}, {@link #SHORT_TERM} and {@link #LONG_TERM}.
     */
    int marking;

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
     * The picture order count {@code PicOrderCnt}.
     */
    int poc;

    /**
     * The {@code temporal_id} of the access unit.
     */
    int temporalId;

    /**
     * Whether the entry stands for a frame lost to a gap in {@code frame_num}.
     */
    boolean nonExisting;

    /**
     * Whether the picture waits to be output.
     */
    boolean outputNeeded;

    /**
     * The samples of a picture of the target dependency representation, or {@code null}.
     */
    DecoderFrame frame;

    /**
     * The macroblock types of a layer picture of dependency 0, or {@code null}.
     */
    byte[] mbType;

    /**
     * The list 0 reference indices of a layer picture of dependency 0, four per macroblock.
     */
    byte[] refIdx0;

    /**
     * The list 1 reference indices of a layer picture of dependency 0, four per macroblock.
     */
    byte[] refIdx1;

    /**
     * The list 0 motion vectors of a layer picture of dependency 0, 32 components per
     * macroblock.
     */
    short[] mv0;

    /**
     * The list 1 motion vectors of a layer picture of dependency 0, 32 components per
     * macroblock.
     */
    short[] mv1;

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
     * Returns whether the entry is a reference.
     *
     * @return {@code true} when marked short-term or long-term
     */
    boolean isReference() {
        return marking != UNUSED;
    }

    /**
     * Keeps a copy of the motion of a layer representation, for the co-located lookups of the
     * following pictures.
     *
     * @param layer the arrays of the layer representation with DQId 0
     */
    void keepMotion(SvcLayer layer) {
        var count = layer.mbCount;
        if (mbType == null || mbType.length != count) {
            mbType = new byte[count];
            refIdx0 = new byte[count * 4];
            refIdx1 = new byte[count * 4];
            mv0 = new short[count * 32];
            mv1 = new short[count * 32];
        }
        System.arraycopy(layer.mbType, 0, mbType, 0, count);
        System.arraycopy(layer.refIdx0, 0, refIdx0, 0, count * 4);
        System.arraycopy(layer.refIdx1, 0, refIdx1, 0, count * 4);
        System.arraycopy(layer.mv0, 0, mv0, 0, count * 32);
        System.arraycopy(layer.mv1, 0, mv1, 0, count * 32);
    }

    /**
     * Returns whether a 4x4 block of the picture, as the co-located block of the spatial direct
     * prediction (ITU-T H.264 8.4.1.2.2), has reference index 0 and a motion vector within one
     * quarter sample of zero in each component.
     *
     * @param mbAddr the address of the co-located macroblock
     * @param block the 4x4 block in raster order
     * @return the {@code colZeroFlag} conditions on the co-located motion
     */
    boolean colocatedZero(int mbAddr, int block) {
        if (mbType == null || mbAddr >= mbType.length || !SvcMb.inter(mbType[mbAddr])) {
            return false;
        }
        var quadrant = mbAddr * 4 + (block >> 3) * 2 + ((block & 3) >> 1);
        int ref;
        short[] mvs;
        if (refIdx0[quadrant] >= 0) {
            ref = refIdx0[quadrant];
            mvs = mv0;
        } else {
            ref = refIdx1[quadrant];
            mvs = mv1;
        }
        if (ref != 0) {
            return false;
        }
        var at = mbAddr * 32 + block * 2;
        return Math.abs(mvs[at]) <= 1 && Math.abs(mvs[at + 1]) <= 1;
    }
}
