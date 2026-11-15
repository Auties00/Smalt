package com.github.auties00.smalt.internal.quantize;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Finds the palette entry nearest to a color by squared Euclidean distance in RGB.
 *
 * <p>The palette is given as three arrays of channel values padded to a multiple of
 * {@link #BLOCK} entries with {@link #SENTINEL}, a value no color can come near. The search
 * returns a key that packs the squared distance above the entry index:
 * {@snippet :
 * key = (dr * dr + dg * dg + db * db) << 8 | index
 * }
 * so the smallest key is the nearest entry, ties going to the lowest index, and
 * {@code key >>> 8} is its distance.
 *
 * @implSpec Implementations must return exactly the key of {@link ScalarPaletteSearchKernel}.
 */
public interface PaletteSearchKernel {
    /**
     * The number of entries the padded palette length is a multiple of.
     */
    int BLOCK = 16;

    /**
     * The channel value of a padding entry: its squared distance to any color of 8-bit
     * channels is at least {@code 3 * 345^2}, above the largest real distance {@code 3 * 255^2},
     * and its key still fits in an {@code int}.
     */
    int SENTINEL = 600;

    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    PaletteSearchKernel INSTANCE = Simd.ENABLED ? VectorHolder.create() : new ScalarPaletteSearchKernel();

    /**
     * Returns the key of the palette entry nearest to a color.
     *
     * @param red    the red channel, from 0 to 255
     * @param green  the green channel, from 0 to 255
     * @param blue   the blue channel, from 0 to 255
     * @param reds   the red channel of each entry
     * @param greens the green channel of each entry
     * @param blues  the blue channel of each entry
     * @param count  the padded palette length, a positive multiple of {@link #BLOCK} up to 256
     * @return the squared distance shifted left by 8, or-ed with the entry index
     */
    int nearest(int red, int green, int blue, int[] reds, int[] greens, int[] blues, int count);

    /**
     * Computes, for each palette entry, the smallest and the largest squared distance from the
     * entry to the points of a cube of colors.
     *
     * <p>The cube holds the colors whose channels lie from {@code red}, {@code green} and
     * {@code blue} to the same values plus {@code span - 1}.
     *
     * @param red    the smallest red channel of the cube
     * @param green  the smallest green channel of the cube
     * @param blue   the smallest blue channel of the cube
     * @param span   the cube's side, positive
     * @param reds   the red channel of each entry
     * @param greens the green channel of each entry
     * @param blues  the blue channel of each entry
     * @param count  the padded palette length, a positive multiple of {@link #BLOCK} up to 256
     * @param near   the smallest squared distance of each entry, {@code count} of them
     * @param far    the largest squared distance of each entry, {@code count} of them
     */
    void bounds(int red, int green, int blue, int span, int[] reds, int[] greens, int[] blues, int count, int[] near,
                int[] far);

    /**
     * Creates the Vector API kernel in a class of its own, so that the vector types are linked only
     * when the Vector API is used.
     */
    final class VectorHolder {
        /**
         * Prevents instantiation.
         */
        private VectorHolder() {
            throw new AssertionError();
        }

        /**
         * Creates the Vector API kernel.
         *
         * @return the vector kernel, or the scalar one on platforms whose preferred vectors are
         *         narrower than the eight 32-bit lanes the kernel is written for
         */
        static PaletteSearchKernel create() {
            return Simd.WIDE ? new VectorPaletteSearchKernel() : new ScalarPaletteSearchKernel();
        }
    }
}
