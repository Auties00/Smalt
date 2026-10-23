package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Quantizes the mantissas of an exponent set that uses the adaptive hybrid transform of E-AC-3
 * (ETSI TS 102 366 clause E.2.4): the six mantissas of each bin over the blocks of a frame are
 * transformed with the DCT the decoder inverts, then coded with the vector quantizer or the
 * gain-adaptive quantizer their high-efficiency bit allocation pointer selects.
 *
 * <p>Every reconstruction this class reports is computed with the decoder's own tables and
 * arithmetic ({@link Aht#vector(int, int, float[])}, {@link Aht#remap(int, int, float)},
 * {@link Aht#inverse(float[], float[])}), so the quantization error it measures is the decoder's.
 * The vector quantizer picks the vector nearest to the transformed mantissas in Euclidean distance,
 * as clause E.2.4.4.1 prescribes. The gain-adaptive quantizer codes a mantissa as a small codeword
 * when its rounded value fits the small quantizer of the gain and as the tag followed by a large
 * codeword otherwise; the gain of each bin and the mode of the set minimize the bits.
 * An instance holds scratch arrays and is not thread-safe.
 */
final class AhtEncoder {
    /**
     * The forward DCT matrix, the transpose of the decoder's inverse over 6: {@code DCT[j * 6 + m]
     * = sqrt(2) R_j cos(j (2m + 1) pi / 12) / 6}, so that the decoder's inverse rebuilds the six
     * mantissas exactly.
     */
    private static final float[] DCT = dct();

    /**
     * The decoded vector of the index being tested, scratch.
     */
    private final float[] vector = new float[6];

    /**
     * Creates an encoder.
     */
    AhtEncoder() {
    }

    /**
     * Computes the forward DCT matrix.
     *
     * @return the 36 coefficients, by transform index then block
     */
    private static float[] dct() {
        var matrix = new float[36];
        for (var j = 0; j < 6; j++) {
            for (var m = 0; m < 6; m++) {
                var weight = j == 0 ? Math.sqrt(0.5) : 1.0;
                matrix[j * 6 + m] = (float) (Math.sqrt(2) * weight * StrictMath.cos(j * (2 * m + 1) * Math.PI / 12) / 6);
            }
        }
        return matrix;
    }

    /**
     * Transforms the six block mantissas of one bin into the hybrid transform domain, clipped to
     * the range the quantizers cover.
     *
     * @param in  the six mantissas {@code C(k, m)}, normalized by the bin's exponent
     * @param out receives the six transform-domain mantissas {@code X(k, j)}
     */
    static void forward(float[] in, float[] out) {
        for (var j = 0; j < 6; j++) {
            var sum = 0.0f;
            for (var m = 0; m < 6; m++) {
                sum += DCT[j * 6 + m] * in[m];
            }
            out[j] = Math.max(-0.99999f, Math.min(0.99999f, sum));
        }
    }

    /**
     * Returns the index of the vector of a vector quantizer nearest to six mantissas.
     *
     * @param hebap the high-efficiency bit allocation pointer, from 1 to 7
     * @param x     the six transform-domain mantissas
     * @return the index, below {@code 2^VQ_BITS[hebap]}
     */
    int nearest(int hebap, float[] x) {
        var count = 1 << Ac3Tables.VQ_BITS[hebap];
        var best = 0;
        var bestDistance = Float.MAX_VALUE;
        for (var index = 0; index < count; index++) {
            Aht.vector(hebap, index, vector);
            var distance = 0.0f;
            for (var n = 0; n < 6; n++) {
                var d = x[n] - vector[n];
                distance += d * d;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    /**
     * Returns the number of bits six mantissas of a bin cost with the gain-adaptive quantizer of a
     * pointer and gain.
     *
     * @param hebap the high-efficiency bit allocation pointer, from 8 to 19
     * @param gain  the gain index: 0 for gain 1, 1 for gain 2, 2 for gain 4
     * @param x     the six transform-domain mantissas
     * @return the bits, gain word excluded
     */
    static int bits(int hebap, int gain, float[] x) {
        var m = Ac3Tables.GAQ_BITS[hebap - 8];
        if (gain == 0) {
            return 6 * m;
        }
        var small = m - gain;
        var total = 6 * small;
        for (var n = 0; n < 6; n++) {
            if (!fitsSmall(m, gain, x[n])) {
                total += gain == 1 ? m - 1 : m;
            }
        }
        return total;
    }

    /**
     * Returns whether a mantissa codes as a small codeword under a gain: its value rounded on the
     * grid of the gain-1 quantizer, {@code 2^-(m-1)}, lies within the small quantizer's levels.
     *
     * @param m    the mantissa bits of the pointer
     * @param gain the gain index, 1 or 2
     * @param x    the mantissa
     * @return whether the small quantizer codes it
     */
    private static boolean fitsSmall(int m, int gain, float x) {
        var q = Math.round(x * (1 << (m - 1)));
        var limit = (1 << (m - 1 - gain)) - 1;
        return q >= -limit && q <= limit;
    }

    /**
     * Quantizes one mantissa with the gain-adaptive quantizer of a pointer and gain and returns the
     * codewords and the value the decoder reconstructs.
     *
     * @param hebap the high-efficiency bit allocation pointer, from 8 to 19
     * @param gain  the gain index: 0 for gain 1, 1 for gain 2, 2 for gain 4
     * @param x     the mantissa
     * @param codes receives the codeword at index 0 and, after a tag, the large codeword at index
     *              1, each masked to its width
     * @param widths receives the width of each codeword, {@code 0} at index 1 for a single one
     * @return the reconstructed mantissa
     */
    static float quantize(int hebap, int gain, float x, int[] codes, int[] widths) {
        var m = Ac3Tables.GAQ_BITS[hebap - 8];
        if (gain == 0) {
            var limit = 1 << (m - 1);
            var row = Ac3Tables.GAQ_REMAP[hebap - 8];
            var a = (short) row[0] * 0x1p-15f;
            var q = Math.round(x / (1 + a) * limit);
            q = Math.max(-limit, Math.min(limit - 1, q));
            var best = q;
            var bestError = Float.MAX_VALUE;
            for (var candidate = Math.max(-limit, q - 1); candidate <= Math.min(limit - 1, q + 1); candidate++) {
                var error = Math.abs(Aht.remap(hebap, 0, candidate / (float) limit) - x);
                if (error < bestError) {
                    bestError = error;
                    best = candidate;
                }
            }
            codes[0] = best & ((1 << m) - 1);
            widths[0] = m;
            widths[1] = 0;
            return Aht.remap(hebap, 0, best / (float) limit);
        }
        var small = m - gain;
        if (fitsSmall(m, gain, x)) {
            var q = Math.round(x * (1 << (m - 1)));
            codes[0] = q & ((1 << small) - 1);
            widths[0] = small;
            widths[1] = 0;
            return Math.scalb((float) q, -(m - 1));
        }
        codes[0] = (-(1 << (small - 1))) & ((1 << small) - 1);
        widths[0] = small;
        var large = gain == 1 ? m - 1 : m;
        var limit = 1 << (large - 1);
        var row = Ac3Tables.GAQ_REMAP[hebap - 8];
        var a = (short) row[gain] * 0x1p-15f;
        var offset = (short) row[x >= 0 ? 2 + gain : 4 + gain] * 0x1p-15f;
        var q = Math.round((x - offset) / (1 + a) * limit);
        q = x >= 0 ? Math.max(0, Math.min(limit - 1, q)) : Math.max(-limit, Math.min(-1, q));
        var best = q;
        var bestError = Float.MAX_VALUE;
        var low = x >= 0 ? 0 : -limit;
        var high = x >= 0 ? limit - 1 : -1;
        for (var candidate = Math.max(low, q - 1); candidate <= Math.min(high, q + 1); candidate++) {
            var error = Math.abs(Aht.remap(hebap, gain, candidate / (float) limit) - x);
            if (error < bestError) {
                bestError = error;
                best = candidate;
            }
        }
        codes[1] = best & ((1 << large) - 1);
        widths[1] = large;
        return Aht.remap(hebap, gain, best / (float) limit);
    }
}
