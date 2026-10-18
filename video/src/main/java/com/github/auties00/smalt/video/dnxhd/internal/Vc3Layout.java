package com.github.auties00.smalt.video.dnxhd.internal;

/**
 * Describes how the DCT blocks of a VC-3 macroblock map to the picture's planes, for each chroma
 * layout (SMPTE ST 2019-1 Tables 4, 5 and 6): the component of every block, its position inside
 * the macroblock's area of that component, and whether it uses the chroma quantization weights.
 */
public final class Vc3Layout {
    /**
     * The layout of 4:2:2 macroblocks: Y0, Y1, Cb0, Cr0, Y2, Y3, Cb1, Cr1.
     */
    public static final Vc3Layout YUV422 = new Vc3Layout(
            new int[]{0, 0, 1, 2, 0, 0, 1, 2},
            new int[]{0, 8, 0, 0, 0, 8, 0, 0},
            new int[]{0, 0, 0, 0, 8, 8, 8, 8},
            1, 0);

    /**
     * The layout of 4:2:0 macroblocks: Y0, Y1, Cb0, Cr0, Y2, Y3.
     */
    public static final Vc3Layout YUV420 = new Vc3Layout(
            new int[]{0, 0, 1, 2, 0, 0},
            new int[]{0, 8, 0, 0, 0, 8},
            new int[]{0, 0, 0, 0, 8, 8},
            1, 1);

    /**
     * The layout of 4:4:4 macroblocks: two blocks of each component in the top half, then two of
     * each in the bottom half.
     */
    public static final Vc3Layout YUV444 = new Vc3Layout(
            new int[]{0, 0, 1, 1, 2, 2, 0, 0, 1, 1, 2, 2},
            new int[]{0, 8, 0, 8, 0, 8, 0, 8, 0, 8, 0, 8},
            new int[]{0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 8, 8},
            0, 0);

    /**
     * The layout of DCT-coded alpha macroblocks (ST 2019-1 Table 7): A0, A1, A2, A3, in raster
     * order over the macroblock, every block of the single alpha plane.
     */
    public static final Vc3Layout ALPHA = new Vc3Layout(
            new int[]{0, 0, 0, 0},
            new int[]{0, 8, 0, 8},
            new int[]{0, 0, 8, 8},
            0, 0);

    /**
     * The component of every block: 0 for luma (or the first RGB channel), 1 and 2 for the others.
     */
    final int[] components;

    /**
     * The horizontal offset of every block inside its component's macroblock area.
     */
    final int[] xs;

    /**
     * The vertical offset of every block inside its component's macroblock area.
     */
    final int[] ys;

    /**
     * The base-2 logarithm of the horizontal chroma subsampling.
     */
    final int chromaShiftX;

    /**
     * The base-2 logarithm of the vertical chroma subsampling.
     */
    final int chromaShiftY;

    /**
     * Creates a layout.
     *
     * @param components   the component of every block
     * @param xs           the horizontal offset of every block
     * @param ys           the vertical offset of every block
     * @param chromaShiftX the horizontal chroma subsampling shift
     * @param chromaShiftY the vertical chroma subsampling shift
     */
    private Vc3Layout(int[] components, int[] xs, int[] ys, int chromaShiftX, int chromaShiftY) {
        this.components = components;
        this.xs = xs;
        this.ys = ys;
        this.chromaShiftX = chromaShiftX;
        this.chromaShiftY = chromaShiftY;
    }

    /**
     * Returns the layout of a sub sampling control value.
     *
     * @param subsampling 0 for 4:2:2, 1 for 4:2:0, 2 for 4:4:4
     * @return the layout
     */
    public static Vc3Layout of(int subsampling) {
        return switch (subsampling) {
            case 1 -> YUV420;
            case 2 -> YUV444;
            default -> YUV422;
        };
    }

    /**
     * Returns the number of blocks of a macroblock.
     *
     * @return 4, 6, 8 or 12
     */
    public int blocks() {
        return components.length;
    }
}
