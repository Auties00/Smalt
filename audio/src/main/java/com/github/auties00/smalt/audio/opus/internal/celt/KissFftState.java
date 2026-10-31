/* Ports KISS FFT as modified in libopus 1.5.2 (celt/kiss_fft.h). Copyright Mark Borgerding and Xiph.Org Foundation;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Describes one mixed-radix complex FFT configuration ({@code kiss_fft_state}).
 *
 * <p>The standard CELT mode shares one twiddle table among its 480, 240, 120 and 60-point FFTs:
 * the smaller transforms read every {@code 2^shift}-th twiddle. Complex data is stored
 * interleaved as real, imaginary pairs.
 */
public final class KissFftState {
    /**
     * The transform size in complex points.
     */
    public final int nfft;

    /**
     * The scale applied to the input of the forward transform, {@code 1 / nfft}.
     */
    public final float scale;

    /**
     * The subsampling shift into the shared twiddle table, or {@code -1} for the table owner.
     */
    public final int shift;

    /**
     * The radix and stage length pairs, {@code p0, m0, p1, m1, ...}, ending with {@code m = 1}.
     */
    public final short[] factors;

    /**
     * The bit-reversal permutation of the input, indexed by input point.
     */
    public final short[] bitrev;

    /**
     * The shared twiddle table, interleaved real, imaginary.
     */
    public final float[] twiddles;

    /**
     * The number of butterfly stages.
     */
    final int stages;

    /**
     * The twiddle stride of each stage before subsampling, {@code stages + 1} entries.
     */
    final int[] fstride;

    /**
     * The twiddles of each stage laid out for the split-array butterflies: for each twiddle
     * sequence {@code k} of the stage, {@code m} real parts then {@code m} imaginary parts of
     * {@code twiddles[k * j * stride]}; {@code null} for stages without twiddles.
     */
    final float[][] stageTwiddles;

    /**
     * The scalar constants of each stage: {@code exp(-2 pi i / 3)}'s imaginary part for radix 3,
     * {@code ya.r, ya.i, yb.r, yb.i} for radix 5; {@code null} otherwise.
     */
    final float[][] stageConstants;

    /**
     * Returns a configuration that owns its twiddle table.
     *
     * @param nfft the transform size
     * @param scale the forward input scale
     * @param factors the radix factorization
     * @param bitrev the bit-reversal permutation
     * @param twiddles the twiddle table, interleaved real, imaginary
     * @return the configuration
     */
    public static KissFftState of(int nfft, float scale, short[] factors, short[] bitrev, float[] twiddles) {
        return new KissFftState(nfft, scale, -1, factors, bitrev, twiddles);
    }

    /**
     * Creates a configuration.
     *
     * @param nfft     the transform size
     * @param scale    the forward input scale
     * @param shift    the twiddle subsampling shift
     * @param factors  the radix factorization
     * @param bitrev   the bit-reversal permutation
     * @param twiddles the twiddle table
     */
    KissFftState(int nfft, float scale, int shift, short[] factors, short[] bitrev, float[] twiddles) {
        this.nfft = nfft;
        this.scale = scale;
        this.shift = shift;
        this.factors = factors;
        this.bitrev = bitrev;
        this.twiddles = twiddles;
        var strides = new int[9];
        strides[0] = 1;
        var L = 0;
        int m;
        do {
            var p = factors[2 * L];
            m = factors[2 * L + 1];
            strides[L + 1] = strides[L] * p;
            L++;
        } while (m != 1);
        this.stages = L;
        this.fstride = strides;
        var tws = new float[L][];
        var consts = new float[L][];
        var sh = shift > 0 ? shift : 0;
        var mm = (int) factors[2 * L - 1];
        for (var i = L - 1; i >= 0; i--) {
            var p = factors[2 * i];
            var stride = strides[i] << sh;
            if (mm > 1 && (p == 3 || p == 4 || p == 5)) {
                var t = new float[2 * (p - 1) * mm];
                for (var k = 1; k < p; k++) {
                    for (var j = 0; j < mm; j++) {
                        var idx = k * j * stride;
                        t[2 * (k - 1) * mm + j] = twiddles[2 * idx];
                        t[2 * (k - 1) * mm + mm + j] = twiddles[2 * idx + 1];
                    }
                }
                tws[i] = t;
            }
            if (p == 3) {
                consts[i] = new float[]{twiddles[2 * (stride * mm) + 1]};
            } else if (p == 5) {
                consts[i] = new float[]{twiddles[2 * (stride * mm)], twiddles[2 * (stride * mm) + 1],
                        twiddles[2 * (stride * 2 * mm)], twiddles[2 * (stride * 2 * mm) + 1]};
            }
            mm = i != 0 ? factors[2 * i - 1] : 1;
        }
        this.stageTwiddles = tws;
        this.stageConstants = consts;
    }
}
