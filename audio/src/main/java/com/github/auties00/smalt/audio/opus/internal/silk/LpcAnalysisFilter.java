/* Ports libopus 1.5.2 (silk/LPC_analysis_filter.c). Copyright Skype Limited; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

/**
 * Filters a signal with an LPC analysis (whitening) filter that always starts from a zero state.
 *
 * <p>The filtering runs through {@link SilkKernels}, whose scalar and vector implementations are
 * bit-identical.
 *
 * @implNote This implementation is the generic path, with {@code USE_CELT_FIR} disabled.
 */
public final class LpcAnalysisFilter {
    private LpcAnalysisFilter() {
        throw new AssertionError();
    }

    /**
     * Computes the prediction residual of a signal ({@code silk_LPC_analysis_filter}).
     *
     * <p>The first {@code d} output samples are set to zero. Intermediate sums wrap like the
     * reference so that two wraps can cancel; the output is saturated.
     *
     * @param out    receives the residual
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param B      the prediction coefficients in Q12
     * @param BOff   the index of the first coefficient
     * @param len    the number of samples, at least {@code d}
     * @param d      the filter order, even and at least 6; {@code out} and {@code in} must not
     *               overlap
     */
    public static void lpcAnalysisFilter(short[] out, int outOff, short[] in, int inOff, short[] B, int BOff, int len,
                                         int d) {
        SilkKernels.INSTANCE.lpcAnalysisFilter(out, outOff, in, inOff, B, BOff, len, d);
        Arrays.fill(out, outOff, outOff + d, (short) 0);
    }
}
