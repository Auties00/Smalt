package com.github.auties00.smalt.audio.flac;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

/**
 * Implements {@link FlacEncoderKernel} with the Vector API.
 *
 * <p>This class references {@code jdk.incubator.vector} types, so it is loaded only when
 * {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}. The
 * autocorrelation vectorizes across lags and the window across samples, so every floating-point
 * value is computed with the same operations in the same order as in
 * {@link ScalarFlacEncoderKernel}; the integer kernels are exact in any order.
 *
 * @implNote This implementation uses only the per-shape {@code lanewise} operations with vector
 * operands (scalar operands only for shifts), and casts every loaded, broadcast, converted or zero
 * vector through the constant exact class of its species, and every accumulator again wherever it
 * receives a call: a vector carried around a loop reaches the compiler with only its declared
 * abstract type, so without the cast the call would be bound through a type profile, which may be
 * missing or shared by every vector shape of the program, and the vector boxed. The kernels thus
 * compile allocation-free even without tiered compilation or type profiles. The wide residual
 * filter sums in double precision, which is exact for every coefficient the encoder quantizes; the
 * fixed-predictor sums of samples wider than {@value #VECTOR_FIXED_BITS} bits run the scalar code,
 * whose results are identical.
 */
final class VectorFlacEncoderKernel implements FlacEncoderKernel {
    /**
     * The largest sample size whose fixed-predictor sums use the vector loop.
     */
    private static final int VECTOR_FIXED_BITS = 20;

    /**
     * The integer species, the platform's preferred shape.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;

    /**
     * The double species, the platform's preferred shape.
     */
    private static final VectorSpecies<Double> DOUBLES = DoubleVector.SPECIES_PREFERRED;

    /**
     * The long species, the platform's preferred shape.
     */
    private static final VectorSpecies<Long> LONGS = LongVector.SPECIES_PREFERRED;

    /**
     * The integer species with as many lanes as {@link #DOUBLES}, for conversions to double.
     */
    private static final VectorSpecies<Integer> INTS_FOR_DOUBLES = VectorSpecies.of(int.class,
            VectorShape.forBitSize(DOUBLES.length() * Integer.SIZE));

    /**
     * The integer species with as many lanes as {@link #LONGS}, for conversions to long.
     */
    private static final VectorSpecies<Integer> INTS_FOR_LONGS = VectorSpecies.of(int.class,
            VectorShape.forBitSize(LONGS.length() * Integer.SIZE));

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The exact class of the vectors of {@link #DOUBLES}.
     */
    private static final Class<DoubleVector> DOUBLE_VECTOR = vectorClass(DOUBLES);

    /**
     * The exact class of the vectors of {@link #LONGS}.
     */
    private static final Class<LongVector> LONG_VECTOR = vectorClass(LONGS);

    /**
     * The exact class of the vectors of {@link #INTS_FOR_DOUBLES}.
     */
    private static final Class<IntVector> NARROW_INT_VECTOR = vectorClass(INTS_FOR_DOUBLES);

    /**
     * The exact class of the vectors of {@link #INTS_FOR_LONGS}.
     */
    private static final Class<IntVector> HALF_INT_VECTOR = vectorClass(INTS_FOR_LONGS);

    /**
     * The number of lanes of {@link #INTS}.
     */
    private static final int INT_LANES = INTS.length();

    /**
     * The number of lanes of {@link #DOUBLES}.
     */
    private static final int DOUBLE_LANES = DOUBLES.length();

    /**
     * The number of lanes of {@link #LONGS}.
     */
    private static final int LONG_LANES = LONGS.length();

    /**
     * The largest coefficient magnitude for which {@link #lpcResidualWide} sums in double
     * precision: with samples below {@code 2^31} and at most 32 taps, every partial sum stays below
     * {@code 2^50} and is exact.
     */
    private static final int MAX_EXACT_COEFFICIENT = 1 << 14;

    /**
     * Zero in every double lane.
     */
    private static final DoubleVector ZERO = DoubleVector.zero(DOUBLES);

    /**
     * One in every double lane.
     */
    private static final DoubleVector ONE = DoubleVector.broadcast(DOUBLES, 1.0);

    /**
     * {@code -2^31} in every double lane.
     */
    private static final DoubleVector LOW_LIMIT = DoubleVector.broadcast(DOUBLES, -0x1p31);

    /**
     * {@code 2^31} in every double lane.
     */
    private static final DoubleVector HIGH_LIMIT = DoubleVector.broadcast(DOUBLES, 0x1p31);

    /**
     * The scalar kernel serving the paths this class does not vectorize.
     */
    private static final ScalarFlacEncoderKernel SCALAR = new ScalarFlacEncoderKernel();

    /**
     * Constructs the kernel.
     */
    private VectorFlacEncoderKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return a new vector kernel
     */
    static FlacEncoderKernel create() {
        return new VectorFlacEncoderKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector shape
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void window(int[] signal, int signalOffset, double[] window, int windowOffset, double[] out, int outOffset, int count) {
        var i = 0;
        for (; i <= count - DOUBLE_LANES; i += DOUBLE_LANES) {
            var samples = DOUBLE_VECTOR.cast(NARROW_INT_VECTOR.cast(IntVector.fromArray(INTS_FOR_DOUBLES, signal, signalOffset + i))
                    .convertShape(VectorOperators.I2D, DOUBLES, 0));
            var coefficients = DOUBLE_VECTOR.cast(DoubleVector.fromArray(DOUBLES, window, windowOffset + i));
            samples.lanewise(VectorOperators.MUL, coefficients).intoArray(out, outOffset + i);
        }
        for (; i < count; i++) {
            out[outOffset + i] = signal[signalOffset + i] * window[windowOffset + i];
        }
    }

    @Override
    public void autocorrelation(double[] data, int length, int lags, double[] autoc) {
        for (var k = 0; k < lags; k += 4 * DOUBLE_LANES) {
            var acc0 = DOUBLE_VECTOR.cast(DoubleVector.zero(DOUBLES));
            var acc1 = DOUBLE_VECTOR.cast(DoubleVector.zero(DOUBLES));
            var acc2 = DOUBLE_VECTOR.cast(DoubleVector.zero(DOUBLES));
            var acc3 = DOUBLE_VECTOR.cast(DoubleVector.zero(DOUBLES));
            for (var s = 0; s < length; s++) {
                var d = DOUBLE_VECTOR.cast(DoubleVector.broadcast(DOUBLES, data[s]));
                var x0 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(DOUBLES, data, s + k));
                var x1 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(DOUBLES, data, s + k + DOUBLE_LANES));
                var x2 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(DOUBLES, data, s + k + 2 * DOUBLE_LANES));
                var x3 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(DOUBLES, data, s + k + 3 * DOUBLE_LANES));
                acc0 = DOUBLE_VECTOR.cast(acc0).lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, x0));
                acc1 = DOUBLE_VECTOR.cast(acc1).lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, x1));
                acc2 = DOUBLE_VECTOR.cast(acc2).lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, x2));
                acc3 = DOUBLE_VECTOR.cast(acc3).lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, x3));
            }
            DOUBLE_VECTOR.cast(acc0).intoArray(autoc, k);
            DOUBLE_VECTOR.cast(acc1).intoArray(autoc, k + DOUBLE_LANES);
            DOUBLE_VECTOR.cast(acc2).intoArray(autoc, k + 2 * DOUBLE_LANES);
            DOUBLE_VECTOR.cast(acc3).intoArray(autoc, k + 3 * DOUBLE_LANES);
        }
    }

    @Override
    public void lpcResidual(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual) {
        var i = order;
        for (; i <= length - 2 * INT_LANES; i += 2 * INT_LANES) {
            var sum0 = INT_VECTOR.cast(IntVector.zero(INTS));
            var sum1 = INT_VECTOR.cast(IntVector.zero(INTS));
            for (var j = 0; j < order; j++) {
                var c = INT_VECTOR.cast(IntVector.broadcast(INTS, coefficients[j]));
                var x0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 1 - j));
                var x1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i + INT_LANES - 1 - j));
                sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, x0));
                sum1 = INT_VECTOR.cast(sum1).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, x1));
            }
            var y0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i));
            var y1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i + INT_LANES));
            y0.lanewise(VectorOperators.SUB, INT_VECTOR.cast(sum0).lanewise(VectorOperators.ASHR, shift)).intoArray(residual, i);
            y1.lanewise(VectorOperators.SUB, INT_VECTOR.cast(sum1).lanewise(VectorOperators.ASHR, shift))
                    .intoArray(residual, i + INT_LANES);
        }
        for (; i < length; i++) {
            var sum = 0;
            for (var j = 0; j < order; j++) {
                sum += coefficients[j] * signal[i - 1 - j];
            }
            residual[i] = signal[i] - (sum >> shift);
        }
    }

    @Override
    public boolean lpcResidualWide(int[] signal, int length, int[] coefficients, int order, int shift, int[] residual) {
        // Two-lane double compares and masks compile to vector code on some platforms only (not on x86 limited to
        // 128-bit vectors), so this kernel needs four double lanes.
        if (DOUBLE_LANES < 4) {
            return SCALAR.lpcResidualWide(signal, length, coefficients, order, shift, residual);
        }
        for (var j = 0; j < order; j++) {
            if (Math.abs(coefficients[j]) > MAX_EXACT_COEFFICIENT) {
                return SCALAR.lpcResidualWide(signal, length, coefficients, order, shift, residual);
            }
        }
        var scale = DOUBLE_VECTOR.cast(DoubleVector.broadcast(DOUBLES, Math.scalb(1.0, -shift)));
        var i = order;
        for (; i <= length - DOUBLE_LANES; i += DOUBLE_LANES) {
            var sum = DOUBLE_VECTOR.cast(DoubleVector.zero(DOUBLES));
            for (var j = 0; j < order; j++) {
                var c = DOUBLE_VECTOR.cast(DoubleVector.broadcast(DOUBLES, coefficients[j]));
                var x = DOUBLE_VECTOR.cast(NARROW_INT_VECTOR.cast(IntVector.fromArray(INTS_FOR_DOUBLES, signal, i - 1 - j))
                        .convertShape(VectorOperators.I2D, DOUBLES, 0));
                sum = DOUBLE_VECTOR.cast(sum).lanewise(VectorOperators.ADD, c.lanewise(VectorOperators.MUL, x));
            }
            // Every product and partial sum is an integer below 2^50, so the sum is exact; scaling
            // by a power of two keeps it exact and flooring it gives the arithmetic shift.
            var prediction = DOUBLE_VECTOR.cast(sum).lanewise(VectorOperators.MUL, scale);
            if (prediction.compare(VectorOperators.LT, LOW_LIMIT).anyTrue()
                    || prediction.compare(VectorOperators.GE, HIGH_LIMIT).anyTrue()) {
                return SCALAR.lpcResidualWide(signal, length, coefficients, order, shift, residual);
            }
            var truncated = DOUBLE_VECTOR.cast(NARROW_INT_VECTOR.cast(prediction.convertShape(VectorOperators.D2I, INTS_FOR_DOUBLES, 0))
                    .convertShape(VectorOperators.I2D, DOUBLES, 0));
            var floor = truncated.lanewise(VectorOperators.SUB, ZERO.blend(ONE, truncated.compare(VectorOperators.GT, prediction)));
            var value = DOUBLE_VECTOR.cast(NARROW_INT_VECTOR.cast(IntVector.fromArray(INTS_FOR_DOUBLES, signal, i))
                    .convertShape(VectorOperators.I2D, DOUBLES, 0)).lanewise(VectorOperators.SUB, floor);
            if (value.compare(VectorOperators.LE, LOW_LIMIT).anyTrue() || value.compare(VectorOperators.GE, HIGH_LIMIT).anyTrue()) {
                return false;
            }
            NARROW_INT_VECTOR.cast(value.convertShape(VectorOperators.D2I, INTS_FOR_DOUBLES, 0)).intoArray(residual, i);
        }
        for (; i < length; i++) {
            var sum = 0L;
            for (var j = 0; j < order; j++) {
                sum += (long) coefficients[j] * signal[i - 1 - j];
            }
            var value = signal[i] - (sum >> shift);
            if (value <= Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return false;
            }
            residual[i] = (int) value;
        }
        return true;
    }

    @Override
    public void fixedResidual(int[] signal, int length, int order, int[] residual) {
        switch (order) {
            case 0 -> System.arraycopy(signal, 0, residual, 0, length);
            case 1 -> fixedResidual1(signal, length, residual);
            case 2 -> fixedResidual2(signal, length, residual);
            default -> fixedResidual34(signal, length, order, residual);
        }
    }

    /**
     * Computes the first-order fixed residual.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param residual receives the residual
     */
    private static void fixedResidual1(int[] signal, int length, int[] residual) {
        var i = 1;
        for (; i <= length - INT_LANES; i += INT_LANES) {
            var x0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i));
            var x1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 1));
            x0.lanewise(VectorOperators.SUB, x1).intoArray(residual, i);
        }
        for (; i < length; i++) {
            residual[i] = signal[i] - signal[i - 1];
        }
    }

    /**
     * Computes the second-order fixed residual.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param residual receives the residual
     */
    private static void fixedResidual2(int[] signal, int length, int[] residual) {
        var i = 2;
        for (; i <= length - INT_LANES; i += INT_LANES) {
            var x0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i));
            var x1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 1));
            var x2 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 2));
            x0.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.SUB, x1.lanewise(VectorOperators.SUB, x2))
                    .intoArray(residual, i);
        }
        for (; i < length; i++) {
            residual[i] = signal[i] - 2 * signal[i - 1] + signal[i - 2];
        }
    }

    /**
     * Computes the third- or fourth-order fixed residual as repeated differences, which wrap to
     * the same 32-bit values as the polynomial form.
     *
     * @param signal the samples
     * @param length the number of samples
     * @param order the predictor order, 3 or 4
     * @param residual receives the residual
     */
    private static void fixedResidual34(int[] signal, int length, int order, int[] residual) {
        var i = order;
        var fourth = order == 4 ? 1 : 0;
        var mask = INT_VECTOR.cast(IntVector.broadcast(INTS, -fourth));
        for (; i <= length - INT_LANES; i += INT_LANES) {
            var x0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i));
            var x1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 1));
            var x2 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 2));
            var x3 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 3));
            var x4 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 3 - fourth));
            var b1 = x1.lanewise(VectorOperators.SUB, x2);
            var b2 = x2.lanewise(VectorOperators.SUB, x3);
            var c2 = b1.lanewise(VectorOperators.SUB, b2);
            var d3 = x0.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.SUB, b1).lanewise(VectorOperators.SUB, c2);
            var c3 = b2.lanewise(VectorOperators.SUB, x3.lanewise(VectorOperators.SUB, x4));
            d3.lanewise(VectorOperators.SUB, c2.lanewise(VectorOperators.SUB, c3).lanewise(VectorOperators.AND, mask))
                    .intoArray(residual, i);
        }
        for (; i < length; i++) {
            residual[i] = order == 3
                    ? signal[i] - 3 * signal[i - 1] + 3 * signal[i - 2] - signal[i - 3]
                    : signal[i] - 4 * signal[i - 1] + 6 * signal[i - 2] - 4 * signal[i - 3] + signal[i - 4];
        }
    }

    @Override
    public void fixedErrors(int[] signal, int length, long[] errors, int bitsPerSample) {
        if (bitsPerSample > VECTOR_FIXED_BITS) {
            SCALAR.fixedErrors(signal, length, errors, bitsPerSample);
            return;
        }
        // Every residual magnitude is below 2^(bits + 3): a block of this many vectors keeps the
        // sum over all lanes below 2^31, so the int reduction is exact.
        var block = Math.max(1, (1 << (28 - bitsPerSample)) / INT_LANES) * INT_LANES;
        Arrays.fill(errors, 0, 5, 0);
        var i = 4;
        while (i <= length - INT_LANES) {
            var count = Math.min(block, (length - i) / INT_LANES * INT_LANES);
            fixedErrorsBlock(signal, i, i + count, errors);
            i += count;
        }
        long t0 = errors[0], t1 = errors[1], t2 = errors[2], t3 = errors[3], t4 = errors[4];
        for (; i < length; i++) {
            var x0 = signal[i];
            var x1 = signal[i - 1];
            var x2 = signal[i - 2];
            var x3 = signal[i - 3];
            var x4 = signal[i - 4];
            t0 += Math.abs(x0);
            t1 += Math.abs(x0 - x1);
            t2 += Math.abs(x0 - 2 * x1 + x2);
            t3 += Math.abs(x0 - 3 * x1 + 3 * x2 - x3);
            t4 += Math.abs(x0 - 4 * x1 + 6 * x2 - 4 * x3 + x4);
        }
        errors[0] = t0;
        errors[1] = t1;
        errors[2] = t2;
        errors[3] = t3;
        errors[4] = t4;
    }

    /**
     * Adds the fixed-predictor error sums of a run of whole vectors to {@code errors}.
     *
     * <p>Every residual magnitude is below {@code 2^(bits + 3)} and the run is short enough that
     * the sum over all its lanes stays below {@code 2^31}, so the int lanes and their reduction are
     * exact.
     *
     * @param signal the samples
     * @param from the index of the first sample, at least four
     * @param to the index after the last sample; {@code to - from} is a multiple of the lane count
     * @param errors the five running sums
     */
    private static void fixedErrorsBlock(int[] signal, int from, int to, long[] errors) {
        var a0 = INT_VECTOR.cast(IntVector.zero(INTS));
        var a1 = INT_VECTOR.cast(IntVector.zero(INTS));
        var a2 = INT_VECTOR.cast(IntVector.zero(INTS));
        var a3 = INT_VECTOR.cast(IntVector.zero(INTS));
        var a4 = INT_VECTOR.cast(IntVector.zero(INTS));
        for (var i = from; i < to; i += INT_LANES) {
            var x0 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i));
            var x1 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 1));
            var x2 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 2));
            var x3 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 3));
            var x4 = INT_VECTOR.cast(IntVector.fromArray(INTS, signal, i - 4));
            var b1 = x1.lanewise(VectorOperators.SUB, x2);
            var b2 = x2.lanewise(VectorOperators.SUB, x3);
            var b3 = x3.lanewise(VectorOperators.SUB, x4);
            var d1 = x0.lanewise(VectorOperators.SUB, x1);
            var d2 = d1.lanewise(VectorOperators.SUB, b1);
            var c2 = b1.lanewise(VectorOperators.SUB, b2);
            var d3 = d2.lanewise(VectorOperators.SUB, c2);
            var d4 = d3.lanewise(VectorOperators.SUB, c2.lanewise(VectorOperators.SUB, b2.lanewise(VectorOperators.SUB, b3)));
            a0 = INT_VECTOR.cast(a0).lanewise(VectorOperators.ADD, x0.lanewise(VectorOperators.ABS));
            a1 = INT_VECTOR.cast(a1).lanewise(VectorOperators.ADD, d1.lanewise(VectorOperators.ABS));
            a2 = INT_VECTOR.cast(a2).lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.ABS));
            a3 = INT_VECTOR.cast(a3).lanewise(VectorOperators.ADD, d3.lanewise(VectorOperators.ABS));
            a4 = INT_VECTOR.cast(a4).lanewise(VectorOperators.ADD, d4.lanewise(VectorOperators.ABS));
        }
        errors[0] += INT_VECTOR.cast(a0).reduceLanesToLong(VectorOperators.ADD);
        errors[1] += INT_VECTOR.cast(a1).reduceLanesToLong(VectorOperators.ADD);
        errors[2] += INT_VECTOR.cast(a2).reduceLanesToLong(VectorOperators.ADD);
        errors[3] += INT_VECTOR.cast(a3).reduceLanesToLong(VectorOperators.ADD);
        errors[4] += INT_VECTOR.cast(a4).reduceLanesToLong(VectorOperators.ADD);
    }

    @Override
    public long absSum(int[] residual, int from, int to) {
        var i = from;
        var sum = 0L;
        if (to - from >= 2 * LONG_LANES) {
            var acc0 = LONG_VECTOR.cast(LongVector.zero(LONGS));
            var acc1 = LONG_VECTOR.cast(LongVector.zero(LONGS));
            for (; i <= to - 2 * LONG_LANES; i += 2 * LONG_LANES) {
                var r0 = HALF_INT_VECTOR.cast(IntVector.fromArray(INTS_FOR_LONGS, residual, i)).lanewise(VectorOperators.ABS);
                var r1 = HALF_INT_VECTOR.cast(IntVector.fromArray(INTS_FOR_LONGS, residual, i + LONG_LANES)).lanewise(VectorOperators.ABS);
                var w0 = LONG_VECTOR.cast(r0.convertShape(VectorOperators.I2L, LONGS, 0));
                var w1 = LONG_VECTOR.cast(r1.convertShape(VectorOperators.I2L, LONGS, 0));
                acc0 = LONG_VECTOR.cast(acc0).lanewise(VectorOperators.ADD, w0);
                acc1 = LONG_VECTOR.cast(acc1).lanewise(VectorOperators.ADD, w1);
            }
            sum = LONG_VECTOR.cast(acc0).lanewise(VectorOperators.ADD, acc1).reduceLanes(VectorOperators.ADD);
        }
        for (; i < to; i++) {
            sum += Math.abs(residual[i]);
        }
        return sum;
    }

    @Override
    public void midSide(int[] left, int[] right, int[] mid, int[] side, int count) {
        var i = 0;
        for (; i <= count - INT_LANES; i += INT_LANES) {
            var l = INT_VECTOR.cast(IntVector.fromArray(INTS, left, i));
            var r = INT_VECTOR.cast(IntVector.fromArray(INTS, right, i));
            l.lanewise(VectorOperators.SUB, r).intoArray(side, i);
            l.lanewise(VectorOperators.ADD, r).lanewise(VectorOperators.ASHR, 1).intoArray(mid, i);
        }
        for (; i < count; i++) {
            var l = left[i];
            var r = right[i];
            side[i] = l - r;
            mid[i] = (l + r) >> 1;
        }
    }
}
