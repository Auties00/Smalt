package com.github.auties00.smalt.video.h264.encoder.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link InterpolationKernels} with the Vector API; the results equal the scalar kernels exactly.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd}
 * and runs the six-tap filters sixteen samples at a time in 16-bit lanes, the second pass of the center position
 * included: with {@code A}, {@code B} and {@code C} the sums of the outer, inner and central pairs of vertical
 * intermediates, {@code (A - 5B + 20C + 512) >> 10} equals {@code (v + C + 32) >> 6} for
 * {@code v = (((A - B) >> 2) + C - B) >> 2}. The one intermediate that can leave the 16-bit range,
 * {@code ((A - B) >> 2) + C - B}, is added with saturation, which only changes results that clip to 0 or 255 either
 * way.
 */
final class VectorInterpolationKernels extends InterpolationKernels {
    /**
     * Sixteen bytes.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * Sixteen shorts.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The exact class of 16-lane byte vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTES_16 = (Class<ByteVector>) B128.vectorType();

    /**
     * The exact class of 16-lane short vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORTS_16 = (Class<ShortVector>) S256.vectorType();

    /**
     * Sixteen shorts equal to five, the second tap of the six-tap filter.
     */
    private static final ShortVector FIVE = ShortVector.broadcast(ShortVector.SPECIES_256, (short) 5);

    /**
     * Sixteen shorts equal to twenty, the third tap of the six-tap filter.
     */
    private static final ShortVector TWENTY = ShortVector.broadcast(ShortVector.SPECIES_256, (short) 20);

    /**
     * Sixteen shorts equal to the rounding offset of a single filter pass.
     */
    private static final ShortVector SIXTEEN = ShortVector.broadcast(ShortVector.SPECIES_256, (short) 16);

    /**
     * Sixteen shorts equal to the rounding offset of the center sample.
     */
    private static final ShortVector THIRTY_TWO = ShortVector.broadcast(ShortVector.SPECIES_256, (short) 32);

    /**
     * Sixteen zero shorts.
     */
    private static final ShortVector ZERO = ShortVector.zero(ShortVector.SPECIES_256);

    /**
     * Sixteen shorts equal to the largest sample value.
     */
    private static final ShortVector MAX_SAMPLE = ShortVector.broadcast(ShortVector.SPECIES_256, (short) 255);

    /**
     * Constructs the vector kernels.
     */
    VectorInterpolationKernels() {
    }

    @Override
    void halfPelRow(byte[] full, int start, int count, int stride, byte[] h, byte[] v, byte[] c, short[] temp) {
        filterColumns(full, start, count, stride, temp);
        filterRows(full, start, count, h, v, temp);
        filterCenter(start, count, c, temp);
    }

    /**
     * Applies the vertical six-tap filter to the full-sample columns of one row, from two samples
     * left of the row to three samples right of its end, keeping the unrounded sums.
     *
     * @param full the full-sample plane
     * @param start the index of the row's first sample
     * @param count the number of samples in the row
     * @param stride the plane's row stride
     * @param temp receives the sum of column {@code x} at {@code temp[x + 2]}
     */
    private static void filterColumns(byte[] full, int start, int count, int stride, short[] temp) {
        var x = -2;
        for (; x + 16 <= count + 3; x += 16) {
            var i = start + x;
            var r0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i - 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i - stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + 2 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var r5 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + 3 * stride)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            r0.lanewise(VectorOperators.ADD, r5)
                    .lanewise(VectorOperators.SUB, r1.lanewise(VectorOperators.ADD, r4).lanewise(VectorOperators.MUL, FIVE))
                    .lanewise(VectorOperators.ADD, r2.lanewise(VectorOperators.ADD, r3).lanewise(VectorOperators.MUL, TWENTY))
                    .intoArray(temp, x + 2);
        }
        for (; x < count + 3; x++) {
            var i = start + x;
            temp[x + 2] = (short) ((full[i - 2 * stride] & 0xFF) - 5 * (full[i - stride] & 0xFF) + 20 * (full[i] & 0xFF)
                    + 20 * (full[i + stride] & 0xFF) - 5 * (full[i + 2 * stride] & 0xFF)
                    + (full[i + 3 * stride] & 0xFF));
        }
    }

    /**
     * Writes the horizontal half-sample row, filtered from the full samples, and the vertical one,
     * rounded from the column sums.
     *
     * @param full the full-sample plane
     * @param start the index of the row's first sample
     * @param count the number of samples in the row
     * @param h receives the horizontal half samples
     * @param v receives the vertical half samples
     * @param temp the column sums of {@link #filterColumns}
     */
    private static void filterRows(byte[] full, int start, int count, byte[] h, byte[] v, short[] temp) {
        var x = 0;
        for (; x + 16 <= count; x += 16) {
            var i = start + x;
            var f0 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i - 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f1 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i - 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f2 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f3 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + 1)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f4 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + 2)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var f5 = SHORTS_16.cast(BYTES_16.cast(ByteVector.fromArray(B128, full, i + 3)).convertShape(VectorOperators.ZERO_EXTEND_B2S, S256, 0));
            var horizontal = f0.lanewise(VectorOperators.ADD, f5)
                    .lanewise(VectorOperators.SUB, f1.lanewise(VectorOperators.ADD, f4).lanewise(VectorOperators.MUL, FIVE))
                    .lanewise(VectorOperators.ADD, f2.lanewise(VectorOperators.ADD, f3).lanewise(VectorOperators.MUL, TWENTY))
                    .lanewise(VectorOperators.ADD, SIXTEEN).lanewise(VectorOperators.ASHR, 5)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            BYTES_16.cast(horizontal.convertShape(VectorOperators.S2B, B128, 0)).intoArray(h, i);
            var vertical = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 2))
                    .lanewise(VectorOperators.ADD, SIXTEEN).lanewise(VectorOperators.ASHR, 5)
                    .lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            BYTES_16.cast(vertical.convertShape(VectorOperators.S2B, B128, 0)).intoArray(v, i);
        }
        for (; x < count; x++) {
            var i = start + x;
            var b = (full[i - 2] & 0xFF) - 5 * (full[i - 1] & 0xFF) + 20 * (full[i] & 0xFF)
                    + 20 * (full[i + 1] & 0xFF) - 5 * (full[i + 2] & 0xFF) + (full[i + 3] & 0xFF);
            h[i] = clip((b + 16) >> 5);
            v[i] = clip((temp[x + 2] + 16) >> 5);
        }
    }

    /**
     * Writes the center half-sample row, filtered horizontally from the column sums.
     *
     * @param start the index of the row's first sample
     * @param count the number of samples in the row
     * @param c receives the center half samples
     * @param temp the column sums of {@link #filterColumns}
     */
    private static void filterCenter(int start, int count, byte[] c, short[] temp) {
        var x = 0;
        for (; x + 16 <= count; x += 16) {
            var t0 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x));
            var t1 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 1));
            var t2 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 2));
            var t3 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 3));
            var t4 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 4));
            var t5 = SHORTS_16.cast(ShortVector.fromArray(S256, temp, x + 5));
            var outer = t0.lanewise(VectorOperators.ADD, t5);
            var inner = t1.lanewise(VectorOperators.ADD, t4);
            var center = t2.lanewise(VectorOperators.ADD, t3);
            var quarter = outer.lanewise(VectorOperators.SUB, inner).lanewise(VectorOperators.ASHR, 2);
            var sixteenth = quarter.lanewise(VectorOperators.SADD, center.lanewise(VectorOperators.SUB, inner))
                    .lanewise(VectorOperators.ASHR, 2);
            var diagonal = sixteenth.lanewise(VectorOperators.ADD, center).lanewise(VectorOperators.ADD, THIRTY_TWO)
                    .lanewise(VectorOperators.ASHR, 6).lanewise(VectorOperators.MAX, ZERO).lanewise(VectorOperators.MIN, MAX_SAMPLE);
            BYTES_16.cast(diagonal.convertShape(VectorOperators.S2B, B128, 0)).intoArray(c, start + x);
        }
        for (; x < count; x++) {
            var j = temp[x] - 5 * temp[x + 1] + 20 * temp[x + 2] + 20 * temp[x + 3] - 5 * temp[x + 4] + temp[x + 5];
            c[start + x] = clip((j + 512) >> 10);
        }
    }
}
