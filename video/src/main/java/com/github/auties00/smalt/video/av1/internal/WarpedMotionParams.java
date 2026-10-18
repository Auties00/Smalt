package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the parameters of an AV1 warped motion model: a global motion model of a reference frame
 * or the local warp of a block (AV1 specification sections 7.10 and 7.11.3).
 *
 * <p>The affine matrix maps {@code (x, y)} to
 * {@code (m2 * x + m3 * y + m0, m4 * x + m5 * y + m1)} in 1/65536 units; the shear parameters
 * {@code alpha, beta, gamma, delta} derived from it drive the warp filter.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dWarpedMotionParams}.
 */
public final class WarpedMotionParams {
    /**
     * The identity model.
     */
    public static final int IDENTITY = 0;

    /**
     * A pure translation.
     */
    public static final int TRANSLATION = 1;

    /**
     * A rotation and zoom.
     */
    public static final int ROT_ZOOM = 2;

    /**
     * A general affine model.
     */
    public static final int AFFINE = 3;

    /**
     * The model type, one of {@link #IDENTITY}, {@link #TRANSLATION}, {@link #ROT_ZOOM} and
     * {@link #AFFINE}.
     */
    public int type;

    /**
     * The six affine matrix entries.
     */
    public final int[] matrix = {0, 0, 1 << 16, 0, 0, 1 << 16};

    /**
     * The shear parameters {@code alpha, beta, gamma, delta}, as 16-bit values.
     */
    public final short[] abcd = new short[4];

    /**
     * Creates an identity model.
     */
    public WarpedMotionParams() {
    }

    /**
     * Resets this model to the identity with the default matrix.
     */
    public void setDefault() {
        type = IDENTITY;
        matrix[0] = 0;
        matrix[1] = 0;
        matrix[2] = 1 << 16;
        matrix[3] = 0;
        matrix[4] = 0;
        matrix[5] = 1 << 16;
        abcd[0] = 0;
        abcd[1] = 0;
        abcd[2] = 0;
        abcd[3] = 0;
    }

    /**
     * Copies another model into this one.
     *
     * @param other the model to copy
     */
    public void copyFrom(WarpedMotionParams other) {
        type = other.type;
        System.arraycopy(other.matrix, 0, matrix, 0, 6);
        System.arraycopy(other.abcd, 0, abcd, 0, 4);
    }
}
