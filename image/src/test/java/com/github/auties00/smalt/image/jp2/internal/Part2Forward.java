package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The forward transforms of ISO/IEC 15444-2 a test encoder needs, written from the equations of the standard and
 * independent of the decoder: the lifting of arbitrary wavelet kernels (annex H, run backwards), the 5-3 wavelet of
 * ISO/IEC 15444-1 F.4.8.1, arbitrary decompositions (annex F, F.4), and region-of-interest masks (L.3.4).
 */
final class Part2Forward {
    static final int LEAF = 0;
    static final int JOINT = 1;
    static final int HORIZONTAL = 2;
    static final int VERTICAL = 3;

    private Part2Forward() {
        throw new AssertionError();
    }

    /** A node of a decomposition tree; children follow Tables F.3 and F.4 (LL, HL, LH, HH; LX, HX; XL, XH). */
    record Tree(int split, Tree[] children) {
        static final Tree LEAF_NODE = new Tree(LEAF, null);

        static Tree of(int split, Tree... children) {
            var expected = split == JOINT ? 4 : 2;
            if (children.length == 0) {
                children = new Tree[expected];
                Arrays.fill(children, LEAF_NODE);
            }
            if (children.length != expected) {
                throw new IllegalArgumentException("split " + split + " needs " + expected + " children");
            }
            return new Tree(split, children);
        }

        /** The dyadic decomposition of ISO/IEC 15444-1 with {@code levels} levels. */
        static Tree dyadic(int levels) {
            return levels == 0 ? LEAF_NODE : of(JOINT, dyadic(levels - 1), LEAF_NODE, LEAF_NODE, LEAF_NODE);
        }
    }

    /** One lifting step: the parity it updates, its offset, coefficients, and for reversible kernels its rounding. */
    record Step(int m, int offset, double[] alphas, int epsilon, double beta) {
    }

    /** A lifting kernel in the form of an ATK segment; {@code steps[s]} is lifting step {@code s}. */
    record Kernel(boolean reversible, double k, boolean symmetricExtension, Step... steps) {
        /** Writes the kernel as an ATK segment of the arbitrary filter category. */
        byte[] atk(int index, int coefficientType) {
            return atk(index, coefficientType, false);
        }

        /**
         * Writes the kernel as an ATK segment: arbitrary (ARB) or, when {@code wholeSample} is set, whole-sample
         * symmetric (WS), which needs symmetric steps with {@code offset == m - L/2}.
         */
        byte[] atk(int index, int coefficientType, boolean wholeSample) {
            var body = new ByteArrayOutputStream();
            var minit = steps[steps.length - 1].m();
            var satk = index | (coefficientType << 8) | (wholeSample ? 0x0800 : 0) | (reversible ? 0x1000 : 0)
                    | (minit << 13) | (symmetricExtension || wholeSample ? 0x4000 : 0);
            u16(body, satk);
            if (!reversible) {
                real(body, k, coefficientType);
            }
            body.write(steps.length);
            for (var step : steps) {
                if (!wholeSample) {
                    body.write(step.offset() & 0xFF);
                }
                if (reversible) {
                    body.write(step.epsilon());
                    real(body, step.beta(), coefficientType);
                }
                var alphas = step.alphas();
                if (wholeSample) {
                    var half = alphas.length / 2;
                    if (alphas.length % 2 != 0 || step.offset() != step.m() - half) {
                        throw new IllegalArgumentException("not a whole-sample symmetric step");
                    }
                    for (var j = 0; j < half; j++) {
                        if (alphas[half + j] != alphas[half - 1 - j]) {
                            throw new IllegalArgumentException("not a whole-sample symmetric step");
                        }
                    }
                    body.write(half);
                    for (var j = 0; j < half; j++) {
                        real(body, alphas[half + j], coefficientType);
                    }
                } else {
                    body.write(alphas.length);
                    for (var alpha : alphas) {
                        real(body, alpha, coefficientType);
                    }
                }
            }
            return segment(Codestream.ATK, body.toByteArray());
        }
    }

    /** The 5-3 wavelet in lifting form (ISO/IEC 15444-2 Table G.2 unfolded). */
    static final Kernel LE_GALL = new Kernel(true, 1, true,
            new Step(1, 0, new double[]{-1, -1}, 1, 1),
            new Step(0, -1, new double[]{1, 1}, 2, 2));

    /** The 9-7 wavelet in lifting form (ISO/IEC 15444-1 Table F.4 and equation F-?), unfolded. */
    static final Kernel DAUBECHIES = new Kernel(false, 1.230174104914001, true,
            new Step(1, 0, new double[]{-1.586134342059924, -1.586134342059924}, 0, 0),
            new Step(0, -1, new double[]{-0.052980118572961, -0.052980118572961}, 0, 0),
            new Step(1, 0, new double[]{0.882911075530934, 0.882911075530934}, 0, 0),
            new Step(0, -1, new double[]{0.443506852043971, 0.443506852043971}, 0, 0));

    /**
     * A reversible 13-7 style kernel with four coefficients per step and a constant extension: unlike any kernel of
     * ISO/IEC 15444-1, so a decoder that ignored the ATK segment would not reconstruct the input.
     */
    static final Kernel WIDE_REVERSIBLE = new Kernel(true, 1, false,
            new Step(1, -1, new double[]{1, -9, -9, 1}, 4, 8),
            new Step(0, -2, new double[]{-1, 5, 5, -1}, 4, 8));

    /** A reversible kernel of three lifting steps, starting with the odd samples, with asymmetric offsets. */
    static final Kernel THREE_STEP = new Kernel(true, 1, true,
            new Step(0, 0, new double[]{1, 1}, 1, 0),
            new Step(1, -1, new double[]{-3, -1}, 2, 1),
            new Step(0, 0, new double[]{1, 1}, 2, 2));

    /** The steps of {@link #WIDE_REVERSIBLE} with symmetric extension: a whole-sample symmetric kernel of four taps. */
    static final Kernel WIDE_SYMMETRIC = new Kernel(true, 1, true, WIDE_REVERSIBLE.steps());

    /**
     * Returns the cell size {@code dC} of the single sample overlap transformation for the one-dimensional
     * transforms of a signal decomposed {@code depth} times in their direction: {@code XC / 2^lev} at level
     * {@code lev = depth + 1}, as the normative IDWT_SSO procedure of ISO/IEC 15444-2 Figure I.3 sets it, and 1 when
     * that falls below 1.
     */
    static long cell(int exponent, int depth) {
        var e = exponent - depth - 1;
        return e <= 0 ? 1 : 1L << e;
    }

    /** Returns the factors {@code B_s} of ISO/IEC 15444-2 equation G-1 (irreversible form) of each lifting step. */
    static double[] boundaryFactors(Kernel kernel) {
        var steps = kernel.steps();
        var factors = new double[steps.length];
        var previous = 1.0;
        var beforePrevious = 1.0;
        for (var s = 0; s < steps.length; s++) {
            var d = 0.0;
            for (var alpha : steps[s].alphas()) {
                d += alpha;
            }
            factors[s] = d * previous + beforePrevious;
            beforePrevious = previous;
            previous = factors[s];
        }
        return factors;
    }

    /**
     * Decomposes one interleaved signal with a reversible kernel and the single sample overlap procedure
     * {@code 1D_FILTD_SSO} (ISO/IEC 15444-2 I.3.2.6.1): the samples whose index is a multiple of {@code period} keep
     * their value, every other one is lifted from the samples of its interval {@code [n_p, n_p+1]} extended
     * symmetrically (equation I-15).
     */
    static void forwardOverlapped(long[] x, long i0, Kernel kernel, long period) {
        var n = x.length;
        if (n == 1) {
            forward(x, i0, kernel);
            return;
        }
        var i1 = i0 + n;
        for (var step : kernel.steps()) {
            var update = new long[n];
            for (var p = 0; p < n; p++) {
                var index = i0 + p;
                if (Math.floorMod(index, 2) != step.m() || Math.floorMod(index, period) == 0) {
                    continue;
                }
                var start = Math.max(i0, Math.floorDiv(index, period) * period);
                var end = Math.min(i1, Math.floorDiv(index, period) * period + period + 1);
                var sum = step.beta();
                var alphas = step.alphas();
                for (var k = 0; k < alphas.length; k++) {
                    var tap = index + 1 - 2L * step.m() + 2L * (k + step.offset());
                    sum += alphas[k] * x[(int) (extend(tap, start, end, true) + start - i0)];
                }
                update[p] = (long) Math.floor(sum / Math.scalb(1.0, step.epsilon()));
            }
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) == step.m()) {
                    x[p] += update[p];
                }
            }
        }
    }

    /**
     * Decomposes one interleaved signal with an irreversible kernel and the single sample overlap procedure
     * {@code 1D_FILTD_SSO} (I.3.2.6.2): at each lifting step the samples of the step's parity whose index is a
     * multiple of {@code period} are multiplied by {@code B_s} (equation I-16), every other one is lifted inside its
     * interval (equation I-17); the scaling is that of the whole signal.
     */
    static void forwardOverlapped(double[] x, long i0, Kernel kernel, long period) {
        var n = x.length;
        if (n == 1) {
            forward(x, i0, kernel);
            return;
        }
        var factors = boundaryFactors(kernel);
        var i1 = i0 + n;
        var steps = kernel.steps();
        for (var s = 0; s < steps.length; s++) {
            var step = steps[s];
            var next = x.clone();
            for (var p = 0; p < n; p++) {
                var index = i0 + p;
                if (Math.floorMod(index, 2) != step.m()) {
                    continue;
                }
                if (Math.floorMod(index, period) == 0) {
                    next[p] = x[p] * factors[s];
                    continue;
                }
                var start = Math.max(i0, Math.floorDiv(index, period) * period);
                var end = Math.min(i1, Math.floorDiv(index, period) * period + period + 1);
                var sum = 0.0;
                var alphas = step.alphas();
                for (var k = 0; k < alphas.length; k++) {
                    var tap = index + 1 - 2L * step.m() + 2L * (k + step.offset());
                    sum += alphas[k] * x[(int) (extend(tap, start, end, true) + start - i0)];
                }
                next[p] = x[p] + sum;
            }
            System.arraycopy(next, 0, x, 0, n);
        }
        for (var p = 0; p < n; p++) {
            x[p] = Math.floorMod(i0 + p, 2) == 0 ? x[p] / kernel.k() : x[p] * kernel.k();
        }
    }

    /**
     * Decomposes a tile-component in place like {@link #decompose(long[], int, int, int, long, long, long, long, Tree,
     * Kernel)}, every one-dimensional transform with the single sample overlap procedure and the cell size
     * {@link #cell(int, int)} gives the depth of the signal it splits.
     */
    static void decomposeOverlapped(long[] data, int stride, int left, int top, long x0, long y0, long x1, long y1,
                                    Tree tree, Kernel kernel, int cellX, int cellY, int depthX, int depthY) {
        if (tree.split() == LEAF) {
            return;
        }
        var width = (int) (x1 - x0);
        var height = (int) (y1 - y0);
        var lowWidth = (int) (ceilDiv2(x1, 1) - ceilDiv2(x0, 1));
        var lowHeight = (int) (ceilDiv2(y1, 1) - ceilDiv2(y0, 1));
        if (width > 0 && height > 0) {
            if (tree.split() != HORIZONTAL) {
                for (var u = 0; u < width; u++) {
                    var column = new long[height];
                    for (var v = 0; v < height; v++) {
                        column[v] = data[(top + v) * stride + left + u];
                    }
                    forwardOverlapped(column, y0, kernel, cell(cellY, depthY));
                    var split = deinterleave(column, y0);
                    for (var v = 0; v < height; v++) {
                        data[(top + v) * stride + left + u] = split[v];
                    }
                }
            }
            if (tree.split() != VERTICAL) {
                for (var v = 0; v < height; v++) {
                    var row = Arrays.copyOfRange(data, (top + v) * stride + left, (top + v) * stride + left + width);
                    forwardOverlapped(row, x0, kernel, cell(cellX, depthX));
                    System.arraycopy(deinterleave(row, x0), 0, data, (top + v) * stride + left, width);
                }
            }
        }
        var childX = depthX + (tree.split() != VERTICAL ? 1 : 0);
        var childY = depthY + (tree.split() != HORIZONTAL ? 1 : 0);
        var children = tree.children();
        for (var j = 0; j < children.length; j++) {
            var horizontal = tree.split() == JOINT ? j & 1 : tree.split() == HORIZONTAL ? j : -1;
            var vertical = tree.split() == JOINT ? j >> 1 : tree.split() == VERTICAL ? j : -1;
            var cx0 = horizontal < 0 ? x0 : horizontal == 0 ? ceilDiv2(x0, 1) : Math.floorDiv(x0, 2);
            var cx1 = horizontal < 0 ? x1 : horizontal == 0 ? ceilDiv2(x1, 1) : Math.floorDiv(x1, 2);
            var cy0 = vertical < 0 ? y0 : vertical == 0 ? ceilDiv2(y0, 1) : Math.floorDiv(y0, 2);
            var cy1 = vertical < 0 ? y1 : vertical == 0 ? ceilDiv2(y1, 1) : Math.floorDiv(y1, 2);
            decomposeOverlapped(data, stride, left + (horizontal == 1 ? lowWidth : 0),
                    top + (vertical == 1 ? lowHeight : 0), cx0, cy0, cx1, cy1, children[j], kernel, cellX, cellY,
                    childX, childY);
        }
    }

    /** The irreversible counterpart of the reversible {@code decomposeOverlapped}. */
    static void decomposeOverlapped(double[] data, int stride, int left, int top, long x0, long y0, long x1, long y1,
                                    Tree tree, Kernel kernel, int cellX, int cellY, int depthX, int depthY) {
        if (tree.split() == LEAF) {
            return;
        }
        var width = (int) (x1 - x0);
        var height = (int) (y1 - y0);
        var lowWidth = (int) (ceilDiv2(x1, 1) - ceilDiv2(x0, 1));
        var lowHeight = (int) (ceilDiv2(y1, 1) - ceilDiv2(y0, 1));
        if (width > 0 && height > 0) {
            if (tree.split() != HORIZONTAL) {
                for (var u = 0; u < width; u++) {
                    var column = new double[height];
                    for (var v = 0; v < height; v++) {
                        column[v] = data[(top + v) * stride + left + u];
                    }
                    forwardOverlapped(column, y0, kernel, cell(cellY, depthY));
                    var split = deinterleave(column, y0);
                    for (var v = 0; v < height; v++) {
                        data[(top + v) * stride + left + u] = split[v];
                    }
                }
            }
            if (tree.split() != VERTICAL) {
                for (var v = 0; v < height; v++) {
                    var row = Arrays.copyOfRange(data, (top + v) * stride + left, (top + v) * stride + left + width);
                    forwardOverlapped(row, x0, kernel, cell(cellX, depthX));
                    System.arraycopy(deinterleave(row, x0), 0, data, (top + v) * stride + left, width);
                }
            }
        }
        var childX = depthX + (tree.split() != VERTICAL ? 1 : 0);
        var childY = depthY + (tree.split() != HORIZONTAL ? 1 : 0);
        var children = tree.children();
        for (var j = 0; j < children.length; j++) {
            var horizontal = tree.split() == JOINT ? j & 1 : tree.split() == HORIZONTAL ? j : -1;
            var vertical = tree.split() == JOINT ? j >> 1 : tree.split() == VERTICAL ? j : -1;
            var cx0 = horizontal < 0 ? x0 : horizontal == 0 ? ceilDiv2(x0, 1) : Math.floorDiv(x0, 2);
            var cx1 = horizontal < 0 ? x1 : horizontal == 0 ? ceilDiv2(x1, 1) : Math.floorDiv(x1, 2);
            var cy0 = vertical < 0 ? y0 : vertical == 0 ? ceilDiv2(y0, 1) : Math.floorDiv(y0, 2);
            var cy1 = vertical < 0 ? y1 : vertical == 0 ? ceilDiv2(y1, 1) : Math.floorDiv(y1, 2);
            decomposeOverlapped(data, stride, left + (horizontal == 1 ? lowWidth : 0),
                    top + (vertical == 1 ? lowHeight : 0), cx0, cy0, cx1, cy1, children[j], kernel, cellX, cellY,
                    childX, childY);
        }
    }

    /** Writes a marker segment: marker, length and body. */
    static byte[] segment(int marker, byte[] body) {
        var out = new ByteArrayOutputStream();
        u16(out, marker);
        u16(out, body.length + 2);
        out.writeBytes(body);
        return out.toByteArray();
    }

    static void u16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    static void u32(ByteArrayOutputStream out, long value) {
        u16(out, (int) (value >>> 16) & 0xFFFF);
        u16(out, (int) value & 0xFFFF);
    }

    static void u64(ByteArrayOutputStream out, long value) {
        u32(out, value >>> 32);
        u32(out, value & 0xFFFFFFFFL);
    }

    /** Writes a value of a coefficient type of Table A.28: 8 or 16-bit integer, 32 or 64-bit float. */
    static void real(ByteArrayOutputStream out, double value, int type) {
        switch (type) {
            case 0 -> out.write((int) value & 0xFF);
            case 1 -> u16(out, (int) value & 0xFFFF);
            case 2 -> u32(out, Float.floatToIntBits((float) value) & 0xFFFFFFFFL);
            case 3 -> u64(out, Double.doubleToLongBits(value));
            default -> throw new IllegalArgumentException("coefficient type " + type);
        }
    }

    /** Divides by a power of two rounding up, for any sign. */
    static long ceilDiv2(long value, int shift) {
        return -Math.floorDiv(-value, 1L << shift);
    }

    /** Returns the sample of a signal extension (H.2.4.2 whole-sample symmetric, H.2.4.3 constant). */
    private static int extend(long index, long i0, long i1, boolean symmetric) {
        if (index >= i0 && index < i1) {
            return (int) (index - i0);
        }
        var n = i1 - i0;
        if (symmetric) {
            var period = 2 * (n - 1);
            var position = Math.floorMod(index - i0, period);
            return (int) (position < n ? position : period - position);
        }
        if (index < i0) {
            return Math.floorMod(index - i0, 2) == 0 ? 0 : 1;
        }
        return Math.floorMod(index - (i1 - 1), 2) == 0 ? (int) (n - 1) : (int) (n - 2);
    }

    /**
     * Decomposes one interleaved signal with a reversible kernel: each lifting step of H.2.5.1 in forward order with
     * the update added instead of subtracted.
     */
    static void forward(long[] x, long i0, Kernel kernel) {
        var n = x.length;
        if (n == 1) {
            if ((i0 & 1) != 0) {
                x[0] *= 2;
            }
            return;
        }
        var i1 = i0 + n;
        for (var step : kernel.steps()) {
            var update = new long[n];
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) != step.m()) {
                    continue;
                }
                var sum = step.beta();
                var exact = 0L;
                var integral = step.beta() == Math.rint(step.beta());
                for (var a : step.alphas()) {
                    integral &= a == Math.rint(a);
                }
                var alphas = step.alphas();
                for (var k = 0; k < alphas.length; k++) {
                    var index = i0 + p + 1 - 2L * step.m() + 2L * (k + step.offset());
                    var value = x[extend(index, i0, i1, kernel.symmetricExtension())];
                    if (integral) {
                        exact += (long) alphas[k] * value;
                    } else {
                        sum += alphas[k] * value;
                    }
                }
                update[p] = integral ? Math.floorDiv(exact + (long) step.beta(), 1L << step.epsilon())
                        : (long) Math.floor(sum / Math.scalb(1.0, step.epsilon()));
            }
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) == step.m()) {
                    x[p] += update[p];
                }
            }
        }
    }

    /** Decomposes one interleaved signal with an irreversible kernel: lifting steps forward, then the scaling. */
    static void forward(double[] x, long i0, Kernel kernel) {
        var n = x.length;
        if (n == 1) {
            if ((i0 & 1) != 0) {
                x[0] *= 2;
            }
            return;
        }
        var i1 = i0 + n;
        for (var step : kernel.steps()) {
            var update = new double[n];
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) != step.m()) {
                    continue;
                }
                var sum = 0.0;
                var alphas = step.alphas();
                for (var k = 0; k < alphas.length; k++) {
                    var index = i0 + p + 1 - 2L * step.m() + 2L * (k + step.offset());
                    sum += alphas[k] * x[extend(index, i0, i1, kernel.symmetricExtension())];
                }
                update[p] = sum;
            }
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) == step.m()) {
                    x[p] += update[p];
                }
            }
        }
        for (var p = 0; p < n; p++) {
            x[p] = Math.floorMod(i0 + p, 2) == 0 ? x[p] / kernel.k() : x[p] * kernel.k();
        }
    }

    /** Decomposes one signal with the 5-3 wavelet, written from ISO/IEC 15444-1 equations F-9 and F-10. */
    static void forward53(long[] x, long i0) {
        var n = x.length;
        if (n == 1) {
            if ((i0 & 1) != 0) {
                x[0] *= 2;
            }
            return;
        }
        var i1 = i0 + n;
        var y = x.clone();
        for (var p = 0; p < n; p++) {
            if (Math.floorMod(i0 + p, 2) == 1) {
                var left = x[extend(i0 + p - 1, i0, i1, true)];
                var right = x[extend(i0 + p + 1, i0, i1, true)];
                y[p] = x[p] - Math.floorDiv(left + right, 2);
            }
        }
        var z = y.clone();
        for (var p = 0; p < n; p++) {
            if (Math.floorMod(i0 + p, 2) == 0) {
                var left = y[extend(i0 + p - 1, i0, i1, true)];
                var right = y[extend(i0 + p + 1, i0, i1, true)];
                z[p] = y[p] + Math.floorDiv(left + right + 2, 4);
            }
        }
        System.arraycopy(z, 0, x, 0, n);
    }

    /**
     * Decomposes a tile-component in place: {@code data} holds {@code width x height} samples of the area
     * {@code [x0, x0 + width) x [y0, y0 + height)}, and receives the sub-bands of the tree, each split placing the
     * low-pass half first (F.4.3).
     */
    static void decompose(long[] data, int stride, int left, int top, long x0, long y0, long x1, long y1, Tree tree,
                          Kernel kernel) {
        if (tree.split() == LEAF) {
            return;
        }
        var width = (int) (x1 - x0);
        var height = (int) (y1 - y0);
        var lowWidth = (int) (ceilDiv2(x1, 1) - ceilDiv2(x0, 1));
        var lowHeight = (int) (ceilDiv2(y1, 1) - ceilDiv2(y0, 1));
        if (width > 0 && height > 0) {
            if (tree.split() != HORIZONTAL) {
                for (var u = 0; u < width; u++) {
                    var column = new long[height];
                    for (var v = 0; v < height; v++) {
                        column[v] = data[(top + v) * stride + left + u];
                    }
                    lift(column, y0, kernel);
                    var split = deinterleave(column, y0);
                    for (var v = 0; v < height; v++) {
                        data[(top + v) * stride + left + u] = split[v];
                    }
                }
            }
            if (tree.split() != VERTICAL) {
                for (var v = 0; v < height; v++) {
                    var row = Arrays.copyOfRange(data, (top + v) * stride + left, (top + v) * stride + left + width);
                    lift(row, x0, kernel);
                    System.arraycopy(deinterleave(row, x0), 0, data, (top + v) * stride + left, width);
                }
            }
        }
        var children = tree.children();
        for (var j = 0; j < children.length; j++) {
            int horizontal;
            int vertical;
            switch (tree.split()) {
                case JOINT -> {
                    horizontal = j & 1;
                    vertical = j >> 1;
                }
                case HORIZONTAL -> {
                    horizontal = j;
                    vertical = -1;
                }
                default -> {
                    horizontal = -1;
                    vertical = j;
                }
            }
            var cx0 = horizontal < 0 ? x0 : horizontal == 0 ? ceilDiv2(x0, 1) : Math.floorDiv(x0, 2);
            var cx1 = horizontal < 0 ? x1 : horizontal == 0 ? ceilDiv2(x1, 1) : Math.floorDiv(x1, 2);
            var cy0 = vertical < 0 ? y0 : vertical == 0 ? ceilDiv2(y0, 1) : Math.floorDiv(y0, 2);
            var cy1 = vertical < 0 ? y1 : vertical == 0 ? ceilDiv2(y1, 1) : Math.floorDiv(y1, 2);
            var childLeft = left + (horizontal == 1 ? lowWidth : 0);
            var childTop = top + (vertical == 1 ? lowHeight : 0);
            decompose(data, stride, childLeft, childTop, cx0, cy0, cx1, cy1, children[j], kernel);
        }
    }

    private static void lift(long[] signal, long i0, Kernel kernel) {
        if (kernel == null) {
            forward53(signal, i0);
        } else {
            forward(signal, i0, kernel);
        }
    }

    /** The irreversible counterpart of {@link #decompose(long[], int, int, int, long, long, long, long, Tree, Kernel)}. */
    static void decompose(double[] data, int stride, int left, int top, long x0, long y0, long x1, long y1, Tree tree,
                          Kernel kernel) {
        if (tree.split() == LEAF) {
            return;
        }
        var width = (int) (x1 - x0);
        var height = (int) (y1 - y0);
        var lowWidth = (int) (ceilDiv2(x1, 1) - ceilDiv2(x0, 1));
        var lowHeight = (int) (ceilDiv2(y1, 1) - ceilDiv2(y0, 1));
        if (width > 0 && height > 0) {
            if (tree.split() != HORIZONTAL) {
                for (var u = 0; u < width; u++) {
                    var column = new double[height];
                    for (var v = 0; v < height; v++) {
                        column[v] = data[(top + v) * stride + left + u];
                    }
                    forward(column, y0, kernel);
                    var split = deinterleave(column, y0);
                    for (var v = 0; v < height; v++) {
                        data[(top + v) * stride + left + u] = split[v];
                    }
                }
            }
            if (tree.split() != VERTICAL) {
                for (var v = 0; v < height; v++) {
                    var row = Arrays.copyOfRange(data, (top + v) * stride + left, (top + v) * stride + left + width);
                    forward(row, x0, kernel);
                    System.arraycopy(deinterleave(row, x0), 0, data, (top + v) * stride + left, width);
                }
            }
        }
        var children = tree.children();
        for (var j = 0; j < children.length; j++) {
            var horizontal = tree.split() == JOINT ? j & 1 : tree.split() == HORIZONTAL ? j : -1;
            var vertical = tree.split() == JOINT ? j >> 1 : tree.split() == VERTICAL ? j : -1;
            var cx0 = horizontal < 0 ? x0 : horizontal == 0 ? ceilDiv2(x0, 1) : Math.floorDiv(x0, 2);
            var cx1 = horizontal < 0 ? x1 : horizontal == 0 ? ceilDiv2(x1, 1) : Math.floorDiv(x1, 2);
            var cy0 = vertical < 0 ? y0 : vertical == 0 ? ceilDiv2(y0, 1) : Math.floorDiv(y0, 2);
            var cy1 = vertical < 0 ? y1 : vertical == 0 ? ceilDiv2(y1, 1) : Math.floorDiv(y1, 2);
            decompose(data, stride, left + (horizontal == 1 ? lowWidth : 0), top + (vertical == 1 ? lowHeight : 0),
                    cx0, cy0, cx1, cy1, children[j], kernel);
        }
    }

    /** Places the samples of even coordinates first, then the odd ones. */
    static long[] deinterleave(long[] signal, long i0) {
        var result = new long[signal.length];
        var position = 0;
        for (var parity = 0; parity < 2; parity++) {
            for (var p = 0; p < signal.length; p++) {
                if (Math.floorMod(i0 + p, 2) == parity) {
                    result[position++] = signal[p];
                }
            }
        }
        return result;
    }

    static double[] deinterleave(double[] signal, long i0) {
        var result = new double[signal.length];
        var position = 0;
        for (var parity = 0; parity < 2; parity++) {
            for (var p = 0; p < signal.length; p++) {
                if (Math.floorMod(i0 + p, 2) == parity) {
                    result[position++] = signal[p];
                }
            }
        }
        return result;
    }

    /**
     * Decomposes a region-of-interest mask like the samples (L.3.4): before each lifting step, in forward order, every
     * marked sample the step updates marks the samples its update reads (equation L-11).
     */
    static void decomposeMask(boolean[] mask, int stride, int left, int top, long x0, long y0, long x1, long y1,
                              Tree tree, Kernel kernel) {
        if (tree.split() == LEAF) {
            return;
        }
        var width = (int) (x1 - x0);
        var height = (int) (y1 - y0);
        var lowWidth = (int) (ceilDiv2(x1, 1) - ceilDiv2(x0, 1));
        var lowHeight = (int) (ceilDiv2(y1, 1) - ceilDiv2(y0, 1));
        if (width > 0 && height > 0) {
            if (tree.split() != HORIZONTAL) {
                for (var u = 0; u < width; u++) {
                    var column = new boolean[height];
                    for (var v = 0; v < height; v++) {
                        column[v] = mask[(top + v) * stride + left + u];
                    }
                    var split = spreadMask(column, y0, kernel);
                    for (var v = 0; v < height; v++) {
                        mask[(top + v) * stride + left + u] = split[v];
                    }
                }
            }
            if (tree.split() != VERTICAL) {
                for (var v = 0; v < height; v++) {
                    var row = new boolean[width];
                    System.arraycopy(mask, (top + v) * stride + left, row, 0, width);
                    System.arraycopy(spreadMask(row, x0, kernel), 0, mask, (top + v) * stride + left, width);
                }
            }
        }
        var children = tree.children();
        for (var j = 0; j < children.length; j++) {
            var horizontal = tree.split() == JOINT ? j & 1 : tree.split() == HORIZONTAL ? j : -1;
            var vertical = tree.split() == JOINT ? j >> 1 : tree.split() == VERTICAL ? j : -1;
            var cx0 = horizontal < 0 ? x0 : horizontal == 0 ? ceilDiv2(x0, 1) : Math.floorDiv(x0, 2);
            var cx1 = horizontal < 0 ? x1 : horizontal == 0 ? ceilDiv2(x1, 1) : Math.floorDiv(x1, 2);
            var cy0 = vertical < 0 ? y0 : vertical == 0 ? ceilDiv2(y0, 1) : Math.floorDiv(y0, 2);
            var cy1 = vertical < 0 ? y1 : vertical == 0 ? ceilDiv2(y1, 1) : Math.floorDiv(y1, 2);
            decomposeMask(mask, stride, left + (horizontal == 1 ? lowWidth : 0), top + (vertical == 1 ? lowHeight : 0),
                    cx0, cy0, cx1, cy1, children[j], kernel);
        }
    }

    private static boolean[] spreadMask(boolean[] line, long i0, Kernel kernel) {
        var n = line.length;
        var marked = line.clone();
        if (n > 1) {
            var i1 = i0 + n;
            for (var step : kernel.steps()) {
                var next = marked.clone();
                for (var p = 0; p < n; p++) {
                    if (Math.floorMod(i0 + p, 2) != step.m() || !marked[p]) {
                        continue;
                    }
                    for (var k = 0; k < step.alphas().length; k++) {
                        var index = i0 + p + 1 - 2L * step.m() + 2L * (k + step.offset());
                        next[extend(index, i0, i1, kernel.symmetricExtension())] = true;
                    }
                }
                marked = next;
            }
        }
        var result = new boolean[n];
        var position = 0;
        for (var parity = 0; parity < 2; parity++) {
            for (var p = 0; p < n; p++) {
                if (Math.floorMod(i0 + p, 2) == parity) {
                    result[position++] = marked[p];
                }
            }
        }
        return result;
    }
}
