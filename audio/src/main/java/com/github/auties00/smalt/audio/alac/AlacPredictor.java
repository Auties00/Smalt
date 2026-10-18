/* Ports Apple Lossless Audio Codec (codec/dp_dec.c). Copyright (c) 2011 Apple Inc.; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.alac;

/**
 * Runs ALAC's adaptive linear predictor over a decoded residual.
 *
 * <p>The predictor estimates each sample from the differences between the sample {@code order + 1}
 * places back and the {@code order} samples before it, and adapts its coefficients after every
 * sample by sign-sign steps until the residual's sign is used up. Order zero copies the residual,
 * and order 31 is a fixed first-order integrator.
 *
 * @implNote This implementation follows Apple's {@code dp_dec.c} ({@code unpc_block}, Apache-2.0):
 * the coefficients are 16-bit and wrap when they adapt, all arithmetic wraps at 32 bits, and each
 * output sample is sign-extended from the channel's prediction size.
 */
final class AlacPredictor {
    /**
     * Prevents instantiation.
     */
    private AlacPredictor() {
        throw new AssertionError();
    }

    /**
     * Restores samples from a residual.
     *
     * @param residual the residual; may be the same array as {@code out}
     * @param out receives the samples
     * @param count the number of samples
     * @param coefficients the coefficients, adapted in place; unused for orders 0 and 31
     * @param order the predictor order, from 0 to 31
     * @param chanBits the prediction size in bits, from 1 to 32
     * @param denShift the coefficient quantization shift, from 0 to 15
     */
    static void restore(int[] residual, int[] out, int count, short[] coefficients, int order, int chanBits, int denShift) {
        if (count <= 0) {
            return;
        }
        var chanShift = 32 - chanBits;
        out[0] = residual[0];
        if (order == 0) {
            if (residual != out) {
                System.arraycopy(residual, 1, out, 1, count - 1);
            }
            return;
        }
        if (order == 31) {
            var previous = out[0];
            for (var j = 1; j < count; j++) {
                var value = residual[j] + previous;
                previous = (value << chanShift) >> chanShift;
                out[j] = previous;
            }
            return;
        }
        var warmUp = Math.min(order, count - 1);
        for (var j = 1; j <= warmUp; j++) {
            var value = residual[j] + out[j - 1];
            out[j] = (value << chanShift) >> chanShift;
        }
        // denShift 0 makes Apple's 1 << (denShift - 1) wrap to 1 << 31 on its target compilers
        var denHalf = 1 << (denShift - 1);
        var limit = order + 1;
        if (order == 4) {
            restore4(residual, out, count, coefficients, chanShift, denShift, denHalf);
            return;
        }
        var a = new int[order];
        for (var k = 0; k < order; k++) {
            a[k] = coefficients[k];
        }
        var b = new int[order];
        for (var j = limit; j < count; j++) {
            var top = out[j - limit];
            var sum = 0;
            for (var k = 0; k < order; k++) {
                var difference = top - out[j - 1 - k];
                b[k] = difference;
                sum -= a[k] * difference;
            }
            var del = residual[j];
            var del0 = del;
            var sign = Integer.signum(del);
            del += top + ((sum + denHalf) >> denShift);
            out[j] = (del << chanShift) >> chanShift;
            if (sign > 0) {
                for (var k = order - 1; k >= 0; k--) {
                    var dd = b[k];
                    var sgn = Integer.signum(dd);
                    a[k] = (short) (a[k] - sgn);
                    del0 -= (order - k) * ((sgn * dd) >> denShift);
                    if (del0 <= 0) {
                        break;
                    }
                }
            } else if (sign < 0) {
                for (var k = order - 1; k >= 0; k--) {
                    var dd = b[k];
                    var sgn = Integer.signum(dd);
                    a[k] = (short) (a[k] + sgn);
                    del0 -= (order - k) * ((-sgn * dd) >> denShift);
                    if (del0 >= 0) {
                        break;
                    }
                }
            }
        }
        for (var k = 0; k < order; k++) {
            coefficients[k] = (short) a[k];
        }
    }

    /**
     * Restores samples with a fourth-order predictor, as {@code unpc_block}'s unrolled case.
     *
     * @param residual the decoded prediction residual
     * @param out the samples, warm-up included
     * @param count the number of samples
     * @param coefficients the coefficients, adapted in place
     * @param chanShift 32 minus the prediction size
     * @param denShift the quantization shift
     * @param denHalf the rounding offset
     */
    private static void restore4(int[] residual, int[] out, int count, short[] coefficients, int chanShift, int denShift,
                                 int denHalf) {
        short a0 = coefficients[0], a1 = coefficients[1], a2 = coefficients[2], a3 = coefficients[3];
        for (var j = 5; j < count; j++) {
            var top = out[j - 5];
            var b0 = top - out[j - 1];
            var b1 = top - out[j - 2];
            var b2 = top - out[j - 3];
            var b3 = top - out[j - 4];
            var sum = (denHalf - a0 * b0 - a1 * b1 - a2 * b2 - a3 * b3) >> denShift;
            var del = residual[j];
            var del0 = del;
            var sign = Integer.signum(del);
            del += top + sum;
            out[j] = (del << chanShift) >> chanShift;
            if (sign > 0) {
                var sgn = Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= (sgn * b3) >> denShift;
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 2 * ((sgn * b2) >> denShift);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 3 * ((sgn * b1) >> denShift);
                if (del0 <= 0) {
                    continue;
                }
                a0 = (short) (a0 - Integer.signum(b0));
            } else if (sign < 0) {
                var sgn = -Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= (sgn * b3) >> denShift;
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 2 * ((sgn * b2) >> denShift);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 3 * ((sgn * b1) >> denShift);
                if (del0 >= 0) {
                    continue;
                }
                a0 = (short) (a0 + Integer.signum(b0));
            }
        }
        coefficients[0] = a0;
        coefficients[1] = a1;
        coefficients[2] = a2;
        coefficients[3] = a3;
    }
}
