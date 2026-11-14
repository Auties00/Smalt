package com.github.auties00.smalt.video.mpeg2.internal;

import java.util.Arrays;

/**
 * Holds one decoded frame: its three 8-bit planes of the macroblock-aligned coded size, the per macroblock state
 * that concealment reads, and what the decoder records about the pictures it was decoded from.
 *
 * <p>The planes have no border: rows are {@link #width} luma samples apart and the chroma planes
 * {@link #chromaWidth} samples apart; a field is addressed with twice the frame stride, from row 0 for the top field
 * and row 1 for the bottom field. Instances are recycled by one decoder; slices of one picture write disjoint
 * macroblocks from several threads.
 */
final class Frame {
    /**
     * The luma width, the macroblock column count times 16.
     */
    final int width;

    /**
     * The luma height, the macroblock row count times 16.
     */
    final int height;

    /**
     * The chroma width.
     */
    final int chromaWidth;

    /**
     * The chroma height.
     */
    final int chromaHeight;

    /**
     * The luma plane.
     */
    final byte[] y;

    /**
     * The blue-difference plane.
     */
    final byte[] u;

    /**
     * The red-difference plane.
     */
    final byte[] v;

    /**
     * Whether each macroblock of the picture being decoded was reconstructed from the stream, indexed by the
     * macroblock address of the picture (a field picture has half the rows of a frame).
     */
    final boolean[] decoded;

    /**
     * Whether each macroblock of the picture being decoded left a forward motion vector that concealment can use
     * for the macroblock below it: a forward prediction or a concealment motion vector.
     */
    final boolean[] hasVector;

    /**
     * The horizontal component of that vector, in half samples.
     */
    final int[] vectorX;

    /**
     * The vertical component of that vector, in half samples of the picture (frame rows for a frame picture, field
     * rows for a field picture).
     */
    final int[] vectorY;

    /**
     * The {@code picture_coding_type} of the first picture of the frame.
     */
    int codingType;

    /**
     * The {@code picture_structure} of the first picture: 1 top field, 2 bottom field, 3 frame.
     */
    int structure;

    /**
     * The number of field pictures decoded into the frame, 0 for a frame picture.
     */
    int fields;

    /**
     * Whether the frame is progressive: an MPEG-1 picture, or {@code progressive_frame} of an MPEG-2 frame
     * picture.
     */
    boolean progressive;

    /**
     * {@code top_field_first} of the first picture.
     */
    boolean topFieldFirst;

    /**
     * {@code repeat_first_field} of the first picture.
     */
    boolean repeatFirstField;

    /**
     * Whether the frame was decoded with damaged slices.
     */
    boolean damaged;

    /**
     * Whether the frame has been handed out.
     */
    boolean emitted;

    /**
     * The closed caption constructs of the frame's pictures, or {@code null}.
     */
    byte[] captions;

    /**
     * Allocates a frame.
     *
     * @param mbWidth the macroblock column count
     * @param mbHeight the macroblock row count of a frame
     * @param chromaFormat 1 for 4:2:0, 2 for 4:2:2, 3 for 4:4:4
     */
    Frame(int mbWidth, int mbHeight, int chromaFormat) {
        this.width = mbWidth * 16;
        this.height = mbHeight * 16;
        this.chromaWidth = chromaFormat == 3 ? width : width / 2;
        this.chromaHeight = chromaFormat == 1 ? height / 2 : height;
        this.y = new byte[width * height];
        this.u = new byte[chromaWidth * chromaHeight];
        this.v = new byte[chromaWidth * chromaHeight];
        var count = mbWidth * mbHeight;
        this.decoded = new boolean[count];
        this.hasVector = new boolean[count];
        this.vectorX = new int[count];
        this.vectorY = new int[count];
    }

    /**
     * Returns whether the frame was allocated for a coded size and chroma format.
     *
     * @param mbWidth the macroblock column count
     * @param mbHeight the macroblock row count of a frame
     * @param chromaFormat the chroma format
     * @return {@code true} when it matches
     */
    boolean fits(int mbWidth, int mbHeight, int chromaFormat) {
        return width == mbWidth * 16 && height == mbHeight * 16
                && chromaWidth == (chromaFormat == 3 ? width : width / 2)
                && chromaHeight == (chromaFormat == 1 ? height / 2 : height);
    }

    /**
     * Clears the per macroblock state before a picture is decoded into the frame.
     *
     * @param count the number of macroblocks of the picture
     */
    void startPicture(int count) {
        Arrays.fill(decoded, 0, count, false);
        Arrays.fill(hasVector, 0, count, false);
    }

    /**
     * Fills the rows of the field that was not decoded with the rows of the decoded one, the picture of a frame
     * whose second field never came.
     *
     * @param decodedBottom whether the decoded field is the bottom field
     */
    void doubleField(boolean decodedBottom) {
        doublePlane(y, width, height, decodedBottom);
        doublePlane(u, chromaWidth, chromaHeight, decodedBottom);
        doublePlane(v, chromaWidth, chromaHeight, decodedBottom);
    }

    /**
     * Copies each row of one field of a plane over the adjacent row of the other field.
     *
     * @param plane the samples of a luma or chroma plane
     * @param stride the row length
     * @param rows the row count, even
     * @param fromBottom whether the bottom field is the source
     */
    private static void doublePlane(byte[] plane, int stride, int rows, boolean fromBottom) {
        for (var row = 0; row + 1 < rows; row += 2) {
            if (fromBottom) {
                System.arraycopy(plane, (row + 1) * stride, plane, row * stride, stride);
            } else {
                System.arraycopy(plane, row * stride, plane, (row + 1) * stride, stride);
            }
        }
    }
}
