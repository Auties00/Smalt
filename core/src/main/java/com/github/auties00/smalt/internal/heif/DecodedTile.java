package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.internal.plane.Narrow;

/**
 * Presents a picture decoded by a codec to a {@link HeifCanvas}: its sample planes, where its
 * visible samples start, and the kernel that narrows its deep samples to 8 bits.
 *
 * <p>Planes are numbered 0 for luma, 1 and 2 for the chroma planes. A tile stores its samples
 * either in {@code byte} planes or in {@code short} planes. Implementations are views of the
 * codec's own picture, valid while the codec keeps it.
 */
public interface DecodedTile {
    /**
     * {@return whether the samples are stored in the {@code byte} planes of {@link #plane8(int)}}
     */
    boolean eightBit();

    /**
     * {@return the {@code byte} samples of a plane, when {@link #eightBit()}}
     *
     * @param plane the plane, from 0 to 2
     */
    byte[] plane8(int plane);

    /**
     * {@return the {@code short} samples of a plane, when not {@link #eightBit()}}
     *
     * @param plane the plane, from 0 to 2
     */
    short[] plane16(int plane);

    /**
     * {@return the index of the first visible sample of a plane, after any cropping}
     *
     * @param plane the plane, from 0 to 2
     */
    int offset(int plane);

    /**
     * {@return the row stride of a plane, in samples}
     *
     * @param plane the plane, from 0 to 2
     */
    int stride(int plane);

    /**
     * {@return the bit depth of the samples of a plane, from 8 to 16}
     *
     * @param plane the plane, from 0 to 2
     */
    int depth(int plane);

    /**
     * Narrows rows of deep samples to 8 bits by rounding to nearest, as {@link Narrow} defines it.
     *
     * @implSpec The default implementation runs {@link Narrow#select()}.
     *
     * @param src the deep samples
     * @param srcOffset the index of the first sample of the first row
     * @param srcStride the source row stride
     * @param dst the 8-bit samples
     * @param dstOffset the index of the first sample of the first row
     * @param dstStride the destination row stride
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the bit depth minus 8
     */
    default void narrow(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width,
                        int height, int shift) {
        Narrow.select().narrow(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, shift);
    }
}
