package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the polyphase FIR outputs of the {@link Resampler}.
 *
 * <p>Every filter evaluation has one exactly defined arithmetic, so the two implementations,
 * {@link ScalarFirKernel} (the reference) and the Vector API one created by
 * {@link VectorProcessKernels#fir()}, return bit-identical results. The taps (a multiple of
 * {@value #CHAINS}) are split into {@value #CHAINS} chains by their index modulo
 * {@value #CHAINS}; chain {@code m} is the sequential fused multiply-add
 * {@code acc = fma(x[k], h[k], acc)} over {@code k = m, m + 8, m + 16, ...} starting from
 * {@code +0}; and the chain results {@code c0 .. c7} are combined as
 * {@code ((c0 + c1) + (c2 + c3)) + ((c4 + c5) + (c6 + c7))}. This is exactly what one eight-lane
 * vector accumulator followed by a fixed pairwise reduction computes.
 *
 * <p>An output at input position {@code base}, whose phase selects the coefficient row
 * {@code phase * taps} of the table, is {@code dot(x, base, table, phase * taps, taps)}. In
 * interpolated mode the phase selects row {@code slot = phase * phases / up} and the weight
 * {@code fraction = (phase * phases mod up) / up}, and the output is
 * {@code dot(x, base, table, slot * taps, taps) + fraction * dot(x, base, slopes, slot * taps,
 * taps)}, each operation rounded to float.
 */
sealed interface FirKernel permits ScalarFirKernel, VectorProcessKernels.VectorFirKernel {
    /**
     * The number of interleaved accumulation chains of every dot product.
     */
    int CHAINS = 8;

    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    FirKernel SELECTED = Simd.ENABLED ? VectorProcessKernels.fir() : new ScalarFirKernel();

    /**
     * Returns the dot product of {@code taps} signal samples and coefficients with the arithmetic
     * described on the interface.
     *
     * @param x       the signal
     * @param xOffset the index of the first signal sample
     * @param h       the coefficients
     * @param hOffset the index of the first coefficient
     * @param taps    the number of products; a non-negative multiple of {@value #CHAINS}
     * @return the dot product
     */
    float dot(float[] x, int xOffset, float[] h, int hOffset, int taps);

    /**
     * Computes {@code count} consecutive outputs from an exact phase table.
     *
     * <p>Output {@code i} is evaluated at signal index {@code base_i} with phase
     * {@code phase_i}, where {@code base_0 = base}, {@code phase_0 = phase}, and each step adds
     * {@code step} to the base and {@code remainder} to the phase, carrying one into the base
     * when the phase reaches {@code up}.
     *
     * @param x         the signal
     * @param base      the signal index of the first tap of the first output
     * @param phase     the phase of the first output, in {@code [0, up)}
     * @param step      the whole part of the input advance per output
     * @param remainder the fractional part of the input advance per output, in units of
     *                  {@code 1 / up}
     * @param up        the number of phases
     * @param table     the coefficients, {@code taps} per phase
     * @param taps      the number of taps; a positive multiple of {@value #CHAINS}
     * @param out       receives the outputs
     * @param offset    the index in {@code out} of the first output
     * @param count     the number of outputs
     */
    void run(float[] x, int base, int phase, int step, int remainder, int up, float[] table, int taps,
             float[] out, int offset, int count);

    /**
     * Computes {@code count} consecutive outputs of two channels from an exact phase table,
     * exactly as two calls of
     * {@link #run(float[], int, int, int, int, int, float[], int, float[], int, int)} would.
     *
     * @param x0        the first channel's signal
     * @param x1        the second channel's signal, indexed like {@code x0}
     * @param base      the signal index of the first tap of the first output
     * @param phase     the phase of the first output, in {@code [0, up)}
     * @param step      the whole part of the input advance per output
     * @param remainder the fractional part of the input advance per output, in units of
     *                  {@code 1 / up}
     * @param up        the number of phases
     * @param table     the coefficients, {@code taps} per phase
     * @param taps      the number of taps; a positive multiple of {@value #CHAINS}
     * @param out0      receives the first channel's outputs
     * @param out1      receives the second channel's outputs
     * @param offset    the index in both outputs of the first output
     * @param count     the number of outputs per channel
     */
    default void runPair(float[] x0, float[] x1, int base, int phase, int step, int remainder, int up, float[] table,
                         int taps, float[] out0, float[] out1, int offset, int count) {
        run(x0, base, phase, step, remainder, up, table, taps, out0, offset, count);
        run(x1, base, phase, step, remainder, up, table, taps, out1, offset, count);
    }

    /**
     * Computes {@code count} consecutive outputs from an interpolated phase table, stepping like
     * {@link #run(float[], int, int, int, int, int, float[], int, float[], int, int)}.
     *
     * @param x         the signal
     * @param base      the signal index of the first tap of the first output
     * @param phase     the phase of the first output, in {@code [0, up)}
     * @param step      the whole part of the input advance per output
     * @param remainder the fractional part of the input advance per output, in units of
     *                  {@code 1 / up}
     * @param up        the number of exact phases
     * @param phases    the number of interpolated phases in the tables
     * @param table     the coefficients, {@code taps} per interpolated phase
     * @param slopes    the difference from each interpolated phase to the next, laid out like
     *                  {@code table}
     * @param taps      the number of taps; a positive multiple of {@value #CHAINS}
     * @param out       receives the outputs
     * @param offset    the index in {@code out} of the first output
     * @param count     the number of outputs
     */
    default void runInterpolated(float[] x, int base, int phase, int step, int remainder, int up, int phases,
                                 float[] table, float[] slopes, int taps, float[] out, int offset, int count) {
        for (var i = 0; i < count; i++) {
            var scaled = (long) phase * phases;
            var row = (int) (scaled / up) * taps;
            var fraction = (float) ((double) (scaled % up) / up);
            out[offset + i] = dot(x, base, table, row, taps) + fraction * dot(x, base, slopes, row, taps);
            base += step;
            phase += remainder;
            if (phase >= up) {
                phase -= up;
                base++;
            }
        }
    }

    /**
     * Computes {@code count} consecutive outputs from an interpolated phase table at positions
     * kept in fixed point with 32 fractional bits.
     *
     * <p>Output {@code i} is evaluated at signal index {@code base_i} with fractional position
     * {@code fraction_i}, in units of {@code 2^-32}: it selects row
     * {@code slot = (fraction * phases) >> 32} and the weight {@code ((fraction * phases) mod 2^32) / 2^32},
     * combined as in interpolated mode. Each step adds {@code stepFraction} to the fraction, carrying
     * into the base, and {@code stepWhole} to the base.
     *
     * @param x            the signal
     * @param base         the signal index of the first tap of the first output
     * @param fraction     the fractional position of the first output, in {@code [0, 2^32)}
     * @param stepWhole    the whole part of the input advance per output
     * @param stepFraction the fractional part of the input advance per output, in {@code [0, 2^32)}
     * @param phases       the number of interpolated phases in the tables, at most 2^20
     * @param table        the coefficients, {@code taps} per interpolated phase
     * @param slopes       the difference from each interpolated phase to the next, laid out like
     *                     {@code table}
     * @param taps         the number of taps; a positive multiple of {@value #CHAINS}
     * @param out          receives the outputs
     * @param offset       the index in {@code out} of the first output
     * @param count        the number of outputs
     */
    default void runVariable(float[] x, int base, long fraction, int stepWhole, long stepFraction, int phases,
                             float[] table, float[] slopes, int taps, float[] out, int offset, int count) {
        for (var i = 0; i < count; i++) {
            var scaled = fraction * phases;
            var row = (int) (scaled >>> 32) * taps;
            var weight = (float) ((scaled & 0xFFFF_FFFFL) * 0x1p-32);
            out[offset + i] = dot(x, base, table, row, taps) + weight * dot(x, base, slopes, row, taps);
            fraction += stepFraction;
            base += stepWhole + (int) (fraction >>> 32);
            fraction &= 0xFFFF_FFFFL;
        }
    }
}
