package com.github.auties00.smalt.video.h264.internal;

/**
 * Holds the motion of one coded picture (a frame or a field) of the dependency representation with
 * {@code dependency_id} 0, which the direct prediction of later pictures reads as co-located motion
 * (ITU-T H.264 8.4.1.2.1): per macroblock whether it is a field macroblock, per 8x8 quadrant the
 * reference indices and the pictures they referred to, and per 4x4 block the motion vectors, in the
 * layout of {@link SvcLayer}.
 */
final class SvcMotionStore {
    /**
     * Whether the store holds the motion of a picture.
     */
    boolean present;

    /**
     * The width of the picture in macroblocks.
     */
    int widthMbs;

    /**
     * The number of macroblocks of the picture.
     */
    int mbCount;

    /**
     * Whether the picture was coded as a field.
     */
    boolean field;

    /**
     * Whether the picture is a frame coded with {@code MbaffFrameFlag} 1.
     */
    boolean mbaff;

    /**
     * Whether each macroblock is a field macroblock.
     */
    boolean[] fieldMb = new boolean[0];

    /**
     * The list 0 reference indices, four per macroblock, -1 for an intra macroblock or an unused
     * list.
     */
    byte[] refIdx0 = new byte[0];

    /**
     * The list 1 reference indices, laid out as {@link #refIdx0}.
     */
    byte[] refIdx1 = new byte[0];

    /**
     * The pictures the list 0 reference indices referred to, four per macroblock: the
     * {@link SvcPicture#id} of the entry times 4 plus the structure of the reference
     * ({@link SvcPicture#TOP}, {@link SvcPicture#BOTTOM} or {@link SvcPicture#FRAME}), -1 when
     * unused.
     */
    int[] refPic0 = new int[0];

    /**
     * The pictures the list 1 reference indices referred to, laid out as {@link #refPic0}.
     */
    int[] refPic1 = new int[0];

    /**
     * The list 0 motion vectors, 32 components per macroblock as in {@link SvcLayer#mv0}.
     */
    short[] mv0 = new short[0];

    /**
     * The list 1 motion vectors, laid out as {@link #mv0}.
     */
    short[] mv1 = new short[0];

    /**
     * Constructs an empty store.
     */
    SvcMotionStore() {
    }

    /**
     * Copies the motion of a layer representation.
     *
     * @param layer the arrays of the layer representation with DQId 0, whose reference indices
     * {@code refPics} resolves
     * @param refPics the identification of the picture each reference index of each quadrant
     * referred to, indexed {@code [list][mbAddr * 4 + quadrant]}, as {@link #refPic0}
     */
    void keep(SvcLayer layer, int[][] refPics) {
        var count = layer.mbCount;
        if (refIdx0.length != count * 4) {
            fieldMb = new boolean[count];
            refIdx0 = new byte[count * 4];
            refIdx1 = new byte[count * 4];
            refPic0 = new int[count * 4];
            refPic1 = new int[count * 4];
            mv0 = new short[count * 32];
            mv1 = new short[count * 32];
        }
        present = true;
        widthMbs = layer.widthMbs;
        mbCount = count;
        field = layer.fieldPic;
        mbaff = layer.mbaff;
        System.arraycopy(layer.fieldMb, 0, fieldMb, 0, count);
        System.arraycopy(layer.refIdx0, 0, refIdx0, 0, count * 4);
        System.arraycopy(layer.refIdx1, 0, refIdx1, 0, count * 4);
        System.arraycopy(refPics[0], 0, refPic0, 0, count * 4);
        System.arraycopy(refPics[1], 0, refPic1, 0, count * 4);
        System.arraycopy(layer.mv0, 0, mv0, 0, count * 32);
        System.arraycopy(layer.mv1, 0, mv1, 0, count * 32);
    }

    /**
     * Fills the store with the motion of intra macroblocks, for a field that was never decoded.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the field height in macroblocks
     */
    void intra(int widthMbs, int heightMbs) {
        var count = widthMbs * heightMbs;
        if (refIdx0.length != count * 4) {
            fieldMb = new boolean[count];
            refIdx0 = new byte[count * 4];
            refIdx1 = new byte[count * 4];
            refPic0 = new int[count * 4];
            refPic1 = new int[count * 4];
            mv0 = new short[count * 32];
            mv1 = new short[count * 32];
        }
        present = true;
        this.widthMbs = widthMbs;
        mbCount = count;
        field = true;
        mbaff = false;
        java.util.Arrays.fill(fieldMb, true);
        java.util.Arrays.fill(refIdx0, (byte) -1);
        java.util.Arrays.fill(refIdx1, (byte) -1);
        java.util.Arrays.fill(refPic0, -1);
        java.util.Arrays.fill(refPic1, -1);
        java.util.Arrays.fill(mv0, (short) 0);
        java.util.Arrays.fill(mv1, (short) 0);
    }
}
