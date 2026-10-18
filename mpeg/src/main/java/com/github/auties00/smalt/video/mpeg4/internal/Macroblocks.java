package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Holds what the parsing of a picture's macroblocks produces for their reconstruction: the
 * prediction kind and direction, the coded block pattern, the motion vectors and the dequantized
 * transform coefficients of every macroblock.
 *
 * <p>Parsing fills the arrays in macroblock order; reconstruction then reads them, possibly from
 * several threads working on disjoint rows. Coefficient blocks are cleared by the inverse
 * transform that consumes them, so the coefficient array is all zeros between pictures.
 */
public final class Macroblocks {
    /**
     * The kind of an intra macroblock.
     */
    public static final byte INTRA = 0;

    /**
     * The kind of a macroblock predicted as one 16x16 block.
     */
    public static final byte INTER16 = 1;

    /**
     * The kind of a macroblock predicted as four 8x8 blocks.
     */
    public static final byte INTER8 = 2;

    /**
     * The kind of a macroblock predicted field by field.
     */
    public static final byte FIELD = 3;

    /**
     * The kind of a macroblock predicted by global motion compensation.
     */
    public static final byte GMC = 4;

    /**
     * The kind of a macroblock lost to corrupt data, concealed by copying the forward reference.
     */
    public static final byte LOST = 5;

    /**
     * The direction bit of forward prediction.
     */
    public static final byte FORWARD = 1;

    /**
     * The direction bit of backward prediction.
     */
    public static final byte BACKWARD = 2;

    /**
     * The flag of a macroblock whose luminance blocks are field DCT coded.
     */
    public static final byte FIELD_DCT = 1;

    /**
     * The flag of a macroblock predicted with overlapped motion compensation.
     */
    public static final byte OVERLAPPED = 2;

    /**
     * The number of macroblocks.
     */
    public final int count;

    /**
     * The prediction kind of every macroblock.
     */
    public final byte[] kind;

    /**
     * The prediction directions of every inter macroblock.
     */
    public final byte[] direction;

    /**
     * The coded block pattern of every macroblock: bit {@code b} set when block {@code b} has
     * coefficients (intra blocks always do).
     */
    public final byte[] cbp;

    /**
     * The flags of every macroblock.
     */
    public final byte[] flags;

    /**
     * The quantizer of every macroblock.
     */
    public final byte[] quant;

    /**
     * The forward luminance vectors, eight values per macroblock: {@code x, y} of blocks 0 to 3;
     * a 16x16 macroblock repeats its vector.
     */
    public final int[] forward;

    /**
     * The backward luminance vectors, laid out like {@link #forward}.
     */
    public final int[] backward;

    /**
     * The forward field vectors, four values per macroblock: top field {@code x, y}, bottom field
     * {@code x, y}.
     */
    public final int[] forwardField;

    /**
     * The backward field vectors, laid out like {@link #forwardField}.
     */
    public final int[] backwardField;

    /**
     * The field references: bit 0 forward top, bit 1 forward bottom, bit 2 backward top, bit 3
     * backward bottom (each 0 for the top field and 1 for the bottom field of the reference).
     */
    public final byte[] fieldRefs;

    /**
     * The dequantized coefficients, 384 values per macroblock in block order, each block in
     * column-major order: the coefficient of column {@code c} and row {@code r} is at
     * {@code c * 8 + r}.
     */
    public final short[] coefficients;

    /**
     * Allocates the arrays of a picture size.
     *
     * @param count the number of macroblocks
     */
    public Macroblocks(int count) {
        this.count = count;
        this.kind = new byte[count];
        this.direction = new byte[count];
        this.cbp = new byte[count];
        this.flags = new byte[count];
        this.quant = new byte[count];
        this.forward = new int[count * 8];
        this.backward = new int[count * 8];
        this.forwardField = new int[count * 4];
        this.backwardField = new int[count * 4];
        this.fieldRefs = new byte[count];
        this.coefficients = new short[count * 384];
    }

    /**
     * Returns the number of bytes the arrays of a picture size occupy.
     *
     * @param count the number of macroblocks
     * @return the size in bytes
     */
    public static long bytes(int count) {
        return count * (6L + 16 * 4 + 8 * 4 + 384 * 2);
    }
}
