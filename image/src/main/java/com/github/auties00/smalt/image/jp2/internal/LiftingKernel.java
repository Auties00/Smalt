package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds a wavelet transformation kernel as a sequence of lifting steps (ISO/IEC 15444-2 annexes G and H), the form of
 * an {@code ATK} segment, and reconstructs interleaved one-dimensional signals with it.
 *
 * <p>Whole-sample symmetric kernels are held in their arbitrary form, unfolded as H.1.2 specifies, so one
 * reconstruction procedure serves both categories. Buffers hold {@code n} positions of {@code lanes} signals each:
 * position {@code p} of signal {@code l} is at index {@code p * lanes + l}, and position {@code p} carries the sample
 * of index {@code i0 + p} on its signal's coordinate axis.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class LiftingKernel {
    /**
     * The reversible 5-3 kernel of ISO/IEC 15444-1 in the form of Table G.2.
     */
    static final LiftingKernel REVERSIBLE_53 = new LiftingKernel(true, 1, new int[]{1, 0}, new int[]{0, -1},
            new double[][]{{-1, -1}, {1, 1}}, new int[]{1, 2}, new double[]{1, 2}, true, true);

    /**
     * The irreversible 9-7 kernel of ISO/IEC 15444-1 in the form of Table G.6, with the exact coefficients of F.4.8.2.
     */
    static final LiftingKernel IRREVERSIBLE_97 = new LiftingKernel(false, 1.230174104914001, new int[]{1, 0, 1, 0},
            new int[]{0, -1, 0, -1},
            new double[][]{{-1.586134342059924, -1.586134342059924}, {-0.052980118572961, -0.052980118572961},
                    {0.882911075530934, 0.882911075530934}, {0.443506852043971, 0.443506852043971}},
            new int[4], new double[4], true, true);

    /**
     * Whether the kernel is reversible ({@code WT_Typ = REV}).
     */
    final boolean reversible;

    /**
     * Whether the kernel belongs to the whole-sample symmetric category ({@code Filt_Cat = WS}), the only one the
     * single sample overlap transformation of ISO/IEC 15444-2 I.3 applies to.
     */
    final boolean wholeSample;

    /**
     * The factor {@code 1 / B_s} of equation I-13 of each lifting step of an irreversible kernel, which scales the
     * samples on the cell boundaries of the single sample overlap transformation instead of the step's update.
     */
    private final double[] boundaryScales;

    /**
     * The scaling factor {@code K} of an irreversible kernel, 1 for a reversible one.
     */
    final double scale;

    /**
     * The update characteristic {@code m_s} of each lifting step: 0 when it updates the even-indexed samples.
     */
    final int[] updates;

    /**
     * The offset {@code off_s} of each lifting step.
     */
    final int[] offsets;

    /**
     * The lifting coefficients {@code alpha_s,k} of each step.
     */
    final double[][] coefficients;

    /**
     * The base-2 scaling exponent {@code epsilon_s} of each step of a reversible kernel.
     */
    final int[] exponents;

    /**
     * The additive residue {@code beta_s} of each step of a reversible kernel.
     */
    final double[] residues;

    /**
     * Whether the lifting steps extend the signal symmetrically ({@code Exten = WS}) rather than by constants.
     */
    final boolean symmetric;

    /**
     * The coefficients as integers, or {@code null} when a coefficient or residue of a reversible kernel is not an
     * integer, which makes the reversible steps use exact integer arithmetic.
     */
    private final long[][] integerCoefficients;

    /**
     * The residues as integers, valid when {@link #integerCoefficients} is not {@code null}.
     */
    private final long[] integerResidues;

    /**
     * Constructs a kernel in its arbitrary form.
     *
     * @param reversible whether the kernel is reversible
     * @param scale the scaling factor, ignored for a reversible kernel
     * @param updates the update characteristic of each step
     * @param offsets the offset of each step
     * @param coefficients the lifting coefficients of each step
     * @param exponents the scaling exponent of each step
     * @param residues the additive residue of each step
     * @param symmetric whether the signal is extended symmetrically
     * @param wholeSample whether the kernel is of the whole-sample symmetric category
     */
    LiftingKernel(boolean reversible, double scale, int[] updates, int[] offsets, double[][] coefficients,
                  int[] exponents, double[] residues, boolean symmetric, boolean wholeSample) {
        this.reversible = reversible;
        this.scale = reversible ? 1 : scale;
        this.updates = updates;
        this.offsets = offsets;
        this.coefficients = coefficients;
        this.exponents = exponents;
        this.residues = residues;
        this.symmetric = symmetric;
        this.wholeSample = wholeSample;
        this.boundaryScales = new double[coefficients.length];
        var previous = 1.0;
        var beforePrevious = 1.0;
        for (var s = 0; s < coefficients.length; s++) {
            var sum = 0.0;
            for (var alpha : coefficients[s]) {
                sum += alpha;
            }
            var current = sum * previous + beforePrevious;
            boundaryScales[s] = 1 / current;
            beforePrevious = previous;
            previous = current;
        }
        long[][] integers = null;
        long[] integerResidues = null;
        if (reversible) {
            integers = new long[coefficients.length][];
            integerResidues = new long[coefficients.length];
            for (var s = 0; s < coefficients.length && integers != null; s++) {
                integers[s] = new long[coefficients[s].length];
                for (var k = 0; k < coefficients[s].length; k++) {
                    var value = coefficients[s][k];
                    if (value != Math.rint(value) || Math.abs(value) > 1 << 30) {
                        integers = null;
                        break;
                    }
                    integers[s][k] = (long) value;
                }
                if (integers != null) {
                    if (residues[s] != Math.rint(residues[s]) || Math.abs(residues[s]) > 1L << 40) {
                        integers = null;
                    } else {
                        integerResidues[s] = (long) residues[s];
                    }
                }
            }
        }
        this.integerCoefficients = integers;
        this.integerResidues = integers == null ? null : integerResidues;
    }

    /**
     * Holds a parsed {@code ATK} segment.
     *
     * @param index the kernel index, from 2 to 255
     * @param kernel the kernel the segment defines
     */
    record Indexed(int index, LiftingKernel kernel) {
    }

    /**
     * Parses an {@code ATK} segment (A.3.5).
     *
     * @param data the codestream
     * @param position the index of the {@code Satk} field
     * @param end the end of the segment
     * @return the kernel and its index
     * @throws MediaException.Malformed if the segment is truncated or its index is 0 or 1
     */
    static Indexed parse(byte[] data, int position, int end) {
        var reader = new FieldReader(data, position, end);
        var satk = reader.unsigned(2);
        var index = satk & 0xFF;
        if (index < 2) {
            throw new MediaException.Malformed("invalid JPEG 2000 ATK index " + index);
        }
        var type = (satk >> 8) & 7;
        if (type > 4) {
            throw new MediaException.Malformed("invalid JPEG 2000 ATK coefficient type " + type);
        }
        var symmetricFilter = (satk & 0x0800) != 0;
        var reversible = (satk & 0x1000) != 0;
        var initial = (satk >> 13) & 1;
        var symmetricExtension = (satk & 0x4000) != 0;
        var scale = reversible ? 1 : reader.real(type);
        var steps = reader.unsigned(1);
        var updates = new int[steps];
        var offsets = new int[steps];
        var coefficients = new double[steps][];
        var exponents = new int[steps];
        var residues = new double[steps];
        var update = initial;
        for (var s = steps - 1; s >= 0; s--) {
            updates[s] = update;
            update = 1 - update;
        }
        for (var s = 0; s < steps; s++) {
            if (!symmetricFilter) {
                offsets[s] = (byte) reader.unsigned(1);
            }
            if (reversible) {
                exponents[s] = reader.unsigned(1);
                residues[s] = reader.real(type);
            }
            var count = reader.unsigned(1);
            var values = new double[count];
            for (var k = 0; k < count; k++) {
                values[k] = reader.real(type);
            }
            if (symmetricFilter) {
                var unfolded = new double[2 * count];
                for (var k = 0; k < count; k++) {
                    unfolded[count + k] = values[k];
                    unfolded[count - 1 - k] = values[k];
                }
                values = unfolded;
                offsets[s] = updates[s] - count;
            }
            coefficients[s] = values;
        }
        if (reversible && !Double.isFinite(scale) || !reversible && (scale == 0 || !Double.isFinite(scale))) {
            throw new MediaException.Malformed("invalid JPEG 2000 ATK scaling factor " + scale);
        }
        return new Indexed(index, new LiftingKernel(reversible, scale, updates, offsets, coefficients, exponents,
                residues, symmetricFilter || symmetricExtension, symmetricFilter));
    }

    /**
     * Returns the number of lifting steps.
     *
     * @return the step count {@code N_LS}
     */
    int steps() {
        return updates.length;
    }

    /**
     * Maps a sample index outside a signal to the index whose value extends the signal there.
     *
     * @param index the sample index
     * @param i0 the first index of the signal
     * @param i1 the index after the last one
     * @return the index inside the signal, from {@code i0} to {@code i1 - 1}
     */
    int extend(long index, long i0, long i1) {
        if (index >= i0 && index < i1) {
            return (int) (index - i0);
        }
        var length = i1 - i0;
        if (symmetric) {
            var period = 2 * (length - 1);
            var offset = Math.floorMod(index - i0, period);
            return (int) Math.min(offset, period - offset);
        }
        if (index < i0) {
            return (int) (((index - i0) & 1) == 0 ? 0 : 1);
        }
        return (int) (((index - (i1 - 1)) & 1) == 0 ? length - 1 : length - 2);
    }

    /**
     * Reconstructs reversible signals in place ({@code 1D_SR_ARB} with {@code 1D_UPDATER_REV}).
     *
     * @param buffer the interleaved signals: low-pass samples at the positions of even indices, high-pass samples
     * at the odd ones
     * @param n the number of positions
     * @param i0 the index of the first position
     * @param lanes the number of signals
     */
    void inverse(long[] buffer, int n, long i0, int lanes) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) != 0) {
                for (var lane = 0; lane < lanes; lane++) {
                    buffer[lane] /= 2;
                }
            }
            return;
        }
        var i1 = i0 + n;
        for (var s = updates.length - 1; s >= 0; s--) {
            var m = updates[s];
            var count = coefficients[s].length;
            var first = (int) Math.floorMod(m - i0, 2);
            var divisor = exponents[s];
            for (var p = first; p < n; p += 2) {
                var base = i0 + p + 1 - 2 * m + 2L * offsets[s];
                var target = p * lanes;
                if (integerCoefficients != null) {
                    var alpha = integerCoefficients[s];
                    for (var lane = 0; lane < lanes; lane++) {
                        var sum = integerResidues[s];
                        for (var k = 0; k < count; k++) {
                            sum += alpha[k] * buffer[extend(base + 2L * k, i0, i1) * lanes + lane];
                        }
                        buffer[target + lane] -= divisor >= 63 ? (sum < 0 ? -1 : 0) : sum >> divisor;
                    }
                } else {
                    var alpha = coefficients[s];
                    var power = Math.scalb(1.0, divisor);
                    for (var lane = 0; lane < lanes; lane++) {
                        var sum = residues[s];
                        for (var k = 0; k < count; k++) {
                            sum += alpha[k] * buffer[extend(base + 2L * k, i0, i1) * lanes + lane];
                        }
                        buffer[target + lane] -= (long) Math.floor(sum / power);
                    }
                }
            }
        }
    }

    /**
     * Reconstructs irreversible signals in place ({@code 1D_SR_ARB} with {@code 1D_SCALER} and
     * {@code 1D_UPDATER_IRR}), in single precision.
     *
     * @param buffer the interleaved signals: low-pass samples at the positions of even indices, high-pass samples
     * at the odd ones
     * @param n the number of positions
     * @param i0 the index of the first position
     * @param lanes the number of signals
     */
    void inverse(float[] buffer, int n, long i0, int lanes) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            if ((i0 & 1) != 0) {
                for (var lane = 0; lane < lanes; lane++) {
                    buffer[lane] *= 0.5f;
                }
            }
            return;
        }
        var low = (float) scale;
        var high = (float) (1 / scale);
        for (var p = 0; p < n; p++) {
            var factor = ((i0 + p) & 1) == 0 ? low : high;
            for (var lane = 0; lane < lanes; lane++) {
                buffer[p * lanes + lane] *= factor;
            }
        }
        var i1 = i0 + n;
        for (var s = updates.length - 1; s >= 0; s--) {
            var m = updates[s];
            var alpha = coefficients[s];
            var count = alpha.length;
            var first = (int) Math.floorMod(m - i0, 2);
            for (var p = first; p < n; p += 2) {
                var base = i0 + p + 1 - 2 * m + 2L * offsets[s];
                var target = p * lanes;
                for (var lane = 0; lane < lanes; lane++) {
                    var sum = 0f;
                    for (var k = 0; k < count; k++) {
                        sum += (float) alpha[k] * buffer[extend(base + 2L * k, i0, i1) * lanes + lane];
                    }
                    buffer[target + lane] -= sum;
                }
            }
        }
    }

    /**
     * Returns whether a signal holds a cell boundary of the single sample overlap transformation that a lifting step
     * updates.
     *
     * @param i0 the index of the first position
     * @param i1 the index after the last position
     * @param parity the parity {@code m_s} the step updates
     * @param period the cell size, a power of two
     * @return whether some index in {@code [i0, i1)} of that parity is a multiple of {@code period}
     */
    private static boolean holdsBoundary(long i0, long i1, int parity, long period) {
        var first = Math.ceilDiv(i0, period) * period;
        for (var index = first; index < i1 && index < first + 2 * period; index += period) {
            if (Math.floorMod(index, 2) == parity) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconstructs reversible signals in place with the single sample overlap procedure {@code 1D_FILTR_SSO} of
     * ISO/IEC 15444-2 I.3.1.6.1.
     *
     * <p>The indices that are multiples of {@code period} split the signal into intervals that share their end
     * points. Every lifting step leaves the samples on those end points unchanged and updates every other sample from
     * the samples of its own interval, extended symmetrically at the interval's ends (equation I-12).
     *
     * @param buffer the interleaved signals: low-pass samples at the positions of even indices, high-pass samples
     * at the odd ones
     * @param n the number of positions
     * @param i0 the index of the first position, on the signal's coordinate axis
     * @param lanes the number of signals
     * @param period the cell size {@code dC} on the signal's axis, a power of two; 1 leaves every sample unchanged
     */
    void inverseOverlapped(long[] buffer, int n, long i0, int lanes, long period) {
        if (n <= 1) {
            inverse(buffer, n, i0, lanes);
            return;
        }
        var i1 = i0 + n;
        for (var s = updates.length - 1; s >= 0; s--) {
            var m = updates[s];
            var count = coefficients[s].length;
            var first = (int) Math.floorMod(m - i0, 2);
            var divisor = exponents[s];
            var power = Math.scalb(1.0, divisor);
            for (var p = first; p < n; p += 2) {
                var index = i0 + p;
                var cell = Math.floorDiv(index, period) * period;
                if (cell == index) {
                    continue;
                }
                var start = Math.max(i0, cell);
                var end = Math.min(i1, cell + period + 1);
                var shift = start - i0;
                var base = index + 1 - 2 * m + 2L * offsets[s];
                var target = p * lanes;
                for (var lane = 0; lane < lanes; lane++) {
                    if (integerCoefficients != null) {
                        var alpha = integerCoefficients[s];
                        var sum = integerResidues[s];
                        for (var k = 0; k < count; k++) {
                            sum += alpha[k] * buffer[(int) (extend(base + 2L * k, start, end) + shift) * lanes
                                    + lane];
                        }
                        buffer[target + lane] -= divisor >= 63 ? (sum < 0 ? -1 : 0) : sum >> divisor;
                    } else {
                        var alpha = coefficients[s];
                        var sum = residues[s];
                        for (var k = 0; k < count; k++) {
                            sum += alpha[k] * buffer[(int) (extend(base + 2L * k, start, end) + shift) * lanes
                                    + lane];
                        }
                        buffer[target + lane] -= (long) Math.floor(sum / power);
                    }
                }
            }
        }
    }

    /**
     * Reconstructs irreversible signals in place with the single sample overlap procedure {@code 1D_FILTR_SSO} of
     * ISO/IEC 15444-2 I.3.1.6.2, in single precision.
     *
     * <p>As {@link #inverseOverlapped(long[], int, long, int, long)}, except that each lifting step multiplies the
     * samples on the interval end points it would update by {@code 1 / B_s} (equation I-13) instead of leaving them
     * unchanged; the scaling steps are those of the whole signal.
     *
     * @implNote This implementation applies equation I-13 at a lifting step only to the end points of the parity the
     * step updates, as the forward example of I.3.4.3 does for the 9-7 wavelet; the factors are those of equation G-1,
     * where that informative example writes factors that differ from them.
     * @param buffer the interleaved signals: low-pass samples at the positions of even indices, high-pass samples
     * at the odd ones
     * @param n the number of positions
     * @param i0 the index of the first position, on the signal's coordinate axis
     * @param lanes the number of signals
     * @param period the cell size {@code dC} on the signal's axis, a power of two
     * @throws MediaException.Malformed if a lifting step whose {@code B_s} of equation G-1 is zero updates a cell
     * boundary of the signal, which equation I-13 leaves undefined; the 5-3 wavelet, whose {@code B_0} is zero, meets
     * one only with cells of a single sample
     */
    void inverseOverlapped(float[] buffer, int n, long i0, int lanes, long period) {
        if (n <= 1) {
            inverse(buffer, n, i0, lanes);
            return;
        }
        var low = (float) scale;
        var high = (float) (1 / scale);
        for (var p = 0; p < n; p++) {
            var factor = ((i0 + p) & 1) == 0 ? low : high;
            for (var lane = 0; lane < lanes; lane++) {
                buffer[p * lanes + lane] *= factor;
            }
        }
        var i1 = i0 + n;
        for (var s = updates.length - 1; s >= 0; s--) {
            var m = updates[s];
            var alpha = coefficients[s];
            var count = alpha.length;
            var first = (int) Math.floorMod(m - i0, 2);
            var boundary = (float) boundaryScales[s];
            if (!Float.isFinite(boundary) && holdsBoundary(i0, i1, m, period)) {
                throw new MediaException.Malformed("JPEG 2000 single sample overlap transformation whose boundary "
                        + "scaling of equation I-13 is undefined");
            }
            for (var p = first; p < n; p += 2) {
                var index = i0 + p;
                var cell = Math.floorDiv(index, period) * period;
                var target = p * lanes;
                if (cell == index) {
                    for (var lane = 0; lane < lanes; lane++) {
                        buffer[target + lane] *= boundary;
                    }
                    continue;
                }
                var start = Math.max(i0, cell);
                var end = Math.min(i1, cell + period + 1);
                var shift = start - i0;
                var base = index + 1 - 2 * m + 2L * offsets[s];
                for (var lane = 0; lane < lanes; lane++) {
                    var sum = 0f;
                    for (var k = 0; k < count; k++) {
                        sum += (float) alpha[k] * buffer[(int) (extend(base + 2L * k, start, end) + shift) * lanes
                                + lane];
                    }
                    buffer[target + lane] -= sum;
                }
            }
        }
    }

    /**
     * Reads the fields of a marker segment in order.
     */
    private static final class FieldReader {
        /**
         * The codestream.
         */
        private final byte[] data;

        /**
         * The end of the segment.
         */
        private final int end;

        /**
         * The index of the next field.
         */
        private int position;

        /**
         * Constructs a reader.
         *
         * @param data the codestream
         * @param position the index of the first field
         * @param end the end of the segment
         */
        FieldReader(byte[] data, int position, int end) {
            this.data = data;
            this.position = position;
            this.end = end;
        }

        /**
         * Reads an unsigned big-endian field.
         *
         * @param bytes the field size, 1 or 2
         * @return the value
         * @throws MediaException.Malformed if the segment ends first
         */
        int unsigned(int bytes) {
            require(bytes);
            var value = bytes == 1 ? data[position] & 0xFF : ByteArrays.u16be(data, position);
            position += bytes;
            return value;
        }

        /**
         * Reads a value of a coefficient type ({@code Coeff_Typ} of Table A.28).
         *
         * @param type the type: 8 or 16-bit signed integer, or 32, 64 or 128-bit binary floating point
         * @return the value
         * @throws MediaException.Malformed if the segment ends first
         */
        double real(int type) {
            var bytes = switch (type) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 8;
                default -> 16;
            };
            require(bytes);
            var at = position;
            position += bytes;
            return Part2Values.real(data, at, type);
        }

        /**
         * Checks that a field fits the segment.
         *
         * @param bytes the field size
         * @throws MediaException.Malformed if it does not
         */
        private void require(int bytes) {
            if (end - position < bytes) {
                throw new MediaException.Malformed("truncated JPEG 2000 ATK segment");
            }
        }
    }
}
