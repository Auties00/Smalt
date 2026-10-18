/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Describes one two-stage NLSF codebook: the first-stage vectors and weights, the second-stage
 * residual entropy coding tables, and the stabilization distances.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class NlsfCodebook {
    /**
     * The number of first-stage vectors.
     */
    public final short nVectors;

    /**
     * The LPC order the codebook applies to.
     */
    public final short order;

    /**
     * The second-stage quantization step size in Q16.
     */
    public final short quantStepSize_Q16;

    /**
     * The inverse of the second-stage quantization step size in Q6.
     */
    public final short invQuantStepSize_Q6;

    /**
     * The first-stage vectors in Q8, {@code nVectors * order} unsigned bytes.
     */
    public final short[] CB1_NLSF_Q8;

    /**
     * The first-stage weights in Q9, {@code nVectors * order} values.
     */
    public final short[] CB1_Wght_Q9;

    /**
     * The iCDFs of the first-stage index, one of {@code nVectors} entries per signal class.
     */
    public final short[] CB1_iCDF;

    /**
     * The backward predictors of the second-stage residuals in Q8, two sets of {@code order - 1}.
     */
    public final short[] pred_Q8;

    /**
     * The per-coefficient selectors of the residual distributions, packed two per byte.
     */
    public final short[] ec_sel;

    /**
     * The iCDFs of the second-stage residuals.
     */
    public final short[] ec_iCDF;

    /**
     * The rates in Q5 of the second-stage residuals.
     */
    public final short[] ec_Rates_Q5;

    /**
     * The minimum distances between consecutive NLSFs in Q15, {@code order + 1} values.
     */
    public final short[] deltaMin_Q15;

    /**
     * Creates a codebook.
     *
     * @param nVectors            the number of first-stage vectors
     * @param order               the LPC order
     * @param quantStepSize_Q16   the step size in Q16
     * @param invQuantStepSize_Q6 the inverse step size in Q6
     * @param CB1_NLSF_Q8         the first-stage vectors
     * @param CB1_Wght_Q9         the first-stage weights
     * @param CB1_iCDF            the first-stage iCDFs
     * @param pred_Q8             the residual predictors
     * @param ec_sel              the distribution selectors
     * @param ec_iCDF             the residual iCDFs
     * @param ec_Rates_Q5         the residual rates
     * @param deltaMin_Q15        the minimum distances
     */
    NlsfCodebook(int nVectors, int order, int quantStepSize_Q16, int invQuantStepSize_Q6, short[] CB1_NLSF_Q8,
                 short[] CB1_Wght_Q9, short[] CB1_iCDF, short[] pred_Q8, short[] ec_sel, short[] ec_iCDF,
                 short[] ec_Rates_Q5, short[] deltaMin_Q15) {
        this.nVectors = (short) nVectors;
        this.order = (short) order;
        this.quantStepSize_Q16 = (short) quantStepSize_Q16;
        this.invQuantStepSize_Q6 = (short) invQuantStepSize_Q6;
        this.CB1_NLSF_Q8 = CB1_NLSF_Q8;
        this.CB1_Wght_Q9 = CB1_Wght_Q9;
        this.CB1_iCDF = CB1_iCDF;
        this.pred_Q8 = pred_Q8;
        this.ec_sel = ec_sel;
        this.ec_iCDF = ec_iCDF;
        this.ec_Rates_Q5 = ec_Rates_Q5;
        this.deltaMin_Q15 = deltaMin_Q15;
    }
}
