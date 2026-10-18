package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds one quantization table in the forms the encoder needs: the table itself and the
 * reciprocal multipliers that divide the scaled forward DCT output by it.
 *
 * <p>The accurate integer forward DCT leaves its outputs scaled by 8, so coefficient {@code i} is
 * divided by {@code d = 8 * table[i]} with rounding to nearest and ties away from zero:
 * {@code sign(x) * ((|x| + d / 2) / d)}. Division is replaced by a multiplication and a shift that
 * give the same quotient for every forward DCT output.
 *
 * @implNote This implementation follows libjpeg-turbo's {@code compute_reciprocal} and
 * {@code quantize} (jcdctmgr.c): {@code q = ((|x| + correction) * reciprocal) >>> shift}.
 */
public final class QuantDivisors {
    /**
     * The quantizer values in natural order.
     */
    final int[] table;

    /**
     * The reciprocal multiplier of each coefficient, natural order.
     */
    final int[] reciprocal;

    /**
     * The rounding correction added to each magnitude, natural order.
     */
    final int[] correction;

    /**
     * The right shift applied to each product, natural order.
     */
    final int[] shift;

    /**
     * Derives the divisors of a quantization table.
     *
     * @param table the 64 quantizer values in natural order, each from 1 to 255
     */
    public QuantDivisors(int[] table) {
        this.table = table.clone();
        this.reciprocal = new int[64];
        this.correction = new int[64];
        this.shift = new int[64];
        for (var i = 0; i < 64; i++) {
            var divisor = table[i] << 3;
            var b = 31 - Integer.numberOfLeadingZeros(divisor);
            var r = 16 + b;
            var fq = (int) ((1L << r) / divisor);
            var fr = (int) ((1L << r) % divisor);
            var c = divisor / 2;
            if (fr == 0) {
                fq >>= 1;
                r--;
            } else if (fr <= divisor / 2) {
                c++;
            } else {
                fq++;
            }
            reciprocal[i] = fq;
            correction[i] = c;
            shift[i] = r;
        }
    }

    /**
     * Quantizes one scaled forward DCT output.
     *
     * @param value the forward DCT output
     * @param index the natural-order coefficient index
     * @return the quantized coefficient
     */
    int quantize(int value, int index) {
        var magnitude = value < 0 ? -value : value;
        var quotient = ((magnitude + correction[index]) * reciprocal[index]) >>> shift[index];
        return value < 0 ? -quotient : quotient;
    }

    /**
     * Returns a copy of the quantizer values.
     *
     * @return the 64 values in natural order
     */
    public int[] table() {
        return table.clone();
    }
}
