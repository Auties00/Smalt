package com.github.auties00.smalt.video.h263.internal;

/**
 * Holds what the picture headers of one layer of one sub-bitstream carry from picture to picture: the optional modes
 * of the last optional part of PLUSPTYPE with the mode inference rules of ITU-T H.263 5.1.4.5, and the picture format
 * and submodes that headers without that part keep.
 */
final class LayerState {
    /**
     * The Unrestricted Motion Vector mode bit of {@link #modes}.
     */
    static final int UMV = 1 << 11;

    /**
     * The Syntax-based Arithmetic Coding mode bit of {@link #modes}.
     */
    static final int SAC = 1 << 10;

    /**
     * The Advanced Prediction mode bit of {@link #modes}.
     */
    static final int AP = 1 << 9;

    /**
     * The Advanced INTRA Coding mode bit of {@link #modes}.
     */
    static final int AIC = 1 << 8;

    /**
     * The Deblocking Filter mode bit of {@link #modes}.
     */
    static final int DF = 1 << 7;

    /**
     * The Slice Structured mode bit of {@link #modes}.
     */
    static final int SS = 1 << 6;

    /**
     * The Reference Picture Selection mode bit of {@link #modes}.
     */
    static final int RPS = 1 << 5;

    /**
     * The Independent Segment Decoding mode bit of {@link #modes}.
     */
    static final int ISD = 1 << 4;

    /**
     * The Alternative INTER VLC mode bit of {@link #modes}.
     */
    static final int AIV = 1 << 3;

    /**
     * The Modified Quantization mode bit of {@link #modes}.
     */
    static final int MQ = 1 << 2;

    /**
     * The Enhanced Reference Picture Selection mode bit of {@link #modes}.
     */
    static final int ERPS = 1 << 1;

    /**
     * The Data Partitioned Slice mode bit of {@link #modes}.
     */
    static final int DPS = 1;

    /**
     * Whether a header of the layer was seen.
     */
    boolean known;

    /**
     * The optional modes in effect, as the {@code UMV} to {@code DPS} bits.
     */
    int modes;

    /**
     * The source format code of the last optional part of PLUSPTYPE, or of the last PTYPE without one.
     */
    int format;

    /**
     * The width of the last custom picture format.
     */
    int customWidth;

    /**
     * The height of the last custom picture format.
     */
    int customHeight;

    /**
     * The pixel aspect ratio width.
     */
    int parWidth = 12;

    /**
     * The pixel aspect ratio height.
     */
    int parHeight = 11;

    /**
     * Whether the Rectangular Slice submode is in use.
     */
    boolean rectangularSlices;

    /**
     * Whether the Arbitrary Slice Ordering submode is in use.
     */
    boolean arbitrarySliceOrder;

    /**
     * Whether the motion vectors are unlimited (UUI "01").
     */
    boolean unlimitedVectors;

    /**
     * The reference picture selection mode flags.
     */
    int rpsmf = 4;

    /**
     * The picture type of the last picture of the layer, or -1.
     */
    int lastType = -1;

    /**
     * The reference layer number of the last B-picture of the layer.
     */
    int lastReferenceLayer = 1;

    /**
     * Creates the state of a layer without headers.
     */
    LayerState() {
    }

    /**
     * Returns the width of the pictures of the current format.
     *
     * @return the width, 0 for a reserved format
     */
    int width() {
        return switch (format) {
            case 1 -> 128;
            case 2 -> 176;
            case 3 -> 352;
            case 4 -> 704;
            case 5 -> 1408;
            case 6 -> customWidth;
            default -> 0;
        };
    }

    /**
     * Returns the height of the pictures of the current format.
     *
     * @return the height, 0 for a reserved format
     */
    int height() {
        return switch (format) {
            case 1 -> 96;
            case 2 -> 144;
            case 3 -> 288;
            case 4 -> 576;
            case 5 -> 1152;
            case 6 -> customHeight;
            default -> 0;
        };
    }
}
