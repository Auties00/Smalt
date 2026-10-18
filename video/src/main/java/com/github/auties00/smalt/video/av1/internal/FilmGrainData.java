package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the film grain synthesis parameters of one AV1 frame ({@code film_grain_params()},
 * AV1 specification section 5.9.30).
 *
 * <p>Instances are mutable field holders filled by the frame header parser and read by the film
 * grain kernels. Every value is stored as it was coded, in the units the specification uses;
 * point arrays hold the coded {@code (value, scaling)} pairs.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dFilmGrainData}.
 */
public final class FilmGrainData {
    /**
     * The 16-bit seed of the pseudo-random grain generator ({@code grain_seed}).
     */
    public int seed;

    /**
     * The number of luma scaling points ({@code num_y_points}), from 0 to 14.
     */
    public int numYPoints;

    /**
     * The luma scaling points as {@code [point][0 = value, 1 = scaling]}.
     */
    public final int[][] yPoints = new int[14][2];

    /**
     * Whether the chroma scaling functions are derived from the luma one
     * ({@code chroma_scaling_from_luma}).
     */
    public boolean chromaScalingFromLuma;

    /**
     * The number of scaling points of each chroma plane ({@code num_cb_points},
     * {@code num_cr_points}), from 0 to 10.
     */
    public final int[] numUvPoints = new int[2];

    /**
     * The chroma scaling points as {@code [plane][point][0 = value, 1 = scaling]}.
     */
    public final int[][][] uvPoints = new int[2][10][2];

    /**
     * The scaling shift ({@code grain_scaling_minus_8 + 8}), from 8 to 11.
     */
    public int scalingShift;

    /**
     * The auto-regression lag ({@code ar_coeff_lag}), from 0 to 3.
     */
    public int arCoeffLag;

    /**
     * The luma auto-regression coefficients ({@code ar_coeffs_y_plus_128 - 128}).
     */
    public final int[] arCoeffsY = new int[24];

    /**
     * The chroma auto-regression coefficients as {@code [plane][coefficient]}; the coefficient
     * after the luma-lag ones is the luma contribution.
     */
    public final int[][] arCoeffsUv = new int[2][28];

    /**
     * The auto-regression coefficient shift ({@code ar_coeff_shift_minus_6 + 6}), from 6 to 9.
     */
    public int arCoeffShift;

    /**
     * The grain scale shift ({@code grain_scale_shift}), from 0 to 3.
     */
    public int grainScaleShift;

    /**
     * The chroma multipliers ({@code cb_mult}, {@code cr_mult}).
     */
    public final int[] uvMult = new int[2];

    /**
     * The chroma luma multipliers ({@code cb_luma_mult}, {@code cr_luma_mult}).
     */
    public final int[] uvLumaMult = new int[2];

    /**
     * The chroma offsets ({@code cb_offset}, {@code cr_offset}).
     */
    public final int[] uvOffset = new int[2];

    /**
     * Whether overlapping grain blocks are blended ({@code overlap_flag}).
     */
    public boolean overlapFlag;

    /**
     * Whether the output is clipped to the restricted (studio) range
     * ({@code clip_to_restricted_range}).
     */
    public boolean clipToRestrictedRange;

    /**
     * Creates an all-zero parameter set.
     */
    public FilmGrainData() {
    }

    /**
     * Copies every parameter of another set into this one.
     *
     * @param other the parameters to copy
     */
    public void copyFrom(FilmGrainData other) {
        seed = other.seed;
        numYPoints = other.numYPoints;
        for (var i = 0; i < yPoints.length; i++) {
            yPoints[i][0] = other.yPoints[i][0];
            yPoints[i][1] = other.yPoints[i][1];
        }
        chromaScalingFromLuma = other.chromaScalingFromLuma;
        for (var plane = 0; plane < 2; plane++) {
            numUvPoints[plane] = other.numUvPoints[plane];
            for (var i = 0; i < 10; i++) {
                uvPoints[plane][i][0] = other.uvPoints[plane][i][0];
                uvPoints[plane][i][1] = other.uvPoints[plane][i][1];
            }
            System.arraycopy(other.arCoeffsUv[plane], 0, arCoeffsUv[plane], 0, arCoeffsUv[plane].length);
            uvMult[plane] = other.uvMult[plane];
            uvLumaMult[plane] = other.uvLumaMult[plane];
            uvOffset[plane] = other.uvOffset[plane];
        }
        scalingShift = other.scalingShift;
        arCoeffLag = other.arCoeffLag;
        System.arraycopy(other.arCoeffsY, 0, arCoeffsY, 0, arCoeffsY.length);
        arCoeffShift = other.arCoeffShift;
        grainScaleShift = other.grainScaleShift;
        overlapFlag = other.overlapFlag;
        clipToRestrictedRange = other.clipToRestrictedRange;
    }
}
