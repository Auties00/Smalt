package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Holds the latest complex QMF columns of one channel in a ring, 64 subbands per column, addressed by the
 * column's index in the stream.
 *
 * <p>A column is readable until {@link #CAPACITY} newer columns have been written. Instances are not
 * thread-safe.
 */
final class SbrColumns {
    /**
     * The number of columns the ring keeps, a power of two.
     */
    static final int CAPACITY = 256;

    /**
     * The real parts, 64 per column.
     */
    private final float[] re = new float[CAPACITY * 64];

    /**
     * The imaginary parts, 64 per column.
     */
    private final float[] im = new float[CAPACITY * 64];

    /**
     * Creates a ring of silent columns.
     */
    SbrColumns() {
    }

    /**
     * Returns the index of a column's subband 0 in {@link #re()} and {@link #im()}.
     *
     * @param column the column's index in the stream; negative indices address silent columns before the
     * stream starts as long as no column was written over them
     * @return the array index
     */
    static int offset(long column) {
        return (int) (column & (CAPACITY - 1)) * 64;
    }

    /**
     * {@return the real parts, 64 per column, at {@link #offset(long)}}
     */
    float[] re() {
        return re;
    }

    /**
     * {@return the imaginary parts, 64 per column, at {@link #offset(long)}}
     */
    float[] im() {
        return im;
    }

    /**
     * Returns the energy of one subband of one column.
     *
     * @param column the column's index in the stream
     * @param band the subband, below 64
     * @return the squared magnitude
     */
    float energy(long column, int band) {
        var index = offset(column) + band;
        var r = re[index];
        var i = im[index];
        return r * r + i * i;
    }
}
