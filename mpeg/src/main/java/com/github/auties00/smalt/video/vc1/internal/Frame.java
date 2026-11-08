package com.github.auties00.smalt.video.vc1.internal;

/**
 * Holds one decoded VC-1 frame: its three 8-bit planes of the macroblock-aligned coded size, and what later pictures
 * read from it as a reference.
 *
 * <p>The planes have no border; rows are {@link #width} luma samples apart and the chroma planes {@link #chromaWidth}
 * samples apart. Instances are recycled by one decoder.
 */
final class Frame {
    /**
     * The luma width.
     */
    final int width;

    /**
     * The luma height.
     */
    final int height;

    /**
     * The width of the color-difference planes.
     */
    final int chromaWidth;

    /**
     * The height of the color-difference planes.
     */
    final int chromaHeight;

    /**
     * The luma samples.
     */
    final byte[] y;

    /**
     * The blue-difference samples.
     */
    final byte[] u;

    /**
     * The red-difference samples.
     */
    final byte[] v;

    /**
     * The direct mode motion vector of each macroblock buffered for the B pictures that follow (section 8.4.5.4), in
     * quarter samples, horizontal in the low 16 bits and vertical in the high 16 bits.
     */
    final int[] directMv;

    /**
     * Whether each macroblock of the frame was intra coded, for direct mode.
     */
    final boolean[] directIntra;

    /**
     * The right limit of the samples motion compensation reads: the coded width in the advanced profile, the
     * macroblock-aligned width in the simple and main profiles.
     */
    int validWidth;

    /**
     * The bottom limit of the samples motion compensation reads: the coded height in the advanced profile, the
     * macroblock-aligned height in the simple and main profiles.
     */
    int validHeight;

    /**
     * The width of the output picture: the coded width of the entry point (or the track) the picture was decoded
     * under.
     */
    int outputWidth;

    /**
     * The height of the output picture.
     */
    int outputHeight;

    /**
     * Whether the frame was coded as interlace (frame or field pictures), which changes how its top and bottom rows
     * are replicated.
     */
    boolean interlaced;

    /**
     * Whether the samples are held range reduced (main profile RANGEREDFRM).
     */
    boolean rangeReduced;

    /**
     * The intensity compensation of each field (index 0 the top field, the even rows) applied when the frame is read
     * as a reference, luma and color-difference tables, or {@code null} for none; the samples themselves are kept for
     * output.
     */
    final byte[][] lumaLut = new byte[2][];

    /**
     * The color-difference intensity compensation of each field, as {@link #lumaLut} holds the luma one.
     */
    final byte[][] chromaLut = new byte[2][];

    /**
     * The luma tables {@link #lumaLut} points to, owned by the frame.
     */
    private final byte[][] lumaStore = new byte[2][];

    /**
     * The color-difference tables {@link #chromaLut} points to, owned by the frame.
     */
    private final byte[][] chromaStore = new byte[2][];

    /**
     * The motion vector of each luma block of each field of an interlace field picture anchor, for direct mode,
     * indexed by field parity, horizontal in the low 16 bits.
     */
    int[][] fieldMv = new int[2][];

    /**
     * Whether each motion vector of {@link #fieldMv} refers to the field of the opposite polarity.
     */
    byte[][] fieldOpposite = new byte[2][];

    /**
     * Whether each macroblock of each field was intra coded.
     */
    boolean[][] fieldIntra = new boolean[2][];

    /**
     * Whether each macroblock of each field was a 4-MV macroblock.
     */
    boolean[][] fieldFourMv = new boolean[2][];

    /**
     * Whether each field used half sample motion vectors.
     */
    final boolean[] fieldHalfpel = new boolean[2];

    /**
     * Whether each field has the motion data of {@link #fieldMv}: false for an I field.
     */
    final boolean[] fieldHasMotion = new boolean[2];

    /**
     * The REFDIST of the picture, which the B pictures predicted from it scale their motion vectors with.
     */
    int referenceDistance;

    /**
     * Whether the frame was coded as an interlace field picture.
     */
    boolean fieldPicture;

    /**
     * The top and bottom field motion vectors of each macroblock of an interlace frame P picture buffered for the
     * direct mode of the B pictures that follow (section 10.8.6.6), two entries per macroblock, or {@code null}.
     */
    int[] interlaceMv;

    /**
     * The picture type of the frame.
     */
    int type;

    /**
     * Whether the frame has motion vectors usable for direct mode: false for I and skipped frames.
     */
    boolean hasMotion;

    /**
     * Whether the picture was coded range reduced (RANGEREDFRM), so its output is scaled up.
     */
    boolean rangeReducedFrame;

    /**
     * The luma range mapping of the entry point in force, or -1 for none.
     */
    int rangeMapY = -1;

    /**
     * The color-difference range mapping of the entry point in force, or -1 for none.
     */
    int rangeMapUv = -1;

    /**
     * Whether the top field is displayed first (TFF).
     */
    boolean topFieldFirst = true;

    /**
     * Whether the first field is displayed twice (RFF).
     */
    boolean repeatFirstField;

    /**
     * The number of times the frame is displayed again (RPTFRM).
     */
    int repeatFrameCount;

    /**
     * Whether the frame is an I picture.
     */
    boolean keyframe;

    /**
     * Whether the frame is displayed progressive rather than as two fields.
     */
    boolean progressiveDisplay = true;

    /**
     * 1 when a multi-resolution frame was coded at half width, 0 otherwise.
     */
    int halfWidth;

    /**
     * 1 when a multi-resolution frame was coded at half height, 0 otherwise.
     */
    int halfHeight;

    /**
     * Whether the frame was handed to the consumer.
     */
    boolean emitted;

    /**
     * Allocates a frame.
     *
     * @param width the luma width, a multiple of 16
     * @param height the luma height, a multiple of 16
     */
    Frame(int width, int height) {
        this.width = width;
        this.height = height;
        this.chromaWidth = width / 2;
        this.chromaHeight = height / 2;
        this.y = new byte[width * height];
        this.u = new byte[chromaWidth * chromaHeight];
        this.v = new byte[chromaWidth * chromaHeight];
        var mbs = (width / 16) * (height / 16);
        this.directMv = new int[mbs];
        this.directIntra = new boolean[mbs];
    }

    /**
     * Returns one plane.
     *
     * @param plane 0 luma, 1 blue-difference, 2 red-difference
     * @return the samples
     */
    byte[] plane(int plane) {
        return plane == 0 ? y : plane == 1 ? u : v;
    }

    /**
     * Copies the samples and the reference state of another frame of the same size.
     *
     * @param other the source
     */
    void copyFrom(Frame other) {
        System.arraycopy(other.y, 0, y, 0, y.length);
        System.arraycopy(other.u, 0, u, 0, u.length);
        System.arraycopy(other.v, 0, v, 0, v.length);
        for (var parity = 0; parity < 2; parity++) {
            if (other.lumaLut[parity] != null) {
                remapRows(y, width, parity, other.lumaLut[parity]);
                remapRows(u, chromaWidth, parity, other.chromaLut[parity]);
                remapRows(v, chromaWidth, parity, other.chromaLut[parity]);
            }
            lumaLut[parity] = null;
            chromaLut[parity] = null;
        }
        validWidth = other.validWidth;
        validHeight = other.validHeight;
        outputWidth = other.outputWidth;
        outputHeight = other.outputHeight;
        interlaced = other.interlaced;
        rangeReduced = other.rangeReduced;
    }

    /**
     * Applies the range reduction mapping of section 8.3.4.11 to every sample.
     *
     * @param down whether to scale down, {@code (x - 128) >> 1) + 128}; otherwise up, {@code clip((x - 128) * 2 + 128)}
     */
    void scaleRange(boolean down) {
        for (var parity = 0; parity < 2; parity++) {
            if (lumaLut[parity] != null) {
                remapRows(y, width, parity, lumaLut[parity]);
                remapRows(u, chromaWidth, parity, chromaLut[parity]);
                remapRows(v, chromaWidth, parity, chromaLut[parity]);
                lumaLut[parity] = null;
                chromaLut[parity] = null;
            }
        }
        scale(y, down);
        scale(u, down);
        scale(v, down);
        rangeReduced = down;
    }

    /**
     * Applies the range reduction mapping to one plane.
     *
     * @param samples the plane
     * @param down whether to scale down
     */
    private static void scale(byte[] samples, boolean down) {
        for (var i = 0; i < samples.length; i++) {
            var value = samples[i] & 0xFF;
            samples[i] = (byte) (down ? ((value - 128) >> 1) + 128 : clip((value - 128) * 2 + 128));
        }
    }

    /**
     * Maps the rows of one parity of a plane through a table.
     *
     * @param samples the plane
     * @param stride the distance between rows
     * @param parity the rows, 0 for the even rows
     * @param table the mapping
     */
    private static void remapRows(byte[] samples, int stride, int parity, byte[] table) {
        for (var row = parity; row * stride < samples.length; row += 2) {
            var base = row * stride;
            for (var x = 0; x < stride; x++) {
                samples[base + x] = table[samples[base + x] & 0xFF];
            }
        }
    }

    /**
     * Composes an intensity compensation onto the reads of one field.
     *
     * @param parity the field, 0 for the top field
     * @param luma the luma table to apply after the field's current one
     * @param chroma the color-difference table to apply after the field's current one
     */
    void compensate(int parity, byte[] luma, byte[] chroma) {
        lumaLut[parity] = compose(lumaLut[parity], luma, lumaStore, parity);
        chromaLut[parity] = compose(chromaLut[parity], chroma, chromaStore, parity);
    }

    /**
     * Composes a mapping after the current one of a field, into the field's own table.
     *
     * @param first the current mapping, the field's table or {@code null} for the identity
     * @param second the mapping applied after it
     * @param store the tables the frame owns, allocated on first use
     * @param parity the field
     * @return the field's table
     */
    private static byte[] compose(byte[] first, byte[] second, byte[][] store, int parity) {
        if (store[parity] == null) {
            store[parity] = new byte[256];
        }
        var out = store[parity];
        for (var i = 0; i < 256; i++) {
            out[i] = second[first == null ? i : first[i] & 0xFF];
        }
        return out;
    }

    /**
     * Allocates the direct mode data of one field.
     *
     * @param parity the field, 0 for the top field
     * @param mbs the macroblocks of the field
     */
    void fieldMotion(int parity, int mbs) {
        if (fieldMv[parity] == null || fieldMv[parity].length < mbs * 4) {
            fieldMv[parity] = new int[mbs * 4];
            fieldOpposite[parity] = new byte[mbs * 4];
            fieldIntra[parity] = new boolean[mbs];
            fieldFourMv[parity] = new boolean[mbs];
        }
    }

    /**
     * Clips a sample to the 8-bit range.
     *
     * @param value the value before the operation
     * @return the value clipped to 0 to 255
     */
    static int clip(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
